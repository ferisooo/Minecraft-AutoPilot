package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.pathing.Digger;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.survival.Inv;
import dev.humanautopilot.survival.Placer;
import dev.humanautopilot.survival.Recipe;
import dev.humanautopilot.survival.ScreenWorker;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * StreamToEarn's One Block mini-game (s2e-oneblock): a void world and a single block that turns into another one each
 * time it's broken (logs, dirt, stone, ores, wool, chests...). The goal: three wool of one colour and wood, a bed, and a
 * sleep in it. Viewers' gifts send mobs (zombies, skeletons, witches, blazes, ghasts, spider bosses, a warden), TNT,
 * chests, islands, blocks.
 * <p>
 * What she does, a step at a time, each tick from the top: a crafting screen open - work it; a warden near - crouch still
 * (it hunts by sound); a mob in reach - hit it; hungry - eat; a bed - put it down and sleep; the wool and wood for one -
 * craft it (a crafting table first); no pickaxe - make one; the platform round the block not done - bridge it out over
 * the void, sneaking at the edge; drops lying on the platform - pick them up; else - mine the block (and the chest that
 * pops up on it). Started standing on (or next to) the one block.
 */
public final class OneBlockModule {
	private static final OneBlockModule INSTANCE = new OneBlockModule();

	public static OneBlockModule get() {
		return INSTANCE;
	}

	private boolean on;
	/** The one block (the generator): it never stays gone. */
	private BlockPos gen;
	private boolean genConfirmed;
	private final ScreenWorker screen = new ScreenWorker();
	private final Placer placer = new Placer();
	private final Placer bedPlacer = new Placer();
	private final Digger digger = new Digger();
	private BlockPos table, bed;
	private long settleUntil, nextSleepClick, startedAt, logAt = -1000, hitAt, lastGenBreakAt = -1000;
	private int sleepClicks, broken;
	private BlockState lastGenState;
	private final Map<String, Long> cooldown = new HashMap<>();
	private String lastRecipe;
	// bridging one block out
	private BlockPos bridgeTarget;
	private long bridgeSince, bridgeClickAt = -1;
	private final Map<BlockPos, Long> badBridge = new HashMap<>();

	{
		bedPlacer.setTwoBlocks(true);
	}

	private OneBlockModule() {}

	public boolean on() {
		return on;
	}

	public BlockPos gen() {
		return gen;
	}

	public int broken() {
		return broken;
	}

	public void start(LocalPlayer p) {
		on = true;
		save(true);
		gen = null;
		genConfirmed = false;
		table = bed = null;
		sleepClicks = 0;
		broken = 0;
		bridgeTarget = null;
		badBridge.clear();
		cooldown.clear();
		placer.reset();
		bedPlacer.reset();
		digger.reset();
		screen.abort("started over");
		startedAt = HumanAutopilot.ticks();
		lastGenBreakAt = startedAt;
		HumanAutopilot.LOGGER.info("[oneblock] on at {}", p.blockPosition().toShortString());
	}

	public void stop() {
		if (!on) return;
		on = false;
		save(false);
		screen.abort("stopped");
		HumanAutopilot.LOGGER.info("[oneblock] off ({} blocks broken)", broken);
	}

	// ---- carried on after the game restarts, like the other mini-games

	private static boolean startChecked;
	private static long inWorldAt = -1;

