package dev.humanautopilot.pathing;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block the normal way: look at it, pick the right tool, hold left click.
 * If the crosshair lands on a different block first (something in the way) it digs that one
 * out, as long as it's safe to.
 */
public final class Digger {
	public enum Result { DONE, WORKING, NO_TOOL, BLOCKED, UNDERFOOT, OUT_OF_REACH }

	private BlockPos current;
	private BlockPos detour;
	private Vec3 aimOffset = Vec3.ZERO;
	private int startDelay;
	private int noHitTicks;

	public static boolean gone(BlockPos pos) {
		var s = Terrain.state(pos);
		return s.isAir() || !s.getFluidState().isEmpty();
	}

	private boolean allowUnderfoot;

	/** Break the block we're standing on (caller has checked the landing is safe). */
	public Result digUnderfoot(BlockPos pos, LocalPlayer p, InputDriver in, AimController aim) {
		allowUnderfoot = true;
		try {
			return dig(pos, p, in, aim);
		} finally {
			allowUnderfoot = false;
		}
	}

	public Result dig(BlockPos pos, LocalPlayer p, InputDriver in, AimController aim) {
		if (detour != null) {
			if (gone(detour)) {
				detour = null;
			} else {
				Result r = digOne(detour, p, in, aim);
				if (r != Result.WORKING && r != Result.DONE) {
					detour = null;
					return r;
				}
				return Result.WORKING;
			}
		}
		Result r = digOne(pos, p, in, aim);
		if (r == Result.BLOCKED) {
			if (obstruction != null && !Terrain.breakableFromHere(obstruction.getX(), obstruction.getY(), obstruction.getZ()))
				dev.humanautopilot.HumanAutopilot.LOGGER.info("[mine] in the way of {}: {} at {}", pos.toShortString(),
						Terrain.state(obstruction).getBlock().getName().getString(), obstruction.toShortString());
			if (obstruction != null && Terrain.breakableFromHere(obstruction.getX(), obstruction.getY(), obstruction.getZ()) && !underfoot(p, obstruction)) {
				detour = obstruction;
				return Result.WORKING;
			}
		}
		return r;
	}

	private BlockPos obstruction;

