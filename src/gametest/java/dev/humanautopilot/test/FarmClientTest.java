package dev.humanautopilot.test;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.FarmModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Farm mode against the real StreamToEarn Farm plugin: joins the test server (localhost:25566, run by Stream to Earn
 * so the plugin's activated; the farm made there already) and plays for HA_FARM_MINUTES (default 6) - the test bot
 * sends the gifts meanwhile. Logs the field every half minute; passes with a win. Run with HA_ONLY=farm.
 */
public class FarmClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("farm-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"farm".equals(System.getenv("HA_ONLY"))) return;
		int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_FARM_MINUTES", "6"));
		String addr = System.getenv().getOrDefault("HA_FARM_SERVER", "localhost:25566");
		ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr),
				new ServerData("farm test", addr, ServerData.Type.OTHER), false, null));
		ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.screen == null, 20 * 90);
		ctx.waitTicks(100);
		LOG.info("joined {} at {}", addr, ctx.computeOnClient(mc -> mc.player.blockPosition().toShortString()
				+ " " + mc.gameMode.getPlayerMode()));
		ctx.runOnClient(mc -> HumanAutopilot.setFarm(true));
		int halfMinutes = minutes * 2;
		int bestWins = 0;
		for (int i = 1; i <= halfMinutes; i++) {
			ctx.waitTicks(600);
			String line = ctx.computeOnClient(mc -> {
				var f = FarmModule.get();
				return String.format("%d of %d ripe, %d snowballs, %d wins | %s", f.ripeNow(), f.fieldSize(), f.thrown(), f.wins(),
						HumanAutopilot.status());
			});
			bestWins = ctx.computeOnClient(mc -> FarmModule.get().wins());
			LOG.info("CHECK: {} s: {}", i * 30, line);
			if (!ctx.computeOnClient(mc -> HumanAutopilot.isFarmOn())) throw new AssertionError("farm mode switched itself off: " + line);
		}
		ctx.takeScreenshot("farm-end");
		ctx.runOnClient(mc -> HumanAutopilot.setFarm(false));
		ctx.runOnClient(mc -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("test over")));
		ctx.waitFor(mc -> mc.level == null, 20 * 20);
		ctx.runOnClient(mc -> mc.setScreen(new TitleScreen())); // (a gametest has to end on the title screen)
		ctx.waitTicks(10);
		if (bestWins < 1) throw new AssertionError("no win in " + minutes + " minutes");
		LOG.info("ALL FARM TESTS PASSED: {} wins in {} minutes", bestWins, minutes);
	}
}
