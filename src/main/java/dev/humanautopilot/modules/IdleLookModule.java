package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.util.Human;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/** When nothing needs doing, glance at nearby animals, players, items, or just around. */
public final class IdleLookModule {
	private long nextGlanceAt;
	private long glanceUntil;
	private Supplier<Vec3> glance;
	private Object glanceKey;

	public String tick(LocalPlayer p, AimController aim) {
		long now = HumanAutopilot.ticks();
		if (glance != null && now < glanceUntil) {
			aim.lookAt(glanceKey, glance);
			return "looking around";
		}
		glance = null;
		if (now >= nextGlanceAt) {
			pick(p);
			glanceUntil = now + Human.rangeInt(14, 40);
			nextGlanceAt = glanceUntil + Human.rangeInt(50, 220);
		}
		return "idle";
	}

	private void pick(LocalPlayer p) {
		List<Entity> nearby = p.level().getEntities(p, p.getBoundingBox().inflate(14),
				// Never an enderman: looking one in the face is how you make it angry.
				e -> (e instanceof LivingEntity || e instanceof ItemEntity) && e.isAlive() && p.hasLineOfSight(e)
						&& !(e instanceof net.minecraft.world.entity.monster.EnderMan));
		if (!nearby.isEmpty() && Human.chance(0.65)) {
			Entity e = nearby.get(Human.RNG.nextInt(nearby.size()));
			Vec3 off = new Vec3(0, e.getBbHeight() * Human.range(0.5, 0.95), 0);
			glanceKey = e;
			glance = () -> e.position().add(off);
			return;
		}
		// Somewhere with a bit of room to look into: in a shaft or a tunnel nobody stares at the wall an inch away,
		// they look up the shaft or along the tunnel.
		Vec3 eye = p.getEyePosition();
		Vec3 best = null;
		double bestRoom = -1;
		for (int i = 0; i < 6; i++) {
			double yaw = Math.toRadians(p.getYRot() + (i < 3 ? Human.range(-75, 75) : Human.range(-180, 180)));
			double pitch = Math.toRadians(i < 4 ? Human.range(-18, 28) : Human.range(-70, -30));
			Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
			double room = room(p, eye, dir);
			if (room > bestRoom) {
				bestRoom = room;
				best = eye.add(dir.scale(Math.max(2, Math.min(8, room))));
			}
			if (room >= 3) break;
		}
		Vec3 point = best;
		glanceKey = point;
		glance = () -> point;
	}

	/** How far we can see that way before the first block (up to 8). */
	private static double room(LocalPlayer p, Vec3 eye, Vec3 dir) {
		var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(dir.scale(8)),
				net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? 8 : hit.getLocation().distanceTo(eye);
	}

	public void reset() {
		glance = null;
	}
}
