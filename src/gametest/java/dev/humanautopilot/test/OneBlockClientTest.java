package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.OneBlockModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * One Block mode against a stand-in for StreamToEarn's one block (s2e-oneblock only runs on a server Stream to Earn
 * starts): a single block 40 up in the air that, broken, turns into a random block from a list weighted like an early
 * one block game (logs, grass/dirt, stone, cobblestone, wool), now and then a chest with a few things in it on top.
 * Night, peaceful (so a bed can be slept in). Passes when she's asleep in a bed she made. Run with HA_ONLY=oneblock.
 */
public class OneBlockClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("oneblock-test");
	private static volatile BlockPos gen;
	private static volatile int regrown;
	private static final Random RNG = new Random(7);

	private static Block next() {
		int r = RNG.nextInt(100);
		if (r < 28) return Blocks.OAK_LOG;
		if (r < 40) return Blocks.GRASS_BLOCK;
		if (r < 52) return Blocks.DIRT;
		if (r < 62) return Blocks.COBBLESTONE;
		if (r < 70) return Blocks.STONE;
		if (r < 84) return Blocks.WHITE_WOOL;
		if (r < 88) return Blocks.RED_WOOL;
		if (r < 92) return Blocks.SAND;
		if (r < 96) return Blocks.BIRCH_LOG;
		return Blocks.COAL_ORE;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"oneblock".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			var at = ctx.computeOnClient(mc -> mc.player.blockPosition());
			BlockPos g = at.above(40);
			gen = g;
			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty peaceful");
			cmd(sp, "time set 18000");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doDaylightCycle false");
			cmd(sp, "gamerule fall_damage false");
			cmd(sp, "gamerule fallDamage false");
			cmd(sp, "setblock " + g.getX() + " " + g.getY() + " " + g.getZ() + " grass_block");
			// The stand-in plugin: the block grows back as something else the moment it's gone; a chest on top now and then.
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				BlockPos b = gen;
				if (b == null) return;
				var level = server.overworld();
				if (level.getBlockState(b).isAir()) {
					level.setBlockAndUpdate(b, next().defaultBlockState());
					regrown++;
					if (RNG.nextInt(25) == 0 && level.getBlockState(b.above()).isAir()) {
						level.setBlockAndUpdate(b.above(), Blocks.CHEST.defaultBlockState());
						if (level.getBlockEntity(b.above()) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity ch) {
							ch.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 3));
							ch.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
						}
					}
				}
			});
			cmd(sp, "tp @a " + (g.getX() + 0.5) + " " + (g.getY() + 1) + " " + (g.getZ() + 0.5));
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> HumanAutopilot.setOneBlock(true));
			long t0 = System.currentTimeMillis();
			int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_MINUTES", "10"));
			long lastLog = 0;
			while (true) {
				ctx.waitTicks(20);
				boolean asleep = ctx.computeOnClient(mc -> mc.player.isSleeping());
				double y = ctx.computeOnClient(mc -> mc.player.getY());
				long el = (System.currentTimeMillis() - t0) / 1000;
				if (asleep) {
					LOG.info("ONE BLOCK PASSED: asleep in a bed after {} s, {} blocks grown back", el, regrown);
					break;
				}
				if (y < g.getY() - 3) {
					ctx.takeScreenshot("oneblock-fell");
					throw new AssertionError("fell off the platform at " + el + " s (" + HumanAutopilot.status() + ")");
				}
				if (el - lastLog >= 30) {
					lastLog = el;
					int platform = ctx.computeOnClient(mc -> {
						int n = 0;
						for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
							if ((dx != 0 || dz != 0) && !mc.level.getBlockState(g.offset(dx, 0, dz)).isAir()) n++;
						return n;
					});
					String bag = ctx.computeOnClient(mc -> {
						var m = new java.util.TreeMap<String, Integer>();
						var inv = mc.player.getInventory();
						for (int i = 0; i < 36; i++) {
							var st = inv.getItem(i);
							if (!st.isEmpty()) m.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
						}
						return m.toString();
					});
					int items = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
							new net.minecraft.world.phys.AABB(g).inflate(6, 60, 6)).size());
					LOG.info("{} s: {} grown back, platform {} blocks, {} items lying about, bag {}, status: {}", el, regrown, platform, items, bag, HumanAutopilot.status());
				}
				if (el > minutes * 60L) {
					ctx.takeScreenshot("oneblock-timeout");
					throw new AssertionError("no bed and sleep in " + minutes + " min: " + HumanAutopilot.status());
				}
			}
			ctx.takeScreenshot("oneblock-asleep");
			ctx.runOnClient(mc -> HumanAutopilot.setOneBlock(false));
			gen = null;
		}
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.warn("command failed: {} -> {}", c, t.toString());
		}
	}
}
