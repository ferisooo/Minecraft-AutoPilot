package dev.humanautopilot.record;

import dev.humanautopilot.util.Angles;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.CombatModule;
import dev.humanautopilot.pathing.Terrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Records how the human plays, so the bot can learn their style: the camera every frame, keys and
 * the cursor every tick, and what happened (items gained/used, screens, hits, deaths) with the situation
 * around it. Only while the player is in control (bot-driven moments are skipped). Nothing leaves the PC:
 * one gzipped JSON-lines file per session in .minecraft/humanautopilot-recordings/. No chat is recorded.
 *
 * Lines: {"h":...} header, {"f":[ms,yaw,pitch,cx,cy]} frame, {"t":{...}} tick, {"e":{...}} event, {"s":{...}} situation (every 10 s).
 * v2 events (from the game's own interaction calls, so only real clicks): "broke" (block, ms to break, tool, face,
 * hit point on the block, where it was relative to the feet, moving/sneaking), "place"/"use_on" (item, face, cell,
 * sneak, pitch, light there), "click" (slot clicks in menus: slot, button, type, item, carried), "recipe" (recipe-book
 * click: result, shift), "hit" (mob, distance, crit/sprint/charge), "drop" (Q), "sound" (nearby mob/danger sounds:
 * id, where, how far from the crosshair), "seen" (every 2 s: mobs and ores/lava in sight, with their angle from the
 * crosshair); "slot" events say whether the wheel or a number key was used.
 * Tick "st" bits: 1 sprinting, 2 in water, 4 underwater, 8 sleeping, 16 on a ladder/vine, 32 on fire, 64 in lava,
 * 128 riding, 256 swimming; "air" only when below full.
 */
public final class Recorder {
	private BufferedWriter out;
	private Path file;
	private long startNanos;
	private long ticks;
	private long frames;
	private int lastSlot = -1;
	private String lastScreen = "";
	private boolean wasDead;
	private int lastKeys;
	private Map<String, Integer> lastInv = new HashMap<>();
	private boolean pausedForBot;

	/** Tests: record the bot's own play too, to compare it with the player's using the same analyzer. */
	public static boolean recordBot;

	public boolean on() {
		return out != null;
	}

	/**
	 * Whose play the open file is: "bot" or "you". A file holds one of them only (the user: "make sure the bot's
	 * recordings belong to the bot so you will be able to tell mine and his apart"): the name ends -bot or -you, the
	 * header says so too, and when the other one takes over a new file starts - the builder driving for 2 s (in the
	 * player's file), or the player playing (keys or the mouse on 5 ticks in 2 s, the bot not driving at all, in the
	 * bot's). Nothing's recorded while that's being made sure of, so neither file gets the other's play.
	 */
	private String who = "you";
	private int switchWait;
	private int humanTicks;
	private boolean holding;
	private float lastYaw = Float.NaN, lastPitch;
	private boolean quiet;
	private int splits;

	public String who() {
		return who;
	}

	public Path file() {
		return file;
	}

	public void toggle() {
		if (on()) stop();
		else start();
	}

