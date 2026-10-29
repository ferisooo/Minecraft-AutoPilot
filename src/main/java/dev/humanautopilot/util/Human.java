package dev.humanautopilot.util;

import dev.humanautopilot.HumanAutopilot;

import java.util.Random;

/** Randomness that makes timing look like a person: delays, reaction time, pauses. */
public final class Human {
	public static final Random RNG = new Random();

	private Human() {}

	public static double range(double min, double max) {
		return min + RNG.nextDouble() * (max - min);
	}

	public static int rangeInt(int min, int max) {
		return min + RNG.nextInt(Math.max(1, max - min + 1));
	}

	public static boolean chance(double p) {
		return RNG.nextDouble() < p;
	}

	/** Gaussian clamped to [min, max], centered between them. Humans cluster around a typical value. */
	public static double gauss(double min, double max) {
		double mid = (min + max) / 2.0;
		double sd = (max - min) / 4.0;
		return Math.clamp(mid + RNG.nextGaussian() * sd, min, max);
	}

	private static int msToTicks(double ms) {
		// Round randomly so 150ms is sometimes 3 ticks, sometimes 4.
		double t = ms / 50.0;
		int whole = (int) Math.floor(t);
		return whole + (RNG.nextDouble() < t - whole ? 1 : 0);
	}

	/** Small gap between consecutive actions (switch item -> start using, etc). */
	public static int actionDelay() {
		var c = HumanAutopilot.config();
		return msToTicks(gauss(c.actionDelayMinMs, c.actionDelayMaxMs));
	}

	/** Time to "notice" something new, like a mob appearing. */
	public static int reaction() {
		var c = HumanAutopilot.config();
		return msToTicks(gauss(c.reactionMinMs, c.reactionMaxMs));
	}

	public static int secondsToTicks(double s) {
		return (int) Math.round(s * 20.0);
	}
}
