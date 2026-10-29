package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parkour mini-games (StreamToEarn's "S2E Parkour Plugin": climb a tower of blocks to the top and hold out for the win
 * timer while viewers' gifts knock you off): the user, "auto pilot has to work for it too".
 *
 * Its own jumper, like a player's: the course round the start is mapped (every block with room to stand on it), a
 * route of jumps worked out from the block it's on to the top (a jump: up to 4 across - a 3-block gap - on the level,
 * 3 across one up, further going down; any direction, diagonals and corners too - Baritone's parkour only jumps
 * straight north/south/east/west, and a spiral course turns corners: it found "no way there" on the practice tower),
 * and then each jump made by hand: turn to the next block, back up a little for a long one, run (sprinting for a long
 * one), jump off the edge, ease off over the block so as not to overshoot a 1x1, land. Nothing placed, nothing
 * broken. Knocked off (TNT, arrows, a ride down) or a jump missed: a new route from wherever it lands. At the top:
 * stays put till the game's over.
 */
public final class ParkourModule {
	private static final ParkourModule INSTANCE = new ParkourModule();

	public static ParkourModule get() {
		return INSTANCE;
	}

	/** How far round the start the course is mapped, and how far up. */
	// (The real course is a 19x17 spiral inside a barrier cage, 100 blocks up: with 14 round where it started, the block
	// two on from x 24 was outside the map and "no jump I can make" for minutes. Mapped round where it is now.)
	private static final int RADIUS = 24, HEIGHT = 160;

	private boolean on;
	private BlockPos centre;   // where the climb started (the course is round it)
	private BlockPos top;      // the block to stand on at the end
	private Set<BlockPos> stands = new HashSet<>();
	private List<BlockPos> route = new ArrayList<>();
	private long mappedAt = -10000, startedAt, topSince = -1, stuckLogAt = -1000;
	/**
	 * The course's height (goal) when she last won, and a win to put it back after: the plugin resets its goal to 100 after
	 * every win - the user's viewers had it at 359 - so she types /parkour goal add for the difference (the user asked).
	 */
	private int goalHeight;
	private boolean goalPending;
	private long lastTopAt = -10000, goalCmdAt = -10000;
	/** The lowest it's stood (re-mapped) since parkour went on: the map always reaches down to there. */
	private int lowY = Integer.MAX_VALUE;
	private int falls, jumps, misses;
	private double lastGroundY = Double.NaN;
	/** Jumps that went wrong twice: left out of routes (from-to). */
	private final Map<Long, Integer> bad = new HashMap<>();

	// the jump under way
	private BlockPos from, to;
	/** This jump was chained straight on from the landing of the last (keys never let go): no stopping to turn. */
	private boolean carried;
	private enum Phase { SETTLE, RUN, AIR }
	private Phase phase;
	private long phaseAt;
	/** Where it was when it last moved in the air, and when: caught inside a block if it stays there. */
	private Vec3 stillPos;
	private long stillAt;
	/** This jump, a tick at a time (phase, where, speed, keys): written out when it goes wrong. */
	private final StringBuilder trace = new StringBuilder();

	// the camera on the way up
	/**
	 * The pitch the eyes keep running up the steps, and the one it drifts round (the user's stays at ~16 degrees down,
	 * moving under a degree a jump: "her camera keeps bobbing up and down" when it looked at each block it landed on).
	 */
	private double runPitch = 16, runPitchBase = 16;
	/** The heading last looked along (kept when right over the point looked at: the heading to it spins there). */
	private double runYaw = Double.NaN;
	/** A longer jump's pitch, taken once when its run starts (for that jump). */
	private BlockPos pitchFor;
	private double jumpPitch;

	// thrown (TNT, a launch), carried by a gift's ride, or fallen off: in the air with no jump of ours under way
	private long airborneSince = -1, landedAt = -100, landPickedAt;
	/** The block it's steering to come down on, or null. */
	private BlockPos landOn;
	/** Blocks looked at this time in the air: is there a way on up to the top from there (cage posts, outside: no). */
	private final Map<BlockPos, Boolean> landWayUp = new HashMap<>();
	// stuck with no way to the top (outside the cage, on a corner post): back with the plugin's /parkour tp, then a /tp
	private long noWaySince = -1, rescueAt = -10000;
	private int rescues;
	/** A floor spot a climb to the top started from (where the /tp rescue puts it). */
	private BlockPos startSpot;
	private long glanceUntil;
	private Vec3 glanceAt;
	private int glances;

	// slips (Config.parkourSlipChance): the route jump that goes wrong this climb (-1: none), and how
	private int slipAt = -1;
	private boolean slipping;
	/** A slip came already on this climb (to the top): the routes after it don't roll again (two in one climb was too many). */
	private boolean slippedThisClimb;
	/**
	 * 0: the jump key missed, a walk off the edge; 1: the angle misjudged, turned ~30 degrees too far and jumped that way,
	 * past the block's side. (Letting go half way or a sprint jump over it didn't fail on a 1-block gap one up: it came
	 * down on the block, bumped its side and slid onto it, or landed back.)
	 */
	private int slipKind;
	/** The misjudged angle (degrees), and whether it has turned that far (then it goes, no waiting again). */
	private double slipYawOff;
	private boolean slipTurned;
	/** Tests: the kind every slip is (-1: either, at random). */
	public static int forceSlipKind = -1;
	/** Slips that really put it off the block (fell, or came down somewhere else). */
	private int slipsOff;

	public int slipsOff() {
		return slipsOff;
	}

	private ParkourModule() {}

	public boolean on() {
		return on;
	}

	public void start(LocalPlayer p) {
		on = true;
		save(true);
		centre = p.blockPosition();
		top = null;
		route = new ArrayList<>();
		falls = jumps = misses = 0;
		bad.clear();
		lastGroundY = Double.NaN;
		topSince = -1;
		mappedAt = -10000;
		startedAt = HumanAutopilot.ticks();
		to = null;
		runPitchBase = runPitch = Human.gauss(12, 20);
		lowY = Integer.MAX_VALUE;
		noWaySince = -1;
		startSpot = null;
		slipAt = -1;
		slipping = false;
		airborneSince = -1;
		landOn = null;
		HumanAutopilot.LOGGER.info("[parkour] on at {}", centre.toShortString());
	}

	public void stop() {
		if (!on) return;
		on = false;
		save(false);
		to = null;
		HumanAutopilot.LOGGER.info("[parkour] off ({} jumps, {} missed, {} falls)", jumps, misses, falls);
	}

	// ---- carried on after the game restarts (LiveKeeper reopens it): like a build, "it should start automatically"

