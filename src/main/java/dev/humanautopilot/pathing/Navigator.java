package dev.humanautopilot.pathing;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.modules.Attention;
import dev.humanautopilot.pathing.PathFinder.Move;
import dev.humanautopilot.pathing.PathFinder.Node;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Noise;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows a path using only movement keys and the camera. Walking direction comes from where the
 * camera faces, so steering is literally turning the "mouse" and holding W.
 */
public final class Navigator {
	public enum Status { IDLE, SEARCHING, MOVING, DIGGING, ARRIVED, FAILED }

	private static final int MAX_NODES = 40_000;
	private static final int NODES_PER_TICK = 1_500;
	private static final int STRICT_MAX_NODES = 12_000;

	private Goal goal;
	private boolean dig;
	private PathFinder search;
	private List<Node> path;
	private boolean complete;
	private int idx;

	private int stuckTicks;
	private double bestDist;
	private int replans;
	private int emptySearches;
	private int jumpCooldown;
	private int sprintJumpCooldown;
	private final Digger digger = new Digger();
	private final Noise lateral = new Noise(0.04, 0.12);
	private final Noise pitchNoise = new Noise(0.05, 0.2);
	private String failReason = "";
	private boolean requireComplete;
	private int maxNodes = MAX_NODES;
	// This trip is being walked by Baritone (our own moves couldn't do it), and whether we've tried that yet.
	private boolean viaBaritone, triedBaritone;
	private long lastTick;

	/** Like go(), but give up at once if there's no full route (don't walk partway toward unreachable things). */
	public void goStrict(Goal g, boolean allowDig) {
		go(g, allowDig);
		requireComplete = true;
		maxNodes = STRICT_MAX_NODES;
	}

	public void go(Goal g, boolean allowDig) {
		if (viaBaritone) BaritoneLegs.stop();
		viaBaritone = false;
		triedBaritone = false;
		requireComplete = false;
		maxNodes = MAX_NODES;
		goal = g;
		dig = allowDig;
		path = null;
		search = null;
		replans = 0;
		emptySearches = 0;
		failReason = "";
	}

	/** Straight to Baritone if it's installed (the user's /goto: far away, over anything); else our own walker. */
	public void goFar(Goal g, boolean allowDig) {
		go(g, allowDig);
		if (HumanAutopilot.config().baritoneForGoto && BaritoneLegs.go(g, allowDig)) {
			viaBaritone = true;
			triedBaritone = true;
		}
	}

	/** Baritone is walking this trip. */
	public boolean viaBaritone() {
		return viaBaritone;
	}

	public void stop() {
		if (viaBaritone) BaritoneLegs.stop();
		viaBaritone = false;
		goal = null;
		path = null;
		search = null;
		digger.reset();
	}

	public Goal goal() {
		return goal;
	}

	public boolean active() {
		return goal != null;
	}

	public String failReason() {
		return failReason;
	}

	/** Remaining straight-line distance to the goal target, for the HUD. */
	public double distanceLeft(LocalPlayer p) {
		if (goal == null) return 0;
		BlockPos b = p.blockPosition();
		return goal.heuristic(b.getX(), b.getY(), b.getZ());
	}

	/** Forget the current path; the next tick plans a new one from wherever we are now. */
	public void replan() {
		path = null;
		search = null;
	}

	private BlockPos feetBlock(LocalPlayer p) {
		Vec3 f = p.position();
		return BlockPos.containing(f.x, f.y + 0.2, f.z);
	}

