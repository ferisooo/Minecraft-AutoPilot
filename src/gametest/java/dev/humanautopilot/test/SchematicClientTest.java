package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.BoxFillModule;
import dev.humanautopilot.schematic.Placement;
import dev.humanautopilot.schematic.SchematicLoader;
import dev.humanautopilot.schematic.SchematicMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Build Schematic in creative: a small house (floor, log corners, cobblestone walls, a door, a wall torch, a stair and
 * slab roof with a double slab), starting with an empty inventory so every block comes out of the creative menu. The
 * preview is placed and confirmed with a real right-click. Then a turned schematic (stairs and a sideways log) to check
 * rotation. Checks: every block in, every block the right way round, a block already in the way left alone, no
 * camera snaps or teleports. Run with HA_ONLY=schem.
 */
public class SchematicClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("schem-test");
	private float lastYaw = Float.NaN;
	private Vec3 lastPos;
	private double maxTurn, maxStep;
	private int tnt20At = -1, tnt20Before, tnt20Repaired = -1, tnt20Back = -1, tnt20MaxBlown, tntStart = -1;
	private List<BlockPos> tnt20Right = List.of();
	private int tntAllAt = -1, tntAllMax, tntAllBack = -1;
	private List<BlockPos> tntAllRight = List.of();

	/** Every spot of the build that's built right now. */
	private static List<BlockPos> builtRight(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			List<BlockPos> out = new ArrayList<>();
			int[] bx = SchematicMode.get().builtBox();
			for (BlockPos c : BlockPos.betweenClosed(bx[0], bx[1], bx[2], bx[0] + bx[3] - 1, bx[1] + bx[4] - 1, bx[2] + bx[5] - 1)) {
				BlockState w = SchematicMode.get().wantAt(c);
				if (w == null || w.isAir() || !Placement.buildable(w)) continue;
				if (Placement.matches(mc.level.getBlockState(c), w)) out.add(c.immutable());
			}
			return out;
		});
	}

	/**
	 * TNT all over what's built: {@code n} of them on top of built spots with open air over them, spread out (at least
	 * 6 apart), a few round him too, fuses a little different so they go off over a couple of seconds.
	 */
	private void tntEverywhere(ClientGameTestContext ctx, TestSingleplayerContext sp, int n, List<BlockPos> built) {
		java.util.Random rnd = new java.util.Random(42);
		List<BlockPos> tops = ctx.computeOnClient(mc -> {
			List<BlockPos> out = new ArrayList<>();
			for (BlockPos c : built) if (mc.level.getBlockState(c.above()).isAir() && mc.level.getBlockState(c.above(2)).isAir()) out.add(c);
			return out;
		});
		java.util.Collections.shuffle(tops, rnd);
		List<BlockPos> picked = new ArrayList<>();
		for (BlockPos c : tops) {
			if (picked.size() >= n) break;
			boolean close = false;
			for (BlockPos q : picked) if (q.distSqr(c) < 36) close = true;
			if (!close) picked.add(c);
		}
		BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
		for (int i = 0; i < 4; i++) picked.add(me.offset(i < 2 ? 3 : -3, 0, i % 2 == 0 ? 3 : -3).below());
		for (BlockPos c : picked) {
			cmd(sp, "summon tnt " + c.getX() + " " + (c.getY() + 1) + " " + c.getZ() + " {fuse:" + (30 + rnd.nextInt(40)) + "}");
		}
		LOG.info("TNTALL: {} TNT all over the build ({} built spots with air over them), him at {}, {} built right before",
				picked.size(), tops.size(), me.toShortString(), built.size());
	}
	private boolean menuShot;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"schem".equals(System.getenv("HA_ONLY"))) return;
		// No sound from the test window: the user plays their own game alongside it ("mute the sounds so it doesn't
		// overlap with my minecraft").
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
			mc.options.save();
		});
		// HA_TERRAIN=1: a normal world with hills (the user: "build it in a non flat world"), a new random seed each time.
		var builder = ctx.worldBuilder();
		if (System.getenv("HA_TERRAIN") != null) {
			builder = builder.setUseConsistentSettings(false).adjustSettings(st -> {
				st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
				st.setAllowCommands(true);
				st.setGenerateStructures(false);
				// HA_SEED=<seed>: the same world every time (a before/after check on the same hills).
				String seed = System.getenv("HA_SEED");
				if (seed != null && !seed.isBlank()) st.setSeed(seed);
			});
		}
		try (TestSingleplayerContext sp = builder.create()) {
			sp.getClientLevel().waitForChunksRender();
			BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			// (With a set seed, at the world's spawn point itself, not where he happened to come in: the game puts a player
			// anywhere within 10 of it, so the same seed gave a build 10 blocks off from one run to the next.)
			// (HA_SPAWN_TP=0: the seed's world, but built where he really spawned - no teleport at all.)
			if (System.getenv("HA_TERRAIN") != null && System.getenv("HA_SEED") != null && !System.getenv("HA_SEED").isBlank()
					&& !"0".equals(System.getenv("HA_SPAWN_TP"))) {
				BlockPos spawn = sp.getServer().computeOnServer(s -> {
					var lvl = s.overworld();
					BlockPos sp0 = lvl.getRespawnData().pos();
					int h = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sp0.getX(), sp0.getZ());
					return new BlockPos(sp0.getX(), h, sp0.getZ());
				});
				cmd(sp, "tp @a " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
				ctx.waitTicks(20);
				sp.getClientLevel().waitForChunksRender();
				p = spawn;
				LOG.info("REAL at the world's spawn point {} (seed {})", spawn.toShortString(), System.getenv("HA_SEED"));
			}
			int cx = p.getX() + 6, cz = p.getZ(), y0 = p.getY() - 1; // on the flat ground, not a floating platform
			if ("back".equals(System.getenv("HA_CAM"))) {
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
			}
			cmd(sp, "gamemode creative @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doDaylightCycle false");
			if (System.getenv("HA_TERRAIN") != null) {
				// (Old and new gamerule names: whichever this version has.)
				for (String c : new String[]{"weather clear", "gamerule advance_weather false", "gamerule doWeatherCycle false",
						"gamerule spawn_mobs false", "gamerule doMobSpawning false", "gamerule fire_spread_radius_around_player 0",
						"gamerule doFireTick false"}) {
					try {
						cmd(sp, c);
					} catch (Throwable ignored) {
					}
				}
			}
			// HA_EXACT=1: the "exactly like pasting it" test (farm, water, pot, candles, trapdoor, hopper, clearing).
			if (System.getenv("HA_EXACT") != null) {
				cmd(sp, "clear @a");
				// HA_EXACT=pond: a walled pond with a channel off it, and lily pads on the water (the Himeji castle's moat:
				// 31k water sources each waiting on the next for ever, 527 pads with "no spot").
				if ("pond".equals(System.getenv("HA_EXACT"))) pondBuild(ctx, sp, p);
				// HA_EXACT=grow: vines, bamboo, cane and a sapling growing fast, while it builds and after (see growBuild).
				else if ("grow".equals(System.getenv("HA_EXACT"))) growBuild(ctx, sp, p);
				// HA_EXACT=eaves: two rows of upside-down stairs under a roof, off a wall's log beam (see eavesBuild).
				else if ("eaves".equals(System.getenv("HA_EXACT"))) eavesBuild(ctx, sp, p);
				else exactBuild(ctx, sp, p);
				return;
			}
			// HA_CASE=<file>: a spot the user's game left as it was for good, put back together and tried again (caseBuild).
			if (System.getenv("HA_CASE") != null) {
				cmd(sp, "clear @a");
				caseBuild(ctx, sp, p, System.getenv("HA_CASE"));
				return;
			}
			// HA_CHAIN=1: two little files in the folder: the first built, then on to the second by itself beside it (past a
			// post somebody put in the first spot), then round to the first again (the user: "build schematic indefinitly").
			if (System.getenv("HA_CHAIN") != null) {
				cmd(sp, "clear @a");
				chainBuild(ctx, sp, p);
				return;
			}
			// HA_LOOSE=1: pieces touching nothing (a floating platform, a block, a hanging column of panes) by a yard that
			// has a spot that won't go in (the floating pagoda's crystals: 3,600 given up on).
			if (System.getenv("HA_LOOSE") != null) {
				cmd(sp, "clear @a");
				looseBuild(ctx, sp, p);
				return;
			}
			// HA_STUCK=1: a little build with one spot nobody can get to (shut in bedrock): it has to be called done, and
			// the next file started, not tried for ever (the floating pagoda's button: 11 minutes, never done).
			if (System.getenv("HA_STUCK") != null) {
				cmd(sp, "clear @a");
				stuckBuild(ctx, sp, p);
				return;
			}
			// HA_SWITCH=1: a build left half way for another one, then picked again from the list and finished (the user:
			// "i get bored of it building then start building a new schematic ... i cant go back").
			if (System.getenv("HA_SWITCH") != null) {
				cmd(sp, "clear @a");
				switchBuild(ctx, sp, p);
				return;
			}
			// HA_TRICKY=1: a hanging chain under a beam on a 30-high pillar (the statue's stuck spots), and TNT on a
			// finished part 30 blocks away while it's up the pillar (has to go back and fix it first).
			if (System.getenv("HA_TRICKY") != null) {
				cmd(sp, "clear @a");
				trickyBuild(ctx, sp, p);
				return;
			}
			// HA_TOWER=1: a 9x9 hollow tower 24 high; when it's 12+ up, 20 TNT round it (the user's stream): they fall in
			// onto the floor and outside onto the ground and the low walls. Everything has to be put back, soon.
			if (System.getenv("HA_TOWER") != null) {
				cmd(sp, "clear @a");
				towerBuild(ctx, sp, p);
				return;
			}
			// HA_SHELVES=<walmart.litematic>: the Walmart's shelf rack (fences, fence gates, trapdoors, lightning rods, two
			// levels), cut out of it. He built himself in there (the user: "i had to break some spots for him to break
			// free"): no 20 s without progress, and all of it built.
			// HA_DIAG=1: pieces that touch the rest only at an edge or a corner (MagicQueen's wings: 271 such pieces,
			// he froze with "nothing to click"): they need temporary blocks out to them, removed again afterwards.
			if (System.getenv("HA_DIAG") != null) {
				cmd(sp, "clear @a");
				diagBuild(ctx, sp, p);
				return;
			}
			// HA_RODS=1: a basement with end rods hanging from its ceiling (the cathedral's 2300: picked again and again
			// before their ceiling was there, "175 targets in 10 s, nothing placed"), a house on it with a wooden branch
			// standing out from a post (wood that has to lie the right way with nothing above or below it); and the
			// outside of each layer before its inside.
			if (System.getenv("HA_RODS") != null) {
				cmd(sp, "clear @a");
				rodsBuild(ctx, sp, p);
				return;
			}
			// HA_DOOR=1: a closed room reached only down a corridor with a door in it, standing open across the corridor
			// (the hospital's door: he walked into its frame, hopping, for 83 s). Everything's built but two blocks in
			// the room: he has to shut the door to get by, walk in and put them in.
			if (System.getenv("HA_DOOR") != null) {
				cmd(sp, "clear @a");
				doorBuild(ctx, sp, p);
				return;
			}
			String shelves = System.getenv("HA_SHELVES");
			if (shelves != null && !shelves.isBlank()) {
				cmd(sp, "clear @a");
				shelvesBuild(ctx, sp, p, shelves);
				return;
			}
			// HA_HARD=1: the "professional" chapel (~500 blocks, chandelier inside), built on flat ground.
			if (System.getenv("HA_HARD") != null) {
				cmd(sp, "clear @a");
				hardBuild(ctx, sp, p);
				return;
			}
			// HA_SCHEM=<file>: build a real schematic on the flat ground for HA_MINUTES and report how it went.
			String file = System.getenv("HA_SCHEM");
			if (file != null && !file.isBlank()) {
				cmd(sp, "clear @a");
				realFile(ctx, sp, file, p);
				return;
			}
			cmd(sp, "clear @a");
			int ox = cx - 2, oy = y0 + 1, oz = cz - 2;
			// Already there: one floor block that's right (skip it), and glass where a wall block goes (leave it).
			cmd(sp, "setblock " + (ox + 4) + " " + oy + " " + (oz + 4) + " oak_planks");
			cmd(sp, "setblock " + (ox + 1) + " " + (oy + 1) + " " + oz + " glass");
			// Stand west of the corner, facing east, looking down at it: the build goes away and to the right.
			cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f -90 28.4", ox - 3 + 0.5, oy, oz + 0.5));
			sp.getServer().runOnServer(s -> {
				for (var pl : s.getPlayerList().getPlayers()) {
					pl.getAbilities().flying = false;
					pl.onUpdateAbilities();
				}
			});
			ctx.waitTicks(30);

			// Record the bot through the house (to run tools/analyze_recording.py / the twitch check on it).
			ctx.runOnClient(mc -> {
				dev.humanautopilot.record.Recorder.recordBot = true;
				HumanAutopilot.recorder().start();
			});
			SchematicLoader.Schematic house = schem("test-house", 5, 4, 5, SchematicClientTest::house);
			ctx.runOnClient(mc -> SchematicMode.get().startPreview(house, "test-house"));
			ctx.waitTicks(10);
			// Turn it all the way round (four presses) and confirm with a real right-click.
			for (int i = 0; i < 4; i++) {
				ctx.getInput().pressKey(GLFW.GLFW_KEY_R);
				ctx.waitTicks(3);
			}
			ctx.takeScreenshot("schem-preview");
			ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			ctx.waitTicks(2);
			BoxFillModule.Box b = ctx.computeOnClient(mc -> BoxFillModule.get().box());
			if (!ctx.computeOnClient(mc -> BoxFillModule.get().planMode()) || b == null)
				throw new AssertionError("the right-click didn't start the build");
			if (b.minX() != ox || b.minY() != oy || b.minZ() != oz)
				throw new AssertionError("build placed at " + b.minX() + " " + b.minY() + " " + b.minZ() + ", wanted " + ox + " " + oy + " " + oz);
			Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(house, 0, new BlockPos(ox, oy, oz)));
			// Blown up low down while it's up on the roof: it has to go back for those first.
			for (int t = 0; t < 3000 && ctx.computeOnClient(mc -> BoxFillModule.get().planDone()) < 60; t++) {
				ctx.waitTick();
				watch(ctx);
			}
			cmd(sp, "setblock " + (ox + 4) + " " + (oy + 1) + " " + (oz + 2) + " air");
			cmd(sp, "setblock " + ox + " " + (oy + 1) + " " + (oz + 1) + " air");
			int backAt = -1;
			for (int t = 0; t < 400; t++) {
				ctx.waitTick();
				watch(ctx);
				boolean fixed = sp.getServer().computeOnServer(s -> !s.overworld().getBlockState(new BlockPos(ox + 4, oy + 1, oz + 2)).isAir()
						&& !s.overworld().getBlockState(new BlockPos(ox, oy + 1, oz + 1)).isAir());
				if (fixed) {
					backAt = t;
					break;
				}
			}
			LOG.info("house MIDBUILD: low wall blocks blown while on the roof: both back in {} ticks", backAt);
			if (backAt < 0) throw new AssertionError("didn't go back for the blown-out wall blocks within 20 s");
			build(ctx, "house", 6000);
			ctx.runOnClient(mc -> HumanAutopilot.recorder().stop());
			LOG.info("house RECORDING {}", (Object) ctx.computeOnClient(mc -> HumanAutopilot.recorder().file().toAbsolutePath().toString()));
			check(ctx, sp, "house", want, null); // (the glass in the wall gets knocked out and replaced now: exact)

			// A floor block inside the finished house: the only way in is the door. It has to open it, go in, put the
			// block back, come out (and shut the door).
			cmd(sp, "setblock " + (ox + 2) + " " + oy + " " + (oz + 2) + " air");
			ctx.waitTicks(10);
			build(ctx, "door", 2400);
			boolean floorBack = sp.getServer().computeOnServer(s -> !s.overworld().getBlockState(new BlockPos(ox + 2, oy, oz + 2)).isAir());
			LOG.info("door: floor block inside back: {}, door open now: {}", floorBack, sp.getServer().computeOnServer(
					s -> s.overworld().getBlockState(new BlockPos(ox + 2, oy + 1, oz)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN)));
			if (!floorBack) throw new AssertionError("didn't get into the closed house through the door");
			// A floor block in the back corner: too far to reach through the doorway, so it has to go in.
			cmd(sp, "setblock " + (ox + 1) + " " + oy + " " + (oz + 3) + " air");
			ctx.waitTicks(10);
			build(ctx, "inside", 2400);
			boolean backCorner = sp.getServer().computeOnServer(s -> !s.overworld().getBlockState(new BlockPos(ox + 1, oy, oz + 3)).isAir());
			LOG.info("inside: back corner floor block back: {}", backCorner);
			if (!backCorner) throw new AssertionError("didn't go in through the door for the back corner");

			// Blown up after it's done: a wall block low down (a finished layer) and a roof stair. It has to notice and
			// put both back (it's guarding the build now).
			cmd(sp, "setblock " + (ox + 4) + " " + (oy + 1) + " " + (oz + 2) + " air");
			cmd(sp, "setblock " + ox + " " + (oy + 3) + " " + (oz + 2) + " air");
			ctx.waitTicks(10);
			build(ctx, "repair", 2400);
			check(ctx, sp, "repair", want, null);

			// Stuck in a 1x1 gap in the roof (the user had to fly it out by hand): standing in the hole of a missing roof
			// slab, with a wall block missing on the far side too. It has to get itself out and fix both.
			cmd(sp, "setblock " + (ox + 2) + " " + (oy + 3) + " " + (oz + 2) + " air");
			cmd(sp, "setblock " + ox + " " + (oy + 1) + " " + (oz + 3) + " air");
			cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 30 40", ox + 2.5, oy + 3, oz + 2.5));
			ctx.waitTicks(10);
			build(ctx, "roofhole", 1200);
			check(ctx, sp, "roofhole", want, null);
			// And from inside, right under it (the door shut): up through the gap or out through the door, either way.
			cmd(sp, "setblock " + (ox + 2) + " " + (oy + 3) + " " + (oz + 2) + " air");
			cmd(sp, "setblock " + (ox + 4) + " " + (oy + 2) + " " + (oz + 3) + " air");
			cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 200 -30", ox + 2.5, oy + 1, oz + 2.5));
			ctx.waitTicks(10);
			build(ctx, "underhole", 1200);
			check(ctx, sp, "underhole", want, null);

			// A turned one: stairs facing north and a log lying east-west, turned a quarter clockwise.
			int ax = cx - 2, az = cz + 4;
			cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f -90 28.4", ax - 3 + 0.5, oy, az + 0.5));
			ctx.waitTicks(10);
			SchematicLoader.Schematic turned = schem("test-turned", 3, 1, 2, c -> c[2] == 0 ? "oak_stairs[facing=north]" : c[0] == 0 ? "cobblestone" : "oak_log[axis=x]");
			ctx.runOnClient(mc -> SchematicMode.get().startPreview(turned, "test-turned"));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_R);
			ctx.waitTicks(3);
			ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			ctx.waitTicks(2);
			Map<BlockPos, BlockState> want2 = ctx.computeOnClient(mc -> planOf(turned, 1, new BlockPos(ax, oy, az)));
			// The turn has to send the stairs east and lay the log north-south.
			for (var e : want2.entrySet()) LOG.info("turned wants {} at {}", e.getValue(), e.getKey().toShortString());
			build(ctx, "turned", 2400);
			check(ctx, sp, "turned", want2, null);

			// A thousand TNT: the house and the ground under it all gone, a pit down to the world's real ground. It
			// has to put up a pillar to build the floating bottom layer from, then build it all again.
			ctx.runOnClient(mc -> BoxFillModule.get().startPlan(planOf(house, 0, new BlockPos(ox, oy, oz)), "test-house"));
			cmd(sp, "fill " + (ox - 3) + " " + (oy - 3) + " " + (oz - 3) + " " + (ox + 7) + " " + (oy + 4) + " " + (oz + 5) + " air");
			ctx.waitTicks(10);
			build(ctx, "crater", 4000);
			check(ctx, sp, "crater", want, null);
			ctx.runOnClient(mc -> HumanAutopilot.panic());
			LOG.info("SCHEMATIC RESULT: ok, worst turn {} deg/tick, worst step {}", String.format("%.0f", maxTurn), String.format("%.2f", maxStep));
		}
	}

	/**
	 * A spot on land within ~96 blocks of (160 if nothing near is anywhere near flat: a mountain at spawn put the
	 * decorative build on a slope 64..121) {@code p} for a w x l build: no water on the ground under it, some hills (the
	 * ground's height there 2-8 apart, nearest 4), the floor at the ground's middle height. [corner, where to stand].
	 */
	private static BlockPos[] landSpot(TestSingleplayerContext sp, BlockPos p, int w, int l) {
		boolean hill = "1".equals(System.getenv("HA_HILL"));
		// HA_SLOPE=1: up a hillside, the floor a quarter of the way up the ground's heights there - most of the build's
		// ground under the hill, some of it over a dip (the user's copper guard: its ground layer under the hill's stone and
		// the trees on it, "flying around like a bot" from one buried spot to the next).
		boolean slope = "1".equals(System.getenv("HA_SLOPE"));
		return sp.getServer().computeOnServer(server -> {
			var lvl = server.overworld();
			BlockPos best = null;
			int bestScore = Integer.MAX_VALUE, bestMid = 0;
			String bestWhy = "";
			for (int ring = 0; ring <= (best == null || bestScore >= 100 ? 160 : 96) && (best == null || bestScore > 1); ring += 8) {
				for (int dx = -ring; dx <= ring; dx += 8) {
					for (int dz = -ring; dz <= ring; dz += 8) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
						int ox = p.getX() + dx, oz = p.getZ() + dz;
						List<Integer> hs = new ArrayList<>();
						int wet = 0, samples = 0;
						int step = Math.max(1, Math.min(w, l) / 8);
						boolean unloaded = false;
						for (int x = 0; x < w && !unloaded; x += step) {
							for (int z = 0; z < l && !unloaded; z += step) {
								// (Not loaded: its height reads as the bottom of the world, flat: three builds went to y -64 and
								// sat there "far away" for 4 minutes each.)
								if (!lvl.hasChunk((ox + x) >> 4, (oz + z) >> 4)) {
									unloaded = true;
									break;
								}
								int h = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox + x, oz + z);
								if (h <= lvl.getMinY() + 1) {
									unloaded = true;
									break;
								}
								BlockState top = lvl.getBlockState(new BlockPos(ox + x, h - 1, oz + z));
								samples++;
								if (!top.getFluidState().isEmpty() || top.is(net.minecraft.world.level.block.Blocks.ICE)) wet++;
								else hs.add(h);
							}
						}
						// (Dry: on land, the user's "not floating", and working under water is slow for him (house nhzpp3wk, 65
						// wide, over a pond: 2-10 blocks in 30 s down there). Only a giant one (120+, the copper guard) may
						// cover a little water: there's no dry spot that big near spawn.)
						int maxWet = Math.max(1, Math.min(w, l) >= 120 ? samples / 10 : 0);
						if (unloaded || hs.isEmpty() || wet >= maxWet) continue;
						java.util.Collections.sort(hs);
						int range = hs.get(hs.size() - 1) - hs.get(0), mid = hs.get(hs.size() / 2);
						// (A big build covers more of the land: more up and down is all right; too much is only a last pick.)
						int limit = Math.max(10, Math.min(w, l) / 6);
						int score = Math.abs(range - 4) + (range < 2 ? 3 : 0) + (range > limit ? 100 + range : 0) + wet * 10;
						// HA_HILL=1: a hillside, the floor at its foot (the user's castle in a hill, its walls 24 under the
						// grass: "he doesn't know how to dig straight down").
						if (hill) score = Math.abs(range - 20) + (range < 12 ? 60 : 0) + wet * 10;
						if (slope) score = Math.abs(range - 16) + (range < 8 ? 60 : 0) + wet * 10;
						if (score < bestScore) {
							bestScore = score;
							best = new BlockPos(ox, hill ? hs.get(0) : slope ? hs.get(hs.size() / 4) : mid, oz);
							bestMid = mid;
							bestWhy = "heights " + hs.get(0) + ".." + hs.get(hs.size() - 1);
						}
					}
				}
			}
			if (best == null) {
				int h0 = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX() + 4, p.getZ() + 4);
				best = new BlockPos(p.getX() + 4, h0, p.getZ() + 4);
				bestWhy = "nothing better: at spawn";
			}
			int sx = best.getX() - 3, sz = best.getZ() - 3;
			int sh = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
			LOG.info("REAL land: corner {} ({}), standing at {} {} {}", best.toShortString(), bestWhy, sx, sh + 1, sz);
			return new BlockPos[]{best, new BlockPos(sx, sh + 1, sz)};
		});
	}

	/** Up over (x, z) and till the ground 100 out each way is loaded, 20 s at most (3 s was too short: a look found nothing). */
	private static void goAndLoad(ClientGameTestContext ctx, TestSingleplayerContext sp, int x, int z) {
		cmd(sp, "tp @a " + x + " 200 " + z);
		for (int wt = 0; wt < 20; wt++) {
			ctx.waitTicks(20);
			boolean ready = sp.getServer().computeOnServer(server -> {
				var lvl = server.overworld();
				for (int ex = -100; ex <= 100; ex += 50)
					for (int ez = -100; ez <= 100; ez += 50) if (!lvl.hasChunk((x + ex) >> 4, (z + ez) >> 4)) return false;
				return true;
			});
			if (ready) break;
		}
		sp.getClientLevel().waitForChunksRender();
	}

	/**
	 * HA_AT=x,y,z: the build's middle there, its floor at y (the user's test spot: seed 5436, the forested valley at
	 * 272, 47, -271 - "keep that coords, seed and test on there from now on"). [corner, where to stand].
	 */
	private static BlockPos[] fixedSpot(ClientGameTestContext ctx, TestSingleplayerContext sp, String at, int w, int l) {
		String[] v = at.split(",");
		int cx = Integer.parseInt(v[0].trim()), cy = Integer.parseInt(v[1].trim()), cz = Integer.parseInt(v[2].trim());
		goAndLoad(ctx, sp, cx, cz);
		BlockPos corner = new BlockPos(cx - w / 2, cy, cz - l / 2);
		BlockPos stand = sp.getServer().computeOnServer(server -> new BlockPos(cx,
				server.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, cx, cz) + 1, cz));
		LOG.info("REAL land: corner {} (the test spot {}, {}, {} as its middle), standing at {} {} {}", corner.toShortString(), cx, cy, cz,
				stand.getX(), stand.getY(), stand.getZ());
		return new BlockPos[]{corner, stand};
	}

	/**
	 * HA_VALLEY=1 (the user: "put your bot in the middle of two hills, and heavily forest area"): looks round the world
	 * for a valley floor with hills rising on both sides of it, thick trees all over the build's footprint and next to no
	 * water, going 256 blocks at a time (up to 12 looks) so the chunks out there load. [corner, where to stand].
	 */
	private static BlockPos[] valleySpot(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p, int w, int l) {
		int[][] probes = {{0, 0}, {256, 0}, {0, 256}, {-256, 0}, {0, -256}, {256, 256}, {-256, 256}, {256, -256}, {-256, -256},
				{512, 0}, {0, 512}, {-512, 0}};
		BlockPos best = null;
		double bestScore = -1e9;
		String bestWhy = "";
		for (int k = 0; k < probes.length; k++) {
			int px = p.getX() + probes[k][0], pz = p.getZ() + probes[k][1];
			if (k > 0) {
				goAndLoad(ctx, sp, px, pz);
			}
			final int fx = px, fz = pz;
			Object[] r = sp.getServer().computeOnServer(server -> {
				var lvl = server.overworld();
				var NL = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
				var MB = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;
				BlockPos b = null;
				double bs = -1e9;
				String why = "";
				for (int dx = -96; dx <= 96; dx += 8) {
					for (int dz = -96; dz <= 96; dz += 8) {
						int cx = fx + dx, cz = fz + dz;
						int x0 = cx - w / 2, z0 = cz - l / 2;
						// The whole footprint and 45 round it loaded (unloaded reads as the bottom of the world: "flat").
						if (!lvl.hasChunk((x0 - 45) >> 4, (z0 - 45) >> 4) || !lvl.hasChunk((x0 + w + 45) >> 4, (z0 + l + 45) >> 4)
								|| !lvl.hasChunk((x0 - 45) >> 4, (z0 + l + 45) >> 4) || !lvl.hasChunk((x0 + w + 45) >> 4, (z0 - 45) >> 4)) continue;
						int h0 = lvl.getHeight(NL, cx, cz);
						if (h0 <= lvl.getMinY() + 1 || !lvl.getBlockState(new BlockPos(cx, h0 - 1, cz)).getFluidState().isEmpty()) continue;
						int canopy = 0, wet = 0, n = 0;
						for (int x = 0; x < w; x += 6) {
							for (int z = 0; z < l; z += 6) {
								int ax = x0 + x, az = z0 + z;
								if (!lvl.hasChunk(ax >> 4, az >> 4)) continue;
								int hn = lvl.getHeight(NL, ax, az), hm = lvl.getHeight(MB, ax, az);
								n++;
								if (hm > hn + 1) canopy++;
								if (!lvl.getBlockState(new BlockPos(ax, hn - 1, az)).getFluidState().isEmpty()) wet++;
							}
						}
						if (n == 0) continue;
						double forest = canopy / (double) n, water = wet / (double) n;
						// Hills on both sides: along each of four lines through it, the highest ground 10-45 out each way.
						int rise = 0;
						String axis = "";
						int[][] axes = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
						for (int[] a : axes) {
							int hiA = Integer.MIN_VALUE, hiB = Integer.MIN_VALUE;
							for (int t = 10; t <= 45; t += 3) {
								hiA = Math.max(hiA, lvl.getHeight(NL, cx + a[0] * t, cz + a[1] * t));
								hiB = Math.max(hiB, lvl.getHeight(NL, cx - a[0] * t, cz - a[1] * t));
							}
							int r2 = Math.min(hiA, hiB) - h0;
							if (r2 > rise) {
								rise = r2;
								axis = (a[0] == 1 && a[1] == 0 ? "east-west" : a[0] == 0 ? "north-south" : a[1] == 1 ? "southeast-northwest" : "northeast-southwest")
										+ " (" + (hiA - h0) + " and " + (hiB - h0) + " up)";
							}
						}
						double score = Math.min(rise, 24) * 2 + forest * 30 - water * 80 - (rise < 8 ? 40 : 0) - (forest < 0.4 ? 30 : 0);
						if (score > bs) {
							bs = score;
							b = new BlockPos(cx, h0, cz);
							why = String.format(java.util.Locale.ROOT, "valley floor y %d, hills %s, trees over %.0f%%, water %.0f%%", h0, axis, forest * 100, water * 100);
						}
					}
				}
				return new Object[]{b, bs, why};
			});
			if (r[0] != null && (double) r[1] > bestScore) {
				best = (BlockPos) r[0];
				bestScore = (double) r[1];
				bestWhy = (String) r[2];
			}
			LOG.info("REAL valley look {} at {} {}: best here {} ({})", k, px, pz, r[0] == null ? "-" : ((BlockPos) r[0]).toShortString(), r[2]);
			if (bestScore >= 64) break; // (hills 16+ up both sides and the trees thick: good enough)
		}
		if (best == null) {
			LOG.info("REAL valley: nothing found, building where he is");
			return new BlockPos[]{p.offset(4, 0, 4), p};
		}
		BlockPos corner = new BlockPos(best.getX() - w / 2, best.getY(), best.getZ() - l / 2);
		final BlockPos c0 = best;
		BlockPos stand = sp.getServer().computeOnServer(server -> new BlockPos(c0.getX(),
				server.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, c0.getX(), c0.getZ()) + 1, c0.getZ()));
		LOG.info("REAL land: corner {} (in a valley: {}), standing at {} {} {}", corner.toShortString(), bestWhy, stand.getX(), stand.getY(), stand.getZ());
		return new BlockPos[]{corner, stand};
	}

	// ------------------------------------------------------------------ carrying a test build on from one run to the next

	/**
	 * HA_SAVE=<name>: the build's area (the schematic's box, 6 round it, 16 under to 6 over) and where he is, written to
	 * %TEMP%\hatest-saves\<name>.gz every 5 minutes and at the end. HA_RESUME=<name>: pasted back in before he starts, so
	 * the next run carries on from the same percent with its time started over (the user: "as the build percent goes
	 * higher and higher, that's where problems start ... keep the same percentage ... so he can try to complete the
	 * schematic 100 percent").
	 */
	private static java.io.File saveFile(String name) {
		java.io.File d = new java.io.File(System.getenv("TEMP"), "hatest-saves");
		d.mkdirs();
		return new java.io.File(d, name + ".gz");
	}

	private static int[] saveRegion() {
		int[] bx = SchematicMode.get().builtBox();
		if (bx == null) return null;
		return new int[]{bx[0] - 6, bx[1] - 16, bx[2] - 6, bx[3] + 12, bx[4] + 22, bx[5] + 12};
	}

	private static void saveBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, BlockPos corner) {
		int[] r = ctx.computeOnClient(mc -> saveRegion());
		if (r == null) return;
		double[] me = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot()});
		long t0 = System.nanoTime();
		Object[] got = sp.getServer().computeOnServer(server -> {
			var lvl = server.overworld();
			Map<BlockState, Integer> pal = new java.util.LinkedHashMap<>();
			int[] data = new int[r[3] * r[4] * r[5]];
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			int i = 0;
			for (int y = 0; y < r[4]; y++)
				for (int z = 0; z < r[5]; z++)
					for (int x = 0; x < r[3]; x++) {
						BlockState s = lvl.getBlockState(m.set(r[0] + x, r[1] + y, r[2] + z));
						data[i++] = pal.computeIfAbsent(s, k -> pal.size());
					}
			return new Object[]{pal, data};
		});
		@SuppressWarnings("unchecked") Map<BlockState, Integer> pal = (Map<BlockState, Integer>) got[0];
		int[] data = (int[]) got[1];
		java.io.File f = saveFile(name), tmp = new java.io.File(f.getPath() + ".tmp");
		try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(tmp))))) {
			out.writeUTF("HASAVE1");
			for (int v : new int[]{corner.getX(), corner.getY(), corner.getZ()}) out.writeInt(v);
			for (int v : r) out.writeInt(v);
			for (double v : me) out.writeDouble(v);
			out.writeInt(pal.size());
			for (BlockState s : pal.keySet()) out.writeUTF(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s));
			for (int v : data) out.writeInt(v);
		} catch (java.io.IOException e) {
			LOG.info("REAL save {} failed: {}", name, e.toString());
			return;
		}
		f.delete();
		tmp.renameTo(f);
		String pct = ctx.computeOnClient(mc -> Math.round(BoxFillModule.get().planProgress() * 100) + "% (" + BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal() + ")");
		LOG.info("REAL saved the build at {} to {} ({} kinds, {} ms)", pct, f, pal.size(), (System.nanoTime() - t0) / 1_000_000);
	}

	/** Pastes a save back in (HA_RESUME) if it was made with its corner at {@code corner}: true if it did. */
	private static boolean resumeBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, BlockPos corner) {
		java.io.File f = saveFile(name);
		if (!f.isFile()) {
			LOG.info("REAL nothing saved as {} yet: starting from scratch", name);
			return false;
		}
		int[] c = new int[3], r = new int[6];
		double[] me = new double[5];
		List<BlockState> pal = new ArrayList<>();
		int[] data;
		try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f))))) {
			if (!"HASAVE1".equals(in.readUTF())) throw new java.io.IOException("not a save");
			for (int i = 0; i < 3; i++) c[i] = in.readInt();
			for (int i = 0; i < 6; i++) r[i] = in.readInt();
			for (int i = 0; i < 5; i++) me[i] = in.readDouble();
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				String s = in.readUTF();
				BlockState st;
				try {
					st = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, s, false).blockState();
				} catch (Exception e) {
					st = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
				}
				pal.add(st);
			}
			data = new int[r[3] * r[4] * r[5]];
			for (int i = 0; i < data.length; i++) data[i] = in.readInt();
		} catch (java.io.IOException e) {
			LOG.info("REAL couldn't read the save {}: {}", name, e.toString());
			return false;
		}
		if (c[0] != corner.getX() || c[1] != corner.getY() || c[2] != corner.getZ()) {
			LOG.info("REAL the save {} was made at {} {} {}, this build is at {}: starting from scratch", name, c[0], c[1], c[2], corner.toShortString());
			return false;
		}
		long t0 = System.nanoTime();
		// In slices, so no one server tick takes the whole paste.
		int slice = r[3] * r[5];
		for (int y0 = 0; y0 < r[4]; y0 += 8) {
			final int a = y0 * slice, b = Math.min(r[4], y0 + 8) * slice;
			final int[] d = data;
			sp.getServer().runOnServer(server -> {
				var lvl = server.overworld();
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (int i = a; i < b; i++) {
					int y = i / slice, rest = i % slice, z = rest / r[3], x = rest % r[3];
					m.set(r[0] + x, r[1] + y, r[2] + z);
					BlockState want = pal.get(d[i]);
					if (lvl.getBlockState(m) != want) lvl.setBlock(m, want, 2 | 16);
				}
			});
		}
		ctx.waitTicks(40);
		cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", me[0], me[1], me[2], me[3], me[4]));
		ctx.waitTicks(40);
		sp.getClientLevel().waitForChunksRender();
		LOG.info("REAL resumed the save {} ({} kinds, {} ms): he's back at {} {} {}", name, pal.size(), (System.nanoTime() - t0) / 1_000_000,
				Math.round(me[0]), Math.round(me[1]), Math.round(me[2]));
		return true;
	}

	private void realFile(ClientGameTestContext ctx, TestSingleplayerContext sp, String file, BlockPos p) {
		SchematicLoader.Schematic sc;
		try {
			sc = SchematicLoader.load(new java.io.File(file));
		} catch (Exception e) {
			throw new AssertionError("couldn't load " + file + ": " + e, e);
		}
		Map<String, Integer> kinds = new java.util.TreeMap<>();
		int solid = 0;
		for (int y = 0; y < sc.height; y++)
			for (int z = 0; z < sc.length; z++)
				for (int x = 0; x < sc.width; x++) {
					BlockState st = sc.blockAt(x, y, z);
					if (st == null || !Placement.buildable(st)) continue;
					solid++;
					kinds.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), 1, Integer::sum);
				}
		LOG.info("REAL {}: {}x{}x{}, {} blocks to build, {} kinds: {}", sc.name, sc.width, sc.height, sc.length, solid, kinds.size(), kinds);
		// On the flat ground a few blocks away, the player standing next to the corner. With HA_TERRAIN: a spot on land
		// near spawn, the build's floor at the ground's middle height there (where it's higher it gets dug out, where it
		// dips the gap under the floor gets dirt).
		BlockPos at = p.offset(4, 0, 4);
		BlockPos stand = p;
		boolean tp = true;
		if (System.getenv("HA_TERRAIN") != null) {
			if (System.getenv("HA_AT") != null && !System.getenv("HA_AT").isBlank()) {
				BlockPos[] spot = fixedSpot(ctx, sp, System.getenv("HA_AT"), sc.width, sc.length);
				at = spot[0];
				stand = spot[1];
			} else if ("1".equals(System.getenv("HA_VALLEY"))) {
				BlockPos[] spot = valleySpot(ctx, sp, p, sc.width, sc.length);
				at = spot[0];
				stand = spot[1];
			} else if (System.getenv("HA_LANDSPOT") != null) {
				BlockPos[] spot = landSpot(sp, p, sc.width, sc.length);
				at = spot[0];
				stand = spot[1];
			} else {
				// Where he is (the user: "stop teleporting him and have him build it wherever he is, that'll challenge him
				// more"): the corner a few blocks in front of where he spawned, the floor at his feet - water, trees, a
				// slope, whatever is there.
				tp = false;
				final BlockPos corner = at;
				String what = sp.getServer().computeOnServer(server -> {
					var lvl = server.overworld();
					int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, wet = 0, n = 0;
					int step = Math.max(1, Math.min(sc.width, sc.length) / 8);
					for (int x = 0; x < sc.width; x += step) {
						for (int z = 0; z < sc.length; z += step) {
							if (!lvl.hasChunk((corner.getX() + x) >> 4, (corner.getZ() + z) >> 4)) continue;
							int h = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, corner.getX() + x, corner.getZ() + z);
							BlockState top = lvl.getBlockState(new BlockPos(corner.getX() + x, h - 1, corner.getZ() + z));
							n++;
							if (!top.getFluidState().isEmpty()) wet++;
							lo = Math.min(lo, h);
							hi = Math.max(hi, h);
						}
					}
					return n == 0 ? "not loaded yet" : "heights " + lo + ".." + hi + (wet > 0 ? ", water at " + wet + " of " + n + " spots" : "");
				});
				LOG.info("REAL land: corner {} (where he spawned, {}), standing at {} {} {}", at.toShortString(), what, p.getX(), p.getY(), p.getZ());
			}
		}
		// HA_HILL=1: the build sunk 15 into the ground where he stands, its walls under a hill of the world's own ground
		// to clear from the top down (the user's castle in a hillside: walls at y 64, the grass at 88 - "he doesn't know
		// how to dig straight down").
		if ("1".equals(System.getenv("HA_HILL"))) {
			// (A third of its height at most: sunk 15, the 20-high castle was all under the ground.)
			int sink = Math.max(4, Math.min(15, sc.height / 3));
			at = at.below(sink);
			LOG.info("REAL sunk {} blocks into the ground: corner {}", sink, at.toShortString());
		}
		// HA_SINK=N: the build N blocks down into the ground where he stands - on uneven ground with trees, part of it under
		// the hill and part over a dip (the user's copper guard on its hillside, the ground layer under the hill's stone).
		int sinkN = Integer.parseInt(System.getenv().getOrDefault("HA_SINK", "0"));
		if (sinkN > 0) {
			at = at.below(sinkN);
			LOG.info("REAL sunk {} blocks into the ground (HA_SINK): corner {}", sinkN, at.toShortString());
		}
		// HA_FLOAT=N: the build N blocks up in the air (a build started in mid-air: nothing to click its first block on).
		int lift = Integer.parseInt(System.getenv().getOrDefault("HA_FLOAT", "0"));
		if (lift > 0) {
			at = at.above(lift);
			LOG.info("REAL lifted {} blocks into the air: corner {}", lift, at.toShortString());
		}
		if (tp) cmd(sp, "tp @a " + stand.getX() + " " + stand.getY() + " " + stand.getZ() + " -45 20");
		final BlockPos atF = at;
		ctx.waitTicks(System.getenv("HA_TERRAIN") != null ? 100 : 20); // (the chunks round the spot load in)
		if (System.getenv("HA_TERRAIN") != null) sp.getClientLevel().waitForChunksRender();
		ctx.runOnClient(mc -> {
			SchematicMode.get().startPreview(sc, new java.io.File(file).getName());
			SchematicMode.get().buildAt(atF);
		});
		String resume = System.getenv("HA_RESUME"), save = System.getenv("HA_SAVE");
		if (resume != null && !resume.isBlank()) {
			ctx.runOnClient(mc -> BoxFillModule.get().setPaused(true));
			resumeBuild(ctx, sp, resume, atF);
			ctx.runOnClient(mc -> BoxFillModule.get().setPaused(false));
		}
		// HA_PREBUILD=N: the bottom N layers pasted straight in (the statue half built), so he starts up there (where the
		// user's TNT came down on it).
		int pre = Integer.parseInt(System.getenv().getOrDefault("HA_PREBUILD", "0"));
		// HA_EXCEPT=trapdoor,stairs: with HA_PREBUILD, blocks whose id has one of these in it are left out (only the
		// awkward ones left to put in: a quick test of the ones that get "left as they are").
		String[] except = System.getenv().getOrDefault("HA_EXCEPT", "").isBlank() ? new String[0] : System.getenv("HA_EXCEPT").split(",");
		if (pre > 0) {
			List<Object[]> puts = ctx.computeOnClient(mc -> {
				List<Object[]> out = new ArrayList<>();
				int[] bx = SchematicMode.get().builtBox();
				for (int y = 0; y < Math.min(pre, bx[4]); y++)
					for (int z = 0; z < bx[5]; z++)
						for (int x = 0; x < bx[3]; x++) {
							BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
							BlockState w = SchematicMode.get().wantAt(c);
							// (The other half of a door, a bed, tall grass too: the halves the bot never places itself. Left out, the
							// cottage's door stood there half there, and he gave up walking through it.)
							boolean otherHalf = (w != null && w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
									&& w.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
									== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
									|| (w != null && w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
									&& w.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
									== net.minecraft.world.level.block.state.properties.BedPart.HEAD);
							if (w == null || w.isAir() || !(Placement.buildable(w) || otherHalf)) continue;
							String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(w.getBlock()).getPath();
							boolean skip = false;
							// ("=stone": that id exactly, not stone bricks or cobblestone too.)
							for (String e : except) {
								String e2 = e.trim();
								if (!e2.isEmpty() && (e2.startsWith("=") ? id.equals(e2.substring(1)) : id.contains(e2))) skip = true;
							}
							// (Leaves as a player's: lasting. Pasted as the schematic has them, a tree's own, they withered with no log
							// near, and the TNT runs' first minutes went on putting them back - 2100 "damaged", most of it that.)
							if (w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)) {
								w = w.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
							}
							if (!skip) out.add(new Object[]{c, w});
						}
				return out;
			});
			sp.getServer().runOnServer(s -> {
				for (Object[] o : puts) s.overworld().setBlock((BlockPos) o[0], (BlockState) o[1], 2 | 16);
			});
			LOG.info("REAL prebuilt the bottom {} layers: {} blocks", pre, puts.size());
		}
		// HA_PREPARTS=N: the parts of the build order before part N pasted straight in (2: the ground and the outside
		// walls), all but the roofs (the top block of a column with nothing under it) and water (it goes in last): he
		// starts on the roofs, the hatch, the inside.
		int preParts = Integer.parseInt(System.getenv().getOrDefault("HA_PREPARTS", "0"));
		if (preParts > 0) {
			List<Object[]> puts = ctx.computeOnClient(mc -> {
				List<Object[]> out = new ArrayList<>();
				int[] bx = SchematicMode.get().builtBox();
				for (int y = 0; y < bx[4]; y++)
					for (int z = 0; z < bx[5]; z++)
						for (int x = 0; x < bx[3]; x++) {
							BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
							BlockState w = SchematicMode.get().wantAt(c);
							if (w == null || w.isAir() || !Placement.buildable(w)) continue;
							int part = BoxFillModule.get().phaseFor(c);
							if (part < 0 || part >= preParts || Placement.fluid(w)) continue;
							BlockState under = SchematicMode.get().wantAt(c.below());
							if (BoxFillModule.get().topOfColumn(c) && y > 1 && (under == null || under.isAir())) continue; // a roof
							if (w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)) {
								w = w.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
							}
							out.add(new Object[]{c, w});
						}
				return out;
			});
			sp.getServer().runOnServer(s -> {
				for (Object[] o : puts) s.overworld().setBlock((BlockPos) o[0], (BlockState) o[1], 2 | 16);
			});
			LOG.info("REAL prebuilt the parts before {} (not the roofs): {} blocks", preParts, puts.size());
		}
		// HA_PASTE_MAIN=1: the build's biggest piece (its blocks joined by faces) pasted straight in, the rest left to
		// build: the pieces touching nothing (the floating pagoda's 150 crystals and shards, 2,256 blocks, given up on).
		if (System.getenv("HA_PASTE_MAIN") != null) {
			List<Object[]> puts = ctx.computeOnClient(mc -> {
				int[] bx = SchematicMode.get().builtBox();
				Map<BlockPos, BlockState> want = new HashMap<>();
				for (int y = 0; y < bx[4]; y++)
					for (int z = 0; z < bx[5]; z++)
						for (int x = 0; x < bx[3]; x++) {
							BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
							BlockState w = SchematicMode.get().wantAt(c);
							if (w != null && Placement.buildable(w)) want.put(c, w);
						}
				java.util.Set<BlockPos> seen = new java.util.HashSet<>();
				List<BlockPos> best = new ArrayList<>();
				int pieces = 0;
				for (BlockPos c0 : want.keySet()) {
					if (!seen.add(c0)) continue;
					pieces++;
					List<BlockPos> comp = new ArrayList<>();
					java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
					q.add(c0);
					while (!q.isEmpty()) {
						BlockPos c = q.poll();
						comp.add(c);
						for (var d : net.minecraft.core.Direction.values()) {
							BlockPos n = c.relative(d);
							if (want.containsKey(n) && seen.add(n)) q.add(n);
						}
					}
					if (comp.size() > best.size()) best = comp;
				}
				List<Object[]> out = new ArrayList<>();
				for (BlockPos c : best) {
					BlockState w = want.get(c);
					if (w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)) {
						w = w.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
					}
					out.add(new Object[]{c, w});
				}
				LOG.info("REAL {} pieces (by faces), the biggest {} blocks of {}: the other {} left to build", pieces, best.size(), want.size(),
						want.size() - best.size());
				return out;
			});
			sp.getServer().runOnServer(s -> {
				for (Object[] o : puts) s.overworld().setBlock((BlockPos) o[0], (BlockState) o[1], 2 | 16);
			});
			LOG.info("REAL pasted the biggest piece: {} blocks", puts.size());
		}
		int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_MINUTES", "8"));
		// HA_RECORD=1: record the bot (camera, keys, clicks) for the analysis scripts (the twitch after each click).
		boolean rec = System.getenv("HA_RECORD") != null;
		if (rec) {
			ctx.runOnClient(mc -> {
				dev.humanautopilot.record.Recorder.recordBot = true;
				HumanAutopilot.recorder().start();
			});
		}
		long placed0 = BoxFillModule.placedCount, skipped0 = BoxFillModule.skippedCount, stalls0 = BoxFillModule.stalls;
		lastYaw = Float.NaN;
		lastPos = null;
		int t = 0, lastDone = 0, stillAt = -1, stillSince = 0;
		// The build order: when each spot first came out right, and which part it is (outside, top, inside).
		Map<BlockPos, int[]> orderSeen = new HashMap<>();
		try {
		for (; t < minutes * 1200; t++) {
			ctx.waitTick();
			watch(ctx);
			if (save != null && !save.isBlank() && t > 0 && t % 6000 == 0) {
				saveBuild(ctx, sp, save, atF);
				lastYaw = Float.NaN; // (the save stopped the server a moment)
				lastPos = null;
			}
			if (!ctx.computeOnClient(mc -> BoxFillModule.get().planMode())) break;
			if (t > 20 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
			// (Progress: blocks put in or knocked out. Clearing a hill off a big site counts too: the trading hall's 5 minutes
			// of digging, 1085 blocks, was stopped as "no progress".)
			int doneNow = ctx.computeOnClient(mc -> BoxFillModule.get().planDone()) + (int) BoxFillModule.placedCount;
			if (doneNow != stillAt) {
				stillAt = doneNow;
				stillSince = t;
			} else if (t - stillSince > 20 * 240) {
				LOG.info("REAL no progress for 4 minutes: stopping");
				break;
			}
			if (t % 600 == 0) {
				int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
				String st = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status());
				LOG.info("REAL t={}s: {} (+{} in 30 s), worst tick {} ms, ticks over 5 ms {}", t / 20, st, done - lastDone,
						BoxFillModule.slowestTickMs, BoxFillModule.slowTicks);
				lastDone = done;
			}
			if (t % 2400 == 1200) {
				ctx.takeScreenshot("schem-real-" + (t / 1200));
				lastYaw = Float.NaN; // (the screenshot took a few ticks: "moved 1.63 blocks in one tick" was two)
				lastPos = null;
			}
			if (t % 100 == 50) {
				final int now = t;
				ctx.runOnClient(mc -> {
					int[] bx = SchematicMode.get().builtBox();
					if (bx == null) return;
					for (int y = 0; y < bx[4]; y++)
						for (int z = 0; z < bx[5]; z++)
							for (int x = 0; x < bx[3]; x++) {
								BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
								if (orderSeen.containsKey(c)) continue;
								BlockState w = SchematicMode.get().wantAt(c);
								if (w == null || w.isAir() || !Placement.buildable(w)) continue;
								if (!Placement.matches(mc.level.getBlockState(c), w)) continue;
								orderSeen.put(c, new int[]{now, BoxFillModule.get().phaseFor(c)});
							}
				});
			}
			// The user: "summon tnt right now everywhere": a file %TEMP%\hatest-tnt-now.txt (holding how many, default
			// 40) sets off TNT all over what's built, there and then; any number of times in a run.
			if (t % 20 == 0) {
				java.io.File trig = new java.io.File(System.getenv().getOrDefault("TEMP", "."), "hatest-tnt-now.txt");
				if (trig.exists()) {
					int n = 40;
					try {
						n = Integer.parseInt(java.nio.file.Files.readString(trig.toPath()).trim());
					} catch (Exception ignored) {
					}
					trig.delete();
					tntAllAt = t;
					tntAllRight = builtRight(ctx);
					tntAllMax = 0;
					tntAllBack = -1;
					tntEverywhere(ctx, sp, n, tntAllRight);
				}
			}
			if (tntAllAt >= 0 && t > tntAllAt && (t - tntAllAt) % 20 == 0) {
				int blownAll = blownOf(ctx, tntAllRight);
				tntAllMax = Math.max(tntAllMax, blownAll);
				if ((t - tntAllAt) % 200 == 0) {
					LOG.info("TNTALL +{}s: {} of the {} built before still blown out | {}", (t - tntAllAt) / 20, blownAll, tntAllRight.size(),
							ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + " done, " + BoxFillModule.get().damagedCount() + " damaged | "
									+ HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
				}
				if (t > tntAllAt + 200 && tntAllBack < 0 && blownAll == 0) {
					tntAllBack = t;
					LOG.info("TNTALL: everything the TNT broke is back {} s after it ({} were out at most)",
							String.format("%.1f", (t - tntAllAt) / 20.0), tntAllMax);
				}
			}
			// HA_TNT: after 2 minutes, blow up part of what's built (low down, near the corner) and watch where it works.
			// HA_TNT20: the user's stream: after 3 minutes, 20 TNT spread out around him, a few at a time like gifts coming
			// in (summoned where he is, falling onto the build). Then: is every blown-out bit put back, how soon, and
			// does he get stuck on the way?
			if (System.getenv("HA_TNT20") != null) {
				// (From 3 minutes on, once there's something built round him to blow up: 100 blocks of it, or at 10 minutes.
				// At 3 minutes sharp a big build was still on its dirt: "0 of the 0 built round him", nothing learned.)
				if (tntStart < 0 && t >= 3600 && t % 200 == 0 && (t >= 12000 || rightNear(ctx, 24).size() >= 100)) tntStart = t;
				// (HA_TNTN=300: that many, one every 3 ticks, up to 16 out - the user's stream had 2000 in 3 minutes, a 1400-spot
				// repair turned into 5300.)
				int tntN = Integer.parseInt(System.getenv().getOrDefault("HA_TNTN", "20"));
				if (tntStart >= 0 && t >= tntStart && t < tntStart + 3 * tntN && (t - tntStart) % 3 == 0) {
					int i = (t - tntStart) / 3; // 20 of them over 3 s
					BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
					double ang = i * (Math.PI * 2 / 20) + (i % 3) * 0.4;
					double r = 2 + (i * 7) % (tntN > 20 ? 15 : 9); // 2..10 out (2..16 for more)
					int x = me.getX() + (int) Math.round(Math.cos(ang) * r), z = me.getZ() + (int) Math.round(Math.sin(ang) * r);
					cmd(sp, "summon tnt " + x + " " + me.getY() + " " + z);
					if (i == 0) {
						tnt20At = t;
						tnt20Before = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
						tnt20Right = rightNear(ctx, 24);
						LOG.info("TNT20: 20 TNT round him from t={}s, him at {}, {} done ({} built round him)", t / 20, me.toShortString(),
								tnt20Before, tnt20Right.size());
					}
				}
				int blown = -1;
				if (tnt20At > 0 && t > tnt20At && (t - tnt20At) % 20 == 0) {
					// What the world says, not what he thinks: of what was built right round him before, how much is still out.
					blown = blownOf(ctx, tnt20Right);
					tnt20MaxBlown = Math.max(tnt20MaxBlown, blown);
					if (t > tnt20At + 150 && tnt20Back < 0 && blown == 0) {
						tnt20Back = t;
						LOG.info("TNT20: everything the TNT broke is back {} s after the first TNT ({} were out at most)",
								String.format("%.1f", (t - tnt20At) / 20.0), tnt20MaxBlown);
					}
				}
				if (tnt20At > 0 && t > tnt20At && (t - tnt20At) % 200 == 0) {
					int sinceS = (t - tnt20At) / 20;
					String st = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + " done, " + BoxFillModule.get().damagedCount()
							+ " damaged, " + BoxFillModule.get().holesLeft() + " holes | " + HumanAutopilot.status() + " | at "
							+ mc.player.blockPosition().toShortString());
					LOG.info("TNT20 +{}s: {} | {} of the {} built round him still blown out", sinceS, st, blown, tnt20Right.size());
				}
				if (tnt20At > 0 && t > tnt20At + 200 && tnt20Repaired < 0 && ctx.computeOnClient(mc -> BoxFillModule.get().damagedCount()) == 0) {
					tnt20Repaired = t;
					LOG.info("TNT20: he thinks all the damage is put back {} s after the first TNT", String.format("%.1f", (t - tnt20At) / 20.0));
				}
				if (tnt20At > 0 && t > tnt20At + 20 * 60 * 5) {
					LOG.info("TNT20: 5 minutes after the TNT, stopping");
					break;
				}
				if (tnt20Back > 0 && t > tnt20Back + 20 * 60) break; // and a minute of building after
			}
			if (System.getenv("HA_TNT") != null && t == 2400) {
				BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
				for (int i = 0; i < 4; i++) cmd(sp, "summon tnt " + (at.getX() + 3 + i * 2) + " " + (at.getY() + 1) + " " + (at.getZ() + 3) + " {fuse:20}");
				LOG.info("REAL TNT at t={}s, me at {}", t / 20, me.toShortString());
			}
			if (System.getenv("HA_TNT") != null && t > 2400 && t < 3600 && t % 10 == 0) {
				String w = ctx.computeOnClient(mc -> {
					var tg = BoxFillModule.get().target();
					return (tg == null ? "-" : tg.toShortString()) + " me " + mc.player.blockPosition().toShortString() + " | " + HumanAutopilot.status();
				});
				LOG.info("REAL after TNT t={}s: working on {}", String.format("%.1f", t / 20.0), w);
			}
		}
		} finally {
			// (Saved even when the run fails: the first valley run stopped at 67 minutes with the last save 1.5 min old.)
			if (save != null && !save.isBlank()) {
				try {
					saveBuild(ctx, sp, save, atF);
				} catch (Throwable e) {
					LOG.info("REAL couldn't save at the end: {}", e.toString());
				}
			}
		}
		ctx.takeScreenshot("schem-real-end");
		// The order: per part, how many and when; and how much of the outside was still to do when the inside started.
		{
			String[] parts = {"ground", "outside", "top", "inside"};
			int[] n = new int[4], lastAt = new int[4];
			int[] firstAt = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
			List<Integer> outsideTimes = new ArrayList<>();
			for (int[] v : orderSeen.values()) {
				if (v[1] < 0 || v[0] <= 50) continue; // (there before he started: the ground, a prebuilt part)
				n[v[1]]++;
				firstAt[v[1]] = Math.min(firstAt[v[1]], v[0]);
				lastAt[v[1]] = Math.max(lastAt[v[1]], v[0]);
				if (v[1] == 1) outsideTimes.add(v[0]);
			}
			int insideStart = firstAt[3], outsideAfter = 0;
			for (int ot : outsideTimes) if (ot > insideStart) outsideAfter++;
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				sb.append(i > 0 ? ", " : "").append(parts[i]).append(' ').append(n[i]);
				if (n[i] > 0) sb.append(" (").append(firstAt[i] / 20).append('-').append(lastAt[i] / 20).append(" s)");
			}
			LOG.info("REAL ORDER: {}; outside put in after the inside started: {} of {}", sb, outsideAfter, n[1]);
		}
		if (rec) {
			ctx.runOnClient(mc -> HumanAutopilot.recorder().stop());
			LOG.info("REAL RECORDING {}", (Object) ctx.computeOnClient(mc -> HumanAutopilot.recorder().file().toAbsolutePath().toString()));
		}
		String st = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal());
		LOG.info("REAL RESULT: {} in {} s ({} blocks/min), placed {} skipped {} stalls {}, worst tick {} ms, slow ticks {}, worst turn {} deg/tick; doing: {}",
				st, t / 20, String.format("%.0f", (BoxFillModule.placedCount - placed0) / Math.max(1.0, t / 1200.0)),
				BoxFillModule.placedCount - placed0, BoxFillModule.skippedCount - skipped0, BoxFillModule.stalls - stalls0,
				BoxFillModule.slowestTickMs, BoxFillModule.slowTicks, String.format("%.0f", maxTurn), BoxFillModule.noteTicks);
		if (tnt20At > 0) {
			LOG.info("TNT20 RESULT: {} of the {} built round him were blown out, all back after {} s (he thought {} s), {} still out at the end",
					tnt20MaxBlown, tnt20Right.size(), tnt20Back < 0 ? "never" : String.format("%.1f", (tnt20Back - tnt20At) / 20.0),
					tnt20Repaired < 0 ? "never" : String.format("%.1f", (tnt20Repaired - tnt20At) / 20.0), blownOf(ctx, tnt20Right));
			// Which, and what he makes of them (grass on dirt he leaves on purpose, for one).
			ctx.runOnClient(mc -> {
				int n = 0;
				java.util.Map<String, Integer> outKinds = new java.util.TreeMap<>();
				for (BlockPos c : tnt20Right) {
					BlockState w = SchematicMode.get().wantAt(c), got = mc.level.getBlockState(c);
					if (w == null || Placement.matches(got, w)) continue;
					String k = w.getBlock().getName().getString() + " has " + got.getBlock().getName().getString() + ": " + BoxFillModule.get().jobInfo(c);
					outKinds.merge(k, 1, Integer::sum);
					if (n++ < 12) LOG.info("TNT20 still out: {} wants {} has {}: {}", c.toShortString(), w, got, BoxFillModule.get().jobInfo(c));
				}
				LOG.info("TNT20 still out, by kind: {}", outKinds);
				LOG.info("TNT20 he has down as damaged: {}", BoxFillModule.get().damagedSpots().stream().limit(10)
						.map(c -> c.toShortString() + " " + BoxFillModule.get().jobInfo(c)).toList());
			});
		}
		// The same as pasting it? Every spot of the schematic's box, air included, against the world.
		ctx.runOnClient(mc -> {
			int[] bx = SchematicMode.get().builtBox();
			if (bx == null) return;
			int right = 0, total = 0;
			Map<String, Integer> why = new java.util.TreeMap<>();
			List<String> examples = new ArrayList<>();
			for (int y = 0; y < bx[4]; y++) {
				for (int z = 0; z < bx[5]; z++) {
					for (int x = 0; x < bx[3]; x++) {
						BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
						BlockState want = SchematicMode.get().wantAt(c);
						if (want == null) continue;
						// (Running water goes wherever it runs, pasted or not.)
						if (want.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && !want.getFluidState().isSource()) continue;
						BlockState world = mc.level.getBlockState(c);
						total++;
						if (want.isAir() ? world.isAir() : Placement.matches(world, want)) {
							right++;
							continue;
						}
						String k = want.isAir() ? "extra " + id(world) : world.isAir() ? "missing " + id(want)
								: world.getBlock() != want.getBlock() ? id(want) + " is " + id(world) : "wrong state " + id(want);
						why.merge(k, 1, Integer::sum);
						if (examples.size() < 60) examples.add("(" + x + "," + y + "," + z + ") want " + want + " got " + world);
					}
				}
			}
			LOG.info("REAL EXACT: {} of {} spots like pasted ({}%), wrong: {}", right, total,
					String.format("%.1f", right * 100.0 / Math.max(1, total)), why);
			for (String e : examples) LOG.info("REAL EXACT wrong: {}", e);
		});
		ctx.runOnClient(mc -> HumanAutopilot.panic());
	}

	private static String id(BlockState s) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}

	/**
	 * A small chapel, 9 x 12 x 9: stone brick walls with log corner posts and a band of logs lying along the walls,
	 * glass windows, a door, an upside-down stair cornice under the ceiling, a chandelier hanging from the ceiling
	 * (chains, a centre block, four arms with lanterns hanging under them and candles on top), pews, an altar with a
	 * candle, wall torches, and a stepped stair roof with double-slab corners and a lantern on top.
	 */
	private static String chapel(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		boolean ex = x == 0 || x == 8, ez = z == 0 || z == 8, edge = ex || ez, corner = ex && ez;
		if (y == 0) return edge ? "stone_bricks" : "polished_andesite";
		if (y >= 1 && y <= 5) {
			if (corner) return "oak_log[axis=y]";
			if (edge) {
				if (x == 4 && z == 0 && y <= 2) return "oak_door[facing=north,half=" + (y == 1 ? "lower" : "upper") + ",hinge=left]";
				boolean window = (y == 2 || y == 3) && (((x == 2 || x == 6) && ez) || ((z == 2 || z == 6) && ex));
				if (window) return "glass_pane";
				if (y == 5) return ez ? "dark_oak_log[axis=x]" : "dark_oak_log[axis=z]";
				return "stone_bricks";
			}
			// inside
			if (y == 1 && (z == 3 || z == 5) && (x == 2 || x == 3 || x == 5 || x == 6)) return "oak_stairs[facing=south]";
			if (y == 1 && z == 7 && x >= 3 && x <= 5) return "smooth_quartz";
			if (y == 2 && z == 7 && x == 4) return "candle";
			if (y == 2 && z == 7 && x == 3) return "oak_sign[rotation=0]"; // a sign on the altar (its editor pops up)
			if (y == 3 && z == 7 && (x == 3 || x == 5)) return "wall_torch[facing=north]"; // on the back wall (stone, not the windows), pointing into the room
			// the chandelier
			if (x == 4 && z == 4 && (y == 5 || y == 4)) return "iron_chain[axis=y]";
			if (y == 3 && ((x == 4 && z == 4) || (Math.abs(x - 4) + Math.abs(z - 4) == 1))) return "polished_blackstone";
			if (y == 2 && Math.abs(x - 4) + Math.abs(z - 4) == 1) return "lantern[hanging=true]";
			if (y == 4 && Math.abs(x - 4) + Math.abs(z - 4) == 1) return "candle";
			return null;
		}
		if (y == 6) {
			if (corner) return "polished_andesite";
			if (z == 0) return "stone_brick_stairs[facing=south,half=top]"; // the cornice, its back to the wall
			if (z == 8) return "stone_brick_stairs[facing=north,half=top]";
			if (x == 0) return "stone_brick_stairs[facing=east,half=top]";
			if (x == 8) return "stone_brick_stairs[facing=west,half=top]";
			return "dark_oak_planks";
		}
		if (y >= 7 && y <= 10) {
			int r = y - 7; // each step of the roof one block further in
			if (x < r || x > 8 - r || z < r || z > 8 - r) return null;
			boolean rx = x == r || x == 8 - r, rz = z == r || z == 8 - r;
			if (rx && rz) return "stone_brick_slab[type=double]";
			if (z == r) return "stone_brick_stairs[facing=south]";
			if (z == 8 - r) return "stone_brick_stairs[facing=north]";
			if (x == r) return "stone_brick_stairs[facing=east]";
			if (x == 8 - r) return "stone_brick_stairs[facing=west]";
			return "spruce_planks";
		}
		if (y == 11 && x == 4 && z == 4) return "lantern";
		return null;
	}

	private void hardBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("chapel", 9, 12, 9, SchematicClientTest::chapel);
		BlockPos at = p.offset(5, 0, 3); // on the flat ground, next to where the player stands
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		Map<String, Integer> kinds = new java.util.TreeMap<>();
		for (BlockState st : want.values()) kinds.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), 1, Integer::sum);
		LOG.info("HARD chapel: {} blocks, {} kinds: {}", want.size(), kinds.size(), kinds);
		ctx.runOnClient(mc -> {
			SchematicMode.get().startPreview(sc, "chapel");
			SchematicMode.get().buildAt(at);
		});
		long t0 = System.currentTimeMillis();
		build(ctx, "chapel", 20 * 60 * 12);
		long secs = (System.currentTimeMillis() - t0) / 1000;
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		cmd(sp, "tp @a " + (at.getX() + 4) + " " + (at.getY() + 5) + " " + (at.getZ() - 10) + " 0 20");
		ctx.waitTicks(30);
		ctx.takeScreenshot("chapel-front");
		cmd(sp, "tp @a " + (at.getX() + 4) + " " + (at.getY() + 1) + " " + (at.getZ() + 1) + " 0 -25");
		ctx.waitTicks(30);
		ctx.takeScreenshot("chapel-inside");
		LOG.info("HARD took {} s ({} blocks/min), menu trips {}, doors opened {}, wrong placements logged {}", secs,
				String.format("%.0f", want.size() * 60.0 / Math.max(1, secs)), BoxFillModule.fetches, BoxFillModule.doorsOpened, BoxFillModule.wrongWay);
		check(ctx, sp, "chapel", want, null);
	}

	/**
	 * Exactly like pasting it, 7 x 2 x 7. The ground layer (grass, sunk into the world's grass) has a cobblestone path,
	 * a dirt path and a little farm (farmland round a water source); on it: fully grown wheat, a hopper facing open air
	 * and a log lying east-west at the end of a short beam (both need something to click on that isn't there: a
	 * temporary block), stairs, a potted poppy, three lit candles and an open trapdoor.
	 */
	private static String exactSpec(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (y >= 2) return z == 2 && (x == 5 || (x == 6 && y == 4)) ? "black_concrete" : x == 6 && z == 2 && y == 3 ? "end_rod[facing=down]" : null;
		if (y == 0) {
			if (z == 1 && x >= 1 && x <= 5) return "cobblestone";
			if (z == 3 && x == 3) return "water";
			if (z == 3 && x >= 1 && x <= 5) return "farmland";
			if (z == 5 && x >= 1 && x <= 5) return "dirt_path";
			return "grass_block";
		}
		if (z == 3 && x >= 1 && x <= 5 && x != 3) return "wheat[age=7]";
		if (z == 1 && (x == 1 || x == 2)) return "oak_log[axis=x]";
		if (z == 1 && x == 3) return "hopper[facing=north]"; // (into thin air: needs a temporary block)
		if (z == 1 && x == 0) return "chest[facing=south]";
		if (z == 2 && x == 0) return "hopper[facing=north]"; // into the chest: sneak-click on it
		if (z == 1 && x == 5) return "oak_stairs[facing=east]"; // (waterlogged in open air would flood the place, pasted or not)
		if (z == 6 && x == 1) return "candle[candles=3,lit=true]"; // on the grass (candles can't stand on a path)
		if (z == 6 && x == 3) return "oak_trapdoor[facing=north,half=bottom,open=true]";
		if (z == 6 && x == 5) return "potted_poppy";
		// A lamp arm: a 4-high pillar with an arm, and an end rod hanging under the arm (the rod goes in against the
		// block above it, from the floor, looking up: a player's head must stay out of the rod's cell).
		if (z == 2 && x == 5 && y >= 1 && y <= 4) return "black_concrete";
		if (y == 4 && z == 2 && x == 6) return "black_concrete";
		if (y == 3 && z == 2 && x == 6) return "end_rod[facing=down]";
		// Glazed terracotta: the way it faces is from where the player stands, not the face clicked.
		if (y == 1 && z == 0 && x == 6) return "gray_glazed_terracotta[facing=north]";
		if (y == 1 && z == 0 && x == 0) return "gray_glazed_terracotta[facing=east]";
		return null;
	}

	/**
	 * A: a 3x3x3 brick cube. B, 30 blocks away: a 30-high stone pillar, a beam off its top, and a chain of blocks
	 * hanging from the beam's end down to 3 over the ground (its bottom block floats: nothing to click on until the
	 * one above it is in).
	 */
	private static String tricky(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (x <= 2 && y <= 2 && z <= 2) return "stone_bricks";
		if (z != 0) return null;
		if (x == 30) return "stone";
		if (y == 29 && x >= 31 && x <= 34) return "stone";
		if (x == 34 && y >= 3 && y <= 28) return "andesite";
		return null;
	}

	private void trickyBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("tricky", 35, 30, 3, SchematicClientTest::tricky);
		BlockPos at = p.offset(4, 0, 4);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "tricky");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		int tntAt = -1, repairedAt = -1, lastDone = -1, still = 0, bLeftAtRepair = -1;
		for (int t = 0; t < 20 * 60 * 10; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else if (++still > 20 * 60) {
				LOG.info("tricky: no progress for 60 s, stopping");
				break;
			}
			if (t % 400 == 0) LOG.info("tricky t={}: {}", t, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
			boolean aDone = sp.getServer().computeOnServer(s -> {
				for (int x = 0; x <= 2; x++)
					for (int y = 0; y <= 2; y++)
						for (int z = 0; z <= 2; z++) if (!s.overworld().getBlockState(at.offset(x, y, z)).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) return false;
				return true;
			});
			double myY = ctx.computeOnClient(mc -> mc.player.getY());
			if (tntAt < 0 && aDone && myY > at.getY() + 14) {
				cmd(sp, "summon tnt " + (at.getX() + 1) + " " + (at.getY() + 3) + " " + (at.getZ() + 1) + " {fuse:1}");
				cmd(sp, "summon tnt " + (at.getX() + 3) + " " + (at.getY() + 1) + " " + (at.getZ() + 1) + " {fuse:1}");
				tntAt = t;
				LOG.info("tricky: TNT on the cube at t={} (it's {} blocks up the pillar)", t, String.format("%.0f", myY - at.getY()));
			}
			if (tntAt >= 0 && repairedAt < 0 && t > tntAt + 20 && aDone) {
				repairedAt = t;
				bLeftAtRepair = ctx.computeOnClient(mc -> BoxFillModule.get().planTotal() - BoxFillModule.get().planDone());
				LOG.info("tricky: the cube was put back {} s after the TNT ({} blocks of the rest still to do then)",
						String.format("%.1f", (t - tntAt) / 20.0), bLeftAtRepair);
			}
			if (repairedAt >= 0 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				if (wrong <= 10) LOG.info("tricky wrong: {} wanted {} got {}", e.getKey().toShortString(), e.getValue(), got);
			}
		}
		LOG.info("TRICKY CHECK: {} of {} right; TNT at {}, cube back after {} s", want.size() - wrong, want.size(), tntAt,
				repairedAt < 0 ? "never" : String.format("%.1f", (repairedAt - tntAt) / 20.0));
		if (tntAt < 0) throw new AssertionError("tricky: never got 14 blocks up the pillar (stuck low?)");
		if (repairedAt < 0) throw new AssertionError("tricky: never put the blown-up cube back");
		if (repairedAt - tntAt > 20 * 40) throw new AssertionError("tricky: took " + (repairedAt - tntAt) / 20 + " s to go back for the TNT damage");
		if (wrong > 0) throw new AssertionError("tricky: " + wrong + " wrong");
	}

	/** A 9x9 tower, 24 high: stone brick floor and walls, windows every 4 up, a log at each corner, a slab roof. */
	private static String tower(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		boolean ex = x == 0 || x == 8, ez = z == 0 || z == 8;
		if (y == 0) return "stone_bricks";
		if (y == 23) return "stone_brick_slab[type=bottom]";
		if (ex && ez) return "oak_log[axis=y]";
		if (ex || ez) {
			boolean window = y % 4 == 2 && (x == 4 || z == 4);
			return window ? "glass" : "stone_bricks";
		}
		return null;
	}

	private void towerBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("tower", 9, 24, 9, SchematicClientTest::tower);
		BlockPos at = p.offset(5, 0, 5);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "tower");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		int tntAt = -1, repairedAt = -1, backAt = -1, lastDone = -1, still = 0, worstStill = 0, tntSent = 0, maxBlown = 0;
		List<BlockPos> rightBefore = List.of();
		for (int t = 0; t < 20 * 60 * 14; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else {
				still++;
				if (tntAt >= 0) worstStill = Math.max(worstStill, still);
				if (still > 20 * 90) {
					LOG.info("tower: no progress for 90 s, stopping");
					break;
				}
			}
			if (t % 400 == 0) LOG.info("tower t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
			double myY = ctx.computeOnClient(mc -> mc.player.getY());
			if (tntAt < 0 && myY > at.getY() + 12) {
				tntAt = t;
				rightBefore = rightNear(ctx, 40);
			}
			if (tntAt >= 0 && t > tntAt && (t - tntAt) % 20 == 0) {
				// What the world says, not what he thinks: of what was built right before the TNT, how much is still out.
				int blown = blownOf(ctx, rightBefore);
				maxBlown = Math.max(maxBlown, blown);
				if ((t - tntAt) % 200 == 0) LOG.info("tower TNT +{}s: {} of the {} built before still blown out", (t - tntAt) / 20, blown, rightBefore.size());
				if (t > tntAt + 150 && backAt < 0 && blown == 0) {
					backAt = t;
					LOG.info("tower: everything the TNT broke is back {} s after the first TNT ({} were out at most)",
							String.format("%.1f", (t - tntAt) / 20.0), maxBlown);
				}
			}
			if (tntAt >= 0 && tntSent < 20 && (t - tntAt) % 3 == 0) {
				int i = tntSent++;
				BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
				double ang = i * (Math.PI * 2 / 20) + (i % 3) * 0.4;
				double r = 2 + (i * 7) % 9;
				int x = me.getX() + (int) Math.round(Math.cos(ang) * r), z = me.getZ() + (int) Math.round(Math.sin(ang) * r);
				cmd(sp, "summon tnt " + x + " " + me.getY() + " " + z);
				if (i == 0) {
					LOG.info("tower: 20 TNT round him from t={}s, him at {} ({} up), {} done", t / 20, me.toShortString(),
							String.format("%.0f", myY - at.getY()), done);
				}
			}
			if (tntAt >= 0 && t > tntAt && (t - tntAt) % 200 == 0) {
				LOG.info("tower TNT +{}s: {}", (t - tntAt) / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + " done, "
						+ BoxFillModule.get().damagedCount() + " damaged | " + HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
			}
			if (tntAt >= 0 && t > tntAt + 160 && repairedAt < 0 && ctx.computeOnClient(mc -> BoxFillModule.get().damagedCount()) == 0) {
				repairedAt = t;
				LOG.info("tower: all the damage put back {} s after the first TNT", String.format("%.1f", (t - tntAt) / 20.0));
			}
			if (tntAt >= 0 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				if (wrong <= 12) LOG.info("tower wrong: {} wanted {} got {}", e.getKey().toShortString(), e.getValue(), got);
			}
		}
		LOG.info("TOWER CHECK: {} of {} right; TNT at {}s, {} blown out, all back after {} s (he thought {} s), longest stretch without progress after the TNT {} s",
				want.size() - wrong, want.size(), tntAt / 20, maxBlown, backAt < 0 ? "never" : String.format("%.1f", (backAt - tntAt) / 20.0),
				repairedAt < 0 ? "never" : String.format("%.1f", (repairedAt - tntAt) / 20.0), worstStill / 20);
		if (tntAt < 0) throw new AssertionError("tower: never got 12 up");
		if (backAt < 0) throw new AssertionError("tower: what the TNT broke was never all put back");
		if (backAt - tntAt > 20 * 90) throw new AssertionError("tower: took " + (backAt - tntAt) / 20 + " s to put back what the TNT broke");
		if (worstStill > 20 * 30) throw new AssertionError("tower: stuck " + worstStill / 20 + " s after the TNT");
		if (wrong > 0) throw new AssertionError("tower: " + wrong + " wrong");
	}

	/**
	 * A pillar 5 high; a bar touching its top only along an edge (x 1-3 at y 5); a post touching the bar's end only at
	 * a corner (x 4, z 1, y 6-8). Neither the bar nor the post has a face against anything built.
	 */
	private static String diag(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (x == 0 && z == 0 && y <= 4) return "stone_bricks";
		if (y == 5 && z == 0 && x >= 1 && x <= 3) return "smooth_stone";
		if (x == 4 && z == 1 && y >= 6 && y <= 8) return "polished_andesite";
		return null;
	}

	private void diagBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("diag", 5, 9, 2, SchematicClientTest::diag);
		BlockPos at = p.offset(5, 0, 5);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "diag");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		int lastDone = -1, still = 0, worstStill = 0, t = 0;
		for (; t < 20 * 60 * 4; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else if (++still > worstStill) {
				worstStill = still;
			}
			if (t % 200 == 0) LOG.info("diag t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status()));
			if (still > 20 * 60) break;
			if (t > 100 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0, extra = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				LOG.info("diag wrong: {} wanted {} got {}", e.getKey().toShortString(), e.getValue(), got);
			}
		}
		// Temporary blocks left behind: anything not air in the box (and a block round it) that isn't part of it.
		for (BlockPos c : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(6, 10, 3))) {
			BlockPos q = c.immutable();
			if (want.containsKey(q)) continue;
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(q));
			if (!got.isAir()) {
				extra++;
				LOG.info("diag left behind: {} {}", q.toShortString(), got);
			}
		}
		LOG.info("DIAG CHECK: {} of {} right, {} left behind, {} s, longest stretch without progress {} s", want.size() - wrong, want.size(),
				extra, t / 20, worstStill / 20);
		if (wrong > 0) throw new AssertionError("diag: " + wrong + " wrong");
		if (extra > 0) throw new AssertionError("diag: " + extra + " temporary blocks left behind");
		if (worstStill > 20 * 25) throw new AssertionError("diag: " + worstStill / 20 + " s without progress");
	}

	/** A box cut out of a schematic (its own palette). */
	private static SchematicLoader.Schematic cut(SchematicLoader.Schematic s, String name, int x0, int y0, int z0, int w, int h, int l) {
		List<BlockState> pal = new ArrayList<>();
		Map<BlockState, Integer> ids = new HashMap<>();
		int[] cells = new int[w * h * l];
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					BlockState st = s.blockAt(x0 + x, y0 + y, z0 + z);
					if (st == null) st = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
					Integer i = ids.get(st);
					if (i == null) {
						i = pal.size();
						ids.put(st, i);
						pal.add(st);
					}
					cells[(y * l + z) * w + x] = i;
				}
			}
		}
		return new SchematicLoader.Schematic(name, w, h, l, pal.toArray(new BlockState[0]), cells);
	}

	private void shelvesBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p, String file) {
		SchematicLoader.Schematic full;
		try {
			full = SchematicLoader.load(new java.io.File(file));
		} catch (Exception e) {
			throw new AssertionError("couldn't load " + file + ": " + e, e);
		}
		// The rack: x 33-35, z 72-80, levels at y 6 and 9 (rods between), with a little room round it, the floor under it.
		SchematicLoader.Schematic sc = cut(full, "shelves", 31, 5, 70, 7, 6, 13);
		BlockPos at = p.offset(5, 0, 5);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "shelves");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		int lastDone = -1, still = 0, worstStill = 0, t = 0;
		for (; t < 20 * 60 * 8; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else if (++still > worstStill) {
				worstStill = still;
			}
			if (t % 400 == 0) LOG.info("shelves t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
			if (still > 20 * 90) {
				LOG.info("shelves: no progress for 90 s, stopping");
				break;
			}
			if (t > 40 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				if (wrong <= 12) LOG.info("shelves wrong: {} wanted {} got {}", e.getKey().toShortString(), e.getValue(), got);
			}
		}
		LOG.info("SHELVES CHECK: {} of {} right in {} s, longest stretch without progress {} s", want.size() - wrong, want.size(), t / 20, worstStill / 20);
		if (worstStill > 20 * 20) throw new AssertionError("shelves: " + worstStill / 20 + " s without progress (stuck)");
		if (wrong > 0) throw new AssertionError("shelves: " + wrong + " wrong");
	}

	/**
	 * 7x5x10: a smooth stone floor; a corridor (x 3, z 0-3, 2 high) with an oak door in it (z 1) whose shut panel lies along the
	 * corridor's side; a room (x 1-5, z 4-9, 3 high inside, roofed) at its end; a bookshelf and a crafting table in the
	 * room.
	 */
	private static String doorRoom(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (y == 0) return "smooth_stone"; // (not stone: a layer of natural ground at the bottom sinks into the world's)
		if (z <= 3) {
			if (x == 3 && y <= 2) {
				if (z == 1) return "oak_door[facing=east,half=" + (y == 1 ? "lower" : "upper") + ",hinge=left,open=false,powered=false]";
				return null;
			}
			if (y <= 2 && (x == 2 || x == 4)) return "stone_bricks";
			if (y == 3 && x >= 2 && x <= 4) return "stone_bricks";
			return null;
		}
		if (x < 1 || x > 5) return null;
		if (y == 4) return "oak_planks";
		boolean wall = x == 1 || x == 5 || z == 4 || z == 9;
		if (wall) return x == 3 && z == 4 && y <= 2 ? null : "stone_bricks"; // (the way in from the corridor)
		if (y == 1 && x == 3 && z == 7) return "bookshelf";
		if (y == 1 && x == 2 && z == 6) return "crafting_table";
		return null;
	}

	private void doorBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("door", 7, 5, 10, SchematicClientTest::doorRoom);
		BlockPos at = p.offset(4, 0, 6);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		BlockPos shelf = at.offset(3, 1, 7), table = at.offset(2, 1, 6);
		// All of it there already but the two in the room, and the door standing open (across the corridor).
		sp.getServer().runOnServer(s -> {
			for (var e : want.entrySet()) {
				if (e.getKey().equals(shelf) || e.getKey().equals(table)) continue;
				BlockState st = e.getValue();
				if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) st = st.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true);
				s.overworld().setBlock(e.getKey(), st, 2 | 16);
			}
		});
		// Him out in front of the corridor.
		cmd(sp, "tp @a " + (at.getX() + 3) + " " + (at.getY() + 1) + " " + (at.getZ() - 4) + " 0 10");
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "door");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		int lastDone = -1, still = 0, worstStill = 0, t = 0;
		for (; t < 20 * 60 * 3; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else if (++still > worstStill) {
				worstStill = still;
			}
			if (t % 200 == 0) LOG.info("door t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status() + " | at " + String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", mc.player.getX(), mc.player.getY(), mc.player.getZ())));
			if (still > 20 * 60) break;
			if (t > 40 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				LOG.info("door wrong: {} wanted {} got {}", e.getKey().subtract(at).toShortString(), e.getValue(), got);
			}
		}
		LOG.info("DOOR CHECK: {} of {} right in {} s, longest stretch without progress {} s", want.size() - wrong, want.size(), t / 20, worstStill / 20);
		if (wrong > 0) throw new AssertionError("door: " + wrong + " wrong");
		if (worstStill > 20 * 30) throw new AssertionError("door: " + worstStill / 20 + " s without progress");
	}

	/**
	 * 9x9x9: a basement (stone brick walls, y 0-2) with five end rods hanging from its black concrete ceiling (y 3), two
	 * of them on glass pedestals; a plank house on it (y 4-7) with a doorway, a bookshelf column in the middle, a log post
	 * with a branch of wood standing out from it (wood with its grain up: needs a block over or under it); a plank roof.
	 */
	private static String rods(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		boolean edge = x == 0 || x == 8 || z == 0 || z == 8;
		boolean pedestal = (x == 2 && z == 2) || (x == 4 && z == 4);
		if (y <= 2) {
			if (edge) return "stone_bricks";
			if (y == 0 && pedestal) return "stone";
			if (y == 1 && pedestal) return "black_stained_glass";
			if (y == 2 && (pedestal || (x == 6 && z == 2) || (x == 2 && z == 6) || (x == 6 && z == 6))) return "end_rod[facing=down]";
			return null;
		}
		if (y == 3) return "black_concrete";
		if (y == 8) return "oak_planks";
		if (edge) return x == 4 && z == 0 && y <= 5 ? null : "oak_planks"; // (the doorway)
		if (x == 4 && z == 4 && y <= 5) return "bookshelf";
		if (x == 6 && z == 6 && y <= 6) return "oak_log[axis=y]";
		if (y == 6 && z == 6 && (x == 5 || x == 4)) return "oak_wood[axis=y]";
		return null;
	}

	private void rodsBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("rods", 9, 9, 9, SchematicClientTest::rods);
		BlockPos at = p.offset(5, 0, 5);
		Map<BlockPos, BlockState> want = ctx.computeOnClient(mc -> planOf(sc, 0, at));
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.get().startPreview(sc, "rods");
			SchematicMode.get().buildAt(at);
		});
		lastYaw = Float.NaN;
		lastPos = null;
		// When each spot first came out right (in ticks), for the outside-in check.
		Map<BlockPos, Integer> doneAt = new HashMap<>();
		int lastDone = -1, still = 0, worstStill = 0, t = 0;
		for (; t < 20 * 60 * 8; t++) {
			ctx.waitTick();
			watch(ctx);
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				still = 0;
			} else if (++still > worstStill) {
				worstStill = still;
			}
			if (t % 5 == 0) {
				final int now = t;
				ctx.runOnClient(mc -> {
					for (var e : want.entrySet()) {
						if (!doneAt.containsKey(e.getKey()) && Placement.matches(mc.level.getBlockState(e.getKey()), e.getValue())) doneAt.put(e.getKey(), now);
					}
				});
			}
			if (t % 400 == 0) LOG.info("rods t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/"
					+ BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status() + " | at " + mc.player.blockPosition().toShortString()));
			if (still > 20 * 90) {
				LOG.info("rods: no progress for 90 s, stopping");
				break;
			}
			if (t > 40 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
		}
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0, extra = 0;
		for (var e : want.entrySet()) {
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(e.getKey()));
			if (!Placement.matches(got, e.getValue())) {
				wrong++;
				LOG.info("rods wrong: {} wanted {} got {}", e.getKey().subtract(at).toShortString(), e.getValue(), got);
			}
		}
		for (BlockPos c : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(10, 10, 10))) {
			BlockPos q = c.immutable();
			if (want.containsKey(q)) continue;
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(q));
			if (!got.isAir() && !(q.getY() < at.getY())) {
				extra++;
				LOG.info("rods left behind: {} {}", q.subtract(at).toShortString(), got);
			}
		}
		// Outside in: in each layer, the walls (its edge) before its middle (x and z 2-6). Early: a middle spot that went in
		// with more than a quarter of its layer's edge still to do (one straggler doesn't count).
		int early = 0, inside = 0;
		for (int y = 0; y <= 8; y++) {
			List<Integer> edge = new ArrayList<>();
			List<String> late = new ArrayList<>();
			int firstIn = Integer.MAX_VALUE;
			for (var e : doneAt.entrySet()) {
				BlockPos q = e.getKey().subtract(at);
				if (q.getY() == y && q.getX() >= 2 && q.getX() <= 6 && q.getZ() >= 2 && q.getZ() <= 6) firstIn = Math.min(firstIn, e.getValue());
			}
			for (var e : doneAt.entrySet()) {
				BlockPos q = e.getKey().subtract(at);
				if (q.getY() != y || !(q.getX() == 0 || q.getX() == 8 || q.getZ() == 0 || q.getZ() == 8)) continue;
				edge.add(e.getValue());
				if (e.getValue() > firstIn) late.add(q.getX() + "," + q.getZ() + "@" + e.getValue() / 20 + "s");
			}
			if (!late.isEmpty()) LOG.info("rods layer {}: middle started at {} s with {} of {} edge spots still to do: {}", y, firstIn / 20, late.size(), edge.size(), late);
			for (var e : doneAt.entrySet()) {
				BlockPos q = e.getKey().subtract(at);
				if (q.getY() != y || q.getX() < 2 || q.getX() > 6 || q.getZ() < 2 || q.getZ() > 6) continue;
				inside++;
				int then = e.getValue(), missing = 0;
				for (int te : edge) if (te > then) missing++;
				if (missing * 4 > edge.size()) {
					early++;
					LOG.info("rods inside before the walls: {} at {} s, {} of {} edge spots of its layer still to do", q.toShortString(), then / 20, missing, edge.size());
				}
			}
		}
		LOG.info("RODS CHECK: {} of {} right, {} left behind, {} s, longest stretch without progress {} s, {} of {} inside spots before their layer's walls",
				want.size() - wrong, want.size(), extra, t / 20, worstStill / 20, early, inside);
		if (wrong > 0) throw new AssertionError("rods: " + wrong + " wrong");
		if (extra > 0) throw new AssertionError("rods: " + extra + " temporary blocks left behind");
		if (worstStill > 20 * 25) throw new AssertionError("rods: " + worstStill / 20 + " s without progress");
		if (early * 4 > inside) throw new AssertionError("rods: " + early + " of " + inside + " inside spots went in before their layer's walls");
	}

	/**
	 * The plan's spots within {@code r} of him (sideways; every height of the build: TNT dropped round him falls all the
	 * way down onto whatever is under it) that are built right now, to see later which of them got blown out.
	 */
	private static List<BlockPos> rightNear(ClientGameTestContext ctx, int r) {
		return ctx.computeOnClient(mc -> {
			List<BlockPos> out = new ArrayList<>();
			BlockPos c0 = mc.player.blockPosition();
			int[] bx = SchematicMode.get().builtBox();
			for (BlockPos c : BlockPos.betweenClosed(c0.getX() - r, bx[1], c0.getZ() - r, c0.getX() + r, bx[1] + bx[4] - 1, c0.getZ() + r)) {
				BlockState w = SchematicMode.get().wantAt(c);
				if (w == null || w.isAir() || !Placement.buildable(w)) continue;
				if (Placement.matches(mc.level.getBlockState(c), w)) out.add(c.immutable());
			}
			return out;
		});
	}

	/** How many of these (right before) aren't right now. */
	private static int blownOf(ClientGameTestContext ctx, List<BlockPos> spots) {
		return ctx.computeOnClient(mc -> {
			int n = 0;
			for (BlockPos c : spots) {
				BlockState w = SchematicMode.get().wantAt(c);
				if (w != null && !Placement.matches(mc.level.getBlockState(c), w)) n++;
			}
			return n;
		});
	}

	private void exactBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("exact", 7, 5, 7, SchematicClientTest::exactSpec);
		BlockPos at = p.offset(4, 0, 2); // the corner: on the flat ground (the ground layer sinks into the world's grass)
		BlockPos g = at.below(); // where the schematic's ground layer ends up
		// In the way: a stone and tall grass where the schematic is empty, glass where the path goes.
		cmd(sp, "setblock " + (at.getX() + 6) + " " + at.getY() + " " + (at.getZ() + 4) + " stone");
		cmd(sp, "setblock " + at.getX() + " " + at.getY() + " " + (at.getZ() + 4) + " short_grass");
		cmd(sp, "setblock " + (g.getX() + 2) + " " + g.getY() + " " + (g.getZ() + 1) + " glass");
		// Ground that's ground already: coarse dirt where the schematic's ground has grass. Left alone (no knocking out
		// grass for dirt and back: the user saw it doing that instead of building).
		BlockPos soilSpot = g.offset(6, 0, 0);
		cmd(sp, "setblock " + soilSpot.getX() + " " + soilSpot.getY() + " " + soilSpot.getZ() + " coarse_dirt");
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.get().startPreview(sc, "exact");
			SchematicMode.get().buildAt(at);
		});
		build(ctx, "exact", 20 * 60 * 4);
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		cmd(sp, "tp @a " + (at.getX() + 3) + " " + (at.getY() + 4) + " " + (at.getZ() - 4) + " 0 45");
		ctx.waitTicks(20);
		ctx.takeScreenshot("exact-result");
		// Exactly the schematic: every spot (air included) in its box, from the ground layer up.
		int right = 0, wrong = 0;
		for (int y = 0; y < 5; y++) {
			for (int z = 0; z < 7; z++) {
				for (int x = 0; x < 7; x++) {
					String spec = exactSpec(new int[]{x, y, z});
					BlockState want = SchematicLoader.parseState("minecraft:" + (spec == null ? "air" : spec));
					BlockPos c = g.offset(x, y, z);
					BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
					boolean ok = (Placement.matches(got, want) || (Placement.soil(got) && Placement.soil(want)))
							&& (!want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7)
									|| got.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7) == 7);
					if (ok) right++;
					else {
						wrong++;
						LOG.info("exact wrong: {} (local {} {} {}) wanted {} got {}", c.toShortString(), x, y, z, want, got);
					}
				}
			}
		}
		// No temporary blocks left round it.
		int leftovers = 0;
		for (int x = -1; x <= 7; x++) {
			for (int z = -1; z <= 7; z++) {
				BlockPos c = at.offset(x, 0, z);
				if (sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c)).is(net.minecraft.world.level.block.Blocks.GLASS)) leftovers++;
			}
		}
		boolean soilLeft = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(soilSpot)).is(net.minecraft.world.level.block.Blocks.COARSE_DIRT);
		LOG.info("EXACT CHECK: {} right, {} wrong (of {}), {} temporary blocks left, {} menu trips, {} redone, ground left alone {}", right,
				wrong, right + wrong, leftovers, BoxFillModule.fetches, BoxFillModule.wrongWay, soilLeft);
		if (wrong > 0 || leftovers > 0) throw new AssertionError("exact: " + wrong + " wrong, " + leftovers + " temporary blocks left");
		if (!soilLeft) throw new AssertionError("exact: swapped natural ground (coarse dirt for grass) instead of leaving it");
	}

	private void pondBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("pond", 17, 4, 11, SchematicClientTest::pondSpec);
		BlockPos at = p.offset(4, 0, 2);
		BlockPos g = at.below(); // where the schematic's ground layer ends up
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.get().startPreview(sc, "pond");
			SchematicMode.get().buildAt(at);
		});
		long t0 = System.currentTimeMillis();
		build(ctx, "pond", 20 * 60 * 5);
		long secs = (System.currentTimeMillis() - t0) / 1000;
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		cmd(sp, "tp @a " + (at.getX() + 8) + " " + (at.getY() + 8) + " " + (at.getZ() - 5) + " 0 50");
		ctx.waitTicks(20);
		ctx.takeScreenshot("pond-result");
		int right = 0, wrong = 0, water = 0, pads = 0;
		for (int y = 0; y < 4; y++) {
			for (int z = 0; z < 11; z++) {
				for (int x = 0; x < 17; x++) {
					String spec = pondSpec(new int[]{x, y, z});
					BlockState want = SchematicLoader.parseState("minecraft:" + (spec == null ? "air" : spec));
					BlockPos c = g.offset(x, y, z);
					BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
					boolean ok = Placement.matches(got, want) || (Placement.soil(got) && Placement.soil(want));
					if (ok) {
						right++;
						if (Placement.fluid(want)) water++;
						if (want.is(net.minecraft.world.level.block.Blocks.LILY_PAD)) pads++;
					} else {
						wrong++;
						LOG.info("pond wrong: {} (local {} {} {}) wanted {} got {}", c.toShortString(), x, y, z, want, got);
					}
				}
			}
		}
		LOG.info("POND CHECK: {} right, {} wrong (of {}), water sources {}/{}, lily pads {}/{}, {} s, {} menu trips", right, wrong, right + wrong,
				water, POND_WATER, pads, POND_PADS.length, secs, BoxFillModule.fetches);
		if (wrong > 0) throw new AssertionError("pond: " + wrong + " wrong");
	}

	/**
	 * HA_EXACT=grow: a wall with two rows of vines hanging on it, bamboo, sugar cane by water and an oak sapling, the
	 * random ticks turned right up so they grow while it builds and after. What grows by itself is left as it is (a paste
	 * grows the same), not "repaired" again and again: the user's game cut the top off its bamboo every time it grew a
	 * block, "repairing 1 damaged spots first" across the build each time (1196 of them in an afternoon), and the castle's
	 * vines had him fly 90 blocks down from the roof.
	 */
	private void growBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("grow", 15, 8, 10, SchematicClientTest::growSpec);
		BlockPos at = p.offset(4, 0, 2);
		BlockPos g = at.below(); // where the schematic's ground layer ends up
		for (String c : new String[]{"gamerule random_tick_speed 300", "gamerule randomTickSpeed 300"}) {
			try {
				cmd(sp, c);
			} catch (Throwable ignored) {
			}
		}
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.get().startPreview(sc, "grow");
			SchematicMode.get().buildAt(at);
		});
		long t0 = System.currentTimeMillis();
		int rep0 = BoxFillModule.repairRuns;
		build(ctx, "grow", 20 * 60 * 5);
		long secs = (System.currentTimeMillis() - t0) / 1000;
		int repBuild = BoxFillModule.repairRuns - rep0;
		int rep1 = BoxFillModule.repairRuns;
		long placed1 = BoxFillModule.placedCount;
		// Left to look after it a while longer, everything still growing. (The sapling's spot watched: what it turns into.)
		BlockPos sap = g.offset(11, 1, 4);
		String last = null;
		for (int i = 0; i < 90; i++) {
			ctx.waitTicks(20);
			BlockState s0 = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(sap));
			BlockState s1 = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(sap.above()));
			String now = s0 + " / over it " + s1;
			if (!now.equals(last)) LOG.info("grow sapling spot after {} s: {}", i + 1, now);
			last = now;
		}
		int repGuard = BoxFillModule.repairRuns - rep1;
		long placedGuard = BoxFillModule.placedCount - placed1;
		int grown = ctx.computeOnClient(mc -> BoxFillModule.grownCount());
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		cmd(sp, "tp @a " + (at.getX() + 7) + " " + (at.getY() + 8) + " " + (at.getZ() + 14) + " 180 40");
		ctx.waitTicks(20);
		ctx.takeScreenshot("grow-result");
		int right = 0, wrong = 0, grewOver = 0;
		Map<String, Integer> overKinds = new java.util.TreeMap<>();
		for (int y = 0; y < 8; y++) {
			for (int z = 0; z < 10; z++) {
				for (int x = 0; x < 15; x++) {
					String spec = growSpec(new int[]{x, y, z});
					BlockState want = SchematicLoader.parseState("minecraft:" + (spec == null ? "air" : spec));
					BlockPos c = g.offset(x, y, z);
					BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
					if (Placement.matches(got, want) || (Placement.soil(got) && Placement.soil(want))) {
						right++;
						continue;
					}
					// What grew: a plant, the sapling's tree, where there was air or a plant.
					boolean plantSpot = spec == null || spec.startsWith("vine") || spec.equals("bamboo") || spec.equals("sugar_cane")
							|| spec.equals("oak_sapling");
					boolean plantGot = got.getBlock() instanceof net.minecraft.world.level.block.VineBlock
							|| got.getBlock() instanceof net.minecraft.world.level.block.BambooStalkBlock
							|| got.getBlock() instanceof net.minecraft.world.level.block.SugarCaneBlock
							|| got.is(net.minecraft.tags.BlockTags.LOGS) || got.is(net.minecraft.tags.BlockTags.LEAVES)
							|| got.is(net.minecraft.world.level.block.Blocks.BEE_NEST);
					if (plantSpot && plantGot) {
						grewOver++;
						overKinds.merge(got.getBlock().getName().getString(), 1, Integer::sum);
						continue;
					}
					wrong++;
					LOG.info("grow wrong: {} (local {} {} {}) wanted {} got {}", c.toShortString(), x, y, z, want, got);
				}
			}
		}
		LOG.info("GROW CHECK: {} right, {} wrong (of {}), {} grown over {}, {} grown spots left as they are, repairs {} while building and {}"
				+ " while looking after it (placed {} then), {} s, {} menu trips", right, wrong, right + wrong + grewOver, grewOver, overKinds, grown,
				repBuild, repGuard, placedGuard, secs, BoxFillModule.fetches);
		if (wrong > 0) throw new AssertionError("grow: " + wrong + " wrong");
		if (repGuard > 1) throw new AssertionError("grow: " + repGuard + " repairs of what grew by itself");
	}

	private interface Spec3 {
		String at(int x, int y, int z);
	}

	/** A Sponge v2 .schem file (what the folder takes), made here. */
	private static void writeSchem(java.nio.file.Path f, int w, int h, int l, Spec3 spec) throws java.io.IOException {
		Map<String, Integer> pal = new java.util.LinkedHashMap<>();
		java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
		for (int y = 0; y < h; y++)
			for (int z = 0; z < l; z++)
				for (int x = 0; x < w; x++) {
					int v = pal.computeIfAbsent(spec.at(x, y, z), k -> pal.size());
					while ((v & ~0x7F) != 0) {
						data.write((v & 0x7F) | 0x80);
						v >>>= 7;
					}
					data.write(v);
				}
		var palTag = new net.minecraft.nbt.CompoundTag();
		pal.forEach(palTag::putInt);
		var root = new net.minecraft.nbt.CompoundTag();
		root.putInt("Version", 2);
		root.putShort("Width", (short) w);
		root.putShort("Height", (short) h);
		root.putShort("Length", (short) l);
		root.put("Palette", palTag);
		root.putInt("PaletteMax", pal.size());
		root.putByteArray("BlockData", data.toByteArray());
		net.minecraft.nbt.NbtIo.writeCompressed(root, f);
	}

	/**
	 * HA_CHAIN=1: a hut and a tower in the schematic folder. The hut is started by hand; when it's done she goes on to
	 * the tower by herself, beside it along the row - past the first spot, where somebody's post stands (left alone) -
	 * and when that's done, round to the hut again. CHAIN CHECK: both built right, the post still there.
	 */
	private void chainBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		Spec3 hut = (x, y, z) -> y == 0 ? "minecraft:stone_bricks" : (x == 0 || z == 0 || x == 4 || z == 4) ? "minecraft:cobblestone" : "minecraft:air";
		Spec3 tower = (x, y, z) -> x == 1 && z == 1 && y > 0 ? "minecraft:air" : "minecraft:oak_planks";
		java.nio.file.Path dir = SchematicMode.dir();
		// HA_CHAIN=<file>;<file>...: real files instead (copied in), HA_CHAIN_STARTS builds started (3), HA_MINUTES at most.
		String[] real = "1".equals(System.getenv("HA_CHAIN")) ? null : System.getenv("HA_CHAIN").split(";");
		int goal = Integer.parseInt(System.getenv().getOrDefault("HA_CHAIN_STARTS", "3"));
		List<String> names = new ArrayList<>();
		try {
			if (dir.toString().contains(".minecraft")) throw new AssertionError("chain: not the test's folder: " + dir);
			java.nio.file.Files.createDirectories(dir);
			try (var old = java.nio.file.Files.list(dir)) {
				for (var f : old.toList()) java.nio.file.Files.deleteIfExists(f);
			}
			if (real != null) {
				for (String f : real) {
					java.nio.file.Path src = java.nio.file.Path.of(f.trim());
					java.nio.file.Files.copy(src, dir.resolve(src.getFileName().toString()));
					names.add(src.getFileName().toString());
				}
				names.sort(String.CASE_INSENSITIVE_ORDER);
			} else {
				writeSchem(dir.resolve("a-hut.schem"), 5, 3, 5, hut);
				writeSchem(dir.resolve("b-tower.schem"), 3, 5, 3, tower);
				names.add("a-hut.schem");
			}
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		if (real != null) {
			chainReal(ctx, sp, p, names, goal);
			return;
		}
		BlockPos at = p.offset(4, 0, 4);
		// (The first spot along the row east of the hut: 5 wide + a gap of 6. A post there, somebody's.)
		BlockPos post = at.offset(5 + 6 + 2, 0, 1);
		sp.getServer().runOnServer(s -> {
			s.overworld().setBlock(post, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(), 3);
			s.overworld().setBlock(post.above(), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState(), 3);
		});
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.keepGoing = true;
			String err = SchematicMode.get().choose("a-hut.schem");
			if (err != null) throw new AssertionError("chain: " + err);
			SchematicMode.get().buildAt(at);
		});
		List<String> starts = new ArrayList<>();
		List<int[]> boxes = new ArrayList<>();
		String lastName = null;
		int t = 0;
		for (; t < 20 * 60 * 6 && starts.size() < 3; t++) {
			ctx.waitTick();
			watch(ctx);
			String name = ctx.computeOnClient(mc -> BoxFillModule.get().planMode() ? BoxFillModule.get().planName() : null);
			if (name == null) {
				if (t > 20 * 30) break; // (stopped: no next one started)
				continue;
			}
			if (!name.equals(lastName)) {
				int[] bx = ctx.computeOnClient(mc -> SchematicMode.get().builtBox());
				starts.add(name);
				boxes.add(bx);
				LOG.info("CHAIN start {}: {} at {} {} {} ({}x{}x{}) after {} s", starts.size(), name, bx[0], bx[1], bx[2], bx[3], bx[4], bx[5], t / 20);
				lastName = name;
			}
			if (t % 200 == 0) {
				LOG.info("chain t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planName() + " " + BoxFillModule.get().planDone()
						+ "/" + BoxFillModule.get().planTotal() + (BoxFillModule.get().guarding() ? " guarding" : "") + " | " + HumanAutopilot.status()));
			}
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		// (Stopped part way: the third one is on the list to carry on with; the two done ones aren't.)
		String saved = ctx.computeOnClient(mc -> SchematicMode.get().savedBuild());
		int listed = ctx.computeOnClient(mc -> SchematicMode.get().unfinished().size());
		if (listed != 1) throw new AssertionError("chain: " + listed + " unfinished builds listed, not 1");
		// (The menu with it: the main page, then the Unfinished builds page.)
		ctx.runOnClient(mc -> mc.setScreen(new dev.humanautopilot.AutopilotScreen()));
		ctx.waitTicks(10);
		ctx.takeScreenshot("chain-menu");
		ctx.runOnClient(mc -> mc.setScreen(dev.humanautopilot.AutopilotScreen.unfinishedPage()));
		ctx.waitTicks(10);
		ctx.takeScreenshot("chain-menu-unfinished");
		ctx.runOnClient(mc -> mc.setScreen(null));
		cmd(sp, "tp @a " + (at.getX() + 14) + " " + (at.getY() + 14) + " " + (at.getZ() - 12) + " 0 45");
		ctx.waitTicks(30);
		ctx.takeScreenshot("chain-result");
		int wrong = 0;
		for (int i = 0; i < Math.min(2, boxes.size()); i++) {
			int[] bx = boxes.get(i);
			Spec3 spec = i == 0 ? hut : tower;
			for (int y = 0; y < bx[4]; y++)
				for (int z = 0; z < bx[5]; z++)
					for (int x = 0; x < bx[3]; x++) {
						BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
						String got = sp.getServer().computeOnServer(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
								.getKey(s.overworld().getBlockState(c).getBlock()).toString());
						if (!got.equals(spec.at(x, y, z))) {
							wrong++;
							LOG.info("chain wrong: {} build {} wants {} got {}", c.toShortString(), i + 1, spec.at(x, y, z), got);
						}
					}
		}
		boolean postThere = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(post).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS)
				&& s.overworld().getBlockState(post.above()).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS));
		LOG.info("CHAIN CHECK: starts {}, {} wrong, post {}, saved build {}, {} s", starts, wrong, postThere ? "still there" : "GONE", saved, t / 20);
		if (starts.size() < 3 || !starts.get(0).equals("a-hut.schem") || !starts.get(1).equals("b-tower.schem") || !starts.get(2).equals("a-hut.schem")) {
			throw new AssertionError("chain: started " + starts);
		}
		if (boxes.get(1)[0] <= boxes.get(0)[0] + 5 || boxes.get(2)[0] <= boxes.get(1)[0] + 3) throw new AssertionError("chain: not along the row");
		// (On the ground like the first: the grass under it, not sunk into it - grass_block was taken for a plant.)
		if (boxes.get(1)[1] != boxes.get(0)[1] || boxes.get(2)[1] != boxes.get(0)[1]) throw new AssertionError("chain: not on the ground");
		if (wrong > 0) throw new AssertionError("chain: " + wrong + " wrong");
		if (!postThere) throw new AssertionError("chain: the post got knocked out");
		if (!"a-hut.schem".equals(saved)) throw new AssertionError("chain: saved " + saved);
	}

	/**
	 * HA_SWITCH=1: a 9x5x9 walled yard started; part way, a tower started 40 blocks off instead. When the tower's done,
	 * the yard is on the Unfinished list with how far it got; picked from there, it's finished. SWITCH CHECK.
	 */
	private void switchBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		Spec3 yard = (x, y, z) -> y == 0 ? "minecraft:dirt" : y == 1 ? "minecraft:grass_block" : y == 2 ? "minecraft:stone_bricks"
				: (x == 0 || z == 0 || x == 8 || z == 8) ? "minecraft:bricks" : "minecraft:air";
		Spec3 tower = (x, y, z) -> x == 1 && z == 1 && y > 0 ? "minecraft:air" : "minecraft:oak_planks";
		java.nio.file.Path dir = SchematicMode.dir();
		try {
			if (dir.toString().contains(".minecraft")) throw new AssertionError("switch: not the test's folder: " + dir);
			java.nio.file.Files.createDirectories(dir);
			try (var old = java.nio.file.Files.list(dir)) {
				for (var f : old.toList()) java.nio.file.Files.deleteIfExists(f);
			}
			java.nio.file.Path builds = dir.getParent().resolve("builds");
			if (java.nio.file.Files.isDirectory(builds)) {
				try (var old = java.nio.file.Files.list(builds)) {
					for (var f : old.toList()) java.nio.file.Files.deleteIfExists(f);
				}
			}
			writeSchem(dir.resolve("yard.schem"), 9, 7, 9, yard);
			writeSchem(dir.resolve("tower.schem"), 3, 5, 3, tower);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		BlockPos at = p.offset(4, 0, 4), at2 = p.offset(44, 0, 4);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.keepGoing = false;
			SchematicMode.get().choose("yard.schem");
			SchematicMode.get().buildAt(at);
		});
		int t = 0;
		double firstBar = -1, firstRaw = -1;
		for (; t < 20 * 120; t++) {
			ctx.waitTick();
			watch(ctx);
			if (firstBar < 0 && ctx.computeOnClient(mc -> BoxFillModule.get().planCounted())) {
				firstBar = ctx.computeOnClient(mc -> BoxFillModule.get().planProgress());
				firstRaw = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() / (double) BoxFillModule.get().planTotal());
				LOG.info("SWITCH first count: bar {}%, {}% of it already right (the ground)", Math.round(firstBar * 100), Math.round(firstRaw * 100));
			}
			if (ctx.computeOnClient(mc -> BoxFillModule.get().planCounted() && BoxFillModule.get().planDone() - BoxFillModule.get().planAlready() >= 60)) break;
		}
		if (firstRaw < 0.3 || firstBar > 0.1) throw new AssertionError("switch: the bar started at " + firstBar + " (" + firstRaw + " already right)");
		int total = ctx.computeOnClient(mc -> BoxFillModule.get().planTotal());
		LOG.info("SWITCH yard at {}/{} after {} s: on to the tower", ctx.computeOnClient(mc -> BoxFillModule.get().planDone()), total, t / 20);
		ctx.runOnClient(mc -> {
			SchematicMode.get().choose("tower.schem");
			SchematicMode.get().buildAt(at2);
		});
		build(ctx, "switch-tower", 20 * 120);
		List<String> listed = ctx.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			for (var u : SchematicMode.get().unfinished()) out.add(u.file() + " " + u.done() + "/" + u.total() + " at " + u.at().toShortString());
			return out;
		});
		LOG.info("SWITCH tower done; unfinished: {}", listed);
		if (listed.size() != 1 || !listed.get(0).startsWith("yard.schem")) throw new AssertionError("switch: listed " + listed);
		int doneBefore = ctx.computeOnClient(mc -> SchematicMode.get().unfinished().get(0).done());
		if (doneBefore < 50 || doneBefore >= total) throw new AssertionError("switch: the yard listed at " + doneBefore + "/" + total);
		String err = ctx.computeOnClient(mc -> SchematicMode.get().resume(SchematicMode.get().unfinished().get(0)));
		if (err != null) throw new AssertionError("switch: " + err);
		String name = ctx.computeOnClient(mc -> BoxFillModule.get().planName());
		LOG.info("SWITCH carrying on with {}", name);
		// (Carried on: the bar where it was left - the ground still not counted as built - not back at 0 or at the raw count.)
		for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> BoxFillModule.get().planCounted()); i++) ctx.waitTick();
		double resumedBar = ctx.computeOnClient(mc -> BoxFillModule.get().planProgress());
		LOG.info("SWITCH resumed bar {}%", Math.round(resumedBar * 100));
		if (resumedBar < 0.15 || resumedBar > 0.5) throw new AssertionError("switch: the resumed bar at " + resumedBar);
		build(ctx, "switch-yard", 20 * 240);
		boolean done = ctx.computeOnClient(mc -> BoxFillModule.get().guarding());
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int left = ctx.computeOnClient(mc -> SchematicMode.get().unfinished().size());
		int wrong = 0;
		for (int y = 0; y < 7; y++)
			for (int z = 0; z < 9; z++)
				for (int x = 0; x < 9; x++) {
					BlockPos c = at.offset(x, y - 2, z); // (its 2 layers of ground sunk into the world's)
					String got = sp.getServer().computeOnServer(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
							.getKey(s.overworld().getBlockState(c).getBlock()).toString());
					// (Grass under the floor turns to dirt by itself: soil is soil.)
					boolean soil = y < 2 && (got.equals("minecraft:dirt") || got.equals("minecraft:grass_block"));
					if (!got.equals(yard.at(x, y, z)) && !soil) wrong++;
				}
		cmd(sp, "tp @a " + (at.getX() + 24) + " " + (at.getY() + 12) + " " + (at.getZ() - 14) + " 0 35");
		ctx.waitTicks(30);
		ctx.takeScreenshot("switch-result");
		LOG.info("SWITCH CHECK: yard {} (listed at {}/{}), {} wrong, {} left on the list", done ? "done" : "NOT done", doneBefore, total, wrong, left);
		if (!"yard.schem".equals(name)) throw new AssertionError("switch: carried on with " + name);
		if (!done || wrong > 0) throw new AssertionError("switch: yard " + (done ? "" : "not done, ") + wrong + " wrong");
		if (left != 0) throw new AssertionError("switch: " + left + " still listed");
	}

	/**
	 * HA_LOOSE=1: a 9x3x9 yard (a gold block in its corner shut in by bedrock: "workable", never goes in - what kept
	 * the pagoda's bridges from starting), and three pieces touching nothing: a 3x3 platform of purple glass 2 over the
	 * walls, an amethyst block 3 out from the wall, a column of 3 panes hanging in the air off the other side. LOOSE
	 * CHECK: all three built, no temporary glass left, the yard done, inside 8 minutes.
	 */
	private void looseBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		Spec3 spec = (x, y, z) -> {
			if (x == 7 && y == 1 && z == 7) return "minecraft:gold_block";
			if (x <= 8 && z <= 8 && y == 0) return "minecraft:stone_bricks";
			if (x <= 8 && z <= 8 && (x == 0 || z == 0 || x == 8 || z == 8) && y <= 2) return "minecraft:bricks";
			if (y == 5 && x >= 3 && x <= 5 && z >= 3 && z <= 5) return "minecraft:purple_stained_glass";
			if (x == 12 && y == 3 && z == 4) return "minecraft:amethyst_block";
			if (x == 4 && z == 12 && y >= 4 && y <= 6) return "minecraft:purple_stained_glass_pane";
			return "minecraft:air";
		};
		int W = 14, H = 8, L = 14;
		java.nio.file.Path dir = SchematicMode.dir();
		try {
			if (dir.toString().contains(".minecraft")) throw new AssertionError("loose: not the test's folder: " + dir);
			java.nio.file.Files.createDirectories(dir);
			try (var old = java.nio.file.Files.list(dir)) {
				for (var f : old.toList()) java.nio.file.Files.deleteIfExists(f);
			}
			writeSchem(dir.resolve("loose.schem"), W, H, L, spec);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		BlockPos at = p.offset(4, 0, 4), gold = at.offset(7, 1, 7);
		sp.getServer().runOnServer(s -> {
			for (var d : net.minecraft.core.Direction.values()) s.overworld().setBlock(gold.relative(d), net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 3);
		});
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.keepGoing = false;
			SchematicMode.get().choose("loose.schem");
			SchematicMode.get().buildAt(at);
		});
		int t = 0;
		boolean done = false;
		for (; t < 20 * 60 * 8; t++) {
			ctx.waitTick();
			watch(ctx);
			if (t > 40 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) {
				done = true;
				break;
			}
			if (t % 600 == 0) {
				LOG.info("loose t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal()
						+ " | " + HumanAutopilot.status()));
			}
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		int wrong = 0, glass = 0;
		for (int y = 0; y < H + 2; y++)
			for (int z = -2; z < L + 2; z++)
				for (int x = -2; x < W + 2; x++) {
					BlockPos c = at.offset(x, y, z);
					String got = sp.getServer().computeOnServer(s -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
							.getKey(s.overworld().getBlockState(c).getBlock()).toString());
					if (got.equals("minecraft:glass")) glass++;
					boolean in = x >= 0 && y < H && z >= 0 && x < W && z < L;
					String want = in ? spec.at(x, y, z) : "minecraft:air";
					if (want.equals("minecraft:gold_block") || got.equals("minecraft:bedrock")) continue; // (the shut-in spot)
					// (The two floor spots under the bedrock beside it: shut in too, once the floor round them is in. The one
					// open to the sky beside them has to be there.)
					if (y == 0 && ((x == 6 && z == 7) || (x == 7 && z == 6))) continue;
					if (!got.equals(want)) {
						wrong++;
						if (wrong <= 12) LOG.info("loose wrong: {} (local {} {} {}) wants {} got {}", c.toShortString(), x, y, z, want, got);
					}
				}
		cmd(sp, "tp @a " + (at.getX() + 18) + " " + (at.getY() + 10) + " " + (at.getZ() - 8) + " 50 30");
		ctx.waitTicks(30);
		ctx.takeScreenshot("loose-result");
		LOG.info("LOOSE CHECK: {} after {} s, {} wrong, {} temporary glass left", done ? "done" : "NOT done", t / 20, wrong, glass);
		if (!done || wrong > 0 || glass > 0) throw new AssertionError("loose: " + (done ? "" : "not done, ") + wrong + " wrong, " + glass + " glass left");
	}

	/**
	 * HA_STUCK=1: a 9x3x9 yard with a gold block in its corner shut in by bedrock all round (not the build's: nothing to
	 * click, nothing to take out). Keep going on, a tower next in the folder. STUCK CHECK: the yard done (the gold left as
	 * it is) and the tower started, inside 6 minutes.
	 */
	private void stuckBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		Spec3 yard = (x, y, z) -> x == 7 && y == 1 && z == 7 ? "minecraft:gold_block" : y == 0 ? "minecraft:stone_bricks"
				: (x == 0 || z == 0 || x == 8 || z == 8) && y < 2 ? "minecraft:bricks" : "minecraft:air";
		Spec3 tower = (x, y, z) -> x == 1 && z == 1 && y > 0 ? "minecraft:air" : "minecraft:oak_planks";
		java.nio.file.Path dir = SchematicMode.dir();
		try {
			if (dir.toString().contains(".minecraft")) throw new AssertionError("stuck: not the test's folder: " + dir);
			java.nio.file.Files.createDirectories(dir);
			try (var old = java.nio.file.Files.list(dir)) {
				for (var f : old.toList()) java.nio.file.Files.deleteIfExists(f);
			}
			writeSchem(dir.resolve("a-yard.schem"), 9, 3, 9, yard);
			writeSchem(dir.resolve("b-tower.schem"), 3, 5, 3, tower);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		BlockPos at = p.offset(4, 0, 4), gold = at.offset(7, 1, 7);
		sp.getServer().runOnServer(s -> {
			for (var d : net.minecraft.core.Direction.values()) s.overworld().setBlock(gold.relative(d), net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 3);
		});
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.keepGoing = true;
			SchematicMode.get().choose("a-yard.schem");
			SchematicMode.get().buildAt(at);
		});
		int t = 0;
		String name = "a-yard.schem";
		for (; t < 20 * 60 * 6; t++) {
			ctx.waitTick();
			watch(ctx);
			name = ctx.computeOnClient(mc -> BoxFillModule.get().planMode() ? BoxFillModule.get().planName() : null);
			if (!"a-yard.schem".equals(name)) break;
			if (t % 600 == 0) {
				LOG.info("stuck t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal()
						+ (BoxFillModule.get().guarding() ? " guarding" : "") + " | " + HumanAutopilot.status()));
			}
		}
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		LOG.info("STUCK CHECK: after {} s building {}", t / 20, name);
		if (!"b-tower.schem".equals(name)) throw new AssertionError("stuck: still on " + name + " after " + t / 20 + " s");
	}

	/** HA_CHAIN with real files (see chainBuild): started where he stands, on whatever ground is there. */
	private void chainReal(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p, List<String> names, int goal) {
		BlockPos at = p.offset(4, 0, 4);
		ctx.waitTicks(100);
		sp.getClientLevel().waitForChunksRender();
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.keepGoing = true;
			String err = SchematicMode.get().choose(names.get(0));
			if (err != null) throw new AssertionError("chain: " + err);
			SchematicMode.get().buildAt(at);
		});
		int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_MINUTES", "8"));
		List<String> starts = new ArrayList<>();
		String lastName = null;
		int t = 0, idle = 0;
		for (; t < minutes * 1200 && starts.size() < goal; t++) {
			ctx.waitTick();
			watch(ctx);
			String name = ctx.computeOnClient(mc -> BoxFillModule.get().planMode() ? BoxFillModule.get().planName() : null);
			if (name == null) {
				if (++idle > 20 * 30) break; // (stopped: no next one started)
				continue;
			}
			idle = 0;
			if (!name.equals(lastName)) {
				int[] bx = ctx.computeOnClient(mc -> SchematicMode.get().builtBox());
				starts.add(name);
				LOG.info("CHAIN start {}: {} at {} {} {} ({}x{}x{}) after {} s", starts.size(), name, bx[0], bx[1], bx[2], bx[3], bx[4], bx[5], t / 20);
				lastName = name;
				ctx.takeScreenshot("chain-start-" + starts.size());
				// HA_CHAIN_PASTE=1: all of it but the top 3 layers pasted straight in, so each one is done in a minute or two
				// (what's tested is going on from one to the next, on the world's own ground).
				if (System.getenv("HA_CHAIN_PASTE") != null) {
					List<Object[]> puts = ctx.computeOnClient(mc -> {
						List<Object[]> out = new ArrayList<>();
						for (int y = 0; y < bx[4] - 3; y++)
							for (int z = 0; z < bx[5]; z++)
								for (int x = 0; x < bx[3]; x++) {
									BlockPos c = new BlockPos(bx[0] + x, bx[1] + y, bx[2] + z);
									BlockState w = SchematicMode.get().wantAt(c);
									if (w == null || !Placement.buildable(w)) continue;
									if (w.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)) {
										w = w.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
									}
									out.add(new Object[]{c, w});
								}
						return out;
					});
					sp.getServer().runOnServer(sv -> {
						for (Object[] o : puts) sv.overworld().setBlock((BlockPos) o[0], (BlockState) o[1], 2 | 16);
					});
					LOG.info("CHAIN pasted {} blocks of {} (all but the top 3 layers)", puts.size(), name);
				}
			}
			if (t % 600 == 0) {
				LOG.info("chain t={}s: {}", t / 20, ctx.computeOnClient(mc -> BoxFillModule.get().planName() + " " + BoxFillModule.get().planDone()
						+ "/" + BoxFillModule.get().planTotal() + (BoxFillModule.get().guarding() ? " guarding" : "") + " | " + HumanAutopilot.status()));
			}
		}
		boolean preview = ctx.computeOnClient(mc -> SchematicMode.get().previewing());
		LOG.info("CHAIN CHECK: starts {}, {} s{}", starts, t / 20, preview ? ", ended in a preview (no open ground)" : "");
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		if (starts.size() < goal) throw new AssertionError("chain: started " + starts);
	}

	/**
	 * HA_CASE=<file>: a spot the user's game left as it was for good (BoxFillModule.caseFile wrote down the world's
	 * blocks and the build's round it, 13 across), put back together here - the world's blocks pasted, what the build
	 * wants in that cube as a little schematic over them - and tried again from where he was. CASE CHECK: in or not.
	 */
	private void caseBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p, String file) {
		com.google.gson.JsonObject o;
		try {
			o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(file))).getAsJsonObject();
		} catch (java.io.IOException e) {
			throw new AssertionError("case: can't read " + file + ": " + e);
		}
		int r = o.get("r").getAsInt(), n = 2 * r + 1;
		var palA = o.getAsJsonArray("palette");
		BlockState[] pal = new BlockState[palA.size()];
		String[] ids = new String[palA.size()];
		for (int k = 0; k < pal.length; k++) {
			String s0 = palA.get(k).getAsString();
			pal[k] = SchematicLoader.parseState(s0);
			ids[k] = s0.startsWith("minecraft:") ? s0.substring(10) : s0;
		}
		var worldA = o.getAsJsonArray("world");
		var planA = o.getAsJsonArray("plan");
		var tA = o.getAsJsonArray("target");
		var pA = o.getAsJsonArray("player");
		BlockPos tgt = new BlockPos(tA.get(0).getAsInt(), tA.get(1).getAsInt(), tA.get(2).getAsInt());
		// The cube's corner here: its bottom on the test world's ground, the target r over it.
		BlockPos base = p.offset(4, 0, 2);
		BlockPos at = base.offset(r, r, r);
		List<Object[]> puts = new ArrayList<>();
		int[] wantIdx = new int[n * n * n];
		int i = 0;
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					int wi = worldA.get(i).getAsInt(), pi = planA.get(i).getAsInt();
					puts.add(new Object[]{at.offset(dx, dy, dz), pal[wi]});
					wantIdx[i] = pi >= 0 ? pi : wi; // (nothing in particular wanted there: as it is)
					i++;
				}
			}
		}
		sp.getServer().runOnServer(s -> {
			for (Object[] q : puts) s.overworld().setBlock((BlockPos) q[0], (BlockState) q[1], 2 | 16);
		});
		SchematicLoader.Schematic sc = schem("case", n, n, n, c -> ids[wantIdx[(c[1] * n + c[2]) * n + c[0]]]);
		int ti = (r * n + r) * n + r;
		BlockState want = pal[wantIdx[ti]];
		boolean rep = !o.has("replace") || o.get("replace").getAsBoolean();
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = rep;
			SchematicMode.skipGround = false;
			SchematicMode.get().startPreview(sc, "case");
			SchematicMode.get().buildAt(base);
		});
		// From where he was then (as near as this world lets him be).
		double px = pA.get(0).getAsDouble() - tgt.getX() + at.getX(), py = pA.get(1).getAsDouble() - tgt.getY() + at.getY(),
				pz = pA.get(2).getAsDouble() - tgt.getZ() + at.getZ();
		cmd(sp, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f", px, py, pz));
		LOG.info("CASE {}: {} at {} (here {}), why {}, the build wants {}; {} kinds of block round it", file, tgt.toShortString(),
				at.toShortString(), want, o.get("why").getAsString(), want, pal.length);
		long t0 = System.currentTimeMillis();
		build(ctx, "case", 20 * 120);
		long secs = (System.currentTimeMillis() - t0) / 1000;
		BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(at));
		boolean in = Placement.matches(got, want);
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		// (And everything else the build wanted in the cube.)
		int wanted = 0, right = 0;
		i = 0;
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++, i++) {
					int pi = planA.get(i).getAsInt();
					if (pi < 0 || pi == worldA.get(i).getAsInt()) continue;
					wanted++;
					BlockPos c = at.offset(dx, dy, dz);
					BlockState g = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
					if (Placement.matches(g, pal[pi]) || (Placement.soil(g) && Placement.soil(pal[pi]))) right++;
					else LOG.info("case still not right: {} wanted {} got {}", c.toShortString(), pal[pi], g);
				}
			}
		}
		LOG.info("CASE CHECK: {} {} - wanted {} got {}; {} of the {} spots the build wanted in the cube right; {} s, {} placed, {} skipped",
				in ? "IN" : "STILL NOT IN", tgt.toShortString(), want, got, right, wanted, secs, BoxFillModule.placedCount, BoxFillModule.skippedCount);
		if (!in) throw new AssertionError("case " + tgt.toShortString() + ": still not in");
	}

	/**
	 * HA_EXACT=eaves: a wall with a log beam on it, an inner and an outer row of upside-down stairs off it and a roof over
	 * them - the Himeji castle's eaves. The inner row goes in first (against the beam, from outside), then the outer one
	 * against it; the other way round the inner row's only right-way click is gone ("nowhere", the build's blocks taken out
	 * to get at it). EAVES CHECK: everything right, how many had to be taken out again, how many were "nowhere".
	 */
	private void eavesBuild(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos p) {
		SchematicLoader.Schematic sc = schem("eaves", 12, 7, 8, SchematicClientTest::eavesSpec);
		BlockPos at = p.offset(4, 0, 2);
		BlockPos g = at.below();
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> {
			SchematicMode.replace = true;
			SchematicMode.skipGround = true;
			SchematicMode.get().startPreview(sc, "eaves");
			SchematicMode.get().buildAt(at);
		});
		long t0 = System.currentTimeMillis();
		long skip0 = BoxFillModule.skippedCount;
		build(ctx, "eaves", 20 * 60 * 4);
		long secs = (System.currentTimeMillis() - t0) / 1000;
		ctx.runOnClient(mc -> HumanAutopilot.panic());
		cmd(sp, "tp @a " + (at.getX() + 6) + " " + (at.getY() + 3) + " " + (at.getZ() + 12) + " 180 10");
		ctx.waitTicks(20);
		ctx.takeScreenshot("eaves-result");
		int right = 0, wrong = 0;
		for (int y = 0; y < 7; y++) {
			for (int z = 0; z < 8; z++) {
				for (int x = 0; x < 12; x++) {
					String spec = eavesSpec(new int[]{x, y, z});
					BlockState want = SchematicLoader.parseState("minecraft:" + (spec == null ? "air" : spec));
					BlockPos c = g.offset(x, y, z);
					BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
					if (Placement.matches(got, want) || (Placement.soil(got) && Placement.soil(want))) right++;
					else {
						wrong++;
						LOG.info("eaves wrong: {} (local {} {} {}) wanted {} got {}", c.toShortString(), x, y, z, want, got);
					}
				}
			}
		}
		LOG.info("EAVES CHECK: {} right, {} wrong (of {}), {} skips, {} s, {} menu trips", right, wrong, right + wrong,
				BoxFillModule.skippedCount - skip0, secs, BoxFillModule.fetches);
		if (wrong > 0) throw new AssertionError("eaves: " + wrong + " wrong");
	}

	/** eavesSpec's: grass; a stone brick wall, a spruce beam on it; two rows of upside-down quartz stairs off it; a roof. */
	private static String eavesSpec(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (y == 0) return "grass_block";
		// (End walls the rows butt up against, as on the castle: the outer row can go in from its end, one from the next.)
		if ((x == 0 || x == 11) && z >= 1 && z <= 3 && y <= 5) return "stone_bricks";
		if (x < 1 || x > 10) return null;
		if (z == 1 && y <= 3) return "stone_bricks";
		if (z == 1 && y == 4) return "spruce_log[axis=x]";
		if ((z == 2 || z == 3) && y == 4) return "quartz_stairs[facing=north,half=top]";
		if (z >= 1 && z <= 3 && y == 5) return "smooth_quartz";
		return null;
	}

	/** growBuild's: grass, a stone brick wall with vines on it, bamboo, sugar cane by a little pool, an oak sapling. */
	private static String growSpec(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		if (y == 0) return "grass_block";
		if (z == 1 && x >= 1 && x <= 13 && y <= 6) return "stone_bricks";
		if (z == 2 && x >= 2 && x <= 12 && y >= 5 && y <= 6) return "vine[north=true]";
		if (z == 5 && (x == 2 || x == 4 || x == 6) && y <= 2) return "bamboo";
		if (y == 1 && z >= 7 && z <= 9 && x >= 1 && x <= 3) return x == 2 && z == 8 ? "water" : x == 1 && z == 8 ? "grass_block" : "stone_bricks";
		if (x == 1 && z == 8 && y == 2) return "sugar_cane";
		// (Within 3 of the wall: further out on its own, a plant is the yard round a build, left out of it.)
		if (x == 11 && z == 4 && y == 1) return "oak_sapling";
		return null;
	}

	private static final int[][] POND_PADS = {{2, 2}, {3, 2}, {5, 4}, {8, 7}, {10, 8}, {6, 6}, {4, 8}, {9, 2}, {14, 5}, {11, 5}};
	private static final int POND_WATER = 11 * 9 + 1 + 3;

	/** pondBuild's: grass, a stone brick floor, a wall round an 11 x 9 pond and a 3-long channel off it, lily pads. */
	private static String pondSpec(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		boolean basin = x <= 12, inBasin = x >= 1 && x <= 11 && z >= 1 && z <= 9;
		boolean chan = x >= 12 && x <= 15 && z == 5, chanWall = x >= 12 && x <= 16 && (z == 4 || z == 6) || x == 16 && z == 5;
		if (y == 0) return "grass_block";
		if (y == 1) return basin || x >= 12 && z >= 4 && z <= 6 ? "stone_bricks" : null;
		if (y == 2) {
			if (inBasin || chan) return "water";
			if (basin || chanWall) return "stone_bricks";
			return null;
		}
		for (int[] pd : POND_PADS) if (pd[0] == x && pd[1] == z) return "lily_pad";
		return null;
	}

	/** The house: floor, log corners, cobblestone walls with a door and a torch inside, stairs round the roof, slabs on top. */
	private static String house(int[] c) {
		int x = c[0], y = c[1], z = c[2];
		boolean ex = x == 0 || x == 4, ez = z == 0 || z == 4;
		if (y == 0) return "oak_planks";
		if (y <= 2) {
			if (ex && ez) return "oak_log[axis=y]";
			if (x == 2 && z == 0) return "oak_door[facing=south,half=" + (y == 1 ? "lower" : "upper") + ",hinge=left]";
			if (ex || ez) return "cobblestone";
			if (y == 2 && x == 2 && z == 3) return "wall_torch[facing=north]";
			return null;
		}
		if (ex && ez) return x == 0 && z == 0 ? "oak_slab[type=double]" : "oak_planks";
		if (z == 0) return "oak_stairs[facing=south]";
		if (z == 4) return "oak_stairs[facing=north]";
		if (x == 0) return "oak_stairs[facing=east]";
		if (x == 4) return "oak_stairs[facing=west]";
		if (x == 1 && z == 1) return "oak_slab[type=bottom]";
		return "oak_slab[type=top]";
	}

	private static SchematicLoader.Schematic schem(String name, int w, int h, int l, Function<int[], String> at) {
		List<BlockState> pal = new ArrayList<>();
		Map<String, Integer> ids = new HashMap<>();
		int[] cells = new int[w * h * l];
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					String id = at.apply(new int[]{x, y, z});
					String key = "minecraft:" + (id == null ? "air" : id);
					Integer i = ids.get(key);
					if (i == null) {
						i = pal.size();
						ids.put(key, i);
						pal.add(SchematicLoader.parseState(key));
					}
					cells[(y * l + z) * w + x] = i;
				}
			}
		}
		return new SchematicLoader.Schematic(name, w, h, l, pal.toArray(new BlockState[0]), cells);
	}

	/** What the mode itself plans for this schematic turned {@code rot} times at {@code at} (checked against the world). */
	private static Map<BlockPos, BlockState> planOf(SchematicLoader.Schematic s, int rot, BlockPos at) {
		SchematicMode m = SchematicMode.get();
		// SchematicMode.plan uses its current turn: rebuild the same thing here independently for the check.
		Map<BlockPos, BlockState> out = new HashMap<>();
		net.minecraft.world.level.block.Rotation r = net.minecraft.world.level.block.Rotation.values()[rot];
		for (int y = 0; y < s.height; y++) {
			for (int z = 0; z < s.length; z++) {
				for (int x = 0; x < s.width; x++) {
					BlockState st = s.blockAt(x, y, z);
					if (st == null || !Placement.buildable(st)) continue;
					int rx = rot == 1 ? s.length - 1 - z : x, rz = rot == 1 ? x : z;
					out.put(at.offset(rx, y, rz), st.rotate(r));
				}
			}
		}
		return out;
	}

	private void build(ClientGameTestContext ctx, String name, int maxTicks) {
		lastYaw = Float.NaN;
		lastPos = null;
		int t = 0;
		long placed0 = BoxFillModule.placedCount;
		int lastDone = -1, lastProgress = 0;
		for (; t < maxTicks; t++) {
			ctx.waitTick();
			watch(ctx);
			if (!ctx.computeOnClient(mc -> BoxFillModule.get().planMode())) break;
			if (t > 20 && ctx.computeOnClient(mc -> BoxFillModule.get().guarding() && BoxFillModule.get().holesLeft() == 0)) break;
			int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			if (done != lastDone) {
				lastDone = done;
				lastProgress = t;
			} else if (t - lastProgress > 600) {
				LOG.info("{}: no progress for 30 s, stopping", name);
				break;
			}
			if (t % 200 == 0) {
				String st = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status());
				LOG.info("{} t={}: {}", name, t, st);
			}
			if (t == 400) {
				ctx.takeScreenshot("schem-" + name + "-building");
				lastYaw = Float.NaN;
				lastPos = null;
			}
			if (!menuShot && ctx.computeOnClient(mc -> mc.screen != null && HumanAutopilot.botOwnsScreen())) {
				ctx.waitTicks(12);
				ctx.takeScreenshot("schem-menu-cursor");
				menuShot = true;
			}
		}
		ctx.takeScreenshot("schem-" + name + "-done");
		LOG.info("{}: {} ticks, {} clicks that placed, {} skips", name, t, BoxFillModule.placedCount - placed0, BoxFillModule.skippedCount);
		LOG.info("{} VIEW: {} clicks, average pitch {} deg, average distance to the side {} blocks, {} looking straight down",
				name, BoxFillModule.clicks, String.format("%.0f", BoxFillModule.clickPitchSum / Math.max(1, BoxFillModule.clicks)),
				String.format("%.1f", BoxFillModule.clickDistSum / Math.max(1, BoxFillModule.clicks)), BoxFillModule.steepClicks);
		LOG.info("{} CAMERA{}: {} turning frames, {} turned back the other way frame to frame ({}%; the user ~0.1%)", name,
				dev.humanautopilot.AimController.oldCamera ? " (old)" : "", dev.humanautopilot.AimController.turningFrames,
				dev.humanautopilot.AimController.flipFrames, String.format("%.2f",
						100.0 * dev.humanautopilot.AimController.flipFrames / Math.max(1, dev.humanautopilot.AimController.turningFrames)));
		if (t >= maxTicks) {
			String st = ctx.computeOnClient(mc -> BoxFillModule.get().planDone() + "/" + BoxFillModule.get().planTotal() + " | " + HumanAutopilot.status());
			LOG.info("{}: not finished in time: {}", name, st);
		}
	}

	private void check(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, Map<BlockPos, BlockState> want, BlockPos glass) {
		int ok = 0, wrongWay = 0, missing = 0;
		List<String> bad = new ArrayList<>();
		for (var e : want.entrySet()) {
			BlockPos c = e.getKey();
			BlockState got = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(c));
			if (c.equals(glass)) continue;
			if (Placement.matches(got, e.getValue())) ok++;
			else {
				if (got.getBlock() == e.getValue().getBlock()) wrongWay++;
				else missing++;
				bad.add(c.toShortString() + " wanted " + e.getValue() + " got " + got);
			}
		}
		for (String s : bad) LOG.info("{} wrong: {}", name, s);
		LOG.info("{} CHECK: {} right, {} wrong way round, {} missing (of {})", name, ok, wrongWay, missing, want.size());
		if (glass != null) {
			BlockState g = sp.getServer().computeOnServer(s -> s.overworld().getBlockState(glass));
			if (!g.is(net.minecraft.world.level.block.Blocks.GLASS)) throw new AssertionError("broke the glass that was in the way: " + g);
		}
		if (wrongWay > 0 || missing > 0) throw new AssertionError(name + ": " + wrongWay + " wrong way round, " + missing + " missing");
	}

	private void watch(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			var pl = mc.player;
			if (mc.screen != null) { // in the creative menu: the camera isn't ours to judge, and we glide on a bit
				lastYaw = Float.NaN;
				lastPos = null;
				return;
			}
			float yaw = pl.getYRot();
			if (!Float.isNaN(lastYaw)) maxTurn = Math.max(maxTurn, Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw)));
			lastYaw = yaw;
			Vec3 pos = pl.position();
			// (His own /tp out of a stuck spot is allowed: the valley run died on it, "moved 80 blocks in one tick".)
			boolean ownTp = dev.humanautopilot.HumanAutopilot.ticks() - BoxFillModule.lastTpTick < 60;
			if (lastPos != null && !ownTp) maxStep = Math.max(maxStep, pos.distanceTo(lastPos));
			lastPos = pos;
			if (maxTurn > 70) throw new AssertionError("camera snapped " + String.format("%.0f", maxTurn) + " deg in one tick");
			if (maxStep > 1.6) throw new AssertionError("moved " + String.format("%.2f", maxStep) + " blocks in one tick (teleport?)");
		});
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}
}
