package dev.humanautopilot.schematic;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.BoxFillModule;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Build Schematic: pick a file in the menu, see its outline where you're looking (the block you look at is the bottom
 * corner, the build goes away from you and to your right), rotate it, right-click to start. The building itself is
 * {@link BoxFillModule} with a plan: bottom layer first, row by row, real clicks.
 */
public final class SchematicMode {
	private static final SchematicMode INSTANCE = new SchematicMode();

	public static SchematicMode get() {
		return INSTANCE;
	}

	private SchematicLoader.Schematic schem;
	private String fileName;
	private int rot; // quarter turns clockwise
	private boolean preview;
	private BlockPos min; // the build's lowest corner in the world (while previewing: where we look)
	/**
	 * The schematic's own ground (grass and dirt layers under the house): left out, and the build sits on the real
	 * ground instead. Building thousands of dirt blocks the world already has was most of the time on some builds.
	 */
	public static boolean skipGround = true;
	private int groundLayers;
	/**
	 * Knock out what's in the way (a different block where the schematic has one, anything where it has air), so the
	 * result is exactly the schematic, like pasting it. Off: other blocks are left alone and those spots skipped.
	 */
	public static boolean replace = true;
	/** The corner of the build that's going on (for {@link #wantAt}). */
	private BlockPos built;
	/**
	 * When a build is done, on to the next file in the folder (after the last one, back to the first), on open ground
	 * beside it (the user: "build schematic indefinitly, if its done building schematic 1, it goes to 2nd
	 * automatically"). Off: she stays and guards the one that's done.
	 */
	public static boolean keepGoing = true;
	/**
	 * A build going (not paused) when this was last written: the game closed in the middle of it (crashed, or closed
	 * for an update), and she carries on with it by herself once she's back in that world - LiveKeeper opening the game
	 * again, nobody at the keys. Off, paused or done: not.
	 */
	private static boolean wasBuilding;
	/** This game's look at wasBuilding is done (till then it stays as the last game left it). */
	private boolean startChecked;
	/** The connection the 5-second carry-on was last checked for: a new one (rejoined, server back after a crash) checks again. */
	private Object checkedConn;
	private long inWorldAt = -1;
	/** Which way the row of builds goes: to the right of the first one (the way it was laid out from where we stood). */
	private Direction rowDir = Direction.EAST;
	private static final int GAP = 6, PUSHES = 6;
	private long doneAt = -1;
	private String nextFile;
	/** The file picked (at random) to go on to once this build's done; null until it's asked for. */
	private String pickedNext;
	/** The last few files built, newest last: the random pick leaves them out while there are others. */
	private final java.util.ArrayDeque<String> recentBuilt = new java.util.ArrayDeque<>();
	private SchematicLoader.Schematic nextSchem;
	private int nextGround, spotTry;
	/** A spot on a hillside for the next one: used if there's nowhere flatter. */
	private BlockPos steepSpot;
	/** The first spot too far off to be loaded (flown over to, then looked at again), and how many times we've gone. */
	private BlockPos farSpot;
	private int visits;

