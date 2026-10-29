package dev.humanautopilot.pathing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import static dev.humanautopilot.pathing.Terrain.*;

/**
 * Incremental A* over standing positions. {@link #step} expands a bounded number of nodes so a
 * long search is spread across ticks instead of freezing the game. If the goal is outside loaded
 * chunks (or too far), it returns a partial path to the closest point it found.
 */
public final class PathFinder {
	public enum Move { START, WALK, DIAG, UP, DROP, CLIMB_UP, CLIMB_DOWN, SWIM_UP, SWIM_DOWN }

	public static final class Node {
		public final int x, y, z;
		public final Move move;
		double g, f, h;
		Node parent;
		boolean closed;

		Node(int x, int y, int z, Move move) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.move = move;
		}

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	/** {@code exhausted}: every spot we could walk to was searched (the goal can't be reached with these moves). */
	public record Path(List<Node> nodes, boolean complete, boolean exhausted) {}

	private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private final Goal goal;
	private final boolean allowDig;
	private final int maxDrop;
	private final int maxNodes;
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	// Memoised terrain checks: the same cells get asked about many times during one search.
	private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap standCache = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
	private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap passCache = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
	private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap penaltyCache = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
	private final Node start;
	private Node best;
	private int expanded;

	public PathFinder(BlockPos from, Goal goal, boolean allowDig, int maxDrop, int maxNodes) {
		this.goal = goal;
		this.allowDig = allowDig;
		this.maxDrop = maxDrop;
		this.maxNodes = maxNodes;
		start = new Node(from.getX(), from.getY(), from.getZ(), Move.START);
		start.h = goal.heuristic(start.x, start.y, start.z);
		start.f = start.h;
		nodes.put(BlockPos.asLong(start.x, start.y, start.z), start);
		open.add(start);
		best = start;
	}

	/** Expand up to {@code budget} nodes. Returns null while still searching. */
	public Path step(int budget) {
		for (int i = 0; i < budget; i++) {
			Node n = open.poll();
			if (n == null || expanded >= maxNodes) return finish(best, false, n == null);
			if (n.closed) continue;
			n.closed = true;
			expanded++;
			if (goal.reached(n.x, n.y, n.z)) return finish(n, true, false);
			if (n.h < best.h - 0.01 || (Math.abs(n.h - best.h) < 0.01 && n.g < best.g)) best = n;
			expand(n);
		}
		return null;
	}

	private Path finish(Node end, boolean complete, boolean exhausted) {
		List<Node> list = new ArrayList<>();
		for (Node n = end; n != null; n = n.parent) list.add(n);
		Collections.reverse(list);
		return new Path(list, complete, exhausted);
	}

	private boolean stand(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		byte b = standCache.get(k);
		if (b == 0) {
			b = (byte) (canStand(x, y, z) ? 2 : 1);
			standCache.put(k, b);
		}
		return b == 2;
	}

	private boolean pass(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		byte b = passCache.get(k);
		if (b == 0) {
			b = (byte) (passable(x, y, z) ? 2 : 1);
			passCache.put(k, b);
		}
		return b == 2;
	}

	/** Extra cost for being in water, next to lava, or beside a cliff. */
	private double penalty(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		if (penaltyCache.containsKey(k)) return penaltyCache.get(k);
		double p = 0;
		if (nearLava(x, y, z)) p += 6;
		if (dangerousEdgeNear(x, y, z, maxDrop)) p += 1.5;
		penaltyCache.put(k, p);
		return p;
	}

	private void expand(Node n) {
		int x = n.x, y = n.y, z = n.z;
		boolean inWater = water(x, y, z);
		boolean onLadder = climbable(x, y, z);
		boolean canJump = floor(x, y - 1, z) || inWater || onLadder;

		for (int[] d : CARDINAL) {
			int nx = x + d[0], nz = z + d[1];
			if (!loaded(nx, nz)) continue;

			// Flat step (or dig a 1x2 tunnel when mining)
			if (stand(nx, y, nz)) {
				add(n, nx, y, nz, Move.WALK, 1.0);
			} else if (allowDig && floor(nx, y - 1, nz)) {
				double c = digCost(nx, y, nz) + digCost(nx, y + 1, nz);
				if (c < Double.POSITIVE_INFINITY) add(n, nx, y, nz, Move.WALK, 1.0 + c);
			}

			// Jump up one block
			if (canJump) {
				if (stand(nx, y + 1, nz) && pass(x, y + 2, z)) {
					add(n, nx, y + 1, nz, Move.UP, 2.0);
				} else if (allowDig && !pass(nx, y, nz) && floor(nx, y, nz)) {
					// Block in front is the step itself: clear above it and above us, then hop up.
					double c = digCost(x, y + 2, z) + digCost(nx, y + 1, nz) + digCost(nx, y + 2, nz);
					if (c < Double.POSITIVE_INFINITY) add(n, nx, y + 1, nz, Move.UP, 2.0 + c);
				}
			}

			// Step off and drop down
			if (pass(nx, y, nz) && pass(nx, y + 1, nz) && !floor(nx, y - 1, nz) && !climbable(nx, y, nz)) {
				for (int k = 1; k <= 24; k++) {
					int ny = y - k;
					if (water(nx, ny, nz)) {
						if (swimmable(nx, ny, nz)) add(n, nx, ny, nz, Move.DROP, 1.0 + k * 0.3);
						break;
					}
					if (stand(nx, ny, nz)) {
						if (k <= maxDrop) add(n, nx, ny, nz, Move.DROP, 1.0 + k * 0.5);
						break;
					}
					if (!pass(nx, ny, nz) || k > maxDrop) break;
				}
			}
		}

		// Diagonals: only when both side cells are open, so we never clip a corner.
		for (int[] d : DIAGONAL) {
			int nx = x + d[0], nz = z + d[1];
			if (!loaded(nx, nz)) continue;
			if (pass(nx, y, z) && pass(nx, y + 1, z) && pass(x, y, nz) && pass(x, y + 1, nz)
					&& stand(nx, y, nz)) {
				add(n, nx, y, nz, Move.DIAG, 1.414);
			}
		}

		// Ladders and vines
		if (onLadder && (climbable(x, y + 1, z) || stand(x, y + 1, z)) && pass(x, y + 2, z)) {
			add(n, x, y + 1, z, Move.CLIMB_UP, 1.5);
		}
		if (climbable(x, y - 1, z)) add(n, x, y - 1, z, Move.CLIMB_DOWN, 1.3);

		// Swimming vertically
		if (inWater) {
			if (stand(x, y + 1, z)) add(n, x, y + 1, z, Move.SWIM_UP, 1.5);
			if (swimmable(x, y - 1, z)) add(n, x, y - 1, z, Move.SWIM_DOWN, 2.0);
		}
	}

	private void add(Node parent, int x, int y, int z, Move move, double cost) {
		if (water(x, y, z)) cost *= 2.5;          // swimming is slow; prefer land
		cost += penalty(x, y, z); // lava nearby, cliff edges
		double g = parent.g + cost;
		long key = BlockPos.asLong(x, y, z);
		Node n = nodes.get(key);
		if (n != null && (n.closed || g >= n.g)) return;
		double h = n != null ? n.h : goal.heuristic(x, y, z);
		// Never mutate a node already in the heap: retire it and queue a fresh one.
		if (n != null) n.closed = true;
		n = new Node(x, y, z, move);
		n.h = h;
		nodes.put(key, n);
		n.g = g;
		n.f = g + n.h * 1.15;
		n.parent = parent;
		open.add(n);
	}

	public boolean allowsDigging() {
		return allowDig;
	}
}
