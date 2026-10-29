package dev.humanautopilot.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.RotationMoveEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.util.Angles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The only class that talks to Baritone (loaded by {@link BaritoneLegs} only when Baritone is installed).
 *
 * <ul>
 *   <li>Settings for a person, not a bot: no "free look" (the real camera turns), no path lines or chat spam,
 *       bridges, pillars and parkour allowed, falls no deeper than ours.</li>
 *   <li>Camera: each tick Baritone sets the view it wants; we put the real view back and hand that direction to
 *       our {@link AimController}, which turns there like a hand would. Blocks are only placed or broken once
 *       the real crosshair is on them.</li>
 *   <li>A pause "process": whenever the autopilot isn't walking this trip (eating, fighting, a break, a look at a
 *       noise), Baritone holds still and picks up where it left off.</li>
 * </ul>
 */
final class BaritoneImpl implements BaritoneLegs.Legs {
	private final IBaritone b;
	private boolean driving;
	private long lastDrive, started, lastEventAt, progressAt;
	private PathEvent lastEvent;
	private int calcFails;
	private Vec3 progressPos;
	private float preYaw, prePitch;
	private boolean havePre;
	private double wantYaw = Double.NaN, wantPitch;
	private long wantAt;
	private AimController aim;

	BaritoneImpl() {
		b = BaritoneAPI.getProvider().getPrimaryBaritone();
		configure(BaritoneAPI.getSettings());
		b.getPathingControlManager().registerProcess(new Hold());
		b.getGameEventHandler().registerEventListener(new Hooks());
	}

	private static void configure(Settings s) {
		var cfg = HumanAutopilot.config();
		// The real camera turns (no packet-only looking), and nothing a player wouldn't see.
		s.freeLook.value = false;
		s.blockFreeLook.value = false;
		s.smoothLook.value = false;
		s.randomLooking.value = 0.0;
		s.randomLooking113.value = 0.0;
		s.antiCheatCompatibility.value = true;
		s.renderPath.value = false;
		s.renderGoal.value = false;
		s.renderSelectionBoxes.value = false;
		s.renderCachedChunks.value = false;
		s.chatControl.value = false;
		s.chatDebug.value = false;
		s.logAsToast.value = false;
		s.desktopNotifications.value = false;
		s.logger.value = c -> HumanAutopilot.LOGGER.info("[baritone] {}", c.getString());
		// What we hand it Baritone for.
		s.allowSprint.value = true;
		s.allowPlace.value = true;
		s.allowParkour.value = true;
		s.allowParkourAscend.value = true;
		s.allowParkourPlace.value = false; // no mid-air placing tricks
		s.allowWaterBucketFall.value = false;
		s.allowInventory.value = false;
		s.maxFallHeightNoWater.value = Math.max(2, cfg.maxSafeDrop);
	}

	@Override
	public boolean go(Goal goal, boolean dig) {
		baritone.api.pathing.goals.Goal g = convert(goal);
		if (g == null) return false;
		// (Parkour: the course is to be jumped, not built on or dug through.)
		BaritoneAPI.getSettings().allowBreak.value = !BaritoneLegs.parkourOnly;
		BaritoneAPI.getSettings().allowPlace.value = !BaritoneLegs.parkourOnly;
		b.getCustomGoalProcess().setGoalAndPath(g);
		long now = HumanAutopilot.ticks();
		driving = true;
		lastDrive = started = progressAt = now;
		lastEvent = null;
		calcFails = 0;
		progressPos = null;
		wantYaw = Double.NaN;
		HumanAutopilot.LOGGER.info("[baritone] heading for {} ({})", goal.describe(), g);
		return true;
	}

