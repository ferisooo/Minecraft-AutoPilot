package dev.humanautopilot.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only questions about the blocks around the player, as a careful human would judge them. */
public final class Terrain {
	private static final BlockPos.MutableBlockPos M = new BlockPos.MutableBlockPos();

	private Terrain() {}

	public static ClientLevel level() {
		return Minecraft.getInstance().level;
	}

	public static boolean loaded(int x, int z) {
		ClientLevel l = level();
		if (l == null || !l.hasChunk(x >> 4, z >> 4)) return false;
		// (Not a stand-in: past the view distance the game said it had chunks it never got, and handed back empty ones -
		// every block "void air". Taken as loaded, the far side of the copper guard counted as all holes: the bar went
		// 77% / 68% / 77% with her about the same spot.)
		return !(l.getChunk(x >> 4, z >> 4) instanceof net.minecraft.world.level.chunk.EmptyLevelChunk);
	}

	public static BlockState state(int x, int y, int z) {
		return level().getBlockState(M.set(x, y, z));
	}

	public static BlockState state(BlockPos p) {
		return level().getBlockState(p);
	}

	/** Blocks that hurt or trap you when you walk into or onto them. */
	public static boolean hurts(BlockState s) {
		return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK)
				|| s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB)
				|| s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
				|| s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POINTED_DRIPSTONE)
				// (A portal: through it is another world, straight away in creative.)
				|| s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY)
				|| s.getFluidState().is(FluidTags.LAVA);
	}

	public static boolean climbable(int x, int y, int z) {
		return state(x, y, z).is(BlockTags.CLIMBABLE);
	}

	public static boolean water(int x, int y, int z) {
		return state(x, y, z).getFluidState().is(FluidTags.WATER);
	}

	public static boolean lava(int x, int y, int z) {
		return state(x, y, z).getFluidState().is(FluidTags.LAVA);
	}

	/** A body can occupy this cell. */
	public static boolean passable(int x, int y, int z) {
		if (!loaded(x, z)) return false;
		BlockState s = state(x, y, z);
		if (hurts(s)) return false;
		if (s.is(BlockTags.CLIMBABLE)) return true;
		return s.getCollisionShape(level(), M.set(x, y, z)).isEmpty();
	}

	/** Something solid and safe to stand on. */
	public static boolean floor(int x, int y, int z) {
		if (!loaded(x, z)) return false;
		BlockState s = state(x, y, z);
		if (hurts(s)) return false;
		var shape = s.getCollisionShape(level(), M.set(x, y, z));
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.4;
	}

	/** Feet in water with somewhere to breathe within reach: not a deep-water trap. */
	public static boolean swimmable(int x, int y, int z) {
		if (!water(x, y, z)) return false;
		if (passable(x, y + 1, z) && !water(x, y + 1, z)) return true;
		return water(x, y + 1, z) && passable(x, y + 2, z) && !water(x, y + 2, z);
	}

	public static boolean canStand(int x, int y, int z) {
		if (!passable(x, y, z) || !passable(x, y + 1, z)) return false;
		if (water(x, y, z)) return swimmable(x, y, z);
		return floor(x, y - 1, z) || climbable(x, y, z);
	}

	/** Lava right next to where you'd stand: one misstep away. */
	public static boolean nearLava(int x, int y, int z) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (lava(x + d.getStepX(), y, z + d.getStepZ()) || lava(x + d.getStepX(), y - 1, z + d.getStepZ())) return true;
		}
		return lava(x, y - 1, z);
	}

	/** How far you'd fall stepping into this column (0 if there's floor right there). Capped at 16. */
	public static int dropDepth(int x, int y, int z) {
		int d = 0;
		while (d < 16 && passable(x, y - 1 - d, z) && !water(x, y - 1 - d, z)) d++;
		return d;
	}

	/** True if there's a drop deeper than {@code safe} blocks right beside this spot. */
	public static boolean dangerousEdgeNear(int x, int y, int z, int safe) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			int nx = x + d.getStepX(), nz = z + d.getStepZ();
			if (passable(nx, y, nz) && passable(nx, y + 1, nz) && !floor(nx, y - 1, nz) && dropDepth(nx, y, nz) > safe) {
				return true;
			}
		}
		return false;
	}

	/** Safe to dig out: not a liquid, no liquid next to it, nothing will fall on us, not a container. */
	/**
	 * Ground level around a column: the median top-block height of the 5x5 area. A 1-wide shaft
	 * or a single tree trunk doesn't move the median, so neither fools it.
	 */
	private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap REGIONAL = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

	/**
	 * Ground level of the wider area (a 25x25 patch sampled every 4 blocks, 70th percentile), cached per 8x8 cell:
	 * a ravine or a big cave opening doesn't drag it down the way it does a single column.
	 */
	public static int regionalSurfaceY(int x, int z) {
		long key = (((long) (x >> 3)) << 32) ^ ((z >> 3) & 0xffffffffL);
		if (REGIONAL.size() > 4096) REGIONAL.clear();
		if (REGIONAL.containsKey(key)) return REGIONAL.get(key);
		int cx = (x >> 3) * 8 + 4, cz = (z >> 3) * 8 + 4;
		int[] h = new int[49];
		int n = 0;
		for (int dx = -12; dx <= 12; dx += 4) {
			for (int dz = -12; dz <= 12; dz += 4) {
				if (!loaded(cx + dx, cz + dz)) continue;
				h[n++] = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz);
			}
		}
		int v = n == 0 ? Integer.MIN_VALUE : sorted(h, n)[(int) (n * 0.7)];
		if (n > 0) REGIONAL.put(key, v);
		return v;
	}

	private static int[] sorted(int[] h, int n) {
		java.util.Arrays.sort(h, 0, n);
		return h;
	}

	public static int surfaceY(int x, int z) {
		int[] h = new int[25];
		int n = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (!loaded(x + dx, z + dz)) continue;
				h[n++] = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
			}
		}
		if (n == 0) return Integer.MIN_VALUE;
		java.util.Arrays.sort(h, 0, n);
		return h[n / 2];
	}

	public static boolean breakable(int x, int y, int z) {
		if (!loaded(x, z)) return false;
		BlockState s = state(x, y, z);
		if (s.isAir()) return true;
		// Berry bushes and cobwebs hurt or trap you if you walk into them, but breaking them is fine.
		boolean clearable = s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB);
		if (!s.getFluidState().isEmpty() || s.hasBlockEntity() || (hurts(s) && !clearable)) return false;
		float hardness = s.getDestroySpeed(level(), M.set(x, y, z));
		if (hardness < 0 || hardness > 20) return false;
		for (Direction d : Direction.values()) {
			if (!state(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()).getFluidState().isEmpty()) return false;
		}
		return !(state(x, y + 1, z).getBlock() instanceof FallingBlock);
	}

	/**
	 * Fine to break from where we stand (it's in the way of the crosshair, we won't step into it):
	 * only refuse next to lava, containers, and unbreakable blocks. Water seeping in or gravel
	 * dropping into the gap is no danger from here.
	 */
	public static boolean breakableFromHere(int x, int y, int z) {
		if (!loaded(x, z)) return false;
		BlockState s = state(x, y, z);
		if (s.isAir()) return true;
		if (s.hasBlockEntity() || s.getFluidState().is(FluidTags.LAVA)) return false;
		float hardness = s.getDestroySpeed(level(), M.set(x, y, z));
		if (hardness < 0 || hardness > 20) return false;
		for (Direction d : Direction.values()) {
			if (lava(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) return false;
		}
		return true;
	}

	/** Rough cost (in "blocks walked") of digging a cell, 0 if it's already open. */
	public static double digCost(int x, int y, int z) {
		if (passable(x, y, z)) return 0;
		if (!breakable(x, y, z)) return Double.POSITIVE_INFINITY;
		float hardness = state(x, y, z).getDestroySpeed(level(), M.set(x, y, z));
		return 3 + hardness * 2;
	}
}
