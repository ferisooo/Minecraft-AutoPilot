package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.pathing.Goal;
import dev.humanautopilot.pathing.Navigator;
import dev.humanautopilot.pathing.Terrain;
import dev.humanautopilot.util.Human;
import dev.humanautopilot.util.Tools;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Finds target blocks, walks over, breaks them with the right tool, and picks up the drops.
 * Used for /automine (ores you pick) and by the survival planner (trees, stone, its own table...).
 */
public final class MiningModule {
	public enum Ore {
		COAL(BlockTags.COAL_ORES), IRON(BlockTags.IRON_ORES), COPPER(BlockTags.COPPER_ORES),
		GOLD(BlockTags.GOLD_ORES), REDSTONE(BlockTags.REDSTONE_ORES), LAPIS(BlockTags.LAPIS_ORES),
		DIAMOND(BlockTags.DIAMOND_ORES), EMERALD(BlockTags.EMERALD_ORES),
		QUARTZ(Blocks.NETHER_QUARTZ_ORE), DEBRIS(Blocks.ANCIENT_DEBRIS);

		private final Predicate<BlockState> test;

		Ore(TagKey<Block> tag) {
			test = s -> s.is(tag);
		}

		Ore(Block block) {
			test = s -> s.is(block);
		}

		public boolean matches(BlockState s) {
			return test.test(s);
		}

		public static Ore parse(String s) {
			s = s.toLowerCase(Locale.ROOT).replace("_ore", "").replace("ancient_", "");
			if (s.equals("netherite") || s.equals("ancient_debris")) s = "debris";
			for (Ore o : values()) if (o.name().toLowerCase(Locale.ROOT).equals(s)) return o;
			return null;
		}
	}

	private enum State { SCAN, TRAVEL, MINE, SETTLE, COLLECT }

	private final Set<Ore> ores = EnumSet.noneOf(Ore.class);
	private BiPredicate<BlockPos, BlockState> custom;
	private String customLabel = "";
	private boolean customExposedOnly;
	private boolean quiet;

	private final Navigator nav = new Navigator();
	private final dev.humanautopilot.pathing.Digger digger = new dev.humanautopilot.pathing.Digger();
	private final Set<BlockPos> blacklist = new HashSet<>();
	private final Set<Integer> ignoredItems = new HashSet<>();
	private final Set<Ore> warnedNoTool = EnumSet.noneOf(Ore.class);
	private State state = State.SCAN;
	private BlockPos target;
	private String targetName = "ore";
	private long nextScan;
	private long settleUntil;
	private ItemEntity collecting;
	private long collectDeadline;
	private int mined;
	private String status = "";
	private String stopReason = "";
	private int failsInARow;
	private BlockPos lastBlocked;
	private int blockedTries;
	private long giveUpUntil;

	public Set<Ore> ores() {
		return ores;
	}

	public int minedCount() {
		return mined;
	}

	public String stopReason() {
		return stopReason;
	}

	/** Mine the ores the player asked for. */
	public void start(Set<Ore> which) {
		custom = null;
		quiet = false;
		ores.clear();
		ores.addAll(which);
		reset();
	}

	/** Break any block matching {@code what} (used by the survival planner; no chat messages). */
	public void startCustom(String label, BiPredicate<BlockPos, BlockState> what, boolean exposedOnly) {
		if (custom != null && label.equals(customLabel)) {
			custom = what; // same job, keep progress
			return;
		}
		custom = what;
		customLabel = label;
		customExposedOnly = exposedOnly;
		quiet = true;
		ores.clear();
		reset();
	}

	private void reset() {
		blacklist.clear();
		ignoredItems.clear();
		warnedNoTool.clear();
		state = State.SCAN;
		target = null;
		nextScan = 0;
		mined = 0;
		failsInARow = 0;
		giveUpUntil = 0;
		stopReason = "";
		nav.stop();
		digger.reset();
	}

	/** Called when another module took over for a while: re-plan movement on resume. */
	public void interrupted() {
		nav.replan();
	}

	public void stop() {
		nav.stop();
		digger.reset();
		target = null;
		collecting = null;
		state = State.SCAN;
	}

	private void say(String msg) {
		if (!quiet) HumanAutopilot.message(msg);
	}

