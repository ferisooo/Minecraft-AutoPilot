package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * StreamToEarn's Ladder mini-game (s2e-ladder): up the ladder to the top, then hold on there while the timer runs down.
 * Viewers' gifts pull her down or up, shoot arrows at her, boost her up (a spectral arrow, wings), jail her, or set a
 * zombie or a phantom on her. Real keys only: W into the ladder to climb, sneak to hold on, the camera on the way up.
 * <p>
 * The arena (read out of the plugin, not seen in a world yet): bands of coloured concrete, a ladder (LADDER blocks),
 * barriers; 300 high by default, 1000 with its height datapack.
 */
public final class LadderModule {
	private static final LadderModule INSTANCE = new LadderModule();

	public static LadderModule get() {
		return INSTANCE;
	}

	/** How far round her the ladder is looked for (sideways, and up and down round her feet). */
	private static final int RADIUS = 24, NEAR_DY = 24;

	private boolean on;
	/** The ladder column climbed: its lowest and highest ladder block, and the way the ladders face (off the wall). */
	private BlockPos bottom, topLadder;
	private Direction facing;
	/** Where she stands at the top (on the wall's top block, out over the ladder's end), or null: then she hangs on. */
	private BlockPos topStand;
	private long mappedAt = -10000, startedAt, topSince = -1, logAt = -1000, progressAt, stuckTpAt = -10000;
	private double bestY = Double.NEGATIVE_INFINITY;
	private int climbs, falls, wins;
	private double lastGroundY = Double.NaN;
	/**
	 * Where she looks climbing (positive = down): a little down the ladder, drifting round a base picked per session
	 * (the user, with a screenshot of their own climb: "make it so she would look down like in the picture, not all
	 * the way down but slight").
	 */
	private double climbPitch = 18, climbPitchBase = 18;
	private long glanceUntil, nextGlanceAt;
	private float glanceYaw, glancePitch;
	private long hitAt;

	private LadderModule() {}

	public boolean on() {
		return on;
	}

	public int climbs() {
		return climbs;
	}

	public int falls() {
		return falls;
	}

	public BlockPos topLadder() {
		return topLadder;
	}

	public void start(LocalPlayer p) {
		on = true;
		save(true);
		bottom = topLadder = topStand = null;
		facing = null;
		mappedAt = -10000;
		startedAt = HumanAutopilot.ticks();
		progressAt = startedAt;
		topSince = -1;
		climbs = falls = 0;
		bestY = Double.NEGATIVE_INFINITY;
		lastGroundY = Double.NaN;
		climbPitchBase = climbPitch = Human.gauss(10, 28);
		nextGlanceAt = startedAt + Human.rangeInt(60, 160);
		HumanAutopilot.LOGGER.info("[ladder] on at {}", p.blockPosition().toShortString());
	}

	public void stop() {
		if (!on) return;
		on = false;
		save(false);
		HumanAutopilot.LOGGER.info("[ladder] off ({} climbs to the top, {} falls)", climbs, falls);
	}

	// ---- carried on after the game restarts (LiveKeeper reopens it), like the parkour

	private static boolean startChecked;
	private static long inWorldAt = -1;