	private Result digOne(BlockPos pos, LocalPlayer p, InputDriver in, AimController aim) {
		obstruction = null;
		if (gone(pos)) {
			if (pos.equals(current)) {
				lastBroke = HumanAutopilot.ticks();
				lastBrokenPos = pos;
				lastBrokenState = currentState;
			}
			current = null;
			return Result.DONE;
		}
		// Mining a run of blocks (a tunnel, a vein): like the player we learned from, the finger stays on the
		// button between blocks instead of letting go after each one.
		boolean chaining = HumanAutopilot.ticks() - lastBroke < 15;
		if (!allowUnderfoot && underfoot(p, pos)) return Result.UNDERFOOT;
		var state = Terrain.state(pos);
		if (!pos.equals(current)) {
			current = pos;
			currentState = state;
			// Aim somewhere on the block, not the exact center. In a run of blocks (a staircase, a tunnel, a vein) the
			// hand keeps roughly the same spot on each face and just sweeps on to the next block, not a new random point.
			aimOffset = chaining
					? aimOffset.scale(0.7).add(Human.range(-0.06, 0.06), Human.range(-0.06, 0.06), Human.range(-0.06, 0.06))
					: new Vec3(Human.range(-0.2, 0.2), Human.range(-0.2, 0.2), Human.range(-0.2, 0.2));
			startDelay = chaining ? 0 : Human.actionDelay();
			noHitTicks = 0;
		}
		Vec3 point = Vec3.atCenterOf(pos).add(aimOffset);
		aim.lookAt(pos, () -> point);

		int slot = Tools.bestToolSlot(p, state, HumanAutopilot.config().minToolDurability);
		if (slot == Tools.NONE) return Result.NO_TOOL;
		slot = keepInHand(p, state, pos, slot);
		if (!in.selectHotbar(slot)) return Result.WORKING;

		HitResult hr = Minecraft.getInstance().hitResult;
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
			if (bhr.getBlockPos().equals(pos)) {
				noHitTicks = 0;
				if (startDelay > 0) {
					startDelay--;
				} else {
					in.hold(InputDriver.opts().keyAttack);
					lastSwing = HumanAutopilot.ticks();
				}
				return Result.WORKING;
			}
			if (aim.error() < 5 && ++noHitTicks > 6) {
				obstruction = bhr.getBlockPos();
				return Result.BLOCKED;
			}
			// Sweeping over another block on the way to the next one: keep holding, but only over something
			// that takes a while to break (never a torch, grass, or the dirt we're standing on).
			if (chaining && slowToBreak(p, bhr.getBlockPos())) in.hold(InputDriver.opts().keyAttack);
		} else {
			if (aim.error() < 5 && ++noHitTicks > 20) return Result.OUT_OF_REACH; // crosshair hits nothing: too far away
			if (chaining) in.hold(InputDriver.opts().keyAttack); // swinging at air between blocks is harmless
		}
		return Result.WORKING;
	}

	private long lastBroke = -100;
	private net.minecraft.world.level.block.state.BlockState currentState;
	/** The last block any digger finished breaking, and what it was (for replanting saplings and the like). */
	public static BlockPos lastBrokenPos;
	public static net.minecraft.world.level.block.state.BlockState lastBrokenState;
	private static long lastSwing = -1000;

	/** Digging away right now (or a moment ago): a moo or an oink doesn't pull the eyes off the work. */
	public static boolean busyMining() {
		return HumanAutopilot.ticks() - lastSwing < 25;
	}
	private static long lastToolSwitch = -1000;

	/**
	 * A player doesn't scroll to the "best" tool for every single block: a quick block (grass, a flower) is broken
	 * with whatever is in hand, and if what's in hand is already the right kind of tool for it (a pickaxe on stone,
	 * an axe on wood) they don't swap for a slightly better one. But dirt, gravel and sand get the shovel, wood the axe:
	 * a pickaxe on dirt is the slow, wrong tool and everyone switches.
	 */
	private static int keepInHand(LocalPlayer p, net.minecraft.world.level.block.state.BlockState state, BlockPos pos, int best) {
		var inv = p.getInventory();
		int cur = inv.getSelectedSlot();
		if (best == cur) return best;
		var held = inv.getItem(cur);
		if (state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)) return remember(best);
		if (held.isDamageableItem() && held.getMaxDamage() - held.getDamageValue() <= HumanAutopilot.config().minToolDurability) return remember(best);
		float perTick = state.getDestroyProgress(p, Terrain.level(), pos); // with what's in hand now
		int ticks = perTick > 0 ? (int) Math.ceil(1 / perTick) : 999;
		if (ticks <= 4) return cur; // a moment's work either way
		float heldSpeed = held.getDestroySpeed(state), bestSpeed = inv.getItem(best).getDestroySpeed(state);
		if (heldSpeed > 1.0f && bestSpeed <= heldSpeed * 1.6f) return cur; // already the right kind of tool
		return remember(best);
	}

	private static int remember(int slot) {
		if (slot != Minecraft.getInstance().player.getInventory().getSelectedSlot()) lastToolSwitch = HumanAutopilot.ticks();
		return slot;
	}

	/** A block that takes at least ~12 ticks to break with what we hold: safe to brush the crosshair over. */
	private static boolean slowToBreak(LocalPlayer p, BlockPos pos) {
		var s = Terrain.state(pos);
		if (s.isAir()) return true;
		if (s.hasBlockEntity()) return false;
		float perTick = s.getDestroyProgress(p, Terrain.level(), pos);
		return perTick > 0 && perTick < 1f / 12f;
	}

	public void reset() {
		current = null;
		detour = null;
	}

	/**
	 * The block holding us up: it's under our feet and nothing else under us is solid. Breaking that
	 * is how you fall into lava or off a cliff. (Overhanging a block is fine if another one supports us.)
	 */
	public static boolean underfoot(LocalPlayer p, BlockPos pos) {
		int footY = net.minecraft.util.Mth.floor(p.getY() - 0.01);
		if (pos.getY() != footY) return false;
		var box = p.getBoundingBox();
		boolean onIt = false, otherSupport = false;
		for (int x = net.minecraft.util.Mth.floor(box.minX); x <= net.minecraft.util.Mth.floor(box.maxX - 1e-6); x++) {
			for (int z = net.minecraft.util.Mth.floor(box.minZ); z <= net.minecraft.util.Mth.floor(box.maxZ - 1e-6); z++) {
				if (x == pos.getX() && z == pos.getZ()) onIt = true;
				else if (Terrain.floor(x, footY, z)) otherSupport = true;
			}
		}
		return onIt && !otherSupport;
	}
}
