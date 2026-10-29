package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Melee fighting with reaction time, full-charge hits, strafing, backing off, and shield use. */
public final class CombatModule {
	private LivingEntity target;
	private long engageAt;          // reaction time: don't act on a new target before this tick
	private long nextSwingAt;
	private boolean wasCharged;
	private Vec3 bodyOffset = Vec3.ZERO;
	private long offsetUntil;
	private int strafeDir;
	private long strafeUntil;
	private long backOffUntil, letGoUntil, nextWhiffCheck, critJumpUntil;
	/** Jump-crit swings (for the test log). */
	public static long crits;
	/** Swings that missed (for the test log). */
	public static long whiffs;
	private long lastFought = -1000;

	public LivingEntity target() {
		return target;
	}

	public static List<LivingEntity> hostilesAround(LocalPlayer p, double range) {
		return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range),
				e -> e instanceof Enemy && e.isAlive() && !e.isRemoved() && e != p && !calmNeutral(e)
						&& p.distanceTo(e) <= range && p.hasLineOfSight(e));
	}

	/**
	 * Endermen and zombified piglins are only dangerous once provoked; a player leaves them be
	 * (hitting one, or one piglin of a group, is how you die early on).
	 */
	public static boolean calmNeutral(LivingEntity e) {
		if (!(e instanceof net.minecraft.world.entity.NeutralMob)) return false;
		if (e instanceof net.minecraft.world.entity.monster.EnderMan em && em.isCreepy()) return false;
		return !(e instanceof Mob m && m.isAggressive());
	}

	private static double danger(LivingEntity e) {
		if (e instanceof Creeper) return 3;
		if (e instanceof RangedAttackMob) return 2;
		return 1;
	}

	/** Look for something to fight. Returns true if there is a target worth engaging. */
	public boolean scan(LocalPlayer p) {
		var cfg = HumanAutopilot.config();
		if (target != null && (!target.isAlive() || target.isRemoved() || p.distanceTo(target) > cfg.combatScanRange + 4)) {
			target = null;
		}
		List<LivingEntity> list = hostilesAround(p, cfg.combatScanRange);
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		boolean byDanger = "danger".equalsIgnoreCase(cfg.targetMode);
		// Bare-handed (no sword or axe) nobody goes looking for a fight: only hit back at what's already on us,
		// and never chase a skeleton across a field with fists.
		boolean armed = Tools.meleeDps(p.getInventory().getItem(Tools.bestWeaponSlot(p))) > 5;
		for (LivingEntity e : list) {
			if (Math.abs(e.getY() - p.getY()) > 4) continue; // can't reach it on foot anyway
			if (!armed && (e instanceof RangedAttackMob || p.distanceTo(e) > 3.5)) continue;
			double score = p.distanceTo(e) - (byDanger ? danger(e) * 2.5 : 0);
			if (e == target) score -= 1.5; // stick with the current target unless something is clearly worse
			if (score < bestScore) {
				bestScore = score;
				best = e;
			}
		}
		if (best == null && target != null && !(target instanceof Enemy)) {
			return false; // keep a hunting target, but this isn't a fight we have to take
		}
		if (best != target) {
			// Already fighting (or just got hit): attention is on the fight, so switching to the next mob is quick.
			// Only something new out of the blue gets the full "notice it" reaction time.
			boolean inFight = target != null || p.hurtTime > 0 || HumanAutopilot.ticks() - lastFought < 60;
			target = best;
			engageAt = HumanAutopilot.ticks() + (inFight ? Human.rangeInt(2, 4) : Human.reaction());
			wasCharged = false;
			pickBodyOffset();
		}
		return target != null;
	}

	/** Go after a specific mob (used for hunting animals for food). */
	public void hunt(LivingEntity e) {
		if (e != target) {
			target = e;
			engageAt = HumanAutopilot.ticks() + Human.reaction();
			wasCharged = false;
			pickBodyOffset();
		}
	}

	private void pickBodyOffset() {
		if (target == null) return;
		double w = target.getBbWidth() * 0.3;
		double h = target.getBbHeight();
		bodyOffset = new Vec3(Human.range(-w, w), h * Human.range(0.45, 0.8), Human.range(-w, w));
		offsetUntil = HumanAutopilot.ticks() + Human.rangeInt(10, 25);
	}

	public void reset() {
		target = null;
	}

	/** Fight the current target. Call only after {@link #scan} returned true. */
	public String tick(LocalPlayer p, InputDriver in, AimController aim) {
		var cfg = HumanAutopilot.config();
		var o = InputDriver.opts();
		long now = HumanAutopilot.ticks();
		if (target == null) return "idle";
		if (now < engageAt) return "noticed " + name(target);
		lastFought = now;

		if (now > offsetUntil) pickBodyOffset();
		LivingEntity t = target;
		Vec3 off = bodyOffset;
		aim.lookAt(t, () -> t.position().add(off));

		// Best weapon in hand
		int weapon = Tools.bestWeaponSlot(p);
		in.selectHotbar(weapon);

		double dist = p.distanceTo(t);
		double reach = Math.min(cfg.attackReach, p.entityInteractionRange());
		float charge = p.getAttackStrengthScale(0.5f);
		double yawErr = Math.abs(Angles.wrap(Angles.yawTo(p.position(), t.position()) - p.getYRot()));
		boolean ranged = t instanceof RangedAttackMob;
		boolean creeper = t instanceof Creeper;

		// Movement like the recorded player: walk right into it (they hit from ~1.7 blocks with W still held),
		// now and then let go of W for a moment after a hit, hardly ever back-pedal (except from a creeper).
		boolean safeAhead = safeToStep(p, p.getYRot());
		boolean safeBehind = safeToStep(p, p.getYRot() + 180);
		// Archers: the recorded player sprinted straight at it and kept W held while hitting (they shoot you anyway,
		// so close in fast). Creepers: keep a little room.
		double closeTo = creeper ? reach - 0.4 : ranged ? 1.0 : 1.4;
		if (now < backOffUntil) {
			if (safeBehind) in.hold(o.keyDown);
		} else if (now < letGoUntil) {
			// W-tap: off the key for a beat, then straight back in.
		} else if (dist > closeTo) {
			if (yawErr < 50 && safeAhead) {
				in.hold(o.keyUp);
				if ((dist > 6 || (ranged && dist > 2.5)) && cfg.sprint && p.getFoodData().getFoodLevel() > 6) in.hold(o.keySprint);
				if (p.horizontalCollision && p.onGround()) in.hold(o.keyJump);
			}
		} else if (dist < 0.9 && safeBehind) {
			in.hold(o.keyDown); // right on top of us: a step back to see it
		}
		// A sidestep now and then against a zombie; never against an archer (straight in is quicker and safer).
		if (!ranged && Human.chance(0.02) && now > strafeUntil) {
			strafeDir = Human.chance(0.5) ? (Human.chance(0.5) ? 1 : -1) : 0;
			strafeUntil = now + Human.rangeInt(12, 30);
		}
		if (ranged) strafeDir = 0;
		if (now < strafeUntil && strafeDir != 0 && dist < 8) {
			float side = p.getYRot() + (strafeDir > 0 ? 90 : -90);
			if (safeToStep(p, side)) in.hold(strafeDir > 0 ? o.keyRight : o.keyLeft);
		}

		// Shield: block while our swing recharges and something is about to hit us,
		// or while a skeleton is drawing its bow.
		boolean shield = cfg.useShield && Tools.hasShieldInOffhand(p)
				&& p.getMainHandItem().getUseAnimation() == ItemUseAnimation.NONE;
		boolean arrowIncoming = ranged && t.isUsingItem() && dist > reach;
		boolean meleeIncoming = !ranged && !creeper && dist < 2.6 && charge < 0.75
				&& t instanceof Mob m && m.isAggressive();
		if (shield && (arrowIncoming || meleeIncoming)) {
			in.hold(o.keyUse);
			return "blocking " + name(t);
		}

		// Swing only at full charge, after a human-length beat, with the crosshair actually on it.
		if (charge >= cfg.minAttackCharge) {
			if (!wasCharged) {
				wasCharged = true;
				nextSwingAt = now + Human.actionDelay();
			}
		} else {
			wasCharged = false;
		}
		var hit = Minecraft.getInstance().hitResult;
		boolean onTarget = hit instanceof EntityHitResult ehr && ehr.getEntity() == t;
		boolean ready = wasCharged && now >= nextSwingAt && !p.isUsingItem();
		// Now and then a jump-crit, like the recorded player (a quarter of their hits land in the air): jump, and
		// swing on the way down. Never at a creeper, never with no room overhead.
		if (ready && critJumpUntil == 0 && !creeper && !ranged && p.onGround() && dist <= reach && onTarget
				&& !p.isInWater() && Terrain.passable(Mth.floor(p.getX()), Mth.floor(p.getY()) + 2, Mth.floor(p.getZ()))
				&& Human.chance(0.15)) {
			critJumpUntil = now + 14;
			in.hold(o.keyJump);
			return "fighting " + name(t);
		}
		if (critJumpUntil != 0) {
			boolean falling = !p.onGround() && p.getDeltaMovement().y < -0.05;
			if (now >= critJumpUntil || (p.onGround() && now > critJumpUntil - 11)) {
				critJumpUntil = 0; // came down without a chance: just hit normally
			} else if (!falling) {
				return "fighting " + name(t);
			} else {
				critJumpUntil = 0;
				if (onTarget && dist <= reach + 0.3) {
					in.click(o.keyAttack);
					wasCharged = false;
					crits++;
					pickBodyOffset();
					return "fighting " + name(t);
				}
			}
		}
		if (ready && onTarget && dist <= reach + 0.3) {
			in.click(o.keyAttack);
			wasCharged = false;
			pickBodyOffset();
			if (creeper) backOffUntil = now + Human.rangeInt(8, 14);
			else if (Human.chance(0.06)) backOffUntil = now + Human.rangeInt(3, 7);
			else if (Human.chance(0.4)) letGoUntil = now + Human.rangeInt(3, 7);
		} else if (wasCharged && now >= nextSwingAt && !onTarget && dist <= reach && aim.error() < 7 && !p.isUsingItem()
				&& now >= nextWhiffCheck) {
			// The mob stepped just as we clicked: a swing at thin air now and then, like anyone's.
			nextWhiffCheck = now + 10;
			if (Human.chance(0.08)) {
				in.click(o.keyAttack);
				wasCharged = false;
				whiffs++;
			}
		}
		return "fighting " + name(t);
	}

	/** Don't walk off a cliff or into lava while chasing something. */
	static boolean safeToStep(LocalPlayer p, float yaw) {
		double rad = Math.toRadians(yaw);
		double dx = -Math.sin(rad), dz = Math.cos(rad);
		int y = Mth.floor(p.getY() + 0.2);
		// Further ahead at a run: a sprinting body carries on for a block or two after letting go of W.
		double ahead = p.isSprinting() ? 2.6 : 1.6;
		for (double d = 0.8; d <= ahead; d += 0.6) {
			int x = Mth.floor(p.getX() + dx * d), z = Mth.floor(p.getZ() + dz * d);
			if (!Terrain.loaded(x, z)) return false;
			if (Terrain.lava(x, y, z) || Terrain.lava(x, y - 1, z)) return false;
			if (Terrain.passable(x, y, z) && !Terrain.floor(x, y - 1, z) && !Terrain.water(x, y - 1, z)
					&& Terrain.dropDepth(x, y, z) > HumanAutopilot.config().maxSafeDrop) return false;
		}
		return true;
	}

	static String name(LivingEntity e) {
		return e.getType().getDescription().getString();
	}
}