	public void start() {
		Minecraft mc = Minecraft.getInstance();
		try {
			Path dir = mc.gameDirectory.toPath().resolve("humanautopilot-recordings");
			Files.createDirectories(dir);
			who = recordBot ? "bot" : "you";
			file = dir.resolve("rec-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + who + ".jsonl.gz");
			// syncFlush: a flush really reaches the file, so a crash loses at most the last 30 s.
			out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16, true), StandardCharsets.UTF_8), 1 << 16);
		} catch (IOException e) {
			HumanAutopilot.LOGGER.warn("[rec] can't start recording", e);
			HumanAutopilot.message("§cCouldn't start recording: " + e.getMessage());
			out = null;
			return;
		}
		startNanos = System.nanoTime();
		ticks = frames = 0;
		switchWait = humanTicks = 0;
		holding = false;
		lastYaw = Float.NaN;
		pausedForBot = false;
		if (!quiet) splits = 0;
		lastSlot = -1;
		lastScreen = "";
		lastInv = mc.player != null ? inventory(mc.player) : new HashMap<>();
		var o = mc.options;
		var w = mc.getWindow();
		line(String.format(Locale.ROOT,
				"{\"h\":{\"v\":2,\"who\":\"%s\",\"start\":\"%s\",\"sensitivity\":%.4f,\"fov\":%d,\"fpsLimit\":%d,\"guiScale\":%d,\"screenW\":%d,\"screenH\":%d,\"guiW\":%d,\"guiH\":%d}}",
				who, LocalDateTime.now(), o.sensitivity().get(), o.fov().get(), o.framerateLimit().get(), w.getGuiScale(),
				w.getScreenWidth(), w.getScreenHeight(), w.getGuiScaledWidth(), w.getGuiScaledHeight()));
		if (!listening) {
			mc.getSoundManager().addListener(this::heard);
			listening = true;
		}
		if (!quiet) {
			HumanAutopilot.message(recordBot ? "§c● Recording§r the bot building (numpad 8 to stop); saved on this PC."
					: "§c● Recording§r your play (numpad 8 to stop). The bot too if it starts building; saved on this PC.");
		}
		HumanAutopilot.LOGGER.info("[rec] recording to {} ({})", file, who.equals("bot") ? "the bot" : "the player");
	}

	public void stop() {
		if (out == null) return;
		try {
			out.close();
		} catch (IOException ignored) {
		}
		out = null;
		long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
		if (!quiet) {
			HumanAutopilot.message(String.format("Recording saved: %d min, %s", secs / 60, file.getFileName())
					+ (splits > 0 ? " (+" + splits + " more: a new file each time you or the bot took over)" : ""));
		}
		HumanAutopilot.LOGGER.info("[rec] saved {} ({} s, {} frames, {} ticks)", file, secs, frames, ticks);
	}

	private double ms() {
		return (System.nanoTime() - startNanos) / 1e6;
	}

	private void line(String s) {
		if (out == null) return;
		try {
			out.write(s);
			out.write('\n');
		} catch (IOException e) {
			HumanAutopilot.LOGGER.warn("[rec] write failed, stopping", e);
			stop();
		}
	}

	/** Every rendered frame: where the camera points and (in a menu) where the cursor is. */
	public void frame(Minecraft mc, boolean botDriving) {
		if (out == null || mc.player == null || (botDriving && !recordBot) || holding) return;
		double cx = -1, cy = -1;
		if (mc.screen != null) {
			var w = mc.getWindow();
			cx = mc.mouseHandler.xpos() * w.getGuiScaledWidth() / w.getScreenWidth();
			cy = mc.mouseHandler.ypos() * w.getGuiScaledHeight() / w.getScreenHeight();
		}
		frames++;
		line(String.format(Locale.ROOT, "{\"f\":[%.1f,%.3f,%.3f,%.1f,%.1f]}", ms(), mc.player.getYRot(), mc.player.getXRot(), cx, cy));
	}

	/**
	 * Has the other one taken over (see who)? Then on into a file of its own. True while making sure (the bot's file and
	 * the player doing something): nothing gets recorded then.
	 */
	private boolean whoChanges(Minecraft mc, LocalPlayer p, boolean botDriving) {
		float yaw = p.getYRot(), pitch = p.getXRot();
		boolean turned = !Float.isNaN(lastYaw) && (Math.abs(yaw - lastYaw) > 0.05f || Math.abs(pitch - lastPitch) > 0.05f);
		lastYaw = yaw;
		lastPitch = pitch;
		if (who.equals("you")) {
			// The player's file: the builder driving for 2 s (its ticks are left out meanwhile, as for any of the bot's).
			if (botDriving && dev.humanautopilot.modules.BoxFillModule.get().on()) {
				if (++switchWait >= 40) switchTo("bot");
			} else {
				switchWait = 0;
			}
			return false;
		}
		// The bot's file: the player playing, the bot not driving at all.
		if (botDriving) {
			switchWait = humanTicks = 0;
			return false;
		}
		var o = mc.options;
		boolean input = mc.screen == null && (turned || o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
				|| o.keyJump.isDown() || o.keyShift.isDown() || o.keyAttack.isDown() || o.keyUse.isDown());
		if (input) humanTicks++;
		if (switchWait == 0 && !input) return false;
		if (++switchWait < 40) return true;
		boolean theirs = humanTicks >= 5;
		switchWait = humanTicks = 0;
		if (theirs) switchTo("you");
		return false;
	}

	/** On into a new file of the other one's (quietly but for a line in chat: the recording goes on). */
	private void switchTo(String w) {
		Path was = file;
		quiet = true;
		stop();
		recordBot = w.equals("bot");
		start();
		quiet = false;
		if (out == null) return;
		splits++;
		if (recordBot) event("bot", "\"recording the builder\"");
		HumanAutopilot.message(recordBot ? "Recording the §ebot§r in a file of its own (" + file.getFileName() + ")"
				: "Recording §eyou§r in a file of your own (" + file.getFileName() + ")");
		HumanAutopilot.LOGGER.info("[rec] {} took over: {} saved, on into {}", recordBot ? "the bot" : "the player", was.getFileName(), file.getFileName());
	}

	/** Every game tick: keys, slot, target, plus events and (every 10 s) the situation. */
	public void tick(Minecraft mc, boolean botDriving) {
		if (out == null) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		holding = whoChanges(mc, p, botDriving);
		if (out == null || holding) return;
		if (recordBot) botDriving = false;
		if (botDriving != pausedForBot) {
			pausedForBot = botDriving;
			event("bot", botDriving ? "\"took over\"" : "\"handed back\"");
		}
		if (botDriving) {
			lastInv = inventory(p); // don't count what the bot gathered as the player's doing
			return;
		}
		ticks++;
		var o = mc.options;
		int keys = (o.keyUp.isDown() ? 1 : 0) | (o.keyDown.isDown() ? 2 : 0) | (o.keyLeft.isDown() ? 4 : 0) | (o.keyRight.isDown() ? 8 : 0)
				| (o.keyJump.isDown() ? 16 : 0) | (o.keyShift.isDown() ? 32 : 0) | (o.keySprint.isDown() ? 64 : 0)
				| (o.keyAttack.isDown() ? 128 : 0) | (o.keyUse.isDown() ? 256 : 0);
		int slot = p.getInventory().getSelectedSlot();
		// What the body is doing (sprint is read from the player, so double-tap-W sprints count too).
		int st = (p.isSprinting() ? 1 : 0) | (p.isInWater() ? 2 : 0) | (p.isUnderWater() ? 4 : 0) | (p.isSleeping() ? 8 : 0)
				| (p.onClimbable() ? 16 : 0) | (p.isOnFire() ? 32 : 0) | (p.isInLava() ? 64 : 0) | (p.isPassenger() ? 128 : 0)
				| (p.isSwimming() ? 256 : 0);
		String air = p.getAirSupply() < p.getMaxAirSupply() ? ",\"air\":" + p.getAirSupply() : "";
		line(String.format(Locale.ROOT, "{\"t\":{\"ms\":%.1f,\"k\":%d,\"st\":%d,\"slot\":%d,\"ground\":%b,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"hp\":%.1f,\"food\":%d%s,\"aim\":%s}}",
				ms(), keys, st, slot, p.onGround(), p.getX(), p.getY(), p.getZ(), p.getHealth(), p.getFoodData().getFoodLevel(), air, target(mc)));

		// Events: key presses, hotbar switches, screens, hits, deaths, items gained / used.
		int pressed = keys & ~lastKeys;
		if ((pressed & 128) != 0) event("attack", target(mc));
		if ((pressed & 256) != 0) event("use", "{\"hand\":\"" + id(p.getMainHandItem()) + "\",\"at\":" + target(mc) + "}");
		lastKeys = keys;
		if (slot != lastSlot) {
			boolean numberKey = false;
			for (var k : o.keyHotbarSlots) numberKey |= k.isDown();
			if (lastSlot >= 0) event("slot", "{\"from\":" + lastSlot + ",\"to\":" + slot + ",\"item\":\"" + id(p.getInventory().getItem(slot))
					+ "\",\"via\":\"" + (numberKey ? "key" : "wheel") + "\"}");
			lastSlot = slot;
		}
		boolean dropDown = o.keyDrop.isDown();
		if (dropDown && !dropWasDown && mc.screen == null) {
			boolean ctrl = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL);
			event("drop", "{\"item\":\"" + id(p.getMainHandItem()) + "\",\"n\":" + p.getMainHandItem().getCount() + ",\"stack\":" + ctrl + "}");
		}
		dropWasDown = dropDown;
		if (pendingRecipeAt > 0 && ticks >= pendingRecipeAt) {
			// The book has laid the recipe out by now: what's in the result slot is what they clicked.
			pendingRecipeAt = 0;
			var menu = p.containerMenu;
			String result = menu != null && menu.slots.size() > 0 ? id(menu.getSlot(0).getItem()) : "?";
			event("recipe", "{\"result\":\"" + result + "\",\"shift\":" + pendingRecipeShift + ",\"screen\":\"" + screenName(mc) + "\"}");
		}
		if (ticks % 40 == 7 && mc.screen == null) seen(mc, p);
		String screen = mc.screen == null ? "" : mc.screen.getClass().getSimpleName();
		if (!screen.equals(lastScreen)) {
			event("screen", "\"" + (screen.isEmpty() ? "closed " + lastScreen : screen) + "\"");
			lastScreen = screen;
		}
		if (p.hurtTime == 9) {
			var src = p.getLastDamageSource();
			event("hurt", "{\"hp\":" + (int) p.getHealth() + ",\"by\":\"" + (src == null ? "?" : src.getMsgId())
					+ (src != null && src.getEntity() != null ? "/" + BuiltInRegistries.ENTITY_TYPE.getKey(src.getEntity().getType()).getPath() : "") + "\"}");
		}
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) event("death", "{\"y\":" + p.getBlockY() + "}");
		wasDead = dead;
		if (ticks % 5 == 0 && !(mc.screen instanceof AbstractContainerScreen<?> && ticks % 20 != 0)) {
			Map<String, Integer> inv = inventory(p);
			StringBuilder diff = new StringBuilder();
			for (var e : inv.entrySet()) {
				int d = e.getValue() - lastInv.getOrDefault(e.getKey(), 0);
				if (d != 0) diff.append(diff.isEmpty() ? "" : ",").append('"').append(e.getKey()).append("\":").append(d);
			}
			for (var e : lastInv.entrySet()) {
				if (!inv.containsKey(e.getKey())) diff.append(diff.isEmpty() ? "" : ",").append('"').append(e.getKey()).append("\":").append(-e.getValue());
			}
			if (!diff.isEmpty()) event("items", "{" + diff + "}");
			lastInv = inv;
		}
		if (ticks % 200 == 1) situation(mc, p);
		if (ticks % 600 == 0) {
			try {
				out.flush();
			} catch (IOException ignored) {
			}
		}
	}

	// ------------------------------------------------------------------ v2: the hands, the ears, the eyes

	private boolean listening, dropWasDown;
	private BlockPos digPos;
	private double digStartMs;
	private String digInfo;
	private long pendingRecipeAt;
	private boolean pendingRecipeShift;
	private final Map<String, Double> lastSound = new HashMap<>();

	/** Recording the player right now (not paused while the bot drives). */
	private boolean active() {
		return out != null && (!pausedForBot || recordBot) && Minecraft.getInstance().player != null;
	}

	private static String screenName(Minecraft mc) {
		return mc.screen == null ? "" : mc.screen.getClass().getSimpleName();
	}

	private static String rel(LocalPlayer p, BlockPos b) {
		return "[" + (b.getX() - p.getBlockX()) + "," + (b.getY() - p.getBlockY()) + "," + (b.getZ() - p.getBlockZ()) + "]";
	}

	/** Degrees between the crosshair and a point. */
	private static double offCrosshair(LocalPlayer p, Vec3 at) {
		Vec3 eye = p.getEyePosition();
		double dy = Angles.wrap(Angles.yawTo(eye, at) - p.getYRot());
		double dp = Angles.pitchTo(eye, at) - p.getXRot();
		return Math.hypot(dy, dp);
	}

	public void onDigStart(BlockPos pos, Direction face) {
		if (!active() || pos.equals(digPos)) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		digPos = pos.immutable();
		digStartMs = ms();
		Vec3 hit = mc.hitResult != null ? mc.hitResult.getLocation().subtract(Vec3.atLowerCornerOf(pos)) : Vec3.ZERO;
		digInfo = String.format(Locale.ROOT, "\"face\":\"%s\",\"hit\":[%.2f,%.2f,%.2f],\"rel\":%s,\"tool\":\"%s\",\"pitch\":%.1f,\"moving\":%b,\"sneak\":%b",
				face.getName(), hit.x, hit.y, hit.z, rel(p, pos), id(p.getMainHandItem()), p.getXRot(),
				p.getDeltaMovement().horizontalDistance() > 0.05, p.isShiftKeyDown());
	}

	public void onBroke(BlockPos pos) {
		if (!active()) return;
		Minecraft mc = Minecraft.getInstance();
		String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath();
		boolean known = pos.equals(digPos) && digInfo != null;
		event("broke", "{\"block\":\"" + block + "\",\"ms\":" + (known ? (int) (ms() - digStartMs) : 0)
				+ (known ? "," + digInfo : ",\"rel\":" + rel(mc.player, pos) + ",\"tool\":\"" + id(mc.player.getMainHandItem()) + "\"") + "}");
		digPos = null;
	}

	public void onUseOn(InteractionHand hand, BlockHitResult hit) {
		if (!active()) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ItemStack held = p.getItemInHand(hand);
		BlockPos at = hit.getBlockPos();
		var st = mc.level.getBlockState(at);
		boolean place = held.getItem() instanceof BlockItem;
		BlockPos cell = st.canBeReplaced() ? at : at.relative(hit.getDirection());
		// At an edge (bridging): nothing under the block in front of the feet.
		boolean edge = mc.level.getBlockState(p.blockPosition().relative(p.getDirection()).below()).isAir();
		event(place ? "place" : "use_on", String.format(Locale.ROOT,
				"{\"item\":\"%s\",\"hand\":\"%s\",\"on\":\"%s\",\"face\":\"%s\",\"rel\":%s,\"sneak\":%b,\"pitch\":%.1f,\"ground\":%b,\"edge\":%b,\"blockLight\":%d,\"skyLight\":%d}",
				id(held), hand == InteractionHand.MAIN_HAND ? "main" : "off",
				BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), hit.getDirection().getName(), rel(p, cell),
				p.isShiftKeyDown(), p.getXRot(), p.onGround(), edge,
				mc.level.getBrightness(LightLayer.BLOCK, cell), mc.level.getBrightness(LightLayer.SKY, cell)));
	}

	public void onSlotClick(int slot, int button, ContainerInput type) {
		if (!active()) return;
		Minecraft mc = Minecraft.getInstance();
		var menu = mc.player.containerMenu;
		ItemStack in = slot >= 0 && slot < menu.slots.size() ? menu.getSlot(slot).getItem() : ItemStack.EMPTY;
		event("click", String.format(Locale.ROOT, "{\"screen\":\"%s\",\"slot\":%d,\"button\":%d,\"type\":\"%s\",\"item\":\"%s\",\"n\":%d,\"carried\":\"%s\"}",
				screenName(mc), slot, button, type.name().toLowerCase(Locale.ROOT), id(in), in.getCount(), id(menu.getCarried())));
	}

	public void onRecipe(boolean shift) {
		if (!active()) return;
		pendingRecipeAt = ticks + 3;
		pendingRecipeShift = shift;
	}

	public void onHit(Entity target) {
		if (!active()) return;
		LocalPlayer p = Minecraft.getInstance().player;
		boolean crit = !p.onGround() && p.fallDistance > 0 && p.getDeltaMovement().y < 0 && !p.onClimbable() && !p.isInWater();
		event("hit", String.format(Locale.ROOT, "{\"mob\":\"%s\",\"d\":%.1f,\"crit\":%b,\"sprint\":%b,\"charge\":%.2f,\"w\":%b,\"hp\":%.1f}",
				BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath(), p.distanceTo(target), crit, p.isSprinting(),
				p.getAttackStrengthScale(0.5f), Minecraft.getInstance().options.keyUp.isDown(), p.getHealth()));
	}

	/** Mob noises, arrows, explosions, lava near us: did they turn to look, and how quickly? */
	private void heard(SoundInstance s, WeighedSoundEvents set, float range) {
		if (!active() || s.isRelative()) return;
		SoundSource src = s.getSource();
		String sid = s.getIdentifier().getPath();
		boolean danger = sid.startsWith("block.lava") || sid.equals("entity.generic.explode") || sid.startsWith("entity.arrow")
				|| sid.equals("entity.creeper.primed") || sid.equals("entity.tnt.primed");
		if (src != SoundSource.HOSTILE && src != SoundSource.NEUTRAL && !danger) return;
		if (src == SoundSource.NEUTRAL && (sid.endsWith(".step") || sid.endsWith(".swim"))) return; // animals walking about: noise
		LocalPlayer p = Minecraft.getInstance().player;
		Vec3 at = new Vec3(s.getX(), s.getY(), s.getZ());
		double d = at.distanceTo(p.getEyePosition());
		if (d > 24 || d < 1.0) return;
		double now = ms();
		Double last = lastSound.get(sid);
		if (last != null && now - last < 400) return;
		lastSound.put(sid, now);
		event("sound", String.format(Locale.ROOT, "{\"id\":\"%s\",\"rel\":[%.1f,%.1f,%.1f],\"d\":%.1f,\"off\":%.0f}",
				sid, at.x - p.getX(), at.y - p.getY(), at.z - p.getZ(), d, offCrosshair(p, at)));
	}

	/** Every 2 s: the mobs and the ores / lava they could see, and how far each was from the crosshair. */
	private void seen(Minecraft mc, LocalPlayer p) {
		StringBuilder mobs = new StringBuilder();
		List<Entity> list = mc.level.getEntities(p, p.getBoundingBox().inflate(24), e -> (e instanceof LivingEntity
				|| e instanceof ItemEntity) && e.isAlive() && p.hasLineOfSight(e));
		list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
		for (int i = 0; i < Math.min(12, list.size()); i++) {
			Entity e = list.get(i);
			String type = e instanceof ItemEntity ie ? "item:" + id(ie.getItem()) : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
			mobs.append(mobs.isEmpty() ? "" : ",").append(String.format(Locale.ROOT, "[\"%s\",%.1f,%.1f,%.1f,%.0f]", type,
					e.getX() - p.getX(), e.getY() - p.getY(), e.getZ() - p.getZ(), offCrosshair(p, e.position().add(0, e.getBbHeight() * 0.5, 0))));
		}
		StringBuilder ores = new StringBuilder();
		int found = 0;
		Vec3 eye = p.getEyePosition();
		BlockPos c = p.blockPosition();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -8; dx <= 8 && found < 10; dx++) {
			for (int dy = -6; dy <= 8 && found < 10; dy++) {
				for (int dz = -8; dz <= 8 && found < 10; dz++) {
					m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
					var st = mc.level.getBlockState(m);
					boolean lava = st.getFluidState().is(FluidTags.LAVA) && st.getFluidState().isSource();
					String bid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
					if (!lava && !bid.endsWith("_ore")) continue;
					Vec3 center = Vec3.atCenterOf(m);
					var hit = mc.level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER,
							lava ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, p));
					if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(m)) continue;
					ores.append(ores.isEmpty() ? "" : ",").append(String.format(Locale.ROOT, "[\"%s\",%d,%d,%d,%.0f]", lava ? "lava" : bid, dx, dy, dz, offCrosshair(p, center)));
					found++;
				}
			}
		}
		if (mobs.isEmpty() && ores.isEmpty()) return;
		event("seen", "{\"mobs\":[" + mobs + "],\"ores\":[" + ores + "]}");
	}

	private void event(String kind, String json) {
		line(String.format(Locale.ROOT, "{\"e\":{\"ms\":%.1f,\"kind\":\"%s\",\"data\":%s}}", ms(), kind, json));
	}

	/** The bigger picture every 10 s: what a player has in mind when choosing what to do. */
	private void situation(Minecraft mc, LocalPlayer p) {
		var lvl = mc.level;
		int surface = Terrain.surfaceY(p.getBlockX(), p.getBlockZ());
		Map<String, Integer> mobs = new java.util.TreeMap<>();
		for (var e : CombatModule.hostilesAround(p, 16)) mobs.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1, Integer::sum);
		StringBuilder inv = new StringBuilder();
		for (var e : new java.util.TreeMap<>(inventory(p)).entrySet())
			inv.append(inv.isEmpty() ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
		StringBuilder armor = new StringBuilder();
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
				net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
				net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
			ItemStack s = p.getItemBySlot(slot);
			if (!s.isEmpty()) armor.append(armor.isEmpty() ? "" : ",").append('"').append(id(s)).append('"');
		}
		line(String.format(Locale.ROOT,
				"{\"s\":{\"ms\":%.1f,\"dayTime\":%d,\"dark\":%b,\"y\":%d,\"surfaceY\":%d,\"biome\":\"%s\",\"rain\":%b,\"sky\":%b,\"dim\":\"%s\",\"skyLight\":%d,\"blockLight\":%d,\"hp\":%.1f,\"food\":%d,\"mobs\":%s,\"worn\":[%s],\"inv\":{%s}}}",
				ms(), lvl.getOverworldClockTime() % 24000, lvl.isDarkOutside(), p.getBlockY(), surface,
				lvl.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), lvl.isRaining(),
				lvl.canSeeSky(BlockPos.containing(p.getEyePosition())), lvl.dimension().identifier().getPath(),
				lvl.getBrightness(net.minecraft.world.level.LightLayer.SKY, p.blockPosition()),
				lvl.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p.blockPosition()),
				p.getHealth(), p.getFoodData().getFoodLevel(), mapJson(mobs), armor, inv));
	}

	private static String mapJson(Map<String, Integer> m) {
		StringBuilder b = new StringBuilder("{");
		for (var e : m.entrySet()) b.append(b.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue());
		return b.append('}').toString();
	}

	private static Map<String, Integer> inventory(LocalPlayer p) {
		Map<String, Integer> m = new HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty()) m.merge(id(s), s.getCount(), Integer::sum);
		}
		return m;
	}

	private static String id(ItemStack s) {
		return s.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	/** What the crosshair is on: a block (id + distance), a mob, or nothing. */
	private static String target(Minecraft mc) {
		HitResult hr = mc.hitResult;
		LocalPlayer p = mc.player;
		if (hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) {
			return String.format(Locale.ROOT, "{\"block\":\"%s\",\"d\":%.1f}",
					BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b.getBlockPos()).getBlock()).getPath(), Math.sqrt(p.getEyePosition().distanceToSqr(hr.getLocation())));
		}
		if (hr instanceof EntityHitResult e) {
			return String.format(Locale.ROOT, "{\"mob\":\"%s\",\"d\":%.1f}",
					BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).getPath(), Math.sqrt(p.getEyePosition().distanceToSqr(hr.getLocation())));
		}
		return "null";
	}
}
