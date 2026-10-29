package dev.humanautopilot.survival;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.modules.CombatModule;
import dev.humanautopilot.modules.MiningModule;
import dev.humanautopilot.util.Tools;
import dev.humanautopilot.pathing.Goal;
import dev.humanautopilot.util.Angles;
import net.minecraft.util.Mth;
import dev.humanautopilot.pathing.Navigator;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Plays survival from nothing, like a player would: punch trees, make a table and wooden pickaxe,
 * get stone tools, a furnace, food, iron, then go diamond hunting. Every tick it looks at the
 * inventory and picks the next step, so it picks up where it left off after any interruption.
 */
public final class SurvivalPlanner {
	private static final int IRON_Y = 16;
	private static final int DIAMOND_Y = -54;

	private final MiningModule gatherer = new MiningModule();
	private final MiningModule picker = new MiningModule();
	private final ScreenWorker screen = new ScreenWorker();
	private final Placer placer = new Placer();
	private final Placer torchPlacer = new Placer();
	private long torchCooldownUntil;
	private final Tunneler tunneler = new Tunneler();
	private final Hunter hunter = new Hunter();
	private final Navigator walk = new Navigator();
	private final Navigator roam = new Navigator();
	private BlockPos walkTarget;
	private boolean walkDig;
	private double walkRadius;
	private boolean closeWalkGaveUp, closeMode;
	private long climbRetryAt;
	private long pickerUsedAt;

	private BlockPos table, furnace;           // blocks we placed (and will pick back up)
	private long lastTableUse, lastFurnaceUse;
	private long foodSearchStarted = -1, foodCooldownUntil;
	private double exploreHeading = Double.NaN;
	private int stoneDigY = Integer.MIN_VALUE;
	private boolean stoneRun;
	private BlockPos digSpot, steppedAwayFrom;
	private long steppedAt, faceAwayUntil;
	private final Navigator stepAway = new Navigator();
	private final Map<String, Integer> failures = new HashMap<>();
	private final Map<String, Long> cooldownUntil = new HashMap<>();
	private String stage = "";
	private String lastStage = "";
	/** Thinking pauses before a new goal (for the test log). */
	public static long thinks;
	private final Map<String, Long> stageSeenAt = new HashMap<>();
	private String status = "";
	private String lastLogged = "";
	private String why = "";
	private long settleUntil;
	private long cookingUntil;
	private long nextJunkSort;
	private long nextObsoleteToss;
	private long cookWaitLook;
	private long nextHotbarSort;
	private long tunnelRetryAt;
	private long lastPlaceAttempt = -1000;
	private int woodTarget;
	private BlockPos lastPlaceSpot;
	private final java.util.Set<BlockPos> abandoned = new java.util.HashSet<>();
	private int tableOpenFails, furnaceOpenFails;
	private boolean repackTable, repackFurnace;
	private long pendingCookTicks;
	private long woodSearchStarted = -1;
	private int equipTries;
	private final Navigator climb = new Navigator();

	public String stage() {
		return stage;
	}

	public int tunnelSteps() {
		return tunneler.totalSteps();
	}

	public boolean ownsScreen(Screen s) {
		return screen.owns(s);
	}

	/** In the middle of a crafting / smelting / sorting session (opening the screen or clicking in it). */
	public boolean screenBusy() {
		return screen.active();
	}

	public void screenTick(Minecraft mc) {
		screen.screenTick(mc);
	}

	/** Per-frame cursor movement while one of our screens is open. */
	public void guiFrame() {
		screen.frame();
	}

	public void stop() {
		screen.abort("stopped");
		gatherer.stop();
		picker.stop();
		placer.reset();
		tunneler.reset();
		hunter.reset();
		walk.stop();
		roam.stop();
		homeRun.stop();
		chestPlacer.reset();
		depositing = false;
	}

	/** Something else (a fight, eating) used the controls for a while. */
	public void interrupted() {
		gatherer.interrupted();
		walk.replan();
		roam.replan();
	}

	// ------------------------------------------------------------------ main loop

	public String tick(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat) {
		long now = HumanAutopilot.ticks();

		// A screen session (crafting, smelting, sorting) runs until it finishes.
		if (screen.active()) {
			screen.worldTick(p, in, aim);
			return status = stage + ": " + screen.describe();
		}
		ScreenWorker.Result outcome = screen.takeOutcome();
		// Just closed a screen: take a beat (and let the inventory catch up) before deciding what's next.
		if (outcome != null) settleUntil = now + Human.rangeInt(4, 8);
		if (outcome == ScreenWorker.Result.DONE) {
			if (screen.lastJob().equals("loading the furnace")) cookingUntil = now + pendingCookTicks;
			if (screen.lastJob().equals("emptying the furnace")) cookingUntil = 0;
		}
		if (outcome == ScreenWorker.Result.FAILED) {
			HumanAutopilot.LOGGER.info("[survival] {} failed: {}", screen.lastJob(), screen.failReason());
			noteFailure(screen.lastJob());
			if (screen.failReason().startsWith("couldn't get at the table") && ++tableOpenFails >= 2) repackTable = true;
			if (screen.failReason().startsWith("couldn't get at the furnace") && ++furnaceOpenFails >= 2) repackFurnace = true;
		}

		if (now < settleUntil) return stage + ": " + status;
		status = think(p, in, aim, combat, now);
		if (!stage.equals(lastStage)) {
			// A new goal (the stone tools are done, now a furnace): a moment's thought before getting on with it,
			// not an instant switch. Only for goals we haven't just been at, so a back-and-forth doesn't stutter.
			Long seen = stageSeenAt.get(stage);
			if (!lastStage.isEmpty() && (seen == null || now - seen > 20 * 60) && Human.chance(0.7)
					&& CombatModule.hostilesAround(p, 12).isEmpty()) {
				settleUntil = now + Human.rangeInt(6, 24);
				thinks++;
				HumanAutopilot.LOGGER.info("[habit] new goal '{}': a moment's thought first", stage);
			}
			stageSeenAt.put(lastStage, now);
			lastStage = stage;
		}
		// Stuck detector: hasn't moved in a minute (outside screens) - log it so silent freezes show up.
		// (Mining out a vein or crafting in one spot is progress too: the bag changes.)
		int items = 0;
		for (int i = 0; i < 36; i++) items += p.getInventory().getItem(i).getCount();
		boolean bagChanged = items != stuckItems;
		stuckItems = items;
		if (stuckPos == null || p.position().distanceTo(stuckPos) > 2.0 || bagChanged) {
			stuckPos = p.position();
			stuckSince = now;
		} else if (now - stuckSince > 20 * 60 && now - lastStuckWarn > 20 * 60) {
			lastStuckWarn = now;
			HumanAutopilot.LOGGER.warn("[survival] STUCK for {}s: {}: {}", (now - stuckSince) / 20, stage, status);
		}
		String line = stage + ": " + status;
		String kind = line.replaceAll("[0-9(),/=>-]", "");
		if (!kind.equals(lastLogged)) {
			checkDither(kind, now);
			lastLogged = kind;
			// A flip-flop between two things would print two lines a tick: show each one at most every 2 s.
			Long seen = loggedAt.put(kind, now);
			if (seen != null && now - seen < 40) return line;
			if (loggedAt.size() > 200) loggedAt.clear();
			HumanAutopilot.LOGGER.info("[survival] {}{} | logs={} planks={} sticks={} cobble={} coal={} raw={} iron={} dia={} at {}", line, why.isEmpty() ? "" : " (because " + why + ")",
					Inv.count(p, Inv.LOG), Inv.count(p, Inv.PLANK), Inv.count(p, Inv.STICK), Inv.count(p, Inv.COBBLE),
					Inv.count(p, Inv.COAL), Inv.count(p, Inv.RAW_IRON), Inv.count(p, Inv.IRON), Inv.count(p, Inv.DIAMOND),
					p.blockPosition().toShortString());
		}
		return line;
	}

	// Dither detection: A -> B -> A -> B ... within a short time is the robotic flip-flop we must not do.
	private final java.util.ArrayDeque<String> recentKinds = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Long> recentTimes = new java.util.ArrayDeque<>();
	private long lastDitherWarn;
	private Vec3 stuckPos;
	private long stuckSince, lastStuckWarn;
	private final Map<String, Long> loggedAt = new HashMap<>();
	private int stuckItems;

	private void checkDither(String kind, long now) {
		recentKinds.addLast(kind);
		recentTimes.addLast(now);
		while (!recentTimes.isEmpty() && now - recentTimes.peekFirst() > 400) {
			recentTimes.pollFirst();
			recentKinds.pollFirst();
		}
		if (recentKinds.size() < 8 || now - lastDitherWarn < 400) return;
		java.util.Set<String> distinct = new java.util.HashSet<>(recentKinds);
		if (distinct.size() <= 2) {
			lastDitherWarn = now;
			HumanAutopilot.LOGGER.warn("[survival] DITHERING between {}", distinct);
		}
	}

	private void noteFailure(String key) {
		int n = failures.merge(key, 1, Integer::sum);
		if (n >= 3) {
			cooldownUntil.put(key, HumanAutopilot.ticks() + 20 * 30);
			failures.put(key, 0);
			HumanAutopilot.message("Survival: '" + key + "' keeps failing; trying something else for a bit.");
		}
	}

	private boolean coolingDown(String key) {
		Long until = cooldownUntil.get(key);
		return until != null && HumanAutopilot.ticks() < until;
	}

	private String think(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat, long now) {
		why = "";
		int logs = Inv.count(p, Inv.LOG), planks = Inv.count(p, Inv.PLANK), sticks = Inv.count(p, Inv.STICK);
		int cobble = Inv.count(p, Inv.COBBLE), raw = Inv.count(p, Inv.RAW_IRON), iron = Inv.count(p, Inv.IRON);
		int dia = Inv.count(p, Inv.DIAMOND);
		int pick = Inv.pickaxeTier(p), sword = Inv.swordTier(p);
		// A pack-up we started and then changed our mind about: drop it, so it doesn't look "busy" forever.
		if (picker.busy() && now - pickerUsedAt > 10) picker.stop();
		refreshPlaced();

		// Bags filling up and home is near: take things home rather than throwing them away.
		String dep = depositAtHome(p, in, aim);
		if (dep != null) {
			stage = "home";
			return dep;
		}

		// Housekeeping first: room in the bags and the right tools in the hotbar.
		List<Predicate<ItemStack>> wanted = hotbarWants(p, pick, sword);
		boolean missing = false;
		for (Predicate<ItemStack> w : wanted) if (Inv.inMain(p, w) && !Inv.inHotbar(p, w)) missing = true;
		int free = Inv.freeSlots(p);
		// A person sorts now and then, not every few seconds: rate-limit both reasons.
		boolean bagsFull = Inv.hasJunk(p) && ((free <= 1 && now >= nextJunkSort - 20 * 20) || (free <= 3 && now >= nextJunkSort));
		// Toss outclassed tools like a player does, but up top: in a tunnel we'd walk back over them on the way out.
		boolean oldTools = Inv.hasObsoleteTools(p) && now >= nextObsoleteToss && !underground(p);
		double tossYaw = bagsFull || oldTools ? openYaw(p) : Double.NaN;
		if (Double.isNaN(tossYaw)) {
			// Nowhere to throw it (a tunnel, a staircase): it would land at our feet. Wait till we're somewhere open,
			// unless the bags are completely full.
			if (oldTools) nextObsoleteToss = now + 20 * 10;
			if (bagsFull && free > 1) nextJunkSort = now + 20 * 10;
			oldTools = false;
			bagsFull = bagsFull && free <= 1;
		}
		boolean junkTime = bagsFull || oldTools;
		boolean hotbarTime = missing && now >= nextHotbarSort;
		if ((junkTime || hotbarTime) && !coolingDown("sorting inventory")) {
			HumanAutopilot.LOGGER.info("[survival] sorting: junk={} oldTools={} hotbar={} free={}", bagsFull, oldTools, hotbarTime, free);
			nextHotbarSort = now + 20 * 20;
			screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(wanted, junkTime));
			if (junkTime) {
				nextJunkSort = now + 20 * 30;
				// Threw the old tools out; if one somehow comes back, don't make a ritual of it.
				if (oldTools) nextObsoleteToss = now + 20 * 300;
				why = Double.isNaN(tossYaw) ? "bags are full" : oldTools ? "tossing tools I've outgrown" : "bags are nearly full";
				screen.faceFirst(Double.isNaN(tossYaw) ? p.getYRot() + 180 : tossYaw); // throw it onto open ground
			}
			return "sorting inventory";
		}

