package dev.humanautopilot;

import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Noise;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * Moves the camera like a hand on a mouse: a slightly under-damped spring toward the goal
 * (so it overshoots a little and corrects), a speed cap, and a constant small wobble so the
 * crosshair is never pixel-perfect. Applied per rendered frame through the same turn call
 * that vanilla mouse movement uses.
 */
public final class AimController {
	private Supplier<Vec3> point;
	private double goalYaw, goalPitch;
	private boolean hasDirection;
	private Object targetKey;

	private double velYaw, velPitch;
	private double omega = 11, zeta = 0.7;
	private Noise wobbleYaw = new Noise(0.25, 0.9);
	private Noise wobblePitch = new Noise(0.25, 0.9);
	private double wobbleScale = 1.0;
	private double lastErr = 999;
	private long lastNanos;
	private boolean resting;
	/**
	 * How fast the goal itself is moving across the view (deg/s, smoothed): we're flying past the block we look at.
	 * A hand follows that smoothly (and a little ahead) instead of stopping, falling behind and catching up in jerks.
	 */
	private double goalRateYaw, goalRatePitch, lastGoalYaw = Double.NaN, lastGoalPitch;
	/** How hard (deg/s^2) a turn may swing round to the other way: ~45 ms to go from 30 deg/s one way to 30 the other. */
	private static final double REVERSE_ACCEL = 1400;
	/**
	 * Building (quick): a hop to a new goal is planned like a hand does it. A short pause first (the user's eyes stay
	 * on the block they just placed ~0.1 s; the bot's left it the very next frame: "he twitches every time he places a
	 * block"), then one smooth stroke (minimum jerk: eases in, eases out) timed from the user's recordings.
	 */
	private double hopHoldUntil = -1, heldYaw, heldPitch;
	private boolean hopping;
	private double hopT0, hopT, hopOffYaw, hopOffPitch, hopA1Yaw, hopA1Pitch;
	/** Clicked on the way in: the stroke finishes on the old goal (heldYaw/heldPitch), then the pause, then the next. */
	private boolean finishing;
	/**
	 * The goal point and our eye last frame. A new goal is the POINT changing (the next block), not the angle moving
	 * fast: a point a block away swings across the view at 200+ deg/s as we walk past it (walking through a doorway),
	 * and taking that for new goals every frame restarted the hop every frame.
	 */
	private Vec3 lastGoalPoint, lastEye;
	/** Ticks nobody asked for the camera: a tick or two is a gap in the work, not letting go (it restarted strokes). */
	private int untouched;
	public static long hops;
	/**
	 * The camera's own clock (seconds). It runs with the frames, but a long frame (the game held up by a tick's thinking)
	 * only counts as a little longer than a usual one, and the time a menu was open doesn't count at all. Strokes, pauses
	 * and the wobble go by it, so after a hitch the view carries on from where it was instead of jumping to where it
	 * would have been by then.
	 */
	private double camT;
	/** The last few frame times (what a usual frame is right now: their median, so a hitch doesn't count). */
	private final double[] recentDt = new double[15], sortedDt = new double[15];
	private int recentI, recentN;
	/** Frames back after a gap (a menu was open, the game paused): like a hand coming back to the mouse. */
	public static long gaps;

	/** A hop to a new goal is under way (the pause before it, or the stroke): the crosshair isn't there yet. */
	public boolean busy() {
		double t = camT;
		return hopping || finishing || (hopHoldUntil > 0 && t < hopHoldUntil);
	}

	/** How long a usual frame is just now (seconds). */
	private double usualFrame() {
		if (recentN == 0) return 0.016;
		System.arraycopy(recentDt, 0, sortedDt, 0, recentN);
		java.util.Arrays.sort(sortedDt, 0, recentN);
		return sortedDt[recentN / 2];
	}

