package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.ParkourModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Parkour mode on a practice tower like StreamToEarn's parkour mini-game: single blocks spiralling up a 9x9 column,
 * 1- and 2-block gaps, steps up, survival, nothing to place. Climb to the top; then knocked down to the floor (as a
 * gift would) and climb again; then knocked down from half way up. Run with HA_ONLY=parkour.
 */
public class ParkourClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("parkour-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"parkour".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			int x = p.getX(), z = p.getZ(), y = p.getY() + 2;
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule fall_damage false");
			cmd(sp, "gamerule fallDamage false");
			// A floor to land on, then the tower: the spiral round the column's edge (radius 3), one block a step.
			fill(sp, x - 12, y - 1, z - 12, x + 12, y - 1, z + 12, "smooth_stone");
			List<BlockPos> course = tower(x, y, z, Integer.parseInt(System.getenv().getOrDefault("HA_PARKOUR_STEPS", "30")));
			boolean realStyle = "real".equals(System.getenv("HA_PARKOUR_STYLE"));
			for (BlockPos b : course) cmd(sp, "setblock " + b.getX() + " " + b.getY() + " " + b.getZ() + (realStyle ? " red_stained_glass" : " gold_block"));
			BlockPos last = course.get(course.size() - 1);
			if (realStyle) {
				// Like the real one: an invisible barrier cage a block round the course, up past its top, and glass posts
				// at its corners a block higher than the last course block.
				int x0 = x - 9, x1 = x + 9, z0 = z - 8, z1 = z + 8, yTop = last.getY() + 30;
				fill(sp, x0, y, z0, x1, yTop, z0, "barrier");
				fill(sp, x0, y, z1, x1, yTop, z1, "barrier");
				fill(sp, x0, y, z0, x0, yTop, z1, "barrier");
				fill(sp, x1, y, z0, x1, yTop, z1, "barrier");
				for (int[] c : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) fill(sp, c[0], y, c[1], c[0], last.getY() + 1, c[1], "orange_stained_glass");
			}
			LOG.info("course: {} blocks, {} up, top {}", course.size(), last.getY() + 1 - y, last.toShortString());
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			cmd(sp, "clear @a");
			ctx.waitTicks(40);

			ctx.runOnClient(mc -> {
				HumanAutopilot.setParkour(true);
				// (The first climb recorded, to hold its camera against the user's own parkour recording.)
				dev.humanautopilot.record.Recorder.recordBot = true;
				HumanAutopilot.recorder().start();
			});
			long t0 = System.currentTimeMillis();
			climb(ctx, "first climb", last, 20 * 60 * 4);
			long t1 = System.currentTimeMillis();
			ctx.runOnClient(mc -> HumanAutopilot.recorder().stop());
			ctx.takeScreenshot("parkour-top");

			// HA_PARKOUR_QUICK=1: the first climb, then straight to the slip.
			boolean quick = System.getenv("HA_PARKOUR_QUICK") != null;
			long t2 = t1, t3 = t1, t4 = t1, t5 = t1, t6 = t1;
			if (!quick) {
			// Knocked off from the top, down to the floor (a gift: "chicken Down", TNT).
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20); // (the client down there first: the top check passed while it still stood up there)
			climb(ctx, "after a fall from the top", last, 20 * 60 * 4);
			t2 = System.currentTimeMillis();

			// Knocked down from half way up, mid-climb.
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20); // (down at the bottom first, or "half way up" was the top it still stood on)
			int half = y + (last.getY() - y) / 2;
			ctx.waitFor(mc -> mc.player.getY() >= half || ParkourModule.get().top() == null, 20 * 60 * 2);
			// (Onto the floor off to the side, never into a course block: the real style's first block stands there.)
			int[] off = freeFloor(course, x, y, z, -6, -6);
			cmd(sp, "tp @a " + (x + off[0] + 0.5) + " " + y + " " + (z + off[1] + 0.5));
			ctx.waitTicks(20);
			climb(ctx, "after a knock from half way", last, 20 * 60 * 4);
			t3 = System.currentTimeMillis();

			// Gifts: speed for the climb's start, slowness a little way up (it waits that out), then the top again.
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20);
			cmd(sp, "effect give @a speed 12 1");
			int third = y + (last.getY() - y) / 3;
			ctx.waitFor(mc -> mc.player.getY() >= third, 20 * 60 * 2);
			cmd(sp, "effect give @a slowness 6 2");
			climb(ctx, "with speed then slowness", last, 20 * 60 * 4);
			t4 = System.currentTimeMillis();

			// A blast under her feet a third of the way up (a wind charge: throws her up like the gifts' TNT, breaks nothing).
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20);
			ctx.waitFor(mc -> mc.player.getY() >= third && mc.player.onGround(), 20 * 60 * 2);
			cmd(sp, "execute at @a run summon wind_charge ~ ~0.2 ~ {Motion:[0.0,-1.5,0.0]}");
			ctx.waitTicks(10);
			double thrownY = ctx.computeOnClient(mc -> mc.player.getY());
			LOG.info("blast: at y {} ten ticks after", String.format("%.1f", thrownY));
			climb(ctx, "after a blast", last, 20 * 60 * 4);
			t5 = System.currentTimeMillis();

			// Carried up by a gift's ride (an allay lifting her 14 blocks, then letting go).
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20);
			ctx.waitFor(mc -> mc.player.getY() >= third && mc.player.onGround(), 20 * 60 * 2);
			cmd(sp, "execute at @a run summon allay ~ ~ ~ {NoAI:1b,Invulnerable:1b,Tags:[\"lift\"]}");
			ctx.waitTicks(2);
			cmd(sp, "ride @p mount @e[tag=lift,limit=1]");
			for (int k = 0; k < 14; k++) {
				ctx.waitTicks(4);
				cmd(sp, "execute as @e[tag=lift] at @s run tp @s ~ ~1 ~");
			}
			ctx.waitTicks(20);
			cmd(sp, "ride @p dismount");
			cmd(sp, "kill @e[tag=lift]");
			climb(ctx, "after a ride up", last, 20 * 60 * 4);
			t6 = System.currentTimeMillis();
			}

			// A slip for sure (the user: "not 100 perfect, maybe 80 percent"): one climb with the chance at 1, then back.
			// (Always the misjudged heading here - a walk off the edge is the other kind, seen at random.)
			ctx.runOnClient(mc -> {
				HumanAutopilot.config().parkourSlipChance = 1.0;
				ParkourModule.forceSlipKind = 1;
			});
			cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 4.5));
			ctx.waitTicks(20);
			int falls0 = ctx.computeOnClient(mc -> ParkourModule.get().falls());
			int off0 = ctx.computeOnClient(mc -> ParkourModule.get().slipsOff());
			climb(ctx, "with a slip", last, 20 * 60 * 5);
			int off = ctx.computeOnClient(mc -> ParkourModule.get().slipsOff()) - off0;
			if (off < 1) throw new AssertionError("the slip didn't put her off the block");
			ctx.runOnClient(mc -> {
				HumanAutopilot.config().parkourSlipChance = 0.2;
				ParkourModule.forceSlipKind = -1;
			});
			int slipFalls = ctx.computeOnClient(mc -> ParkourModule.get().falls()) - falls0;
			LOG.info("CHECK: with a slip: {} fall(s) on the way up", slipFalls);
			long t7 = System.currentTimeMillis();

			int falls = ctx.computeOnClient(mc -> ParkourModule.get().falls());
			ctx.runOnClient(mc -> HumanAutopilot.setParkour(false));
			int missed = ctx.computeOnClient(mc -> ParkourModule.get().misses());
			LOG.info("ALL PARKOUR TESTS PASSED: climbs {} s, {} s, {} s, {} s, {} s, {} s, {} s; {} falls counted (7 were ours), {} jumps missed",
					(t1 - t0) / 1000, (t2 - t1) / 1000, (t3 - t2) / 1000, (t4 - t3) / 1000, (t5 - t4) / 1000, (t6 - t5) / 1000, (t7 - t6) / 1000, falls, missed);
		}
	}

	/** Up the spiral: each step 2-3 blocks round from the last (a 1- or 2-block gap), every other one a block up. */
	static List<BlockPos> tower(int cx, int y0, int cz, int steps) {
		// HA_PARKOUR_STYLE=real: the course in the user's recording of s2e-parkour (rec-20261023-105754-you): a square
		// spiral, every block one up and two on (a one-block gap), 7 along the long sides, 5 along the short, a block
		// off to the side at each corner.
		if ("real".equals(System.getenv("HA_PARKOUR_STYLE"))) {
			int[][] lap = new int[28][];
			int k = 0;
			for (int i = 0; i < 7; i++) lap[k++] = new int[]{2, 0};
			lap[k++] = new int[]{1, 2};
			for (int i = 0; i < 5; i++) lap[k++] = new int[]{0, 2};
			lap[k++] = new int[]{-1, 2};
			for (int i = 0; i < 7; i++) lap[k++] = new int[]{-2, 0};
			lap[k++] = new int[]{-1, -2};
			for (int i = 0; i < 5; i++) lap[k++] = new int[]{0, -2};
			lap[k++] = new int[]{1, -2};
			List<BlockPos> real = new ArrayList<>();
			int x = cx - 7, z = cz - 7, y = y0;
			real.add(new BlockPos(x, y, z));
			for (int s = 1; s < steps; s++) {
				int[] m = lap[(s - 1) % lap.length];
				x += m[0];
				z += m[1];
				y++;
				real.add(new BlockPos(x, y, z));
			}
			return real;
		}
		List<BlockPos> ring = new ArrayList<>();
		int r = 3;
		for (int i = -r; i < r; i++) ring.add(new BlockPos(cx + i, 0, cz - r));
		for (int i = -r; i < r; i++) ring.add(new BlockPos(cx + r, 0, cz + i));
		for (int i = r; i > -r; i--) ring.add(new BlockPos(cx + i, 0, cz + r));
		for (int i = r; i > -r; i--) ring.add(new BlockPos(cx - r, 0, cz + i));
		Random rnd = new Random(42);
		List<BlockPos> out = new ArrayList<>();
		int at = 0, y = y0 - 1; // (the first step up is a block at foot level: a one-block jump up from the floor)
		for (int s = 0; s < steps; s++) {
			// HA_PARKOUR_STYLE=steps: like the real course in the user's recording - every block one up, a one-block gap.
			boolean stepStyle = "steps".equals(System.getenv("HA_PARKOUR_STYLE"));
			boolean up = stepStyle || s % 2 == 0 || rnd.nextInt(3) == 0;
			at = (at + (stepStyle ? 2 : up ? 2 : 3)) % ring.size();
			if (up) y++;
			BlockPos b = ring.get(at);
			out.add(new BlockPos(b.getX(), y, b.getZ()));
		}
		return out;
	}

	private static void climb(ClientGameTestContext ctx, String name, BlockPos top, int timeout) {
		try {
			ctx.waitFor(mc -> {
				var pl = mc.player;
				if (!HumanAutopilot.isParkourOn()) throw new AssertionError(name + ": parkour switched itself off: " + HumanAutopilot.status());
				// (On the top level: the tower's last steps can be level, two blocks at the top height.)
				return pl.onGround() && pl.getY() >= top.getY() + 0.9 && Math.abs(pl.getX() - top.getX() - 0.5) < 8
						&& Math.abs(pl.getZ() - top.getZ() - 0.5) < 8;
			}, timeout);
		} catch (AssertionError e) {
			throw e;
		} catch (RuntimeException e) {
			ctx.takeScreenshot("parkour-fail");
			throw new AssertionError(name + ": didn't reach the top: " + ctx.computeOnClient(mc -> mc.player.blockPosition().toShortString()
					+ " status=" + HumanAutopilot.status()));
		}
		ctx.waitTicks(60);
		boolean still = ctx.computeOnClient(mc -> mc.player.getY() >= top.getY() + 0.9);
		if (!still) throw new AssertionError(name + ": got to the top but didn't stay there");
		LOG.info("CHECK: {}: at the top", name);
	}

	/** The floor spot nearest (dx, dz) off the middle with no course block in the two cells over the floor. */
	private static int[] freeFloor(List<BlockPos> course, int x, int y, int z, int dx, int dz) {
		for (int r = 0; r < 6; r++)
			for (int ax = -r; ax <= r; ax++)
				for (int az = -r; az <= r; az++) {
					if (Math.max(Math.abs(ax), Math.abs(az)) != r) continue;
					int cx = x + dx + ax, cz = z + dz + az;
					boolean taken = false;
					for (BlockPos b : course) if (b.getX() == cx && b.getZ() == cz && b.getY() >= y && b.getY() <= y + 1) taken = true;
					if (!taken) return new int[]{dx + ax, dz + az};
				}
		return new int[]{dx, dz};
	}

	private static void fill(TestSingleplayerContext sp, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		cmd(sp, "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block);
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.warn("command failed: {} -> {}", c, t.toString());
		}
	}
}
