package dev.humanautopilot.util;

/** Smooth 1D noise from a few summed sine waves with random frequencies and phases. Output roughly in [-1, 1]. */
public final class Noise {
	private final double[] freq = new double[3];
	private final double[] phase = new double[3];
	private final double[] amp = {0.55, 0.3, 0.15};

	public Noise(double minHz, double maxHz) {
		for (int i = 0; i < 3; i++) {
			freq[i] = Human.range(minHz, maxHz) * (1 + i * 0.9);
			phase[i] = Human.range(0, Math.PI * 2);
		}
	}

	public double at(double seconds) {
		double v = 0;
		for (int i = 0; i < 3; i++) v += amp[i] * Math.sin(seconds * freq[i] * Math.PI * 2 + phase[i]);
		return v;
	}
}
