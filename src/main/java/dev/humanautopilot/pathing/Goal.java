package dev.humanautopilot.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Where the pathfinder is trying to get to. */
public interface Goal {
	boolean reached(int x, int y, int z);

	double heuristic(int x, int y, int z);

	/** Whether the player, standing where they are now, counts as arrived. */
	default boolean reachedBy(Vec3 feet) {
		BlockPos b = BlockPos.containing(feet.x, feet.y + 0.2, feet.z);
		return reached(b.getX(), b.getY(), b.getZ());
	}

	String describe();

	/** An exact block. */
	record Block(int gx, int gy, int gz) implements Goal {
		public boolean reached(int x, int y, int z) {
			return x == gx && z == gz && Math.abs(y - gy) <= 1;
		}

		public double heuristic(int x, int y, int z) {
			double dx = x - gx, dy = y - gy, dz = z - gz;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}

		public String describe() {
			return gx + " " + gy + " " + gz;
		}
	}

	/** A column: any height is fine. */
	record XZ(int gx, int gz) implements Goal {
		public boolean reached(int x, int y, int z) {
			return x == gx && z == gz;
		}

		public double heuristic(int x, int y, int z) {
			double dx = x - gx, dz = z - gz;
			return Math.sqrt(dx * dx + dz * dz);
		}

		public String describe() {
			return gx + " ~ " + gz;
		}
	}

	/** Within {@code radius} blocks of a column, any height. Used for wandering, where the ground height is unknown. */
	record NearXZ(int gx, int gz, double radius) implements Goal {
		public boolean reached(int x, int y, int z) {
			double dx = x - gx, dz = z - gz;
			return dx * dx + dz * dz <= radius * radius;
		}

		public double heuristic(int x, int y, int z) {
			double dx = x - gx, dz = z - gz;
			return Math.max(0, Math.sqrt(dx * dx + dz * dz) - radius);
		}

		public String describe() {
			return gx + " ~ " + gz;
		}
	}

	/** Anywhere under open sky (out of a tunnel or cave). */
	record Surface() implements Goal {
		// Open sky over a ravine floor isn't "up top": compare with the ground level of the wider area too.
		public boolean reached(int x, int y, int z) {
			return y >= Terrain.surfaceY(x, z) - 1 && y >= Terrain.regionalSurfaceY(x, z) - 4;
		}

		public double heuristic(int x, int y, int z) {
			int top = Math.max(Terrain.surfaceY(x, z), Terrain.regionalSurfaceY(x, z) - 3);
			return Math.max(0, top - 1 - y);
		}

		public String describe() {
			return "the surface";
		}
	}

	/** In reach of a block but not standing on top of it (never mine the block you stand on). */
	record Beside(BlockPos block, double radius) implements Goal {
		public boolean reached(int x, int y, int z) {
			if (x == block.getX() && z == block.getZ() && y > block.getY()) return false;
			return Vec3.atCenterOf(block).distanceToSqr(x + 0.5, y + 1.62, z + 0.5) <= radius * radius;
		}

		public double heuristic(int x, int y, int z) {
			return Math.max(0, Math.sqrt(Vec3.atCenterOf(block).distanceToSqr(x + 0.5, y + 1.62, z + 0.5)) - radius);
		}

		@Override
		public boolean reachedBy(Vec3 feet) {
			// Our body must be fully off the target's column, not just our centre.
			double dx = Math.max(Math.max(block.getX() - feet.x, 0), feet.x - (block.getX() + 1));
			double dz = Math.max(Math.max(block.getZ() - feet.z, 0), feet.z - (block.getZ() + 1));
			boolean clear = feet.y < block.getY() + 0.5 || Math.max(dx, dz) > 0.32;
			return clear && Vec3.atCenterOf(block).distanceToSqr(feet.x, feet.y + 1.62, feet.z) <= radius * radius;
		}

		public String describe() {
			return block.toShortString();
		}
	}

	/** Within {@code radius} of a point, measured from the eyes (for mining) or feet. */
	record Near(Vec3 target, double radius, boolean fromEyes) implements Goal {
		public boolean reached(int x, int y, int z) {
			double ey = fromEyes ? y + 1.62 : y + 0.5;
			return target.distanceToSqr(x + 0.5, ey, z + 0.5) <= radius * radius;
		}

		public double heuristic(int x, int y, int z) {
			double ey = fromEyes ? y + 1.62 : y + 0.5;
			return Math.max(0, Math.sqrt(target.distanceToSqr(x + 0.5, ey, z + 0.5)) - radius);
		}

		@Override
		public boolean reachedBy(Vec3 feet) {
			double ey = fromEyes ? feet.y + 1.62 : feet.y + 0.5;
			return target.distanceToSqr(feet.x, ey, feet.z) <= radius * radius;
		}

		public String describe() {
			return String.format("%.0f %.0f %.0f", target.x, target.y, target.z);
		}
	}
}