	private static java.nio.file.Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("ladder.properties");
	}

	private static void save(boolean on) {
		try {
			java.nio.file.Files.createDirectories(file().getParent());
			java.nio.file.Files.writeString(file(), "on=" + on + System.lineSeparator());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[ladder] couldn't save: {}", e.toString());
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
			HumanAutopilot.LOGGER.info("[ladder] the game closed in the middle of the ladder game: carrying on");
			HumanAutopilot.setLadder(true);
		}
	}

	// ---- the ladder

	private static boolean ladder(Level level, BlockPos b) {
		return level.getBlockState(b).is(Blocks.LADDER);
	}

	/**
	 * Finds the ladder: every LADDER block round her (24 sideways, 24 up and down), each followed up and down its column
	 * (gaps of up to 2 bridged); the tallest column wins, the nearer if two reach as high.
	 */
	private void map(LocalPlayer p) {
		Level level = p.level();
		BlockPos at = p.blockPosition();
		int minY = level.getMinY(), maxY = level.getMaxY() - 1;
		BlockPos bestLo = null, bestHi = null;
		double bestD = 0;
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				int x = at.getX() + dx, z = at.getZ() + dz;
				for (int y = Math.max(minY, at.getY() - NEAR_DY); y <= Math.min(maxY, at.getY() + NEAR_DY); y++) {
					BlockPos b = new BlockPos(x, y, z);
					if (!ladder(level, b)) continue;
					if (!seen.add(BlockPos.asLong(x, 0, z))) break; // (one walk per column)
					BlockPos lo = b, hi = b;
					for (int yy = y - 1, gap = 0; yy >= minY && gap <= 2; yy--) {
						if (ladder(level, new BlockPos(x, yy, z))) { lo = new BlockPos(x, yy, z); gap = 0; } else gap++;
					}
					for (int yy = y + 1, gap = 0; yy <= maxY && gap <= 2; yy++) {
						if (ladder(level, new BlockPos(x, yy, z))) { hi = new BlockPos(x, yy, z); gap = 0; } else gap++;
					}
					double d = dx * dx + dz * dz;
					if (bestHi == null || hi.getY() > bestHi.getY() || hi.getY() == bestHi.getY() && d < bestD) {
						bestLo = lo;
						bestHi = hi;
						bestD = d;
					}
					break;
				}
			}
		}
		if (bestHi == null) return;
		BlockState s = level.getBlockState(bestHi);
		Direction f = s.hasProperty(LadderBlock.FACING) ? s.getValue(LadderBlock.FACING) : Direction.NORTH;
		// The top: on the wall's top block if there's room over it (the ladder ends at the top of the tower), else on the
		// block over the ladder's end; neither - she hangs on at the top of the ladder.
		BlockPos stand = null;
		BlockPos wall = bestHi.relative(f.getOpposite());
		for (int up = 0; up <= 2 && stand == null; up++) {
			BlockPos w = wall.above(up);
			if (solidTop(level, w) && clear(level, w.above()) && clear(level, w.above(2))) stand = w;
			BlockPos o = bestHi.above(up);
			if (stand == null && up > 0 && solidTop(level, o) && clear(level, o.above()) && clear(level, o.above(2))) stand = o;
		}
		boolean changed = !bestHi.equals(topLadder) || !bestLo.equals(bottom);
		bottom = bestLo;
		topLadder = bestHi;
		facing = f;
		topStand = stand;
		if (changed) HumanAutopilot.LOGGER.info("[ladder] the ladder: {} up to {} ({} high, facing {}), the top: {}", bottom.toShortString(),
				topLadder.toShortString(), topLadder.getY() - bottom.getY() + 1, f.getName(), stand == null ? "hanging on at the top" : "standing on " + stand.toShortString());
	}

	private static boolean solidTop(Level level, BlockPos b) {
		BlockState s = level.getBlockState(b);
		return !s.isAir() && !s.is(Blocks.BARRIER) && s.isFaceSturdy(level, b, Direction.UP);
	}

	private static boolean clear(Level level, BlockPos b) {
		return level.getBlockState(b).getCollisionShape(level, b).isEmpty();
	}

	// ---- each tick

	public String tick(LocalPlayer p, InputDriver input, AimController aim) {
		long now = HumanAutopilot.ticks();
		var o = InputDriver.opts();
		Level level = p.level();
		if (now - mappedAt > 200 || bottom == null) {
			mappedAt = now;
			map(p);
		}
		if (bottom == null) {
			if (now - logAt > 200) {
				logAt = now;
				HumanAutopilot.LOGGER.info("[ladder] no ladder within {} of {}", RADIUS, p.blockPosition().toShortString());
			}
			return "ladder: no ladder round here";
		}
		climbPitch = Math.clamp(climbPitch + Human.range(-0.05, 0.05), climbPitchBase - 5, climbPitchBase + 5);
		if (p.onGround()) {
			if (!Double.isNaN(lastGroundY) && lastGroundY - p.getY() > 3.5) {
				falls++;
				HumanAutopilot.LOGGER.info("[ladder] down {} blocks to y {} (fall {}): back to the ladder", String.format("%.0f", lastGroundY - p.getY()),
						String.format("%.0f", p.getY()), falls);
				mappedAt = Math.min(mappedAt, now - 180);
			}
			lastGroundY = p.getY();
		}
		if (p.getY() > bestY + 1) {
			bestY = p.getY();
			progressAt = now;
		}
		double cx = bottom.getX() + 0.5, cz = bottom.getZ() + 0.5;
		double side = Math.hypot(p.getX() - cx, p.getZ() - cz);
		float wallYaw = facing.getOpposite().toYRot();

		// A zombie or a phantom on her, close: hit it (on the ladder too - it's right there).
		Entity mob = nearestMob(p, 3.2);
		boolean fighting = mob != null && (p.onGround() || p.onClimbable());

		// At the top: hold on there till the timer's done (crouched on the top block, or sneak on the ladder's end).
		if (atTop(p)) {
			if (topSince < 0) {
				topSince = now;
				climbs++;
				HumanAutopilot.LOGGER.info("[ladder] at the top after {} s ({} falls)", (now - startedAt) / 20, falls);
			}
			input.hold(o.keyShift);
			if (fighting) return fight(p, input, aim, mob, now);
			if (topStand != null && p.onGround()) {
				Vec3 mid = Vec3.atBottomCenterOf(topStand.above());
				if (Math.hypot(mid.x - p.getX(), mid.z - p.getZ()) > 0.3) keysTo(p, input, mid.x - p.getX(), mid.z - p.getZ());
			}
			lookRound(p, aim, now, wallYaw, 10);
			progressAt = now;
			return "ladder: at the top, holding on";
		}
		if (topSince >= 0 && now - topSince < 40 && p.getY() > topLadder.getY() - 3) {
			// (Just off the top - a step back onto the ladder's end while settling: not a new climb yet.)
		} else topSince = -1;

		// Carried off (a gift's ride) or flying with the wings boost: eyes on the ladder, steering for it.
		if (p.isPassenger()) {
			lookRound(p, aim, now, wallYaw, 0);
			return "ladder: being carried";
		}

		boolean onLadder = p.onClimbable() && side < 1.2;
		if (onLadder) {
			if (fighting && Human.chance(0.5)) return fight(p, input, aim, mob, now);
			// Up the ladder: facing the wall it's on, W into it; the eyes up the ladder, now and then a look down or round.
			double py = climbPitch;
			boolean glancing = now < glanceUntil;
			if (!glancing && now >= nextGlanceAt) {
				glanceUntil = now + Human.rangeInt(10, 24);
				nextGlanceAt = glanceUntil + Human.rangeInt(60, 200);
				// (Up to 40 off: the camera is the body - further, W would walk it off the ladder's side.)
				glanceYaw = wallYaw + (float) Human.range(-40, 40);
				glancePitch = (float) (Human.chance(0.55) ? Human.range(35, 70) : Human.range(-10, 20)); // down, or out
				glancing = true;
			}
			// (Glances turn the head, not the body: the body keeps W into the wall only while it still faces it.)
			if (glancing) aim.lookDirection("ld-glance", glanceYaw, glancePitch);
			else aim.lookDirection("ld-climb", wallYaw, py);
			double faceErr = Math.abs(Angles.wrap(wallYaw - p.getYRot()));
			if (faceErr < 45) input.hold(o.keyUp);
			else if (p.getDeltaMovement().y < -0.05) input.hold(o.keyShift); // (looking round, not facing it: hold on)
			// Kept in the ladder's cell sideways (knocked to its edge, it'd slide off past the wall's corner).
			double lx = cx - p.getX(), lz = cz - p.getZ();
			double along = lx * facing.getStepX() + lz * facing.getStepZ();
			double sx = lx - along * facing.getStepX(), sz = lz - along * facing.getStepZ();
			if (faceErr < 45 && Math.hypot(sx, sz) > 0.22) keysTo(p, input, sx, sz);
			progressAt = Math.max(progressAt, p.getDeltaMovement().y > 0.05 ? now : progressAt);
			return "ladder: climbing (" + String.format("%.0f", p.getY() - bottom.getY()) + " up)";
		}

		if (!p.onGround()) {
			// In the air (knocked off, thrown, boosted): back into the ladder if it's in reach on the way down.
			Vec3 into = Vec3.atBottomCenterOf(bottom).add(0, 0, 0);
			boolean beside = p.getY() >= bottom.getY() - 1 && p.getY() <= topLadder.getY() + 1.5;
			if (p.isFallFlying()) {
				// Wings: glide back to the ladder near the top.
				Vec3 t = Vec3.atCenterOf(new BlockPos(bottom.getX(), Math.min(topLadder.getY(), (int) p.getY()), bottom.getZ()));
				aim.lookAt("ld-glide", () -> t);
				return "ladder: gliding back";
			}
			if (beside && side < 2.5) {
				aim.lookDirection("ld-climb", wallYaw, climbPitch);
				keysTo(p, input, into.x - p.getX(), into.z - p.getZ());
				return "ladder: grabbing the ladder";
			}
			lookRound(p, aim, now, wallYaw, 0);
			return "ladder: in the air";
		}

		if (fighting) return fight(p, input, aim, mob, now);

		// On the ground: to the ladder - the cell of it at her height if it reaches down here, else its foot.
		int y = (int) Math.floor(p.getY() + 0.01);
		BlockPos goal = y >= bottom.getY() && y <= topLadder.getY() && ladder(level, new BlockPos(bottom.getX(), y, bottom.getZ()))
				? new BlockPos(bottom.getX(), y, bottom.getZ()) : bottom;
		Vec3 g = Vec3.atBottomCenterOf(goal);
		double dx = g.x - p.getX(), dz = g.z - p.getZ(), d = Math.hypot(dx, dz);
		// (Walking in: the eyes on where it's going, then up the ladder as she gets to it.)
		Vec3 lookAt = d > 1.5 ? g.add(0, 1.2, 0) : g.add(0, 4, 0);
		aim.lookAt("ld-walk" + goal.asLong(), () -> lookAt);
		keysTo(p, input, dx, dz);
		if (d < 1.2) input.hold(o.keyUp); // (W into the wall: climbing starts)
		if (p.horizontalCollision && d > 1.2) input.hold(o.keyJump); // (a step in the way)
		// Not getting anywhere for 45 s (outside the arena, jailed long): back to the start with the plugin's /ladder tp.
		// (45 s: a gift's prison lasts its seconds - she waits that out.)
		if (now - progressAt > 900 && now - stuckTpAt > 600) {
			stuckTpAt = now;
			progressAt = now;
			HumanAutopilot.LOGGER.info("[ladder] not getting to the ladder from {} for 45 s: /ladder tp", p.blockPosition().toShortString());
			p.connection.sendCommand("ladder tp");
			mappedAt = -10000;
			bestY = Double.NEGATIVE_INFINITY;
		}
		if (d < 0.5 && !p.onClimbable() && now - logAt > 200) {
			logAt = now;
			HumanAutopilot.LOGGER.info("[ladder] at the ladder's foot {} but not on it", goal.toShortString());
		}
		return "ladder: to the ladder (" + String.format("%.0f", d) + " away)";
	}

	private boolean atTop(LocalPlayer p) {
		if (topStand != null && p.onGround()) {
			BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
			if (under.getY() >= topStand.getY() && Math.abs(under.getX() - topStand.getX()) <= 1 && Math.abs(under.getZ() - topStand.getZ()) <= 1) return true;
		}
		// (No top block: up at the ladder's end, hanging on.)
		return topStand == null && p.onClimbable() && p.getY() >= topLadder.getY() - 0.1;
	}

	/** The nearest zombie, phantom or other monster within {@code r}, or null. */
	private static Entity nearestMob(LocalPlayer p, double r) {
		Entity best = null;
		double bd = r * r;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(r))) {
			if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
			double d = e.distanceToSqr(p);
			if (d < bd) { bd = d; best = e; }
		}
		return best;
	}

	/** One hit at a time, full charge, the crosshair on it (it's the gift's mob: a few hits and it's gone). */
	private String fight(LocalPlayer p, InputDriver input, AimController aim, Entity mob, long now) {
		aim.setUrgent(true);
		aim.lookAt("ld-mob" + mob.getId(), () -> mob.position().add(0, mob.getBbHeight() * 0.7, 0));
		var hit = Minecraft.getInstance().hitResult;
		if (hit instanceof EntityHitResult eh && eh.getEntity() == mob && p.getAttackStrengthScale(0.5f) >= 0.95f && now - hitAt > 8) {
			hitAt = now;
			input.click(InputDriver.opts().keyAttack);
		}
		if (p.onClimbable()) input.hold(InputDriver.opts().keyShift); // (on the ladder: hold on while hitting it)
		return "ladder: hitting the " + mob.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * A slow look round (at the top, carried, in the air): over the arena, down the ladder, out at the view - each
	 * held a moment.
	 */
	private void lookRound(LocalPlayer p, AimController aim, long now, float baseYaw, double basePitch) {
		if (now >= glanceUntil) {
			glanceUntil = now + Human.rangeInt(25, 70);
			glanceYaw = baseYaw + (float) Human.range(-160, 160);
			glancePitch = (float) (basePitch + Human.range(-15, 45));
		}
		aim.lookDirection("ld-round" + (glanceUntil / 7), glanceYaw, glancePitch);
	}

	/** W/A/S/D towards a horizontal direction, whatever way the camera points (eight ways). */
	private static void keysTo(LocalPlayer p, InputDriver input, double wx, double wz) {
		double len = Math.hypot(wx, wz);
		if (len < 1e-6) return;
		wx /= len;
		wz /= len;
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double f = wx * fx + wz * fz, r = wx * rx + wz * rz;
		var o = InputDriver.opts();
		if (f > 0.38) input.hold(o.keyUp); else if (f < -0.38) input.hold(o.keyDown);
		if (r > 0.38) input.hold(o.keyRight); else if (r < -0.38) input.hold(o.keyLeft);
	}
}
