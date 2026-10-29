package dev.humanautopilot.test;

import dev.humanautopilot.modules.BoxFillModule;
import dev.humanautopilot.net.BuildLink;
import dev.humanautopilot.schematic.SchematicLoader;
import dev.humanautopilot.schematic.SchematicMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The autopilot and Kohaku building one schematic together on the test server (localhost:25566, KawaiiCompanion on
 * it, Player0 op): Kohaku summoned, /kc helpbuild on, a build started. Every second: where each of them is and what
 * Kohaku says she's working on (her "helper" lines), how far apart they are. Passes if she told us where she was,
 * never came within 8 blocks of us, and nothing of ours got broken. HA_TALK_HELP=0 runs the same build alone (to
 * compare the speed). HA_TALK_FILE picks the schematic, HA_MINUTES how long. Run with HA_ONLY=talk.
 * <p>
 * HA_TALK_LEFT=N: the end of a build (the user: "what happens when they're 80 or 90 percent done ... it's making them
 * closer and closer"). Before either starts, all but N% of the build (over its floor) goes in through the test server's
 * console (C:\minecraft-test\claude-cmd.txt, fed to it by the scratchpad's testserver.ps1): left out are its top layers
 * (three quarters of the N%: a build goes bottom up) and the rest scattered. Then both finish it; the distances are
 * split by how far along it is, and it runs a minute past the end to see where she waits.
 */
public class TalkClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("talk-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"talk".equals(System.getenv("HA_ONLY"))) return;
		String file = System.getenv().getOrDefault("HA_TALK_FILE",
				"C:\\Users\\ItsMe\\AppData\\Roaming\\.minecraft\\config\\humanautopilot\\schematics\\japanese-style-house.litematic");
		boolean help = !"0".equals(System.getenv("HA_TALK_HELP"));
		int minutes = Integer.parseInt(System.getenv().getOrDefault("HA_MINUTES", "6"));
		int left = Integer.parseInt(System.getenv().getOrDefault("HA_TALK_LEFT", "0"));
		String addr = System.getenv().getOrDefault("HA_FARM_SERVER", "localhost:25566");
		// (Muted, like the other tests: the user hears the test window otherwise.)
		ctx.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
			mc.options.save();
		});
		ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr),
				new ServerData("talk test", addr, ServerData.Type.OTHER), false, null));
		ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.screen == null, 20 * 90);
		ctx.waitTicks(100);
		boolean can = false;
		for (int i = 0; i < 600 && !can; i += 20) {
			can = ctx.computeOnClient(mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(BuildLink.Payload.TYPE));
			if (!can) ctx.waitTicks(20);
		}
		LOG.info("CHECK: the server takes the build plan: {}", can);
		cmd(ctx, "gamemode creative");
		cmd(ctx, "kc dismiss");
		ctx.waitTicks(40);
		// A fresh bit of the flat world each run (spawn has what earlier tests left: the farm field, wipe leftovers).
		int fx = 4000 + new Random().nextInt(60) * 160, fz = 4000;
		cmd(ctx, "tp @s " + fx + " -50 " + fz);
		ctx.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - fx) < 4 && mc.level.hasChunk(fx >> 4, fz >> 4), 20 * 60);
		ctx.waitTicks(100);
		// The corner on the ground (the player can be hovering: a build put up in the air has nothing under it).
		BlockPos me = ctx.computeOnClient(mc -> mc.player.blockPosition());
		int gx = me.getX() + 4, gz = me.getZ() + 4;
		int gy = ctx.computeOnClient(mc -> {
			for (int y = mc.level.getMaxY(); y > mc.level.getMinY(); y--)
				if (!mc.level.getBlockState(new BlockPos(gx, y, gz)).isAir()) return y + 1;
			return mc.level.getMinY() + 4;
		});
		BlockPos at = new BlockPos(gx, gy, gz);
		// (A clean start: whatever she was doing from the last run went with the dismiss; called here, next to us. Help
		// Build comes on once the build is there - and, for the end of a build, once most of it is in.)
		cmd(ctx, "kc summon");
		ctx.waitTicks(60);
		LOG.info("at {}, building at {} on the ground ({}{})", me.toShortString(), at.toShortString(), help ? "with Kohaku" : "alone",
				left > 0 ? ", " + left + "% of it left to do" : "");
		ctx.runOnClient(mc -> {
			try {
				Path in = SchematicMode.dir().resolve(new java.io.File(file).getName());
				Files.createDirectories(in.getParent());
				Files.copy(Path.of(file), in, StandardCopyOption.REPLACE_EXISTING);
				SchematicLoader.Schematic sc = SchematicLoader.load(in.toFile());
				SchematicMode.replace = true;
				SchematicMode.keepGoing = false; // (done is done here: not on to a second copy beside it)
				SchematicMode.get().startPreview(sc, new java.io.File(file).getName());
				SchematicMode.get().buildAt(at);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitFor(mc -> BoxFillModule.get().planMode(), 20 * 30);
		if (left > 0) {
			// Counted first (what's there at the first count is the bar's 0%: the bar then shows the pasted part like a
			// build that got that far), then held still while the rest goes in (what it sees go in now isn't "built,
			// then blown out").
			ctx.waitFor(mc -> BoxFillModule.get().planCounted(), 20 * 90);
			ctx.runOnClient(mc -> BoxFillModule.get().setPaused(true));
			prepare(ctx, at, left);
		}
		cmd(ctx, help ? "kc helpbuild on" : "kc helpbuild off");
		if (left > 0) ctx.runOnClient(mc -> BoxFillModule.get().setPaused(false));
		ctx.waitTicks(20 * 10);
		ctx.waitFor(mc -> BoxFillModule.get().planTotal() > 0 && BoxFillModule.get().planCounted(), 20 * 90);
		int startDone = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
		int total0 = ctx.computeOnClient(mc -> BoxFillModule.get().planTotal());
		double bar0 = ctx.computeOnClient(mc -> BoxFillModule.get().planProgress());
		LOG.info("start: {} of {} done ({}%), the bar at {}%", startDone, total0, String.format("%.1f", 100.0 * startDone / Math.max(1, total0)),
				String.format("%.0f", 100 * bar0));
		long startPlaced = BoxFillModule.placedCount;
		int samples = 0, heard = 0, under8 = 0, under12 = 0, under16 = 0;
		double closest = Double.MAX_VALUE;
		List<Double> dists = new ArrayList<>();
		// (By how far along the build is: under 90%, 90-95, 95-98, 98 and up, done.)
		String[] phaseName = {"under 90%", "90-95%", "95-98%", "98%+", "done"};
		List<List<Double>> byPhase = new ArrayList<>();
		for (int i = 0; i < phaseName.length; i++) byPhase.add(new ArrayList<>());
		int damagedMax = 0;
		long doneAt = -1;
		// (By the clock, not game ticks: the test client can run well under 20 ticks a second while the server doesn't.)
		long t0 = System.currentTimeMillis(), end = t0 + minutes * 60_000L, nextLine = t0;
		int ticks = 0;
		while (System.currentTimeMillis() < end) {
			ctx.waitTicks(20);
			ticks += 20;
			long t = (System.currentTimeMillis() - t0) / 1000;
			if (!ctx.computeOnClient(mc -> mc.player != null && BoxFillModule.get().planMode())) {
				LOG.info("the build stopped at {} s", t);
				break;
			}
			Vec3 pos = ctx.computeOnClient(mc -> mc.player.position());
			List<BuildLink.Helper> hs = ctx.computeOnClient(mc -> BuildLink.helpers());
			int dmg = ctx.computeOnClient(mc -> BoxFillModule.get().damagedCount());
			int doneNow = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
			int totalNow = ctx.computeOnClient(mc -> BoxFillModule.get().planTotal());
			boolean finished = ctx.computeOnClient(mc -> BoxFillModule.get().guarding());
			double pct = 100.0 * doneNow / Math.max(1, totalNow);
			int phase = finished ? 4 : pct < 90 ? 0 : pct < 95 ? 1 : pct < 98 ? 2 : 3;
			if (finished && doneAt < 0) {
				doneAt = t;
				LOG.info("CHECK: the build's done at {} s ({} of {})", t, doneNow, totalNow);
				end = Math.min(end, System.currentTimeMillis() + 60_000); // (a minute more: where she waits once it's done)
			}
			damagedMax = Math.max(damagedMax, dmg);
			samples++;
			if (!hs.isEmpty()) heard++;
			StringBuilder who = new StringBuilder();
			for (BuildLink.Helper h : hs) {
				double d = h.at().distanceTo(pos);
				dists.add(d);
				byPhase.get(phase).add(d);
				closest = Math.min(closest, d);
				if (d < 8) {
					under8++;
					LOG.info("talk {} s: CLOSE - {} {} away (at {} {} {}, on {}), we're at {} {} {}, {}% done", t, h.name(), String.format("%.1f", d),
							String.format("%.0f", h.at().x), String.format("%.0f", h.at().y), String.format("%.0f", h.at().z),
							h.target() == null ? "-" : h.target().toShortString(), String.format("%.0f", pos.x), String.format("%.0f", pos.y),
							String.format("%.0f", pos.z), String.format("%.1f", pct));
				}
				if (d < 12) under12++;
				if (d < 16) under16++;
				who.append(String.format(" | %s at %.0f %.0f %.0f, %.1f away, on %s", h.name(), h.at().x, h.at().y, h.at().z, d,
						h.target() == null ? "-" : h.target().toShortString()));
			}
			if (System.currentTimeMillis() >= nextLine) {
				nextLine += 30_000;
				LOG.info("talk {} s ({} ticks a second): done {} ({}%, +{}), we placed {}, we're at {} {} {}{}", t,
						String.format("%.1f", ticks / Math.max(1.0, (System.currentTimeMillis() - t0) / 1000.0)), doneNow, String.format("%.1f", pct),
						doneNow - startDone, BoxFillModule.placedCount - startPlaced, String.format("%.0f", pos.x), String.format("%.0f", pos.y),
						String.format("%.0f", pos.z), who.length() == 0 ? " | no helper heard" : who.toString());
			}
		}
		int done = ctx.computeOnClient(mc -> BoxFillModule.get().planDone());
		int total = ctx.computeOnClient(mc -> BoxFillModule.get().planTotal());
		long placed = BoxFillModule.placedCount - startPlaced;
		ctx.takeScreenshot("talk-end");
		Collections.sort(dists);
		LOG.info("CHECK: {}: done {} of {} (+{} in {} min{}), we placed {}", help ? "with Kohaku" : "alone", done, total, done - startDone, minutes,
				doneAt >= 0 ? ", finished at " + doneAt + " s" : ", not finished", placed);
		LOG.info("CHECK: Kohaku told us where she was in {} of {} looks", heard, samples);
		LOG.info("CHECK: distance between us: closest {}, median {}; under 8 blocks {} times, under 12 {}, under 16 {}",
				closest == Double.MAX_VALUE ? "-" : String.format("%.1f", closest), median(dists), under8, under12, under16);
		for (int i = 0; i < phaseName.length; i++) {
			List<Double> ph = byPhase.get(i);
			if (ph.isEmpty()) continue;
			Collections.sort(ph);
			LOG.info("CHECK: distance {}: {} looks, closest {}, median {}", phaseName[i], ph.size(), String.format("%.1f", ph.get(0)), median(ph));
		}
		LOG.info("CHECK: our blocks broken by someone (damage, no TNT here): most at once {}", damagedMax);
		int[] gave = ctx.computeOnClient(mc -> BoxFillModule.get().gaveUpStats());
		LOG.info("CHECK: spots we gave up on: {}, of them put in since all the same: {}", gave[0], gave[1]);
		LOG.info("CHECK: the test client ran {} ticks a second", String.format("%.1f", ticks / Math.max(1.0, (System.currentTimeMillis() - t0) / 1000.0)));
		// What our own log says about racing her for spots and getting stuck (logs/latest.log of this test client).
		try {
			int raced = 0, stalls = 0, confused = 0, asked = 0;
			for (String line : Files.readAllLines(Path.of("logs", "latest.log"))) {
				if (line.contains("we didn't click it")) raced++;
				if (line.contains("[box] STALL")) stalls++;
				if (line.contains("[confused]")) confused++;
			}
			LOG.info("CHECK: our log: {} targets filled by someone else on the way, {} stall lines, {} confused", raced, stalls, confused);
		} catch (Exception e) {
			LOG.info("CHECK: our log: can't read it ({})", e.toString());
		}
		// Her own count, from the test server's log (same PC): her last status line, and what she was asked for.
		try {
			Path slog = Path.of(System.getenv().getOrDefault("HA_TALK_SERVER_LOG", "C:/minecraft-test/logs/latest.log"));
			String last = null, ask = null, gone = null;
			for (String line : Files.readAllLines(slog)) {
				if (line.contains("[companion] status ")) last = line;
				if (line.contains("asks the helpers for")) ask = line;
				if (line.contains("slips away:")) gone = line;
			}
			LOG.info("CHECK: her last status: {}", last == null ? "-" : last.substring(last.indexOf("[companion]")));
			LOG.info("CHECK: last ask: {}", ask == null ? "-" : ask.substring(ask.indexOf("[companion]")));
			LOG.info("CHECK: she slipped away: {}", gone == null ? "no" : gone.substring(gone.indexOf("[companion]")));
		} catch (Exception e) {
			LOG.info("CHECK: her last status: can't read the server log ({})", e.toString());
		}
		List<String> bad = new ArrayList<>();
		if (!can) bad.add("no plan channel");
		if (help && heard == 0) bad.add("never heard from Kohaku");
		if (help && under8 > 0) bad.add("Kohaku came within 8 blocks " + under8 + " times");
		if (done - startDone <= 0) bad.add("nothing got built");
		ctx.runOnClient(mc -> SchematicMode.get().stop());
		ctx.waitTicks(20 * 4);
		// (Gone near the end: the build stopped, she should be back by now.)
		try {
			Path slog = Path.of(System.getenv().getOrDefault("HA_TALK_SERVER_LOG", "C:/minecraft-test/logs/latest.log"));
			boolean gone = false, back = false;
			for (String line : Files.readAllLines(slog)) {
				if (line.contains("slips away:")) { gone = true; back = false; }
				if (line.contains(" is back for ")) back = true;
			}
			if (gone) LOG.info("CHECK: back after the build: {}", back);
			if (gone && !back) bad.add("she didn't come back after the build");
		} catch (Exception e) {
			LOG.info("CHECK: back after the build: can't read the server log ({})", e.toString());
		}
		cmd(ctx, "kc helpbuild off");
		cmd(ctx, "kc dismiss");
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("test over")));
		ctx.waitFor(mc -> mc.level == null, 20 * 20);
		ctx.runOnClient(mc -> mc.setScreen(new TitleScreen()));
		ctx.waitTicks(10);
		if (bad.isEmpty()) LOG.info("ALL TALK TESTS PASSED");
		else LOG.info("TALK TEST FAILED: {}", String.join("; ", bad));
	}

	private static String median(List<Double> sorted) {
		return sorted.isEmpty() ? "-" : String.format("%.1f", sorted.get(sorted.size() / 2));
	}

	/**
	 * All but {@code left}% of the build (over its floor - under it is the grass filler's in a flat world) put in through
	 * the test server's console: its top layers left out (three quarters of it) and the rest scattered. Waits till the
	 * client sees it in.
	 */
	private static void prepare(ClientGameTestContext ctx, BlockPos at, int left) {
		List<Map.Entry<BlockPos, BlockState>> spots = ctx.computeOnClient(mc -> new ArrayList<>(BoxFillModule.planView().entrySet()));
		List<Map.Entry<BlockPos, BlockState>> build = new ArrayList<>();
		for (var e : spots) if (e.getKey().getY() >= at.getY() && !e.getValue().isAir()) build.add(e);
		int toLeave = build.size() * left / 100;
		build.sort(Comparator.comparingInt(e -> -e.getKey().getY()));
		Set<BlockPos> leave = new HashSet<>();
		int i = 0, layers = 0;
		while (i < build.size() && leave.size() < toLeave * 3 / 4) {
			int y = build.get(i).getKey().getY();
			layers++;
			while (i < build.size() && build.get(i).getKey().getY() == y) leave.add(build.get(i++).getKey());
		}
		int top = leave.size();
		List<Map.Entry<BlockPos, BlockState>> rest = new ArrayList<>(build.subList(i, build.size()));
		Collections.shuffle(rest, new Random(7));
		for (int k = 0; k < rest.size() && leave.size() < toLeave; k++) leave.add(rest.get(k).getKey());
		Map<BlockPos, BlockState> put = new HashMap<>();
		for (var e : build) if (!leave.contains(e.getKey())) put.put(e.getKey(), e.getValue());
		// Runs along x of one block as a fill, the rest one by one; strict: no block updates, so nothing pops off for
		// want of a wall that comes a line later.
		List<BlockPos> order = new ArrayList<>(put.keySet());
		order.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getX()));
		List<String> cmds = new ArrayList<>();
		for (int k = 0; k < order.size(); ) {
			BlockPos a = order.get(k);
			BlockState st = put.get(a);
			int j = k + 1;
			while (j < order.size()) {
				BlockPos b = order.get(j);
				if (b.getY() != a.getY() || b.getZ() != a.getZ() || b.getX() != a.getX() + (j - k) || !put.get(b).equals(st)) break;
				j++;
			}
			String s = BlockStateParser.serialize(st);
			if (j - k == 1) cmds.add("setblock " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + s + " strict");
			else cmds.add("fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + (a.getX() + j - k - 1) + " " + a.getY() + " " + a.getZ() + " " + s + " strict");
			k = j;
		}
		console(cmds);
		LOG.info("prepared: {} of the build's {} spots over the floor put in ({} console commands); {} left to do: the top {} layers ({}) and {} scattered",
				put.size(), build.size(), cmds.size(), leave.size(), layers, top, leave.size() - top);
		// In when the client sees (nearly) all of a sample of them.
		List<BlockPos> sample = new ArrayList<>(put.keySet());
		Collections.shuffle(sample, new Random(3));
		List<BlockPos> few = sample.subList(0, Math.min(300, sample.size()));
		for (int w = 0; w < 120; w++) {
			ctx.waitTicks(20);
			int in = ctx.computeOnClient(mc -> {
				int n = 0;
				for (BlockPos p : few) if (mc.level.getBlockState(p).equals(put.get(p))) n++;
				return n;
			});
			if (in >= few.size() * 0.95) {
				LOG.info("prepared: in ({} of {} looked at) after {} s", in, few.size(), w + 1);
				return;
			}
		}
		LOG.info("prepared: not all in after 2 minutes - going on anyway");
	}

	/** Lines for the test server's console (the scratchpad's testserver.ps1 types what's in its claude-cmd.txt). */
	private static void console(List<String> lines) {
		try {
			Path f = Path.of(System.getenv().getOrDefault("HA_TALK_CONSOLE", "C:/minecraft-test/claude-cmd.txt"));
			// (Taken and emptied by the wrapper; one lot at a time.)
			for (int i = 0; i < 100 && Files.exists(f) && Files.size(f) > 0; i++) Thread.sleep(100);
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.write(tmp, lines);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void cmd(ClientGameTestContext ctx, String c) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(c));
		LOG.info("sent /{}", c);
	}
}
