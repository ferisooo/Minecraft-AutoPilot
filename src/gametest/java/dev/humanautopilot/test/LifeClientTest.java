package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Day-to-day life in survival mode: at night it should make a bed, sleep, and pack the bed up in the
 * morning; after dying it should respawn and run back for its dropped items.
 */
public class LifeClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("life-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String only = System.getenv("HA_ONLY");
		if (only != null && !only.isEmpty() && !only.equals("life")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "gamerule spawn_mobs false");
			cmd(sp, "fill -12 -61 -12 12 -61 12 grass_block");
			cmd(sp, "tp @a 0 -60 0");
			cmd(sp, "clear @a");
			// Already well equipped, so the planner wants a bed next.
			cmd(sp, "give @a iron_pickaxe");
			cmd(sp, "give @a iron_sword");
			cmd(sp, "give @a shield");
			cmd(sp, "give @a cooked_beef 16");
			cmd(sp, "give @a white_wool 3");
			cmd(sp, "give @a oak_planks 16");
			cmd(sp, "give @a crafting_table");
			cmd(sp, "give @a furnace");
			cmd(sp, "item replace entity @a armor.head with iron_helmet");
			cmd(sp, "item replace entity @a armor.chest with iron_chestplate");
			cmd(sp, "item replace entity @a armor.legs with iron_leggings");
			cmd(sp, "item replace entity @a armor.feet with iron_boots");
			cmd(sp, "time set 14000");
			ctx.waitTicks(20);

			ctx.runOnClient(mc -> HumanAutopilot.setSurvival(true));

			// 1. It should make a bed and go to sleep.
			int[] t = {0};
			int took = ctx.waitFor(mc -> {
				if (++t[0] % 100 == 0) LOG.info("[{}s] night | {}", t[0] / 20, HumanAutopilot.status());
				return mc.player.isSleeping();
			}, 20 * 120);
			LOG.info("PASS sleep: in bed after {}s", took / 20);
			// 2. Morning: it wakes up and packs the bed away.
			took = ctx.waitFor(mc -> !mc.level.isDarkOutside() && !mc.player.isSleeping()
					&& mc.player.getInventory().contains(s -> s.is(net.minecraft.tags.ItemTags.BEDS)), 20 * 120);
			LOG.info("PASS morning: bed packed up {}s after waking", took / 20);

			// 3. Die, respawn, and fetch the dropped items.
			ctx.runOnClient(mc -> HumanAutopilot.setSurvival(true));
			cmd(sp, "kill @a");
			took = ctx.waitFor(mc -> mc.player.isAlive() && mc.screen == null, 20 * 30);
			LOG.info("respawned after {}s", took / 20);
			t[0] = 0;
			took = ctx.waitFor(mc -> {
				if (++t[0] % 100 == 0) LOG.info("[{}s] recovery | {}", t[0] / 20, HumanAutopilot.status());
				return mc.player.getInventory().contains(s -> s.is(Items.IRON_PICKAXE));
			}, 20 * 120);
			LOG.info("PASS recovery: got the pickaxe back after {}s", took / 20);

			ctx.runOnClient(mc -> HumanAutopilot.panic());
			LOG.info("ALL LIFE TESTS PASSED");
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
