package dev.humanautopilot.survival;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.mixin.ContainerScreenAccessor;
import dev.humanautopilot.util.Human;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Opening the inventory just to have a look, the way the recorded player does every couple of minutes:
 * E, a second or two with the cursor drifting over a slot or two, then E (or Esc) again.
 */
public final class BagPeek {
	private enum Phase { NONE, OPENING, LOOKING }

	private final GuiMouse mouse = new GuiMouse();
	/** Times the bag was actually opened for a look (for the test log). */
	public static long peeks;
	private Phase phase = Phase.NONE;
	private long openedAt, closeAt, nextHover;
	private int hovers;

	public boolean active() {
		return phase != Phase.NONE;
	}

	/** Press the inventory key. */
	public void start(InputDriver in) {
		in.click(InputDriver.opts().keyInventory);
		phase = Phase.OPENING;
		openedAt = HumanAutopilot.ticks();
		mouse.reset();
		hovers = Human.rangeInt(0, 2);
	}

	/** Every tick while active. */
	public void tick(Minecraft mc) {
		long now = HumanAutopilot.ticks();
		if (phase == Phase.OPENING) {
			if (mc.screen instanceof InventoryScreen) {
				phase = Phase.LOOKING;
				peeks++;
				closeAt = now + Human.secondsToTicks(Human.gauss(0.9, 2.6));
				nextHover = now + Human.reaction();
			} else if (now - openedAt > 20) {
				phase = Phase.NONE; // it didn't open; never mind
			}
			return;
		}
		if (!(mc.screen instanceof InventoryScreen scr)) {
			phase = Phase.NONE;
			return;
		}
		if (now >= closeAt || mc.player.hurtTime > 0) {
			// Same key that opened it, now and then Esc.
			int key = Human.chance(0.7) ? KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).getValue() : GLFW.GLFW_KEY_ESCAPE;
			scr.keyPressed(new KeyEvent(key, 0, 0));
			if (mc.screen == scr) mc.player.closeContainer();
			phase = Phase.NONE;
			return;
		}
		if (now >= nextHover && hovers > 0) {
			// Eyes (and the cursor with them) on something in the bags.
			List<Slot> full = new ArrayList<>();
			for (Slot s : scr.getMenu().slots) if (s.hasItem()) full.add(s);
			if (!full.isEmpty()) {
				Slot s = full.get(Human.RNG.nextInt(full.size()));
				var acc = (ContainerScreenAccessor) scr;
				mouse.moveTo(acc.humanautopilot$leftPos() + s.x + 8 + Human.range(-4, 4), acc.humanautopilot$topPos() + s.y + 8 + Human.range(-4, 4));
			}
			hovers--;
			nextHover = now + Human.rangeInt(8, 20);
		}
	}

	/** Per frame while the inventory is open. */
	public void frame() {
		if (phase == Phase.LOOKING) mouse.frame();
	}

	public void cancel() {
		Minecraft mc = Minecraft.getInstance();
		if (phase == Phase.LOOKING && mc.screen instanceof InventoryScreen && mc.player != null) mc.player.closeContainer();
		phase = Phase.NONE;
	}
}