	@Override
	public Navigator.Status tick(LocalPlayer p, AimController aim) {
		this.aim = aim;
		long now = HumanAutopilot.ticks();
		if (!driving) return Navigator.Status.FAILED;
		lastDrive = now;
		// The camera: wherever Baritone last wanted to look, turned to by our hand.
		if (!Double.isNaN(wantYaw) && now - wantAt < 20) {
			aim.setUrgent(true);
			aim.lookDirection("baritone", wantYaw, wantPitch);
		}
		if (lastEvent == PathEvent.AT_GOAL) {
			stop();
			return Navigator.Status.ARRIVED;
		}
		if (lastEvent == PathEvent.CALC_FAILED && lastEventAt >= now - 1) {
			lastEvent = null;
			if (++calcFails >= 3) {
				HumanAutopilot.LOGGER.info("[baritone] no path found either");
				stop();
				return Navigator.Status.FAILED;
			}
		}
		if (!b.getCustomGoalProcess().isActive() && now - started > 10) {
			// Finished on its own: there (as far as it can tell) or given up.
			driving = false;
			return lastEvent == PathEvent.AT_GOAL ? Navigator.Status.ARRIVED : Navigator.Status.FAILED;
		}
		// Watchdog: no real progress in 30 s of driving means it's stuck.
		if (progressPos == null || p.position().distanceTo(progressPos) > 1.5) {
			progressPos = p.position();
			progressAt = now;
		} else if (now - progressAt > 20 * 30) {
			HumanAutopilot.LOGGER.info("[baritone] no progress for 30 s, giving up");
			stop();
			return Navigator.Status.FAILED;
		}
		return b.getPathingBehavior().isPathing() ? Navigator.Status.MOVING : Navigator.Status.SEARCHING;
	}

	@Override
	public void stop() {
		if (driving) b.getPathingBehavior().cancelEverything();
		driving = false;
		wantYaw = Double.NaN;
	}

	@Override
	public void beforeTick(LocalPlayer p) {
		preYaw = p.getYRot();
		prePitch = p.getXRot();
		havePre = true;
	}

	/** Our goals in Baritone's terms (a little tighter, so "there" for Baritone is "there" for us too). */
	private static baritone.api.pathing.goals.Goal convert(Goal g) {
		if (g instanceof Goal.Block bl) return new GoalBlock(bl.gx(), bl.gy(), bl.gz());
		if (g instanceof Goal.XZ xz) return new GoalXZ(xz.gx(), xz.gz());
		if (g instanceof Goal.NearXZ n) return new GoalXZ(n.gx(), n.gz());
		if (g instanceof Goal.Near n) {
			return new GoalNear(BlockPos.containing(n.target()), (int) Math.max(1, Math.floor(n.radius() - (n.fromEyes() ? 1.2 : 0.5))));
		}
		if (g instanceof Goal.Beside bs) return new GoalNear(bs.block(), (int) Math.max(1, Math.floor(bs.radius() - 1.2)));
		return null; // "the surface" and the like: no simple Baritone goal for those
	}

	/** Baritone pauses whenever the autopilot isn't walking this trip right now. */
	private final class Hold implements IBaritoneProcess {
		@Override
		public boolean isActive() {
			return driving && HumanAutopilot.ticks() - lastDrive > 1;
		}

		@Override
		public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
			return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
		}

		@Override
		public boolean isTemporary() {
			return true;
		}

		@Override
		public void onLostControl() {
		}

		@Override
		public double priority() {
			return 1000;
		}

		@Override
		public String displayName0() {
			return "Human Autopilot (holding still)";
		}
	}

	private final class Hooks implements AbstractGameEventListener {
		@Override
		public void onPlayerUpdate(PlayerUpdateEvent e) {
			// Baritone has just set the view it wants: put the real one back and let our hand turn there.
			LocalPlayer p = Minecraft.getInstance().player;
			if (!driving || !havePre || p == null) return;
			float y = p.getYRot(), x = p.getXRot();
			if (Math.abs(Angles.wrap(y - preYaw)) < 0.001 && Math.abs(x - prePitch) < 0.001) return;
			wantYaw = y;
			wantPitch = x;
			wantAt = HumanAutopilot.ticks();
			p.setYRot(preYaw);
			p.setXRot(prePitch);
			if (aim != null) aim.lookDirection("baritone", y, x);
		}

		@Override
		public void onPlayerRotationMove(RotationMoveEvent e) {
			// Optionally walk strictly where the camera points (purer, but parkour and bridging get less reliable).
			LocalPlayer p = Minecraft.getInstance().player;
			if (driving && p != null && HumanAutopilot.config().baritoneBodyFollowsCamera) {
				e.setYaw(p.getYRot());
				e.setPitch(p.getXRot());
			}
		}

		@Override
		public void onPathEvent(PathEvent e) {
			if (!driving) return;
			lastEvent = e;
			lastEventAt = HumanAutopilot.ticks();
		}
	}
}
