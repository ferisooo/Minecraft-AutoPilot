package dev.humanautopilot.survival;

import dev.humanautopilot.mixin.MouseHandlerAccessor;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Moves the bot's cursor across an open screen like a hand would: a quick, slightly springy glide toward the target
 * (small overshoot, then settle), landing a little off-centre. Positions are in GUI coordinates (the same ones slots
 * use). It's the game's own idea of where the mouse is, not the user's real pointer: that stays theirs (and their
 * moves are ignored while the bot works in a menu), and the cursor viewers see is drawn by {@link #draw}.
 */
public final class GuiMouse {
	private double x = Double.NaN, y;      // current, GUI coords
	private double vx, vy;                 // GUI px per second
	private double tx, ty;                 // target
	private boolean hasTarget, resting;
	private double omega = 16, zeta = 0.7;
	private long lastNanos;
	/** The screen we're moving over: a new one starts the cursor where the game put it (the middle). */
	private net.minecraft.client.gui.screens.Screen screen;

	/** Where the bot's cursor was last put (GUI coordinates) and when, for drawing it. */
	private static double shownX, shownY;
	private static long shownAt;

	/** Draw the bot's cursor (a plain arrow) if it moved in the last moment. */
	public static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (shownAt == 0) return;
		int x = (int) Math.round(shownX), y = (int) Math.round(shownY);
		// An arrow: a black outline with a white inside, 1 GUI pixel per step.
		for (int row = 0; row < 11; row++) {
			int w = row < 8 ? row + 1 : 11 - row;
			g.fill(x - 1, y + row - 1, x + w + 1, y + row + 1, 0xFF000000);
		}
		for (int row = 0; row < 10; row++) {
			int w = row < 8 ? row : 9 - row;
			if (w > 0) g.fill(x, y + row, x + w, y + row + 1, 0xFFFFFFFF);
		}
	}

	public void reset() {
		x = Double.NaN;
		hasTarget = false;
		resting = false;
		vx = vy = 0;
	}

	/** Head for a point. Re-targeting the same point keeps the current motion. */
	public void moveTo(double gx, double gy) {
		if (!hasTarget || Math.abs(gx - tx) > 0.5 || Math.abs(gy - ty) > 0.5) {
			omega = Human.range(13, 20);
			zeta = Human.range(0.6, 0.85);
			resting = false;
		}
		tx = gx;
		ty = gy;
		hasTarget = true;
	}

	/** On the target and nearly still. */
	public boolean arrived() {
		return hasTarget && !Double.isNaN(x) && Math.hypot(tx - x, ty - y) < 1.2 && Math.hypot(vx, vy) < 40;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	/** Called every rendered frame while one of our screens is open. */
	public void frame() {
		Minecraft mc = Minecraft.getInstance();
		var win = mc.getWindow();
		double scaleX = (double) win.getScreenWidth() / win.getGuiScaledWidth();
		double scaleY = (double) win.getScreenHeight() / win.getGuiScaledHeight();
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.016 : Math.clamp((now - lastNanos) / 1e9, 0.001, 0.05);
		lastNanos = now;
		if (mc.screen != screen) {
			// A screen just opened: the game centred the cursor on it. Carrying on from where it was in the last one
			// made it jump straight back there in one frame (no hand does that).
			screen = mc.screen;
			x = Double.NaN;
			vx = vy = 0;
			resting = false;
			hasTarget = false; // (the last screen's target means nothing here)
		}
		if (Double.isNaN(x)) {
			// Start from wherever the cursor is (the game centres it when a screen opens).
			x = mc.mouseHandler.xpos() / scaleX;
			y = mc.mouseHandler.ypos() / scaleY;
		}
		shownX = x;
		shownY = y;
		shownAt = now;
		if (!hasTarget || resting) return;
		vx += (omega * omega * (tx - x) - 2 * zeta * omega * vx) * dt;
		vy += (omega * omega * (ty - y) - 2 * zeta * omega * vy) * dt;
		x += vx * dt;
		y += vy * dt;
		if (Math.hypot(tx - x, ty - y) < 0.35 && Math.hypot(vx, vy) < 8) { // come to rest (no endless creep)
			vx = vy = 0;
			resting = true;
		}
		// Whole screen pixels, like a real mouse (not drifting a tenth of a pixel at a time).
		double px = Math.round(x * scaleX), py = Math.round(y * scaleY);
		shownX = x;
		shownY = y;
		shownAt = now;
		MouseHandlerAccessor acc = (MouseHandlerAccessor) mc.mouseHandler;
		acc.humanautopilot$setXpos(px);
		acc.humanautopilot$setYpos(py);
	}
}
