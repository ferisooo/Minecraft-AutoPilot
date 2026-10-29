package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.schematic.SchematicLoader;
import dev.humanautopilot.schematic.SchematicMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "wipe the build" gift, for lag: on the test server (localhost:25566, KawaiiCompanion on it, Player0 op), a
 * schematic (HA_WIPE_FILE, default copper-guard-walls.litematic from the user's schematics folder) started a little
 * way off, the plugin told about it, /kc finish pastes the lot, then /kc wipe takes it all out again - the client's
 * frames a second measured all through (the server's slowest tick is in its own log). Run with HA_ONLY=wipe.
 */
public class WipeClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("wipe-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"wipe".equals(System.getenv("HA_ONLY"))) return;
		String file = System.getenv().getOrDefault("HA_WIPE_FILE",
				"C:\\Users\\ItsMe\\AppData\\Roaming\\.minecraft\\config\\humanautopilot\\schematics\\copper-guard-walls.litematic");
		String addr = System.getenv().getOrDefault("HA_FARM_SERVER", "localhost:25566");
		ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr),
				new ServerData("wipe test", addr, ServerData.Type.OTHER), false, null));
		ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.screen == null, 20 * 90);
		ctx.waitTicks(100);
		// (The plugin's channel has to be there for the build to go over: waited for, and said.)
		boolean can = false;
		for (int i = 0; i < 600 && !can; i += 20) {
			can = ctx.computeOnClient(mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
					dev.humanautopilot.net.BuildLink.Payload.TYPE));
			if (!can) ctx.waitTicks(20);
		}
		LOG.info("CHECK: the server takes the build plan: {}", can);
		BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos at = me.offset(12, 0, 12);
		LOG.info("joined at {}, building at {}", me.toShortString(), at.toShortString());
		ctx.runOnClient(mc -> {
			try {
				// (Into her schematics folder: a build is only told to the server when its file is there.)
				java.nio.file.Path in = SchematicMode.dir().resolve(new java.io.File(file).getName());
				java.nio.file.Files.createDirectories(in.getParent());
				java.nio.file.Files.copy(java.nio.file.Path.of(file), in, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				SchematicLoader.Schematic sc = SchematicLoader.load(in.toFile());
				SchematicMode.replace = true;
				SchematicMode.get().startPreview(sc, new java.io.File(file).getName());
				SchematicMode.get().buildAt(at);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		// The plan over to the plugin (a little a tick), then the whole thing pasted in.
		ctx.waitTicks(20 * 25);
		// (She stays on: switched off, the plugin lets the build go and there's nothing to paste or wipe.)
		cmd(ctx, "kc finish");
		ctx.waitTicks(20 * 15);
		ctx.takeScreenshot("wipe-pasted");
		double before = fps(ctx, 100);
		LOG.info("CHECK: pasted; frames a second over 5 s: {}", String.format("%.0f", before));
		cmd(ctx, "kc wipe Tester");
		int min = Integer.MAX_VALUE;
		double sum = 0;
		int n = 0;
		for (int t = 0; t < 20 * 10; t++) {
			ctx.waitTick();
			int f = ctx.computeOnClient(mc -> mc.getFps());
			min = Math.min(min, f);
			sum += f;
			n++;
		}
		ctx.takeScreenshot("wipe-wiped");
		String after0 = ctx.computeOnClient(mc -> HumanAutopilot.status());
		LOG.info("CHECK: her, after the wipe: {}", after0);
		double after = fps(ctx, 100);
		LOG.info("CHECK: wipe: frames a second before {}, during it lowest {} average {}, after {}", String.format("%.0f", before), min,
				String.format("%.0f", sum / n), String.format("%.0f", after));
		ctx.runOnClient(mc -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("test over")));
		ctx.waitFor(mc -> mc.level == null, 20 * 20);
		ctx.runOnClient(mc -> mc.setScreen(new TitleScreen()));
		ctx.waitTicks(10);
		LOG.info("ALL WIPE TESTS PASSED");
	}

	private static double fps(ClientGameTestContext ctx, int ticks) {
		double s = 0;
		for (int t = 0; t < ticks; t++) {
			ctx.waitTick();
			s += ctx.computeOnClient(mc -> mc.getFps());
		}
		return s / ticks;
	}

	private static void cmd(ClientGameTestContext ctx, String c) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(c));
		LOG.info("sent /{}", c);
	}
}
