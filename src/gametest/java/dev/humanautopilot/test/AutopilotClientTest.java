package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.MiningModule;
import dev.humanautopilot.pathing.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * Plays the mod in a real singleplayer world: builds a small obstacle course with server
 * commands, then checks that each feature actually gets the job done with key presses alone.
 */
public class AutopilotClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("autopilot-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String only = System.getenv("HA_ONLY");
		if (only != null && !only.isEmpty() && !only.equals("basic")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			int x = p.getX(), y = p.getY(), z = p.getZ();
			LOG.info("Player starts at {}", p);

			cmd(sp, "gamemode survival @a");
			cmd(sp, "difficulty easy");
			cmd(sp, "time set noon");
			cmd(sp, "gamerule spawn_mobs false");
			cmd(sp, "gamerule doMobSpawning false");
			cmd(sp, "gamerule advance_time false");
			cmd(sp, "gamerule doDaylightCycle false");

			// Arena: stone floor, clear air above.
			fill(sp, x - 4, y - 1, z - 12, x + 40, y - 1, z + 12, "stone");
			fill(sp, x - 4, y, z - 12, x + 40, y + 6, z + 12, "air");
			// One-block step up onto a raised platform.
			fill(sp, x + 6, y, z - 12, x + 40, y, z + 12, "stone");
			// Two-high wall with a single gap: forces a detour.
			fill(sp, x + 14, y + 1, z - 12, x + 14, y + 2, z + 12, "stone");
			fill(sp, x + 14, y + 1, z + 8, x + 14, y + 2, z + 8, "air");
			// Lava pool flush with the floor, right on the straight line.
			fill(sp, x + 20, y, z - 3, x + 23, y, z + 3, "lava");
			ctx.waitTicks(20);

			// ---------- 1. Pathfinding ----------
			ctx.runOnClient(mc -> HumanAutopilot.gotoGoal(new Goal.XZ(x + 30, z)));
			int took = ctx.waitFor(mc -> {
				var pl = mc.player;
				if (pl.isOnFire() || pl.isInLava()) throw new AssertionError("Walked into lava at " + pl.blockPosition());
				return !HumanAutopilot.isGotoActive();
			}, 2400);
			assertTrue(ctx.computeOnClient(mc -> mc.player.blockPosition().getX() == x + 30 && mc.player.blockPosition().getZ() == z),
					"goto did not arrive: " + ctx.computeOnClient(mc -> mc.player.blockPosition() + " status=" + HumanAutopilot.status()));
			assertTrue(ctx.computeOnClient(mc -> mc.player.getHealth() >= mc.player.getMaxHealth()), "took damage on the way");
			LOG.info("PASS goto: arrived in {} ticks", took);

			// ---------- 2. Mining ----------
			cmd(sp, "item replace entity @a hotbar.0 with diamond_pickaxe");
			BlockPos ore = new BlockPos(x + 34, y + 1, z + 4);
			fill(sp, ore.getX(), ore.getY(), ore.getZ(), ore.getX(), ore.getY(), ore.getZ(), "diamond_ore");
			fill(sp, x + 26, y + 1, z - 4, x + 26, y + 1, z - 4, "coal_ore"); // not selected: must be ignored
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> HumanAutopilot.mine(EnumSet.of(MiningModule.Ore.DIAMOND)));
			took = ctx.waitFor(mc -> mc.player.getInventory().contains(s -> s.is(Items.DIAMOND)), 1600);
			assertTrue(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 26, y + 1, z - 4)).is(net.minecraft.world.level.block.Blocks.COAL_ORE)),
					"mined coal although only diamond was selected");
			ctx.runOnClient(mc -> HumanAutopilot.panic());
			LOG.info("PASS mining: diamond collected in {} ticks", took);

			// ---------- 3. Auto-eat ----------
			cmd(sp, "item replace entity @a hotbar.4 with bread 16");
			cmd(sp, "effect give @a hunger 60 120 true");
			ctx.waitFor(mc -> mc.player.getFoodData().getFoodLevel() <= 10, 1200);
			cmd(sp, "effect clear @a hunger");
			ctx.runOnClient(mc -> HumanAutopilot.setAutoEat(true));
			took = ctx.waitFor(mc -> mc.player.getFoodData().getFoodLevel() > 14, 900);
			int breadLeft = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(4).getCount());
			assertTrue(breadLeft < 16, "food went up but no bread was eaten?");
			ctx.waitTicks(30); // let it switch back to the previous slot
			LOG.info("PASS eat: food restored in {} ticks, ate {} bread", took, 16 - breadLeft);

			// ---------- 4. Combat ----------
			cmd(sp, "item replace entity @a hotbar.1 with iron_sword");
			cmd(sp, "summon zombie " + (x + 36) + " " + (y + 1) + " " + (z - 6)
					+ " {PersistenceRequired:1b,equipment:{head:{id:\"minecraft:leather_helmet\",count:1}}}");
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> HumanAutopilot.setCombat(true));
			took = ctx.waitFor(mc -> mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(40)).isEmpty(), 1200);
			int selected = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			assertTrue(selected == 1, "did not switch to the sword (slot " + selected + ")");
			LOG.info("PASS combat: zombie killed in {} ticks", took);

			ctx.runOnClient(mc -> HumanAutopilot.panic());

			// ---------- 5. Exploring (Path on, no /goto) ----------
			var start = ctx.computeOnClient(mc -> mc.player.position());
			ctx.runOnClient(mc -> HumanAutopilot.setPathing(true));
			took = ctx.waitFor(mc -> mc.player.position().distanceTo(start) > 10, 900);
			assertTrue(ctx.computeOnClient(mc -> !mc.player.isOnFire() && !mc.player.isInLava()), "explored into lava");
			LOG.info("PASS explore: moved 10+ blocks in {} ticks, status={}", took, ctx.computeOnClient(mc -> HumanAutopilot.status()));

			ctx.runOnClient(mc -> HumanAutopilot.panic());
			LOG.info("ALL AUTOPILOT TESTS PASSED");
		}
	}

	private static void fill(TestSingleplayerContext sp, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		cmd(sp, String.format("fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block));
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		try {
			sp.getServer().runCommand(c);
		} catch (Throwable t) {
			LOG.warn("command failed (ok if it's an old/new gamerule name): {} -> {}", c, t.toString());
		}
	}

	private static void assertTrue(boolean b, String msg) {
		if (!b) throw new AssertionError(msg);
	}
}
