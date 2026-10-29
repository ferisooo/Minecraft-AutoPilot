package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.LadderModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ladder mode on a practice arena like StreamToEarn's ladder mini-game (as read out of the plugin: bands of coloured
 * concrete, a ladder, barriers): a 3x3 concrete tower HA_LADDER_HEIGHT high (default 60) with a ladder up its east face.
 * Climb to the top; pulled down to the floor (as a gift would) and up again; pulled 20 down mid-climb; a zombie at the
 * foot. Run with HA_ONLY=ladder.
 */
public class LadderClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("ladder-test");
	private static final String[] BANDS = {"red", "orange", "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink", "white"};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"ladder".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			var at = ctx.computeOnClient(mc -> mc.player.blockPosition());
			int x = at.getX(), z = at.getZ(), y = at.getY();
			int h = Integer.parseInt(System.getenv().getOrDefault("HA_LADDER_HEIGHT", "60"));
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule fall_damage false");
			cmd(sp, "gamerule fallDamage false");
			fill(sp, x - 8, y - 1, z - 8, x + 8, y - 1, z + 8, "smooth_stone");
			// The tower: x-1..x+1, z-1..z+1, bands of 5 of each colour; the ladder in the cells east of it (x+2), facing east.
			for (int band = 0; band * 5 < h; band++) {
				int y0 = y + band * 5, y1 = Math.min(y + h - 1, y0 + 4);
				fill(sp, x - 1, y0, z - 1, x + 1, y1, z + 1, BANDS[band % BANDS.length] + "_concrete");
			}
			fill(sp, x + 2, y, z, x + 2, y + h - 1, z, "ladder[facing=east]");
			int top = y + h - 1;
			LOG.info("arena: tower {}x3x3 at {} {} {}, ladder up its east face, top block y {}", h, x, y, z, top);
			cmd(sp, "tp @a " + (x + 5.5) + " " + y + " " + (z + 3.5) + " 90 0");
			ctx.waitTicks(40);

			ctx.runOnClient(mc -> HumanAutopilot.setLadder(true));
			long t0 = System.currentTimeMillis();
			climb(ctx, "first climb", top, 20 * 60 * 3);
			long t1 = System.currentTimeMillis();

			// Pulled down to the floor (a gift: "/ladder down") and up again.
			cmd(sp, "tp @a " + (x + 4.5) + " " + y + " " + (z - 2.5));
			ctx.waitTicks(20);
			climb(ctx, "after being pulled down", top, 20 * 60 * 3);
			long t2 = System.currentTimeMillis();

			// Pulled 20 down mid-climb, into the ladder.
			cmd(sp, "tp @a " + (x + 4.5) + " " + y + " " + (z + 0.5));
			ctx.waitTicks(20);
			int half = y + h / 2;
			ctx.waitFor(mc -> mc.player.getY() >= half, 20 * 60 * 2);
			cmd(sp, "tp @a " + (x + 2.5) + " " + (half - 20) + " " + (z + 0.5) + " 90 0");
			ctx.waitTicks(10);
			climb(ctx, "after a pull down mid-climb", top, 20 * 60 * 3);
			long t3 = System.currentTimeMillis();

			// A zombie at the ladder's foot (a gift: "/ladder zombie").
			cmd(sp, "difficulty easy");
			cmd(sp, "tp @a " + (x + 5.5) + " " + y + " " + (z + 0.5));
			cmd(sp, "summon zombie " + (x + 4.5) + " " + y + " " + (z + 2.5) + " {Health:6f,Attributes:[{id:\"minecraft:max_health\",base:6}]}");
			ctx.waitTicks(20);
			climb(ctx, "with a zombie", top, 20 * 60 * 3);
			long t4 = System.currentTimeMillis();
			int falls = ctx.computeOnClient(mc -> LadderModule.get().falls());
			ctx.runOnClient(mc -> HumanAutopilot.setLadder(false));
			LOG.info("ALL LADDER TESTS PASSED: climbs {} s, {} s, {} s, {} s; {} falls counted", (t1 - t0) / 1000, (t2 - t1) / 1000,
					(t3 - t2) / 1000, (t4 - t3) / 1000, falls);
		}
	}

	private static void climb(ClientGameTestContext ctx, String name, int top, int timeout) {
		try {
			ctx.waitFor(mc -> {
				if (!HumanAutopilot.isLadderOn()) throw new AssertionError(name + ": the ladder game switched itself off: " + HumanAutopilot.status());
				return mc.player.onGround() && mc.player.getY() >= top + 0.9;
			}, timeout);
		} catch (AssertionError e) {
			throw e;
		} catch (RuntimeException e) {
			ctx.takeScreenshot("ladder-fail");
			throw new AssertionError(name + ": didn't reach the top: " + ctx.computeOnClient(mc -> mc.player.position()
					+ " status=" + HumanAutopilot.status()));
		}
		ctx.waitTicks(60);
		boolean still = ctx.computeOnClient(mc -> mc.player.getY() >= top + 0.9);
		if (!still) throw new AssertionError(name + ": got to the top but didn't stay there");
		LOG.info("CHECK: {}: at the top", name);
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
