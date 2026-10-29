package dev.humanautopilot;

import dev.humanautopilot.mixin.KeyMappingAccessor;
import dev.humanautopilot.util.Human;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives the game only through vanilla key mappings: holding a key sets it "down" exactly
 * like a physical press, and clicks bump the same click counter a real key press does.
 * Modules call {@link #hold} every tick for keys they want held; anything not re-held is released.
 */
public final class InputDriver {
	private final Set<KeyMapping> wanted = new HashSet<>();
	private final Set<KeyMapping> held = new HashSet<>();
	private long hotbarReadyTick;

	public void hold(KeyMapping key) {
		wanted.add(key);
	}

	/** Take back a hold asked for earlier this tick. */
	public void drop(KeyMapping key) {
		wanted.remove(key);
	}

	public void click(KeyMapping key) {
		KeyMappingAccessor acc = (KeyMappingAccessor) key;
		acc.humanautopilot$setClickCount(acc.humanautopilot$getClickCount() + 1);
	}

	/** Pushes this tick's wanted keys to the game. Only releases keys this mod pressed. */
	public void apply() {
		long now = HumanAutopilot.ticks();
		for (var it = held.iterator(); it.hasNext(); ) {
			KeyMapping k = it.next();
			if (!wanted.contains(k)) {
				// A finger stays on a key for at least a moment; no 1-tick taps on movement keys.
				if (smoothed(k) && now - pressedAt.getOrDefault(k, 0L) < MIN_HOLD) continue;
				k.setDown(false);
				releasedAt.put(k, now);
				if (walkKey(k)) keyToggles++;
				it.remove();
			}
		}
		for (KeyMapping k : wanted) {
			if (!held.contains(k)) {
				// ...and doesn't jab the same key again the instant it let go.
				if (walkKey(k) && now - releasedAt.getOrDefault(k, -100L) < MIN_GAP) continue;
				pressedAt.put(k, now);
				if (walkKey(k)) keyToggles++;
			}
			k.setDown(true);
			held.add(k);
		}
		wanted.clear();
	}

	private static final int MIN_HOLD = 3, MIN_GAP = 2;
	/** Movement-key presses + releases so far (for the motion meter in tests). */
	public static long keyToggles;
	private final java.util.Map<KeyMapping, Long> pressedAt = new java.util.HashMap<>();
	private final java.util.Map<KeyMapping, Long> releasedAt = new java.util.HashMap<>();

	private static boolean walkKey(KeyMapping k) {
		var o = opts();
		return k == o.keyUp || k == o.keyDown || k == o.keyLeft || k == o.keyRight;
	}

	private static boolean smoothed(KeyMapping k) {
		var o = opts();
		return walkKey(k) || k == o.keySprint || k == o.keyShift || k == o.keyAttack;
	}

	public void releaseAll() {
		wanted.clear();
		for (KeyMapping k : held) k.setDown(false);
		held.clear();
	}

	/**
	 * Presses the number key for a hotbar slot, with a human gap between switches.
	 * Returns true once that slot is actually selected.
	 */
	public boolean selectHotbar(int slot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || slot < 0 || slot > 8) return false;
		int cur = mc.player.getInventory().getSelectedSlot();
		if (cur == slot) {
			scrolling = false;
			return true;
		}
		long now = HumanAutopilot.ticks();
		if (now < hotbarReadyTick) return false;
		// Like the player we learned from: roll the scroll wheel a notch at a time (shortest way round the
		// hotbar), now and then a number key instead. A short beat (100-250 ms) before reaching for it.
		if (!scrolling) {
			scrolling = true;
			useNumberKey = Human.chance(0.04);
			lastScrollFrom = -1;
			deadNotches = 0;
			hotbarReadyTick = now + Human.rangeInt(2, 5);
			return false;
		}
		if (useNumberKey || scrollBroken) {
			click(mc.options.keyHotbarSlots[slot]);
			hotbarReadyTick = now + 1 + Human.actionDelay();
			return false;
		}
		// Safety net: if notches stop moving the selection, fall back to number keys rather than get stuck.
		if (cur == lastScrollFrom && ++deadNotches > 6) {
			scrollBroken = true;
			HumanAutopilot.LOGGER.warn("[input] scroll wheel isn't changing the hotbar slot; using number keys");
			return false;
		}
		if (cur != lastScrollFrom) deadNotches = 0;
		lastScrollFrom = cur;
		int fwd = Math.floorMod(slot - cur, 9); // notches "down" (next slot)
		int dir = fwd <= 4 ? 1 : -1;
		var acc = (dev.humanautopilot.mixin.MouseHandlerAccessor) mc.mouseHandler;
		acc.humanautopilot$scroll(mc.getWindow().handle(), 0, -dir); // scroll down = next slot
		scrolling = true;
		// Notches come in a quick roll; the first one after a decision comes after a short beat.
		hotbarReadyTick = now + 1;
		return false;
	}

	private boolean scrolling, useNumberKey, scrollBroken;
	private int lastScrollFrom = -1, deadNotches;

	public static Options opts() {
		return Minecraft.getInstance().options;
	}
}
