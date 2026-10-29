package dev.humanautopilot.survival;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.modules.CombatModule;
import dev.humanautopilot.pathing.Goal;
import dev.humanautopilot.pathing.Navigator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Finds a food animal, walks up to it, kills it, and grabs the meat. Leaves named and baby animals alone. */
public final class Hunter {
	public enum Result { WORKING, NONE_AROUND }

	private final Navigator nav = new Navigator();
	private LivingEntity prey;
	private Vec3 lastGoal;
	private long collectUntil;
	private Vec3 killedAt;
	private String status = "";
	private boolean sheepOnly;

	private double maxRange = 32;

	/** How far to go for an animal (a snack on the way is only worth a few steps). */
	public void setMaxRange(double r) {
		if (prey != null && r < maxRange && prey.isAlive() && Math.sqrt(prey.distanceToSqr(prey.position())) > r) prey = null;
		maxRange = r;
	}

	public void setSheepOnly(boolean b) {
		if (b && prey != null && !(prey instanceof Sheep)) prey = null;
		sheepOnly = b;
	}

	public String status() {
		return status;
	}

	public void reset() {
		prey = null;
		nav.stop();
	}

	private static boolean edible(LivingEntity e) {
		return (e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken || e instanceof Rabbit)
				&& e instanceof Animal a && !a.isBaby() && !e.hasCustomName() && e.isAlive();
	}

	public Result tick(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat) {
		long now = HumanAutopilot.ticks();

		// Just killed something: pick up what it dropped.
		if (killedAt != null) {
			if (now > collectUntil) {
				killedAt = null;
				nav.stop();
			} else {
				ItemEntity drop = null;
				for (ItemEntity it : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6, 3, 6))) {
					if (Inv.RAW_MEAT.test(it.getItem()) || it.getItem().is(net.minecraft.world.item.Items.LEATHER) || it.getItem().is(net.minecraft.tags.ItemTags.WOOL)) {
						if (drop == null || p.distanceToSqr(it) < p.distanceToSqr(drop)) drop = it;
					}
				}
				if (drop == null) {
					killedAt = null;
				} else {
					if (!nav.active()) nav.go(new Goal.Near(drop.position(), 0.7, false), false);
					nav.tick(p, in, aim);
					status = "picking up food";
					return Result.WORKING;
				}
			}
		}

		if (prey != null && !prey.isAlive()) {
			killedAt = prey.position();
			collectUntil = now + 100;
			prey = null;
			nav.stop();
			return Result.WORKING;
		}
		if (prey == null || p.distanceTo(prey) > 40) {
			prey = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(maxRange, 10, maxRange), Hunter::edible)) {
				if (sheepOnly && !(e instanceof Sheep)) continue;
				if (p.distanceTo(e) > maxRange) continue;
				if (!p.hasLineOfSight(e)) continue;
				double d = p.distanceToSqr(e);
				if (d < best) {
					best = d;
					prey = e;
				}
			}
			if (prey == null) {
				status = "no animals around";
				return Result.NONE_AROUND;
			}
		}

		double dist = p.distanceTo(prey);
		if (dist > 4.5) {
			if (lastGoal == null || !nav.active() || lastGoal.distanceTo(prey.position()) > 3) {
				lastGoal = prey.position();
				nav.go(new Goal.Near(lastGoal, 2.0, false), false);
			}
			var s = nav.tick(p, in, aim);
			if (s == Navigator.Status.FAILED) prey = null;
			status = "stalking " + prey_name();
			return Result.WORKING;
		}
		nav.stop();
		combat.hunt(prey);
		status = "hunting " + prey_name() + ": " + combat.tick(p, in, aim);
		return Result.WORKING;
	}

	private String prey_name() {
		return prey == null ? "animal" : prey.getType().getDescription().getString().toLowerCase();
	}
}