	private double reach(LocalPlayer p) {
		return p.blockInteractionRange() - 0.3;
	}

	/** Returns a status line, or null if mining has stopped (see {@link #stopReason()}). */
	public String tick(LocalPlayer p, InputDriver in, AimController aim) {
		var cfg = HumanAutopilot.config();
		long now = HumanAutopilot.ticks();

		if (p.getInventory().getFreeSlot() == -1) {
			stopReason = "inventory is full";
			say("Mining stopped: inventory is full.");
			return null;
		}

		switch (state) {
			case SCAN -> {
				if (now < nextScan) return status;
				if (now < giveUpUntil) {
					target = null;
					return status = "no reachable " + label();
				}
				target = findTarget(p);
				if (target == null) {
					nextScan = now + 40;
					status = "no " + label() + " in sight";
					return status;
				}
				targetName = nameAt(target);
				if (Tools.bestToolSlot(p, Terrain.state(target), cfg.minToolDurability) == Tools.NONE) {
					return stopForTool(p, target);
				}
				state = State.TRAVEL;
				digger.reset();
				nav.goStrict(new Goal.Near(Vec3.atCenterOf(target), reach(p) - 0.6, true), cfg.mineAllowTunneling);
				return status = "heading to " + targetName;
			}
			case TRAVEL -> {
				if (!isTarget(target)) return rescan(now);
				if (eyeDist(p, target) <= reach(p) - 0.3 && !dev.humanautopilot.pathing.Digger.underfoot(p, target)) {
					nav.stop();
					state = State.MINE;
					return status;
				}
				var s = nav.tick(p, in, aim);
				if (s == Navigator.Status.FAILED) {
					unreachable(target, now);
					return rescan(now);
				}
				if (s == Navigator.Status.ARRIVED) state = State.MINE;
				return status = "heading to " + targetName;
			}
			case MINE -> {
				if (!isTarget(target)) {
					mined++;
					failsInARow = 0;
					state = State.SETTLE;
					settleUntil = now + Human.rangeInt(8, 16);
					return status = "got " + targetName;
				}
				if (eyeDist(p, target) > reach(p)) {
					state = State.TRAVEL;
					nav.go(new Goal.Near(Vec3.atCenterOf(target), reach(p) - 1.0, true), cfg.mineAllowTunneling);
					return status;
				}
				var r = digger.dig(target, p, in, aim);
				switch (r) {
					case NO_TOOL -> {
						return stopForTool(p, target);
					}
					case BLOCKED -> {
						if (!target.equals(lastBlocked)) blockedTries = 0;
						if (blockedTries < 3) {
							// Something we won't break is in the way (a chest, lava behind): walk round and try from
							// another side, closer each time, the way a player shuffles about until they can reach it.
							lastBlocked = target;
							double near = new double[]{2.5, 1.9, 1.5}[blockedTries++];
							state = State.TRAVEL;
							digger.reset();
							nav.goStrict(new Goal.Beside(target, near), cfg.mineAllowTunneling);
							return status = "finding a better angle on " + targetName;
						}
						HumanAutopilot.LOGGER.info("[mine] blocked at {} (crosshair on something unbreakable), skipping", target.toShortString());
						blacklist.add(target);
						return rescan(now);
					}
					case OUT_OF_REACH -> {
						HumanAutopilot.LOGGER.info("[mine] {} out of reach from {}, moving closer", target.toShortString(), p.blockPosition().toShortString());
						state = State.TRAVEL;
						digger.reset();
						nav.goStrict(new Goal.Beside(target, reach(p) - 1.2), cfg.mineAllowTunneling);
						return status = "moving closer to " + targetName;
					}
					case UNDERFOOT -> {
						HumanAutopilot.LOGGER.info("[mine] {} is under us, stepping aside", target.toShortString());
						// It's under us: step to the side first, then mine it from there.
						state = State.TRAVEL;
						digger.reset();
						nav.goStrict(new Goal.Beside(target, reach(p) - 0.6), false);
						return status = "stepping aside to mine " + targetName;
					}
					default -> {
					}
				}
				return status = "breaking " + targetName;
			}
			case SETTLE -> {
				// Watch the drops land for a moment, like a person would.
				BlockPos t = target;
				aim.lookAt(t, () -> Vec3.atCenterOf(t));
				if (now >= settleUntil) {
					state = State.COLLECT;
					collecting = null;
				}
				return status = "got " + targetName;
			}
			case COLLECT -> {
				if (collecting == null || !collecting.isAlive() || collecting.isRemoved()) {
					collecting = nearestDrop(p);
					if (collecting == null) {
						// Nothing left to grab: the next block in the vein/tree is probably reachable now.
						state = State.SCAN;
						nextScan = now + Human.actionDelay();
						return status = "looking for more";
					}
					collectDeadline = now + 120;
					ItemEntity it = collecting;
					nav.go(new Goal.Near(it.position(), 0.8, false), false);
				}
				if (now > collectDeadline) {
					ignoredItems.add(collecting.getId());
					collecting = null;
					nav.stop();
					return status;
				}
				var s = nav.tick(p, in, aim);
				if (s == Navigator.Status.FAILED || s == Navigator.Status.ARRIVED) {
					// Arrived: it gets picked up on its own. Either way, don't chase this one again.
					ignoredItems.add(collecting.getId());
					collecting = null;
				}
				return status = "picking up drops";
			}
		}
		return status;
	}

