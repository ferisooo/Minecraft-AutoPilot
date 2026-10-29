package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke test for the gameplay recorder: record ~10 s of a scripted "player" (a few smooth turns and a
 * hotbar switch, bot off), stop, and log where the file went so tools/analyze_recording.py can read it.
 * Runs only with HA_ONLY=record.
 */
public class RecorderClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("recorder-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"record".equals(System.getenv("HA_ONLY"))) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			ctx.runOnClient(mc -> HumanAutopilot.recorder().start());
			// A few smooth "hand" turns: accelerate, overshoot a little, settle; then rest.
			for (int turn = 0; turn < 4; turn++) {
				final double size = 40 + turn * 15, sign = turn % 2 == 0 ? 1 : -1;
				for (int i = 0; i < 12; i++) {
					final double f = Math.sin(Math.PI * (i + 0.5) / 12) * size / 7.6;
					ctx.runOnClient(mc -> mc.player.turn(sign * f / 0.15, 0.2 * f / 0.15));
					ctx.waitTick();
				}
				ctx.runOnClient(mc -> mc.player.turn(-sign * 3 / 0.15, 0));
				ctx.waitTicks(20);
			}
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> HumanAutopilot.recorder().stop());
			String where = ctx.computeOnClient(mc -> HumanAutopilot.recorder().file().toAbsolutePath().toString());
			LOG.info("RECORDING FILE {}", (Object) where);

			// The user's way round: numpad 8 first (recording themselves), then a build starts. The bot's building has to
			// end up in the file too (it used to pause for the bot and come out empty).
			ctx.runOnClient(mc -> {
				dev.humanautopilot.record.Recorder.recordBot = false;
				HumanAutopilot.toggleRecording();
			});
			ctx.waitTicks(20);
			net.minecraft.core.BlockPos p = ctx.computeOnClient(mc -> mc.player.blockPosition());
			ctx.runOnClient(mc -> HumanAutopilot.setLocalBox(p.getX() + 3, p.getY(), p.getZ() + 3, p.getX() + 6, p.getY() + 1, p.getZ() + 6, "stone"));
			ctx.runOnClient(mc -> HumanAutopilot.setBoxFill(true));
			ctx.waitTicks(200);
			ctx.runOnClient(mc -> {
				HumanAutopilot.panic();
				HumanAutopilot.toggleRecording();
			});
			String second = ctx.computeOnClient(mc -> HumanAutopilot.recorder().file().toAbsolutePath().toString());
			int[] counts = new int[2]; // frames after the bot took over, ticks with it building
			try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(
					java.nio.file.Files.newInputStream(java.nio.file.Path.of(second))), java.nio.charset.StandardCharsets.UTF_8))) {
				boolean bot = false;
				for (String line; (line = in.readLine()) != null; ) {
					if (line.contains("\"kind\":\"bot\"")) bot = true;
					if (bot && line.startsWith("{\"f\"")) counts[0]++;
					if (bot && line.startsWith("{\"t\"")) counts[1]++;
				}
			} catch (java.io.IOException e) {
				throw new AssertionError("couldn't read " + second, e);
			}
			LOG.info("RECORDING THEN BUILD: {} frames and {} ticks recorded after the bot took over ({})", counts[0], counts[1], second);
			if (counts[1] < 100) throw new AssertionError("the bot's building wasn't recorded (" + counts[1] + " ticks)");
		}
	}
}