	private static java.nio.file.Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("oneblock.properties");
	}

	private static void save(boolean on) {
		try {
			java.nio.file.Files.createDirectories(file().getParent());
			java.nio.file.Files.writeString(file(), "on=" + on + System.lineSeparator());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[oneblock] couldn't save: {}", e.toString());
		}
	}

	public void worldTick(LocalPlayer p) {
		if (startChecked) return;
		if (inWorldAt < 0) inWorldAt = HumanAutopilot.ticks();
		if (HumanAutopilot.ticks() - inWorldAt < 100) return;
		startChecked = true;
		boolean was = false;
		try { was = java.nio.file.Files.readString(file()).contains("on=true"); } catch (Exception ignored) { }
		if (was && !on && !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			HumanAutopilot.LOGGER.info("[oneblock] the game closed in the middle of the one block game: carrying on");
			HumanAutopilot.setOneBlock(true);
		}
	}

	// ---- our crafting screens (the main tick hands them over while one is open)

	public boolean ownsScreen(Screen s) {
		return screen.owns(s);
	}

	public void screenTick(Minecraft mc) {
		screen.screenTick(mc);
	}

	public void guiFrame() {
		screen.frame();
	}

	// ---- the block

	/**
	 * The one block: the block under her feet if it stands alone (a fresh island), else the nearest block within 5 that
	 * isn't part of a platform (no solid block beside it at its height), else the block under her feet.
	 */
	private BlockPos findGen(LocalPlayer p) {
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		if (!Terrain.state(under).isAir() && neighbours(under) == 0) return under;
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		BlockPos at = p.blockPosition();
		for (BlockPos b : BlockPos.betweenClosed(at.offset(-5, -3, -5), at.offset(5, 1, 5))) {
			if (Terrain.state(b).isAir() || neighbours(b) > 0) continue;
			double d = b.distSqr(at);
			if (d < bd) { bd = d; best = b.immutable(); }
		}
		if (best != null) return best;
		return Terrain.state(under).isAir() ? null : under;
	}

	private static int neighbours(BlockPos b) {
		int n = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (!Terrain.state(b.relative(d)).isAir()) n++;
		return n;
	}

	/** A block she can build the platform with: a full, solid block that doesn't fall, burn her, blow up, or go in the bed. */
	private static final Predicate<ItemStack> PLATFORM = s -> {
		if (!(s.getItem() instanceof BlockItem bi)) return false;
		var b = bi.getBlock();
		if (b instanceof FallingBlock || s.is(ItemTags.WOOL) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.BEDS)) return false;
		if (b == Blocks.TNT || b == Blocks.MAGMA_BLOCK || b == Blocks.CRAFTING_TABLE || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
				|| b == Blocks.ENDER_CHEST || b == Blocks.SLIME_BLOCK || b == Blocks.HONEY_BLOCK || b == Blocks.SPONGE || b == Blocks.ICE) return false;
		var st = b.defaultBlockState();
		return st.isCollisionShapeFullBlock(Terrain.level(), BlockPos.ZERO) && st.getFluidState().isEmpty();
	};

	private static boolean solid(BlockPos b) {
		BlockState s = Terrain.state(b);
		return !s.isAir() && s.isCollisionShapeFullBlock(Terrain.level(), b);
	}

	/** The block she's standing on, or null. */
	private static BlockPos support(LocalPlayer p) {
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		if (!Terrain.state(under).isAir()) return under;
		for (double ox : new double[]{-0.29, 0.29}) for (double oz : new double[]{-0.29, 0.29}) {
			BlockPos b = BlockPos.containing(p.getX() + ox, p.getY() - 0.2, p.getZ() + oz);
			if (!Terrain.state(b).isAir()) return b;
		}
		return null;
	}

	private boolean coolingDown(String what) {
		Long t = cooldown.get(what);
		return t != null && HumanAutopilot.ticks() < t;
	}

	// ---- each tick

	public String tick(LocalPlayer p, InputDriver in, AimController aim, SurvivalModule survival) {
		long now = HumanAutopilot.ticks();
		var o = InputDriver.opts();
		if (screen.active()) {
			screen.worldTick(p, in, aim);
			return "oneblock: " + screen.describe();
		}
		ScreenWorker.Result outcome = screen.takeOutcome();
		if (outcome != null) {
			settleUntil = now + Human.rangeInt(4, 8);
			if (outcome == ScreenWorker.Result.FAILED) {
				HumanAutopilot.LOGGER.info("[oneblock] {} didn't work out: {}", screen.lastJob(), screen.failReason());
				cooldown.put(screen.lastJob(), now + 200);
				if (lastRecipe != null) cooldown.put(lastRecipe, now + 600); // (not tried again every 3 s)
			}
			lastRecipe = null;
		}
		if (now < settleUntil) return "oneblock: a moment";

		if (gen == null || gen.distSqr(p.blockPosition()) > 40 * 40) {
			gen = findGen(p);
			genConfirmed = false;
			if (gen == null) {
				if (now - logAt > 200) {
					logAt = now;
					HumanAutopilot.LOGGER.info("[oneblock] no block round {} to start from", p.blockPosition().toShortString());
				}
				return "oneblock: no block round here";
			}
			placer.avoid(gen.above());
			bedPlacer.avoid(gen.above());
			HumanAutopilot.LOGGER.info("[oneblock] the one block: {} ({})", gen.toShortString(), Terrain.state(gen).getBlock().getName().getString());
		}
		watchGen(now);

		// A warden: it hunts by sound - crouched and still till it's gone (the way players get by one).
		Warden warden = nearest(p, Warden.class, 20);
		if (warden != null) {
			in.hold(o.keyShift);
			if (now - logAt > 200) {
				logAt = now;
				HumanAutopilot.LOGGER.info("[oneblock] a warden {} away: crouching still", String.format("%.0f", warden.distanceTo(p)));
			}
			if (warden.distanceTo(p) < 3.0) return fight(p, in, aim, warden, now); // (right on her: nothing else for it)
			aim.lookAt("ob-warden", () -> warden.position().add(0, 2, 0));
			return "oneblock: keeping still, a warden";
		}

		// A mob on the platform: hit it (closer in, sneaking, if it's a step or two off).
		LivingEntity mob = nearestMob(p, 6);
		if (mob != null && Math.abs(mob.getY() - p.getY()) < 2.5) {
			double d = mob.distanceTo(p);
			if (d > 2.8 && onPlatform(mob.position())) {
				in.hold(o.keyShift);
				keysTo(p, in, mob.getX() - p.getX(), mob.getZ() - p.getZ());
			}
			if (d < 4.5) return fight(p, in, aim, mob, now);
		}

		// Hungry: eat.
		if (survival.isEating() || survival.wantsToEat(p) && Inv.foodPoints(p) > 0) return survival.eatTick(p, in, aim);

		String s = bedGoal(p, in, aim, now);
		if (s != null) return s;
		s = tools(p, in, aim, now);
		if (s != null) return s;
		s = platform(p, in, aim, now);
		if (s != null) return s;
		s = pickUp(p, in, aim);
		if (s != null) return s;
		return mine(p, in, aim, now);
	}

	/** Breaks of the block counted (it changing to another block), and whether it's really the one block (it grew back). */
	private void watchGen(long now) {
		BlockState st = Terrain.state(gen);
		if (lastGenState != null && st != lastGenState && !st.isAir()) {
			broken++;
			lastGenBreakAt = now;
			if (!genConfirmed) {
				genConfirmed = true;
				HumanAutopilot.LOGGER.info("[oneblock] {} grew back as {}: that's the one block", gen.toShortString(), st.getBlock().getName().getString());
			}
		}
		lastGenState = st;
	}

	// ---- the goal: a bed, and sleep in it

	private String bedGoal(LocalPlayer p, InputDriver in, AimController aim, long now) {
		if (bed != null && !Terrain.state(bed).is(BlockTags.BEDS)) bed = null;
		if (bed == null && Inv.has(p, Inv.BED)) {
			String r = placeBed(p, in, aim, now);
			if (r != null) return r;
		}
		if (bed != null) {
			BlockPos b = bed;
			aim.lookAt("ob-bed", () -> Vec3.atCenterOf(b).add(0, 0.1, 0));
			var hr = Minecraft.getInstance().hitResult;
			if (now >= nextSleepClick && hr instanceof BlockHitResult bhr && Terrain.state(bhr.getBlockPos()).is(BlockTags.BEDS) && aim.error() < 5) {
				in.click(InputDriver.opts().keyUse);
				sleepClicks++;
				nextSleepClick = now + Human.rangeInt(60, 120);
				HumanAutopilot.LOGGER.info("[oneblock] into the bed (try {})", sleepClicks);
			}
			if (Math.hypot(b.getX() + 0.5 - p.getX(), b.getZ() + 0.5 - p.getZ()) > 3) keysTo(p, in, b.getX() + 0.5 - p.getX(), b.getZ() + 0.5 - p.getZ());
			return "oneblock: going to sleep";
		}
		// Three wool of one colour and the wood: a bed (at a crafting table).
		var wool = Inv.bedWool(p);
		if (wool == null || coolingDown("bed")) return null;
		int planks = Inv.count(p, Inv.PLANK), logs = Inv.count(p, Inv.LOG);
		boolean tableHere = table != null && Terrain.state(table).is(Blocks.CRAFTING_TABLE);
		int wood = planks + 4 * logs - (tableHere || Inv.has(p, Inv.TABLE) ? 0 : 4);
		if (wood < 3) return null;
		return atTable(p, in, aim, Recipe.bed(wool), "the bed", now);
	}

	private BlockPos bedOn, bedStand;
	private Direction bedDir;
	private long bedClickAt = -1, bedSince, bedLogAt;

	/**
	 * The bed on the platform: two platform blocks in a row with room over them (the foot on the first, the head on the
	 * next), and a third behind them to stand on, facing along the row - the bed goes down pointing the way she faces.
	 * Null when there's no such spot yet (the platform grows first).
	 */
	private String placeBed(LocalPlayer p, InputDriver in, AimController aim, long now) {
		var o = InputDriver.opts();
		if (bedOn == null || !roomFor(bedOn) || !roomFor(bedOn.relative(bedDir)) || !standable(bedStand) || now - bedSince > 400) {
			bedOn = null;
			double best = Double.MAX_VALUE;
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				// (The one block counts too: getting into the bed is the win, nothing's mined after it. A small platform
				// seldom has three blocks in a row without it.)
				BlockPos a = gen.offset(dx, 0, dz);
				if (!roomFor(a)) continue;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos b = a.relative(d), c = a.relative(d.getOpposite());
					if (!roomFor(b) || !standable(c)) continue;
					double score = c.distSqr(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ())) + (a.equals(gen) || b.equals(gen) ? 2 : 0);
					if (score < best) { best = score; bedOn = a; bedDir = d; bedStand = c; }
				}
			}
			if (bedOn == null) return null;
			bedSince = now;
			HumanAutopilot.LOGGER.info("[oneblock] the bed goes on {} (head {}), from {}", bedOn.toShortString(), bedDir.getName(), bedStand.toShortString());
		}
		int slot = Inv.hotbarSlot(p, Inv.BED);
		if (slot < 0) {
			screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(List.of(Inv.BED, PLATFORM)));
			return "oneblock: getting the bed out";
		}
		in.hold(o.keyShift);
		Vec3 stand = Vec3.atBottomCenterOf(bedStand.above());
		BlockPos on = support(p);
		if (on == null || !on.equals(bedStand) || Math.hypot(stand.x - p.getX(), stand.z - p.getZ()) > 0.3) {
			keysTo(p, in, stand.x - p.getX(), stand.z - p.getZ());
			aim.lookAt("ob-bedwalk", () -> stand.add(0, 0.5, 0));
			return "oneblock: over to put the bed down";
		}
		BlockPos a = bedOn;
		Vec3 top = new Vec3(a.getX() + 0.5 - bedDir.getStepX() * 0.15, a.getY() + 1.0, a.getZ() + 0.5 - bedDir.getStepZ() * 0.15);
		aim.lookAt("ob-bedput", () -> top);
		if (!in.selectHotbar(slot)) return "oneblock: putting the bed down";
		if (Terrain.state(a.above()).is(BlockTags.BEDS)) {
			bed = a.above();
			bedOn = null;
			HumanAutopilot.LOGGER.info("[oneblock] the bed's down at {}: to sleep", bed.toShortString());
			return "oneblock: the bed's down";
		}
		if (bedClickAt >= 0 && now - bedClickAt < 8) return "oneblock: putting the bed down";
		HitResult hr = Minecraft.getInstance().hitResult;
		if (now - bedLogAt > 60) {
			bedLogAt = now;
			HumanAutopilot.LOGGER.info("[oneblock] putting the bed on {}: crosshair {}, aim off {}, holding {}, at {} looking {} / {}", a.toShortString(),
					hr instanceof BlockHitResult b0 && hr.getType() == HitResult.Type.BLOCK ? b0.getBlockPos().toShortString() + " " + b0.getDirection() : hr == null ? "null" : hr.getType(),
					String.format("%.1f", aim.error()), p.getMainHandItem().getHoverName().getString(),
					String.format("%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ()), String.format("%.0f", p.getYRot()), String.format("%.0f", p.getXRot()));
		}
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(a)
				&& bhr.getDirection() == Direction.UP && aim.error() < 4) {
			in.click(o.keyUse);
			bedClickAt = now;
		}
		return "oneblock: putting the bed down";
	}

	/** A platform block (not the one block) with two free cells over it. */
	private boolean roomFor(BlockPos b) {
		return b != null && solid(b) && Terrain.state(b.above()).isAir() && Terrain.state(b.above(2)).isAir();
	}

	/** Somewhere to stand: solid, room for a body over it. */
	private static boolean standable(BlockPos b) {
		return b != null && solid(b) && Terrain.state(b.above()).getCollisionShape(Terrain.level(), b.above()).isEmpty()
				&& Terrain.state(b.above(2)).getCollisionShape(Terrain.level(), b.above(2)).isEmpty();
	}

	// ---- tools: a wooden pickaxe, then a stone one (stone by hand takes ages and drops nothing)

	private String tools(LocalPlayer p, InputDriver in, AimController aim, long now) {
		int pick = Inv.pickaxeTier(p);
		int planks = Inv.count(p, Inv.PLANK), logs = Inv.count(p, Inv.LOG), cobble = Inv.count(p, Inv.COBBLE);
		boolean tableHere = table != null && Terrain.state(table).is(Blocks.CRAFTING_TABLE);
		int spare = planks + 4 * logs - (tableHere || Inv.has(p, Inv.TABLE) ? 0 : 4) - (Inv.bedWool(p) != null ? 3 : 0);
		if (pick < 1 && spare >= 5 && !coolingDown(Recipe.WOOD_PICK.name())) return atTable(p, in, aim, Recipe.WOOD_PICK, "a wooden pickaxe", now);
		if (pick < 2 && cobble >= 3 && spare >= 2 && !coolingDown(Recipe.STONE_PICK.name())) return atTable(p, in, aim, Recipe.STONE_PICK, "a stone pickaxe", now);
		return null;
	}

	/** At the crafting table (made and put down first if there isn't one): a recipe. */
	private String atTable(LocalPlayer p, InputDriver in, AimController aim, Recipe r, String what, long now) {
		if (table != null && !Terrain.state(table).is(Blocks.CRAFTING_TABLE)) table = null;
		if (table == null) {
			if (!Inv.has(p, Inv.TABLE)) {
				if (coolingDown("crafting table")) return null;
				screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.BatchCraftJob(List.of(new ScreenWorker.Order(Recipe.TABLE, 1))));
				return "oneblock: making a crafting table for " + what;
			}
			if (coolingDown("table spot")) return null;
			Placer.Result pr = placer.tick(p, in, aim, Inv.TABLE, Blocks.CRAFTING_TABLE);
			switch (pr) {
				case DONE -> {
					table = placer.placed();
					placer.reset();
				}
				case NOT_IN_HOTBAR -> {
					screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(List.of(Inv.TABLE, PLATFORM)));
					return "oneblock: getting the crafting table out";
				}
				case NO_SPOT, FAILED -> {
					placer.reset();
					cooldown.put("table spot", now + 100); // (the platform grows first)
					return null;
				}
				default -> {
				}
			}
			return "oneblock: putting the crafting table down";
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(table)) > 4.2) {
			keysTo(p, in, table.getX() + 0.5 - p.getX(), table.getZ() + 0.5 - p.getZ());
			in.hold(InputDriver.opts().keyShift);
			return "oneblock: to the crafting table";
		}
		HumanAutopilot.LOGGER.info("[oneblock] crafting {}", what);
		screen.start(ScreenWorker.Kind.TABLE, table, new ScreenWorker.BatchCraftJob(List.of(new ScreenWorker.Order(r, 1))));
		cooldown.put(r.name(), now + 60); // (a failed sitting isn't tried again at once)
		lastRecipe = r.name();
		return "oneblock: crafting " + what;
	}

	// ---- the platform: the eight blocks round the one block, then the ring round those, out over the void

	private boolean onPlatform(Vec3 pos) {
		if (gen == null) return false;
		BlockPos under = BlockPos.containing(pos.x, pos.y - 0.2, pos.z);
		return under.getY() == gen.getY() && Math.abs(under.getX() - gen.getX()) <= 2 && Math.abs(under.getZ() - gen.getZ()) <= 2
				&& !Terrain.state(under).isAir();
	}

	private String platform(LocalPlayer p, InputDriver in, AimController aim, long now) {
		badBridge.values().removeIf(t -> t < now);
		int blocks = Inv.count(p, PLATFORM);
		if (blocks == 0) {
			bridgeTarget = null;
			return null;
		}
		// The next spot: ring 1 first (the cells next to the block first: she mines it from one of them), then ring 2
		// when there's plenty to build with; always one beside a block that's down already, the nearest to her.
		if (bridgeTarget != null && (solid(bridgeTarget) || badBridge.containsKey(bridgeTarget))) bridgeTarget = null;
		if (bridgeTarget == null) {
			BlockPos best = null;
			double bs = Double.MAX_VALUE;
			for (int r = 1; r <= 2 && best == null; r++) {
				if (r == 2 && blocks < 6) break;
				for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					BlockPos c = gen.offset(dx, 0, dz);
					if (!Terrain.state(c).isAir() || badBridge.containsKey(c)) continue;
					if (againstOf(c) == null) continue;
					double score = c.distSqr(p.blockPosition()) + (dx != 0 && dz != 0 ? 3 : 0);
					if (score < bs) { bs = score; best = c; }
				}
			}
			if (best == null) return null;
			bridgeTarget = best;
			bridgeSince = now;
			bridgeClickAt = -1;
		}
		return bridge(p, in, aim, bridgeTarget, now);
	}

	/**
	 * The solid block beside {@code c} (at its height) a block can be put against, standing on it - so with room for her
	 * over it (not the one under the crafting table) - or null.
	 */
	private BlockPos againstOf(BlockPos c) {
		BlockPos gotGen = null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = c.relative(d);
			if (!standable(n)) continue;
			if (n.equals(gen)) gotGen = n;
			else return n;
		}
		return gotGen;
	}

	/**
	 * One block out over the edge onto {@code target}: standing on the block beside it, sneaking out over that block's
	 * edge, looking back down at its side, a right-click - how a player builds out over the void.
	 */
	private String bridge(LocalPlayer p, InputDriver in, AimController aim, BlockPos target, long now) {
		var o = InputDriver.opts();
		BlockPos against = againstOf(target);
		if (against == null || now - bridgeSince > 200) {
			badBridge.put(target, now + 1200);
			var hr0 = Minecraft.getInstance().hitResult;
			HumanAutopilot.LOGGER.info("[oneblock] couldn't put a block at {}: leaving it a minute (against {}, standing on {} at {}, looking {} / {}, crosshair on {})",
					target.toShortString(), against == null ? "-" : against.toShortString(), support(p) == null ? "nothing" : support(p).toShortString(),
					String.format("%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ()), String.format("%.0f", p.getYRot()), String.format("%.0f", p.getXRot()),
					hr0 instanceof BlockHitResult b0 && hr0.getType() == HitResult.Type.BLOCK ? b0.getBlockPos().toShortString() + " " + b0.getDirection() : "nothing");
			bridgeTarget = null;
			return "oneblock: building the platform";
		}
		if (Terrain.state(target).isAir() == false) {
			bridgeTarget = null;
			return "oneblock: building the platform";
		}
		Direction toward = Direction.getApproximateNearest(target.getX() - against.getX(), 0, target.getZ() - against.getZ());
		in.hold(o.keyShift);
		// On the block it goes against first (walked over, crouched).
		BlockPos on = support(p);
		Vec3 mid = Vec3.atBottomCenterOf(against.above());
		if (on == null || !on.equals(against)) {
			keysTo(p, in, mid.x - p.getX(), mid.z - p.getZ());
			aim.lookAt("ob-walk" + against.asLong(), () -> mid.add(0, 0.3, 0));
			return "oneblock: building the platform (over to its edge)";
		}
		// Out over its edge, crouched (sneaking keeps her on it).
		double along = (p.getX() - mid.x) * toward.getStepX() + (p.getZ() - mid.z) * toward.getStepZ();
		// (To a point at its edge, in the middle of it sideways: off to one side she walked into the crafting table.)
		if (along < 0.74) keysTo(p, in, mid.x + toward.getStepX() * 0.78 - p.getX(), mid.z + toward.getStepZ() * 0.78 - p.getZ());
		// The eyes back down at the middle of the block's side. (Looking near its bottom from almost straight over it, a
		// degree off put the crosshair under the block - "crosshair on nothing" - and nothing went down.)
		Vec3 face = new Vec3(against.getX() + 0.5 + toward.getStepX() * 0.5, against.getY() + 0.55, against.getZ() + 0.5 + toward.getStepZ() * 0.5);
		aim.lookAt("ob-bridge" + target.asLong(), () -> face);
		int slot = Inv.hotbarSlot(p, PLATFORM);
		if (slot < 0) {
			screen.start(ScreenWorker.Kind.INVENTORY, null, new ScreenWorker.OrganizeJob(List.of(PLATFORM)));
			return "oneblock: getting blocks to build with out";
		}
		if (!in.selectHotbar(slot)) return "oneblock: building the platform";
		if (bridgeClickAt >= 0 && now - bridgeClickAt < 6) return "oneblock: building the platform";
		HitResult hr = Minecraft.getInstance().hitResult;
		if (along >= 0.5 && hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(against)
				&& bhr.getDirection() == toward && aim.error() < 6) {
			in.click(o.keyUse);
			bridgeClickAt = now;
		}
		return "oneblock: building the platform (" + target.toShortString() + ")";
	}

	// ---- drops lying on the platform

	private String pickUp(LocalPlayer p, InputDriver in, AimController aim) {
		if (gen == null) return null;
		ItemEntity best = null;
		double bd = Double.MAX_VALUE;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(6, 2, 6))) {
			if (!(e instanceof ItemEntity it) || !it.onGround() || Inv.tossedByUs(it)) continue;
			if (!onPlatform(it.position()) || p.distanceTo(it) < 0.7) continue;
			double d = p.distanceToSqr(it);
			if (d < bd) { bd = d; best = it; }
		}
		if (best == null) return null;
		ItemEntity it = best;
		in.hold(InputDriver.opts().keyShift); // (crouched on a small platform over the void)
		keysTo(p, in, it.getX() - p.getX(), it.getZ() - p.getZ());
		aim.lookAt("ob-item" + it.getId(), () -> it.position());
		return "oneblock: picking up " + it.getItem().getHoverName().getString();
	}

	// ---- mining the one block (and the chest that turns up on top of it)

	private String mine(LocalPlayer p, InputDriver in, AimController aim, long now) {
		var o = InputDriver.opts();
		BlockPos target = gen;
		BlockState top = Terrain.state(gen.above());
		if (top.is(Blocks.CHEST) || top.is(Blocks.TRAPPED_CHEST) || top.is(Blocks.BARREL)) target = gen.above(); // (its loot pops out)
		BlockState st = Terrain.state(target);
		// Off the block, onto the platform beside it, if there is one: mined from the side the drops land on the platform
		// (and she isn't standing in whatever it turns into - lava is on the list).
		BlockPos on = support(p);
		if (on != null && on.equals(gen)) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos side = gen.relative(d);
				if (solid(side) && Terrain.state(side.above()).isAir() && Terrain.state(side.above(2)).isAir()) {
					in.hold(o.keyShift);
					keysTo(p, in, side.getX() + 0.5 - p.getX(), side.getZ() + 0.5 - p.getZ());
					return "oneblock: stepping off the block";
				}
			}
		}
		if (!st.getFluidState().isEmpty()) {
			// Turned into a liquid: a block in it (it grows back from that when that's mined).
			return "oneblock: the block's a liquid - waiting";
		}
		boolean underfoot = on != null && on.equals(target);
		// The block under her feet (only at the start, nothing beside it yet): mined standing, and a jump on the very tick
		// it breaks - she's going up as it goes, and comes down on the one that grows back. (Standing on it, the client
		// takes the broken block away at once and the new one comes a tick or two later: she sank into it and fell
		// through to the void. Jumping all the time instead, it now and then broke just as she landed.)
		if (underfoot && breaksNextTick(p, target)) in.hold(o.keyJump);
		Digger.Result r = underfoot ? digger.digUnderfoot(target, p, in, aim) : digger.dig(target, p, in, aim);
		if (r == Digger.Result.NO_TOOL) {
			// Nothing right for it in the hotbar (stone with no pickaxe): fists then - it has to go.
			Vec3 c = Vec3.atCenterOf(target);
			aim.lookAt("ob-fist" + target.asLong(), () -> c);
			var hr = Minecraft.getInstance().hitResult;
			if (hr instanceof BlockHitResult bhr && bhr.getBlockPos().equals(target)) in.hold(o.keyAttack);
		} else if (r == Digger.Result.OUT_OF_REACH || r == Digger.Result.BLOCKED) {
			keysTo(p, in, target.getX() + 0.5 - p.getX(), target.getZ() + 0.5 - p.getZ());
			in.hold(o.keyShift);
		}
		if (now - lastGenBreakAt > 1200 && now - logAt > 600) {
			logAt = now;
			HumanAutopilot.LOGGER.info("[oneblock] the block at {} hasn't changed for a minute ({}, digger {})", gen.toShortString(),
					st.getBlock().getName().getString(), r);
		}
		return "oneblock: mining the block (" + st.getBlock().getName().getString() + ", " + broken + " so far)";
	}

	private static java.lang.reflect.Field progressField;

	/** The block being broken will break on the next tick (the game's own progress, plus a tick of it). */
	private static boolean breaksNextTick(LocalPlayer p, BlockPos pos) {
		var gm = Minecraft.getInstance().gameMode;
		if (gm == null) return false;
		try {
			if (progressField == null) {
				progressField = net.minecraft.client.multiplayer.MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
				progressField.setAccessible(true);
			}
			float done = progressField.getFloat(gm);
			float perTick = Terrain.state(pos).getDestroyProgress(p, p.level(), pos);
			return done > 0 && done + perTick >= 0.999f || perTick >= 0.999f;
		} catch (ReflectiveOperationException e) {
			return true; // (can't tell: jump all the time, the old way)
		}
	}

	// ---- fights

	private static <T extends Entity> T nearest(LocalPlayer p, Class<T> type, double r) {
		T best = null;
		double bd = r * r;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(r))) {
			if (!type.isInstance(e) || !e.isAlive()) continue;
			double d = e.distanceToSqr(p);
			if (d < bd) { bd = d; best = type.cast(e); }
		}
		return best;
	}

	private static LivingEntity nearestMob(LocalPlayer p, double r) {
		LivingEntity best = null;
		double bd = r * r;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(r))) {
			if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
			double d = e.distanceToSqr(p);
			if (d < bd) { bd = d; best = le; }
		}
		return best;
	}

	/** A full-charge hit at a time, the crosshair on it. */
	private String fight(LocalPlayer p, InputDriver in, AimController aim, LivingEntity mob, long now) {
		aim.setUrgent(true);
		aim.lookAt("ob-mob" + mob.getId(), () -> mob.position().add(0, mob.getBbHeight() * 0.7, 0));
		int sword = Inv.hotbarSlot(p, Inv::isSword);
		if (sword < 0) sword = Inv.hotbarSlot(p, Inv::isAxe);
		if (sword >= 0) in.selectHotbar(sword);
		var hit = Minecraft.getInstance().hitResult;
		if (hit instanceof EntityHitResult eh && eh.getEntity() == mob && p.getAttackStrengthScale(0.5f) >= 0.95f && now - hitAt > 8) {
			hitAt = now;
			in.click(InputDriver.opts().keyAttack);
		}
		return "oneblock: fighting the " + mob.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
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
