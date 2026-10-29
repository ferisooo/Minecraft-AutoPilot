package dev.humanautopilot.pathing;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;

/**
 * Optional heavy-duty legs: when the Baritone mod is installed, trips our own pathfinder can't manage (a gap to
 * bridge, a hole to pillar out of, a jump to make, a long way to go) are handed to Baritone. Its camera goes through
 * our {@link AimController} so it still turns like a hand on a mouse, and it holds still whenever the autopilot
 * stops to eat, fight, look round or take a break.
 *
 * This class never touches a Baritone type, so the mod loads fine without it; all of that lives in
 * {@code BaritoneImpl}, which is only loaded once we know Baritone is there.
 */
public final class BaritoneLegs {
	/** What the walker needs from the heavy-duty legs. */
	interface Legs {
		boolean go(Goal goal, boolean dig);

		Navigator.Status tick(LocalPlayer p, AimController aim);

		void stop();

		void beforeTick(LocalPlayer p);
	}

	private static Legs impl;
	private static boolean tried;
	/** Parkour (ParkourModule): Baritone only jumps - it doesn't place or break a block. */
	public static boolean parkourOnly;

	private BaritoneLegs() {}

	/** Baritone is installed, switched on in the config, and ready. */
	public static boolean available() {
		if (!HumanAutopilot.config().useBaritone || !FabricLoader.getInstance().isModLoaded("baritone")) return false;
		return legs() != null;
	}

	private static Legs legs() {
		if (impl == null && !tried) {
			tried = true;
			try {
				impl = (Legs) Class.forName("dev.humanautopilot.pathing.BaritoneImpl").getDeclaredConstructor().newInstance();
				HumanAutopilot.LOGGER.info("[baritone] found Baritone: hard trips (bridges, pillars, jumps, far away) go to it");
			} catch (Throwable t) {
				HumanAutopilot.LOGGER.warn("[baritone] couldn't hook into Baritone, using only our own pathfinder", t);
			}
		}
		return impl;
	}

	static boolean go(Goal goal, boolean dig) {
		return available() && impl.go(goal, dig);
	}

	static Navigator.Status tick(LocalPlayer p, AimController aim) {
		return impl == null ? Navigator.Status.FAILED : impl.tick(p, aim);
	}

	static void stop() {
		if (impl != null) impl.stop();
	}

	/** Panic: whatever Baritone is doing for us, stop it now. */
	public static void stopAll() {
		if (impl != null) impl.stop();
	}

	/** Start of every client tick (before the player moves): lets the camera hook see what Baritone changes. */
	public static void beforeTick(LocalPlayer p) {
		if (impl != null) impl.beforeTick(p);
	}
}