		// Nearly done and we're standing by the furnace: wait for it rather than walk off and come all the way back.
		if (cookingUntil != 0 && now < cookingUntil && cookingUntil - now < 20 * 30) {
			BlockPos f = furnaceNearby(p);
			if (f != null && p.blockPosition().distSqr(f) < 8 * 8) {
				if (now - cookWaitLook > Human.rangeInt(80, 160)) cookWaitLook = now;
				if (now - cookWaitLook < 30) aim.lookAt(f, () -> Vec3.atCenterOf(f)); // a glance at the arrow now and then
				why = "it's nearly done; no point walking off and coming back";
				return "waiting for the furnace (" + (cookingUntil - now) / 20 + "s)";
			}
		}
		// Something has been cooking while we did other things: go back and take it out.
		if (cookingUntil != 0 && now >= cookingUntil) {
			BlockPos f = furnaceNearby(p);
			if (f == null || p.blockPosition().distSqr(f) > 64 * 64) {
				cookingUntil = 0; // too far or gone: let it go
			} else if (furnaceOpenFails >= 2) {
				// Can't open it: break it, its contents spill out and we pick them up.
				String s = packUp(p, in, aim, f, Blocks.FURNACE);
				if (s != null) return s;
				cookingUntil = 0;
				furnaceOpenFails = 0;
			} else {
				String w = walkTo(p, in, aim, f);
				if (w != null) return "going back to the furnace";
				screen.start(ScreenWorker.Kind.FURNACE, f, ScreenWorker.SmeltJob.collect());
				return "taking things out of the furnace";
			}
		}

		// Just respawned: run back for our things first.
		String rec = recoverItems(p, in, aim, now);
		if (rec != null) {
			stage = "after dying";
			return rec;
		}

		// Spawned or wandered onto a forest canopy: break the leaves underfoot and drop down a bit at a time.
		String down = offTheCanopy(p, in, aim);
		if (down != null) {
			stage = "getting down";
			return down;
		}

		// Night: sleep if we have a bed (and pack it up in the morning).
		String night = nightTime(p, in, aim, now);
		if (night != null) {
			stage = "night";
			return night;
		}

		// Felled a tree a moment ago: plant a sapling where it stood, now and then, like the recorded player.
		String plant = replant(p, in, aim, now);
		if (plant != null) {
			stage = "replanting";
			return plant;
		}

		// A chest we came across (a village, a shipwreck, a mineshaft): have a look inside, like the recorded player did.
		String loot = lootOnTheWay(p, in, aim, now);
		if (loot != null) {
			stage = "a chest!";
			return loot;
		}

		// Food on the way, like the recorded player: a pig that's right there, a berry bush we're passing.
		String snack = foodOnTheWay(p, in, aim, combat, now);
		if (snack != null) {
			stage = "food on the way";
			return snack;
		}

