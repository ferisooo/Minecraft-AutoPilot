package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.survival.Inv;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Survival from an empty inventory: an island of grass, dirt and stone with a few real oak trees.
 * The bot must chop wood, craft a table and wooden pickaxe, dig to stone, make stone tools and a
 * furnace, then hunt and cook food.
 */
public class SurvivalClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("survival-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String only = System.getenv("HA_ONLY");
		if (only != null && !only.isEmpty() && !only.equals("survival")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty easy");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule spawn_mobs false");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "clear @a");

			// Island: stone y-59..-44, dirt -43..-42, grass -41. Player stands at y -40.
			cmd(sp, "fill -20 -59 -20 20 -44 20 stone");
			cmd(sp, "fill -20 -43 -20 20 -42 20 dirt");
			cmd(sp, "fill -20 -41 -20 20 -41 20 grass_block");
			cmd(sp, "fill -20 -40 -20 20 -30 20 air");
			cmd(sp, "tp @a 0 -40 0");
			ctx.waitTicks(20);
			cmd(sp, "place feature minecraft:oak 8 -40 5");
			cmd(sp, "place feature minecraft:oak -9 -40 10");
			cmd(sp, "place feature minecraft:oak 12 -40 -9");
			cmd(sp, "place feature minecraft:birch 3 -40 14");
			int[][] more = {{-15, -14}, {-4, -16}, {16, 15}, {-16, 3}, {15, 2}, {-6, 17}, {0, -10}, {-14, -3}};
			for (int[] t : more) cmd(sp, "place feature minecraft:oak " + t[0] + " -40 " + t[1]);
			cmd(sp, "summon sheep -10 -40 -12");
			cmd(sp, "summon sheep -11 -40 -13");
			cmd(sp, "summon sheep -12 -40 -11");
			// Ore veins inside the stone: iron + coal around y -46, diamonds around y -54.
			for (int x = -18; x <= 12; x += 6) {
				for (int z = -18; z <= 12; z += 6) {
					cmd(sp, "fill " + x + " -53 " + z + " " + (x + 1) + " -45 " + z + " iron_ore");
					cmd(sp, "fill " + (x + 3) + " -53 " + (z + 3) + " " + (x + 3) + " -45 " + (z + 3) + " coal_ore");
					cmd(sp, "fill " + (x + 2) + " -58 " + (z + 2) + " " + (x + 2) + " -51 " + (z + 2) + " diamond_ore");
				}
			}
			cmd(sp, "summon cow 10 -40 12");
			cmd(sp, "summon cow 11 -40 13");
			cmd(sp, "summon pig -12 -40 -8");
			ctx.waitTicks(40);

			ctx.runOnClient(mc -> HumanAutopilot.setSurvival(true));

			milestone(ctx, "wooden pickaxe", s -> s.is(Items.WOODEN_PICKAXE), 20 * 240);
			milestone(ctx, "stone pickaxe", s -> s.is(Items.STONE_PICKAXE), 20 * 300);
			milestone(ctx, "stone sword", s -> s.is(Items.STONE_SWORD), 20 * 180);
			milestone(ctx, "furnace", s -> s.is(Items.FURNACE), 20 * 240, true);
			milestone(ctx, "cooked food", s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP), 20 * 300);
			milestone(ctx, "iron pickaxe", s -> s.is(Items.IRON_PICKAXE), 20 * 600);
			milestone(ctx, "diamond", s -> s.is(Items.DIAMOND), 20 * 900);
			milestone(ctx, "diamond pickaxe", s -> s.is(Items.DIAMOND_PICKAXE), 20 * 900);

			ctx.takeScreenshot("survival-end");
			ctx.runOnClient(mc -> HumanAutopilot.panic());
			LOG.info("ALL SURVIVAL MILESTONES PASSED");
		}
	}

	private void milestone(ClientGameTestContext ctx, String name, Predicate<net.minecraft.world.item.ItemStack> item, int timeout) {
		milestone(ctx, name, item, timeout, false);
	}

	/** Wait until the item is in the inventory (or, for placeable stations, placed nearby). Logs progress. */
	private void milestone(ClientGameTestContext ctx, String name, Predicate<net.minecraft.world.item.ItemStack> item,
						   int timeout, boolean orPlacedFurnace) {
		long[] lastLog = {0};
		int[] ticks = {0};
		try {
			int took = ctx.waitFor(mc -> {
				ticks[0]++;
				if (ticks[0] - lastLog[0] >= 100) {
					lastLog[0] = ticks[0];
					LOG.info("[{}s] {} | pos={} hp={} food={} | {}", ticks[0] / 20, name, mc.player.blockPosition().toShortString(),
							(int) mc.player.getHealth(), mc.player.getFoodData().getFoodLevel(), HumanAutopilot.status());
				}
				if (Inv.has(mc.player, item)) return true;
				return orPlacedFurnace && furnaceNearby(mc);
			}, timeout);
			LOG.info("MILESTONE {} reached after {}s", name, took / 20);
		} catch (Throwable t) {
			ctx.takeScreenshot("survival-stuck-" + name.replace(' ', '-'));
			String inv = ctx.computeOnClient(SurvivalClientTest::dumpInventory);
			throw new AssertionError("Stuck before '" + name + "'. Status: " + ctx.computeOnClient(mc -> HumanAutopilot.status())
					+ " | inventory: " + inv, t);
		}
	}

	private static boolean furnaceNearby(Minecraft mc) {
		var c = mc.player.blockPosition();
		for (var pos : net.minecraft.core.BlockPos.betweenClosed(c.offset(-8, -4, -8), c.offset(8, 4, 8))) {
			if (mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FURNACE)) return true;
		}
		return false;
	}

	private static String dumpInventory(Minecraft mc) {
		StringBuilder b = new StringBuilder();
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			var s = inv.getItem(i);
			if (!s.isEmpty()) b.append(i).append('=').append(s.getCount()).append(' ').append(s.getItem()).append(", ");
		}
		return b.toString();
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.warn("command failed: {} -> {}", c, t.toString());
		}
	}
}
