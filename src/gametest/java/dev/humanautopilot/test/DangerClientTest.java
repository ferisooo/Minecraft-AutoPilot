package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A night on normal difficulty with mobs spawning: survival mode keeps working (mining) and has to
 * deal with zombies, skeletons and creepers. Reports deaths, kills and how much it got done.
 */
public class DangerClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("danger-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String only = System.getenv("HA_ONLY");
		if (only != null && !only.isEmpty() && !only.equals("danger")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty normal");
			cmd(sp, "gamerule spawn_mobs true");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "time set midnight");
			cmd(sp, "clear @a");
			// Island like the survival test, but it's night and mobs are about.
			cmd(sp, "fill -20 -59 -20 20 -44 20 stone");
			cmd(sp, "fill -20 -43 -20 20 -42 20 dirt");
			cmd(sp, "fill -20 -41 -20 20 -41 20 grass_block");
			cmd(sp, "fill -20 -40 -20 20 -30 20 air");
			for (int x = -18; x <= 12; x += 6) {
				for (int z = -18; z <= 12; z += 6) {
					cmd(sp, "fill " + x + " -53 " + z + " " + (x + 1) + " -45 " + z + " iron_ore");
					cmd(sp, "fill " + (x + 3) + " -53 " + (z + 3) + " " + (x + 3) + " -45 " + (z + 3) + " coal_ore");
				}
			}
			cmd(sp, "tp @a 0 -40 0");
			cmd(sp, "give @a stone_pickaxe");
			cmd(sp, "give @a stone_sword");
			cmd(sp, "give @a furnace");
			cmd(sp, "give @a crafting_table");
			cmd(sp, "give @a oak_planks 32");
			cmd(sp, "give @a cooked_beef 12");
			cmd(sp, "summon zombie 8 -40 8");
			cmd(sp, "summon skeleton -8 -40 6");
			cmd(sp, "summon creeper 6 -40 -8");
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> HumanAutopilot.setSurvival(true));

			int[] t = {0};
			int[] deaths = {0};
			boolean[] wasDead = {false};
			ctx.waitFor(mc -> {
				t[0]++;
				boolean dead = mc.player.isDeadOrDying();
				if (dead && !wasDead[0]) deaths[0]++;
				wasDead[0] = dead;
				if (t[0] % 200 == 0) {
					LOG.info("[{}s] hp={} food={} pos={} deaths={} | {}", t[0] / 20, (int) mc.player.getHealth(),
							mc.player.getFoodData().getFoodLevel(), mc.player.blockPosition().toShortString(), deaths[0],
							HumanAutopilot.status());
				}
				return t[0] >= 20 * 240;
			}, 20 * 260);
			String inv = ctx.computeOnClient(mc -> {
				StringBuilder b = new StringBuilder();
				for (int i = 0; i < 36; i++) {
					var s = mc.player.getInventory().getItem(i);
					if (!s.isEmpty()) b.append(s.getCount()).append(' ').append(s.getItem()).append(", ");
				}
				return b.toString();
			});
			LOG.info("DANGER RESULT: deaths={} | inventory: {}", deaths[0], inv);
			ctx.takeScreenshot("danger-end");
			ctx.runOnClient(mc -> HumanAutopilot.panic());
			if (deaths[0] > 2) throw new AssertionError("died " + deaths[0] + " times in 4 minutes");
		}
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable e) {
			LOG.warn("command failed: {} -> {}", c, e.toString());
		}
	}
}