	/**
	 * How long a hand takes for a hop of {@code d} degrees (the user: ~0.19 s for 20, 0.28 for 45, 0.34 for 90, 0.39
	 * for 160: peak speed ~1.9 d / T).
	 */
	private static double hopTime(double d) {
		double log2 = Math.log(Math.max(d, 1)) / Math.log(2);
		// (Building, the user peaks ~160 deg/s on the ~16 degree hop to the next block: 0.19 s.)
		return Math.clamp(0.21 + 0.0667 * (log2 - 4.32), 0.1, 0.47) * Human.range(0.9, 1.15);
	}

	/** Start a stroke from where the view is now to the goal, carrying on at the speed it has (relative to the goal). */
	private void planHop(double t, double viewYaw, double viewPitch, double goalYaw, double goalPitch, double relVelYaw,
						 double relVelPitch) {
		hopOffYaw = Angles.wrap(viewYaw - goalYaw);
		hopOffPitch = viewPitch - goalPitch;
		double d = Math.hypot(hopOffYaw * Math.cos(Math.toRadians(goalPitch)), hopOffPitch);
		hopT = hopTime(d);
		hopT0 = t;
		// Quintic from the offset to 0: position, speed (as it is now) and acceleration (0) matched at the start, 0 at the end.
		hopA1Yaw = relVelYaw * hopT;
		hopA1Pitch = relVelPitch * hopT;
		hopping = true;
		hops++;
	}

	/** Where the stroke is at fraction {@code u} (0..1), as an offset from the goal (degrees). */
	private static double hopOff(double off0, double a1, double u) {
		double dd = -off0, a3 = 10 * dd - 6 * a1, a4 = -15 * dd + 8 * a1, a5 = 6 * dd - 3 * a1;
		return off0 + a1 * u + a3 * u * u * u + a4 * u * u * u * u + a5 * u * u * u * u * u;
	}

	/** Tests only: the camera as it was before 2026-10-14 (aimed from the tick position, stop-and-go), to compare. */
	public static boolean oldCamera = System.getenv("HA_OLDCAM") != null;
	/**
	 * Shake meter: frames where the view turns (> 20 deg/s), and of those, the ones where it turns back the other way
	 * from the frame before (both > 15 deg/s). The user's own building: ~0.1% of turning frames.
	 */
	public static long turningFrames, flipFrames;
	private float meterYaw = Float.NaN, meterPitch;
	private double meterRateYaw, meterRatePitch;

	/** Aim at a moving world point. {@code key} identifies the target so a new one gets a fresh reaction curve. */
	public void lookAt(Object key, Supplier<Vec3> worldPoint) {
		touched = true;
		smallHop = isSmallHop(worldPoint);
		if (!accept(key)) return;
		point = worldPoint;
		hasDirection = false;
		pointGlides = false;
	}

	/**
	 * Aim at a point that glides along by itself (the eyes going slowly across a build): its own motion counts as the
	 * goal moving, to follow smoothly. (For an ordinary point only our own motion does - a block's face point shifting
	 * isn't motion - so a slow pan went rest, fall behind, catch up, rest: "the camera keeps nudging to the left".)
	 */
	public void lookAtGliding(Object key, Supplier<Vec3> worldPoint) {
		lookAt(key, worldPoint);
		if (point == worldPoint) pointGlides = true;
	}

	private boolean pointGlides;

	public void lookAt(Vec3 p) {
		lookAt(net.minecraft.core.BlockPos.containing(p), () -> p);
	}

	/** Aim in a fixed direction (degrees). */
	public void lookDirection(Object key, double yaw, double pitch) {
		touched = true;
		var pl = net.minecraft.client.Minecraft.getInstance().player;
		smallHop = pl != null && Math.hypot(Angles.wrap(yaw - pl.getYRot()), pitch - pl.getXRot()) < 30;
		if (!accept(key)) return;
		point = null;
		hasDirection = true;
		goalYaw = yaw;
		goalPitch = pitch;
	}

	private boolean touched;
	private boolean smallHop;

