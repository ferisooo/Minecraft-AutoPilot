package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.BoxFillModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Box fill (the StreamToEarn Bedrock Box) in creative, flying, the way the streamer plays it:
 * patch scattered holes and a crater (one sealed hole must be left alone), get out of the way of lit TNT, then
 * refill the whole box after a wipe. Throughout: no teleports, no camera snaps, never closed in by its own blocks.
 * Run with HA_ONLY=box.
 */
public class BoxClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("box-test");
	/** The user's server box: radius 8 (15 inside), 15 high, bands of 3 from amethyst up to diamond. */
	private static final int R = 7, H = 15;
	private static final String LAYERS = "amethyst_block,amethyst_block,amethyst_block,emerald_block,emerald_block,emerald_block,"
			+ "iron_block,iron_block,iron_block,gold_block,gold_block,gold_block,diamond_block,diamond_block,diamond_block";

	private int cx, y0, cz;
	private double maxTurn, maxStep;
	private int enclosedTicks, worstEnclosed, lowTicks;
	/** Up/down direction changes while flying (bouncing), and the last vertical direction. */
	private int vFlips, vLast;
	private double lastY = Double.NaN;
	private float lastYaw = Float.NaN;
	private Vec3 lastPos;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"box".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			cx = p.getX() + 14;
			cz = p.getZ();
			y0 = p.getY() + 20;
			cmd(sp, "gamemode creative @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doDaylightCycle false");
			// The user's real box (s2e radius 8, height 15): bedrock shell, 15x15x15 inside, five 3-high bands.
			fill(sp, cx - R - 1, y0, cz - R - 1, cx + R + 1, y0 + H, cz + R + 1, "bedrock");
			fillLayers(sp);
			// A ledge to start from, outside the box.
			fill(sp, cx - R - 6, y0 + H, cz - 1, cx - R - 4, y0 + H, cz + 1, "stone");
			cmd(sp, "tp @a " + (cx - R - 5) + " " + (y0 + H + 1) + " " + cz + " -90 20");
			cmd(sp, "clear @a");
			stock(sp);
			// Standing on the ledge, not flying yet: it has to double-tap jump to take off.
			sp.getServer().runOnServer(s -> {
				for (var pl : s.getPlayerList().getPlayers()) {
					pl.getAbilities().flying = false;
					pl.onUpdateAbilities();
				}
			});
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> {
				HumanAutopilot.setLocalBox(cx - R, y0 + 1, cz - R, cx + R, y0 + H, cz + R, LAYERS);
				HumanAutopilot.setBoxFill(true);
				// Record the bot with the player recorder, to compare with the user's own box-filling recording.
				dev.humanautopilot.record.Recorder.recordBot = true;
				HumanAutopilot.recorder().start();
			});

			// HA_STAGES=barrage: just a long TNT storm, counting how much it bounces up and down (the user: "it goes up
			// and down like jumping when TNT is summoned"). The recorded player changes vertical direction ~19 times a
			// minute.
			if ("barrage".equals(System.getenv("HA_STAGES"))) {
				long placed0 = BoxFillModule.placedCount;
				lastYaw = Float.NaN;
				lastPos = null;
				for (int t = 0; t < 1200; t++) {
					if (t % 10 == 0) {
						int bx = cx - R + 2 + (t * 7) % (2 * R - 3), bz = cz - R + 2 + (t * 13) % (2 * R - 3);
						cmd(sp, "summon tnt " + bx + " " + (y0 + H + 2) + " " + bz + " {fuse:20}");
					}
					ctx.waitTick();
					watch(ctx);
					if (t == 600) vFlips = 0; // second half only: it's working in craters by then
				}
				double perMin = vFlips / 0.5;
				ctx.takeScreenshot("box-barrage");
				LOG.info("BARRAGE: placed {} in 60 s of TNT, up/down direction changes {}/min (second half), stopped flying {} times",
						BoxFillModule.placedCount - placed0, String.format("%.0f", perMin), BoxFillModule.landings);
				LOG.info("BARRAGE vertical input ticks: off-ground {}, up {}, down {}; doing: {}", BoxFillModule.vGuard,
						BoxFillModule.vUp, BoxFillModule.vDown, BoxFillModule.noteTicks);
				ctx.runOnClient(mc -> {
					HumanAutopilot.recorder().stop();
					HumanAutopilot.panic();
				});
				return;
			}

			// 1) Scattered holes in the top, a crater, and one sealed hole at the bottom it can't reach.
			for (int i = 0; i < 20; i++) {
				int x = cx - R + (i * 7) % (2 * R + 1), z = cz - R + (i * 11) % (2 * R + 1);
				setBlock(sp, x, y0 + H, z, "air");
			}
			crater(sp, cx - 3, cz + 2, 3, 3);
			setBlock(sp, cx + R, y0 + 1, cz + R, "air"); // sealed: solid on all six sides
			LOG.info("stage patch: {} holes", holes(sp));
			run(ctx, sp, "patch", 1, 3600);

			// 2) Lit TNT right next to it: creative can't be hurt, so keep working; then mend what it broke.
			Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
			long dodgesBefore = BoxFillModule.dodgeCount;
			cmd(sp, String.format(java.util.Locale.ROOT, "summon tnt %.1f %.1f %.1f {fuse:50}", at.x + 1.5, Math.max(at.y - 1, y0 + H + 1.0), at.z));
			lastYaw = Float.NaN;
			lastPos = null;
			for (int i = 0; i < 90; i++) {
				ctx.waitTick();
				watch(ctx);
			}
			if (BoxFillModule.dodgeCount != dodgesBefore) throw new AssertionError("flew away from TNT in creative");
			LOG.info("stage tnt: kept working through it");
			run(ctx, sp, "tnt-mend", 1, 3600);

			// 3) Stuck down a 1x1 hole: fill under its feet and come up, like pillaring out.
			int before = holes(sp);
			fill(sp, cx, y0 + H - 5, cz, cx, y0 + H, cz, "air");
			cmd(sp, "tp @a " + cx + ".5 " + (y0 + H - 5) + " " + cz + ".5");
			LOG.info("stage shaft: 6-deep 1x1 hole with it at the bottom");
			run(ctx, sp, "shaft", before, 1200);

			// 3b) A long 1x1 hole (10 deep) with a few holes around: it should finish the long one, not wander off.
			before = holes(sp);
			fill(sp, cx + 3, y0 + H - 9, cz + 3, cx + 3, y0 + H, cz + 3, "air");
			for (int i = 0; i < 5; i++) setBlock(sp, cx - 4 + i * 2, y0 + H, cz - 4, "air");
			LOG.info("stage long-shaft: 10-deep 1x1 hole and 5 others");
			run(ctx, sp, "long-shaft", before, 1200);

			// 3c) A big hollow under the roof, reachable only through a small hole (the user's box had one): it has to
			// fly in through the hole, fill the hollow, get back out and finish the hole.
			before = holes(sp);
			fill(sp, cx - 3, y0 + H - 4, cz - 3, cx + 3, y0 + H - 2, cz + 3, "air");
			fill(sp, cx, y0 + H - 1, cz, cx, y0 + H, cz, "air");
			LOG.info("stage hollow: 7x3x7 hollow under a 2-thick roof, one 1x1 hole in: {} holes", holes(sp) - before);
			// Done when it stops placing for 10 s; report how much of the hollow got filled (sealed leftovers are fine).
			long hollowStart = BoxFillModule.placedCount, lastCount = -1;
			int quiet = 0, t0 = 0;
			for (; t0 < 4800 && quiet < 200; t0++) {
				ctx.waitTick();
				watch(ctx);
				quiet = BoxFillModule.placedCount == lastCount ? quiet + 1 : 0;
				lastCount = BoxFillModule.placedCount;
			}
			int left = holes(sp) - before;
			LOG.info("stage hollow: filled {} blocks in {} ticks, {} of 149 left", BoxFillModule.placedCount - hollowStart, t0 - quiet, left);
			if (left > 40) throw new AssertionError("hollow: only got " + (149 - left) + " of 149 in");
			// Then back out through the way in (filling the hollow in for the next stage with it still inside buried it).
			int outAt = -1;
			for (int t = 0; t < 600 && outAt < 0; t++) {
				ctx.waitTick();
				watch(ctx);
				if (ctx.computeOnClient(mc -> mc.player.getY() > y0 + H + 0.5 || Math.abs(mc.player.getX() - (cx + 0.5)) > 3.8
						|| Math.abs(mc.player.getZ() - (cz + 0.5)) > 3.8)) outAt = t;
			}
			LOG.info("stage hollow: out of the hollow {} ticks after it stopped filling; status={}", outAt,
					ctx.computeOnClient(mc -> HumanAutopilot.status()));
			if (outAt < 0) throw new AssertionError("hollow: still inside the hollow 30 s after it stopped filling");
			fill(sp, cx - 3, y0 + H - 4, cz - 3, cx + 3, y0 + H, cz + 3, "gold_block"); // tidy up for the next stage

			// 4) A stream's worth of TNT: craters of different sizes all over the top.
			before = holes(sp);
			crater(sp, cx + 4, cz - 4, 3, 4);
			crater(sp, cx - 5, cz - 5, 2, 2);
			crater(sp, cx + 5, cz + 5, 3, 3);
			crater(sp, cx - 1, cz - 1, 2, 3);
			crater(sp, cx - 5, cz + 4, 2, 2);
			// An undercut hollow, like real TNT leaves: under the top layer, open on one side to the crater.
			setBlock(sp, cx + 1, y0 + H - 1, cz - 1, "air");
			setBlock(sp, cx + 2, y0 + H - 1, cz - 1, "air");
			LOG.info("stage craters: {} holes", holes(sp) - before);
			run(ctx, sp, "craters", before, 6000);

			// 4b) A TNT barrage like on stream (a new blast every half second for 15 s): it must keep filling through it.
			long placedAtStart = BoxFillModule.placedCount;
			lastYaw = Float.NaN;
			lastPos = null;
			for (int t = 0; t < 300; t++) {
				if (t % 10 == 0) {
					int bx = cx - R + 2 + (t * 7) % (2 * R - 3), bz = cz - R + 2 + (t * 13) % (2 * R - 3);
					cmd(sp, "summon tnt " + bx + " " + (y0 + H + 2) + " " + bz + " {fuse:20}");
				}
				ctx.waitTick();
				watch(ctx);
			}
			long duringBarrage = BoxFillModule.placedCount - placedAtStart;
			LOG.info("stage barrage: placed {} blocks during 15 s of TNT", duringBarrage);
			if (duringBarrage < 20) throw new AssertionError("barrage: stopped filling while TNT kept coming (" + duringBarrage + " blocks)");
			before = 0;
			run(ctx, sp, "barrage-mend", 1, 6000);

			// 5) The top two layers wiped (the old plugin built itself in on a wipe).
			fill(sp, cx - R, y0 + H - 1, cz - R, cx + R, y0 + H, cz + R, "air");
			LOG.info("stage wipe: {} holes", holes(sp));
			run(ctx, sp, "wipe", before, 7200);

			// 6) A brand-new box, like the user's fresh /bedrock create: everything but the bottom two layers empty.
			// It can't finish in a test, but it must keep going without stalls (the user saw it go up and down a
			// pillar, stuck). Four minutes; fail on any 5-second stretch without a block.
			fill(sp, cx - R, y0 + 3, cz - R, cx + R, y0 + H, cz + R, "air");
			LOG.info("stage fresh: {} holes", holes(sp));
			long stallsBefore = BoxFillModule.stalls;
			long placedBefore = BoxFillModule.placedCount;
			lastYaw = Float.NaN;
			lastPos = null;
			for (int t = 0; t < 4800; t++) {
				ctx.waitTick();
				watch(ctx);
				if (t % 600 == 0) LOG.info("[fresh] t={} holes={} status={}", t, holes(sp), ctx.computeOnClient(mc -> HumanAutopilot.status()));
			}
			long freshStalls = BoxFillModule.stalls - stallsBefore;
			ctx.takeScreenshot("box-fresh");
			LOG.info("stage fresh: placed {} in 4 minutes, {} stall reports", BoxFillModule.placedCount - placedBefore, freshStalls);
			if (freshStalls > 2) throw new AssertionError("fresh box: stalled (" + freshStalls + " seconds of stall reports)");

			LOG.info("ALL BOX TESTS PASSED (placed {}, skipped {}, misplaced blocks knocked out {}, stopped flying {} times, ticks under 1 block above ground {}, slowest think {} ms, think ticks over 5 ms {}, biggest camera turn {} deg/tick, biggest step {} blocks/tick, longest closed-in {} ticks)",
					BoxFillModule.placedCount, BoxFillModule.skippedCount, BoxFillModule.lidsBroken, BoxFillModule.landings, lowTicks, BoxFillModule.slowestTickMs, BoxFillModule.slowTicks, String.format("%.0f", maxTurn),
					String.format("%.2f", maxStep), worstEnclosed);
			ctx.runOnClient(mc -> {
				HumanAutopilot.recorder().stop();
				HumanAutopilot.panic();
			});
		}
	}

	/** A TNT crater: a rough bowl from the top layer down, radius r, depth d. */
	private void crater(TestSingleplayerContext sp, int x0, int z0, int r, int d) {
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = 0; dy < d; dy++) {
			double rr = r * (1.0 - dy / (double) d) + 0.5;
			int x = x0 + dx, z = z0 + dz;
			if (dx * dx + dz * dz <= rr * rr && Math.abs(x - cx) <= R && Math.abs(z - cz) <= R) setBlock(sp, x, y0 + H - dy, z, "air");
		}
	}

	/** Let it work until only {@code leftOver} holes remain, checking how it moves every tick. */
	private void run(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, int leftOver, int maxTicks) {
		long placedBefore = BoxFillModule.placedCount;
		lastYaw = Float.NaN; // not watched in between stages
		lastPos = null;
		for (int t = 0; t < maxTicks; t++) {
			ctx.waitTick();
			watch(ctx);
			if (t % 100 == 0) stock(sp);
			if (t % 600 == 0) LOG.info("[{}] t={} holes={} status={}", name, t, holes(sp), ctx.computeOnClient(mc -> HumanAutopilot.status()));
			// Done: down to what was left before, or nothing left it can get at (holes sealed in under its own blocks are
			// left for good: breaking into roofs for them is off, the user's choice).
			if (t % 10 == 0 && (holes(sp) <= leftOver || (t > 200 && openHoles(sp) == 0))) {
				ctx.takeScreenshot("box-" + name);
				double perMin = (BoxFillModule.placedCount - placedBefore) / (t / 1200.0 + 1e-9);
				LOG.info("PASS {}: done in {} ticks ({} blocks, {} per minute, {} sealed in)", name, t,
						BoxFillModule.placedCount - placedBefore, String.format("%.0f", perMin), holes(sp) - openHoles(sp));
				return;
			}
		}
		ctx.takeScreenshot("box-" + name + "-fail");
		// Which ones, and how they're closed in (open sides: air next to them).
		sp.getServer().runOnServer(s -> {
			var level = s.overworld();
			for (int y = y0 + 1; y <= y0 + H; y++)
				for (int x = cx - R; x <= cx + R; x++)
					for (int z = cz - R; z <= cz + R; z++) {
						BlockPos c = new BlockPos(x, y, z);
						if (!level.getBlockState(c).isAir()) continue;
						StringBuilder open = new StringBuilder();
						for (var d : net.minecraft.core.Direction.values()) if (level.getBlockState(c.relative(d)).isAir()) open.append(d).append(' ');
						LOG.info("{} left: hole at {} (top layer {}), open to: {}", name, c.toShortString(), y0 + H, open.isEmpty() ? "nothing" : open);
					}
		});
		throw new AssertionError(name + ": " + holes(sp) + " holes left after " + maxTicks + " ticks; status="
				+ ctx.computeOnClient(mc -> HumanAutopilot.status()));
	}

	private void watch(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			var pl = mc.player;
			if (pl.isDeadOrDying()) throw new AssertionError("died at " + pl.blockPosition());
			if (!Double.isNaN(lastY)) {
				double dy = pl.getY() - lastY;
				if (Math.abs(dy) > 0.02) {
					int dir = dy > 0 ? 1 : -1;
					if (vLast != 0 && dir != vLast) vFlips++;
					vLast = dir;
				}
			}
			lastY = pl.getY();
			float yaw = pl.getYRot();
			if (!Float.isNaN(lastYaw)) maxTurn = Math.max(maxTurn, Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw)));
			lastYaw = yaw;
			Vec3 pos = pl.position();
			if (lastPos != null) maxStep = Math.max(maxStep, pos.distanceTo(lastPos));
			lastPos = pos;
			if (maxTurn > 70) throw new AssertionError("camera snapped " + String.format("%.0f", maxTurn) + " deg in one tick");
			if (maxStep > 1.6) throw new AssertionError("moved " + String.format("%.2f", maxStep) + " blocks in one tick (teleport?)");
			// Closed in: something solid over our head inside the box, i.e. we built a lid over ourselves.
			int x = net.minecraft.util.Mth.floor(pl.getX()), z = net.minecraft.util.Mth.floor(pl.getZ());
			// Boxed in: solid right over the head AND on all four sides at feet and head height (a roof alone is fine:
			// it may be working inside a hollow).
			boolean lid = false;
			if (x >= cx - R && x <= cx + R && z >= cz - R && z <= cz + R) {
				int fy = net.minecraft.util.Mth.floor(pl.getY() + 0.1);
				lid = !pl.level().getBlockState(new BlockPos(x, net.minecraft.util.Mth.floor(pl.getEyeY()) + 1, z)).isAir();
				for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
					for (int yy = fy; yy <= fy + 1 && lid; yy++) {
						if (pl.level().getBlockState(new BlockPos(x + d.getStepX(), yy, z + d.getStepZ())).isAir()) lid = false;
					}
				}
			}
			enclosedTicks = lid ? enclosedTicks + 1 : 0;
			// Height over the ground (the user wants it 2+ blocks up, never touching down).
			if (pl.getAbilities().flying) {
				int gx = net.minecraft.util.Mth.floor(pl.getX()), gz = net.minecraft.util.Mth.floor(pl.getZ());
				for (int dy = 0; dy <= 4; dy++) {
					BlockPos g = BlockPos.containing(pl.getX(), pl.getY() - 0.01 - dy, pl.getZ());
					if (!pl.level().getBlockState(g).isAir()) {
						if (pl.getY() - (g.getY() + 1) < 1.0) lowTicks++;
						break;
					}
				}
			}
			worstEnclosed = Math.max(worstEnclosed, enclosedTicks);
			if (enclosedTicks > 40) throw new AssertionError("closed in under its own blocks at " + pl.blockPosition());
		});
	}

	/** Holes it can still get at: air in the box joined (through air) to the open air round it. Sealed-in ones aren't. */
	private int openHoles(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> {
			var level = s.overworld();
			java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
			java.util.Set<BlockPos> seen = new java.util.HashSet<>();
			for (int y = y0 + 1; y <= y0 + H; y++)
				for (int x = cx - R; x <= cx + R; x++)
					for (int z = cz - R; z <= cz + R; z++) {
						BlockPos c = new BlockPos(x, y, z);
						if (!level.getBlockState(c).isAir()) continue;
						for (var d : net.minecraft.core.Direction.values()) {
							BlockPos n = c.relative(d);
							if (!inBox(n) && level.getBlockState(n).isAir()) {
								seen.add(c);
								q.add(c);
								break;
							}
						}
					}
			while (!q.isEmpty()) {
				BlockPos c = q.poll();
				for (var d : net.minecraft.core.Direction.values()) {
					BlockPos n = c.relative(d);
					if (inBox(n) && !seen.contains(n) && level.getBlockState(n).isAir()) {
						seen.add(n);
						q.add(n);
					}
				}
			}
			return seen.size();
		});
	}

	private boolean inBox(BlockPos c) {
		return c.getY() >= y0 + 1 && c.getY() <= y0 + H && Math.abs(c.getX() - cx) <= R && Math.abs(c.getZ() - cz) <= R;
	}

	private int holes(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> {
			var level = s.overworld();
			int n = 0;
			for (int y = y0 + 1; y <= y0 + H; y++)
				for (int x = cx - R; x <= cx + R; x++)
					for (int z = cz - R; z <= cz + R; z++)
						if (level.getBlockState(new BlockPos(x, y, z)).isAir()) n++;
			return n;
		});
	}

	private void fillLayers(TestSingleplayerContext sp) {
		String[] l = LAYERS.split(",");
		for (int i = 0; i < l.length; i++) fill(sp, cx - R, y0 + 1 + i, cz - R, cx + R, y0 + 1 + i, cz + R, l[i]);
	}

	/** What the plugin does on the server: keep the layer blocks in the hotbar. */
	private static void stock(TestSingleplayerContext sp) {
		cmd(sp, "item replace entity @a hotbar.0 with gold_block 64"); // held, like the user's recording
	}

	private static void setBlock(TestSingleplayerContext sp, int x, int y, int z, String b) {
		cmd(sp, String.format("setblock %d %d %d %s", x, y, z, b));
	}

	private static void fill(TestSingleplayerContext sp, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		cmd(sp, String.format("fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block));
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.warn("command failed: {} -> {}", c, t.toString());
		}
	}
}
