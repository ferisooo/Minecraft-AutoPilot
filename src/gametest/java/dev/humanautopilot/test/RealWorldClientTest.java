package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.survival.Inv;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A normal generated world (fixed seed), normal difficulty, day/night and mobs on. Survival mode
 * plays for a while and we record what it achieves and when. Nothing is staged.
 * Env: HA_SEED (default 20261006), HA_MINUTES (default 15).
 */
public class RealWorldClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("realworld-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String only = System.getenv("HA_ONLY");
		if (only == null || !only.equals("real")) return; // long run: only on request
		String seed = System.getenv().getOrDefault("HA_SEED", "20261006");
		int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_MINUTES", "15"));

		try (TestSingleplayerContext sp = ctx.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(s -> {
					s.setSeed(seed);
					s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
					s.setDifficulty(Difficulty.NORMAL);
					s.setAllowCommands(true);
					s.setGenerateStructures(true);
				})
				.create()) {
			sp.getClientLevel().waitForChunksRender();
			LOG.info("Real world, seed {}, start {}", seed, ctx.computeOnClient(mc -> mc.player.blockPosition().toShortString()));
			// HA_RECORD_BOT=1: record the bot's own play with the player recorder, to compare with the player's recordings.
			if ("1".equals(System.getenv("HA_RECORD_BOT"))) {
				ctx.runOnClient(mc -> {
					dev.humanautopilot.record.Recorder.recordBot = true;
					HumanAutopilot.recorder().start();
				});
			}
			ctx.runOnClient(mc -> HumanAutopilot.setSurvival(true));

			Map<String, Predicate<ItemStack>> goals = new LinkedHashMap<>();
			goals.put("log", Inv.LOG);
			goals.put("wooden pickaxe", s -> s.is(Items.WOODEN_PICKAXE));
			goals.put("cobblestone", Inv.COBBLE);
			goals.put("stone pickaxe", s -> s.is(Items.STONE_PICKAXE));
			goals.put("stone sword", s -> s.is(Items.STONE_SWORD));
			goals.put("furnace", Inv.FURNACE);
			goals.put("cooked food", s -> s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN));
			goals.put("coal", Inv.COAL);
			goals.put("raw iron", Inv.RAW_IRON);
			goals.put("iron ingot", Inv.IRON);
			goals.put("iron pickaxe", s -> s.is(Items.IRON_PICKAXE));
			goals.put("bed", Inv.BED);
			goals.put("diamond", Inv.DIAMOND);
			Map<String, Integer> reached = new LinkedHashMap<>();

			int[] t = {0};
			int[] deaths = {0};
			boolean[] wasDead = {false};
			int total = minutes * 60 * 20;
			long startMs = System.currentTimeMillis();
			// Motion meter: how the camera moves tick to tick (jitter = small back-and-forth reversals).
			double[] mm = new double[8]; // lastYaw, lastPitch, lastDYaw, reversals, micro, maxTurn, sumTurn, samples
			mm[0] = Double.NaN;
			long[] keysAt = {dev.humanautopilot.InputDriver.keyToggles};
			int[] gait = new int[4]; // ticks moving: walking, sprinting, sprint-jumping (airborne while sprinting), torch-in-offhand
			double[] lastPos = {Double.NaN, 0};
			for (int chunk = 1; t[0] < total; chunk++) {
			int chunkEnd = Math.min(total, chunk * 20 * 180);
			ctx.waitFor(mc -> {
				t[0]++;
				var p = mc.player;
				boolean dead = p.isDeadOrDying();
				if (dead && !wasDead[0]) {
					deaths[0]++;
					LOG.info("[{}s] DIED at {} ({})", t[0] / 20, p.blockPosition().toShortString(), HumanAutopilot.status());
				}
				wasDead[0] = dead;
				if (mc.screen == null && !dead) {
					double yaw = p.getYRot(), pitch = p.getXRot();
					if (!Double.isNaN(mm[0])) {
						double dy = net.minecraft.util.Mth.wrapDegrees(yaw - mm[0]), dp = pitch - mm[1];
						double turn = Math.hypot(dy, dp);
						if (Math.abs(dy) > 0.3 && Math.abs(mm[2]) > 0.3 && Math.signum(dy) != Math.signum(mm[2])) mm[3]++;
						if (turn > 0.01 && turn < 0.4) mm[4]++;
						mm[5] = Math.max(mm[5], turn);
						mm[6] += turn;
						mm[7]++;
						mm[2] = dy;
					}
					mm[0] = yaw;
					mm[1] = pitch;
					if (!Double.isNaN(lastPos[0]) && Math.hypot(p.getX() - lastPos[0], p.getZ() - lastPos[1]) > 0.05) {
						if (!p.isSprinting()) gait[0]++;
						else if (p.onGround()) gait[1]++;
						else gait[2]++;
					}
					if (p.getOffhandItem().is(net.minecraft.world.item.Items.TORCH)) gait[3]++;
					lastPos[0] = p.getX();
					lastPos[1] = p.getZ();
				}
				if (t[0] % 600 == 0 && mm[7] > 0) {
					long keys = dev.humanautopilot.InputDriver.keyToggles - keysAt[0];
					keysAt[0] = dev.humanautopilot.InputDriver.keyToggles;
					int moving = Math.max(1, gait[0] + gait[1] + gait[2]);
					LOG.info("[motion] last 30s: reversals/min={} tinyCorrections/min={} avgTurn={}deg/tick maxTurn={}deg/tick moveKeyToggles/min={} walk/sprint/hop%={}/{}/{} offhandTorch={}s glances={} heard={} peeks={} whiffs={} crits={} edgePeeks={} thinks={}",
							String.format("%.1f", mm[3] * 2), String.format("%.0f", mm[4] * 2), String.format("%.2f", mm[6] / mm[7]),
							String.format("%.1f", mm[5]), keys * 2, gait[0] * 100 / moving, gait[1] * 100 / moving, gait[2] * 100 / moving, gait[3] / 20,
								dev.humanautopilot.modules.Attention.glances, dev.humanautopilot.modules.Attention.soundLooks,
								dev.humanautopilot.survival.BagPeek.peeks, dev.humanautopilot.modules.CombatModule.whiffs, dev.humanautopilot.modules.CombatModule.crits,
								dev.humanautopilot.pathing.Navigator.edgePeeks, dev.humanautopilot.survival.SurvivalPlanner.thinks);
					LOG.info("[aim] last 30s: {}", dev.humanautopilot.AimController.auditLine(0.5));
					java.util.Arrays.fill(gait, 0);
					mm[3] = mm[4] = mm[5] = mm[6] = mm[7] = 0;
				}
				for (var e : goals.entrySet()) {
					if (!reached.containsKey(e.getKey()) && Inv.has(p, e.getValue())) {
						reached.put(e.getKey(), t[0] / 20);
						LOG.info("[{}s] GOT {}", t[0] / 20, e.getKey());
					}
				}
				if (t[0] % 600 == 0) {
					LOG.info("[{}s] tps={} hp={} food={} pos={} tunnel={} day={} | {}", t[0] / 20,
							String.format("%.1f", t[0] / ((System.currentTimeMillis() - startMs) / 1000.0)), (int) p.getHealth(), p.getFoodData().getFoodLevel(),
							p.blockPosition().toShortString(), HumanAutopilot.tunnelSteps(), mc.level.isBrightOutside() ? "day" : "night", HumanAutopilot.status());
				}
				return t[0] >= chunkEnd;
			}, 20 * 180 + 200);
			// A short burst so motion across a moment can be judged, not just a single still.
			for (int f = 0; f < 5; f++) {
				ctx.takeScreenshot("realworld-" + (t[0] / 20) + "s-" + f);
				ctx.waitTicks(8);
			}
			}
			LOG.info("REAL WORLD RESULT after {} min: deaths={} reached={}", minutes, deaths[0], reached);
			ctx.takeScreenshot("realworld-end");
			ctx.runOnClient(mc -> HumanAutopilot.panic());
			ctx.runOnClient(mc -> {
				if (HumanAutopilot.recorder().on()) {
					LOG.info("bot recording: {}", HumanAutopilot.recorder().file());
					HumanAutopilot.recorder().stop();
				}
			});
		}
	}
}
