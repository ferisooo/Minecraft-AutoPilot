package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.net.BoxLink;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.schematic.CreativePicker;
import dev.humanautopilot.schematic.Placement;
import dev.humanautopilot.survival.Inv;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a box (the StreamToEarn Bedrock Box) filled the way a player in flight would: fly over, hover, look at
 * the block a hole sits on and right-click. Only real keys and the smooth camera; nothing is placed for us.
 * <p>
 * Style from the user's own recording (2026-10-12, 87 blocks in 31 s): about a click every 0.3 s as quick taps,
 * drifting around while placing instead of stopping, clicking whichever face is easiest (tops and sides alike),
 * from ~2.7 blocks away, nearest hole first, and one block type for everything.
 * <p>
 * One rule keeps it safe: never put a block on top of a hole. Our own body stands in holes, so that alone means
 * we can never put a lid over our head, and a hole we had to skip can't get sealed in.
 */
public final class BoxFillModule {
	private static final BoxFillModule INSTANCE = new BoxFillModule();
	/** Feet this far above the block we hover in, so we never "land" (landing ends flight). */
	private static final double HOVER = 1.5;
	/**
	 * The user: "always at least 2 blocks above the block below him, so he never touches the ground". 2 full blocks
	 * left the top layer's holes out of arm's reach (it got stuck); 1.5 still never touches down.
	 */
	private static final double CLEAR = 1.5;

	public static BoxFillModule get() {
		return INSTANCE;
	}

	/** The box interior and the item for each layer (index 0 = minY). */
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Item[] layers) {
		public Item itemAt(int y) {
			return layers[Math.clamp(y - minY, 0, layers.length - 1)];
		}

		public boolean inside(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}

		public String describe() {
			return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1) + " at " + minX + " " + minY + " " + minZ;
		}
	}

	private Box serverBox, localBox;
	private boolean on;
	private String note = "off";

	/**
	 * Build Schematic: the blocks to put where (instead of "fill every hole in the box"). Same flying, aiming and
	 * clicking; the holes are the plan's empty spots, bottom layer first, row by row, each block the right way round.
	 */
	private static Map<BlockPos, BlockState> plan;
	private Box planBox;
	private String planName = "";
	private int planDone, planTotal, planBlocked;
	private boolean paused;
	/** Spots where no click gives the right facing: after giving up on them twice, any facing will do. */
	private static final java.util.Set<BlockPos> relaxed = new java.util.HashSet<>();
	/**
	 * Big schematics (a cathedral is ~650k blocks): the plan by layer, and only a few layers looked at at a time
	 * (re-checking every block every few ticks ran the game at 1 FPS). Progress is counted a slice at a time.
	 */
	private final java.util.TreeMap<Integer, List<BlockPos>> planLayers = new java.util.TreeMap<>();
	private final List<BlockPos> planList = new ArrayList<>();
	private int activeY = Integer.MIN_VALUE, extraLayers = 4;
	private int countCursor, countDone, countBlocked;
	/**
	 * One count pass split by how each spot was counted (near: looked at; far: loaded but far; mem: out of sight, as last
	 * seen), and far holes that had been seen built: why the bar went 68% / 77% / 68% with her in about the same spot.
	 */
	private int cNearDone, cNearAll, cFarDone, cFarAll, cMemDone, cMemAll, cFarWasBuilt;
	private final StringBuilder cFarSample = new StringBuilder();
	private long countLogAt;

	/** A count pass done: what went into it, now and then. */
	private void countPassDone() {
		long now = HumanAutopilot.ticks();
		if (now - countLogAt >= 600) {
			countLogAt = now;
			HumanAutopilot.LOGGER.info("[count] pass: {} done = near {}/{} + far {}/{} + out of sight {}/{}; far holes seen built before {}{}",
					countDone, cNearDone, cNearAll, cFarDone, cFarAll, cMemDone, cMemAll, cFarWasBuilt,
					cFarSample.length() == 0 ? "" : " (" + cFarSample + ")");
		}
		cNearDone = cNearAll = cFarDone = cFarAll = cMemDone = cMemAll = cFarWasBuilt = 0;
		cFarSample.setLength(0);
	}
	/** The lowest layer still worth looking at; a full pass that finds a lower hole (TNT) moves it back down. */
	private int scanFromY = Integer.MIN_VALUE, passLowest = Integer.MAX_VALUE;
	/** Built: now keeping it that way (anything that gets blown up is put back). */
	private boolean guarding, repairing;
	/** Pillars we put up (not part of the schematic) to have something to build the floating bottom layer from. */
	private final java.util.Set<BlockPos> support = new java.util.HashSet<>();
	/** Exact mode: knock out what's in the way (the menu's Replace switch, taken when the build starts). */
	private static boolean replace = true;
	/** What the schematic has at a spot (air too; null outside it): to clear out what's in the way. Null: no clearing. */
	private static java.util.function.Function<BlockPos, BlockState> lookup;
	/** Clearing only from here up (below is the world's own ground the build was sunk into). */
	private static int clearFromY = Integer.MAX_VALUE;
	/** Temporary blocks put down to click against (spot -> the block they're for), knocked out again after. */
	private static final Map<BlockPos, BlockPos> temps = new HashMap<>();
	private final java.util.Set<BlockPos> scaffoldTried = new java.util.HashSet<>();
	/** How often our own block went in wrong and was knocked out to redo it: twice and it's left as it is. */
	private static final Map<BlockPos, Integer> redos = new HashMap<>();
	/**
	 * Spots tried again and again with no luck (nowhere to click it from, can't get there): left as they are until
	 * something next to them changes. And how often each spot failed.
	 */
	private static final java.util.Set<BlockPos> cant = new java.util.HashSet<>();
	/**
	 * Soil of the build's ground with no look at it from anywhere: as it is while what's round it stays as it was then
	 * (the value: around() then). See giveUp.
	 */
	private static final Map<BlockPos, Integer> buriedSoil = new HashMap<>();

	/**
	 * Soil holes round one that had no look at it from anywhere, to look at from here too (one every few ticks, see
	 * checkBuried): a TNT blast's buried dirt is a few hundred of them side by side, and she flew to each in turn to find
	 * out (300 a quarter of an hour while TNT was going off).
	 */
	private final java.util.ArrayDeque<BlockPos> buriedCheck = new java.util.ArrayDeque<>();
	private int buriedBatch;

	private void checkBuried(LocalPlayer p, Box b) {
		BlockPos c = buriedCheck.poll();
		if (c != null && !buriedSoil.containsKey(c) && !c.equals(target) && plan.get(c) != null && Placement.soil(plan.get(c))
				&& jobAt(c) == Placement.Job.PLACE && Math.abs(c.getX() - regX) <= REG_H - 2 && Math.abs(c.getZ() - regZ) <= REG_H - 2
				&& Math.abs(c.getY() - regY) <= REG_V - 2) {
			var hits = tuftHits;
			tuftHits = null;
			BlockPos spot;
			try {
				spot = standFor(p, b, c);
			} finally {
				tuftHits = hits;
			}
			if (spot == null) {
				buriedSoil.put(c.immutable(), around(c));
				buriedBatch++;
			}
		}
		if (buriedCheck.isEmpty() && buriedBatch > 0) {
			HumanAutopilot.LOGGER.info("[schem] {} more of the buried dirt round it left as it is too (no look at it from anywhere)", buriedBatch);
			buriedBatch = 0;
		}
	}

	/** Each of the six spots round {@code c} is solid, or another spot of the build still to go in: nothing open round it. */
	private static boolean insideBuild(BlockPos c) {
		for (Direction d : Direction.values()) {
			BlockPos n = c.relative(d);
			if (Terrain.state(n).canOcclude()) continue;
			BlockState w = plan.get(n);
			if (w == null || w.isAir()) return false;
		}
		return true;
	}

	/** What's in the six spots round {@code c}, as one number: changes when any of them does. */
	private static int around(BlockPos c) {
		int h = 1;
		for (Direction d : Direction.values()) h = h * 31 + System.identityHashCode(Terrain.state(c.relative(d)));
		return h;
	}
	private static final Map<BlockPos, Integer> fails = new HashMap<>();
	/** How often a block of ours was gone again as soon as it was in (see the "went in wrong" check). */
	private static final Map<BlockPos, Integer> vanished = new HashMap<>();
	/** What it stood on when it went (it gets another go only if that's changed). */
	private static final Map<BlockPos, BlockState> wontStayOn = new HashMap<>();
	/** When planDone last went up, and what it was: nothing in for 3 minutes near the end and what's left is left. */
	private long progressedAt = -1, progressGuardAt = -1;
	private long onlyCantSaidAt = -100000;
	/** When she last asked the server to put in the rest (/kc finish at 97%); -1: not yet for this build. */
	private long finishAskedAt = -1;
	/**
	 * 80% in on the bar: the server puts in the rest and we're on to the next build (see handOff). When, for this build
	 * (-1: not yet).
	 */
	private long handedOffAt = -1;
	private static final double HAND_OFF_AT = 0.70; // (80% at first; 90% on 2026-10-24; 70% on 2026-10-25, the user: "make the plugin finish and fills the schematic at 70 percent")
	/** Where and when "flying over to the rest of the build" last got anywhere (a /tp if it doesn't). */
	private Vec3 overFrom;
	private long overSince;
	private long stuckTpAt = -100000;
	/** When we last sent a /tp of our own (the tests let that one move us far in a tick). */
	public static long lastTpTick = -100000;
	private Vec3 stuckPos;
	private long stuckPosAt;
	private int progressedDone = -1;
	/** planDone every second for the last 3 minutes ({tick, done}): the wind-down near the end goes by it too. */
	private final java.util.ArrayDeque<long[]> doneSamples = new java.util.ArrayDeque<>();
	private static final Map<BlockPos, String> failWhy = new HashMap<>();
	/** How many times we've put each spot right: another block taking its place after that is the world's doing. */
	private static final Map<BlockPos, Integer> fixes = new HashMap<>();
	private Placement.Job lastJob;
	/**
	 * Out near the edge of what the game has loaded, blocks read as empty air until the real ones arrive (it flew back
	 * and forth chasing "holes" 90 blocks away that turned into grass). Only blocks this close count; the rest of the
	 * build is somewhere to fly to.
	 */
	private BlockPos farGoal;
	/** Our body hovering {@code up} over the floor of cell {@code c}. */
	private static AABB body(BlockPos c, double up) {
		return new AABB(c.getX() + 0.2, c.getY() + up, c.getZ() + 0.2, c.getX() + 0.8, c.getY() + up + 1.8, c.getZ() + 0.8);
	}

	/** Hovering just over the floor (to look up at something overhead), and whether the last stand found wants it. */
	private static final double LOW = 0.15;
	private boolean standLow, lastStandLow, lowHover;
	private static int planBottomY;
	/** Spots we've seen up close with nothing left to do (built, or something else is there): no need to fly back. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet seenDone = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** The memory of the work window: per layer, the spots last seen as holes (seenDone has the ones seen done). */
	private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> holesByLayer = new HashMap<>();
	/** Spots of the build that were done and aren't any more (TNT): repaired before anything else. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet damaged = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** The damaged spot she last set off to put back: kept first while it's still to do (no switching mid-flight). */
	private BlockPos dmgCommit;
	/** Where TNT near the build went off, and when to look the place over (tick): the damage is found at once. */
	private final List<long[]> blastChecks = new ArrayList<>();
	private final Map<Integer, Vec3> tntSeen = new HashMap<>();
	private List<Integer> winLayers = List.of();
	private final List<BlockPos> winCells = new ArrayList<>();
	private int winCursor;
	private long farGoalAt = -1000, clearScanAt = -1000;
	/** Spots seen built right (for the done count: far parts stay counted while we're elsewhere). */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet seenBuilt = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** The open-air search only covers this much round us in a schematic (a whole cathedral is millions of cells). */
	private static final int REG_H = 14, REG_V = 16;
	private static int regX, regY, regZ;
	private final Map<BlockPos, Integer> nowhere = new HashMap<>();
	/**
	 * Schematic: like a player, place from a few blocks off at a normal angle, clicking a face that faces us. Spots
	 * that can only be done looking straight down (a 1x1 hole) get that after we've failed to find a spot once.
	 */
	private final java.util.Set<BlockPos> steepOk = new java.util.HashSet<>();
	private static final double MAX_PITCH = 68, MIN_FACING = 0.35;

	private final List<BlockPos> holes = new ArrayList<>();
	private int holeCount = -1;
	private long scannedAt;
	private final Map<BlockPos, Long> bad = new HashMap<>();

	private BlockPos target, stand, lastPlaced, prevPlaced;
	private long lastPlacedAt;
	private double faceOffA, faceOffB;
	private long clickedAt = -1, nextClickAt, reactUntil;
	/** What the spot had when we last clicked it (a click that changed it wasn't wasted). */
	private BlockState clickedState;
	/** Since when we've held sneak to put a block against a chest (-1: not doing that). */
	private long sneakSince = -1;

	/** A placing click there won't open anything: not a chest or a door, or sneak held long enough for the server to know. */
	private boolean sneakOk(LocalPlayer p, BlockHitResult hit, long now) {
		return !Placement.usable(Terrain.state(hit.getBlockPos())) || (p.isShiftKeyDown() && sneakSince >= 0 && now - sneakSince >= 2);
	}
	private int tries;
	/** Clicks on this target since it was picked (not reset when its job flips, unlike tries). */
	private int targetClicks;
	private double bestDist;
	/** What bestDist measures: 0 the straight distance, 1 the cells of the way left (see route). */
	private int progressMode;
	private long progressAt;
	private boolean sprintTrip;
	private int takeoffTick = -1;
	private int tntSeenId = -1;
	private long tntReactAt;

	/** For the HUD and the tests. */
	public static long placedCount, skippedCount, dodgeCount, landings;
	/** Placing clicks (for the tests): how many, camera pitch and side distance summed, how many looking straight down. */
	public static long clicks, steepClicks;
	public static double clickPitchSum, clickDistSum;
	/** Ticks of each vertical input (for the tests): pushed up off the ground, up to a goal, down to a goal. */
	public static long vGuard, vUp, vDown;
	/** Ticks spent in each kind of work (for the tests). */
	public static final Map<String, Long> noteTicks = new HashMap<>();
	private boolean wasFlying;

	private BoxFillModule() {}

	public boolean on() {
		return on;
	}

	public String note() {
		return note;
	}

	public Box box() {
		if (planBox != null) return planBox;
		return serverBox != null ? serverBox : localBox;
	}

	/** The block we're working on (for the tests). */
	public BlockPos target() {
		return target;
	}

	public boolean planMode() {
		return plan != null;
	}

	public String planName() {
		return planName;
	}

	public int planDone() {
		return planDone;
	}

	/** The build's been counted through once since it started (planDone means something). */
	private boolean planCounted;

	/** For tests: the plan being built (read only; empty with none). */
	public static Map<BlockPos, BlockState> planView() {
		return plan == null ? Map.of() : java.util.Collections.unmodifiableMap(plan);
	}

	/** For tests: spots given up on (left as they are after their tries), and how many of them are in now all the same. */
	public int[] gaveUpStats() {
		int in = 0;
		if (plan != null) for (BlockPos c : cant) if (jobAt(c) == Placement.Job.DONE) in++;
		return new int[]{cant.size(), in};
	}

	public boolean planCounted() {
		return planCounted;
	}

	/**
	 * How much of it was already right when it was started (the world's own ground under it, mostly): the bar goes
	 * from there, so a new build starts at 0% (the user: "i started a new build and the percentage hotbar was already
	 * at 20 percent" - the japanese house's 2 layers of ground the world had already). -1: not counted yet.
	 */
	private int planAlready = -1;

	public int planAlready() {
		return planAlready;
	}

	/** A build carried on: how much was already right when it was first started (0: not known, from nothing). */
	public void setPlanAlready(int n) {
		planAlready = n;
	}

	/** The spots of the build seen built (to keep with the build, so a far end out of sight still counts after a relog). */
	public long[] seenBuiltNow() {
		return seenBuilt.toLongArray();
	}

	/**
	 * A build carried on: what was seen built last time, so what's too far off to be loaded now counts as it was (the
	 * user: "it was at like 40 percent now its at 10 percent" - the copper walls' far half, 208 across, wasn't loaded
	 * after the relog and counted as not built).
	 */
	public void seedSeenBuilt(long[] keys) {
		if (plan == null) return;
		int n = 0;
		for (long k : keys) {
			if (plan.containsKey(BlockPos.of(k)) && seenBuilt.add(k)) n++;
		}
		HumanAutopilot.LOGGER.info("[schem] {} spots seen built last time (counted as they were where they're out of sight)", n);
	}

	/** In a flat world: the ground under the build (soil under its floor) is left to the grass filler. */
	private static boolean leaveGround;

	public static void setLeaveGround(boolean on) {
		leaveGround = on;
	}

	/**
	 * A spot of the ground under the build: plain ground under its floor - grass, dirt, and stone, sand, mud too (the
	 * copper walls' ground had stone and mud in it: he dug the world's dirt out under the floor to put them in, the
	 * user's "autopilot placing blocks below the structure"). What's made under the floor - smooth stone, bricks, a path -
	 * is the build's still.
	 */
	public static boolean isGround(BlockPos c, BlockState want) {
		// (All of it under the floor, made or not - the copper walls' smooth stone down there: 85 of her 91 "no spot"
		// under the structure, the user's "confused" in among all those blocks. The refiller goes through blocks; she
		// only works from the floor up - the user: "the companion and the autopilot never go below structure to repair,
		// only the refiller repair anything below the structure including destroyed by tnt".)
		return clearFromY != Integer.MAX_VALUE && c.getY() < clearFromY;
	}

	/** How far it's got, 0..1, from where it was when it was started. */
	public double planProgress() {
		int base = Math.max(0, planAlready);
		if (planTotal - base <= 0) return planDone >= planTotal ? 1 : 0;
		return Math.min(1, Math.max(0, (planDone - base) / (double) (planTotal - base)));
	}

	public int planTotal() {
		return planTotal;
	}

	public boolean guarding() {
		return guarding;
	}

	/** Targets dropped unclicked (not to do any more by the time we got there): how often lately, since when. */
	private final Map<BlockPos, long[]> dropped = new HashMap<>();

	/** Being stuck (nothing at all to do): the last step tried, the holes then, and how often each step got her out. */
	private int stuckStep, stuckHoles;
	private long stuckStepAt;
	/** How often each step got her out (6: none did, they were left). */
	public static final int[] stuckSteps = new int[7];
	private static final String[] STUCK_STEPS = {"-", "another look", "clearing the way", "temporary blocks", "not waiting any more",
			"any way round", "leaving them"};
	/** Spots that don't wait on another any more (the stuck steps' "not waiting any more"). */
	private final java.util.Set<BlockPos> noWait = new java.util.HashSet<>();

	private void stuckLog(long idle, String what) {
		HumanAutopilot.LOGGER.info("[stuck] {} s, {} spot(s) left: step {} - {}", idle / 20, stuckHoles, stuckStep, what);
	}

	/** Where to fly over to while guarding (the next build's spot, not loaded from here), or null. */
	private BlockPos visit;
	private long visitSince;

	public void visit(BlockPos at) {
		visit = at;
		visitSince = HumanAutopilot.ticks();
	}

	public boolean visiting() {
		return visit != null;
	}

	private long visitDoneAt = -1000;

	/** Flying over to the next build's spot (visit): the note, or null once there (or after 2 minutes). */
	private String visitStep(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		Vec3 g = new Vec3(visit.getX() + 0.5, Math.max(p.getY(), visit.getY() + 6), visit.getZ() + 0.5);
		double dist = Math.hypot(g.x - p.getX(), g.z - p.getZ());
		if (dist < 16 || now - visitSince > 20 * 120) {
			HumanAutopilot.LOGGER.info("[schem] over at the next spot {} ({} away)", visit.toShortString(), Math.round(dist));
			visit = null;
			visitDoneAt = now;
			return null;
		}
		if (!p.getAbilities().flying && p.getAbilities().mayfly) {
			takeOff(p, in);
			return note = "taking off for the next build's spot";
		}
		work(aim, g.add(0, p.getEyeHeight(), 0));
		steer(p, in, route(p, b, g), false, 0.9);
		if (dist > 6) in.hold(InputDriver.opts().keySprint);
		air(p, b);
		return note = "flying over to the next build's spot";
	}

	public boolean paused() {
		return paused;
	}

	public void setPaused(boolean v) {
		paused = v;
		if (v) {
			target = null;
			stand = null;
			clickedAt = -1;
		} else {
			onSince = HumanAutopilot.ticks(); // (the pause isn't a stall: it read "STALL 61s" right after carrying on)
		}
	}

	/** Build these blocks (schematic mode). */
	public void startPlan(Map<BlockPos, BlockState> blocks, String name) {
		startPlan(blocks, name, null, Integer.MAX_VALUE);
	}

	/**
	 * Build these blocks exactly. {@code wantAt}: what the schematic has anywhere in its box (air too), so what's in
	 * the way where it's empty gets cleared from {@code clearFrom} up; null for no clearing.
	 */
	public void startPlan(Map<BlockPos, BlockState> blocks, String name, java.util.function.Function<BlockPos, BlockState> wantAt, int clearFrom) {
		setOn(false);
		lookup = wantAt;
		clearFromY = clearFrom;
		replace = dev.humanautopilot.schematic.SchematicMode.replace;
		temps.clear();
		shutDoors.clear();
		redos.clear();
		scaffoldTried.clear();
		gapTempsTried.clear();
		behindSince.clear();
		scaffoldGoes.clear();
		clearFirst.clear();
		clearedOnTop.clear();
		leafWay.clear();
		opened.clear();
		outFor.clear();
		outForAt.clear();
		outTries.clear();
		openedAt.clear();
		putBack.clear();
		plantNext.clear();
		flyFails.clear();
		openTries.clear();
		pocketTried.clear();
		lastPhase = -1;
		secAnchor = null;
		undone.clear();
		stuckCells.clear();
		stuckTimes.clear();
		portals = null;
		portalWait.clear();
		portalClear.clear();
		openedDoors.clear();
		portalFor = null;
		cant.clear();
		neverAt.clear();
		grown.clear();
		grewIn.clear();
		notGrewAt.clear();
		saplingsIn.clear();
		fails.clear();
		failWhy.clear();
		fixes.clear();
		lastJob = null;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos c : blocks.keySet()) {
			minX = Math.min(minX, c.getX());
			minY = Math.min(minY, c.getY());
			minZ = Math.min(minZ, c.getZ());
			maxX = Math.max(maxX, c.getX());
			maxY = Math.max(maxY, c.getY());
			maxZ = Math.max(maxZ, c.getZ());
		}
		plan = blocks;
		planBottomY = blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
		planLayers.clear();
		planList.clear();
		waterFirst.clear();
		for (var e : blocks.entrySet()) {
			BlockPos c = e.getKey();
			planLayers.computeIfAbsent(c.getY(), y -> new ArrayList<>()).add(c);
			planList.add(c);
			// Kelp, seagrass, a coral or a stair standing in water: the water first (a bucket), then the block into it
			// (kelp only goes into water; a coral put down dry dies before the water gets to it). The Himeji castle's moat:
			// 553 kelp, 52 seagrass, "would put null against grass" - 123 targets in 10 s, nothing placed.
			if (needsWaterIn(e.getValue())) waterFirst.put(c, e.getValue());
		}
		for (BlockPos c : waterFirst.keySet()) plan.put(c, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
		activeY = Integer.MIN_VALUE;
		extraLayers = 4;
		countCursor = countDone = countBlocked = 0;
		scanFromY = Integer.MIN_VALUE;
		passLowest = Integer.MAX_VALUE;
		guarding = repairing = false;
		visit = null;
		support.clear();
		needPillar.clear();
		cantPillar.clear();
		forcedPillars = 0;
		finalSweeps = 0;
		placedAts.clear();
		planStartedAt = HumanAutopilot.ticks();
		sweepsLeftNoted = false;
		sweepUntil = -1;
		sweepFar = null;
		noBridgeUntil = -1;
		seenDone.clear();
		holesByLayer.clear();
		waterWaitAt.clear();
		holesFull.clear();
		holesFullAt = -1000;
		damaged.clear();
		blastChecks.clear();
		tntSeen.clear();
		winLayers = List.of();
		winCells.clear();
		winCursor = 0;
		seenBuilt.clear();
		planBox = new Box(minX, minY, minZ, maxX, maxY, maxZ, new Item[]{net.minecraft.world.item.Items.STONE});
		planName = name;
		planTotal = blocks.size();
		planDone = 0;
		planCounted = false;
		planAlready = -1;
		noWait.clear();
		dropped.clear();
		doorRooms.clear();
		progressedAt = -1;
		progressedDone = -1;
		doneSamples.clear();
		finishAskedAt = -1;
		handedOffAt = -1;
		vanished.clear();
		buriedSoil.clear();
		buriedCheck.clear();
		farSeen.clear();
		blindAt.clear();
		farApproved = null;
		layerSkipAt.clear();
		farBuried.clear();
		clearAboveQueue.clear();
		hatchHeld.clear();
		wontStayOn.clear();
		relaxed.clear();
		nowhere.clear();
		steepOk.clear();
		yawLoose.clear();
		headingMoved.clear();
		paused = false;
		lastPlaced = prevPlaced = null;
		ringLayers.clear();
		takenOut.clear();
		flownTotal = 0;
		ringBases.clear();
		pocketLayers.clear();
		ringY0 = minY;
		ringY1 = maxY;
		shellLayers.clear();
		hatchesLogged.clear();
		ringX0 = minX - 1;
		ringZ0 = minZ - 1;
		ringW = maxX - minX + 3;
		ringD = maxZ - minZ + 3;
		skyTop = new int[ringW * ringD];
		java.util.Arrays.fill(skyTop, Integer.MIN_VALUE);
		for (var e : blocks.entrySet()) {
			if (e.getValue().isAir()) continue;
			BlockPos c = e.getKey();
			int i = (c.getX() - ringX0) * ringD + (c.getZ() - ringZ0);
			skyTop[i] = Math.max(skyTop[i], c.getY());
		}
		setOn(true);
	}

	/** Stop building the schematic (if we were). */
	public void stopPlan() {
		if (plan == null) return;
		dev.humanautopilot.net.BuildLink.stop();
		setOn(false);
		plan = null;
		planBox = null;
		planLayers.clear();
		planList.clear();
		CreativePicker.get().cancel();
	}

	/**
	 * 80% of the build in (the bar's percent): the server puts in the rest at once (BuildLink.finish - the companion
	 * plugin pastes what's still missing) and this one counts as done, so Keep going moves on to the next file. The user:
	 * "if the build is 80 percent done, then it gets filled right away. and my character moves on to the next
	 * schematic". Only where the server has the whole plan (BuildLink.sent): elsewhere it's built to the end as before.
	 * Our own temporary blocks already standing still come out (their blocks go in from the server); those not put in
	 * yet are dropped, and anything taken out to get at a spot is the server's to fill.
	 */
	private void handOff(long now) {
		handedOffAt = now;
		HumanAutopilot.LOGGER.info("[schem] {}% of {} in ({} of {}): the server puts in the rest, on to the next", Math.round(planProgress() * 100),
				planName, planDone, planTotal);
		dev.humanautopilot.net.BuildLink.finish();
		// (Out of the way of the fill-in first: it closes the build round us - see the "Done building" /tp.)
		var pl0 = Minecraft.getInstance().player;
		if (pl0 != null && planBox != null && canTp(pl0)) {
			int ty = Math.max(planBox.maxY() + 3, Mth.floor(pl0.getY()) + 3);
			HumanAutopilot.LOGGER.info("[schem] handing off: up over the build to y {} out of the way of the server's fill-in", ty);
			pl0.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", pl0.getX(), ty, pl0.getZ())); lastTpTick = HumanAutopilot.ticks();
			stuckTpAt = now;
		}
		for (BlockPos tmp : temps.keySet()) if (Terrain.state(tmp).canBeReplaced()) cant.add(tmp);
		opened.clear();
		outFor.clear();
		outForAt.clear();
		outTries.clear();
		openedAt.clear();
		damaged.clear();
		finalSweeps = 6; // (no looking the build over: the server has it)
		target = null;
	}

	/** Everything's in: say so (once), then keep watching it. */
	private void planComplete() {
		if (guarding && !repairing) return;
		if (!guarding) {
			HumanAutopilot.LOGGER.info("[schem] done: {} {}/{} ({} spots left as they are)", planName, planDone, planTotal, planBlocked);
			// (The ones left - inside the walls, under the floor, where she can't get at them - the server puts in, if it has the
			// companion plugin: the user, "so she won't 100 percent it?". See BuildLink.finish.)
			dev.humanautopilot.net.BuildLink.finish();
			int shown = 0;
			for (BlockPos c : planList) {
				if (shown >= 40) break;
				BlockState w = plan.get(c);
				if (w == null) continue;
				if (jobAt(c) != Placement.Job.BLOCKED) continue;
				shown++;
				HumanAutopilot.LOGGER.info("[schem] left as it is: {} wants {} has {}: {}", c.toShortString(), w, Terrain.state(c), blockedWhy(c));
			}
			String next = dev.humanautopilot.schematic.SchematicMode.get().nextName();
			// Handed off: the server puts the rest in all at once, round us too - then up over the build at once, not 20 s
			// later. (Knight's Order, 09:47: filled in round her, "air map 0 cells", 24 s hovering in the blocks before the
			// stuck /tp - the user: "it's 100 percent she's just standing there".)
			var pl0 = Minecraft.getInstance().player;
			if (handedOffAt >= 0 && pl0 != null && planBox != null && canTp(pl0)) {
				int ty = Math.max(planBox.maxY() + 3, Mth.floor(pl0.getY()) + 3);
				HumanAutopilot.LOGGER.info("[schem] handed off: up over the build to y {} before the server's fill-in closes round us", ty);
				pl0.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", pl0.getX(), ty, pl0.getZ())); lastTpTick = HumanAutopilot.ticks();
				stuckTpAt = HumanAutopilot.ticks();
			}
			HumanAutopilot.message("§aDone building " + planName + "!§r" + (planBlocked > 0 ? " (" + planBlocked + " spots left as they are)" : "")
					+ (next != null ? " Next up: §e" + next + "§r." : " Guarding it now."));
		} else {
			HumanAutopilot.LOGGER.info("[schem] repaired {}", planName);
		}
		guarding = true;
		repairing = false;
	}

	public int holesLeft() {
		return holeCount;
	}

	/** Spots of the build that were done and got blown out (or broken), not yet put back. */
	public int damagedCount() {
		return damaged.size();
	}

	/** Tests: the spots it has down as damaged. */
	public List<BlockPos> damagedSpots() {
		List<BlockPos> out = new ArrayList<>();
		for (var it = damaged.iterator(); it.hasNext(); ) out.add(BlockPos.of(it.nextLong()));
		return out;
	}

	/** Tests: what this spot of the plan still needs, and why it's left if it is. */
	public String jobInfo(BlockPos c) {
		if (plan == null || !plan.containsKey(c)) return "not in the plan";
		Placement.Job j = jobAt(c);
		return j + (j == Placement.Job.BLOCKED ? " (" + blockedWhy(c) + ")" : "") + (bad.containsKey(c) ? " skipped: " + failWhy.getOrDefault(c, "?") : "");
	}

	public void setOn(boolean v) {
		if (on == v) return;
		on = v;
		reset();
		onSince = HumanAutopilot.ticks();
		note = v ? "on" : "off";
		if (!v) paused = false;
		if (plan == null) BoxLink.sendState(v); // (the server plugin only cares about the box)
	}

	/** The plugin told us where the box is (null = no box here). */
	public void serverBox(Box b) {
		// The plugin repeats it every half second: only a box that actually changed starts us over (resetting on
		// every message dropped the target twice a second: flying back and forth, never getting anywhere).
		if (sameBox(serverBox, b)) return;
		serverBox = b;
		if (plan == null) reset(); // a schematic build has nothing to do with the box
		if (b != null) HumanAutopilot.LOGGER.info("[box] server box {}", b.describe());
	}

	private static boolean sameBox(Box a, Box b) {
		if (a == null || b == null) return a == b;
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ() && a.maxX() == b.maxX()
				&& a.maxY() == b.maxY() && a.maxZ() == b.maxZ() && java.util.Arrays.equals(a.layers(), b.layers());
	}

	/** Hold off building for a while (the server saw a win wipe). */
	public void pause(int ticks) {
		if (plan != null) return;
		reactUntil = Math.max(reactUntil, HumanAutopilot.ticks() + Math.clamp(ticks, 0, 200) + Human.reaction());
		target = null;
		stand = null;
		HumanAutopilot.LOGGER.info("[box] server says the box was wiped for a win: waiting {} ticks", ticks);
	}

	/** Set by hand with /autopilot box set (singleplayer and tests). */
	public void localBox(Box b) {
		localBox = b;
		reset();
	}

	public void reset() {
		holes.clear();
		holeCount = -1;
		bad.clear();
		target = null;
		stand = null;
		clickedAt = -1;
		takeoffTick = -1;
		lid = null;
		locked = null;
	}

	// ------------------------------------------------------------------ TNT

	/** Lit TNT close enough to hurt: a player who sees the fuse gets out of the way (after a moment to notice). */
	public PrimedTnt tntThreat(LocalPlayer p) {
		// In creative TNT can't hurt us: the user keeps filling right through it, so do we.
		if (!on || box() == null || p.getAbilities().invulnerable) return null;
		PrimedTnt worst = null;
		double best = 7.5;
		for (var e : p.level().getEntitiesOfClass(PrimedTnt.class, p.getBoundingBox().inflate(8))) {
			double d = e.distanceTo(p);
			if (d < best && e.getFuse() < 60) {
				best = d;
				worst = e;
			}
		}
		if (worst == null) {
			tntSeenId = -1;
			return null;
		}
		long now = HumanAutopilot.ticks();
		if (worst.getId() != tntSeenId) {
			tntSeenId = worst.getId();
			tntReactAt = now + Human.reaction();
		}
		return now >= tntReactAt ? worst : null;
	}

	public String dodge(LocalPlayer p, InputDriver in, AimController aim, PrimedTnt tnt) {
		Box b = box();
		Vec3 away = p.position().subtract(tnt.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
		// Watch it while backing off and climbing out of the box.
		aim.lookAt(tnt, () -> tnt.position().add(0, 0.5, 0));
		if (!p.getAbilities().flying) {
			if (p.getAbilities().mayfly) takeOff(p, in);
			else in.hold(InputDriver.opts().keyDown);
			return "TNT! getting away";
		}
		Vec3 goal = p.position().add(away.scale(5));
		goal = new Vec3(goal.x, Math.max(p.getY() + 3, b.maxY() + 2.5), goal.z);
		steer(p, in, goal, true, 1.0);
		if (dodgeCount == 0 || tnt.getId() != lastDodged) {
			dodgeCount++;
			lastDodged = tnt.getId();
			HumanAutopilot.LOGGER.info("[box] dodging lit TNT {} blocks away", String.format("%.1f", tnt.distanceTo(p)));
		}
		target = null;
		return "TNT! getting away";
	}

	private int lastDodged = -1;
	private long placingSince = -1;
	private long useUntil;
	private long lastTickAt;
	private Face locked;
	/** Where the time went for the current block (ticks), logged when a block takes long. */
	private int tFly, tAim, tWait, tPending, offFace;
	private BlockPos forceStandFor;
	private long badClearedAt;
	/** A roof we're breaking into (to reach a sealed hollow): keep digging down this column until we're through. */
	private BlockPos openingCol;
	private long openingUntil;
	private long atStandSince = -1;
	/** Lining up on a hover spot: tight tolerances in steer(). */
	private boolean precise;
	private long onSince;
	public static long stalls;
	private long pickedAt;
	private double pickTurn;
	private int lockMisses;
	private BlockPos lid;
	private long lidSince;
	public static long lidsBroken;

	/**
	 * The block to put in. By default whatever full block is in hand (the recorded player filled every layer with
	 * gold), else a layer block from the hotbar, else any full block there. {@code boxMatchLayers} = layer blocks.
	 */
	private static Item blockFor(LocalPlayer p, Box b, BlockPos t) {
		if (plan != null) {
			BlockState want = plan.get(t);
			return want == null ? net.minecraft.world.item.Items.AIR : Placement.placeItem(want);
		}
		if (HumanAutopilot.config().boxMatchLayers) return b.itemAt(t.getY());
		if (fillBlock(p.getMainHandItem())) return p.getMainHandItem().getItem();
		for (Item layer : b.layers()) if (Inv.hotbarSlot(p, s -> s.is(layer)) >= 0) return layer;
		for (int i = 0; i < 9; i++) {
			var s = p.getInventory().getItem(i);
			if (fillBlock(s)) return s.getItem();
		}
		return b.itemAt(t.getY());
	}

	private static boolean fillBlock(net.minecraft.world.item.ItemStack s) {
		if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return false;
		var block = bi.getBlock();
		return !(block instanceof net.minecraft.world.level.block.FallingBlock)
				&& block.defaultBlockState().isCollisionShapeFullBlock(Terrain.level(), BlockPos.ZERO);
	}

	private static String fmt(Vec3 v) {
		return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", v.x, v.y, v.z);
	}

	// ------------------------------------------------------------------ main tick

	/** Box filling this tick, or null when there's nothing for it to do (box full, no box, far away). */
	public String tick(LocalPlayer p, InputDriver in, AimController aim) {
		long t0 = System.nanoTime();
		tickStartNs = t0;
		// (What this tick's thinking went on, for a long one: the costs so far, to take off at the end.)
		Map<String, Long> before = cost.isEmpty() ? Map.of() : new HashMap<>(cost);
		String r = null;
		try {
			return r = tickInner(p, in, aim);
		} finally {
			long ms = (System.nanoTime() - t0) / 1_000_000;
			if (ms > slowestTickMs) slowestTickMs = ms;
			if (ms >= 5) slowTicks++;
			// A tick of 25 ms or more is a frame or two dropped on the stream: what it went on (at most every 10 s).
			long now = HumanAutopilot.ticks();
			if (ms >= 25 && now - longTickLogAt > 200) {
				longTickLogAt = now;
				StringBuilder sb = new StringBuilder();
				for (var e : cost.entrySet()) {
					long d = (e.getValue() - before.getOrDefault(e.getKey(), 0L)) / 1_000_000;
					if (d >= 1) sb.append(e.getKey()).append(' ').append(d).append(" ms, ");
				}
				HumanAutopilot.LOGGER.info("[box] long tick: {} ms ({}doing: {}, {} holes)", ms, sb, r, holes.size());
			}
		}
	}

	private long longTickLogAt = -1000;
	/** When this tick's thinking started (see giveUp: the temporary blocks and the open-up put off to a fresh tick). */
	private long tickStartNs;
	private final java.util.Set<BlockPos> deferredNowhere = new java.util.HashSet<>();

	/** How long box-fill thinking takes per game tick (for the tests): the worst, and how many took 5 ms+. */
	public static long slowestTickMs, slowTicks;
	/** Nanoseconds spent in each part of the thinking since the last status line (the long ticks are the user's "jitter"). */
	private static final Map<String, Long> cost = new java.util.LinkedHashMap<>();

	private static long costStart() {
		return System.nanoTime();
	}

	private static void costEnd(String what, long t0) {
		cost.merge(what, System.nanoTime() - t0, Long::sum);
	}

	private String tickInner(LocalPlayer p, InputDriver in, AimController aim) {
		String r = tickInner2(p, in, aim);
		if (r != null) {
			String what = r.replaceAll("[0-9(),]+.*", "").trim();
			noteTicks.merge(what, 1L, Long::sum);
			windowNotes.merge(what, 1, Integer::sum);
		}
		diagnostics(p);
		return r;
	}

	// ------------------------------------------------------------------ diagnostics (for the log, to find bugs later)

	/** What it spent its time on since the last status line, and what it gave up on. */
	private final Map<String, Integer> windowNotes = new HashMap<>();
	private final Map<String, Integer> windowSkips = new HashMap<>();
	private long statusAt = -1, statusPlaced, statusSkipped, statusStalls, statusLandings, statusSlowTicks;
	private int statusFetches, statusTargets;
	/** Blocks flown since the last status line, and where we were last tick. */
	private double flown;
	private Vec3 flownLast;
	/** All of it since the build started (the take-out log says how far he went before putting one back). */
	private double flownTotal;
	/** Targets picked recently (tick, where) and placements, to notice dithering: lots of picking, no placing. */
	private final java.util.ArrayDeque<long[]> picks = new java.util.ArrayDeque<>();
	private long confusedAt = -1000;
	public static int fetches, doorsOpened, wrongWay;
	/** Times it dropped everything to repair damage (the tests: none for what grew by itself). */
	public static int repairRuns;

	/** Spots of the build that grew something by themselves, left as they are (the tests). */
	public static int grownCount() {
		return grown.size() + grewIn.size();
	}

	private void diagnostics(LocalPlayer p) {
		long now = HumanAutopilot.ticks();
		if (!on) {
			statusAt = -1;
			return;
		}
		// How far it flew (back and forth shows as many blocks flown for each one placed).
		Vec3 here = p.position();
		if (flownLast != null) {
			double step = here.distanceTo(flownLast);
			if (step < 3) {
				flown += step;
				flownTotal += step;
			}
		}
		flownLast = here;
		if (statusAt < 0) {
			statusAt = now;
			statusPlaced = placedCount;
			statusSkipped = skippedCount;
			statusStalls = stalls;
			statusLandings = landings;
			statusSlowTicks = slowTicks;
			statusFetches = fetches;
			windowNotes.clear();
			windowSkips.clear();
			return;
		}
		// A status line every 30 s: progress, speed, where, what it's been doing, what it skipped and why.
		if (now - statusAt >= 600) {
			long placed = placedCount - statusPlaced;
			String flewNote = String.format(java.util.Locale.ROOT, ", flew %.0f blocks", flown);
			flown = 0;
			String what = windowNotes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
					.map(e -> e.getKey() + " " + e.getValue() * 5 / 100 + "s").reduce((a, b) -> a + ", " + b).orElse("-");
			HumanAutopilot.LOGGER.info("[status] {} | {} | +{} placed ({}/min), {} skipped {}, {} stalls, {} landings, {} menu trips, {} targets{} | at {} | layer {} window {} holes | slowest tick {} ms, {} ticks over 5 ms | doing: {}",
					plan != null ? "schematic " + planName : "box fill", plan != null ? planDone + "/" + planTotal + (guarding ? " guarding" : "") : holeCount + " holes left",
					placed, placed * 2, skippedCount - statusSkipped, windowSkips.isEmpty() ? "" : windowSkips,
					stalls - statusStalls, landings - statusLandings, fetches - statusFetches, statusTargets, flewNote,
					fmt(p.position()), plan != null ? activeY : "-", holes.size(), slowestTickMs, slowTicks - statusSlowTicks, what);
			// All of the 30 s by kind (the line above names only the top four).
			int[] kinds = new int[5]; // menu, flying, placing, looking, other
			int counted = 0;
			for (var e : windowNotes.entrySet()) {
				String k = e.getKey();
				int i = k.startsWith("getting ") || k.startsWith("picking ") ? 0
						: k.startsWith("flying") || k.startsWith("taking off") || k.startsWith("going") || k.startsWith("stepping back")
						|| k.startsWith("walking") || k.startsWith("out from under") || k.startsWith("heading") ? 1
						: k.startsWith("placing") || k.startsWith("taking out") || k.startsWith("knocking out") ? 2
						: k.startsWith("looking") ? 3 : 4;
				kinds[i] += e.getValue();
				counted += e.getValue();
			}
			HumanAutopilot.LOGGER.info("[status] time in the last 30 s: menu {}s, flying {}s, placing {}s, looking {}s, other {}s, nothing said {}s",
					kinds[0] / 20, kinds[1] / 20, kinds[2] / 20, kinds[3] / 20, kinds[4] / 20, Math.max(0, (int) (now - statusAt) - counted) / 20);
			StringBuilder cs = new StringBuilder();
			for (var e : cost.entrySet()) cs.append(e.getKey()).append(' ').append(e.getValue() / 1_000_000).append(" ms, ");
			HumanAutopilot.LOGGER.info("[status] thinking time in the last 30 s: {}{} holes in the window{}", cs, holes.size(),
					vertFirst > 0 ? ", " + vertFirst + " ticks getting to a gap's height before going in" : "");
			if (plan != null) {
				HumanAutopilot.LOGGER.info("[status] looking before flying: {} far spots looked over ({} workable from the sky, {} not), {} passed over as buried",
						farLooks, farYes, farNo, buriedPassed);
				var hs = dev.humanautopilot.net.BuildLink.helpers();
				if (!hs.isEmpty()) {
					StringBuilder sb = new StringBuilder();
					for (var hp : hs) sb.append(hp.name()).append(" at ").append(fmt(hp.at())).append(hp.target() == null ? " (nothing to do)" : " on " + hp.target().toShortString()).append("; ");
					HumanAutopilot.LOGGER.info("[status] helpers: {}{} spot checks left to them", sb, helperPassed);
				}
			}
			farLooks = farYes = farNo = buriedPassed = helperPassed = 0;
			vertFirst = 0;
			cost.clear();
			statusAt = now;
			statusPlaced = placedCount;
			statusSkipped = skippedCount;
			statusStalls = stalls;
			statusLandings = landings;
			statusSlowTicks = slowTicks;
			statusFetches = fetches;
			statusTargets = 0;
			slowestTickMs = 0;
			windowNotes.clear();
			windowSkips.clear();
		}
		// Confused: 8+ different targets in 10 s and nothing placed (flying back and forth, never getting anywhere).
		while (!picks.isEmpty() && now - picks.peekFirst()[0] > 200) picks.pollFirst();
		if (picks.size() >= 8 && now - lastPlacedAt > 200 && now - confusedAt > 200) {
			confusedAt = now;
			StringBuilder list = new StringBuilder();
			for (long[] e : picks) list.append(BlockPos.of(e[1]).toShortString()).append("; ");
			HumanAutopilot.LOGGER.info("[confused] {} targets in 10 s, nothing placed, at {} ({}): {} | skipped lately: {}",
					picks.size(), fmt(p.position()), note, list, windowSkips);
		}
	}

	private String tickInner2(LocalPlayer p, InputDriver in, AimController aim) {
		Box b = box();
		if (!on) return null;
		if (paused) {
			note = "paused";
			return null;
		}
		if (b == null) {
			note = "no box (server hasn't sent one)";
			return null;
		}
		if (!near(p, b)) {
			note = "box is far away";
			// Done, and over to the next build's spot to see what's there: on with that, not back to the build that's done.
			// (Out past this reach, "flying over to the build" took him back and after 10 s /tp'd him there - out, back,
			// out again: the user's "he's confused after the build is complete", the iron farm, 2026-10-25.)
			if (plan != null && guarding) {
				long vnow = HumanAutopilot.ticks();
				if (visit != null) {
					String v = visitStep(p, in, aim, b, vnow);
					if (v != null) return v;
				}
				// (Just there: a moment for the next spot to be looked at, hovering - not back across.)
				if (vnow - visitDoneAt < 200) {
					steer(p, in, p.position(), false, 0.9);
					return note = "at the next build's spot, looking";
				}
			}
			// A schematic out there (the next one, started on open ground beside the last - just past this reach from where
			// that one ended): over to it, over its top, not waiting for ever (the fantasy castle: 13 minutes at 0 placed,
			// hovering where the copper guard had finished).
			if (plan != null) {
				Vec3 g = new Vec3((b.minX() + b.maxX()) / 2.0 + 0.5, b.maxY() + 3, (b.minZ() + b.maxZ()) / 2.0 + 0.5);
				// (Up off the ground first: steering only flies - she stood on the copper guard's wall with "flying over to the
				// build" for half a minute, not moving.)
				if (!p.getAbilities().flying && p.getAbilities().mayfly) {
					takeOff(p, in);
					return note = "taking off for the build";
				}
				// (Up to its height first, then across: over whatever's between, the last build's walls.)
				if (p.getY() < g.y - 2) g = new Vec3(p.getX(), g.y, p.getZ());
				work(aim, g.add(0, p.getEyeHeight(), 0));
				steer(p, in, g, false, 0.9);
				if (p.position().distanceTo(g) > 6) in.hold(InputDriver.opts().keySprint);
				// Not getting anywhere for 10 s (shut in under the giant tree's roots, the iron farm 125 blocks off: 3 minutes
				// in place, "still stuck"): over there by /tp.
				long tnow = HumanAutopilot.ticks();
				if (overFrom == null || p.position().distanceTo(overFrom) > 2) {
					overFrom = p.position();
					overSince = tnow;
				} else if (tnow - overSince > 200 && canTp(p)) {
					overSince = tnow;
					HumanAutopilot.LOGGER.info("[schem] not getting over to the build from {} for 10 s: /tp to {}", fmt(p.position()), fmt(g));
					p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f", g.x, g.y, g.z)); lastTpTick = HumanAutopilot.ticks();
				}
				return note = "flying over to the build";
			}
			return null;
		}
		long now = HumanAutopilot.ticks();
		if (holeCount < 0 || now - scannedAt >= 5) {
			long c0 = costStart();
			scan(b, now);
			costEnd("scan", c0);
		}
		farLookBudget = FAR_LOOKS; // (far spots looked over this tick, at most: see workableFromAfar)
		if (plan != null) {
			sendHold(now);
			sendNeed(now);
			talk(p, now);
		}
		if (now - lastTickAt > 1) {
			// Something else had the controls (a short break, a look at a noise): the give-up clocks start over.
			placingSince = -1;
			progressAt = now;
			bestDist = Double.MAX_VALUE;
		}
		lastTickAt = now;

		// The last click worked: on to the next one after a short beat.
		if (target != null && !hole(target)) {
			if (clickedAt >= 0) {
				placedCount++;
				// (Not one taken out on purpose to get at another: it's meant to be empty for now. Counted as "gone again
				// as soon as it was in", the build's own short grass got left out for good.)
				if (plan != null && plan.get(target) != null && !Placement.matches(Terrain.state(target), plan.get(target))
						&& !Placement.slabToDouble(Terrain.state(target), plan.get(target)) && !opened.containsKey(target)
						&& !outFor.containsKey(target) && !temps.containsKey(target)) {
					wrongWay++;
					HumanAutopilot.LOGGER.info("[schem] {} went in wrong: wanted {} got {} (looking {}/{}, from {})", target.toShortString(),
							plan.get(target), Terrain.state(target), Math.round(p.getYRot()), Math.round(p.getXRot()), fmt(p.getEyePosition()));
					// Gone again at once (a button or a torch with nothing it can hang on): twice and it's left as it is. It
					// never counted as a failed go, so it was tried again every minute for ever - the floating pagoda's last
					// birch button, 11 minutes of flying out to look at the build and back, "it got confused and stopped".
					BlockState after = Terrain.state(target);
					if (after.isAir() || after.canBeReplaced()) {
						int n = vanished.merge(target.immutable(), 1, Integer::sum);
						wontStayOn.put(target.immutable(), Terrain.state(target.below()));
						failWhy.put(target, "it won't stay there (gone again as soon as it's in, " + n + " times)");
						if (n >= 2 && cant.add(target.immutable())) {
							HumanAutopilot.LOGGER.info("[schem] leaving {} ({}) as it is: it won't stay there, gone again {} times", target.toShortString(),
									plan.get(target), n);
						}
					}
				}
				TakeOut back = plan == null ? null : takenOut.get(target);
				if (back != null && plan.get(target) != null && Terrain.state(target).getBlock() == plan.get(target).getBlock()) {
					takenOut.remove(target);
					HumanAutopilot.LOGGER.info("[undo] put {} back at {} after {} s (taken out {}; flew {} blocks meanwhile, {} still out) - {}% in",
							name(plan.get(target).getBlock().asItem()), target.toShortString(), (now - back.at()) / 20, back.why(),
							Math.round(flownTotal - back.flown()), takenOut.size(), Math.round(planProgress() * 100));
				}
				if (plan != null && jobAt(target) == Placement.Job.DONE) {
					fixes.merge(target, 1, Integer::sum);
					fails.remove(target);
					sealChecked.clear(); // (two last roof holes each counted the other as the way in: look again)
					// Something changed next to these: worth another go now (a log waiting for the log beside it to click
					// on sat out its whole minute-long wait after that one went in).
					for (Direction d : Direction.values()) {
						BlockPos nb = target.relative(d);
						bad.remove(nb);
						cant.remove(nb);
						// And two away: a temporary block in the empty spot beside this one can stand on it now, for the
						// one next to that (an upright basalt over air in the statue's ribbon tried its temporary block
						// once, before this was in, and was given up on for good).
						BlockState nw = plan.get(nb);
						if ((nw != null && !nw.isAir()) || !Terrain.state(nb).isAir()) continue;
						for (Direction e : Direction.values()) {
							BlockPos t2 = nb.relative(e);
							if (t2.equals(target) || !scaffoldTried.remove(t2)) continue;
							if (failWhy.getOrDefault(t2, "").startsWith("nowhere")) {
								cant.remove(t2);
								bad.remove(t2);
							}
						}
					}
				}
				if (now - pickedAt > 16) {
					HumanAutopilot.LOGGER.info("[box] slow block {}: {} ticks (flying {}, aiming {}, waiting {}, click pending {}), {} deg away when picked",
							target.toShortString(), now - pickedAt, tFly, tAim, tWait, tPending, String.format("%.0f", pickTurn));
				}
				// Farmland in (put in or tilled): its crop goes in next, before it dries back to dirt (the cottage's wheat came
				// minutes later with the outside: 17 "nowhere", the farmland grass again, 4 left out).
				if (plan != null && Terrain.state(target).is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
					BlockState crop = plan.get(target.above());
					if (crop != null && !crop.isAir() && hole(target.above())) plantNext.add(target.above().immutable());
				}
				prevPlaced = lastPlaced;
				lastPlaced = target;
				lastPlacedAt = now;
				placedAts.addLast(now);
				while (placedAts.size() > 30) placedAts.removeFirst();
				failStreak = 0;
				skyAt = -1000; // (the way out is worked out again: that block may have been on it)
				noWayOutSince = -1;
				// The recorded rhythm: a click every ~0.3 s (0.2-0.4), now and then a longer beat.
				nextClickAt = now + (int) Math.round(Human.gauss(1, 3)) + (Human.chance(0.03) ? Human.rangeInt(2, 5) : 0);
			} else if (plan != null) {
				// Picked, and not to do by the time we got there, a second time in 2 minutes: left alone a while. (Something
				// made it look like one to do every pick: the copper walls' bedrock, 2 hours of flying back and forth.)
				long[] dr = dropped.computeIfAbsent(target.immutable(), k -> new long[]{0, now});
				if (now - dr[1] > 2400) {
					dr[0] = 0;
					dr[1] = now;
				}
				if (++dr[0] >= 2) {
					bad.put(target.immutable(), now + 2400);
					failWhy.put(target.immutable(), "picked again and again, never to do when we got there");
					if (dr[0] == 2) HumanAutopilot.LOGGER.info("[schem] {} picked again and again, never to do when we got there: leaving it 2 minutes",
							target.toShortString());
				}
				if (now - droppedLogAt > 20) {
					droppedLogAt = now;
					HumanAutopilot.LOGGER.info("[schem] {} isn't to do any more (we didn't click it): now {}, loaded {}, {} blocks away",
							target.toShortString(), Terrain.state(target), Terrain.loaded(target.getX(), target.getZ()),
							String.format("%.0f", Math.sqrt(target.distToCenterSqr(p.position()))));
				}
			}
			target = null;
			clickedAt = -1;
		}
		// The last few hundred and hardly any going in (fewer than 5 in 3 minutes, no damage to put right): what's left
		// is what can't be done from anywhere he's tried - left as it is, and done, rather than fumbling on for ever (the
		// cottage's last 80: 20 minutes, a block or two a minute, never "done").
		// (Once, then again only 3 minutes on: it put its own clock back to one placing, and with fewer than 5 the clock
		// is the plan's start - "winding down ... 0 left as they are" every second, the castle top's end.)
		// (Or 98% in and a few hundred more left, nearly all inside the walls where nothing can get at them, fewer than 30
		// going in in 3 minutes: the copper guard at 98.9%, 970 left, a block every half a minute - "it shows 98 percent
		// and she's flying around".)
		boolean nearlyAll = planCounted && planTotal > 0 && planDone >= planTotal * 0.98;
		// 97% in: the server puts the rest in (/kc finish) - the user: "just make her do kc finish at 97 percent". (The
		// floating pagoda's last 3% was an hour of temporary blocks and long flights, then 25 minutes round one button.)
		// Again 5 minutes on if it's still not all in (TNT since, or the server didn't have the build yet).
		if (plan != null && planCounted && planTotal > 0 && planDone < planTotal && planDone >= planTotal * 0.97 && now % 20 == 0
				&& (finishAskedAt < 0 || now - finishAskedAt > 6000) && dev.humanautopilot.net.BuildLink.sent()) {
			finishAskedAt = now;
			HumanAutopilot.LOGGER.info("[schem] {} of {} in (97%+): asking the server to put in the rest (/kc finish)", planDone, planTotal);
			p.connection.sendCommand("kc finish");
		}
		if (plan != null && now % 100 == 0 && !takenOut.isEmpty()) {
			// Put back without a click of ours (the server's fill-in, a helper): said so, off the list.
			takenOut.entrySet().removeIf(e -> {
				BlockState w = plan.get(e.getKey());
				if (w == null || Terrain.state(e.getKey()).getBlock() != w.getBlock()) return false;
				HumanAutopilot.LOGGER.info("[undo] {} is back at {} after {} s (taken out {}; put in without a click of ours)",
						name(w.getBlock().asItem()), e.getKey().toShortString(), (now - e.getValue().at()) / 20, e.getValue().why());
				return true;
			});
		}
		if (plan != null && !guarding && handedOffAt < 0 && planCounted && planTotal > 0 && planProgress() >= HAND_OFF_AT
				&& now % 20 == 0 && dev.humanautopilot.net.BuildLink.sent()) handOff(now);
		// (And the count itself, not only what she clicked: 98% in, "+53 placed" every half minute - temporary blocks and
		// dirt pillars in and out, fence gates with nothing to hang on - while the castle stood at 62,272-62,278 of
		// 63,299 for 15 minutes. Fewer than 30 placed never came, so it never wound down.)
		if (planCounted && now % 20 == 0) {
			doneSamples.addLast(new long[] {now, planDone});
			while (now - doneSamples.peekFirst()[0] > 3620) doneSamples.removeFirst();
		}
		boolean countStill = nearlyAll && !doneSamples.isEmpty() && now - doneSamples.peekFirst()[0] >= 3580
				&& planDone - doneSamples.peekFirst()[1] < 30;
		if (plan != null && !guarding && holeCount > 0
				&& (holeCount <= 300 ? placedFewerThan(5, now) : nearlyAll && (placedFewerThan(30, now) || countStill))
				&& (!damageToDo() || countStill) && now % 20 == 0 && now - windDownAt > 3600 && now - planStartedAt > 3600) {
			// (Damage too, once the count stands still: 69 blown-out spots "waiting on their column", out of reach, kept
			// "damage to do" up for good and the castle at 98.3% - "i thought the build should've auto complete itself".)
			windDownAt = now;
			String few = holeCount <= 300 ? "fewer than 5 in 3 minutes"
					: placedFewerThan(30, now) ? "98% in, fewer than 30 in 3 minutes" : "98% in, the count up fewer than 30 in 3 minutes";
			int n = 0;
			for (BlockPos h : new ArrayList<>(holes)) {
				if (!hole(h) || temps.containsKey(h) || !cant.add(h)) continue;
				failWhy.put(h, "the last ones: " + few + (failWhy.containsKey(h) ? " (" + failWhy.get(h) + ")" : ""));
				n++;
			}
			// (98% in: the rest of the build too, every layer, not this window's layers only - left a layer at a time, the
			// next layer's leftovers came up each time, 3 minutes apart: "she won't 100 percent it". The server puts them in
			// once she's done - BuildLink.finish.)
			if (holeCount > 300) {
				for (BlockPos c : planList) {
					if (temps.containsKey(c) || cant.contains(c) || seenDone.contains(c.asLong())) continue;
					boolean known = Terrain.loaded(c.getX(), c.getZ()) && !Terrain.state(c).is(net.minecraft.world.level.block.Blocks.VOID_AIR);
					Placement.Job jc = known ? jobAt(c) : (seenBuilt.contains(c.asLong()) ? Placement.Job.DONE : Placement.Job.PLACE);
					seenDone.add(c.asLong());
					if (jc == Placement.Job.DONE || jc == Placement.Job.BLOCKED) continue;
					cant.add(c);
					failWhy.put(c, "the last ones: " + few);
					n++;
				}
			}
			damaged.removeIf((java.util.function.LongPredicate) k -> cant.contains(BlockPos.of(k)));
			opened.clear();
			outFor.clear();
			outForAt.clear();
			outTries.clear();
			openedAt.clear();
			finalSweeps = 6; // (looked over enough: these were all tried)
			if (n > 0) HumanAutopilot.LOGGER.info("[schem] winding down: {}, {} left as they are", few, n);
			placedAts.clear();
			placedAts.addLast(now);
		}
		// Nothing at all gone in for 3 minutes, a few left: those are left as they are, whatever's keeping them out. (A loop
		// that kept the clocks above going - picked again every minute, failed, a look at the build, picked again - kept
		// the floating pagoda "building" 1 button for 11 minutes, never done, never on to the next.)
		if (plan != null && !guarding && planCounted) {
			if (now - progressGuardAt > 20) progressedAt = now; // (paused, or a menu open: that's not stuck)
			progressGuardAt = now;
			if (planDone != progressedDone) {
				progressedDone = planDone;
				progressedAt = now;
			} else if (holeCount > 0 && now - progressedAt > 3600) {
				progressedAt = now;
				List<BlockPos> stuck = new ArrayList<>();
				for (BlockPos h : holes) if (hole(h) && !temps.containsKey(h) && !cant.contains(h)) stuck.add(h);
				// (Only spots near us count as holes: from out where he'd gone to look at the build, the pagoda's button
				// 30 blocks off was "0 holes" but its layer still open. The open spots of the layers left, then.)
				if (stuck.isEmpty() && activeY != Integer.MIN_VALUE) {
					outer:
					for (var e : planLayers.tailMap(activeY, true).entrySet()) {
						for (BlockPos c : e.getValue()) {
							if (seenDone.contains(c.asLong()) || temps.containsKey(c) || cant.contains(c) || !Terrain.loaded(c.getX(), c.getZ()) || !hole(c)) continue;
							stuck.add(c);
							if (stuck.size() > 50) break outer;
						}
					}
				}
				// (A few left only: the end of it, not a big build still going.)
				if (!stuck.isEmpty() && stuck.size() <= 50) {
					List<String> left = new ArrayList<>();
					for (BlockPos h : stuck) {
						if (!cant.add(h)) continue;
						failWhy.put(h, "nothing went in for 3 minutes" + (failWhy.containsKey(h) ? " (" + failWhy.get(h) + ")" : ""));
						if (left.size() < 8) left.add(h.toShortString() + " " + (plan.get(h) == null ? "?" : plan.get(h).getBlock().getName().getString()));
					}
					HumanAutopilot.LOGGER.info("[schem] nothing has gone in for 3 minutes: leaving the last {} as they are: {}", stuck.size(), left);
				}
			}
		}
		// Stall report: 3 s without a block while holes are left -> say exactly what we're doing, once a second.
		if (holeCount > 0 && now - Math.max(lastPlacedAt, onSince) > 60 && (now - lastPlacedAt) % 20 == 0) {
			stalls++;
			HumanAutopilot.LOGGER.info("[box] STALL {}s: at {} look {}/{} | target {} stand {} forced {} offFace {} lid {} skipped {} shaft {} flying {} | steering to {} ({}), low {} | {} | last steer {} ticks ago: {}",
					(now - Math.max(lastPlacedAt, onSince)) / 20, fmt(p.position()), Math.round(p.getYRot()), Math.round(p.getXRot()),
					target == null ? "-" : target.toShortString(), stand == null ? "-" : stand.toShortString(),
					forceStandFor != null, offFace, lid == null ? "-" : lid.toShortString(), bad.size(), inShaft(p),
					p.getAbilities().flying, lastRoute == null ? "-" : fmt(lastRoute), lastRouteWhy, lowHover, note,
					now - steerAt, steerWhy);
			// 20 s with nothing in and hardly moving, in the build: up over its top by /tp, and on from there. (The giant tree's roots: under
			// them at 51, -60, -118 for 100 s, "under a roof ... no way out found" with the sides open, 9 holes she couldn't get
			// at - the user had to say "stuck".)
			long stuckFor = now - Math.max(lastPlacedAt, onSince);
			// (20 s, the user's ask - but only hardly moving: a long flight down the tree to the next hole is 20 s too.)
			if (stuckPos == null || p.position().distanceTo(stuckPos) > 3) {
				stuckPos = p.position();
				stuckPosAt = now;
			}
			// (Or 30 s with nothing in, moving or not: walking round inside a pocket she'd dug under the copper guard's ground,
			// "shut in ... 120 open cells", kept moving 3 blocks now and then - 42 s before the /tp, "getting stuck couple of
			// times".)
			if ((stuckFor > 400 && now - stuckPosAt > 400 || stuckFor > 600) && now - stuckTpAt > 400 && plan != null && b != null && canTp(p)) {
				stuckTpAt = now;
				int ty = Math.max(b.maxY() + 3, Mth.floor(p.getY()) + 3);
				HumanAutopilot.LOGGER.info("[schem] nothing in for {} s at {}: /tp up over the build to y {}", stuckFor / 20, fmt(p.position()), ty);
				p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", p.getX(), ty, p.getZ())); lastTpTick = HumanAutopilot.ticks();
				// And not straight back down to the same block: the giant tree, 01:18, /tp up 30 blocks, back down to the
				// one he'd been stuck on under the trunk, stuck again, /tp again ("[confused] 8 targets in 10 s").
				if (target != null) {
					bad.put(target.immutable(), now + 2400);
					target = null;
					stand = null;
				}
			}
		}
		if (holeCount == 0) {
			if (plan != null && closeDoor(p, in, aim, b, now)) return note;
			if (plan != null) {
				String lighting = lightPortals(p, in, aim, b, now);
				if (lighting != null) return note = lighting;
			}
			// One last look over all of it first: spots hanging in the air don't hold a layer open, and once the window
			// had gone up past them nothing went back down for them (the obsidian bridge's two ends over the valley:
			// "done" at 1394/1646, 0 left as they are, 252 missing). Parts too far off to be loaded that were never seen
			// in: over there to look.
			if (plan != null && !guarding && now < sweepUntil) {
				if (sweepFar != null) {
					Vec3 g = new Vec3(sweepFar.getX() + 0.5, Math.max(p.getY(), sweepFar.getY() + 3), sweepFar.getZ() + 0.5);
					double dist = Math.hypot(g.x - p.getX(), g.z - p.getZ());
					if (Terrain.loaded(sweepFar.getX(), sweepFar.getZ()) && dist < 24) {
						sweepUntil = now; // there: the scan takes it from here
					} else {
						work(aim, g.add(0, p.getEyeHeight(), 0));
						steer(p, in, route(p, b, g), false, 0.9);
						if (dist > 6) in.hold(InputDriver.opts().keySprint);
						air(p, b);
						return note = "flying over to look at the far end";
					}
				} else {
					return note = "looking the build over";
				}
			}
			if (plan != null && !guarding && finalSweeps >= 6 && now >= sweepUntil && !sweepsLeftNoted) {
				// Looked it over 6 times and these still won't go in: left as they are, and said so (the gothic portal's
				// last stair: "done 1209/1210 (0 spots left as they are)").
				sweepsLeftNoted = true;
				for (BlockPos c : planList) {
					if (cant.contains(c) || !Terrain.loaded(c.getX(), c.getZ()) || temps.containsKey(c)) continue;
					Placement.Job j = jobAt(c);
					if (j != Placement.Job.PLACE && j != Placement.Job.BREAK && j != Placement.Job.ADJUST) continue;
					cant.add(c);
					failWhy.put(c, "still not in after looking the build over 6 times" + (failWhy.containsKey(c) ? " (" + failWhy.get(c) + ")" : ""));
				}
			}
			if (plan != null && !guarding && finalSweeps < 6 && now >= sweepUntil) {
				int low = Integer.MAX_VALUE, n = 0;
				BlockPos farC = null;
				double farD = Double.MAX_VALUE;
				for (BlockPos c : planList) {
					if (cant.contains(c)) continue;
					BlockState w0 = plan.get(c);
					if (w0 == null) continue;
					if (!Terrain.loaded(c.getX(), c.getZ())) {
						// (Never seen in, a real block of the build, out of sight now.)
						if (w0.isAir() || temps.containsKey(c) || support.contains(c) || seenDone.contains(c.asLong())) continue;
						double d = Vec3.atCenterOf(c).distanceToSqr(p.position());
						if (d < farD) {
							farD = d;
							farC = c;
						}
						continue;
					}
					Placement.Job j = jobAt(c);
					if (j != Placement.Job.PLACE && j != Placement.Job.BREAK && j != Placement.Job.ADJUST) continue;
					n++;
					low = Math.min(low, c.getY());
				}
				if (n > 0 || farC != null) {
					finalSweeps++;
					sweepFar = n > 0 ? null : farC;
					sweepUntil = now + (n > 0 ? 100 : 1200);
					if (n > 0) {
						scanFromY = Math.min(scanFromY, low);
						extraLayers = Math.max(extraLayers, 4);
						HumanAutopilot.LOGGER.info("[schem] before calling it done: {} spot(s) still to do (lowest at y {}): back to them", n, low);
					} else {
						HumanAutopilot.LOGGER.info("[schem] before calling it done: the far end isn't loaded and was never seen in ({} away): over there to look",
								Math.round(Math.sqrt(farD)));
					}
					return note = "looking the build over";
				}
			}
			// Done, and the next build's spot is too far off to be loaded: over there to see what's there (SchematicMode).
			if (plan != null && guarding && visit != null) {
				String v = visitStep(p, in, aim, b, now);
				if (v != null) return v;
			}
			if (plan != null) {
				planComplete();
				note = "guarding " + planName;
				return null;
			}
			note = "box full";
			return null;
		}
		if (plan != null && guarding && !repairing) {
			// Something got blown up (or broken): back to work on it.
			repairing = true;
			HumanAutopilot.LOGGER.info("[schem] {} got damaged: {} holes, fixing", planName, holeCount);
		}
		if (now < reactUntil) {
			note = "whoa, that blew a hole";
			return note;
		}
		if (wasFlying && !p.getAbilities().flying) {
			landings++;
			// Down three times in 3 s: bouncing on the floor (hovering a hair over it for a low spot, nowhere to walk):
			// a bit higher for a few seconds. (The cottage's wall top: down, up, down, up, 4 times in one second.)
			landedAt[landedN++ % landedAt.length] = now;
			long oldest = landedAt[landedN % landedAt.length];
			if (landedN >= landedAt.length && now - oldest < 60) {
				if (now >= bounceUntil) HumanAutopilot.LOGGER.info("[box] bouncing on the floor: hovering a bit higher for a while");
				bounceUntil = now + 80;
			}
			// Set down on purpose: on our feet a moment at least, like a player who's landed (touch and go, half a second on
			// the ground, was most of the cottage's "bunny hopping"). A way over a gap still takes off at once (steer).
			if ("set-down".equals(lastVert) && now - lastVertAt <= 6) walkUntil = Math.max(walkUntil, now + 30);
			// (Down when he didn't mean to be - touched the floor of a low room hovering just over it: a moment before the
			// double tap to fly again, like a player, not up again the very next tick. The awkward-blocks cottage: 8 of its
			// 33 landings were 0.04-0.34 s on the ground, bouncing.)
			else if (!"door landing".equals(lastVert)) walkUntil = Math.max(walkUntil, now + Human.rangeInt(6, 12));
			HumanAutopilot.LOGGER.info("[box] stopped flying at {} (on ground: {}, doing: {}; last down: {}, {} ticks ago, goal {}; walk {} set-down-ok {})",
					p.blockPosition().toShortString(), p.onGround(), note, lastVert, now - lastVertAt, lastSteerGoal == null ? "-" : fmt(lastSteerGoal),
					walkUntil - now, noSetDownUntil - now);
		}
		wasFlying = p.getAbilities().flying;
		if (!p.getAbilities().flying) {
			if (walkDoor != null && walkThroughDoor(p, in, aim, b, null, now)) return note;
			if (!p.getAbilities().mayfly) {
				note = "can't fly: switch to creative";
				return note;
			}
			// Set down on purpose to walk into a low gap (or still under its roof): on foot, like a player. (Hovering a
			// hair over the floor to fit in bounced down-brake-up in front of it for minutes; taking off in there
			// only bumps the roof.)
			// On foot under a low roof, the next block near and no higher: walk to it. (Taking off for every short move in
			// the cottage's low rooms, and touching down again: 56 landings in 15 minutes, the user's "bunny hopping".)
			if (plan != null && now >= walkUntil && now >= takeOffNowUntil && footWork(p)) walkUntil = now + 10;
			// The take-off not catching 3 times running (a low roof): walk out to where there's room over the head first.
			if (plan != null && takeoffFails >= 3 && now >= walkOutUntil) {
				takeoffFails = 0;
				walkOutTo = roomToTakeOff(p);
				walkOutUntil = now + 60;
				HumanAutopilot.LOGGER.info("[box] can't take off here (no room over the head at {}): walking to {} first", fmt(p.position()),
						walkOutTo == null ? "- (nowhere near)" : fmt(walkOutTo));
			}
			if (walkOutTo != null && now < walkOutUntil) {
				if (Math.hypot(walkOutTo.x - p.getX(), walkOutTo.z - p.getZ()) < 0.35) {
					walkOutTo = null;
				} else {
					work(aim, walkOutTo.add(0, 1.5, 0));
					steer(p, in, walkOutTo, false, 0.5);
					note = "walking out from under the roof to take off";
					return note;
				}
			}
			if (plan == null || now >= walkUntil) {
				takeOff(p, in);
				note = "taking off";
				return note;
			}
		} else {
			takeoffTick = -1;
			takeoffFails = 0;
			walkOutTo = null;
		}
		// Night vision while building (it's dark once he's inside), and a look at the whole build every few minutes.
		if (plan != null) {
			String nv = nightVision(p, in, aim, now);
			if (nv != null) return note = nv;
			String look = lookAround(p, in, aim, b, now);
			if (look != null) {
				onSince = now; // (not a stall)
				return note = look;
			}
		}

		// (No more knocking out a block over our head: on the real box that's usually a TNT overhang, and the user
		// saw it digging up through good blocks. Under an overhang we just fly out from under it.)
		if (plan == null && lid == null && target == null && !bad.isEmpty() && !pickTarget(p, b, now) && now - badClearedAt > 40) {
			// Nothing else to do: look at the skipped holes again rather than idle until their timers run out
			// (the user saw it sit 30-60 s next to a hole it could reach by then).
			bad.clear();
			badClearedAt = now;
		}
		// Breaking into a sealed hollow: carry on down the same spot until it's open (refilling the first broken
		// block straight away made it go back and forth).
		if (openingCol != null && lid == null) {
			BlockPos next = null;
			if (now < openingUntil) {
				for (BlockPos c = openingCol.below(); b.inside(c.getX(), c.getY(), c.getZ()); c = c.below()) {
					if (solid(c)) next = c; // more roof to get through
					break; // else: through, the hollow is open
				}
			}
			if (next != null && next.getY() >= openingCol.getY() - 3) {
				lid = next;
				lidSince = now;
				stand = null;
			} else {
				openingCol = null;
			}
		}
		if (lid == null && target == null && !pickTarget(p, b, now)) {
			// Inside a hollow with nothing left we may fill from in here (the way out has to stay open): fly back out
			// through it, then finish the hole from outside.
			Vec3 out = wayOut(p, b);
			if (out != null && throughDoor(p, in, aim, b, BlockPos.containing(out.x, out.y + 0.05, out.z), now)) return note;
			if (out != null && walkThroughDoor(p, in, aim, b, BlockPos.containing(out.x, out.y + 0.05, out.z), now)) return note;
			if (out != null) {
				noWayOutSince = -1;
				work(aim, out.add(0, p.getEyeHeight(), 0));
				steer(p, in, out, false, 0.6);
				return note = "climbing out of the hollow";
			}
			BlockPos me = bodyCell(p);
			boolean shutIn = b.inside(me.getX(), me.getY(), me.getZ()) && !skyOpen(b, me.getX(), me.getY(), me.getZ());
			if (shutIn && now - noWayOutLogAt > 100) {
				noWayOutLogAt = now;
				StringBuilder around = new StringBuilder();
				for (Direction d : Direction.values()) {
					BlockPos n = me.relative(d);
					around.append(d).append(flyable(n.getX(), n.getY(), n.getZ()) ? " open" : " shut").append("; ");
				}
				HumanAutopilot.LOGGER.info("[box] under a roof at {} ({}) with nothing to fill and no way out found: {}", me.toShortString(),
						fmt(p.position()), around);
			}
			// Built in (the shelves' fences, gates and trapdoors all round us) for 3 s: knock one block out of the way,
			// like the user had to; it goes back in later, from outside.
			if (shutIn && plan != null) {
				if (noWayOutSince < 0) noWayOutSince = now;
				if (now - noWayOutSince > 60) {
					String br = breakOut(p, in, aim, b, now);
					if (br != null) return note = br;
				}
			} else {
				noWayOutSince = -1;
			}
		}
		// Still looking the far spots over (each a search for a spot to work from, a few a tick): a moment's thought, not
		// "nothing to do" - no flying off to the rest of the build meanwhile, and no "stuck" clock.
		if (plan != null && lid == null && target == null && farPendingAt == now) {
			brake(p, in);
			return note = "looking the build over for the next block";
		}
		if (plan != null && lid == null && target == null && farGoal != null && !pickTarget(p, b, now)) {
			// Nothing to do round here: the rest of the build is further on. (Far off: over the build's top, in the open -
			// not through its halls. It only has to come in sight, and the castle-top tests spent their first 1.5 minutes
			// finding a way through the pasted castle at the bottom to the corners still unseen, "STALL 60s" all along.)
			// (Just over whatever is in the way along the line there - what's built so far, the ground - not the schematic's
			// full height in the air.)
			double gy = farGoal.getY() + 3;
			double ex = farGoal.getX() + 0.5, ez = farGoal.getZ() + 0.5, len = Math.hypot(ex - p.getX(), ez - p.getZ());
			if (len > 24) {
				int top = Integer.MIN_VALUE;
				for (double d = 0; d <= len; d += 3) {
					int x = Mth.floor(p.getX() + (ex - p.getX()) * d / len), z = Mth.floor(p.getZ() + (ez - p.getZ()) * d / len);
					if (Terrain.loaded(x, z)) top = Math.max(top, Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
				}
				if (top != Integer.MIN_VALUE) gy = Math.max(gy, top + 2);
			}
			Vec3 g = new Vec3(farGoal.getX() + 0.5, Math.max(p.getY(), gy), farGoal.getZ() + 0.5);
			work(aim, g.add(0, p.getEyeHeight(), 0));
			steer(p, in, route(p, b, g), false, 0.9);
			if (Math.hypot(g.x - p.getX(), g.z - p.getZ()) > 6) in.hold(InputDriver.opts().keySprint);
			air(p, b);
			// Not getting anywhere for 10 s (walled in by the last build - /kc finish filled the pagoda in round her - with
			// the next build's box elsewhere, so no breaking out): over there by /tp. (3 minutes in place, "stuck".)
			if (overFrom == null || p.position().distanceTo(overFrom) > 2) {
				overFrom = p.position();
				overSince = now;
			} else if (now - overSince > 200 && canTp(p)) {
				overSince = now;
				HumanAutopilot.LOGGER.info("[schem] not getting over to the rest of the build from {} for 10 s: /tp to {}", fmt(p.position()), fmt(g));
				p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f", g.x, g.y, g.z)); lastTpTick = HumanAutopilot.ticks();
			}
			return note = "flying over to the rest of the build";
		}
		// Flying up over the build (where /tp isn't allowed): there, or 15 s.
		if (climbGoal != null) {
			if (now < climbUntil && p.getY() < climbGoal.y - 0.5) {
				work(aim, climbGoal.add(0, p.getEyeHeight() + 3, 0));
				steer(p, in, route(p, b, climbGoal), false, 0.6);
				return note = "flying up over the build";
			}
			climbGoal = null;
		}
		if (lid != null || (target == null && !pickTarget(p, b, now))) {
			// Nothing left we can fill: holes have been covered over (a misclick, a TNT cave). In creative a player
			// just knocks the covering block out and fills it properly.
			String fixing = fixLid(p, in, aim, b, now);
			if (fixing != null) return note = fixing;
			note = holeCount + " hole(s) I can't get at";
			// Nothing at all we can do for 30 s (shut in under a finished ceiling: the rods test hovered there for good,
			// "box: 5 hole(s) I can't get at"): leave them as they are, finish, and guard. (Anything that had a skip timer
			// running got its next go well within that.)
			if (plan != null) {
				// Out into the open first, if we're under a roof: what we can't get at from in here may be easy from out
				// there. (Shut in by a bug of ours under the cottage's roof, all 57 holes left were given up at once.)
				BlockPos meC = bodyCell(p);
				boolean underRoof = b.inside(meC.getX(), meC.getY(), meC.getZ()) && !skyOpen(b, meC.getX(), meC.getY(), meC.getZ());
				if (now < outFirstUntil && underRoof) {
					Vec3 out = wayOut(p, b);
					if (out != null) {
						work(aim, out.add(0, p.getEyeHeight(), 0));
						steer(p, in, out, false, 0.6);
						return note = "getting out into the open";
					}
				}
				// STUCK: nothing at all to do. Like a player who's stuck: one thing after another, each given a few seconds,
				// before anything's left as it is - the user: "help her know what to do in these situation shes stuck". (Most
				// of these were there, each only for its own case: the floating pagoda's crystals never got the bridges out to
				// them, as something else was "workable", and all 3,600 were left after 30 s.) Each step is logged, and which
				// one got her out (see where cantGetSince goes back to -1).
				if (cantGetSince < 0) {
					cantGetSince = now;
					stuckStep = 0;
					stuckStepAt = now;
					stuckHoles = holeCount;
				}
				long idle = now - cantGetSince;
				// One step at a time, 5 s each (a look at the build takes longer: the steps after it all went at once).
				if (idle > 100 && stuckStep < 5 && (stuckStep == 0 || now - stuckStepAt > 100)) {
					stuckStep++;
					stuckStepAt = now;
					switch (stuckStep) {
						case 1 -> {
							// Another look: the ones skipped for now get a go straight away, out from under a roof first, or a
							// look at the build from out there (a player taking stock).
							int n = 0;
							for (BlockPos h : holes) if (bad.remove(h) != null) n++;
							// (And round the spots he couldn't fly to lately: those areas get their go again too.)
							n += flyFails.size();
							flyFails.clear();
							if (underRoof && !outFirstTried) {
								outFirstTried = true;
								outFirstUntil = now + 200;
							} else if (lookStage == 0 && now - lookSince > 1200) {
								lookNextAt = Math.min(lookNextAt, now);
							}
							stuckLog(idle, "another look: " + n + " skipped ones again" + (underRoof ? ", out from under the roof" : ""));
						}
						case 2 -> {
							// Clear the way: a block of the build that shuts one in comes out to get at it (it goes back after).
							int tries = 0, opened0 = opened.size();
							for (BlockPos h : new ArrayList<>(holes)) {
								if (tries >= 4) break;
								// (Buried ones too - a full block on every side, never tried so never "nowhere": the build's own
								// blocks round one can come out for it the same way.)
								if (!hole(h) || cant.contains(h) || (!failWhy.getOrDefault(h, "").startsWith("nowhere") && openFace(h))) continue;
								tries++;
								if (openUp(p, h, now)) break;
							}
							boolean door = opened.size() == opened0 && roomDoor(p, b, now);
							stuckLog(idle, "clear the way: " + (door ? "a doorway into the room it's in" : opened.size() > opened0 ? "taking a block out to get at one"
									: "nothing to take out (" + tries + " looked at)"));
						}
						case 3 -> {
							// Temporary blocks: bridges out to what hangs in the air, one to click on for one that won't go in the
							// right way round, or a dirt pillar up to the build.
							String did = null;
							if (now >= noBridgeUntil && bridgeFloating(p, now)) did = "a bridge out";
							if (did == null) {
								int sc = 0;
								for (BlockPos h : new ArrayList<>(holes)) {
									if (sc >= 6) break;
									if (!hole(h) || cant.contains(h)) continue;
									sc++;
									if (tryScaffold(p, h)) {
										did = "a block to click on";
										break;
									}
								}
							}
							if (did == null && forcedPillars < 3 && forcePillar(p)) {
								forcedPillars++;
								did = "a dirt pillar";
							}
							stuckLog(idle, "temporary blocks: " + (did != null ? did : "none would help"));
						}
						case 4 -> {
							// Not waiting any more: the ones holding back for another (the one under them, one beside they'd shut
							// in, a hill over them to clear first) just go, like a player would. (5 grass blocks inside the house
							// on the hill, each waiting on its column for good, were left as they were.)
							int n = 0;
							for (BlockPos h : holes) {
								if (!hole(h) || cant.contains(h) || !workable(h) || !columnWaits(h)) continue;
								noWait.add(h.immutable());
								bad.remove(h);
								n++;
							}
							stuckLog(idle, "not waiting any more: " + n + " of them go now");
						}
						case 5 -> {
							// Any way round: the ones that won't go in the right way round go in as they can (a barrel, a stair).
							int n = 0;
							for (BlockPos h : holes) {
								BlockState w = plan.get(h);
								if (w == null || !hole(h) || cant.contains(h) || relaxed.contains(h) || nowhere.getOrDefault(h, 0) < 2) continue; // (twice with no spot)
								if (!Placement.oriented(w) || !w.canSurvive(Terrain.level(), h)) continue;
								relaxed.add(h);
								bad.remove(h);
								n++;
							}
							stuckLog(idle, "any way round: " + n + " of them");
						}
						default -> {
						}
					}
				}
				if (stuckStep > 0) note = "stuck " + idle / 20 + " s: " + STUCK_STEPS[stuckStep];
				// Last (30 s, and 5 s after the step before): nothing else for it - left as they are, and on with the rest.
				if (idle > 600 && stuckStep >= 5 && now - stuckStepAt > 100) {
					stuckLog(idle, "nothing else for it: leaving them");
					stuckSteps[6]++;
					cantGetSince = -1;
					outFirstTried = false;
					stuckStep = 0;
					List<String> left = new ArrayList<>();
					// (Not the ones only waiting on a spot beside or under them, this time: once those are left as they are,
					// these can go in. A floor block by a gold block shut in by bedrock waited on the two floor spots shut in
					// under it, and was left with them, open to the sky.) Only waiting ones left: those, then.
					// (Which wait, worked out before any are left: left first, the ones round them didn't wait any more.)
					// (Waiting on a spot beside it it would shut in: last of all - that one's likely among those left now.)
					List<BlockPos> give = new ArrayList<>(), waiters = new ArrayList<>(), beside = new ArrayList<>();
					for (BlockPos h : holes) {
						if (!hole(h) || cant.contains(h) || plan.get(h) == null) continue;
						if (!workable(h) || !columnWaits(h)) give.add(h);
						else (sealsHoleBeside(h) ? beside : waiters).add(h);
					}
					if (give.isEmpty()) give = waiters;
					if (give.isEmpty()) give = beside;
					if (give.isEmpty()) {
						// Nothing on the list, and something still holds the build open: a temporary block still out, or a
						// spot of an open layer not looked at since the game was opened (seenDone starts empty, and a layer is
						// only finished when every spot of it was seen done). Out of sight and remembered built: done; the
						// rest left as they are too. (The copper guard's very end: "1 spot left", nothing on the list, round
						// the steps again and again for good.)
						for (BlockPos tmp : temps.keySet()) if (!cant.contains(tmp)) give.add(tmp);
						int looked = 0;
						for (var e : planLayers.tailMap(scanFromY, true).entrySet()) {
							for (BlockPos c : e.getValue()) {
								long k = c.asLong();
								if (seenDone.contains(k)) continue;
								if (++looked > 40000) break;
								boolean known = Terrain.loaded(c.getX(), c.getZ()) && !Terrain.state(c).is(net.minecraft.world.level.block.Blocks.VOID_AIR);
								Placement.Job jc = known ? jobAt(c) : (seenBuilt.contains(k) ? Placement.Job.DONE : Placement.Job.PLACE);
								seenDone.add(k);
								if (jc != Placement.Job.DONE && jc != Placement.Job.BLOCKED && !cant.contains(c)) give.add(c);
							}
						}
						HumanAutopilot.LOGGER.info("[schem] nothing on the list but the build's still open: {} spots never seen done looked at, {} of them left as they are",
								looked, give.size());
					}
					// (Not a build far from done: under the giant tree's roots at 31%, 53,176 spots never seen were left as they
					// are at once - "done", the server pasted the rest and she went on to the next. Up over it by /tp instead, to
					// see them.)
					// (Buried ones - a full block on every side, seen and nothing to be done from anywhere - don't count: those are
					// left like the rest. Counted, a few hundred of them under a hill had her /tp up every 30 s for good.)
					int unseenGive = 0;
					for (BlockPos h : give) {
						if (unseenGive > 200) break;
						if (!Terrain.loaded(h.getX(), h.getZ()) || openFace(h)) unseenGive++;
					}
					// (Only where /tp is allowed: without it - a world with no cheats - it never happened, and he went round the
					// steps for good, 3+ minutes in the carpet test.)
					if (unseenGive > 200 && !(planCounted && planTotal > 0 && planDone >= planTotal * 0.97) && !canTp(p)) {
						// No /tp here: up over the build by flying, to see them from up there (not hundreds left as they are).
						climbGoal = new Vec3(p.getX(), Math.max(b.maxY() + 3, Mth.floor(p.getY()) + 3), p.getZ());
						climbUntil = now + 300;
						HumanAutopilot.LOGGER.info("[schem] not leaving {} spots at {} of {}: flying up over the build to y {} instead (no /tp here)",
								give.size(), planDone, planTotal, Math.round(climbGoal.y));
						give.clear();
					}
					if (unseenGive > 200 && !(planCounted && planTotal > 0 && planDone >= planTotal * 0.97) && canTp(p)) {
						int ty = Math.max(b.maxY() + 3, Mth.floor(p.getY()) + 3);
						HumanAutopilot.LOGGER.info("[schem] not leaving {} spots at {} of {}: /tp up over the build to y {} instead", give.size(), planDone, planTotal, ty);
						p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", p.getX(), ty, p.getZ())); lastTpTick = HumanAutopilot.ticks();
						stuckTpAt = now;
						give.clear();
					}
					for (BlockPos h : give) {
						cant.add(h);
						failWhy.put(h, "nothing could get to it for 30 s");
						if (left.size() < 8) left.add(h.toShortString() + " " + (plan.get(h) == null ? "?" : plan.get(h).getBlock().getName().getString()));
					}
					if (!left.isEmpty()) HumanAutopilot.LOGGER.info("[schem] leaving what I can't get to as it is (nothing else to do for 30 s): {}", left);
				}
			}
			return null;
		}
		// (Out of being stuck: which step did it - see the steps where nothing at all was to do.)
		if (stuckStep > 0 && cantGetSince >= 0) {
			HumanAutopilot.LOGGER.info("[stuck] out of it after {} s, after step {} ({}): on with {}", (now - cantGetSince) / 20, stuckStep,
					STUCK_STEPS[stuckStep], target == null ? "-" : target.toShortString());
			stuckSteps[stuckStep]++;
		}
		stuckStep = 0;
		cantGetSince = -1;
		outFirstTried = false;
		if (walkDoor != null && walkThroughDoor(p, in, aim, b, null, now)) return note;
		BlockPos t = target;
		if (now < useUntil) in.hold(InputDriver.opts().keyUse); // finishing a tap
		Placement.Job job = plan != null ? jobAt(t) : Placement.Job.PLACE;
		if (plan != null && job != lastJob) {
			// The spot needs something else now (knocked out -> put in, put in -> a finishing touch): start it fresh.
			if (lastJob == Placement.Job.PLACE && job == Placement.Job.BREAK && Terrain.state(t).getBlock() == plan.get(t).getBlock()) {
				int n = redos.merge(t, 1, Integer::sum);
				wrongWay++;
				HumanAutopilot.LOGGER.info("[schem] {} went in wrong ({}, wanted {}): knocking it out to do it again (try {})",
						t.toShortString(), Terrain.state(t), plan.get(t), n);
			}
			// (A new spot to do it from, too: the one for knocking the grass out, 3 blocks off, couldn't see into the pit
			// to put the concrete in: he hovered there staring, the camera wiggling, then skipped it: 40 times on the
			// castle's ground edge.)
			if (lastJob != null) {
				stand = null;
				atStandSince = -1;
				offFace = 0;
			}
			lastJob = job;
			clickedAt = -1;
			tries = 0;
			placingSince = -1;
			locked = null;
		}
		Item item = plan == null ? blockFor(p, b, t) : itemFor(t);
		// Stick to one face per hole while it stays in sight (re-choosing every tick makes the hand wander).
		Face face;
		// (A face whose heading is on the line (a stair, see planPoint) isn't one to place from while there's a spot with
		// room for it: we fly to the spot. Once there, or if there's none, it'll do: yawLoose.)
		boolean looseOk = yawLoose.contains(t), lineOnly = false;
		// (And for a stair, the face it was locked on still puts it in the right way round from here: the lock is taken from
		// the spot we're flying to, and we start placing as soon as it's in reach on the way - from somewhere else, with
		// another heading. The awkward cottage: "would go in facing=east" for a south stair, the look 40 deg off.)
		if (locked != null && locked.cell.equals(t) && (!locked.loose() || looseOk) && headingRightFrom(p, p.getEyePosition(), locked, t)
				&& (visible(p, p.getEyePosition(), locked) || ++lockMisses < 4)) {
			face = locked; // a tick or two out of sight while drifting: keep the same face (no flicking between faces)
			if (visible(p, p.getEyePosition(), locked)) lockMisses = 0;
		} else {
			// (From where we'll be by the time the eyes are on it, too: still coasting from the flight there, a stair
			// picked with room for its heading went in wrong 5 ticks later, "off the face for 10 ticks" on the cottage.)
			Vec3 v0 = p.getDeltaMovement();
			yawLead = new Vec3(v0.x, 0, v0.z).scale(5);
			try {
				face = faceFrom(p, p.getEyePosition(), p.getViewVector(1f), t);
			} finally {
				yawLead = Vec3.ZERO;
			}
			if (face != null && face.loose() && !looseOk) {
				face = null;
				lineOnly = true;
			}
			if (face != null) {
				locked = face;
				lockMisses = 0;
			}
		}
		boolean shaft = inShaft(p);
		boolean bodyInTheWay = shaft ? p.getBoundingBox().intersects(new AABB(t))
				: p.getBoundingBox().expandTowards(0, -1.2, 0).intersects(new AABB(t));

		if (forceStandFor != null && !forceStandFor.equals(t)) forceStandFor = null;
		if (face != null && !bodyInTheWay && forceStandFor == null) {
			// In reach: keep drifting (the recorded player places on the move 86% of the time), look and click.
			if (shaft && plan == null) {
				// Pillaring: hover just over it, and rise as it fills.
				steer(p, in, new Vec3(t.getX() + 0.5, t.getY() + 1 + HOVER, t.getZ() + 0.5), false, 0.4);
			} else {
				// Slow right down until the crosshair is on it: drifting past at ~5 blocks/s from 2-3 blocks away
				// slid the target across the view faster than the camera follows (10-15 degrees behind = "stuck").
				drift(p, in, t, offFace > 0 ? 0.06 : 0.18);
			}
			Vec3 pt = face.point;
			work(aim, pt);
			if (item != null) {
				int slot = Inv.hotbarSlot(p, s -> s.is(item));
				if (slot < 0) {
					if (plan == null) return note = "no " + name(item) + " in my hotbar";
					// Not in the hotbar: get it from the creative menu now we're about to use it (getting it first, before
					// knowing the spot can be reached, had it fetching the same hopper seven times).
					if (!p.getAbilities().instabuild) {
						giveUp(t, now, "no " + name(item) + " (creative only for now)");
						return note = "no " + name(item) + " in my hotbar";
					}
					if (CreativePicker.get().failed(item)) {
						giveUp(t, now, "can't get " + name(item));
						return note = "looking for the next block";
					}
					// One already in the build, in sight: a middle click on it (no menu).
					if (!CreativePicker.get().active()) {
						String picking = pickFromWorld(p, in, aim, item, now);
						if (picking != null) return note = picking;
					}
					brake(p, in);
					if (!CreativePicker.get().active()) CreativePicker.get().needs(needsNear(p));
					return note = CreativePicker.get().fetch(p, in, item);
				}
				if (pickItem == item) {
					HumanAutopilot.LOGGER.info("[schem] got {} off the build in {} ticks", name(item), now - pickSince);
					pickItem = null;
					pickFrom = null;
					picksDone++;
				}
				CreativePicker.get().used(slot);
				if (!in.selectHotbar(slot)) return note = "taking out " + name(item);
			}
			if (clickedAt >= 0 && now - clickedAt > 8) {
				clickedAt = -1;
				// Only a click that changed nothing counts against it: snow 6 layers deep is six clicks, each adding one
				// (the luxury villa's snowy edge: "clicks didn't place it" after the third, a whole row of it).
				if (Terrain.state(t) != clickedState) tries = 0;
				else if (++tries >= 3) {
					giveUp(t, now, "clicks didn't place it");
					return note = "that one won't go in";
				}
			}
			// Clicked again and again and it never stays done (each click "works", then water washes the snow layer away
			// and it's to do again: tries above starts over every time the job flips): leave it for a while.
			if (targetClicks >= 12) {
				giveUp(t, now, "it keeps coming undone");
				return note = "that one keeps coming undone";
			}
			if (placingSince < 0) placingSince = now;
			// The companion (or another player) right on it, or in the way of the click, for a moment: another hole for
			// now, not a wait for her to move (the user: "if she comes into blocking my path I go to a different spot").
			if (clickedAt < 0 && (nearest(otherPlayers(p), Vec3.atCenterOf(t)) < 2.0 || entityInTheWay(p))) {
				// (Nothing else left: wait for her to move off - no click through her - rather than pass it over.)
				if (++blockedTicks >= 8 && !crowdFallback) {
					blockedTicks = 0;
					HumanAutopilot.LOGGER.info("[box] someone's in the way of {}: another spot for now", t.toShortString());
					bad.put(t.immutable(), now + 60);
					target = null;
					stand = null;
					clickedAt = -1;
					return note = "someone's in the way: another spot";
				}
			} else blockedTicks = 0;
			if (clickedAt < 0 && now - placingSince > 50) {
				// Can't line the crosshair up on it (something keeps getting in the way): leave it for now.
				HitResult hr = Minecraft.getInstance().hitResult;
				HumanAutopilot.LOGGER.info("[box] can't line up on {}: eye {} face {} {} at {}, crosshair on {}, aim error {}",
						t.toShortString(), fmt(p.getEyePosition()), face.against.toShortString(), face.side, fmt(face.point),
						hr instanceof BlockHitResult bh ? bh.getBlockPos().toShortString() + " " + bh.getDirection() : hr == null ? "nothing" : hr.getType(),
						String.format("%.1f", aim.error()));
				giveUp(t, now, "couldn't line up the crosshair");
				return note = "looking for the next hole";
			}
			// "Oh, it's missing. Fill it. Move on." Click as soon as the crosshair is on any face that puts a block
			// into this hole. Checked with the very ray the game will use for the click (this tick's position, not
			// the last drawn frame's, which is a little behind while we drift): so the block lands where we meant.
			// A finishing touch is a click on the block itself: not with shift down (that skips the block).
			if (job == Placement.Job.ADJUST) in.drop(InputDriver.opts().keyShift);
			// Putting it against a chest, a crafting table...: sneak held a beat before the click (so the server knows
			// too), and space with it while flying (sneak alone sinks us, onto the ground even).
			if (job == Placement.Job.PLACE && locked != null && locked.cell.equals(t) && Placement.usable(Terrain.state(locked.against))) {
				// (Only with space to hold against it: a fresh space press within 8 ticks of the last would be a double tap,
				// so jump() holds it back - and sneak alone sank us onto the floor, placing against the cottage's
				// trapdoors: 8 landings in 6 minutes. Then sneak waits those few ticks too.)
				if (!p.getAbilities().flying || canJump()) {
					in.hold(InputDriver.opts().keyShift);
					if (p.getAbilities().flying) jump(in);
					if (sneakSince < 0) sneakSince = now;
					lastVert = "sneak-place";
					lastVertAt = now;
				}
			} else {
				sneakSince = -1;
			}
			HitResult hr = clickRay(p);
			boolean onIt = plan != null && job == Placement.Job.PLACE && pad(plan.get(t)) ? padAim(p, t, hr) && !entityInTheWay(p)
					: hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
					&& (plan == null ? bhr.getBlockPos().relative(bhr.getDirection()).equals(t) && solid(bhr.getBlockPos()) && sneakOk(p, bhr, now)
							: job == Placement.Job.PLACE ? clickPlaces(p, bhr, item, t) && sneakOk(p, bhr, now)
							: bhr.getBlockPos().equals(t) && (job == Placement.Job.BREAK || !p.isShiftKeyDown()))
					// Someone in the way (the companion floating past, a mob): the click would hit them (it opened
					// Kohaku's menu), so wait for a clear shot or move.
					&& !entityInTheWay(p);
			if (clickedAt >= 0) tPending++;
			else if (!onIt) tAim++;
			else tWait++;
			// On the very face we chose, the eyes there, and still the wrong way round: the heading (a stair faces the way we
			// look) - it's where we hang that's wrong, not the aim. To a spot with room for it at once, rather than 10 ticks
			// of staring and a second face from the same spot (383 of the user's castle's stairs).
			if (!onIt && clickedAt < 0 && !aim.busy() && plan != null && job == Placement.Job.PLACE && locked != null && locked.cell.equals(t)
					&& hr instanceof BlockHitResult wh && wh.getType() == HitResult.Type.BLOCK && wh.getBlockPos().equals(locked.against)
					&& wh.getDirection() == locked.side && headingMatters(plan.get(t)) && !yawLoose.contains(t) && item != null
					// (Wrong with the angles we have now: with the ones the server last had, a tick old, it settles by itself -
					// 20 of the cottage's 28 moves were that, the barrel's look up a hair from the line, the stair right.)
					&& !Placement.sameWay(Placement.predict(p, wh, item, t, p.getYRot(), p.getXRot()), Placement.placeShape(plan.get(t)), relaxed.contains(t))
					&& headingMoved.add(t.immutable())) {
				if (headingMoved.size() > 2000) headingMoved.clear();
				HumanAutopilot.LOGGER.info("[box] {} would go in {} from here (eye {}, look {}/{}): to a better spot", t.toShortString(),
						Placement.predict(p, wh, item, t, p.getYRot(), p.getXRot()), fmt(p.getEyePosition()), Math.round(p.getYRot()), Math.round(p.getXRot()));
				forceStandFor = t;
				stand = null;
				offFace = 0;
				return note = "looking for a better spot";
			}
			if (!onIt && clickedAt < 0) {
				// (Not while the eyes are still on their way: a hand's hop takes ~0.3 s with the pause before it.)
				if (!aim.busy()) offFace++;
				if (offFace == 5 || offFace == 10) {
					// Near it but the click wouldn't land: take the face we see best from here, aim at its middle.
					faceOffA = 0;
					faceOffB = 0;
					Face fresh = faceFrom(p, p.getEyePosition(), p.getViewVector(1f), t);
					if (fresh != null && (!fresh.loose() || yawLoose.contains(t))) locked = fresh;
					if (offFace >= 10) {
						// Still can't land the click from here (looking down a narrow hole at an angle, say): move to
						// a better spot, like straight above it, instead of staring.
						forceStandFor = t;
						stand = null;
					}
					if (offFace == 10) {
						HumanAutopilot.LOGGER.info("[box] off the face of {} for 10 ticks: aiming at {} {} ({}), the click would hit {}, aim error {}; eye {}, look {}/{}, loose {}{}",
								t.toShortString(), locked.against.toShortString(), locked.side, fmt(locked.point),
								hr instanceof BlockHitResult bh2 ? bh2.getBlockPos().toShortString() + " " + bh2.getDirection() + " at " + fmt(bh2.getLocation())
										+ " (" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Terrain.state(bh2.getBlockPos()).getBlock()).getPath() + ")"
										+ (plan != null && job == Placement.Job.PLACE ? " would put " + Placement.predict(p, bh2, item, t, p.getYRot(), p.getXRot()) : "")
										: hr == null ? "nothing" : hr.getType(), String.format("%.1f", aim.error()), fmt(p.getEyePosition()),
								Math.round(p.getYRot()), Math.round(p.getXRot()), locked.loose(), yawLoose.contains(t) ? " (on the line allowed)" : "");
					}
				}
			} else {
				offFace = 0;
			}
			if (clickedAt < 0 && now >= nextClickAt) {
				if (onIt && job == Placement.Job.BREAK) {
					noteTakeOut(t, p, null);
					// Creative: one left-click knocks it out (the game wants a moment before the next).
					in.click(InputDriver.opts().keyAttack);
					lastClickTick = now;
					clickedAt = now;
					clickedState = Terrain.state(t);
					nextClickAt = now + 6;
					targetClicks++;
				} else if (onIt) {
					// A quick tap (2-3 ticks), not a held button.
					in.click(InputDriver.opts().keyUse);
					in.hold(InputDriver.opts().keyUse);
					useUntil = now + Human.rangeInt(1, 2);
					lastClickTick = now;
					clicks++;
					targetClicks++;
					clickPitchSum += p.getXRot();
					clickDistSum += Math.hypot(p.getX() - (t.getX() + 0.5), p.getZ() - (t.getZ() + 0.5));
					if (p.getXRot() > MAX_PITCH) steepClicks++;
					clickedAt = now;
					clickedState = Terrain.state(t);
				}
			}
			String doing = "placing " + (item == null ? "it" : name(item));
			if (job == Placement.Job.BREAK) doing = "knocking out " + Terrain.state(t).getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
			else if (job == Placement.Job.ADJUST) {
				var a = Placement.adjust(Terrain.state(t), plan.get(t));
				doing = a == null ? "finishing it" : a.what();
			}
			return note = doing + (plan != null ? " (" + planDone + "/" + planTotal + ")" : " (" + holeCount + " left)");
		}

		// Not from here: fly to a spot we can do it from.
		if (plan != null && !buriedCheck.isEmpty() && now % 3 == 0) checkBuried(p, b);
		placingSince = -1;
		tFly++;
		if (plan == null && shaft && t.getX() == Mth.floor(p.getX()) && t.getZ() == Mth.floor(p.getZ()) && t.getY() < Mth.floor(p.getY() - 0.2)) { // only a hole BELOW our feet (not the space we are in: that flip-flopped with the stand)
			// Down our own shaft to its bottom: centred (off-centre it couldn't see the bottom well enough to click),
			// a bit lower than usual, eyes on it. And not forever: 3 s and it's another hole's turn.
			if (shaftSince < 0) shaftSince = now;
			if (now - shaftSince > 60) {
				shaftSince = -1;
				giveUp(t, now, "couldn't fill it from down the hole");
				return note = "looking for the next hole";
			}
			work(aim, Vec3.atCenterOf(t.below()).add(0, 0.5, 0));
			precise = true;
			steer(p, in, new Vec3(t.getX() + 0.5, t.getY() + 1 + 1.2, t.getZ() + 0.5), false, 0.6);
			precise = false;
			return note = "down the hole to fill it from the bottom";
		}
		if (plan != null && stand == null && (Math.abs(t.getX() - regX) > REG_H - 2 || Math.abs(t.getZ() - regZ) > REG_H - 2
				|| Math.abs(t.getY() - regY) > REG_V - 2)) {
			// Further than the open-air map reaches (down a TNT pit, the far end of a big build, TNT damage 40 below on a
			// statue): fly over there first, then pick a spot to place it from. (Below or above us counts too: "nowhere to
			// place it from" straight away for TNT damage at the foot of the statue gave up on all of it.)
			// Looked over from here first, unless the far pick did that already (or chose to go and see it blind): no flight
			// for a spot nothing can be done to from up there (see workableFromAfar). Not counted as a failed go - from close
			// by it gets its normal turn.
			// (Not TNT damage: a blown-out wall is seldom buried and often seen only from inside or from the side - over there
			// to look, as before. The pick has already passed over the buried ones.)
			if (!t.equals(farApproved) && !damaged.contains(t.asLong())) {
				Boolean w = farOk(p, b, t);
				if (w == null) return note = "looking the build over for the next block";
				if (!w) {
					// (Something to knock out where the build is empty - a bit of the hill hidden behind the build: 5 minutes,
					// not 1. The valley castle cycled through hundreds of them, "[confused] 35 targets in 10 s, nothing placed".)
					BlockState wantF = plan.get(t);
					bad.put(t.immutable(), now + (wantF != null && wantF.isAir() ? 6000 : 1200));
					failWhy.put(t.immutable(), "nothing to work on it from, seen from afar");
					windowSkips.merge("seen from afar: nothing to do it from", 1, Integer::sum);
					if (now - farSkipLogAt > 40) {
						farSkipLogAt = now;
						HumanAutopilot.LOGGER.info("[schem] not flying over to {} ({}): {} - another for now", t.toShortString(),
								plan.get(t) == null ? "?" : plan.get(t).getBlock().getName().getString(),
								openFace(t) ? "no spot we can fly in to with a view of it" : "buried, a full block on every side");
					}
					target = null;
					stand = null;
					clickedAt = -1;
					return note = "looking for the next block";
				}
				farApproved = t;
			}
			// (To the spot it was found workable from, if there's one: open to the sky, so there's a way down to it.)
			BlockPos fs = farSpot(t);
			Vec3 goal = fs != null ? new Vec3(fs.getX() + 0.5, fs.getY() + hov(fs), fs.getZ() + 0.5)
					: new Vec3(t.getX() + 0.5, t.getY() + 1 + HOVER, t.getZ() + 0.5);
			double dist = p.position().distanceTo(goal);
			// (Going round the statue the straight distance doesn't shrink for a while: what's left of the way does. One
			// measure at a time: the cells of a long way round are more than the straight distance, and comparing the two
			// gave up on a castle's 96-cell way round after 5 s while it was getting there - 138 times in an evening.)
			double left = routeLeft >= 0 ? routeLeft : dist;
			int mode = routeLeft >= 0 ? 1 : 0;
			if (mode != progressMode) {
				progressMode = mode;
				bestDist = left;
				progressAt = now;
			} else if (left < bestDist - 0.3) {
				bestDist = left;
				progressAt = now;
			} else if (now - progressAt > 100) {
				giveUp(t, now, "couldn't fly there");
				return note = "looking for the next block";
			}
			work(aim, Vec3.atCenterOf(t));
			BlockPos goalCell = BlockPos.containing(goal);
			// (A door on the way is progress too: not given up on while opening it - for 8 s.)
			if (throughDoor(p, in, aim, b, goalCell, now) || walkThroughDoor(p, in, aim, b, goalCell, now)) {
				doorProgress(now);
				return note;
			}
			steer(p, in, route(p, b, goal), false, 0.9);
			if (routeNoWay) {
				giveUp(t, now, "couldn't fly there");
				return note = "looking for the next block";
			}
			if (dist > 4) in.hold(InputDriver.opts().keySprint);
			air(p, b); // keep the map following us
			readyItem(p, in, t);
			return note = "flying over to the next block";
		}
		if (stand == null) {
			tuftHits = plan != null ? new java.util.LinkedHashSet<>() : null;
			stand = standFor(p, b, t);
			if (stand == null && plan != null && steepOk.add(t)) {
				// No spot at a normal angle (a floor block in a small room: every spot is right over it): looking down
				// at it is what a player does then, straight away (not after leaving it for half a minute).
				stand = standFor(p, b, t);
			}
			lastTuftHits = tuftHits;
			tuftHitsFor = t;
			tuftHits = null;
			standLow = lastStandLow;
			standOff = lastStandOff;
			bestDist = Double.MAX_VALUE;
			progressAt = now;
			sprintTrip = Human.chance(0.7);
			if (stand == null) {
				giveUp(t, now, "nowhere to place it from");
				return note = "looking for the next hole";
			}
		}
		// (Something's gone into the spot we were heading for since it was picked - one of our own blocks: another spot now,
		// not 4 s of hovering over it and "couldn't fly there", the fantasy terrain run.)
		// (By the open-air map's own test - a ladder's spot is a way through, though solid() calls it solid: no picking the
		// same spot again and again.)
		boolean standOpen = way(stand.getX(), stand.getY(), stand.getZ()) && way(stand.getX(), stand.getY() + 1, stand.getZ());
		if (!stand.equals(standOpenFor)) {
			standOpenFor = stand;
			standWasOpen = standOpen;
		} else if (plan != null && standWasOpen && !standOpen) {
			stand = null;
			standOpenFor = null;
			return note = "looking for a better spot";
		}
		Vec3 goal = new Vec3(stand.getX() + 0.5 + standOff.x, stand.getY() + (standLow ? LOW : hov(stand)), stand.getZ() + 0.5 + standOff.z);
		lowHover = standLow;
		double dist = p.position().distanceTo(goal);
		if (forceStandFor != null && dist < 0.5) {
			forceStandFor = null;
			offFace = 0;
		}
		// (Hanging about just off it is being there: bobbing 0.2-0.6 from the spot while the server lagged 4 s, he gave it up
		// as "couldn't fly there" from right next to it - the castle's eaves. TNT going off makes a server lag like that.)
		boolean nearGoal = dist < 0.8;
		double leftN = routeLeft >= 0 && dist > 1.5 ? routeLeft : dist;
		int modeN = routeLeft >= 0 && dist > 1.5 ? 1 : 0;
		if (modeN != progressMode) {
			progressMode = modeN;
			bestDist = leftN;
			progressAt = now;
		} else if (leftN < bestDist - 0.3) {
			bestDist = leftN;
			progressAt = now;
		} else if (now - progressAt > 80 && !nearGoal) {
			giveUp(t, now, "couldn't fly there");
			return note = "looking for the next hole";
		}
		// There (or as good as) and still can't see it: don't hang about, take another hole.
		if (dist < 0.5 || (nearGoal && (atStandSince >= 0 || now - progressAt > 40))) {
			// (A stair, and it's only the heading's room either side that's missing from where we hang: right on the line
			// will do now, rather than wait and look for another spot. Once we're right on the spot, or have hung there a
			// moment: "there" is half a block, more than the room the spot was picked with.)
			if (lineOnly && (dist < 0.2 || (atStandSince >= 0 && now - atStandSince > 8))) yawLoose.add(t.immutable());
			if (atStandSince < 0) atStandSince = now;
			else if (now - atStandSince > 15) {
				// What's wrong with the view from here, once in a while (the castle's ground edge: 27 of these in 2 minutes).
				if (now - cantSeeLogAt > 200) {
					cantSeeLogAt = now;
					debug = new StringBuilder();
					faceFrom(p, p.getEyePosition(), p.getViewVector(1f), t);
					HumanAutopilot.LOGGER.info("[box] can't see {} ({}, {}, has {}) from {} (eye {}, look {}/{}): {}", t.toShortString(),
							plan != null ? plan.get(t) : "-", job, Terrain.state(t), stand.toShortString(), fmt(p.getEyePosition()),
							Math.round(p.getYRot()), Math.round(p.getXRot()), debug);
					debug = null;
				}
				atStandSince = -1;
				if (plan != null && steepOk.add(t)) {
					// There, and the only way to see it is a bit steeper than we let ourselves look: a spot over it,
					// looking down, now (rather than leave it and come back).
					stand = null;
					return note = "looking for a better spot";
				}
				giveUp(t, now, "can't see it from the spot");
				return note = "looking for the next hole";
			}
		} else {
			atStandSince = -1;
		}
		double flat = Math.hypot(goal.x - p.getX(), goal.z - p.getZ());
		// The companion (or another player) where we're going - on the hole or in the spot we'd fill it from: another
		// hole, rather than fly into her (the user: "if she comes into blocking my path I go to a different spot").
		List<Vec3> othersNow = otherPlayers(p);
		if (!crowdFallback && !othersNow.isEmpty() && (nearest(othersNow, Vec3.atCenterOf(t)) < 2.0 || nearest(othersNow, goal.add(0, 0.9, 0)) < 1.5)) {
			HumanAutopilot.LOGGER.info("[box] someone's where we're going ({}): another spot for now", t.toShortString());
			bad.put(t.immutable(), now + 60);
			target = null;
			stand = null;
			return note = "someone's in the way: another spot";
		}
		{
			// Eyes on the hole we're heading for (where the block will go).
			Vec3 preEye = new Vec3(goal.x, goal.y + p.getEyeHeight(), goal.z);
			Face pre = locked != null && locked.cell.equals(t) ? locked
					: faceFrom(p, preEye, Vec3.atCenterOf(t).subtract(preEye).normalize(), t);
			if (pre != null) locked = pre;
			work(aim, pre != null ? pre.point : Vec3.atCenterOf(t));
		}
		if (throughDoor(p, in, aim, b, stand, now) || walkThroughDoor(p, in, aim, b, stand, now)) {
			doorProgress(now);
			return note;
		}
		// The last block of the way: slow down and line up exactly (stopping half a block off left the hole just out
		// of sight, and it hung around there "confused").
		precise = dist < 1.2;
		steer(p, in, route(p, b, goal), false, precise ? 0.2 : 0.9);
		precise = false;
		if (routeNoWay) {
			giveUp(t, now, "couldn't fly there");
			return note = "looking for the next hole";
		}
		if (flat > 3 && sprintTrip) in.hold(InputDriver.opts().keySprint);
		readyItem(p, in, t);
		return note = "flying to a hole (" + holeCount + " left)";
	}

	private static String name(Item item) {
		return item.getName(item.getDefaultInstance()).getString().toLowerCase(java.util.Locale.ROOT);
	}

	private boolean near(LocalPlayer p, Box b) {
		return p.getX() > b.minX() - 48 && p.getX() < b.maxX() + 49 && p.getZ() > b.minZ() - 48 && p.getZ() < b.maxZ() + 49
				&& p.getY() > b.minY() - 32 && p.getY() < b.maxY() + 64;
	}

	/**
	 * Looking straight down at it is fine: after a failed try (no spot at a normal angle), or for a block whose way
	 * round comes from where we face rather than the face we click (glazed terracotta, a furnace, a chest): a floor of
	 * those under a ceiling can only be done from right above, so no point failing once first (the cathedral's hidden
	 * tile floor: 1400 wasted tries).
	 */
	private boolean steepFine(BlockPos t) {
		if (steepOk.contains(t)) return true;
		BlockState want = plan != null ? plan.get(t) : null;
		if (want != null && Placement.headingFaced(want)) return true;
		// At the bottom of a gap (solid on all four sides): only from over it, looking down - straight away, not after a
		// failed try each (the user's castle's wall core, cobblestone after cobblestone "straight down from here" first,
		// 44 a minute).
		return want != null && solid(t.north()) && solid(t.south()) && solid(t.east()) && solid(t.west());
	}

	/** A tuft over a spot to put a block in (the build has air there, or nothing): on the clearing list, once. */
	private boolean clearOnTop(BlockPos c) {
		if (!clearedOnTop.add(c)) return false;
		if (!plan.containsKey(c)) {
			plan.put(c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			planLayers.computeIfAbsent(c.getY(), k -> new ArrayList<>()).add(c);
			planList.add(c);
		}
		remember(c, Placement.Job.BREAK);
		scanFromY = Math.min(scanFromY, c.getY());
		return true;
	}

	private static final java.util.Set<BlockPos> clearedOnTop = new java.util.HashSet<>();

	/**
	 * A spot the build has shut in - its neighbours all in, and from nowhere a face of one to click that puts it in the
	 * right way round, or (leaves in it) a look at it: one of those neighbours out to get at it, it in, that one back,
	 * like a player would. The neighbour: a plain full block (nothing to get the right way round putting it back, nothing
	 * in it), nothing hanging on it or falling when it goes, no water by it; the one whose going gives a spot to do it
	 * from (the spots tried with the rays going through it), the nearest. Twice per spot at most. (The cottage: a log in
	 * a wall whose neighbours went in first, a grindstone, stairs, trapdoors, leaves in its roof - 35 left as they were.)
	 */
	private boolean openUp(LocalPlayer p, BlockPos t, long now) {
		Box b = box();
		if (p == null || plan == null || b == null || opened.containsValue(t) || opened.containsKey(t) || openTries.getOrDefault(t, 0) >= 2) return false;
		if (plan.get(t) == null || !hole(t)) return false;
		// Not for the ground under the build (its sunk ground layers, soil): nobody sees those, and digging in for them
		// isn't worth it (the 25k house's first one: a dirt block of its ground, under the grass).
		if ((clearFromY != Integer.MAX_VALUE && t.getY() < clearFromY) || Placement.soil(plan.get(t)) || plan.get(t).isAir()) return false;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (Direction d : Direction.values()) {
			BlockPos n = t.relative(d);
			BlockState wn = plan.get(n), in = Terrain.state(n);
			if (wn == null || wn.isAir() || !Placement.matches(in, wn) || opened.containsKey(n) || opened.containsValue(n) || temps.containsKey(n)) continue;
			if (!wn.getProperties().isEmpty() || in.hasBlockEntity() || !in.isCollisionShapeFullBlock(Terrain.level(), n)
					|| !Placement.breakable(Terrain.level(), n, in)) continue;
			boolean holds = false;
			for (Direction d2 : Direction.values()) {
				BlockPos m = n.relative(d2);
				if (m.equals(t)) continue;
				BlockState ms = Terrain.state(m);
				if (!ms.getFluidState().isEmpty()) {
					holds = true; // (it would run in)
					break;
				}
				if (ms.isAir()) continue;
				if (d2 == Direction.UP && ms.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
					holds = true;
					break;
				}
				if (ms.isCollisionShapeFullBlock(Terrain.level(), m)) continue;
				var mb = ms.getBlock();
				if (mb instanceof net.minecraft.world.level.block.SlabBlock || mb instanceof net.minecraft.world.level.block.StairBlock
						|| mb instanceof net.minecraft.world.level.block.FenceBlock || mb instanceof net.minecraft.world.level.block.WallBlock
						|| mb instanceof net.minecraft.world.level.block.IronBarsBlock || mb instanceof net.minecraft.world.level.block.TrapDoorBlock
						|| mb instanceof net.minecraft.world.level.block.FenceGateBlock || mb instanceof net.minecraft.world.level.block.LeavesBlock) continue;
				holds = true; // (a torch, a ladder, a plant, a lantern...: might hang on it or stand on it)
				break;
			}
			if (holds) continue;
			pretendAir = java.util.Set.of(n.immutable());
			BlockPos spot;
			try {
				spot = standFor(p, b, t);
			} finally {
				pretendAir = java.util.Set.of();
			}
			if (spot == null) continue;
			double dd = p.position().distanceToSqr(Vec3.atCenterOf(spot));
			if (dd < bestD) {
				bestD = dd;
				best = n;
			}
		}
		openTries.merge(t, 1, Integer::sum);
		if (best == null) return false;
		opened.put(best.immutable(), t.immutable());
		openedAt.put(t.immutable(), now);
		cant.remove(t);
		bad.remove(t);
		bad.remove(best);
		HumanAutopilot.LOGGER.info("[schem] {} ({}) is shut in by the build: taking {} ({}) out to get at it (it goes back after)",
				t.toShortString(), plan.get(t), best.toShortString(), plan.get(best).getBlock().getName().getString());
		return true;
	}

	private long pocketAt = -1000, farFullAt = -1000;

	/** A spot shut in by the build opened up (openUp, openPocket) more than {@code r} from {@code at}, still open. */
	private static boolean openFar(Vec3 at, double r) {
		if (opened.isEmpty()) return false;
		for (BlockPos t : opened.values()) if (Vec3.atCenterOf(t).distanceToSqr(at) > r * r) return true;
		return false;
	}
	/** Skips in a row since the last block went in (see buildOrder). */
	private int failStreak;
	private final Map<BlockPos, Long> pocketTried = new HashMap<>();

	/** Damage still to be put right first: blown out, and not tried twice already with no way to do it (see pickPlanTarget). */
	private boolean damageToDo() {
		for (long k : damaged) if (fails.getOrDefault(BlockPos.of(k), 0) < 2) return true;
		return false;
	}

	/** Doorways made into rooms of the build shut all round (roomDoor): each of the doorway's blocks -> the room's spots. */
	private final Map<BlockPos, java.util.Set<BlockPos>> doorRooms = new HashMap<>();

	/**
	 * Spots still to do in a room of the build that's shut all round, from out here (walled out: its walls and roof went
	 * in first) - more than a pocket (openPocket takes 12 cells at most): a doorway in, two blocks of its wall one over
	 * the other, taken out like a player would, and put back once the room's done (it stays open while its spots go in,
	 * one after another; a minute with none, and it goes back). True if a doorway's going in.
	 */
	private boolean roomDoor(LocalPlayer p, Box b, long now) {
		Map<BlockPos, BlockPos> flyTo = air(p, b);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos h : holes) {
			if (!hole(h) || cant.contains(h) || temps.containsKey(h) || opened.containsValue(h) || opened.containsKey(h)
					|| !Terrain.loaded(h.getX(), h.getZ()) || gettable(h, flyTo)) continue;
			BlockState st = Terrain.state(h);
			if (!st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) continue; // (in the open part of a room)
			double d = Vec3.atCenterOf(h).distanceToSqr(p.position());
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		if (best == null) return false;
		// The room: its open cells, from that spot (open to us after all: something else keeps it from us, not walls).
		java.util.Set<BlockPos> room = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		room.add(best.immutable());
		q.add(best);
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (room.contains(n)) continue;
				BlockState st = Terrain.state(n);
				if (!st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) continue;
				if (flyTo.containsKey(n)) return false;
				room.add(n.immutable());
				if (room.size() > 6000) return false;
				q.add(n);
			}
		}
		// The doorway: two plain blocks of the wall one over the other, the room 2 high right inside them, our open air
		// right outside - the nearest.
		BlockPos door = null;
		Direction doorDir = null;
		double doorD = Double.MAX_VALUE;
		for (BlockPos c : room) {
			if (!room.contains(c.above())) continue;
			for (Direction hd : Direction.Plane.HORIZONTAL) {
				BlockPos a = c.relative(hd), au = a.above();
				if (room.contains(a) || room.contains(au)) continue;
				int w0 = pocketWall(a, room, flyTo), w1 = pocketWall(au, room, flyTo);
				if (w0 == 0 || w1 == 0) continue;
				BlockPos o = a.relative(hd), ou = o.above();
				if (!flyTo.containsKey(o) && !flyTo.containsKey(ou)) continue;
				double dd = p.position().distanceToSqr(Vec3.atCenterOf(a));
				if (dd < doorD) {
					doorD = dd;
					door = a;
					doorDir = hd;
				}
			}
		}
		if (door == null) {
			HumanAutopilot.LOGGER.info("[schem] walled out of a room ({} open cells) by {}: no doorway that would do", room.size(), best.toShortString());
			return false;
		}
		java.util.Set<BlockPos> spots = new java.util.HashSet<>();
		for (BlockPos c : room) if (plan.get(c) != null && hole(c)) spots.add(c);
		BlockPos du = door.above().immutable(), dl = door.immutable();
		opened.put(dl, best.immutable());
		opened.put(du, best.immutable());
		openedAt.put(best.immutable(), now);
		doorRooms.put(dl, spots);
		doorRooms.put(du, spots);
		bad.remove(dl);
		bad.remove(du);
		HumanAutopilot.LOGGER.info("[schem] walled out of a room of the build ({} open cells, {} spots to do in it): a doorway in at {} facing {} ({}), it goes back after",
				room.size(), spots.size(), dl.toShortString(), doorDir, plan.get(dl).getBlock().getName().getString());
		return true;
	}

	/**
	 * {@code t} is shut in a small pocket of open cells (12 at most) with the build's blocks all round: one of those
	 * blocks out - a plain one with nothing hanging on it, open air we can get to on its other side, and with it gone
	 * a cell beside {@code t} our body fits in - so the pocket opens to us (see {@link #openUp}: it goes back after).
	 */
	private boolean openPocket(LocalPlayer p, Box b, BlockPos t, Map<BlockPos, BlockPos> flyTo, long now) {
		// (Looked at: not again for a minute, whatever it turned out to be - a room's worth of air the nearest every time
		// would have kept the pockets past it from ever being looked at.)
		if (pocketTried.size() > 500) pocketTried.values().removeIf(at -> now - at > 1200);
		pocketTried.put(t.immutable(), now);
		java.util.Set<BlockPos> pocket = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		pocket.add(t.immutable());
		q.add(t);
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (pocket.contains(n)) continue;
				BlockState st = Terrain.state(n);
				if (!st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) continue; // (its wall)
				if (flyTo.containsKey(n) || flyTo.containsKey(n.below())) return false; // (open to us after all)
				pocket.add(n.immutable());
				if (pocket.size() > 12) return false; // (a room, not a pocket; TNT through a wall's core leaves a few together)
				q.add(n);
			}
		}
		openTries.merge(t, 1, Integer::sum);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos c : pocket) {
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (pocket.contains(n) || (best != null && n.equals(best))) continue;
				if (pocketWall(n, pocket, flyTo) != 2) continue; // (not one to take out, or none of our open air past it)
				// With it gone, somewhere our body fits to put it in from: beside it (feet level with it), or two over it
				// looking down past the cell between (standFor never stands right on top of the spot).
				java.util.function.Predicate<BlockPos> free = x -> x.equals(n) || pocket.contains(x) || Terrain.passable(x.getX(), x.getY(), x.getZ());
				boolean fits = free.test(t.above()) && free.test(t.above(2)) && free.test(t.above(3));
				for (Direction hd : Direction.Plane.HORIZONTAL) {
					BlockPos x = t.relative(hd);
					if (!fits && free.test(x) && free.test(x.above())) fits = true;
				}
				if (!fits) continue;
				double dd = p.position().distanceToSqr(Vec3.atCenterOf(n));
				if (dd < bestD) {
					bestD = dd;
					best = n;
				}
			}
		}
		// None that one block out lets us at it (the wall's gone on up over it: the user's castle's cores, 2-3 blocks of
		// wall over the pocket by the time it was looked at): two out of the wall's face beside it, one over the other, if
		// from out there it can then be seen and put in (worked out as standFor would, with those two as air).
		BlockPos best2 = null;
		if (best == null) {
			for (Direction hd : Direction.Plane.HORIZONTAL) {
				BlockPos x = t.relative(hd), xu = x.above();
				if (pocket.contains(x) || pocket.contains(xu)) continue;
				int w0 = pocketWall(x, pocket, flyTo), w1 = pocketWall(xu, pocket, flyTo);
				if (w0 == 0 || w1 == 0 || (w0 != 2 && w1 != 2)) continue;
				pretendAir = java.util.Set.of(x.immutable(), xu.immutable());
				BlockPos spot;
				try {
					spot = standFor(p, b, t);
				} finally {
					pretendAir = java.util.Set.of();
				}
				if (spot == null) continue;
				double dd = p.position().distanceToSqr(Vec3.atCenterOf(x));
				if (dd < bestD) {
					bestD = dd;
					best = x;
					best2 = xu;
				}
			}
		}
		if (best == null) return false;
		opened.put(best.immutable(), t.immutable());
		if (best2 != null) opened.put(best2.immutable(), t.immutable());
		openedAt.put(t.immutable(), now);
		cant.remove(t);
		bad.remove(t);
		bad.remove(best);
		if (best2 != null) bad.remove(best2);
		HumanAutopilot.LOGGER.info("[schem] {} ({}) is shut in a pocket of the build ({} open): taking {} ({}){} out to get at it (they go back after)",
				t.toShortString(), plan.get(t), pocket.size(), best.toShortString(), plan.get(best).getBlock().getName().getString(),
				best2 == null ? "" : " and " + best2.toShortString() + " (" + plan.get(best2).getBlock().getName().getString() + ")");
		return true;
	}

	/**
	 * A block round a pocket (see openPocket): 0 if it's not one to take out (not the build's own plain full block as it
	 * should be, or something might hang on it or run in), 1 if it is, 2 if it is and open air we can get to is past it.
	 */
	private int pocketWall(BlockPos n, java.util.Set<BlockPos> pocket, Map<BlockPos, BlockPos> flyTo) {
		BlockState wn = plan.get(n), in = Terrain.state(n);
		if (wn == null || wn.isAir() || !Placement.matches(in, wn) || opened.containsKey(n) || opened.containsValue(n) || temps.containsKey(n)) return 0;
		if (!wn.getProperties().isEmpty() || in.hasBlockEntity() || !in.isCollisionShapeFullBlock(Terrain.level(), n)
				|| !Placement.breakable(Terrain.level(), n, in)) return 0;
		boolean outside = false;
		for (Direction d2 : Direction.values()) {
			BlockPos m = n.relative(d2);
			if (pocket.contains(m)) continue;
			if (flyTo.containsKey(m) || flyTo.containsKey(m.below())) outside = true;
			BlockState ms = Terrain.state(m);
			if (!ms.getFluidState().isEmpty() || (d2 == Direction.UP && ms.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return 0;
			if (ms.isAir() || ms.isCollisionShapeFullBlock(Terrain.level(), m)) continue;
			var mb = ms.getBlock();
			if (mb instanceof net.minecraft.world.level.block.SlabBlock || mb instanceof net.minecraft.world.level.block.StairBlock
					|| mb instanceof net.minecraft.world.level.block.FenceBlock || mb instanceof net.minecraft.world.level.block.WallBlock
					|| mb instanceof net.minecraft.world.level.block.IronBarsBlock || mb instanceof net.minecraft.world.level.block.TrapDoorBlock
					|| mb instanceof net.minecraft.world.level.block.FenceGateBlock || mb instanceof net.minecraft.world.level.block.LeavesBlock) continue;
			return 0; // (a torch, a ladder, a lantern... might hang on it)
		}
		return outside ? 2 : 1;
	}


	private void giveUp(BlockPos t, long now, String why) {
		// Opened up for this one and still no spot to put it in from: what was taken out for it goes straight back, not a
		// minute later. (Knight's Order, 03:35: one block out for -12, -59, 41, which went on being "nowhere to place it
		// from" and skipped, the gap left open for the minute.)
		// (Any reason, not only "nowhere": 04:10, "can't see it from the spot" left a gap open the same way.)
		if (plan != null && opened.containsValue(t)) {
			List<BlockPos> back = new ArrayList<>();
			for (var o : opened.entrySet()) if (o.getValue().equals(t)) back.add(o.getKey());
			for (BlockPos q : back) {
				opened.remove(q);
				doorRooms.remove(q);
			}
			openedAt.remove(t);
			putBack.addAll(back);
			HumanAutopilot.LOGGER.info("[undo] {} skipped ({}) with {} out: {} goes back", t.toShortString(), why, back, back.size() == 1 ? "it" : "they");
		}
		if (plan != null && why.startsWith("couldn't fly")) {
			flyFails.values().removeIf(at -> now - at > 1200);
			flyFails.put(t.immutable(), now);
		}
		// Got there and still couldn't see it (a floor of upright wood, its neighbours in already: only from right over it):
		// looking straight down at it is fine next time (16 of these in the hospital, each tried again the same way).
		if (plan != null && why.startsWith("can't see")) steepOk.add(t);
		// A snow layer or a tuft of grass in the spot itself, hiding the faces under it (the snowy hill under the barn's
		// floor: dirt "nowhere to place it from" again and again): knock it out first, then it's open to put the block in.
		BlockState inIt = Terrain.state(t);
		// (The lower half of a tall plant - tall grass, a large fern - in a pit: its upper half over the pit hides it from
		// every side, so it can't be knocked out where it is either. Its upper half can, and takes the lower one with it:
		// the natural medieval's dirt fill, 22 targets in 10 s and nothing done, at the end.)
		if (plan != null && why.startsWith("nowhere") && inIt.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& inIt.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
				&& inIt.canBeReplaced() && Terrain.state(t.above()).getBlock() == inIt.getBlock() && clearOnTop(t.above())) {
			HumanAutopilot.LOGGER.info("[schem] {} has a tall {} in it: knocking out its top first", t.toShortString(),
					inIt.getBlock().getName().getString());
			bad.put(t, now + 40);
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		if (plan != null && why.startsWith("nowhere") && !inIt.isAir() && inIt.canBeReplaced() && inIt.getFluidState().isEmpty()
				&& clearFirst.add(t)) {
			HumanAutopilot.LOGGER.info("[schem] {} has {} in it hiding where to click: knocking that out first", t.toShortString(),
					inIt.getBlock().getName().getString());
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// The same for a tuft ON a block to knock out, in a spot where the build has a block too (so not on the clearing
		// list): it hides the top face, and the hill round it the sides. (The black hole's foot in the hillside: grass to
		// dig out under flowers and grass, "ray hits" the tuft over it, 46 + 34 times.) It goes first, then this one.
		BlockPos onT = t.above();
		BlockState onIt = Terrain.state(onT);
		// (And over a pit to put a block in, too: the snowy cottage's cobblestone floor, a snow layer or grass tuft in the
		// spot over each pit hiding it, "ray hits" it, 10 left as they were. One the build has air in, or nothing: on the
		// clearing list.)
		Placement.Job tj = plan != null ? jobAt(t) : null;
		if (plan != null && why.startsWith("nowhere") && (tj == Placement.Job.BREAK || tj == Placement.Job.PLACE) && !onIt.isAir()
				&& onIt.canBeReplaced() && onIt.getFluidState().isEmpty() && !onIt.getShape(Terrain.level(), onT).isEmpty()
				&& Placement.breakable(Terrain.level(), onT, onIt)
				&& (plan.containsKey(onT) && !plan.get(onT).isAir() ? clearFirst.add(onT) : clearOnTop(onT))) {
			HumanAutopilot.LOGGER.info("[schem] {} has {} on it hiding its top: knocking that out first", t.toShortString(),
					onIt.getBlock().getName().getString());
			bad.put(t, now + 40);
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// Leaf litter, grass, flowers on the blocks round it, in the way of every look at it (not on it: that's above):
		// knocked out first, like a player would. (The black hole in a birch wood: the ground's leaf litter in front of
		// one face after another, "nowhere to place it from".)
		// Soil of the build's ground with no look at it from anywhere: nobody sees it and the only way in is digging. As it
		// is while what's round it stays as it is, not tried again every few minutes. (The copper guard's bottom dirt: a
		// hollow of 30+ under its grass at the edge, her flying from the walls to them and back - "stuck going back and
		// forth", 28 a minute; then TNT craters with their tops back in, "nowhere" with open air over them too, 0-2 a
		// minute for 6 minutes. A solid block over it was asked for at first: that missed the second.)
		// (Any block shut inside the build the same way, after two goes: hoppers in a wall's core, the build's own blocks
		// all round them or more of its holes, 13 goes each through the night - TNT kept changing what was round them.)
		if (plan != null && why.startsWith("nowhere") && !Placement.soil(plan.get(t)) && nowhere.getOrDefault(t, 0) >= 2 && insideBuild(t)
				&& buriedSoil.putIfAbsent(t.immutable(), around(t)) == null) {
			HumanAutopilot.LOGGER.info("[schem] leaving {} ({}) as it is: shut inside the build, no look at it from anywhere (till something round it changes)",
					t.toShortString(), plan.get(t));
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// (Or under finished ground, whatever stopped her: a hollow of air and old tree leaves inside the copper guard's dirt
		// in a normal world, its grass already in over it - "couldn't fly there", "can't see it", 5 goes each, digging
		// pockets she then got stuck in; nobody sees it, and the 97% fill-in puts it in.)
		boolean covered = plan != null && Placement.soil(plan.get(t)) && underDoneGround(t);
		if (plan != null && (why.startsWith("nowhere") || covered) && Placement.soil(plan.get(t))
				&& buriedSoil.putIfAbsent(t.immutable(), around(t)) == null) {
			HumanAutopilot.LOGGER.info("[schem] leaving {} ({}) as it is: {} (till something round it changes)",
					t.toShortString(), plan.get(t), covered && !why.startsWith("nowhere") ? "under ground that's in already (" + why + ")"
							: "no look at it from anywhere");
			// (The rest of it round here looked at from here, not flown to one by one. Within 6 at first: after 400 TNT she
			// still flew 30-50 blocks to the next lot each time and placed nothing for 7 minutes; the open-air map she
			// looks from reaches REG_H.)
			if (buriedCheck.size() < 600) {
				for (BlockPos h : holesNear(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5, REG_H - 2, 3)) {
					if (buriedCheck.size() >= 600) break;
					BlockState hw = plan.get(h);
					if (hw != null && Placement.soil(hw) && !buriedSoil.containsKey(h) && !buriedCheck.contains(h)) buriedCheck.add(h.immutable());
				}
			}
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		if (plan != null && why.startsWith("nowhere") && t.equals(tuftHitsFor) && lastTuftHits != null && !lastTuftHits.isEmpty()
				&& tuftsHideIt(t, lastTuftHits)) {
			Vec3 me = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.position() : Vec3.atCenterOf(t);
			List<BlockPos> hits = new ArrayList<>(lastTuftHits);
			List<BlockPos> tufts = new ArrayList<>(lastTuftHits);
			tufts.removeIf(c -> {
				BlockState w = plan.get(c);
				return (w != null && !w.isAir()) || clearedOnTop.contains(c) || !Placement.breakable(Terrain.level(), c, Terrain.state(c));
			});
			tufts.sort(java.util.Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(Vec3.atCenterOf(t))));
			int n = 0;
			for (BlockPos c : tufts) {
				if (n >= 3) break;
				if (clearOnTop(c)) n++;
			}
			lastTuftHits = null;
			if (n > 0) {
				HumanAutopilot.LOGGER.info("[schem] {} is hidden behind {} ({} of them): knocking that out first", t.toShortString(),
						Terrain.state(tufts.get(0)).getBlock().getName().getString(), n);
				bad.put(t, now + 40);
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			// The build's own grass or flowers in the way instead (in already, the spot behind them blown out by TNT after,
			// or never in): out to get at it, and back in after, like a player would. (The user's garden: a path's stone
			// under the build's tall grass, "nowhere to place it from" - 28 gaps of ~22 s in 90 minutes.)
			if (!outFor.containsValue(t) && !outFor.containsKey(t) && outTries.getOrDefault(t, 0) < 2) {
				List<BlockPos> own = new ArrayList<>(hits);
				own.removeIf(c -> {
					BlockState w = plan.get(c), in = Terrain.state(c);
					return w == null || w.isAir() || !Placement.matches(in, w) || !in.canBeReplaced() || !in.getFluidState().isEmpty()
							|| !in.getCollisionShape(Terrain.level(), c).isEmpty() || outFor.containsKey(c) || outFor.containsValue(c) || opened.containsKey(c)
							|| temps.containsKey(c) || !Placement.breakable(Terrain.level(), c, in);
				});
				own.sort(java.util.Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(Vec3.atCenterOf(t))));
				int k = 0;
				for (BlockPos c : own) {
					if (k >= 2) break;
					outFor.put(c.immutable(), t.immutable());
					outForAt.put(c.immutable(), now);
					remember(c, jobAt(c));
					k++;
				}
				if (k > 0) {
					outTries.merge(t.immutable(), 1, Integer::sum);
					HumanAutopilot.LOGGER.info("[schem] {} is hidden behind the build's own {} ({} of them): taking that out to get at it, back in after",
							t.toShortString(), Terrain.state(own.get(0)).getBlock().getName().getString(), k);
					bad.put(t, now + 40);
					target = null;
					stand = null;
					clickedAt = -1;
					return;
				}
			}
		}
		// Floating over a gap a block or two high with ground under it (a rock in a garden, a beam over a crawl space): a
		// temporary block or two under it from the ground, it goes on top, they come out again - like a player. (The
		// fantasy build's garden: two rocks over a 1-high gap, "nowhere to place it from" every 15 minutes, 24 skips.)
		if (plan != null && why.startsWith("nowhere") && gapTempsTried.add(t.immutable()) && gapTemps(t)) {
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// Under a tree's canopy with no way down to it (the copper guard's dark oaks: 3 minutes over them, each spot under
		// them "couldn't fly there" in turn): a shaft down through the leaves over it, then another go.
		if (plan != null && why.startsWith("couldn't fly") && leavesOverhead(t)) {
			bad.put(t, now + 100);
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// In a tree (its trunk in the build): the leaves in the way go first, then it gets another go soon.
		if (plan != null && why.startsWith("nowhere") && leavesInWay(Minecraft.getInstance().player, t)) {
			bad.put(t, now + 60);
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// A hopper: which way it points hardly shows (its spout), and the face to click for that way is often inside the
		// wall it sits in (the copper guard's hopper rows at y -51: 225 "no spot" in 5 minutes, the TNT clearing their
		// give-up count every time). Any way round after the first "nowhere".
		if (plan != null && why.startsWith("nowhere") && !relaxed.contains(t) && plan.get(t) != null
				&& plan.get(t).getBlock() instanceof net.minecraft.world.level.block.HopperBlock) {
			relaxed.add(t);
			bad.remove(t);
			HumanAutopilot.LOGGER.info("[schem] {} (a hopper) has no spot to put it in pointing the right way: any way round", t.toShortString());
			target = null;
			stand = null;
			clickedAt = -1;
			return;
		}
		// A barrel facing down needs a look up at its spot from below; on top of another block of the build (the cottage's
		// stacked barrels, on a slab, on another barrel) there's no below to look from: any way round, now, not after
		// three goes minutes apart (the TNT took two out and they waited on it the rest of the test).
		if (plan != null && why.startsWith("nowhere") && !relaxed.contains(t) && plan.get(t) != null
				&& plan.get(t).getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
				&& plan.get(t).getValue(net.minecraft.world.level.block.BarrelBlock.FACING) == Direction.DOWN) {
			BlockState under = plan.get(t.below());
			if ((under != null && !under.isAir()) || solid(t.below())) {
				relaxed.add(t);
				bad.remove(t);
				HumanAutopilot.LOGGER.info("[schem] {} (a barrel facing down) has no room under it to look up from: any way round", t.toShortString());
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
		}
		if (plan != null && why.startsWith("nowhere")) {
			// (8 ms gone in this tick already - the search that found no spot, a pick among thousands: the temporary blocks
			// and the open-up, a few more searches each, half a second on in a tick of their own. All at once they were the
			// cottage's 26-50 ms ticks, a hitch every 15 s. Once per spot, so never put off for good.)
			if (System.nanoTime() - tickStartNs > 8_000_000L && deferredNowhere.add(t.immutable())) {
				if (deferredNowhere.size() > 2000) deferredNowhere.clear();
				bad.put(t, now + 10);
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			nowhere.merge(t, 1, Integer::sum);
			steepOk.add(t); // no spot at a normal angle: looking straight down at it is fine next time
			// The build's bottom over a pit (TNT took the ground from under the tower's wall): a dirt pillar up to it
			// now. (Only once nothing else was left to do, it waited 4 minutes: the tower test's 90 s went by.)
			// (Any number at once: TNT leaves a crater under several; one at a time, four were still out 5 minutes on.)
			BlockPos under = t.below();
			int baseY = clearFromY != Integer.MAX_VALUE ? clearFromY : planBottomY; // (the floor: dirt under it counts as below)
			boolean pillarFits = t.getY() <= baseY && !solid(under) && Terrain.passable(under.getX(), under.getY(), under.getZ())
					&& !plan.containsKey(under);
			// (A short one only, first: over the 25k house's ravine a ground block at its edge got "a 38-block dirt pillar
			// up to it" - 76 clicks up and back down for one block, and the window down at the ravine's floor - where a
			// temporary block or two out from the edge beside it would do. A tall one only when that won't.)
			if (pillarFits && pillarDepth(t) <= 8 && pillarUnder(t)) {
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			// Nothing to click on gives the right way round: a temporary block next to it to click on.
			if (tryScaffold(Minecraft.getInstance().player, t)) {
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			if (pillarFits && pillarUnder(t)) {
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			// Shut in by the build itself (second time with no spot; the first, for one TNT blew out - the next go at it
			// was many minutes off with the rest of the blast to look at first): one of its blocks out to get at it, it in,
			// that one back - like a player would.
			if ((nowhere.getOrDefault(t, 0) >= 2 || damaged.contains(t.asLong())) && openUp(Minecraft.getInstance().player, t, now)) {
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
		}
		// A hole hidden under an overhang (nowhere to place it from) won't change soon: leave it longer, and longer
		// each time (trying them all again every couple of seconds was the "confused" flying about at the end).
		long wait = why.startsWith("nowhere") ? 600 : 200;
		if (plan != null) wait *= Math.min(4, Math.max(1, nowhere.getOrDefault(t, 1)));
		if (plan != null) failWhy.put(t, why);
		// (Something to knock out where the build is empty, hidden behind what's been built: 2 goes, not 5. The valley castle:
		// 135 such bits of the hill, 4-5 flights and searches each, 550 "nowhere" and the "[confused]" bursts. It still gets
		// another go when something next to it changes.)
		BlockState wantG = plan == null ? null : plan.get(t);
		int maxFails = wantG != null && wantG.isAir() && why.startsWith("nowhere") ? 2 : 5;
		if (plan != null && fails.merge(t, 1, Integer::sum) >= maxFails) {
			// (A barrel or a 16-way block with no spot to put it in the right way round: any way round instead of not at
			// all. The relaxing at the very end never got to the cottage's 12 barrels: they'd been left by then.)
			BlockState wantT = plan.get(t);
			if (wantT != null && why.startsWith("nowhere") && !relaxed.contains(t) && jobAt(t) == Placement.Job.PLACE
					&& (Placement.fineRotation(wantT) || wantT.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)
					&& wantT.canSurvive(Terrain.level(), t)) {
				relaxed.add(t);
				fails.remove(t);
				nowhere.remove(t);
				HumanAutopilot.LOGGER.info("[schem] no way to put {} the right way round: any way will do for this one", t.toShortString());
				bad.put(t, now + 20);
				target = null;
				stand = null;
				clickedAt = -1;
				return;
			}
			// Five goes and no luck: leave it as it is (it gets another go when something next to it changes), rather
			// than hang about waiting to try it again, for ever.
			if (cant.add(t)) {
				HumanAutopilot.LOGGER.info("[schem] leaving {} ({}, has {}) as it is after {} tries: {}", t.toShortString(), plan.get(t),
						Terrain.state(t), fails.get(t), why);
				caseFile(t, why);
			}
		}
		if (plan != null && temps.containsValue(t)) wait = 40; // its temporary block is on the way
		bad.put(t, now + wait);
		skippedCount++;
		failStreak++;
		windowSkips.merge(why, 1, Integer::sum);
		HumanAutopilot.LOGGER.info("[box] skipping {} for now: {}", t.toShortString(), why);
		target = null;
		stand = null;
		clickedAt = -1;
	}

	// ------------------------------------------------------------------ looking at the box

	private static boolean hole(BlockPos c) {
		BlockState s = Terrain.state(c);
		if (plan != null) {
			// Schematic: anything the spot still needs (put in, knocked out first, a finishing touch).
			Placement.Job j = jobAt(c);
			return j == Placement.Job.PLACE || j == Placement.Job.BREAK || j == Placement.Job.ADJUST;
		}
		return s.isAir() || s.canBeReplaced();
	}

	/** What a spot of the plan still needs to be exactly like the schematic. */
	static Placement.Job jobAt(BlockPos c) {
		BlockState want = plan.get(c);
		if (want == null) return Placement.Job.DONE;
		// The ground under the build in a flat world - grass, dirt under its floor - is the grass filler's (the companion
		// plugin's): not ours to put back after TNT (the user: "make the autopilot and the building companion continue to
		// only repair the structures/blocks that belongs to the schematic").
		if (leaveGround && isGround(c, want)) return Placement.Job.DONE;
		BlockState world = Terrain.state(c);
		// One of ours taken out to get at another, to go back now: a hole, whatever the "buried, nobody sees it" rules
		// below say. (Knight's Order: polished andesite taken out to reach a smoker; with the smoker in, the floor round
		// the gap made it "done under cover", it came off the put-back list and stayed out - the user: "break a wall block
		// or a block he's already put in, fly away ... come back and restore it".)
		if (putBack.contains(c) && world.getBlock() != want.getBlock() && (world.isAir() || world.canBeReplaced()) && world.getFluidState().isEmpty()) {
			return Placement.Job.PLACE;
		}
		// A normal world: the build's ground layers sink into the world's own ground. Under its top layer, the world's own
		// ground is as good as the schematic's (stone for its dirt: nobody sees it); and a cave, an old tree's leaves or air
		// under ground that covers it isn't to be dug into and filled. (The copper guard on a hillside: hundreds of its dirt
		// spots deep in the world's ground, "nowhere to place it from" one by one, "[confused] 30 targets in 10 s", flying
		// round for minutes - "running confused again". The top layer still gets the schematic's own blocks; the 97%
		// fill-in puts the rest in exactly.)
		if (!leaveGround && isGround(c, want) && (Placement.soil(want) || Placement.naturalGround(want))) {
			// (The top layer too while the world's own ground is still over it - the hill to clear: grass wanted where the
			// hillside's stone is, stone over it, "nowhere" 42 times at y 68 in 2 minutes. Cleared over, it's to do again.)
			if (worldGround(world) && (c.getY() < clearFromY - 1 || Terrain.state(c.above()).canOcclude())) return Placement.Job.DONE;
			// (Empty and not open to the sky - ground, an old tree's leaves or logs over it: under the copper guard's trees,
			// dirt at y 66-67 "nowhere" 49 times in 4 minutes after the fix above. Once what's over it is cleared away it's
			// open to the sky and to do again: this is looked at fresh each time.)
			if (!world.canOcclude() && world.getFluidState().isEmpty()) {
				for (int i = 1; i <= 12; i++) {
					BlockState over = Terrain.state(c.above(i));
					if (over.isAir() || !over.getFluidState().isEmpty()) continue;
					if (over.canOcclude() || over.is(net.minecraft.tags.BlockTags.LEAVES) || over.is(net.minecraft.tags.BlockTags.LOGS)) {
						return Placement.Job.DONE;
					}
				}
			}
		}
		// The top half of a tall plant (a door) whose bottom half is the build's, where the schematic has nothing: the game
		// puts both halves in, a player can't do otherwise. (The cottage's tall grass with air saved over it: put in,
		// its top knocked out - and the bottom with it - put in again, 47 times in 15 minutes.)
		if (want.isAir() && world.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& world.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
			BlockState wantUnder = plan.get(c.below());
			if (wantUnder != null && wantUnder.getBlock() == world.getBlock()) return Placement.Job.DONE;
		}
		// A bamboo shoot where the build has bamboo: the next stalk on top turns it into bamboo (none on top: a shoot is
		// as near as a player gets).
		if (world.is(net.minecraft.world.level.block.Blocks.BAMBOO_SAPLING) && want.is(net.minecraft.world.level.block.Blocks.BAMBOO)) return Placement.Job.DONE;
		// And the other way round: a shoot the schematic has under its bamboo turns into bamboo by itself (the game does it
		// as soon as anything next to it changes; a paste too). (The castle's bamboo: knocked out as "wrong", the whole
		// stalk over it came down, and the vines crept in before it could go back up: 29 stalls, 8 blocks a minute.)
		if (world.is(net.minecraft.world.level.block.Blocks.BAMBOO) && want.is(net.minecraft.world.level.block.Blocks.BAMBOO_SAPLING)) return Placement.Job.DONE;
		// Concrete powder by water sets as it goes in, a sponge by water soaks it up: no other way to have them there.
		if ((want.getBlock() instanceof net.minecraft.world.level.block.ConcretePowderBlock && !world.isAir()
				&& (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(world.getBlock()).getPath() + "_powder")
						.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath()))
				|| (want.is(net.minecraft.world.level.block.Blocks.SPONGE) && world.is(net.minecraft.world.level.block.Blocks.WET_SPONGE))) {
			return Placement.Job.DONE;
		}
		// The build's own grass or flowers in the way of every look at a spot still to go in (see giveUp): out until it's in.
		if (!outFor.isEmpty()) {
			BlockPos forT = outFor.get(c);
			if (forT != null) {
				Placement.Job jt = jobAt(forT);
				if (jt == Placement.Job.PLACE || jt == Placement.Job.BREAK || jt == Placement.Job.ADJUST) {
					return world.isAir() || !world.getFluidState().isEmpty() ? Placement.Job.BLOCKED : Placement.Job.BREAK;
				}
			}
		}
		// Taken out to get at a spot it shut in (openUp): out, and left out until that one's in.
		if (!opened.isEmpty()) {
			BlockPos forT = opened.get(c);
			if (forT != null) {
				Placement.Job jt = jobAt(forT);
				if (jt == Placement.Job.PLACE || jt == Placement.Job.BREAK || jt == Placement.Job.ADJUST) {
					return world.isAir() || (world.canBeReplaced() && world.getFluidState().isEmpty()) ? Placement.Job.BLOCKED : Placement.Job.BREAK;
				}
			}
		}
		// (Our own temporary blocks are always ours to knock out again.)
		Placement.Job j = Placement.job(Terrain.level(), c, world, want, replace || temps.containsKey(c));
		// Grown there by itself after it was done (see grewThere), or from one of the build's own plants where it's to be
		// empty (grewFromBuild): as it is.
		if (j == Placement.Job.BREAK && !grown.isEmpty() && grown.contains(c.asLong()) && grewThere(world, want)) return Placement.Job.DONE;
		if (j == Placement.Job.BREAK && want.isAir() && grewFromBuild(c, world, HumanAutopilot.ticks())) return Placement.Job.DONE;
		// Right but for water in it, in a spot that holds a water source (water ran in while it was out): as good as in -
		// knocked out, the source stays, and it goes back in wet every time. (The Himeji castle's cobblestone slab by the
		// moat: "keeps coming undone", and every minute he flew 100 blocks down from the roof to put it right and back.)
		// (Or emptying the water out with a bucket, the ADJUST: by the moat it was wet again within 2 s, every time.)
		if ((j == Placement.Job.BREAK || j == Placement.Job.ADJUST) && world.getBlock() == want.getBlock()
				&& world.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
				&& world.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
				&& want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
				&& !want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
				&& Placement.matches(world.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false), want)) {
			return Placement.Job.DONE;
		}
		// Ours went in wrong twice already: leave it rather than knock it out for ever.
		if (j == Placement.Job.BREAK && world.getBlock() == want.getBlock() && redos.getOrDefault(c, 0) >= 2) return Placement.Job.BLOCKED;
		// Let go in any way round (no way to get it right): in is in.
		if (j == Placement.Job.BREAK && world.getBlock() == want.getBlock() && relaxed.contains(c)) return Placement.Job.DONE;
		// Never knock something out for a block that can't stay there (a dirt path under a trapdoor goes back to dirt).
		if (j == Placement.Job.BREAK && !want.isAir() && !Placement.fluid(want) && !want.canSurvive(Terrain.level(), c)) return Placement.Job.BLOCKED;
		if (j == Placement.Job.DONE) return j;
		// A door we opened to get through (the schematic has it shut): done for now, shut again at the end (closeDoor).
		if (!openedDoors.isEmpty() && world.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && world.getBlock() == want.getBlock()
				&& world.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && world.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false).equals(want)
				&& openedDoors.contains(world.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? c.below() : c)) {
			return Placement.Job.DONE;
		}
		// The ground under the build is ground: grass where the schematic has dirt (or the other way round) isn't worth
		// knocking out and putting back (one turns into the other by itself), and ground the build covers isn't seen.
		// The structure gets built instead (the user: "she's replacing grass block with dirt block"). Farmland and paths
		// still get made, holes in the ground filled, hills and trees in the way cleared.
		if (j == Placement.Job.BREAK && Placement.naturalGround(world) && Placement.naturalGround(want)
				&& ((Placement.soil(world) && Placement.soil(want)) || covered(c))) {
			return Placement.Job.DONE;
		}
		// The same for any block buried in the world's own ground under a solid block of the build: a foundation nobody
		// will see (willowspire's ground layers sink into the world: digging pits at y -62/-63 to put stone bricks and
		// concrete in, 285 "nowhere to place it from" and 31 left in 8 minutes). Rooms the schematic has dug out (air)
		// still get dug.
		if (j == Placement.Job.BREAK && worldGround(world) && !want.isAir() && !Placement.fluid(want)
				&& (covered(c) || groundStaysOver(c) || sealedInGround(c))) {
			return Placement.Job.DONE;
		}
		// A spot of the sunk ground layers already shut in (the floor over it, solid all round): nobody will see it and
		// there's no way to it: as it is. (The badlands house: he left the walls for one of these after another, failed,
		// went back: the flying back and forth.)
		if (j == Placement.Job.PLACE && clearFromY != Integer.MAX_VALUE && c.getY() < clearFromY && shutAway(c)) return Placement.Job.DONE;
		// (Soil anywhere - dirt, grass - shut in on all six sides: the same, nobody sees it and there's no face to
		// click. The fantasy build on a hillside: dirt of its ground under the world's grass and stone, "nowhere to place it
		// from" 3-4 times each; the user's game, 532 "no spot" in 80 minutes, most of them dirt and stone. It opens up
		// again by itself if a block beside it goes.)
		if (j == Placement.Job.PLACE && Placement.soil(want) && shutAway(c) && Terrain.state(c.below()).canOcclude()) return Placement.Job.DONE;
		// (And a few of them side by side, shut in together. The copper guard's bottom dirt under its grass, holes in
		// twos and threes: 48 times its own short grass taken out to get at one - no use, the grass block over it was in
		// the way - "flying around breaking grass".)
		if (j == Placement.Job.PLACE && Placement.soil(want) && sealedPocket(c)) return Placement.Job.DONE;
		// (And the build's bottom ground hollowed out under its cover - TNT through it, the top back in: soil there under
		// one of its blocks, with only more of that beside it. Nobody sees it; found one by one, "no look at it from
		// anywhere", a few thousand of them took all her time - 11 placed in 6 minutes after 400 TNT.)
		// (Not only soil: the walls' smooth stone and stone brick foundation blown out under them the same way, after a
		// restart - which forgets what's been given up on - looked at one by one again, "confused the minute he started".)
		if (j == Placement.Job.PLACE && !want.isAir() && !Placement.fluid(want) && clearFromY != Integer.MAX_VALUE && c.getY() < clearFromY + 3
				&& underCover(c)) {
			return Placement.Job.DONE;
		}
		if (j == Placement.Job.PLACE && !buriedSoil.isEmpty()) {
			Integer was = buriedSoil.get(c);
			if (was != null) {
				if (was == around(c)) return Placement.Job.DONE;
				buriedSoil.remove(c); // (something round it changed - TNT, a block in: one to try again)
			}
		}
		// A pocket in the world's own ground deep under the build (a cave the schematic's sunk ground layers run through),
		// under natural ground that stays: nobody sees it, and the only way in is digging down to it: as it is. (The
		// villager's six ground layers over a cave: he flew back and forth between it and the floor 20 blocks off,
		// "nowhere to place it from" at both, 3 blocks in a minute and a half.)
		if (j == Placement.Job.PLACE && clearFromY != Integer.MAX_VALUE && c.getY() + 1 < clearFromY && underGround(c)) return Placement.Job.DONE;
		// Snow or grass in the spot that hid every face to put the block against: knocked out first (a click on it).
		if (j == Placement.Job.PLACE && clearFirst.contains(c) && !world.isAir() && world.getFluidState().isEmpty()
				&& Placement.breakable(Terrain.level(), c, world)) return Placement.Job.BREAK;
		// Tried again and again: left alone until something next to it changes. (In now all the same - a helper put it in,
		// asked to (see sendNeed), or the server's finish: done.)
		if (cant.contains(c)) return j == Placement.Job.DONE ? Placement.Job.DONE : Placement.Job.BLOCKED;
		// Gone again as soon as it was in, twice, and what it stands on hasn't changed since: it won't stay - left for good,
		// not tried again whenever a block goes in beside it (that let it go round and round: the copper walls' short grass,
		// 601 goes in 17 minutes, the user's "breaking grass running back and forth endlessly").
		if (j == Placement.Job.PLACE && !vanished.isEmpty() && vanished.getOrDefault(c, 0) >= 2) {
			BlockState was = wontStayOn.get(c);
			if (was != null && was.equals(Terrain.state(c.below()))) return Placement.Job.BLOCKED;
		}
		// Put right twice already, and another block took its place again by itself (grass spreading onto dirt,
		// farmland drying out): the world's doing, a paste drifts the same way. Not ours to fight for ever.
		if (j == Placement.Job.BREAK && !want.isAir() && !world.isAir() && world.getBlock() != want.getBlock()
				&& fixes.getOrDefault(c, 0) >= 2) return Placement.Job.BLOCKED;
		// Can't stay there at all, everything round it done (pink petals on cobblestone: a paste puts them there, the
		// game won't let a player).
		if (j == Placement.Job.PLACE && !Placement.fluid(want) && !want.canSurvive(Terrain.level(), c) && aroundDone(c)) {
			return Placement.Job.BLOCKED;
		}
		// Can't stay there even with the whole build done round it (a torch over the schematic's own air, under a dirt
		// overhang - a paste leaves it floating): as it is now, not once all round it is done. (The fantasy build's torch
		// in your game: "nowhere to place it from" every few minutes, a flight over each time, as the dirt round it
		// changed.)
		if (j == Placement.Job.PLACE && !Placement.fluid(want) && !want.canSurvive(Terrain.level(), c) && neverStays(c, want)) {
			return Placement.Job.BLOCKED;
		}
		// Sand, gravel, powder over what the build has under it that it falls straight through (tall grass, air - a
		// paste leaves it hanging there): no way to make it stay. (The wizard tower's cyan powder on tall grass: put
		// back "first" after the TNT, "nowhere to place it from" again and again.)
		if (j == Placement.Job.PLACE && fallsThrough(c, want)) return Placement.Job.BLOCKED;
		return j;
	}

	/**
	 * The world's own ground over this spot stays: it's below where the clearing starts (the sunk ground layers), the
	 * schematic has nothing there, and the world has natural ground. (Willowspire's moss in a dip at y -62 under the
	 * world's grass at -61, which is left: 54 "nowhere to place it from".)
	 */
	private static boolean groundStaysOver(BlockPos c) {
		BlockPos u = c.above();
		BlockState above = plan.get(u);
		if ((above != null && !above.isAir()) || !worldGround(Terrain.state(u))) return false;
		if (u.getY() < clearFromY) return true;
		// Over the floor too, where the hill there isn't cleared (out at the edge of the build's ground plate the world's
		// hills stay): the medieval house's grass plate under the hillside at its edge, stone to swap for grass that
		// nobody sees, "nowhere to place it from" 19 times.
		return lookup != null && Terrain.state(u).canOcclude() && lookup.apply(u) == null;
	}

	/** The world's own ground: natural ground, and the badlands' terracotta (only where the world's side counts). */
	private static boolean worldGround(BlockState s) {
		return Placement.naturalGround(s) || s.is(net.minecraft.tags.BlockTags.TERRACOTTA);
	}

	/**
	 * Natural ground over it that stays (the schematic doesn't dig a room out there): right over it, or over the cave's
	 * air it's at the bottom of, below the floor. (Only the block right over it: a cave two or more high kept its
	 * bottom spots, "0 open spots", in the villager's ground on the third go.) Open up to the floor: a dip or a crater,
	 * filled.
	 */
	private static boolean underGround(BlockPos c) {
		for (int y = c.getY() + 1; y < clearFromY; y++) {
			BlockPos u = new BlockPos(c.getX(), y, c.getZ());
			BlockState wu = Terrain.state(u);
			if (wu.isAir() || !wu.getFluidState().isEmpty() || wu.canBeReplaced()) continue; // (the cave)
			if (!worldGround(wu) || !wu.canOcclude()) return false;
			BlockState pu = plan.get(u);
			return pu == null || !pu.isAir();
		}
		return false;
	}

	/** Solid blocks over it and on all four sides: shut away. */
	/**
	 * With the grass and flowers the looks at {@code t} ran into gone, would there be a spot to put it in from? Not when a
	 * solid block hides it as well: out for nothing then, and back in, over and over. (The copper guard's bottom dirt
	 * under its grass at the edge: "flying around breaking grass", the build's own and the world's, for minutes.)
	 */
	private boolean tuftsHideIt(BlockPos t, java.util.Collection<BlockPos> tufts) {
		LocalPlayer p = Minecraft.getInstance().player;
		Box b = box();
		if (p == null || b == null) return false;
		java.util.Set<BlockPos> out = new java.util.HashSet<>();
		for (BlockPos c : tufts) out.add(c.immutable());
		java.util.Set<BlockPos> wasAir = pretendAir;
		var wasHits = tuftHits;
		pretendAir = out;
		tuftHits = null;
		BlockPos spot;
		try {
			spot = standFor(p, b, t);
		} finally {
			pretendAir = wasAir;
			tuftHits = wasHits;
		}
		if (spot != null) return true;
		lastTuftHits = null;
		if (noUseTufts.add(t.immutable())) {
			HumanAutopilot.LOGGER.info("[schem] {} ({}): taking the grass round it out wouldn't help (a solid block hides it too): leaving the grass{}",
					t.toShortString(), plan.get(t), sealedPocket(t) ? "" : pocketWhyAt == null ? "; not covered" : "; " + pocketWhy());
		}
		return false;
	}

	/** Spots tuftsHideIt said no to (said once each). */
	private final java.util.Set<BlockPos> noUseTufts = new java.util.HashSet<>();

	/**
	 * Over it, up to 3 up: open spots and then a solid block of the build, in; round it: solid, or more of the build's
	 * blocks not in yet (the hollow it's in) - nothing open to the outside or to a room of the build, where it's seen from.
	 */
	private static boolean underCover(BlockPos c) {
		BlockPos q = c.above();
		boolean covered = false;
		for (int i = 0; i < 3; i++, q = q.above()) {
			BlockState in = Terrain.state(q);
			if (in.canOcclude()) {
				BlockState w = plan.get(q);
				// (Or the world's own ground over it where the build has its ground too - grass on the build's dirt, stone,
				// coarse dirt: the copper guard in a normal world, hundreds of its dirt spots under the hillside's own ground,
				// "nowhere to place it from" one by one, 30 targets in 10 s - "flying around confused".)
				covered = w != null && (Placement.matches(in, w) || (worldGround(in) && (Placement.soil(w) || Placement.naturalGround(w))));
				break;
			}
			if (!in.isAir() && !in.canBeReplaced()) return false; // (a slab, a fence: it may show past that)
			BlockState w = plan.get(q);
			if (w == null || w.isAir() || !w.canOcclude()) return false; // (open over it, to the air or a room: seen from there)
		}
		if (!covered) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = c.relative(d);
			if (Terrain.state(n).canOcclude()) continue;
			BlockState w = plan.get(n);
			if (w == null || w.isAir() || !w.canOcclude()) return false;
		}
		return true;
	}

	private static boolean shutAway(BlockPos c) {
		if (!Terrain.state(c.above()).canOcclude()) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) if (!Terrain.state(c.relative(d)).canOcclude()) return false;
		return true;
	}

	/**
	 * A spot with a solid block over it in a little pocket of open spots (up to 12) that all want soil, solid all round
	 * the pocket: nobody sees in, and there's no face to click in there. (It opens up again if a block round it goes.)
	 */
	private static boolean sealedPocket(BlockPos c) {
		pocketWhyAt = null;
		if (!Terrain.state(c.above()).canOcclude()) return false;
		java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> in = new java.util.HashSet<>();
		todo.add(c.immutable());
		in.add(c.immutable());
		while (!todo.isEmpty()) {
			BlockPos q = todo.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = q.relative(d);
				if (in.contains(n) || Terrain.state(n).canOcclude()) continue;
				BlockState w = plan.get(n);
				if (in.size() >= 32 || w == null || !Placement.soil(w)) {
					// (Open to more than soil, or a big hole: not sealed. Where, for tuftsHideIt's log line.)
					pocketWhyAt = n.immutable();
					pocketWhyN = in.size();
					return false;
				}
				in.add(n.immutable());
				todo.add(n.immutable());
			}
		}
		return true;
	}

	private static BlockPos pocketWhyAt;
	private static int pocketWhyN;

	/** Why the last spot sealedPocket looked at wasn't a sealed pocket, for a log line. */
	private static String pocketWhy() {
		if (pocketWhyAt == null) return null;
		BlockState w = plan == null ? null : plan.get(pocketWhyAt);
		return "not shut in: " + pocketWhyN + " open spots, then " + pocketWhyAt.toShortString() + " (" + Terrain.state(pocketWhyAt)
				+ ", the build has " + w + ")";
	}

	/**
	 * The build puts a solid block right on top of this spot: nobody will ever see what's in it. (Not while that block
	 * isn't in yet, at the top of the ground or above: the ground goes first now, open, and gets put right like a paste;
	 * the exact test's two cobblestones under the house's corners stayed grass.)
	 */
	private static boolean covered(BlockPos c) {
		// (Only in the ground: above the floor it's seen from the side once the hill round it is cleared. Any solid block
		// over it counted, the hill's own too: where a hill ran through the walls, its dirt was left in them, "cobblestone
		// is dirt" in the cottage's foot. And at the top ground layer, the build's own block over it, really in.)
		if (clearFromY != Integer.MAX_VALUE && c.getY() >= clearFromY) return false;
		BlockState above = plan.get(c.above());
		if (above == null || above.isAir() || !above.canOcclude()) return false;
		return c.getY() + 1 < clearFromY || Placement.matches(Terrain.state(c.above()), above);
	}

	/** The ground over {@code c} is in already: the block over it is the build's own soil or ground, solid, as it should be. */
	private static boolean underDoneGround(BlockPos c) {
		BlockPos u = c.above();
		BlockState pu = plan.get(u), wu = Terrain.state(u);
		return pu != null && !pu.isAir() && (Placement.soil(pu) || Placement.naturalGround(pu))
				&& (Placement.matches(wu, pu) || worldGround(wu)) && wu.canOcclude();
	}

	private static boolean fallsThrough(BlockPos c, BlockState want) {
		if (!(want.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return false;
		if (!net.minecraft.world.level.block.FallingBlock.isFree(Terrain.state(c.below()))) return false;
		BlockState under = plan.get(c.below());
		if (under == null && lookup != null) under = lookup.apply(c.below()); // (the schematic's own air there)
		if (under == null) return lookup == null && c.getY() > planBottomY; // (nothing of the build under it: fallsNow's pillar)
		return net.minecraft.world.level.block.FallingBlock.isFree(under);
	}

	/**
	 * The world's own ground sealed in: the build's own block over it already in, and on all four sides ground that
	 * stays (or the build's blocks, in). Nothing of it can be seen or clicked any more: as it is. (The black hole's
	 * pixel-art wall, 1 thick, its bottom row in the ground: the row over it went in first, and the dirt under it was
	 * "nowhere to place it from", over a hundred times.)
	 */
	private static boolean sealedInGround(BlockPos c) {
		BlockPos u = c.above();
		BlockState pu = plan.get(u);
		if (pu == null || pu.isAir() || !Placement.matches(Terrain.state(u), pu) || !Terrain.state(u).canOcclude()) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = c.relative(d);
			BlockState wn = Terrain.state(n);
			if (!wn.canOcclude()) return false;
			BlockState pn = plan.get(n);
			if (pn == null) {
				// Outside the build: the world's ground there stays only where nothing's to be cleared.
				BlockState sn = lookup != null ? lookup.apply(n) : null;
				if ((sn != null && sn.isAir()) || !worldGround(wn)) return false;
			} else if (pn.isAir()) {
				return false; // (to be cleared: it'll open up; a block of the build beside it, solid now, stays solid)
			}
		}
		return true;
	}

	/** Every spot of the build round {@code c} is as it will stay (done, or left as it is). */
	private static boolean aroundDone(BlockPos c) {
		for (Direction d : Direction.values()) {
			BlockPos n = c.relative(d);
			BlockState wn = plan.get(n);
			if (wn == null || cant.contains(n)) continue;
			// (One that can't stay where it is itself won't be what holds this one up: four petals in a row on
			// cobblestone each waited for the next.)
			if (!wn.isAir() && !Placement.fluid(wn) && !wn.canSurvive(Terrain.level(), n) && Terrain.state(n).isAir()) continue;
			Placement.Job j = Placement.job(Terrain.level(), n, Terrain.state(n), wn, replace || temps.containsKey(n));
			if (j != Placement.Job.DONE && j != Placement.Job.BLOCKED) return false;
		}
		return true;
	}

	/** Done spots of the build that grew something by themselves after (see grewThere): left as they are. */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet grown = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * What's in this spot grew there by itself: a vine crept down onto a spot the build has empty, bamboo, cane, kelp or a
	 * cactus a block taller, a sapling come up as a tree (its trunk where the sapling was, its leaves round it), a
	 * mushroom spread, a stem's pumpkin, snow fallen, water frozen, copper gone green, coral dead out of water. The
	 * world's doing - a paste grows the same - not damage to fly over and put right. (The Himeji castle's 10,391 vines and
	 * 195 saplings: "repairing 1 damaged spots first", a 70-block flight down from the roof and back, twice in 2 minutes
	 * with no TNT at all.) Only for a spot seen done before (remember): the world's own vines and trees in the way of
	 * the build still go.
	 */
	private static boolean grewThere(BlockState world, BlockState want) {
		net.minecraft.world.level.block.Block b = world.getBlock(), wb = want.getBlock();
		if (b == wb || world.isAir()) return false;
		if (b instanceof net.minecraft.world.level.block.WeatheringCopper wc && wb instanceof net.minecraft.world.level.block.WeatheringCopper ww) {
			return wc.getAge().ordinal() > ww.getAge().ordinal();
		}
		var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
		String id = reg.getKey(b).getPath(), wid = reg.getKey(wb).getPath();
		// (Coral dead out of water, concrete powder set by the water next to it, a sponge soaked: the same block further on.)
		if (id.equals("dead_" + wid) || (wid.endsWith("_concrete_powder") && id.equals(wid.substring(0, wid.length() - 7)))
				|| (id.equals("wet_sponge") && wid.equals("sponge"))) return true;
		boolean grows = b instanceof net.minecraft.world.level.block.VineBlock || b instanceof net.minecraft.world.level.block.GrowingPlantBlock
				|| b instanceof net.minecraft.world.level.block.SugarCaneBlock || b instanceof net.minecraft.world.level.block.CactusBlock
				|| b instanceof net.minecraft.world.level.block.BambooStalkBlock || b instanceof net.minecraft.world.level.block.BambooSaplingBlock
				|| b instanceof net.minecraft.world.level.block.ChorusPlantBlock || b instanceof net.minecraft.world.level.block.ChorusFlowerBlock
				|| b instanceof net.minecraft.world.level.block.MushroomBlock || b instanceof net.minecraft.world.level.block.HugeMushroomBlock
				|| b instanceof net.minecraft.world.level.block.CocoaBlock || b instanceof net.minecraft.world.level.block.BeehiveBlock
				|| b instanceof net.minecraft.world.level.block.MangroveRootsBlock || b instanceof net.minecraft.world.level.block.AmethystClusterBlock
				|| b instanceof net.minecraft.world.level.block.AttachedStemBlock || b instanceof net.minecraft.world.level.block.SnowLayerBlock
				|| world.is(net.minecraft.world.level.block.Blocks.PUMPKIN) || world.is(net.minecraft.world.level.block.Blocks.MELON)
				|| world.is(net.minecraft.tags.BlockTags.LOGS)
				|| (b instanceof net.minecraft.world.level.block.LeavesBlock
						&& world.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)
						&& !world.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT));
		if (b instanceof net.minecraft.world.level.block.IceBlock && Placement.fluid(want)) return true;
		if (!grows) return false;
		return want.isAir() || want.canBeReplaced() || wb instanceof net.minecraft.world.level.block.SaplingBlock
				|| wb instanceof net.minecraft.world.level.block.MangrovePropaguleBlock || wb instanceof net.minecraft.world.level.block.BambooSaplingBlock
				|| wb instanceof net.minecraft.world.level.block.StemBlock;
	}

	/** The build's saplings seen in (the tree one grows into is the build's own: grewFromBuild). */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet saplingsIn = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/** Spots the schematic has empty where one of the build's own plants grew (grewFromBuild), and ones that aren't (when). */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet grewIn = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap notGrewAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	/** What kind of growing thing this is, for following it back to where it grew from (see grewFromBuild), or null. */
	private static String plantKind(BlockState s) {
		net.minecraft.world.level.block.Block b = s.getBlock();
		if (s.is(net.minecraft.tags.BlockTags.LOGS) || b instanceof net.minecraft.world.level.block.SaplingBlock
				|| b instanceof net.minecraft.world.level.block.MangrovePropaguleBlock || b instanceof net.minecraft.world.level.block.MangroveRootsBlock
				|| b instanceof net.minecraft.world.level.block.BeehiveBlock || b instanceof net.minecraft.world.level.block.CocoaBlock
				|| (b instanceof net.minecraft.world.level.block.LeavesBlock
						&& s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)
						&& !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT))) return "tree";
		if (b instanceof net.minecraft.world.level.block.VineBlock) return "vine";
		if (b instanceof net.minecraft.world.level.block.ChorusPlantBlock || b instanceof net.minecraft.world.level.block.ChorusFlowerBlock) return "chorus";
		if (b instanceof net.minecraft.world.level.block.GrowingPlantBlock || b instanceof net.minecraft.world.level.block.SugarCaneBlock
				|| b instanceof net.minecraft.world.level.block.CactusBlock || b instanceof net.minecraft.world.level.block.BambooStalkBlock
				|| b instanceof net.minecraft.world.level.block.BambooSaplingBlock) {
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (id.endsWith("_plant")) id = id.substring(0, id.length() - 6);
			return id.equals("bamboo_sapling") ? "bamboo" : id;
		}
		return null;
	}

	/**
	 * A plant in a spot the schematic has empty that came from one of the build's own: a vine crept down from the
	 * build's vines, bamboo, cane or kelp grown up from the build's, a tree come up from its sapling (trunk and leaves).
	 * The world's doing, as in grewThere - not to be cleared. Followed through the same plant (up or down a stalk; any
	 * way through a vine, a tree) to a spot where the schematic has it (for a tree, its sapling). (The castle-top test:
	 * "off to 87, -36, 225 (40 away)", 90 blocks down from the roof for a vine that had crept a block down the wall.)
	 */
	private static boolean grewFromBuild(BlockPos c, BlockState world, long now) {
		String kind = plantKind(world);
		if (kind == null || lookup == null) return false;
		long k = c.asLong();
		if (grewIn.contains(k)) return true;
		long v = notGrewAt.getOrDefault(k, Long.MIN_VALUE);
		if (v != Long.MIN_VALUE && now - v < 600) return false;
		boolean tree = kind.equals("tree"), stalk = !tree && !kind.equals("vine") && !kind.equals("chorus");
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		q.add(c);
		seen.add(c);
		int cap = tree ? 160 : 40;
		boolean from = false;
		while (!q.isEmpty() && !from) {
			BlockPos n = q.poll();
			BlockState want = lookup.apply(n);
			// (A tree: from a sapling of ours that was in - not the world's own tree standing where the build has one.)
			if (want != null && !want.isAir() && (tree ? saplingsIn.contains(n.asLong()) : kind.equals(plantKind(want)))) {
				from = true;
				break;
			}
			if (seen.size() >= cap) continue;
			for (Direction d : Direction.values()) {
				if (stalk && d.getAxis() != Direction.Axis.Y) continue;
				BlockPos m = n.relative(d);
				if (seen.contains(m) || !kind.equals(plantKind(Terrain.state(m)))) continue;
				seen.add(m);
				q.add(m);
			}
		}
		if (from) grewIn.add(k);
		else notGrewAt.put(k, now);
		return from;
	}

	/** Spots worked out by neverStays: the tick it was worked out << 1, and 1 for "never". */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap neverAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	private static net.minecraft.world.level.LevelReader finalLevel;
	private static net.minecraft.client.multiplayer.ClientLevel finalLevelOf;

	/**
	 * {@code want} can't stay at {@code c} even once the build's done: what'll be under it (or behind it) then - the
	 * plan's blocks in, the schematic's air where it clears, the world's own blocks elsewhere. Worked out again a minute
	 * on (the world's ground round the build can change). (Not a mushroom: that's the light, which the roof changes.)
	 */
	private static boolean neverStays(BlockPos c, BlockState want) {
		if (want.getBlock() instanceof net.minecraft.world.level.block.MushroomBlock) return false;
		long k = c.asLong(), now = HumanAutopilot.ticks();
		long v = neverAt.getOrDefault(k, -1L);
		if (v >= 0 && now - (v >>> 1) < 1200) return (v & 1) != 0;
		boolean never;
		try {
			never = !want.canSurvive(finalLevel(), c);
		} catch (Throwable e) {
			never = false;
		}
		if (never && (v < 0 || (v & 1) == 0)) {
			HumanAutopilot.LOGGER.info("[schem] leaving {} ({}) as it is: it can't stay there even with the build done round it",
					c.toShortString(), want.getBlock().getName().getString());
		}
		neverAt.put(k, now << 1 | (never ? 1 : 0));
		return never;
	}

	/** What'll be at {@code p} once the build's done: the plan's block, the schematic's air where it clears, or the world's. */
	private static BlockState finalState(BlockPos p) {
		BlockState s = plan == null ? null : plan.get(p);
		if (s != null) return s;
		if (lookup != null && p.getY() >= clearFromY) {
			BlockState l = lookup.apply(p);
			if (l != null) return l;
		}
		return Terrain.state(p);
	}

	/** The level with the build done in it (finalState), for asking a block whether it can stay; the rest is the world's. */
	private static net.minecraft.world.level.LevelReader finalLevel() {
		net.minecraft.client.multiplayer.ClientLevel real = Terrain.level();
		if (finalLevel == null || finalLevelOf != real) {
			finalLevelOf = real;
			finalLevel = (net.minecraft.world.level.LevelReader) java.lang.reflect.Proxy.newProxyInstance(
					net.minecraft.world.level.LevelReader.class.getClassLoader(), new Class<?>[]{net.minecraft.world.level.LevelReader.class},
					(proxy, m, args) -> {
						Class<?>[] pt = m.getParameterTypes();
						if (pt.length == 1 && pt[0] == BlockPos.class) {
							if (m.getReturnType() == BlockState.class) return finalState((BlockPos) args[0]);
							if (m.getReturnType() == net.minecraft.world.level.material.FluidState.class) {
								return finalState((BlockPos) args[0]).getFluidState();
							}
						}
						if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
						try {
							return m.invoke(real, args);
						} catch (java.lang.reflect.InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}
		return finalLevel;
	}

	/** Why a spot of the build is left as it is (for the log). */
	private static String blockedWhy(BlockPos c) {
		BlockState want = plan.get(c), world = Terrain.state(c);
		if (cant.contains(c)) return "tried " + fails.getOrDefault(c, 0) + " times: " + failWhy.getOrDefault(c, "?");
		if (fixes.getOrDefault(c, 0) >= 2 && !world.isAir()) return "put right " + fixes.get(c) + " times, it keeps turning into " + world.getBlock().getName().getString() + " by itself";
		if (redos.getOrDefault(c, 0) >= 2) return "went in wrong twice";
		if (!want.isAir() && !Placement.fluid(want) && !want.canSurvive(Terrain.level(), c)) return "the game won't let " + want.getBlock().getName().getString() + " stay there";
		if (fallsThrough(c, want)) return want.getBlock().getName().getString() + " falls through the " + plan.get(c.below()).getBlock().getName().getString() + " under it";
		if (!world.isAir() && !Placement.breakable(Terrain.level(), c, world)) return "won't break the " + world.getBlock().getName().getString() + " there";
		if (!replace) return world.getBlock().getName().getString() + " there (Replace is off)";
		return "?";
	}

	/** The item the spot's next click needs (null: nothing in particular). */
	/** The blocks of the spots the last pick chose from (these layers, round here), counted. */
	private Map<Item, Integer> upcoming = Map.of();

	/**
	 * A block that's already in the build, in sight and in reach, is the quickest place to get another of it in creative:
	 * look at it and middle-click (pick block), like a builder does, instead of a trip to the menu (~2 s with the menu
	 * over the screen; the hospital's ground floor, ~40 kinds of block, spent a quarter of its time in there). The slot
	 * it goes into is chosen first (the game puts it into the one in hand when the hotbar's full). Null when there's
	 * none in sight, or it didn't work: then the menu.
	 */
	private BlockPos pickFrom;
	private Item pickItem;
	private int pickSlot = -1;
	private long pickSince, pickClickAt = -1;
	private final Map<Item, Long> pickFailed = new HashMap<>();
	public static long picksDone;

	private String pickFromWorld(LocalPlayer p, InputDriver in, AimController aim, Item item, long now) {
		Long no = pickFailed.get(item);
		if (no != null && no > now) return null;
		if (pickItem != item || pickFrom == null || Terrain.state(pickFrom).getBlock().asItem() != item) {
			pickItem = item;
			pickFrom = pickSource(p, item);
			pickSlot = -1;
			pickSince = now;
			pickClickAt = -1;
			if (pickFrom == null) {
				pickItem = null;
				pickFailed.put(item, now + 100); // none in sight (look again in a moment)
				return null;
			}
		}
		if (now - pickSince > 60) {
			HumanAutopilot.LOGGER.info("[schem] couldn't pick {} off the build at {}: the menu instead", name(item), pickFrom.toShortString());
			pickFailed.put(item, now + 1200);
			pickItem = null;
			pickFrom = null;
			return null;
		}
		if (pickSlot < 0) pickSlot = CreativePicker.get().slotToFill(p, needsNear(p));
		if (!in.selectHotbar(pickSlot)) return "picking " + name(item) + " off the build";
		Face f = interactFace(p, p.getEyePosition(), p.getViewVector(1f), pickFrom);
		if (f == null) {
			pickFrom = pickSource(p, item); // moved, or it went out of sight: another one, or the menu
			if (pickFrom == null) {
				pickItem = null;
				pickFailed.put(item, now + 100);
				return null;
			}
			return "picking " + name(item) + " off the build";
		}
		work(aim, f.point());
		HitResult hr = clickRay(p);
		if (pickClickAt >= 0 && now - pickClickAt > 10) pickClickAt = -1; // (didn't take: again)
		if (pickClickAt < 0 && hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(pickFrom)) {
			in.click(InputDriver.opts().keyPickItem);
			pickClickAt = now;
		}
		return "picking " + name(item) + " off the build";
	}

	/**
	 * On the way to {@code t}: its block in hand already, if it's in the hotbar. A player rolls the wheel while flying over,
	 * not after getting there ("taking out" was a fifth of the time in the fantasy building's crown: the wheel only
	 * started once the block was in reach).
	 */
	private void readyItem(LocalPlayer p, InputDriver in, BlockPos t) {
		if (plan == null || t == null) return;
		Item item = itemFor(t);
		if (item == null) return;
		int slot = Inv.hotbarSlot(p, s -> s.is(item));
		if (slot < 0 || slot == p.getInventory().getSelectedSlot()) return;
		CreativePicker.get().used(slot);
		in.selectHotbar(slot);
	}

	/** The block of this item in sight and in reach that's the smallest turn of the head away, or null. */
	private BlockPos pickSource(LocalPlayer p, Item item) {
		Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
		BlockPos e = BlockPos.containing(eye);
		BlockPos best = null;
		double bestTurn = Double.MAX_VALUE;
		for (BlockPos c : BlockPos.betweenClosed(e.offset(-4, -4, -4), e.offset(4, 4, 4))) {
			BlockState s = Terrain.state(c);
			if (s.isAir() || s.getBlock().asItem() != item) continue;
			Face f = interactFace(p, eye, look, c);
			if (f == null) continue;
			double turn = Math.acos(Math.clamp(look.dot(f.point().subtract(eye).normalize()), -1, 1));
			if (turn < bestTurn) {
				bestTurn = turn;
				best = c.immutable();
			}
		}
		return best;
	}

	/**
	 * How many of the spots coming up need each item: what's worth keeping in the hotbar. The last pick's choice (these
	 * layers, round here); else everything within 24 blocks. (Everything within 24 kept the glazed tiles the crown's
	 * other side wanted, and the jungle log, brown wool and brown concrete wanted right now took turns in one slot.)
	 */
	private Map<Item, Integer> needsNear(LocalPlayer p) {
		Map<Item, Integer> n = new HashMap<>();
		for (BlockPos h : holes) {
			double dx = h.getX() + 0.5 - p.getX(), dz = h.getZ() + 0.5 - p.getZ();
			if (dx * dx + dz * dz > 24 * 24) continue;
			Item it = itemFor(h);
			if (it != null) n.merge(it, 1, Integer::sum);
		}
		// What's to be done next counts most, but what the rest round here needs still counts: with only the next few
		// counted, the build's main blocks went out of the hotbar for a lantern and came back a minute later (the
		// cottage: spruce stairs fetched 7 times in 8 minutes, spruce slabs and dirt 6, 36 trips to the menu).
		for (var e : upcoming.entrySet()) n.merge(e.getKey(), e.getValue() * 4, Integer::sum);
		// And what the build still needs all told, a quarter each: the blocks it's made of stay in hand, the one-offs
		// (a furnace, a smoker, a crafting table for the room in hand) take the slots that turn over. (With the room's
		// one-offs fetched "while the menu's open", spruce trapdoors went out and back 14 times in 15 minutes.)
		long now = HumanAutopilot.ticks();
		if (now - allNeedAt > 100) {
			allNeedAt = now;
			allNeed = new HashMap<>();
			for (BlockPos h : holes) {
				// (The block it wants, without looking at the world: tens of thousands of holes on a big build.)
				BlockState want = plan.get(h);
				if (want == null || want.isAir()) continue;
				Item it = Placement.placeItem(want);
				if (it != null) allNeed.merge(it, 1, Integer::sum);
			}
		}
		// (At most a few, though: on a big build the whole window's counts - hundreds - outweighed everything round us, and
		// the hotbar held what the build needs most somewhere, not what's needed here: the fantasy build's stone brick
		// slabs, trapdoors and stairs in use here pushed out and fetched back again and again.)
		for (var e : allNeed.entrySet()) n.merge(e.getKey(), Math.min(6, e.getValue() / 4), Integer::sum);
		return n;
	}

	private Map<Item, Integer> allNeed = Map.of();
	private long allNeedAt = -1000;

	private static Item itemFor(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null) return null;
		return switch (jobAt(h)) {
			case PLACE -> Placement.placeItem(want);
			case ADJUST -> {
				var a = Placement.adjust(Terrain.state(h), want);
				yield a == null ? null : a.item();
			}
			default -> null;
		};
	}

	/** Clicking there right now puts the planned block into {@code t} the right way round (for us and the server). */
	private boolean clickPlaces(LocalPlayer p, BlockHitResult hit, Item item, BlockPos t) {
		BlockState want = plan.get(t);
		if (want == null) return false;
		if (Placement.fluid(want)) {
			// A bucket: the water lands next to the face we click, unless that block takes it in itself.
			BlockPos n = hit.getBlockPos();
			if (n.equals(t) || Placement.takesWater(p, n, Terrain.state(n), want) || !n.relative(hit.getDirection()).equals(t)) return false;
			BlockState ts = Terrain.state(t);
			return ts.isAir() || ts.canBeReplaced();
		}
		BlockState shape = Placement.placeShape(want);
		boolean any = relaxed.contains(t);
		if (!Placement.sameWay(Placement.predict(p, hit, item, t, p.getYRot(), p.getXRot()), shape, any)) return false;
		// The server decides the facing with the angles we last sent it (a tick old): those have to agree too.
		var acc = (dev.humanautopilot.mixin.LocalPlayerAccessor) p;
		return Placement.sameWay(Placement.predict(p, hit, item, t, acc.humanautopilot$yRotLast(), acc.humanautopilot$xRotLast()), shape, any);
	}

	private static boolean solid(BlockPos c) {
		if (!pretendAir.isEmpty() && pretendAir.contains(c)) return false;
		if (!pretendSolid.isEmpty() && pretendSolid.contains(c)) return true;
		BlockState s = Terrain.state(c);
		if (plan != null && handDoor(s)) return false; // a doorway: a way through, not something to land on or hit
		return !s.isAir() && !s.canBeReplaced() && !s.getCollisionShape(Terrain.level(), c).isEmpty();
	}

	private void scan(Box b, long now) {
		holes.clear();
		if (plan != null) {
			scanPlan(now);
			return;
		}
		for (int y = b.minY(); y <= b.maxY(); y++) {
			for (int x = b.minX(); x <= b.maxX(); x++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					if (!Terrain.loaded(x, z)) continue;
					BlockPos c = new BlockPos(x, y, z);
					if (hole(c)) holes.add(c);
				}
			}
		}
		int before = holeCount;
		holeCount = holes.size();
		scannedAt = now;
		bad.values().removeIf(until -> until < now);
		// A chunk of the box just vanished (TNT, a win wipe). No pause and no dropping what we're doing: with TNT
		// raining in, a new blast every scan kept restarting a pause and it never filled anything.
		if (before >= 0 && holeCount - before >= 6) {
			HumanAutopilot.LOGGER.info("[box] {} new holes at once ({} now)", holeCount - before, holeCount);
		}
	}

	/** Every spot of this layer has been seen done (a spot never looked at, or seen as a hole, isn't in seenDone). */
	private boolean layerFinished(List<BlockPos> layer, boolean skipFloating) {
		for (BlockPos c : layer) {
			long k = c.asLong();
			if (seenDone.contains(k)) continue;
			// A spot hanging in the air with nothing to click on yet (it goes in once something next to it is in, maybe
			// higher up) doesn't hold the layer open: one such block at the bottom kept the window there and the
			// statue stuck 24 layers up, everything above out of reach.
			var hs = holesByLayer.get(c.getY());
			if (skipFloating && hs != null && hs.contains(k)) {
				// (Looked at once in 2 s: a layer's thousands of buried spots under a hill, checked every scan, would be a hitch.)
				long now = HumanAutopilot.ticks(), at = layerSkipAt.get(k);
				if (at != Long.MIN_VALUE && now - at < 40) continue;
				// (Nor one with a full block on every side: nothing of it can be seen, it's never picked till something next to
				// it opens up - and it kept the window down there for good. The count pass brings the window back down for it
				// then.)
				if (floating(c) || buriedHole(c)) {
					if (layerSkipAt.size() > 200_000) layerSkipAt.clear();
					layerSkipAt.put(k, now);
					continue;
				}
			}
			return false;
		}
		return true;
	}

	/** A spot to fill with nothing next to it to click against (and loaded, so that's really so). */
	private boolean floating(BlockPos c) {
		return Terrain.loaded(c.getX(), c.getZ()) && jobAt(c) == Placement.Job.PLACE && !workable(c);
	}

	/** Look at a spot of the build (if close enough to trust what we see) and remember what it needs. */
	private void look(BlockPos c, double px, double pz, double trust) {
		double dx = c.getX() + 0.5 - px, dz = c.getZ() + 0.5 - pz;
		if (dx * dx + dz * dz > trust * trust || !Terrain.loaded(c.getX(), c.getZ())) return;
		remember(c, jobAt(c));
	}

	private void remember(BlockPos c, Placement.Job j) {
		long k = c.asLong();
		boolean wasBuilt = seenBuilt.contains(k);
		// Done before, and what's there now grew by itself (see grewThere): done still - not damage to fly over for.
		if (j == Placement.Job.BREAK && wasBuilt && plan != null && !temps.containsKey(c) && !opened.containsKey(c)) {
			BlockState want = plan.get(c);
			BlockState w = Terrain.state(c);
			if (want != null && grewThere(w, want)) {
				if (grown.add(k) && (grown.size() <= 20 || grown.size() % 100 == 0)) {
					HumanAutopilot.LOGGER.info("[schem] {} grew {} by itself (the build has {} there): left as it is ({} spots so far)",
							c.toShortString(), w.getBlock().getName().getString(), want.getBlock().getName().getString(), grown.size());
				}
				j = Placement.Job.DONE;
			}
		}
		boolean isHole = j == Placement.Job.PLACE || j == Placement.Job.BREAK || j == Placement.Job.ADJUST;
		if (j == Placement.Job.DONE) {
			seenBuilt.add(k);
			BlockState wantS = plan != null ? plan.get(c) : null;
			if (wantS != null && (wantS.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock
					|| wantS.getBlock() instanceof net.minecraft.world.level.block.MangrovePropaguleBlock)) saplingsIn.add(k);
		}
		// (Blocked - what it hangs on blown out with it, a sign without its wall - it still was in: forgotten, it came
		// back as an ordinary hole once the wall was, a decoration for the very end. The TNT test's 3 wall signs.)
		else if (j != Placement.Job.BLOCKED || opened.containsKey(c)) seenBuilt.remove(k);
		if (isHole) {
			// Seen built before, a hole now: damage (TNT, someone breaking it). Fixed first, wherever it is. (Only if it
			// really was in: a trapdoor given up on counted as "not to do", and each time it got another go it came out
			// as damage, first before everything, for minutes; nor our own temporary block coming out again.)
			if (wasBuilt && !temps.containsKey(c) && !opened.containsKey(c)) {
				// Put back and gone again within a minute, a third time (water washing a plant off, a crop trampled):
				// not damage to fix first, and not straight away (the castle's short grass: back ~100 times).
				long now = HumanAutopilot.ticks();
				long[] u = undone.computeIfAbsent(k, x -> new long[]{0, now, 0});
				if (now - u[1] > 1200) {
					u[0] = 0;
					u[1] = now;
				}
				// (Blown out by TNT that just went off: damage, every time - not "keeps coming undone". The user's stream:
				// viewers summoning TNT by the dozen on the same corner, and after the third blast a minute those spots
				// were left for 30 s, holes in the walls on camera.)
				boolean blast = nearBlast(c, now);
				if (!blast && ++u[0] >= 3) {
					damaged.remove(k);
					// (A second spell of it: for 5 minutes, not 30 s - and see below.)
					if (u[0] == 3) u[2]++;
					bad.put(c, now + (u[2] >= 2 ? 6000 : 600));
					failWhy.put(c, "it keeps coming undone");
					if (u[0] == 3) HumanAutopilot.LOGGER.info("[schem] {} ({}) keeps coming undone (now {}): leaving it a while",
							c.toShortString(), plan.get(c), Terrain.state(c));
				} else if (!blast && plan.get(c) != null && Placement.naturalGround(plan.get(c))) {
					// (Dirt, grass, stone of the ground gone with no TNT: her own tree-clearing in a normal world, not damage to drop
					// everything for - the copper guard's 54 "damaged" ground spots under the trees had her fly 100 blocks across
					// and back, "flying confused". A hole like the rest.)
				} else if (!blast && u[2] >= 2) {
					// (Come undone in two spells already - water running at it, something else's doing: a hole like the rest
					// now, not damage to drop everything for. A slab by the castle's moat had him fly 100 blocks down from the
					// roof and back every minute.)
				} else {
					// (Below the layers the window has now: it comes down for it. Blown out of the ground under the floor
					// over a crater, "floating", it never did: the wizard tower's 4 spots at y 71 were still out after 5
					// minutes, "repairing 5 damaged spots" with none of them in the window.)
					if (damaged.add(k) && c.getY() < scanFromY) scanFromY = c.getY();
				}
			}
			seenDone.remove(k);
			holesByLayer.computeIfAbsent(c.getY(), y -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(k);
		} else {
			seenDone.add(k);
			damaged.remove(k);
			var set = holesByLayer.get(c.getY());
			if (set != null) set.remove(k);
		}
	}

	/** TNT near the build: remember where each one is; when one's gone (it went off), look its blast area over. */
	/** TNT that went off by the build lately: where and when (see nearBlast). */
	private final List<long[]> recentBlasts = new ArrayList<>();

	/** Within reach of a TNT blast in the last 5 s: what went here was blown out, not undone by the world. */
	private boolean nearBlast(BlockPos c, long now) {
		if (recentBlasts.isEmpty()) return false;
		recentBlasts.removeIf(b -> now - b[1] > 100);
		for (long[] b : recentBlasts) {
			BlockPos o = BlockPos.of(b[0]);
			if (o.distSqr(c) <= 64) return true;
		}
		return false;
	}

	private void watchTnt(long now) {
		var level = Minecraft.getInstance().level;
		if (level == null || planBox == null) return;
		java.util.Set<Integer> alive = new java.util.HashSet<>();
		for (var e : level.entitiesForRendering()) {
			if (!(e instanceof net.minecraft.world.entity.item.PrimedTnt) && !(e instanceof net.minecraft.world.entity.vehicle.minecart.MinecartTNT)) continue;
			Vec3 q = e.position();
			if (q.x < planBox.minX() - 8 || q.x > planBox.maxX() + 9 || q.z < planBox.minZ() - 8 || q.z > planBox.maxZ() + 9
					|| q.y < planBox.minY() - 8 || q.y > planBox.maxY() + 9) continue;
			alive.add(e.getId());
			tntSeen.put(e.getId(), q);
		}
		for (var it = tntSeen.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (alive.contains(e.getKey())) continue;
			Vec3 q = e.getValue();
			recentBlasts.add(new long[]{BlockPos.containing(q).asLong(), now});
			// (The blocks it broke come in a moment after: look now, and again shortly.)
			blastChecks.add(new long[]{BlockPos.containing(q).asLong(), now + 2});
			blastChecks.add(new long[]{BlockPos.containing(q).asLong(), now + 12});
			it.remove();
		}
	}

	private void blastLook(long now, double px, double pz, double trust) {
		for (var it = blastChecks.iterator(); it.hasNext(); ) {
			long[] bc = it.next();
			if (bc[1] > now) continue;
			it.remove();
			BlockPos o = BlockPos.of(bc[0]);
			int r = 7;
			for (int x = -r; x <= r; x++) {
				for (int y = -r; y <= r; y++) {
					for (int z = -r; z <= r; z++) {
						if (x * x + y * y + z * z > r * r) continue;
						BlockPos c = o.offset(x, y, z);
						BlockState w = plan.get(c);
						if (w == null || w.isAir()) continue;
						// Everything round it is different now: what was given up on here gets a fresh go (TNT damage
						// given up on stayed out for good).
						if (cant.remove(c) | fails.remove(c) != null) bad.remove(c);
						nowhere.remove(c);
						scaffoldTried.remove(c);
						look(c, px, pz, trust);
						if (c.getY() < scanFromY && hole(c) && !floating(c)) scanFromY = c.getY(); // down to it straight away
					}
				}
			}
		}
	}

	private void scanPlan(long now) {
		// The build's own grass taken out for a spot it hid (see giveUp): back to a hole of its own once that spot's in
		// (or after a minute without it: it goes back anyway).
		if (!outFor.isEmpty()) {
			for (var it = outFor.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				Placement.Job jt = jobAt(e.getValue());
				boolean waiting = jt == Placement.Job.PLACE || jt == Placement.Job.BREAK || jt == Placement.Job.ADJUST;
				if (waiting && now - outForAt.getOrDefault(e.getKey(), now) < 1200) continue;
				it.remove();
				outForAt.remove(e.getKey());
				remember(e.getKey(), jobAt(e.getKey()));
			}
		}
		var me = Minecraft.getInstance().player;
		double px = me == null ? 0 : me.getX(), pz = me == null ? 0 : me.getZ();
		int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
		double trust = Math.max(24, Math.min(56, view * 16 - 24));
		// Progress (done / total): up to 20k blocks per scan, the count published after each full pass. Parts too far
		// to see count as they were last seen (the number jumped about as chunks came and went).
		// (TNT first: a blast this tick is known before its holes are looked at, so they count as damage - see nearBlast.)
		watchTnt(now);
		long cs0 = costStart();
		long countUntil = System.nanoTime() + 1_500_000;
		for (int i = 0; i < planList.size() && (i < 200 || System.nanoTime() < countUntil); i++) {
			if (countCursor >= planList.size()) {
				planDone = countDone;
				planBlocked = countBlocked;
				planCounted = true;
				if (planAlready < 0) planAlready = planDone;
				countPassDone();
				countCursor = countDone = countBlocked = 0;
			}
			BlockPos c = planList.get(countCursor++);
			if (support.contains(c)) continue;
			BlockState want0 = plan.get(c);
			if (want0 == null || want0.isAir() || temps.containsKey(c)) continue; // clearing and scaffolding aren't counted
			double cdx = c.getX() + 0.5 - px, cdz = c.getZ() + 0.5 - pz;
			// (Void air: a stand-in chunk, nothing known there - as last seen, like one out of sight.)
			if (!Terrain.loaded(c.getX(), c.getZ()) || Terrain.state(c).is(net.minecraft.world.level.block.Blocks.VOID_AIR)) {
				cMemAll++;
				if (seenBuilt.contains(c.asLong())) {
					countDone++;
					cMemDone++;
				}
				continue;
			}
			if (cdx * cdx + cdz * cdz > trust * trust) {
				// (Too far to go by for the work, but loaded: counted as it is, for the progress only - after a relog
				// nothing far was "seen" yet, and the bar started far too low.)
				// (And remembered as built: once it's out of sight it still counts. Counted only while loaded, the copper
				// walls' bar - 208 across - went down 9000 in 4 minutes as he flew about and the far side unloaded, with
				// 60 blocks a minute going in: "it keeps taking damage so the percentage keeps going down or up".)
				// (Out again over there - TNT on the companion's side: not built any more, so it doesn't count once it's out of
				// sight either; it's a hole like any other when we get there.)
				// (A hole from out here that was seen built: not taken off the "built" list from this far - only up close.
				// Taken off from out here, about 13,000 of them came and went between one pass and the next.)
				Placement.Job jf = jobAt(c);
				cFarAll++;
				if (jf == Placement.Job.DONE) {
					countDone++;
					cFarDone++;
					seenBuilt.add(c.asLong());
				} else if ((jf == Placement.Job.PLACE || jf == Placement.Job.BREAK || jf == Placement.Job.ADJUST) && seenBuilt.contains(c.asLong())) {
					cFarWasBuilt++;
					if (cFarWasBuilt <= 3) {
						if (cFarSample.length() > 0) cFarSample.append("; ");
						cFarSample.append(c.toShortString()).append(' ').append(jf).append(" has ").append(Terrain.state(c)).append(" wants ")
								.append(want0).append(String.format(java.util.Locale.ROOT, " %.0f away", Math.sqrt(cdx * cdx + cdz * cdz)));
					}
					countDone++;
				}
				continue;
			}
			Placement.Job j = jobAt(c);
			boolean built = j == Placement.Job.DONE;
			remember(c, j);
			cNearAll++;
			if (built) {
				countDone++;
				cNearDone++;
			}
			else if (j == Placement.Job.BLOCKED) countBlocked++;
			else if (!floating(c) && (j != Placement.Job.PLACE || openFace(c))) passLowest = Math.min(passLowest, c.getY());
		}
		if (countCursor >= planList.size()) {
			planDone = countDone;
			planBlocked = countBlocked;
			planCounted = true;
			if (planAlready < 0) planAlready = planDone;
			countPassDone();
			countCursor = countDone = countBlocked = 0;
			if (passLowest < scanFromY) {
				// A hole in a layer we'd finished (TNT): go back down for it, bottom first as always.
				HumanAutopilot.LOGGER.info("[schem] something's missing down at y {}: going back for it", passLowest);
				scanFromY = passLowest;
			}
			passLowest = Integer.MAX_VALUE;
		}
		costEnd("s.count", cs0);
		cs0 = costStart();
		blastLook(now, px, pz, trust);
		costEnd("s.blast", cs0);
		cs0 = costStart();
		// The work: the lowest layer that still has empty spots, plus a couple above it (some blocks there are what
		// the lower ones hang from). Finished layers drop out and aren't looked at again.
		// With a memory of what each spot last looked like (a hole, or done = in seenDone): everything round us is
		// looked at again every scan (a block we've just put in must count at once), the rest of the window a few
		// thousand spots a scan, in turn. (Looking at all 75k spots of a big build's window every quarter second was
		// 30 ms ticks: the hitching the user saw as a jittery camera.)
		holes.clear();
		boolean found = false;
		int layers = 0;
		List<Integer> win = new ArrayList<>();
		// (First with spots hanging in the air not holding a layer open; if that leaves nothing at all, with them:
		// everything blown away down to a pit is all hanging in the air, and then a pillar goes up to build from.)
		for (int pass = 0; pass < 2 && !found; pass++) {
			layers = 0;
			win.clear();
			for (var e : planLayers.tailMap(scanFromY, true).entrySet()) {
				if (found && layers > extraLayers) break;
				if (!found) {
					if (layerFinished(e.getValue(), pass == 0)) continue;
					// (Keeping its top when the lowest open layer moves up: the outside may be well over it.)
					if (activeY != Integer.MIN_VALUE && e.getKey() != activeY) extraLayers = Math.max(4, activeY + extraLayers - e.getKey());
					activeY = e.getKey();
					found = true;
				}
				win.add(e.getKey());
				layers++;
			}
		}
		// On the ground (under the floor), the hill over the floor that's to go too: cleared round us as we go, from its
		// top down, alongside the dips. (The window stopped at the floor, so the hill waited for every dip on the site,
		// and the ground under it waited for the hill.)
		if (found && lookup != null && clearFromY != Integer.MAX_VALUE && activeY < clearFromY && planBox != null) {
			int reachUp = Math.min(planBox.maxY(), clearFromY + 10) - activeY;
			if (extraLayers < reachUp) {
				extraLayers = reachUp;
				clearScanAt = -100;
			}
		}
		if (found) {
			costEnd("s.window", cs0);
			cs0 = costStart();
			int size = 0;
			for (int y : win) size += planLayers.get(y).size();
			if (!win.equals(winLayers) || size != winCells.size()) {
				winLayers = new ArrayList<>(win);
				winCells.clear();
				for (int y : win) winCells.addAll(planLayers.get(y));
				winCursor = 0;
			}
			costEnd("s.cells", cs0);
			cs0 = costStart();
			// Round us, every scan (the layers in reach up and down: the window can be the whole height of a castle).
			// (Smaller round a huge window: the Himeji castle's solid base had every spot of 25x25x21 in the plan - 13k
			// looks 4 times a second, 5-8 ms a scan. In reach is what has to be fresh; the rest goes round in turn below.)
			boolean big = winCells.size() > 50_000;
			int r = big ? 8 : 12, ry = big ? 6 : 10, mx = Mth.floor(px), mz = Mth.floor(pz), my = me == null ? 0 : Mth.floor(me.getY());
			for (int y : win) {
				if (Math.abs(y - my) > ry) continue;
				for (int x = mx - r; x <= mx + r; x++) {
					for (int z = mz - r; z <= mz + r; z++) {
						BlockPos c = new BlockPos(x, y, z);
						if (plan.containsKey(c)) look(c, px, pz, trust);
					}
				}
			}
			costEnd("s.round", cs0);
			cs0 = costStart();
			// The rest, in turn.
			int budget = Math.min(winCells.size(), 2500);
			for (int i = 0; i < budget; i++) {
				if (winCursor >= winCells.size()) winCursor = 0;
				BlockPos c = winCells.get(winCursor++);
				if (Math.abs(c.getX() - mx) <= r && Math.abs(c.getZ() - mz) <= r && Math.abs(c.getY() - my) <= ry) continue; // (just done)
				look(c, px, pz, trust);
			}
			costEnd("s.rest", cs0);
			cs0 = costStart();
			// What's to do: every spot the memory has as a hole, lowest layer first. (A huge window's only once a second:
			// the Himeji castle's 280k spots four times a second were half a second of every 30 and a heap of garbage. What
			// the pick needs fresh round us it looks up in the memory itself: holesNear.)
			if (holesFull.size() > 20000 && win.equals(holesFullWin) && now - holesFullAt < 20) {
				holes.addAll(holesFull);
			} else {
				for (int y : win) {
					var set = holesByLayer.get(y);
					if (set == null) continue;
					for (var it = set.iterator(); it.hasNext(); ) holes.add(BlockPos.of(it.nextLong()));
				}
				holesFull.clear();
				holesFull.addAll(holes);
				holesFullWin = new ArrayList<>(win);
				holesFullAt = now;
			}
			// Damage outside the window too: it's fixed first, wherever it is. (Blown out of the ground under the floor,
			// over the crater, it held no layer open: the wizard tower's 4 spots at y 71 were never in the window.)
			if (!damaged.isEmpty() && !win.isEmpty()) {
				int wLo = win.get(0), wHi = win.get(win.size() - 1);
				boolean addedDmg = false;
				for (long k : damaged) {
					int y = BlockPos.getY(k);
					if (y >= wLo && y <= wHi) continue;
					BlockPos c = BlockPos.of(k);
					if (hole(c)) {
						holes.add(c);
						addedDmg = true;
					}
				}
				if (addedDmg) holes.sort(java.util.Comparator.comparingInt(BlockPos::getY));
			}
			costEnd("s.holes", cs0);
			cs0 = costStart();
			// Nothing near to do: somewhere to go (a part of the bottom layer not seen done yet, too far to see).
			if (holes.isEmpty() || now - farGoalAt > 100) {
				farGoalAt = now;
				farGoal = null;
				double farD = Double.MAX_VALUE;
				for (BlockPos c : planLayers.get(activeY)) {
					if (seenDone.contains(c.asLong())) continue;
					double dx = c.getX() + 0.5 - px, dz = c.getZ() + 0.5 - pz, d2 = dx * dx + dz * dz;
					if ((d2 > trust * trust || !Terrain.loaded(c.getX(), c.getZ())) && d2 < farD) {
						farD = d2;
						farGoal = c;
					}
				}
			}
			costEnd("s.far", cs0);
		} else {
			winLayers = List.of();
			winCells.clear();
			farGoal = null;
		}
		cs0 = costStart();
		// Exact mode: what's in the way where the schematic is empty (tall grass in the house, a stray block), round us.
		if (lookup != null && replace && me != null && found && planBox != null && now - clearScanAt >= 20) {
			clearScanAt = now;
			List<BlockPos> clear = new ArrayList<>();
			// The schematic's whole box, its empty margin round the build too: a paste clears that as well (the cottage's
			// box has 2-4 empty blocks round the house and 5 over its roof; two spruces stood in that margin, their trunks
			// and leaves were still there at the end - only the blocks' own box was being cleared).
			int[] sb = dev.humanautopilot.schematic.SchematicMode.get().builtBox();
			int cbx0 = planBox.minX(), cbx1 = planBox.maxX(), cbz0 = planBox.minZ(), cbz1 = planBox.maxZ(), cbTop = planBox.maxY();
			if (sb != null) {
				cbx0 = Math.min(cbx0, sb[0]);
				cbx1 = Math.max(cbx1, sb[0] + sb[3] - 1);
				cbz0 = Math.min(cbz0, sb[2]);
				cbz1 = Math.max(cbz1, sb[2] + sb[5] - 1);
				cbTop = Math.max(cbTop, sb[1] + sb[4] - 1);
			}
			int r = 12, x0 = Math.max(cbx0, Mth.floor(px) - r), x1 = Math.min(cbx1, Mth.floor(px) + r);
			int z0 = Math.max(cbz0, Mth.floor(pz) - r), z1 = Math.min(cbz1, Mth.floor(pz) + r);
			// Up to the top of the ground round here, not just the window: a hill over the build has to go from its top
			// down, and its top was over the window (the user's castle in a hillside: its walls 24 under the hill's grass,
			// the clearing stopped 10 over the floor, and he flew up 30 and down 30 at the buried walls for 2.5 minutes).
			int clearTop = activeY + extraLayers;
			for (int x = x0; x <= x1; x += 2) {
				for (int z = z0; z <= z1; z += 2) {
					if (!Terrain.loaded(x, z)) continue;
					// (Over the build's box the ground isn't the build's business; up to the box's top it is.)
					int g = Math.min(cbTop, Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
					if (g <= clearTop) continue;
					// (Only the world's own ground over spots the build has empty - not the build's own tower going up.)
					BlockState wantTop = lookup.apply(new BlockPos(x, g, z));
					if (wantTop != null && wantTop.isAir() && !Terrain.state(new BlockPos(x, g, z)).isAir()) clearTop = g;
				}
			}
			// (And the window with it: what's cleared over the window's top dropped out of the holes at the next scan.)
			// (To the schematic's own top, not the blocks' - with its empty layers over the roof cleared too, the ones up
			// there were out of the window, never holes, and everything under them waited on them: the cottage on a
			// mountaintop, its window 168..180 and the ground to clear up to 185.)
			if (clearTop > activeY + extraLayers) {
				extraLayers = Math.min(cbTop, clearTop) - activeY;
				if (now - hillLogAt > 600) {
					hillLogAt = now;
					HumanAutopilot.LOGGER.info("[schem] ground over the build up to y {} round here: clearing it from the top down (window {}..{})",
							clearTop, activeY, activeY + extraLayers);
				}
			}
			// (A tree's leaves left to fall by themselves, and the logs to be knocked out that hold them up: see below.)
			List<BlockPos> leafWait = new ArrayList<>(), logsGoing = new ArrayList<>();
			// (Each column only up to its highest block: over that it's open air, nothing to clear. The whole window's
			// height in a 25x25 square every second was 55k spots and 22-36 ms ticks over the Himeji castle's base.)
			// (Nothing of the box round us - out over the side to look at the build: no columns. A negative size here
			// crashed the game, the fantasy build's step back to look at it.)
			int[] colTop = new int[Math.max(0, x1 - x0 + 1) * Math.max(0, z1 - z0 + 1)];
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					// (Not loaded: nothing of that column, as before.)
					colTop[(x - x0) * (z1 - z0 + 1) + (z - z0)] = Terrain.loaded(x, z)
							? Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1 : Integer.MIN_VALUE;
				}
			}
			// (Air first, the plan after: most of a big build's insides are rooms, and the plan's map is 958k spots over the
			// Himeji castle - a lookup in it for every spot of 110 layers round us was ~27 ms a scan, every second.)
			BlockPos.MutableBlockPos cm = new BlockPos.MutableBlockPos();
			// Far under us (a tall build's lower storeys, done long ago - or never seen, the castle-top test's window
			// from y -61 with the work at 50): 16 layers of it a scan, in turn; round us every scan. (All 110 layers
			// round us were ~22 ms a scan, every second.)
			int yLo = Math.max(activeY, clearFromY), nearLo = Math.max(yLo, Mth.floor(me.getY()) - 12), farSlab = -1;
			if (nearLo - yLo > 16) {
				clearSlab = (clearSlab + 1) % ((nearLo - yLo + 15) / 16);
				farSlab = clearSlab;
			}
			for (int y = yLo; y <= Math.min(cbTop, clearTop); y++) {
				if (y < nearLo && farSlab >= 0 && (y - yLo) / 16 != farSlab) continue;
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						if (y > colTop[(x - x0) * (z1 - z0 + 1) + (z - z0)]) continue;
						BlockState world = Terrain.state(cm.set(x, y, z));
						if (world.isAir()) continue;
						BlockPos c = cm.immutable();
						BlockState pc = plan.get(c);
						if (pc != null) {
							if (pc.isAir() && world.is(net.minecraft.tags.BlockTags.LOGS)) logsGoing.add(c);
							continue;
						}
						if (world.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
						if (grewFromBuild(c, world, now)) continue;
						if (world.is(net.minecraft.tags.BlockTags.LOGS)) logsGoing.add(c);
						// (A tree's own leaves go by themselves once its trunk is out: knocking them out one by one, they
						// vanished on the way to them, and the barn didn't go up for minutes.)
						// (Unless they hide a spot: leavesInWay. Or they hang low over the floor, a roof 2-3 blocks up he had to
						// set down and walk under, then take off again: 9 of house 547's 16 landings in 5 minutes.)
						// (Low over the ground under them too, not just over the floor: over a hill higher than the floor the
						// spruces' lowest leaves were a roof to land and walk under, 69 landings on the statue's hillside.)
						if (naturalLeaves(world) && !leafWay.contains(c) && c.getY() > clearFromY + 3 && !lowOverGround(c)) {
							leafWait.add(c);
							continue;
						}
						BlockState want = lookup.apply(c);
						if (want == null || !want.isAir() || !Placement.breakable(Terrain.level(), c, world)) continue;
						// (The world's own grass and flowers in the yard round the build stay: a player doesn't weed the lawn
						// first. The iron farm: 10 minutes of tufts before the first block.)
						if ((world.canBeReplaced() || world.getCollisionShape(Terrain.level(), c).isEmpty())
								&& !dev.humanautopilot.schematic.SchematicMode.get().inCore(c)) continue;
						// (Snow, grass, a flower on a block that's going too: it drops with that one. Picked by itself, it
						// was gone by the time he got there: "isn't to do any more", and the eyes went looking.)
						if (world.canBeReplaced() || world.getCollisionShape(Terrain.level(), c).isEmpty()) {
							BlockPos dn = c.below();
							BlockState wd = Terrain.state(dn), wantDn = lookup.apply(dn);
							if (!wd.isAir() && wantDn != null && wantDn.isAir() && Placement.breakable(Terrain.level(), dn, wd)) continue;
						}
						clear.add(c);
					}
				}
			}
			// Leaves that won't fall by themselves: held by a log that stays (the tree's trunk outside the build), no log to
			// be knocked out within their reach. Out by hand, like the rest. (The cottage on the cliff: a spruce beside it
			// leaned in, and 95 of its leaves were still in the build at the end.)
			for (BlockPos c : leafWait) {
				BlockState w = Terrain.state(c);
				if (!w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DISTANCE)) continue;
				int d = w.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DISTANCE);
				if (d >= 7) continue; // (going already)
				boolean goes = false;
				for (BlockPos l : logsGoing) {
					if (l.distManhattan(c) <= d) {
						goes = true;
						break;
					}
				}
				if (!goes) clear.add(c);
			}
			for (BlockPos c : clear) {
				plan.put(c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
				planLayers.computeIfAbsent(c.getY(), k -> new ArrayList<>()).add(c);
				planList.add(c);
				holes.add(c);
				remember(c, Placement.Job.BREAK);
			}
			if (!clear.isEmpty()) holes.sort(java.util.Comparator.comparingInt(BlockPos::getY));
		}
		// A portal's opening cleared: off the plan again (lit, the portal blocks aren't "something in the way").
		if (!portalClear.isEmpty()) {
			for (var it = portalClear.iterator(); it.hasNext(); ) {
				BlockPos c = it.next();
				BlockState w = Terrain.state(c);
				if (!w.isAir() && !w.is(net.minecraft.world.level.block.Blocks.FIRE) && !w.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue;
				plan.remove(c);
				var layer = planLayers.get(c.getY());
				if (layer != null) layer.remove(c);
				planList.remove(c);
				var hs = holesByLayer.get(c.getY());
				if (hs != null) hs.remove(c.asLong());
				it.remove();
			}
		}
		// Water in where a block stands in water (kelp, a coral...): now the block itself.
		if (!waterFirst.isEmpty()) {
			for (var it = waterFirst.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				BlockPos c = e.getKey();
				var fs = Terrain.state(c).getFluidState();
				if (!fs.is(net.minecraft.world.level.material.Fluids.WATER) || !fs.isSource()) continue;
				plan.put(c, e.getValue());
				it.remove();
				remember(c, jobAt(c));
			}
		}
		// Temporary blocks: once the block they were for is in, knock them out; once gone, forget them.
		if (!temps.isEmpty()) {
			for (var it = temps.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				BlockPos tmp = e.getKey();
				BlockState w = plan.get(tmp);
				if (w == null) {
					it.remove();
					continue;
				}
				BlockPos forT = e.getValue();
				boolean forDone = !plan.containsKey(forT) || jobAt(forT) != Placement.Job.PLACE;
				// It's in: the block it's for gets another go now (not when its skip timer runs out a minute later).
				if (!w.isAir() && !forDone && jobAt(tmp) == Placement.Job.DONE) bad.remove(forT);
				if (!w.isAir() && forDone) {
					if (keepsForNext(tmp, forT)) continue;
					plan.put(tmp, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
					// (Under the layers we're on now, maybe: the window comes down to knock it out. And the memory has
					// it as done from when it went in, and the count pass skips temporary blocks: it's a hole now.)
					scanFromY = Math.min(scanFromY, tmp.getY());
					remember(tmp, jobAt(tmp));
				} else if (w.isAir() && Terrain.state(tmp).isAir()) {
					plan.remove(tmp);
					var layer = planLayers.get(tmp.getY());
					if (layer != null) layer.remove(tmp);
					planList.remove(tmp);
					it.remove();
				}
			}
		}
		// Finished layers below: only the slow full pass above looks at them again.
		if (found) scanFromY = activeY;
		holeCount = found ? Math.max(holes.size(), 1) : 0;
		// Only given-up spots left in the window (and nothing hanging on a temporary block): done, not "1 hole" for ever.
		// (The floating pagoda's last stone button, vines in its spot, given up on in the wind-down: tried every 2 s for
		// 15 minutes at 19,586 of 19,975, never "done", so the server never put the rest in.)
		if (holeCount > 0 && !holes.isEmpty() && planCounted && planTotal > 0 && planDone >= planTotal * 0.98) {
			boolean real = false;
			for (BlockPos h : holes) {
				if (!cant.contains(h) && !temps.containsKey(h) && hole(h)) {
					real = true;
					break;
				}
			}
			if (!real) {
				if (now - onlyCantSaidAt > 1200) {
					onlyCantSaidAt = now;
					HumanAutopilot.LOGGER.info("[schem] only given-up spots left ({}, first {}): done", holes.size(), holes.get(0).toShortString());
				}
				holes.clear();
				holeCount = 0;
			}
		}
		// Handed off to the server (80% in, see handOff): only our own temporary blocks still to come out - for a minute at
		// most (one whose block the server couldn't put in, someone standing in its spot, stays).
		if (handedOffAt >= 0) {
			if (now - handedOffAt > 1200) cant.addAll(temps.keySet());
			holes.removeIf(h -> !temps.containsKey(h) || cant.contains(h));
			holeCount = holes.size();
		}
		// A temporary block still to knock out (or still to go in) isn't done: "Done building!" came one scan before
		// the bridges out to a statue's floating pieces turned into "knock me out", and they were left behind.
		if (holeCount == 0) {
			for (BlockPos tmp : temps.keySet()) {
				BlockState w = plan.get(tmp);
				if (cant.contains(tmp)) continue; // (given up on: it doesn't hold the build open for ever)
				if (w != null && (w.isAir() ? !Terrain.state(tmp).isAir() : jobAt(tmp) != Placement.Job.DONE)) {
					holeCount = 1;
					scanFromY = Math.min(scanFromY, tmp.getY());
					break;
				}
			}
		}
		scannedAt = now;
		bad.values().removeIf(until -> until < now);
		costEnd("s.clear", cs0);
	}

	/** The hole to fill next: the one that can be filled soonest (see below). */
	private boolean pickTarget(LocalPlayer p, Box b, long now) {
		if (plan != null) {
			// (Nothing to pick this tick already: not the whole pick again. With nothing to do it ran up to three times a
			// tick - once for each of the "nothing to do" branches that ask.)
			if (pickNoneAt == now && target == null) return false;
			long c0 = costStart();
			try {
				boolean got = pickPlanTarget(p, b, now);
				if (!got && target == null) pickNoneAt = now;
				return got;
			} finally {
				costEnd("pick", c0);
			}
		}
		int lowest = Integer.MAX_VALUE;
		List<BlockPos> ok = new ArrayList<>();
		Map<BlockPos, BlockPos> flyTo = air(p, b);
		for (BlockPos h : holes) {
			// The list is up to 5 ticks old: the block we just placed is still on it.
			if (bad.containsKey(h) || !hole(h) || !workable(h)) continue;
			// Sealed in (no open air we can get to next to it): picking those one after another, giving up on each,
			// was the long "thinking". Left for the roof-opener (fixLid) instead.
			if (!gettable(h, flyTo)) continue;
			if (openingCol != null && h.getX() == openingCol.getX() && h.getZ() == openingCol.getZ()) continue;
			ok.add(h);
			lowest = Math.min(lowest, h.getY());
		}
		if (ok.isEmpty()) return false;
		// A hole tucked in under the top (TNT undercuts it) is reached only through the hole beside it: filling that one
		// first sealed it in for good (breaking roofs open is off). The tucked one first, like a player.
		if (ok.size() > 1) {
			List<BlockPos> free = new ArrayList<>(ok);
			free.removeIf(this::sealsAnother);
			if (!free.isEmpty()) ok.retainAll(free);
		}
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);
		boolean recent = lastPlaced != null && now - lastPlacedAt < 40;
		BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
		// In a 1x1 shaft: finish it before going anywhere else. Its bottom hole is the one we can fill (nothing
		// goes on top of a hole): go down to it, fill under our feet, rise, repeat, like pillaring up out of a hole.
		if (inShaft(p)) {
			for (BlockPos c = below; c.getY() >= b.minY(); c = c.below()) {
				if (!hole(c)) break;
				if (ok.contains(c)) {
					setTarget(c);
					return true;
				}
			}
		}
		BlockPos best = null, underUs = null;
		// Tucked under an overhang: the open spots right beside us are the way out, so they wait.
		BlockPos feet = bodyCell(p);
		boolean tucked = b.inside(feet.getX(), feet.getY(), feet.getZ()) && !skyOpen(b, feet.getX(), feet.getY(), feet.getZ());
		// Racing the viewers: the next block is whichever gets filled SOONEST. Anything we can already click from right
		// here (no flying) beats everything else, and of those the smallest flick of the camera (a little in favour of
		// the next block in the same line, and of lower ones). Only when nothing is in reach: the nearest hole.
		double reach = p.blockInteractionRange();
		List<Object[]> inReach = new ArrayList<>();
		List<Object[]> farList = new ArrayList<>();
		BlockPos nearest = null;
		double nearestScore = Double.MAX_VALUE;
		BlockPos step = recent && prevPlaced != null ? lastPlaced.subtract(prevPlaced) : null;
		List<Vec3> others = otherPlayers(p);
		// A hollow under the roof we can still get into: fill it first (fly in, fill, fly out), and the open holes,
		// above all the one we'd go in through, after. Filling the way in first sealed the hollow off.
		Map<BlockPos, BlockPos> airMap = air(p, b);
		java.util.Set<BlockPos> hollow = new java.util.HashSet<>();
		for (BlockPos h : ok) {
			if (!skyOpen(b, h.getX(), h.getY(), h.getZ()) && (airMap.containsKey(h) || airMap.containsKey(h.below()))) hollow.add(h);
		}
		boolean anyHollow = !hollow.isEmpty();
		if (anyHollow) {
			// Only the hollow's holes until it's done: a handy open hole nearby (often the one under the way in) won
			// otherwise, sealed the hollow off and left us stuck.
			ok.retainAll(hollow);
		}
		// Filling a narrow (1x1) hole: carry on straight up it until it's full (after the hollow
		// rule: going up the column we came in through sealed the hollow off), don't wander off after a block or two
		// (the user saw it go down a long 1x1, place one or two, then fly off to another hole).
		if (lastPlaced != null && now - lastPlacedAt < 60) {
			BlockPos up = lastPlaced.above();
			if (ok.contains(up) && narrow(up)) {
				setTarget(up);
				return true;
			}
		}
		if (anyHollow) {
			// A hollow under the roof: from the far end back toward the way in, each column bottom to top, the way in
			// last (even when nearer holes are easier: filling round the entrance first sealed the rest off, and
			// filling layer by layer left a slit under the roof too thin for our body).
			java.util.Set<Long> entry = new java.util.HashSet<>();
			for (BlockPos h : hollow) {
				for (BlockPos n : new BlockPos[]{h.above(), h.north(), h.south(), h.east(), h.west()}) {
					if (skyOpen(b, n.getX(), n.getY(), n.getZ())) entry.add(((long) n.getX() << 32) ^ (n.getZ() & 0xffffffffL));
				}
			}
			List<Object[]> order = new ArrayList<>();
			for (BlockPos h : ok) {
				double fromEntry = 99;
				for (long e : entry) {
					int ex = (int) (e >> 32), ez = (int) e;
					fromEntry = Math.min(fromEntry, Math.abs(h.getX() - ex) + Math.abs(h.getZ() - ez));
				}
				if (entry.isEmpty()) fromEntry = 0;
				double sc = -12 * fromEntry + 2 * (h.getY() - lowest) + Vec3.atCenterOf(h).distanceTo(eye)
						+ (lastPlaced != null && h.equals(lastPlaced.above()) ? -40 : 0);
				order.add(new Object[]{h, sc});
			}
			order.sort((x, y) -> Double.compare((double) x[1], (double) y[1]));
			for (int i = 0; i < Math.min(30, order.size()); i++) {
				BlockPos h = (BlockPos) order.get(i)[0];
				if (leavesWayOut(p, b, h)) {
					setTarget(h);
					return true;
				}
			}
		}
		BlockPos nearOthers = null; // the best hole that's right by someone, only if nothing else is left
		BlockPos exit = null;
		for (BlockPos h : ok) {
			Vec3 c = Vec3.atCenterOf(h);
			if (tucked && Math.abs(h.getX() - feet.getX()) + Math.abs(h.getZ() - feet.getZ()) == 1
					&& h.getY() >= feet.getY() && h.getY() <= feet.getY() + 1) continue;
			// Right under our feet in the open (filling it would set us down on it), or where our body is: others first.
			if (h.equals(below) || h.equals(below.below()) || p.getBoundingBox().inflate(0.05).intersects(new AABB(h))) {
				underUs = h;
				continue;
			}
			// Stay away from the companion (Kohaku) and other players: their area is theirs to fill.
			double crowd = nearest(others, c);
			if (crowd < 2.5) {
				if (nearOthers == null) nearOthers = h;
				continue;
			}
			double d = c.distanceTo(eye);
			double far = d + 0.3 * (h.getY() - lowest) + (crowd < 4.5 ? 6 : 0) + (anyHollow && !hollow.contains(h) ? 25 : 0)
					- (exit != null ? 3 * (Math.abs(h.getX() - exit.getX()) + Math.abs(h.getZ() - exit.getZ())) : 0);
			farList.add(new Object[]{h, far});
			if (far < nearestScore) {
				nearestScore = far;
				nearest = h;
			}
			if (d > reach + 0.6) continue;
			double turn = Math.toDegrees(Math.acos(Math.clamp(look.dot(c.subtract(eye).normalize()), -1, 1)));
			double score = turn + 3 * (h.getY() - lowest) + (crowd < 4.5 ? 30 : 0) + (anyHollow && !hollow.contains(h) ? 90 : 0);
			if (exit != null) {
				double fromExit = Math.abs(h.getX() - exit.getX()) + Math.abs(h.getZ() - exit.getZ());
				score = turn * 0.3 + 2 * (h.getY() - lowest) - 12 * fromExit
						+ (lastPlaced != null && h.equals(lastPlaced.above()) ? -40 : 0);
			}
			if (recent && step != null && step.distManhattan(BlockPos.ZERO) == 1 && h.equals(lastPlaced.offset(step))) score -= 12;
			else if (recent && h.distManhattan(lastPlaced) == 1) score -= 6;
			inReach.add(new Object[]{h, score});
		}
		inReach.sort((x, y) -> Double.compare((double) x[1], (double) y[1]));
		for (int i = 0; i < Math.min(40, inReach.size()) && best == null; i++) {
			BlockPos h = (BlockPos) inReach.get(i)[0];
			if (faceFrom(p, eye, look, h) != null && leavesWayOut(p, b, h)) best = h;
		}
		if (best == null) {
			// The nearest that doesn't cut off our way out (giving up when the very nearest did froze it in a hollow).
			farList.sort((x, y) -> Double.compare((double) x[1], (double) y[1]));
			for (int i = 0; i < Math.min(30, farList.size()) && best == null; i++) {
				BlockPos h = (BlockPos) farList.get(i)[0];
				if (leavesWayOut(p, b, h)) best = h;
			}
		}
		if (best == null) best = nearOthers;
		boolean fallback = best != null && best == nearOthers;
		// Only the one under us left: go for it anyway (we'll move off to the side first).
		if (best == null) best = underUs;
		if (best != null) setTarget(best);
		crowdFallback = fallback; // (after setTarget, which clears it)
		return best != null;
	}

	/**
	 * Schematic: bottom layer first, going up; in a layer, along rows like box fill's lines (carry on in the same
	 * direction, then the same row, then whatever's least camera turn in reach, else the nearest).
	 */
	private boolean pickPlanTarget(LocalPlayer p, Box b, long now) {
		scaffoldBudget = 4;
		// What's in the spot over a tall plant still to go in (see waitsOnSupport): on the clearing list.
		if (!clearAboveQueue.isEmpty()) {
			for (BlockPos c : clearAboveQueue) {
				BlockState w = Terrain.state(c), pc = plan.get(c);
				if (!w.isAir() && !w.canBeReplaced() && (pc == null || pc.isAir())) clearOnTop(c);
			}
			clearAboveQueue.clear();
		}
		if (!needPillar.isEmpty()) {
			int n = 0;
			for (BlockPos h : new ArrayList<>(needPillar)) {
				if (n >= 4) break; // (a few a pick)
				needPillar.remove(h);
				if (!hole(h) || plan.get(h.below()) != null) continue;
				n++;
				if (!pillarUnder(h, "it would fall")) cantPillar.add(h);
			}
		}
		if (now - pickLoggedAt > 200) {
			pickLoggedAt = now;
			HumanAutopilot.LOGGER.info("[schem] picking from {} holes", holes.size());
		}
		Map<BlockPos, BlockPos> flyTo = air(p, b);
		CreativePicker picker = CreativePicker.get();
		// A huge window (the Himeji castle's base: 240k holes in 13 layers, ~8000 of them round us): what's round here is
		// looked up spot by spot (holesNear) instead of going through all of them, and of that only what can be picked
		// this time gets the full checks (see below). (All 8000 checked on every pick, and all 240k gone through: picks of
		// 30-100 ms, a third of the ticks over 5 ms.)
		// (From 8000: the castle's base at 8-20k holes, dense round us, still had 23-51 ms picks the old way.)
		boolean bigWin = holes.size() > 8000;
		// A block of the build shut in a small closed pocket of it (a wall's core: it and the cell over it still air, the
		// wall closed over both): never gettable, so never picked, and openUp (after a 2nd "no spot") never ran for it -
		// the user's castle sent him 50 blocks up and over for one, again and again. Every 2 s, the nearest such within
		// 10: one of the build's blocks round the pocket out, the one that lets us in (it goes back after).
		// (Not while one's open somewhere else: that one gets finished first, not left for one here and back.)
		// (TNT damage to put right as well, too: blown out of a wall's core, the damage is the pockets. Not while there
		// was any, she flew from one shut-in spot of a blast to the next for 7 minutes, 0 placed: "nowhere to place it from".)
		if (now - pocketAt > 40 && !openFar(p.position(), 16)) {
			pocketAt = now;
			BlockPos best = null;
			double bestD = 100;
			for (BlockPos h : bigWin ? holesNear(p.getX(), p.getY(), p.getZ(), 10, 11) : holes) {
				double d2 = Vec3.atCenterOf(h).distanceToSqr(p.position());
				if (d2 >= bestD || openTries.getOrDefault(h, 0) >= 2 || temps.containsKey(h) || opened.containsKey(h) || opened.containsValue(h)
						|| now - pocketTried.getOrDefault(h, -10000L) < 1200) continue;
				BlockState w = plan.get(h);
				if (w == null || w.isAir() || Placement.soil(w) || (clearFromY != Integer.MAX_VALUE && h.getY() < clearFromY)) continue;
				if (jobAt(h) != Placement.Job.PLACE || !inRegion(h.getX(), h.getY(), h.getZ()) || gettable(h, flyTo)) continue;
				best = h;
				bestD = d2;
			}
			if (best != null) openPocket(p, b, best, flyTo, now);
		}
		// Bottom-up column by column: a block can go in once the one under it (if that's part of the build) is in.
		// Near ones first, lower a bit before higher; far ones only if nothing near will do. (Whole layers first had
		// it flying 10 blocks to put down one pillar base, then the next, then back for the next layer of each.)
		List<BlockPos> ok = new ArrayList<>();
		List<BlockPos> far = new ArrayList<>();
		double px = p.getX(), pz = p.getZ();
		int layerLow = holes.isEmpty() ? 0 : holes.get(0).getY();
		// Something got blown out (TNT): that first, near or far, like a guard dropping what they're doing (the user:
		// "it did not repair the broken parts affected by tnt immediately": it finished everything near it first).
		boolean repairing0 = false;
		if (!damaged.isEmpty()) {
			List<BlockPos> dmgNear = new ArrayList<>(), dmgFar = new ArrayList<>();
			java.util.Set<BlockPos> dmgSeen = new java.util.HashSet<>();
			List<BlockPos> dmgHoles = holes;
			if (bigWin) {
				// (Just the damage, not all the holes to find it in.)
				dmgHoles = new ArrayList<>();
				for (long k : damaged) {
					BlockPos c = BlockPos.of(k);
					if (inHoles(c)) dmgHoles.add(c);
				}
				dmgHoles.sort(java.util.Comparator.comparingInt(BlockPos::getY));
			}
			// (Lots of it - 20 TNT on the castle, 2200 spots: the far ones' way in is only worked out when it's their turn,
			// nearest first, below. Worked out for every one on every pick, picks were 24-30 ms all through the repair.)
			boolean manyDmg = damaged.size() > 300;
			List<BlockPos> dmgLater = new ArrayList<>();
			for (BlockPos h0 : dmgHoles) {
				if (!damaged.contains(h0.asLong())) continue;
				// (Tried twice and no way to put it back from where we got to: not first any more. The user's castle: a stone
				// blown out of a wall's core, shut in by the rest, pulled him back across the castle every few minutes,
				// "nowhere to place it from" each time. A hole like the rest now - openPocket and the end see to it - till
				// TNT goes off by it again, blastLook clears its count.)
				if (fails.getOrDefault(h0, 0) >= 2) continue;
				double dx0 = h0.getX() + 0.5 - px, dz0 = h0.getZ() + 0.5 - pz;
				if (manyDmg && dx0 * dx0 + dz0 * dz0 > 16 * 16) {
					dmgLater.add(h0);
					continue;
				}
				BlockPos h = repairStep(h0);
				// (And the step to it too: the dirt under 56 damaged spots of the copper guard's ground, "couldn't fly there",
				// was first again every minute for an hour - its own tries never counted, only the spots it was a step for.)
				if (h == null || fails.getOrDefault(h, 0) >= 2 || !dmgSeen.add(h)) continue;
				double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz;
				(dx * dx + dz * dz > 14 * 14 ? dmgFar : dmgNear).add(h);
			}
			// Nearest first, up and down counted too (a bit more: flying up and down is slower), and the one she set off
			// for kept first while it's still to do: bottom-first, re-picked at every look, sent her up to y 14, back down to
			// -19, up to 19 and down to -22 for a handful of TNT holes on the pagoda - "flying up and down confused".
			final double fpx = px, fpy = p.getY(), fpz = pz;
			dmgNear.sort(java.util.Comparator.comparingDouble(h -> {
				double ddx = h.getX() + 0.5 - fpx, ddy = (h.getY() + 0.5 - fpy) * 1.5, ddz = h.getZ() + 0.5 - fpz;
				return ddx * ddx + ddy * ddy + ddz * ddz - (h.equals(dmgCommit) ? 1e5 : 0);
			}));
			for (BlockPos h : dmgNear) if (planOk(h, flyTo, picker)) ok.add(h);
			if (ok.isEmpty() && !dmgFar.isEmpty()) {
				int low = dmgFar.stream().mapToInt(BlockPos::getY).min().orElse(0);
				// (One that had nowhere to be put in from when we were last there: the others first. Flying 50 blocks back to
				// a glazed tile with no spot to do it from, again and again, was half the "confused" flying about.)
				dmgFar.sort(java.util.Comparator.comparingDouble(h -> Vec3.atCenterOf(h).distanceToSqr(px, h.getY(), pz) + 9 * (h.getY() - low) * (h.getY() - low)
						+ (nowhere.containsKey(h) ? 1e6 : 0) - (h.equals(dmgCommit) ? 1e7 : 0)));
				for (BlockPos h : dmgFar) {
					if (planOk(h, flyTo, picker)) {
						ok.add(h);
						break;
					}
				}
			}
			if (ok.isEmpty() && !dmgLater.isEmpty()) {
				int low = dmgLater.stream().mapToInt(BlockPos::getY).min().orElse(0);
				dmgLater.sort(java.util.Comparator.comparingDouble(h -> Vec3.atCenterOf(h).distanceToSqr(px, h.getY(), pz) + 9 * (h.getY() - low) * (h.getY() - low)
						+ (nowhere.containsKey(h) ? 1e6 : 0)));
				int tried = 0;
				for (BlockPos h0 : dmgLater) {
					BlockPos h = repairStep(h0);
					if (h != null && fails.getOrDefault(h0, 0) < 2 && fails.getOrDefault(h, 0) < 2 && dmgSeen.add(h) && planOk(h, flyTo, picker)) {
						ok.add(h);
						break;
					}
					if (++tried >= 200) break; // (the rest at the next pick)
				}
			}
			repairing0 = !ok.isEmpty();
			dmgCommit = repairing0 ? ok.get(0) : null;
			if (repairing0 && now - repairLogAt > 100) {
				repairLogAt = now;
				HumanAutopilot.LOGGER.info("[schem] repairing {} damaged spots first (next {})", damaged.size(), ok.get(0).toShortString());
				repairRuns++;
			}
			// Damage none of which can be done now, for a while: why, spot by spot (the barn's post, 2 blocks of it out for
			// 5 minutes after the TNT and never once tried).
			if (!repairing0 && now - dmgWhyAt > 600) {
				dmgWhyAt = now;
				StringBuilder why = new StringBuilder();
				int n = 0;
				damaged.removeIf(k -> jobAt(BlockPos.of(k)) == Placement.Job.DONE); // (put back while out of the window)
				for (long k : damaged) {
					if (n++ >= 8) break;
					BlockPos h = BlockPos.of(k);
					why.append(h.toShortString()).append(' ').append(whyNotOk(h, flyTo, picker)).append("; ");
				}
				if (!damaged.isEmpty()) HumanAutopilot.LOGGER.info("[schem] {} damaged spots, none to do now: {}", damaged.size(), why);
				// One hanging in the air with nothing of the build beside it to start from (the ground it was put against
				// blown away): temporary blocks out to it from something solid, like a player would.
				for (long k : damaged) {
					BlockPos h = BlockPos.of(k);
					if (!hole(h) || jobAt(h) != Placement.Job.PLACE || workable(h) || columnWaits(h) || waitsOnSupport(h)
							|| !plan.get(h).canSurvive(Terrain.level(), h)) continue;
					List<BlockPos> chain = bridgeTo(p, h);
					if (chain == null) continue;
					for (BlockPos q : chain) {
						plan.put(q, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
						planLayers.computeIfAbsent(q.getY(), y -> new ArrayList<>()).add(q);
						planList.add(q);
						temps.put(q, h);
						scanFromY = Math.min(scanFromY, q.getY());
					}
					bad.remove(h);
					HumanAutopilot.LOGGER.info("[schem] {} (put right) hangs in the air with nothing to put it against: {} temporary block(s) out to it from {}",
							h.toShortString(), chain.size(), chain.get(0).toShortString());
					break;
				}
			}
		}
		// A spot the build shut in (openUp): the block taken out for it first, then the spot, then that block back in.
		if (!repairing0 && !opened.isEmpty()) {
			// (The nearest of them, not the first in the map: with two opened 45 apart she went from one to the other and
			// back, a block each time - "flying back and forth confused".)
			List<BlockPos> openNext = new ArrayList<>();
			for (var e : new ArrayList<>(opened.entrySet())) {
				BlockPos n = e.getKey(), t = e.getValue();
				Placement.Job jt = jobAt(t);
				boolean left = jt == Placement.Job.PLACE || jt == Placement.Job.BREAK || jt == Placement.Job.ADJUST;
				if (!opened.containsKey(n)) continue; // (let go of already, with the others for its spot)
				// (A room's doorway: on to the next of the room's spots while there are any, not shut after the first.)
				if (!left && doorRooms.containsKey(n)) {
					BlockPos t2 = null;
					for (BlockPos s2 : doorRooms.get(n)) {
						if (s2.equals(t) || cant.contains(s2)) continue;
						Placement.Job j2 = jobAt(s2);
						if (j2 == Placement.Job.PLACE || j2 == Placement.Job.BREAK || j2 == Placement.Job.ADJUST) {
							t2 = s2;
							break;
						}
					}
					if (t2 != null) {
						for (var o : new ArrayList<>(opened.entrySet())) if (o.getValue().equals(t)) opened.put(o.getKey(), t2);
						openedAt.remove(t);
						openedAt.put(t2, now);
						continue;
					}
				}
				if (!left || now - openedAt.getOrDefault(t, now) > 1200) {
					// (All of them for that spot at once: one at a time, the rest lost the time they were taken out at.)
					List<BlockPos> back = new ArrayList<>();
					for (var o : opened.entrySet()) if (o.getValue().equals(t)) back.add(o.getKey());
					for (BlockPos q : back) {
						opened.remove(q);
						doorRooms.remove(q);
					}
					openedAt.remove(t);
					putBack.addAll(back);
					if (left) HumanAutopilot.LOGGER.info("[schem] couldn't get {} in after all: {} goes back", t.toShortString(), back);
					else HumanAutopilot.LOGGER.info("[schem] {} is in: {} goes back", t.toShortString(), back);
					continue;
				}
				// (Leaves: a few of them for one spot, each out first - the nearest on the way in that will do.)
				BlockPos h = jobAt(n) == Placement.Job.BREAK ? n : t;
				if (h.equals(t) && opened.entrySet().stream().anyMatch(o -> o.getValue().equals(t) && jobAt(o.getKey()) == Placement.Job.BREAK)) continue;
				if (!openNext.contains(h)) openNext.add(h);
			}
			openNext.sort(java.util.Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(p.position())));
			for (BlockPos h : openNext) {
				if (planOk(h, flyTo, picker)) {
					ok.add(h);
					repairing0 = true;
					break;
				}
			}
			// (Opened up and still not to do: say why, so a take-out followed by half a minute of other work shows its
			// reason - Knight's Order, 02:45: two blocks out for a terracotta, 15 others and a look at the build first.)
			if (!repairing0 && !openNext.isEmpty() && now - openWhyAt > 100) {
				openWhyAt = now;
				BlockPos h = openNext.get(0);
				HumanAutopilot.LOGGER.info("[undo] {} is opened up but not to do yet: {}", h.toShortString(), whyNotOk(h, flyTo, picker));
			}
		}
		if (!repairing0 && !putBack.isEmpty()) {
			putBack.removeIf(c -> !hole(c));
			List<BlockPos> backNext = new ArrayList<>(putBack);
			backNext.sort(java.util.Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(p.position())));
			for (BlockPos c : backNext) {
				if (planOk(c, flyTo, picker)) {
					ok.add(c);
					repairing0 = true;
					break;
				}
			}
		}
		if (!repairing0 && !plantNext.isEmpty()) {
			plantNext.removeIf(c -> !hole(c) || !Terrain.state(c.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND));
			for (BlockPos c : plantNext) {
				if (planOk(c, flyTo, picker)) {
					ok.add(c);
					repairing0 = true;
					break;
				}
			}
		}
		// The huge window: the part to do now first (buildOrder's first half, from the holes alone), so that of the ones
		// round here only what it can use gets the full checks - that part, up to 8 layers over its lowest (the most
		// buildOrder takes), or with no part to go by the lowest two layers with something to do; temporary blocks,
		// clearing out and water running out always. The rest round here only if none of that will do.
		int[] part = null;
		List<BlockPos> later = new ArrayList<>();
		if (bigWin && !repairing0) {
			if (skyTop != null) {
				long cOrder = costStart();
				try {
					part = orderPart(flyTo, picker, now);
				} finally {
					costEnd("order", cOrder);
				}
				if (part == null) return false; // (the window is growing up the outside: next scan)
			}
			long cNear = costStart();
			int cap = part == null || part[0] < 0 ? Integer.MAX_VALUE
					: part[0] == 0 && clearFromY != Integer.MAX_VALUE ? Math.max(part[1] + 8, clearFromY - 1) : part[1] + 8;
			java.util.Set<BlockPos> tempFor = temps.isEmpty() ? java.util.Set.of() : new java.util.HashSet<>(temps.values());
			int lowOk = Integer.MAX_VALUE;
			long cList = costStart();
			// (Within 7 up and down of a window of over 50k spots, like scanPlan's "round us": all 13 of the castle's base
			// layers round us, 8000 spots, was most of a pick's time and growing with the window - 784 ms of every 30 s at
			// 270k holes. The far fallback still has the rest.)
			List<BlockPos> nearList = holesNear(px, p.getY(), pz, 14, winCells.size() > 50_000 ? 7 : 12);
			costEnd("pn.list", cList);
			for (BlockPos h : nearList) {
				BlockState wantH = plan.get(h);
				if (wantH == null) continue;
				boolean deferred = cap != Integer.MAX_VALUE ? h.getY() > cap || phaseOf(h) != part[0] : lowOk != Integer.MAX_VALUE && h.getY() > lowOk + 1;
				// (Water running out: looked at only for the ones that would wait - the others get their checks anyway.)
				if (deferred && !(wantH.isAir() || temps.containsKey(h) || tempFor.contains(h) || outFor.containsKey(h) || plugsWater(h))) {
					later.add(h);
					continue;
				}
				long cOk = costStart();
				boolean good = nearOk(h, wantH, flyTo, picker);
				costEnd("pn.ok", cOk);
				if (good) {
					ok.add(h);
					lowOk = Math.min(lowOk, h.getY());
				}
			}
			if (ok.isEmpty()) {
				for (BlockPos h : later) if (nearOk(h, plan.get(h), flyTo, picker)) ok.add(h);
				later.clear();
			}
			costEnd("pnear", cNear);
		}
		int nearDy = bigWin && winCells.size() > 50_000 ? 7 : 12; // (as holesNear above: the rest is far)
		for (BlockPos h : holes) {
			if (repairing0 || !ok.isEmpty() && bigWin) break;
			double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz;
			boolean isFar = dx * dx + dz * dz > 14 * 14 || Math.abs(h.getY() + 0.5 - p.getY()) > nearDy;
			// (The far ones only matter when nothing near will do; shut in, not at all. Only listed here: their checks come
			// below, nearest first, and stop at the first that will do. Checked as they came, lowest layer first, the
			// maze's 58000 holes took a second of every 30 s, 17% of ticks over 5 ms.)
			if (isFar) {
				// (Not the ones found buried lately: under a hill they were all the nearest, and the far ones further on that
				// could be done never got a look - see farBuriedNow.)
				if (!airShutIn && !farBuriedSeen(h, now)) far.add(h);
				continue;
			}
			if (bigWin) continue; // (looked at above)
			if (nearOk(h, plan.get(h), flyTo, picker)) ok.add(h);
		}
		if (ok.isEmpty() && !far.isEmpty()) {
			long cFar = costStart();
			final int low = layerLow;
			// The nearest 64 first (a sort of tens of thousands on every pick is a hitch); all of them only if none of those
			// will do.
			java.util.PriorityQueue<Object[]> top = new java.util.PriorityQueue<>((u, v) -> Double.compare((double) v[0], (double) u[0]));
			for (BlockPos h : far) {
				double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz, dy = h.getY() - low;
				double sc = dx * dx + dz * dz + 9 * dy * dy + (nowhere.containsKey(h) ? 1e6 : 0);
				if (top.size() >= 64 && sc >= (double) top.peek()[0]) continue;
				// (Buried ones looked at only when they'd be among the nearest: a few hundred looks, not one per far hole.)
				if (farBuriedNow(h, now)) continue;
				if (top.size() >= 64) top.poll();
				top.add(new Object[]{sc, h});
			}
			List<Object[]> nearest = new ArrayList<>(top);
			nearest.sort((u, v) -> Double.compare((double) u[0], (double) v[0]));
			// Look before flying: the nearest far one that can be seen from here to be workable from a spot open to the sky
			// (workableFromAfar), not just the nearest - most of those were buried in the hill and every flight was for
			// nothing. One that can't be told yet (this tick's looks used up) waits for the next tick. If none of them will
			// do from afar: the nearest not flown over to blind lately, to see from up close (once in 2 minutes each).
			BlockPos blind = null;
			boolean pending = false;
			int looked0 = farLooks;
			for (Object[] o : nearest) {
				BlockPos h = (BlockPos) o[1];
				if (columnWaits(h) || !planOk(h, flyTo, picker)) continue;
				Boolean w = farOk(p, b, h);
				if (w == null) {
					pending = true;
					break;
				}
				if (w) {
					ok.add(h);
					farApproved = h;
					break; // the nearest of the far ones that will do: fly over there
				}
				if (blind == null && now - blindAt.getOrDefault(h, -100000L) > 2400) blind = h;
			}
			// (All of them sorted at most every 2 s: none of the nearest 64 doing, it was every pick - the stone-less
			// castle's 22000 holes, 40-50 ms picks while he flew over to the rest of the build.)
			if (ok.isEmpty() && !pending && far.size() > 64 && now - farFullAt > 40) {
				farFullAt = now;
				// (Sorted by a number each, worked out once: a comparator working it out at every comparison was 40-50 ms
				// with the Himeji castle's 280k holes.)
				long[] ord = new long[far.size()];
				boolean anyNowhere = !nowhere.isEmpty();
				for (int i = 0; i < ord.length; i++) {
					BlockPos h = far.get(i);
					double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz, dy = h.getY() - low;
					double sc = dx * dx + dz * dz + 9 * dy * dy + (anyNowhere && nowhere.containsKey(h) ? 1e6 : 0);
					ord[i] = ((long) Float.floatToIntBits((float) sc) << 32) | i; // (a float's bits sort like the float, if not negative)
				}
				// (Only the nearest 1500 of them, picked out and sorted - not all 240k sorted, then checked one by one till one
				// would do: an 83 ms hitch when he came back from looking at the castle from 25 up, none of it near. The rest
				// get their go 2 s on, from wherever he is then.)
				int n = Math.min(ord.length, 1500);
				if (n < ord.length) selectSmallest(ord, n);
				java.util.Arrays.sort(ord, 0, n);
				int newBuried = 0;
				for (int i = 0; i < n; i++) {
					BlockPos h = far.get((int) ord[i]);
					if (farBuriedNow(h, now)) {
						newBuried++;
						continue;
					}
					if (columnWaits(h) || !planOk(h, flyTo, picker)) continue;
					Boolean w = farOk(p, b, h);
					if (w == null) {
						pending = true;
						farFullAt = now - 41; // (on with the rest next tick, not in 2 s)
						break;
					}
					if (w) {
						ok.add(h);
						farApproved = h;
						break;
					}
					if (blind == null && now - blindAt.getOrDefault(h, -100000L) > 2400) blind = h;
				}
				// (Buried ones among them, more further on: those further on next tick, without these - before flying anywhere
				// blind. The whole lot of a hill's buried spots are passed over a few thousand a tick, then forgotten in a minute.)
				if (ok.isEmpty() && !pending && newBuried > 0 && ord.length > n) {
					pending = true;
					farFullAt = now - 41;
				}
			}
			if (ok.isEmpty() && pending) farPendingAt = now;
			if (ok.isEmpty() && !pending && blind != null) {
				blindAt.put(blind.immutable(), now);
				if (blindAt.size() > 2000) blindAt.values().removeIf(at -> now - at > 2400);
				ok.add(blind);
				farApproved = blind;
				if (now - blindLogAt > 200) {
					blindLogAt = now;
					HumanAutopilot.LOGGER.info("[schem] nothing far off looks workable from here ({} looked over this time): over to {} to see it up close",
							farLooks - looked0, blind.toShortString());
				}
			}
			costEnd("pickfar", cFar);
		}
		// (Still looking the far ones over: that's all for this tick. Not "nothing to place" - its bridges, pillars and looks
		// over every hole of a big window were 237-254 ms hitches, for a pick that only had to wait a tick.)
		if (ok.isEmpty() && farPendingAt == now) return false;
		// (Not while shut in: out first. Built in under the 25k house's ground at the ravine's edge, a buried spot 57 blocks
		// off was the target every 5 s - "couldn't fly there" each time - and the knocking out of a way out, which only
		// comes with no target, waited 40 s for it.)
		if (ok.isEmpty() && lookup != null && now - digDownAt > 100 && !airShutIn) {
			// Nothing else to do, and spots of the build under a hill still to clear over them (far off, so the clearing
			// round us never got there): over to the top of the ground over the nearest and down from there, like a
			// player digging down to it (the user: "he doesn't know how to dig straight down").
			BlockPos nearestBuried = null;
			double nb = Double.MAX_VALUE;
			int looked = 0;
			for (BlockPos h : holes) {
				if (++looked > 4000) break;
				if (!hole(h) || cant.contains(h) || inRegion(h.getX(), h.getY(), h.getZ()) || !underHill(h)) continue;
				double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz, d = dx * dx + dz * dz;
				if (d < nb) {
					nb = d;
					nearestBuried = h;
				}
			}
			if (nearestBuried != null) {
				digDownAt = now;
				int g = Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						nearestBuried.getX(), nearestBuried.getZ()) - 1;
				BlockPos top = new BlockPos(nearestBuried.getX(), g, nearestBuried.getZ());
				if (Placement.breakable(Terrain.level(), top, Terrain.state(top)) && (clearOnTop(top) || hole(top))) {
					HumanAutopilot.LOGGER.info("[schem] {} is under the ground still to clear ({} over it): digging down to it from {}",
							nearestBuried.toShortString(), g - nearestBuried.getY(), top.toShortString());
					// (Straight to it: over the window's top, the next scan would drop it from the holes again.)
					setTarget(top);
					farApproved = top; // (the top of the ground: chosen to dig down from, no look from afar needed)
					return true;
				}
			}
		}
		if (ok.isEmpty()) {
			// Nothing else to do: the ones skipped for "nowhere to place it from" get their next go in a moment (that
			// check is instant, no flying about), so the last few are settled in seconds, not minutes of hovering.
			// (Only at the real end: every hole left has had no spot twice. With others still waiting on something - the
			// dining hall 13 minutes in, 194 to go - 38 logs were left this way that would have gone in later.)
			boolean allStuck = true;
			int openUps = 2; // (a few a pick: each tries the spots round it six times over)
			for (BlockPos h : holes) {
				if (cant.contains(h)) continue;
				if (!failWhy.getOrDefault(h, "").startsWith("nowhere") || fails.getOrDefault(h, 0) < 2) {
					allStuck = false;
					break;
				}
			}
			for (BlockPos h : bigWin ? inHolesOf(bad.keySet()) : holes) {
				Long until = bad.get(h);
				if (until == null || !failWhy.getOrDefault(h, "").startsWith("nowhere")) continue;
				// (Twice already with no spot, and nothing else is left that could change that: as it is, now. Tried every
				// 2 s they had him hover and glance from one to the next, "confused", for minutes at the end of the
				// gothic portal. It gets another go if something next to it changes.)
				if (allStuck && fails.getOrDefault(h, 0) >= 2 && !cant.contains(h)) {
					if (openUps-- > 0 && openUp(p, h, now)) return false;
					cant.add(h);
					HumanAutopilot.LOGGER.info("[schem] leaving {} ({}, has {}) as it is: nothing else left, and no spot for it twice", h.toShortString(),
							plan.get(h), Terrain.state(h));
					continue;
				}
				if (until > now + 40) bad.put(h, now + 40);
			}
			// Stuck: what's left can't be clicked in the right way round from anywhere (the first log of a row lying
			// on its side has nothing to click against that way). Let ONE go in any way round; the next ones can then
			// click against it properly.
			boolean waiting = false;
			for (BlockPos r : relaxed) if (hole(r)) waiting = true;
			if (!waiting) {
				for (BlockPos h : bigWin ? inHolesOf(nowhere.keySet()) : holes) {
					// Only if it could stay there at all (a lantern waiting for the arm it hangs from isn't stuck, it's early).
					// (Only blocks set to one of 16 directions, where the camera has to be just so; everything else gets a
					// temporary block to click on instead of going in wrong.)
					// (And a barrel: facing down on a floor needs a look up at it from below, out of anyone's reach; the
					// wrong way it's only its lid turned, a hole where it goes shows far more. The cottage: 11 left.)
					if (nowhere.getOrDefault(h, 0) >= 3 && !relaxed.contains(h) && hole(h)
							&& (Placement.fineRotation(plan.get(h)) || plan.get(h).getBlock() instanceof net.minecraft.world.level.block.BarrelBlock)
							&& plan.get(h).canSurvive(Terrain.level(), h)) {
						relaxed.add(h);
						bad.remove(h);
						HumanAutopilot.LOGGER.info("[schem] no way to put {} the right way round: any way will do for this one", h.toShortString());
						return false;
					}
				}
			}
			// Nothing at all to click against (TNT blew the ground out from under the build): put up one pillar from
			// whatever is below to the lowest layer, like a player would, and build out from it.
			// (The whole window in the air, nothing at all to click - a cliff top: a temporary bridge out to it first, it
			// comes out again; so does the dirt pillar, once the block it was for is in.)
			boolean noneWorkable = true;
			for (BlockPos h : holes) if (workable(h)) { noneWorkable = false; break; }
			if (!waiting && noneWorkable && now >= noBridgeUntil && bridgeFloating(p, now)) return false;
			// Pieces of the build touching nothing at all while something else is still "workable" but won't go in (the
			// floating pagoda: 2,256 blocks in 150 crystals and shards touching nothing, the island had a few spots waiting
			// on the ones under them - no bridge ever went out, and after 30 s all 3,600 were given up on). With nothing
			// to place for a couple of seconds: a temporary bridge out to the nearest loose piece, like a player.
			if (!waiting && !noneWorkable && now >= noBridgeUntil && cantGetSince >= 0 && now - cantGetSince > 40
					&& bridgeFloating(p, now, true)) return false;
			boolean bridging = false, bridgeStarted = false;
			for (BlockPos tmp : temps.keySet()) {
				BlockState w = plan.get(tmp);
				if (w == null || w.isAir()) continue;
				if (hole(tmp)) bridging = true;
				else bridgeStarted = true;
			}
			// A bridge out that hasn't got a single block in for 10 s (its first one out of reach: house 547 up on a
			// hilltop over a valley, the 10-block run of glass from below never started, 30 s of nothing and then the
			// whole house given up): dropped, and the dirt pillar instead.
			if (bridging && !bridgeStarted && now - bridgedAt > 200) {
				dropUnbuiltTemps();
				noBridgeUntil = now + 1200;
				bridging = false;
				HumanAutopilot.LOGGER.info("[schem] the temporary blocks out to the build never got started (10 s): a dirt pillar instead");
			}
			if (!waiting && !bridging && supportPillar(p)) return false;
			// Nothing workable in these layers: look higher, as far up as the build goes (a statue's wing hangs from
			// its shoulder 30 layers up: 24 never reached it, and one hole he couldn't get to kept the window down).
			int top = planBox != null ? planBox.maxY() - activeY : 24;
			if (extraLayers < top) {
				extraLayers = Math.min(top, extraLayers + (extraLayers < 24 ? 1 : 8));
			} else if (!waiting && bridgeFloating(p, now)) {
				// All of it up to the top hangs in the air with nothing to click: a temporary block (or a few) out to one.
				return false;
			}
			if (now - whyLoggedAt > 100 && !holes.isEmpty()) {
				whyLoggedAt = now;
				int nb = 0, nh = 0, nw = 0, ng = 0, nf = 0, nc = 0;
				// (Of a huge window, the ones round here: all 240k of them looked over was a 100 ms hitch every 5 s.)
				for (BlockPos h : bigWin ? holesNear(px, p.getY(), pz, 14, 12) : holes) {
					if (bad.containsKey(h)) nb++;
					else if (!hole(h)) nh++;
					else if (!workable(h)) nw++;
					else if (columnWaits(h)) nc++;
					else if (inRegion(h.getX(), h.getY(), h.getZ()) && !gettable(h, flyTo)) ng++;
					else if (picker.failed(plan.get(h).getBlock().asItem())) nf++;
				}
				BlockPos h0 = holes.get(0);
				HumanAutopilot.LOGGER.info("[schem] nothing to place in {} holes: skipped {}, filled {}, nothing to click {}, waiting on the one under {}, can't get to {}, no item {}; window {}..{}; air map {} cells round {} {} {}; first {} wants {} has {}",
						holes.size(), nb, nh, nw, nc, ng, nf, activeY, activeY + extraLayers, flyTo.size(), regX, regY, regZ, h0.toShortString(), plan.get(h0), Terrain.state(h0));
				// The last ones, one by one, once a minute: what each waits on (the cottage's last 65, 20 minutes of "nothing to
				// place" and nothing more to go on).
				if (holes.size() <= 150 && now - leftWhyAt > 1200) {
					leftWhyAt = now;
					StringBuilder sb = new StringBuilder();
					int k = 0;
					for (BlockPos h : holes) {
						if (k++ >= 60) break;
						// (A temporary block taken off the plan since the holes were listed: no wanted block - that crashed the
						// game on the hillside house, bridges going out.)
						if (plan.get(h) == null) continue;
						sb.append(h.toShortString()).append(' ')
								.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(plan.get(h).getBlock()).getPath())
								.append(": ").append(whyNotOk(h, flyTo, picker)).append("; ");
					}
					HumanAutopilot.LOGGER.info("[schem] what's left ({}): {}", holes.size(), sb);
				}
			}
			return false;
		}
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);
		boolean recent = lastPlaced != null && now - lastPlacedAt < 40;
		BlockPos step = recent && prevPlaced != null && prevPlaced.getY() == lastPlaced.getY() ? lastPlaced.subtract(prevPlaced) : null;
		if (step != null && step.distManhattan(BlockPos.ZERO) != 1) step = null;
		BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
		List<Vec3> others = otherPlayers(p);
		double reach = p.blockInteractionRange();
		BlockPos underUs = null;
		java.util.Set<Item> inHotbar = new java.util.HashSet<>();
		for (int i = 0; i < 9; i++) inHotbar.add(p.getInventory().getItem(i).getItem());
		// Round here, the lowest first (a layer above it at most): something blown out down below beats the roof.
		int okLow = ok.stream().mapToInt(BlockPos::getY).min().orElse(0);
		List<BlockPos> first = new ArrayList<>(ok);
		first.removeIf(h -> h.getY() > okLow + 1);
		// The build order, for any build: see buildOrder.
		if (!repairing0 && skyTop != null) {
			long cOrder = costStart();
			List<BlockPos> ordered;
			try {
				ordered = part != null ? orderPick(ok, first, part[0], part[1], flyTo, picker, px, pz) : buildOrder(ok, first, flyTo, picker, px, pz, now);
			} finally {
				costEnd("order", cOrder);
			}
			if (ordered == null) return false; // (the window is growing up the outside: next scan)
			first = ordered;
		}
		// What's coming up round here: what's worth keeping in the hotbar.
		upcoming = new HashMap<>();
		for (BlockPos h : first) {
			Item it = itemFor(h);
			if (it != null) upcoming.merge(it, 1, Integer::sum);
		}
		// This side of the doors first: of what's to do now, what's in the room we're in (got to without a doorway), if any
		// of it is; then through. (The awkward-blocks test: trapdoors by the door, each with its temporary block on the
		// other side of the wall - temporary block, through the door, trapdoor, back, the next... 7 doorways in 26 s.)
		java.util.Set<BlockPos> room = plan != null ? roomCells(p, b, now) : null;
		boolean roomKept = false;
		if (room != null) {
			List<BlockPos> here = new ArrayList<>();
			for (BlockPos h : first) if (inRoomFor(room, h)) here.add(h);
			if (!here.isEmpty() && here.size() < first.size()) {
				first = here;
				roomKept = true;
			}
		}
		// Something hanging from the block over it (an end rod under a ceiling) is put in from under that block: the
		// block it hangs from goes before the rest of its layer, and then it, straight away, while there's still a way
		// in under there (the basement test: its ceiling got finished first, and the five rods were shut in for good).
		if (!repairing0) {
			List<BlockPos> hang = new ArrayList<>();
			for (BlockPos h : first) if (hangsNow(h) || holdsHanger(h)) hang.add(h);
			// The same for a block whose temporary block(s) are in, from anywhere round here (whatever its ring): it goes in
			// now, and they come out again straight after, before what's round them closes them in (the hospital: a log
			// waited its turn for 7 minutes, the floor over its temporary glass went in, and the glass stayed for good).
			for (BlockPos h : ok) if (!hang.contains(h) && tempReady(h) && (!roomKept || inRoomFor(room, h))) hang.add(h);
			// Water running out where the build has blocks (a trough not closed yet): those first, they stop it (and a
			// plant it washed off can go back in once it's gone).
			int plugs = 0;
			for (BlockPos h : ok) {
				if (!hang.contains(h) && plugsWater(h)) {
					hang.add(h);
					plugs++;
				}
			}
			if (plugs > 0 && now - plugLogAt > 100) {
				plugLogAt = now;
				HumanAutopilot.LOGGER.info("[schem] water's running out: {} block(s) in its way first", plugs);
			}
			if (!hang.isEmpty()) {
				first = hang;
				if (now - hangLogAt > 40) {
					hangLogAt = now;
					HumanAutopilot.LOGGER.info("[schem] hanging ones first: {}", hang.stream().limit(6).map(h -> h.toShortString()
							+ (hangsNow(h) ? " (hangs)" : " (holds one)")).toList());
				}
			}
		}
		// A temporary block first (the block it's for is waiting on it); the structure before the little things on it
		// (torches, flowers, signs, pots, water: a builder decorates last, rather than hopping roof -> torch -> flower).
		if (first.stream().anyMatch(temps::containsKey)) first.removeIf(h -> !temps.containsKey(h));
		if (first.stream().anyMatch(h -> !decor(h))) first.removeIf(this::decor);
		// (And if none of those can be done from anywhere just now, anything else, rather than hanging about.)
		BlockPos best = null;
		List<Object[]> inReach = new ArrayList<>(), farList = new ArrayList<>();
		// Material first, like a builder: what's in hand (in reach, or up to 4 blocks on), then what's in the hotbar (8),
		// and only then something that needs the menu, the block most of these want (one trip for many). (The hospital's
		// ground floor, ~40 kinds of block, nearest first: 69 trips to the menu in 5 minutes, packed mud 7 times.)
		List<BlockPos> handL = new ArrayList<>(), barL = new ArrayList<>();
		for (BlockPos h : first) {
			Item it = itemFor(h);
			if (it == null || p.getMainHandItem().is(it)) handL.add(h);
			else if (inHotbar.contains(it)) barL.add(h);
		}
		barL.addAll(0, handL);
		List<List<BlockPos>> passes = new ArrayList<>();
		List<Double> farLimit = new ArrayList<>();
		List<String> passWhy = new ArrayList<>();
		// (Not further: flying 10 blocks to save rolling the wheel a notch or two, ~0.3 s, made it fly 46% of the time.)
		if (!handL.isEmpty()) {
			passes.add(handL);
			farLimit.add(4.0);
			passWhy.add("the block in hand");
		}
		if (barL.size() > handL.size()) {
			passes.add(barL);
			farLimit.add(8.0);
			passWhy.add("a block in the hotbar");
		}
		// (And a bit further for what's in the hotbar, before anything that needs the menu: a trip there is 2-3 s, a block
		// 8-16 off ~1.5 s of flying - like a player putting the dandelions in hand round the garden before getting the
		// ferns. The user's fantasy garden: 10-13 trips to the menu a minute, each kind fetched 5-6 times over.)
		// (Not with what's round the last one still to do, though: that first, menu or not - the user's "starts the far left
		// wall, then goes to the middle or the right wall before the left one is done". The 12-16 block hops for a block in
		// the hotbar were 6 in 30 minutes of the user's fantasy build, each leaving 1-9 behind.)
		int localLeft = 0;
		if (lastPlaced != null && now - lastPlacedAt < 200) {
			for (BlockPos h : first) {
				if (Math.hypot(h.getX() - lastPlaced.getX(), h.getZ() - lastPlaced.getZ()) <= 6 && h.getY() <= lastPlaced.getY() + 2) localLeft++;
			}
		}
		if (!barL.isEmpty() && first.size() > barL.size() && localLeft < 2) {
			passes.add(barL);
			farLimit.add(16.0);
			passWhy.add("a block in the hotbar, a bit further");
		}
		passes.add(first);
		farLimit.add(Double.MAX_VALUE);
		passWhy.add(lastPhase >= 0 ? PARTS[lastPhase] + " first" : "the lowest layers first");
		if (!first.equals(ok) || !later.isEmpty()) {
			passes.add(ok);
			farLimit.add(Double.MAX_VALUE);
			passWhy.add("nothing of that could be done: anything near");
		}
		int bestPass = -1;
		// Inside (under a roof): what's in this room first, then through a doorway to the next (the fantasy house's
		// upper floor: the nearest block was through a wall in the next room - 16 doorways walked through in 7 minutes,
		// each a landing and a take-off).
		for (int pass = 0; pass < passes.size(); pass++) {
			List<BlockPos> list = passes.get(pass);
			if (list == ok && !later.isEmpty()) {
				// (The ones round here left for later above: their checks now, nothing else would do.)
				for (BlockPos h : later) if (nearOk(h, plan.get(h), flyTo, picker)) ok.add(h);
				later.clear();
			}
			double maxFar = farLimit.get(pass);
			inReach.clear();
			farList.clear();
			java.util.Set<BlockPos> okSetP = repairing0 ? java.util.Set.of() : new java.util.HashSet<>(ok);
			for (BlockPos h : list) {
				// (Not what's right behind an upside-down stair still to go in while that stair - or what it's clicked
				// against - can go in now, in any pass: that first, firstInstead.)
				if (!repairing0 && firstInstead(h, okSetP) != null) continue;
				Vec3 c = Vec3.atCenterOf(h);
				if (h.equals(below) || h.equals(below.below()) || p.getBoundingBox().inflate(0.05).intersects(new AABB(h))) {
					underUs = h;
					continue;
				}
				if (nearest(others, c) < 2.0) continue; // someone's standing there
				boolean sameRow = recent && lastPlaced != null && h.getY() == lastPlaced.getY()
						&& (step == null ? h.getZ() == lastPlaced.getZ() : step.getX() != 0 ? h.getZ() == lastPlaced.getZ() : h.getX() == lastPlaced.getX());
				// What's already in the hotbar first (a player puts down all the cobblestone round them, then gets the
				// next block), the one in hand most of all: fetching a new kind for every block was a trip to the menu
				// each time.
				Item it = itemFor(h);
				boolean inHand = it == null || p.getMainHandItem().is(it), inBar = inHand || inHotbar.contains(it);
				// Stay on the part we're doing (it used to drop the roof for a wall, then a torch, then a flower).
				boolean onPart = recent && lastPlaced != null && h.distManhattan(lastPlaced) <= 2;
				double d = c.distanceTo(eye);
				int want = upcoming.getOrDefault(it, 0); // (needing the menu: the block most of them want)
				if (d <= maxFar) {
					farList.add(new Object[]{h, d - (sameRow ? 1.5 : 0) - (onPart ? 2 : 0) - (inBar ? 4 : 0) - (inHand ? 2 : 0)
							- Math.min(4, 0.4 * want) + 1.5 * (h.getY() - layerLow) + (room != null && !inRoom(room, h) ? 12 : 0)});
				}
				if (d > reach + 0.6) continue;
				double turn = Math.toDegrees(Math.acos(Math.clamp(look.dot(c.subtract(eye).normalize()), -1, 1)));
				// (What's in the hotbar well first: a trip to the menu is ~2 s, a turn of 40 degrees a quarter of a second.
				// And what's in hand before that: the tree's crown, a different block nearly every time, spent a fifth of
				// the time rolling the wheel to the next slot.)
				double score = turn - (sameRow ? 10 : 0) - (onPart ? 15 : 0) - (inBar ? 40 : 0) - (inHand ? 25 : 0) - Math.min(30, 3.0 * want)
						+ 8 * (h.getY() - layerLow);
				if (recent && step != null && h.equals(lastPlaced.offset(step))) score -= 25;
				else if (recent && h.distManhattan(lastPlaced) == 1) score -= 6;
				inReach.add(new Object[]{h, score});
			}
			inReach.sort((x, y) -> Double.compare((double) x[1], (double) y[1]));
			for (int i = 0; i < Math.min(40, inReach.size()) && best == null; i++) {
				BlockPos h = (BlockPos) inReach.get(i)[0];
				if (faceFrom(p, eye, look, h) != null && leavesWayOut(p, b, h)) best = h;
			}
			if (best == null) {
				farList.sort((x, y) -> Double.compare((double) x[1], (double) y[1]));
				// One in sight first, if it's not much further than the nearest (on our side of the wall: by straight-line
				// distance alone the cottage's next block was through the door, out, then the next one back in: 7 trips
				// through it in 18 s). A builder does what they can see, then goes round.
				int n = Math.min(30, farList.size());
				double nearestScore = n > 0 ? (double) farList.get(0)[1] : 0;
				for (int i = 0; i < n && best == null; i++) {
					if ((double) farList.get(i)[1] > nearestScore + 8) break;
					BlockPos h = (BlockPos) farList.get(i)[0];
					if (inSight(p, eye, h) && leavesWayOut(p, b, h)) best = h;
				}
				for (int i = 0; i < n && best == null; i++) {
					BlockPos h = (BlockPos) farList.get(i)[0];
					if (leavesWayOut(p, b, h)) best = h;
				}
			}
			if (best != null) {
				bestPass = pass;
				break;
			}
		}
		if (best == null) best = underUs;
		// Off somewhere else with what we were on not done (the user: "he's building the far left wall then heads to the
		// middle or far right wall instead of finishing the left wall"): which rule sent us, for the log.
		if (best != null && lastPlaced != null && now - jumpLogAt > 60) {
			double jd = Math.hypot(best.getX() - lastPlaced.getX(), best.getZ() - lastPlaced.getZ());
			if (jd > 12) {
				int left = 0;
				for (BlockPos h : first) if (Math.hypot(h.getX() - lastPlaced.getX(), h.getZ() - lastPlaced.getZ()) <= 6 && h.getY() <= lastPlaced.getY() + 2) left++;
				if (left > 0) {
					jumpLogAt = now;
					HumanAutopilot.LOGGER.info("[schem] off to {} ({} away) with {} still to do round {}: {}", best.toShortString(), Math.round(jd), left,
							lastPlaced.toShortString(), bestPass >= 0 ? passWhy.get(bestPass) : "only the one under us");
				}
			}
		}
		if (best == null && now - whyLoggedAt > 100) {
			whyLoggedAt = now;
			BlockPos me = BlockPos.containing(p.getX(), p.getY() + 0.1, p.getZ());
			HumanAutopilot.LOGGER.info("[schem] {} placeable but none picked: {} in reach, {} listed, me {} sky open {}, first {}",
					ok.size(), inReach.size(), farList.size(), me.toShortString(), skyOpen(b, me.getX(), me.getY(), me.getZ()),
					ok.get(0).toShortString());
		}
		if (best != null) setTarget(best);
		return best != null;
	}

	/** Spots given temporary blocks under them already (see gapTemps): once each. */
	private final java.util.Set<BlockPos> gapTempsTried = new java.util.HashSet<>();

	/**
	 * A full block over a gap of 1-3 open spots with solid ground under them (nothing of the build planned in the gap):
	 * temporary blocks up the gap to it, so it goes in on top of them (they come out again once it's in).
	 */
	private boolean gapTemps(BlockPos t) {
		BlockState want = plan.get(t);
		if (want == null || want.isAir() || jobAt(t) != Placement.Job.PLACE || Placement.fluid(want) || temps.containsValue(t) || temps.containsKey(t)
				|| !want.isCollisionShapeFullBlock(Terrain.level(), t) || want.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
		// (Only if a click on the top of what's under it puts it in the right way round: a log lying on its side wouldn't.)
		if ((rightSides(want) & (1 << Direction.DOWN.ordinal())) == 0) return false;
		List<BlockPos> col = new ArrayList<>();
		BlockPos c = t.below();
		while (col.size() < 3) {
			BlockState w = Terrain.state(c), pw = plan.get(c);
			if (!(w.isAir() || (w.canBeReplaced() && w.getFluidState().isEmpty())) || (pw != null && !pw.isAir()) || temps.containsKey(c)) break;
			col.add(c);
			c = c.below();
		}
		if (col.isEmpty() || !solid(c)) return false;
		for (BlockPos q : col) {
			boolean planned = plan.containsKey(q);
			plan.put(q, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
			if (!planned) {
				planLayers.computeIfAbsent(q.getY(), y -> new ArrayList<>()).add(q);
				planList.add(q);
			}
			temps.put(q.immutable(), t.immutable());
			scanFromY = Math.min(scanFromY, q.getY());
			remember(q, jobAt(q));
		}
		bad.remove(t);
		HumanAutopilot.LOGGER.info("[schem] {} ({}) floats over a {}-high gap: {} temporary block(s) under it from the ground",
				t.toShortString(), want.getBlock().getName().getString(), col.size(), col.size());
		return true;
	}

	/** Puts the {@code k} smallest of {@code a} (in no order) into its first {@code k} places: quickselect, O(n). */
	private static void selectSmallest(long[] a, int k) {
		int lo = 0, hi = a.length - 1;
		java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
		while (lo < hi) {
			long pivot = a[lo + rnd.nextInt(hi - lo + 1)];
			int i = lo, j = hi;
			while (i <= j) {
				while (a[i] < pivot) i++;
				while (a[j] > pivot) j--;
				if (i <= j) {
					long t = a[i];
					a[i] = a[j];
					a[j] = t;
					i++;
					j--;
				}
			}
			// (a[lo..j] <= pivot <= a[i..hi]; the k-th place is in one of them, or between)
			if (k - 1 <= j) hi = j;
			else if (k - 1 >= i) lo = i;
			else return;
		}
	}

	/** A damaged spot to repair: the spot itself, or what it waits on that has to go in first; null if neither can go now. */
	private BlockPos repairStep(BlockPos h0) {
		// (The build's own grass hiding it, to take out first: that.)
		if (!outFor.isEmpty()) {
			for (var e : outFor.entrySet()) if (e.getValue().equals(h0) && jobAt(e.getKey()) == Placement.Job.BREAK) return e.getKey();
		}
		// Waiting on the spot under it, a hole that isn't damage itself (a bush's lower leaves that never went in,
		// held back by the order of the parts): that one first, as part of the repair. (The wizard tower's leaves:
		// "damaged spots, none to do now: waits on its column" for minutes while the ground went in.)
		BlockPos h = h0;
		// (Or for its support: a post's log over the crater, the dirt under it not damage - or the piece over it, a
		// post blown out from under its top: the barn's corner posts, the bottom two pieces each waiting on the other
		// for the rest of the test. The support that's coming is the one to do first, up or down.)
		for (int i = 0; i < 8 && h != null && (columnWaits(h) || waitsOnSupport(h)); i++) {
			BlockPos sup = columnWaits(h) ? null : supportComing(h);
			// (Not down into what can't be knocked out: the copper walls' grass-level damage led down to the bedrock under
			// it, picked, "isn't to do any more", picked again - 2 hours of one block, a turn round, one block.)
			BlockPos dn = h.below();
			BlockState ds = Terrain.state(dn);
			boolean dnOk = hole(dn) && (ds.isAir() || ds.canBeReplaced() || Placement.breakable(Terrain.level(), dn, ds));
			h = sup != null ? sup : dnOk ? dn : null;
		}
		// Nothing next to it to click on (what held it went too, and that wasn't damage: the wall beside a bush,
		// not built yet): a hole of the build beside it that has something to click, first.
		if (h != null && !columnWaits(h) && jobAt(h) == Placement.Job.PLACE && !workable(h)) {
			for (Direction d : Direction.values()) {
				BlockPos n = h.relative(d);
				BlockState w = plan.get(n);
				if (w == null || w.isAir() || !hole(n) || jobAt(n) != Placement.Job.PLACE || !workable(n) || columnWaits(n)) continue;
				h = n;
				break;
			}
		}
		return h == null || columnWaits(h) || waitsOnSupport(h) ? null : h;
	}

	/** A hole round us that can go in now (pickPlanTarget's near ones). */
	private boolean nearOk(BlockPos h, BlockState wantH, Map<BlockPos, BlockPos> flyTo, CreativePicker picker) {
		// (Deep in a hill to be cleared, more of it right over it still to go: it waits on that one, known from the holes
		// alone - columnWaits looked each of the mountain's ~10000 cells round us over on every pick, 17-28 ms picks
		// and 11% of ticks over 5 ms on the 25k house's mountainside.)
		if (wantH != null && wantH.isAir()) {
			var hsUp = holesByLayer.get(h.getY() + 1);
			BlockPos up = h.above();
			if (hsUp != null && hsUp.contains(up.asLong()) && !cant.contains(up)) {
				BlockState wu = Terrain.state(up);
				if (!wu.isAir() && !wu.canBeReplaced() && wu.getFluidState().isEmpty() && !wu.is(net.minecraft.tags.BlockTags.LEAVES)) return false;
			}
		}
		// (Buried: six quick looks, before columnWaits - see openFace.)
		if (!openFace(h)) {
			buriedPassed++;
			return false;
		}
		return !columnWaits(h) && planOk(h, flyTo, picker);
	}

	/**
	 * The window's holes within {@code r} across (and {@code dy} up or down) of here, lowest layer first, from the memory
	 * of holes spot by spot (a few thousand looks, not the whole window gone through); damage under or over the window
	 * too, like the holes (see scanPlan).
	 */
	private List<BlockPos> holesNear(double px, double py, double pz, int r, int dy) {
		List<BlockPos> out = new ArrayList<>();
		int mx = Mth.floor(px), mz = Mth.floor(pz);
		for (int y : winLayers) {
			if (Math.abs(y + 0.5 - py) > dy) continue;
			var set = holesByLayer.get(y);
			if (set == null || set.isEmpty()) continue;
			for (int x = mx - r; x <= mx + r; x++) {
				double dx = x + 0.5 - px;
				for (int z = mz - r; z <= mz + r; z++) {
					double dz = z + 0.5 - pz;
					if (dx * dx + dz * dz > r * r || !set.contains(BlockPos.asLong(x, y, z))) continue;
					out.add(new BlockPos(x, y, z));
				}
			}
		}
		if (!damaged.isEmpty() && !winLayers.isEmpty()) {
			int wLo = winLayers.get(0), wHi = winLayers.get(winLayers.size() - 1);
			boolean added = false;
			for (long k : damaged) {
				int y = BlockPos.getY(k);
				if (y >= wLo && y <= wHi || Math.abs(y + 0.5 - py) > dy) continue;
				double dx = BlockPos.getX(k) + 0.5 - px, dz = BlockPos.getZ(k) + 0.5 - pz;
				BlockPos c = BlockPos.of(k);
				if (dx * dx + dz * dz > r * r || !hole(c)) continue;
				out.add(c);
				added = true;
			}
			if (added) out.sort(java.util.Comparator.comparingInt(BlockPos::getY));
		}
		return out;
	}

	/** Of these spots, the ones in the holes, lowest layer first (instead of all the holes gone through for them). */
	private List<BlockPos> inHolesOf(java.util.Collection<BlockPos> spots) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos c : spots) if (inHoles(c)) out.add(c);
		out.sort(java.util.Comparator.comparingInt(BlockPos::getY));
		return out;
	}

	/** In the holes (as the memory has it now): in the window, a hole there; under or over it, damage still out. */
	private boolean inHoles(BlockPos c) {
		int y = c.getY();
		if (!winLayers.isEmpty() && y >= winLayers.get(0) && y <= winLayers.get(winLayers.size() - 1)) {
			var set = holesByLayer.get(y);
			return set != null && set.contains(c.asLong());
		}
		return damaged.contains(c.asLong()) && hole(c);
	}

	/** The spot (or a block right next to it, what we'd click) is in plain sight from {@code eye}: nothing else in between. */
	private static boolean inSight(LocalPlayer p, Vec3 eye, BlockPos h) {
		Vec3 c = Vec3.atCenterOf(h);
		var hit = p.level().clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(c) < 1.0;
	}

	private boolean outFirstTried;
	private long outFirstUntil = -1;
	/** When the last 5 blocks went in (the wind-down at the end), and when this build started. */
	private final java.util.ArrayDeque<Long> placedAts = new java.util.ArrayDeque<>();

	/** Fewer than {@code n} blocks in in the last 3 minutes (placedAts keeps the last 30). */
	private boolean placedFewerThan(int n, long now) {
		if (placedAts.size() < n) return now - planStartedAt > 3600;
		int i = 0, from = placedAts.size() - n;
		for (long at : placedAts) if (i++ == from) return now - at > 3600;
		return false;
	}
	private long planStartedAt;
	private long hillLogAt = -10000;
	private long digDownAt = -10000;
	private long cantGetSince = -1, hangLogAt = -1000, cantSeeLogAt = -1000, plugLogAt = -1000, jumpLogAt = -1000;
	private long whyLoggedAt = -1000, pickLoggedAt = -1000, droppedLogAt = -1000, noWayOutLogAt = -1000, repairLogAt = -1000;

	/**
	 * Outside in, like a builder (the user: "build from the outside going in instead of just randomly building
	 * anywhere"): while a layer's outside (ring 1, see ringAt) still has something to put in, anywhere round the build,
	 * its inside (ring 3) waits; the row just inside the outside (2: the back of a thick wall, the edge of a floor) goes
	 * with it. Nothing of that near: the nearest of it, over there. (Only a preference: if none of it can be picked, the
	 * rest of {@code ok} still gets its go.)
	 */
	private List<BlockPos> outsideIn(List<BlockPos> first, int low, Map<BlockPos, BlockPos> flyTo, CreativePicker picker,
			double px, double pz) {
		if (skyTop == null) return first;
		boolean[] outside = new boolean[2];
		for (BlockPos h : holes) {
			int k = h.getY() - low;
			if (k < 0 || k > 1 || outside[k] || ringAt(h) != 1 || !ringed(h)) continue;
			// (One waiting on the wall under it counts too: it's coming. The roof's middle went in while its front edge
			// waited for the top of the front wall.)
			if (columnWaits(h) ? !bad.containsKey(h) : planOk(h, flyTo, picker)) outside[k] = true;
		}
		if (!outside[0] && !outside[1]) return first;
		List<BlockPos> in = new ArrayList<>(first);
		in.removeIf(h -> outside[h.getY() - low] && ringAt(h) == 3 && ringed(h));
		if (in.stream().noneMatch(this::ringed)) {
			BlockPos go = null;
			double best = Double.MAX_VALUE;
			for (BlockPos h : holes) {
				int k = h.getY() - low;
				if (k < 0 || k > 1 || !outside[k] || ringAt(h) == 3 || !ringed(h)) continue;
				double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz, d = dx * dx + dz * dz + 9 * k * k + (nowhere.containsKey(h) ? 1e6 : 0);
				if (d >= best || columnWaits(h) || !planOk(h, flyTo, picker)) continue;
				best = d;
				go = h;
			}
			if (go != null) in.add(go);
		}
		return in;
	}

	/**
	 * The build order, for any build (the user: "he builds the outer layer of the castle walls first, then after the
	 * walls, the outer layer top if any, then the first layer inside and 2nd and so on"; "it's tied to general
	 * building"; "make sure he builds outside going up instead of going outside then inside layer to layer"):
	 * 0. the ground layers it stands in (paths, the courtyard's floor), like laying the foundation;
	 * 1. the outside (rings 1-2 of each layer, see ringAt), from the bottom up, all the way to the top;
	 * 2. then the top over the inside (coversInside + roofOf: a flat roof's middle), bottom up;
	 * 3. then the inside, a storey (4 layers) at a time from the bottom.
	 * A block at the top of its column (a roof) that would close in inside still to build, with no other way in, waits:
	 * that one is the hatch (sealsInside), and goes in once the inside under it is done.
	 * Of the OK holes near us, the ones of the earliest part that has any anywhere (near or far), in its lowest two
	 * layers; none of them near: the nearest over there. Temporary blocks and clearing out aren't held back. Null:
	 * the outside goes higher than the window; it's been made taller, so nothing this tick. {@code band0}: the old
	 * choice (the lowest two layers of what's OK near us), for when there's no part to go by.
	 */
	private List<BlockPos> buildOrder(List<BlockPos> ok, List<BlockPos> band0, Map<BlockPos, BlockPos> flyTo, CreativePicker picker,
			double px, double pz, long now) {
		int[] pb = orderPart(flyTo, picker, now);
		return pb == null ? null : orderPick(ok, band0, pb[0], pb[1], flyTo, picker, px, pz);
	}

	/**
	 * buildOrder's first half, from the holes alone: the part to do now and its lowest layer, {part, layer} (part -1: no
	 * part to go by), or null when the window has just been made taller (nothing this tick).
	 */
	private int[] orderPart(Map<BlockPos, BlockPos> flyTo, CreativePicker picker, long now) {
		// (Each part's structure before its decoration: torches, flowers and grass don't hold its layers down. The
		// castle's flowers round its foot kept the walls at 2 high while he flew round planting them.)
		int[] low = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] lowDecor = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] count = new int[4]; // (how much of each part's structure can be done now, up to STICK)
		int[] tried = new int[4]; // (checks per part once its lowest is known: a few hundred is plenty to count to STICK)
		// (At most 3000 holes, lowest first: that's where each part's lowest is. All 17000 of the copper guard's, every
		// pick, was a quarter of a second each 30 s and 28% of ticks over 5 ms.)
		int seen = 0;
		boolean cut = false;
		for (BlockPos h : holes) {
			if (++seen > 3000) {
				cut = true;
				break;
			}
			int ph = phaseOf(h);
			if (ph < 0) continue;
			if (low[ph] != Integer.MAX_VALUE && tried[ph]++ > 300) continue;
			// (One that's had no spot to be put in from twice doesn't call us back to its part: the cottage's stuck stairs
			// had him leave the inside for the outside 166 times. Those come at the end, with whatever's left.)
			// (After a run of skips with nothing put in, one "no spot" is enough: the 25k house's ground over the ravine,
			// ~40 spots under a tree's crown each tried twice first - 4 minutes "confused", flying from one to the next,
			// before the outside started.)
			if (nowhere.getOrDefault(h, 0) >= (failStreak >= 6 ? 1 : 2)) continue;
			int y = h.getY();
			if (low[ph] != Integer.MAX_VALUE && y >= low[ph] && count[ph] >= STICK) continue; // (lowest known, enough of it)
			boolean dec = decor(h);
			if (dec && y >= lowDecor[ph]) continue;
			if (columnWaits(h) || !planOk(h, flyTo, picker) || hatch(h, ph)) continue;
			if (dec) lowDecor[ph] = Math.min(lowDecor[ph], y);
			else {
				low[ph] = Math.min(low[ph], y);
				// (Ones that had no spot once already don't count: the badlands villager's plate under the hill passed
				// every check, failed, came back when its skip ran out: ground, outside, ground... every second or two.)
				if (!nowhere.containsKey(h)) count[ph]++;
			}
		}
		// The ground and the outside done as far as the window goes, but the build goes higher: the window reaches on up
		// (the inside holds the lowest layer open the whole time the walls go up, to the top: a cap of 24 layers had the
		// castle's walls wait half way up for its inside).
		if (!cut && low[0] == Integer.MAX_VALUE && low[1] == Integer.MAX_VALUE && planBox != null && activeY + extraLayers < planBox.maxY()) {
			extraLayers = Math.min(planBox.maxY() - activeY, Math.max(extraLayers + 4, 8));
			scannedAt = -1000; // (look the new layers over at once)
			if (now - orderLogAt > 100) {
				orderLogAt = now;
				HumanAutopilot.LOGGER.info("[schem] the outside's done up to y {}: on up (window {}..{})", activeY + extraLayers - 4,
						activeY, activeY + extraLayers);
			}
			return null;
		}
		for (int i = 0; i < low.length; i++) if (low[i] == Integer.MAX_VALUE) low[i] = lowDecor[i];
		int ph = -1;
		for (int i = 0; i < low.length && ph < 0; i++) if (low[i] < Integer.MAX_VALUE) ph = i;
		if (ph < 0) return new int[]{-1, 0};
		// On to a later part: back to an earlier one only for real work there (STICK blocks or more that can go in now),
		// not for a straggler or two whose skip ran out (the special medieval house's last outside spots pulled him out of
		// the inside and back every second or two: "building the outside", "building the inside", 30 times a minute).
		// The stragglers come once this part's done, or with the rest at the end.
		// (And not within 20 s of getting there: the snowy cottage's outside came back in waves - a band done, the next
		// waiting on a block or two - and he went outside, inside, outside... every 15 s for 8 minutes, 24 switches.)
		// (And in fact not while this part still has anything to do now - count stops at STICK, so 3 x STICK is never
		// reached: the oak-hill cottage went outside, inside, outside... 5 times in 4 minutes, each wave of the outside a
		// handful of blocks. Finish what's to do in here, then back out.)
		// (And while there's anything to do in here right now, up to 2 minutes: the fantasy house went back out every
		// time the outside had a wave of work, in and out through its door - 16 "landing by the door" in 15 minutes,
		// 373 of its 614 outside blocks after the inside had started.)
		if (lastPhase > ph && lastPhase < low.length && low[lastPhase] < Integer.MAX_VALUE
				&& (count[ph] < 3 * STICK || now - phaseSince < 400 || (count[lastPhase] > 0 && now - phaseSince < 2400))) ph = lastPhase;
		if (ph != lastPhase) {
			HumanAutopilot.LOGGER.info("[schem] building {} (from y {})", PARTS[ph], low[ph]);
			// On to a later part with an earlier one not done: why none of that one could be done now (the fantasy
			// house's inside started 2.5 minutes in, 373 of the outside's 614 blocks still to come, and nothing said why).
			if (lastPhase >= 0 && ph > lastPhase) {
				StringBuilder why = new StringBuilder();
				int n = 0, all = 0;
				for (BlockPos h : holes) {
					if (phaseOf(h) != lastPhase) continue;
					all++;
					if (n < 5) {
						why.append(h.toShortString()).append(' ').append(whyNotOk(h, flyTo, picker)).append("; ");
						n++;
					}
				}
				if (all > 0) HumanAutopilot.LOGGER.info("[schem] {} has {} still to do, none now: {}", PARTS[lastPhase], all, why);
			}
			lastPhase = ph;
			phaseSince = now;
		}
		return new int[]{ph, low[ph]};
	}

	/** buildOrder's second half: of {@code ok}, what's to do now for part {@code ph}, from layer {@code band} up. */
	private List<BlockPos> orderPick(List<BlockPos> ok, List<BlockPos> band0, int ph, int band, Map<BlockPos, BlockPos> flyTo,
			CreativePicker picker, double px, double pz) {
		List<BlockPos> l = orderPick0(ok, band0, ph, band, flyTo, picker, px, pz);
		return l == null || l.isEmpty() ? l : notBehindStairs(l, new java.util.HashSet<>(ok));
	}

	/**
	 * The upside-down stair still to go in that {@code h} is right behind, on the side a player puts it in from, or null:
	 * in first, h would leave the stair's only right-way click (the upper half of the face beyond it, looking the way it
	 * faces) out of reach behind it - the stair should go first (firstInstead). (The Himeji castle's eaves: the outer row
	 * of upside-down stairs and the wall on it went up first, and the inner row's only click left was from right over it,
	 * too steep - 25 "nowhere to place it from", 20 of the build's blocks taken out and put back to get at them.) Not
	 * while that stair's been given up on, not for a temporary block or one going back in, and for 5 minutes at most.
	 */
	private BlockPos stairInFront(BlockPos h) {
		if (temps.containsKey(h) || putBack.contains(h)) return null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos a = h.relative(d);
			BlockState wa = plan.get(a);
			if (wa == null || !(wa.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
					|| wa.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
					!= net.minecraft.world.level.block.state.properties.Half.TOP
					|| wa.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING) != d) continue;
			if (cant.contains(a) || !hole(a) || jobAt(a) != Placement.Job.PLACE) continue;
			if (jobAt(h) != Placement.Job.PLACE) return null;
			// (Not for ever: 5 minutes at most - whatever that stair waits on may wait on something that waits on this.)
			long now = HumanAutopilot.ticks();
			long since = behindSince.computeIfAbsent(h.immutable(), k -> now);
			return now - since < 6000 ? a : null;
		}
		return null;
	}

	/**
	 * What to do instead of {@code h} for now (stairInFront): the stair in front of it if that can go in now, else what
	 * the stair is clicked against (beyond it, the way it faces) if that can; null when neither can - then h goes in as
	 * it is, rather than the wall waiting: held back with nothing else to do round it, the castle-top test's sections
	 * of wall jumped from one side of the keep to the other (20-30 blocks at a time, 6 before).
	 */
	private BlockPos firstInstead(BlockPos h, java.util.Set<BlockPos> okSet) {
		if (NO_FIRST) return null;
		BlockPos a = stairInFront(h);
		if (a == null) return null;
		if (okSet.contains(a)) return a;
		BlockState wa = plan.get(a);
		Direction f = wa.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
		BlockPos ref = a.relative(f);
		if (okSet.contains(ref)) return ref;
		// (Or one along its row that can: a row of them goes in one from the next, from its end - the castle's eaves had
		// the stair in front of each outer block waiting on its neighbour, so the outer row went in first after all, and
		// the inner one was "nowhere" again.)
		for (Direction side : new Direction[]{f.getClockWise(), f.getCounterClockWise()}) {
			BlockPos c = a;
			for (int i = 0; i < 16; i++) {
				c = c.relative(side);
				BlockState wc = plan.get(c);
				if (wc == null || !wc.equals(wa)) break; // (the row's end)
				if (okSet.contains(c)) return c;
			}
		}
		return null;
	}

	/** Spots left for good written down this session (caseFile). */
	private int casesWritten;

	/**
	 * A spot left as it is for good, written down with what's round it - the world's blocks and what the build wants
	 * there, 13 across - so a test can put the same thing back together and try it again (the gametest's HA_CASE).
	 * Into .minecraft/humanautopilot-cases/, at most 40 a session. (The user's game had spots no test had: dirt over a
	 * cave, grass under an overhang, stone in a pit - only "no spot for" in the log to go by.)
	 */
	private void caseFile(BlockPos t, String why) {
		if (casesWritten >= 40 || plan == null) return;
		casesWritten++;
		try {
			var mc = Minecraft.getInstance();
			java.nio.file.Path dir = mc.gameDirectory.toPath().resolve("humanautopilot-cases");
			java.nio.file.Files.createDirectories(dir);
			// (19 across: what closes off the way in can be further than the spots it's seen from - the castle's eave stair
			// went in at once in a 13-across copy standing in the open, its corridor shut beyond the copy's edge.)
			int r = 9;
			Map<String, Integer> pal = new java.util.LinkedHashMap<>();
			StringBuilder w = new StringBuilder(), pl = new StringBuilder();
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int dx = -r; dx <= r; dx++) {
						BlockPos c = t.offset(dx, dy, dz);
						String ws = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(Terrain.state(c));
						if (w.length() > 0) w.append(',');
						w.append(pal.computeIfAbsent(ws, k -> pal.size()));
						// What the build wants there: its block, the schematic's own air where it clears, or nothing (as it is).
						BlockState ps = plan.get(c);
						if (ps == null && lookup != null && c.getY() >= clearFromY) ps = lookup.apply(c);
						if (pl.length() > 0) pl.append(',');
						pl.append(ps == null ? -1 : pal.computeIfAbsent(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(ps), k -> pal.size()));
					}
				}
			}
			var me = mc.player;
			StringBuilder out = new StringBuilder("{\"why\":\"").append(why.replace("\"", "'")).append("\",\"target\":[")
					.append(t.getX()).append(',').append(t.getY()).append(',').append(t.getZ()).append("],\"r\":").append(r)
					.append(",\"player\":[").append(me == null ? "0,0,0" : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", me.getX(), me.getY(), me.getZ()))
					.append("],\"replace\":").append(replace).append(",\"openSpots\":").append(lastOpenSpots)
					.append(",\"palette\":[");
			boolean first = true;
			for (String k : pal.keySet()) {
				if (!first) out.append(',');
				first = false;
				out.append('"').append(k.replace("\"", "'")).append('"');
			}
			out.append("],\"world\":[").append(w).append("],\"plan\":[").append(pl).append("]}").append(System.lineSeparator());
			java.nio.file.Path f = dir.resolve("case-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
					+ "-" + t.getX() + "_" + t.getY() + "_" + t.getZ() + ".json");
			java.nio.file.Files.writeString(f, out);
			HumanAutopilot.LOGGER.info("[schem] wrote {} down for a test: {}", t.toShortString(), f.getFileName());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't write the case down", e);
		}
	}

	/** When the last few were last left as they are for being too slow to get in (winding down). */
	private long windDownAt = -1_000_000;

	/** Which 16 layers far under us the clear scan looks at this time (see scanPlan). */
	private int clearSlab;

	/** The tests' HA_NOFIRST: without firstInstead (to see what it's for). */
	private static final boolean NO_FIRST = System.getenv("HA_NOFIRST") != null;

	/** Spots held back by stairInFront/firstInstead, since when. */
	private final Map<BlockPos, Long> behindSince = new HashMap<>();

	/** {@code l} with what's right behind an upside-down stair still to go in swapped for what goes first (firstInstead). */
	private List<BlockPos> notBehindStairs(List<BlockPos> l, java.util.Set<BlockPos> okSet) {
		List<BlockPos> keep = null;
		java.util.Set<BlockPos> in = null;
		for (int i = 0; i < l.size(); i++) {
			BlockPos h = l.get(i);
			BlockPos f = firstInstead(h, okSet);
			if (f != null) {
				if (keep == null) {
					keep = new ArrayList<>(l.subList(0, i));
					in = new java.util.HashSet<>(keep);
				}
				if (in.add(f)) keep.add(f);
			} else if (keep != null && in.add(h)) keep.add(h);
		}
		return keep == null ? l : keep;
	}

	private List<BlockPos> orderPick0(List<BlockPos> ok, List<BlockPos> band0, int ph, int band, Map<BlockPos, BlockPos> flyTo,
			CreativePicker picker, double px, double pz) {
		if (ph < 0) return band0;
		// The walls a section at a time: the stretch round one spot (about 10 wide), up to 6 rows over the lowest, before
		// the next stretch along - not a row or two right round the whole build and then the next row (the user: "he
		// starts the far left wall, then goes to the middle or the right wall before the left one is done"). The spot
		// stays put till its stretch has nothing more to do now; then the next one is the nearest of the lowest row.
		if (ph == 1) {
			List<BlockPos> sec = sectionOf(ok, band + 5);
			if (sec == null) {
				secAnchor = null;
				double bestD = Double.MAX_VALUE;
				for (BlockPos h : ok) {
					if (phaseOf(h) != 1 || h.getY() > band + 1 || hatch(h, 1)) continue;
					double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz, d = dx * dx + dz * dz;
					if (d < bestD) {
						bestD = d;
						secAnchor = h;
					}
				}
				sec = sectionOf(ok, band + 5);
				if (sec != null) HumanAutopilot.LOGGER.info("[schem] the wall round {} next (up to y {})", secAnchor.toShortString(), band + 5);
			}
			if (sec != null) return sec;
		}
		// (The inside a storey at a time, ~4 layers: two at a time had him fly round every room of the castle for each
		// pair of layers.)
		int bandTop = band + (ph == 3 ? 3 : 1);
		// The ground (dirt in the dips under a build on a hillside, its own ground layers): all of it, near first, each
		// dip filled from its bottom up before the next (the one under goes first anyway: columnWaits). Two layers at a
		// time over the forest cathedral's whole 75 x 44, he flew 64 blocks to the next dip's bottom, then on to another.
		if (ph == 0 && clearFromY != Integer.MAX_VALUE) bandTop = Math.max(bandTop, clearFromY - 1);
		List<BlockPos> out = new ArrayList<>();
		boolean any = false;
		for (BlockPos h : ok) {
			int hp = phaseOf(h);
			if (hp < 0) {
				out.add(h); // (temporary blocks, clearing out: not held back)
				continue;
			}
			if (hp != ph || h.getY() > bandTop || hatch(h, hp)) continue;
			out.add(h);
			any = true;
		}
		if (!any) {
			// None of it near: first the same part right here, a few layers up (finish the wall here, then go across, the
			// user's "finishing the left wall"; the trading hall's corner got one block a band, 60 blocks each way).
			for (BlockPos h : ok) {
				if (phaseOf(h) != ph || h.getY() > band + 8 || hatch(h, ph)) continue;
				out.add(h);
				any = true;
			}
		}
		if (!any) {
			// None of it near: the nearest of it over there.
			BlockPos go = null;
			double best = Double.MAX_VALUE;
			for (BlockPos h : holes) {
				if (h.getY() > bandTop || h.getY() < band || phaseOf(h) != ph) continue;
				double dx = h.getX() + 0.5 - px, dz = h.getZ() + 0.5 - pz;
				double d = dx * dx + dz * dz + 9.0 * (h.getY() - band) * (h.getY() - band) + (nowhere.containsKey(h) ? 1e6 : 0);
				if (d >= best || columnWaits(h) || !planOk(h, flyTo, picker) || hatch(h, ph)) continue;
				best = d;
				go = h;
			}
			if (go != null) out.add(go);
		}
		return out;
	}

	/** The stretch of wall being finished: round this spot (horizontally). */
	private BlockPos secAnchor;

	/** The outside's holes round the stretch's spot, up to {@code top} (with temporary blocks and clearing), or null if none of the outside. */
	private List<BlockPos> sectionOf(List<BlockPos> ok, int top) {
		if (secAnchor == null) return null;
		List<BlockPos> out = new ArrayList<>();
		boolean any = false;
		for (BlockPos h : ok) {
			int hp = phaseOf(h);
			if (hp < 0) {
				out.add(h); // (temporary blocks, clearing out: not held back)
				continue;
			}
			if (hp != 1 || h.getY() > top || hatch(h, 1)) continue;
			int dx = h.getX() - secAnchor.getX(), dz = h.getZ() - secAnchor.getZ();
			if (dx * dx + dz * dz > 30) continue;
			out.add(h);
			any = true;
		}
		return any ? out : null;
	}

	private static final String[] PARTS = {"the ground", "the outside", "the top", "the inside"};
	private static final int STICK = 8;
	private int lastPhase = -1;
	private long orderLogAt = -1000, phaseSince = -1000;

	/** Tests: the top block of the build in its column (a roof). */
	public boolean topOfColumn(BlockPos c) {
		int x = c.getX() - ringX0, z = c.getZ() - ringZ0;
		return skyTop != null && x >= 0 && z >= 0 && x < ringW && z < ringD && skyTop[x * ringD + z] == c.getY();
	}

	/** Tests: which part of the build order this spot belongs to (0 ground, 1 outside, 2 top, 3 inside), whatever it needs now. */
	public int phaseFor(BlockPos c) {
		return skyTop == null ? -1 : partOf(c);
	}

	/**
	 * Which part of the build order a hole is in (see buildOrder); -1 for temporary blocks and clearing out where the
	 * build is empty. (Knocking out a wrong block goes by where it is, like the block that goes in after it: grass to
	 * knock out of the castle's courtyard, never held back, had him redo the whole ground first.)
	 */
	private int phaseOf(BlockPos h) {
		if (temps.containsKey(h) || outFor.containsKey(h)) return -1;
		BlockState want = plan.get(h);
		if (want == null || want.isAir()) return -1;
		return partOf(h);
	}

	private int partOf(BlockPos h) {
		if (clearFromY != Integer.MAX_VALUE && h.getY() < clearFromY) return 0; // (the ground layers sunk into the world)
		// The outside: what the open air round the build touches, and the back of a thick wall (a full block right
		// behind it). Not what stands against a wall on the inside (a trough, shelves, trapdoors behind a 1-thick wall
		// came out "outside": he went in through the doors for them while the walls were going up).
		int ring = ringAt(h);
		if (ring <= 1) return 1;
		if (ring >= 2) {
			BlockState want = plan.get(h);
			if (want != null && want.isCollisionShapeFullBlock(Terrain.level(), h) && (ring == 2 || solidCore(h))) return 1;
		}
		return coversInside(h) && roofOf(h) ? 2 : 3;
	}

	/**
	 * A roof's block: roof stuff (a full block, a slab, stairs), joined side by side in its own layer to the outside
	 * of the build (a flat roof's middle is, through its edge). Not a shelf or a lantern on a wall, not a stall's
	 * canopy in the courtyard: those are the inside (the castle flipped between "the top" and "the inside" for them).
	 */
	private boolean roofOf(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null) return false;
		var blk = want.getBlock();
		if (!want.isCollisionShapeFullBlock(Terrain.level(), h) && !(blk instanceof net.minecraft.world.level.block.SlabBlock)
				&& !(blk instanceof net.minecraft.world.level.block.StairBlock)) return false;
		int x = h.getX() - ringX0, z = h.getZ() - ringZ0;
		if (x < 0 || z < 0 || x >= ringW || z >= ringD) return false;
		return shellLayers.computeIfAbsent(h.getY(), this::shellFor).get(x * ringD + z);
	}

	/** Per layer: the build's blocks joined, side by side in that layer, to its outside (rings 1-2). */
	private final Map<Integer, java.util.BitSet> shellLayers = new HashMap<>();

	private java.util.BitSet shellFor(int y) {
		int d = ringD, n = ringW * ringD;
		boolean[] blk = new boolean[n];
		List<BlockPos> layer = planLayers.get(y);
		if (layer != null) {
			for (BlockPos c : layer) {
				BlockState st = plan.get(c);
				if (st == null || st.isAir() || temps.containsKey(c) || support.contains(c)) continue;
				blk[(c.getX() - ringX0) * d + (c.getZ() - ringZ0)] = true;
			}
		}
		java.util.BitSet seen = new java.util.BitSet(n);
		int[] q = new int[n];
		int qh = 0, qt = 0;
		for (int i = 0; i < n; i++) {
			if (blk[i] && ringAt(new BlockPos(i / d + ringX0, y, i % d + ringZ0)) <= 2) {
				seen.set(i);
				q[qt++] = i;
			}
		}
		while (qh < qt) {
			int i = q[qh++], x = i / d, z = i % d;
			for (int k = 0; k < 4; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx < 0 || nz < 0 || nx >= ringW || nz >= d) continue;
				int j = nx * d + nz;
				if (!blk[j] || seen.get(j)) continue;
				seen.set(j);
				q[qt++] = j;
			}
		}
		return seen;
	}

	/**
	 * Held back as the hatch: a roof block (the top of its column, part of the outside or the top) that would close in
	 * inside still to build with no other way in (sealsInside).
	 */
	private boolean hatch(BlockPos h, int ph) {
		if (ph != 1 && ph != 2) return false;
		int x = h.getX() - ringX0, z = h.getZ() - ringZ0;
		if (x < 0 || z < 0 || x >= ringW || z >= ringD || skyTop[x * ringD + z] != h.getY()) return false;
		boolean seals = sealsInside(h);
		if (seals && hatchesLogged.add(h)) {
			HumanAutopilot.LOGGER.info("[schem] leaving {} ({}) open for now: the way in to the inside under it", h.toShortString(), plan.get(h).getBlock().getName().getString());
		}
		if (seals) hatchHeld.put(h.immutable(), HumanAutopilot.ticks());
		return seals;
	}

	private final java.util.Set<BlockPos> hatchesLogged = new java.util.HashSet<>();
	/** Roof spots left open as the way in (see hatch), and when that was last decided: the companion leaves them too. */
	private final Map<BlockPos, Long> hatchHeld = new HashMap<>();
	private long holdSentAt = -1000;

	/**
	 * What we're keeping open on purpose - blocks of the build taken out to get at a spot (opened), its own grass taken
	 * out for one (outFor), the way in through a roof (hatch) - over to the server, every second: the companion (Kohaku)
	 * leaves those spots alone. (She took them for TNT damage and put them back, and we took them out again, back and
	 * forth; a roof's way in, filled by her, shut us out of the inside.)
	 */
	private void sendHold(long now) {
		if (now - holdSentAt < 20) return;
		holdSentAt = now;
		hatchHeld.entrySet().removeIf(e -> now - e.getValue() > 200 || !hole(e.getKey()));
		java.util.Set<BlockPos> hold = new java.util.HashSet<>(opened.keySet());
		hold.addAll(outFor.keySet());
		hold.addAll(hatchHeld.keySet());
		dev.humanautopilot.net.BuildLink.hold(hold);
	}

	private long needSentAt;

	/**
	 * "I can't get to these - can you?": the spots we gave up on (left as they are after their tries: nowhere to place
	 * them from, couldn't get there) that are still empty, for a helper - she places without a line of sight, and may
	 * come into our part for these. Once one's in, jobAt counts it done (the right block in a given-up spot).
	 */
	private void sendNeed(long now) {
		if (now - needSentAt < 20) return;
		needSentAt = now;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos c : cant) {
			BlockState want = plan.get(c);
			if (want == null || want.isAir() || !want.getFluidState().isEmpty() || temps.containsKey(c) || opened.containsKey(c)
					|| outFor.containsKey(c)) continue;
			BlockState world = Terrain.state(c);
			if (!world.isAir() && !world.canBeReplaced()) continue;
			out.add(c);
			if (out.size() >= 1500) break;
		}
		dev.humanautopilot.net.BuildLink.need(out);
	}

	/** How far round where we're working is our part (the companion keeps out of it): the near pick's 14, and a bit. */
	private static final int OUR_PART = 16;
	/** The helpers' spots (where each is, what she's on) this tick, if we're keeping off them (see helperClaims). */
	private List<dev.humanautopilot.net.BuildLink.Helper> helpersNow = List.of();
	/** Spots passed over as a helper's (see helperClaims), since the last status line. */
	private int helperPassed;

	/**
	 * The autopilot and the companion talk (the user: "make auto pilot and kohaku talk to each other ... stay away from
	 * me, I'm working this part"): "I'm working here" goes to the server every second - where we are, or the far spot
	 * we've set off for - and she keeps out of that part; "I'm working there" comes back for each helper, and the spots
	 * she's on are left to her (unless we've nothing else to do: then we don't wait on her).
	 */
	private void talk(LocalPlayer p, long now) {
		BlockPos focus = target != null && !inRegion(target.getX(), target.getY(), target.getZ()) ? target : p.blockPosition();
		dev.humanautopilot.net.BuildLink.area(focus, OUR_PART, planCounted ? (int) Math.floor(planProgress() * 100) : -1);
		helpersNow = now - pickNoneAt < 100 ? List.of() : dev.humanautopilot.net.BuildLink.helpers();
	}

	/** A helper's: within 6 of the spot she's on, or 3 of where she is. */
	private boolean helperClaims(BlockPos h) {
		for (var hp : helpersNow) {
			if (hp.target() != null && hp.target().distSqr(h) <= 36) return true;
			if (hp.at().distanceToSqr(Vec3.atCenterOf(h)) <= 9) return true;
		}
		return false;
	}

	/**
	 * The top of its column in the build, with open space of the build under it: a roof over the inside. (Not the
	 * bottom layer: the castle's courtyard ground has nothing under it either, and came out as "the top".)
	 */
	private boolean coversInside(BlockPos h) {
		int x = h.getX() - ringX0, z = h.getZ() - ringZ0;
		if (skyTop == null || x < 0 || z < 0 || x >= ringW || z >= ringD || skyTop[x * ringD + z] != h.getY()) return false;
		if (h.getY() <= planBottomY) return false;
		BlockState below = plan.get(h.below());
		return below == null || below.isAir() || temps.containsKey(h.below());
	}

	/**
	 * Putting this top block in would close in inside that isn't built yet, with no other way in: it's the hatch, and
	 * waits (the user: he leaves one opening in the top over it, builds the inside through it, and closes it last).
	 * From the space under it, through what's open (air, a door), not through it: open sky (nothing of the build over
	 * it, or outside the build) is a way in; a spot of the build still to do next to the way is inside to build.
	 * Cached for 3 s.
	 */
	private boolean sealsInside(BlockPos h) {
		long now = HumanAutopilot.ticks();
		long[] c0 = sealChecked.get(h);
		if (c0 != null && c0[0] > now) return c0[1] == 1;
		boolean seals = sealsInside0(h);
		sealChecked.put(h, new long[]{now + 60, seals ? 1 : 0});
		if (sealChecked.size() > 4000) sealChecked.values().removeIf(v -> v[0] <= now);
		return seals;
	}

	private final Map<BlockPos, long[]> sealChecked = new HashMap<>();

	private boolean sealsInside0(BlockPos h) {
		BlockPos start = h.below();
		if (!openForWalk(start)) return false;
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		q.add(start);
		seen.add(start);
		boolean work = false;
		while (!q.isEmpty()) {
			if (seen.size() > 6000) return false; // (a big space: it'll have a way in)
			BlockPos c = q.poll();
			int x = c.getX() - ringX0, z = c.getZ() - ringZ0;
			if (x < 0 || z < 0 || x >= ringW || z >= ringD || c.getY() > skyTop[x * ringD + z]) return false; // out: a way in
			if (ringAt(c) == 0) return false; // (the open air round the build, through a door: a way in)
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (n.equals(h) || seen.contains(n)) continue;
				BlockState want = plan.get(n);
				if (!work && want != null && !want.isAir() && !temps.containsKey(n) && !cant.contains(n) && hole(n)) work = true;
				if (!lastingWay(n)) continue;
				seen.add(n);
				q.add(n);
			}
		}
		return work;
	}

	/**
	 * Open now and staying open (not a spot the build still fills: the tower's TNT holes at the foot of its wall
	 * counted as its way in, the roof went on, the holes were put back, and the floor inside was shut in), with room
	 * for us (two high). Another opening in the roof counts: the last of those is the hatch and stays open (counting
	 * none of them, every block of the tower's roof waited as "the way in").
	 */
	private boolean lastingWay(BlockPos n) {
		if (!openForWalk(n) || !(openForWalk(n.above()) || openForWalk(n.below()))) return false;
		BlockState want = plan.get(n);
		if (want == null || want.isAir() || temps.containsKey(n) || handDoor(want)) return true;
		if (want.getCollisionShape(Terrain.level(), n).isEmpty()) return true;
		if (!topOfColumn(n) || !hole(n)) return false;
		int part = partOf(n);
		return part == 1 || part == 2;
	}

	/** Open to get through: air, something that doesn't block, or a door. */
	private static boolean openForWalk(BlockPos c) {
		BlockState s = Terrain.state(c);
		return s.isAir() || s.canBeReplaced() || handDoor(s) || s.getCollisionShape(Terrain.level(), c).isEmpty();
	}

	/**
	 * Night vision while building (the user: "give him night vision, considering it'll be dark once he's inside"; "only
	 * if it's dark and he can't see"): once it's been dark where he is for 3 s, the effect, by /effect with no
	 * particles; if the server won't have that (no effect after two goes), a potion of night vision from the creative
	 * menu, drunk. Again when it runs out, if it's still dark.
	 */
	private long nvCheckAt, nvDrinkSince = -1, darkSince = -1;
	private int nvCommands;
	private boolean nvAnyPotion;

	private String nightVision(LocalPlayer p, InputDriver in, AimController aim, long now) {
		if (!p.getAbilities().instabuild) return null;
		// On, and not running out (its last 10 s flicker: that's when a player drinks the next one).
		var eff = p.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
		if (eff != null && (eff.isInfiniteDuration() || eff.getDuration() > 200)) {
			if (nvDrinkSince >= 0) HumanAutopilot.LOGGER.info("[schem] night vision on ({})", eff.isInfiniteDuration() ? "for good" : eff.getDuration() / 20 + " s");
			nvDrinkSince = -1;
			return null;
		}
		if (nvDrinkSince >= 0) {
			// Drinking: hold the button (a potion takes 1.6 s), looking up at the sky (not at a block to click on).
			onSince = now; // (not a stall)
			int slot = nvSlot(p);
			if (slot < 0 || now - nvDrinkSince > 120) {
				nvDrinkSince = -1;
				nvCheckAt = now + 600;
				return null;
			}
			brake(p, in);
			aim.lookDirection("drink", p.getYRot(), -60);
			if (!in.selectHotbar(slot)) return "taking out a potion of night vision";
			if (p.getXRot() < -40) in.hold(InputDriver.opts().keyUse);
			return "drinking a potion of night vision";
		}
		if (now < nvCheckAt) return null;
		// Only when it's dark here, for a few seconds (in a roofed room, at night): in daylight there's no need.
		BlockPos eyeAt = BlockPos.containing(p.getEyePosition());
		boolean dark = p.level().getMaxLocalRawBrightness(eyeAt) <= 5;
		if (!dark) {
			darkSince = -1;
			return null;
		}
		if (darkSince < 0) darkSince = now;
		if (now - darkSince < 60) return null;
		if (eff == null && nvCommands < 2) {
			nvCheckAt = now + 100;
			nvCommands++;
			p.connection.sendCommand("effect give @s minecraft:night_vision infinite 0 true");
			HumanAutopilot.LOGGER.info("[schem] night vision: /effect (try {})", nvCommands);
			return null;
		}
		// The command didn't take: a potion, the long one (8 minutes; the short one had him back in the menu every 3), or
		// any if the menu hasn't got that. (None to be had at all: leave it, and look again in 5 minutes.)
		CreativePicker cp = CreativePicker.get();
		if (cp.failed(net.minecraft.world.item.Items.POTION)) {
			if (nvAnyPotion) {
				nvCheckAt = now + 6000;
				return null;
			}
			nvAnyPotion = true;
			cp.unfail(net.minecraft.world.item.Items.POTION);
		}
		if (cp.active() && !cp.fetchingSpecial()) return null;
		onSince = now; // (not a stall)
		if (nvSlot(p) >= 0) {
			nvDrinkSince = now;
			HumanAutopilot.LOGGER.info("[schem] night vision: drinking a potion");
			return "drinking a potion of night vision";
		}
		brake(p, in);
		return cp.fetch(p, in, net.minecraft.world.item.Items.POTION, "night vision",
				nvAnyPotion ? BoxFillModule::nightVisionPotion : BoxFillModule::longNightVision);
	}

	/** The hotbar slot with the potion to drink: the long one (or any, if the menu hasn't got that one). */
	private int nvSlot(LocalPlayer p) {
		int s = Inv.hotbarSlot(p, BoxFillModule::longNightVision);
		return s >= 0 || !nvAnyPotion ? s : Inv.hotbarSlot(p, BoxFillModule::nightVisionPotion);
	}

	private static boolean longNightVision(net.minecraft.world.item.ItemStack st) {
		if (!st.is(net.minecraft.world.item.Items.POTION)) return false;
		var pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		return pc != null && pc.potion().isPresent() && pc.potion().get().value() == net.minecraft.world.item.alchemy.Potions.LONG_NIGHT_VISION.value();
	}

	private static boolean nightVisionPotion(net.minecraft.world.item.ItemStack st) {
		if (!st.is(net.minecraft.world.item.Items.POTION)) return false;
		var pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		return pc != null && pc.potion().isPresent() && (pc.potion().get().value() == net.minecraft.world.item.alchemy.Potions.NIGHT_VISION.value()
				|| pc.potion().get().value() == net.minecraft.world.item.alchemy.Potions.LONG_NIGHT_VISION.value());
	}

	/**
	 * Every 4-6 minutes of building, out to where the whole build is in view, a look across it for ~4 s, and back to
	 * work (the user: "every 5 minutes he flies out to look at the build"). Not while TNT damage is being put back, or
	 * shut in under a roof (then a little later); not while guarding (that's no building).
	 */
	private long lookNextAt = -1, lookSince, lookingSince;
	private int lookStage; // 0 building, 1 on the way out, 2 looking
	private Vec3 lookFrom, lookMid, lookSide;
	private double lookWidth;
	/**
	 * The point the eyes are on while looking across the build: from one side to the other in 4 s, by the clock, not the
	 * game tick (moving 20 times a second it went in steps, and the camera after it in nudges).
	 */
	private long lookingSinceNs;
	private final java.util.function.Supplier<Vec3> lookPan = () -> lookMid == null ? null
			// (From the middle - where the eyes were on the way out - over to one side and back, eased: starting at the
			// far edge was a quick 15-degree turn the other way first, then the pan.)
			: lookMid.add(lookSide.scale(lookWidth * 0.5 * Math.sin(Math.PI * Math.min(1.0, (System.nanoTime() - lookingSinceNs) / 4.0e9))));

	private String lookAround(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		if (planBox == null || guarding) {
			lookStage = 0;
			return null;
		}
		if (lookNextAt < 0) lookNextAt = now + Human.rangeInt(4800, 7200);
		if (lookStage == 0) {
			if (now < lookNextAt) return null;
			BlockPos me = bodyCell(p);
			boolean shutIn = b.inside(me.getX(), me.getY(), me.getZ()) && !skyOpen(b, me.getX(), me.getY(), me.getZ());
			if (damageToDo() || CreativePicker.get().active() || walkDoor != null || shutIn) {
				lookNextAt = now + 200; // (a little later)
				return null;
			}
			// Where it all fits in view: out on our side, back as far as it's wide, a bit over the top of what's built.
			double cx = (planBox.minX() + planBox.maxX() + 1) / 2.0, cz = (planBox.minZ() + planBox.maxZ() + 1) / 2.0;
			double r = Math.hypot(planBox.maxX() - planBox.minX() + 1, planBox.maxZ() - planBox.minZ() + 1) / 2.0;
			Vec3 outward = new Vec3(p.getX() - cx, 0, p.getZ() - cz);
			// A big build: the part we're working on, not all of it - a player steps back from the wall they're on. (The
			// copper guard, 208 across: 90 blocks out and back up over it every few minutes, 44 times in two hours, one of
			// the user's "flying around like a bot".)
			if (r > 32) {
				BlockPos at = lastPlaced != null && now - lastPlacedAt < 1200 ? lastPlaced : bodyCell(p);
				double ax = Math.clamp(at.getX() + 0.5, planBox.minX(), planBox.maxX() + 1.0), az = Math.clamp(at.getZ() + 0.5, planBox.minZ(), planBox.maxZ() + 1.0);
				// (Out on our side of it, or out from the build's middle if we're right over it.)
				Vec3 side = new Vec3(p.getX() - ax, 0, p.getZ() - az);
				outward = side.lengthSqr() >= 4 ? side : new Vec3(ax - cx, 0, az - cz);
				cx = ax;
				cz = az;
				r = 24;
			}
			Vec3 dir = outward.lengthSqr() < 1 ? new Vec3(1, 0, 0) : outward.normalize();
			// (Over what's built - the last block in - not over where we are: looking again from the last look's spot
			// went higher each time, y 41, 61, 80, 89, the floating pagoda's "flew out to view the build".)
			int builtTop = Math.min(planBox.maxY(), Math.max(activeY, lastPlaced != null ? lastPlaced.getY() : activeY));
			double dist = Math.clamp(r * 1.4 + 4, 12, 90);
			lookFrom = new Vec3(cx + dir.x * dist, builtTop + 4 + dist * 0.3, cz + dir.z * dist);
			lookMid = new Vec3(cx, (planBox.minY() + builtTop) / 2.0, cz);
			lookSide = new Vec3(-dir.z, 0, dir.x);
			lookWidth = r * 0.8;
			lookStage = 1;
			lookSince = now;
			HumanAutopilot.LOGGER.info("[schem] stepping back to look at the build (to {})", fmt(lookFrom));
		}
		if (lookStage == 1) {
			double d = p.position().distanceTo(lookFrom);
			if (d > 2.5 && now - lookSince < 240) {
				aim.setQuick(false);
				aim.lookAt("look-build", () -> lookMid);
				steer(p, in, route(p, b, lookFrom), false, 0.9);
				if (d > 6) in.hold(InputDriver.opts().keySprint);
				air(p, b);
				return "stepping back to look at the build";
			}
			lookStage = 2;
			lookingSince = now;
			lookingSinceNs = System.nanoTime();
		}
		// Looking: still, the eyes going slowly across it from one side to the other.
		brake(p, in);
		aim.setQuick(false);
		aim.lookAtGliding("look-build", lookPan);
		if (now - lookingSince < 80 + Human.rangeInt(0, 20)) return "looking at the build";
		lookStage = 0;
		lookNextAt = now + Human.rangeInt(4800, 7200);
		// (A fresh pick from out here: the block we were on still had its face and its clock, and from 50 blocks off
		// it was "can't line up the crosshair".)
		stand = null;
		target = null;
		locked = null;
		placingSince = -1;
		HumanAutopilot.LOGGER.info("[schem] back to work");
		return null;
	}

	/** Part of the build proper, to be put in: not a temporary block, not something to knock out, not decoration. */
	private boolean ringed(BlockPos h) {
		return !temps.containsKey(h) && jobAt(h) == Placement.Job.PLACE && !decor(h);
	}

	/**
	 * How far into its layer a spot of the build is: 1 on the outside (next to the open air round the build), 2 just
	 * inside that, 3 further in (rooms, the middle of a floor). The open air round the build doesn't get in through a
	 * doorway or a window: a gap of up to 6 between blocks of the build, with the build over it, counts as shut (the
	 * hospital's rooms all came out "outside" through their doors). Worked out for a layer the first time it's needed.
	 */
	private final Map<Integer, byte[]> ringLayers = new HashMap<>();
	/** Per column of the plan box (one spot round it more): the top block of the build in it. */
	private int[] skyTop;
	private int ringX0, ringZ0, ringW, ringD;

	private int ringAt(BlockPos h) {
		if (skyTop == null) return 3;
		int x = h.getX() - ringX0, z = h.getZ() - ringZ0;
		if (x < 0 || z < 0 || x >= ringW || z >= ringD) return 1;
		byte[] r = ringLayers.get(h.getY());
		if (r == null) {
			ringLayers.keySet().removeIf(y -> y < activeY - 1); // (finished layers)
			pocketLayers.keySet().removeIf(y -> y < activeY - 40);
			r = ringsFor(h.getY());
			ringLayers.put(h.getY(), r);
		}
		return r[x * ringD + z];
	}

	private byte[] ringsFor(int y) {
		int w = ringW, d = ringD, n = w * d;
		RingBase base = ringBase(y);
		boolean[] out = new boolean[n];
		if (base == null) java.util.Arrays.fill(out, true);
		else {
			for (int i = base.out.nextSetBit(0); i >= 0; i = base.out.nextSetBit(i + 1)) out[i] = true;
			// A small pocket of air shut in by the build (fewer than POCKET cells, counted in 3D) is no room: what's
			// round it goes with the outside. (The giant tree's crown is full of them: 70% of the tree came out "the
			// inside", built from in there after its shell had shut it in.)
			for (int i = 0; i < n; i++) if (!out[i] && !base.blk.get(i) && !base.gap.get(i) && pocket(y, i)) out[i] = true;
		}
		int[] q = new int[n];
		int qh, qt;
		// Steps in from it: 1, 2, and 3 for everything further in.
		byte[] ring = new byte[n];
		java.util.Arrays.fill(ring, (byte) 3);
		qh = qt = 0;
		for (int i = 0; i < n; i++) {
			if (out[i]) {
				ring[i] = 0;
				q[qt++] = i;
			}
		}
		while (qh < qt) {
			int i = q[qh++], x = i / d, z = i % d, r = ring[i];
			if (r >= 2) continue;
			for (int k = 0; k < 4; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
				int j = nx * d + nz;
				if (ring[j] <= r + 1) continue;
				ring[j] = (byte) (r + 1);
				q[qt++] = j;
			}
		}
		return ring;
	}

	/** One layer of the build for the rings: its blocks, its doorways and windows, and the open air round the build in it. */
	private record RingBase(java.util.BitSet blk, java.util.BitSet gap, java.util.BitSet out) {
	}

	private final Map<Integer, RingBase> ringBases = new HashMap<>();
	/** Per layer and ring cell: 0 not looked at, 1 a small pocket of shut-in air (no room), 2 part of something bigger. */
	private final Map<Integer, byte[]> pocketLayers = new HashMap<>();
	private int ringY0, ringY1;
	private static final int POCKET = 27;

	private RingBase ringBase(int y) {
		if (skyTop == null || y < ringY0 || y > ringY1) return null;
		RingBase b = ringBases.get(y);
		if (b != null) return b;
		int k = y;
		while (k > ringY0 && !ringBases.containsKey(k - 1)) k--;
		for (; k <= y; k++) ringBases.put(k, ringBaseFor(k, ringBases.get(k - 1)));
		return ringBases.get(y);
	}

	private RingBase ringBaseFor(int y, RingBase below) {
		int w = ringW, d = ringD, n = w * d;
		boolean[] blk = new boolean[n], gap = new boolean[n], out = new boolean[n];
		List<BlockPos> layer = planLayers.get(y);
		if (layer != null) {
			for (BlockPos c : layer) {
				BlockState s = plan.get(c);
				if (s == null || s.isAir() || temps.containsKey(c) || support.contains(c)) continue;
				blk[(c.getX() - ringX0) * d + (c.getZ() - ringZ0)] = true;
			}
		}
		// Doorways, windows: a short run of air between two blocks of the build, with the build right over it (a lintel
		// within 8). Not just anything of the build somewhere over it: under the giant tree's crown, 30 up, every gap
		// between its roots counted as a door, and the roots came out "the inside".
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; ) {
				if (blk[x * d + z]) {
					x++;
					continue;
				}
				int s = x;
				while (x < w && !blk[x * d + z]) x++;
				if (s > 0 && x < w && x - s <= 6) for (int i = s; i < x; i++) if (lintelOver(i, z, y, m)) gap[i * d + z] = true;
			}
		}
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < d; ) {
				if (blk[x * d + z]) {
					z++;
					continue;
				}
				int s = z;
				while (z < d && !blk[x * d + z]) z++;
				if (s > 0 && z < d && z - s <= 6) for (int i = s; i < z; i++) if (lintelOver(x, i, y, m)) gap[x * d + i] = true;
			}
		}
		// The open air round the build: in from the edge (one spot outside the plan box all round) through air.
		int[] q = new int[n];
		int qh = 0, qt = 0;
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < d; z++) {
				if (x == 0 || z == 0 || x == w - 1 || z == d - 1) {
					out[x * d + z] = true;
					q[qt++] = x * d + z;
				}
			}
		}
		while (qh < qt) {
			int i = q[qh++], x = i / d, z = i % d;
			for (int k = 0; k < 4; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
				int j = nx * d + nz;
				if (out[j] || blk[j] || gap[j]) continue;
				out[j] = true;
				q[qt++] = j;
			}
		}
		// And up from the open air in the layer under it: air walled in sideways but open underneath (inside a tree's
		// crown, under an overhang or a roof on posts) is the open air too, never a room. (Not down from the sky: a
		// courtyard stays the inside, built after its walls.)
		if (below != null) {
			boolean[] seen = new boolean[n];
			for (int i = 0; i < n; i++) {
				if (out[i] || blk[i] || gap[i] || seen[i]) continue;
				qh = qt = 0;
				q[qt++] = i;
				seen[i] = true;
				boolean open = false;
				while (qh < qt) {
					int c = q[qh++], x = c / d, z = c % d;
					if (below.out.get(c)) open = true;
					for (int k = 0; k < 4; k++) {
						int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
						if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
						int j = nx * d + nz;
						if (seen[j] || out[j] || blk[j] || gap[j]) continue;
						seen[j] = true;
						q[qt++] = j;
					}
				}
				if (open) for (int k = 0; k < qt; k++) out[q[k]] = true;
			}
		}
		return new RingBase(bits(blk), bits(gap), bits(out));
	}

	private static java.util.BitSet bits(boolean[] a) {
		java.util.BitSet b = new java.util.BitSet(a.length);
		for (int i = 0; i < a.length; i++) if (a[i]) b.set(i);
		return b;
	}

	/** A block of the build 1-8 over this ring cell (x, z) of layer y: a doorway's or a window's top. */
	private boolean lintelOver(int x, int z, int y, BlockPos.MutableBlockPos m) {
		if (skyTop[x * ringD + z] <= y) return false;
		for (int dy = 1; dy <= 8; dy++) {
			BlockState s = plan.get(m.set(x + ringX0, y + dy, z + ringZ0));
			if (s != null && !s.isAir() && !temps.containsKey(m) && !support.contains(m)) return true;
		}
		return false;
	}

	/**
	 * Ring cell i of layer y is air shut in by the build (in its layer) in a pocket of fewer than POCKET cells all
	 * told, through shut-in air up, down and sideways: too small to be a room (a gap inside a tree's crown, a slot in a
	 * thick wall). A room's air between its furniture is part of the room over it: not a pocket.
	 */
	private boolean pocket(int y, int i) {
		int n = ringW * ringD, d = ringD;
		byte[] st = pocketLayers.computeIfAbsent(y, k -> new byte[n]);
		if (st[i] != 0) return st[i] == 1;
		long[] seen = new long[POCKET];
		int qh = 0, qt = 0;
		seen[qt++] = (long) y * n + i;
		boolean small = true;
		search:
		while (qh < qt) {
			long c = seen[qh++];
			int cy = (int) Math.floorDiv(c, (long) n), ci = (int) Math.floorMod(c, (long) n), x = ci / d, z = ci % d;
			for (int k = 0; k < 6; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0), ny = cy + (k == 4 ? 1 : k == 5 ? -1 : 0);
				if (nx < 0 || nz < 0 || nx >= ringW || nz >= d) continue;
				if (ny < ringY0) continue; // (the ground under the build)
				int j = nx * d + nz;
				RingBase b = ringBase(ny);
				if (b == null) { // (over the top of the build: open)
					small = false;
					break search;
				}
				if (b.out.get(j) || b.blk.get(j) || b.gap.get(j)) continue;
				long key = (long) ny * n + j;
				boolean known = false;
				for (int s = 0; s < qt; s++) {
					if (seen[s] == key) {
						known = true;
						break;
					}
				}
				if (known) continue;
				byte[] other = pocketLayers.get(ny);
				if (qt >= POCKET || (other != null && other[j] == 2)) {
					small = false;
					break search;
				}
				seen[qt++] = key;
			}
		}
		byte v = small ? (byte) 1 : (byte) 2;
		for (int s = 0; s < qt; s++) {
			long c = seen[s];
			int cy = (int) Math.floorDiv(c, (long) n);
			pocketLayers.computeIfAbsent(cy, k -> new byte[n])[(int) Math.floorMod(c, (long) n)] = v;
		}
		return small;
	}

	/**
	 * Full block {@code h} (ring 3: well in from the open air round the build) is in the middle of a solid mass - a
	 * tree's trunk, a thick wall's core, a statue's body: blocks of the build all round it, on a full block (or the
	 * ground), and the build or the open air over it, not a room. It goes up with the outside, layer by layer, while
	 * it's still open from above (left for "the inside", the trunk's middle was a deep pit under the bark by then).
	 */
	private boolean solidCore(BlockPos h) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState s = plan.get(h.relative(d));
			if (s == null || s.isAir()) return false;
		}
		BlockPos dn = h.below();
		BlockState s = plan.get(dn);
		if (h.getY() > ringY0 && (s == null || !s.isCollisionShapeFullBlock(Terrain.level(), dn))) return false;
		BlockPos up = h.above();
		s = plan.get(up);
		return (s != null && !s.isAir()) || up.getY() > ringY1 || ringAt(up) == 0;
	}

	/** Filling {@code h} would leave a hole beside or under it with no other open side (tucked under the top). */
	private boolean sealsAnother(BlockPos h) {
		for (Direction d : Direction.values()) {
			if (d == Direction.UP) continue; // (one above it is reached from its own top, or waits its turn anyway)
			BlockPos n = h.relative(d);
			if (bad.containsKey(n) || !hole(n)) continue; // one we can't do anyway doesn't hold this one up
			boolean other = false;
			for (Direction e : Direction.values()) {
				BlockPos m = n.relative(e);
				if (!m.equals(h) && !solid(m)) {
					other = true;
					break;
				}
			}
			if (!other) return true;
		}
		return false;
	}

	private int forcedPillars, finalSweeps;
	private boolean sweepsLeftNoted;
	private long sweepUntil = -1;
	private BlockPos sweepFar;

	/** A dirt pillar (it comes out again) under the nearest hole of the lowest layer with nothing to click, now. */
	private boolean forcePillar(LocalPlayer p) {
		dropUnbuiltTemps();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		int lowY = Integer.MAX_VALUE;
		for (BlockPos h : holes) {
			if (jobAt(h) != Placement.Job.PLACE || workable(h) || plan.containsKey(h.below())) continue;
			double d = Vec3.atCenterOf(h).distanceToSqr(p.position()) + 64.0 * (h.getY() - Math.min(lowY, h.getY()));
			if (h.getY() < lowY) {
				lowY = h.getY();
				bestD = Double.MAX_VALUE;
			}
			if (h.getY() > lowY) continue;
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		if (best == null) return false;
		HumanAutopilot.LOGGER.info("[schem] nothing to put anything against for 30 s ({} holes): a pillar up to {} first", holeCount, best.toShortString());
		return pillarUnder(best);
	}

	private boolean supportPillar(LocalPlayer p) {
		for (BlockPos c : support) if (hole(c)) return false; // one at a time: that one's still going up
		if (holes.isEmpty()) return false;
		int y = holes.get(0).getY();
		// Only for the build's own bottom layer left hanging over a pit: something hanging inside the build (a
		// chandelier waiting for its chain) gets no dirt pillar under it. (Its floor: the dirt filled in under it on a
		// hillside is lower down.) Unless nothing at all in reach of the window has anything to click (the whole build
		// in the air - a cliff top - and no way to bridge out to it: a pillar beats giving all of it up after 30 s).
		boolean anyWorkable = false;
		for (BlockPos h : holes) if (workable(h)) { anyWorkable = true; break; }
		if (anyWorkable && y > (clearFromY != Integer.MAX_VALUE ? clearFromY : planBottomY)) return false;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos h : holes) {
			if (h.getY() != y) break;
			if (bad.containsKey(h) && !nowhere.containsKey(h)) continue;
			if (workable(h)) continue;
			double d = Vec3.atCenterOf(h).distanceToSqr(p.position());
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		if (best == null) return false;
		return pillarUnder(best);
	}

	/**
	 * A dirt pillar from whatever is below up to {@code best} (a spot of the build's bottom layer over a pit), so it has
	 * something to go on. Its blocks count as damage to put right too when {@code best} is (they go in first).
	 */
	private boolean pillarUnder(BlockPos best) {
		return pillarUnder(best, null);
	}

	/** How many open cells a pillar under {@code best} would fill, down to something solid (96 at most). */
	private int pillarDepth(BlockPos best) {
		int n = 0;
		BlockPos c = best.below();
		int floor = Terrain.level().getMinY();
		while (c.getY() > floor && n < 96 && !solid(c) && Terrain.passable(c.getX(), c.getY(), c.getZ())) {
			n++;
			c = c.below();
		}
		return n;
	}

	private boolean pillarUnder(BlockPos best, String why) {
		List<BlockPos> col = new ArrayList<>();
		BlockPos c = best.below();
		int floor = Terrain.level().getMinY();
		while (c.getY() > floor && col.size() < 96 && !solid(c) && Terrain.passable(c.getX(), c.getY(), c.getZ())) {
			col.add(c);
			c = c.below();
		}
		if (col.isEmpty() || !solid(c)) return false; // nothing below to stand it on
		BlockState dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
		boolean dmg = damaged.contains(best.asLong());
		int added = 0;
		for (BlockPos q : col) {
			if (plan.containsKey(q)) continue;
			plan.put(q, dirt);
			planList.add(q);
			planLayers.computeIfAbsent(q.getY(), k -> new ArrayList<>()).add(q);
			support.add(q);
			// Only there to get the first block in (a build starting in mid-air, the ground blown out from under it):
			// it comes out again once that block's in, like a player knocks down the pillar they went up on (the
			// floating gothic portal kept a 29-block dirt pillar under it for good). Not under sand or gravel: that
			// one holds it up.
			if (why == null) temps.put(q, best);
			if (dmg) damaged.add(q.asLong());
			added++;
		}
		// (Nothing new: the spots under it are the build's already. "A pillar" that added nothing was tried again
		// every tick: 201 picks of the same block in 10 s, and no progress for 4 minutes.)
		if (added == 0) return false;
		int bottom = col.get(col.size() - 1).getY();
		scanFromY = Math.min(scanFromY, bottom);
		if (bottom < planBox.minY()) {
			planBox = new Box(planBox.minX(), bottom, planBox.minZ(), planBox.maxX(), planBox.maxY(), planBox.maxZ(), planBox.layers());
		}
		bad.remove(best);
		HumanAutopilot.LOGGER.info("[schem] nothing under {} to build on{}: a {}-block dirt pillar up to it{}", best.toShortString(),
				why != null ? " (" + plan.get(best).getBlock().getName().getString() + ", " + why + ")" : "", col.size(),
				why != null ? "" : " (it comes out again after)");
		return true;
	}

	/**
	 * Its column isn't up to here yet: the spot under it is part of the build and still to do (only for putting blocks
	 * in: clearing goes top down, and waiting for the spot under a stone to be done first, when the stone was in the
	 * way of it, waited for ever). Not for a hanging or flimsy block below (an end rod hanging from the ceiling, a
	 * lantern on a chain, a torch): that one is put in against the block above it, so the block above goes first.
	 * (The cathedral's 400 ceiling lamps: the rod waited for the ceiling, the ceiling waited for the rod.)
	 */
	private boolean columnWaits(BlockPos h) {
		if (!noWait.isEmpty() && noWait.contains(h)) return false; // (told not to: stuck, see the stuck steps)
		// Sand or gravel over it that's going (cleared, or another block there): that first, from the top, or it falls
		// into the spot again as soon as this one's knocked out (the badlands house: red sand, again and again).
		BlockPos over = h.above();
		BlockState overW = Terrain.state(over);
		if (overW.getBlock() instanceof net.minecraft.world.level.block.FallingBlock && jobAt(h) != Placement.Job.ADJUST) {
			BlockState overWant = plan.get(over);
			if (overWant == null && lookup != null) overWant = lookup.apply(over);
			if (overWant != null && overWant.getBlock() != overW.getBlock()) return true;
		}
		// Clearing a hill out of the way: from the top down (one under another still to clear can't be seen: "nowhere to
		// place it from" 71 times on the barn's hillside, each left a minute or more). The same for a spot of the ground
		// under the hill (the badlands villager's terracotta plate under the hill: "nowhere" 70 times in 3 minutes).
		BlockState wantH = plan.get(h);
		if (lookup != null && !temps.containsKey(h)) {
			BlockPos up = h.above();
			BlockState wu = Terrain.state(up);
			// (A tuft of grass or snow on it that's on the clearing list: that first, it's one click and it hides the top
			// face. The cottage's foundation in the hillside: "ray hits" the grass on the dirt to knock out, 28 times.)
			if (!wu.isAir() && wu.canBeReplaced() && wu.getFluidState().isEmpty() && !cant.contains(up)) {
				BlockState planUp = plan.get(up);
				if (planUp != null && (planUp.isAir() || clearFirst.contains(up)) && !wu.getShape(Terrain.level(), up).isEmpty()) return true;
			}
			// (Leaves over it where the build has a block too - a tree's top in the build's own spot - count as well: the
			// black hole's wall in a wood, bottom rows under oak leaves to knock out, picked first and "nowhere".)
			boolean leavesUp = wu.is(net.minecraft.tags.BlockTags.LEAVES);
			BlockState planUp0 = plan.get(up);
			// (Not on one that can't be knocked out at all - bedrock over a floor spot: it waited for good.)
			if (!wu.isAir() && !wu.canBeReplaced() && wu.getFluidState().isEmpty() && (!leavesUp || (planUp0 != null && !planUp0.isAir()))
					&& !cant.contains(up) && Placement.breakable(Terrain.level(), up, wu)) {
				BlockState planUp = planUp0;
				BlockState schemUp = planUp != null ? planUp : lookup.apply(up);
				if (!leavesUp && schemUp != null && schemUp.isAir()) return true;
				// (Or the hill's dirt where a block of the build goes over it, while this one's to be knocked out too: from
				// the top. The barn's hay bales stacked in the hillside: the lower one picked first, "nowhere", 8 left.)
				if (planUp != null && !planUp.isAir() && jobAt(up) == Placement.Job.BREAK && jobAt(h) == Placement.Job.BREAK) return true;
				// (Or a spot to fill under it: the hill's dirt over it, where the build has a block of its own, comes out
				// first; till then it's buried. The black hole's pixel-art wall, 1 thick, into a hillside: its bottom rows
				// picked one after another, "nowhere to place it from", "confused" 19-26 targets in 10 s.)
				if (planUp != null && !planUp.isAir() && jobAt(up) == Placement.Job.BREAK && jobAt(h) == Placement.Job.PLACE
						&& (worldGround(wu) || leavesUp)) return true;
			}
		}
		if (wantH != null && wantH.isAir()) return false;
		// A pocket in the hill: the ground layers' air under more of it up past the floor, the hill's own ground that's to
		// be cleared on top. Sealed till then ("0 with a view" from 33 open spots over it, the villager's third go: each
		// of those pulled him back from the walls to the ground, 40 times). The hill first.
		BlockState overH = Terrain.state(h.above());
		if (clearFromY != Integer.MAX_VALUE && h.getY() < clearFromY && lookup != null && (overH.isAir() || overH.canBeReplaced())) {
			for (int y = h.getY() + 2; y <= clearFromY + 10; y++) {
				BlockPos u = new BlockPos(h.getX(), y, h.getZ());
				BlockState wu = Terrain.state(u);
				if (wu.isAir() || wu.canBeReplaced() || !wu.getFluidState().isEmpty()) continue;
				if (wu.is(net.minecraft.tags.BlockTags.LEAVES) || cant.contains(u)) break;
				BlockState pu = plan.get(u);
				BlockState su = pu != null ? pu : lookup.apply(u);
				if (su != null && su.isAir()) return true;
				break;
			}
		}
		// A temporary block doesn't wait on the one under it: it's there because that one can't go in by itself (the
		// cottage's upside-down stair, its temporary block over it waiting on the stair, the stair on the temporary block:
		// 9 minutes, the whole wall over it waiting too, and the inside started early).
		if (temps.containsKey(h)) return false;
		if (sealsHoleBeside(h)) return true;
		BlockPos under = h.below();
		BlockState wantUnder = plan.get(under);
		if (wantUnder == null || wantUnder.isAir()) return false;
		if (!wantUnder.isCollisionShapeFullBlock(Terrain.level(), under)
				&& !(wantUnder.getBlock() instanceof net.minecraft.world.level.block.SlabBlock)
				&& !(wantUnder.getBlock() instanceof net.minecraft.world.level.block.StairBlock)) return false;
		if (!hole(under) || jobAt(h) != Placement.Job.PLACE) return false;
		// (Only if the one below can go in by itself: one hanging in the air, with nothing to click on, goes in against
		// this one, so this one goes first. Both waiting on each other froze the statue's hanging parts.)
		// (Something to click on the right way round, that is: a post of upright wood blown out under its top piece, the
		// wall beside the bottom piece "something to click" but a log against it lies sideways - the bottom waited for
		// the middle, the middle for the bottom, both out for 5 minutes after the TNT on the barn.)
		if (!workable(under)) return false;
		BlockState wu = plan.get(under);
		int sides = wu != null ? rightSides(wu) : 0x3F;
		if (sides == 0x3F) return true;
		for (Direction d : Direction.values()) {
			if ((sides & (1 << d.ordinal())) != 0 && !under.relative(d).equals(h) && solid(under.relative(d))) return true;
		}
		return false;
	}

	/**
	 * Putting this in would shut in a spot beside it that's still to fill: every other side of that one is solid already,
	 * so this is its last way in (seen through, or reached through). That one first - unless it's had no spot three
	 * times, then this goes in anyway. (TNT craters in the user's fantasy build: the bottom's middle had no spot, the
	 * ring over it went in, and those were shut in for good - "repairing 7 damaged spots first", "nowhere", "can't get
	 * to it", 30 s stalls. The castle's rock: its bottom stragglers built over the same way.)
	 */
	private boolean sealsHoleBeside(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null || want.isAir() || !want.isCollisionShapeFullBlock(Terrain.level(), h)) return false;
		for (Direction d : Direction.values()) {
			BlockPos n = h.relative(d);
			if (solid(n)) continue; // (filled already, or something there)
			BlockState pn = plan.get(n);
			if (pn == null || pn.isAir() || temps.containsKey(n) || cant.contains(n) || nowhere.getOrDefault(n, 0) >= 3) continue;
			boolean open = false;
			for (Direction d2 : Direction.values()) {
				if (d2 == d.getOpposite()) continue; // (that's this spot)
				if (!solid(n.relative(d2))) {
					open = true;
					break;
				}
			}
			if (!open && jobAt(n) == Placement.Job.PLACE) return true;
		}
		return false;
	}

	/**
	 * Under the world's own ground in its column: the top of the ground there is more than a block over it and is a spot
	 * the build has empty (a hill to clear), not the build's own block.
	 */
	private boolean underHill(BlockPos h) {
		if (!Terrain.loaded(h.getX(), h.getZ())) return false;
		int g = Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.getX(), h.getZ()) - 1;
		if (g <= h.getY() + 1) return false;
		BlockPos topC = new BlockPos(h.getX(), g, h.getZ());
		BlockState top = lookup.apply(topC);
		// (The top a hill's: the world's own ground. Anything else there - a stray block over a castle's tower, a temporary
		// block of ours - isn't a hill to dig down through: the pocket test's flat-world castle, its wall cores' stone
		// "under the ground still to clear (27 over it): digging down to it" from the top of a tower, 30 s wedged.)
		if (top != null && top.isAir() && worldGround(Terrain.state(topC))) return true;
		// (Or the world's own ground right over it, where the build has something else: a wall's spots still full of the
		// hill's dirt, the build's own block at the column's top - the hill test's wall foot, 16 under it, dived at from
		// 40 up and given up on.)
		BlockPos u = h.above();
		BlockState wu = Terrain.state(u), pu = plan.get(u);
		return worldGround(wu) && (pu == null || !Placement.matches(wu, pu));
	}

	/** The goal's cell is under the world's own ground (its column's top well over it, ground right over it): no air way. */
	private boolean goalBuried(Vec3 goal) {
		BlockPos c = BlockPos.containing(goal.x, goal.y + 0.05, goal.z);
		if (lookup == null || !Terrain.loaded(c.getX(), c.getZ())) return false;
		int g = Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1;
		if (g <= c.getY() + 2) return false;
		BlockPos u = c.above(2);
		BlockState wu = Terrain.state(u), pu = plan.get(u);
		return worldGround(wu) && (pu == null || !Placement.matches(wu, pu));
	}

	/** Spots he couldn't fly to lately, and when (see planOk). */
	private final Map<BlockPos, Long> flyFails = new HashMap<>();

	/** Why a hole isn't picked: the first of planOk's (and columnWaits') reasons that holds. */
	private String whyNotOk(BlockPos h, Map<BlockPos, BlockPos> flyTo, CreativePicker picker) {
		if (!holes.contains(h)) return "not in the holes (" + jobAt(h) + ", has " + Terrain.state(h).getBlock().getName().getString() + ")";
		if (hole(h) && !openFace(h)) return "buried (a full block on every side: nothing of it to see)";
		if (columnWaits(h)) {
			// (Which one it waits on, and why that one isn't being done: the wizard tower's leaves, "waits on its column"
			// for minutes and nothing more to go on.)
			BlockPos u = h.below(), o = h.above();
			return "waits on its column (over it " + Terrain.state(o).getBlock().getName().getString() + ", plan " + plan.get(o)
					+ "; under it " + jobAt(u) + " has " + Terrain.state(u).getBlock().getName().getString()
					+ (hole(u) ? ", a hole: workable " + workable(u) + ", in the window " + holes.contains(u) + ", skipped "
					+ (bad.containsKey(u) ? failWhy.getOrDefault(u, "?") : "no") + ", window from y " + activeY : "") + ")";
		}
		if (bad.containsKey(h)) return "skipped (" + failWhy.getOrDefault(h, "?") + ")";
		if (!hole(h)) return "not a hole";
		if (!workable(h)) return "nothing to click on";
		if (waitsOnSupport(h)) return "waits for its support";
		if (needsTemp(h)) {
			// (Which, and why that isn't in: the cottage's stair waited 9 minutes on one that never went in, nothing said why.)
			StringBuilder sb = new StringBuilder("needs a temporary block");
			for (var e : temps.entrySet()) {
				if (!e.getValue().equals(h)) continue;
				BlockPos q = e.getKey();
				sb.append(" [").append(q.toShortString()).append(' ').append(jobAt(q));
				if (hole(q)) {
					sb.append(bad.containsKey(q) ? ", skipped: " + failWhy.getOrDefault(q, "?") : "")
							.append(cant.contains(q) ? ", given up" : "").append(workable(q) ? "" : ", nothing to click on")
							.append((inRegion(q.getX(), q.getY(), q.getZ()) || airShutIn) && !gettable(q, flyTo) ? ", can't get to it" : "");
				}
				sb.append(']');
			}
			return sb.toString();
		}
		if (tempPending(h)) return "its temporary block isn't in yet";
		if (lookup != null && !inRegion(h.getX(), h.getY(), h.getZ()) && underHill(h)) return "under the hill still to clear";
		if ((inRegion(h.getX(), h.getY(), h.getZ()) || airShutIn) && !gettable(h, flyTo)) return "can't get to it";
		return planOk(h, flyTo, picker) ? "ok" : "other (water, menu...)";
	}

	private long dmgWhyAt = -1000;
	private long leftWhyAt = -10000;

	private boolean planOk(BlockPos h, Map<BlockPos, BlockPos> flyTo, CreativePicker picker) {
		if (bad.containsKey(h) || !hole(h)) return false;
		// Nothing of it can be seen from anywhere (a full block on every side): not now - see openFace. (First: six quick
		// looks, before the slower checks below.)
		if (!openFace(h)) {
			buriedPassed++;
			return false;
		}
		// The companion's on it, or right there: hers (see talk).
		if (!helpersNow.isEmpty() && helperClaims(h)) {
			helperPassed++;
			return false;
		}
		if (!workable(h) || waitsOnSupport(h) || needsTemp(h) || tempPending(h)) return false;
		// The open-air map only covers the space round us: farther holes count as reachable (we fly over). Not when
		// we're shut in: then only what's in here.
		if ((inRegion(h.getX(), h.getY(), h.getZ()) || airShutIn) && !gettable(h, flyTo)) return false;
		// Far off and under the world's own ground that's to be cleared over it (a hill over the build): not yet, the
		// clearing from the top comes first. (Taken for reachable because it was far, it had him fly down at it, find the
		// hill in the way, fly back up 30 blocks and down again at the next, 2.5 minutes - the user's "doesn't know how
		// to dig straight down".)
		if (lookup != null && !inRegion(h.getX(), h.getY(), h.getZ()) && underHill(h)) return false;
		// Round a spot he just couldn't fly to (the same ground in the way): not for a minute. (Far ones only: one round
		// the map here has open air we can get to right next to it - checked above. One slow way down through the tower's
		// roof hatch put its whole floor out of bounds for a minute, and then the floor was given up on.)
		if (!flyFails.isEmpty() && !inRegion(h.getX(), h.getY(), h.getZ())) {
			long now0 = HumanAutopilot.ticks();
			for (var e : flyFails.entrySet()) {
				if (now0 - e.getValue() < 1200 && e.getKey().distManhattan(h) <= 5 && Math.abs(e.getKey().getY() - h.getY()) <= 3) return false;
			}
		}
		BlockState want = plan.get(h);
		// A plant, a torch, a rail...: not where water runs now or can run in (it washes them straight off: the castle's
		// short grass by a leaking trough went back in again and again, "up down up down").
		if (jobAt(h) == Placement.Job.PLACE && washedAway(want) && waterOnIt(h)) return false;
		// (The same for watering a block that holds water - a waterlogged stair is a water source with open sides: the
		// dining hall's, watered at 6 minutes with the farm round it not built, ran out over the whole floor, 111 blocks.)
		boolean watering = jobAt(h) == Placement.Job.ADJUST && itemFor(h) == net.minecraft.world.item.Items.WATER_BUCKET;
		if ((Placement.fluid(want) && jobAt(h) == Placement.Job.PLACE) || watering) {
			// Water last: everything round it built first, so it stays where it belongs (and floods nothing). Not just
			// the blocks touching it: water runs up to 7 blocks through any gap still open (the modern mansion's pool
			// went in with its walls half done; the water ran out, washed away 42 blocks, and a snow layer 4 blocks off
			// was put back and washed away again for minutes).
			// (The build's own water beside it doesn't count: it'll be water there anyway. Counted, a pond's spots each waited
			// on the next for ever - the Himeji castle's moat, 31k of them. Where it runs on from there, waterWouldRun sees.)
			for (Direction d : Direction.values()) {
				if (d == Direction.UP) continue;
				BlockPos n = h.relative(d);
				if (plan.containsKey(n) && hole(n) && !Placement.fluid(plan.get(n))) return false;
			}
			if (waterWouldRun(h)) return false;
			if (!watering && waterWaits(h)) return false;
		}
		Item it = itemFor(h);
		return it == null || !picker.failed(it);
	}

	/**
	 * It only goes in the right way round against a block of the build that isn't in yet (an end rod hanging from a
	 * ceiling that's still to come): it waits for that block, rather than being picked and given up on over and over
	 * (the cathedral's 2300 rods under its basement ceiling, picked before the ceiling was there: "175 targets in 10 s,
	 * nothing placed", the confused looking and flying about). Not when nothing will be there (a temporary block's job)
	 * or when that block waits on this one in turn.
	 */
	private boolean waitsOnSupport(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null || want.isAir() || jobAt(h) != Placement.Job.PLACE) return false;
		// (A plant - a crop, grass, a flower - that can't stand there yet over a spot still to do under it: the farmland
		// still grass to till, the ground under it not in. That first. The cottage on its hill: wheat over grass, tall
		// grass over the ground still to come, "nowhere to place it from" 4-5 times each.)
		// (Anything that can't stand there yet, not just plants: bamboo or cane over the stalk still to go back under it -
		// the castle's bamboo went "nowhere" piece by piece over the air where its shoot was to go.)
		if (!Placement.fluid(want) && !want.canSurvive(Terrain.level(), h) && plan.get(h.below()) != null && hole(h.below())) return true;
		// A tall plant (tall grass, a large fern, a sunflower...) takes the spot over it too: with anything solid there it
		// won't go in at all (the game says no: "would put null"). What's there first, out of the way, like a player would.
		// (The copper guard's tall grass under a tree's leaves: "nowhere to place it from" 105 times, tried again and again.)
		if (want.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock
				&& want.hasProperty(net.minecraft.world.level.block.DoublePlantBlock.HALF)
				&& want.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
			BlockPos up = h.above();
			BlockState wu = Terrain.state(up);
			if (!wu.isAir() && !wu.canBeReplaced() && wu.getFluidState().isEmpty() && Placement.breakable(Terrain.level(), up, wu) && !cant.contains(up)) {
				BlockState pu = plan.get(up);
				if (pu == null || pu.isAir()) {
					// (On the clearing list at the next pick, not from in here: this is asked while lists are gone through.)
					clearAboveQueue.add(up.immutable());
					return true;
				}
				// (Its own top half's spot: knocked out as a wrong block.)
				if (pu.getBlock() == want.getBlock() && jobAt(up) == Placement.Job.BREAK) return true;
			}
		}
		if (pad(want)) {
			// (A lily pad waits for the water under it, if that's still to go in.)
			BlockState under = plan.get(h.below());
			return !padBase(h.below()) && under != null && (Placement.fluid(under) || under.getBlock() instanceof net.minecraft.world.level.block.IceBlock
					|| under.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER) && hole(h.below());
		}
		if (fallsNow(h, want)) return true;
		int sides = rightSides(want);
		if (sides == 0x3F) return false;
		boolean coming = false;
		for (Direction d : Direction.values()) {
			if ((sides & (1 << d.ordinal())) == 0) continue;
			BlockPos n = h.relative(d);
			if (solid(n)) return false; // there to click on now
			if (!coming && comes(n, h, 24)) coming = true; // (a row of logs on their side: each from the one before)
		}
		return coming;
	}

	/** Spots that need a dirt pillar under them before they can go in (put up at the start of the next pick). */
	private final java.util.Set<BlockPos> needPillar = new java.util.LinkedHashSet<>();
	/** What's over a tall plant still to go in, to go on the clearing list at the start of the next pick. */
	private final java.util.Set<BlockPos> clearAboveQueue = new java.util.LinkedHashSet<>();

	/**
	 * Sand, gravel, concrete powder with nothing under it: it would fall straight down. The build's own block under it
	 * first; with nothing of the build under it (its bottom layer over lower ground - built where he stood on a hillside,
	 * or the ground blown out from under it), a dirt pillar up to it first. (The medieval house's light gray powder fell
	 * one block after another, and only then "a 3-block dirt pillar up to it" went in.) Inside the build with the
	 * build's air under it (pasted floating): nothing can hold it there; it goes in and falls, as before.
	 */
	private boolean fallsNow(BlockPos h, BlockState want) {
		if (!(want.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return false;
		BlockPos below = h.below();
		if (!net.minecraft.world.level.block.FallingBlock.isFree(Terrain.state(below))) return false;
		BlockState under = plan.get(below);
		if (under != null && !under.isAir()) return !net.minecraft.world.level.block.FallingBlock.isFree(under); // that first
		// Over the build's own air (pasted hanging there): a pillar would fill that in (fallsThrough leaves it out).
		if ((under != null && under.isAir()) || (lookup != null && lookup.apply(below) != null)) return false;
		// Nothing of the build under it: below its bottom (the dirt fill under the rest doesn't count - the house's
		// powder at the floor over a dip too deep to fill). Without the schematic's box to go by: its bottom layer only.
		if (lookup == null && h.getY() > planBottomY) return false;
		if (cantPillar.contains(h)) return false;
		needPillar.add(h.immutable());
		return true;
	}

	/** Spots a pillar couldn't be put under (nothing solid below in reach): they go in anyway. */
	private final java.util.Set<BlockPos> cantPillar = new java.util.HashSet<>();

	/**
	 * Goes in the right way round only against a side with nothing there and nothing coming (wood with its grain up, air
	 * over and under it): a temporary block there first, set up from here, rather than flying over to find out "nowhere
	 * to place it from" (the fantasy building's tree: 50-block flights back and forth, one dark oak block after
	 * another). Once that's been tried and nothing came of it, the old way (picked, and left after a few goes).
	 */
	private boolean needsTemp(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null || want.isAir() || pad(want) || jobAt(h) != Placement.Job.PLACE) return false;
		// Its temporary block on the way (one given up on isn't): wait for it, first of all. (After the check below, a
		// bottom slab beside an upside-down stair counted as something to click - its upper half is empty - the stair
		// was picked before its temporary block went in, failed 5 times and was left: the cottage's oak stairs.)
		for (var e : temps.entrySet()) {
			if (e.getValue().equals(h) && !cant.contains(e.getKey()) && plan.get(e.getKey()) != null && !plan.get(e.getKey()).isAir()
					&& jobAt(e.getKey()) != Placement.Job.DONE) return true;
		}
		int sides = rightSides(want);
		if (sides == 0x3F) return false;
		// (A trapdoor or a stair goes against any solid block's face the right way: a fence post's side, a slab's top.)
		boolean anySolid = want.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| want.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
		for (Direction d : Direction.values()) {
			BlockPos n = h.relative(d);
			if ((sides & (1 << d.ordinal())) != 0 && solid(n) && (anySolid || Terrain.state(n).isCollisionShapeFullBlock(Terrain.level(), n))) return false;
		}
		if (scaffoldTried.contains(h)) return false;
		if (scaffoldBudget <= 0) return true; // (a few a pick: the rest next time)
		scaffoldBudget--;
		tryScaffold(Minecraft.getInstance().player, h);
		return true;
	}

	private int scaffoldBudget;

	/**
	 * A temporary block under the block it was for (a floor of upright wood over a room: only a click on a top or bottom
	 * face puts it in that way) stays while the floor's next block beside that one still has to go in with nothing under
	 * it: that one's temporary block then goes in against this one, instead of a new one with "1 or 2 to hold it up"
	 * each time (the 25k house's first floor: 166 logs, 2.2 glass each, put in and knocked out again).
	 */
	private boolean keepsForNext(BlockPos tmp, BlockPos forT) {
		if (!forT.equals(tmp.above())) return false;
		BlockState wantT = plan.get(forT);
		if (wantT == null || wantT.isAir()) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = tmp.relative(d), up = n.above();
			BlockState wu = plan.get(up);
			if (wu == null || wu.getBlock() != wantT.getBlock() || cant.contains(up) || jobAt(up) != Placement.Job.PLACE) continue;
			BlockState pn = plan.get(n);
			if (temps.containsKey(n)) {
				if (pn != null && !pn.isAir() && jobAt(n) == Placement.Job.PLACE) return true; // (its temporary block goes in against this)
				continue; // (in already: this one's not needed for it)
			}
			if (pn != null && !pn.isAir()) continue; // (the build's own block goes there)
			BlockState wn = Terrain.state(n);
			if (wn.isAir() || wn.canBeReplaced()) return true; // (nothing there: its temporary block will want this one)
		}
		return false;
	}

	/** Its temporary blocks are all in (and it isn't). */
	/**
	 * Its temporary block (to click on) is still to go in: not before that. (The cottage's top-half stairs: tried again
	 * and again before their temporary block was in - "holds one", first - five goes in 6 s, and left as they were.)
	 */
	private boolean tempPending(BlockPos h) {
		if (temps.isEmpty()) return false;
		for (var e : temps.entrySet()) {
			if (!e.getValue().equals(h)) continue;
			BlockState w = plan.get(e.getKey());
			if (w != null && !w.isAir() && hole(e.getKey())) return true;
		}
		return false;
	}

	private boolean tempReady(BlockPos h) {
		if (temps.isEmpty() || jobAt(h) != Placement.Job.PLACE) return false;
		boolean any = false;
		for (var e : temps.entrySet()) {
			if (!e.getValue().equals(h)) continue;
			BlockState w = plan.get(e.getKey());
			if (w == null || w.isAir()) continue;
			if (jobAt(e.getKey()) != Placement.Job.DONE) return false;
			any = true;
		}
		return any;
	}

	/** Hangs from the block over it (only that way round from there), and that block is in. */
	private boolean hangsNow(BlockPos h) {
		BlockState want = plan.get(h);
		return want != null && !want.isAir() && rightSides(want) == 1 << Direction.UP.ordinal() && jobAt(h) == Placement.Job.PLACE
				&& solid(h.above());
	}

	/** What's to go under this spot hangs from it (and is still to go in). */
	private boolean holdsHanger(BlockPos h) {
		BlockPos u = h.below();
		BlockState wu = plan.get(u);
		return wu != null && !wu.isAir() && rightSides(wu) == 1 << Direction.UP.ordinal() && jobAt(u) == Placement.Job.PLACE
				&& jobAt(h) == Placement.Job.PLACE;
	}

	/** The neighbour on one of the right sides of {@code h} that's still to go in and can go in without it, or null. */
	private BlockPos supportComing(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null) return null;
		int sides = rightSides(want);
		if (sides == 0x3F) return null;
		for (Direction d : Direction.values()) {
			if ((sides & (1 << d.ordinal())) == 0) continue;
			BlockPos n = h.relative(d);
			if (hole(n) && comes(n, h, 24)) return n;
		}
		return null;
	}

	/** The build puts a block at {@code n} that can go in without the one at {@code h} (waiting on it). */
	private boolean comes(BlockPos n, BlockPos h, int depth) {
		BlockState wn = plan.get(n);
		if (wn == null || wn.isAir() || Placement.fluid(wn) || temps.containsKey(n) || jobAt(n) != Placement.Job.PLACE) return false;
		int sides = rightSides(wn);
		if (sides == 0x3F) return true;
		for (Direction d : Direction.values()) {
			if ((sides & (1 << d.ordinal())) == 0) continue;
			BlockPos m = n.relative(d);
			if (m.equals(h)) continue;
			if (solid(m) || (depth > 0 && comes(m, n, depth - 1))) return true;
		}
		return false;
	}

	/**
	 * The sides of a spot whose block, clicked on its face towards the spot, puts {@code want} in the right way round (a
	 * bit per Direction; 0x3F: any, as far as this knows). A rod points away from the block it was clicked on; a log
	 * lies along the face it was clicked on.
	 */
	private static int rightSides(BlockState want) {
		net.minecraft.world.level.block.Block b = want.getBlock();
		if (b instanceof net.minecraft.world.level.block.RodBlock && want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
			return 1 << want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getOpposite().ordinal();
		}
		if (b instanceof net.minecraft.world.level.block.RotatedPillarBlock && want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
				&& !Placement.barkAllRound(want)) {
			Direction.Axis a = want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
			int m = 0;
			for (Direction d : Direction.values()) if (d.getAxis() == a) m |= 1 << d.ordinal();
			return m;
		}
		// A trapdoor faces the face it's clicked on (so it goes against the block behind it), or, clicked on a top or a
		// bottom, sits in that half: the shutter beside a window against the wall it hangs on, not any block round it.
		// (House 547's open shutters, 34 "nowhere to place it from": picked before their wall was in, then left.)
		if (b instanceof net.minecraft.world.level.block.TrapDoorBlock
				&& want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
				&& want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
			Direction f = want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
			boolean top = want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
					== net.minecraft.world.level.block.state.properties.Half.TOP;
			return (1 << f.getOpposite().ordinal()) | (1 << (top ? Direction.UP : Direction.DOWN).ordinal());
		}
		// A stair faces the way we look; its upper or lower half is the half of the face clicked: an upside-down one
		// against anything beside it or the block over it, not on the block under it.
		if (b instanceof net.minecraft.world.level.block.StairBlock
				&& want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
			boolean top = want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
					== net.minecraft.world.level.block.state.properties.Half.TOP;
			int m = 0;
			// (Not the one behind it: clicking that one's face we'd look the other way. With only that one in, the
			// castle's upside-down stairs under the eaves stopped waiting and went "nowhere to place it from".)
			Direction back = want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).getOpposite();
			for (Direction d : Direction.Plane.HORIZONTAL) if (d != back) m |= 1 << d.ordinal();
			return m | (1 << (top ? Direction.UP : Direction.DOWN).ordinal());
		}
		return 0x3F;
	}

	/**
	 * A pond's water poured like a player does it: one spot in every 2 x 2 first (the lattice, x and z both even), and a
	 * spot with two sources beside it and something under it left to fill itself (the game turns it into a source).
	 * One bucket per spot, the moat's 31k sources were 3 hours of clicking; this way about a quarter. Not for long,
	 * though: a spot kept waiting 30 s (15 s once it should be filling itself) gets its bucket after all.
	 */
	private boolean waterWaits(BlockPos h) {
		long now = HumanAutopilot.ticks();
		Long since = waterWaitAt.get(h);
		boolean fills = selfFills(h);
		boolean wait = fills;
		if (!wait && ((h.getX() & 1) != 0 || (h.getZ() & 1) != 0)) {
			// Off the lattice: a lattice spot of the same water right by it still to pour, that first.
			for (int dx = -1; dx <= 1 && !wait; dx++) {
				for (int dz = -1; dz <= 1 && !wait; dz++) {
					if (((h.getX() + dx) & 1) != 0 || ((h.getZ() + dz) & 1) != 0) continue;
					BlockPos n = h.offset(dx, 0, dz);
					BlockState wn = plan.get(n);
					if (wn != null && Placement.fluid(wn) && jobAt(n) == Placement.Job.PLACE && !cant.contains(n)) wait = true;
				}
			}
		}
		if (!wait) return false;
		if (since == null) {
			if (waterWaitAt.size() > 100_000) waterWaitAt.clear();
			waterWaitAt.put(h.immutable(), now);
			return true;
		}
		return now - since < (fills ? 300 : 600);
	}

	/** The window's holes as last listed in full, for which layers, and when (see scanPlan). */
	private final List<BlockPos> holesFull = new ArrayList<>();
	private List<Integer> holesFullWin = List.of();
	private long holesFullAt = -1000;

	/** Spots of the build's water told to wait (see waterWaits), and since when. */
	private final Map<BlockPos, Long> waterWaitAt = new HashMap<>();

	/** Water would turn into a source here by itself: two sources beside it, and under it a solid block or a source. */
	private static boolean selfFills(BlockPos h) {
		BlockState w = Terrain.state(h);
		if (!w.isAir() && !(w.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && !w.getFluidState().isSource())) return false;
		int src = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState n = Terrain.state(h.relative(d));
			if (n.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && n.getFluidState().isSource()
					&& n.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER) src++;
		}
		if (src < 2) return false;
		BlockState below = Terrain.state(h.below());
		return below.isSolid() || below.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;
	}

	/**
	 * Water poured at {@code h} would run into a spot of the build still to go in: where it can flow from there (down any
	 * way, sideways up to 8 out, through anything it passes through). The 8-round, one-level-down check let the modern
	 * mansion's water fall a few levels and fill where a door, a furnace and 14 coral fans were still to go.
	 */
	private final Map<BlockPos, long[]> waterChecked = new HashMap<>(); // spot -> {until, 1 if it would run}

	private boolean waterWouldRun(BlockPos h) {
		long now = HumanAutopilot.ticks();
		long[] c0 = waterChecked.get(h);
		if (c0 != null && c0[0] > now) return c0[1] == 1;
		boolean run = waterWouldRun0(h);
		waterChecked.put(h, new long[]{now + (run ? 100 : 20), run ? 1 : 0});
		if (waterChecked.size() > 4000) waterChecked.values().removeIf(v -> v[0] <= now);
		return run;
	}

	private boolean waterWouldRun0(BlockPos h) {
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		q.add(h);
		seen.add(h);
		while (!q.isEmpty() && seen.size() < 3000) {
			BlockPos c = q.poll();
			for (Direction d : Direction.values()) {
				if (d == Direction.UP) continue;
				BlockPos n = c.relative(d);
				if (seen.contains(n) || n.getY() < h.getY() - 24) continue;
				if (d != Direction.DOWN && (Math.abs(n.getX() - h.getX()) > 8 || Math.abs(n.getZ() - h.getZ()) > 8)) continue;
				BlockState w = Terrain.state(n);
				if (!(w.isAir() || w.canBeReplaced() || w.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) continue;
				BlockState want = plan.get(n);
				if (want != null && !want.isAir() && !Placement.fluid(want) && hole(n)) return true;
				// (Anywhere the finished build has no water - air, or outside it - it would run out and stay: the dining
				// hall's bubble elevator went in with its shaft still open at the bottom, and 90 blocks of its floor got
				// water. Only on through spots the build has water in.)
				if (lookup != null) {
					BlockState fin = want != null ? want : lookup.apply(n);
					if (fin == null || fin.getFluidState().isEmpty()) return true;
				}
				seen.add(n);
				q.add(n);
			}
		}
		return false;
	}

	/**
	 * Water running into it knocks it off (FlowingFluid's own rule: no collision, nothing for water to sit in, not a
	 * door, sign, ladder or sugar cane): plants, torches, rails, buttons, carpets of snow.
	 */
	private static boolean washedAway(BlockState s) {
		var b = s.getBlock();
		if (s.isAir() || Placement.fluid(s) || b instanceof net.minecraft.world.level.block.LiquidBlockContainer || s.blocksMotion()) return false;
		return !(b instanceof net.minecraft.world.level.block.DoorBlock) && !s.is(net.minecraft.tags.BlockTags.SIGNS)
				&& !s.is(net.minecraft.world.level.block.Blocks.LADDER) && !s.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE)
				&& !s.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
	}

	/** Water in this spot, or beside or over it able to run in. */
	private static boolean waterOnIt(BlockPos h) {
		if (!Terrain.state(h).getFluidState().isEmpty()) return true;
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			var fl = Terrain.state(h.relative(d)).getFluidState();
			if (fl.isEmpty()) continue;
			if (d == Direction.UP || fl.isSource() || fl.getAmount() > 1) return true;
		}
		return false;
	}

	/** Water running through a spot of the build that's to be a block that holds it back: put in, it stops the flow. */
	private static boolean plugsWater(BlockPos h) {
		var fl = Terrain.state(h).getFluidState();
		if (fl.isEmpty() || fl.isSource()) return false;
		BlockState want = plan.get(h);
		return want != null && !want.isAir() && !Placement.fluid(want) && !washedAway(want) && jobAt(h) == Placement.Job.PLACE;
	}

	/** Something small that goes on the finished structure (torch, flower, sign, pot, lantern, crop, water): last. */
	private boolean decor(BlockPos h) {
		BlockState want = plan.get(h);
		if (want == null || want.isAir() || jobAt(h) != Placement.Job.PLACE) return false;
		// Not the crops: planted straight after their farmland, like a farmer (bare farmland from the menu dries back to
		// dirt within a minute or so, and grass grows over it).
		BlockState under = plan.get(h.below());
		if (under != null && under.is(net.minecraft.world.level.block.Blocks.FARMLAND)) return false;
		return Placement.fluid(want) || Placement.decoration(Terrain.level(), h, want);
	}

	private static boolean inRegion(int x, int y, int z) {
		return Math.abs(x - regX) <= REG_H - 1 && Math.abs(z - regZ) <= REG_H - 1 && y >= regY - REG_V + 1 && y <= regY + REG_V - 1;
	}

	private void setTarget(BlockPos t) {
		lastJob = null;
		crowdFallback = false;
		blockedTicks = 0;
		standLow = false;
		standOff = Vec3.ZERO;
		lowHover = false;
		if (target == null || !target.equals(t)) {
			picks.addLast(new long[]{HumanAutopilot.ticks(), t.asLong()});
			statusTargets++;
		}
		target = t;
		bestDist = Double.MAX_VALUE;
		progressAt = HumanAutopilot.ticks();
		shaftSince = -1;
		atStandSince = -1;
		pickedAt = HumanAutopilot.ticks();
		tFly = tAim = tWait = tPending = offFace = 0;
		var pl = Minecraft.getInstance().player;
		pickTurn = pl == null ? 0 : Math.toDegrees(Math.acos(Math.clamp(
				pl.getViewVector(1f).dot(Vec3.atCenterOf(t).subtract(pl.getEyePosition()).normalize()), -1, 1)));
		locked = null;
		placingSince = -1;
		stand = null;
		tries = 0;
		targetClicks = 0;
		clickedAt = -1;
		faceOffA = Human.gauss(-0.15, 0.15);
		faceOffB = Human.gauss(-0.15, 0.15);
	}

	/** Where the other players are (the companion is a player too), within 48 blocks. */
	private static List<Vec3> otherPlayers(LocalPlayer p) {
		List<Vec3> out = new ArrayList<>();
		for (var other : p.level().players()) {
			if (other == p || other.isSpectator() || other.distanceToSqr(p) > 48 * 48) continue;
			out.add(other.position().add(0, 0.9, 0));
		}
		return out;
	}

	private static double nearest(List<Vec3> points, Vec3 c) {
		double best = Double.MAX_VALUE;
		for (Vec3 q : points) best = Math.min(best, q.distanceTo(c));
		return best;
	}

	/** Solid on at least three of its four sides. */
	private static boolean narrow(BlockPos h) {
		int walls = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (solid(h.relative(d))) walls++;
		return walls >= 3;
	}

	/** Solid on at least three sides at both feet and head height: a 1x1 hole. */
	private static boolean inShaft(LocalPlayer p) {
		BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.1, p.getZ());
		int walls = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (solid(feet.relative(d)) && solid(feet.above().relative(d))) walls++;
		}
		return walls >= 3;
	}

	/**
	 * Creative only: find a block in the box that sits on a hole (topmost of such a stack, open above), fly to
	 * where we can see it, and knock it out with one left-click. Null when there's nothing to fix.
	 */
	private String fixLid(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		// Off: the user found breaking into roofs (to reach sealed-in holes) too slow. Sealed-in holes are simply left
		// (pickTarget skips them), and everything else keeps getting filled.
		if (true) {
			lid = null;
			openingCol = null;
			return null;
		}
		if (!p.getAbilities().instabuild) return null;
		if (lid != null && (!solid(lid) || now - lidSince > 200)) {
			if (!solid(lid)) {
				lidsBroken++;
				nextClickAt = now + Human.reaction();
				bad.clear(); // the hole under it can be reached now: no waiting out its skip timer
				openingCol = lid;
				openingUntil = now + 200;
			}
			lid = null;
			stand = null;
			return null; // filling takes over again next tick
		}
		if (lid == null) {
			lid = findLid(p, b);
			if (lid == null) return null;
			lidSince = now;
			stand = null;
			HumanAutopilot.LOGGER.info("[box] a block at {} covers a hole: knocking it out", lid.toShortString());
		}
		BlockPos l = lid;
		Face f = breakFace(p, p.getEyePosition(), p.getViewVector(1f), l);
		if (f != null) {
			brake(p, in);
			Vec3 pt = f.point;
			work(aim, pt);
			HitResult hr = clickRay(p);
			if (now >= nextClickAt && hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
					&& bhr.getBlockPos().equals(l)) {
				in.click(InputDriver.opts().keyAttack);
				nextClickAt = now + 6;
			}
			return "knocking out a misplaced block";
		}
		if (stand == null) {
			stand = standFor(p, b, l, (eye, look) -> breakFace(p, eye, look, l));
			progressAt = now;
			bestDist = Double.MAX_VALUE;
			if (stand == null) {
				lid = null;
				return null;
			}
		}
		Vec3 goal = new Vec3(stand.getX() + 0.5, stand.getY() + hov(stand), stand.getZ() + 0.5);
		work(aim, Vec3.atCenterOf(l));
		steer(p, in, route(p, b, goal), false, 0.9);
		return "flying over to a misplaced block";
	}

	/**
	 * Everything left within reach hangs in the air with nothing to click against: pieces of a statue that touch the
	 * rest only at an edge or a corner (the MagicQueen statue: 271 such pieces, 5229 of its 14630 blocks; he froze
	 * with "nothing to click 1912"). A player puts a temporary block (or a few) out from something solid to one of
	 * them, puts it in, and the piece is built on from there. One piece at a time; the temporary blocks come out once
	 * the block they were for is in.
	 */
	private long bridgedAt = -1000, noBridgeUntil = -1;

	/** Temporary blocks not put in yet: out of the plan again (with their skip timers, so the blocks they were for get a go). */
	private void dropUnbuiltTemps() {
		for (var it = temps.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			BlockPos tmp = e.getKey();
			BlockState w = plan.get(tmp);
			if (w == null || w.isAir() || !hole(tmp)) continue;
			plan.remove(tmp);
			var layer = planLayers.get(tmp.getY());
			if (layer != null) layer.remove(tmp);
			planList.remove(tmp);
			var hs = holesByLayer.get(tmp.getY());
			if (hs != null) hs.remove(tmp.asLong());
			bad.remove(e.getValue());
			it.remove();
		}
	}

	private boolean bridgeFloating(LocalPlayer p, long now) {
		return bridgeFloating(p, now, false);
	}

	/** {@code looseOnly}: only spots of a piece that touches nothing at all (see loosePiece). */
	private boolean bridgeFloating(LocalPlayer p, long now, boolean looseOnly) {
		if (now - bridgedAt < 10) return false;
		// Ones already on the way in (not ones waiting to be knocked out: that waited for good, a layer under the window):
		// up to 4 short ones at once. (One at a time, 2 s apart, the floating pagoda's crystals - single blocks touching
		// only at their corners, a temporary block each - went in at 20 a minute.)
		int inFlight = 0;
		for (BlockPos tmp : temps.keySet()) {
			BlockState w = plan.get(tmp);
			if (w != null && !w.isAir() && hole(tmp)) inFlight++;
		}
		if (inFlight >= 4 || (inFlight > 0 && now - bridgedAt < 40)) return false;
		List<BlockPos> cands = new ArrayList<>();
		for (BlockPos h : holes) {
			if (bad.containsKey(h) || !hole(h) || jobAt(h) != Placement.Job.PLACE || workable(h) || waitsOnSupport(h)) continue;
			if (inFlight > 0 && temps.containsValue(h)) continue; // (its temporary block's on the way already)
			if (!plan.get(h).canSurvive(Terrain.level(), h)) continue; // (a lantern needs what it hangs from: not this)
			if (looseOnly && !loosePiece(h, now)) continue;
			cands.add(h);
		}
		Vec3 me = p.position();
		cands.sort(java.util.Comparator.comparingDouble(h -> Vec3.atCenterOf(h).distanceToSqr(me) + 16.0 * (h.getY() - activeY) * (h.getY() - activeY)));
		// Nothing within 3 of any of them (the whole build set down in mid-air: the gothic portal on a cliff top over the
		// sea, 422 spots with nothing to click, all given up after 30 s): the one nearest anything solid, up to 16 free
		// cells away, a line of temporary blocks out to it - like a player bridging out to where the build starts.
		boolean near = false;
		for (int i = 0; i < Math.min(200, cands.size()) && !near; i++) near = bridgeTo(p, cands.get(i)) != null;
		if (!near && inFlight == 0 && !cands.isEmpty() && now - longBridgeFailAt > 100) {
			Object[] lb = longBridge(p, cands.subList(0, Math.min(200, cands.size())), 16);
			if (lb == null) longBridgeFailAt = now; // (a big search: not again every tick)
			if (lb != null) {
				BlockPos t = (BlockPos) lb[0];
				@SuppressWarnings("unchecked") List<BlockPos> chain = (List<BlockPos>) lb[1];
				for (BlockPos n : chain) {
					plan.put(n, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
					planLayers.computeIfAbsent(n.getY(), k -> new ArrayList<>()).add(n);
					planList.add(n);
					temps.put(n, t);
					scanFromY = Math.min(scanFromY, n.getY());
				}
				bad.remove(t);
				bridgedAt = now;
				HumanAutopilot.LOGGER.info("[schem] the build hangs in the air here, nothing to put {} against: {} temporary block(s) out to it from {}",
						t.toShortString(), chain.size(), chain.get(0).toShortString());
				return true;
			}
		}
		// (Up to 4 at once, the nearest: then they go in one after another, not each after 2 s of nothing to do.)
		// (Each to a different piece: four bridges to four blocks of one crystal, side by side, where the first one in
		// gives the rest something to go against - the pagoda's second go was slower than the first.)
		int made = 0;
		java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
		if (inFlight > 0) {
			for (var e : temps.entrySet()) {
				BlockState w = plan.get(e.getKey());
				if (w != null && !w.isAir() && hole(e.getKey())) {
					claimed.addAll(pieceOf(e.getValue(), 500));
				}
			}
		}
		for (int i = 0; i < Math.min(200, cands.size()) && inFlight + made < 4; i++) {
			BlockPos t = cands.get(i);
			if (claimed.contains(t) || (made > 0 && temps.containsValue(t))) continue;
			List<BlockPos> chain = bridgeTo(p, t);
			if (chain == null) continue;
			claimed.addAll(pieceOf(t, 500));
			for (BlockPos n : chain) {
				plan.put(n, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
				planLayers.computeIfAbsent(n.getY(), k -> new ArrayList<>()).add(n);
				planList.add(n);
				temps.put(n, t);
				scanFromY = Math.min(scanFromY, n.getY());
			}
			bad.remove(t);
			made++;
			HumanAutopilot.LOGGER.info("[schem] {} hangs in the air (it touches the build only at an edge or corner): {} temporary block(s) out to it, from {}",
					t.toShortString(), chain.size(), chain.get(0).toShortString());
		}
		if (made > 0) bridgedAt = now;
		return made > 0;
	}

	/** The spots still to put in joined to {@code h} by faces (h too), at most {@code max} of them. */
	private java.util.Set<BlockPos> pieceOf(BlockPos h, int max) {
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		seen.add(h);
		q.add(h);
		while (!q.isEmpty() && seen.size() < max) {
			BlockPos c = q.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (seen.contains(n)) continue;
				BlockState w = plan.get(n);
				if (w == null || w.isAir() || temps.containsKey(n) || jobAt(n) != Placement.Job.PLACE) continue;
				seen.add(n);
				q.add(n);
			}
		}
		return seen;
	}

	/** Spots known to be in a loose piece (true) or not (false), for 10 s (see loosePiece). */
	private final Map<BlockPos, Boolean> looseSeen = new HashMap<>();
	private long looseSeenAt = -1000;

	/**
	 * A piece of the build still to do that touches nothing: not one of its spots (joined by faces, all still to put in)
	 * has anything to click against and can go in now - a crystal floating by the floating pagoda. More than 3000 spots
	 * or not all loaded: part of something bigger, not loose.
	 */
	private boolean loosePiece(BlockPos h, long now) {
		if (now - looseSeenAt > 200) {
			looseSeen.clear();
			looseSeenAt = now;
		}
		Boolean known = looseSeen.get(h);
		if (known != null) return known;
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		seen.add(h);
		q.add(h);
		boolean loose = true;
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			if (!Terrain.loaded(c.getX(), c.getZ()) || seen.size() > 3000 || (workable(c) && !columnWaits(c))) {
				loose = false;
				break;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (seen.contains(n)) continue;
				BlockState w = plan.get(n);
				if (w == null || w.isAir() || temps.containsKey(n) || jobAt(n) != Placement.Job.PLACE) continue;
				seen.add(n);
				q.add(n);
			}
		}
		for (BlockPos c : seen) looseSeen.put(c, loose);
		return loose;
	}

	/**
	 * From all of {@code targets} at once: the shortest run of free cells (at most {@code max}) from something solid
	 * (not usable, not ours, not one of the targets) to a cell next to one of them. [target, chain - placing order, the
	 * one against the solid block first], or null.
	 */
	private long longBridgeFailAt = -1000;

	private Object[] longBridge(LocalPlayer p, List<BlockPos> targets, int max) {
		java.util.Set<BlockPos> tset = new java.util.HashSet<>(targets);
		Map<BlockPos, BlockPos> from = new HashMap<>(), forT = new HashMap<>();
		Map<BlockPos, Integer> depth = new HashMap<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		AABB body = p.getBoundingBox().inflate(0.1);
		for (BlockPos t : targets) {
			for (Direction d : Direction.values()) {
				BlockPos n = t.relative(d);
				if (from.containsKey(n) || tset.contains(n) || !freeForTemp(n, body)) continue;
				from.put(n, n);
				forT.put(n, t);
				depth.put(n, 1);
				q.add(n);
			}
		}
		while (!q.isEmpty() && from.size() < 30000) {
			BlockPos c = q.poll();
			for (Direction e : Direction.values()) {
				BlockPos m = c.relative(e);
				if (tset.contains(m) || from.containsKey(m)) continue;
				if (solid(m) && !Placement.usable(Terrain.state(m)) && !temps.containsKey(m)) {
					List<BlockPos> chain = new ArrayList<>();
					for (BlockPos x = c; ; x = from.get(x)) {
						chain.add(x);
						if (from.get(x).equals(x)) break;
					}
					BlockPos root = chain.get(chain.size() - 1);
					return new Object[]{forT.get(root), chain};
				}
			}
			int dc = depth.get(c);
			if (dc >= max) continue;
			for (Direction e : Direction.values()) {
				BlockPos m = c.relative(e);
				if (from.containsKey(m) || tset.contains(m) || !freeForTemp(m, body)) continue;
				from.put(m, c);
				depth.put(m, dc + 1);
				q.add(m);
			}
		}
		return null;
	}

	/**
	 * The shortest run of free cells (empty in the build and in the world) from something solid to one next to
	 * {@code t}, at most 3: placing order, the one against the solid block first. Null if there's none.
	 */
	private List<BlockPos> bridgeTo(LocalPlayer p, BlockPos t) {
		Map<BlockPos, BlockPos> from = new HashMap<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		AABB body = p.getBoundingBox().inflate(0.1);
		for (Direction d : Direction.values()) {
			BlockPos n = t.relative(d);
			if (freeForTemp(n, body)) {
				from.put(n, n);
				q.add(n);
			}
		}
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			int depth = 1;
			for (BlockPos x = c; !from.get(x).equals(x); x = from.get(x)) depth++;
			// Something solid next to it (not the block it's for, nor another of ours): we can start here.
			for (Direction e : Direction.values()) {
				BlockPos m = c.relative(e);
				if (m.equals(t) || from.containsKey(m)) continue;
				if (solid(m) && !Placement.usable(Terrain.state(m)) && !temps.containsKey(m)) {
					List<BlockPos> chain = new ArrayList<>();
					for (BlockPos x = c; ; x = from.get(x)) {
						chain.add(x);
						if (from.get(x).equals(x)) break;
					}
					return chain; // c (against the solid block) first, the one next to t last
				}
			}
			if (depth >= 3) continue;
			for (Direction e : Direction.values()) {
				BlockPos m = c.relative(e);
				if (from.containsKey(m) || m.equals(t) || !freeForTemp(m, body)) continue;
				from.put(m, c);
				q.add(m);
			}
		}
		return null;
	}

	/** Free for a temporary block: nothing of the build goes there, it's empty now, and it isn't where we are. */
	private static boolean freeForTemp(BlockPos c, AABB body) {
		BlockState planned = plan.get(c);
		if (planned != null && !planned.isAir()) return false;
		BlockState s = Terrain.state(c);
		if (!(s.isAir() || s.canBeReplaced())) return false;
		return !body.intersects(new AABB(c));
	}

	/** Flying up over the build where /tp isn't allowed (see the stuck steps), and till when. */
	private Vec3 climbGoal;
	private long climbUntil;

	/** /tp works for us here (op level 2: a server that made us op, or a world with cheats on). */
	private static boolean canTp(LocalPlayer p) {
		return p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	/** Built in with no way out: the block we're knocking out of the way, since when, and since when we've been shut in. */
	private BlockPos breakOutBlock;
	private long breakOutSince, noWayOutSince = -1, doorRetryAt = -1000;
	/** Moving out by hand from a "shut in" the map has wrong (see breakOut), and till when. */
	private Vec3 escapeGoal;
	private long escapeUntil;
	private Direction lastEscape;

	/** One of the build's own blocks, in and right, taken out again (to get at another, to get out...). */
	private record TakeOut(long at, double flown, String why) {
	}

	/** The build's own blocks taken out and not back yet, for the log (the user: "he would break a wall block or a block he's
	 * already put in, fly away, do it somewhere else, come back and restore it - put that in the log too"). */
	private final Map<BlockPos, TakeOut> takenOut = new HashMap<>();
	private long openWhyAt = -1000;

	/** About to knock {@code c} out: if it's one of the build's own blocks (not a temporary one), log it and why. */
	private void noteTakeOut(BlockPos c, LocalPlayer p, String why) {
		if (plan == null || temps.containsKey(c) || takenOut.containsKey(c)) return;
		BlockState want = plan.get(c), world = Terrain.state(c);
		if (want == null || want.isAir() || world.getBlock() != want.getBlock()) return;
		long now = HumanAutopilot.ticks();
		if (why == null) {
			BlockPos o = opened.get(c), f = outFor.get(c);
			why = o != null ? "to get at " + o.toShortString() + " (" + name(plan.getOrDefault(o, world).getBlock().asItem()) + ", shut in by the build)"
					: f != null ? "out of the way of " + f.toShortString()
					: !Placement.matches(world, want) ? "to put it in again the right way round (has " + world + ")"
					: "the plan wants it out now (" + failWhy.getOrDefault(c, "no reason noted") + ")";
		}
		// Others still out somewhere else: the nearest, so a hop across the build between them shows.
		String others = "";
		if (!takenOut.isEmpty()) {
			BlockPos near = null;
			double best = Double.MAX_VALUE;
			for (BlockPos o : takenOut.keySet()) {
				double d = o.distSqr(c);
				if (d < best) {
					best = d;
					near = o;
				}
			}
			others = String.format(java.util.Locale.ROOT, "; %d other(s) still out, the nearest %s (%.0f away)", takenOut.size(), near.toShortString(), Math.sqrt(best));
		}
		HumanAutopilot.LOGGER.info("[undo] taking {} out at {} (we're at {}): {}{} - {}% in", name(want.getBlock().asItem()), c.toShortString(),
				fmt(p.position()), why, others, Math.round(planProgress() * 100));
		takenOut.put(c.immutable(), new TakeOut(now, flownTotal, why));
		// Old ones never put back (another took their place, or they're left out): said once, then forgotten.
		takenOut.entrySet().removeIf(e -> {
			if (now - e.getValue().at() < 12000) return false;
			HumanAutopilot.LOGGER.info("[undo] {} still out after 10 minutes (taken out {})", e.getKey().toShortString(), e.getValue().why());
			return true;
		});
	}

	private String breakOut(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		if (!p.getAbilities().instabuild) return null;
		// Moving out by hand (see below): there, or long enough - the map's worked out again from where we are.
		if (escapeGoal != null) {
			if (now < escapeUntil && p.position().distanceTo(escapeGoal) > 0.3) {
				work(aim, escapeGoal.add(0, p.getEyeHeight(), 0));
				steer(p, in, escapeGoal, false, 0.3);
				return "moving out by hand";
			}
			escapeGoal = null;
			skyAt = -1000;
			airAt = -1000;
			noWayOutSince = now;
			return null;
		}
		if (breakOutBlock != null && (!solid(breakOutBlock) || now - breakOutSince > 100)) {
			if (!solid(breakOutBlock)) HumanAutopilot.LOGGER.info("[box] knocked {} out of the way: out we go", breakOutBlock.toShortString());
			breakOutBlock = null;
			noWayOutSince = now; // (another one in 3 s if that wasn't enough)
			skyAt = -1000;
			airAt = -1000;
			return null;
		}
		if (breakOutBlock == null && now - doorRetryAt > 200) {
			// A door near us given up on as no way (shut, a failed walk through, pushed into): that again first, before any
			// block of the build comes out (the gate house: 13 blocks knocked out to get out of a room with a door).
			int cleared = 0;
			BlockPos me0 = bodyCell(p);
			// (8 round and 3 up or down: Knight's Order, 04:18, the room's door 6 off and 3 down - three of its stone
			// bricks knocked out instead, the user's "break a wall block ... fly away ... come back and restore it".)
			for (BlockPos c : BlockPos.betweenClosed(me0.offset(-8, -3, -8), me0.offset(8, 3, 8))) {
				if (!handDoor(Terrain.state(c))) continue;
				if (shutDoors.remove(c.asLong()) != null) cleared++;
				if (stuckCells.remove(c.asLong()) != null) cleared++;
				if (stuckCells.remove(c.below().asLong()) != null) cleared++;
			}
			if (cleared > 0) {
				doorRetryAt = now;
				airAt = -1000;
				skyAt = -1000;
				HumanAutopilot.LOGGER.info("[box] shut in at {}: trying the door by us again before knocking anything out", me0.toShortString());
				return null;
			}
		}
		// At most 3 of the build's blocks knocked out a minute to get out (Knight's Order, 05:08: two cobblestones and a
		// stair in 7 s, each a gap the map still called shut): then up over the build by /tp where that's allowed, or
		// wait - not more of the walls.
		breakOutTimes.removeIf(at -> now - at > 1200);
		if (breakOutBlock == null && breakOutTimes.size() >= 3) {
			if (canTp(p) && plan != null && now - stuckTpAt > 400 && b != null) {
				stuckTpAt = now;
				int ty = Math.max(b.maxY() + 3, Mth.floor(p.getY()) + 3);
				HumanAutopilot.LOGGER.info("[box] built in at {}, 3 blocks knocked out this minute already: /tp up over the build to y {} instead",
						bodyCell(p).toShortString(), ty);
				p.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", p.getX(), ty, p.getZ())); lastTpTick = HumanAutopilot.ticks();
				airAt = -1000;
				skyAt = -1000;
			}
			return null;
		}
		if (breakOutBlock == null) {
			breakOutBlock = pickBreakOut(p, b);
			if (breakOutBlock != null) breakOutTimes.addLast(now);
			if (breakOutBlock == null) {
				// Why not (the cottage's attic: boxed in by trapdoor panels and slabs, 48 s, and no "built in" at all).
				if (now - breakOutWhyAt > 200) {
					breakOutWhyAt = now;
					BlockPos me = bodyCell(p);
					Vec3 eye = p.getEyePosition();
					StringBuilder why = new StringBuilder();
					for (Direction d : Direction.values()) {
						if (d == Direction.DOWN) continue;
						for (int up = 0; up <= 1; up++) {
							if (d == Direction.UP && up == 1) continue;
							BlockPos n = d == Direction.UP ? me.above(2) : me.above(up).relative(d);
							BlockState st = Terrain.state(n);
							why.append(d).append(up).append(' ').append(st.getBlock().getName().getString()).append(':')
									.append(!solid(n) ? "not solid" : !Placement.breakable(Terrain.level(), n, st) ? "unbreakable"
											: breakFace(p, eye, Vec3.atCenterOf(n).subtract(eye).normalize(), n) == null ? "no face" : "ok").append("; ");
						}
					}
					HumanAutopilot.LOGGER.info("[box] built in at {} ({}), nothing to knock out: {}", me.toShortString(), fmt(p.position()), why);
				}
				// Nothing solid in the way, though: the map has it wrong (a carpeted floor once counted as solid - 3+ minutes
				// "shut in" in the middle of a room). A side the body really fits through: out that way by hand.
				AABB bb = p.getBoundingBox();
				for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
					if (lastEscape != null && d == lastEscape.getOpposite()) continue; // (not straight back)
					Vec3 step = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
					if (p.level().noCollision(p, bb.move(step.scale(0.5))) && p.level().noCollision(p, bb.move(step))) {
						escapeGoal = p.position().add(step);
						escapeUntil = now + 40;
						lastEscape = d;
						HumanAutopilot.LOGGER.info("[box] the map has us shut in at {}, but the way {} is open: out by hand", fmt(p.position()), d);
						return "moving out by hand";
					}
				}
				return null;
			}
			breakOutSince = now;
			HumanAutopilot.LOGGER.info("[box] built in at {}: knocking {} ({}) out of the way", bodyCell(p).toShortString(),
					breakOutBlock.toShortString(), Terrain.state(breakOutBlock));
		}
		BlockPos l = breakOutBlock;
		Face f = breakFace(p, p.getEyePosition(), p.getViewVector(1f), l);
		if (f == null) {
			breakOutBlock = null;
			return null;
		}
		brake(p, in);
		work(aim, f.point);
		HitResult hr = clickRay(p);
		if (now >= nextClickAt && hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(l)) {
			noteTakeOut(l, p, "to get out: built in at " + bodyCell(p).toShortString());
			in.click(InputDriver.opts().keyAttack);
			lastClickTick = now;
			nextClickAt = now + 6;
		}
		return "knocking a block out of the way to get out";
	}

	/** Which block to knock out: one beside us (feet or head height) or over our head, that we can see; open air past it first. */
	private long breakOutWhyAt = -1000;

	private BlockPos pickBreakOut(LocalPlayer p, Box b) {
		BlockPos me = bodyCell(p);
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestScore = -1;
		// Something thin in our own cells (an open trapdoor's panel beside us: we're in its cell, and the map has no open
		// cell round us at all - the cottage's attic): that one first, it frees where we are.
		for (BlockPos n : new BlockPos[]{me, me.above()}) {
			BlockState s = Terrain.state(n);
			if (solid(n) && Placement.breakable(Terrain.level(), n, s) && breakFace(p, eye, Vec3.atCenterOf(n).subtract(eye).normalize(), n) != null) {
				return n.immutable();
			}
		}
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			for (int up = 0; up <= 1; up++) {
				if (d == Direction.UP && up == 1) continue;
				BlockPos n = d == Direction.UP ? me.above(2) : me.above(up).relative(d);
				BlockState s = Terrain.state(n);
				if (!solid(n) || !Placement.breakable(Terrain.level(), n, s)) continue;
				if (breakFace(p, eye, Vec3.atCenterOf(n).subtract(eye).normalize(), n) == null) continue;
				BlockPos past = n.relative(d);
				double score = 1;
				if (d != Direction.UP && flyable(past.getX(), past.getY() - up, past.getZ())) score += 4; // room for us past it
				if (way(past.getX(), past.getY(), past.getZ())) score += 2;
				if (skyOpen(b, past.getX(), past.getY(), past.getZ())) score += 3;
				if (score > bestScore) {
					bestScore = score;
					best = n;
				}
			}
		}
		return best;
	}

	/** The topmost block of a stack sitting on a hole, with open air above it; nearest first. */
	private BlockPos findLid(LocalPlayer p, Box b) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos h : holes) {
			if (!hole(h)) continue;
			BlockPos top = null;
			for (BlockPos c = h.above(); b.inside(c.getX(), c.getY(), c.getZ()); c = c.above()) {
				if (solid(c)) top = c;
				else if (top != null) break;
			}
			// A misclick is a block or two sitting on a hole; a hole buried deep inside the box is left alone (no
			// player digs a tunnel down to it).
			if (top == null || top.getY() - h.getY() > 3) continue;
			double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(top));
			if (d < bestD) {
				bestD = d;
				best = top;
			}
		}
		return best;
	}

	/** A face of {@code block} with open air in front of it, in reach and in sight: least camera turn. */
	private Face breakFace(LocalPlayer p, Vec3 eye, Vec3 look, BlockPos block) {
		double reach = p.blockInteractionRange() - 0.25;
		Face best = null;
		double bestTurn = Double.MAX_VALUE;
		for (Direction side : ORDER) {
			BlockPos front = block.relative(side);
			if (!Terrain.passable(front.getX(), front.getY(), front.getZ())) continue;
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 pt = Vec3.atCenterOf(block).add(normal.scale(0.5));
			if (eye.subtract(pt).dot(normal) < 0.1 || eye.distanceTo(pt) > reach) continue;
			if (!sees(p, eye, pt, block, side, normal, null)) continue;
			double turn = Math.acos(Math.clamp(look.dot(pt.subtract(eye).normalize()), -1, 1));
			if (turn < bestTurn) {
				bestTurn = turn;
				best = new Face(block, side, pt, block);
			}
		}
		return best;
	}

	/**
	 * Something next to it to click against, and nothing open underneath: building on top of a hole (one we had to
	 * skip) would seal it in for good, so its column waits until it's filled.
	 */
	private boolean workable(BlockPos h) {
		if (plan != null) {
			Placement.Job j = jobAt(h);
			if (j == Placement.Job.BREAK || j == Placement.Job.ADJUST) return true; // the block itself is there to click on
			if (pad(plan.get(h))) return padBase(h.below()); // (the water it sits on: see pad)
			// Something to click against (a chest or a crafting table too: with sneak held), or the half slab itself.
			if (Placement.slabToDouble(Terrain.state(h), plan.get(h))) return true;
			for (Direction d : Direction.values()) {
				if (solid(h.relative(d))) return true;
			}
			return false;
		}
		Box b = box();
		BlockPos under = h.below();
		if (b != null && b.inside(under.getX(), under.getY(), under.getZ()) && hole(under)) return false;
		for (Direction d : Direction.values()) if (solid(h.relative(d))) return true;
		return false;
	}

	/** {@code loose}: the point puts it in the right way round, but the heading's on the line (see planPoint). */
	private record Face(BlockPos against, Direction side, Vec3 point, BlockPos cell, boolean loose) {
		Face(BlockPos against, Direction side, Vec3 point, BlockPos cell) {
			this(against, side, point, cell, false);
		}
	}

	/**
	 * All the box work is one job for the eyes: the point moves from hole to hole but it's the same "target", so
	 * the camera's attention dwell (meant for noticing new things) doesn't hold every block up by 0.25-0.4 s.
	 * Look, fill, move on.
	 */
	private Vec3 workPoint;
	private final java.util.function.Supplier<Vec3> workSupplier = () -> workPoint;

	private void work(AimController aim, Vec3 point) {
		workPoint = point;
		aim.setQuick(true);
		LocalPlayer pl = Minecraft.getInstance().player;
		long now = HumanAutopilot.ticks();
		if (pl != null && now != eyeHistTick) {
			eyeHistTick = now;
			eyeHist.addLast(pl.getEyePosition());
			while (eyeHist.size() > 20) eyeHist.pollFirst();
		}
		// Jiggling in place (wedged, pushing back and forth against something): look from where we are on average, not
		// from each jiggle - the view swung 5-10 degrees back and forth at the hole for seconds (the user's "staring at
		// a hole with the camera wiggling left and right").
		if (pl != null && eyeHist.size() >= 20) {
			double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
			double sx = 0, sy = 0, sz = 0, travel = 0;
			Vec3 prev = null;
			for (Vec3 e : eyeHist) {
				x0 = Math.min(x0, e.x); x1 = Math.max(x1, e.x);
				y0 = Math.min(y0, e.y); y1 = Math.max(y1, e.y);
				z0 = Math.min(z0, e.z); z1 = Math.max(z1, e.z);
				sx += e.x; sy += e.y; sz += e.z;
				if (prev != null) travel += prev.distanceTo(e);
				prev = e;
			}
			double range = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
			if (range < 0.8 && travel > 1.2) {
				Vec3 mean = new Vec3(sx / eyeHist.size(), sy / eyeHist.size(), sz / eyeHist.size());
				aim.lookDirection("box-work", Angles.yawTo(mean, point), Angles.pitchTo(mean, point));
				return;
			}
		}
		aim.lookAt("box-work", workSupplier);
	}

	private final java.util.ArrayDeque<Vec3> eyeHist = new java.util.ArrayDeque<>();
	private long eyeHistTick = -1;

	/** Last right-click we made (to spot a menu it opened by accident). */
	public static long lastClickTick = -100;

	/** An entity (companion, mob, player) is closer along the crosshair than the block we'd click. */
	/** Ticks the current hole has had someone on it or in the way of the click (see where it's counted). */
	private int blockedTicks;
	/**
	 * The hole picked is one right by someone because nothing else was left (the last holes the companion's at): gone to
	 * anyway - she keeps her distance and moves off - not passed over, or she stopped at the top while Kohaku built on.
	 */
	private boolean crowdFallback;

	private static boolean entityInTheWay(LocalPlayer p) {
		HitResult frame = Minecraft.getInstance().hitResult;
		if (frame != null && frame.getType() == HitResult.Type.ENTITY) return true;
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getViewVector(1f).scale(p.blockInteractionRange()));
		AABB area = p.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
		var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, area,
				en -> !en.isSpectator() && en.isPickable(), p.blockInteractionRange() * p.blockInteractionRange());
		if (hit == null) return false;
		HitResult block = clickRay(p);
		return block.getType() != HitResult.Type.BLOCK || eye.distanceToSqr(hit.getLocation()) < eye.distanceToSqr(block.getLocation());
	}

	/** What a click this tick would hit: the game picks from the player's tick position, like this. */
	private static HitResult clickRay(LocalPlayer p) {
		return p.pick(p.blockInteractionRange(), 1.0f, false);
	}

	/** The crosshair's spot on the face is at least 0.12 from every edge. */
	private static boolean awayFromEdge(Vec3 hit, BlockPos block, Direction side) {
		double x = hit.x - block.getX(), y = hit.y - block.getY(), z = hit.z - block.getZ();
		double a, b;
		switch (side.getAxis()) {
			case X -> { a = y; b = z; }
			case Y -> { a = x; b = z; }
			default -> { a = x; b = y; }
		}
		return a > 0.12 && a < 0.88 && b > 0.12 && b < 0.88;
	}

	/** Still in reach and in sight from {@code eye}. */
	private boolean visible(LocalPlayer p, Vec3 eye, Face f) {
		if (eye.distanceTo(f.point) > p.blockInteractionRange() - 0.4) return false;
		if (plan != null && pad(plan.get(f.cell))) return padRay(p, eye, f.point, f.against);
		return sees(p, eye, f.point, f.against, f.side, Vec3.atLowerCornerOf(f.side.getUnitVec3i()), null);
	}

	/**
	 * A lily pad: it sits on a water source (or ice), and goes down the way the game does it - looking at the water and
	 * using the pad (its own look along the view for the first water source), not a click on a block's face. (The Himeji
	 * castle's moat: 527 pads, "no spot" for every one, nothing beside them to click and the water under them no face.)
	 */
	private static boolean pad(BlockState want) {
		return want != null && want.is(net.minecraft.world.level.block.Blocks.LILY_PAD);
	}

	/** A lily pad can go on this (WaterlilyBlock's own rule): a water source or ice, nothing wet over it. */
	private static boolean padBase(BlockPos c) {
		BlockState s = Terrain.state(c);
		return (s.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER || s.getBlock() instanceof net.minecraft.world.level.block.IceBlock)
				&& Terrain.state(c.above()).getFluidState().isEmpty();
	}

	/** The pad's own look from {@code eye} towards {@code pt} (blocks' outlines, water sources) meets {@code water} first. */
	private static boolean padRay(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos water) {
		Vec3 d = pt.subtract(eye);
		double len = d.length();
		if (len < 1e-3) return false;
		var hit = rays(p).clip(new ClipContext(eye, pt.add(d.scale(0.3 / len)), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(water);
	}

	private static final double[][] PAD_SPOTS = {{0, 0}, {0.2, 0}, {-0.2, 0}, {0, 0.2}, {0, -0.2}};

	/** Where to look to put the lily pad at {@code t}: the water's top under it, in sight and in reach from {@code eye}. */
	private Face padFace(LocalPlayer p, Vec3 eye, BlockPos t, double reach) {
		BlockPos w = t.below();
		if (!padBase(w)) return null;
		// (Just under the water's top - a source is 8/9 of a block - or an ice block's top.)
		double top = Terrain.state(w).getBlock() instanceof net.minecraft.world.level.block.IceBlock ? 0.98 : 0.85;
		Vec3 pt = new Vec3(w.getX() + 0.5, w.getY() + top, w.getZ() + 0.5);
		if (eye.y < pt.y + 0.3 || eye.distanceTo(pt) > reach) return null;
		for (double[] o : PAD_SPOTS) if (!padRay(p, eye, pt.add(o[0], 0, o[1]), w)) return null;
		return new Face(w, Direction.UP, pt, t);
	}

	/**
	 * Using the pad now puts it into {@code t}: its look meets the water under {@code t} first, and the crosshair isn't on
	 * something the click would open instead.
	 */
	private static boolean padAim(LocalPlayer p, BlockPos t, HitResult hr) {
		BlockState ts = Terrain.state(t);
		if (!ts.isAir() && !ts.canBeReplaced() || !padBase(t.below())) return false;
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && Placement.usable(Terrain.state(bh.getBlockPos()))) return false;
		Vec3 eye = p.getEyePosition();
		var hit = rays(p).clip(new ClipContext(eye, eye.add(p.getViewVector(1f).scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE,
				ClipContext.Fluid.SOURCE_ONLY, p));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(t.below());
	}

	/**
	 * A face we could click from {@code eye} that puts a block into {@code t}: of those in sight, the one the
	 * crosshair has to move least to reach (the recorded player used tops and sides about equally).
	 */
	private Face faceFrom(LocalPlayer p, Vec3 eye, Vec3 look, BlockPos t) {
		double reach = p.blockInteractionRange() - 0.25 - standSlack;
		Face best = null;
		double bestTurn = Double.MAX_VALUE;
		BlockState want = plan != null ? plan.get(t) : null;
		yawSlack = want == null || yawLoose.contains(t) ? 0 : standSlack > 0 ? 0.5 : 0.2;
		sightLoose = false;
		// Knocking it out, or a finishing touch: click the block itself.
		if (want != null && jobAt(t) != Placement.Job.PLACE) return interactFace(p, eye, look, t);
		if (pad(want)) return padFace(p, eye, t, reach);
		if (want != null && Placement.slabToDouble(Terrain.state(t), want)) {
			// Finishing a double slab: click the half that's there, on its open side.
			boolean bottom = Terrain.state(t).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
					== net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
			Direction side = bottom ? Direction.UP : Direction.DOWN;
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 pt = Vec3.atCenterOf(t);
			if (eye.subtract(pt).dot(normal) > 0.1 && eye.distanceTo(pt) <= reach && sees(p, eye, pt, t, side, normal, debug)
					&& rightWay(p, eye, pt, t, side, t, want)) {
				return new Face(t, side, pt, t);
			}
		}
		// A tuft of grass, a snow layer, a flower in the spot itself: click it, like a player - the block takes its place.
		// (The faces behind it are hidden by it: "nowhere", then knocked out first, 211 times on the TNT house's grassy
		// hill. Tried before the neighbours: it's the face in front.)
		BlockState inT = want != null ? Terrain.state(t) : null;
		if (inT != null && !inT.isAir() && inT.canBeReplaced() && inT.getFluidState().isEmpty() && !Placement.fluid(want)
				&& !inT.getShape(Terrain.level(), t).isEmpty()) {
			AABB tb = inT.getShape(Terrain.level(), t).bounds().move(t);
			for (Direction side : ORDER) {
				Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
				Vec3 pt = onFace(tb, side);
				if (eye.subtract(pt).dot(normal) < 0.1 || eye.distanceTo(pt) > reach) continue;
				if (!sees(p, eye, pt, t, side, normal, debug) || !rightWay(p, eye, pt, t, side, t, want)) continue;
				double facing = eye.subtract(pt).normalize().dot(normal);
				if (facing < MIN_FACING || (!steepFine(t) && Placement.pitchTo(eye, pt) > MAX_PITCH)) continue;
				double turn = Math.acos(Math.clamp(look.dot(pt.subtract(eye).normalize()), -1, 1)) + (1 - facing) * 0.8;
				if (turn < bestTurn) {
					bestTurn = turn;
					best = new Face(t, side, pt, t);
				}
			}
		}
		for (Direction d : ORDER) {
			BlockPos n = t.relative(d);
			if (!solid(n)) continue;
			// Against a chest, a crafting table, a trapdoor...: a plain click opens it, so only with sneak held (a hopper
			// pointing into a chest is put in just like that). The last choice, though.
			boolean sneak = want != null && Placement.usable(Terrain.state(n));
			Direction side = d.getOpposite(); // the face of n that touches t
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			// On the block's real face (farmland and paths are a bit lower than a full block, a top slab's side is its
			// upper half): aiming at a full block's face went just over the farmland and clicked nothing.
			Vec3 center = want != null ? faceCenter(n, side) : Vec3.atCenterOf(n).add(normal.scale(0.5));
			if (eye.subtract(center).dot(normal) < 0.1) { // we're behind that face
				if (debug != null) debug.append(d).append(": behind (normal ").append(normal).append("); ");
				continue;
			}
			Vec3 pt = center.add(inPlane(side, faceOffA, faceOffB));
			double patch = 0.25;
			boolean ptLoose = false;
			if (want != null) {
				// Schematic: a spot on the face that puts it in the right way round (stairs facing, top/bottom half,
				// log direction), and a smaller patch around it so a slightly-off click still does.
				pt = planPoint(p, eye, look, n, side, normal, center, t, want);
				if (pt == null) {
					if (debug != null) debug.append(d).append(": wrong way round from here; ");
					continue;
				}
				ptLoose = planLoose;
				if (ptLoose && debug != null) debug.append(d).append(": heading on the line from here; ");
				patch = 0.12;
				// A thin block (a chain, a fence post, bars): the face is only as wide as the block, so is the margin.
				var shape = Terrain.state(n).getShape(Terrain.level(), n);
				if (!shape.isEmpty()) {
					var bb = shape.bounds();
					double w = switch (side.getAxis()) {
						case X -> Math.min(bb.getYsize(), bb.getZsize());
						case Y -> Math.min(bb.getXsize(), bb.getZsize());
						case Z -> Math.min(bb.getXsize(), bb.getYsize());
					};
					patch = Math.min(patch, w * 0.3);
				}
			}
			if (eye.distanceTo(pt) > reach) {
				if (debug != null) debug.append(d).append(": too far ").append(String.format("%.2f", eye.distanceTo(pt))).append("; ");
				continue;
			}
			// Not just a sliver at a grazing angle: a patch around the point must be in sight too, so a hand
			// that's a few pixels off still clicks the right face.
			boolean clear = sees(p, eye, pt, n, side, normal, debug);
			for (int i = 0; clear && i < 4; i++) {
				double a = (i & 1) == 0 ? patch : -patch;
				Vec3 q = pt.add(i < 2 ? inPlane(side, a, 0) : inPlane(side, 0, a));
				clear = sees(p, eye, q, n, side, normal, debug);
			}
			if (!clear) continue;
			double turn = Math.acos(Math.clamp(look.dot(pt.subtract(eye).normalize()), -1, 1));
			if (want != null) {
				double facing = eye.subtract(pt).normalize().dot(normal);
				if (facing < MIN_FACING) {
					if (debug != null) debug.append(d).append(": only its edge from here; ");
					continue; // a thin sliver seen side-on: a player clicks a face they can see properly
				}
				if (!steepFine(t) && Placement.pitchTo(eye, pt) > MAX_PITCH) {
					if (debug != null) debug.append(d).append(": straight down from here; ");
					continue;
				}
				turn += (1 - facing) * 0.8; // the face looking at us
				if (sneak) turn += 0.6;
				if (ptLoose) turn += 1.5; // (one with room for the heading, if there's one)
			}
			if (turn < bestTurn) {
				bestTurn = turn;
				best = new Face(n, side, pt, t, ptLoose);
			}
		}
		sightLoose = best != null && best.loose();
		return best;
	}

	/** How far off the middle of a spot to lean (standFor's last try): a quarter block straight, a fifth across. */
	private static final double[][] LEANS = {{0.25, 0}, {-0.25, 0}, {0, 0.25}, {0, -0.25}, {0.2, 0.2}, {-0.2, 0.2}, {0.2, -0.2}, {-0.2, -0.2}};
	/** How many open spots round the target the last standFor had (for the case files). */
	private int lastOpenSpots;
	/** The last standFor's lean off the spot's middle, and the one for the stand we're heading to. */
	private Vec3 lastStandOff = Vec3.ZERO, standOff = Vec3.ZERO;

	private static final double[][] SPOTS = {{0, 0}, {0, 0.25}, {0, -0.25}, {0.25, 0}, {-0.25, 0},
			{0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}};

	/** Where on this face to click (looking from {@code eye}) so the block goes in the right way round, or null. */
	private Vec3 planPoint(LocalPlayer p, Vec3 eye, Vec3 look, BlockPos n, Direction side, Vec3 normal, Vec3 center, BlockPos t, BlockState want) {
		// Every spot when it matters where we click, or when the face isn't a full square (the side of a top slab
		// is only its upper half: its middle is the slab's edge, and the ray there hits its top instead).
		boolean full = pretendSolid.contains(n) || Terrain.state(n).isCollisionShapeFullBlock(Terrain.level(), n); // (a temporary block to be: glass)
		int tries = Placement.spotMatters(want) || !full ? SPOTS.length : 1;
		List<Vec3> spots = new ArrayList<>();
		if (tries == 1 && side.getAxis() == Direction.Axis.Y && Math.abs(Placement.pitchTo(eye, center)) > 50) {
			// A top (or bottom) face seen from nearly over (under) it: where the crosshair meets it first, not its
			// middle. Right over the middle, the heading to it swings right round with every few cm we drift, and the
			// camera spun 900 deg/s from one block of a floor to the next (and 80 deg in one tick). A player just tips
			// the view down onto the nearest bit of it.
			double dy = center.y - eye.y, k = Math.abs(look.y) > 0.05 ? dy / look.y : -1;
			Vec3 at = k > 0 ? eye.add(look.scale(k)) : center.add(new Vec3(look.x, 0, look.z).normalize().scale(0.3));
			Vec3 near = center.add(Math.clamp(at.x - center.x, -0.3, 0.3), 0, Math.clamp(at.z - center.z, -0.3, 0.3));
			// (Not steeper than faceFrom lets us look, where that applies: then it's the middle, as before; the near
			// point right under us was turned down and nothing else tried, "can't see it from the spot" 52 times.)
			// (And in sight, with a patch round it: else its middle, as before. Unchecked, a hidden near point turned the
			// whole face down: 49 "can't see it from the spot" on the luxury villa's floors.)
			if ((steepFine(t) || Placement.pitchTo(eye, near) <= MAX_PITCH) && sees(p, eye, near, n, side, normal, null)
					&& patchSeen(p, eye, near, n, side, normal)) spots.add(near);
		}
		for (int i = 0; i < tries; i++) spots.add(center.add(inPlane(side, SPOTS[i][0], SPOTS[i][1])));
		if (!full) spots.addAll(faceParts(n, side));
		// The heading: from a little to either side of here too. Stairs face the way we look, and a heading right on the
		// line between two ways flips with every few cm we drift (the user's castle: 383 of its 1293 stairs "off the
		// face for 10 ticks" with the crosshair right on the spot, 271 of them with the heading within 3 deg of the
		// line). A spot that's right but on the line is kept in case there's nothing better (planLoose), and after 3 of
		// those the rest won't be better. (Not a sign or a banner: 16 ways round, those go any way round in the end.)
		boolean heading = yawSlack > 0 && headingMatters(want);
		Vec3 loose = null;
		int onLine = 0;
		planLoose = false;
		for (Vec3 pt : spots) {
			if (!full && !sees(p, eye, pt, n, side, normal, debug)) continue;
			// (And a little round it, as faceFrom wants: a spot on a fence's thin arm passed here, failed there, and
			// the post top after it never got a look.)
			if (!full && !patchSeen(p, eye, pt, n, side, normal)) continue;
			if (!rightWay(p, eye, pt, n, side, t, want)) {
				if (debug != null) {
					var hit = new BlockHitResult(pt, side, n, false);
					debug.append("[").append(fmt(pt)).append(" would put ")
							.append(Placement.predictFrom(p, hit, Placement.placeItem(want), t, eye))
							.append(" against ").append(Terrain.state(n)).append("] ");
				}
				continue;
			}
			boolean room = !heading || yawSteady(p, eye, pt, n, side, t, want);
			if (!room && loose != null) {
				if (++onLine >= 3) break;
				continue;
			}
			// A little off the spot must come out the same.
			boolean steady = true;
			for (int k = 0; steady && k < 4; k++) {
				double a = (k & 1) == 0 ? 0.1 : -0.1;
				steady = rightWay(p, eye, pt.add(k < 2 ? inPlane(side, a, 0) : inPlane(side, 0, a)), n, side, t, want);
			}
			if (!steady) continue;
			if (room) return pt;
			loose = pt;
			onLine++;
		}
		if (loose != null) planLoose = true;
		return loose;
	}

	/** The last planPoint's point puts it in right, but the heading's on the line (no room either side). */
	private boolean planLoose;
	/** The last faceFrom's face is a loose one (see Face). */
	private boolean sightLoose;

	/**
	 * Sideways room for the heading (see planPoint), in blocks either side of the eye: more when choosing a spot to
	 * hover at (we end up half a block off it), less from where we are; none for a block that's had no spot that way.
	 */
	private double yawSlack;
	private final java.util.Set<BlockPos> yawLoose = new java.util.HashSet<>();

	/** Spots whose block stands in water (see setPlan): the plan has water there until the water's in, then the block. */
	private final Map<BlockPos, BlockState> waterFirst = new HashMap<>();

	/**
	 * Only goes into water, or dies out of it: kelp, seagrass (the water is part of the block), a coral or a sea pickle
	 * that's waterlogged. (A waterlogged stair or slab goes in dry and gets its water after, as before: water put in first
	 * up on a roof would run down the build.)
	 */
	private static boolean needsWaterIn(BlockState want) {
		if (want == null || want.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
		var b = want.getBlock();
		if (want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
			return want.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
					&& (b instanceof net.minecraft.world.level.block.BaseCoralPlantTypeBlock || b instanceof net.minecraft.world.level.block.SeaPickleBlock);
		}
		return want.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER) && want.getFluidState().isSource();
	}

	/** Has a way round that can depend on where we look from (not a 16-way sign or banner: those have their own way). */
	private static boolean headingMatters(BlockState want) {
		return want != null && Placement.oriented(want) && !Placement.fineRotation(want);
	}

	/** {@link #rightWay} from {@link #yawSlack} to either side of {@code eye} too (across the line to the point). */
	private boolean yawSteady(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos n, Direction side, BlockPos t, BlockState want) {
		double hx = eye.x - pt.x, hz = eye.z - pt.z, hd = Math.hypot(hx, hz);
		// (Right over it: any heading at all, so a block that faces the way we look is never steady from there.)
		Vec3 across = hd < 1e-3 ? new Vec3(yawSlack, 0, 0) : new Vec3(-hz / hd * yawSlack, 0, hx / hd * yawSlack);
		if (!rightWay(p, eye.add(across), pt, n, side, t, want) || !rightWay(p, eye.subtract(across), pt, n, side, t, want)) return false;
		// (Facing any of six ways - a barrel, an observer: up or down goes by how steeply we look, so a little higher and
		// lower too. The cottage's barrels facing down, looked at from 41-45 deg below.)
		if (want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
			Vec3 up = new Vec3(0, yawSlack, 0);
			if (!rightWay(p, eye.add(up), pt, n, side, t, want) || !rightWay(p, eye.subtract(up), pt, n, side, t, want)) return false;
		}
		return yawLead.lengthSqr() < 0.0025 || rightWay(p, eye.add(yawLead), pt, n, side, t, want);
	}

	/**
	 * For a block whose way round depends on where we look from: clicking that face from {@code eye} puts it in right,
	 * with room for the heading (unless the target's allowed on the line), counting where we're drifting to.
	 */
	private boolean headingRightFrom(LocalPlayer p, Vec3 eye, Face f, BlockPos t) {
		BlockState want = plan != null ? plan.get(t) : null;
		if (!headingMatters(want) || jobAt(t) != Placement.Job.PLACE) return true;
		if (!rightWay(p, eye, f.point(), f.against(), f.side(), t, want)) return false;
		if (yawLoose.contains(t)) return true;
		Vec3 v0 = p.getDeltaMovement();
		double was = yawSlack;
		yawSlack = 0.2;
		yawLead = new Vec3(v0.x, 0, v0.z).scale(5);
		try {
			return yawSteady(p, eye, f.point(), f.against(), f.side(), t, want);
		} finally {
			yawSlack = was;
			yawLead = Vec3.ZERO;
		}
	}

	/** Where we're drifting to in the next few ticks (see the placing in tickInner2), for yawSteady; zero otherwise. */
	private Vec3 yawLead = Vec3.ZERO;
	/** Targets already moved for because the heading was wrong where we hung (once each: then as before). */
	private final java.util.Set<BlockPos> headingMoved = new java.util.HashSet<>();

	/** Clicking {@code pt} on that face of {@code n}, looking from {@code eye}, puts {@code want} into {@code t}. */
	private boolean rightWay(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos n, Direction side, BlockPos t, BlockState want) {
		if (Placement.fluid(want)) return !Placement.takesWater(p, n, Terrain.state(n), want); // a bucket: any spot on the face
		BlockState got;
		if (!pretendSolid.isEmpty() && pretendSolid.contains(n)) {
			// A temporary block that isn't there yet (tryScaffold asking if it would do): the click as if it were. The real
			// world's air there took the click itself, "would put null against air", and since 03:00 no temporary block
			// passed (the 25k: 13 put in, 268 "no spot to click it from"; the awkward cottage: 0 of 42).
			Vec3 mid = Vec3.atCenterOf(n).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
			got = Placement.predictAgainst(p, n, side, t, Placement.placeItem(want), eye, pt.subtract(mid));
		} else {
			var hit = new BlockHitResult(pt, side, n, false);
			got = Placement.predictFrom(p, hit, Placement.placeItem(want), t, eye); // (our body there, not here)
		}
		return Placement.sameWay(got, Placement.placeShape(want), relaxed.contains(t));
	}

	/** The middle of a block's face as the crosshair sees it (its outline), not a full cube's. */
	private static Vec3 faceCenter(BlockPos n, Direction side) {
		var shape = Terrain.state(n).getShape(Terrain.level(), n);
		if (shape.isEmpty()) return Vec3.atCenterOf(n).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
		return onFace(shape.bounds().move(n), side);
	}

	/** The click patch round {@code pt} is in sight: as wide as faceFrom's check (smaller on a thin block). */
	private boolean patchSeen(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos n, Direction side, Vec3 normal) {
		double patch = 0.12;
		var shape = Terrain.state(n).getShape(Terrain.level(), n);
		if (!shape.isEmpty()) {
			var bb = shape.bounds();
			double w = switch (side.getAxis()) {
				case X -> Math.min(bb.getYsize(), bb.getZsize());
				case Y -> Math.min(bb.getXsize(), bb.getZsize());
				case Z -> Math.min(bb.getXsize(), bb.getYsize());
			};
			patch = Math.min(patch, w * 0.3);
		}
		for (int i = 0; i < 4; i++) {
			double a = (i & 1) == 0 ? patch : -patch;
			if (!sees(p, eye, pt.add(i < 2 ? inPlane(side, a, 0) : inPlane(side, 0, a)), n, side, normal, null)) return false;
		}
		return true;
	}

	/** The middle of that face of a box. */
	private static Vec3 onFace(AABB a, Direction side) {
		Vec3 c = a.getCenter();
		return switch (side) {
			case UP -> new Vec3(c.x, a.maxY, c.z);
			case DOWN -> new Vec3(c.x, a.minY, c.z);
			case EAST -> new Vec3(a.maxX, c.y, c.z);
			case WEST -> new Vec3(a.minX, c.y, c.z);
			case SOUTH -> new Vec3(c.x, c.y, a.maxZ);
			case NORTH -> new Vec3(c.x, c.y, a.minZ);
		};
	}

	/**
	 * The middles of the pieces of a block's shape that are out on that face: a fence's post top. The middle of the box
	 * round the whole shape is in the air between a post and its arms (a lightning rod on a fence post in the Walmart's
	 * shelves: every click aimed there hit nothing). Tried after the usual spots (a stair's bottom half as the main
	 * spot put top slabs in as bottom ones: the house roof).
	 */
	private static List<Vec3> faceParts(BlockPos n, Direction side) {
		var shape = Terrain.state(n).getShape(Terrain.level(), n);
		List<Vec3> out = new ArrayList<>();
		if (shape.isEmpty()) return out;
		AABB bb = shape.bounds().move(n);
		for (AABB a : shape.toAabbs()) {
			a = a.move(n);
			boolean onIt = switch (side) {
				case UP -> a.maxY >= bb.maxY - 1e-6;
				case DOWN -> a.minY <= bb.minY + 1e-6;
				case EAST -> a.maxX >= bb.maxX - 1e-6;
				case WEST -> a.minX <= bb.minX + 1e-6;
				case SOUTH -> a.maxZ >= bb.maxZ - 1e-6;
				case NORTH -> a.minZ <= bb.minZ + 1e-6;
			};
			if (onIt) out.add(onFace(a, side));
		}
		return out;
	}

	/** A face of the block in {@code t} itself to click (knocking it out, or a finishing touch): in sight and reach. */
	private Face interactFace(LocalPlayer p, Vec3 eye, Vec3 look, BlockPos t) {
		BlockState s = Terrain.state(t);
		var shape = s.getShape(Terrain.level(), t);
		if (shape.isEmpty()) return null;
		AABB bb = shape.bounds().move(t);
		Vec3 c = bb.getCenter();
		double reach = p.blockInteractionRange() - 0.25 - standSlack;
		Face best = null;
		double bestTurn = Double.MAX_VALUE;
		// (A hoe or a shovel does nothing on a block's underside.)
		boolean tool = plan != null && plan.get(t) != null && Placement.tool(Placement.adjust(s, plan.get(t)));
		for (Direction side : ORDER) {
			if (tool && side == Direction.DOWN) continue;
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 pt = switch (side) {
				case UP -> new Vec3(c.x, bb.maxY, c.z);
				case DOWN -> new Vec3(c.x, bb.minY, c.z);
				case EAST -> new Vec3(bb.maxX, c.y, c.z);
				case WEST -> new Vec3(bb.minX, c.y, c.z);
				case SOUTH -> new Vec3(c.x, c.y, bb.maxZ);
				case NORTH -> new Vec3(c.x, c.y, bb.minZ);
			};
			if (eye.subtract(pt).dot(normal) < 0.05 || eye.distanceTo(pt) > reach) continue;
			if (!sees(p, eye, pt, t, side, normal, debug)) continue;
			double facing = eye.subtract(pt).normalize().dot(normal);
			if (facing < 0.2) continue;
			if (!steepFine(t) && Placement.pitchTo(eye, pt) > MAX_PITCH) continue;
			double turn = Math.acos(Math.clamp(look.dot(pt.subtract(eye).normalize()), -1, 1)) + (1 - facing) * 0.8;
			if (turn < bestTurn) {
				bestTurn = turn;
				best = new Face(t, side, pt, t);
			}
		}
		return best;
	}

	/**
	 * No click on anything that's there puts it in the right way round (a hopper facing open air, the first log of a
	 * beam lying on its side): put a temporary block next to it to click on, like a builder does, knocked out after.
	 */
	private boolean tryScaffold(LocalPlayer p, BlockPos t) {
		if (p == null || plan == null || !scaffoldTried.add(t)) return false;
		if (scaffoldGoes.getOrDefault(t, 0) >= 2) return false; // (two goes with a temporary block didn't do it)
		BlockState want = plan.get(t);
		// (Only when it's the way round that's the problem: wheat that can't be reached isn't helped by a block next to it.)
		if (want == null || jobAt(t) != Placement.Job.PLACE || Placement.fluid(want) || Placement.fineRotation(want)
				|| !Placement.oriented(want)) return false;
		// Only for a block that stands by itself: one hanging on the temporary block would drop when it goes.
		if (!want.canSurvive(Terrain.level(), t)) return false;
		Item item = Placement.placeItem(want);
		// (Why each side won't do, for the log: the medieval house's open shutters, 34 "nowhere", no temporary block.)
		StringBuilder whyNot = new StringBuilder();
		for (Direction d : Direction.values()) {
			BlockPos n = t.relative(d);
			BlockState planned = plan.get(n);
			if (planned != null && !planned.isAir()) { whyNot.append(d).append(": the build's; "); continue; } // part of the build
			BlockState ns = Terrain.state(n);
			if (!(ns.isAir() || ns.canBeReplaced()) || p.getBoundingBox().intersects(new AABB(n))) { whyNot.append(d).append(": taken; "); continue; }
			// It has to come out again once t is in: not in a pocket whose only way in is t's spot (KingBlack: glass under
			// a block put in from above, basalt below and the statue all round it, left there for good).
			if (!keepsOpenSide(n, t)) { whyNot.append(d).append(": shut in; "); continue; }
			Direction side = d.getOpposite(); // the face of n that looks at t
			// From somewhere we can be (open now), looking at that face. Its top or bottom: from the side, the way we face
			// is what counts (worked out from straight over it, the way we'd face was anything: a trapdoor and a shelf got
			// their glass under them, every click put them in the wrong way round, and round it went for minutes).
			List<Vec3> eyes = new ArrayList<>();
			Vec3 mid = Vec3.atCenterOf(t);
			if (d.getAxis() == Direction.Axis.Y) {
				for (Direction h : Direction.Plane.HORIZONTAL) {
					for (double up : new double[]{1.2, 2.2}) eyes.add(mid.add(h.getStepX() * 1.6, d == Direction.DOWN ? up : -up, h.getStepZ() * 1.6));
				}
			} else {
				Vec3 back = Vec3.atLowerCornerOf(d.getUnitVec3i());
				for (double up : new double[]{0.8, 2.2, -0.6}) eyes.add(mid.subtract(back.scale(2.0)).add(0, up, 0));
				eyes.add(mid.subtract(back.scale(0.4)).add(0, 2.4, 0)); // (over it, looking down at the face)
				// (And off to one side, looking along the face: stairs face the way we look, so from straight in front they
				// could only face the block clicked. A row of upside-down stairs facing out over the special medieval
				// house's edge, only their side neighbours to click, got no temporary block and 8 were left out.)
				Vec3 along = new Vec3(-back.z, 0, back.x);
				for (double sgn : new double[]{1, -1}) {
					for (double up : new double[]{0.8, 2.0}) eyes.add(mid.subtract(back.scale(1.2)).add(along.scale(2.2 * sgn)).add(0, up, 0));
				}
			}
			boolean right = false;
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			for (Vec3 eye : eyes) {
				// (And one we can get to, with the face in sight and in reach from it: the dining hall's trapdoors, top half,
				// got their glass over them, whose underside is at head height, never in sight from the floor; 18 of them
				// were left out. Beside them, the upper half of its side is what a player clicks.)
				if (right || !roomForEye(eye) || !eyeGettable(eye)) continue;
				for (double off : new double[]{0, 0.25, -0.25}) {
					Vec3 o = side.getAxis() != Direction.Axis.Y ? new Vec3(0, off, 0)
							: Math.abs(eye.x - mid.x) > Math.abs(eye.z - mid.z) ? new Vec3(0, 0, off) : new Vec3(off, 0, 0);
					Vec3 pt = Vec3.atCenterOf(n).add(normal.scale(0.5)).add(o);
					if (eye.distanceTo(pt) > p.blockInteractionRange() - 0.5 || !clearTo(p, eye, pt.subtract(normal.scale(0.05)), n)) continue;
					BlockState got = Placement.predictAgainst(p, n, side, t, item, eye, o);
					if (Placement.sameWay(got, Placement.placeShape(want), false)) right = true;
				}
			}
			if (!right) { whyNot.append(d).append(": no click on it gives the right way; "); continue; }
			// Something for the temporary block to go against: right there, or a temporary block or two out to it (a
			// branch of the fantasy building's tree, wood with its grain up, with nothing under it for blocks: 343
			// pieces of dark oak left out in a night).
			List<BlockPos> chain = supportChain(p, n, t);
			if (chain == null) { whyNot.append(d).append(": nothing to hold it up; "); continue; }
			// And a spot to click it from once it's in - the real search, with it (and what holds it up) in, not just a few
			// eyes: the awkward test's upside-down stair got its temporary block south of it at once, then from nowhere was
			// a click on it the right way round (5 goes in 2 s, left as it was).
			Box sb = box();
			if (sb != null) {
				java.util.Set<BlockPos> would = new java.util.HashSet<>(chain);
				would.add(n);
				pretendSolid = would;
				BlockPos spot;
				try {
					spot = standFor(p, sb, t);
				} finally {
					pretendSolid = java.util.Set.of();
				}
				if (spot == null) { whyNot.append(d).append(": no spot to click it from; "); continue; }
			}
			List<BlockPos> all = new ArrayList<>(chain);
			all.add(n);
			for (BlockPos q : all) {
				// Glass: it doesn't turn the grass under it to dirt (dirt did, and then grew grass itself).
				plan.put(q, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
				planLayers.computeIfAbsent(q.getY(), k -> new ArrayList<>()).add(q);
				planList.add(q);
				temps.put(q, t);
				// (Under the layers we're on: the window comes down for it. A fence gate in the Walmart's shelves
				// waited on its temporary block a layer under the window for good.)
				scanFromY = Math.min(scanFromY, q.getY());
			}
			bad.remove(t);
			scaffoldGoes.merge(t, 1, Integer::sum);
			HumanAutopilot.LOGGER.info("[schem] nothing to click on puts {} in the right way round: a temporary block at {} to click on{}",
					t.toShortString(), n.toShortString(), chain.isEmpty() ? "" : " (and " + chain.size() + " to hold it up)");
			return true;
		}
		HumanAutopilot.LOGGER.info("[schem] no temporary block puts {} ({}) in the right way round: {}", t.toShortString(), want, whyNot);
		return false;
	}

	/**
	 * What holds a temporary block at {@code n} up: nothing more if something solid is next to it already, else the
	 * shortest run (at most 2) of free cells from something solid to it, the one against the solid block first. Null if
	 * there's none.
	 */
	private List<BlockPos> supportChain(LocalPlayer p, BlockPos n, BlockPos t) {
		if (holdsUp(n, t, null)) return List.of();
		AABB body = p.getBoundingBox().inflate(0.1);
		Map<BlockPos, BlockPos> from = new HashMap<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		for (Direction d : Direction.values()) {
			BlockPos c = n.relative(d);
			if (c.equals(t) || !freeForTemp(c, body)) continue;
			from.put(c, n);
			q.add(c);
		}
		while (!q.isEmpty()) {
			BlockPos c = q.poll();
			if (holdsUp(c, t, from)) {
				List<BlockPos> chain = new ArrayList<>();
				for (BlockPos x = c; !x.equals(n); x = from.get(x)) chain.add(x);
				return chain; // c (against the solid block) first, the one next to n last
			}
			if (!from.get(c).equals(n)) continue; // (two at most)
			for (Direction d : Direction.values()) {
				BlockPos m = c.relative(d);
				if (m.equals(t) || m.equals(n) || from.containsKey(m) || !freeForTemp(m, body)) continue;
				from.put(m, c);
				q.add(m);
			}
		}
		return null;
	}

	private final Map<BlockPos, Integer> scaffoldGoes = new HashMap<>();
	/** The build's own grass, flowers... out for a spot they hid (see giveUp), when they went out, and tries per spot. */
	private static final Map<BlockPos, BlockPos> outFor = new HashMap<>();
	private final Map<BlockPos, Long> outForAt = new HashMap<>();
	private final Map<BlockPos, Integer> outTries = new HashMap<>();
	/** Spots with snow or grass in them to knock out before the block goes in (see giveUp). */
	private static final java.util.Set<BlockPos> clearFirst = new java.util.HashSet<>();
	/** A tree's own leaves to knock out after all: between us and a spot they hide (see leavesInWay). */
	private static final java.util.Set<BlockPos> leafWay = new java.util.HashSet<>();
	/**
	 * Blocks of the build taken out again to get at a spot they shut in, each with that spot (openUp): out until it's
	 * in, then back in, first thing (putBack).
	 */
	private static final Map<BlockPos, BlockPos> opened = new HashMap<>();
	private static final Map<BlockPos, Long> openedAt = new HashMap<>();
	private static final java.util.Set<BlockPos> putBack = new java.util.LinkedHashSet<>();
	/** Crops whose farmland just went in: planted next (see the placing). */
	private static final java.util.Set<BlockPos> plantNext = new java.util.LinkedHashSet<>();
	private final Map<BlockPos, Integer> openTries = new HashMap<>();
	/** While trying spots as if these blocks were out (openUp): the rays go through them, and they're nothing to click. */
	private static java.util.Set<BlockPos> pretendAir = java.util.Set.of();
	/** While trying spots as if these temporary blocks were in (tryScaffold): the rays stop at them, they're to click. */
	private static java.util.Set<BlockPos> pretendSolid = java.util.Set.of();

	/** Solid ground (not leaves, not a log) 1-3 under it: a canopy low enough to be a roof to work under. */
	private static boolean lowOverGround(BlockPos c) {
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos u = c.below(dy);
			BlockState w = Terrain.state(u);
			if (w.isAir() || w.canBeReplaced() || w.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
			if (w.is(net.minecraft.tags.BlockTags.LOGS)) return false;
			return solid(u);
		}
		return false;
	}

	/** Within {@code m} of the build's box (on any side). */
	private boolean nearPlanBox(BlockPos c, int m) {
		return planBox != null && c.getX() >= planBox.minX() - m && c.getX() <= planBox.maxX() + m && c.getY() >= planBox.minY() - m
				&& c.getY() <= planBox.maxY() + m && c.getZ() >= planBox.minZ() - m && c.getZ() <= planBox.maxZ() + m;
	}

	private static boolean naturalLeaves(BlockState s) {
		return s.is(net.minecraft.tags.BlockTags.LEAVES) && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)
				&& !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT);
	}

	/**
	 * No view of {@code t} because a tree's leaves are all round it (the clearing leaves those to fall by themselves once
	 * the trunk is out, but the trunk was inside them: the dark gothic portal had a spruce in it, "nowhere to place it
	 * from" to the end, 46 logs and 492 leaves left in the build). From the spots round it, the line to it with the
	 * fewest leaves on it and nothing else: those leaves go on the clearing list. False if there's no such line.
	 */
	/** Natural leaves in the column over {@code t} (up to the first other solid block): on the clearing list, the window up to them. */
	private boolean leavesOverhead(BlockPos t) {
		if (lookup == null) return false;
		List<BlockPos> leaves = new ArrayList<>();
		for (int y = t.getY() + 1; y <= t.getY() + 30; y++) {
			BlockPos c = new BlockPos(t.getX(), y, t.getZ());
			BlockState w = Terrain.state(c);
			if (w.isAir() || w.canBeReplaced()) continue;
			if (!naturalLeaves(w)) break;
			BlockState want = lookup.apply(c);
			if (want == null || !want.isAir()) break;
			leaves.add(c);
		}
		if (leaves.isEmpty() || !leafWay.addAll(leaves)) return false;
		int top = leaves.get(leaves.size() - 1).getY();
		if (activeY != Integer.MIN_VALUE && top > activeY + extraLayers) extraLayers = top - activeY;
		clearScanAt = -100;
		HumanAutopilot.LOGGER.info("[schem] {} is under a tree's leaves with no way down: clearing a way through the {} over it", t.toShortString(), leaves.size());
		return true;
	}

	private boolean leavesInWay(LocalPlayer p, BlockPos t) {
		if (lookup == null || p == null) return false;
		Vec3 to = Vec3.atCenterOf(t);
		List<BlockPos> best = null;
		double bestD = 0;
		for (int dy = 0; dy <= 3; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx == 0 && dz == 0) continue;
					int x = t.getX() + dx, y = t.getY() + dy, z = t.getZ() + dz;
					if (!flyable(x, y, z)) continue;
					Vec3 eye = new Vec3(x + 0.5, y + hov(new BlockPos(x, y, z)) + p.getEyeHeight(), z + 0.5);
					double dist = eye.distanceTo(to);
					if (dist > p.blockInteractionRange() - 0.5) continue;
					List<BlockPos> leaves = new ArrayList<>();
					boolean ok = true;
					BlockPos last = null;
					for (double k = 0; ok && k < dist - 0.45; k += 0.1) {
						BlockPos c = BlockPos.containing(eye.add(to.subtract(eye).scale(k / dist)));
						if (c.equals(last) || c.equals(t)) continue;
						last = c;
						BlockState w = Terrain.state(c);
						if (w.getShape(Terrain.level(), c).isEmpty()) continue;
						BlockState want = naturalLeaves(w) ? lookup.apply(c) : null;
						// (Or a tree's own leaves just outside the build, up against it: the black hole's pixel-art wall
						// through a birch wood, every look at its bottom rows through the leaves beside it, "confused" 26
						// targets in 10 s. A player cuts into the tree there too.)
						// (Or in a spot of the build that wants something else: they go anyway, so first. The cottage in an
						// oak wood: its roof ridge round a tree's crown, six spots of it in leaves hiding each other, each
						// "nowhere to place it from" and left that way.)
						boolean goes = want != null && !want.isAir() && !Placement.matches(w, want);
						if ((want != null && want.isAir()) || goes || (want == null && naturalLeaves(w) && nearPlanBox(c, 2))) {
							if (!leaves.contains(c)) leaves.add(c);
						} else {
							ok = false; // something that stays (or isn't ours to take) in the way
						}
					}
					if (!ok || leaves.isEmpty()) continue;
					if (best == null || leaves.size() < best.size() || (leaves.size() == best.size() && dist < bestD)) {
						best = leaves;
						bestD = dist;
					}
				}
			}
		}
		if (best == null) return false;
		boolean any = false;
		for (BlockPos c : best) {
			BlockState want = lookup.apply(c);
			if (want == null) any |= clearOnTop(c); // (outside: straight on the list)
			else if (want.isAir()) any |= leafWay.add(c);
			else if (!opened.containsKey(t) && !opened.containsValue(c)) {
				// A spot of the build: its leaves out first, before anything else, and the spot left open until the one it
				// hid is in (filled straight away, the planks hid it again) - like a block taken out for it (openUp).
				cant.remove(c);
				bad.remove(c);
				if (opened.putIfAbsent(c.immutable(), t.immutable()) == null) {
					openedAt.putIfAbsent(t.immutable(), HumanAutopilot.ticks());
					any = true;
				}
			}
		}
		if (!any) return false;
		clearScanAt = -100; // (the clearing list, now)
		HumanAutopilot.LOGGER.info("[schem] {} is hidden in a tree's leaves: knocking out the {} in the way first", t.toShortString(), best.size());
		return true;
	}
	/** Spots seen built, then gone again: how many times in the last minute, since when. */
	private final Map<Long, long[]> undone = new HashMap<>();

	/** Room for us with our eyes here (the cells of the eyes and the feet are open). */
	/** Where we'd be with our eyes at {@code eye} is a spot we can fly to (or outside the map round us: not known). */
	private boolean eyeGettable(Vec3 eye) {
		BlockPos feet = BlockPos.containing(eye.x, eye.y - 1.62 + 0.05, eye.z);
		if (!inRegion(feet.getX(), feet.getY(), feet.getZ())) return true;
		return airFrom.containsKey(feet) || airFrom.containsKey(feet.below());
	}

	/** Nothing in the way from {@code eye} to {@code pt} (in the empty spot {@code n}, where a block is still to go). */
	private static boolean clearTo(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos n) {
		var hit = rays(p).clip(new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(n);
	}

	private static boolean roomForEye(Vec3 eye) {
		int x = Mth.floor(eye.x), z = Mth.floor(eye.z);
		return Terrain.passable(x, Mth.floor(eye.y), z) && Terrain.passable(x, Mth.floor(eye.y - 1.5), z);
	}

	/** A side of {@code c} other than {@code t}'s that stays open in the finished build (to see a block there and knock it out). */
	private static boolean keepsOpenSide(BlockPos c, BlockPos t) {
		for (Direction e : Direction.values()) {
			BlockPos m = c.relative(e);
			if (m.equals(t)) continue;
			BlockState planned = plan.get(m);
			boolean ours = temps.containsKey(m);
			if (!ours && planned != null && !planned.isAir()) continue; // the build goes there
			if (!ours && solid(m)) continue; // the world has something there that stays
			return true;
		}
		return false;
	}

	/** Something solid (not {@code t}, not clickable-open like a chest) next to {@code c} to put a block against. */
	private boolean holdsUp(BlockPos c, BlockPos t, Map<BlockPos, BlockPos> chain) {
		for (Direction e : Direction.values()) {
			BlockPos m = c.relative(e);
			if (m.equals(t) || (chain != null && chain.containsKey(m))) continue;
			// (Not another temporary block: that one goes once its own block is in, and this one is left hanging: the
			// second piece of a branch waited on a temporary block under nothing. Unless it's one that stays for the next
			// block beside its own - keepsForNext - which is just this: the floor's next log. Ignoring those, 65 of the
			// floor test's 109 temporary blocks still came with "1 or 2 to hold it up".)
			if (solid(m) && !Placement.usable(Terrain.state(m)) && (!temps.containsKey(m) || tempStays(m))) return true;
		}
		return false;
	}

	/** A temporary block that's in and stays a while: under a floor block, kept for the next one beside it. */
	private boolean tempStays(BlockPos m) {
		BlockState w = plan.get(m);
		BlockPos forT = temps.get(m);
		return w != null && !w.isAir() && forT != null && jobAt(m) == Placement.Job.DONE && keepsForNext(m, forT);
	}

	/** Set while explaining a failure to the log. */
	private StringBuilder debug;

	/** While looking for a spot: tufts (leaf litter, grass, flowers, snow) that were what a ray hit instead. */
	private static java.util.Set<BlockPos> tuftHits, lastTuftHits;
	private static BlockPos tuftHitsFor;

	/** The world as the rays see it: with pretendAir out (openUp), else just the world. */
	private static net.minecraft.world.level.BlockGetter rays(LocalPlayer p) {
		net.minecraft.world.level.BlockGetter l = p.level();
		if (pretendAir.isEmpty() && pretendSolid.isEmpty()) return l;
		java.util.Set<BlockPos> out = pretendAir, in = pretendSolid;
		return new net.minecraft.world.level.BlockGetter() {
			@Override
			public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos q) {
				return out.contains(q) || in.contains(q) ? null : l.getBlockEntity(q);
			}

			@Override
			public BlockState getBlockState(BlockPos q) {
				if (in.contains(q)) return net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState();
				return out.contains(q) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : l.getBlockState(q);
			}

			@Override
			public net.minecraft.world.level.material.FluidState getFluidState(BlockPos q) {
				return out.contains(q) || in.contains(q) ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState() : l.getFluidState(q);
			}

			@Override
			public int getHeight() {
				return l.getHeight();
			}

			@Override
			public int getMinY() {
				return l.getMinY();
			}
		};
	}

	private static boolean sees(LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos n, Direction side, Vec3 normal, StringBuilder debug) {
		var hit = rays(p).clip(new ClipContext(eye, pt.subtract(normal.scale(0.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean ok = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(n) && hit.getDirection() == side;
		if (!ok && tuftHits != null && tuftHits.size() < 64 && hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(n)) {
			BlockPos hp = hit.getBlockPos();
			BlockState hs = Terrain.state(hp);
			// (Kelp and seagrass too: the fantasy build over a lake, the kelp round its bottom layer's spots in front of every
			// face - "0 with a view" from 294 open spots, twice each.)
			if (!hs.isAir() && ((hs.canBeReplaced() && hs.getFluidState().isEmpty()) || waterPlant(hs)) && hs.getCollisionShape(Terrain.level(), hp).isEmpty()) {
				tuftHits.add(hp.immutable());
			}
		}
		if (!ok && debug != null) {
			debug.append(side).append(" face of ").append(n.toShortString()).append(": ray hits ")
					.append(hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().toShortString() + " " + hit.getDirection() + " ("
							+ net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Terrain.state(hit.getBlockPos()).getBlock()).getPath()
							+ (naturalLeaves(Terrain.state(hit.getBlockPos())) ? ", a tree's" : "") + ")" : hit.getType())
					.append("; ");
		}
		return ok;
	}

	/** Kelp, seagrass: a lake's own plants, knocked out like a tuft of grass when they're in the way of every look. */
	private static boolean waterPlant(BlockState s) {
		return s.is(net.minecraft.world.level.block.Blocks.KELP) || s.is(net.minecraft.world.level.block.Blocks.KELP_PLANT)
				|| s.is(net.minecraft.world.level.block.Blocks.SEAGRASS) || s.is(net.minecraft.world.level.block.Blocks.TALL_SEAGRASS);
	}

	private static final Direction[] ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

	private static Vec3 inPlane(Direction side, double a, double b) {
		return switch (side.getAxis()) {
			case Y -> new Vec3(a, 0, b);
			case X -> new Vec3(0, a, b);
			case Z -> new Vec3(a, b, 0);
		};
	}

	// ------------------------------------------------------------------ looking before flying

	/**
	 * Some face of this spot can be seen at all: one of the six blocks round it lets a look through (it isn't a full
	 * block). With a full block on every side nothing of it can be seen from anywhere, so there's no point flying over or
	 * looking for a spot to work from - a player wouldn't even try. (The copper guard on its hill, the user's "flying
	 * around like a bot": of 365 long flights to far spots only 9 put a block in; 302 ended "nowhere to place it from" at
	 * a spot buried in the hill. Near him, the full search for a spot ran on one buried spot after another: "[confused]
	 * 8-30 targets in 10 s".) It goes on the list again as soon as something next to it opens up.
	 */
	static boolean openFace(BlockPos h) {
		for (Direction d : Direction.values()) {
			BlockPos n = h.relative(d);
			if (!Terrain.loaded(n.getX(), n.getZ())) return true; // (can't tell from here)
			BlockState s = Terrain.state(n);
			if (s.isAir() || !net.minecraft.world.level.block.Block.isShapeFullBlock(s.getShape(Terrain.level(), n))) return true;
		}
		return false;
	}

	/** A spot to fill, loaded, with nothing round it to see it through (see openFace): it can't hold its layer open. */
	private static boolean buriedHole(BlockPos c) {
		return Terrain.loaded(c.getX(), c.getZ()) && !openFace(c) && jobAt(c) == Placement.Job.PLACE;
	}

	/** When a spot was last found not to hold its layer open (hanging in the air, or buried): see layerFinished. */
	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap layerSkipAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	{
		layerSkipAt.defaultReturnValue(Long.MIN_VALUE);
	}

	/** Spots passed over because nothing of them can be seen (openFace), since the last status line. */
	private int buriedPassed;

	/**
	 * Far spots looked over from where we are: when, and whether there's a spot open to the sky to work on it from (1) or
	 * not (0). Kept a while, so the same far spot isn't looked over again on every pick.
	 */
	private final Map<BlockPos, long[]> farSeen = new HashMap<>();
	/** standFor for a far spot (no open-air map out there): only spots open to the sky count, and the first one will do. */
	private boolean farProbe;
	/** Far spots looked over / found workable from the sky / not, since the last status line. */
	private int farLooks, farYes, farNo;

	/**
	 * Worth flying over for: from where we are (the chunks are loaded that far), there's a spot near it, open to the sky
	 * (we can come down to it from above), from which a face that puts it in can be seen and reached - the same test as
	 * when we're there (standFor), only without the open-air map round us. A spot under an overhang or inside a room isn't
	 * open to the sky, so a "no" here is only "not from afar": once we're over there (or nothing else is left) it gets its
	 * normal go. (Flying first and looking after, most far flights were for nothing.)
	 */
	private boolean workableFromAfar(LocalPlayer p, Box b, BlockPos t) {
		if (!Terrain.loaded(t.getX(), t.getZ()) || Terrain.state(t).is(net.minecraft.world.level.block.Blocks.VOID_AIR)) return true; // (can't tell)
		if (!openFace(t)) return false;
		long now = HumanAutopilot.ticks();
		long[] v = farSeen.get(t);
		// (A yes for 10 s, a no for a minute: what's round it changes as the build goes up.)
		if (v != null && now - v[0] < (v[1] == 1 ? 200 : 1200)) return v[1] == 1;
		BlockPos spot;
		long c0 = costStart();
		farProbe = true;
		standSlack = 0.6;
		try {
			farAirAt(b, t);
			spot = standFor(p, b, t, (eye, look) -> faceFrom(p, eye, look, t));
			// (And looking down steeply, as she would on a second go once there: a "no" only if that fails too.)
			if (spot == null && plan != null && steepOk.add(t)) {
				try {
					spot = standFor(p, b, t, (eye, look) -> faceFrom(p, eye, look, t));
				} finally {
					steepOk.remove(t);
				}
			}
		} finally {
			farProbe = false;
			standSlack = 0;
			costEnd("farlook", c0);
		}
		boolean ok = spot != null;
		if (farSeen.size() > 4000) farSeen.values().removeIf(e -> now - e[0] > 1200);
		// (The spot too: the flight over goes to it - open to the sky, so we can come down to it from above - not to the
		// air right over the block, which was often inside the hill: "couldn't fly there".)
		farSeen.put(t.immutable(), new long[]{now, ok ? 1 : 0, ok ? spot.asLong() : 0});
		farLooks++;
		if (ok) farYes++;
		else farNo++;
		// (Why not, now and then: a "no" here that's wrong costs a spot; the log shows what kind they are.)
		if (!ok && now - farNoLogAt > 100) {
			farNoLogAt = now;
			BlockState want = plan != null ? plan.get(t) : null;
			HumanAutopilot.LOGGER.info("[schem] far look at {} ({} -> {}): no spot we can fly in to with a view of it ({} open spots round it)",
					t.toShortString(), Terrain.state(t).getBlock().getName().getString(), want == null ? "?" : want.getBlock().getName().getString(),
					lastOpenSpots);
		}
		return ok;
	}

	private long farNoLogAt = -1000;

	/** The open air round a far spot we could fly into from above (see farAirAt), for standFor's farProbe. */
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet farAirCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * The open air round {@code t} that can be got into from above: from the top of each column within 7 (over its highest
	 * block that stops us, leaves too) down and in through cells the body fits, within 8 over it and 5 under it - so under
	 * a tree's crown, open at the sides, counts, but a cave in the hill doesn't. (Open straight up only, a spot under the
	 * trees - the top of the ground she'd chosen to dig down from - was "no spot open to the sky".)
	 */
	private void farAirAt(Box b, BlockPos t) {
		farAirCells.clear();
		int r = 7, x0 = t.getX() - r, x1 = t.getX() + r, z0 = t.getZ() - r, z1 = t.getZ() + r, yTop = t.getY() + 8, yBot = t.getY() - 5;
		it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue q = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (!Terrain.loaded(x, z)) continue;
				int h = Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
				int sy = Math.max(h, yBot);
				if (sy > yTop || !inBox(b, x, sy, z) || !flyable(x, sy, z)) continue;
				long k = BlockPos.asLong(x, sy, z);
				if (farAirCells.add(k)) q.enqueue(k);
			}
		}
		while (!q.isEmpty() && farAirCells.size() < 3000) {
			long k = q.dequeueLong();
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			for (Direction d : Direction.values()) {
				int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
				if (nx < x0 || nx > x1 || nz < z0 || nz > z1 || ny < yBot || ny > yTop) continue;
				long nk = BlockPos.asLong(nx, ny, nz);
				if (farAirCells.contains(nk) || !inBox(b, nx, ny, nz) || !flyable(nx, ny, nz)) continue;
				farAirCells.add(nk);
				q.enqueue(nk);
			}
		}
	}

	/** The spot open to the sky a far one was found workable from (see workableFromAfar), if it's still a recent yes. */
	private BlockPos farSpot(BlockPos t) {
		long[] v = farSeen.get(t);
		if (v == null || v[1] != 1 || v.length < 3 || HumanAutopilot.ticks() - v[0] > 1200) return null;
		BlockPos s = BlockPos.of(v[2]);
		return flyable(s.getX(), s.getY(), s.getZ()) ? s : null;
	}

	/** How many far spots this tick may still look over (see workableFromAfar): each can be a full search for a spot. */
	private int farLookBudget;
	private static final int FAR_LOOKS = 6;

	/** Far holes found buried (openFace), and when: left out of the far pick for a minute (see farBuriedNow). */
	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap farBuried = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	{
		farBuried.defaultReturnValue(Long.MIN_VALUE);
	}

	/** Found buried in the last minute (see farBuriedNow): no new look. */
	private boolean farBuriedSeen(BlockPos h, long now) {
		if (farBuried.isEmpty()) return false;
		long at = farBuried.get(h.asLong());
		return at != Long.MIN_VALUE && now - at < 1200;
	}

	/**
	 * Buried (openFace), looked at now if not in the last minute, and remembered: the far pick passes over it, and the
	 * next pick doesn't even list it - so a hill full of buried spots that are all the nearest doesn't hide the ones
	 * further on that can be done.
	 */
	private boolean farBuriedNow(BlockPos h, long now) {
		long k = h.asLong(), at = farBuried.get(k);
		if (at != Long.MIN_VALUE && now - at < 1200) return true;
		if (openFace(h)) {
			if (at != Long.MIN_VALUE) farBuried.remove(k);
			return false;
		}
		if (farBuried.size() > 300_000) farBuried.clear();
		farBuried.put(k, now);
		return true;
	}
	/** The tick the last pick found nothing to do (see pickTarget). */
	private long pickNoneAt = -1;
	/** The tick a far pick ran out of looks with far spots still to look over (it goes on next tick: not stuck). */
	private long farPendingAt = -1;
	/** The far spot the pick chose to fly over for (looked over already, or flown to blind on purpose). */
	private BlockPos farApproved;
	/** Far spots flown over to without a look from afar saying yes (nothing else would do): when, once in 2 minutes each. */
	private final Map<BlockPos, Long> blindAt = new HashMap<>();
	private long blindLogAt = -1000, farSkipLogAt = -1000;

	/**
	 * workableFromAfar within this tick's looks: null when it would need a look over and there's none left this tick
	 * (6 a tick, and none once the tick has had 8 ms).
	 */
	private Boolean farOk(LocalPlayer p, Box b, BlockPos h) {
		if (!Terrain.loaded(h.getX(), h.getZ()) || Terrain.state(h).is(net.minecraft.world.level.block.Blocks.VOID_AIR)) return true;
		if (!openFace(h)) return false;
		long[] v = farSeen.get(h);
		long now = HumanAutopilot.ticks();
		if (v != null && now - v[0] < (v[1] == 1 ? 200 : 1200)) return v[1] == 1;
		// (One look a tick always, whatever the tick has cost so far: a big build's scan and pick can take 8 ms by
		// themselves, and then none would ever have been looked over - "looking the build over" for good.)
		if (farLookBudget <= 0 || (farLookBudget < FAR_LOOKS && System.nanoTime() - tickStartNs > 8_000_000L)) return null;
		farLookBudget--;
		return workableFromAfar(p, b, h);
	}

	/**
	 * Where to hover: 1-3 above the hole, at most 3 to the side, in a column open to the sky (so we can't be
	 * closed in), from where the hole's face is in reach and in sight. Close to where we are now wins.
	 */
	private BlockPos standFor(LocalPlayer p, Box b, BlockPos t) {
		long c0 = costStart();
		// A spot where it's in reach with some to spare: hovering there we're never exactly where we meant (the castle's
		// ground edge: 4.69 from the spot as worked out, 5.10 from where we hung, and "can't see it" every time; up to
		// half a block off each way). (Not more on the steeper second go: right over a pit the eyes are 4.1 from its
		// floor, and 1.0 to spare left the medieval house's ground layer "nowhere to place it from", 23 in 30 s.)
		standSlack = 0.6;
		try {
			return standFor(p, b, t, (eye, look) -> faceFrom(p, eye, look, t));
		} finally {
			standSlack = 0;
			costEnd("stand", c0);
		}
	}

	private double standSlack;
	private long noSpotWhyAt = -1000;

	private static long now0() {
		return HumanAutopilot.ticks();
	}

	private BlockPos standFor(LocalPlayer p, Box b, BlockPos t, java.util.function.BiFunction<Vec3, Vec3, Face> sight) {
		List<Vec3> crowdNow = otherPlayers(p);
		boolean onFootNow = !p.getAbilities().flying && p.onGround();
		int footY = Mth.floor(p.getY() + 0.01);
		BlockPos firstOpen = null;
		int firstOpenD = 99;
		BlockPos best = null;
		boolean bestLow = false;
		double bestScore = Double.MAX_VALUE;
		int open = 0, seen = 0;
		// Schematic: also from lower down (looking up at the underside of a ceiling for a chain, or at the top half
		// of a side for an upside-down stair).
		for (int dy = plan != null ? -3 : -1; dy <= 3; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					// (Looking a far spot over: one spot to do it from is all we need to know.)
					if (farProbe && best != null) {
						lastStandLow = bestLow;
						return best;
					}
					BlockPos c = t.offset(dx, dy, dz);
					if (!hoverable(p, b, c)) continue;
					if (!pretendSolid.isEmpty() && (pretendSolid.contains(c) || pretendSolid.contains(c.above()) || pretendSolid.contains(c.above(2)))) continue;
					if (plan != null && (handDoor(Terrain.state(c)) || handDoor(Terrain.state(c.above())))) continue; // not in a doorway
					if (nearest(crowdNow, Vec3.atBottomCenterOf(c)) < 2.5) continue; // not right next to Kohaku
					open++;
					if (firstOpen == null || (Math.abs(dx) + Math.abs(dz) == 2 && firstOpenD != 2)) {
						firstOpen = c;
						firstOpenD = Math.abs(dx) + Math.abs(dz);
					}
					// We'd be in it (hovering 1.5 up, straight above is fine; in a schematic, right under it is fine too, if
					// our head stays clear of it: looking up at a ceiling to hang a chain).
					if (dx == 0 && dz == 0 && (plan == null ? dy <= 0 : c.getY() + LOW + 1.9 > t.getY() && dy <= 1)) continue;
					// (Out of reach of every face of it from here, at our hover or low over the floor: the next one. The sight
					// checks of the far cells were most of a "no spot" search, 10-40 ms on the cottage's mountaintop.)
					{
						double tcy = t.getY() + 0.5, eyHi = c.getY() + hov(c) + p.getEyeHeight(), eyLo = c.getY() + LOW + p.getEyeHeight();
						double ddy = tcy >= eyLo && tcy <= eyHi ? 0 : Math.min(Math.abs(tcy - eyLo), Math.abs(tcy - eyHi));
						if (dx * dx + dz * dz + ddy * ddy > Math.pow(p.blockInteractionRange() + 0.9, 2)) continue;
					}
					// Hovering there, it would go in right under our feet (we'd be set down on it), which the placing never
					// does: in a low room (a 3-high hollow lets us hover only a hair over the floor) the spot straight over
					// a hole is no use, so it flew there, didn't place, gave up and picked the same spot again for ever.
					// (At the usual hover our body would be in or right over the spot itself: no place to put it from.)
					boolean overIt = body(c, hov(c)).expandTowards(0, -1.2, 0).intersects(new AABB(t));
					// Not right over it looking straight down (right under it looking up is fine; standing on the floor
					// next to a pit, looking down into it, too: below).
					boolean overNear = plan != null && !overIt && Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && !steepFine(t)
							&& c.getY() + hov(c) + p.getEyeHeight() > t.getY() + 1;
					Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + hov(c) + p.getEyeHeight(), c.getZ() + 0.5);
					boolean low = false;
					// On our feet already (on a bridge's deck, a floor): a spot on the same floor, from standing height,
					// first - walk over and put it in from there, like a player, rather than take off to hover over the
					// floor for the next block of it and touch down again (the obsidian bridge: 11 landings, 11 hop-offs).
					boolean standHere = false;
					if (onFootNow && plan != null && (dx != 0 || dz != 0) && t.getY() <= c.getY() && c.getY() == footY && solid(c.below())) {
						Vec3 se = new Vec3(c.getX() + 0.5, c.getY() + LOW + p.getEyeHeight(), c.getZ() + 0.5);
						if (!body(c, LOW).expandTowards(0, -1.2, 0).intersects(new AABB(t))
								&& sight.apply(se, Vec3.atCenterOf(t).subtract(se).normalize()) != null) {
							eye = se;
							low = true;
							standHere = true;
						}
					}
					if (!standHere && (overNear || overIt || sight.apply(eye, Vec3.atCenterOf(t).subtract(eye).normalize()) == null)) {
						if (plan != null && t.getY() <= c.getY() && (dx != 0 || dz != 0) && solid(c.below())) {
							// A pit in the floor (a ground block knocked out for the right one, its neighbours in already):
							// only its bottom shows, from steeply over it, and from our usual hover that's out of reach
							// (the eyes 4.1 over it). A player stands on the floor next to it and looks down in.
							// ("nowhere to place it from": 109 of the medieval house's ground blocks, 93 of the barn's floor.)
							// And one on the floor beside us that only goes in the right way round seen from low down (a
							// barrel facing sideways needs a look less than 45 deg down; from our hover every look at it is
							// steeper: "wrong way round from here", barrels and trapdoors left in the cottage and the house.)
							eye = new Vec3(c.getX() + 0.5, c.getY() + LOW + p.getEyeHeight(), c.getZ() + 0.5);
							if (body(c, LOW).expandTowards(0, -1.2, 0).intersects(new AABB(t))
									|| sight.apply(eye, Vec3.atCenterOf(t).subtract(eye).normalize()) == null) continue;
						} else {
							if (overNear) continue;
							// Straight over a pit one block wide and deeper than that (the dirt under the house's floor, the
							// columns round it higher): only its bottom shows, only from right over it, and from our usual
							// hover that's out of reach. Low over the pit's mouth, looking straight down, like a player.
							// (Once the usual spots have failed: steepFine. 22 of the house's fill blocks were "nowhere".)
							if (plan != null && dx == 0 && dz == 0 && dy >= 2 && steepFine(t)) {
								eye = new Vec3(c.getX() + 0.5, c.getY() + LOW + p.getEyeHeight(), c.getZ() + 0.5);
								if (sight.apply(eye, Vec3.atCenterOf(t).subtract(eye).normalize()) == null) continue;
								low = true;
								seen++;
								double score0 = p.position().distanceTo(Vec3.atBottomCenterOf(c)) * 0.5 + Math.abs(c.getY() + LOW - p.getY()) * 1.5 + 6
										+ (sightLoose ? 4 : 0);
								if (score0 < bestScore) {
									bestScore = score0;
									best = c;
									bestLow = true;
								}
								continue;
							}
							// Something overhead (a chain hanging from the ceiling, a rod hanging under a beam): from just
							// over the floor, looking up, like a player standing under it. Our usual hover put our head
							// where it goes; down here the whole body stays under it.
							// (Flying, no floor needed: a leaf in a pocket under the canopy, open only underneath, goes in
							// from just under it, looking up; needing a floor there left them out for good after TNT.)
							if (plan == null || t.getY() < c.getY() + 1) continue;
							eye = new Vec3(c.getX() + 0.5, c.getY() + LOW + p.getEyeHeight(), c.getZ() + 0.5);
							if (eye.y + 0.1 > t.getY() || body(c, LOW).expandTowards(0, -1.2, 0).intersects(new AABB(t))
									|| sight.apply(eye, Vec3.atCenterOf(t).subtract(eye).normalize()) == null) continue;
						}
						low = true;
					}
					seen++;
					// Close to where we are, and close to the hole (~2.2).
					// Stay at the height we're at if we can (every height change looked like jumping), close to us,
					// ~2.2 from the hole.
					double score = p.position().distanceTo(Vec3.atBottomCenterOf(c))
							+ Math.abs(c.getY() + hov(c) - p.getY()) * 4.0
							+ Math.abs(eye.distanceTo(Vec3.atCenterOf(t)) - 2.2) * 0.8;
					if (plan != null) {
						// Schematic: ~3 blocks off to the side, eyes a little above the block (looking slightly down).
						double hd = Math.hypot(dx, dz), up = eye.y - (t.getY() + 0.5);
						score = p.position().distanceTo(Vec3.atBottomCenterOf(c)) * 0.5
								+ Math.abs(c.getY() + hov(c) - p.getY()) * 1.5
								+ Math.abs(hd - 3.0) * 1.5
								+ Math.max(0, up - 2.5) * 1.5 + Math.max(0, 0.3 - up) * 2.0
								// Outside, under the open sky, if at all possible (squeezing in through a doorway to
								// fix an outside wall is not what a player does).
								+ (skyOpen(b, c.getX(), c.getY(), c.getZ()) ? 0 : 6);
					}
					if (standHere) score -= 2;
					else if (low) score += 2;
					// (Where a stair's heading has room either side, if there's such a spot: see planPoint.)
					if (sightLoose) score += 4;
					if (score < bestScore) {
						bestScore = score;
						best = c;
						bestLow = low;
					}
				}
			}
		}
		// Nothing from the middle of any spot: from a little off one - a player leans to see past a block's edge or round a
		// corner (the castle top's last wool, its only sight a corner of the wool beside it from the middle of the 2-high
		// slot next to it, the ray grazing that corner; the dark oak stair under the eaves). Only now: tried for every
		// spot it would be most of the search.
		Vec3 off0 = Vec3.ZERO;
		if (farProbe) {
			// (No leaning out for a far spot, and nothing to log: we'll see once we're there.)
			lastStandLow = bestLow;
			lastOpenSpots = open;
			return best;
		}
		if (best == null && plan != null) {
			double bestOff = Double.MAX_VALUE;
			for (int dy = -1; dy <= 2; dy++) {
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						if (dx == 0 && dz == 0 && dy <= 1) continue;
						BlockPos c = t.offset(dx, dy, dz);
						if (!hoverable(p, b, c)) continue;
						for (double h : new double[]{hov(c), LOW}) {
							for (double[] o : LEANS) {
								AABB body = body(c, h).move(o[0], 0, o[1]);
								if (body.expandTowards(0, -1.2, 0).intersects(new AABB(t)) || !p.level().noCollision(p, body)) continue;
								Vec3 eye = new Vec3(c.getX() + 0.5 + o[0], c.getY() + h + p.getEyeHeight(), c.getZ() + 0.5 + o[1]);
								if (sight.apply(eye, Vec3.atCenterOf(t).subtract(eye).normalize()) == null) continue;
								double score = p.position().distanceTo(eye) + Math.abs(dy) * 1.5;
								if (score < bestOff) {
									bestOff = score;
									best = c;
									bestLow = h == LOW;
									off0 = new Vec3(o[0], 0, o[1]);
								}
							}
						}
					}
				}
			}
			if (best != null) {
				HumanAutopilot.LOGGER.info("[box] {}: no spot from the middle of one, from a little off {} ({} {}){}", t.toShortString(),
						best.toShortString(), String.format(java.util.Locale.ROOT, "%+.2f", off0.x), String.format(java.util.Locale.ROOT, "%+.2f", off0.z),
						bestLow ? ", low" : "");
			}
		}
		lastStandOff = off0;
		lastOpenSpots = open;
		if (best == null) {
			// Explain it once from the spot straight above (the easiest one).
			BlockPos up = firstOpen != null && !hoverable(p, b, t.above()) ? firstOpen : t.above();
			debug = new StringBuilder();
			Vec3 eye = new Vec3(up.getX() + 0.5, up.getY() + hov(up) + p.getEyeHeight(), up.getZ() + 0.5);
			faceFrom(p, eye, Vec3.atCenterOf(t).subtract(eye).normalize(), t);
			HumanAutopilot.LOGGER.info("[box] no spot for {}{}: {} open spots, {} with a view. From {} (eye {}, open={}): {}",
					t.toShortString(), plan != null ? " (" + plan.get(t) + ", under it " + Terrain.state(t.below()) + ")" : "",
					open, seen, up.toShortString(), fmt(eye), hoverable(p, b, up), debug);
			// And from the open spots nearest it, high and low (the few blocks left out at the end: why).
			if (plan != null && now0() - noSpotWhyAt > 100) {
				noSpotWhyAt = now0();
				List<BlockPos> near = new ArrayList<>();
				for (BlockPos c : BlockPos.betweenClosed(t.offset(-2, -2, -2), t.offset(2, 1, 2))) {
					if (!c.equals(t) && hoverable(p, b, c)) near.add(c.immutable());
				}
				near.sort(java.util.Comparator.comparingDouble(c -> c.distSqr(t)));
				for (int i = 0; i < Math.min(3, near.size()); i++) {
					BlockPos c = near.get(i);
					for (double h : new double[]{hov(c), LOW}) {
						debug = new StringBuilder();
						Vec3 e = new Vec3(c.getX() + 0.5, c.getY() + h + p.getEyeHeight(), c.getZ() + 0.5);
						Face f = faceFrom(p, e, Vec3.atCenterOf(t).subtract(e).normalize(), t);
						HumanAutopilot.LOGGER.info("[box]   from {} (eye {}): {}{}", c.toShortString(), fmt(e), f != null ? "SEES IT " : "", debug);
					}
				}
			}
			debug = null;
		}
		lastStandLow = bestLow;
		return best;
	}

	private Vec3 routeLastPos = Vec3.ZERO, routeLastSide = Vec3.ZERO;
	private long routeLastMove, routeLastSideMove;
	private long shaftSince = -1;

	/**
	 * A spot we can hover in: our body fits, and we can fly there through open air from where we are or from the
	 * sky (so a big hollow under a roof, reachable only through a small hole, counts too).
	 */
	private boolean hoverable(LocalPlayer p, Box b, BlockPos c) {
		// (Looking a far spot over: no open-air map out there - the open air round it we can get into from above will do,
		// see farAirAt.)
		// (inBox, not inArea: inArea is only the map round us, and every spot by a far one was "not here".)
		if (farProbe) return inBox(b, c.getX(), c.getY(), c.getZ()) && flyable(c.getX(), c.getY(), c.getZ())
				&& (airFrom.containsKey(c) || farAirCells.contains(c.asLong()) || skyOpen(b, c.getX(), c.getY(), c.getZ()));
		return inArea(b, c.getX(), c.getY(), c.getZ()) && flyable(c.getX(), c.getY(), c.getZ()) && air(p, b).containsKey(c);
	}

	/**
	 * How high to hover over the ground in cell {@code c}: 1.5 in the open, less under a low roof (a 3-high hollow
	 * leaves room for ~1.1), never below 0.1.
	 */
	private static double hov(BlockPos c) {
		int room = 0;
		while (room < 6 && way(c.getX(), c.getY() + room, c.getZ())) room++;
		// (In a low room, in the middle of the room there is: at room - 1.9 a 3-high room left 0.1 to the ceiling; the head
		// bumped it, he dropped, the catch was a second jump inside the double-tap guard, and he landed: the cottage's rooms.)
		if (room < 5) return Math.clamp(Math.min(HOVER, (room - 1.8) / 2), 0.1, HOVER);
		return Math.clamp(Math.min(HOVER, room - 1.9), 0.1, HOVER);
	}

	// ------------------------------------------------------------------ the open air around the box

	/** Cells we can fly to (body fits), each with the cell we came from: a breadth-first search from us and the sky. */
	private final Map<BlockPos, BlockPos> airFrom = new HashMap<>();
	private long airAt = -100;
	private BlockPos airStart;

	private static boolean flyable(int x, int y, int z) {
		if (!way(x, y, z) || !way(x, y + 1, z)) return false;
		// Over a fence or a wall (1.5 high) our feet are half a block up the cell: the head needs the cell above that
		// too. (The castle's market stall, fences under a canopy: the map had the half-block gap as a way through, and
		// he pushed into it for a minute and a half.)
		if (tallBelow(x, y, z) && !way(x, y + 2, z)) return false;
		long k = BlockPos.asLong(x, y, z);
		if (!stuckCells.isEmpty()) {
			Long until = stuckCells.get(k);
			if (until != null && until > HumanAutopilot.ticks()) return false;
		}
		return true;
	}

	/** A fence, a wall or a shut fence gate under this cell: its top is half a block into it. */
	private static boolean tallBelow(int x, int y, int z) {
		BlockState s = Terrain.state(x, y - 1, z);
		var b = s.getBlock();
		return b instanceof net.minecraft.world.level.block.FenceBlock || b instanceof net.minecraft.world.level.block.WallBlock
				|| (b instanceof net.minecraft.world.level.block.FenceGateBlock && !s.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN));
	}

	/** Cells we pushed into and couldn't get into (the map has them open: a corner of something sticks in), until when. */
	private static final Map<Long, Long> stuckCells = new HashMap<>();
	/** How many times each cell got stuck: off the map longer each time (30 s, 1, 2, 4 minutes). */
	private static final Map<Long, Integer> stuckTimes = new HashMap<>();

	/**
	 * How long a cell we couldn't get into stays off the map: longer each time. (The cottage's rooms: the same five
	 * cells, each pushed into 2-4 times, 30 s apart - every time the map had them open again.)
	 */
	private static long stuckFor(BlockPos c) {
		int n = stuckTimes.merge(c.asLong(), 1, Integer::sum);
		return 600L << Math.min(3, n - 1);
	}

	/**
	 * The cell we're in, for the open-air searches: the one our feet are in, or (when that one isn't free: a lightning
	 * rod or a fence post beside us shares it) another one our body overlaps that is, or the one above (standing on a
	 * carpet). In the Walmart's shelves the middle of us was in a lightning rod's cell: every search started "inside a
	 * block", found no way anywhere, and he pushed into the ceiling until the user broke him out.
	 */
	private static BlockPos bodyCell(LocalPlayer p) {
		double y = p.getY() + 0.1;
		BlockPos c = BlockPos.containing(p.getX(), y, p.getZ());
		if (flyable(c.getX(), c.getY(), c.getZ())) return c;
		for (double ox : new double[]{-0.29, 0.29}) {
			for (double oz : new double[]{-0.29, 0.29}) {
				BlockPos q = BlockPos.containing(p.getX() + ox, y, p.getZ() + oz);
				if (flyable(q.getX(), q.getY(), q.getZ())) return q;
			}
		}
		if (flyable(c.getX(), c.getY() + 1, c.getZ())) return c.above();
		return c;
	}

	/** Open air, or (building a schematic) a door we can open by hand: a way into a closed room. */
	private static boolean way(int x, int y, int z) {
		if (Terrain.passable(x, y, z)) return true;
		if (plan == null) return false;
		BlockState s = Terrain.state(x, y, z);
		// Something thin lying on the floor - a carpet, a low snow layer: the body fits in the cell over it, the feet just
		// above. (Counted as solid, a carpeted room was no way at all: in the medieval house, after the red carpets went in,
		// "shut in (2 open cells)", nothing solid to knock out, and 3+ minutes hovering in the middle of the room.)
		if (Terrain.loaded(x, z) && !Terrain.hurts(s)) {
			var shape = s.getCollisionShape(Terrain.level(), new BlockPos(x, y, z));
			if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) <= 0.15) return true;
		}
		// Lava in creative: it can't hurt us and it's no wall. (The iron farm's lava: "built in ... nothing to knock out:
		// Lava: not solid" and "out by hand" every few seconds, back in, again.)
		if (Terrain.loaded(x, z) && s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
				&& s.getCollisionShape(Terrain.level(), new BlockPos(x, y, z)).isEmpty()) {
			var pl = Minecraft.getInstance().player;
			if (pl != null && pl.getAbilities().invulnerable) return true;
		}
		if (!handDoor(s)) return false;
		BlockPos lower = s.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? new BlockPos(x, y - 1, z) : new BlockPos(x, y, z);
		Long until = shutDoors.get(lower.asLong());
		return until == null || until < HumanAutopilot.ticks();
	}

	/**
	 * Doors that are no way through for now (lower half), until when: neither open nor shut does it leave a gap the
	 * way we come at it, or we got stuck in it. (A door standing open across the way he wanted to go: he walked into
	 * its frame, hopping, for 83 s.)
	 */
	private static final Map<Long, Long> shutDoors = new HashMap<>();

	/** The widest gap a door leaves in its spot to walk through it along x (or along z): 1 for nothing in the way. */
	private static double doorGap(BlockState st, BlockPos pos, boolean alongX) {
		var shape = st.getCollisionShape(Terrain.level(), pos);
		if (shape.isEmpty()) return 1;
		var bb = shape.bounds();
		return alongX ? Math.max(bb.minZ, 1 - bb.maxZ) : Math.max(bb.minX, 1 - bb.maxX);
	}

	/** A door a player opens with a click (wood, copper; not iron). */
	private static boolean handDoor(BlockState s) {
		return s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock d && d.type().canOpenByHand();
	}

	private long doorClickAt = -100;
	/**
	 * Doors (lower halves) we opened to get through that the schematic has shut: left open while we work (shutting each
	 * as "damage" and opening it again to get past was the cottage's door that "keeps coming undone"), shut at the end.
	 */
	private static final java.util.Set<BlockPos> openedDoors = new java.util.HashSet<>();
	private BlockPos closingDoor;
	private long closingSince;

	/**
	 * The way to {@code goal} goes through a closed door near us: fly up to it, look at it and right-click it open
	 * (pushing into it got nowhere). True while we're busy with it.
	 */
	/** Walks through doors lately: [tick, door]. */
	private final java.util.ArrayDeque<long[]> doorWalks = new java.util.ArrayDeque<>();
	/** When blocks of the build were knocked out to get out (breakOut), the last minute. */
	private final java.util.ArrayDeque<Long> breakOutTimes = new java.util.ArrayDeque<>();
	private BlockPos doorAimDoor, doorGoDoor;
	private long doorAimSince, doorGoAt;
	private double doorGoBest;

	private boolean throughDoor(LocalPlayer p, InputDriver in, AimController aim, Box b, BlockPos goal, long now) {
		if (plan == null || goal == null) return false;
		List<BlockPos> path = doorPath(p, b, goal);
		for (int i = 0; i < Math.min(24, path.size()); i++) {
			for (BlockPos d : new BlockPos[]{path.get(i), path.get(i).above()}) {
				BlockState st = Terrain.state(d);
				if (!handDoor(st)) continue;
				BlockPos lower = st.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
						== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? d.below() : d;
				// In the way: shut, or standing open across the way we go through it (from the spot before it to the
				// one after it). Neither way leaves a gap: no way through here, for now.
				BlockPos from = i > 0 ? path.get(i - 1) : BlockPos.containing(p.getX(), d.getY(), p.getZ());
				BlockPos to = i + 1 < path.size() ? path.get(i + 1) : d;
				int gx = to.getX() - from.getX(), gz = to.getZ() - from.getZ();
				boolean alongX = gx == 0 && gz == 0 ? Math.abs(d.getX() + 0.5 - p.getX()) >= Math.abs(d.getZ() + 0.5 - p.getZ())
						: Math.abs(gx) >= Math.abs(gz);
				if (doorGap(st, d, alongX) >= 0.65) continue; // a way through as it is
				if (doorGap(st.cycle(net.minecraft.world.level.block.DoorBlock.OPEN), d, alongX) < 0.65) {
					if (shutDoors.put(lower.asLong(), now + 600) == null) {
						HumanAutopilot.LOGGER.info("[schem] the door at {} is no way through going along {}, open or shut", lower.toShortString(), alongX ? "x" : "z");
					}
					airAt = -1000;
					continue;
				}
				// Like a player: from a couple of blocks in front of it, about door height, looking at its lower half
				// (from right above it the click hit the roof over the door).
				BlockPos before = i > 0 ? path.get(i - 1) : null;
				Vec3 out = before == null ? p.position().subtract(Vec3.atCenterOf(lower)).multiply(1, 0, 1)
						: new Vec3(before.getX() - path.get(i).getX(), 0, before.getZ() - path.get(i).getZ());
				out = out.lengthSqr() < 0.01 ? new Vec3(0, 0, -1) : out.normalize();
				Vec3 spot = Vec3.atBottomCenterOf(lower).add(out.scale(2.3)).add(0, 0.6, 0);
				Vec3 mid = Vec3.atCenterOf(lower);
				work(aim, mid);
				// There: close enough to the side; up and down only roughly (we hover a bit over the floor).
				if (Math.hypot(spot.x - p.getX(), spot.z - p.getZ()) > 0.7 || Math.abs(spot.y - p.getY()) > 1.4) {
					// (No nearer the spot in front of it for 3 s - stairs or a roof edge in the way: another way for 30 s.
					// Knight's Order, 03:35: 20 s "going to the door" in place, then "couldn't fly there" - the user: "stuck".)
					double dd = spot.distanceTo(p.position());
					if (doorGoDoor == null || !doorGoDoor.equals(lower)) {
						doorGoDoor = lower.immutable();
						doorGoBest = dd;
						doorGoAt = now;
					} else if (dd < doorGoBest - 0.15) {
						doorGoBest = dd;
						doorGoAt = now;
					} else if (now - doorGoAt > 60) {
						shutDoors.put(lower.asLong(), now + 600);
						airAt = -1000;
						HumanAutopilot.LOGGER.info("[schem] can't get in front of the door at {} (no nearer for 3 s, {} off): another way for 30 s",
								lower.toShortString(), String.format(java.util.Locale.ROOT, "%.1f", dd));
						doorGoDoor = null;
						return false;
					}
					precise = true;
					steer(p, in, spot, false, 0.3);
					precise = false;
					doorAimDoor = null; // (the 2 s at the door start once we're there)
					note = "going to the door";
					return true;
				}
				brake(p, in);
				// Not with shift down: a sneaking right-click puts the block in hand down instead of opening the door
				// (that was the slab in front of the door).
				in.drop(InputDriver.opts().keyShift);
				HitResult hr = clickRay(p);
				// At the spot and the crosshair never gets onto the door (stairs in front of it, or out of reach): 2 s,
				// then another way for 30 s. (Knight's Order, 03:20: 16 s hovering in front of a door, "opening the
				// door", no click - the user: "just stalled for about 15 secs".)
				if (doorAimDoor == null || !doorAimDoor.equals(lower)) {
					doorAimDoor = lower.immutable();
					doorAimSince = now;
				}
				if (now - doorAimSince > 40 && now - doorClickAt > 40) {
					shutDoors.put(lower.asLong(), now + 600);
					airAt = -1000;
					HumanAutopilot.LOGGER.info("[schem] can't click the door at {} from {} (the crosshair is on {}): another way for 30 s",
							lower.toShortString(), fmt(p.position()), hr instanceof BlockHitResult bh0 && hr.getType() == HitResult.Type.BLOCK
									? bh0.getBlockPos().toShortString() + " " + name(Terrain.state(bh0.getBlockPos()).getBlock().asItem()) : "nothing");
					doorAimDoor = null;
					return false;
				}
				if (now - doorClickAt > 12 && !p.isShiftKeyDown() && hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
						&& handDoor(Terrain.state(bh.getBlockPos())) && !entityInTheWay(p)) {
					in.click(InputDriver.opts().keyUse);
					doorClickAt = now;
					BlockState was = Terrain.state(bh.getBlockPos());
					BlockPos low = handDoor(was) && was.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? bh.getBlockPos().below() : bh.getBlockPos();
					BlockState want = plan.get(low);
					if (!st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && want != null && want.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !want.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) openedDoors.add(low);
					doorsOpened++;
					HumanAutopilot.LOGGER.info("[schem] {} the door at {}", st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)
							? "shutting (it stands open across the way)" : "opening", bh.getBlockPos().toShortString());
				}
				note = "opening the door";
				return true;
			}
		}
		return false;
	}

	/**
	 * The way to {@code goal} for the doors on it: the open-air path, or - going somewhere beyond the map round us - the
	 * start of the long way it's on (the far way never opened a door: it pushed into the gate house's shut door, took it
	 * for a wall, was "shut in", and knocked 13 blocks out of the building to get out).
	 */
	private List<BlockPos> doorPath(LocalPlayer p, Box b, BlockPos goal) {
		List<BlockPos> path = airPath(p, b, goal);
		if (path.size() > 1 || routePath == null || !lastRouteWhy.startsWith("far") || routePath.isEmpty()) return path;
		return routePath.subList(0, Math.min(routePath.size(), 30));
	}

	private long doorBusySince = -1000, doorBusyLast = -1000;

	/** Busy with a door on the way: counts as getting somewhere for the first 8 s of it. */
	private void doorProgress(long now) {
		if (now - doorBusyLast > 20) doorBusySince = now;
		doorBusyLast = now;
		if (now - doorBusySince < 160) progressAt = now;
	}

	/** Walking through a doorway (landed in front of it): the door, where we started, where we're going. */
	private BlockPos walkDoor;
	private Vec3 walkFrom, walkTo;
	private long walkSince, walkBestAt;
	/** The landing by the door came down short of its spot (stuck in the air near it): see walkThroughDoor. */
	private boolean walkLandedShort;
	private double walkBestD;

	/**
	 * A doorway is too low to fly through (2 blocks, and we hover over the ground): like a player, land in front of
	 * it, walk through (hopping up a step if there is one) and take off again on the other side. True while doing it.
	 */
	private boolean walkThroughDoor(LocalPlayer p, InputDriver in, AimController aim, Box b, BlockPos goal, long now) {
		if (plan == null) return false;
		if (walkDoor == null) {
			if (goal == null) return false;
			List<BlockPos> path = doorPath(p, b, goal);
			for (int i = 1; i + 1 < Math.min(24, path.size()); i++) {
				BlockPos c = path.get(i);
				BlockPos door = handDoor(Terrain.state(c)) ? c : handDoor(Terrain.state(c.above())) ? c.above() : null;
				if (door == null) continue;
				BlockState st = Terrain.state(door);
				BlockPos lower = st.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
						== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? door.below() : door;
				if (Math.hypot(lower.getX() + 0.5 - p.getX(), lower.getZ() + 0.5 - p.getZ()) > 8) return false; // closer first
				// About level with it (a door two floors down is the path's business first: he landed on the floor over it
				// and "walked through" it six times a second, already right over it).
				if (Math.abs(lower.getY() - p.getY()) > 2.5) return false;
				BlockPos before = path.get(i - 1), after = path.get(i + 1);
				int sx = Integer.signum(after.getX() - before.getX()), sz = Integer.signum(after.getZ() - before.getZ());
				if (sx != 0 && sz != 0) sz = 0;
				if (sx == 0 && sz == 0) return false;
				if (doorGap(st, door, sx != 0) < 0.65) return false; // shut, or open across our way: throughDoor sees to it first
				// The open door stands along one side of the doorway: walk through the middle of what's left (dead
				// centre our shoulder caught on it).
				double off = 0;
				var shape = st.getCollisionShape(Terrain.level(), door);
				if (!shape.isEmpty()) {
					var bb = shape.bounds();
					double lo = sx != 0 ? bb.minZ : bb.minX, hi = sx != 0 ? bb.maxZ : bb.maxX;
					double free = lo > 1 - hi ? lo / 2 : (hi + 1) / 2; // middle of the wider free part
					off = free - 0.5;
				}
				double ox = sx != 0 ? 0 : off, oz = sx != 0 ? off : 0;
				// Only with ground to land on right in front of it (a step down at most): a door hanging over a TNT
				// pit isn't one to walk through (it landed at the bottom of the pit).
				BlockPos front = lower.offset(-sx, 0, -sz);
				if (!solid(front.below()) && !solid(front.below(2))) return false;
				// And where we come down, 1.6 out: ground there too, or just the doorstep (1.1 out). The cottage on a cliff's
				// edge: a doorstep, then the drop - he came down 1.6 out and on down 45 blocks to the foot of the cliff, and
				// flew back up.
				// Back and forth through the same doors (two side by side, the way along them: through one, then back through the
				// other, 16 times in 25 s - Knight's Order, 04:00): 4 walks through doors here in 15 s, and those doors are
				// no way for a minute.
				doorWalks.removeIf(w -> now - w[0] > 300);
				int here = 0;
				for (long[] w : doorWalks) if (BlockPos.of(w[1]).distSqr(lower) <= 16) here++;
				if (here >= 3) {
					List<BlockPos> those = new ArrayList<>();
					for (long[] w : doorWalks) if (BlockPos.of(w[1]).distSqr(lower) <= 16) those.add(BlockPos.of(w[1]));
					those.add(lower);
					for (BlockPos q : those) shutDoors.put(q.asLong(), now + 1200);
					doorWalks.clear();
					airAt = -1000;
					HumanAutopilot.LOGGER.info("[schem] back and forth through the doors at {}: no way through them for a minute",
							those.stream().map(BlockPos::toShortString).distinct().toList());
					return false;
				}
				doorWalks.addLast(new long[]{now, lower.asLong()});
				double outD = 1.6;
				BlockPos land = BlockPos.containing(lower.getX() + 0.5 + ox - sx * outD, lower.getY(), lower.getZ() + 0.5 + oz - sz * outD);
				if (!solid(land.below()) && !solid(land.below(2))) outD = 1.1;
				// (Only now: set before that check, a door over a gap left walkDoor set with no walkTo, and the next tick
				// crashed the game - the special medieval house, NullPointerException on walkTo.)
				walkDoor = lower;
				walkFrom = new Vec3(lower.getX() + 0.5 + ox - sx * outD, lower.getY(), lower.getZ() + 0.5 + oz - sz * outD);
				walkTo = new Vec3(lower.getX() + 0.5 + ox + sx * 1.2, lower.getY(), lower.getZ() + 0.5 + oz + sz * 1.2);
				walkSince = now;
				walkBestD = Double.MAX_VALUE;
				walkBestAt = now;
				walkLandedShort = false;
				HumanAutopilot.LOGGER.info("[schem] walking through the door at {}", lower.toShortString());
				break;
			}
			if (walkDoor == null) return false;
		}
		if (walkTo == null || walkFrom == null) {
			walkDoor = null;
			return false;
		}
		Vec3 pos = p.position();
		boolean through = Math.hypot(walkTo.x - pos.x, walkTo.z - pos.z) < 0.45 && Math.abs(pos.y - walkDoor.getY()) < 1.5;
		if (through || now - walkSince > 160 || !handDoor(Terrain.state(walkDoor))) {
			if (!through && handDoor(Terrain.state(walkDoor))) {
				// Stuck in it: no way through here for a while (trying it again straight away, over and over, was the
				// 83 s at the hospital's door). The way round, or another block meanwhile.
				shutDoors.put(walkDoor.asLong(), now + 600);
				airAt = -1000;
				HumanAutopilot.LOGGER.info("[schem] gave up walking through the door at {}: no way through it for 30 s", walkDoor.toShortString());
			}
			walkDoor = null; // flying takes over again (it takes off from here)
			walkFrom = walkTo = null;
			return false;
		}
		var o = InputDriver.opts();
		// Eyes ahead, through the doorway, at head height (like walking in).
		work(aim, new Vec3(walkTo.x + (walkTo.x - walkFrom.x) * 0.5, walkDoor.getY() + 1.4, walkTo.z + (walkTo.z - walkFrom.z) * 0.5));
		// Gone down past the door's foot (nothing to land on in front of it after all): not this way - back up, round.
		if (pos.y < walkDoor.getY() - 1.3 && p.getDeltaMovement().y < -0.05) {
			shutDoors.put(walkDoor.asLong(), now + 600);
			airAt = -1000;
			HumanAutopilot.LOGGER.info("[schem] no ground to land on in front of the door at {} (down at {}): not that way for 30 s",
					walkDoor.toShortString(), fmt(pos));
			walkDoor = null;
			walkFrom = walkTo = null;
			return false;
		}
		if (p.getAbilities().flying) {
			// Line up in front of the doorway and come down onto the ground there (landing ends flight).
			Vec3 d = walkFrom.subtract(pos);
			double yaw = Math.toRadians(p.getYRot());
			double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
			Vec3 v = p.getDeltaMovement();
			precise = true;
			axis(in, d.x * fx + d.z * fz, v.x * fx + v.z * fz, o.keyUp, o.keyDown, false, 0.15);
			axis(in, d.x * rx + d.z * rz, v.x * rx + v.z * rz, o.keyRight, o.keyLeft, false, 0.15);
			precise = false;
			double flat = Math.hypot(d.x, d.z);
			if (flat < walkBestD - 0.1) {
				walkBestD = flat;
				walkBestAt = now;
			}
			// (Not getting any nearer the spot in front of it - a fence post, the open door in the way - and the ground's
			// right under us: down here, the rest on foot. The fantasy house: 5 s hovering a block off it, then gave up.)
			// (Up to 3 down, onto a floor about the door's own height: 1.2 was too little - Knight's Order, 03:31, stairs
			// over the spot in front of the door, the floor 1.7 under him: 8 s hovering, then "gave up walking through".)
			double gNear = groundTop(pos.y + 0.2, pos.x, pos.z);
			boolean groundNear = !Double.isNaN(gNear) && pos.y - gNear < 3 && gNear >= walkDoor.getY() - 1.0;
			boolean stuckNear = now - walkBestAt > 30 && flat < 2.5 && groundNear;
			if (now - walkBestAt > 60 && !groundNear) {
				shutDoors.put(walkDoor.asLong(), now + 600);
				airAt = -1000;
				HumanAutopilot.LOGGER.info("[schem] can't get in front of the door at {} (no nearer for 3 s, no floor to come down on): not that way for 30 s",
						walkDoor.toShortString());
				walkDoor = null;
				walkFrom = walkTo = null;
				return false;
			}
			if (flat < 0.6 || stuckNear) {
				in.hold(o.keyShift);
				lastVert = "door landing";
				lastVertAt = now;
				if (flat >= 0.6) walkLandedShort = true;
			}
			note = "landing by the door";
			return true;
		}
		// On our feet: walk straight through, hopping up a step into the doorway if there is one.
		// (Set down somewhere else, off to the side - 4 blocks off, after a set-down - to the front of the doorway first:
		// straight at the far side from there walked him into the fence post beside the door, wedged for 40 s.)
		double ax = walkTo.x - walkFrom.x, az = walkTo.z - walkFrom.z, len2 = ax * ax + az * az;
		double along = len2 < 1e-6 ? 1 : ((pos.x - walkFrom.x) * ax + (pos.z - walkFrom.z) * az) / len2;
		if (along < 0.2 && Math.hypot(walkFrom.x - pos.x, walkFrom.z - pos.z) > 0.6) {
			// (Not on foot where the way to it isn't clear: the fence post beside the cottage's door stopped the walk
			// there for 8 s. Up and over instead: the take-off comes next, and in the air the landing by the door.)
			if (!walkable(pos, new Vec3(walkFrom.x, pos.y, walkFrom.z))) {
				// (Came down short of it because the air way there was blocked too: then it's not this way. Up and back to
				// the landing again was a loop - the awkward cottage, 14 landings and take-offs in 6 s by its door, with
				// temporary blocks by the doorway.)
				if (walkLandedShort) {
					shutDoors.put(walkDoor.asLong(), now + 600);
					airAt = -1000;
					HumanAutopilot.LOGGER.info("[schem] gave up walking through the door at {}: can't get in front of it, flying or on foot",
							walkDoor.toShortString());
					walkDoor = null;
					walkFrom = walkTo = null;
					return false;
				}
				walkUntil = now - 1;
				takeOffNowUntil = now + 20; // (not kept on foot by footWork meanwhile)
				return false;
			}
			Vec3 d0 = walkFrom.subtract(pos);
			double yaw0 = Math.toRadians(p.getYRot());
			double fx0 = -Math.sin(yaw0), fz0 = Math.cos(yaw0), rx0 = -Math.cos(yaw0), rz0 = -Math.sin(yaw0);
			Vec3 v0 = p.getDeltaMovement();
			axis(in, d0.x * fx0 + d0.z * fz0, v0.x * fx0 + v0.z * fz0, o.keyUp, o.keyDown, false, 0.2);
			axis(in, d0.x * rx0 + d0.z * rz0, v0.x * rx0 + v0.z * rz0, o.keyRight, o.keyLeft, false, 0.2);
			if (p.horizontalCollision && p.onGround() && stepAhead(p, d0)) jump(in);
			note = "walking to the door";
			return true;
		}
		Vec3 d = walkTo.subtract(pos);
		double yaw = Math.toRadians(p.getYRot());
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double side = d.x * rx + d.z * rz;
		in.hold(o.keyUp);
		if (side > 0.06) in.hold(o.keyRight);
		else if (side < -0.06) in.hold(o.keyLeft);
		// (Hop up a step into the doorway - not against the frame or the door itself: bumping it, he hopped in the
		// doorway again and again, "bunny hopping".)
		if (p.horizontalCollision && p.onGround() && stepAhead(p, d)) jump(in);
		note = "walking through the door";
		return true;
	}

	/** Done round here: shut the door we opened, like a player would (if it's in reach; else leave it). */
	// ------------------------------------------------------------------ nether portals

	/**
	 * The build's nether portals: no item puts a portal block in; a player lights the finished obsidian frame with flint
	 * and steel and the whole portal fills (the decorated nether portal: 780 portal blocks in 4 frames). Once the rest is
	 * done, each frame that's whole and not lit: from in front of it, flint and steel on the inside of its bottom.
	 */
	private List<List<BlockPos>> portals;
	private final Map<BlockPos, Long> portalWait = new HashMap<>(); // a portal (its first cell) left until then
	private final java.util.Set<BlockPos> portalClear = new java.util.HashSet<>(); // its opening's cells on the clearing list
	private int portalTries;
	private long portalClickAt = -1, portalClearSince = -1000;
	private BlockPos portalFor;
	private long portalAimSince = -1;
	private boolean portalFlip;

	private String lightPortals(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		if (portals == null) portals = portalGroups(dev.humanautopilot.schematic.SchematicMode.get().portalCells());
		List<BlockPos> todo = null;
		for (List<BlockPos> g : portals) {
			BlockPos first = g.get(0);
			if (portalWait.getOrDefault(first, -1L) > now || !Terrain.loaded(first.getX(), first.getZ())) continue;
			if (Terrain.state(first).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue; // lit
			todo = g;
			break;
		}
		if (todo == null) return null;
		BlockPos first = todo.get(0);
		if (!first.equals(portalFor)) {
			portalFor = first;
			portalTries = 0;
			portalClickAt = -1;
		}
		// The frame: every side of the opening, in its own plane, obsidian; the opening empty.
		boolean alongX = todo.stream().allMatch(c -> c.getZ() == first.getZ());
		Direction[] inPlane = alongX ? new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
				: new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
		java.util.Set<BlockPos> cells = new java.util.HashSet<>(todo);
		// Something in the opening (a tree's leaves where the gothic portal went, its portal never lit): on the clearing
		// list, knocked out, and then the portal. (Taken off the list again once empty: lit, it isn't "air".)
		List<BlockPos> inIt = new ArrayList<>();
		for (BlockPos c : todo) {
			BlockState w = Terrain.state(c);
			if (!w.isAir() && !w.is(net.minecraft.world.level.block.Blocks.FIRE)) inIt.add(c);
		}
		if (!inIt.isEmpty()) {
			int added = 0;
			boolean listed = false;
			for (BlockPos c : inIt) {
				BlockState w = Terrain.state(c);
				if (portalClear.contains(c)) {
					listed = true;
					continue;
				}
				if (plan.containsKey(c) || !Placement.breakable(Terrain.level(), c, w)) continue;
				plan.put(c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
				planLayers.computeIfAbsent(c.getY(), k -> new ArrayList<>()).add(c);
				planList.add(c);
				portalClear.add(c);
				remember(c, Placement.Job.BREAK);
				scanFromY = Math.min(scanFromY, c.getY());
				added++;
			}
			if (added > 0) {
				HumanAutopilot.LOGGER.info("[schem] the portal at {} has {} in it: knocking that out first ({} spots)", first.toShortString(),
						Terrain.state(inIt.get(0)).getBlock().getName().getString(), added);
				portalWait.remove(first);
				portalClearSince = now;
				scannedAt = -1000; // (the next scan has them as holes)
			}
			// On the list: a scan away from being holes to knock out. Not "done" in the meantime (the gothic portal said
			// "Done building" the very tick the leaves in its opening went on the list, and was never lit). Not for ever.
			if ((added > 0 || listed) && now - portalClearSince < 200) {
				brake(p, in);
				return "clearing the portal's opening";
			}
			return leavePortal(first, now, "something's in it at " + inIt.get(0).toShortString());
		}
		for (BlockPos c : todo) {
			BlockState w = Terrain.state(c);
			if (!w.isAir() && !w.is(net.minecraft.world.level.block.Blocks.FIRE)) return leavePortal(first, now, "something's in it at " + c.toShortString());
			for (Direction d : inPlane) {
				BlockPos n = c.relative(d);
				if (!cells.contains(n) && !Terrain.state(n).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) {
					return leavePortal(first, now, "its frame isn't whole at " + n.toShortString());
				}
			}
		}
		// Lit by the last click?
		if (portalClickAt >= 0 && now - portalClickAt < 15) {
			brake(p, in);
			return "lighting the portal";
		}
		if (portalClickAt >= 0) {
			portalClickAt = -1;
			if (++portalTries >= 3) return leavePortal(first, now, "it didn't light");
		}
		// The bottom row's middle; from in front of it (the side away from the build's middle, if there's room), eyes 2
		// over its floor, 2.5 out.
		List<BlockPos> bottom = new ArrayList<>();
		for (BlockPos c : todo) if (!cells.contains(c.below())) bottom.add(c);
		bottom.sort(java.util.Comparator.comparingInt(c -> alongX ? c.getX() : c.getZ()));
		BlockPos cell = bottom.get(bottom.size() / 2);
		Vec3 pt = new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5); // the top of the obsidian under it
		Direction out = alongX ? Direction.SOUTH : Direction.EAST;
		double midOff = alongX ? cell.getZ() + 0.5 - (planBox.minZ() + planBox.maxZ() + 1) / 2.0 : cell.getX() + 0.5 - (planBox.minX() + planBox.maxX() + 1) / 2.0;
		if (midOff < 0) out = out.getOpposite();
		Vec3 eye = null;
		if (portalFlip) out = out.getOpposite(); // (no click from the first side: the other one)
		// A spot 2-4 out in front, in the middle of a cell (the body has to fit: 2.5 out put it in the gothic portal's fence
		// screen, out of reach, "going to light the portal" for the whole test), eyes at our usual hover over the floor
		// there (2 over the obsidian was lower than he ever hovers), the obsidian in sight from it (not through the fences).
		pick:
		for (Direction o : new Direction[]{out, out.getOpposite()}) {
			for (int k = 2; k <= 4; k++) {
				BlockPos at = cell.relative(o, k);
				if (!flyable(at.getX(), at.getY(), at.getZ())) continue;
				Vec3 e = new Vec3(at.getX() + 0.5, at.getY() + hov(at) + p.getEyeHeight(), at.getZ() + 0.5);
				if (!roomForEye(e) || !clearTo(p, e, pt.subtract(0, 0.05, 0), cell.below())) continue;
				eye = e;
				break pick;
			}
		}
		if (eye == null) return leavePortal(first, now, "no room in front of it");
		// Flint and steel in hand.
		int slot = Inv.hotbarSlot(p, st -> st.is(net.minecraft.world.item.Items.FLINT_AND_STEEL));
		if (slot < 0) {
			if (CreativePicker.get().failed(net.minecraft.world.item.Items.FLINT_AND_STEEL)) return leavePortal(first, now, "no flint and steel");
			brake(p, in);
			return CreativePicker.get().fetch(p, in, net.minecraft.world.item.Items.FLINT_AND_STEEL);
		}
		Vec3 goal = eye.subtract(0, p.getEyeHeight(), 0);
		double dist = p.position().distanceTo(goal);
		work(aim, pt);
		// (There: within 0.6 sideways and a block up or down - the height is the hover's business, and it never settles to
		// the centimetre.)
		if (Math.hypot(goal.x - p.getX(), goal.z - p.getZ()) > 0.6 || Math.abs(goal.y - p.getY()) > 1.0) {
			portalAimSince = -1;
			// (On foot - set down somewhere on the way - in the air first: walking only, he stood against a step 3.6 off the
			// gothic portal for the last minute of the test. This runs before the usual take-off.)
			if (!p.getAbilities().flying && p.getAbilities().mayfly) {
				takeOff(p, in);
				return "taking off";
			}
			if (now % 100 == 0) HumanAutopilot.LOGGER.info("[schem] going to light the portal at {}", cell.toShortString());
			steer(p, in, route(p, b, goal), false, dist < 1.5 ? 0.3 : 0.9);
			if (dist > 4) in.hold(InputDriver.opts().keySprint);
			air(p, b);
			return "going to light the portal";
		}
		brake(p, in);
		if (!in.selectHotbar(slot)) return "taking out flint and steel";
		if (portalAimSince < 0) portalAimSince = now;
		HitResult hr = clickRay(p);
		// (Any inner face of the frame will do: the fire goes in the opening and lights it. Only the bottom block's top
		// counted, and with the gothic portal's fences and lanterns in front he stood there to the end without a click.)
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && now >= nextClickAt
				&& cells.contains(bh.getBlockPos().relative(bh.getDirection()))
				&& Terrain.state(bh.getBlockPos()).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) {
			in.click(InputDriver.opts().keyUse);
			portalClickAt = now;
			portalAimSince = -1;
			nextClickAt = now + 10;
			HumanAutopilot.LOGGER.info("[schem] lighting the portal at {} ({} blocks)", cell.toShortString(), todo.size());
		} else if (now - portalAimSince > 100) {
			// 5 s there and no click: something in front of it. From the other side.
			portalAimSince = -1;
			portalFlip = !portalFlip;
			HumanAutopilot.LOGGER.info("[schem] no clear look at the portal at {} from here: the other side", cell.toShortString());
		}
		return "lighting the portal";
	}

	private String leavePortal(BlockPos first, long now, String why) {
		if (portalWait.getOrDefault(first, -1L) < now) HumanAutopilot.LOGGER.info("[schem] can't light the portal at {} for now: {}", first.toShortString(), why);
		portalWait.put(first, now + 1200);
		return null;
	}

	/** The portal cells in connected pieces (one per frame). */
	private static List<List<BlockPos>> portalGroups(List<BlockPos> all) {
		java.util.Set<BlockPos> left = new java.util.HashSet<>(all);
		List<List<BlockPos>> out = new ArrayList<>();
		while (!left.isEmpty()) {
			BlockPos s0 = left.iterator().next();
			left.remove(s0);
			List<BlockPos> g = new ArrayList<>();
			java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
			q.add(s0);
			while (!q.isEmpty()) {
				BlockPos c = q.poll();
				g.add(c);
				for (Direction d : Direction.values()) {
					BlockPos n = c.relative(d);
					if (left.remove(n)) q.add(n);
				}
			}
			out.add(g);
		}
		return out;
	}

	private boolean closeDoor(LocalPlayer p, InputDriver in, AimController aim, Box b, long now) {
		// (Shut already, gone, or one the schematic has standing open: off the list.)
		openedDoors.removeIf(d -> {
			BlockState st = Terrain.state(d), want = plan.get(d);
			return !handDoor(st) || !st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) || want == null || !(want.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
					|| want.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
		});
		if (openedDoors.isEmpty()) return false;
		BlockPos door = null;
		for (BlockPos d : openedDoors) if (door == null || d.distSqr(p.blockPosition()) < door.distSqr(p.blockPosition())) door = d;
		// (Not for ever: the cottage's last minute was "closing the door", the crosshair never on it.)
		if (!door.equals(closingDoor)) {
			closingDoor = door;
			closingSince = now;
		} else if (now - closingSince > 300) {
			HumanAutopilot.LOGGER.info("[schem] couldn't shut the door at {}: leaving it open", door.toShortString());
			openedDoors.remove(door);
			closingDoor = null;
			return false;
		}
		Vec3 mid = Vec3.atCenterOf(door);
		double dist = p.getEyePosition().distanceTo(mid);
		if (p.getBoundingBox().inflate(0.3).intersects(new AABB(door).expandTowards(0, 1, 0))) {
			// Standing in the doorway: out of it first (the way we're facing away from the door's middle).
			Vec3 away = p.position().subtract(mid).multiply(1, 0, 1);
			away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
			steer(p, in, p.position().add(away.scale(1.5)), false, 0.3);
			note = "stepping out of the doorway";
			return true;
		}
		if (dist > p.blockInteractionRange() - 0.6) {
			// Back to it (the last thing to do: like a player shutting the door on the way out).
			Vec3 off = p.position().subtract(mid).multiply(1, 0, 1);
			off = off.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : off.normalize();
			Vec3 spot = Vec3.atBottomCenterOf(door).add(off.scale(2.3)).add(0, 0.6, 0);
			steer(p, in, route(p, b, spot), false, 0.9);
			work(aim, mid);
			note = "going to shut the door";
			return true;
		}
		brake(p, in);
		in.drop(InputDriver.opts().keyShift);
		work(aim, mid);
		HitResult hr = clickRay(p);
		if (now - doorClickAt > 12 && !p.isShiftKeyDown() && hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
				&& handDoor(Terrain.state(bh.getBlockPos())) && !entityInTheWay(p)) {
			in.click(InputDriver.opts().keyUse);
			doorClickAt = now;
			HumanAutopilot.LOGGER.info("[schem] shutting the door at {} (opened it to get through)", door.toShortString());
		}
		note = "closing the door";
		return true;
	}

	private static boolean inArea(Box b, int x, int y, int z) {
		if (plan != null && (Math.abs(x - regX) > REG_H || Math.abs(z - regZ) > REG_H || Math.abs(y - regY) > REG_V)) return false;
		return inBox(b, x, y, z);
	}

	/** In the build's box (and the margin round it), wherever we are: inArea without the map round us (a far spot's look). */
	private static boolean inBox(Box b, int x, int y, int z) {
		return x >= b.minX() - 2 && x <= b.maxX() + 2 && z >= b.minZ() - 2 && z <= b.maxZ() + 2
				&& y >= b.minY() && y <= b.maxY() + 3;
	}

	private Map<BlockPos, BlockPos> air(LocalPlayer p, Box b) {
		long c0 = costStart();
		try {
			return air0(p, b);
		} finally {
			costEnd("air", c0);
		}
	}

	private Map<BlockPos, BlockPos> air0(LocalPlayer p, Box b) {
		long now = HumanAutopilot.ticks();
		BlockPos start = bodyCell(p);
		if (now - airAt < 10 && (start.equals(airStart) || (plan != null && start.distManhattan(airStart) < 3))) return airFrom;
		airAt = now;
		airStart = start;
		airFrom.clear();
		if (plan != null) {
			regX = start.getX();
			regY = start.getY();
			regZ = start.getZ();
		}
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		// From us first, then from the open sky over the box. (Which cells came from us: if that part never meets the
		// part from the sky, we're shut in, and only what's in here can be got at.)
		it.unimi.dsi.fastutil.longs.LongOpenHashSet mine = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (BlockPos s0 : new BlockPos[]{start, start.below()}) {
			if (inArea(b, s0.getX(), s0.getY(), s0.getZ()) && flyable(s0.getX(), s0.getY(), s0.getZ())
					&& airFrom.putIfAbsent(s0, s0) == null) {
				q.add(s0);
				mine.add(s0.asLong());
			}
		}
		boolean skySeeded = false, linked = false;
		int sky = plan != null ? Math.min(b.maxY() + 2, regY + REG_V) : b.maxY() + 2;
		int x0 = b.minX() - 2, x1 = b.maxX() + 2, z0 = b.minZ() - 2, z1 = b.maxZ() + 2;
		if (plan != null) {
			x0 = Math.max(x0, regX - REG_H);
			x1 = Math.min(x1, regX + REG_H);
			z0 = Math.max(z0, regZ - REG_H);
			z1 = Math.min(z1, regZ + REG_H);
		}
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				BlockPos s0 = new BlockPos(x, sky, z);
				if (flyable(x, sky, z)) {
					skySeeded = true;
					if (airFrom.putIfAbsent(s0, s0) == null) q.add(s0);
					else if (mine.contains(s0.asLong())) linked = true; // (we're up at the sky ourselves)
				}
			}
		}
		while (!q.isEmpty() && airFrom.size() < (plan != null ? 40000 : 12000)) {
			BlockPos c = q.poll();
			boolean cm = mine.contains(c.asLong());
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				BlockPos was = airFrom.get(n);
				if (was != null) {
					if (!linked && cm != mine.contains(n.asLong())) linked = true;
					continue;
				}
				if (!inArea(b, n.getX(), n.getY(), n.getZ()) || !flyable(n.getX(), n.getY(), n.getZ())) continue;
				airFrom.put(n, c);
				if (cm) mine.add(n.asLong());
				q.add(n);
			}
		}
		// Shut in (our part never met the open air from the sky: trapdoors and glass all round us in the castle's tower
		// corner): only what's in here counts as reachable. Holes outside it looked reachable, he picked them one after
		// another, "couldn't fly there" each time, for two minutes, and never knocked his way out.
		boolean shut = plan != null && !linked && skySeeded && !mine.isEmpty() && q.isEmpty();
		if (shut) airFrom.keySet().removeIf(c -> !mine.contains(c.asLong()));
		if (shut && !airShutIn && now - shutLogAt > 200) {
			shutLogAt = now;
			HumanAutopilot.LOGGER.info("[box] shut in at {} ({} open cells in here): only what's in here till we're out", start.toShortString(), airFrom.size());
		}
		airShutIn = shut;
		return airFrom;
	}

	/** The open-air map's own part (from us) never met the open air from the sky: we're shut in. */
	private boolean airShutIn;
	private long shutLogAt = -1000;

	/** The way to {@code goal} through open air, from us (or the sky): the cells in order, or empty. */
	/**
	 * The open cells we can get to from here without going through a doorway: the room we're in (worked out at most twice
	 * a second, or when we've moved to another cell). Null if there are more than 6000 of them (out in the open: no room
	 * to speak of).
	 */
	private java.util.Set<BlockPos> roomCells(LocalPlayer p, Box b, long now) {
		BlockPos me = bodyCell(p);
		if (now - roomAt < 10 && me.equals(roomFrom)) return roomSet;
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		for (BlockPos s0 : new BlockPos[]{me, me.below()}) {
			if (flyable(s0.getX(), s0.getY(), s0.getZ()) && seen.add(s0)) q.add(s0);
		}
		while (!q.isEmpty() && seen.size() < 6000) {
			BlockPos c = q.poll();
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (seen.contains(n) || !inArea(b, n.getX(), n.getY(), n.getZ()) || !flyable(n.getX(), n.getY(), n.getZ())) continue;
				if (handDoor(Terrain.state(n)) || handDoor(Terrain.state(n.above()))) continue; // (a doorway: the next room)
				seen.add(n);
				q.add(n);
			}
		}
		roomSet = q.isEmpty() ? seen : null;
		roomAt = now;
		roomFrom = me;
		return seen;
	}

	/**
	 * {@link #inRoom}, except a block that goes in against its temporary block: from the side away from that, so it's
	 * this room's only if that side is. (A door's frame, its temporary blocks inside the doorway's wall: each counted as
	 * in here - it's right beside us - so after its glass it was out through the door to click it, back in for the next
	 * glass, out... 8 doorways in 32 s on the awkward cottage. Now the glass all goes in, then the frame from outside.)
	 */
	private boolean inRoomFor(java.util.Set<BlockPos> room, BlockPos h) {
		if (!temps.isEmpty()) {
			boolean any = false;
			for (var e : temps.entrySet()) {
				if (!e.getValue().equals(h)) continue;
				BlockPos q = e.getKey();
				if (q.distManhattan(h) != 1) continue; // (one holding another up, further off)
				any = true;
				BlockPos o = h.offset(h.getX() - q.getX(), h.getY() - q.getY(), h.getZ() - q.getZ());
				if (room.contains(o) || room.contains(o.below())) return true;
			}
			if (any) return false;
		}
		return inRoom(room, h);
	}

	/** A spot of the build in the room (the cells we can get to without a doorway): in it, or right beside one. */
	private static boolean inRoom(java.util.Set<BlockPos> room, BlockPos h) {
		if (room.contains(h) || room.contains(h.below())) return true;
		for (Direction d : Direction.values()) {
			BlockPos n = h.relative(d);
			if (room.contains(n) || room.contains(n.below())) return true;
		}
		return false;
	}

	private java.util.Set<BlockPos> roomSet;
	private long roomAt = -100;
	private BlockPos roomFrom;

	/** For the log: the blocks our box would run into a little further on towards {@code to} (what the map didn't know). */
	private static String inTheWay(LocalPlayer p, Vec3 here, Vec3 to) {
		Vec3 dir = to.subtract(here);
		if (dir.lengthSqr() < 1e-6) return "-";
		// (Each way on its own too: off the middle of a 1-wide shaft, the box going straight down catches the edge of the
		// block beside it, while the slant towards the middle is free - "nothing solid" told nothing.)
		String side = hitBy(p, p.getBoundingBox().move(new Vec3(dir.x, 0, dir.z).lengthSqr() < 1e-6 ? Vec3.ZERO : new Vec3(dir.x, 0, dir.z).normalize().scale(0.3)));
		String vert = hitBy(p, p.getBoundingBox().move(0, Math.signum(dir.y) * 0.3, 0));
		String both = hitBy(p, p.getBoundingBox().move(dir.normalize().scale(0.3)));
		return "straight " + both + "; sideways " + side + "; " + (dir.y < 0 ? "down " : "up ") + vert
				+ String.format(java.util.Locale.ROOT, "; off the middle %.2f %.2f", here.x - (Math.floor(here.x) + 0.5), here.z - (Math.floor(here.z) + 0.5));
	}

	private static String hitBy(LocalPlayer p, AABB moved) {
		StringBuilder hit = new StringBuilder();
		for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(moved.minX, moved.minY, moved.minZ),
				BlockPos.containing(moved.maxX, moved.maxY, moved.maxZ))) {
			BlockState qs = Terrain.state(q);
			var sh = qs.getCollisionShape(Terrain.level(), q);
			if (sh.isEmpty()) continue;
			for (AABB bb : sh.toAabbs()) {
				if (bb.move(q).intersects(moved)) {
					hit.append(q.toShortString()).append(' ').append(qs.getBlock().getName().getString()).append(", ");
					break;
				}
			}
		}
		return hit.length() == 0 ? "free" : hit.toString();
	}

	private List<BlockPos> airPath(LocalPlayer p, Box b, BlockPos goal) {
		BlockPos me = bodyCell(p);
		// Under a roof (in a house we walked into): the way from US, out through the door. The shared map also grows
		// from the sky, and its way to a spot outside came down from the sky, never through the door.
		if (plan != null && !skyOpen(b, me.getX(), me.getY(), me.getZ())) return pathFromMe(p, b, goal);
		Map<BlockPos, BlockPos> m = air(p, b);
		if (!m.containsKey(goal)) {
			if (m.containsKey(goal.below())) goal = goal.below();
			else return List.of();
		}
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos c = goal; c != null && path.size() < 400; ) {
			path.add(c);
			BlockPos from = m.get(c);
			if (from == null || from.equals(c)) break;
			c = from;
		}
		java.util.Collections.reverse(path);
		// A way that starts up in the sky isn't a way from here: the one from us. (Outside the tower's wall, the way
		// in through the hatch came down from the sky: he pushed against the wall towards it for a minute and a half.)
		if (plan != null && !path.isEmpty() && airStart != null && !path.get(0).equals(airStart) && !path.get(0).equals(airStart.below())) {
			return pathFromMe(p, b, goal);
		}
		return path;
	}

	private List<BlockPos> pathFromMe(LocalPlayer p, Box b, BlockPos goal) {
		// (The same way for half a second, while we're still on it: worked out every tick it was a hitch.)
		long now = HumanAutopilot.ticks();
		BlockPos me0 = bodyCell(p);
		if (goal.equals(pfmGoal) && now - pfmAt < 10 && !pfmPath.isEmpty() && pfmPath.get(0).distManhattan(me0) <= 3) return pfmPath;
		List<BlockPos> path = pathFromMe0(p, b, goal);
		pfmGoal = goal;
		pfmAt = now;
		pfmPath = path;
		return path;
	}

	private BlockPos pfmGoal;
	private long pfmAt = -100;
	private List<BlockPos> pfmPath = List.of();

	private List<BlockPos> pathFromMe0(LocalPlayer p, Box b, BlockPos goal) {
		BlockPos me = bodyCell(p);
		Map<BlockPos, BlockPos> from = new HashMap<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		for (BlockPos s0 : new BlockPos[]{me, me.below()}) {
			if (flyable(s0.getX(), s0.getY(), s0.getZ()) && from.putIfAbsent(s0, s0) == null) q.add(s0);
		}
		BlockPos end = null;
		while (!q.isEmpty() && from.size() < 20000) {
			BlockPos c = q.poll();
			if (c.equals(goal) || c.equals(goal.below())) {
				end = c;
				break;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (from.containsKey(n) || !inArea(b, n.getX(), n.getY(), n.getZ()) || !flyable(n.getX(), n.getY(), n.getZ())) continue;
				from.put(n, c);
				q.add(n);
			}
		}
		if (end == null) return List.of();
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos c = end; c != null && path.size() < 400; ) {
			path.add(c);
			BlockPos f = from.get(c);
			if (f == null || f.equals(c)) break;
			c = f;
		}
		java.util.Collections.reverse(path);
		return path;
	}

	/** There's open air we can fly to right next to (or in) this hole. */
	private static boolean gettable(BlockPos h, Map<BlockPos, BlockPos> reach) {
		if (reach.containsKey(h) || reach.containsKey(h.above()) || reach.containsKey(h.below())) return true;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = h.relative(d);
			if (reach.containsKey(n) || reach.containsKey(n.below()) || reach.containsKey(n.above())) return true;
		}
		return false;
	}

	/** Under a roof: the open-sky cell nearest us through open air (the hollow's way out), else null. */
	private BlockPos exitCell(LocalPlayer p, Box b) {
		BlockPos me = bodyCell(p);
		if (!b.inside(me.getX(), me.getY(), me.getZ()) || skyOpen(b, me.getX(), me.getY(), me.getZ())) return null;
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		BlockPos start = flyable(me.getX(), me.getY(), me.getZ()) ? me : me.below();
		q.add(start);
		seen.add(start);
		while (!q.isEmpty() && seen.size() < 8000) {
			BlockPos c = q.poll();
			if (skyOpen(b, c.getX(), c.getY(), c.getZ())) return c;
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (seen.contains(n) || !inArea(b, n.getX(), n.getY(), n.getZ()) || !flyable(n.getX(), n.getY(), n.getZ())) continue;
				seen.add(n);
				q.add(n);
			}
		}
		return null;
	}

	/** Under a roof: the next point on the way out to the open sky (through open air), else null. */
	private Vec3 wayOut(LocalPlayer p, Box b) {
		BlockPos me = bodyCell(p);
		if (!b.inside(me.getX(), me.getY(), me.getZ()) || skyOpen(b, me.getX(), me.getY(), me.getZ())) return null;
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		BlockPos start = startCell(me);
		if (start == null) return null;
		q.add(start);
		from.put(start, start);
		BlockPos exit = null;
		while (!q.isEmpty() && from.size() < 8000 && exit == null) {
			BlockPos c = q.poll();
			if (skyOpen(b, c.getX(), c.getY(), c.getZ())) {
				exit = c;
				break;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (from.containsKey(n) || !inArea(b, n.getX(), n.getY(), n.getZ()) || !flyable(n.getX(), n.getY(), n.getZ())) continue;
				from.put(n, c);
				q.add(n);
			}
		}
		if (exit == null) {
			// The open sky further off than the map round us (under a statue's body): the long way out.
			List<BlockPos> out = plan != null ? skyPath(p, b) : null;
			if (out == null || out.size() < 2) return null;
			Vec3 here = p.position();
			for (int i = Math.min(out.size() - 1, 24); i >= 1; i--) {
				BlockPos c = out.get(i);
				Vec3 pt = new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
				if (clearLine(here, pt)) return pt;
			}
			BlockPos c = out.get(1);
			return new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
		}
		// Once in the open column, straight up and out.
		List<BlockPos> path = new ArrayList<>();
		path.add(new BlockPos(exit.getX(), b.maxY() + 2, exit.getZ()));
		for (BlockPos c = exit; ; c = from.get(c)) {
			path.add(c);
			if (from.get(c).equals(c)) break;
		}
		// path runs exit-first: aim for the first point (nearest the way out) we can see straight to.
		Vec3 here = p.position();
		for (BlockPos c : path) {
			Vec3 pt = new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
			if (clearLine(here, pt)) return pt;
		}
		// Nothing in clear sight from exactly here (pressed against a fence post by the cottage's wall, every line from us
		// started in it: "no way out found", 40 s): the first step out, then the rest.
		if (path.size() >= 2) {
			BlockPos c = path.get(path.size() - 2);
			return new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
		}
		return null;
	}

	/**
	 * Filling {@code h} still leaves us a way out to the sky. Only matters when we're under a roof: then the hole we
	 * came in through mustn't be the one we fill.
	 */
	private boolean leavesWayOut(LocalPlayer p, Box b, BlockPos h) {
		long c0 = costStart();
		try {
			return leavesWayOut0(p, b, h);
		} finally {
			costEnd("wayout", c0);
		}
	}

	private boolean leavesWayOut0(LocalPlayer p, Box b, BlockPos h) {
		if (plan != null) {
			// Knocking something out, a door (we open those to get through), something we walk through: no new wall.
			BlockState want = plan.get(h);
			if (want != null && (jobAt(h) == Placement.Job.BREAK || handDoor(want) || want.getCollisionShape(Terrain.level(), h).isEmpty())) {
				return true;
			}
		}
		BlockPos me = bodyCell(p);
		// Not into the space we're in ourselves: a thin trapdoor panel at the edge of our cell fits beside us, and then
		// our own cell isn't open in the map (the cottage's attic: an open trapdoor put in where he hovered, then every
		// side "shut" and 48 s boxed in). Move first.
		if (plan != null && p.getBoundingBox().inflate(0.05).intersects(new AABB(h))) return false;
		if (plan != null) {
			// Open sky over us is a way out, unless h is what goes in over our head: in a shelf of the Walmart, open on
			// top, he put the fence over his own head ("open sky: fine"), and then he was built in.
			if (skyOpen(b, me.getX(), me.getY(), me.getZ()) && !inColumn(h, me) && !inColumn(h.below(), me)) return true;
			// The way out from under the whole build, not just the bit of it round us: under the statue's body the open
			// sky was 30 blocks to the side, past the map round us, so "no way out" refused every hole near and he hung
			// there for good with TNT damage all round him. Filling h only matters if the way out goes through it (or up
			// the open column it ends in).
			List<BlockPos> out = skyPath(p, b);
			if (out == null) return false;
			BlockPos last = out.get(out.size() - 1);
			if (!out.contains(h) && !out.contains(h.below()) && !inColumn(h, last) && !inColumn(h.below(), last)) return true;
			return aStarOut(me, b, h, h.below()) != null;
		}
		if (skyOpen(b, me.getX(), me.getY(), me.getZ())) return true;
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		q.add(me);
		seen.add(me);
		while (!q.isEmpty() && seen.size() < 8000) {
			BlockPos c = q.poll();
			if (c.getY() > b.maxY() || (plan != null && c.getY() >= regY + REG_V - 1)) return true;
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (seen.contains(n) || !inArea(b, n.getX(), n.getY(), n.getZ())) continue;
				if (n.equals(h) || n.equals(h.below())) continue; // as if it were already filled
				if (!flyable(n.getX(), n.getY(), n.getZ())) continue;
				seen.add(n);
				q.add(n);
			}
		}
		return false;
	}

	/**
	 * An open (flyable) cell to start a search for the way out from: ours, the one under it (hovering low), or one beside
	 * us. (Ours not open - hovering under a top-half trapdoor in the cottage, its cell "not a way" - the search started in
	 * the floor block under us: "no way out", every block refused as "it would shut us in", 57 left after 30 s.)
	 */
	private static BlockPos startCell(BlockPos me) {
		if (flyable(me.getX(), me.getY(), me.getZ())) return me;
		if (flyable(me.getX(), me.getY() - 1, me.getZ())) return me.below();
		for (Direction d : Direction.values()) {
			BlockPos n = me.relative(d);
			if (flyable(n.getX(), n.getY(), n.getZ())) return n;
		}
		return null;
	}

	/** {@code c} is in the open column going up from {@code base} (at or above it). */
	private static boolean inColumn(BlockPos c, BlockPos base) {
		return c != null && c.getX() == base.getX() && c.getZ() == base.getZ() && c.getY() >= base.getY();
	}

	/** The way out to the open sky from where we are (worked out again each second, or when we move), or null. */
	private List<BlockPos> skyCells;
	private BlockPos skyFrom;
	private long skyAt = -1000;

	private List<BlockPos> skyPath(LocalPlayer p, Box b) {
		long now = HumanAutopilot.ticks();
		BlockPos me = bodyCell(p);
		if (me.equals(skyFrom) && now - skyAt < 20) return skyCells;
		skyFrom = me;
		skyAt = now;
		skyCells = aStarOut(me, b, null, null);
		return skyCells;
	}

	/**
	 * A way out from under the build: A* to open sky, past its top or out beside it, not through {@code skipA} or
	 * {@code skipB} (as if they were filled). Null if there's none, or it's past 40k cells / 8 ms (then it's as good
	 * as shut in).
	 */
	private static List<BlockPos> aStarOut(BlockPos me, Box b, BlockPos skipA, BlockPos skipB) {
		BlockPos start = startCell(me);
		if (start == null) return null;
		int x0 = b.minX() - 3, x1 = b.maxX() + 3, z0 = b.minZ() - 3, z1 = b.maxZ() + 3, y0 = b.minY(), y1 = b.maxY() + 3;
		long ska = skipA == null ? Long.MIN_VALUE : skipA.asLong(), skb = skipB == null ? Long.MIN_VALUE : skipB.asLong();
		var g = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		g.defaultReturnValue(Integer.MAX_VALUE);
		var from = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
		var closed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((u, v) -> Long.compare(u[0], v[0]));
		long sk = start.asLong();
		g.put(sk, 0);
		from.put(sk, sk);
		open.add(new long[]{0, sk});
		long end = Long.MIN_VALUE, deadline = System.nanoTime() + 8_000_000;
		int n = 0;
		while (!open.isEmpty() && closed.size() < 40000) {
			if ((++n & 511) == 0 && System.nanoTime() > deadline) break;
			long k = open.poll()[1];
			if (!closed.add(k)) continue;
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			// (An open column only if what we'd fill isn't up it: skyOpen sees it still empty.)
			if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ() || y > b.maxY()
					|| (k != sk && skyOpen(b, x, y, z) && !inColumn(skipA, BlockPos.of(k)) && !inColumn(skipB, BlockPos.of(k)))) {
				end = k;
				break;
			}
			int gk = g.get(k);
			for (Direction d : Direction.values()) {
				int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
				if (nx < x0 || nx > x1 || ny < y0 || ny > y1 || nz < z0 || nz > z1) continue;
				long nk = BlockPos.asLong(nx, ny, nz);
				if (nk == ska || nk == skb || closed.contains(nk) || gk + 10 >= g.get(nk)) continue;
				if (!flyable(nx, ny, nz)) {
					closed.add(nk);
					continue;
				}
				g.put(nk, gk + 10);
				from.put(nk, k);
				// Towards the nearest side of the build, or its top.
				int hx = Math.min(nx - b.minX() + 1, b.maxX() + 1 - nx), hz = Math.min(nz - b.minZ() + 1, b.maxZ() + 1 - nz);
				int hh = Math.max(0, Math.min(Math.min(hx, hz), b.maxY() + 1 - ny));
				open.add(new long[]{gk + 10 + 13L * hh, nk});
			}
		}
		if (end == Long.MIN_VALUE) return null;
		List<BlockPos> path = new ArrayList<>();
		for (long c = end; path.size() < 2000; ) {
			path.add(BlockPos.of(c));
			long f = from.get(c);
			if (f == c) break;
			c = f;
		}
		java.util.Collections.reverse(path);
		return path;
	}

	/** Nothing solid from here up to above the box. */
	private static boolean skyOpen(Box b, int x, int y, int z) {
		int top = Math.max(b.maxY() + 2, y + 2);
		if (plan != null) top = Math.min(top, y + 24);
		for (int yy = y; yy <= top; yy++) if (!Terrain.passable(x, yy, z)) return false;
		return true;
	}

	// ------------------------------------------------------------------ flying with the keys

	/**
	 * Straight there if the way is clear; otherwise hop over whatever is in the way only as high as needed (up,
	 * across, down), and only as a last resort up above the whole box.
	 */
	private Vec3 lastRoute;
	private String lastRouteWhy = "";

	private Vec3 route(LocalPlayer p, Box b, Vec3 goal) {
		long c0 = costStart();
		routeNoWay = false;
		routeLeft = -1;
		Vec3 r;
		try {
			r = route0(p, b, goal);
		} finally {
			costEnd("route", c0);
		}
		lastRoute = r;
		return r;
	}

	private BlockPos wedgeCell;
	private Vec3 routeGoalSeen, routeBestPos = Vec3.ZERO;
	private double routeBestD;
	private long routeBestAt;
	private long wedgeSince, wedgeStart = -1, wedgeLastAt = -1000;
	private Vec3 wedgePos = Vec3.ZERO;

	/**
	 * How much of the way the last route had left, in cells (-1: straight there). Getting nearer is measured by this on a
	 * path: going round something the straight distance grows for a while.
	 */
	private double routeLeft = -1;
	/** The open-air path the last route went by (null: straight there, or the far way) and the point of it steered to. */
	private List<BlockPos> routePath;
	private int routeIdx;
	/** The last route went straight to where we're going (nothing more after the point steered to). */
	private boolean routeStraight;

	private Vec3 route0(LocalPlayer p, Box b, Vec3 goal) {
		Vec3 here = p.position();
		long now = HumanAutopilot.ticks();
		routePath = null;
		routeStraight = false;
		// Pushing and not moving for half a second: follow the open-air path one step at a time, no shortcuts.
		if (here.distanceTo(routeLastPos) > 0.1) {
			routeLastPos = here;
			routeLastMove = now;
		}
		// (Or a second without getting anywhere sideways while the way goes sideways: bobbing up and down counted as
		// moving. On a slab beside a 2-high way in, his head didn't fit under its top: down, up, down, 13 landings.)
		if (Math.hypot(here.x - routeLastSide.x, here.z - routeLastSide.z) > 0.1) {
			routeLastSide = here;
			routeLastSideMove = now;
		}
		// (Getting nearer counts, not just moving: pushing into spruce leaves one way and sliding back and forth the other,
		// "moving" all along, the house's 4 s against the leaves before "couldn't fly there", the camera on them.)
		double hd = Math.hypot(goal.x - here.x, goal.z - here.z);
		if (routeGoalSeen == null || routeGoalSeen.distanceTo(goal) > 1.0 || here.distanceTo(routeBestPos) > 1.0) {
			// (A new goal, or well on the move - going round something: not stuck.)
			routeGoalSeen = goal;
			routeBestD = hd;
			routeBestAt = now;
			routeBestPos = here;
		} else if (hd < routeBestD - 0.15) {
			routeBestD = hd;
			routeBestAt = now;
			routeBestPos = here;
		}
		boolean stuckSideways = (now - routeLastSideMove > 20 || now - routeBestAt > 30) && hd > 0.5;
		if (now - routeLastMove > 10 || stuckSideways) {
			List<BlockPos> path = airPath(p, b, BlockPos.containing(goal.x, goal.y + 0.05, goal.z));
			if (path.size() > 1) {
				// The first step of the way that's a real move from where we are (the very next cell was half a block
				// straight up: inside the height dead zone, so we never moved and it was the next step for ever).
				BlockPos c = path.get(1);
				for (int i = 1; i < path.size(); i++) {
					BlockPos q = path.get(i);
					c = q;
					if (here.distanceTo(new Vec3(q.getX() + 0.5, q.getY() + 0.15, q.getZ() + 0.5)) > 0.8 && !overPoint(here, q)) break;
				}
				lastRouteWhy = "wedged: next step";
				routePath = path;
				routeIdx = path.indexOf(c);
				// (Jiggling in and out of "not moving" doesn't start it again: only getting a block away does.)
				if (wedgeStart < 0 || here.distanceTo(wedgePos) > 1.0 || now - wedgeLastAt > 100) {
					wedgeStart = now;
					wedgePos = here;
				}
				wedgeLastAt = now;
				// (Or wedged for 2.5 s in all, the step ahead flicking between two cells as we jiggled: 13 s pushing at a
				// tree trunk in the wizard tower's wood, two targets given up on before "can't get into".)
				if (!c.equals(wedgeCell) && now - wedgeStart <= 50) {
					wedgeCell = c;
					wedgeSince = now;
				} else if (now - wedgeSince > 60 || now - wedgeStart > 50) {
					wedgeStart = -1;
					// Pushing into the same cell for 3 s and not getting in: something sticks into it the map doesn't
					// know about. Off the map for a while: another way.
					stuckCells.put(c.asLong(), now + stuckFor(c));
					if (stuckCells.size() > 200) stuckCells.values().removeIf(u -> u <= now);
					HumanAutopilot.LOGGER.info("[box] can't get into {} from {} (something in the way: {}; height first {} ticks, gap under us {}, last down {}): another way",
							c.toShortString(), fmt(here), inTheWay(p, here, new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5)),
							heightFirstSince < 0 ? "-" : String.valueOf(now - heightFirstSince), String.format(java.util.Locale.ROOT, "%.2f", gapBelow(p)),
							lastVert == null ? "-" : lastVert + " " + (now - lastVertAt) + " ticks ago");
					airAt = -100; // (the map again, without it)
					wedgeCell = null;
				}
				return new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
			}
		}
		lastRouteWhy = "straight";
		if (clearLine(here, goal)) {
			routeStraight = true;
			return goal;
		}
		// Through the open air: down a hole into a hollow under a roof, round a pillar. Head for the furthest point
		// of the way we can see straight to.
		List<BlockPos> path = airPath(p, b, BlockPos.containing(goal.x, goal.y + 0.05, goal.z));
		int passed = 0;
		while (passed + 1 < path.size() && overPoint(here, path.get(passed + 1))) passed++;
		for (int i = path.size() - 1; i > passed; i--) { // (not path[0]: that's where we are: steering there stood still)
			BlockPos c = path.get(i);
			Vec3 pt = new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
			if (clearLine(here, pt)) {
				lastRouteWhy = "path point " + i + "/" + path.size();
				routePath = path;
				routeIdx = i;
				routeLeft = path.size() - 1 - passed;
				return pt;
			}
		}
		lastRouteWhy = "path " + path.size() + " cells, none in sight";
		if (path.size() > 1) routeLeft = path.size() - 1 - passed;
		if (path.size() > 1) {
			// Nothing on the way in clear sight from exactly here (squeezed against something): just take the next step.
			BlockPos c = path.get(Math.min(path.size() - 1, passed + 1));
			routePath = path;
			routeIdx = Math.min(path.size() - 1, passed + 1);
			return new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
		}
		if (plan != null) {
			// Beyond the open-air map round us: the long way, worked out properly (round the statue, down beside it).
			// "Up over the top of the whole build" took him 100 blocks up over a statue, where nothing was in reach.
			List<BlockPos> far = farPath(p, b, BlockPos.containing(goal.x, goal.y + 0.05, goal.z));
			if (far.size() > 1) {
				Vec3 step = farStep(p, far);
				lastRouteWhy = "far path, " + far.size() + " cells, " + Math.round(farLeft) + " left";
				routeLeft = farLeft;
				// (Kept, like the near way, for walkRest: with none kept a set-down was never allowed on it, and the
				// cottage's first minute went hovering 0.24 too high in front of the gap under its overhang.)
				routePath = far;
				routeIdx = farStepIdx;
				// Not getting any nearer for 2.5 s: that step off the map for a while, another way.
				if (stuckSideways) {
					if (farWedgeSince < 0) farWedgeSince = now;
					else if (now - farWedgeSince > 50) {
						BlockPos c = far.get(farStepIdx);
						stuckCells.put(c.asLong(), now + stuckFor(c));
						if (stuckCells.size() > 200) stuckCells.values().removeIf(u -> u <= now);
						HumanAutopilot.LOGGER.info("[box] can't get into {} from {} (on the long way, something in the way: {}): another way", c.toShortString(), fmt(here),
								inTheWay(p, here, new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5)));
						airAt = -100;
						farWedgeSince = -1;
					}
				} else {
					farWedgeSince = -1;
				}
				return step;
			}
			// No way there from under a roof: out from under it first, else stay put ("up over the top of the whole
			// build" from inside the Walmart only pushed into its ceiling).
			BlockPos me = bodyCell(p);
			if (b.inside(me.getX(), me.getY(), me.getZ()) && !skyOpen(b, me.getX(), me.getY(), me.getZ())) {
				Vec3 out = wayOut(p, b);
				if (out == null) {
					// Wedged where the map has no open cell (our own isn't one): a step to an open one beside us first.
					for (Direction d : Direction.values()) {
						BlockPos n = me.relative(d);
						if (d != Direction.DOWN && flyable(n.getX(), n.getY(), n.getZ()) && air(p, b).containsKey(n)) {
							lastRouteWhy = "wedged: a step out to " + n.toShortString();
							return new Vec3(n.getX() + 0.5, n.getY() + 0.15, n.getZ() + 0.5);
						}
					}
				}
				lastRouteWhy = out != null ? "out from under the roof first" : "no way from here";
				return out != null ? out : here;
			}
		}
		// (Over what's really in the way along the line there - the ground, the last build - not this build's full height:
		// on to the next build after the fantasy castle, the floating pagoda's 130 blocks, he went 130 straight up,
		// across, and 130 down again: "why does he fly straight up".)
		double top = Math.max(Math.max(Math.min(b.maxY() + 1.5, lineTop(here, goal) + 1.0), goal.y), p.getY());
		for (double h = Math.max(here.y, goal.y); h < top; h += 1.0) {
			Vec3 up = new Vec3(here.x, h, here.z), over = new Vec3(goal.x, h, goal.z);
			if (clearLine(here, up) && clearLine(up, over) && clearLine(over, goal)) {
				return Math.abs(here.y - h) > 0.4 && Math.hypot(goal.x - here.x, goal.z - here.z) > 0.6 ? up : over;
			}
		}
		double cruise = top;
		double flat = Math.hypot(goal.x - p.getX(), goal.z - p.getZ());
		// Neither way through the air (near or the long way) reached it: up over the top of the whole build to come down
		// at it won't either - a hill or the build's own walls are in between. Over 8 up, that's the yo-yo: the user's
		// castle in a hillside, up 30, down 30 onto the grass over its buried walls, for 2.5 minutes. Not reachable now.
		// (And whatever the A* did - on a big build it runs out of time rather than places to look: something over the
		// goal between it and the top means coming down on it from up there can't work. The user's castle, a block at
		// the bottom inside its wall 13 blocks off: up 47 blocks, down onto the wall, "couldn't fly there", the next.)
		// (Within 24: further off, going over still takes us near it - a room's inside reached by its door once there,
		// which the A* had run out of time for - and the pocket it's in gets looked at from there, openPocket.)
		if (plan != null && ((aStarExhausted && cruise - Math.max(here.y, goal.y) > 8) || goalBuried(goal)
				|| (flat <= 24 && cruise - goal.y > 8 && !clearLine(new Vec3(goal.x, cruise, goal.z), goal)))) {
			routeNoWay = true;
			lastRouteWhy = "no way through the air";
			return here;
		}
		if (flat > 0.6 && p.getY() < cruise - 0.4) return new Vec3(p.getX(), cruise, p.getZ());
		if (flat > 0.6) return new Vec3(goal.x, cruise, goal.z);
		return goal;
	}

	/**
	 * The highest anything stands along the straight way from {@code a} to {@code b} (the first free height of each column,
	 * leaves and water too), down the middle and half a block to each side of it; a column not loaded counts as very high.
	 */
	private static double lineTop(Vec3 a, Vec3 b) {
		double dx = b.x - a.x, dz = b.z - a.z, len = Math.hypot(dx, dz);
		double sx = len > 0 ? -dz / len * 0.5 : 0, sz = len > 0 ? dx / len * 0.5 : 0;
		int top = Integer.MIN_VALUE;
		for (double d = 0; d <= len + 0.5; d += 1.0) {
			double f = len > 0 ? Math.min(1, d / len) : 0;
			for (int side = -1; side <= 1; side++) {
				int x = Mth.floor(a.x + dx * f + sx * side), z = Mth.floor(a.z + dz * f + sz * side);
				if (!Terrain.loaded(x, z)) return 100000;
				top = Math.max(top, Terrain.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
			}
		}
		return top;
	}

	/** The last route() found no way through the air to its goal (and wouldn't go up over the top for it). */
	private boolean routeNoWay;

	/** The long way (A*): the cells, what it was worked out for and when, how much of it is left (blocks; -1 none). */
	private List<BlockPos> farCells = List.of();
	private BlockPos farFor;
	private long farAt = -1000;
	private double farLeft = -1;

	private List<BlockPos> farPath(LocalPlayer p, Box b, BlockPos goal) {
		long now = HumanAutopilot.ticks();
		boolean same = goal.equals(farFor);
		// Worked out again every 2 s (the world changes: TNT, our own blocks), sooner if we've strayed off it; a way
		// that couldn't be found isn't looked for again for 3 s.
		if (same && farCells.isEmpty() && now - farAt < 60) return farCells;
		if (same && !farCells.isEmpty() && now - farAt < 40 && farNearest(p, farCells) >= 0) return farCells;
		long c0 = costStart();
		try {
			farFor = goal;
			farAt = now;
			farCells = aStar(bodyCell(p), goal, b);
		} finally {
			costEnd("far", c0);
		}
		if (farCells.isEmpty()) farLeft = -1;
		return farCells;
	}

	/** The cell of the way nearest us (within 3 blocks), else -1. */
	private static int farNearest(LocalPlayer p, List<BlockPos> path) {
		int best = -1;
		double bd = 9;
		for (int i = 0; i < path.size(); i++) {
			BlockPos c = path.get(i);
			double d = p.position().distanceToSqr(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
			if (d <= bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/** Where to head on the long way: the furthest point of it a little way ahead that's in clear sight. */
	/**
	 * A way point (its feet 0.15 over the floor of its cell) we're right over, at our usual hover or lower: as good as
	 * reached. Steering there means going down to the floor, which the hover never does in the open: the badlands
	 * villager hung over one for 4 minutes, "far path, 16 cells, 15 left", not moving at all.
	 */
	private static boolean overPoint(Vec3 here, BlockPos c) {
		double dx = here.x - (c.getX() + 0.5), dz = here.z - (c.getZ() + 0.5), up = here.y - c.getY();
		return dx * dx + dz * dz < 0.36 && up >= -0.2 && up <= CLEAR + 0.8 && solid(c.below());
	}

	private Vec3 farStep(LocalPlayer p, List<BlockPos> path) {
		int at = Math.max(0, farNearest(p, path));
		Vec3 here = p.position();
		while (at + 1 < path.size() && overPoint(here, path.get(at + 1))) at++;
		farLeft = path.size() - 1 - at;
		for (int i = Math.min(path.size() - 1, at + 24); i > at; i -= 3) {
			BlockPos c = path.get(i);
			Vec3 pt = new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
			if (clearLine(here, pt)) {
				farStepIdx = i;
				return pt;
			}
		}
		farStepIdx = Math.min(path.size() - 1, at + 1);
		BlockPos c = path.get(farStepIdx);
		return new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
	}

	private int farStepIdx;
	private long farWedgeSince = -1;

	/**
	 * A* through open air (two blocks high, like the air map) over the build area, from {@code me} to within 2 of
	 * {@code goal}. A bit greedy (weighted) so it's quick; gives up after 60k cells or 12 ms (then: empty).
	 */
	/** The last aStar() looked at every open cell it could get to and found no way (not just out of time). */
	private static boolean aStarExhausted;

	private static List<BlockPos> aStar(BlockPos me, BlockPos goal, Box b) {
		aStarExhausted = false;
		BlockPos start = flyable(me.getX(), me.getY(), me.getZ()) ? me : me.below();
		if (!flyable(start.getX(), start.getY(), start.getZ())) return List.of();
		int x0 = b.minX() - 3, x1 = b.maxX() + 3, z0 = b.minZ() - 3, z1 = b.maxZ() + 3, y0 = b.minY(), y1 = b.maxY() + 3;
		// (And where we are, if that's out beyond it: stepped back to look at the build, 25 blocks off, no way back was
		// found from out there, and he flew up over the top of the tower to y 153 and down again.)
		x0 = Math.min(x0, start.getX() - 2);
		x1 = Math.max(x1, start.getX() + 2);
		z0 = Math.min(z0, start.getZ() - 2);
		z1 = Math.max(z1, start.getZ() + 2);
		y0 = Math.min(y0, start.getY() - 2);
		y1 = Math.max(y1, start.getY() + 2);
		var g = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		g.defaultReturnValue(Integer.MAX_VALUE);
		var from = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
		var closed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((u, v) -> Long.compare(u[0], v[0]));
		long sk = start.asLong();
		g.put(sk, 0);
		from.put(sk, sk);
		open.add(new long[]{0, sk});
		long end = Long.MIN_VALUE;
		int gx = goal.getX(), gy = goal.getY(), gz = goal.getZ();
		long deadline = System.nanoTime() + 12_000_000; // (never a hitch you'd see)
		int n = 0;
		while (!open.isEmpty() && closed.size() < 60000) {
			if ((++n & 511) == 0 && System.nanoTime() > deadline) break;
			long k = open.poll()[1];
			if (!closed.add(k)) continue;
			int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
			if (Math.abs(x - gx) <= 2 && Math.abs(y - gy) <= 2 && Math.abs(z - gz) <= 2) {
				end = k;
				break;
			}
			int gk = g.get(k);
			for (Direction d : Direction.values()) {
				int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
				if (nx < x0 || nx > x1 || ny < y0 || ny > y1 || nz < z0 || nz > z1) continue;
				long nk = BlockPos.asLong(nx, ny, nz);
				if (closed.contains(nk) || gk + 10 >= g.get(nk)) continue;
				if (!flyable(nx, ny, nz)) {
					closed.add(nk);
					continue;
				}
				g.put(nk, gk + 10);
				from.put(nk, k);
				open.add(new long[]{gk + 10 + 13L * (Math.abs(nx - gx) + Math.abs(ny - gy) + Math.abs(nz - gz)), nk});
			}
		}
		aStarExhausted = end == Long.MIN_VALUE && open.isEmpty(); // (searched it all: really no way)
		if (end == Long.MIN_VALUE) return List.of();
		List<BlockPos> path = new ArrayList<>();
		for (long c = end; path.size() < 2000; ) {
			path.add(BlockPos.of(c));
			long f = from.get(c);
			if (f == c) break;
			c = f;
		}
		java.util.Collections.reverse(path);
		return path;
	}

	private static boolean clearLine(Vec3 from, Vec3 to) {
		double len = from.distanceTo(to);
		int steps = Math.max(1, (int) Math.ceil(len / 0.4));
		for (int i = 1; i <= steps; i++) {
			Vec3 q = from.lerp(to, i / (double) steps);
			// Just inside our 0.6 width: brushing a wall (x exactly on a block edge) isn't "blocked" (it froze it
			// against a wall in a hollow, every way out looking shut).
			// Our real width (0.6), a hair inside it so touching a wall isn't "blocked": 0.28 let it think it could slip
			// past a block's corner it couldn't, and it pushed into it for good.
			for (double ox = -0.299; ox <= 0.299; ox += 0.598) {
				for (double oz = -0.299; oz <= 0.299; oz += 0.598) {
					int x = (int) Math.floor(q.x + ox), z = (int) Math.floor(q.z + oz);
					for (double oy = 0.02; oy <= 1.8; oy += 0.89) {
						if (!Terrain.passable(x, (int) Math.floor(q.y + oy), z)) return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * W/A/S/D relative to wherever the camera points (so the eyes can stay on the work), space/shift for height.
	 * Flight glides, so let go early and tap the other way to stop, like a player does.
	 */
	private void steer(LocalPlayer p, InputDriver in, Vec3 goal, boolean hurry, double cap) {
		var o = InputDriver.opts();
		lastSteerGoal = goal;
		Vec3 d = goal.subtract(p.position());
		// Blocked just ahead at this height but not at the goal's (a 2-high gap under a roof's edge, a step up under a
		// beam): to its height first, then in. Sliding in at our own height we pushed against the lintel - the cottage's
		// "can't get into" wedges, 25 of its 63 camera stares with keys held and nothing moving.
		// (Even a few hundredths: 0.21 over the floor of a 2-high gap, the head 0.01 into the block over it, inside the
		// height dead zone - pushed into it for 3 s, "can't get into" the cell at its own level, the cottage's other half.)
		boolean heightFirst = false;
		if (p.getAbilities().flying && Math.abs(d.y) > 0.02 && d.x * d.x + d.z * d.z > 0.01) {
			Vec3 hor = new Vec3(d.x, 0, d.z).normalize().scale(0.35);
			AABB box = p.getBoundingBox();
			// (The whole way up or down free, not just the end of it: a slab between, and he'd only push into that instead.)
			if (!p.level().noCollision(p, box.move(hor)) && p.level().noCollision(p, box.expandTowards(0, d.y, 0))
					&& p.level().noCollision(p, box.move(hor.x, d.y, hor.z))) {
				// (Only the sideways keys hold still: the goal itself stays where it is, so the height rules below - how low
				// to go under the gap's roof, setting down to walk in - still look at the gap, not at where we hang.)
				heightFirst = true;
				vertFirst++;
			}
		}
		// (Kept through a tick or ten without it: the way ahead flicking between two cells as we jiggle turned it off and on,
		// and the second's wait below for the tap down started over every time - the fantasy test's "can't get into" at
		// 0.37 over the floor of a 2-high gap, "height first 17 ticks".)
		if (heightFirst) {
			long t0 = HumanAutopilot.ticks();
			if (heightFirstSince < 0 || t0 - heightFirstLastAt > 10) heightFirstSince = t0;
			heightFirstLastAt = t0;
		} else if (HumanAutopilot.ticks() - heightFirstLastAt > 10) {
			heightFirstSince = -1;
		}
		Vec3 v = p.getDeltaMovement();
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		// Going down a narrow gap (one block wide: a slab on one side, a trapdoor on the other, in the cottage's
		// ceiling): line up over it first, then drop. A quarter block off (inside the usual 0.3 slack), the body caught
		// the slab's edge and set us down on it, the take-off and the next try did the same: a landing a second.
		boolean lineUp = false;
		if (p.getAbilities().flying && d.y < -0.6 && d.x * d.x + d.z * d.z < 0.64 && Math.abs(d.x) + Math.abs(d.z) > 0.1) {
			double straight = Math.min(gapAt(p.getY(), goal.x, goal.z), -d.y);
			lineUp = gapBelow(p) < straight - 0.3;
		}
		// (Not for ever: something in the way sideways and never lined up, he'd hang there. After 1.5 s, down anyway.)
		if (!lineUp) lineUpSince = -1;
		else if (lineUpSince < 0) lineUpSince = HumanAutopilot.ticks();
		else if (HumanAutopilot.ticks() - lineUpSince > 30) lineUp = false;
		double slack = lineUp ? 0.08 : -1, lineCap = lineUp ? Math.min(cap, 0.12) : cap;
		// Wedged, going step by step: into the next cell's middle exactly. Within the usual 0.3 slack the last few tenths
		// sideways were never taken - the 25k house's trees: 0.3 off the middle, the box's edge 0.1 into an oak log's
		// column, pushing straight at the log for 4 s ("can't get into", "straight ... Oak Log; sideways free").
		if (slack < 0 && lastRoute != null && goal.distanceToSqr(lastRoute) < 1e-6 && lastRouteWhy.startsWith("wedged")) {
			slack = 0.05;
			lineCap = Math.min(lineCap, 0.15);
		}
		double hx = heightFirst ? 0 : d.x, hz = heightFirst ? 0 : d.z; // (height first: hold still sideways)
		axis(in, hx * fx + hz * fz, v.x * fx + v.z * fz, o.keyUp, o.keyDown, hurry, lineCap, slack);
		axis(in, hx * rx + hz * rz, v.x * rx + v.z * rz, o.keyRight, o.keyLeft, hurry, lineCap, slack);
		if (!p.getAbilities().flying) {
			// On foot (set down to walk under a low roof): just walk. Still under one, or one just ahead: stay on foot
			// a while longer; out from under it, the take-off comes by itself.
			// (The same roof test as the setting down: with only the roof just ahead counted here, a roof over a spot 2 off
			// set us down, nothing kept us on foot, and off again we went, a second later.)
			boolean near = Math.hypot(goal.x - p.getX(), goal.z - p.getZ()) < 2.5;
			if (plan != null && (roofAhead(p, goal) || lowRoofOver(p.position()) || (near && lowRoofOver(goal)))) walkUntil = HumanAutopilot.ticks() + 20;
			// Walking into a step a block high: jump up it (one press - jump() keeps presses 8 ticks apart, so it's never
			// the double tap that would switch flying on).
			if (plan != null && p.onGround() && p.horizontalCollision && stepUpAhead(p, goal)) jump(in);
			// (Nothing to stand on on the way, a way point over a gap in the floor or a step up: fly, now. On foot he walked
			// off the edge of the special medieval house's upper floor, fell 4 blocks, flew back up and set down again.)
			// (Only a real gap or step: a spot hovering a block over its floor counted as "nothing under it" too, and the
			// cottage took off the moment it set down, 83 landings in 15 minutes.)
			// (Or the way goes straight up from here: that's flying, not walking. Set down to walk in among the giant tree's
			// roots, the way out went up through the cell over his head; on foot he stood under it pushing at nothing,
			// "can't get into" it, "shut in", and knocked three of the build's blocks out to get free: "caved himself in".)
			boolean upHere = goal.y - p.getY() > 0.6 && Math.hypot(goal.x - p.getX(), goal.z - p.getZ()) < 0.8;
			if (plan != null && (upHere || !walkable(p.position(), goal))) {
				walkUntil = HumanAutopilot.ticks() - 1;
				// (And not back on foot for footWork's sake while the take-off's double tap is under way: every other
				// tick on foot again, the taps came 8 ticks apart, never a double tap - the cottage hopping on the spot
				// for 40 s, "can't take off here".)
				takeOffNowUntil = HumanAutopilot.ticks() + 20;
				// (And no setting down again for a bit: down for one way point, the next one over a gap, up, down for the
				// first again... 53 landings in 6 minutes round the cottage's upper floor.)
				noSetDownUntil = HumanAutopilot.ticks() + 60;
				if (HumanAutopilot.ticks() - walkOffLogAt > 100) {
					walkOffLogAt = HumanAutopilot.ticks();
					HumanAutopilot.LOGGER.info("[box] on foot, {} {} ({}): flying", upHere ? "the way goes up from here to" : "no floor all the way to",
							fmt(goal), lastRouteWhy);
				}
			}
			return;
		}
		double glideY = v.y * 1.5;
		// Only fix the height when it's well off (the recorded player bobs up/down ~19 times a minute, not 60).
		double dead = hurry ? 0.15 : precise ? 0.12 : 0.5;
		// Following the way step by step (wedged, or round something): every step has to be taken, small ones too.
		if (lastRoute != null && goal.distanceToSqr(lastRoute) < 1e-6 && !lastRouteWhy.equals("straight")) dead = Math.min(dead, 0.12);
		if (heightFirst) dead = Math.min(dead, 0.01); // (to the gap's height exactly, then in)
		double gap = gapBelow(p);
		// Over hills: the ground ahead too, where we'll be in a quarter of a second (it came up under us faster than we
		// rose: he touched down and took off again 61 times in 15 minutes on the barn's hillside: "bunny hopping").
		double aheadX = v.x * 5, aheadZ = v.z * 5;
		if (aheadX * aheadX + aheadZ * aheadZ > 0.04 && !lowRoofOver(p.position())) {
			gap = Math.min(gap, gapAt(p.getY(), p.getX() + aheadX, p.getZ() + aheadZ));
			gap = Math.min(gap, gapAt(p.getY(), p.getX() + aheadX * 0.5, p.getZ() + aheadZ * 0.5));
		}
		// Touching down ends flight (and taking off again looks like jumping): never let the feet land. Keep CLEAR
		// (1.5) of air under the feet, less under a low roof (it has to fit); a band, so it doesn't pulse at the edge.
		double clear = Math.min(CLEAR, hov(BlockPos.containing(p.getX(), p.getY() - gap + 0.01, p.getZ())));
		if (lowHover) clear = Math.min(clear, HumanAutopilot.ticks() < bounceUntil && !lowRoofOver(p.position()) ? 0.5 : LOW);
		// Heading for a spot low over its floor (the way into a 2-high gap: the route keeps to 0.15 over the floor
		// there): let us get that low once we're nearly there. At our usual height we bumped into the lintel for ever
		// (the user's "stuck" stalls: 30 s against a wall, 26 times in a night).
		// (Only if there's really a roof over it: way points hug the floor at 0.15 in the open too, and going down to
		// each one over the statue's leaves set us down on them again and again.)
		double overFloor = goal.y - Math.floor(goal.y);
		boolean goalNear = Math.hypot(goal.x - p.getX(), goal.z - p.getZ()) < 2.5;
		// (And still once we're down there: stopping at 0.3 over it let the hover take us up again, the head caught the
		// gap's top, and up and down in front of it we went, 37 s, the fantasy medieval house.)
		if (overFloor < 0.4 && goal.y < p.getY() + 0.1 && goalNear && lowRoofOver(goal)) {
			clear = Math.min(clear, Math.max(LOW, overFloor));
		}
		// Under a low roof every bit of height counts (it had to drop half a block to fit under and the 0.5 dead
		// zone kept it bumping the roof instead): fine height control there; out in the open, calm (no bobbing).
		// Under one now, or sliding in under one just ahead (the open column we came down kept us 1.5 up, too high to
		// fit under it). Not any block at head height beside us: in a tree's leaves there always is one, and it kept
		// dropping us onto the leaves and taking off again ("he's bunny hopping over the leaves").
		// And once low under one, low for a second more: the leaves' upper layer comes and goes over us, and rising in
		// every gap only to drop under the next leaf was the bobbing.
		long now = HumanAutopilot.ticks();
		boolean roofNear = roofAhead(p, goal) || (goalNear && lowRoofOver(goal));
		if (roofNear) lowRoofUntil = now + 20;
		if (now < lowRoofUntil) clear = Math.min(clear, 0.15);
		// Going in under a low roof to a spot low over its floor (a 2-high gap): set down on the floor and walk in,
		// like a player. Hovering a hair over the floor to fit in (down, the brake, up, down...) never got low enough
		// and bounced in front of the gap for minutes (MagicQueen, three times).
		// (Only right over the goal's own floor: over a lower one beside it - gapBelow counts any corner over the ledge - we
		// slid down onto the lower floor, a step under the goal, and took off again at once.)
		double myFloor = groundTop(p.getY() + 0.05, p.getX(), p.getZ()), goalFloor = groundTop(goal.y + 0.05, goal.x, goal.z);
		if (plan != null && roofNear && goalNear && overFloor < 0.4 && goal.y < p.getY() - 0.05 && floorUnder(goal) && now >= noSetDownUntil
				&& (now - setDownAt > 200 || !p.getAbilities().flying)
				&& !Double.isNaN(myFloor) && !Double.isNaN(goalFloor) && Math.abs(myFloor - goalFloor) <= 0.6
				&& walkable(new Vec3(p.getX(), myFloor, p.getZ()), goal)
				&& (walkRest(new Vec3(goal.x, goalFloor, goal.z)) || now - routeBestAt > 40)) { // (or 2 s getting nowhere hovering: down anyway)
			walkUntil = now + 20;
			// (One set-down per 10 s at most: under a wood's canopy on a slope, down to walk under the leaves, the slope
			// stopped the walk, up again - 4 times in 16 s on the cottage's mountainside.)
			if (p.getAbilities().flying && gap < 0.3) setDownAt = now;
			if (p.getAbilities().flying && gap < 0.3 && now - setDownLogAt > 100) {
				setDownLogAt = now;
				BlockPos rc = BlockPos.containing(goal.x, goal.y + 2.0, goal.z);
				HumanAutopilot.LOGGER.info("[box] setting down to walk in under a low roof (going to {}, over it {}, roof ahead {})",
						fmt(goal), Terrain.state(rc).getBlock().getName().getString(), roofAhead(p, goal));
			}
			if (gap > 0.01) {
				in.hold(o.keyShift);
				vDown++;
				lastVert = "set-down";
				lastVertAt = now;
			}
			return;
		}
		boolean low = clear < CLEAR - 0.01;
		if (low) dead = Math.min(dead, 0.1);
		// Coming down, the floor where we'll be in a tick or few too: sinking over a pit while drifting onto the ledge
		// beside it, the brake only looked straight down ("descend (gap 3.00)", landed 2 ticks later on the forest
		// cathedral's hillside).
		// (Height first we don't move sideways: the ground "ahead" - where the drift would take us - isn't where we'll be.
		// Counted, a wall in front made it negative, and he hung 2 over the floor of the user's 2-high gap, wanting 1.84
		// down, never going: "ahead -1.01", "couldn't fly there".)
		double aheadG = heightFirst ? gap : aheadGap(p, v, d);
		double ahead = v.y < -0.05 ? Math.min(gap, aheadG) : gap;
		// (For the stall report: what the height control saw last.)
		steerAt = now;
		steerWhy = String.format(java.util.Locale.ROOT, "goal %s d.y %.2f, height first %s, gap %.2f ahead %.2f, clear %.2f, low %s, roof near %s, line up %s",
				fmt(goal), d.y, heightFirst, gap, aheadGap(p, v, d), clear, low, roofNear, lineUp);
		// (Not on the way down to a gap's height, a second in: there, down onto the floor is fine - walked in from there. The
		// tap down came, this pushed us straight back up, and we bobbed in front of the gap: the user's 2-high gaps, "height
		// first" 50-80 ticks and "can't get into" 66 times in 3 hours.)
		boolean downToGap = heightFirst && d.y < 0 && gap < 0.6 && heightFirstSince >= 0 && now - heightFirstSince > 20;
		if (!downToGap && ahead - Math.min(0, v.y) * -3 < Math.max(clear - 0.35, 0.08)) {
			jump(in);
			vGuard++;
		} else if (d.y - glideY > dead
				// Bumped into something on the way (a wall edge a hair above our feet): go up over it, like a player,
				// instead of pushing into it for good because the height to gain was inside the dead zone.
				|| (p.horizontalCollision && d.y > -0.3 && !roofNear && !solid(BlockPos.containing(p.getX(), p.getY() + 2.0, p.getZ())))) {
			jump(in);
			vUp++;
			// (Low: the last tap from 0.55 up at least - from 0.43 it sometimes went on down to the floor and flight ended: 13
			// of the 25k house's 24 landings, "descend (gap 0.44, clear 0.15)", over the pits of its ground.)
		} else if (d.y - glideY < -dead && (Math.min(gap, aheadG) > (low ? Math.max(clear + 0.05, (Math.max(0, -v.y) + 0.2) / 0.4 + 0.05) : clear + 0.3)
				// (Held up a second in front of a 2-high gap, a tenth or two too high to fit under it and the floor too near
				// for a tap that won't touch it: tap anyway - down on the floor is fine, it's walked in from there. Hanging
				// 0.13 too high, the head on the block over the gap, was most of the 25k house's "can't get into"s.)
				|| (heightFirst && gap < 0.6 && now - heightFirstSince > 20)) && !lineUp
				// Low over the floor: not straight after a jump (the brake that's then needed is a second press inside
				// the double-tap window, which jump() holds back so flying isn't switched off, and we landed instead),
				// and not while already coming down quickly.
				&& (!low || (now - jumpPressedAt >= 8 && v.y > -0.12))) {
			in.hold(o.keyShift);
			vDown++;
			// (Down to fit under a gap: landing there is meant - walked on in, like a set-down.)
			lastVert = heightFirst && gap < 0.6 ? "set-down" : "descend (gap " + String.format("%.2f", gap) + ", clear " + String.format("%.2f", clear) + ")";
			lastVertAt = now;
		}
	}

	private void axis(InputDriver in, double rem, double vel, net.minecraft.client.KeyMapping plus,
							 net.minecraft.client.KeyMapping minus, boolean hurry, double cap) {
		axis(in, rem, vel, plus, minus, hurry, cap, -1);
	}

	/** {@code slackOver}: how close is close enough, when it's not the usual (lining up over a narrow gap). */
	private void axis(InputDriver in, double rem, double vel, net.minecraft.client.KeyMapping plus,
							 net.minecraft.client.KeyMapping minus, boolean hurry, double cap, double slackOver) {
		// Fly like a player: keep the key down until close, then tap the other way to stop (letting go and coasting
		// takes ages: flight only loses ~9% speed a tick). cap: blocks/tick (drifting while placing ~2 blocks/s).
		// (One tick of a key while flying carries us ~0.5 blocks: "precise" at 0.08 was a tap one way, a tap back, for
		// as long as we stayed there, and the camera, on the block, wiggled left and right: the user saw it.)
		double slack = slackOver >= 0 ? slackOver : hurry ? 0.6 : 0.3;
		double stop = Math.abs(vel) * 3 + 0.1; // about how far a tap-back stop takes
		// (Lining up over a 1-wide shaft: braking from a slower speed, or the coast - flight loses ~9% a tick - carried us
		// past the middle every time, 0.25 one way, 0.25 the other, and the shaft wants it within 0.2: the 25k house's
		// ravine, 4 s swaying over a shaft, dipping onto its edge and up again, "can't get into".)
		double brakeIn = slackOver >= 0 ? 0.02 : 0.1, brakeNear = slackOver >= 0 ? 0.03 : 0.12;
		if (rem > slack) {
			if (vel < 0 || (rem > stop && vel < cap)) in.hold(plus);
			else if (rem <= stop && vel > brakeNear) in.hold(minus);
		} else if (rem < -slack) {
			if (vel > 0 || (-rem > stop && vel > -cap)) in.hold(minus);
			else if (-rem <= stop && vel < -brakeNear) in.hold(plus);
		} else if (Math.abs(vel) > brakeIn) {
			in.hold(vel > 0 ? minus : plus); // tap back to stop
		}
	}

	/**
	 * While placing: ease toward a comfortable spot ~2.5 from the hole on our side of it, not too far above or
	 * below it, so we keep moving gently from block to block like the recorded player (median ~2 blocks/s).
	 */
	private void drift(LocalPlayer p, InputDriver in, BlockPos t, double cap) {
		Vec3 c = Vec3.atCenterOf(t);
		Vec3 flat = new Vec3(p.getX() - c.x, 0, p.getZ() - c.z);
		double d = flat.length();
		Vec3 spot = p.position();
		// Too far: ease in closer. Close is fine: never back away from the hole (the user saw it "keeping its
		// distance"), and no aimless strafing (it made the camera trail behind the face it was aiming at).
		if (d > 3.2 && plan == null) {
			Vec3 dir = flat.scale(1 / d);
			spot = new Vec3(c.x + dir.x * 2.2, p.getY(), c.z + dir.z * 2.2);
		}
		if (plan != null && (d > 3.8 || (d < 1.8 && !steepFine(t) && !lowHover))) {
			// Schematic: stay a few blocks off, like a player building in front of themselves.
			Vec3 dir = d > 0.05 ? flat.scale(1 / d) : new Vec3(-Math.sin(Math.toRadians(p.getYRot())), 0, Math.cos(Math.toRadians(p.getYRot()))).scale(-1);
			spot = new Vec3(c.x + dir.x * 2.8, p.getY(), c.z + dir.z * 2.8);
		}
		double eyeOver = p.getEyeY() - c.y;
		// Hold one steady height while working (it bobbed between "lower, to see the hole" and "up, off the ground",
		// which looked like jumping). Only go up for a hole above eye level, down for one far below.
		double y = eyeOver < -0.3 ? p.getY() + 1.0 : eyeOver > (plan != null ? 3.2 : 4.6) ? p.getY() - 1.0 : p.getY();
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.05, p.getZ());
		if (lowHover) y = p.getY(); // looking up at something overhead from just over the floor: stay down here
		else if (solid(under)) y = Math.max(y, under.getY() + 1 + hov(under.above()));
		else if (solid(under.below())) y = Math.max(y, under.getY() + hov(under));
		steer(p, in, new Vec3(spot.x, y, spot.z), false, cap);
	}

	/**
	 * Space while flying, without ever making a double-tap: two presses within 7 ticks switch creative flight OFF
	 * (the bot then dropped onto a block and double-tapped back up, which looked like jumping). Holding it down is
	 * fine; a new press only 8+ ticks after the last one.
	 */
	/** Walking along {@code dir}: a step up just in front of the feet (something low with room over it), not a wall. */
	private static boolean stepAhead(LocalPlayer p, Vec3 dir) {
		double len = Math.hypot(dir.x, dir.z);
		if (len < 1e-3) return false;
		double ax = p.getX() + dir.x / len * 0.5, az = p.getZ() + dir.z / len * 0.5;
		BlockPos foot = BlockPos.containing(ax, p.getY() + 0.05, az);
		if (!solid(foot)) return false;
		double top = topOf(foot);
		if (top - p.getY() > 1.05) return false; // (higher than a jump)
		return !solid(BlockPos.containing(ax, top + 0.05, az)) && !solid(BlockPos.containing(ax, top + 1.05, az));
	}

	/** jump() would hold space this tick (the same press going on, or a new one 8+ ticks after the last). */
	private boolean canJump() {
		long now = HumanAutopilot.ticks();
		return jumpHeldAt >= now - 1 || now - jumpPressedAt >= 8;
	}

	private void jump(InputDriver in) {
		long now = HumanAutopilot.ticks();
		if (jumpHeldAt == now - 1 || jumpHeldAt == now) {
			in.hold(InputDriver.opts().keyJump); // still the same press
			jumpHeldAt = now;
		} else if (now - jumpPressedAt >= 8) {
			in.hold(InputDriver.opts().keyJump);
			jumpPressedAt = now;
			jumpHeldAt = now;
		}
	}

	private long jumpHeldAt = -100, jumpPressedAt = -100, lowRoofUntil = -100, walkUntil = -100, setDownLogAt = -1000, lineUpSince = -1, setDownAt = -1000;
	private long walkOffLogAt = -1000, noSetDownUntil = -100, lastVertAt = -1000, takeOffNowUntil = -100;
	/** Ticks spent getting to a gap's height before going in (steer): for the log. */
	static long vertFirst;
	private String lastVert = "-";
	private Vec3 lastSteerGoal;
	private final long[] landedAt = new long[3];
	private int landedN;
	private long bounceUntil = -100;

	/** A roof within reach of the head somewhere just around us (we're in, or going into, a low space). */
	/**
	 * A low roof over us or in front of us at head height, the way we're going (up to 2 blocks on): a 3-high gap under
	 * the leaves we have to drop a little to get into. (Only the way we're going: leaves sticking out beside us aren't
	 * in the way, and going low for every one of them was the bobbing.)
	 */
	private static boolean roofAhead(LocalPlayer p, Vec3 goal) {
		Vec3 here = p.position();
		Vec3 dir = new Vec3(goal.x - here.x, 0, goal.z - here.z);
		double len = dir.length();
		if (len < 0.05) return lowRoofOver(here);
		dir = dir.scale(1 / len);
		for (double s = 0; s <= Math.min(2.0, len + 0.3); s += 0.5) {
			if (lowRoofOver(here.add(dir.scale(s)))) return true;
		}
		return false;
	}

	/**
	 * A low roof over a body at {@code pos} (feet there): a block at head height right over it, with open space under
	 * it (an overhang, a 2-high gap). A crater wall (solid all the way down) isn't one: counting walls made it twitch
	 * up and down in every TNT crater.
	 */
	/** A ceiling 3 over the feet, the cells below it open (a room's ceiling, at any corner of us). */
	private static boolean roomRoof(Vec3 pos) {
		int y = Mth.floor(pos.y + 3.0);
		for (double ox : new double[]{-0.29, 0.29}) {
			for (double oz : new double[]{-0.29, 0.29}) {
				BlockPos head = BlockPos.containing(pos.x + ox, y, pos.z + oz);
				if (solid(head) && !solid(head.below()) && !solid(head.below(2))) return true;
			}
		}
		return false;
	}

	private static boolean lowRoofOver(Vec3 pos) {
		int y = Mth.floor(pos.y + 2.0);
		for (double ox : new double[]{-0.29, 0.29}) {
			for (double oz : new double[]{-0.29, 0.29}) {
				BlockPos head = BlockPos.containing(pos.x + ox, y, pos.z + oz);
				if (solid(head) && !solid(head.below()) && !solid(head.below(2))) return true;
			}
		}
		return false;
	}

	/** Under a low roof, with where we're going near (6) and no higher than our feet: on foot is the way. */
	private boolean footWork(LocalPlayer p) {
		BlockPos goal = stand != null ? stand : target;
		if (goal == null) return false;
		// (The next block picked, its spot not worked out yet: near and no higher, stay on foot to see. Taking off before
		// that, then setting down beside the next pit of the ground plate, was 34 hops on the forest cathedral.)
		if (stand == null && target != null) {
			double tx = target.getX() + 0.5 - p.getX(), tz = target.getZ() + 0.5 - p.getZ();
			if (tx * tx + tz * tz < 16 && target.getY() <= Math.floor(p.getY() + 0.5) + 1 && target.getY() >= Math.floor(p.getY()) - 2) return true;
		}
		if (stand != null && !solid(stand.below())) return false; // (a spot in the air: flying)
		// (The way there crosses a gap in the floor: flying, not walking into it.)
		if (lastRoute != null && !floorUnder(lastRoute) && lastRoute.y > p.getY() - 1.5) return false;
		double dx = goal.getX() + 0.5 - p.getX(), dz = goal.getZ() + 0.5 - p.getZ();
		boolean nearLevel = goal.getY() <= Math.floor(p.getY() + 0.5);
		// A spot "just over the floor, looking up" (the underside of an arch): standing on the floor is the same thing.
		// (Hovering a hair over it, the up and down keys touched it 3 times a second: 70 landings on the gothic portal.)
		if (lowHover && nearLevel && dx * dx + dz * dz < 9) return true;
		// (Not a 3-high room too: tried, the cottage went from 43 landings to 75, set down to walk and off again at once.)
		if (!lowRoofOver(p.position())) return false;
		return dx * dx + dz * dz < 36 && nearLevel;
	}

	/**
	 * Feet at {@code feet}: floor all the way (flat) to {@code goal} at about this height: no gap to drop into on the
	 * way, no step up we'd have to jump (walking only, under a low roof).
	 */
	/**
	 * From {@code feet} (at the way point steered to now) on along the rest of the route's open-air path: floor all the
	 * way, nothing to jump up or drop into. (Set down for the next way point, the one after it a step up or over a gap:
	 * "on foot, no floor all the way to ...: flying" a tick later, all 5 of the wizard tower's set-downs.)
	 */
	private boolean walkRest(Vec3 feet) {
		// (No path kept: straight there is fine; the far way round, or out from under a roof first, isn't - set down
		// for its next step, the black hole's 20-cell way went on over a step 2 s later.)
		if (routePath == null) return routeStraight;
		Vec3 prev = feet;
		for (int i = Math.max(0, routeIdx + 1); i < routePath.size(); i++) {
			BlockPos c = routePath.get(i);
			double f = groundTop(c.getY() + 0.2, c.getX() + 0.5, c.getZ() + 0.5);
			if (Double.isNaN(f)) return false;
			Vec3 next = new Vec3(c.getX() + 0.5, f, c.getZ() + 0.5);
			if (!walkable(prev, next)) return false;
			prev = next;
		}
		return true;
	}

	private static boolean walkable(Vec3 feet, Vec3 goal) {
		double dx = goal.x - feet.x, dz = goal.z - feet.z;
		int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.5));
		for (int i = 1; i <= n; i++) {
			if (!walkColumn(feet.y, feet.x + dx * i / n, feet.z + dz * i / n)) return false;
		}
		return true;
	}

	/**
	 * Feet at {@code feet} can walk over (x, z): something to stand on there no more than a step up (0.6: a slab, a
	 * carpet, a trapdoor - their real tops, not the cell's) or 1.1 down, and nothing else in the body's way (1.8 up).
	 */
	/** Half a block on toward {@code goal}: a block a step up (at most 1.05 over the feet) with room over it to stand. */
	private static boolean stepUpAhead(LocalPlayer p, Vec3 goal) {
		double dx = goal.x - p.getX(), dz = goal.z - p.getZ(), len = Math.hypot(dx, dz);
		if (len < 0.3) return false;
		double x = p.getX() + dx / len * 0.6, z = p.getZ() + dz / len * 0.6, feet = p.getY();
		int fy = Mth.floor(feet + 0.01);
		BlockPos c = BlockPos.containing(x, fy, z);
		if (!standable(c)) return false;
		double top = fy + Terrain.state(c).getCollisionShape(Terrain.level(), c).max(Direction.Axis.Y);
		if (top <= feet + 0.6 || top > feet + 1.05) return false;
		return Terrain.passable(c.getX(), fy + 1, c.getZ()) && Terrain.passable(c.getX(), fy + 2, c.getZ())
				&& Terrain.passable(Mth.floor(p.getX()), fy + 2, Mth.floor(p.getZ()));
	}

	private static boolean walkColumn(double feet, double x, double z) {
		int bx = Mth.floor(x), bz = Mth.floor(z), fy = Mth.floor(feet);
		for (int y = fy + 2; y >= fy - 2; y--) {
			BlockPos c = new BlockPos(bx, y, bz);
			if (!standable(c)) continue;
			var shape = Terrain.state(c).getCollisionShape(Terrain.level(), c);
			double top = y + shape.max(Direction.Axis.Y), bottom = y + shape.min(Direction.Axis.Y);
			if (top <= feet + 0.6) return top >= feet - 1.1; // the ground there (a drop of more: no)
			// A step up a block (the cells over it open - the scan came down through them): a jump while walking, like a
			// player going up a slope. (Taking off for every step of a wooded hillside was the cottage's 30 landings.)
			if (top <= feet + 1.05 && y >= fy) return true;
			if (bottom < feet + 1.8) return false; // in the body's way
		}
		return false; // nothing to stand on within 2 down
	}

	/**
	 * Something the feet stop on: any collision at all. (solid() leaves out what a block can be put into - snow layers
	 * among them, 2-8 deep with a real top - so over snowy ground the gap under us read "3 or more" and down we went
	 * onto it: the snowy cottage's and taiga's "descend (gap 3.00)", landed the next tick.)
	 */
	private static boolean standable(BlockPos c) {
		BlockState s = Terrain.state(c);
		if (s.isAir()) return false;
		if (plan != null && handDoor(s)) return false;
		return !s.getCollisionShape(Terrain.level(), c).isEmpty();
	}

	/** Where the top of the block in {@code c} really is (a slab's half, farmland's 15/16, a fence's 1.5). */
	private static double topOf(BlockPos c) {
		var shape = Terrain.state(c).getCollisionShape(Terrain.level(), c);
		return c.getY() + (shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y));
	}

	/** The real top of the first thing to stand on at (x, z) at or under {@code fromY} (3 down at most), or NaN. */
	private static double groundTop(double fromY, double x, double z) {
		int bx = Mth.floor(x), bz = Mth.floor(z), fy = Mth.floor(fromY);
		for (int y = fy; y >= fy - 3; y--) {
			BlockPos c = new BlockPos(bx, y, bz);
			if (!standable(c)) continue;
			double top = y + Terrain.state(c).getCollisionShape(Terrain.level(), c).max(Direction.Axis.Y);
			if (top <= fromY + 0.01) return top;
		}
		return Double.NaN;
	}

	/** Something to stand on in the cell {@code goal} is in (a way point hugs its cell's bottom at 0.15, floor or not). */
	private static boolean floorUnder(Vec3 goal) {
		return solid(BlockPos.containing(goal.x, goal.y + 0.01, goal.z).below());
	}

	/** How far feet at height {@code feet} would be over the ground at (x, z): negative if the ground there is higher. */
	private static double gapAt(double feet, double x, double z) {
		int bx = Mth.floor(x), bz = Mth.floor(z), fy = Mth.floor(feet);
		for (int y = fy + 1; y >= fy - 3; y--) {
			BlockPos c = new BlockPos(bx, y, bz);
			if (!standable(c)) continue;
			// (At body height with more over it: a wall, not ground to rise over; the way round is the route's job.)
			if (y >= fy && solid(new BlockPos(bx, y + 1, bz))) return 3;
			return feet - topOf(c);
		}
		return 3;
	}

	/**
	 * The gap under our whole body where it will be a moment from now (shifted by (dx, dz)): sinking into a pit we were
	 * digging, drifting sideways at the same time, the body's edge caught the rim and down we were ("descend (gap
	 * 2.21)", landed 2 ticks later, the fantasy house's hill).
	 */
	/**
	 * The least gap under the body over the next few ticks' way (1, 2 and 4 ticks on at this speed), and half a block
	 * on toward where we're steering ({@code d}: speeding up from nearly still, the speed alone said nothing - out of a
	 * pit, dipping to the next way point, onto the ledge 0.4 on: the black hole's "descend (gap 3.00)" landings).
	 */
	private static double aheadGap(LocalPlayer p, Vec3 v, Vec3 d) {
		double g = Math.min(footprintGap(p, v.x, v.z), Math.min(footprintGap(p, v.x * 2, v.z * 2), footprintGap(p, v.x * 4, v.z * 4)));
		double hd = Math.hypot(d.x, d.z);
		if (hd > 0.2) {
			double k = Math.min(0.6, hd) / hd;
			g = Math.min(g, footprintGap(p, d.x * k, d.z * k));
		}
		return g;
	}

	private static double footprintGap(LocalPlayer p, double dx, double dz) {
		double best = 3;
		for (double ox : new double[]{-0.3, 0, 0.3}) {
			for (double oz : new double[]{-0.3, 0, 0.3}) {
				best = Math.min(best, gapAt(p.getY(), p.getX() + ox + dx, p.getZ() + oz + dz));
			}
		}
		return best;
	}

	/** How far the feet are above the nearest block top under any corner of us (up to 3). */
	private static double gapBelow(LocalPlayer p) {
		double y = p.getY(), best = 3;
		double[] offs = {-0.29, 0, 0.29};
		for (double ox : offs) {
			for (double oz : offs) {
				for (int dy = 0; dy <= 3; dy++) {
					BlockPos under = BlockPos.containing(p.getX() + ox, y - 0.01 - dy, p.getZ() + oz);
					if (!standable(under)) continue;
					// Only ground under us counts: a wall beside us (its top above our feet, when we hang a little
					// off-centre in a narrow hole) isn't something we're about to land on. Counting it kept pushing
					// us back up while trying to go down a hole: the "up and down a pillar" the user saw.
					// (Its real top: a slab, farmland, a trapdoor under us is lower than its cell's, and with our feet
					// in its cell it didn't count at all - "nothing under us", down we went onto it: the cottage's
					// "descend (gap 3.00)" and down in the next tick.)
					double top = topOf(under);
					if (top <= y + 0.01) best = Math.min(best, y - top);
					break;
				}
			}
		}
		return best;
	}

	/** Come to a stop where we are, a little above whatever is under us (touching down ends flight). */
	private void brake(LocalPlayer p, InputDriver in) {
		double y = p.getY();
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.05, p.getZ());
		if (solid(under)) y = Math.max(y, under.getY() + 1 + hov(under.above()));
		else if (solid(under.below())) y = Math.max(y, under.getY() + hov(under));
		steer(p, in, new Vec3(p.getX(), y, p.getZ()), false, 0.25);
	}

	/** Double-tap jump, like a player switching flight on. */
	private void takeOff(LocalPlayer p, InputDriver in) {
		// (The taps in ticks one after the other: a take-off left off for a while starts again from its first tap, not
		// counted as failed. Picked up where it was, the second tap came too late to be a double tap.)
		long now = HumanAutopilot.ticks();
		if (takeoffTick >= 0 && now - takeoffCallAt > 1) takeoffTick = -1;
		takeoffCallAt = now;
		takeoffTick++;
		if (takeoffTick > 16) {
			takeoffTick = 0; // didn't catch: try again
			takeoffFails++;
		}
		int t = takeoffTick;
		// A ceiling just over the head (leaves 2 up): the first jump is back on the ground by the 4th tick, and the second
		// tap there switches flying on and straight off again (on the ground). Quicker taps, the second while still up.
		// (The black hole: 60 s on the ground under a birch, "taking off" over and over.)
		// (Worked out once, from the ground, as the taps start: looked at each tick, the jump itself took the head up to
		// a ceiling 3 up, it read "low" from the 3rd tick on and the second tap - due on the 4th - never came. The
		// castle's hillside: 15 s jumping on the spot under it, 18 hops.)
		if (t == 0) takeoffLow = !Terrain.passable(Mth.floor(p.getX()), Mth.floor(p.getY() + 2.2), Mth.floor(p.getZ()));
		if (takeoffLow ? (t == 0 || t == 2) : (t <= 1 || (t >= 4 && t <= 5))) in.hold(InputDriver.opts().keyJump);
	}

	private boolean takeoffLow;

	private int takeoffFails;
	private long heightFirstSince = -1, heightFirstLastAt = -1000;
	/** The spot we're heading for, and whether it was open when we first headed for it (see the flight to it). */
	private BlockPos standOpenFor;
	private boolean standWasOpen;
	private long steerAt = -1;
	private String steerWhy = "-";
	private long takeoffCallAt = -100;

	/**
	 * Somewhere near to take off from: on this floor, room over the head (3 open), walkable to from here. For when the
	 * take-off keeps failing under a low roof. Null if none.
	 */
	private Vec3 roomToTakeOff(LocalPlayer p) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		int fy = Mth.floor(p.getY() + 0.05);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				int x = Mth.floor(p.getX()) + dx, z = Mth.floor(p.getZ()) + dz;
				if (!Terrain.passable(x, fy, z) || !Terrain.passable(x, fy + 1, z) || !Terrain.passable(x, fy + 2, z)) continue;
				Vec3 c = new Vec3(x + 0.5, p.getY(), z + 0.5);
				double d = c.distanceToSqr(p.position());
				if (d >= bestD || !walkable(p.position(), c)) continue;
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	private Vec3 walkOutTo;
	private long walkOutUntil = -1;
}
