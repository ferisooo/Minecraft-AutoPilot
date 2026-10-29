package dev.humanautopilot.survival;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Puts a block down next to the player: select it, look at the ground beside you, right-click. */
public final class Placer {
	public enum Result { WORKING, DONE, NOT_IN_HOTBAR, NO_SPOT, FAILED }

	private BlockPos spot;
	private Vec3 aimPoint;
	private int attempts;
	private long waitUntil;
	private long clickedAt = -1;
	private long spotSince;
	private int spotsTried;
	private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private BlockPos placed;
	private boolean twoBlocks;
	/** Spots never to put it (kept over reset()): the one-block game's generator top, say. */
	private final java.util.Set<BlockPos> avoid = new java.util.HashSet<>();

	public void avoid(BlockPos b) {
		avoid.add(b.immutable());
	}

	/** Beds take two blocks: the second goes one further in the direction we're facing. */
	public void setTwoBlocks(boolean b) {
		twoBlocks = b;
	}

	public BlockPos placed() {
		return placed;
	}

	/** Where we're trying to put the block (may already be there). */
	public BlockPos spot() {
		return spot;
	}

	public void reset() {
		spot = null;
		spotsTried = 0;
		badSpots.clear();
		attempts = 0;
		clickedAt = -1;
		placed = null;
	}

	public Result tick(LocalPlayer p, InputDriver in, AimController aim, Predicate<ItemStack> item, Block block) {
		long now = HumanAutopilot.ticks();
		int slot = Inv.hotbarSlot(p, item);
		// Holding it in the off-hand (a torch while caving): right-click with a tool that does nothing on blocks.
		boolean offhand = item.test(p.getOffhandItem());
		if (offhand) {
			int keep = harmlessSlot(p);
			if (keep >= 0) slot = keep;
		}
		if (slot < 0) return Result.NOT_IN_HOTBAR;
		if (spot == null) {
			spot = findSpot(p, twoBlocks, badSpots, avoid);
			if (spot == null) return Result.NO_SPOT;
			spotSince = now;
			aimPoint = new Vec3(spot.getX() + Human.range(0.3, 0.7), spot.getY() + 0.02, spot.getZ() + Human.range(0.3, 0.7));
			waitUntil = now + Human.actionDelay();
		}
		if (Terrain.state(spot).is(block) || (twoBlocks && Terrain.state(spot).is(net.minecraft.tags.BlockTags.BEDS))) {
			placed = spot;
			return Result.DONE;
		}
		if (clickedAt >= 0 && now - clickedAt < 10) return Result.WORKING; // give the server a moment
		if (clickedAt >= 0) {
			clickedAt = -1;
			if (++attempts >= 3) {
				spot = null;
				attempts = 0;
				return Result.FAILED;
			}
		}
		// Can't line up on this spot (something in the way): try another one.
		if (clickedAt < 0 && now - spotSince > 40) {
			badSpots.add(spot);
			spot = null;
			if (++spotsTried >= 3) {
				spotsTried = 0;
				return Result.FAILED;
			}
			return Result.WORKING;
		}
		// Look at the top of the block below the spot.
		BlockPos floor = spot.below();
		Vec3 pt = aimPoint;
		aim.lookAt(floor, () -> pt);
		if (!in.selectHotbar(slot) || now < waitUntil) return Result.WORKING;
		HitResult hr = Minecraft.getInstance().hitResult;
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
				&& bhr.getBlockPos().equals(floor) && bhr.getDirection() == Direction.UP && aim.error() < 4) {
			in.click(InputDriver.opts().keyUse);
			clickedAt = now;
		}
		return Result.WORKING;
	}

	/** A hotbar slot whose item ignores a right-click on a block (so the off-hand item gets used): current first. */
	private static int harmlessSlot(LocalPlayer p) {
		var inv = p.getInventory();
		int cur = inv.getSelectedSlot();
		if (harmless(inv.getItem(cur))) return cur;
		for (int i = 0; i < 9; i++) if (harmless(inv.getItem(i))) return i;
		return -1;
	}

	private static boolean harmless(ItemStack s) {
		return s.isEmpty() || Inv.isPickaxe(s) || Inv.isSword(s);
	}

	/** A straight look from our eyes reaches the top face of {@code floor} (near its middle). */
	private static boolean canClickTop(LocalPlayer p, BlockPos floor) {
		Vec3 eye = p.getEyePosition();
		Vec3 into = new Vec3(floor.getX() + 0.5, floor.getY() + 0.95, floor.getZ() + 0.5);
		var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, into,
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor) && hit.getDirection() == Direction.UP;
	}

	/** An open spot on solid ground, 2-3 blocks away, not where we're standing. */
	private static BlockPos findSpot(LocalPlayer p, boolean twoBlocks, java.util.Set<BlockPos> bad, java.util.Set<BlockPos> avoid) {
		BlockPos feet = p.blockPosition();
		AABB body = p.getBoundingBox().inflate(0.2);
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		// Two up or down too: in a staircase the only free spots are the steps above and below us.
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos c = feet.offset(dx, dy, dz);
					if (new AABB(c).intersects(body)) continue;
					if (bad.contains(c) || avoid.contains(c)) continue;
					var s = Terrain.state(c);
					if (!s.isAir() && !s.canBeReplaced()) continue;
					if (!s.getFluidState().isEmpty()) continue;
					if (!Terrain.floor(c.getX(), c.getY() - 1, c.getZ())) continue;
					if (twoBlocks) {
						Direction facing = Direction.getApproximateNearest(c.getX() + 0.5 - p.getX(), 0, c.getZ() + 0.5 - p.getZ());
						BlockPos head = c.relative(facing);
						var hs = Terrain.state(head);
						if ((!hs.isAir() && !hs.canBeReplaced()) || !Terrain.floor(head.getX(), head.getY() - 1, head.getZ())) continue;
						if (new AABB(head).intersects(body) || avoid.contains(head)) continue;
					}
					double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(c.below()).add(0, 0.5, 0));
					if (d > p.blockInteractionRange() - 0.6 || d < 1.1) continue;
					// We must be able to see (and so click) the top of the block it goes on: in a staircase the
					// step above or the ceiling often hides it.
					if (!canClickTop(p, c.below())) continue;
					double score = Math.abs(d - 2.5) + Math.abs(dy) * 0.5 + Human.range(0, 0.3);
					if (score < bestScore) {
						bestScore = score;
						best = c;
					}
				}
			}
		}
		return best;
	}
}
