package dev.humanautopilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.humanautopilot.modules.Attention;
import dev.humanautopilot.modules.BoxFillModule;
import dev.humanautopilot.modules.CombatModule;
import dev.humanautopilot.modules.IdleLookModule;
import dev.humanautopilot.modules.MiningModule;
import dev.humanautopilot.modules.SurvivalModule;
import dev.humanautopilot.survival.SurvivalPlanner;
import dev.humanautopilot.pathing.Goal;
import dev.humanautopilot.pathing.Navigator;
import dev.humanautopilot.util.Human;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class HumanAutopilot implements ClientModInitializer {
	public static final String MOD_ID = "humanautopilot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static long ticks;

	private static final InputDriver input = new InputDriver();
	private static final AimController aim = new AimController();
	private static final Navigator gotoNav = new Navigator();
	private static final Navigator wanderNav = new Navigator();
	private static final CombatModule combat = new CombatModule();
	private static final SurvivalModule survival = new SurvivalModule();
	private static final MiningModule mining = new MiningModule();
	private static final IdleLookModule idle = new IdleLookModule();
	private static final SurvivalPlanner planner = new SurvivalPlanner();
	private static final dev.humanautopilot.record.Recorder recorder = new dev.humanautopilot.record.Recorder();
	private static final dev.humanautopilot.survival.BagPeek peek = new dev.humanautopilot.survival.BagPeek();
	private static long nextPeekAt;

	// Feature toggles
	private static boolean pathing;
	private static boolean combatOn;
	private static boolean miningOn;
	private static boolean eatOn;
	private static boolean idleOn;
	private static boolean hudOn;
	private static boolean survivalOn;

	private static KeyMapping keyPanic, keyGoto, keyCombat, keyMining, keyEat, keyIdle, keyHud, keySurvival, keyRecord, keyBox;
	private static KeyMapping keyMenu, keyRotate, keyPause;
	private static final java.util.Map<String, KeyMapping> keysByName = new java.util.HashMap<>();

	private enum Owner { NONE, SAFETY, EAT, COMBAT, LOOK, PAUSE, BOX, SURVIVAL, MINING, GOTO, WANDER, IDLE }

	private static Owner lastOwner = Owner.NONE;
	private static String status = "off";
	private static long nextPauseAt;
	private static long pauseUntil;
	private static long respawnAt;
	private static int strayScreenTicks;
	/** When a chat screen was last open (a command typed: the menu that follows is the player's own). */
	private static long chatOpenAt = -1000;
	private static double wanderHeading = Double.NaN;
	private static boolean deathNoted;
	private static final dev.humanautopilot.survival.GuiMouse deathMouse = new dev.humanautopilot.survival.GuiMouse();
	private static double respawnJitterX, respawnJitterY;
	private static LivingEntity hitBy;
	private static long lookAtHitterFrom, lookAtHitterUntil;

	public static Config config() {
		return config;
	}

	public static long ticks() {
		return ticks;
	}

	public static void message(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.getChat().addClientSystemMessage(Component.literal("§6[Autopilot]§r " + text));
		}
	}

	@Override
	public void onInitializeClient() {
		config = Config.load();
		combatOn = config.startCombat;
		eatOn = config.startAutoEat;
		idleOn = config.startIdleLook;
		hudOn = config.showHud;

		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		keyPanic = key("panic", GLFW.GLFW_KEY_KP_0, cat);
		keyGoto = key("goto", GLFW.GLFW_KEY_KP_1, cat);
		keyCombat = key("combat", GLFW.GLFW_KEY_KP_2, cat);
		keyMining = key("mining", GLFW.GLFW_KEY_KP_3, cat);
		keyEat = key("eat", GLFW.GLFW_KEY_KP_4, cat);
		keyIdle = key("idle", GLFW.GLFW_KEY_KP_5, cat);
		keyHud = key("hud", GLFW.GLFW_KEY_INSERT, cat);
		keySurvival = key("survival", GLFW.GLFW_KEY_KP_7, cat);
		keyRecord = key("record", GLFW.GLFW_KEY_KP_8, cat);
		keyBox = key("box", GLFW.GLFW_KEY_KP_6, cat);
		keyMenu = key("menu", GLFW.GLFW_KEY_KP_9, cat);
		keyRotate = key("rotate", GLFW.GLFW_KEY_R, cat);
		keyPause = key("pause", GLFW.GLFW_KEY_KP_DECIMAL, cat);
		dev.humanautopilot.net.BoxLink.init();
		dev.humanautopilot.net.BuildLink.init();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> tick());
		// Close the recording cleanly when the game shuts down.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> recorder.stop());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
			dispatcher.register(literal("goto")
					.then(literal("stop").executes(c -> {
						gotoNav.stop();
						feedback(c, "Stopped.");
						return 1;
					}))
					// /goto x z   or   /goto x y z
					.then(argument("x", IntegerArgumentType.integer())
							.then(argument("a", IntegerArgumentType.integer())
									.executes(c -> startGoto(c, new Goal.XZ(IntegerArgumentType.getInteger(c, "x"),
											IntegerArgumentType.getInteger(c, "a"))))
									.then(argument("b", IntegerArgumentType.integer())
											.executes(c -> startGoto(c, new Goal.Block(IntegerArgumentType.getInteger(c, "x"),
													IntegerArgumentType.getInteger(c, "a"), IntegerArgumentType.getInteger(c, "b"))))))));
			dispatcher.register(literal("automine")
					.executes(c -> startMining(c, String.join(" ", config.defaultOres)))
					.then(literal("stop").executes(c -> {
						miningOn = false;
						mining.stop();
						feedback(c, "Mining stopped. Mined " + mining.minedCount() + " ore(s).");
						return 1;
					}))
					.then(argument("ores", StringArgumentType.greedyString())
							.suggests((c, b) -> {
								for (MiningModule.Ore o : MiningModule.Ore.values()) b.suggest(o.name().toLowerCase(Locale.ROOT));
								return b.buildFuture();
							})
							.executes(c -> startMining(c, StringArgumentType.getString(c, "ores")))));
			dispatcher.register(literal("autopilot")
					.executes(c -> {
						feedback(c, statusReport());
						return 1;
					})
					.then(literal("reload").executes(c -> {
						config = Config.load();
						feedback(c, "Config reloaded from config/humanautopilot.json");
						return 1;
					}))
						.then(literal("panic").executes(c -> {
						panic();
						return 1;
					}))
					.then(literal("box")
							.executes(c -> {
								feedback(c, boxReport());
								return 1;
							})
							.then(literal("on").executes(c -> {
								setBoxFill(true);
								return 1;
							}))
							.then(literal("off").executes(c -> {
								setBoxFill(false);
								return 1;
							}))
							.then(literal("clear").executes(c -> {
								BoxFillModule.get().localBox(null);
								feedback(c, "Local box cleared.");
								return 1;
							}))
							// /autopilot box set x1 y1 z1 x2 y2 z2 blocks  (singleplayer: no plugin to tell us)
							.then(literal("set").then(argument("x1", IntegerArgumentType.integer())
									.then(argument("y1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
									.then(argument("x2", IntegerArgumentType.integer()).then(argument("y2", IntegerArgumentType.integer())
									.then(argument("z2", IntegerArgumentType.integer())
									.then(argument("blocks", StringArgumentType.greedyString()).executes(c -> {
										int x1 = IntegerArgumentType.getInteger(c, "x1"), y1 = IntegerArgumentType.getInteger(c, "y1");
										int z1 = IntegerArgumentType.getInteger(c, "z1"), x2 = IntegerArgumentType.getInteger(c, "x2");
										int y2 = IntegerArgumentType.getInteger(c, "y2"), z2 = IntegerArgumentType.getInteger(c, "z2");
										setLocalBox(x1, y1, z1, x2, y2, z2, StringArgumentType.getString(c, "blocks"));
										feedback(c, "Box set: " + BoxFillModule.get().box().describe());
										return 1;
									})))))))))));
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (g, delta) -> renderHud(g));
		// The bot's own cursor, drawn over menus it is working in.
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> {
					if (botOwnsScreen()) dev.humanautopilot.survival.GuiMouse.draw(g);
				}));
	}

	private static KeyMapping key(String name, int glfw, KeyMapping.Category cat) {
		KeyMapping k = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + MOD_ID + "." + name, InputConstants.Type.KEYSYM, glfw, cat));
		keysByName.put(name, k);
		return k;
	}

	/** What one of our keys is bound to, for messages ("Numpad 9"). */
	public static String keyName(String name) {
		KeyMapping k = keysByName.get(name);
		return k == null ? name : keyName(k);
	}

	private static void feedback(CommandContext<FabricClientCommandSource> c, String text) {
		c.getSource().sendFeedback(Component.literal("§6[Autopilot]§r " + text));
	}

	private static int startGoto(CommandContext<FabricClientCommandSource> c, Goal goal) {
		gotoGoal(goal);
		feedback(c, "Walking to " + goal.describe() + ". Press " + keyName(keyPanic) + " to stop everything.");
		return 1;
	}

	private static int startMining(CommandContext<FabricClientCommandSource> c, String list) {
		Set<MiningModule.Ore> ores = EnumSet.noneOf(MiningModule.Ore.class);
		List<String> unknown = new ArrayList<>();
		for (String s : list.trim().split("[\\s,]+")) {
			if (s.isEmpty()) continue;
			MiningModule.Ore o = MiningModule.Ore.parse(s);
			if (o == null) unknown.add(s);
			else ores.add(o);
		}
		if (!unknown.isEmpty()) {
			c.getSource().sendError(Component.literal("Unknown ore(s): " + String.join(", ", unknown)
					+ ". Try: coal iron copper gold redstone lapis diamond emerald quartz debris"));
			return 0;
		}
		if (ores.isEmpty()) return 0;
		mine(ores);
		feedback(c, "Mining " + ores.toString().toLowerCase(Locale.ROOT) + ". Toggle with " + keyName(keyMining)
				+ ", stop everything with " + keyName(keyPanic) + ".");
		return 1;
	}

	// ---- Public API (used by the commands and by the automated in-game tests)

	/** Parkour mini-game on/off (ParkourModule): climb the course round here to the top. */
	public static void setParkour(boolean on) {
		var pk = dev.humanautopilot.modules.ParkourModule.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (on && p != null) {
			pk.start(p);
			message("Parkour §aON§r: climbing to the top. Numpad 0 stops everything.");
		} else {
			pk.stop();
			if (!on) message("Parkour " + onOff(false));
		}
	}

	/** The ladder mini-game on/off (LadderModule): up the ladder round here to the top, and hold on there. */
	public static void setLadder(boolean on) {
		var ld = dev.humanautopilot.modules.LadderModule.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (on && p != null) {
			dev.humanautopilot.modules.ParkourModule.get().stop();
			dev.humanautopilot.modules.FarmModule.get().stop();
			ld.start(p);
			message("Ladder game §aON§r: up the ladder to the top. Numpad 0 stops everything.");
		} else {
			ld.stop();
			if (!on) message("Ladder game " + onOff(false));
		}
	}

	public static boolean isLadderOn() {
		return dev.humanautopilot.modules.LadderModule.get().on();
	}

	/** The one block mini-game on/off (OneBlockModule): mine the block, build out, make a bed and sleep in it. */
	public static void setOneBlock(boolean on) {
		var ob = dev.humanautopilot.modules.OneBlockModule.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (on && p != null) {
			dev.humanautopilot.modules.ParkourModule.get().stop();
			dev.humanautopilot.modules.FarmModule.get().stop();
			dev.humanautopilot.modules.LadderModule.get().stop();
			ob.start(p);
			message("One Block §aON§r: mining the block, building out, then a bed and sleep. Numpad 0 stops everything.");
		} else {
			ob.stop();
			if (!on) message("One Block " + onOff(false));
		}
	}

	public static boolean isOneBlockOn() {
		return dev.humanautopilot.modules.OneBlockModule.get().on();
	}

	/** The farm mini-game on/off (FarmModule): snowball the field ripe, again and again. */
	public static void setFarm(boolean on) {
		var fm = dev.humanautopilot.modules.FarmModule.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (on && p != null) {
			dev.humanautopilot.modules.ParkourModule.get().stop();
			fm.start(p);
			message("Farm game §aON§r: snowballing the field ripe. Numpad 0 stops everything.");
		} else {
			fm.stop();
			if (!on) message("Farm game " + onOff(false));
		}
	}

	public static boolean isFarmOn() {
		return dev.humanautopilot.modules.FarmModule.get().on();
	}

	public static boolean isParkourOn() {
		return dev.humanautopilot.modules.ParkourModule.get().on();
	}

	public static void gotoGoal(Goal goal) {
		gotoNav.goFar(goal, config.breakBlocksForGoto);
		pathing = true;
	}

	public static void mine(Set<MiningModule.Ore> ores) {
		mining.start(ores);
		miningOn = true;
	}

	public static void setCombat(boolean on) {
		combatOn = on;
		if (!on) combat.reset();
	}

	public static void setPathing(boolean on) {
		pathing = on;
		if (!on) wanderNav.stop();
	}

	/** Survival mode plays the game on its own; it needs fighting and eating, so it turns those on too. */
	public static void setSurvival(boolean on) {
		survivalOn = on;
		if (on) {
			combatOn = true;
			eatOn = true;
			message("Survival mode §aON§r: playing from scratch (wood, tools, food, iron, diamonds). Numpad 0 stops everything.");
		} else {
			planner.stop();
			message("Survival mode " + onOff(false));
		}
	}

	/** Numpad 8 (in the game or in the Autopilot menu): start or stop recording. */
	public static void toggleRecording() {
		// Started while box fill / a schematic build is on: record the bot (to check how it moves and looks);
		// otherwise it's the player's own play, as before.
		if (!recorder.on()) {
			dev.humanautopilot.record.Recorder.recordBot = BoxFillModule.get().on();
			if (dev.humanautopilot.record.Recorder.recordBot) message("Recording the §ebot§r this time (box fill / schematic is on).");
		}
		recorder.toggle();
	}

	/** The record key (numpad 8 unless changed), for the menu to pass it on. */
	public static boolean isRecordKey(net.minecraft.client.input.KeyEvent e) {
		return keyRecord != null && keyRecord.matches(e);
	}

	public static dev.humanautopilot.record.Recorder recorder() {
		return recorder;
	}

	public static int tunnelSteps() {
		return planner.tunnelSteps();
	}

	public static String survivalStage() {
		return planner.stage();
	}

	/** Keep the box filled (the server plugin sends where it is; /autopilot box set does it by hand). */
	public static void setBoxFill(boolean on) {
		// One mode at a time: box fill replaces a schematic build (and its preview).
		dev.humanautopilot.schematic.SchematicMode.get().stop();
		BoxFillModule.get().setOn(on);
		message("Box fill " + onOff(on) + (on && BoxFillModule.get().box() == null ? "§r (no box yet: waiting for the server)" : ""));
	}

	/** A local box: layers bottom-up as a comma list of block ids (the last one repeats to the top). */
	public static void setLocalBox(int x1, int y1, int z1, int x2, int y2, int z2, String blocks) {
		int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
		String[] ids = blocks.trim().split("[\\s,]+");
		net.minecraft.world.item.Item[] layers = new net.minecraft.world.item.Item[maxY - minY + 1];
		for (int i = 0; i < layers.length; i++) layers[i] = dev.humanautopilot.net.BoxLink.item(ids[Math.min(i, ids.length - 1)]);
		BoxFillModule.get().localBox(new BoxFillModule.Box(Math.min(x1, x2), minY, Math.min(z1, z2), Math.max(x1, x2), maxY,
				Math.max(z1, z2), layers));
	}

	/** Pause or carry on with whichever mode is on (box fill or the schematic). */
	public static void togglePause() {
		BoxFillModule b = BoxFillModule.get();
		if (!b.on()) return;
		b.setPaused(!b.paused());
		input.releaseAll();
		message((b.planMode() ? "Building " + b.planName() : "Box fill") + (b.paused() ? " §epaused§r (" + keyName(keyPause) + " to carry on)" : " §aresumed"));
	}

	/** The menu's Off button: box fill, schematic and its preview all off. */
	public static void autopilotOff() {
		boolean was = BoxFillModule.get().on() || dev.humanautopilot.schematic.SchematicMode.get().previewing()
				|| dev.humanautopilot.modules.ParkourModule.get().on() || dev.humanautopilot.modules.FarmModule.get().on()
				|| dev.humanautopilot.modules.LadderModule.get().on() || dev.humanautopilot.modules.OneBlockModule.get().on();
		dev.humanautopilot.modules.ParkourModule.get().stop();
		dev.humanautopilot.modules.LadderModule.get().stop();
		dev.humanautopilot.modules.OneBlockModule.get().stop();
		dev.humanautopilot.modules.FarmModule.get().stop();
		dev.humanautopilot.schematic.SchematicMode.get().stop();
		BoxFillModule.get().setOn(false);
		dev.humanautopilot.schematic.CreativePicker.get().cancel();
		input.releaseAll();
		if (was) message("Autopilot " + onOff(false));
	}

	private static String boxReport() {
		var b = BoxFillModule.get();
		return "Box fill " + onOff(b.on()) + "§r | " + (b.box() == null ? "no box" : b.box().describe() + ", " + b.holesLeft() + " holes")
				+ " | placed " + BoxFillModule.placedCount + ", skipped " + BoxFillModule.skippedCount + " | " + b.note();
	}

	public static void setAutoEat(boolean on) {
		eatOn = on;
	}

	public static boolean isGotoActive() {
		return gotoNav.active();
	}

	public static boolean isMiningOn() {
		return miningOn;
	}

	public static String status() {
		return status;
	}

	/** Stop everything right now and hand the controls back. */
	public static void panic() {
		dev.humanautopilot.pathing.BaritoneLegs.stopAll();
		gotoNav.stop();
		wanderNav.stop();
		mining.stop();
		planner.stop();
		survivalOn = false;
		combat.reset();
		pathing = false;
		survival.abortEating(input);
		idle.reset();
		peek.cancel();
		dev.humanautopilot.schematic.SchematicMode.get().stop();
		dev.humanautopilot.schematic.CreativePicker.get().cancel();
		BoxFillModule.get().setOn(false);
		dev.humanautopilot.modules.ParkourModule.get().stop();
		dev.humanautopilot.modules.FarmModule.get().stop();
		dev.humanautopilot.modules.LadderModule.get().stop();
		dev.humanautopilot.modules.OneBlockModule.get().stop();
		miningOn = false;
		combatOn = false;
		eatOn = false;
		idleOn = false;
		input.releaseAll();
		aim.clear();
		respawnAt = 0;
		status = "off";
		message("§cPANIC: everything stopped. You have control.");
	}

	/**
	 * The open menu is the bot's (getting a block in creative, crafting, a look in the bags, the death screen): the
	 * user's real mouse is ignored and hidden there, and the bot's own cursor is drawn instead.
	 */
	public static boolean botOwnsScreen() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen == null || mc.player == null) return false;
		return dev.humanautopilot.schematic.CreativePicker.get().ownsScreen(mc.screen)
				|| (survivalOn && planner.ownsScreen(mc.screen)) || peek.active()
				|| (survivalOn && respawnAt != 0 && mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen);
	}

	private static boolean pointerHidden;
	private static long signSeenAt = -1;

	/** Hide the real pointer while the bot has the menu (the drawn one is the bot's), show it again after. */
	private static void pointer(Minecraft mc) {
		boolean hide = botOwnsScreen();
		if (hide == pointerHidden) return;
		pointerHidden = hide;
		if (mc.screen != null) {
			GLFW.glfwSetInputMode(mc.getWindow().handle(), GLFW.GLFW_CURSOR, hide ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL);
		}
	}

	/** Per-frame camera update (called from the mouse handler mixin). */
	public static void onFrame() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		recorder.frame(mc, lastOwner != Owner.NONE);
		pointer(mc);
		if (mc.screen != null) {
			if (peek.active()) peek.frame();
			// Getting a block out of the creative menu: the cursor glides to it.
			if (dev.humanautopilot.schematic.CreativePicker.get().ownsScreen(mc.screen)) dev.humanautopilot.schematic.CreativePicker.get().frame();
			// In one of our crafting / furnace / chest screens: move the real cursor between slots.
			if (survivalOn && planner.ownsScreen(mc.screen)) planner.guiFrame();
			if (dev.humanautopilot.modules.OneBlockModule.get().on() && dev.humanautopilot.modules.OneBlockModule.get().ownsScreen(mc.screen)) dev.humanautopilot.modules.OneBlockModule.get().guiFrame();
			// Dead: after reading the screen a moment, drift the cursor over to Respawn.
			var b = respawnButton(mc.screen);
			if (survivalOn && b != null && respawnAt != 0 && ticks >= respawnAt - 15) {
				deathMouse.moveTo(b.getX() + b.getWidth() / 2.0 + respawnJitterX, b.getY() + b.getHeight() / 2.0 + respawnJitterY);
				deathMouse.frame();
			}
			return;
		}
		if (mc.isPaused()) return;
		aim.frame(mc.player);
	}

	/** The death screen's Respawn button, or null if this isn't the death screen. */
	private static net.minecraft.client.gui.components.Button respawnButton(net.minecraft.client.gui.screens.Screen s) {
		if (!(s instanceof net.minecraft.client.gui.screens.DeathScreen)) return null;
		String label = Component.translatable("deathScreen.respawn").getString();
		for (var c : s.children()) {
			if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(label)) return b;
		}
		return null;
	}

	private static void handleKeys() {
		while (keyPanic.consumeClick()) panic();
		while (keyGoto.consumeClick()) {
			pathing = !pathing;
			if (!pathing) wanderNav.stop();
			if (gotoNav.active()) message("Walking to " + gotoNav.goal().describe() + " " + (pathing ? "§aresumed" : "§cpaused"));
			else message("Exploring " + onOff(pathing) + (pathing ? "§r (no /goto set, so I'll wander around)" : ""));
		}
		while (keyCombat.consumeClick()) {
			combatOn = !combatOn;
			if (!combatOn) combat.reset();
			message("Combat " + onOff(combatOn));
		}
		while (keyMining.consumeClick()) {
			if (!miningOn && mining.ores().isEmpty()) {
				Set<MiningModule.Ore> ores = EnumSet.noneOf(MiningModule.Ore.class);
				for (String s : config.defaultOres) {
					MiningModule.Ore o = MiningModule.Ore.parse(s);
					if (o != null) ores.add(o);
				}
				mining.start(ores);
			}
			miningOn = !miningOn;
			if (!miningOn) mining.stop();
			message("Mining " + onOff(miningOn) + " " + mining.ores().toString().toLowerCase(Locale.ROOT));
		}
		while (keyEat.consumeClick()) {
			eatOn = !eatOn;
			message("Auto-eat " + onOff(eatOn));
		}
		while (keyIdle.consumeClick()) {
			idleOn = !idleOn;
			message("Idle looking " + onOff(idleOn));
		}
		while (keyHud.consumeClick()) hudOn = !hudOn;
		while (keySurvival.consumeClick()) setSurvival(!survivalOn);
		while (keyRecord.consumeClick()) toggleRecording();
		// Recording, and the builder (box fill / a schematic) starts: the recorder moves on to a file of the bot's own
		// once it drives (Recorder.whoChanges), so the player's files stay theirs.
		while (keyBox.consumeClick()) setBoxFill(!(BoxFillModule.get().on() && !BoxFillModule.get().planMode()));
		while (keyMenu.consumeClick()) Minecraft.getInstance().setScreen(new AutopilotScreen());
		while (keyRotate.consumeClick()) dev.humanautopilot.schematic.SchematicMode.get().rotate();
		while (keyPause.consumeClick()) togglePause();
	}

	private static void tick() {
		dev.humanautopilot.net.BuildLink.tick(); // (the build's plan over to the companion's plugin, a little a tick)
		ticks++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			input.releaseAll();
			return;
		}
		handleKeys();
		// The game's own "pause on lost focus" opens its menu whenever another window gets clicked (the stream's), and
		// with a menu open nothing of hers runs: the fantasy castle sat at 0 placed for 7 minutes. Off while she builds;
		// and that menu, open while she builds with nobody at the game (its window not the one in front), closed again.
		if (BoxFillModule.get().on()) {
			if (mc.options.pauseOnLostFocus) {
				mc.options.pauseOnLostFocus = false;
				mc.options.save();
				LOGGER.info("[schem] turned the game's pause-on-lost-focus off: its menu stopped the build whenever another window was clicked");
			}
			// (Her own menu too: LiveKeeper opened it to click "Continue building", found her building already, and left it
			// open - nothing ran under it.)
			if ((mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen || mc.screen instanceof AutopilotScreen)
					&& !mc.isWindowActive() && !BoxFillModule.get().paused()) {
				mc.setScreen(null);
				LOGGER.info("[schem] closed the game's menu (opened with nobody at the game): back to building");
			}
		}
		if (mc.screen == null) dev.humanautopilot.schematic.SchematicMode.get().tick(mc, p);
		dev.humanautopilot.pathing.BaritoneLegs.beforeTick(p);
		Attention.get().ensureListening(mc);
		recorder.tick(mc, lastOwner != Owner.NONE);
		dev.humanautopilot.modules.ParkourModule.get().worldTick(p);
		dev.humanautopilot.modules.FarmModule.get().worldTick(p);
		dev.humanautopilot.modules.LadderModule.get().worldTick(p);
		dev.humanautopilot.modules.OneBlockModule.get().worldTick(p);
		if ((survivalOn || dev.humanautopilot.modules.ParkourModule.get().on() || dev.humanautopilot.modules.LadderModule.get().on()
				|| dev.humanautopilot.modules.OneBlockModule.get().on())
				&& mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
			// Died: read the death screen for a few seconds, then click Respawn and start over.
			input.releaseAll();
			if (respawnAt == 0) {
				respawnAt = ticks + Human.rangeInt(50, 100);
				deathMouse.reset();
				respawnJitterX = Human.range(-40, 40); // somewhere on the (200 px wide) button, not dead centre
				respawnJitterY = Human.range(-3, 3);
				// The death screen can come back once while the server finishes the respawn: that's still the same death.
				if (!deathNoted) {
					deathNoted = true;
					if (survivalOn) planner.onDeath(p.blockPosition());
					LOGGER.info("[{}] died at {}", survivalOn ? "survival" : "parkour", p.blockPosition().toShortString());
				}
				if (survivalOn) planner.stop();
			}
			var respawnButton = respawnButton(mc.screen);
			if (ticks >= respawnAt && respawnButton != null && respawnButton.active && deathMouse.arrived()) {
				// Click it with the real cursor, where it now sits on the button.
				var ev = new net.minecraft.client.input.MouseButtonEvent(deathMouse.x(), deathMouse.y(),
						new net.minecraft.client.input.MouseButtonInfo(0, 0));
				mc.screen.mouseClicked(ev, false);
				mc.screen.mouseReleased(ev);
				respawnAt = 0;
			} else if (ticks >= respawnAt + 100) {
				respawnAt = 0; // couldn't find or reach the button: respawn anyway rather than sit here
				p.respawn();
				mc.setScreen(null);
			}
			return;
		}
		if (!p.isDeadOrDying()) deathNoted = false;
		if (peek.active()) {
			// Having a look in the bags: hands off everything else for that second or two.
			input.releaseAll();
			peek.tick(mc);
			strayScreenTicks = 0;
			return;
		}
		var picker = dev.humanautopilot.schematic.CreativePicker.get();
		if (picker.ownsScreen(mc.screen)) {
			// Our creative menu (getting a block for the schematic): search, cursor, number key.
			input.releaseAll();
			picker.screenTick(mc);
			return;
		}
		if (mc.screen != null && dev.humanautopilot.modules.OneBlockModule.get().on() && dev.humanautopilot.modules.OneBlockModule.get().ownsScreen(mc.screen)) {
			// The one block game's crafting / inventory session: keep clicking.
			input.releaseAll();
			dev.humanautopilot.modules.OneBlockModule.get().screenTick(mc);
			return;
		}
		if (mc.screen != null && survivalOn && planner.ownsScreen(mc.screen)) {
			// Our own crafting / furnace / inventory session: keep clicking.
			input.releaseAll();
			planner.screenTick(mc);
			return;
		}
		// A crafting / furnace / inventory screen left open that no job is using: close it, like a person would.
		if (survivalOn && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
			if (++strayScreenTicks > 60) {
				strayScreenTicks = 0;
				p.closeContainer();
				LOGGER.info("[survival] closed a stray {}", mc.screen == null ? "screen" : mc.screen.getClass().getSimpleName());
			}
		} else {
			strayScreenTicks = 0;
		}
		// A menu that popped up right after one of box fill's clicks (the click hit a companion or a chest): close it -
		// the companion's own (her bag, her panel: titles starting with the plugin's flower) up to 2 s after one (the
		// user: "if I accidentally click on her to open her inventory it closes right away"). One the player opened
		// themselves is left alone: /kc menu while building (the user: "now I can't access kc menu when I'm in
		// schematic build mode", when every one of hers was closed).
		// (Chat open a moment ago: they typed a command - /kc menu - so it's theirs, whenever box fill last clicked: while
		// building it clicks every fraction of a second.)
		if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) chatOpenAt = ticks;
		if (BoxFillModule.get().on() && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				&& ticks - chatOpenAt > 60
				&& (ticks - BoxFillModule.lastClickTick < 10 || ticks - BoxFillModule.lastClickTick < 40
						&& mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen
						&& mc.screen.getTitle().getString().startsWith("✿"))) {
			LOGGER.info("[box] a click opened {}: closing it", mc.screen.getClass().getSimpleName());
			p.closeContainer();
			BoxFillModule.lastClickTick = -100;
			input.releaseAll();
			return;
		}
		// We just put a sign down and the game opened its text editor: leave it blank and press Done after a moment.
		if (BoxFillModule.get().on() && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
				&& ticks - BoxFillModule.lastClickTick < 60) {
			input.releaseAll();
			if (signSeenAt < 0) signSeenAt = ticks + Human.rangeInt(6, 14);
			if (ticks >= signSeenAt) {
				LOGGER.info("[schem] closing the sign editor (left blank)");
				mc.screen.keyPressed(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
				if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen) mc.setScreen(null);
				signSeenAt = -1;
			}
			return;
		}
		signSeenAt = -1;
		if (mc.screen != null || p.isDeadOrDying()) {
			// A person can't walk around with a menu or chat open.
			input.releaseAll();
			return;
		}
		if (p.hurtTime == 9) {
			var src = p.getLastDamageSource();
			// Something hit us: after a moment's reaction, look round at it (a zombie behind us, an arrow's skeleton).
			if (src != null && src.getEntity() instanceof LivingEntity who && who != p
					&& !(who instanceof Creeper) && p.distanceTo(who) < 24) {
				hitBy = who;
				lookAtHitterFrom = ticks + Human.reaction();
				lookAtHitterUntil = lookAtHitterFrom + Human.rangeInt(8, 16);
			}
			LOGGER.info("[hurt] hp={} by {} at {} doing: {}", (int) p.getHealth(), src == null ? "?" : src.getMsgId()
					+ (src.getEntity() != null ? " (" + src.getEntity().getType().getDescription().getString() + ")" : ""),
					p.blockPosition().toShortString(), status);
		}
		aim.beginTick();
		Owner owner = decide(p);
		// (Not in parkour: the whole course is edges over drops, and the sneak it held made every run-up a creep - the
		// hops fell short at half walking speed.)
		if (owner != Owner.NONE && owner != Owner.IDLE && !dev.humanautopilot.modules.ParkourModule.get().on()
				&& !dev.humanautopilot.modules.FarmModule.get().on() && !dev.humanautopilot.modules.LadderModule.get().on()) edgeGuard(p);
		if (owner != Owner.NONE) breathGuard(p);
		if (owner != lastOwner) {
			if (owner == Owner.GOTO) gotoNav.replan();
			if (owner == Owner.WANDER) wanderNav.replan();
			if (owner == Owner.MINING) mining.interrupted();
			if (owner == Owner.SURVIVAL) planner.interrupted();
			lastOwner = owner;
		}
		aim.endTick();
		input.apply();
		// The bot playing counts as the player being active: otherwise the "reduce FPS when idle (AFK)" option drops
		// the game to 30 FPS after a minute (10 after ten) while it plays on its own, and the camera gets choppy.
		if (owner != Owner.NONE) mc.getFramerateLimitTracker().onInputReceived();
	}

	/** Priority order: danger, low health, eating, fighting, (human pause), mining, travel, idle. */
	private static Owner decide(LocalPlayer p) {
		long now = ticks;
		BoxFillModule box = BoxFillModule.get();
		var parkour = dev.humanautopilot.modules.ParkourModule.get();
		var farm = dev.humanautopilot.modules.FarmModule.get();
		var ladderGame = dev.humanautopilot.modules.LadderModule.get();
		var oneBlock = dev.humanautopilot.modules.OneBlockModule.get();
		if (parkour.on() && box.on()) parkour.stop(); // (one at a time: a build started since takes over)
		if (farm.on() && (box.on() || parkour.on())) farm.stop();
		if (ladderGame.on() && (box.on() || parkour.on() || farm.on())) ladderGame.stop();
		if (oneBlock.on() && (box.on() || parkour.on() || farm.on() || ladderGame.on())) oneBlock.stop();
		boolean anything = combatOn || eatOn || idleOn || miningOn || pathing || survivalOn || box.on() || parkour.on() || farm.on()
				|| ladderGame.on() || oneBlock.on();
		if (!anything) {
			status = "off";
			return Owner.NONE;
		}

		Creeper creeper = survival.creeperThreat(p);
		if (creeper != null) {
			aim.setUrgent(true);
			status = survival.escapeCreeper(p, input, aim, creeper);
			return Owner.SAFETY;
		}

		// Lit TNT next to us while working on the box: get out of the blast.
		var tnt = box.tntThreat(p);
		if (tnt != null) {
			aim.setUrgent(true);
			status = box.dodge(p, input, aim, tnt);
			return Owner.SAFETY;
		}

		LivingEntity threat = survival.retreatThreat(p);
		if (threat != null) {
			aim.setUrgent(true);
			if (eatOn && p.distanceTo(threat) > 5 && survival.wantsToEat(p)) {
				status = survival.eatTick(p, input, aim);
				return Owner.EAT;
			}
			status = survival.retreat(p, input, aim, threat);
			return Owner.SAFETY;
		}

		if (eatOn && survival.isEating()) {
			status = survival.eatTick(p, input, aim);
			return Owner.EAT;
		}

		if (combatOn && combat.scan(p)) {
			aim.setUrgent(true);
			status = combat.tick(p, input, aim);
			return Owner.COMBAT;
		}

		// Just got hit by something we're not already fighting (behind us, out of sight): turn and see what it was.
		// A reflex, never more urgent than running away or a fight already going on.
		if (hitBy != null && now >= lookAtHitterFrom && now < lookAtHitterUntil && hitBy.isAlive()
				&& (combatOn || survivalOn) && !survival.isEating()) {
			LivingEntity who = hitBy;
			aim.setUrgent(true);
			aim.lookAt(who, () -> who.position().add(0, who.getBbHeight() * 0.7, 0));
			status = "looking at what hit me (" + who.getType().getDescription().getString() + ")";
			return Owner.SAFETY;
		}
		if (hitBy != null && now >= lookAtHitterUntil) hitBy = null;

		// Heard something worth a look (a groan behind us, a hiss, an arrow) while busy on the spot: turn and see.
		// Walking over easy ground the walker does this itself without stopping; on tricky ground only real alarms
		// are worth stopping for.
		Attention attention = Attention.get();
		Attention.Look heard = attention.soundLook(now);
		if (heard != null && (combatOn || survivalOn || idleOn) && !survival.isEating() && !attention.walkerHandles(now)
				&& (heard.alarm() || (!attention.walking(now) && !InputDriver.opts().keyAttack.isDown()
						&& !dev.humanautopilot.pathing.Digger.busyMining())) // an oink doesn't stop the digging
				&& !p.onClimbable() && !p.isInWater()) {
			attention.took(heard);
			if (heard.alarm()) aim.setUrgent(true);
			aim.lookAt(heard.key(), heard.where());
			status = "heard " + heard.what() + ", having a look";
			return Owner.LOOK;
		}

		if (eatOn && survival.wantsToEat(p)) {
			status = survival.eatTick(p, input, aim);
			return Owner.EAT;
		}

		// (No "short breaks" while filling the box: the recorded player doesn't stop, and a pause there looks like
		// staring at a block.)
		boolean moving = miningOn || pathing || survivalOn;
		if (moving) {
			if (nextPauseAt == 0) schedulePause(now);
			if (nextPeekAt == 0) nextPeekAt = now + 20L * Human.rangeInt(60, 150);
			if (now >= nextPauseAt) {
				pauseUntil = now + Human.secondsToTicks(Human.range(config.pauseMinSec, config.pauseMaxSec));
				schedulePause(pauseUntil);
				// Every couple of minutes a break is a look in the bags instead (the recorded player: every ~2 min, 1-2.5 s).
				if (survivalOn && now >= nextPeekAt && calmMoment(p) && !planner.screenBusy()) {
					nextPeekAt = now + 20L * Human.rangeInt(80, 200);
					pauseUntil = now;
					peek.start(input);
					LOGGER.info("[habit] having a look in the inventory");
					status = "checking my inventory";
					return Owner.PAUSE;
				}
			}
			if (now < pauseUntil) {
				idle.tick(p, aim);
				status = "taking a short break";
				return Owner.PAUSE;
			}
		}

		if (box.on()) {
			String s = box.tick(p, input, aim);
			if (s != null) {
				status = s;
				return Owner.BOX;
			}
		}

		if (survivalOn) {
			status = planner.tick(p, input, aim, combat);
			return Owner.SURVIVAL;
		}

		// The farm mini-game: snowballs at whatever isn't ripe (FarmModule).
		if (farm.on()) {
			status = farm.tick(p, input, aim);
			return Owner.GOTO;
		}

		// A parkour mini-game: up the course to the top (ParkourModule).
		if (parkour.on()) {
			status = parkour.tick(p, input, aim);
			return Owner.GOTO;
		}

		// The one block mini-game: mine, build out, a bed and sleep (OneBlockModule).
		if (oneBlock.on()) {
			status = oneBlock.tick(p, input, aim, survival);
			return Owner.GOTO;
		}

		// The ladder mini-game: up the ladder to the top, hold on there (LadderModule).
		if (ladderGame.on()) {
			status = ladderGame.tick(p, input, aim);
			return Owner.GOTO;
		}

		if (miningOn) {
			String s = mining.tick(p, input, aim);
			if (s == null) {
				miningOn = false;
				mining.stop();
				status = "mining stopped";
				return Owner.NONE;
			}
			// No ore in sight and Path is on: go looking for some instead of standing still.
			if (!(pathing && !gotoNav.active() && mining.waitingForOre())) {
				status = s;
				return Owner.MINING;
			}
		}

		if (pathing && gotoNav.active()) {
			String dest = gotoNav.goal().describe();
			Navigator.Status s = gotoNav.tick(p, input, aim);
			switch (s) {
				case ARRIVED -> {
					message("Arrived at " + dest + ".");
					status = "arrived";
					pathing = false;
				}
				case FAILED -> {
					message("Couldn't reach " + dest + ": " + gotoNav.failReason() + ".");
					status = "goto failed";
					pathing = false;
				}
				case SEARCHING -> status = "planning route to " + dest;
				case DIGGING -> status = "digging through";
				default -> status = String.format("walking to %s (%.0fm)", dest, gotoNav.distanceLeft(p));
			}
			return Owner.GOTO;
		}

		if (pathing) {
			status = wander(p);
			if (miningOn) status = "exploring for " + mining.status().replace("no ", "").replace(" in sight", "");
			return Owner.WANDER;
		}

		if (idleOn) {
			status = idle.tick(p, aim);
			return Owner.IDLE;
		}
		if (box.on()) {
			status = "box: " + box.note();
			return Owner.NONE;
		}
		status = "waiting";
		return Owner.NONE;
	}

	/** Explore: walk to spots 20-45 blocks away, mostly keeping the same general heading. */
	private static String wander(LocalPlayer p) {
		if (!wanderNav.active()) {
			if (Double.isNaN(wanderHeading)) wanderHeading = p.getYRot();
			wanderHeading += Human.gauss(-60, 60);
			double dist = Human.range(20, 45);
			double rad = Math.toRadians(wanderHeading);
			int tx = (int) Math.floor(p.getX() - Math.sin(rad) * dist);
			int tz = (int) Math.floor(p.getZ() + Math.cos(rad) * dist);
			wanderNav.go(new Goal.NearXZ(tx, tz, 3), false);
		}
		Navigator.Status s = wanderNav.tick(p, input, aim);
		if (s == Navigator.Status.FAILED) {
			// Blocked that way (water, cliff, wall): turn well away and try again.
			wanderHeading += (Human.chance(0.5) ? 1 : -1) * Human.range(90, 180);
		}
		return s == Navigator.Status.SEARCHING ? "exploring (choosing a route)" : "exploring";
	}

	/**
	 * Safety net under everything else: standing next to a big drop, crouch (vanilla stops a
	 * sneaking player from walking off a ledge), the way a player creeps along a cliff.
	 */
	/** Head under water: swim up. Running out of air: drop everything, look up and swim for the surface. */
	private static void breathGuard(LocalPlayer p) {
		// (Not in creative: nobody drowns there, and holding space in the water made the box builder's presses a double
		// tap - flight off in the dining hall's pool - and then swallowed every double tap to fly out again: 70 s
		// bobbing at the surface, "taking off".)
		if (!p.isUnderWater() || p.getAbilities().invulnerable) {
			breathTarget = null;
			return;
		}
		if (p.getAirSupply() >= p.getMaxAirSupply() / 2) {
			input.hold(InputDriver.opts().keyJump); // plenty of air: just keep the head up
			return;
		}
		aim.setUrgent(true);
		long now = ticks;
		// Swim for the nearest air we can actually get to: straight up is useless under a ceiling (a flooded
		// staircase or cave). Up, across or down to it: only hold jump if it's above us, dive if it's below.
		net.minecraft.core.BlockPos air = nearestAir(p);
		if (air != null && !air.equals(breathTarget)) {
			breathTarget = air;
			breathSince = now;
		}
		if (air != null && now - breathSince > 50) {
			// Not getting there (a lip, a ceiling in the way): try another pocket.
			badAir.put(air, now + 200);
			breathTarget = null;
			air = nearestAir(p);
			breathSince = now;
		}
		if (air != null) {
			net.minecraft.world.phys.Vec3 to = net.minecraft.world.phys.Vec3.atCenterOf(air);
			aim.lookAt(air, () -> to);
			input.hold(InputDriver.opts().keyUp);
			double eye = p.getEyeY();
			if (to.y > eye - 0.3) input.hold(InputDriver.opts().keyJump);
			else if (to.y < eye - 0.8) input.hold(InputDriver.opts().keyShift);
			status = "swimming to air at " + air.toShortString() + "!";
		} else {
			input.hold(InputDriver.opts().keyJump);
			aim.lookDirection("breath", p.getYRot(), -60);
			input.hold(InputDriver.opts().keyUp);
			status = "swimming up for air!";
		}
	}

	private static net.minecraft.core.BlockPos breathTarget;
	private static long breathSince;
	private static final java.util.Map<net.minecraft.core.BlockPos, Long> badAir = new java.util.HashMap<>();

	/** The nearest open-air cell reachable through water (breadth-first, a few blocks), or null. */
	private static net.minecraft.core.BlockPos nearestAir(LocalPlayer p) {
		net.minecraft.core.BlockPos start = net.minecraft.core.BlockPos.containing(p.getEyePosition());
		java.util.ArrayDeque<net.minecraft.core.BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<net.minecraft.core.BlockPos> seen = new java.util.HashSet<>();
		q.add(start);
		seen.add(start);
		while (!q.isEmpty() && seen.size() < 400) {
			var c = q.poll();
			boolean wet = dev.humanautopilot.pathing.Terrain.water(c.getX(), c.getY(), c.getZ());
			Long bad = badAir.get(c);
			if (!wet && dev.humanautopilot.pathing.Terrain.passable(c.getX(), c.getY(), c.getZ()) && (bad == null || ticks > bad)) return c;
			if (!wet) continue; // only swim through water
			for (var d : net.minecraft.core.Direction.values()) {
				var n = c.relative(d);
				if (Math.abs(n.getX() - start.getX()) > 6 || Math.abs(n.getZ() - start.getZ()) > 6 || Math.abs(n.getY() - start.getY()) > 6) continue;
				if (seen.add(n)) q.add(n);
			}
		}
		return null;
	}

	private static void edgeGuard(LocalPlayer p) {
		if (!p.onGround() || p.isInWater() || p.onClimbable()) return;
		int y = net.minecraft.util.Mth.floor(p.getY() + 0.2);
		// Where will our body be in a few ticks at this speed? Check the ground under all four corners.
		var v = p.getDeltaMovement();
		double fx = p.getX() + v.x * 4, fz = p.getZ() + v.z * 4;
		int here = net.minecraft.util.Mth.floor(p.getX()), hereZ = net.minecraft.util.Mth.floor(p.getZ());
		for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
			for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
				int x = net.minecraft.util.Mth.floor(fx + ox), z = net.minecraft.util.Mth.floor(fz + oz);
				if (x == here && z == hereZ) continue;
				if (!dev.humanautopilot.pathing.Terrain.passable(x, y, z) || dev.humanautopilot.pathing.Terrain.floor(x, y - 1, z)) continue;
				if (dev.humanautopilot.pathing.Terrain.water(x, y - 1, z)) continue;
				if (dev.humanautopilot.pathing.Terrain.dropDepth(x, y, z) > config.maxSafeDrop
						|| dev.humanautopilot.pathing.Terrain.lava(x, y - 1, z) || dev.humanautopilot.pathing.Terrain.lava(x, y - 2, z)) {
					input.hold(InputDriver.opts().keyShift);
					return;
				}
			}
		}
	}

	/** Nothing about to hurt us, feet on solid ground: a moment where a player takes their eyes off the world. */
	private static boolean calmMoment(LocalPlayer p) {
		return p.onGround() && !p.isInWater() && !p.onClimbable() && p.getHealth() > 12 && p.hurtTime == 0
				&& CombatModule.hostilesAround(p, 16).isEmpty() && !survival.isRetreating();
	}

	private static void schedulePause(long from) {
		nextPauseAt = from + Human.secondsToTicks(Human.range(config.pauseEveryMinSec, config.pauseEveryMaxSec));
	}

	// ------------------------------------------------------------------ HUD

	private static String onOff(boolean b) {
		return b ? "§aON" : "§cOFF";
	}

	private static String keyName(KeyMapping k) {
		return k.getTranslatedKeyMessage().getString();
	}

	private static String statusReport() {
		return "Path " + onOff(pathing) + "§r, Combat " + onOff(combatOn) + "§r, Mining " + onOff(miningOn)
				+ "§r, Eat " + onOff(eatOn) + "§r, Idle " + onOff(idleOn) + "§r | " + status;
	}

	/** What the progress bar shows now (eased toward the real fraction), and when it last moved up or down. */
	private static double barShown = -1;
	private static long barLastNanos, barUpAt, barDownAt;
	private static double barLastTarget = -1;
	/** The build (and world) the bar is for: another one, or a relog, starts it again from where it really is. */
	private static Object barFor;

	/**
	 * The build's progress over the hotbar: a bar the width of the hotbar that slides to the real percentage, a light
	 * sweeping along its filled part, flashing green when blocks go in and red when some come out (TNT).
	 */
	private static void progressBar(GuiGraphicsExtractor g, Minecraft mc, BoxFillModule bx) {
		long now = System.nanoTime();
		double target = bx.planProgress(); // (from where it was when it was started: a new build starts at 0)
		// (Another build, or back in after a relog: straight to where it is, no slide and no flash from the last one.)
		Object key = java.util.List.of(bx.planName(), bx.planTotal(), System.identityHashCode(mc.level));
		if (!key.equals(barFor)) {
			barFor = key;
			barShown = -1;
			barLastTarget = -1;
			barUpAt = barDownAt = 0;
		}
		double dt = barLastNanos == 0 ? 0 : Math.min(0.25, (now - barLastNanos) / 1e9);
		barLastNanos = now;
		if (barShown < 0) barShown = target;
		if (barLastTarget >= 0 && target > barLastTarget + 1e-6) barUpAt = now;
		if (barLastTarget >= 0 && target < barLastTarget - 1e-6) barDownAt = now;
		barLastTarget = target;
		barShown += (target - barShown) * (1 - Math.exp(-dt * 4)); // eased slide
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		int w = 182, h = 5, x = sw / 2 - w / 2;
		// Over the hotbar, above the hearts/XP when they show (survival), right over the hotbar in creative.
		boolean creative = mc.player.getAbilities().instabuild;
		int y = sh - (creative ? 30 : 46);
		int fill = (int) Math.round(w * barShown);
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
		g.fill(x, y, x + w, y + h, 0xFF2A2A2A);
		// Fill: a rainbow that scrolls along it.
		if (fill > 0) {
			float shift = (float) ((now / 1e9) % 3.0 / 3.0);
			for (int i = 0; i < fill; i++) {
				float hue = (i / (float) w + shift) % 1f;
				g.fill(x + i, y, x + i + 1, y + h, 0xFF000000 | net.minecraft.util.Mth.hsvToRgb(hue, 0.85f, 1f));
			}
			g.fill(x, y, x + fill, y + 1, 0x60FFFFFF); // top highlight
			// A light sweeping along the filled part.
			double t = (now / 1e9) % 2.0 / 2.0;
			int sx = x + (int) ((fill + 30) * t) - 15;
			for (int i = -8; i <= 8; i++) {
				int px = sx + i;
				if (px < x || px >= x + fill) continue;
				int a = (int) (0x70 * (1 - Math.abs(i) / 9.0));
				g.fill(px, y, px + 1, y + h, (a << 24) | 0xFFFFFF);
			}
		}
		// Flash on change: green when it went up, red when it went down.
		double up = (now - barUpAt) / 1e9, down = (now - barDownAt) / 1e9;
		if (barDownAt != 0 && down < 0.8) {
			int a = (int) (0xA0 * (1 - down / 0.8));
			g.fill(x - 1, y - 1, x + w + 1, y + h + 1, (a << 24) | 0xFF3030);
		} else if (barUpAt != 0 && up < 0.4) {
			int a = (int) (0x60 * (1 - up / 0.4));
			g.fill(x, y, x + Math.max(fill, 1), y + h, (a << 24) | 0xFFFFFF);
		}
		String pct = (int) Math.floor(barShown * 100 + 1e-9) + "%";
		// (Beside the bar's right end: over it, it ran into the held item's name.)
		int tc = barDownAt != 0 && down < 0.8 ? 0xFFFF6060 : 0xFFFFFFFF;
		g.text(mc.font, pct, x + w + 5, y - 2, tc, true);
	}

	private static void renderHud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) return;
		// Schematic progress (and the preview's hints) at the top middle, whether or not the HUD is shown.
		BoxFillModule bx = BoxFillModule.get();
		String line = dev.humanautopilot.schematic.SchematicMode.get().hudLine();
		// (Just the build's name and how it stands: the count is the bar's, the keys the menu's - "85000/158728  . pause,
		// 0 stop" on the stream the whole time was clutter.)
		if (line == null && bx.planMode()) {
			line = (bx.guarding() && bx.holesLeft() == 0 ? "§aGuarding§r " : "§aBuilding§r ") + bx.planName() + (bx.paused() ? " §e(paused)" : "");
		}
		// (The mini-games too: LiveKeeper reads this line after a restart - "arming" / "arkour" - to know she carried on.)
		if (line == null && dev.humanautopilot.modules.FarmModule.get().on()) {
			var f = dev.humanautopilot.modules.FarmModule.get();
			line = "§6Farming§r " + f.ripeNow() + "/" + f.fieldSize() + " ripe" + (f.wins() > 0 ? ", " + f.wins() + " wins" : "");
		}
		if (line == null && dev.humanautopilot.modules.ParkourModule.get().on()) line = "§bParkour§r climbing";
		if (line == null && dev.humanautopilot.modules.LadderModule.get().on()) line = "§dLadder§r climbing";
		if (line == null && dev.humanautopilot.modules.OneBlockModule.get().on()) line = "§2One Block§r " + dev.humanautopilot.modules.OneBlockModule.get().broken() + " broken";
		if (line != null) {
			int lw = mc.font.width(line);
			int lx = mc.getWindow().getGuiScaledWidth() / 2 - lw / 2;
			g.fill(lx - 3, 3, lx + lw + 3, 15, 0x90000000);
			g.text(mc.font, line, lx, 5, 0xFFFFFFFF, true);
		}
		if (bx.planMode() && bx.planTotal() > 0 && bx.planCounted()) progressBar(g, mc, bx);
		else if (bx.planMode() && bx.planTotal() > 0) {
			// (The first count through a big build takes a while - the castle a few minutes: no bar till it's real.)
			// (Paused, nothing gets counted: say so - "counting the build..." for minutes looked stuck.)
			String c = bx.paused() ? "§epaused§7 (" + keyName("pause") + " to carry on)" : "§7counting the build...";
			int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
			int y = sh - (mc.player.getAbilities().instabuild ? 30 : 46);
			g.text(mc.font, c, sw / 2 - mc.font.width(c) / 2, y - 2, 0xFFFFFFFF, true);
		}
		if (!hudOn) return;
		var font = mc.font;
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{gotoNav.active() ? "Path (goto)" : "Path (explore)", keyName(keyGoto), String.valueOf(pathing)});
		rows.add(new String[]{"Survival", keyName(keySurvival), String.valueOf(survivalOn)});
		rows.add(new String[]{"Box fill", keyName(keyBox), String.valueOf(BoxFillModule.get().on() && !BoxFillModule.get().planMode())});
		// (The schematic has its own line at the top and the bar: not here as well.)
		rows.add(new String[]{"Combat", keyName(keyCombat), String.valueOf(combatOn)});
		rows.add(new String[]{"Mining", keyName(keyMining), String.valueOf(miningOn)});
		rows.add(new String[]{"Eat", keyName(keyEat), String.valueOf(eatOn)});
		rows.add(new String[]{"Idle look", keyName(keyIdle), String.valueOf(idleOn)});
		// (Only what's on: all 8 with their keys, most of them OFF, sat in the corner of the stream the whole time.)
		rows.removeIf(r -> !Boolean.parseBoolean(r[2]));
		if (rows.isEmpty() && !recorder.on()) return;
		// (Under the build's line at the top, if that's showing: side by side they overlapped in a small window.)
		int x = 4, y = line != null ? 20 : 4, lh = 10;
		String title = (recorder.on() ? "§c● REC§r " : "") + (rows.isEmpty() ? "" : "Autopilot: " + status);
		int w = font.width(title);
		for (String[] r : rows) w = Math.max(w, font.width(r[0] + " ON") + 4);
		int h = lh * (rows.size() + 1) + 4;
		g.fill(x - 2, y - 2, x + w + 4, y + h, 0x90000000);
		g.text(font, title, x, y, 0xFFFFD866, true);
		y += lh + 2;
		for (String[] r : rows) {
			String label = r[0] + " ";
			g.text(font, label, x, y, 0xFFFFFFFF, true);
			g.text(font, "ON", x + font.width(label), y, 0xFF55FF55, true);
			y += lh;
		}
	}
}