	public Status tick(LocalPlayer p, InputDriver in, AimController aim) {
		if (goal == null) return Status.IDLE;
		long now = HumanAutopilot.ticks();
		if (now - lastTick > 10 && path != null) replan(); // someone else moved us; old path is stale
		lastTick = now;
		Vec3 feet = p.position();
		if (goal.reachedBy(feet)) {
			stop();
			return Status.ARRIVED;
		}
		if (viaBaritone) {
			Status s = BaritoneLegs.tick(p, aim);
			if (s == Status.ARRIVED || s == Status.FAILED) {
				viaBaritone = false;
				if (s == Status.FAILED) failReason = "no way there, even for Baritone";
				goal = null;
				path = null;
				search = null;
			}
			return s;
		}

		if (path == null && search == null) {
			search = new PathFinder(feetBlock(p), goal, dig, HumanAutopilot.config().maxSafeDrop, maxNodes);
		}
		if (search != null) {
			PathFinder.Path result = search.step(NODES_PER_TICK);
			if (result == null) return Status.SEARCHING;
			search = null;
			if (result.nodes().size() <= 1) {
				if (++emptySearches >= 3) return fail("no route found");
				return Status.SEARCHING;
			}
			if (requireComplete && !result.complete()) return fail("unreachable");
			// Searched every spot we can walk to and none of them gets us there: a gap to bridge, a wall to pillar,
			// a jump to make. That's a job for Baritone, if it's installed.
			if (!result.complete() && result.exhausted() && !triedBaritone && BaritoneLegs.available()) {
				triedBaritone = true;
				if (BaritoneLegs.go(goal, dig)) {
					viaBaritone = true;
					search = null;
					HumanAutopilot.LOGGER.info("[nav] no way to {} on foot: handing the trip to Baritone", goal.describe());
					return Status.SEARCHING;
				}
			}
			emptySearches = 0;
			path = new ArrayList<>(result.nodes());
			complete = result.complete();
			idx = 1;
			resetProgress();
		}
		return follow(p, in, aim, feet);
	}

	private void resetProgress() {
		stuckTicks = 0;
		bestDist = Double.MAX_VALUE;
	}