		// 1. Wooden pickaxe
		// (Already got cobblestone, e.g. a pickaxe just broke underground? Skip straight to stone.)
		if (pick == 0 && cobble < 3) {
			stage = "getting started";
			if (Inv.axeTier(p) == 0) {
				// Like the player we recorded: an axe first, it makes the rest of the chopping quicker.
				why = "an axe first makes chopping the rest quicker";
				String s = needWood(p, in, aim, 3, 2, true, logs, planks, sticks);
				if (s != null) return s;
				return craft(p, in, aim, Recipe.WOOD_AXE);
			}
			String s = needWood(p, in, aim, 3, 2, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.WOOD_PICK);
		}
		// 2. The stone age, the way the recorded player did it: a stone pickaxe and axe as soon as there's cobble for
		// both (~6), at one sitting; the furnace when there's 8 more, the shovel with it; a sword only once monsters
		// are about or evening comes (they made theirs on the spot when two skeletons showed up).
		if (!CombatModule.hostilesAround(p, 24).isEmpty()) lastHostileSeen = now;
		int stoneSet = (pick < 2 ? 3 : 0) + (Inv.axeTier(p) < 2 ? 3 : 0);
		if (pick >= 1 && stoneSet > 0 && cobble < stoneSet && (stoneRun || cobble < 3)) {
			stoneRun = true;
			stage = "stone age";
			return gatherStone(p, in, aim, cobble, stoneSet);
		}
		stoneRun = false;
		if (pick < 2) {
			stage = "stone age";
			if (cobble < 3) return gatherStone(p, in, aim, cobble, 3);
			String s = needWood(p, in, aim, 0, 2, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.STONE_PICK);
		}
		if (Inv.axeTier(p) < 2) {
			// A stone axe makes chopping trees a lot quicker.
			stage = "stone age";
			if (cobble < 3) return gatherStone(p, in, aim, cobble, 3);
			String s = needWood(p, in, aim, 0, 2, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.STONE_AXE);
		}
		if (sword < 2 && wantSword(p)) {
			stage = "stone age";
			why = "monsters about (or night coming): time for a sword";
			if (cobble < 2) return gatherStone(p, in, aim, cobble, 2);
			String s = needWood(p, in, aim, 0, 1, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.STONE_SWORD);
		}
		// 3. Furnace (and the shovel at the same sitting)
		boolean haveFurnace = Inv.has(p, Inv.FURNACE) || furnace != null;
		if (!haveFurnace) {
			stage = "making a furnace";
			int stone = 8 + (Inv.shovelTier(p) < 2 ? 1 : 0);
			if (cobble < stone) return gatherStone(p, in, aim, cobble, stone);
			String s = needWood(p, in, aim, 0, 0, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.FURNACE);
		}
		if (Inv.shovelTier(p) < 2) {
			// A shovel for the dirt and gravel on the way down, like the player we recorded.
			stage = "stone age";
			if (cobble < 1) return gatherStone(p, in, aim, cobble, 1);
			String s = needWood(p, in, aim, 0, 2, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.STONE_SHOVEL);
		}
		// Carry a spare pickaxe before a long dig, like a player does (made while the table is still out).
		String spare = sparePickaxe(p, in, aim, cobble, planks, sticks);
		if (spare != null) return spare;
		// 4. Food
		int rawMeat = Inv.count(p, Inv.RAW_MEAT);
		if (rawMeat >= 3 && !coolingDown("smelting")) {
			stage = "cooking";
			var meat = Inv.mostOf(p, Inv.RAW_MEAT);
			String s = smelt(p, in, aim, st -> st.is(meat), Math.min(Inv.count(p, st -> st.is(meat)), 16), logs, planks);
			if (s != null) return s;
		}
		boolean dark = Terrain.level().isDarkOutside();
		// At night only go hunting if actually hungry; otherwise leave it till morning.
		if (Inv.foodPoints(p) < (dark ? 6 : 14) && now >= foodCooldownUntil && cookingUntil == 0) {
			stage = "getting food";
			String s = getFood(p, in, aim, combat, now);
			if (s != null) return s;
		}
		// 4b. A chest at home for the things we don't carry around
		String ch = setUpChest(p, in, aim, logs, planks, sticks);
		if (ch != null) {
			stage = "setting up home";
			return ch;
		}
		// 5. Iron pickaxe
		if (pick < 3) {
			stage = "iron age";
			if (iron >= 3) {
				String s = needWood(p, in, aim, 0, 2, true, logs, planks, sticks);
				if (s != null) return s;
				return craft(p, in, aim, Recipe.IRON_PICK);
			}
			if (raw + iron >= 3 && !coolingDown("smelting")) {
				String s = smelt(p, in, aim, Inv.RAW_IRON, raw, logs, planks);
				if (s != null) return s;
			}
			return mineOres(p, in, aim, IRON_Y, ironTargets(p));
		}
		if (sword < 3 && iron >= 2) {
			stage = "iron age";
			String s = needWood(p, in, aim, 0, 1, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.IRON_SWORD);
		}
		if (sword < 3 && raw >= 2 && !coolingDown("smelting")) {
			String s = smelt(p, in, aim, Inv.RAW_IRON, raw, logs, planks);
			if (s != null) return s;
		}
		// 5b. Iron gear: wear what we have, then a shield and armor pieces as iron comes in.
		String gear = ironGear(p, in, aim, iron, raw, logs, planks, sticks);
		if (gear != null) return gear;
		String bedTask = getBed(p, in, aim, combat, logs, planks, sticks);
		if (bedTask != null) return bedTask;
		// 6. Diamonds
		stage = "diamond hunting";
		if (dia >= 3 && pick < 4) {
			String s = needWood(p, in, aim, 0, 2, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.DIAMOND_PICK);
		}
		if (dia >= 2 && pick >= 4 && sword < 4) {
			String s = needWood(p, in, aim, 0, 1, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.DIAMOND_SWORD);
		}
		return mineOres(p, in, aim, DIAMOND_Y, diamondTargets(p));
	}

	// ------------------------------------------------------------------ home & chest

	private BlockPos home;
	private BlockPos chest;
	private final Placer chestPlacer = new Placer();
	private final Navigator homeRun = new Navigator();
	private boolean depositing;

	/** First surface setup spot becomes home. */
	private void noteHome(LocalPlayer p, BlockPos where) {
		if (home == null && !underground(p)) {
			home = where;
			HumanAutopilot.LOGGER.info("[survival] home is at {}", where.toShortString());
		}
	}

	/** Make a chest and put it down at home, like a player setting up a base. */
	private String setUpChest(LocalPlayer p, InputDriver in, AimController aim, int logs, int planks, int sticks) {
		// Just put it down? Record it before deciding anything else (it's no longer in the bag).
		BlockPos spot = chestPlacer.spot();
		if (chest == null && spot != null) {
			BlockPos found = Terrain.state(spot).is(Blocks.CHEST) ? spot : findNear(spot, Blocks.CHEST);
			if (found != null) {
				chest = found;
				chestPlacer.reset();
				HumanAutopilot.LOGGER.info("[survival] chest placed at {}", chest.toShortString());
				return null;
			}
		}
		if (home == null || chest != null) return null;
		if (!Inv.has(p, Inv.CHEST)) {
			if (planks < 8 && coolingDown("wood")) return null;
			String s = needWood(p, in, aim, 8, 0, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.CHEST);
		}
		if (p.blockPosition().distSqr(home) > 4 * 4) {
			if (!homeRun.active()) homeRun.go(new Goal.Near(Vec3.atCenterOf(home), 3, false), false);
			if (homeRun.tick(p, in, aim) == Navigator.Status.FAILED) home = p.blockPosition(); // can't get back: this is home now
			return "heading home to put a chest down";
		}
		Placer.Result r = chestPlacer.tick(p, in, aim, Inv.CHEST, Blocks.CHEST);
		switch (r) {
			case DONE -> {
				chest = chestPlacer.placed();
				chestPlacer.reset();
				HumanAutopilot.LOGGER.info("[survival] chest placed at {}", chest.toShortString());
				return null;
			}
			case NOT_IN_HOTBAR -> {
				List<Predicate<ItemStack>> wants = new ArrayList<>(hotbarWants(p, Inv.pickaxeTier(p), Inv.swordTier(p)));
				wants.add(0, Inv.CHEST);
				screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(wants));
				return "getting the chest out";
			}
			case NO_SPOT, FAILED -> {
				chestPlacer.reset();
				home = null; // pick somewhere else to settle
				return null;
			}
			default -> {
				return "putting a chest down at home";
			}
		}
	}

	/** Bags getting full and home isn't far: walk back and put things away instead of throwing them out. */
	private String depositAtHome(LocalPlayer p, InputDriver in, AimController aim) {
		if (chest == null) return null;
		if (!Terrain.state(chest).is(Blocks.CHEST)) {
			chest = null;
			return null;
		}
		int free = Inv.freeSlots(p);
		if (!depositing && free > 6) return null;
		if (!depositing) {
			if (p.blockPosition().distSqr(chest) > 160 * 160 || coolingDown("putting things in the chest")) return null;
			// Deep in a mine and not near home: nobody walks back just to drop off cobblestone. Full bags get
			// sorted (junk tossed) instead; we deposit when we're up top or passing by.
			if (underground(p) && p.blockPosition().distSqr(chest) > 24 * 24) return null;
			boolean anything = false;
			for (int i = 0; i < 36; i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (!s.isEmpty() && !Inv.keepOnPerson(s)) anything = true;
			}
			if (!anything) return null;
			depositing = true;
		}
		double reach = p.blockInteractionRange() - 0.5;
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) > reach) {
			if (!homeRun.active()) homeRun.go(new Goal.Near(Vec3.atCenterOf(chest), reach - 0.8, true), underground(p) && Inv.pickaxeTier(p) > 0);
			if (homeRun.tick(p, in, aim) == Navigator.Status.FAILED) {
				// Can't get there from here: don't try again every tick (that flip-flops with whatever else we do).
				depositing = false;
				cooldownUntil.put("putting things in the chest", HumanAutopilot.ticks() + 20 * 120);
				HumanAutopilot.LOGGER.info("[survival] can't get home to the chest ({}); trying again later", homeRun.failReason());
				return null;
			}
			return String.format("heading home to drop stuff off (%.0fm)", Math.sqrt(p.blockPosition().distSqr(chest)));
		}
		homeRun.stop();
		depositing = false;
		screen.start(ScreenWorker.Kind.CHEST, chest, new ScreenWorker.DepositJob(s -> !Inv.keepOnPerson(s)));
		return "putting things in the chest";
	}

	// ------------------------------------------------------------------ death

	private BlockPos deathPos, lastDeath;
	private long deathAt;
	private final Navigator corpseRun = new Navigator();
	private net.minecraft.world.entity.item.ItemEntity grabbing;
	private double corpseBest = Double.MAX_VALUE;
	private long corpseProgressAt;

	/** Called when we die: remember where, so we can run back for our stuff. */
	public void onDeath(BlockPos where) {
		long now = HumanAutopilot.ticks();
		// Died again on the way back (or right where we died before): whatever killed us is still there.
		// A player cuts their losses rather than run into it a third time.
		boolean again = deathAt != 0 && now - deathAt < 20 * 240
				&& (deathPos == null || deathPos.closerThan(where, 48) || lastDeath != null && lastDeath.closerThan(where, 48));
		lastDeath = where;
		if (again) {
			HumanAutopilot.LOGGER.info("[survival] died again near {}: not going back for that stuff", where.toShortString());
			deathPos = null;
			deathAt = now;
			corpseRun.stop();
			cookingUntil = 0;
			table = null;
			furnace = null;
			bed = null;
			return;
		}
		deathPos = where;
		deathAt = now;
		corpseBest = Double.MAX_VALUE;
		corpseProgressAt = deathAt;
		cookingUntil = 0;
		table = null;
		furnace = null;
		bed = null;
	}

	/** Items despawn after 5 minutes: go back to where we died and grab what's still there. */
	private String recoverItems(LocalPlayer p, InputDriver in, AimController aim, long now) {
		if (deathPos == null) return null;
		if (now - deathAt > 20 * 280) {
			deathPos = null;
			corpseRun.stop();
			return null;
		}
		double dist = Math.sqrt(p.blockPosition().distSqr(deathPos));
		if (dist < corpseBest - 1) {
			corpseBest = dist;
			corpseProgressAt = now;
		} else if (now - corpseProgressAt > 20 * 30) {
			deathPos = null; // can't get any closer (under water, in a cave...): let it go
			corpseRun.stop();
			return null;
		}
		if (dist > 6) {
			if (!corpseRun.active()) corpseRun.go(new Goal.Near(Vec3.atCenterOf(deathPos), 3, false), false);
			if (corpseRun.tick(p, in, aim) == Navigator.Status.FAILED) {
				deathPos = null; // can't get back there
				return null;
			}
			return String.format("running back for my stuff (%.0fm)", dist);
		}
		if (grabbing == null || !grabbing.isAlive()) {
			grabbing = null;
			for (var e : p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(10, 4, 10))) {
				if (Inv.notWorthPickingUp(p, e)) continue;
				if (grabbing == null || p.distanceToSqr(e) < p.distanceToSqr(grabbing)) grabbing = e;
			}
			if (grabbing == null) {
				deathPos = null;
				return null;
			}
			corpseRun.go(new Goal.Near(grabbing.position(), 0.8, false), false);
		}
		var st = corpseRun.tick(p, in, aim);
		if (st == Navigator.Status.ARRIVED || st == Navigator.Status.FAILED) grabbing = null;
		return "picking my stuff back up";
	}

	private final dev.humanautopilot.pathing.Digger leafDigger = new dev.humanautopilot.pathing.Digger();

	private boolean descending;

	private String offTheCanopy(LocalPlayer p, InputDriver in, AimController aim) {
		// Dropping to the next layer of leaves: still climbing down (don't start chopping mid-air).
		if (!p.onGround()) return descending && !p.isInWater() ? "climbing down through the leaves" : null;
		BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		var s = Terrain.state(below);
		if (!(s.getBlock() instanceof LeavesBlock) || s.getValue(LeavesBlock.PERSISTENT)) {
			descending = false; // back on real ground
			return null;
		}
		int x = below.getX(), z = below.getZ();
		// How far down is real (non-leaf) ground, and what's the next thing we'd land on?
		int ground = -1, landing = -1;
		for (int d = 1; d <= 16; d++) {
			int y = below.getY() - d;
			if (Terrain.lava(x, y, z) || Terrain.water(x, y, z)) return null;
			var t = Terrain.state(x, y, z);
			if (t.getCollisionShape(Terrain.level(), new BlockPos(x, y, z)).isEmpty()) continue;
			if (landing < 0) landing = d;
			if (!(t.getBlock() instanceof LeavesBlock)) {
				ground = d;
				break;
			}
		}
		// Only when really up in the trees (not standing on a low bush or the crown we're chopping);
		// once started, keep going until we're down instead of deciding afresh every moment.
		if (!descending && (ground >= 0 && ground < 3)) return null;
		// Up on the leaves of the tree we're chopping (a log right there in reach, the ground a few blocks down):
		// that's where we mean to be, not a canopy to climb off.
		if (!descending && ground >= 0 && ground <= 4 && logInReach(p)) return null;
		// 3 or less is free; with plenty of health a player just drops a bit further (a heart or so) to get down.
		// Stuck up a big tree at full health, a player just takes the drop (8 blocks is ~2.5 hearts) rather than wander
		// about on the canopy.
		int okDrop = HumanAutopilot.config().maxSafeDrop + (p.getHealth() >= 18 ? 6 : p.getHealth() >= 16 ? 3 : 0);
		if (landing < 0 || landing > okDrop) {
			if (HumanAutopilot.ticks() % 100 == 0) HumanAutopilot.LOGGER.info("[survival] on leaves, drop too far (landing {} ground {})", landing, ground);
			return null;
		}
		descending = true;
		leafDigger.digUnderfoot(below, p, in, aim);
		return "climbing down through the leaves";
	}

	// ------------------------------------------------------------------ night & bed

	private final Placer bedPlacer = new Placer();
	private BlockPos bed;
	private int sleepClicks;
	private long nextSleepClick;

	{
		bedPlacer.setTwoBlocks(true);
	}

	/** At night: put the bed down on the surface and sleep. In the morning: pack it up again. */
	private String nightTime(LocalPlayer p, InputDriver in, AimController aim, long now) {
		var lvl = Terrain.level();
		if (lvl.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
		if (!lvl.isDarkOutside()) {
			sleepClicks = 0;
			if (bed == null) return null;
			BlockPos b = bed;
			if (!Terrain.state(b).is(BlockTags.BEDS) && !picker.busy()) {
				bed = null;
				return null;
			}
			pickerUsedAt = now;
			picker.startCustom("bed",(pos, s) -> s.is(BlockTags.BEDS) && pos.closerThan(b, 2), false);
			String s = picker.tick(p, in, aim);
			if (s == null || picker.waitingForOre()) {
				bed = null; // picked up (or can't reach it; leave it)
				picker.stop();
				return null;
			}
			return "packing up the bed";
		}
		if (bed == null && !Inv.has(p, Inv.BED)) return null;
		if (sleepClicks >= 4) return null; // monsters around or not allowed: get on with things
		if (bed == null) {
			String up = surfaceFirst(p, in, aim);
			if (up != null) return "going up to sleep";
			Placer.Result r = bedPlacer.tick(p, in, aim, Inv.BED, Blocks.WHITE_BED);
			switch (r) {
				case DONE -> {
					bed = bedPlacer.placed();
					bedPlacer.reset();
				}
				case NOT_IN_HOTBAR -> {
					List<Predicate<ItemStack>> wants = new ArrayList<>(hotbarWants(p, Inv.pickaxeTier(p), Inv.swordTier(p)));
					wants.add(0, Inv.BED);
					screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(wants));
					return "getting the bed out";
				}
				case NO_SPOT, FAILED -> {
					bedPlacer.reset();
					return explore(p, in, aim, "looking for a spot for the bed");
				}
				default -> {
				}
			}
			return "putting the bed down";
		}
		String w = walkTo(p, in, aim, bed);
		if (w != null) return w;
		BlockPos b = bed;
		aim.lookAt(b, () -> Vec3.atCenterOf(b).add(0, 0.1, 0));
		var hr = Minecraft.getInstance().hitResult;
		if (now >= nextSleepClick && hr instanceof net.minecraft.world.phys.BlockHitResult bhr
				&& Terrain.state(bhr.getBlockPos()).is(BlockTags.BEDS) && aim.error() < 5) {
			in.click(InputDriver.opts().keyUse);
			sleepClicks++;
			nextSleepClick = now + 40;
		}
		return "going to bed";
	}

	/** Wool from sheep, then a bed: sleeping skips the night like a player would. */
	private String getBed(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat, int logs, int planks, int sticks) {
		if (bed != null || Inv.has(p, Inv.BED)) return null;
		var wool = Inv.bedWool(p);
		if (wool != null) {
			stage = "making a bed";
			if (planks < 3 && coolingDown("wood")) return null;
			String s = needWood(p, in, aim, 3, 0, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.bed(wool));
		}
		if (underground(p) || coolingDown("wool") || Terrain.level().isDarkOutside()) return null;
		hunter.setSheepOnly(true);
		Hunter.Result r = hunter.tick(p, in, aim, combat);
		hunter.setSheepOnly(false);
		if (r == Hunter.Result.NONE_AROUND) {
			cooldownUntil.put("wool", HumanAutopilot.ticks() + 20 * 300);
			return null;
		}
		stage = "making a bed";
		return "getting wool: " + hunter.status();
	}

	/** Stone-or-better pickaxes that aren't about to break. */
	private static int workingPickaxes(LocalPlayer p) {
		int working = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (Inv.isPickaxe(s) && tierOf(s, true) >= 2 && s.getMaxDamage() - s.getDamageValue() > HumanAutopilot.config().minToolDurability) working++;
		}
		return working;
	}

	private String sparePickaxe(LocalPlayer p, InputDriver in, AimController aim, int cobble, int planks, int sticks) {
		int working = workingPickaxes(p);
		// With an iron pickaxe we still want a stone one for plain rock (saves the iron one for ore).
		int stoneWorking = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (Inv.isPickaxe(s) && tierOf(s, true) == 2 && s.getMaxDamage() - s.getDamageValue() > HumanAutopilot.config().minToolDurability) stoneWorking++;
		}
		boolean wantRockPick = Inv.pickaxeTier(p) >= 3 && stoneWorking == 0;
		if ((working >= 2 && !wantRockPick) || cobble < 3 || coolingDown("crafting stone pickaxe")) return null;
		// Only while a table is standing right here: nobody puts one down halfway down the stairs for a spare.
		// (Or, for a worn-out rock pickaxe deep down, the table we carry: worth putting down for that.)
		boolean tableHere = table != null && p.blockPosition().distSqr(table) < 10 * 10
				|| wantRockPick && Inv.has(p, Inv.TABLE);
		if (!tableHere || (sticks < 2 && planks < 2)) return null; // not worth a trip for
		why = "only " + working + " working pickaxe before a long dig";
		String s = needWood(p, in, aim, 0, 2, true, Inv.count(p, Inv.LOG), planks, sticks);
		if (s != null) return s;
		stage = "getting ready to dig";
		return craft(p, in, aim, Recipe.STONE_PICK);
	}

	// ------------------------------------------------------------------ gear

	private record Piece(Recipe recipe, net.minecraft.world.item.Item item, net.minecraft.world.entity.EquipmentSlot slot, int cost) {}

	private static final List<Piece> ARMOR = List.of(
			new Piece(Recipe.IRON_HELMET, Items.IRON_HELMET, net.minecraft.world.entity.EquipmentSlot.HEAD, 5),
			new Piece(Recipe.IRON_BOOTS, Items.IRON_BOOTS, net.minecraft.world.entity.EquipmentSlot.FEET, 4),
			new Piece(Recipe.IRON_LEGS, Items.IRON_LEGGINGS, net.minecraft.world.entity.EquipmentSlot.LEGS, 7),
			new Piece(Recipe.IRON_CHEST, Items.IRON_CHESTPLATE, net.minecraft.world.entity.EquipmentSlot.CHEST, 8));

	private String ironGear(LocalPlayer p, InputDriver in, AimController aim, int iron, int raw, int logs, int planks, int sticks) {
		// Anything wearable sitting in the bag? Put it on.
		boolean toWear = false;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			var slot = p.getEquipmentSlotForItem(s);
			boolean armor = slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR;
			boolean shield = s.is(Items.SHIELD);
			ItemStack worn = p.getItemBySlot(slot);
			if ((armor || shield) && (worn.isEmpty() || (shield && Inv.TORCH.test(worn)))) toWear = true;
		}
		if (!toWear) equipTries = 0;
		if (toWear && equipTries < 3) {
			equipTries++;
			stage = "gearing up";
			screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.EquipJob());
			return "putting on gear";
		}
		// Smelt the iron we've been collecting.
		boolean noWood = coolingDown("wood") && Inv.count(p, Inv.COAL) * 8 < raw;
		if (raw >= 4 && !coolingDown("smelting") && !noWood) {
			stage = "gearing up";
			String s = smelt(p, in, aim, Inv.RAW_IRON, raw, logs, planks);
			if (s != null) return s;
		}
		if ((p.getOffhandItem().isEmpty() || Inv.TORCH.test(p.getOffhandItem())) && !Inv.has(p, s -> s.is(Items.SHIELD)) && iron >= 1
				&& (planks >= 6 || !coolingDown("wood"))) {
			stage = "gearing up";
			String s = needWood(p, in, aim, 6, 0, true, logs, planks, sticks);
			if (s != null) return s;
			return craft(p, in, aim, Recipe.SHIELD);
		}
		for (Piece piece : ARMOR) {
			if (p.getItemBySlot(piece.slot()).isEmpty() && !Inv.has(p, s -> s.is(piece.item())) && iron >= piece.cost()) {
				stage = "gearing up";
				String s = needWood(p, in, aim, 0, 0, true, logs, planks, sticks);
				if (s != null) return s;
				return craft(p, in, aim, piece.recipe());
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ wood

	/** Make sure we have the planks/sticks (and a table) needed. Returns an action status, or null if we're set. */
	/**
	 * Standing next to our table on the surface and about to dig down: walk 5-8 blocks off first (away from it), so
	 * the stairs don't undermine the table. Returns a status while walking, null once we're somewhere fine to dig.
	 */
	private String stepAwayFromTable(LocalPlayer p, InputDriver in, AimController aim) {
		BlockPos t = table;
		long now = HumanAutopilot.ticks();
		// Once per trip for stone (per table): after that, dig wherever we are.
		if (t == null || underground(p) || (t.equals(steppedAwayFrom) && now - steppedAt < 20 * 120)) {
			digSpot = null;
			return null;
		}
		if (digSpot == null) {
			double dist = Math.hypot(p.getX() - (t.getX() + 0.5), p.getZ() - (t.getZ() + 0.5));
			digSpot = dist >= 4.5 ? null : pickDigSpot(p, t);
			if (digSpot == null) {
				steppedAwayFrom = t; // already a fair way off (or nowhere better): dig here
				steppedAt = now;
				return null;
			}
			stepAway.go(new Goal.Near(Vec3.atBottomCenterOf(digSpot), 1.0, false), false);
			faceAwayUntil = 0;
		}
		boolean there = p.position().distanceTo(Vec3.atBottomCenterOf(digSpot)) < 1.6;
		if (!there) {
			Navigator.Status st = stepAway.tick(p, in, aim);
			if (st != Navigator.Status.FAILED && st != Navigator.Status.IDLE && st != Navigator.Status.ARRIVED) {
				return "walking a few steps away from the table to dig";
			}
		}
		// There (or as close as we'll get): face away from the table for a moment, so the stairs lead off that way.
		if (faceAwayUntil == 0) faceAwayUntil = now + 12;
		double yaw = Angles.yawTo(Vec3.atCenterOf(t), p.position());
		if (now < faceAwayUntil && Math.abs(Angles.wrap(yaw - p.getYRot())) > 15) {
			aim.lookDirection("dig-away", yaw, 30);
			return "turning away from the table to dig";
		}
		stepAway.stop();
		digSpot = null;
		steppedAwayFrom = t;
		steppedAt = now;
		return null;
	}

	/** Somewhere 5-8 blocks from the table, on dry level ground, roughly on our side of it. */
	private static BlockPos pickDigSpot(LocalPlayer p, BlockPos table) {
		double base = Angles.yawTo(Vec3.atCenterOf(table), p.position());
		for (int i = 0; i < 16; i++) {
			double yaw = Math.toRadians(base + Human.range(-70, 70) + (i >= 8 ? 180 : 0));
			double d = Human.range(5, 8);
			int x = Mth.floor(table.getX() + 0.5 - Math.sin(yaw) * d), z = Mth.floor(table.getZ() + 0.5 + Math.cos(yaw) * d);
			if (!Terrain.loaded(x, z)) continue;
			for (int y = p.getBlockY() + 2; y >= p.getBlockY() - 2; y--) {
				if (Terrain.canStand(x, y, z) && !Terrain.water(x, y - 1, z) && !Terrain.nearLava(x, y, z)) return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	private String needWood(LocalPlayer p, InputDriver in, AimController aim, int planksNeeded, int sticksNeeded,
							boolean needTable, int logs, int planks, int sticks) {
		boolean haveTable = Inv.has(p, Inv.TABLE) || tableNearby(p) != null;
		int stickCrafts = (Math.max(0, sticksNeeded - sticks) + 3) / 4;
		int plankTotal = planksNeeded + stickCrafts * 2 + (needTable && !haveTable ? 4 : 0);
		if (planks < plankTotal) {
			int crafts = (plankTotal - planks + 3) / 4;
			why = "need " + plankTotal + " planks (" + planksNeeded + " for the item, " + stickCrafts * 2 + " for sticks" + (needTable && !haveTable ? ", 4 for a table" : "") + "), have " + planks + " planks + " + logs + " logs";
			if (logs < crafts) {
				// While we're at it, take the whole tree (at least ~6 logs), like a player would.
				woodTarget = Math.max(woodTarget, logs + Math.max(crafts - logs + 1, 6));
				return gatherLogs(p, in, aim, crafts - logs + 1);
			}
			if (logs < woodTarget && treeNearby(p)) return gatherLogs(p, in, aim, woodTarget - logs);
			woodTarget = 0;
			// No separate trips into the grid for planks: they're made in the same sitting as the table (in the
			// inventory) or as the tool (at the table), the way a player lays it all out at once.
			if (needTable && !haveTable) return craft(p, in, aim, Recipe.TABLE);
			if (needTable) return null;
			if (stickCrafts > 0) return craft(p, in, aim, Recipe.STICKS, stickCrafts);
			return craft(p, in, aim, Recipe.PLANKS, crafts);
		}
		if (needTable && !haveTable) return craft(p, in, aim, Recipe.TABLE);
		// Sticks for a table recipe are made at the table along with it.
		if (stickCrafts > 0 && !needTable) return craft(p, in, aim, Recipe.STICKS, stickCrafts);
		return null;
	}

	/**
	 * Dark out and we can dig: chopping trees and chasing animals can wait for morning. Underground we stay
	 * down; up top we dig in, the classic first-night move (a bed changes that: see nightTime).
	 */
	private static boolean stayDownTonight(LocalPlayer p) {
		return Terrain.level().isDarkOutside() && Inv.pickaxeTier(p) > 0;
	}

	/** Night, underground, and the next job is up top: keep mining down here until morning, like a player would. */
	private String mineUntilMorning(LocalPlayer p, InputDriver in, AimController aim, String wanted) {
		String s = mineOres(p, in, aim, Inv.pickaxeTier(p) >= 3 ? DIAMOND_Y : IRON_Y,
				Inv.pickaxeTier(p) >= 3 ? diamondTargets(p) : ironTargets(p));
		why = "it's night; " + wanted + " can wait till morning, I'll keep mining down here";
		return s;
	}

	private String gatherLogs(LocalPlayer p, InputDriver in, AimController aim, int more) {
		long now = HumanAutopilot.ticks();
		if (stayDownTonight(p)) return mineUntilMorning(p, in, aim, "wood");
		String up = surfaceFirst(p, in, aim);
		if (up != null) return up;
		gatherer.startCustom("wood", (pos, st) -> naturalLog(pos, st) && reachableFromGround(pos), true);
		String s = gatherer.tick(p, in, aim);
		if (s == null || gatherer.waitingForOre()) {
			if (woodSearchStarted < 0) woodSearchStarted = now;
			if (now - woodSearchStarted > 20 * 90) {
				// No trees around here: stop chasing wood for optional things for a while.
				cooldownUntil.put("wood", now + 20 * 300);
				woodSearchStarted = -1;
			}
			return explore(p, in, aim, "looking for trees");
		}
		woodSearchStarted = -1;
		return "chopping wood (" + more + " more)";
	}

	/** A natural log close enough to hit from where we stand. */
	private static boolean logInReach(LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		BlockPos c = p.blockPosition();
		double r = p.blockInteractionRange();
		for (BlockPos b : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 5, 4))) {
			if (eye.distanceToSqr(Vec3.atCenterOf(b)) <= r * r && naturalLog(b, Terrain.state(b))) return true;
		}
		return false;
	}

	/** A tree log within a few blocks (the one we're chopping, or its neighbour). */
	private static boolean treeNearby(LocalPlayer p) {
		BlockPos c = p.blockPosition();
		for (BlockPos b : BlockPos.betweenClosed(c.offset(-5, -2, -5), c.offset(5, 8, 5))) {
			if (naturalLog(b, Terrain.state(b))) return true;
		}
		return false;
	}

	/**
	 * Real ground (not leaves) within 5 blocks under this log, so it can be chopped standing on the ground.
	 * A player chops the trunk from below; they don't climb into the canopy for the branches.
	 */
	private static boolean reachableFromGround(BlockPos pos) {
		int x = pos.getX(), z = pos.getZ();
		for (int d = 1; d <= 5; d++) {
			int y = pos.getY() - d;
			var t = Terrain.state(x, y, z);
			if (t.is(BlockTags.LOGS) || t.getBlock() instanceof LeavesBlock) continue;
			if (t.getCollisionShape(Terrain.level(), new BlockPos(x, y, z)).isEmpty()) continue;
			return true;
		}
		return false;
	}

	/** A log that's part of a tree (has natural leaves nearby), so we never chop someone's house. */
	private static boolean naturalLog(BlockPos pos, BlockState s) {
		if (!s.is(BlockTags.LOGS)) return false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= 7; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockState l = Terrain.state(m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
					if (l.getBlock() instanceof LeavesBlock && !l.getValue(LeavesBlock.PERSISTENT)) return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ stone & ores

	private String gatherStone(LocalPlayer p, InputDriver in, AimController aim, int have, int need) {
		why = "need " + need + " cobblestone, have " + have;
		// Already digging our staircase: keep at it, every step down is more stone.
		long nowT = HumanAutopilot.ticks();
		if (stoneDigY != Integer.MIN_VALUE && underground(p) && nowT >= tunnelRetryAt) {
			if (p.getBlockY() <= stoneDigY) stoneDigY = p.getBlockY() - 10;
			if (tunneler.tick(p, in, aim, stoneDigY) != Tunneler.Result.FAILED) {
				return "getting cobblestone (" + have + "/" + need + "): " + tunneler.status();
			}
			tunneler.reset();
			tunnelRetryAt = nowT + 20 * 15; // boxed in here: go somewhere else for a bit
			return explore(p, in, aim, "looking for stone");
		}
		// Already walking off to a dig spot: finish that first (the stone finder looking about would fight it for the eyes).
		if (digSpot != null) {
			String away = stepAwayFromTable(p, in, aim);
			if (away != null) return "getting cobblestone (" + have + "/" + need + "): " + away;
		}
		// Mine the walls, not the floor: a block you could stand on is a step of our own staircase (or the
		// ground we walk on), and digging it out cuts off the way back up.
		gatherer.startCustom("stone", (pos, s) -> (s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE))
				&& !(Terrain.passable(pos.getX(), pos.getY() + 1, pos.getZ()) && Terrain.passable(pos.getX(), pos.getY() + 2, pos.getZ())), true);
		String s = gatherer.tick(p, in, aim);
		if (s == null || gatherer.waitingForOre()) {
			// No bare stone in view: dig down to it. Not right beside the table though (a hole there and we can't
			// get back to it): a few steps away first, then the stairs head off away from it.
			String away = stepAwayFromTable(p, in, aim);
			if (away != null) return "getting cobblestone (" + have + "/" + need + "): " + away;
			if (stoneDigY == Integer.MIN_VALUE || p.getBlockY() <= stoneDigY) stoneDigY = p.getBlockY() - 10;
			if (HumanAutopilot.ticks() < tunnelRetryAt) return explore(p, in, aim, "looking for stone");
			if (tunneler.tick(p, in, aim, stoneDigY) == Tunneler.Result.FAILED) {
				tunneler.reset();
				tunnelRetryAt = HumanAutopilot.ticks() + 20 * 15; // walk a fair way before trying again
				return explore(p, in, aim, "looking for stone");
			}
			return "getting cobblestone (" + have + "/" + need + "): " + tunneler.status();
		}
		return "getting cobblestone (" + have + "/" + need + ")";
	}

	private BiPredicate<BlockPos, BlockState> ironTargets(LocalPlayer p) {
		boolean wantCoal = Inv.count(p, Inv.COAL) < 8;
		return (pos, s) -> s.is(BlockTags.IRON_ORES) || (wantCoal && s.is(BlockTags.COAL_ORES));
	}

	private BiPredicate<BlockPos, BlockState> diamondTargets(LocalPlayer p) {
		boolean wantCoal = Inv.count(p, Inv.COAL) < 8;
		boolean wantIron = Inv.count(p, Inv.IRON) + Inv.count(p, Inv.RAW_IRON) < 12;
		return (pos, s) -> s.is(BlockTags.DIAMOND_ORES) || (wantIron && s.is(BlockTags.IRON_ORES))
				|| (wantCoal && s.is(BlockTags.COAL_ORES)) || s.is(BlockTags.GOLD_ORES);
	}

	private String mineOres(LocalPlayer p, InputDriver in, AimController aim, int targetY,
							BiPredicate<BlockPos, BlockState> what) {
		boolean forDiamonds = targetY == DIAMOND_Y;
		// Pick a working depth like a player would: iron anywhere underground (go down only if we're
		// high up), diamonds deep. Never stay down by the bedrock layers.
		int y = p.getBlockY();
		int floorY = Terrain.level().getMinY();
		if (targetY == IRON_Y) targetY = underground(p) && y <= IRON_Y + 8 ? y : IRON_Y;
		if (y < floorY + 8) targetY = floorY + 12;
		// Before heading down, pack some wood: down there a worn-out pickaxe or a dark tunnel needs sticks and planks.
		// (Not at night: then wood waits for morning, and gatherLogs would send us straight back here.)
		// Almost no wood at all is too risky to dig down with, even if trees were hard to find a while ago.
		boolean woodless = Inv.count(p, Inv.STICK) < 2 && Inv.count(p, Inv.PLANK) < 4;
		// Already on our way into a cave: finish that first. Right at the cave mouth "underground" flips with every
		// step, and wood up top vs. cave down here would rock us back and forth on the spot.
		if (!underground(p) && !caveNav.active() && (woodless || !coolingDown("wood")) && !stayDownTonight(p)) {
			// No coal for torches: burn a couple of logs into charcoal first (2 charcoal = 8 torches).
			// Before packing planks: that turns every log into planks and leaves none to burn.
			boolean haveFurnace = Inv.has(p, Inv.FURNACE) || furnaceNearby(p) != null;
			if (Inv.count(p, Inv.COAL) == 0 && Inv.count(p, Inv.TORCH) < 4 && haveFurnace && cookingUntil == 0
					&& !coolingDown("smelting") && !coolingDown("charcoal")) {
				int lg = Inv.count(p, Inv.LOG);
				if (lg < 2) {
					why = "no coal for torches: I'll make charcoal, which needs a couple of logs";
					return gatherLogs(p, in, aim, 2 - lg);
				}
				String s = smelt(p, in, aim, Inv.LOG, 2, lg, Inv.count(p, Inv.PLANK));
				if (s != null) {
					why = "no coal for torches, so charcoal it is";
					return s;
				}
				cooldownUntil.put("charcoal", HumanAutopilot.ticks() + 20 * 120); // couldn't: don't keep trying
			}
			int pl = Inv.count(p, Inv.PLANK), st = Inv.count(p, Inv.STICK);
			if (st < 4 || pl < 8) {
				String s = needWood(p, in, aim, 8, 4, false, Inv.count(p, Inv.LOG), pl, st);
				if (s != null) {
					why = "taking wood along for new tools and torches down there";
					return s;
				}
			}
		}
		String pickup = leaveSite(p, in, aim);
		if (pickup != null) return pickup;
		why = (forDiamonds ? "diamonds are" : "iron is") + " found around y=" + targetY + ", I'm at y=" + p.getBlockY();
		gatherer.startCustom(forDiamonds ? "diamonds" : "ore", what, true);
		String s = gatherer.tick(p, in, aim);
		if (s == null) {
			// Inventory full: sort (now and then), otherwise keep digging.
			if (Inv.hasJunk(p) && Inv.freeSlots(p) <= 1 && HumanAutopilot.ticks() >= nextJunkSort) {
				nextJunkSort = HumanAutopilot.ticks() + 20 * 30;
				double yaw = openYaw(p);
				screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(hotbarWants(p, Inv.pickaxeTier(p), Inv.swordTier(p)), true));
				screen.faceFirst(Double.isNaN(yaw) ? p.getYRot() + 180 : yaw);
				return "sorting inventory";
			}
		}
		if (s == null || gatherer.waitingForOre()) {
			String light = lightTheWay(p, in, aim);
			if (light != null) return light;
			// A cave nearby shows far more ore than any tunnel: go and have a look, like a player would.
			String cave = exploreCave(p, in, aim);
			if (cave != null) return cave;
			if (HumanAutopilot.ticks() < tunnelRetryAt) return explore(p, in, aim, "looking for a place to dig");
			if (tunneler.tick(p, in, aim, targetY) == Tunneler.Result.FAILED) {
				tunneler.reset();
				tunnelRetryAt = HumanAutopilot.ticks() + 20 * 15; // walk a fair way before trying again
				return explore(p, in, aim, "looking for a place to dig");
			}
			return tunneler.status();
		}
		return gatherer.status();
	}

	// ------------------------------------------------------------------ caves

	private final Navigator caveNav = new Navigator();
	private final java.util.Set<Long> caveSeen = new java.util.HashSet<>(); // 6x6x6 regions already looked at
	private long caveCheckAt, caveLookUntil;
	private double caveLookYaw;

	private static long caveCell(BlockPos b) {
		return BlockPos.asLong(Math.floorDiv(b.getX(), 6), Math.floorDiv(b.getY(), 6), Math.floorDiv(b.getZ(), 6));
	}

	/** Walk into a nearby cave (open space that isn't our own tunnel). Returns a status while doing so, else null. */
	private String exploreCave(LocalPlayer p, InputDriver in, AimController aim) {
		long now = HumanAutopilot.ticks();
		if (caveNav.active()) { // once on our way, keep going (don't flip-flop at the edge of "underground")
			var st = caveNav.tick(p, in, aim);
			if (st != Navigator.Status.ARRIVED && st != Navigator.Status.FAILED) {
				why = "there's a cave here; caves show far more ore than a tunnel";
				return "exploring a cave";
			}
			// Got there: stand and look round the cave a moment (everything in sight counts as seen), then on to the
			// next part of it. Couldn't get there: dig on for a while.
			if (st == Navigator.Status.ARRIVED) {
				BlockPos here = p.blockPosition();
				for (int dx = -8; dx <= 8; dx += 4)
					for (int dy = -4; dy <= 4; dy += 4)
						for (int dz = -8; dz <= 8; dz += 4) caveSeen.add(caveCell(here.offset(dx, dy, dz)));
				caveLookUntil = now + Human.rangeInt(20, 45);
				caveLookYaw = p.getYRot() + (Human.chance(0.5) ? 1 : -1) * Human.range(50, 140);
				caveCheckAt = caveLookUntil;
			} else {
				caveCheckAt = now + 20 * 20;
				HumanAutopilot.LOGGER.info("[survival] cave: no way over there, digging on");
			}
		}
		if (now < caveLookUntil) {
			// Looking round the cave for ore before moving on (not digging a hole in the floor meanwhile).
			aim.lookDirection("cave-look", caveLookYaw, 10);
			why = "having a look round this cave for ore";
			return "looking round the cave";
		}
		if (now < caveCheckAt) return null;
		// Hurt: stay in our own lit tunnels until we've healed; dark caves are where things spawn.
		if (p.getHealth() < 14) return null;
		// Only properly deep: a hole near the top of our stairs is not a cave worth a detour.
		if (p.getBlockY() > Terrain.surfaceY(p.getBlockX(), p.getBlockZ()) - 6) return null;
		caveCheckAt = now + 20 * 10;
		BlockPos spot = findCaveSpot(p);
		if (spot == null) return null;
		caveSeen.add(caveCell(spot));
		HumanAutopilot.LOGGER.info("[survival] cave: heading into the open space at {}", spot.toShortString());
		caveNav.goStrict(new Goal.Near(Vec3.atBottomCenterOf(spot), 1.5, false), false);
		var first = caveNav.tick(p, in, aim);
		if (first == Navigator.Status.FAILED) {
			caveCheckAt = now + 20 * 20;
			return null;
		}
		why = "there's a cave here; caves show far more ore than a tunnel";
		return "exploring a cave";
	}

	/** An open, cave-like spot 6-16 blocks away (no sky, not our tunnel, not explored yet), as far as possible. */
	private BlockPos findCaveSpot(LocalPlayer p) {
		BlockPos c = p.blockPosition();
		var lvl = Terrain.level();
		BlockPos best = null;
		double bestScore = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -16; dx <= 16; dx += 2) {
			for (int dz = -16; dz <= 16; dz += 2) {
				for (int dy = -5; dy <= 5; dy++) {
					int x = c.getX() + dx, y = c.getY() + dy, z = c.getZ() + dz;
					double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
					if (d < 6 || d > 16) continue;
					if (!Terrain.canStand(x, y, z) || Terrain.water(x, y, z)) continue;
					m.set(x, y, z);
					if (lvl.getBrightness(net.minecraft.world.level.LightLayer.SKY, m) > 0) continue; // that's outside
					if (caveSeen.contains(caveCell(m)) || tunneler.dugNear(m)) continue;
					// Cave-like: lots of open space around (a 1x2 tunnel has ~6 of 27, a cave far more).
					int open = 0;
					for (int ox = -1; ox <= 1; ox++)
						for (int oy = 0; oy <= 2; oy++)
							for (int oz = -1; oz <= 1; oz++)
								if (Terrain.passable(x + ox, y + oy, z + oz)) open++;
					if (open < 18 || Terrain.nearLava(x, y, z)) continue;
					double score = d + open * 0.2 + Human.range(0, 2);
					if (score > bestScore) {
						bestScore = score;
						best = m.immutable();
					}
				}
			}
		}
		return best;
	}

	/** Underground and it's dark: make torches if we can, and put one down. Returns a status while busy. */
	private String lightTheWay(LocalPlayer p, InputDriver in, AimController aim) {
		long now = HumanAutopilot.ticks();
		if (now < torchCooldownUntil) return null;
		BlockPos here = p.blockPosition();
		var lvl = Terrain.level();
		boolean dark = lvl.getBrightness(net.minecraft.world.level.LightLayer.SKY, here) < 8
				&& lvl.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, here) < 5;
		if (!dark) return null;
		int torches = Inv.count(p, Inv.TORCH);
		if (torches == 0) {
			int coal = Inv.count(p, Inv.COAL), sticks = Inv.count(p, Inv.STICK), planks = Inv.count(p, Inv.PLANK);
			if (coal == 0) return null;
			// Keep 2 sticks for a new pickaxe (unless we carry a spare): a worn-out pickaxe deep down with no
			// sticks means a trip to the surface, maybe at night.
			int keep = workingPickaxes(p) >= 2 ? 0 : 2;
			if (sticks <= keep && planks >= 2 && !coolingDown("crafting sticks")) return craft(p, in, aim, Recipe.STICKS);
			int spareSticks = sticks - keep;
			if (spareSticks <= 0 || coolingDown("crafting torches")) return null;
			return craft(p, in, aim, Recipe.TORCH, Math.min(Math.min(coal, spareSticks), 2));
		}
		String swap = torchToOffhand(p, in);
		if (swap != null) return swap;
		BlockPos feet = p.blockPosition();
		if (Terrain.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) >= 5) return null;
		Placer.Result r = torchPlacer.tick(p, in, aim, Inv.TORCH, Blocks.TORCH);
		switch (r) {
			case DONE -> {
				torchPlacer.reset();
				return null;
			}
			case NOT_IN_HOTBAR -> {
				screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(hotbarWants(p, Inv.pickaxeTier(p), Inv.swordTier(p))));
				return "getting torches out";
			}
			case NO_SPOT, FAILED -> {
				torchPlacer.reset();
				torchCooldownUntil = now + 100;
				return null;
			}
			default -> {
				return "placing a torch";
			}
		}
	}

	private long torchSwapAt = -1000;
	private int torchSwapTries;

	/**
	 * Caving with a torch in the off-hand, like the recorded player: pick the torch slot and press F. Not once
	 * we own a shield (that's what the off-hand is for then).
	 */
	private String torchToOffhand(LocalPlayer p, InputDriver in) {
		if (!p.getOffhandItem().isEmpty() || Inv.has(p, s -> s.is(Items.SHIELD)) || torchSwapTries >= 3) return null;
		int slot = Inv.hotbarSlot(p, Inv.TORCH);
		if (slot < 0) return null;
		long now = HumanAutopilot.ticks();
		if (now - torchSwapAt < 20) return "taking a torch in my other hand";
		if (!in.selectHotbar(slot)) return "taking a torch in my other hand";
		in.click(InputDriver.opts().keySwapOffhand);
		torchSwapAt = now;
		torchSwapTries++;
		HumanAutopilot.LOGGER.info("[survival] torch to the off-hand (try {})", torchSwapTries);
		return "taking a torch in my other hand";
	}

	// ------------------------------------------------------------------ food

	private final java.util.Set<BlockPos> looted = new java.util.HashSet<>();
	private BlockPos lootChest;
	private long nextLootScan;

	/** A chest that isn't ours, close by and in sight: walk over, open it, take the good stuff (once per chest). */
	private String lootOnTheWay(LocalPlayer p, InputDriver in, AimController aim, long now) {
		if (p.getHealth() <= 10 || !CombatModule.hostilesAround(p, 12).isEmpty() || Inv.freeSlots(p) < 2) {
			lootChest = null;
			return null;
		}
		if (lootChest == null && now >= nextLootScan) {
			nextLootScan = now + 40;
			BlockPos at = p.blockPosition();
			double bestD = Double.MAX_VALUE;
			for (BlockPos c : BlockPos.betweenClosed(at.offset(-10, -4, -10), at.offset(10, 4, 10))) {
				var st = Terrain.state(c);
				if (!(st.is(Blocks.CHEST) || st.is(Blocks.BARREL) || st.is(Blocks.TRAPPED_CHEST))) continue;
				BlockPos pos = c.immutable();
				if (looted.contains(pos) || pos.equals(chest) || (home != null && pos.closerThan(home, 4))) continue;
				double d = pos.distSqr(at);
				if (d < bestD && MiningModule.canSee(p, pos)) {
					bestD = d;
					lootChest = pos;
				}
			}
			if (lootChest != null) HumanAutopilot.LOGGER.info("[habit] spotted a chest at {}: having a look inside", lootChest.toShortString());
		}
		if (lootChest == null) return null;
		BlockPos c = lootChest;
		if (!(Terrain.state(c).is(Blocks.CHEST) || Terrain.state(c).is(Blocks.BARREL) || Terrain.state(c).is(Blocks.TRAPPED_CHEST))) {
			lootChest = null;
			return null;
		}
		String w = walkTo(p, in, aim, c);
		if (w != null) {
			if (walk.failReason().startsWith("no way") || p.blockPosition().distSqr(c) > 16 * 16) {
				looted.add(c); // can't get to it: forget it
				lootChest = null;
				return null;
			}
			return "going to the chest";
		}
		looted.add(c);
		lootChest = null;
		why = "a chest that isn't ours: worth a look";
		screen.start(ScreenWorker.Kind.CHEST, c, new ScreenWorker.LootJob());
		return "looting a chest";
	}

	private BlockPos stump;
	private long stumpAt, nextPlantClick;
	private boolean plantThisOne;

	/**
	 * After taking a tree down to the ground, a player often puts a sapling back where it stood (the recorded player
	 * did, a few times in a row). Only for a trunk we chopped ourselves off dirt or grass, once the tree is gone, with a
	 * sapling in the hotbar, and not every time.
	 */
	private String replant(LocalPlayer p, InputDriver in, AimController aim, long now) {
		BlockPos b = dev.humanautopilot.pathing.Digger.lastBrokenPos;
		var st = dev.humanautopilot.pathing.Digger.lastBrokenState;
		if (b != null && st != null && st.is(BlockTags.LOGS) && !b.equals(stump)) {
			var under = Terrain.state(b.below());
			if (under.is(BlockTags.DIRT)) {
				stump = b.immutable();
				stumpAt = now;
				plantThisOne = Human.chance(0.6);
			}
		}
		if (stump == null || !plantThisOne) return null;
		// Wait till the tree is down (no log left right above the stump), and give up after a minute.
		if (now - stumpAt > 20 * 60 || underground(p) || !Terrain.state(stump).isAir()) {
			stump = null;
			return null;
		}
		for (int dy = 1; dy <= 6; dy++) if (Terrain.state(stump.above(dy)).is(BlockTags.LOGS)) return null;
		if (now - stumpAt < 30) return null; // a beat after the last log, not instantly
		int slot = Inv.hotbarSlot(p, s -> s.is(net.minecraft.tags.ItemTags.SAPLINGS));
		if (slot < 0 || p.blockPosition().distSqr(stump) > 12 * 12) {
			stump = null;
			return null;
		}
		BlockPos ground = stump.below();
		String w = walkTo(p, in, aim, ground);
		if (w != null) return "going back to plant a sapling";
		Vec3 top = Vec3.atCenterOf(ground).add(0, 0.5, 0);
		aim.lookAt(ground, () -> top);
		if (!in.selectHotbar(slot)) return "getting a sapling out";
		var hr = Minecraft.getInstance().hitResult;
		if (now >= nextPlantClick && hr instanceof net.minecraft.world.phys.BlockHitResult bhr && bhr.getBlockPos().equals(ground)
				&& bhr.getDirection() == net.minecraft.core.Direction.UP && aim.error() < 5) {
			in.click(InputDriver.opts().keyUse);
			nextPlantClick = now + 10;
		}
		if (Terrain.state(stump).is(BlockTags.SAPLINGS)) {
			HumanAutopilot.LOGGER.info("[habit] planted a sapling where the tree stood");
			stump = null;
			return null;
		}
		return "planting a sapling";
	}

	private BlockPos snackBush;
	private long snackUntil, snackCooldownUntil, nextBushClick;
	private boolean snacking;

	/**
	 * Not out looking for food, just not walking past it: an animal a few steps away, ripe berries right here. Daytime,
	 * on the surface, not already well stocked, and only for a little while before getting back to the plan.
	 */
	private String foodOnTheWay(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat, long now) {
		boolean fine = Inv.foodPoints(p) < 16 && !Terrain.level().isDarkOutside() && !underground(p) && p.getHealth() > 10
				&& CombatModule.hostilesAround(p, 16).isEmpty();
		if (!fine || now < snackCooldownUntil) {
			if (snacking) {
				snacking = false;
				hunter.reset();
				hunter.setMaxRange(32);
			}
			return null;
		}
		if (!snacking) {
			snacking = true;
			snackUntil = now + 20 * 25;
		}
		if (now > snackUntil) {
			snacking = false;
			snackCooldownUntil = now + 20 * 90;
			hunter.reset();
			hunter.setMaxRange(32);
			return null;
		}
		// Ripe sweet berries within a few steps: walk up (not into the bush: it scratches) and pick them.
		if (snackBush == null || !ripe(snackBush)) snackBush = ripeBushNear(p);
		if (snackBush != null) {
			BlockPos b = snackBush;
			why = "berries right here (" + Inv.foodPoints(p) + " food points on me)";
			String w = walkTo(p, in, aim, b);
			if (w != null) return "going to the berry bush";
			aim.lookAt(b, () -> Vec3.atCenterOf(b).add(0, -0.1, 0));
			var hr = Minecraft.getInstance().hitResult;
			if (now >= nextBushClick && hr instanceof net.minecraft.world.phys.BlockHitResult bhr && bhr.getBlockPos().equals(b) && aim.error() < 6) {
				in.click(InputDriver.opts().keyUse);
				nextBushClick = now + Human.rangeInt(6, 12);
			}
			return "picking berries";
		}
		// An animal close by.
		hunter.setMaxRange(12);
		// With an axe or a sword (the recorded player went for their first pig right after making the axe), not fists.
		// A group right here gets taken (they stocked up on three pigs, a chicken and a cow in a row), up to ~16 points.
		if (Inv.foodPoints(p) < 16 && Tools.meleeDps(p.getInventory().getItem(Tools.bestWeaponSlot(p))) > 5 && hunter.tick(p, in, aim, combat) == Hunter.Result.WORKING) {
			why = "an animal right here (" + Inv.foodPoints(p) + " food points on me)";
			return hunter.status();
		}
		hunter.setMaxRange(32);
		snacking = false;
		snackCooldownUntil = now + 20 * 20; // nothing right here: look again in a bit
		return null;
	}

	private static boolean ripe(BlockPos pos) {
		var s = Terrain.state(pos);
		return s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 2;
	}

	private static BlockPos ripeBushNear(LocalPlayer p) {
		BlockPos at = p.blockPosition(), best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos c : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(6, 2, 6))) {
			if (!ripe(c)) continue;
			double d = c.distSqr(at);
			if (d < bestD && MiningModule.canSee(p, c)) {
				bestD = d;
				best = c.immutable();
			}
		}
		return best;
	}

	private String getFood(LocalPlayer p, InputDriver in, AimController aim, CombatModule combat, long now) {
		why = "only " + Inv.foodPoints(p) + " food points on me";
		if (stayDownTonight(p) && p.getFoodData().getFoodLevel() > 6) return mineUntilMorning(p, in, aim, "hunting");
		hunter.setSheepOnly(false);
		String up = surfaceFirst(p, in, aim);
		if (up != null) return up;
		if (hunter.tick(p, in, aim, combat) == Hunter.Result.WORKING) {
			foodSearchStarted = -1;
			return hunter.status();
		}
		if (foodSearchStarted < 0) foodSearchStarted = now;
		if (now - foodSearchStarted > 20 * 60) {
			// Searched a minute with no luck: get on with other things, try again later.
			foodSearchStarted = -1;
			foodCooldownUntil = now + 20 * 180;
			return null;
		}
		return explore(p, in, aim, "looking for animals");
	}

	// ------------------------------------------------------------------ crafting / smelting

	private String craft(LocalPlayer p, InputDriver in, AimController aim, Recipe r) {
		return craft(p, in, aim, r, 1);
	}

	private String craft(LocalPlayer p, InputDriver in, AimController aim, Recipe r, int times) {
		if (coolingDown("crafting " + r.name())) return explore(p, in, aim, "taking a break from crafting");
		if (r.fitsInInventory()) {
			// The batch turns logs into planks first if it has to: planks + table (or planks + sticks) in one sitting.
			screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.BatchCraftJob(List.of(new ScreenWorker.Order(r, times))));
			return "crafting " + r.name();
		}
		if (repackTable) {
			// Can't get at it where it is: pick it up and put it somewhere better.
			if (table != null) {
				String s = packUp(p, in, aim, table, Blocks.CRAFTING_TABLE);
				if (s != null) return s;
			}
			repackTable = false;
			tableOpenFails = 0;
		}
		BlockPos t = tableNearby(p);
		if (t == null && !Inv.has(p, Inv.TABLE)) {
			// Lost the old one (left behind, couldn't get back to it): make a table first.
			String s = needWood(p, in, aim, 0, 0, true, Inv.count(p, Inv.LOG), Inv.count(p, Inv.PLANK), Inv.count(p, Inv.STICK));
			if (s != null) return s;
		}
		if (t == null) {
			String s = place(p, in, aim, Inv.TABLE, Blocks.CRAFTING_TABLE);
			if (placer.placed() != null) {
				table = placer.placed();
				noteHome(p, table);
				placer.reset();
			}
			return s;
		}
		lastTableUse = HumanAutopilot.ticks();
		String w = walkTo(p, in, aim, t);
		if (w != null) return w;
		List<ScreenWorker.Order> orders = tableOrders(p, r, times);
		if (orders.size() > 1) {
			StringBuilder list = new StringBuilder();
			for (var o : orders) list.append(list.isEmpty() ? "" : ", ").append(o.recipe().name());
			HumanAutopilot.LOGGER.info("[craft] at the table: {} (one sitting for all of it)", list);
		}
		screen.start(ScreenWorker.Kind.TABLE, t, new ScreenWorker.BatchCraftJob(orders));
		return "crafting " + r.name();
	}

	/**
	 * Everything worth making on this visit to the table: what we came for, then the rest of the plan's tools we
	 * may already have the stuff for (the batch leaves off whatever we're short of). One sitting, not five.
	 */
	private List<ScreenWorker.Order> tableOrders(LocalPlayer p, Recipe main, int times) {
		List<ScreenWorker.Order> orders = new ArrayList<>();
		orders.add(new ScreenWorker.Order(main, times));
		// What's left of the stone / iron / wool after the main thing: extras only while it lasts (wood is sorted out at
		// the table itself, from logs if need be).
		Map<Predicate<ItemStack>, Integer> left = new HashMap<>();
		take(p, main, times, left);
		boolean mainListed = false;
		for (Recipe w : tableWishes(p)) {
			if (w == main && !mainListed) {
				mainListed = true;
				continue;
			}
			if (take(p, w, 1, left)) orders.add(new ScreenWorker.Order(w, 1));
		}
		return orders;
	}

	/** Uses up the non-wood ingredients of {@code times} crafts from {@code left}; false (and nothing used) if short. */
	private static boolean take(LocalPlayer p, Recipe r, int times, Map<Predicate<ItemStack>, Integer> left) {
		Map<Predicate<ItemStack>, Integer> need = new HashMap<>();
		for (Predicate<ItemStack> g : r.grid()) {
			if (g == null || g == Inv.PLANK || g == Inv.STICK || g == Inv.LOG) continue;
			need.merge(g, times, Integer::sum);
		}
		for (var e : need.entrySet()) {
			if (left.computeIfAbsent(e.getKey(), k -> Inv.count(p, k)) < e.getValue()) return false;
		}
		for (var e : need.entrySet()) left.merge(e.getKey(), -e.getValue(), Integer::sum);
		return true;
	}

	private long lastHostileSeen = -100_000;

	/** Monsters seen in the last two minutes, or evening / night: a player wants a sword now (not before). */
	private boolean wantSword(LocalPlayer p) {
		long t = Terrain.level().getOverworldClockTime() % 24000;
		return (t > 11000 && t < 23500) || HumanAutopilot.ticks() - lastHostileSeen < 20 * 120;
	}

	/** The table-made things the plan wants next, in the order it wants them. */
	private List<Recipe> tableWishes(LocalPlayer p) {
		List<Recipe> w = new ArrayList<>();
		int pick = Inv.pickaxeTier(p), sword = Inv.swordTier(p), axe = Inv.axeTier(p), shovel = Inv.shovelTier(p);
		int cobble = Inv.count(p, Inv.COBBLE), iron = Inv.count(p, Inv.IRON);
		if (pick == 0 && cobble < 3) {
			if (axe == 0) w.add(Recipe.WOOD_AXE);
			w.add(Recipe.WOOD_PICK);
			if (shovel == 0) w.add(Recipe.WOOD_SHOVEL); // the recorded player made one at the first table too
		}
		if (pick < 2) w.add(Recipe.STONE_PICK);
		if (axe < 2) w.add(Recipe.STONE_AXE);
		if (!Inv.has(p, Inv.FURNACE) && furnace == null) w.add(Recipe.FURNACE);
		if (shovel < 2) w.add(Recipe.STONE_SHOVEL);
		if (sword < 2 && wantSword(p)) w.add(Recipe.STONE_SWORD);
		// A spare for the long dig (not when an iron one is about to be made anyway).
		if (workingPickaxes(p) + (pick < 2 ? 1 : 0) < 2 && iron < 3) w.add(Recipe.STONE_PICK);
		if (pick < 3) w.add(Recipe.IRON_PICK);
		if (sword < 3) w.add(Recipe.IRON_SWORD);
		if ((p.getOffhandItem().isEmpty() || Inv.TORCH.test(p.getOffhandItem())) && !Inv.has(p, st -> st.is(Items.SHIELD))) w.add(Recipe.SHIELD);
		for (Piece piece : ARMOR) {
			if (p.getItemBySlot(piece.slot()).isEmpty() && !Inv.has(p, st -> st.is(piece.item()))) w.add(piece.recipe());
		}
		return w;
	}

	private String smelt(LocalPlayer p, InputDriver in, AimController aim, Predicate<ItemStack> input, int count,
						 int logs, int planks) {
		if (cookingUntil != 0) return null; // already cooking: do something else meanwhile
		// Fuel: coal first, then planks/logs (each cooks 1.5 items).
		Predicate<ItemStack> fuel;
		int fuelCount;
		int coal = Inv.count(p, Inv.COAL);
		// Keep a couple of coal for torches (dark caves are where things spawn) if wood can do the smelting.
		boolean woodWillDo = planks * 3 / 2 >= count || logs * 3 / 2 >= count;
		if (woodWillDo && coal >= 1 && (coal - (count + 7) / 8) < 2) coal = 0;
		if (coal * 8 >= count) {
			fuel = Inv.COAL;
			fuelCount = (count + 7) / 8;
		} else if (planks * 3 / 2 >= count) {
			fuel = Inv.PLANK;
			fuelCount = (count * 2 + 2) / 3;
		} else if (logs * 3 / 2 >= count) {
			fuel = Inv.LOG;
			fuelCount = (count * 2 + 2) / 3;
		} else {
			return gatherLogs(p, in, aim, (count * 2 + 2) / 3 - logs);
		}
		if (repackFurnace && cookingUntil == 0) {
			if (furnace != null) {
				String s = packUp(p, in, aim, furnace, Blocks.FURNACE);
				if (s != null) return s;
			}
			repackFurnace = false;
			furnaceOpenFails = 0;
		}
		BlockPos f = furnaceNearby(p);
		if (f == null) {
			if (!Inv.has(p, Inv.FURNACE)) {
				// Ours is back at home (or gone) and we're carrying plenty of stone: make another one right here, like a
				// player with a bag full of cobble does, rather than climb all the way back (or never smelt at all).
				if (Inv.count(p, Inv.COBBLE) >= 8) {
					why = "my furnace is far away and I've got plenty of cobblestone for a new one";
					return craft(p, in, aim, Recipe.FURNACE);
				}
				return null;
			}
			String s = place(p, in, aim, Inv.FURNACE, Blocks.FURNACE);
			if (placer.placed() != null) {
				furnace = placer.placed();
				placer.reset();
			}
			return s;
		}
		lastFurnaceUse = HumanAutopilot.ticks();
		String w = walkTo(p, in, aim, f);
		if (w != null) return w;
		if (cookingUntil != 0) return null; // already cooking something; carry on meanwhile
		pendingCookTicks = count * 200L + 40;
		screen.start(ScreenWorker.Kind.FURNACE, f, new ScreenWorker.SmeltJob(input, count, fuel, fuelCount).leaveItCooking());
		return "loading the furnace";
	}

	private String place(LocalPlayer p, InputDriver in, AimController aim, Predicate<ItemStack> item, Block block) {
		lastPlaceAttempt = HumanAutopilot.ticks();
		if (placer.spot() != null) lastPlaceSpot = placer.spot();
		Placer.Result r = placer.tick(p, in, aim, item, block);
		switch (r) {
			case NOT_IN_HOTBAR -> {
				List<Predicate<ItemStack>> wants = new ArrayList<>(hotbarWants(p, Inv.pickaxeTier(p), Inv.swordTier(p)));
				wants.add(0, item);
				screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(wants));
				return "getting " + block.getName().getString().toLowerCase() + " out";
			}
			case NO_SPOT, FAILED -> {
				placer.reset();
				return explore(p, in, aim, "looking for a flat spot");
			}
			default -> {
				return "placing " + block.getName().getString().toLowerCase();
			}
		}
	}

	private String walkTo(LocalPlayer p, InputDriver in, AimController aim, BlockPos block) {
		double reach = p.blockInteractionRange() - 0.5;
		double dist = p.getEyePosition().distanceTo(Vec3.atCenterOf(block));
		boolean inReach = dist <= reach;
		if (!block.equals(walkTarget)) {
			closeWalkGaveUp = false;
			closeMode = false;
		}
		// Close enough to try once we can see it, or we're right beside it, or we can't get any closer anyway.
		if (inReach && (dist < 2.3 || closeWalkGaveUp || MiningModule.canSee(p, block))) {
			walk.stop();
			walkDig = false;
			return null;
		}
		// In reach but can't see it (a step or the stair ceiling in the way): walk right up to it.
		// Once we've been in reach of it, stay in "step right up to it" mode: bobbing across the reach boundary
		// on a step would otherwise restart the walk every tick (and its give-up counter with it).
		if (inReach) closeMode = true;
		double radius = closeMode ? 1.8 : reach - 0.8;
		// The walker may still be heading somewhere else from an earlier, interrupted errand.
		if (!walk.active() || !block.equals(walkTarget) || radius != walkRadius) {
			if (!block.equals(walkTarget)) walkDig = false;
			walk.go(new Goal.Near(Vec3.atCenterOf(block), radius, true), walkDig);
			walkTarget = block.immutable();
			walkRadius = radius;
		}
		var s = walk.tick(p, in, aim);
		if (s == Navigator.Status.FAILED && inReach) {
			closeWalkGaveUp = true; // can't step any closer: try from here
			return "walking back to the " + Terrain.state(block).getBlock().getName().getString().toLowerCase();
		}
		if (s == Navigator.Status.FAILED && !walkDig && underground(p)) {
			// No open way back (a step got dug out, gravel fell): dig our way back to it, like a player would.
			HumanAutopilot.LOGGER.info("[survival] no open way back to the {} at {}: digging to it",
					Terrain.state(block).getBlock().getName().getString().toLowerCase(), block.toShortString());
			walkDig = true;
			walk.go(new Goal.Near(Vec3.atCenterOf(block), reach - 0.8, true), true);
			return "digging back to the " + Terrain.state(block).getBlock().getName().getString().toLowerCase();
		}
		if (s == Navigator.Status.FAILED) {
			walkDig = false;
			// Can't get back to it: leave it where it is, we'll make or place another.
			HumanAutopilot.LOGGER.info("[survival] can't walk back to the {} at {}: {}",
					Terrain.state(block).getBlock().getName().getString().toLowerCase(), block.toShortString(), walk.failReason());
			abandoned.add(block.immutable());
			if (block.equals(table)) table = null;
			if (block.equals(furnace)) furnace = null;
		}
		return "walking back to the " + Terrain.state(block).getBlock().getName().getString().toLowerCase();
	}

	// ------------------------------------------------------------------ placed blocks

	private void refreshPlaced() {
		LocalPlayer pl = Minecraft.getInstance().player;
		// Lost track of a station we just put down (it landed a block off)? Look around for it.
		if (pl != null && lastPlaceSpot != null && HumanAutopilot.ticks() - lastPlaceAttempt < 200) {
			if (table == null && !Inv.has(pl, Inv.TABLE)) table = findNear(lastPlaceSpot, Blocks.CRAFTING_TABLE);
			if (furnace == null && !Inv.has(pl, Inv.FURNACE)) furnace = findNear(lastPlaceSpot, Blocks.FURNACE);
		}
		// Catch a block we just put down before planning anything else.
		BlockPos s = placer.spot();
		if (s != null) {
			if (Terrain.state(s).is(Blocks.CRAFTING_TABLE)) {
				table = s;
				if (pl != null) noteHome(pl, s);
				lastTableUse = HumanAutopilot.ticks();
				placer.reset();
			} else if (Terrain.state(s).is(Blocks.FURNACE)) {
				furnace = s;
				lastFurnaceUse = HumanAutopilot.ticks();
				placer.reset();
			}
		}
		if (table != null && !Terrain.state(table).is(Blocks.CRAFTING_TABLE) && !picker.busy()) table = null;
		if (furnace != null && !Terrain.state(furnace).is(Blocks.FURNACE) && !picker.busy()) furnace = null;
	}

	private BlockPos findNear(BlockPos c, Block block) {
		for (BlockPos b : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 2, 2))) {
			if (Terrain.state(b).is(block) && !abandoned.contains(b)) return b.immutable();
		}
		return null;
	}

	private BlockPos tableNearby(LocalPlayer p) {
		if (table != null && p.blockPosition().distSqr(table) < 24 * 24) return table;
		return null;
	}

	private BlockPos furnaceNearby(LocalPlayer p) {
		int range = cookingUntil != 0 ? 64 : 24;
		if (furnace != null && p.blockPosition().distSqr(furnace) < range * range) return furnace;
		return null;
	}

	/**
	 * Before wandering off, take the table / furnace we put down with us (if we're done with it),
	 * like a player packing up. Returns a status while doing that, or null.
	 */
	private String leaveSite(LocalPlayer p, InputDriver in, AimController aim) {
		return leaveSite(p, in, aim, false);
	}

	/** {@code leaving}: we're heading off right now, so don't wait for the "just used it" grace period. */
	private String leaveSite(LocalPlayer p, InputDriver in, AimController aim, boolean leaving) {
		long now = HumanAutopilot.ticks();
		int grace = leaving ? 0 : 60;
		BlockPos target = null;
		Block block = null;
		if (table != null && now - lastTableUse > grace && p.blockPosition().distSqr(table) < 12 * 12) {
			target = table;
			block = Blocks.CRAFTING_TABLE;
		} else if (furnace != null && cookingUntil == 0 && now - lastFurnaceUse > grace && p.blockPosition().distSqr(furnace) < 12 * 12
				&& Inv.pickaxeTier(p) > 0) {
			target = furnace;
			block = Blocks.FURNACE;
		}
		if (target == null) return null;
		return packUp(p, in, aim, target, block);
	}

	/** Break one of our placed blocks and pick it up. Returns a status while busy, null when done. */
	private String packUp(LocalPlayer p, InputDriver in, AimController aim, BlockPos target, Block block) {
		final BlockPos t = target;
		final Block b = block;
		pickerUsedAt = HumanAutopilot.ticks();
		picker.startCustom("pickup " + t.toShortString(), (pos, s) -> pos.equals(t) && s.is(b), false);
		String s = picker.tick(p, in, aim);
		if (Terrain.state(t).is(b) && (s == null || picker.waitingForOre())) {
			// Can't get to it: leave it behind, we'll make another if needed.
			abandoned.add(t);
			if (t.equals(table)) table = null;
			if (t.equals(furnace)) furnace = null;
			picker.stop();
			return null;
		}
		if (!Terrain.state(t).is(b) && (s == null || picker.waitingForOre())) {
			if (t.equals(table)) table = null;
			if (t.equals(furnace)) furnace = null;
			picker.stop();
			return null;
		}
		return "packing up the " + b.getName().getString().toLowerCase();
	}

	// ------------------------------------------------------------------ hotbar & exploring

	private static List<Predicate<ItemStack>> hotbarWants(LocalPlayer p, int pickTier, int swordTier) {
		List<Predicate<ItemStack>> w = new ArrayList<>();
		// A pickaxe about to break doesn't count (the miner won't use it): swap the spare in from the bag instead.
		int min = HumanAutopilot.config().minToolDurability;
		if (pickTier > 0) w.add(s -> Inv.isPickaxe(s) && tierOf(s, true) == pickTier && s.getMaxDamage() - s.getDamageValue() > min);
		// With an iron (or better) pickaxe, keep a stone one at hand too: that's the one for plain rock.
		if (pickTier >= 3) w.add(s -> Inv.isPickaxe(s) && tierOf(s, true) == 2 && s.getMaxDamage() - s.getDamageValue() > min);
		w.add(Inv.TORCH);
		if (swordTier > 0) w.add(s -> Inv.isSword(s) && tierOf(s, false) == swordTier);
		if (Inv.axeTier(p) > 0) w.add(Inv::isAxe);
		if (Inv.shovelTier(p) > 0) w.add(Inv::isShovel);
		w.add(s -> s.has(DataComponents.FOOD) && !s.is(Items.ROTTEN_FLESH) && !Inv.RAW_MEAT.test(s));
		return w;
	}

	private static int tierOf(ItemStack s, boolean pickaxe) {
		var item = s.getItem();
		if (pickaxe) {
			if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) return 1;
			if (item == Items.STONE_PICKAXE || item == Items.COPPER_PICKAXE) return 2;
			if (item == Items.IRON_PICKAXE) return 3;
			if (item == Items.DIAMOND_PICKAXE) return 4;
			return 5;
		}
		if (item == Items.WOODEN_SWORD || item == Items.GOLDEN_SWORD) return 1;
		if (item == Items.STONE_SWORD || item == Items.COPPER_SWORD) return 2;
		if (item == Items.IRON_SWORD) return 3;
		if (item == Items.DIAMOND_SWORD) return 4;
		return 5;
	}

	/**
	 * A direction (yaw) with a few blocks of open, body-high space to throw things into, preferring
	 * behind us (away from where we're heading). NaN if we're boxed in, e.g. in a tunnel or staircase.
	 */
	private static double openYaw(LocalPlayer p) {
		double base = p.getYRot();
		int y = net.minecraft.util.Mth.floor(p.getY() + 0.2);
		for (double off : new double[]{180, 150, -150, 120, -120, 90, -90}) {
			double yaw = base + off + Human.range(-10, 10);
			double rad = Math.toRadians(yaw);
			double dx = -Math.sin(rad), dz = Math.cos(rad);
			boolean clear = true;
			for (double d = 1; d <= 4 && clear; d += 1) {
				int x = net.minecraft.util.Mth.floor(p.getX() + dx * d), z = net.minecraft.util.Mth.floor(p.getZ() + dz * d);
				clear = Terrain.passable(x, y, z) && Terrain.passable(x, y + 1, z);
			}
			if (clear) return yaw;
		}
		return Double.NaN;
	}

	private static boolean underground(LocalPlayer p) {
		return p.getBlockY() < Terrain.surfaceY(p.getBlockX(), p.getBlockZ()) - 2;
	}

	/** Trees and animals live up top: if we're in a tunnel, walk (or dig) back up first. */
	private String surfaceFirst(LocalPlayer p, InputDriver in, AimController aim) {
		if (!climb.active()) {
			// Only a real hole counts (4+ below the ground around us); once climbing we go all the way up. The gap
			// stops a flip-flop in dips right at the edge of "underground".
			if (p.getBlockY() >= Terrain.surfaceY(p.getBlockX(), p.getBlockZ()) - 4) return null;
			if (HumanAutopilot.ticks() < climbRetryAt) return null; // that way up didn't work: do something else a while
			// Heading up and away: take the table / furnace along first, not halfway up the stairs.
			String pickup = leaveSite(p, in, aim, true);
			if (pickup != null) return pickup;
			// Dig our way up only with a pickaxe; without one, walk up the way we came (stairs, caves).
			climb.go(new Goal.Surface(), Inv.pickaxeTier(p) > 0);
		}
		if (climb.tick(p, in, aim) == Navigator.Status.FAILED) {
			HumanAutopilot.LOGGER.info("[survival] can't get up from here ({}); trying elsewhere", climb.failReason());
			climbRetryAt = HumanAutopilot.ticks() + 20 * 20;
			return null;
		}
		return "heading back up to the surface";
	}

	/** The ground at this column is water (lake, river, ocean). Unknown (unloaded) counts as dry. */
	private static boolean wet(int x, int z) {
		if (!Terrain.loaded(x, z)) return false;
		int top = Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
		return Terrain.water(x, top - 1, z);
	}

	/** Walk somewhere new, 25-50 blocks at a time, mostly in the same direction. */
	private String explore(LocalPlayer p, InputDriver in, AimController aim, String why) {
		String pickup = leaveSite(p, in, aim); // heading off somewhere: take the table / furnace along
		if (pickup != null) return pickup;
		if (!roam.active()) {
			if (Double.isNaN(exploreHeading)) exploreHeading = p.getYRot();
			exploreHeading += Human.gauss(-50, 50);
			int tx = 0, tz = 0;
			for (int tries = 0; tries < 8; tries++) {
				double dist = Human.range(25, 50);
				double rad = Math.toRadians(exploreHeading);
				tx = (int) (p.getX() - Math.sin(rad) * dist);
				tz = (int) (p.getZ() + Math.cos(rad) * dist);
				if (!wet(tx, tz)) break;
				exploreHeading += Human.range(40, 90) * (Human.chance(0.5) ? 1 : -1); // lake or sea that way
			}
			// Underground there's no walking "over there" without a pickaxe: dig a way through at this depth,
			// like a player would (a goal at any height would just lead back up to the cheap surface).
			if (underground(p) && Inv.pickaxeTier(p) > 0) {
				roam.go(new Goal.Near(new Vec3(tx + 0.5, p.getY(), tz + 0.5), 4, false), true);
			} else {
				roam.go(new Goal.NearXZ(tx, tz, 3), false);
			}
		}
		if (roam.tick(p, in, aim) == Navigator.Status.FAILED) {
			HumanAutopilot.LOGGER.info("[nav] explore leg failed ({}) at {}", roam.failReason(), p.blockPosition().toShortString());
			exploreHeading += (Human.chance(0.5) ? 1 : -1) * Human.range(90, 180);
		}
		return why;
	}
}
