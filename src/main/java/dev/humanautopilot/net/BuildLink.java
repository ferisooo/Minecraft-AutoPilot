package dev.humanautopilot.net;

import dev.humanautopilot.HumanAutopilot;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells the KawaiiCompanion server plugin what's being built, so the companion can help with it from the other side of
 * the build (the user: "kawaii companion will help her build too but again they both stay distant from each other").
 * Channel {@code kawaii:schembuild}, plain text: {@code start;id;ox;oy;oz;count;replace;name}, {@code pal;id} and lines
 * {@code i=state}, {@code blk;id;dx,dy,dz,i;...}, {@code go;id}, {@code stop;id}, {@code hold;id;x,y,z;...} (spots kept
 * open on purpose, see hold), {@code area;id;x,y,z;r} ("I'm working here", see area); back from it {@code resend;id} (it
 * lost the build: restarted) and {@code helper;id;name;x,y,z;tx,ty,tz} ("I'm working there", see heard). Sent a few
 * messages a tick. Nothing at all on a server without the plugin.
 */
public final class BuildLink {
	public record Payload(byte[] data) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("kawaii", "schembuild"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBytes(p.data),
				buf -> {
					byte[] b = new byte[buf.readableBytes()];
					buf.readBytes(b);
					return new Payload(b);
				});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Under the 32k a message from us may be. */
	private static final int MAX = 30000;
	private static final ArrayDeque<String> queue = new ArrayDeque<>();
	private static int id;
	private static String name;
	private static boolean replace;
	private static Map<BlockPos, BlockState> plan;
	/** The build's floor (its ground under it is the grass filler's, in a flat world); MIN_VALUE: none of it is. */
	private static int floorY = Integer.MIN_VALUE;
	/** Spots under the floor the schematic has empty (sent as air, ",2": the grass filler leaves them). */
	private static java.util.List<BlockPos> empties = java.util.List.of();