	/** The new target is within ~30 degrees of where we're looking now. */
	private static boolean isSmallHop(Supplier<Vec3> p) {
		var pl = net.minecraft.client.Minecraft.getInstance().player;
		if (pl == null) return false;
		Vec3 t = p.get();
		if (t == null) return false;
		Vec3 eye = pl.getEyePosition();
		return Math.hypot(Angles.wrap(Angles.yawTo(eye, t) - pl.getYRot()), Angles.pitchTo(eye, t) - pl.getXRot()) < 30;
	}
	private boolean urgent;
	private boolean urgentTurn; // stays on for the current target once it was urgent
	private long lastSwitch;
	private int dwellTicks = 5;

	private boolean quick, quickSet;

	/**
	 * Quick hand this tick (box filling): short, firm flicks that land and stop, like the recorded player
	 * placing blocks (~26 degrees in ~0.2 s, 270 flicks a minute), instead of the calm walking camera.
	 */
	public void setQuick(boolean q) {
		quick = q;
		quickSet = true;
	}

	/** Danger / a fight this tick: the eyes may jump to the new thing at once. */
	public void setUrgent(boolean u) {
		urgent = u;
		if (u) urgentTurn = true;
	}

	/**
	 * Attention dwell: a person keeps looking at one thing for at least a moment before their eyes
	 * move on. A new target only takes over once the current one has held for ~250-400 ms.
	 */
	private boolean accept(Object key) {
		if (java.util.Objects.equals(key, targetKey)) return true;
		long now = HumanAutopilot.ticks();
		if (!urgent && targetKey != null && (point != null || hasDirection) && now - lastSwitch < dwellTicks) {
			audit(kindOf(targetKey) + " vs " + kindOf(key), denied);
			deniedCount++;
			return false;
		}
		String kind = kindOf(key);
		switchCount++;
		if (kind.equals(prevKind) && !kind.equals(lastKind) && now - prevSwitchAt < 30) {
			pingPongCount++;
			audit(lastKind.compareTo(kind) < 0 ? lastKind + " <-> " + kind : kind + " <-> " + lastKind, pingPong);
		}
		prevKind = lastKind;
		prevSwitchAt = lastSwitchAt;
		lastKind = kind;
		lastSwitchAt = now;
		// A small hop to something right next to what we were looking at (the next block in a staircase, the
		// next step of the path) is the same hand carrying on, not a fresh movement with a new feel.
		if (!smallHop || !(point != null || hasDirection)) retune();
		else resting = false;
		targetKey = key;
		lastSwitch = now;
		dwellTicks = Human.rangeInt(5, 8);
		return true;
	}

	// ---- Audit: the eyes ping-ponging between two things (A -> B -> A within 1.5 s), or two things asking at once.
	private static long switchCount, pingPongCount, deniedCount;
	private static final java.util.Map<String, Integer> pingPong = new java.util.HashMap<>(), denied = new java.util.HashMap<>();
	private String lastKind = "", prevKind = "";
	private long lastSwitchAt, prevSwitchAt;

	private static void audit(String pair, java.util.Map<String, Integer> map) {
		map.merge(pair, 1, Integer::sum);
	}

	/** What kind of thing a target key is ("nav", "tunnel", "block", "entity"...). */
	private static String kindOf(Object key) {
		if (key == null) return "none";
		if (key instanceof String str) return str;
		if (key instanceof net.minecraft.core.BlockPos) return "block";
		if (key instanceof net.minecraft.world.entity.Entity e) return "entity:" + e.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
		if (key instanceof Vec3) return "point";
		return key.getClass().getSimpleName();
	}

	/** One line for the test's motion meter (and resets): switches, ping-pongs, clashes, and the worst pairs. */
	public static String auditLine(double minutes) {
		String line = String.format(java.util.Locale.ROOT, "aimSwitches/min=%.0f pingPong/min=%.1f clashes/min=%.0f%s%s",
				switchCount / minutes, pingPongCount / minutes, deniedCount / minutes, top(" pp:", pingPong), top(" clash:", denied));
		switchCount = pingPongCount = deniedCount = 0;
		pingPong.clear();
		denied.clear();
		return line;
	}