	private Status follow(LocalPlayer p, InputDriver in, AimController aim, Vec3 feet) {
		var cfg = HumanAutopilot.config();
		var o = InputDriver.opts();
		if (jumpCooldown > 0) jumpCooldown--;
		if (sprintJumpCooldown > 0) sprintJumpCooldown--;

		// Skip ahead over nodes we've already reached (vanilla movement often overshoots a node).
		for (int look = Math.min(path.size() - 1, idx + 3); look >= idx; look--) {
			if (atNode(path.get(look), feet)) {
				idx = look + 1;
				resetProgress();
				break;
			}
		}
		if (idx >= path.size()) {
			// Finished this stretch. If it was partial (goal beyond loaded chunks) plan the next leg.
			replan();
			if (complete) replans++;
			if (replans > 8) return fail("can't reach the exact spot");
			return Status.MOVING;
		}

		Node node = path.get(idx);
		Node prev = path.get(idx - 1);
		double cx = node.x + 0.5, cz = node.z + 0.5;
		double hd = Math.hypot(cx - feet.x, cz - feet.z);
		double dy = feet.y - node.y;

		if (hd > 4.5 || dy > 4 || dy < -6) {
			replan();
			return Status.MOVING;
		}

		// Dig out anything in the way first.
		if (dig) {
			BlockPos block = blockingCell(prev, node);
			if (block != null) {
				Digger.Result r = digger.dig(block, p, in, aim);
				if (r == Digger.Result.NO_TOOL || r == Digger.Result.BLOCKED || r == Digger.Result.UNDERFOOT || r == Digger.Result.OUT_OF_REACH) {
					digger.reset();
					// Same limit as getting stuck: a block we can't dig, re-planned into again and again, is a dead end.
					if (++replans > 8) {
						HumanAutopilot.LOGGER.info("[nav] giving up: can't dig through ({} at {})", r.name().toLowerCase(), block.toShortString());
						return fail("can't dig through (" + r.name().toLowerCase() + " at " + block.toShortString() + ")");
					}
					replan();
				}
				resetProgress();
				return Status.DIGGING;
			}
		}

		// The ground we planned to step on is gone (we or someone dug it): plan again rather than fall.
		if ((node.move == Move.WALK || node.move == Move.DIAG || node.move == Move.UP)
				&& !Terrain.floor(node.x, node.y - 1, node.z) && !Terrain.water(node.x, node.y, node.z)
				&& !Terrain.climbable(node.x, node.y, node.z)) {
			replan();
			return Status.MOVING;
		}

		// Progress / stuck detection
		if (hd < bestDist - 0.04) {
			bestDist = hd;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		if (stuckTicks > 60) {
			replans++;
			if (replans > 8) return fail("stuck");
			replan();
			return Status.MOVING;
		}

		// ----- Steering -----
		Vec3 target = new Vec3(cx, node.y, cz);
		Node next = idx + 1 < path.size() ? path.get(idx + 1) : null;
		boolean flat = isFlat(node) && next != null && isFlat(next) && next.y == node.y;
		boolean edge = nearEdge(idx, cfg.maxSafeDrop);
		double t = HumanAutopilot.ticks() / 20.0;

		if (flat && !edge) {
			// Look a few steps ahead (where the path is clear) so the view stays steady, and drift a
			// little sideways: no laser lines, no snapping toward every block we pass.
			Node far = next;
			for (int k = idx + 2; k <= Math.min(idx + 3, path.size() - 1); k++) {
				Node c = path.get(k);
				if (!isFlat(c) || c.y != node.y || !clearLine(feet, c)) break;
				far = c;
			}
			target = clearLine(feet, far) ? new Vec3(far.x + 0.5, far.y, far.z + 0.5)
					: target.lerp(new Vec3(next.x + 0.5, next.y, next.z + 0.5), 0.3);
			double dirX = next.x - prev.x, dirZ = next.z - prev.z;
			double len = Math.hypot(dirX, dirZ);
			if (len > 0) {
				double off = lateral.at(t) * 0.1;
				target = target.add(-dirZ / len * off, 0, dirX / len * off);
			}
		}

		double yaw = Angles.yawTo(feet, target);
		double pitch = switch (node.move) {
			case DROP -> 38;
			case UP -> 4;
			case CLIMB_UP -> -40;
			case CLIMB_DOWN -> 55;
			case SWIM_UP, SWIM_DOWN -> 10;
			default -> gazePitch();
		} + pitchNoise.at(t) * 2;

		boolean vertical = node.move == Move.CLIMB_UP || node.move == Move.CLIMB_DOWN
				|| node.move == Move.SWIM_UP || node.move == Move.SWIM_DOWN;
		boolean inWater = p.isInWater();
		boolean onGround = p.onGround();
		int run = straightRun(idx);
		int open = openRun(idx);
		double yawErr = Math.abs(Angles.wrap(yaw - p.getYRot()));

		// About to drop down a few blocks: most players stop at the edge for a beat and look where they'll land.
		long tick = HumanAutopilot.ticks();
		long nodeKey = BlockPos.asLong(node.x, node.y, node.z);
		if (node.move == Move.DROP && prev.y - node.y >= 2 && nodeKey != peeked && hd < 1.3 && onGround && !inWater) {
			peeked = nodeKey;
			peekUntil = Human.chance(0.75) ? tick + Human.rangeInt(6, 16) : 0;
			if (peekUntil > 0) edgePeeks++;
		}
		if (nodeKey == peeked && tick < peekUntil) {
			Vec3 land = new Vec3(node.x + 0.5, node.y, node.z + 0.5);
			aim.lookAt(node, () -> land);
			resetProgress();
			return Status.MOVING;
		}

		// An easy stretch of open ground: the eyes may leave the way ahead for a moment (a sound, something on
		// the way), and the feet carry on along the path with whichever keys point that way.
		// (A step up further on cuts the glance short when we get there: eyes back on the ground.)
		boolean calm = (node.move == Move.WALK || node.move == Move.DIAG) && !edge && !inWater && onGround && open >= 3
				&& !p.onClimbable();
		Attention attention = Attention.get();
		Attention.Look look = attention.whileWalking(p, yaw, calm);
		if (look != null || (calm && attention.justLooked(HumanAutopilot.ticks()) && yawErr > 22)) {
			if (look != null) aim.lookAt(look.key(), look.where());
			else aim.lookDirection("nav", yaw, pitch);
			walkWhileLooking(p, in, yaw);
			if (p.horizontalCollision && onGround && stuckTicks > 8 && jumpCooldown == 0) {
				in.hold(o.keyJump);
				jumpCooldown = 10;
			}
			return Status.MOVING;
		}

		if (vertical && hd < 0.35) {
			// Straight up/down: no need to turn, just look up/down.
			aim.lookDirection("nav-vertical", p.getYRot(), pitch);
		} else {
			aim.lookDirection("nav", yaw, pitch);
		}

		boolean forward = !vertical || hd >= 0.35;
		// Like a person, turn first at sharp corners, then go.
		if (yawErr > 55) forward = false;
		if (forward) in.hold(o.keyUp);

		// Jumping
		if (node.move == Move.UP && onGround && hd < 1.5 && dy < -0.4 && jumpCooldown == 0) {
			in.hold(o.keyJump);
			jumpCooldown = 4;
		}
		if (node.move == Move.CLIMB_UP || node.move == Move.SWIM_UP) in.hold(o.keyJump);
		if (node.move == Move.SWIM_DOWN) in.hold(o.keyShift);
		if (inWater && node.move != Move.SWIM_DOWN && (p.isUnderWater() || node.y >= Math.floor(feet.y))) {
			in.hold(o.keyJump); // keep head above water
		}
		if (p.horizontalCollision && onGround && isFlat(node) && stuckTicks > 8 && jumpCooldown == 0) {
			in.hold(o.keyJump);
			jumpCooldown = 10;
		}

		// Sprinting, sprint-jumping, and creeping near edges
		boolean food = p.getFoodData().getFoodLevel() > 6;
		if (edge && node.move != Move.DROP && (next == null || next.move != Move.DROP) && !inWater) {
			in.hold(o.keyShift); // sneak so we can't slip off
		} else if (forward && cfg.sprint && sprintMood(p) && open >= 3 && food && !inWater && yawErr < 25) {
			in.hold(o.keySprint);
			if (cfg.sprintJump && hopping && run >= 7 && onGround && yawErr < 8 && sprintJumpCooldown == 0) {
				in.hold(o.keyJump);
				sprintJumpCooldown = Human.rangeInt(8, 14);
			}
		}
		return Status.MOVING;
	}

	/**
	 * Keep going along the path while looking elsewhere: whichever of W/A/S/D (or a pair of them) points that
	 * way from where the camera faces, the way a player strafes past while watching something.
	 */
	private static void walkWhileLooking(LocalPlayer p, InputDriver in, double pathYaw) {
		var o = InputDriver.opts();
		int oct = (int) Math.round(Angles.wrap(pathYaw - p.getYRot()) / 45.0); // + = the path is to our right
		if (Math.abs(oct) <= 1) in.hold(o.keyUp);
		if (Math.abs(oct) >= 3) in.hold(o.keyDown);
		if (oct >= 1 && oct <= 3) in.hold(o.keyRight);
		if (oct <= -1 && oct >= -3) in.hold(o.keyLeft);
	}

	private double gaze = 14;
	private long gazeUntil;
	private long peeked;
	/** Stops at an edge to look down before dropping (for the test log). */
	public static long edgePeeks;
	private long peekUntil;

	/**
	 * Where the eyes rest walking on level ground. The recorded player isn't fixed on one height: mostly
	 * the ground a few blocks ahead (median ~15 degrees down), now and then the horizon, now and then their feet.
	 */
	private double gazePitch() {
		long now = HumanAutopilot.ticks();
		if (now >= gazeUntil) {
			double r = Human.RNG.nextDouble();
			gaze = r < 0.12 ? Human.range(-6, 4) : r < 0.84 ? Human.gauss(5, 27) : Human.range(28, 44);
			gazeUntil = now + Human.rangeInt(30, 120);
		}
		return gaze;
	}

	/**
	 * Our own moves can't do it. With Baritone installed, hand it this trip once (it can bridge, pillar and jump);
	 * otherwise give up as before.
	 */
	private Status fail(String reason) {
		Goal g = goal;
		if (!triedBaritone && g != null) {
			triedBaritone = true;
			if (BaritoneLegs.go(g, dig)) {
				viaBaritone = true;
				path = null;
				search = null;
				HumanAutopilot.LOGGER.info("[nav] {} on my own: handing the trip to {} to Baritone", reason, g.describe());
				return Status.SEARCHING;
			}
		}
		failReason = reason;
		stop();
		return Status.FAILED;
	}

	private boolean sprinting, hopping;
	private long sprintRollAt;

	/**
	 * Every 3-10 s decide whether we're in a hurry (more often on a long trip), and if so whether to
	 * sprint-jump. The recorded player (measured from speed, since they double-tap W) runs about half the
	 * time they move forward, and sprint-jumps for about a third of that.
	 */
	private boolean sprintMood(LocalPlayer p) {
		long now = HumanAutopilot.ticks();
		if (now >= sprintRollAt) {
			double base = HumanAutopilot.config().sprintChance;
			double chance = distanceLeft(p) > 40 ? Math.min(0.9, base * 1.6) : base;
			sprinting = Human.chance(chance);
			hopping = sprinting && Human.chance(0.4);
			sprintRollAt = now + Human.rangeInt(60, 200);
		}
		return sprinting;
	}

	/** Body-height clear straight line from our feet to a node (so heading straight for it won't snag). */
	private static boolean clearLine(Vec3 feet, Node n) {
		double tx = n.x + 0.5, tz = n.z + 0.5;
		int y = n.y;
		for (int i = 1; i <= 6; i++) {
			double f = i / 6.0;
			int x = net.minecraft.util.Mth.floor(feet.x + (tx - feet.x) * f), z = net.minecraft.util.Mth.floor(feet.z + (tz - feet.z) * f);
			if (!Terrain.passable(x, y, z) || !Terrain.passable(x, y + 1, z) || !Terrain.floor(x, y - 1, z)) return false;
		}
		return true;
	}

	private static boolean isFlat(Node n) {
		return n.move == Move.WALK || n.move == Move.DIAG;
	}

	private static boolean atNode(Node n, Vec3 feet) {
		double hd = Math.hypot(n.x + 0.5 - feet.x, n.z + 0.5 - feet.z);
		double dy = feet.y - n.y;
		return switch (n.move) {
			case CLIMB_UP, SWIM_UP -> hd < 0.8 && dy > -0.15 && dy < 1.0;
			case CLIMB_DOWN, SWIM_DOWN -> hd < 0.8 && dy < 0.35 && dy > -1.0;
			case DROP -> hd < 0.6 && dy < 0.6 && dy > -0.6;
			default -> hd < 0.42 && Math.abs(dy) < 0.75;
		};
	}

	/** Number of upcoming nodes that continue in a straight, level line. */
	private int straightRun(int from) {
		if (from >= path.size()) return 0;
		Node a = path.get(from - 1), b = path.get(from);
		int dx = b.x - a.x, dz = b.z - a.z, y = b.y;
		int n = 0;
		for (int i = from; i < path.size() && n < 12; i++) {
			Node cur = path.get(i), before = path.get(i - 1);
			if (!isFlat(cur) || cur.y != y || cur.x - before.x != dx || cur.z - before.z != dz) break;
			n++;
		}
		return n;
	}

	/**
	 * How far the path keeps going roughly the same way over ordinary ground (single steps up or down are
	 * fine): enough room to break into a run, the way a player sprints over bumpy grass, not only dead-flat lines.
	 */
	private int openRun(int from) {
		if (from >= path.size()) return 0;
		Node start = path.get(from - 1), first = path.get(from);
		double hx = first.x - start.x, hz = first.z - start.z;
		double hl = Math.hypot(hx, hz);
		if (hl == 0) return 0;
		int n = 0;
		for (int i = from; i < path.size() && n < 12; i++) {
			Node cur = path.get(i), before = path.get(i - 1);
			if (cur.move != Move.WALK && cur.move != Move.DIAG && cur.move != Move.UP && cur.move != Move.DROP) break;
			if (Math.abs(cur.y - before.y) > 1) break;
			double dx = cur.x - start.x, dz = cur.z - start.z, dl = Math.hypot(dx, dz);
			if (dl > 0 && (dx * hx + dz * hz) / (dl * hl) < 0.87) break; // drifts more than ~30 degrees off
			n++;
		}
		return n;
	}

	private boolean nearEdge(int from, int safe) {
		for (int i = Math.max(0, from - 1); i < Math.min(path.size(), from + 2); i++) {
			Node n = path.get(i);
			if (Terrain.dangerousEdgeNear(n.x, n.y, n.z, safe)) return true;
		}
		return false;
	}

	/** The first solid cell standing between {@code from} and {@code to}, or null if the way is open. */
	private BlockPos blockingCell(Node from, Node to) {
		List<BlockPos> cells = new ArrayList<>(3);
		if (to.move == Move.UP) cells.add(new BlockPos(from.x, from.y + 2, from.z));
		if (to.move == Move.WALK || to.move == Move.UP) {
			cells.add(new BlockPos(to.x, to.y + 1, to.z));
			cells.add(new BlockPos(to.x, to.y, to.z));
		}
		for (BlockPos c : cells) {
			if (!Terrain.passable(c.getX(), c.getY(), c.getZ())) return c;
		}
		return null;
	}
}