	private BuildLink() {}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, ctx) -> {
			String text = new String(payload.data(), StandardCharsets.UTF_8);
			if (text.equals("resend;" + id) && plan != null) {
				HumanAutopilot.LOGGER.info("[schem] the server asked for the build again (it restarted): sending it");
				enqueue();
			} else if (text.startsWith("helper;")) {
				heard(text);
			}
		});
		// (Kept over a reconnect: the autopilot carries on with its build after one, so the server - restarted, with
		// nothing - gets it again once it can take it. Forgetting it here left Kohaku with Help Build ON and no build.)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			queue.clear();
			resendBy = plan == null ? -1 : HumanAutopilot.ticks() + 600;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			queue.clear();
			resendBy = -1;
		});
	}

	/** Till this tick (after a reconnect): send the build again as soon as the server can take it; -1: nothing to. */
	private static long resendBy = -1;

	/** A build's started: its blocks over to the server (a few messages a tick, see tick). */
	public static void start(String buildName, Map<BlockPos, BlockState> blocks, boolean replaceOn, int floor, java.util.List<BlockPos> emptyUnder) {
		if (!canSend()) return;
		floorY = floor;
		empties = new java.util.ArrayList<>(emptyUnder);
		if (plan != null) queue.add("stop;" + id);
		id = (int) (System.nanoTime() & 0x7FFFFFFF);
		name = buildName == null ? "?" : buildName.replace(';', ' ').replace('\n', ' ');
		replace = replaceOn;
		plan = new LinkedHashMap<>();
		for (var e : blocks.entrySet()) if (!e.getValue().isAir()) plan.put(e.getKey().immutable(), e.getValue());
		enqueue();
	}

	/**
	 * Done all she can: the server (the companion plugin) puts in what's still missing of it - the spots she couldn't get
	 * at - like a paste. Nothing at all on a server without the plugin.
	 */
	public static void finish() {
		if (plan == null || !canSend()) return;
		queue.add("finish;" + id);
		HumanAutopilot.LOGGER.info("[schem] asking the server to put in what's still missing of {}", name);
	}

	/** The build's over (done, stopped, another one): the server lets the companion go. */
	public static void stop() {
		if (plan == null) return;
		queue.clear();
		queue.add("stop;" + id);
		plan = null;
		holdSig = 0;
		needSig = 0;
		areaSent = null;
		helpers.clear();
	}

	/** What the last hold message said (a sum of its spots' hashes; 0: none sent since the build went over). */
	private static long holdSig;

	/** The last "I'm working here" sent: where, how far round, when (client ticks). */
	private static BlockPos areaSent;
	private static int areaSentR;
	private static long areaSentAt = -1000;

	/**
	 * "I'm working here" ({@code area;id;x,y,z;r;pct}): where the autopilot is busy now, or the far spot it's set off for,
	 * how far round that's its part (the companion keeps out of it), and how far along the build is (the progress bar's
	 * percent, -1 not counted yet: near the end she leaves instead of coming close). When it moves 4 blocks or more, else
	 * once a second (the server forgets an area after a few seconds without one).
	 */
	public static void area(BlockPos at, int r, int pct) {
		if (plan == null || resendBy >= 0 || at == null || !canSend()) return;
		long now = HumanAutopilot.ticks();
		boolean moved = areaSent == null || areaSent.distManhattan(at) >= 4 || r != areaSentR;
		if (!moved && now - areaSentAt < 20) return;
		if (moved && now - areaSentAt < 4) return; // (not every tick on the move)
		areaSent = at.immutable();
		areaSentR = r;
		areaSentAt = now;
		queue.add("area;" + id + ";" + at.getX() + "," + at.getY() + "," + at.getZ() + ";" + r + ";" + pct);
	}

	/** A companion helping with this build: where she is, the spot she's on (or null), when we last heard (client ticks). */
	public record Helper(String name, net.minecraft.world.phys.Vec3 at, BlockPos target, long heardAt) {}

	private static final Map<String, Helper> helpers = new java.util.concurrent.ConcurrentHashMap<>();

	/** "I'm working there" from the server ({@code helper;id;name;x,y,z;tx,ty,tz} or {@code -}). */
	private static void heard(String text) {
		String[] f = text.split(";");
		if (f.length < 5) return;
		try {
			if (Integer.parseInt(f[1].trim()) != id) return;
			String[] a = f[3].split(",");
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]));
			BlockPos t = null;
			if (!f[4].trim().equals("-")) {
				String[] b = f[4].split(",");
				t = new BlockPos(Integer.parseInt(b[0].trim()), Integer.parseInt(b[1].trim()), Integer.parseInt(b[2].trim()));
			}
			if (!helpers.containsKey(f[2])) HumanAutopilot.LOGGER.info("[schem] {} is helping with {}: we each keep to our own part", f[2], name);
			helpers.put(f[2], new Helper(f[2], at, t, HumanAutopilot.ticks()));
		} catch (RuntimeException ignored) {
			// (A line we don't understand: the next one will do.)
		}
	}

	/** The companions helping now (heard from in the last 3 s). */
	public static java.util.List<Helper> helpers() {
		if (helpers.isEmpty()) return java.util.List.of();
		long now = HumanAutopilot.ticks();
		helpers.values().removeIf(h -> now - h.heardAt() > 60);
		return new java.util.ArrayList<>(helpers.values());
	}

	/**
	 * The spots the autopilot is keeping open on purpose (blocks taken out to get at a spot, its way in through a roof):
	 * {@code hold;id;x,y,z;...}, the whole list each time it changes, so the companion leaves them alone. Absolute block
	 * positions. Nothing on a server without the plugin.
	 */
	public static void hold(java.util.Collection<BlockPos> spots) {
		if (plan == null || resendBy >= 0 || !canSend()) return;
		long sig = sigOf(spots);
		if (sig == holdSig) return;
		holdSig = sig;
		queue.add(spotsMsg("hold", spots));
	}

	/** What the last need message said (like holdSig). */
	private static long needSig;

	/**
	 * "I can't get to these - can you?" ({@code need;id;x,y,z;...}): spots of the build the autopilot gave up on (no spot
	 * to place them from, couldn't get there) that are still empty. A helper puts them in even inside the autopilot's
	 * part (not right next to it): she doesn't need to see a face. The whole list each time it changes. Nothing on a
	 * server without the plugin.
	 */
	public static void need(java.util.Collection<BlockPos> spots) {
		if (plan == null || resendBy >= 0 || !canSend()) return;
		long sig = sigOf(spots);
		if (sig == needSig) return;
		if (needSig == 0 && spots.isEmpty()) {
			needSig = sig; // (nothing to ask for yet: nothing to say)
			return;
		}
		needSig = sig;
		queue.add(spotsMsg("need", spots));
	}

	private static long sigOf(java.util.Collection<BlockPos> spots) {
		long sig = 0x5bd1e995L + spots.size();
		for (BlockPos p : spots) {
			long h = p.asLong() * 0x9E3779B97F4A7C15L;
			sig += h ^ (h >>> 29);
		}
		return sig;
	}

	private static String spotsMsg(String kind, java.util.Collection<BlockPos> spots) {
		StringBuilder sb = new StringBuilder(kind + ";" + id + ";");
		for (BlockPos p : spots) {
			String c = p.getX() + "," + p.getY() + "," + p.getZ() + ";";
			if (sb.length() + c.length() > MAX) break;
			sb.append(c);
		}
		return sb.toString();
	}

	private static void enqueue() {
		queue.clear();
		holdSig = 0; // (the hold list goes over again after the build)
		needSig = 0; // (and what we asked for help with)
		areaSent = null; // (and where we are)
		if (plan == null || plan.isEmpty()) return;
		int ox = Integer.MAX_VALUE, oy = Integer.MAX_VALUE, oz = Integer.MAX_VALUE;
		for (BlockPos p : plan.keySet()) {
			ox = Math.min(ox, p.getX());
			oy = Math.min(oy, p.getY());
			oz = Math.min(oz, p.getZ());
		}
		Map<BlockState, Integer> pal = new HashMap<>();
		StringBuilder palMsg = new StringBuilder("pal;" + id);
		StringBuilder blk = new StringBuilder("blk;" + id + ";");
		java.util.List<String> blks = new java.util.ArrayList<>(), pals = new java.util.ArrayList<>();
		for (var e : plan.entrySet()) {
			Integer i = pal.get(e.getValue());
			if (i == null) {
				i = pal.size();
				pal.put(e.getValue(), i);
				String line = "\n" + i + "=" + BlockStateParser.serialize(e.getValue());
				if (palMsg.length() + line.length() > MAX) {
					pals.add(palMsg.toString());
					palMsg = new StringBuilder("pal;" + id);
				}
				palMsg.append(line);
			}
			BlockPos p = e.getKey();
			// (",1": the ground under its floor - soil there - the grass filler's, not the building companion's.)
			boolean ground = floorY != Integer.MIN_VALUE && p.getY() < floorY; // (all of it under the floor: the refiller's)
			String cell = (p.getX() - ox) + "," + (p.getY() - oy) + "," + (p.getZ() - oz) + "," + i + (ground ? ",1;" : ";");
			if (blk.length() + cell.length() > MAX) {
				blks.add(blk.toString());
				blk = new StringBuilder("blk;" + id + ";");
			}
			blk.append(cell);
		}
		// (The schematic's empty spots under its floor: air, marked to be left empty.)
		if (!empties.isEmpty()) {
			BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			int ai = pal.computeIfAbsent(air, k -> pal.size());
			String line = "\n" + ai + "=" + BlockStateParser.serialize(air);
			if (palMsg.indexOf(line) < 0) {
				if (palMsg.length() + line.length() > MAX) {
					pals.add(palMsg.toString());
					palMsg = new StringBuilder("pal;" + id);
				}
				palMsg.append(line);
			}
			for (BlockPos p : empties) {
				String cell = (p.getX() - ox) + "," + (p.getY() - oy) + "," + (p.getZ() - oz) + "," + ai + ",2;";
				if (blk.length() + cell.length() > MAX) {
					blks.add(blk.toString());
					blk = new StringBuilder("blk;" + id + ";");
				}
				blk.append(cell);
			}
		}
		pals.add(palMsg.toString());
		blks.add(blk.toString());
		queue.add("start;" + id + ";" + ox + ";" + oy + ";" + oz + ";" + plan.size() + ";" + replace + ";" + floorY + ";" + name);
		queue.addAll(pals);
		queue.addAll(blks);
		queue.add("go;" + id);
		HumanAutopilot.LOGGER.info("[schem] telling the server about {} ({} blocks, {} kinds) for the companion: {} messages", name,
				plan.size(), pal.size(), queue.size());
	}

	/** Every client tick: a few of what's waiting go out. */
	public static void tick() {
		if (resendBy >= 0) {
			if (plan == null || !dev.humanautopilot.modules.BoxFillModule.get().planMode()) {
				plan = null; // (the build ended while we were away)
				resendBy = -1;
			} else if (canSend()) {
				resendBy = -1;
				HumanAutopilot.LOGGER.info("[schem] back on the server: telling it about the build again");
				enqueue();
			} else if (HumanAutopilot.ticks() > resendBy) {
				resendBy = -1; // (no companion plugin there)
			}
		}
		if (queue.isEmpty()) return;
		if (!canSend()) {
			queue.clear();
			return;
		}
		for (int i = 0; i < 4 && !queue.isEmpty(); i++) {
			try {
				ClientPlayNetworking.send(new Payload(queue.poll().getBytes(StandardCharsets.UTF_8)));
			} catch (RuntimeException e) {
				HumanAutopilot.LOGGER.warn("[schem] couldn't send the build to the server: {}", e.toString());
				queue.clear();
				return;
			}
		}
	}

	/** The server has her whole build (sent, nothing still queued): /kc finish has something to fill in. */
	public static boolean sent() {
		return plan != null && queue.isEmpty() && canSend();
	}

	private static boolean canSend() {
		try {
			return ClientPlayNetworking.canSend(Payload.TYPE);
		} catch (IllegalStateException notConnected) {
			return false;
		}
	}
}
