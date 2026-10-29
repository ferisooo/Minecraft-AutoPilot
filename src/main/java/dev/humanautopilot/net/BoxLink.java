package dev.humanautopilot.net;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.modules.BoxFillModule;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;

/**
 * Talks to the KawaiiBedrockPilot server plugin on channel {@code kawaii:bedrockbox}. Plain text both ways:
 * <ul>
 *   <li>server -> us: {@code box;minX;minY;minZ;maxX;maxY;maxZ;item,item,...} (one item id per layer, bottom up)
 *       or {@code none}; {@code pause;ticks} after a win wipe.</li>
 *   <li>us -> server: {@code on} / {@code off} when box fill is switched, so it only lets us fly while we're filling.</li>
 * </ul>
 * The plugin never moves the player; everything on screen is our own key presses and mouse.
 */
public final class BoxLink {
	public record Payload(byte[] data) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("kawaii", "bedrockbox"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBytes(p.data),
				buf -> {
					byte[] b = new byte[buf.readableBytes()];
					buf.readBytes(b);
					return new Payload(b);
				});

		Payload(String text) {
			this(text.getBytes(StandardCharsets.UTF_8));
		}

		String text() {
			return new String(data, StandardCharsets.UTF_8);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private BoxLink() {}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, ctx) -> receive(payload.text()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> BoxFillModule.get().serverBox(null));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> BoxFillModule.get().serverBox(null));
	}

	/** Tell the plugin whether box fill is on (no-op on servers without it). */
	public static void sendState(boolean on) {
		try {
			if (ClientPlayNetworking.canSend(Payload.TYPE)) ClientPlayNetworking.send(new Payload(on ? "on" : "off"));
		} catch (IllegalStateException notConnected) {
			// Not in a world: nothing to tell.
		}
	}

	private static void receive(String text) {
		if (text.equals("none")) {
			BoxFillModule.get().serverBox(null);
			return;
		}
		if (text.startsWith("pause;")) {
			// The box was just wiped for a win: let that play out before building again.
			try {
				BoxFillModule.get().pause(Integer.parseInt(text.substring(6).trim()));
			} catch (NumberFormatException e) {
				HumanAutopilot.LOGGER.warn("[box] bad pause message: {}", text);
			}
			return;
		}
		String[] f = text.split(";");
		if (f.length != 8 || !f[0].equals("box")) {
			HumanAutopilot.LOGGER.warn("[box] unknown message from the server: {}", text);
			return;
		}
		try {
			int minX = Integer.parseInt(f[1]), minY = Integer.parseInt(f[2]), minZ = Integer.parseInt(f[3]);
			int maxX = Integer.parseInt(f[4]), maxY = Integer.parseInt(f[5]), maxZ = Integer.parseInt(f[6]);
			String[] ids = f[7].split(",");
			if (ids.length != maxY - minY + 1) throw new IllegalArgumentException("layers " + ids.length);
			Item[] layers = new Item[ids.length];
			for (int i = 0; i < ids.length; i++) layers[i] = item(ids[i]);
			BoxFillModule.get().serverBox(new BoxFillModule.Box(minX, minY, minZ, maxX, maxY, maxZ, layers));
			// Let it know where we stand (it may have restarted since we switched on).
			sendState(BoxFillModule.get().on());
		} catch (RuntimeException e) {
			HumanAutopilot.LOGGER.warn("[box] bad box message: {} ({})", text, e.toString());
		}
	}

	public static Item item(String id) {
		Identifier key = Identifier.tryParse(id.trim());
		Item it = key == null ? null : BuiltInRegistries.ITEM.getValue(key);
		return it == null || it == Items.AIR ? Items.STONE : it;
	}
}
