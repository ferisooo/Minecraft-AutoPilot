package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.pathing.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baritone as the heavy-duty legs, high in the air so a mistake is a real fall: bridge a 6-block void, pillar up
 * onto a floating platform, jump 2-block gaps with no blocks to place, and finally let our own walker hand a
 * bridging job to Baritone by itself. Throughout, the camera must turn like a hand (no snapping).
 * Run with HA_ONLY=baritone.
 */
public class BaritoneClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("baritone-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"baritone".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			int x = p.getX(), z = p.getZ(), y = p.getY() + 30;
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doDaylightCycle false");
			// Floating course (standing level = y).
			fill(sp, x - 2, y - 1, z - 2, x + 2, y - 1, z + 2, "stone");             // start
			fill(sp, x + 9, y - 1, z - 2, x + 13, y - 1, z + 2, "stone");            // across a 6-wide void
			fill(sp, x + 15, y + 4, z - 1, x + 17, y + 4, z + 1, "stone");           // floating, 5 up
			for (int sx : new int[]{20, 23, 26}) fill(sp, x + sx, y + 4, z, x + sx, y + 4, z, "stone"); // stepping stones
			fill(sp, x + 29, y + 4, z - 1, x + 31, y + 4, z + 1, "stone");           // landing
			fill(sp, x + 36, y + 4, z - 1, x + 38, y + 4, z + 1, "stone");           // across a 4-wide void (fallback test)
			cmd(sp, "tp @a " + x + " " + y + " " + z);
			cmd(sp, "clear @a");
			cmd(sp, "item replace entity @a hotbar.0 with stone_pickaxe");
			cmd(sp, "item replace entity @a hotbar.1 with cobblestone 32");
			ctx.waitTicks(40);

			double[] maxTurn = {0};
			float[] last = {Float.NaN, 0};

			stage(ctx, "bridge", new Goal.Block(x + 11, y, z), maxTurn, last);
			stage(ctx, "pillar", new Goal.Block(x + 16, y + 5, z), maxTurn, last);
			cmd(sp, "clear @a cobblestone");
			ctx.waitTicks(10);
			stage(ctx, "parkour", new Goal.Block(x + 30, y + 5, z), maxTurn, last);

			// Our own walker first this time: it can't cross a void, so it must hand the trip to Baritone itself.
			cmd(sp, "item replace entity @a hotbar.1 with cobblestone 32");
			ctx.runOnClient(mc -> HumanAutopilot.config().baritoneForGoto = false);
			ctx.waitTicks(10);
			stage(ctx, "fallback", new Goal.Block(x + 37, y + 5, z), maxTurn, last);
			ctx.runOnClient(mc -> HumanAutopilot.config().baritoneForGoto = true);

			if (maxTurn[0] > 70) throw new AssertionError("camera snapped: " + String.format("%.0f", maxTurn[0]) + " deg in one tick");
			LOG.info("ALL BARITONE TESTS PASSED (largest camera turn in one tick: {} deg)", String.format("%.0f", maxTurn[0]));
			ctx.runOnClient(mc -> HumanAutopilot.panic());
		}
	}

	private static void stage(ClientGameTestContext ctx, String name, Goal goal, double[] maxTurn, float[] last) {
		ctx.runOnClient(mc -> HumanAutopilot.gotoGoal(goal));
		int took = ctx.waitFor(mc -> {
			var pl = mc.player;
			if (pl.isDeadOrDying() || pl.getY() < goal_y(goal) - 8) throw new AssertionError(name + ": fell at " + pl.blockPosition());
			float yaw = pl.getYRot();
			if (!Float.isNaN(last[0])) maxTurn[0] = Math.max(maxTurn[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - last[0])));
			last[0] = yaw;
			return !HumanAutopilot.isGotoActive();
		}, 2400);
		boolean there = ctx.computeOnClient(mc -> goal.reachedBy(mc.player.position()));
		ctx.takeScreenshot("baritone-" + name);
		if (!there) throw new AssertionError(name + ": didn't get there: " + ctx.computeOnClient(mc -> mc.player.blockPosition() + " status=" + HumanAutopilot.status()));
		LOG.info("PASS {}: arrived in {} ticks", name, took);
	}

	private static int goal_y(Goal g) {
		return g instanceof Goal.Block b ? b.gy() : 0;
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