	private static String top(String label, java.util.Map<String, Integer> map) {
		if (map.isEmpty()) return "";
		StringBuilder b = new StringBuilder(label);
		map.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(3)
				.forEach(e -> b.append(" [").append(e.getKey()).append("]x").append(e.getValue()));
		return b.toString();
	}

	/** Start of a game tick: whoever wants the camera this tick must ask for it again. */
	public void beginTick() {
		touched = false;
		urgent = false;
		quickSet = false;
	}

	/** End of a game tick: if nobody aimed this tick, let the camera rest (but remember the last target). */
	public void endTick() {
		// (Quick unless whoever aimed this tick didn't ask for it. A tick or two with nobody aiming keeps the hand it had:
		// those frames went on with the same goal as the calm walking spring - coming back from the creative menu, the
		// spring did half a 70-degree turn in one frame.)
		if (touched && !quickSet) quick = false;
		if (touched) {
			untouched = 0;
		} else if (++untouched > 2) {
			point = null;
			hasDirection = false;
		}
	}

	public void clear() {
		untouched = 99;
		point = null;
		hasDirection = false;
		targetKey = null;
	}

	/** Settled a few degrees off but that isn't good enough: make the small correction now. */
	public void wake() {
		if (resting) {
			resting = false;
			needScale = true;
		}
	}

	public boolean active() {
		return point != null || hasDirection;
	}

	/** Angular distance to the goal at the last frame, in degrees. */
	public double error() {
		return lastErr;
	}

	private boolean needScale = true;
	private double moveSize = 0, moveScale = 1;

	private void retune() {
		resting = false;
		needScale = true;
		urgentTurn = urgent;
		var c = HumanAutopilot.config();
		omega = Human.range(c.aimSpeedMin, c.aimSpeedMax);
		zeta = Human.range(0.55, 0.8); // < 1 gives a small overshoot
		wobbleYaw = new Noise(0.25, 0.9);
		wobblePitch = new Noise(0.25, 0.9);
		wobbleScale = Human.range(0.6, 1.2);
	}

