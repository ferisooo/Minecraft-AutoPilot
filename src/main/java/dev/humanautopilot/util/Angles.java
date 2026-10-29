package dev.humanautopilot.util;

import net.minecraft.world.phys.Vec3;

public final class Angles {
	private Angles() {}

	public static double wrap(double deg) {
		deg %= 360.0;
		if (deg >= 180.0) deg -= 360.0;
		if (deg < -180.0) deg += 360.0;
		return deg;
	}

	public static double yawTo(Vec3 from, Vec3 to) {
		return Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0;
	}

	public static double pitchTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		return -Math.toDegrees(Math.atan2(to.y - from.y, Math.sqrt(dx * dx + dz * dz)));
	}
}