	private SchematicMode() {}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("schematics");
	}

	/** The schematic files in the folder, by name. */
	public List<String> files() {
		List<String> out = new ArrayList<>();
		try {
			Files.createDirectories(dir());
			try (var s = Files.list(dir())) {
				s.filter(f -> Files.isRegularFile(f)).map(f -> f.getFileName().toString())
						.filter(n -> {
							String l = n.toLowerCase(Locale.ROOT);
							return l.endsWith(".schem") || l.endsWith(".schematic") || l.endsWith(".litematic");
						})
						.sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
			}
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] can't read {}: {}", dir(), e.toString());
		}
		return out;
	}

	public boolean previewing() {
		return preview;
	}

	public String fileName() {
		return fileName;
	}

	/** Load a file and show where it would go. Null if it worked, else what went wrong. */
	public String choose(String file) {
		try {
			schem = SchematicLoader.load(dir().resolve(file).toFile());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't load {}", file, e);
			return e.getMessage() == null ? e.toString() : e.getMessage();
		}
		return startPreview(schem, file);
	}

	/** Show this schematic's outline (also used by the tests with a schematic made in code). */
	public String startPreview(SchematicLoader.Schematic s, String name) {
		keepNow(); // (the one going on stays on the list, to carry on with later)
		schem = s;
		fileName = name;
		rot = 0;
		preview = true;
		min = null;
		groundLayers = groundLayers(s);
		BoxFillModule.get().stopPlan();
		HumanAutopilot.LOGGER.info("[schem] {}: {}x{}x{}, {} ground layer(s) at the bottom", name, s.width, s.height, s.length, groundLayers);
		HumanAutopilot.message("§e" + name + "§r (" + s.width + "x" + s.height + "x" + s.length + "): look where it goes, §aright-click§r to build, §e"
				+ HumanAutopilot.keyName("rotate") + "§r to turn it.");
		return null;
	}

	public void rotate() {
		if (!preview) return;
		rot = (rot + 1) & 3;
	}

	/** Back out of the preview and stop a build. */
	public void stop() {
		keepNow();
		preview = false;
		nextSchem = null;
		doneAt = -1;
		BoxFillModule.get().stopPlan();
	}

	/** How many bottom layers we leave out. */
	private int skip() {
		return skipGround ? groundLayers : 0;
	}

	/** Bottom layers that are just ground: mostly filled, and mostly grass / dirt / stone / sand. */
	private static int groundLayers(SchematicLoader.Schematic s) {
		int n = 0;
		for (int y = 0; y < s.height - 1; y++) {
			int built = 0, terrain = 0;
			for (int z = 0; z < s.length; z++) {
				for (int x = 0; x < s.width; x++) {
					BlockState b = s.blockAt(x, y, z);
					if (b == null || !Placement.buildable(b)) continue;
					built++;
					if (terrain(b)) terrain++;
				}
			}
			if (built < s.width * s.length * 0.5 || terrain < built * 0.7) break;
			n++;
		}
		return n;
	}

	static boolean terrain(BlockState b) {
		if (b.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || b.is(net.minecraft.world.level.block.Blocks.DIRT)
				|| b.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) || b.is(net.minecraft.world.level.block.Blocks.PODZOL)
				|| b.is(net.minecraft.world.level.block.Blocks.MYCELIUM) || b.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
				|| b.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK) || b.is(net.minecraft.world.level.block.Blocks.MUD)
				|| b.is(net.minecraft.world.level.block.Blocks.STONE) || b.is(net.minecraft.world.level.block.Blocks.DEEPSLATE)
				|| b.is(net.minecraft.world.level.block.Blocks.SAND) || b.is(net.minecraft.world.level.block.Blocks.RED_SAND)
				|| b.is(net.minecraft.world.level.block.Blocks.SANDSTONE) || b.is(net.minecraft.world.level.block.Blocks.TUFF)
				|| b.is(net.minecraft.world.level.block.Blocks.GRANITE) || b.is(net.minecraft.world.level.block.Blocks.DIORITE)
				|| b.is(net.minecraft.world.level.block.Blocks.NETHERRACK)) return true;
		return b.is(net.minecraft.tags.BlockTags.DIRT) || b.is(net.minecraft.tags.BlockTags.SAND)
				|| b.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || b.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)
				|| b.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || b.is(net.minecraft.world.level.block.Blocks.FARMLAND)
				|| b.is(net.minecraft.world.level.block.Blocks.GRAVEL) || b.is(net.minecraft.world.level.block.Blocks.CLAY)
				|| b.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || b.is(net.minecraft.world.level.block.Blocks.BEDROCK);
	}

	private int sizeX() {
		return (rot & 1) == 0 ? schem.width : schem.length;
	}

	private int sizeZ() {
		return (rot & 1) == 0 ? schem.length : schem.width;
	}

	/** Each tick (no screen open): while previewing, follow the crosshair, draw the outline, start on a right-click. */
	public void tick(Minecraft mc, LocalPlayer p) {
		carryOnAfterRestart();
		keep();
		chain();
		if (!preview || schem == null) return;
		HitResult hr = mc.hitResult;
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
			BlockPos corner = bh.getBlockPos().relative(bh.getDirection());
			// Away from us and to our right, starting at the block we look at.
			Direction f = p.getDirection(), r = f.getClockWise();
			int sx = f.getAxis() == Direction.Axis.X ? f.getStepX() : r.getStepX();
			int sz = f.getAxis() == Direction.Axis.Z ? f.getStepZ() : r.getStepZ();
			int x0 = sx > 0 ? corner.getX() : corner.getX() - sizeX() + 1;
			int z0 = sz > 0 ? corner.getZ() : corner.getZ() - sizeZ() + 1;
			min = new BlockPos(x0, corner.getY(), z0);
			Gizmos.cuboid(new AABB(min.getX(), min.getY() - skip(), min.getZ(), min.getX() + sizeX(), min.getY() + schem.height - skip(), min.getZ() + sizeZ()),
					GizmoStyle.stroke(0xFF40E0FF, 2.5f));
			Gizmos.cuboid(corner, GizmoStyle.fill(0x6040E0FF));
		} else {
			min = null;
		}
		var use = mc.options.keyUse;
		boolean clicked = false;
		while (use.consumeClick()) clicked = true;
		if (clicked) {
			use.setDown(false); // this click is ours: don't let the game place a block with it
			if (min == null) {
				HumanAutopilot.message("Look at a block first: that's where the build's corner goes.");
				return;
			}
			rowDir = p.getDirection().getClockWise();
			confirm(-1, -1);
		}
	}

	/** Start building at {@code min}; {@code done} / {@code total}: how far it had got before (a build carried on), or -1. */
	private void confirm(int done, int total) {
		Map<BlockPos, BlockState> plan = plan(min);
		preview = false;
		pickedNext = null; // (picked afresh when this one's done)
		if (plan.isEmpty()) {
			HumanAutopilot.message("That schematic has nothing to build.");
			return;
		}
		CreativePicker.get().forgetFailures();
		built = min;
		// (A flat world: the ground under the build is the grass filler's, not ours - see BoxFillModule.jobAt.)
		var lvl = Minecraft.getInstance().level;
		// (A flat world's horizon is the bottom of the world: there's no isFlat to ask any more.)
		boolean flat = lvl != null && lvl.getLevelData() instanceof net.minecraft.client.multiplayer.ClientLevel.ClientLevelData d
				&& d.getHorizonHeight(lvl) <= lvl.getMinY();
		BoxFillModule.setLeaveGround(flat);
		BoxFillModule.get().startPlan(plan, fileName, replace ? this::wantAt : null, min.getY());
		// (The plan to the server too, if it has the companion plugin: she helps from the other side of it.)
		if (Files.isRegularFile(dir().resolve(fileName))) {
			dev.humanautopilot.net.BuildLink.start(fileName, plan, replace, flat ? min.getY() : Integer.MIN_VALUE, flat ? emptyUnderFloor() : List.of());
		}
		keptDone = done;
		keptTotal = total >= 0 ? total : plan.size();
		keptAlready = -1;
		seenSavedAt = HumanAutopilot.ticks();
		keptAt = HumanAutopilot.ticks();
		keptGone = false;
		save();
		HumanAutopilot.LOGGER.info("[schem] building {} ({} blocks) at {}, turned {}x", fileName, plan.size(), min.toShortString(), rot);
		HumanAutopilot.message("Building §e" + fileName + "§r (" + plan.size() + " blocks).");
	}

	// ------------------------------------------------------------------ one build after another

	/** The file it goes on to when the one being built is done, or null (it stays and guards it). */
	public String nextName() {
		if (!keepGoing || built == null || fileName == null || !Files.isRegularFile(dir().resolve(fileName))) return null;
		List<String> all = files();
		if (all.isEmpty()) return null;
		if (pickedNext == null || !all.contains(pickedNext) || (pickedNext.equals(fileName) && all.size() > 1)) pickedNext = pickRandom(all);
		return pickedNext;
	}

	/**
	 * A random file to go on to (the user: "he chooses a random next one to build instead of going by order"): not
	 * the one just built, and not one of the last few while there are others.
	 */
	private String pickRandom(List<String> all) {
		List<String> fresh = new ArrayList<>(), other = new ArrayList<>();
		for (String f : all) {
			if (f.equals(fileName)) continue;
			(recentBuilt.contains(f) ? other : fresh).add(f);
		}
		List<String> from = !fresh.isEmpty() ? fresh : !other.isEmpty() ? other : all;
		return from.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(from.size()));
	}

	/**
	 * Each tick: once the build's done (guarding, nothing broken), a few seconds later on to the next file: load it, then
	 * one spot a tick beside the last build (on along the row first, then the other ways) until one is open ground.
	 */
	private void chain() {
		BoxFillModule bx = BoxFillModule.get();
		if (!keepGoing || preview || built == null || schem == null || !bx.planMode() || !bx.guarding() || bx.paused()
				|| bx.holesLeft() > 0 || fileName == null || !fileName.equals(bx.planName())) {
			doneAt = -1;
			nextSchem = null;
			return;
		}
		if (bx.visiting()) return; // (flying over to a spot that wasn't loaded)
		long now = HumanAutopilot.ticks();
		if (doneAt < 0) doneAt = now;
		if (now - doneAt < 60) return; // (a moment to see it's done)
		if (nextSchem == null) {
			if (!Files.isRegularFile(dir().resolve(fileName))) { // (a test build, not from a file)
				doneAt = now + 1200;
				return;
			}
			List<String> all = files();
			// The random pick first (the one the "Done building" message named), then the rest in a random order.
			List<String> order = new ArrayList<>(all);
			java.util.Collections.shuffle(order);
			String first = nextName();
			if (first != null) {
				order.remove(first);
				order.add(0, first);
			}
			if (order.size() > 1) {
				order.remove(fileName);
				order.add(fileName); // (the one just built only if nothing else loads)
			}
			for (int k = 0; k < order.size() && nextSchem == null; k++) {
				String f = order.get(k);
				try {
					nextSchem = SchematicLoader.load(dir().resolve(f).toFile());
					nextFile = f;
				} catch (Exception e) {
					HumanAutopilot.LOGGER.warn("[schem] next up {} won't load: {}", f, e.toString());
				}
			}
			if (nextSchem == null) {
				doneAt = now + 1200; // (try again in a minute: maybe a file gets put in)
				return;
			}
			nextGround = groundLayers(nextSchem);
			spotTry = 0;
			steepSpot = null;
			farSpot = null;
			visits = 0;
			HumanAutopilot.LOGGER.info("[schem] {} done: next up {} ({}x{}x{}), looking for open ground beside it", fileName, nextFile,
					nextSchem.width, nextSchem.height, nextSchem.length);
			return; // (the spots from the next tick: loading a big one takes a moment)
		}
		int nx = (rot & 1) == 0 ? nextSchem.width : nextSchem.length, nz = (rot & 1) == 0 ? nextSchem.length : nextSchem.width;
		Direction[] ways = {rowDir, rowDir.getClockWise(), rowDir.getCounterClockWise(), rowDir.getOpposite()};
		if (spotTry >= ways.length * PUSHES) {
			if (steepSpot != null) {
				moveOn(steepSpot);
				return;
			}
			// Too far off to tell what's there: fly over, then look again (twice at most).
			if (farSpot != null && visits < 2) {
				visits++;
				HumanAutopilot.LOGGER.info("[schem] next up {}: the spot at {} isn't loaded from here, flying over to look", nextFile, farSpot.toShortString());
				bx.visit(farSpot);
				spotTry = 0;
				farSpot = null;
				return;
			}
			HumanAutopilot.LOGGER.info("[schem] no open ground for {} beside {}: showing it to place by hand", nextFile, fileName);
			HumanAutopilot.message("No open ground for §e" + nextFile + "§r beside " + fileName
					+ " (only grass, trees, plants and water may be in the way, never a build).");
			String f = nextFile;
			var s = nextSchem;
			nextSchem = null;
			doneAt = -1;
			startPreview(s, f);
			return;
		}
		Direction d = ways[spotTry / PUSHES];
		int push = (spotTry % PUSHES) * 8;
		spotTry++;
		// Beside the last one, with a gap, lined up with it; further along if that's taken.
		int x0, z0;
		switch (d) {
			case EAST -> { x0 = built.getX() + sizeX() + GAP + push; z0 = built.getZ(); }
			case WEST -> { x0 = built.getX() - GAP - push - nx; z0 = built.getZ(); }
			case SOUTH -> { x0 = built.getX(); z0 = built.getZ() + sizeZ() + GAP + push; }
			default -> { x0 = built.getX(); z0 = built.getZ() - GAP - push - nz; }
		}
		OpenGround.Look look = OpenGround.look(x0, z0, nx, nz, nextSchem.height, skipGround ? nextGround : 0);
		HumanAutopilot.LOGGER.info("[schem] next up {}: {} +{} at {} {}: {}{}", nextFile, d.getSerializedName(), push, x0, z0, look.verdict(),
				look.why() == null ? "" : " (" + dev.humanautopilot.pathing.Terrain.state(look.why()) + " at " + look.why().toShortString() + ")");
		if (look.verdict() == OpenGround.Verdict.STEEP && steepSpot == null) steepSpot = new BlockPos(x0, look.y(), z0);
		if (look.verdict() == OpenGround.Verdict.NOT_LOADED && farSpot == null) farSpot = new BlockPos(x0 + nx / 2, built.getY(), z0 + nz / 2);
		if (look.verdict() == OpenGround.Verdict.OPEN) moveOn(new BlockPos(x0, look.y(), z0));
	}

	/** Start the next file at {@code at} (its corner, like the block over the one looked at). */
	private void moveOn(BlockPos at) {
		String from = fileName;
		List<String> all = files();
		schem = nextSchem;
		fileName = nextFile;
		pickedNext = null;
		recentBuilt.remove(from);
		recentBuilt.addLast(from);
		while (recentBuilt.size() > 3) recentBuilt.removeFirst();
		groundLayers = nextGround;
		min = at;
		nextSchem = null;
		doneAt = -1;
		int i = all.indexOf(fileName) + 1;
		HumanAutopilot.LOGGER.info("[schem] on to {} ({} of {}) at {}, after {}", fileName, i, all.size(), at.toShortString(), from);
		HumanAutopilot.message("§aNext up:§r §e" + fileName + "§r (" + i + " of " + all.size() + ").");
		confirm(-1, -1);
	}

	// ------------------------------------------------------------------ builds left unfinished (carried on later)

	/**
	 * Every build started from a file, until it's done: a small file each in builds/ (the user: "often time i get bored
	 * of it building then start building a new schematic ... i cant go back and have it continue building the previous
	 * build"). Picked again in the menu (Numpad 9 -> Unfinished builds) it carries on where it was: what's in is skipped.
	 */
	private static Path buildsDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("builds");
	}

	/** (The one build kept before there was a list: moved into it.) */
	private static Path oldSaveFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("current-build.properties");
	}

	/** Which world / server we're in (a build is only offered again there). */
	private static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		var server = mc.getCurrentServer();
		return server != null ? "mp:" + server.ip : "?";
	}

	/** A build not finished: its file, its corner, how far it got (done of total, -1: not counted), when last built on. */
	public record Unfinished(Path record, String file, BlockPos at, int done, int total, int already, long when, java.util.Properties props) {
		/** How far it got, from what was already right when it was started (the world's own ground under it). */
		public int percent() {
			int base = Math.max(0, already);
			if (total <= 0 || done < 0) return -1;
			return total - base <= 0 ? 100 : (int) Math.floor(Math.max(0, done - base) * 100.0 / (total - base));
		}
	}

	private static String recordName(String world, String file, BlockPos at) {
		String key = world + "|" + file + "|" + at.getX() + "," + at.getY() + "," + at.getZ();
		String base = file.replaceAll("[^A-Za-z0-9_-]+", "_");
		if (base.length() > 40) base = base.substring(0, 40);
		return base + "-" + Integer.toHexString(key.hashCode()) + ".properties";
	}

	/** What's been seen built of a build (BlockPos longs, gzipped), beside its record. */
	private static Path seenFile(Path record) {
		String n = record.getFileName().toString();
		return record.resolveSibling(n.substring(0, n.length() - ".properties".length()) + ".seen");
	}

	private long seenSavedAt = -1;

	/** Write down what's been seen built of the build going on (every 2 minutes, and before it stops). */
	private void saveSeen() {
		if (built == null || fileName == null || !Files.isRegularFile(dir().resolve(fileName))) return;
		long[] keys = BoxFillModule.get().seenBuiltNow();
		Path f = seenFile(buildsDir().resolve(recordName(worldKey(), fileName, built)));
		try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(f))))) {
			out.writeInt(keys.length);
			for (long k : keys) out.writeLong(k);
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't save what's been built: {}", e.toString());
		}
		seenSavedAt = HumanAutopilot.ticks();
	}

	private static long[] loadSeen(Path record) {
		Path f = seenFile(record);
		if (!Files.isRegularFile(f)) return null;
		try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(f))))) {
			int n = in.readInt();
			if (n < 0 || n > 50_000_000) return null;
			long[] keys = new long[n];
			for (int i = 0; i < n; i++) keys[i] = in.readLong();
			return keys;
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't read what was built last time: {}", e.toString());
			return null;
		}
	}

	/** How far the build going on had got when last written down (-1: not counted yet), and when. */
	private int keptDone = -1, keptTotal = -1, keptAlready = -1;
	private long keptAt = -1;
	private boolean keptGone;

	/** The build going on is ours (from a file) and not stopped. */
	private boolean building() {
		BoxFillModule bx = BoxFillModule.get();
		return built != null && fileName != null && bx.planMode() && fileName.equals(bx.planName())
				&& Files.isRegularFile(dir().resolve(fileName));
	}

	/** Write down the build going on (file, corner, turn, settings, how far it's got), to carry on with later. */
	private void save() {
		if (built == null || fileName == null || !Files.isRegularFile(dir().resolve(fileName))) return; // a test build, not from a file
		var props = new java.util.Properties();
		props.setProperty("file", fileName);
		props.setProperty("x", String.valueOf(built.getX()));
		props.setProperty("y", String.valueOf(built.getY()));
		props.setProperty("z", String.valueOf(built.getZ()));
		props.setProperty("rot", String.valueOf(rot));
		props.setProperty("skipGround", String.valueOf(skipGround));
		props.setProperty("replace", String.valueOf(replace));
		props.setProperty("rowDir", rowDir.getSerializedName());
		props.setProperty("done", String.valueOf(keptDone));
		props.setProperty("total", String.valueOf(keptTotal));
		props.setProperty("already", String.valueOf(keptAlready));
		props.setProperty("when", String.valueOf(System.currentTimeMillis()));
		props.setProperty("world", worldKey());
		try {
			Files.createDirectories(buildsDir());
			try (var out = Files.newBufferedWriter(buildsDir().resolve(recordName(worldKey(), fileName, built)))) {
				props.store(out, "Human Autopilot: a build not finished yet (Numpad 9 -> Unfinished builds)");
			}
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't save the build: {}", e.toString());
		}
	}

	/** Each tick: every 20 s, how far the build has got; once it's done, off the list. */
	private void keep() {
		if (!building()) return;
		BoxFillModule bx = BoxFillModule.get();
		if (bx.guarding()) {
			if (!keptGone) {
				keptGone = true;
				// (The plan stays with the server: the companion guards her side and puts TNT damage right too, till the
				// build's stopped or the next one starts - the user: "and the companion also repairs yes?")
				try {
					Path rec = buildsDir().resolve(recordName(worldKey(), fileName, built));
					// (Kept as the last one done, off the list: back in the game after a crash, she goes on from it to
					// the next file - see carryOnAfterRestart.)
					if (Files.isRegularFile(rec)) Files.move(rec, lastDoneFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					Files.deleteIfExists(seenFile(rec));
				} catch (Exception ignored) {
				}
			}
			return;
		}
		long now = HumanAutopilot.ticks();
		if (bx.planCounted() && now - seenSavedAt > 2400) saveSeen();
		if (now - keptAt < 400 || !bx.planCounted()) return;
		keptAt = now;
		if (bx.planDone() == keptDone && bx.planTotal() == keptTotal && bx.planAlready() == keptAlready) return;
		keptDone = bx.planDone();
		keptTotal = bx.planTotal();
		keptAlready = bx.planAlready();
		save();
	}

	/** Before it stops (another build, Off, panic): how far this one got, written down. */
	private void keepNow() {
		if (!building() || BoxFillModule.get().guarding()) return;
		BoxFillModule bx = BoxFillModule.get();
		if (bx.planCounted()) {
			keptDone = bx.planDone();
			keptTotal = bx.planTotal();
			keptAlready = bx.planAlready();
			saveSeen();
		}
		save();
	}

	/** The builds in this world not finished yet, last built on first (not the one going on now). */
	public List<Unfinished> unfinished() {
		migrate();
		List<Unfinished> out = new ArrayList<>();
		String world = worldKey();
		String now = building() ? recordName(world, fileName, built) : null;
		if (!Files.isDirectory(buildsDir())) return out;
		try (var s = Files.list(buildsDir())) {
			for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".properties")).toList()) {
				if (f.getFileName().toString().equals(now)) continue;
				var props = new java.util.Properties();
				try (var in = Files.newBufferedReader(f)) {
					props.load(in);
				} catch (Exception e) {
					continue;
				}
				if (!world.equals(props.getProperty("world")) || props.getProperty("file") == null) continue;
				try {
					BlockPos at = new BlockPos(Integer.parseInt(props.getProperty("x")), Integer.parseInt(props.getProperty("y")),
							Integer.parseInt(props.getProperty("z")));
					out.add(new Unfinished(f, props.getProperty("file"), at, Integer.parseInt(props.getProperty("done", "-1")),
							Integer.parseInt(props.getProperty("total", "-1")), Integer.parseInt(props.getProperty("already", "-1")),
							Long.parseLong(props.getProperty("when", "0")), props));
				} catch (Exception e) {
					HumanAutopilot.LOGGER.warn("[schem] can't read the build in {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] can't read {}: {}", buildsDir(), e.toString());
		}
		out.sort((a, b) -> Long.compare(b.when(), a.when()));
		return out;
	}

	private static void migrate() {
		Path old = oldSaveFile();
		if (!Files.isRegularFile(old)) return;
		try {
			var props = new java.util.Properties();
			try (var in = Files.newBufferedReader(old)) {
				props.load(in);
			}
			BlockPos at = new BlockPos(Integer.parseInt(props.getProperty("x")), Integer.parseInt(props.getProperty("y")),
					Integer.parseInt(props.getProperty("z")));
			Files.createDirectories(buildsDir());
			Path to = buildsDir().resolve(recordName(props.getProperty("world", "?"), props.getProperty("file"), at));
			if (!Files.exists(to)) {
				try (var out = Files.newBufferedWriter(to)) {
					props.store(out, "Human Autopilot: a build not finished yet (Numpad 9 -> Unfinished builds)");
				}
			}
			Files.delete(old);
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't move the saved build into the list: {}", e.toString());
		}
	}

	/** The build last worked on in this world that isn't finished (file name), or null. */
	public String savedBuild() {
		List<Unfinished> u = unfinished();
		return u.isEmpty() ? null : u.get(0).file();
	}

	/**
	 * Five seconds into the first world of this game: the build she was in the middle of when the game closed, carried
	 * on with (the user: "restart minecraft and it should start building automatically"). After that: whether one's
	 * going, written down when it changes.
	 */
	private void carryOnAfterRestart() {
		BoxFillModule box = BoxFillModule.get();
		// Every join, not just the game's first world: back on the server after it crashed or after leaving, the same
		// 5 seconds and on with the build (the user: "make sure the 5 second applies to autopilot to build next or current
		// schematic").
		Object conn = Minecraft.getInstance().getConnection();
		if (conn != checkedConn) {
			checkedConn = conn;
			startChecked = false;
			inWorldAt = -1;
		}
		if (!startChecked) {
			if (inWorldAt < 0) inWorldAt = HumanAutopilot.ticks();
			if (HumanAutopilot.ticks() - inWorldAt < 100) return;
			startChecked = true;
			// (Not in the tests' game: their run folder keeps the last run's builds.)
			if (!wasBuilding || box.on() || preview || FabricLoader.getInstance().isDevelopmentEnvironment()) return;
			String name = savedBuild();
			if (name != null) {
				String err = resume();
				HumanAutopilot.LOGGER.info("[schem] back in the game in the middle of {}: {}", name, err == null ? "carrying on with it" : "couldn't carry on: " + err);
				HumanAutopilot.message(err == null ? "Carrying on with §e" + name + "§r."
						: "Couldn't carry on with " + name + ": " + err);
				return;
			}
			// Nothing unfinished: the last one was done. Back to it (all in: she guards it a moment) and Keep going takes her
			// on to the next file beside it.
			Unfinished last = keepGoing ? lastDone() : null;
			if (last != null) {
				String err = resume(last);
				HumanAutopilot.LOGGER.info("[schem] back in the game after {} was done: {}", last.file(), err == null ? "on to the next one from there" : "couldn't: " + err);
				if (err == null) HumanAutopilot.message("Back to building: next one after §e" + last.file() + "§r.");
			}
			return;
		}
		boolean now = box.planMode() && !box.paused();
		if (now != wasBuilding) {
			wasBuilding = now;
			saveSettings();
		}
	}

	private static Path lastDoneFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("last-done.properties");
	}

	/** The last build finished in this world, or null. */
	private static Unfinished lastDone() {
		Path f = lastDoneFile();
		if (!Files.isRegularFile(f)) return null;
		var props = new java.util.Properties();
		try (var in = Files.newBufferedReader(f)) {
			props.load(in);
			if (!worldKey().equals(props.getProperty("world")) || props.getProperty("file") == null) return null;
			BlockPos at = new BlockPos(Integer.parseInt(props.getProperty("x")), Integer.parseInt(props.getProperty("y")),
					Integer.parseInt(props.getProperty("z")));
			return new Unfinished(f, props.getProperty("file"), at, Integer.parseInt(props.getProperty("done", "-1")),
					Integer.parseInt(props.getProperty("total", "-1")), Integer.parseInt(props.getProperty("already", "-1")),
					Long.parseLong(props.getProperty("when", "0")), props);
		} catch (Exception e) {
			return null;
		}
	}

	/** Carry on with the build last worked on here. */
	public String resume() {
		List<Unfinished> u = unfinished();
		if (u.isEmpty()) return "no build saved for this world";
		return resume(u.get(0));
	}

	/** Carry on with this one: same file, same place, same turn. Blocks already there are skipped. */
	public String resume(Unfinished u) {
		var props = u.props();
		String file = u.file();
		SchematicLoader.Schematic s;
		try {
			s = SchematicLoader.load(dir().resolve(file).toFile());
		} catch (Exception e) {
			return "couldn't load " + file + ": " + e.getMessage();
		}
		keepNow(); // (the one going on now stays on the list too)
		schem = s;
		fileName = file;
		rot = Integer.parseInt(props.getProperty("rot", "0")) & 3;
		skipGround = Boolean.parseBoolean(props.getProperty("skipGround", "true"));
		replace = Boolean.parseBoolean(props.getProperty("replace", "true"));
		Direction d = Direction.byName(props.getProperty("rowDir", "east"));
		rowDir = d != null && d.getAxis().isHorizontal() ? d : Direction.EAST;
		groundLayers = groundLayers(schem);
		min = u.at();
		preview = false;
		HumanAutopilot.LOGGER.info("[schem] carrying on with {} at {} ({}% when it was left)", file, min.toShortString(), u.percent());
		confirm(u.done(), u.total());
		// (The bar from where it was first started: what was built before counts. Not known - a build kept before this
		// was written down - from nothing; never counted at all: counted now, like a new one.)
		int already = u.already() >= 0 ? u.already() : u.done() >= 0 ? 0 : -1;
		BoxFillModule.get().setPlanAlready(already);
		keptAlready = already;
		long[] seen = loadSeen(u.record());
		if (seen != null) BoxFillModule.get().seedSeenBuilt(seen);
		seenSavedAt = HumanAutopilot.ticks();
		return null;
	}

	/** Take a build off the list (the menu's x). */
	public void forget(Unfinished u) {
		try {
			Files.deleteIfExists(u.record());
			Files.deleteIfExists(seenFile(u.record()));
			HumanAutopilot.LOGGER.info("[schem] forgot the unfinished {} at {}", u.file(), u.at().toShortString());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't forget {}: {}", u.record(), e.toString());
		}
	}

	// ------------------------------------------------------------------ settings kept between games

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("schematic-settings.properties");
	}

	static {
		try (var in = Files.newBufferedReader(settingsFile())) {
			var props = new java.util.Properties();
			props.load(in);
			keepGoing = Boolean.parseBoolean(props.getProperty("keepGoing", "true"));
			wasBuilding = Boolean.parseBoolean(props.getProperty("building", "false"));
		} catch (Exception ignored) {
			// (none yet: the defaults)
		}
	}

	/** The menu's Keep going button: remembered for next time. */
	public static void setKeepGoing(boolean on) {
		keepGoing = on;
		saveSettings();
	}

	private static void saveSettings() {
		var props = new java.util.Properties();
		props.setProperty("keepGoing", String.valueOf(keepGoing));
		props.setProperty("building", String.valueOf(wasBuilding));
		try {
			Files.createDirectories(settingsFile().getParent());
			try (var out = Files.newBufferedWriter(settingsFile())) {
				props.store(out, "Human Autopilot: Build Schematic settings");
			}
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[schem] couldn't save the settings: {}", e.toString());
		}
	}

	/**
	 * Per column of the schematic (x * length + z): part of the build, or within {@link #YARD} of it. The schematic's
	 * own ground (grass, dirt, stone in its ground layers) and plants on it are only built there: some come on a plate
	 * of ground far bigger than the build (the user: "some schematic is 30x30 and the grass it's like 50x50"). Floors,
	 * paths and farmland count as the build, wherever they are. Null: all of it.
	 */
	private boolean[] kept;
	/** Per column: part of the build itself (no yard round it). Null: all of it. */
	private boolean[] core;
	private static final int YARD = 3;

	private static boolean[] keptColumns(SchematicLoader.Schematic s, int groundLayers) {
		int w = s.width, l = s.length;
		if (groundLayers <= 0) return null;
		boolean[] build = new boolean[w * l];
		boolean any = false;
		for (int y = 0; y < s.height; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					BlockState b = s.blockAt(x, y, z);
					if (b == null || !Placement.buildable(b)) continue;
					if (y < groundLayers && plainGround(b)) continue; // its ground
					if (loose(b)) continue; // a flower, grass, a torch: not what makes it the build
					build[x * l + z] = true;
					any = true;
				}
			}
		}
		if (!any) return null; // (all ground: then it's all wanted)
		boolean[] out = new boolean[w * l];
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < l; z++) {
				if (!build[x * l + z]) continue;
				for (int dx = -YARD; dx <= YARD; dx++) {
					for (int dz = -YARD; dz <= YARD; dz++) {
						int nx = x + dx, nz = z + dz;
						if (nx >= 0 && nz >= 0 && nx < w && nz < l) out[nx * l + nz] = true;
					}
				}
			}
		}
		return out;
	}

	/** Plain ground: grass, dirt, stone, sand... (not paths, farmland or gravel: those are made, and kept). */
	/** Plain ground - grass, dirt, stone, sand, mud... (not paths, farmland or gravel: those are made). */
	public static boolean plainGroundBlock(BlockState b) {
		return plainGround(b);
	}

	private static boolean plainGround(BlockState b) {
		if (b.is(Blocks.DIRT_PATH) || b.is(Blocks.FARMLAND) || b.is(Blocks.GRAVEL)) return false;
		return terrain(b);
	}

	/** Something small with nothing to stand on (a flower, grass, a torch). */
	private static boolean loose(BlockState b) {
		return !(b.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
				&& b.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** The column (schematic x, z) is the build or near it. */
	/** The build's own columns (what keptColumns grows the yard round). */
	private static boolean[] coreColumns(SchematicLoader.Schematic s, int groundLayers) {
		int w = s.width, l = s.length;
		boolean[] build = new boolean[w * l];
		boolean any = false;
		for (int y = 0; y < s.height; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					BlockState b = s.blockAt(x, y, z);
					if (b == null || !Placement.buildable(b)) continue;
					if (y < groundLayers && plainGround(b)) continue;
					if (loose(b)) continue;
					build[x * l + z] = true;
					any = true;
				}
			}
		}
		return any ? build : null;
	}

	/**
	 * A world spot of the build being made is over the build itself, not just the yard round it (the world's own grass
	 * and flowers there stay: pulling every tuft off the iron farm's yard took 10 minutes before a block went in).
	 */
	public boolean inCore(BlockPos c) {
		if (schem == null || built == null || core == null) return true;
		int w = schem.width, l = schem.length;
		int rx = c.getX() - built.getX(), rz = c.getZ() - built.getZ();
		int x, z;
		switch (rot) {
			case 1 -> { x = rz; z = l - 1 - rx; }
			case 2 -> { x = w - 1 - rx; z = l - 1 - rz; }
			case 3 -> { x = w - 1 - rz; z = rx; }
			default -> { x = rx; z = rz; }
		}
		if (x < 0 || z < 0 || x >= w || z >= l) return false;
		return core[x * l + z];
	}

	private boolean keptAt(int x, int z) {
		return kept == null || kept[x * schem.length + z];
	}

	/** Where the build being made has nether portal blocks (lit with flint and steel once the rest is done). */
	private final List<BlockPos> portalCells = new ArrayList<>();

	public List<BlockPos> portalCells() {
		return portalCells;
	}

	/** World position -> wanted block, bottom layer first, then row by row; air and leftover halves left out. */
	public Map<BlockPos, BlockState> plan(BlockPos at) {
		Rotation r = Rotation.values()[rot];
		int w = schem.width, l = schem.length;
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		List<Object[]> cells = new ArrayList<>();
		// Everything, exactly like pasting it. The ground layers at the bottom sink into the real ground (the house
		// stands on the world's grass): where the world already has the same block there's nothing to do, the paths,
		// farmland, floors and water in them get put in (leaving those out lost the farm and the floor).
		int skip = skip();
		portalCells.clear();
		kept = keptColumns(schem, groundLayers);
		core = coreColumns(schem, groundLayers);
		for (int y = 0; y < schem.height; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					if (!keptAt(x, z)) continue; // (far out on its ground plate)
					BlockState s = schem.blockAt(x, y, z);
					if (s == null) continue;
					// (Nether portal blocks: no item puts one in. The frame gets lit at the end, like a player does it.)
					boolean portal = s.is(Blocks.NETHER_PORTAL);
					if (!portal && !Placement.buildable(s)) continue;
					int rx, rz;
					switch (rot) {
						case 1 -> { rx = l - 1 - z; rz = x; }
						case 2 -> { rx = w - 1 - x; rz = l - 1 - z; }
						case 3 -> { rx = z; rz = w - 1 - x; }
						default -> { rx = x; rz = z; }
					}
					if (portal) {
						portalCells.add(at.offset(rx, y - skip, rz));
						continue;
					}
					cells.add(new Object[]{at.offset(rx, y - skip, rz), s.rotate(r)});
				}
			}
		}
		// Not below the bottom of the world (or over its top): willowspire's 5 layers of ground sank to y -65 in a
		// superflat world (bedrock at -64), and he flew down at the void over and over, 0 blocks in 4 minutes.
		var lvl = net.minecraft.client.Minecraft.getInstance().level;
		if (lvl != null) {
			int lo = lvl.getMinY(), hi = lvl.getMaxY();
			int before = cells.size();
			cells.removeIf(c -> ((BlockPos) c[0]).getY() < lo || ((BlockPos) c[0]).getY() > hi);
			if (cells.size() < before) {
				HumanAutopilot.LOGGER.info("[schem] leaving out {} blocks outside the world (below y {} or over y {})", before - cells.size(), lo, hi);
			}
		}
		if (lvl != null) fillUnder(lvl, at, cells);
		cells.sort((a, b) -> {
			BlockPos p = (BlockPos) a[0], q = (BlockPos) b[0];
			if (p.getY() != q.getY()) return Integer.compare(p.getY(), q.getY());
			if (p.getZ() != q.getZ()) return Integer.compare(p.getZ(), q.getZ());
			return Integer.compare(p.getX(), q.getX());
		});
		for (Object[] c : cells) out.put((BlockPos) c[0], (BlockState) c[1]);
		return out;
	}

	/**
	 * On hills the ground dips under parts of the build: from the lowest block of each column that sits at the bottom of
	 * the build (its floor or its ground, not an overhang higher up), down to the world's ground, dirt (the user: "fill
	 * that gap with dirt") - round its foot only (the columns with open ground beside them: what's seen), 6 deep at
	 * most, not into water; and one block under sand and gravel with nothing under them, anywhere, or they fall. Added
	 * to {@code cells} (they go in first: the ground part).
	 */
	private static void fillUnder(net.minecraft.client.multiplayer.ClientLevel lvl, BlockPos at, List<Object[]> cells) {
		Map<Long, Integer> lowest = new java.util.HashMap<>();
		Map<Long, BlockState> lowestState = new java.util.HashMap<>();
		for (Object[] c : cells) {
			BlockPos p = (BlockPos) c[0];
			BlockState st = (BlockState) c[1];
			if (st.isAir() || loose(st) || st.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
			long k = BlockPos.asLong(p.getX(), 0, p.getZ());
			Integer was = lowest.get(k);
			if (was == null || p.getY() < was) {
				lowest.put(k, p.getY());
				lowestState.put(k, st);
			}
		}
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		int added = 0, deep = 0;
		for (var e : lowest.entrySet()) {
			int y0 = e.getValue();
			if (y0 > at.getY()) continue; // (it doesn't stand on the ground there: an overhang, a balcony)
			int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
			// Only a dip (6 deep at most): deeper is a cliff or a valley under it, left open like a bridge (a 208x212
			// build next to spawn would have had 217,207 blocks of dirt under it: the user's "absurdly" far).
			// (Under sand, gravel, concrete powder: deeper too, or it falls - a pillar put in with the rest of the ground
			// now, not halfway through the walls: the medieval house went back to "the ground" 3 times for them.)
			BlockState low = lowestState.get(e.getKey());
			boolean falls = low != null && low.getBlock() instanceof net.minecraft.world.level.block.FallingBlock;
			// Round the foot of the build only (a column with open ground beside it): that's what's seen, like a
			// foundation wall; under the floor further in nobody sees the gap. (The user, watching the obsidian bridge
			// go up as 957 blocks of dirt first and the maze as 15 minutes of nothing but dirt: "is he supposed to be
			// building with just dirt?" Filled to 3 deep only, the cottage then hung 5 over a dip, its floor in the air.)
			boolean edge = false;
			for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				Integer ny = lowest.get(BlockPos.asLong(x + o[0], 0, z + o[1]));
				if (ny == null || ny > at.getY()) {
					edge = true;
					break;
				}
			}
			if (!edge && !falls) continue;
			int maxDeep = 6;
			List<BlockPos> col = new ArrayList<>();
			boolean ground = false;
			if (edge) {
				for (int y = y0 - 1; col.size() <= maxDeep && y >= lvl.getMinY(); y--) {
					BlockPos q = new BlockPos(x, y, z);
					BlockState w = lvl.getBlockState(q);
					// (A tree's top under it: not ground. Dirt heaped on its leaves, in among the rest of the canopy, could
					// only be seen from straight over it: the medieval house in a snowy spruce wood, "nowhere to place it
					// from" one after another. Left open, like a dip too deep.)
					if (w.is(net.minecraft.tags.BlockTags.LEAVES) || w.is(net.minecraft.tags.BlockTags.LOGS)) break;
					// (Nor into water: a build over a lake or the sea stands over it, it doesn't get a dirt bank under it.)
					if (!w.getFluidState().isEmpty()) break;
					if (!w.isAir() && !w.canBeReplaced()) {
						ground = true;
						break;
					}
					col.add(q);
				}
			}
			if (ground && col.size() <= maxDeep) {
				for (BlockPos q : col) cells.add(new Object[]{q, dirt});
				added += col.size();
				continue;
			}
			// Under sand, gravel, powder with nothing under it: one block of dirt right under it holds it, it doesn't
			// need ground all the way down (the medieval house by the sea: 2234 blocks of dirt in columns 40 deep down
			// through the water, the first quarter hour on nothing else).
			if (falls) {
				BlockPos q = new BlockPos(x, y0 - 1, z);
				BlockState w = lvl.getBlockState(q);
				if (w.isAir() || w.canBeReplaced() || !w.getFluidState().isEmpty()) {
					cells.add(new Object[]{q, dirt});
					added++;
				}
				continue;
			}
			deep++;
		}
		if (added > 0 || deep > 0) {
			HumanAutopilot.LOGGER.info("[schem] {} blocks of dirt under the build where the ground dips, round its foot ({} columns too deep, left open)", added, deep);
		}
	}

	/**
	 * What the schematic has at a world spot of the build being made (air included), or null outside it: lets the
	 * builder clear out what's in the way where the schematic is empty. (Null too far out on its ground plate: the
	 * world's hills and trees there stay.)
	 */
	public BlockState wantAt(BlockPos c) {
		if (schem == null || built == null) return null;
		int skip = skip(), w = schem.width, l = schem.length;
		int rx = c.getX() - built.getX(), ry = c.getY() - built.getY() + skip, rz = c.getZ() - built.getZ();
		int x, z;
		switch (rot) {
			case 1 -> { x = rz; z = l - 1 - rx; }
			case 2 -> { x = w - 1 - rx; z = l - 1 - rz; }
			case 3 -> { x = w - 1 - rz; z = rx; }
			default -> { x = rx; z = rz; }
		}
		if (x < 0 || z < 0 || ry < 0 || x >= w || z >= l || ry >= schem.height) return null;
		if (!keptAt(x, z)) return null;
		BlockState s = schem.blockAt(x, ry, z);
		return s == null ? Blocks.AIR.defaultBlockState() : s.rotate(Rotation.values()[rot]);
	}

	/**
	 * The spots under the floor the schematic has empty (a cellar, a pond dug into its ground): the grass filler leaves
	 * them. (Everywhere else under the floor that isn't the build's is plain ground to him - its yard far out on its
	 * ground plate isn't in the plan at all: the copper walls' yard, 200 across, he took for the schematic's own empty
	 * space and left a crater to the bedrock in it.)
	 */
	private List<BlockPos> emptyUnderFloor() {
		List<BlockPos> out = new ArrayList<>();
		int[] bx = builtBox();
		if (bx == null || min == null) return out;
		for (int y = bx[1]; y < min.getY(); y++)
			for (int z = bx[2]; z < bx[2] + bx[5]; z++)
				for (int x = bx[0]; x < bx[0] + bx[3]; x++) {
					BlockPos c = new BlockPos(x, y, z);
					BlockState w = wantAt(c);
					if (w != null && w.isAir()) out.add(c);
				}
		return out;
	}

	/** Where the build being made is in the world: min corner and size (x, y, z), or null (the tests check it with this). */
	public int[] builtBox() {
		if (schem == null || built == null) return null;
		return new int[]{built.getX(), built.getY() - skip(), built.getZ(), sizeX(), schem.height, sizeZ()};
	}

	/** Start building straight away at {@code at} (the tests). */
	public void buildAt(BlockPos at) {
		min = at;
		confirm(-1, -1);
	}

	public String hudLine() {
		if (preview) {
			return "§bPreview§r " + fileName + (min == null ? " §7(look at a block)" : " §7right-click to build, " + HumanAutopilot.keyName("rotate") + " to turn");
		}
		return null;
	}

	static File file(String name) {
		return dir().resolve(name).toFile();
	}
}