	/** Called once per frame from the mouse handler. */
	public void frame(LocalPlayer player) {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0.016 : (now - lastNanos) / 1e9;
		lastNanos = now;
		boolean gap = !oldCamera && dt > 0.25;
		if (gap) {
			// No frames for a while: a menu was open (frame() isn't called then) or the game was paused. Nothing of the
			// move before carries on. (The first frame after the creative menu closed was worked out over the whole
			// 0.1 s clamp, with the goal and the speeds from before the menu: half of a 70-degree turn in one 14 ms frame,
			// then easing out - a snap each time it came back from the menu, ~70 an hour in the user's building: "the
			// camera is kinda snappy and not smooth".)
			gaps++;
			velYaw = velPitch = 0;
			lastGoalYaw = Double.NaN;
			lastGoalPoint = lastEye = null;
			goalRateYaw = goalRatePitch = 0;
			hopping = false;
			finishing = false;
			resting = false;
			needScale = true;
			// (A hand comes back to the mouse a moment after the menu shuts, then moves.)
			hopHoldUntil = camT + Human.gauss(0.06, 0.16);
			dt = usualFrame();
		}
		// Up to 0.1 s: in the background the game drops to ~10 FPS, and a smaller clamp would halve the turn speed
		// (the spring is integrated in small substeps, so long frames are fine).
		dt = Math.clamp(dt, 0.001, 0.1);
		// How far the camera goes on in this frame: a usual frame's worth at most (a little over), so a hitch is a short
		// pause, not a jump to catch up. (The median of the last frames: at a steady low frame rate that's the frame
		// itself, so the turns don't slow down.)
		double step = oldCamera ? dt : Math.min(dt, Math.max(0.012, usualFrame() * 1.6));
		if (!gap) {
			recentDt[recentI] = dt;
			recentI = (recentI + 1) % recentDt.length;
			if (recentN < recentDt.length) recentN++;
		}
		camT += step;
		// The meter: how the view actually moved since the last frame (what a recording sees).
		if (!Float.isNaN(meterYaw)) {
			double ry = Angles.wrap(player.getYRot() - meterYaw) / dt, rp = (player.getXRot() - meterPitch) / dt;
			if (Math.hypot(ry, rp) > 20 || Math.hypot(meterRateYaw, meterRatePitch) > 20) {
				turningFrames++;
				if ((Math.abs(ry) > 15 && Math.abs(meterRateYaw) > 15 && ry * meterRateYaw < 0)
						|| (Math.abs(rp) > 15 && Math.abs(meterRatePitch) > 15 && rp * meterRatePitch < 0)) flipFrames++;
			}
			meterRateYaw = ry;
			meterRatePitch = rp;
		}
		meterYaw = player.getYRot();
		meterPitch = player.getXRot();
		if (!active()) {
			velYaw *= 0.8;
			velPitch *= 0.8;
			lastGoalYaw = Double.NaN;
			lastGoalPoint = lastEye = null;
			goalRateYaw = goalRatePitch = 0;
			hopping = false;
			finishing = false;
			hopHoldUntil = -1;
			return;
		}

		double yaw, pitch;
		Vec3 eye = null, goalPoint = null;
		if (point != null) {
			// From where the camera is THIS frame (between two ticks), not where the last tick left us: flying along, the
			// tick position jumps 20 times a second, and the goal with it (the little shake the user saw).
			float pt = net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
			eye = oldCamera ? player.getEyePosition() : player.getEyePosition(pt);
			goalPoint = point.get();
			if (goalPoint == null) return;
			yaw = Angles.yawTo(eye, goalPoint);
			pitch = Angles.pitchTo(eye, goalPoint);
		} else {
			yaw = goalYaw;
			pitch = goalPitch;
		}
		// How fast the goal moves (a jump of more than a few degrees in one frame is a new goal, not motion).
		double t = camT;
		boolean planned = quick && !oldCamera && !urgentTurn;
		// A slow, small drift (a hand is never perfectly still), not a constant shake. (Worked out once per frame and
		// used the same way everywhere: a stroke planned without it jumped by it on its first frame.)
		double amp = HumanAutopilot.config().aimWobbleDegrees * 0.3 * wobbleScale;
		double wobY = wobbleYaw.at(t) * amp, wobP = wobblePitch.at(t) * amp * 0.6;
		if (!planned && hopHoldUntil > 0 && t >= hopHoldUntil) hopHoldUntil = -1; // (the pause after a gap: done)
		if (!planned && hopHoldUntil > 0 && t < hopHoldUntil) {
			// The calm hand back from a menu: a moment still before it moves.
			lastGoalYaw = yaw;
			lastGoalPitch = pitch;
			lastGoalPoint = goalPoint;
			lastEye = eye;
			lastErr = Math.hypot(Angles.wrap(yaw - player.getYRot()), pitch - player.getXRot());
			return;
		}
		if (planned && Double.isNaN(lastGoalYaw) && !hopping && hopHoldUntil < 0) {
			// Starting to look at something: straight into a stroke (no pause: nothing was being looked at).
			planHop(t, player.getYRot(), player.getXRot(), yaw + wobY, pitch + wobP, 0, 0);
		}
		if (!Double.isNaN(lastGoalYaw)) {
			double gy, gp;
			boolean newGoal;
			if (goalPoint != null && lastGoalPoint != null && lastEye != null && pointGlides) {
				// A gliding point: all of its motion across the view (ours and its own), like a direction.
				gy = Angles.wrap(yaw - lastGoalYaw) / dt;
				gp = (pitch - lastGoalPitch) / dt;
				newGoal = Math.abs(gy) * dt > 1.5 || Math.abs(gp) * dt > 1.5;
			} else if (goalPoint != null && lastGoalPoint != null && lastEye != null) {
				// A point: new if the point itself moved (the next block); its motion across the view is the SAME point
				// seen from here vs from last frame's eye (the point's own little shifts on a face aren't motion).
				newGoal = goalPoint.distanceToSqr(lastGoalPoint) > 0.3 * 0.3;
				gy = Angles.wrap(Angles.yawTo(eye, lastGoalPoint) - Angles.yawTo(lastEye, lastGoalPoint)) / dt;
				gp = (Angles.pitchTo(eye, lastGoalPoint) - Angles.pitchTo(lastEye, lastGoalPoint)) / dt;
			} else {
				// A direction: a hop of more than ~1.5 degrees in one frame is a new one (taken as motion it gave a
				// 100+ deg/s shove for a tenth of a second, then the correction back: a twitch).
				gy = Angles.wrap(yaw - lastGoalYaw) / dt;
				gp = (pitch - lastGoalPitch) / dt;
				newGoal = Math.abs(gy) * dt > 1.5 || Math.abs(gp) * dt > 1.5;
			}
			if (newGoal) {
				goalRateYaw = goalRatePitch = 0;
				if (planned) {
					if (hopping && !finishing) {
						// Clicked on the way in (like the user: still moving when they click): the stroke carries on to
						// where it was going, the hand rests a moment, and only then goes on to the new goal. (Turning
						// mid-stroke straight to the next block was a swing out and back: the twitch.) Where it was going
						// AT the click, as angles: the hand finishes its move on the mouse, it doesn't keep following
						// the block as we fly on (that kept the view creeping after every click).
						finishing = true;
						heldYaw = lastGoalYaw;
						heldPitch = lastGoalPitch;
					} else if (!hopping && hopHoldUntil < 0) {
						// The hand rests a moment (the block just placed), then moves on.
						hopHoldUntil = t + Human.gauss(0.03, 0.11); // (the user moves on ~115 ms after the click, the frame lag included)
					}
					// (Already pausing, or finishing a stroke: the next stroke goes to the newest goal.)
				}
			} else {
				// Smoothed over ~80 ms picking up, but dropping at once when the goal slows (the lag carried the
				// camera on past a goal that had stopped, then back).
				double a = Math.min(1, dt / 0.08);
				goalRateYaw += (gy - goalRateYaw) * (Math.abs(gy) < Math.abs(goalRateYaw) ? 1 : a);
				goalRatePitch += (gp - goalRatePitch) * (Math.abs(gp) < Math.abs(goalRatePitch) ? 1 : a);
			}
		}
		lastGoalYaw = yaw;
		lastGoalPitch = pitch;
		lastGoalPoint = goalPoint;
		lastEye = eye;
		if (planned) {
			if (hopping) {
				// The stroke: exactly along the planned curve, relative to its goal (so it ends on it even as we drift;
				// the old goal when it's finishing after a click).
				double sy = finishing ? heldYaw : yaw, sp = finishing ? heldPitch : pitch;
				double u = (t - hopT0) / hopT;
				if (u < 1) {
					double wantYaw = sy + wobY + hopOff(hopOffYaw, hopA1Yaw, u);
					double wantPitch = Math.clamp(sp + wobP + hopOff(hopOffPitch, hopA1Pitch, u), -90, 90);
					lastErr = Math.hypot(Angles.wrap(yaw - player.getYRot()), pitch - player.getXRot());
					resting = false;
					// Looking (nearly) straight down or up, the yaw of a point under us swings wildly with every few cm
					// we drift, and hardly moves the view: turn it only as much as it shows (the tracker does the same).
					// And never faster than a hand (900 deg/s).
					double yw = Math.clamp(Math.cos(Math.toRadians(Math.max(Math.abs(wantPitch), Math.abs(player.getXRot())))) / 0.5, 0.1, 1);
					// (Not more than 45 degrees in one frame, however long the frame: after a hitch (a world save, the
					// window in the background) the stroke caught up at once, 80 degrees in one tick.)
					double cap = 900 * step;
					double dY = Math.clamp(Angles.wrap(wantYaw - player.getYRot()) * yw, -cap, cap);
					double dP = Math.clamp(wantPitch - player.getXRot(), -cap, cap);
					player.turn(dY / 0.15, dP / 0.15);
					return;
				}
				hopping = false; // there: the tracker carries on (following the goal, small corrections)
				velYaw = velPitch = 0;
				if (finishing) {
					finishing = false;
					// The rest after the click, a little shorter (part of it went by on the way in).
					hopHoldUntil = t + Human.gauss(0.02, 0.08);
				}
			}
			if (hopHoldUntil > 0) {
				if (t < hopHoldUntil) {
					// The pause: the hand rests on the mouse and the view doesn't move at all. (The user's camera is dead
					// still for over half of the 100 ms after a click; ours kept tracking the block just placed as we flew
					// on, and the wobble and the next block's motion moved it too: 2-3 degrees of creep after every click,
					// "he still twitches after he places a block".)
					velYaw = velPitch = 0;
					lastErr = Math.hypot(Angles.wrap(yaw - player.getYRot()), pitch - player.getXRot());
					return;
				}
				hopHoldUntil = -1;
				double d1 = Math.hypot(Angles.wrap(yaw + wobY - player.getYRot()), pitch + wobP - player.getXRot());
				if (d1 > 1) {
					planHop(t, player.getYRot(), player.getXRot(), yaw + wobY, pitch + wobP, 0, 0);
					lastErr = d1;
					return; // (the stroke takes over from the next frame)
				}
			}
		}
		yaw += wobY;
		pitch += wobP;
		pitch = Math.clamp(pitch, -90, 90);

		double errYaw = Angles.wrap(yaw - player.getYRot());
		double errPitch = pitch - player.getXRot();
		// Looking (nearly) straight up or down, yaw hardly matters: a block overhead is "in view" from any heading,
		// and a target a few cm off-axis would otherwise swing the yaw wildly back and forth. Weigh yaw by how far
		// it actually moves the view (cos of the pitch), like real angular distance on screen.
		double yawWeight = Math.max(0.05, Math.cos(Math.toRadians(Math.max(Math.abs(pitch), Math.abs(player.getXRot())))));
		errYaw *= yawWeight;
		lastErr = Math.sqrt(errYaw * errYaw + errPitch * errPitch);

		// Deadzone: once roughly on target, leave the mouse alone until the error grows again.
		double speedNow = Math.hypot(velYaw, velPitch);
		// Quick hand (box filling): a small dead zone, so it lands ON the face instead of a few degrees off it.
		double wakeAt = quick ? 1.5 : 4.0, restAt = quick ? 0.5 : 1.2;
		// Following a goal that's moving (flying past it): no resting, the hand keeps gliding with it (stopping at half
		// a degree and starting again at one and a half was a stutter, several times a second).
		// (Not only when building: the slow look across the build after stepping back went in 4-degree nudges - rest,
		// fall behind, catch up, rest - "when he flies out to look the camera keeps nudging to the left". Eyes follow a
		// slowly moving thing smoothly.)
		boolean following = !oldCamera && Math.hypot(goalRateYaw * yawWeight, goalRatePitch) > (quick ? 3 : 1.5);
		if (resting) {
			if (lastErr < wakeAt && !following) {
				velYaw = velPitch = 0;
				return;
			}
			resting = false;
			needScale = !following; // a new movement starts: size it
		} else if (lastErr < restAt && speedNow < 15 && !following) {
			resting = true;
			velYaw = velPitch = 0;
			return;
		}

		// Like a hand: a big turn isn't just a small one scaled up. The recorded player peaks ~200 deg/s on a 20-degree
		// turn, ~300 on 45, ~500 on 90, ~770 on 160+: the hand slows down relative to how far it has to go.
		if (needScale || lastErr > moveSize * 1.6) {
			moveSize = lastErr;
			moveScale = Math.clamp(Math.pow(Math.max(lastErr, 20) / 20.0, -0.5), 0.35, 1.0);
			needScale = false;
		}
		// quick (box filling): firmer than walking, but big turns still a bit slower, like the recorded player
		// (peaks ~260 deg/s typical, ~500 at the fast end).
		// quick: faster than before but eased (well damped: glides in and settles, no snap-and-stop).
		double w = omega * (quick ? 2.1 * Math.max(moveScale, 0.6) : moveScale);
		double z = quick ? Math.max(zeta, 0.95) : zeta;
		// deg/s: big flicks may be quick (the recorded player peaks ~250 typically, ~850 at the fast end);
		// danger or a fight allows a bit more.
		double max = urgentTurn ? 950 : quick ? 560 : 700;
		// Quick hand: also a limit on how fast the turn speeds up / slows down (deg/s^2), so every turn eases in and
		// out like a hand on a mouse instead of snapping (the user: "faster, but not snappy"). ~0.2 s for 30 degrees.
		double maxAccel = quick ? 3800 : Double.MAX_VALUE; // the recorded player's fast turns top out ~700-900 deg/s
		// Spring-damper: accel = w^2 * err - 2*zeta*w * vel, in small steps: one big step per frame at a low
		// frame rate overshoots back and forth (the camera "flickers") once the spring is stiff.
		int steps = Math.max(1, (int) Math.ceil(step / 0.004));
		double h = step / steps, dYaw = 0, dPitch = 0;
		for (int i = 0; i < steps; i++) {
			if (quick) {
				// Eased motion profile: speed up at most maxAccel, cruise, and start slowing in time to arrive without
				// overshooting (a clamped spring overshoots). Smooth both ends, still quick.
				double ey = errYaw - dYaw, ep = errPitch - dPitch, dist = Math.hypot(ey, ep);
				double want = dist < 1e-6 ? 0 : Math.min(max, Math.sqrt(2 * maxAccel * dist) * 0.9);
				// Moving with the goal (pursuit), plus closing what's left of the gap.
				double ff = oldCamera ? 0 : 0.7; // (most of the goal's motion; the correction does the rest, no overshoot)
				double wy = ff * goalRateYaw * yawWeight + (dist < 1e-6 ? 0 : ey / dist * want);
				double wp = ff * goalRatePitch + (dist < 1e-6 ? 0 : ep / dist * want);
				double dvy = wy - velYaw, dvp = wp - velPitch, dv = Math.hypot(dvy, dvp), lim = maxAccel * h;
				if (dv > lim) {
					dvy *= lim / dv;
					dvp *= lim / dv;
				}
				// Turning back the other way (on to the next block, right after a click): a hand eases to a stop first,
				// it doesn't flick from one way to the other between two frames (2/3 of the little shakes the user saw).
				// (A fast turn may still brake hard: only the small, slow swings are eased.)
				if (!oldCamera) {
					double revY = Math.max(REVERSE_ACCEL, Math.abs(velYaw) * 12) * h, revP = Math.max(REVERSE_ACCEL, Math.abs(velPitch) * 12) * h;
					if (velYaw * wy < 0 && Math.abs(dvy) > revY) dvy = Math.copySign(revY, dvy);
					if (velPitch * wp < 0 && Math.abs(dvp) > revP) dvp = Math.copySign(revP, dvp);
				}
				velYaw += dvy;
				velPitch += dvp;
			} else {
				// (Following a moving goal: damped toward its speed, not toward standing still - no lag-and-catch-up.)
				double fy = following ? goalRateYaw * yawWeight : 0, fp = following ? goalRatePitch : 0;
				velYaw += (w * w * (errYaw - dYaw) + 2 * z * w * (fy - velYaw)) * h;
				velPitch += (w * w * (errPitch - dPitch) + 2 * z * w * (fp - velPitch)) * h;
			}
			double speed = Math.hypot(velYaw, velPitch);
			if (speed > max) {
				velYaw *= max / speed;
				velPitch *= max / speed;
			}
			dYaw += velYaw * h;
			dPitch += velPitch * h;
		}
		// Entity.turn multiplies by 0.15, same as mouse input.
		player.turn(dYaw / 0.15, dPitch / 0.15);
	}
}
