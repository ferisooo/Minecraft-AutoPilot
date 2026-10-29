package dev.humanautopilot.survival;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.pathing.Digger;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Digs the way a player strip-mines: a 1-wide staircase down to a target depth, then a straight
 * 2-high tunnel, turning now and then to start a new branch. Refuses steps that would open
 * into lava, water, a drop, or under gravel/sand.
 */
public final class Tunneler {
	public enum Result { WORKING, FAILED }

	private Direction dir;
	private BlockPos next;
	private int stepsThisBranch;
	private int totalSteps;
	private int stepsSinceScan;
	private int nextScanAfter = 10;
	private long scanStarted = -1;
	private int scanLeftFor, scanRightFor;
	private double scanPitch;
	private double scanLeftAngle, scanRightAngle;
	private double stepPitch;

	public int totalSteps() {
		return totalSteps;
	}

	/** Part of (or right beside) a tunnel we dug ourselves. */
	public boolean dugNear(BlockPos pos) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					if (visited.contains(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) return true;
		return false;
	}
	private int branchLength = 30;
	private int turnsWithoutProgress;
	private int stuckTicks;
	private final Digger digger = new Digger();
	/** Cells our tunnels already pass through (feet level), so new branches go into fresh rock. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet visited = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private String status = "";

	public String status() {
		return status;
	}

	public void reset() {
		dir = null;
		next = null;
		digger.reset();
		turnsWithoutProgress = 0;
	}

	public Result tick(LocalPlayer p, InputDriver in, AimController aim, int targetY) {
		BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		if (dir == null) {
			dir = Direction.fromYRot(p.getYRot());
			branchLength = Human.rangeInt(20, 45);
		}
		boolean down = feet.getY() > targetY;
		boolean up = feet.getY() < targetY - 1;

		// Every so often in a straight tunnel, stop and look along both walls for ore, like a player does.
		if (!down && !up && next == null && stepsSinceScan >= nextScanAfter) {
			long now = HumanAutopilot.ticks();
			if (scanStarted < 0) {
				scanStarted = now;
				scanLeftFor = Human.rangeInt(12, 20);
				scanRightFor = Human.rangeInt(12, 20);
				scanPitch = Human.range(-5, 20);
				scanLeftAngle = Human.range(60, 80);
				scanRightAngle = Human.range(60, 80);
			}
			long t = now - scanStarted;
			double base = dir.toYRot();
			if (t < scanLeftFor) {
				aim.lookDirection("scan-left", base - scanLeftAngle, scanPitch);
				status = "looking along the tunnel walls";
				return Result.WORKING;
			}
			if (t < scanLeftFor + scanRightFor) {
				aim.lookDirection("scan-right", base + scanRightAngle, scanPitch);
				status = "looking along the tunnel walls";
				return Result.WORKING;
			}
			scanStarted = -1;
			stepsSinceScan = 0;
			nextScanAfter = Human.rangeInt(8, 16);
		}

		if (next == null) {
			if (stepsThisBranch >= branchLength && !down && !up) {
				dir = Human.chance(0.5) ? dir.getClockWise() : dir.getCounterClockWise();
				stepsThisBranch = 0;
				branchLength = Human.rangeInt(20, 45);
			}
			BlockPos candidate = down ? feet.relative(dir).below() : up ? feet.relative(dir).above() : feet.relative(dir);
			if (!down && !up && visited.contains(candidate.asLong())) {
				// That's a tunnel we already dug: turn off into fresh rock rather than walk our old tunnels.
				Direction a = Human.chance(0.5) ? dir.getClockWise() : dir.getCounterClockWise();
				for (Direction d : new Direction[]{a, a.getOpposite()}) {
					BlockPos c = feet.relative(d);
					if (!visited.contains(c.asLong()) && safeStep(feet, c)) {
						dir = d;
						candidate = c;
						stepsThisBranch = 0;
						branchLength = Human.rangeInt(20, 45);
						break;
					}
				}
			}
			if (!safeStep(feet, candidate)) {
				if (++turnsWithoutProgress > 4) {
					status = "boxed in";
					HumanAutopilot.LOGGER.info("[tunnel] boxed in at {} ({}): {}", feet.toShortString(), down ? "going down" : up ? "going up" : "level",
							whyNot(feet, candidate));
					return Result.FAILED;
				}
				status = "looking for a way to dig";
				dir = Human.chance(0.5) ? dir.getClockWise() : dir.getCounterClockWise();
				return Result.WORKING;
			}
			next = candidate;
			stepPitch = Human.range(-4, 4); // chosen once per step, not every tick
			stuckTicks = 0;
		}

		// Clear the way, top block first, like you'd swing at it.
		for (BlockPos c : cellsFor(feet, next)) {
			if (!Terrain.passable(c.getX(), c.getY(), c.getZ())) {
				Digger.Result r = digger.dig(c, p, in, aim);
				if (r == Digger.Result.NO_TOOL || r == Digger.Result.BLOCKED || r == Digger.Result.UNDERFOOT || r == Digger.Result.OUT_OF_REACH) {
					next = null;
					dir = dir.getClockWise();
					digger.reset();
					turnsWithoutProgress++;
					if (turnsWithoutProgress > 4) {
						HumanAutopilot.LOGGER.info("[tunnel] gave up at {}: {} digging {}", feet.toShortString(),
								r.name().toLowerCase(), Terrain.state(c).getBlock().getName().getString());
						return Result.FAILED;
					}
					return Result.WORKING;
				}
				// Like the recorded player in a tunnel: W stays down while mining, so we walk on the moment the way opens
				// (about a third of their blocks were broken on the move).
				Vec3 to = new Vec3(next.getX() + 0.5, p.getY(), next.getZ() + 0.5);
				// Only in a level tunnel: going down, W would walk us onto the step we're about to dig out from under us.
				if (!up && !down && Math.abs(Angles.wrap(Angles.yawTo(p.position(), to) - p.getYRot())) < 35) in.hold(InputDriver.opts().keyUp);
				status = down ? "digging down (y=" + feet.getY() + " -> " + targetY + ")" : up ? "digging up (y=" + feet.getY() + " -> " + targetY + ")" : "branch mining";
				return Result.WORKING;
			}
		}

		// Step into it, eyes already on the next block to dig (a player keeps looking at the work, not at a fixed
		// "walking" angle in between blocks).
		Vec3 target = new Vec3(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
		double yaw = Angles.yawTo(p.position(), target);
		BlockPos ahead = down ? next.relative(dir).below().above(2) : next.relative(dir).above();
		if (!Terrain.passable(ahead.getX(), ahead.getY(), ahead.getZ())) {
			Vec3 face = Vec3.atCenterOf(ahead).add(-dir.getStepX() * 0.45, 0, -dir.getStepZ() * 0.45);
			aim.lookAt(ahead, () -> face);
		} else {
			aim.lookDirection("tunnel", yaw, Math.clamp(p.getXRot(), down ? 20 : 0, down ? 60 : 30));
		}
		if (Math.abs(Angles.wrap(yaw - p.getYRot())) < 40) in.hold(InputDriver.opts().keyUp);
		double hd = Math.hypot(target.x - p.getX(), target.z - p.getZ());
		if (next.getY() > feet.getY() && p.onGround() && hd < 1.3) in.hold(InputDriver.opts().keyJump);
		if (hd < 0.35 && Math.abs(p.getY() - next.getY()) < 0.6) {
			if (visited.size() > 20_000) visited.clear();
			visited.add(feet.asLong());
			visited.add(next.asLong());
			next = null;
			stepsThisBranch++;
			totalSteps++;
			stepsSinceScan++;
			turnsWithoutProgress = 0;
		} else if (++stuckTicks > 80) {
			if (p.onGround()) in.hold(InputDriver.opts().keyJump);
			if (stuckTicks > 140) {
				next = null;
				dir = dir.getClockWise();
			}
		}
		status = down ? "digging down (y=" + feet.getY() + " -> " + targetY + ")" : up ? "digging up (y=" + feet.getY() + " -> " + targetY + ")" : "branch mining";
		return Result.WORKING;
	}

	private static List<BlockPos> cellsFor(BlockPos feet, BlockPos next) {
		List<BlockPos> cells = new ArrayList<>(3);
		if (next.getY() < feet.getY()) cells.add(next.above(2)); // head room as we step down
		if (next.getY() > feet.getY()) cells.add(feet.above(2)); // room to jump up
		cells.add(next.above());
		cells.add(next);
		return cells;
	}

	/** Plain-words reason a step isn't safe (for the log). */
	private static String whyNot(BlockPos feet, BlockPos next) {
		if (!Terrain.loaded(next.getX(), next.getZ())) return "not loaded";
		if (next.getY() < Terrain.level().getMinY() + 6) return "bedrock layers";
		for (BlockPos c : cellsFor(feet, next)) {
			if (Terrain.lava(c.getX(), c.getY(), c.getZ())) return "lava at " + c.toShortString();
			if (Terrain.water(c.getX(), c.getY(), c.getZ())) return "water at " + c.toShortString();
			if (!Terrain.passable(c.getX(), c.getY(), c.getZ()) && !Terrain.breakable(c.getX(), c.getY(), c.getZ()))
				return "won't break " + Terrain.state(c).getBlock().getName().getString() + " at " + c.toShortString() + " (liquid/gravel next to it?)";
		}
		if (!Terrain.floor(next.getX(), next.getY() - 1, next.getZ())) return "no floor under " + next.toShortString() + " (a drop)";
		if (Terrain.lava(next.getX(), next.getY() - 2, next.getZ())) return "lava under the floor";
		return "?";
	}

	private static boolean safeStep(BlockPos feet, BlockPos next) {
		if (!Terrain.loaded(next.getX(), next.getZ())) return false;
		if (next.getY() < Terrain.level().getMinY() + 6) return false; // stay off the bedrock layers
		for (BlockPos c : cellsFor(feet, next)) {
			if (!Terrain.passable(c.getX(), c.getY(), c.getZ()) && !Terrain.breakable(c.getX(), c.getY(), c.getZ())) return false;
			if (Terrain.lava(c.getX(), c.getY(), c.getZ()) || Terrain.water(c.getX(), c.getY(), c.getZ())) return false;
		}
		// Must have solid, safe floor under the new spot, with no lava just below it.
		if (!Terrain.floor(next.getX(), next.getY() - 1, next.getZ())) return false;
		if (Terrain.lava(next.getX(), next.getY() - 2, next.getZ())) return false;
		return true;
	}
}
