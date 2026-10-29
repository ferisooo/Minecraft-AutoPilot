package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

/** Staying alive: eating, running from creepers, and backing off when hurt. */
public final class SurvivalModule {
	private enum Eat { NONE, SELECT, WAIT, EATING, RESTORE }

	private Eat eat = Eat.NONE;
	private int foodSlot;
	private int prevSlot;
	private long waitUntil;
	private long eatStarted;
	private ItemStack eatingStack = ItemStack.EMPTY;
	private int startCount;
	private boolean lookedAway;
	private double eatYaw;
	private boolean retreating;
	private int fleeSide;
	private long fleeSideUntil;
	private double fleePitch = 8;

	// ---------------------------------------------------------------- creepers

	public Creeper creeperThreat(LocalPlayer p) {
		double r = HumanAutopilot.config().creeperPanicDistance;
		Creeper worst = null;
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(r))) {
			if (!c.isAlive() || p.distanceTo(c) > r) continue;
			if (c.getSwelling(1f) > 0.05f || c.getSwellDir() > 0 || c.isIgnited()) {
				if (worst == null || p.distanceTo(c) < p.distanceTo(worst)) worst = c;
			}
		}
		return worst;
	}

	public String escapeCreeper(LocalPlayer p, InputDriver in, AimController aim, Creeper c) {
		abortEating(in);
		if (Tools.hasShieldInOffhand(p) && HumanAutopilot.config().useShield && p.distanceTo(c) < 2.0
				&& c.getSwelling(1f) > 0.6f) {
			// Too close to outrun it: face it and block.
			aim.lookAt(c, () -> c.position().add(0, c.getBbHeight() * 0.5, 0));
			in.hold(InputDriver.opts().keyUse);
			return "SHIELDING from creeper";
		}
		flee(p, in, aim, c);
		return "RUNNING from creeper";
	}

	// ---------------------------------------------------------------- low health

	/** Returns the mob to back away from while health is low, or null. */
	public LivingEntity retreatThreat(LocalPlayer p) {
		var cfg = HumanAutopilot.config();
		float hp = p.getHealth();
		if (hp <= cfg.retreatAtHealth) retreating = true;
		else if (hp >= cfg.resumeAtHealth) retreating = false;
		if (!retreating && hp <= 12) {
			// Down to about half health and an archer is shooting: the recorded player didn't take that fight on,
			// they got out of its range and ate.
			for (LivingEntity e : CombatModule.hostilesAround(p, 16)) {
				if (e instanceof net.minecraft.world.entity.monster.RangedAttackMob) return e;
			}
		}
		if (!retreating) return null;
		LivingEntity nearest = null;
		int close = 0;
		for (LivingEntity e : CombatModule.hostilesAround(p, 12)) {
			if (p.distanceTo(e) < 8) close++;
			if (nearest == null || p.distanceTo(e) < p.distanceTo(nearest)) nearest = e;
		}
		// One zombie on us and a weapon in hand: turning our back just gets us hit from behind (it keeps up in a
		// cave). Finish it unless we're really low; run from groups, archers, or with bare hands.
		boolean armed = Tools.meleeDps(p.getInventory().getItem(Tools.bestWeaponSlot(p))) > 5;
		if (nearest != null && close <= 1 && armed && hp > 4 && !(nearest instanceof net.minecraft.world.entity.monster.RangedAttackMob)
				&& !(nearest instanceof Creeper)) return null;
		return nearest;
	}

	public boolean isRetreating() {
		return retreating;
	}

	public String retreat(LocalPlayer p, InputDriver in, AimController aim, LivingEntity threat) {
		flee(p, in, aim, threat);
		return "RETREATING (low health)";
	}

	private void flee(LocalPlayer p, InputDriver in, AimController aim, Entity from) {
		var o = InputDriver.opts();
		long now = HumanAutopilot.ticks();
		double away = Angles.yawTo(from.position(), p.position());
		if (now > fleeSideUntil) {
			fleeSide = Human.rangeInt(-1, 1);
			fleeSideUntil = now + Human.rangeInt(15, 35);
			fleePitch = Human.range(4, 12);
		}
		double yaw = away + fleeSide * 25;
		// Cornered (a drop straight ahead): any way off at an angle will do, not standing still.
		if (!CombatModule.safeToStep(p, (float) yaw)) {
			for (double off : new double[]{45, -45, 90, -90, 135, -135}) {
				if (CombatModule.safeToStep(p, (float) (away + off))) {
					yaw = away + off;
					break;
				}
			}
		}
		aim.lookDirection("flee", yaw, fleePitch);
		double err = Math.abs(Angles.wrap(yaw - p.getYRot()));
		if (err < 70 && CombatModule.safeToStep(p, p.getYRot())) {
			in.hold(o.keyUp);
			if (p.getFoodData().getFoodLevel() > 6) in.hold(o.keySprint);
			if (p.horizontalCollision && p.onGround()) in.hold(o.keyJump);
		} else if (CombatModule.safeToStep(p, p.getYRot() + 180)) {
			in.hold(o.keyDown); // back-pedal while turning around
		}
	}

	// ---------------------------------------------------------------- eating

	private int eatAt = -1;

	public boolean isEating() {
		return eat != Eat.NONE;
	}

	/** Whether we should start (or keep) eating now. */
	public boolean wantsToEat(LocalPlayer p) {
		if (eat != Eat.NONE) return true;
		var cfg = HumanAutopilot.config();
		int food = p.getFoodData().getFoodLevel();
		boolean hurt = p.getHealth() <= cfg.retreatAtHealth;
		// A person doesn't eat at exactly the same hunger every time.
		if (eatAt < 0) eatAt = Math.max(4, Math.min(18, cfg.eatAtFoodLevel + Human.rangeInt(-2, 2)));
		if (food > eatAt && !(hurt && food < 20)) return false;
		if (p.isCreative() || food >= 20) return false;
		return Tools.bestFoodSlot(p, food <= 6, hurt) != Tools.NONE;
	}

	public String eatTick(LocalPlayer p, InputDriver in, AimController aim) {
		var o = InputDriver.opts();
		long now = HumanAutopilot.ticks();
		var inv = p.getInventory();
		switch (eat) {
			case NONE -> {
				eatAt = -1; // roll a new hunger level for the next meal
				int food = p.getFoodData().getFoodLevel();
				foodSlot = Tools.bestFoodSlot(p, food <= 6, p.getHealth() <= HumanAutopilot.config().retreatAtHealth);
				if (foodSlot == Tools.NONE) return "no food";
				prevSlot = inv.getSelectedSlot();
				eat = Eat.SELECT;
				lookedAway = false;
			}
			case SELECT -> {
				if (in.selectHotbar(foodSlot)) {
					eat = Eat.WAIT;
					waitUntil = now + Human.actionDelay();
				}
			}
			case WAIT -> {
				avoidLookingAtBlocks(p, aim);
				if (now >= waitUntil) {
					eatingStack = inv.getItem(foodSlot).copy();
					startCount = eatingStack.getCount();
					eatStarted = now;
					eat = Eat.EATING;
				}
			}
			case EATING -> {
				avoidLookingAtBlocks(p, aim);
				ItemStack cur = inv.getItem(foodSlot);
				boolean finished = cur.getCount() < startCount || !ItemStack.isSameItem(cur, eatingStack);
				boolean gaveUp = now - eatStarted > 90 || (now - eatStarted > 12 && !p.isUsingItem());
				if (finished || gaveUp || inv.getSelectedSlot() != foodSlot) {
					eat = Eat.RESTORE;
					waitUntil = now + Human.actionDelay();
				} else {
					in.hold(o.keyUse);
				}
			}
			case RESTORE -> {
				if (now >= waitUntil && (in.selectHotbar(prevSlot) || prevSlot == foodSlot)) eat = Eat.NONE;
			}
		}
		return "eating";
	}

	private void avoidLookingAtBlocks(LocalPlayer p, AimController aim) {
		// Right-clicking a chest or door would open it instead of eating; look slightly up first.
		var hr = Minecraft.getInstance().hitResult;
		if (!lookedAway && hr != null && hr.getType() == HitResult.Type.BLOCK) {
			lookedAway = true;
			eatYaw = p.getYRot() + Human.range(-8, 8);
		}
		if (lookedAway) aim.lookDirection("eat", eatYaw, -20);
	}

	public void abortEating(InputDriver in) {
		if (eat != Eat.NONE) eat = Eat.NONE;
	}
}
