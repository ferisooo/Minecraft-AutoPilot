package dev.humanautopilot.modules;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Where the eyes go when the hands are busy with something routine. A player turns toward a sound worth
 * knowing about (a zombie groaning behind them, a creeper's hiss, an arrow, lava popping) and, walking along,
 * glances at things on the way (an animal, a dropped item, the view) and then looks back where they're going.
 * From the recorded player: 4-7 glances per minute of walking, 35-60 degrees, 1.2-1.8 s there and back.
 */
public final class Attention implements SoundEventListener {
	/** Somewhere to look from tick {@code from} until {@code until}. */
	public record Look(Object key, Supplier<Vec3> where, long from, long until, String what, boolean alarm) {
		boolean live(long now) {
			return now >= from && now < until && (!(key instanceof Entity e) || e.isAlive());
		}
	}

	private static final Attention INSTANCE = new Attention();

	public static Attention get() {
		return INSTANCE;
	}

	/** For the test's motion meter. */
	public static long glances, soundLooks;

	private Look sound, glance;
	private long nextSoundOk, nextGlanceAt, glanceEnded = -1000;
	private long walkedAt = -1000, calmWalkAt = -1000;
	private boolean registered;
	// Heard that one already: a zombie groaning behind the wall gets a look the first time, not every time.
	private final Map<Object, Long> habituated = new HashMap<>();

	private Attention() {}

	/** Starts listening once the game's sound system is up. */
	public void ensureListening(Minecraft mc) {
		if (registered) return;
		mc.getSoundManager().addListener(this);
		registered = true;
	}

	// ------------------------------------------------------------------ hearing

	@Override
	public void onPlaySound(SoundInstance s, WeighedSoundEvents set, float range) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || s.isRelative()) return;
		String id = s.getIdentifier().getPath();
		int level = interest(id);
		// The recorded player hardly ever turns to a noise (about one in ten, and mostly by chance): animals never get
		// a look, a groan or a rattle only now and then; a hiss, an arrow or an explosion always does.
		if (level <= 1) return;
		if (level == 2 && id.endsWith(".ambient") && !Human.chance(0.25)) return;
		long now = HumanAutopilot.ticks();
		Vec3 eye = p.getEyePosition();
		Vec3 at = new Vec3(s.getX(), s.getY(), s.getZ());
		double d = at.distanceTo(eye);
		if (d < 2.0 || d > (level >= 2 ? 20 : 12)) return;
		if (level < 3 && now < nextSoundOk) return;
		if (sound != null && now < sound.until && (sound.alarm || level < 2)) return; // already turning to something
		if (inView(p, at, 30)) return; // it's right there in front of us anyway

		// Whose sound? An entity right where it played (track it, it may be walking), else just the spot.
		Entity who = null;
		List<Entity> near = mc.level.getEntities(p, new AABB(at, at).inflate(1.2),
				e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof EnderMan));
		for (Entity e : near) if (who == null || e.distanceToSqr(at) < who.distanceToSqr(at)) who = e;
		Object key = who != null ? who : BlockPos.containing(at);
		// Remembered by who made it and by what/where it was (the same groan from the same corner of the cave).
		String spot = id + "@" + (Mth.floor(at.x) >> 2) + "," + (Mth.floor(at.y) >> 2) + "," + (Mth.floor(at.z) >> 2);
		Long last = habituated.get(key), lastHere = habituated.get(spot);
		if (level < 3 && ((last != null && now - last < 20 * 75) || (lastHere != null && now - lastHere < 20 * 75))) return;
		// Behind a wall it's only a muffled noise: sometimes worth a look, often not.
		boolean seen = who != null ? p.hasLineOfSight(who) : true;
		if (!seen && !Human.chance(level >= 2 ? 0.5 : 0.25)) return;
		if (level == 1 && !Human.chance(0.6)) return;

		Entity e = who;
		Supplier<Vec3> where = e != null ? () -> e.position().add(0, e.getBbHeight() * 0.75, 0) : () -> at;
		long from = now + Human.reaction() + Human.rangeInt(1, 5); // hear, place it, then turn
		long dwell = level >= 2 ? Human.rangeInt(14, 30) : Human.rangeInt(10, 22);
		String what = describe(id) + String.format(" %.0fm away%s", d, seen ? "" : " (out of sight)");
		sound = new Look(key, where, from, from + dwell, what, level >= 2);
		habituated.put(key, now);
		habituated.put(spot, now);
		if (habituated.size() > 64) habituated.values().removeIf(t -> now - t > 20 * 120);
		nextSoundOk = now + 20L * Human.rangeInt(5, 12);
	}

	/** 0 = not worth turning for, 1 = mildly interesting (animals), 2 = worth knowing about, 3 = danger. */
	private static int interest(String id) {
		if (id.contains("enderman")) return 0; // never look one in the face
		if (id.equals("entity.creeper.primed") || id.equals("entity.tnt.primed") || id.equals("entity.generic.explode")) return 3;
		if (id.equals("entity.arrow.shoot") || id.equals("entity.arrow.hit") || id.endsWith("skeleton.shoot")
				|| id.equals("entity.stray.shoot")) return 2;
		if (id.equals("block.lava.pop") || id.equals("block.lava.ambient")) return 2;
		if (id.endsWith(".ambient") || id.endsWith(".hurt")) {
			for (String h : HOSTILE) if (id.startsWith("entity." + h + ".")) return id.endsWith(".hurt") ? 1 : 2;
			for (String a : ANIMALS) if (id.startsWith("entity." + a + ".") && id.endsWith(".ambient")) return 1;
		}
		return 0;
	}

	private static final String[] HOSTILE = {"zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray",
			"bogged", "spider", "witch", "creeper", "slime", "pillager", "vindicator", "phantom", "silverfish"};
	private static final String[] ANIMALS = {"cow", "pig", "sheep", "chicken", "villager", "wolf", "horse",
			"donkey", "goat", "rabbit", "fox", "llama", "cat", "parrot"};

	private static String describe(String id) {
		if (id.equals("entity.creeper.primed")) return "a creeper hiss";
		if (id.startsWith("block.lava")) return "lava";
		if (id.startsWith("entity.arrow") || id.endsWith(".shoot")) return "an arrow";
		if (id.equals("entity.generic.explode")) return "an explosion";
		String[] parts = id.split("\\.");
		return parts.length > 1 ? "a " + parts[1].replace('_', ' ') : id;
	}

	private static boolean inView(LocalPlayer p, Vec3 at, double deg) {
		Vec3 eye = p.getEyePosition();
		double dy = Angles.wrap(Angles.yawTo(eye, at) - p.getYRot());
		double dp = Angles.pitchTo(eye, at) - p.getXRot();
		return Math.hypot(dy, dp) < deg;
	}

	/** A sound worth turning to, if it's time to be looking at it now (null otherwise). */
	public Look soundLook(long now) {
		if (sound == null) return null;
		if (now >= sound.until) {
			sound = null;
			glanceEnded = now;
			return null;
		}
		if (!sound.live(now)) return null;
		return sound;
	}

	/** Note that a sound look actually took the eyes (counts it and logs it once). */
	public void took(Look l) {
		if (l == null || l == loggedSound) return;
		loggedSound = l;
		soundLooks++;
		HumanAutopilot.LOGGER.info("[look] heard {}: turning to look", l.what());
	}

	private Look loggedSound;

	// ------------------------------------------------------------------ glances on the way

	/**
	 * The walker asks every tick: anything to look at instead of the path? {@code calm} = an easy stretch of
	 * open ground (no edge, no water, no climbing), the only place the eyes leave the way ahead.
	 */
	public Look whileWalking(LocalPlayer p, double pathYaw, boolean calm) {
		long now = HumanAutopilot.ticks();
		walkedAt = now;
		if (calm) calmWalkAt = now;
		Look s = soundLook(now);
		if (s != null && calm) {
			took(s);
			return s;
		}
		if (glance != null && (!calm || now >= glance.until || (now >= glance.from && !glance.live(now)))) {
			glance = null;
			glanceEnded = now;
		}
		if (!calm) return null;
		if (glance != null) return glance.live(now) ? glance : null;
		if (nextGlanceAt == 0) nextGlanceAt = now + gap();
		if (now < nextGlanceAt) return null;
		nextGlanceAt = now + gap();
		glance = pick(p, pathYaw, now);
		if (glance != null) glances++;
		return glance != null && glance.live(now) ? glance : null;
	}

	/** Walking right now on an easy stretch, so the walker itself handles any looking. */
	public boolean walkerHandles(long now) {
		return now - calmWalkAt <= 2;
	}

	/** Walking right now (on any ground). */
	public boolean walking(long now) {
		return now - walkedAt <= 2;
	}

	/** The eyes just came back from a glance: the walker may still be turning back onto the path. */
	public boolean justLooked(long now) {
		return now - glanceEnded < 14;
	}

	private static long gap() {
		return 20L * Human.rangeInt(16, 40); // the recorded player: ~3-7 glances per minute of walking
	}

	/** Something on the way worth a glance: a creature or a dropped item nearby, else just the view. */
	private Look pick(LocalPlayer p, double pathYaw, long now) {
		Vec3 eye = p.getEyePosition();
		List<Entity> things = p.level().getEntities(p, p.getBoundingBox().inflate(20),
				e -> e.isAlive() && !(e instanceof EnderMan) && (e instanceof LivingEntity || e instanceof ItemEntity && e.distanceTo(p) < 10)
						&& Math.abs(Angles.wrap(Angles.yawTo(eye, e.position()) - pathYaw)) < 100
						&& !inView(p, e.position(), 12) && p.hasLineOfSight(e));
		long from = now + Human.rangeInt(0, 3);
		long until = from + Human.rangeInt(12, 30);
		if (!things.isEmpty() && Human.chance(0.7)) {
			// Closer things catch the eye more often.
			Entity best = null;
			double bestScore = Double.MAX_VALUE;
			for (Entity e : things) {
				double score = e.distanceTo(p) * Human.range(0.5, 1.5);
				if (score < bestScore) {
					bestScore = score;
					best = e;
				}
			}
			Entity e = best;
			double h = e.getBbHeight() * Human.range(0.5, 0.9);
			return new Look(e, () -> e.position().add(0, h, 0), from, until, e.getType().getDescription().getString(), false);
		}
		// Ore glinting in a wall, a glow of lava: that's where a player's eyes go (mostly in caves and tunnels).
		BlockPos shiny = Human.chance(0.75) ? shinyBlock(p, pathYaw) : null;
		if (shiny != null) {
			Vec3 c = Vec3.atCenterOf(shiny);
			String name = Terrain.lava(shiny.getX(), shiny.getY(), shiny.getZ()) ? "lava" : "ore";
			return new Look(shiny, () -> c, from, until, name, false);
		}
		// Underground there's no view, just rock: mostly keep the eyes on the way ahead.
		if (!p.level().canSeeSky(BlockPos.containing(eye)) && Human.chance(0.7)) return null;
		double yaw = Math.toRadians(pathYaw + (Human.chance(0.5) ? 1 : -1) * Human.range(40, 80));
		double pitch = Math.toRadians(Human.gauss(-10, 25));
		Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 point = eye.add(dir.scale(Human.range(10, 20)));
		return new Look(point, () -> point, from, until, "the view", false);
	}

	/** The nearest ore or lava we can actually see from here, off to the side of the way ahead (or null). */
	private static BlockPos shinyBlock(LocalPlayer p, double pathYaw) {
		Vec3 eye = p.getEyePosition();
		BlockPos at = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -10; dx <= 10; dx++) {
			for (int dy = -5; dy <= 6; dy++) {
				for (int dz = -10; dz <= 10; dz++) {
					int x = at.getX() + dx, y = at.getY() + dy, z = at.getZ() + dz;
					double d = dx * dx + dy * dy + dz * dz;
					if (d >= bestD || d > 100) continue;
					m.set(x, y, z);
					var state = p.level().getBlockState(m);
					boolean lava = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
					if (!lava && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) continue;
					Vec3 c = Vec3.atCenterOf(m);
					double rel = Math.abs(Angles.wrap(Angles.yawTo(eye, c) - pathYaw));
					if (rel < 20 || rel > 100) continue; // straight ahead we see anyway; behind is too far round
					var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, c, net.minecraft.world.level.ClipContext.Block.COLLIDER,
							lava ? net.minecraft.world.level.ClipContext.Fluid.ANY : net.minecraft.world.level.ClipContext.Fluid.NONE, p));
					if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS && !hit.getBlockPos().equals(m)) continue;
					best = m.immutable();
					bestD = d;
				}
			}
		}
		return best;
	}
}