	/** Couldn't get to it: skip it and its neighbours (same cliff face), and after a few misses stop trying for a while. */
	private void unreachable(BlockPos pos, long now) {
		HumanAutopilot.LOGGER.info("[mine] can't reach {} ({} in a row)", pos.toShortString(), failsInARow + 1);
		for (BlockPos b : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) blacklist.add(b.immutable());
		if (++failsInARow >= 3) {
			failsInARow = 0;
			giveUpUntil = now + 20 * 30;
		}
	}

	private String rescan(long now) {
		nav.stop();
		digger.reset();
		state = State.SCAN;
		nextScan = now + Human.actionDelay();
		return status;
	}

	private String stopForTool(LocalPlayer p, BlockPos pos) {
		BlockState s = Terrain.state(pos);
		boolean hasWornTool = false;
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && st.isCorrectToolForDrops(s)) hasWornTool = true;
		}
		if (hasWornTool) {
			stopReason = "tool about to break";
			say("Mining stopped: your tool is about to break.");
			return null;
		}
		if (custom != null) {
			// Survival planner: skip this block, it will fetch a better tool first.
			blacklist.add(pos);
			stopReason = "no tool for " + nameAt(pos);
			return rescan(HumanAutopilot.ticks());
		}
		// No tool that can mine this ore at all: skip this ore type, keep mining the others.
		Ore o = oreAt(pos);
		if (o != null && warnedNoTool.add(o)) {
			say("No tool in your hotbar can mine " + o.name().toLowerCase(Locale.ROOT) + "; skipping it.");
		}
		if (o != null) ores.remove(o);
		if (ores.isEmpty()) {
			stopReason = "no usable tool";
			say("Mining stopped: no usable tool for any selected ore.");
			return null;
		}
		return rescan(HumanAutopilot.ticks());
	}

	private boolean isTarget(BlockPos pos) {
		if (pos == null) return false;
		BlockState s = Terrain.state(pos);
		if (custom != null) return custom.test(pos, s);
		return oreAt(pos) != null;
	}

	private Ore oreAt(BlockPos pos) {
		BlockState s = Terrain.state(pos);
		for (Ore o : ores) if (o.matches(s)) return o;
		return null;
	}

	private String nameAt(BlockPos pos) {
		// The real block ("iron ore", "oak log"), not the job's label: diamond hunting also picks up iron and coal.
		if (custom != null) return Terrain.state(pos).getBlock().getName().getString().toLowerCase(Locale.ROOT);
		Ore o = oreAt(pos);
		return o == null ? "ore" : o.name().toLowerCase(Locale.ROOT);
	}

	private String label() {
		if (custom != null) return customLabel;
		StringBuilder b = new StringBuilder();
		for (Ore o : ores) b.append(b.isEmpty() ? "" : "/").append(o.name().toLowerCase(Locale.ROOT));
		return b.toString();
	}

	private static double eyeDist(LocalPlayer p, BlockPos pos) {
		return Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
	}

	private BlockPos findTarget(LocalPlayer p) {
		var cfg = HumanAutopilot.config();
		BlockPos center = p.blockPosition();
		Vec3 eye = p.getEyePosition();
		java.util.List<BlockPos> found = new java.util.ArrayList<>();
		java.util.List<Double> scores = new java.util.ArrayList<>();
		java.util.Set<BlockPos> sameTrunk = new java.util.HashSet<>();
		boolean exposedOnly = custom != null ? customExposedOnly : cfg.mineOnlyExposedOres;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int r = cfg.mineRadius, ry = cfg.mineVerticalRadius;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int x = center.getX() + dx, z = center.getZ() + dz;
				if (!Terrain.loaded(x, z)) continue;
				for (int dy = -ry; dy <= ry; dy++) {
					m.set(x, center.getY() + dy, z);
					BlockState s = Terrain.state(m);
					if (s.isAir()) continue;
					if (!(custom != null ? custom.test(m, s) : oreAt(m) != null)) continue;
					if (blacklist.contains(m)) continue;
					// Never pick the block straight under us: that's digging straight down.
					if (dy < 0 && dx == 0 && dz == 0) continue;
					if (exposedOnly && !exposed(m)) continue;
					found.add(m.immutable());
					double sc = eye.distanceToSqr(Vec3.atCenterOf(m)) + Math.abs(dy) * 2.0;
					// Trees: chop from the bottom up, like a player (lowest log of the nearest tree first).
					if (custom != null && "wood".equals(customLabel)) {
						sc = (dx * dx + dz * dz) + Math.max(dy, 0) * 12.0 + Math.max(-dy, 0) * 3.0;
						// Finish the tree we're on before starting the next: the trunk we just took a log from (or its 2x2
						// neighbours) comes first while we can still reach it.
						BlockPos last = dev.humanautopilot.pathing.Digger.lastBrokenPos;
						if (last != null && Math.abs(x - last.getX()) <= 1 && Math.abs(z - last.getZ()) <= 1
								&& Math.abs(m.getY() - last.getY()) <= 6 && eye.distanceTo(Vec3.atCenterOf(m)) <= reach(p) + 0.5) {
								sc = -100 + dy;
								sameTrunk.add(m.immutable());
							}
					}
					scores.add(sc);
				}
			}
		}
		Integer[] order = new Integer[found.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Double.compare(scores.get(a), scores.get(b)));
		// Only go for things we can actually see from here: no seeing ore through solid rock.
		int checks = 0;
		for (int i : order) {
			BlockPos c = found.get(i);
			// The trunk we're chopping: go for it even behind its own leaves (they get cleared on the way, like a player).
			if (!exposedOnly || sameTrunk.contains(c)) return c;
			if (++checks > 40) break;
			if (canSee(p, c)) return c;
		}
		return null;
	}

	/** A clear line from our eyes to the block (its centre, or the face turned toward us). */
	public static boolean canSee(LocalPlayer p, BlockPos pos) {
		Vec3 eye = p.getEyePosition();
		Vec3 c = Vec3.atCenterOf(pos);
		Vec3 toEye = eye.subtract(c).normalize();
		for (Vec3 target : new Vec3[]{c, c.add(toEye.scale(0.49))}) {
			var hit = p.level().clip(new net.minecraft.world.level.ClipContext(eye, target,
					net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return true;
		}
		return false;
	}

	/** At least one face open to air or water, so a player walking by could actually see it. */
	private static boolean exposed(BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState s = Terrain.state(pos.relative(d));
			if (s.isAir() || !s.isSolidRender()) return true;
		}
		return false;
	}

	private ItemEntity nearestDrop(LocalPlayer p) {
		ItemEntity best = null;
		for (ItemEntity e : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(7, 3, 7))) {
			if (!e.isAlive() || ignoredItems.contains(e.getId())) continue;
			// Don't fetch junk, or the old tools we just threw out, back again.
			if (dev.humanautopilot.survival.Inv.notWorthPickingUp(p, e)) continue;
			if (best == null || p.distanceToSqr(e) < p.distanceToSqr(best)) best = e;
		}
		return best;
	}

	public String status() {
		return status;
	}

	/** True while there's nothing to break in sight, so something else (exploring, tunneling) may move us. */
	public boolean waitingForOre() {
		return state == State.SCAN && target == null;
	}

	public boolean busy() {
		return state != State.SCAN;
	}
}