	private static boolean startChecked;
	private static long inWorldAt = -1;

	private static java.nio.file.Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("parkour.properties");
	}

	private static void save(boolean on) {
		try {
			java.nio.file.Files.createDirectories(file().getParent());
			java.nio.file.Files.writeString(file(), "on=" + on + System.lineSeparator());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[parkour] couldn't save: {}", e.toString());
		}
	}

	/** Every tick in a world: five seconds into the first one of this game, back on if it was on when the game closed. */
	public void worldTick(LocalPlayer p) {
		if (startChecked) return;
		if (inWorldAt < 0) inWorldAt = HumanAutopilot.ticks();
		if (HumanAutopilot.ticks() - inWorldAt < 100) return;
		startChecked = true;
		boolean was = false;
		try { was = java.nio.file.Files.readString(file()).contains("on=true"); } catch (Exception ignored) { }
		if (was && !on && !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			HumanAutopilot.LOGGER.info("[parkour] the game closed in the middle of the parkour: carrying on");
			HumanAutopilot.setParkour(true);
		}
	}

	public BlockPos top() {
		return top;
	}

	public int falls() {
		return falls;
	}

	public int misses() {
		return misses;
	}

	// ---- the course

	static boolean standable(Level level, BlockPos b) {
		BlockState s = level.getBlockState(b);
		if (s.isAir() || !s.isFaceSturdy(level, b, Direction.UP)) return false;
		// (Barriers: the course's invisible cage, wall to y 127 - its top was "the top" of the course. No one sees them.)
		if (s.is(net.minecraft.world.level.block.Blocks.BARRIER)) return false;
		return clear(level, b.above()) && clear(level, b.above(2));
	}

	private static boolean clear(Level level, BlockPos b) {
		return level.getBlockState(b).getCollisionShape(level, b).isEmpty();
	}

	/** Every block to stand on round the start, and the highest of them (the nearest the middle of those). */
	private void map(Level level) {
		Set<BlockPos> s = new HashSet<>();
		// (From the lowest it's stood since parkour went on: re-centred high up, the map from there up left out the laps
		// under it, and a slip at y 38 found nothing to come down on - 97 blocks to the floor.)
		lowY = Math.min(lowY, centre.getY());
		int yLo = Math.max(level.getMinY(), lowY - 6), yHi = Math.min(level.getMaxY() - 3, Math.max(lowY + HEIGHT, centre.getY() + 40));
		BlockPos best = null;
		double bestD = 0;
		for (int y = yLo; y <= yHi; y++) {
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					BlockPos b = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
					if (level.getBlockState(b).isAir() || !standable(level, b)) continue;
					s.add(b);
					// (The top is a course block - one standing free, nothing under it - not the top of a pillar: the cage's
					// glass corner posts go a block higher than the course's last.)
					if (!level.getBlockState(b.below()).isAir()) continue;
					double d = dx * dx + dz * dz;
					if (best == null || y > best.getY() || y == best.getY() && d < bestD) { best = b; bestD = d; }
				}
			}
		}
		stands = s;
		if (best != null && !best.equals(top)) {
			HumanAutopilot.LOGGER.info("[parkour] mapped {} blocks to stand on; the top: {} ({} up from the start)", s.size(),
					best.toShortString(), best.getY() - centre.getY() + 1);
			top = best;
			route = new ArrayList<>();
			int h = best.getY() - lowY;
			long t = HumanAutopilot.ticks();
			if (goalPending && h < goalHeight && t - goalCmdAt > 100) {
				var pl = net.minecraft.client.Minecraft.getInstance().player;
				if (pl != null) {
					HumanAutopilot.LOGGER.info("[parkour] the goal went back to {} after the win: /parkour goal add {} (back to {})", h, goalHeight - h, goalHeight);
					pl.connection.sendCommand("parkour goal add " + (goalHeight - h));
					goalCmdAt = t;
					mappedAt = t - 140; // (mapped again in 3 s: the course grows back up)
					goalPending = false;
				}
			}
		}
	}

	/** Can a player jump from standing on a to standing on b (or walk/step it)? */
	private boolean canJump(Level level, BlockPos a, BlockPos b) {
		int dy = b.getY() - a.getY();
		double d = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
		if (dy > 1 || dy < -4 || d < 0.5) return false;
		double max = dy == 1 ? 3.2 : dy == 0 ? 4.1 : 4.4;
		if (d > max) return false;
		Integer n = bad.get(key(a, b));
		if (n != null && n >= 4) return false;
		// Head room above the take-off, and the way across clear (feet, head, and the top of the arc mid-way).
		if (d > 1.5 && !clear(level, a.above(3))) return false;
		// (The feet's height across: the take-off's for the first half; going up, the landing's for the second - it's up
		// over the block by then; going down, still the take-off's till late in the jump. A check at the take-off's
		// height all the way ran into the block it was jumping up onto: "no jump I can make" from the floor.)
		int steps = (int) Math.ceil(d * 4);
		for (int i = 1; i < steps; i++) {
			double t = i / (double) steps;
			double x = a.getX() + 0.5 + (b.getX() - a.getX()) * t, z = a.getZ() + 0.5 + (b.getZ() - a.getZ()) * t;
			int feet = t < 0.5 ? a.getY() + 1 : dy > 0 ? b.getY() + 1 : a.getY() + 1;
			BlockPos c = BlockPos.containing(x, feet + 0.1, z);
			// (Over the landing block's own column the last bit is the landing itself.)
			boolean overLanding = c.getX() == b.getX() && c.getZ() == b.getZ();
			if (!overLanding && (!clear(level, c) || !clear(level, c.above()))) return false;
			if (dy < 0 && t >= 0.5 && !overLanding) {
				BlockPos low = BlockPos.containing(x, b.getY() + 1.1, z);
				if (t > 0.8 && (!clear(level, low) || !clear(level, low.above()))) return false;
			}
			if (d > 1.5 && t > 0.15 && t < 0.85 && !clear(level, c.above(2))) return false;
		}
		return true;
	}

	private static long key(BlockPos a, BlockPos b) {
		return a.asLong() * 31 + b.asLong();
	}

	/**
	 * The safest way from start to the top (walking a block costs least, then a hop, a sprint jump, a long leap the
	 * most: a route of fewest jumps took 4-block leaps across the floor round the course and missed them); or, if the
	 * top can't be reached, to the highest block it can reach.
	 */
	private List<BlockPos> plan(Level level, BlockPos start) {
		Map<BlockPos, BlockPos> prev = new HashMap<>();
		Map<BlockPos, Double> cost = new HashMap<>();
		java.util.PriorityQueue<Object[]> q = new java.util.PriorityQueue<>((x, y) -> Double.compare((double) x[1], (double) y[1]));
		q.add(new Object[]{start, 0.0});
		cost.put(start, 0.0);
		prev.put(start, start);
		Set<BlockPos> done = new HashSet<>();
		BlockPos bestReach = start;
		while (!q.isEmpty()) {
			Object[] e = q.poll();
			BlockPos a = (BlockPos) e[0];
			if (!done.add(a)) continue;
			if (a.equals(top)) { bestReach = a; break; }
			if (a.getY() > bestReach.getY()) bestReach = a;
			double ca = cost.get(a);
			for (int dy = 1; dy >= -4; dy--) {
				for (int dx = -4; dx <= 4; dx++) {
					for (int dz = -4; dz <= 4; dz++) {
						BlockPos b = a.offset(dx, dy, dz);
						if (done.contains(b) || !stands.contains(b)) continue;
						double hd = Math.hypot(dx, dz);
						double c = ca + (hd <= 1.5 ? 1 + (dy > 0 ? 0.5 : 0) : hd <= 2.6 ? 3 : hd <= 3.3 ? 5 : 9) + (dy < -1 ? 1 : 0);
						Double old = cost.get(b);
						if (old != null && old <= c) continue;
						if (!canJump(level, a, b)) continue;
						cost.put(b, c);
						prev.put(b, a);
						q.add(new Object[]{b, c});
					}
				}
			}
		}
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos c = bestReach; ; c = prev.get(c)) {
			path.add(0, c);
			if (c.equals(start)) break;
		}
		return path;
	}

	/** The block it's standing on (the one under its middle, or any it overlaps). */
	private BlockPos support(LocalPlayer p) {
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		if (stands.contains(under)) return under;
		for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
			BlockPos b = BlockPos.containing(p.getX() + ox, p.getY() - 0.2, p.getZ() + oz);
			if (stands.contains(b)) return b;
		}
		return standable(p.level(), under) ? under : null;
	}

	private boolean atTop(LocalPlayer p) {
		BlockPos s = p.onGround() ? support(p) : null;
		return s != null && s.equals(top);
	}

	// ---- each tick

	public String tick(LocalPlayer p, InputDriver input, AimController aim) {
		long now = HumanAutopilot.ticks();
		Level level = p.level();
		// The course, mapped again every 10 s (made taller, or not all loaded the first time).
		if (now - mappedAt > 200) {
			mappedAt = now;
			// (Round where it is now, standing: the course is wider than the area round where the climb began.)
			if (p.onGround()) centre = p.blockPosition();
			map(level);
		}
		if (top == null) return "parkour: no course round here";
		// (The pitch kept while running drifts a little, as a hand on a mouse does.)
		runPitch = Math.clamp(runPitch + Human.range(-0.04, 0.04), runPitchBase - 2.5, runPitchBase + 2.5);
		if (p.onGround() && airborneSince >= 0) {
			airborneSince = -1;
			landOn = null;
			landedAt = now;
		}
		if (p.onGround()) {
			if (!Double.isNaN(lastGroundY) && lastGroundY - p.getY() > 3.5) {
				falls++;
				if (now - lastTopAt < 200 && lastGroundY - p.getY() > 30 && goalHeight > 0) goalPending = true;
				HumanAutopilot.LOGGER.info("[parkour] down {} blocks to y {} (fall {}{}): a new route from here",
						String.format("%.0f", lastGroundY - p.getY()), String.format("%.0f", p.getY()), falls,
						to == null ? "" : ", jumping " + from.toShortString() + " -> " + to.toShortString() + " in " + phase);
				if (to != null) HumanAutopilot.LOGGER.info("[parkour] that jump:{}", trace);
				to = null;
				route = new ArrayList<>();
				mappedAt = -10000; // (mapped again round where it landed: the map was round where it fell from)
			} else if (!Double.isNaN(lastGroundY) && lastGroundY - p.getY() > 1.5 && to == null && trace.length() > 0) {
				// (A quiet drop off the route, under what counts as a fall: what the last jump did.)
				HumanAutopilot.LOGGER.info("[parkour] dropped {} to y {} after the jump from {}:{}", String.format("%.1f", lastGroundY - p.getY()),
						String.format("%.0f", p.getY()), from == null ? "?" : from.toShortString(), trace);
				trace.setLength(0);
			}
			lastGroundY = p.getY();
		}
		if (atTop(p)) {
			if (topSince < 0) {
				topSince = now;
				slippedThisClimb = false;
				HumanAutopilot.LOGGER.info("[parkour] at the top {} after {} s ({} jumps, {} missed, {} falls)", top.toShortString(),
						(now - startedAt) / 20, jumps, misses, falls);
				goalHeight = Math.max(goalHeight, top.getY() - lowY);
			}
			to = null;
			lastTopAt = now;
			goalPending = false;
			// Stay on it, crouched (no stepping off), back towards its middle if near the edge.
			Vec3 mid = Vec3.atBottomCenterOf(top.above());
			aim.lookAt("pk-top", () -> mid.add(0, 0.2, 0));
			input.hold(InputDriver.opts().keyShift);
			if (Math.hypot(mid.x - p.getX(), mid.z - p.getZ()) > 0.3) keysToward(p, input, mid.x - p.getX(), mid.z - p.getZ());
			return "parkour: at the top, holding on";
		}
		topSince = -1;
		// Carried by a gift's ride (wolf, chicken, allay - up or down): hands off till it lets go, then on from there.
		// Looking round meanwhile, at the course and the way down, as anyone carried off would.
		if (p.isPassenger()) {
			to = null;
			route = new ArrayList<>();
			lastGroundY = Double.NaN;
			if (airborneSince < 0) airborneSince = now;
			lookAround(p, aim, now);
			return "parkour: being carried";
		}
		if (to != null) return jump(p, input, aim, now);
		// Just come down from a throw or a fall: steady on the block first (crouched, the speed off), then on.
		if (p.onGround() && now - landedAt < 6 && Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z) > 0.04) {
			input.hold(InputDriver.opts().keyShift);
			keysToward(p, input, -p.getDeltaMovement().x, -p.getDeltaMovement().z);
			return "parkour: landed, steadying";
		}
		// On the ground: the next jump of the route (a new route if it's off it).
		// Flying (creative, switched on with the autopilot): down to the ground first - in the air she only hung there,
		// waiting to land (15 s at the start once, till the course's own teleport).
		if (p.getAbilities().flying) {
			input.hold(InputDriver.opts().keyShift);
			return "parkour: down out of the air first";
		}
		if (!p.onGround()) return airborne(p, input, aim, now);
		stillPos = null;
		BlockPos here = support(p);
		if (here == null) {
			// Somewhere not mapped (round the edge of the mapped area, say): mapped again at once.
			mappedAt = -10000;
			return "parkour: finding my feet";
		}
		// Slowed (a gift's slowness): a jump planned for full speed would fall short - crouched on the block till it wears off.
		if (p.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS)) {
			input.hold(InputDriver.opts().keyShift);
			return "parkour: waiting out the slowness";
		}
		int i = route.indexOf(here);
		if (i < 0 || i + 1 >= route.size()) {
			route = plan(level, here);
			if (!route.get(route.size() - 1).equals(top) && !bad.isEmpty()) {
				// (Jumps left out after misses may be the only way up: all back in, rather than stuck.)
				bad.clear();
				route = plan(level, here);
			}
			if (route.size() < 2) {
				String r = rescue(p, here, now);
				if (r != null) return r;
				if (now - stuckLogAt > 200) {
					stuckLogAt = now;
					HumanAutopilot.LOGGER.info("[parkour] no jump I can make from {} towards the top {}", here.toShortString(), top.toShortString());
				}
				mappedAt = Math.min(mappedAt, now - 160); // (mapped again soon: something may have changed)
				return "parkour: no jump I can make from here";
			}
			// (Also no way to the top from here - a dead end that only goes higher on the wrong side: rescued too. The
			// clock only starts over once there's a way to the top again.)
			if (!route.get(route.size() - 1).equals(top)) {
				String r = rescue(p, here, now);
				if (r != null) return r;
			} else {
				noWaySince = -1;
				if (here.getY() <= lowY + 1) startSpot = here;
			}
			HumanAutopilot.LOGGER.info("[parkour] route from {}: {} jumps to {}{}", here.toShortString(), route.size() - 1,
					route.get(route.size() - 1).toShortString(), route.get(route.size() - 1).equals(top) ? " (the top)" : " (as high as I see a way)");
			i = 0;
			planSlip();
		}
		from = here;
		to = route.get(i + 1);
		startSlip(i);
		// A plain step up (one up, close): no stopping on the block - hold W and space and steer on, like the user (their
		// recording: 94 jumps in 62 s, 0.1 s on each block, W + space held, never sprinting, ~1.5 across, one up each).
		phase = (flowStep(p, from, to) || walkStep(from, to)) && flowTurnOk(p, to) ? Phase.RUN : Phase.SETTLE;
		carried = false;
		trace.setLength(0);
		phaseAt = now;
		return jump(p, input, aim, now);
	}

	/**
	 * No way to the top from where it stands (outside the cage, on a corner post) for 6 s: the plugin's /parkour tp, and
	 * if that didn't bring it back, a /tp to a floor spot a climb to the top has started from (or where parkour went on).
	 * Null while it's still giving it a moment.
	 */
	private String rescue(LocalPlayer p, BlockPos here, long now) {
		if (noWaySince < 0) noWaySince = now;
		if (now - noWaySince < 120 || now - rescueAt < 80) return null;
		rescueAt = now;
		rescues++;
		route = new ArrayList<>();
		mappedAt = -10000;
		if (rescues % 2 == 1) {
			HumanAutopilot.LOGGER.info("[parkour] no way to the top from {} for {} s (outside the cage?): /parkour tp", here.toShortString(), (now - noWaySince) / 20);
			p.connection.sendCommand("parkour tp");
		} else {
			BlockPos s = startSpot != null ? startSpot : centre;
			HumanAutopilot.LOGGER.info("[parkour] still no way to the top from {}: /tp to {}", here.toShortString(), s.toShortString());
			p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", s.getX() + 0.5, s.getY() + 1, s.getZ() + 0.5));
		}
		return "parkour: getting back to the course";
	}

	/**
	 * Whether this route has a slip in it, and where: about {@code parkourSlipChance} of a full climb's routes (less for a
	 * short one, as from half way up), at a hop somewhere along it - not the first few, not the last.
	 */
	private void planSlip() {
		slipAt = -1;
		if (slippedThisClimb) return;
		int n = route.size() - 1;
		double chance = HumanAutopilot.config().parkourSlipChance * Math.min(1, n / 90.0);
		if (n < 10 || !Human.chance(chance)) return;
		for (int k = Human.rangeInt(3, n - 3); k < n - 1; k++) {
			BlockPos a = route.get(k), b = route.get(k + 1);
			if (Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()) > 1.5) { // (a hop: a step up can't be walked off)
				slipAt = k;
				break;
			}
		}
	}

	/** The jump from the route's block {@code i} is starting: the slip, if it's this one. */
	private void startSlip(int i) {
		slipping = i >= 0 && i == slipAt;
		if (!slipping) return;
		slipAt = -1;
		slippedThisClimb = true;
		slipKind = forceSlipKind >= 0 ? forceSlipKind : Human.chance(0.5) ? 0 : 1;
		slipYawOff = Human.range(35, 45) * (Human.chance(0.5) ? 1 : -1);
		slipTurned = false;
		HumanAutopilot.LOGGER.info("[parkour] a slip on the jump {} -> {} ({}; about {} of climbs have one)", from.toShortString(), to.toShortString(),
				slipKind == 0 ? "the jump key missed: off the edge" : "turned the wrong way: stepped off its side", HumanAutopilot.config().parkourSlipChance);
	}

	/** A step across the floor to the block beside it (level, a block or a diagonal away): walked, never stopped at. */
	private static boolean walkStep(BlockPos a, BlockPos b) {
		double d = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
		return a.getY() == b.getY() && d >= 0.5 && d <= 1.5;
	}

	/**
	 * Where the eyes go walking across the floor: a few steps on along the route (the first block that isn't on the
	 * floor if that comes sooner) - ahead, not down at the next block under its feet.
	 */
	private Vec3 walkAhead(BlockPos b) {
		int i = route.indexOf(b);
		BlockPos far = b;
		for (int k = i + 1; i >= 0 && k < route.size() && k <= i + 3; k++) {
			far = route.get(k);
			if (far.getY() != b.getY()) break;
		}
		return Vec3.atBottomCenterOf(far.above());
	}

	/** A jump taken on the move: one up, at most a short hop across, no effect that changes how far it goes. */
	private static boolean flowStep(LocalPlayer p, BlockPos a, BlockPos b) {
		if (b.getY() - a.getY() != 1) return false;
		double d = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
		return d >= 0.9 && d <= 2.3 && !p.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS)
				&& !p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) && !p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
	}

	/** Facing it near enough already (the user turns a few degrees between blocks, now and then 20). */
	private static boolean flowTurnOk(LocalPlayer p, BlockPos b) {
		Vec3 c = Vec3.atBottomCenterOf(b.above());
		return Math.abs(Angles.wrap(Angles.yawTo(p.getEyePosition(), c) - p.getYRot())) < 50;
	}

	/** When W was last held (by holdW). */
	private long wHeldAt = -100;

	/**
	 * W held, unless that would be a fresh press inside the double-tap window (vanilla sprints then: sprintWindow, 6-7
	 * ticks) and no sprint is wanted. Holding on from last tick is always fine. False: not held this tick.
	 */
	private boolean holdW(InputDriver input, long now, boolean sprintOk) {
		boolean continuing = now - wHeldAt <= 1;
		if (!continuing && !sprintOk && now - wHeldAt <= 8) return false;
		input.hold(InputDriver.opts().keyUp);
		wHeldAt = now;
		return true;
	}

	/** The route's block after {@code b}, or null. */
	private BlockPos nextAfter(BlockPos b) {
		int i = route.indexOf(b);
		return i >= 0 && i + 1 < route.size() ? route.get(i + 1) : null;
	}

	/** The route's jump after {@code b} is another flow step: keep going without letting go of the keys. */
	private boolean flowNext(LocalPlayer p, BlockPos b) {
		int i = route.indexOf(b);
		return i >= 0 && i + 1 < route.size() && flowStep(p, b, route.get(i + 1));
	}

	/**
	 * Moves the feet towards a direction whatever way the camera points (forward/back/left/right, eight ways), as a
	 * player does stepping about on a block. {@code w} is a horizontal direction.
	 */
	private static void keysToward(LocalPlayer p, InputDriver input, double wx, double wz) {
		double len = Math.hypot(wx, wz);
		if (len < 1e-6) return;
		wx /= len;
		wz /= len;
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // where forward goes
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // where right (D) goes
		double f = wx * fx + wz * fz, r = wx * rx + wz * rz;
		var o = InputDriver.opts();
		if (f > 0.38) input.hold(o.keyUp); else if (f < -0.38) input.hold(o.keyDown);
		if (r > 0.38) input.hold(o.keyRight); else if (r < -0.38) input.hold(o.keyLeft);
	}

	/** One jump, from {@code from} to {@code to}, a tick at a time. */
	private String jump(LocalPlayer p, InputDriver input, AimController aim, long now) {
		BlockPos target = to;
		Vec3 a = Vec3.atBottomCenterOf(from.above()), b = Vec3.atBottomCenterOf(target.above());
		double d = Math.hypot(b.x - a.x, b.z - a.z);
		Vec3 dir = new Vec3(b.x - a.x, 0, b.z - a.z).normalize();
		boolean up = target.getY() > from.getY();
		boolean flow = flowStep(p, from, target);
		boolean walk = d <= 1.5;
		// A short one is a walking hop (a sprint jump flies right over a 1x1 two away); a long one, a sprint jump.
		// (The real course's corners are a block off to the side and two on - 2.24 across, one up: the user walk-hops
		// them. Sprinting those flew past the block.)
		boolean sprint = up ? d > 2.4 : d > 2.6;
		// (Sped up by a gift: a sprint jump goes much further - only the longest get one.)
		if (p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED)) sprint = d > 3.3;
		var o = InputDriver.opts();
		Vec3 look0 = b.add(0, up ? 0.2 : -0.1, 0);
		// Going on up the steps: in the air already, the eyes on the block after this one (looking down at the block
		// we land on, the camera's heading was anything - a stop to turn on a 1x1 and the run-on slid us off it).
		BlockPos after = flow && phase == Phase.AIR ? nextAfter(target) : null;
		// (Across the floor to the first block: one walk, the eyes ahead - the user: "she looks down. go. stop. go. stop
		// to first block", when each floor block was a jump of its own, settled at and looked down at.)
		boolean floorWalk = walkStep(from, target);
		Vec3 look = floorWalk ? walkAhead(target)
				: after != null && flowStep(p, target, after) ? Vec3.atBottomCenterOf(after.above()).add(0, 0.2, 0) : look0;
		aim.setUrgent(true);
		if (floorWalk) {
			Vec3 eye = p.getEyePosition();
			if (Math.hypot(look.x - eye.x, look.z - eye.z) > 0.8 || Double.isNaN(runYaw)) runYaw = Angles.yawTo(eye, look);
			aim.lookDirection("pk-walk", runYaw, runPitch);
		} else if (phase == Phase.SETTLE) aim.lookAt("pk-" + target.asLong(), () -> look);
		else {
			// Running and in the air: the pitch held, only the heading follows the blocks. Up the steps the pitch the
			// user keeps (~16 down, drifting a little); a longer jump keeps the pitch it had at its run's start. (Looking
			// at each block it landed on, the view tipped down and up every hop.)
			if (!target.equals(pitchFor)) {
				pitchFor = target;
				jumpPitch = Math.clamp(Angles.pitchTo(p.getEyePosition(), look0), 6, 40);
			}
			Vec3 eye = p.getEyePosition();
			if (Math.hypot(look.x - eye.x, look.z - eye.z) > 0.8 || Double.isNaN(runYaw)) runYaw = Angles.yawTo(eye, look);
			// (A fresh turn for each block, as with the point: one key for the whole run turned slower round the corners and
			// the chained corner jump left at half speed - "missed ... 2.2 across".)
			double yawGoal = runYaw + (slipping && slipKind == 1 ? slipYawOff : 0);
			aim.lookDirection("pk-run" + (look != look0 ? after.asLong() : target.asLong()), yawGoal, flow ? runPitch : jumpPitch);
		}
		double along = (p.getX() - a.x) * dir.x + (p.getZ() - a.z) * dir.z;
		if (phase != Phase.SETTLE) {
			Vec3 v0 = p.getDeltaMovement();
			if (trace.length() < 4000) trace.append(String.format(" | %s a%.2f y%.2f v%.2f/%.2f%s%s yaw%.0f", phase.name().charAt(0), along,
					p.getY() - from.getY() - 1, v0.x * dir.x + v0.z * dir.z, v0.y, p.onGround() ? " G" : "",
					(o.keyUp.isDown() ? " W" : "") + (p.isSprinting() ? " S" : ""), Angles.wrap(Angles.yawTo(p.position(), b) - p.getYRot())));
		} else trace.setLength(0);
		// (A step along the floor takes well under a second: 8 s was her standing pushing at the first block - the user's
		// "she would just stand there at first block not moving".)
		if (now - phaseAt > (walk && !up ? 40 : 20 * 8)) return giveUp("took too long");
		switch (phase) {
			case SETTLE -> {
				// Still on the take-off block, not sliding, turned to the next one; then the run. Sneaking meanwhile, as a
				// player does on a small block: it can't step off an edge (a landing's speed, or a step to the middle,
				// carried it off a 1x1: "down 5 blocks ... in SETTLE").
				Vec3 v = p.getDeltaMovement();
				double off = Math.hypot(a.x - p.getX(), a.z - p.getZ()), speed = Math.hypot(v.x, v.z);
				double yawErr = Math.abs(Angles.wrap(Angles.yawTo(p.getEyePosition(), look) - p.getYRot()));
				if (!p.onGround()) return "parkour: finding my feet";
				boolean ready = (speed <= 0.04 && off <= 0.35 || walk && off < 0.45) && (yawErr < 6 || walk && !up && yawErr < 15)
						|| now - phaseAt > 60;
				if (!ready) {
					input.hold(o.keyShift);
					if (speed > 0.05) keysToward(p, input, -v.x, -v.z);          // brake
					else if (off > 0.35) keysToward(p, input, a.x - p.getX(), a.z - p.getZ()); // to the middle
				} else if (!p.isCrouching() && !o.keyShift.isDown()) {
					phase = Phase.RUN;   // (up again from the sneak first: no sprinting out of one)
					phaseAt = now;
				}
				return "parkour: lining up the jump to " + target.toShortString();
			}
			case RUN -> {
				if (now == phaseAt + 1) HumanAutopilot.LOGGER.info("[parkour] jump {} -> {}: {} across, {}{}", from.toShortString(), target.toShortString(),
						String.format("%.1f", d), up ? "one up" : target.getY() == from.getY() ? "level" : "down " + (from.getY() - target.getY()),
						walk ? ", a step" : sprint ? ", sprint jump" : ", hop");
				// (Turned to it first: running while the camera still swung round carried it off to the side - "missed ...
				// landed on" the block beside it. A moment standing, no crouch: the user does that before a sharper turn.)
				double yawErrRun = Math.abs(Angles.wrap(Angles.yawTo(p.getEyePosition(), look) - p.getYRot()));
				// (Chained on from the last landing: no stop - the eyes were on this one already in the air. Level/down a walk
				// away: a little more slack. Not for ever either.)
				double turnOk = walk && !up ? 15 : 10;
				if (!carried && !slipping && p.onGround() && yawErrRun > turnOk) {
					if (now - phaseAt < 15) return "parkour: turning to " + target.toShortString();
					// (Still facing well off it after the turn's time - the camera held up, or knocked round: not off at an angle
					// (on the real course two such jumps went 50-80 degrees wide), crouched on the block and turned first.)
					if (yawErrRun > 35) {
						HumanAutopilot.LOGGER.info("[parkour] still {} degrees off {} after turning: settling first", (int) yawErrRun, target.toShortString());
						phase = Phase.SETTLE;
						phaseAt = now;
						return "parkour: turning first";
					}
				}
				// (The misjudged angle: turned that far first, standing, then off that way.)
				boolean misjudge = slipping && slipKind == 1;
				// (Once turned it goes: waiting again as the camera settled let go of W, the double-tap guard kept it off,
				// and the hop from a standstill still came down on the block's edge.)
				if (misjudge && !slipTurned) {
					if (p.onGround() && Math.abs(Angles.wrap(runYaw + slipYawOff - p.getYRot())) > 6 && now - phaseAt < 25)
						return "parkour: lining up the jump to " + target.toShortString();
					slipTurned = true;
				}
				if (!holdW(input, now, sprint)) return "parkour: (not tapping W twice)";
				if (sprint) input.hold(o.keySprint);
				// (Sprinting after all - a quick W again counts as a double tap: a walking hop at sprint speed flies right
				// over a 1x1. Back to the block's middle, crouched, and again.)
				if (!sprint && p.isSprinting() && p.onGround()) {
					HumanAutopilot.LOGGER.info("[parkour] sprinting by mistake on {}: back to the middle first", from.toShortString());
					phase = Phase.SETTLE;
					phaseAt = now;
					return "parkour: sprinting by mistake";
				}
				if (!p.onGround()) { phase = Phase.AIR; phaseAt = now; return "parkour: jumping"; }
				// Off the edge: the later, the further; a short hop or a step up can go earlier; a walk across or down, none.
				// (A step up: a little run first - jumped from a standstill on a 1x1 it didn't get over a diagonal's corner.)
				double at = walk ? (up ? 0.2 : 99) : sprint ? 0.5 : 0.35;
				// (On the move already, towards it: up at once, as the user does - space held through the landing.)
				double vAlong = p.getDeltaMovement().x * dir.x + p.getDeltaMovement().z * dir.z;
				// (Only at walking speed: the user takes off at ~0.23 a tick, 0.21 at the slowest. Up at once at 0.10 hit the
				// side of the block two on. Slower: a step or two on the block first, then up.)
				if (flow && vAlong > 0.19) at = -9;
				// (Chained round a corner the speed along the new way is low: from the edge then, not at once.)
				else if (flow && carried) at = vAlong >= 0.15 ? 0.25 : 0.55;
				// (Walking into the block it steps up onto: the body stops at its face, along 0.2 at most - jump then.)
				if (walk && up && (p.horizontalCollision || along >= 0.12)) at = -9;
				if (misjudge) at = 0.4;
				// (A slip never jumps: the jump key missed, or turned the wrong way and stepped off - jumping the wrong way still
				// came down on the 1x1's edge about half the time.)
				if (along >= at && !slipping) input.hold(o.keyJump);
				if (floorWalk && (along >= d - 0.3 || Math.hypot(b.x - p.getX(), b.z - p.getZ()) < 0.5)) {
					jumps++;
					// (On to the next without stopping: the next floor block, or the step up onto the first block.)
					BlockPos nx = nextAfter(target);
					int ti = route.indexOf(target);
					if (nx != null && p.onGround() && (walkStep(target, nx) || flowStep(p, target, nx)) && !(ti == slipAt && slipAt >= 0)) {
						from = target;
						to = nx;
						phase = Phase.RUN;
						phaseAt = now;
						carried = true;
						trace.setLength(0);
						startSlip(ti);
						return "parkour: walking on";
					}
					to = null; // (walked across onto it)
					return "parkour: stepped across";
				}
				return "parkour: running at the jump";
			}
			case AIR -> {
				Vec3 vv = p.getDeltaMovement();
				// Thrown (a blast, a launch): far faster than any jump of ours. Off the route - where to come down instead.
				double fast = p.hasEffect(net.minecraft.world.effect.MobEffects.SPEED) ? 0.65 : 0.5;
				if (Math.hypot(vv.x, vv.z) > fast || vv.y > 0.6) {
					HumanAutopilot.LOGGER.info("[parkour] thrown off the jump {} -> {} (speed {} across, {} up): looking for a block to come down on",
							from.toShortString(), target.toShortString(), String.format("%.2f", Math.hypot(vv.x, vv.z)), String.format("%.2f", vv.y));
					to = null;
					slipping = false;
					route = new ArrayList<>();
					return airborne(p, input, aim, now);
				}
				// Well under the block it was jumping to and coming down: missed. Somewhere else to land on, if there is one.
				// (Just under it: a teleport down - the test's, a gift's - is a fall, seen on landing, not a miss.)
				if (vv.y < 0 && p.getY() < target.getY() + 1 - 1.2 && p.getY() < from.getY() + 1 - 0.6 && p.getY() > target.getY() + 1 - 5) {
					if (!slipping) {
						misses++;
						bad.merge(key(from, target), 1, Integer::sum);
					} else slipsOff++;
					HumanAutopilot.LOGGER.info("[parkour] {} the jump {} -> {}: falling, looking for a block to come down on", slipping ? "slipped off" : "missed",
							from.toShortString(), target.toShortString());
					if (!slipping) HumanAutopilot.LOGGER.info("[parkour] that jump:{}", trace);
					to = null;
					slipping = false;
					route = new ArrayList<>();
					return airborne(p, input, aim, now);
				}
				if (slipping) {
					// The slip: no keys at all after a missed jump key; let go and back a touch half way through a jump.
					// (The misjudged angle: W on through the flight, the way it's facing.)
					if (slipKind == 1) holdW(input, now, false);
					if (p.onGround()) {
						if (!target.equals(support(p))) slipsOff++;
						HumanAutopilot.LOGGER.info("[parkour] the slip came down on {}:{}", support(p) == null ? "nothing mapped" : support(p).toShortString(), trace);
						to = null;
						slipping = false;
						route = new ArrayList<>();
						return "parkour: slipped";
					}
					return "parkour: slipping";
				}
				double left = (b.x - p.getX()) * dir.x + (b.z - p.getZ()) * dir.z;
				double v = p.getDeltaMovement().x * dir.x + p.getDeltaMovement().z * dir.z;
				// Ease off over the block: a 1x1 is easy to overshoot.
				// (Going on up the steps: W and space held through the landing, no braking.)
				// (Not into a slip of the jump key: that one starts from a standstill landing, without the keys held on.)
				boolean goOn = flow && flowNext(p, target) && !(route.indexOf(target) == slipAt && slipAt >= 0);
				if (goOn) {
					holdW(input, now, false);
					// (Space held through the landing only at speed - the next jump goes at once then. Slower, it lands, runs a
					// step and jumps from the RUN.)
					BlockPos nx2 = nextAfter(target);
					Vec3 nd = nx2 == null ? dir : new Vec3(nx2.getX() - target.getX(), 0, nx2.getZ() - target.getZ()).normalize();
					double vNext = p.getDeltaMovement().x * nd.x + p.getDeltaMovement().z * nd.z;
					if (vNext > 0.19) input.hold(o.keyJump);
				} else if (left > 0.5) {
					holdW(input, now, sprint);
					if (sprint) input.hold(o.keySprint);
				} else if (v > 0.08 && left < 0.3) {
					input.hold(o.keyDown);
				}
				if (p.onGround()) {
					BlockPos s = support(p);
					to = null;
					if (target.equals(s)) {
						jumps++;
						// (Now and then a small nudge of the pitch on a landing, as the user's moves 0.5-3 degrees a jump.)
						if (Human.chance(0.35)) runPitch = Math.clamp(runPitch + Human.range(-2, 2), runPitchBase - 3, runPitchBase + 3);
						// Up the steps: the next jump at once, keys still held (space held through the landing jumps again
						// straight away - left to "in the air" with no jump set up, it sailed off unsteered).
						BlockPos nx = goOn ? nextAfter(target) : null;
						if (nx != null) {
							from = target;
							to = nx;
							phase = Phase.RUN;
							phaseAt = now;
							carried = true;
							trace.setLength(0);
							startSlip(route.indexOf(target));
						}
						return "parkour: landed";
					}
					if (from.equals(s)) return "parkour: still on the take-off block";
					misses++;
					bad.merge(key(from, target), 1, Integer::sum);
					HumanAutopilot.LOGGER.info("[parkour] missed the jump {} -> {} ({} across, {}), landed on {} at y {}", from.toShortString(),
							target.toShortString(), String.format("%.1f", d), up ? "one up" : "level/down",
							s == null ? "nothing mapped" : s.toShortString(), String.format("%.1f", p.getY()));
					HumanAutopilot.LOGGER.info("[parkour] that jump:{}", trace);
					route = new ArrayList<>();
					return "parkour: missed, again";
				}
				return "parkour: in the air";
			}
		}
		return "parkour";
	}

	// ---- in the air off the route: thrown, carried off, fallen

	/**
	 * In the air with no jump of ours under way (a blast threw it, a ride let go, a jump missed): a moment's surprise,
	 * looking round on the way up, then the highest block of the course it can still steer onto, eyes on it, W/A/S/D
	 * steering in the air (the user: "tnt makes her fly up ... she does not aim which block to land"). Caught inside a
	 * block and not moving for 2 s: out sideways, jumping.
	 */
	private String airborne(LocalPlayer p, InputDriver input, AimController aim, long now) {
		if (airborneSince < 0) {
			airborneSince = now;
			landOn = null;
			landWayUp.clear();
			glanceUntil = now + Human.rangeInt(4, 9); // (the surprise: the eyes stay where they were a moment)
			glanceAt = null;
		}
		Level level = p.level();
		if (stillPos == null || p.position().distanceToSqr(stillPos) > 0.01) {
			stillPos = p.position();
			stillAt = now;
		} else if (now - stillAt > 40) {
			BlockPos feet = p.blockPosition();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos c = feet.relative(d);
				if (level.getBlockState(c).getCollisionShape(level, c).isEmpty() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) {
					if (now - stuckLogAt > 200) {
						stuckLogAt = now;
						HumanAutopilot.LOGGER.info("[parkour] caught in a block at {}: out to {}", feet.toShortString(), c.toShortString());
					}
					keysToward(p, input, c.getX() + 0.5 - p.getX(), c.getZ() + 0.5 - p.getZ());
					input.hold(InputDriver.opts().keyJump);
					break;
				}
			}
			return "parkour: out of a block";
		}
		Vec3 v = p.getDeltaMovement();
		boolean rising = v.y > 0.08;
		// Where to come down: worked out again every few ticks once it's coming down (or after a long way up).
		if ((!rising || now - airborneSince > 40) && (landOn == null || now - landPickedAt >= 4)) {
			BlockPos was = landOn;
			landOn = pickLanding(p);
			landPickedAt = now;
			if (landOn != null && !landOn.equals(was))
				HumanAutopilot.LOGGER.info("[parkour] coming down: steering for {}{}", landOn.toShortString(), landOn.equals(top) ? " (the top!)" : "");
		}
		if (landOn != null) {
			Vec3 c = Vec3.atBottomCenterOf(landOn.above());
			aim.lookAt("pk-land" + landOn.asLong(), () -> c);
			double[] l = predictCrossing(p, landOn.getY() + 1);
			if (l != null) {
				double dx = c.x - l[0], dz = c.z - l[1];
				double off = Math.hypot(dx, dz);
				if (off > 0.12) keysToward(p, input, dx, dz);
				// (Crouched for the landing on a small block, so the speed doesn't carry it off the edge.)
				if (l[2] <= 2 && off < 0.35) input.hold(InputDriver.opts().keyShift);
			}
			return "parkour: coming down on " + landOn.toShortString();
		}
		lookAround(p, aim, now);
		return rising ? "parkour: thrown up" : "parkour: falling";
	}

	/**
	 * Where the feet come down through height {@code yTop} on the way down with no keys pressed: {x, z, ticks}, or null if
	 * they never do (it's above where it'll get to, or it's below already). Vanilla air physics: move, then the fall speed
	 * (v - 0.08) * 0.98, across * 0.91.
	 */
	private static double[] predictCrossing(LocalPlayer p, double yTop) {
		Vec3 v = p.getDeltaMovement();
		double x = p.getX(), y = p.getY(), z = p.getZ(), vx = v.x, vy = v.y, vz = v.z;
		for (int t = 1; t <= 100; t++) {
			double nx = x + vx, ny = y + vy, nz = z + vz;
			if (vy < 0 && y >= yTop && ny <= yTop) {
				double f = (y - yTop) / Math.max(1e-6, y - ny);
				return new double[]{x + f * (nx - x), z + f * (nz - z), t};
			}
			x = nx;
			y = ny;
			z = nz;
			vy = (vy - 0.08) * 0.98;
			vx *= 0.91;
			vz *= 0.91;
			if (y < yTop - 1 && vy < 0) return null;
		}
		return null;
	}

	/** How far W/A/S/D held the whole way can shift where it comes down in {@code t} ticks (0.02 a tick in the air). */
	private static double steerReach(double t) {
		double a = 0.02, k = 0.91;
		return a / (1 - k) * (t - k * (1 - Math.pow(k, t)) / (1 - k));
	}

	/**
	 * The highest mapped block it can still steer onto from here that has a way on up to the top (the top itself if it
	 * can), or null. (Not just the highest: the cage's glass corner posts stand a block over the course's top with no
	 * barrier over them - thrown way up, it came down on one, outside the cage on its corner, and couldn't get back in.)
	 */
	private BlockPos pickLanding(LocalPlayer p) {
		List<double[]> cands = new ArrayList<>(); // {index into list, y, off}
		List<BlockPos> blocks = new ArrayList<>();
		Map<Integer, double[]> byY = new HashMap<>();
		for (BlockPos b : stands) {
			double[] l = byY.computeIfAbsent(b.getY(), y -> {
				double[] r = predictCrossing(p, y + 1);
				return r == null ? new double[0] : r;
			});
			if (l.length == 0) continue;
			double off = Math.hypot(b.getX() + 0.5 - l[0], b.getZ() + 0.5 - l[1]);
			if (off > steerReach(l[2]) * 0.8 + 0.4) continue;
			blocks.add(b);
			cands.add(new double[]{blocks.size() - 1, b.getY(), off});
		}
		cands.sort((x, y) -> x[1] != y[1] ? Double.compare(y[1], x[1]) : Double.compare(x[2], y[2]));
		int looked = 0;
		for (double[] c : cands) {
			BlockPos b = blocks.get((int) c[0]);
			Boolean ok = landWayUp.get(b);
			if (ok == null) {
				if (looked++ >= 4) continue; // (a few route checks a pick: the rest next time)
				List<BlockPos> r = b.equals(top) ? List.of(b) : plan(p.level(), b);
				ok = !r.isEmpty() && r.get(r.size() - 1).equals(top);
				landWayUp.put(b, ok);
			}
			if (ok) return b;
		}
		return null;
	}

	/**
	 * Eyes going round while carried or flung: the course's blocks round about, the top, the way down, a look off to the
	 * side - each held a moment, as a person looks round when something whisks them off (the user: "also not looking
	 * around").
	 */
	private void lookAround(LocalPlayer p, AimController aim, long now) {
		if (now < glanceUntil && glanceAt == null) return; // (the first moment: no look yet)
		if (glanceAt == null || now >= glanceUntil) {
			glances++;
			glanceUntil = now + Human.rangeInt(10, 26);
			Vec3 eye = p.getEyePosition();
			double r = Human.range(0, 1);
			glanceAt = null;
			if (r < 0.5 && !stands.isEmpty()) {
				// A block of the course within 14 or so (any way: it's looking round).
				List<BlockPos> near = new ArrayList<>();
				for (BlockPos b : stands) if (b.distSqr(BlockPos.containing(eye)) < 14 * 14) near.add(b);
				if (!near.isEmpty()) glanceAt = Vec3.atBottomCenterOf(near.get(Human.RNG.nextInt(near.size())).above());
			} else if (r < 0.7 && top != null) {
				glanceAt = Vec3.atBottomCenterOf(top.above());
			}
			if (glanceAt == null) {
				// Off to one side, level-ish or down.
				double yaw = Math.toRadians(p.getYRot() + Human.range(40, 110) * (Human.chance(0.5) ? 1 : -1));
				double pitch = Math.toRadians(Human.range(-5, 50));
				glanceAt = eye.add(-Math.sin(yaw) * Math.cos(pitch) * 8, -Math.sin(pitch) * 8, Math.cos(yaw) * Math.cos(pitch) * 8);
			}
		}
		Vec3 g = glanceAt;
		aim.lookAt("pk-glance" + glances, () -> g);
	}

	private String giveUp(String why) {
		HumanAutopilot.LOGGER.info("[parkour] jump {} -> {} {}: another route", from.toShortString(), to.toShortString(), why);
		bad.merge(key(from, to), 1, Integer::sum);
		to = null;
		route = new ArrayList<>();
		return "parkour: " + why;
	}
}
