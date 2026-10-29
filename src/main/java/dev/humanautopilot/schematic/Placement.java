package dev.humanautopilot.schematic;

import dev.humanautopilot.mixin.BlockItemInvoker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * What a right-click would put down, worked out the way the game does it (the block item's own placement rules, with
 * our camera angles), so schematic blocks go in facing the right way: stairs, slabs, logs, doors, torches. And what
 * each spot still needs to be exactly like the schematic: a block put in, a wrong one knocked out first, or a
 * finishing touch (open the trapdoor, plant the pot, grow the wheat, add candles, water it).
 */
public final class Placement {
	private Placement() {}

	/** Properties set by how the block is put down. The rest (fence and stair connections, ...) follow the neighbours. */
	private static final Set<String> ORIENT = Set.of("facing", "axis", "half", "rotation", "face", "hinge", "attachment",
			"orientation", "hanging", "vertical_direction");

	/** Counts that go up one per click with the same item (candles, sea pickles, turtle eggs, petals, leaf litter, snow). */
	private static final List<IntegerProperty> COUNTS = List.of(BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
			BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT, BlockStateProperties.LAYERS);

	/** What a spot still needs. */
	public enum Job { DONE, PLACE, BREAK, ADJUST, BLOCKED }

	/** A finishing touch: click the block itself with this item (null: whatever is in hand). */
	public record Adjust(Item item, String what) {}

	/** Worth building: not air, has a way to put it there, and isn't the second half of something placed in one go. */
	public static boolean buildable(BlockState s) {
		if (s.isAir() || s.is(Blocks.STRUCTURE_VOID) || s.is(Blocks.MOVING_PISTON) || s.is(Blocks.PISTON_HEAD)) return false;
		Block b = s.getBlock();
		if (b instanceof LiquidBlock) return fluid(s); // a source, with a bucket (flowing water comes by itself)
		if (b instanceof FlowerPotBlock) return true; // a pot, then what's in it
		if (b.asItem() == Items.AIR) return false;
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
		if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return false;
		return true;
	}

	/** A water or lava source (put down with a bucket). */
	/** Grass, dirt and the like: one turns into the other by itself (grass spreads, grass under a block dies). */
	public static boolean soil(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)
				|| s.is(Blocks.MYCELIUM) || s.is(Blocks.ROOTED_DIRT);
	}

	/** Natural ground (soil, stone, sand, gravel...), not something made out of it (farmland, a path). */
	public static boolean naturalGround(BlockState s) {
		if (s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH)) return false;
		return soil(s) || s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.SAND)
				|| s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER)
				|| s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SANDSTONE) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.MUD)
				|| s.is(Blocks.MOSS_BLOCK);
	}

	/** Its way round comes from where the player faces, not the face clicked (glazed terracotta, furnaces, chests...). */
	public static boolean headingFaced(BlockState s) {
		Block b = s.getBlock();
		return b instanceof net.minecraft.world.level.block.HorizontalDirectionalBlock
				&& !(b instanceof net.minecraft.world.level.block.StairBlock);
	}

	public static boolean fluid(BlockState s) {
		return s.getBlock() instanceof LiquidBlock && s.getFluidState().isSource();
	}

	/** The item that puts this down: a bucket for water, the pot for a potted plant, else the block's own item. */
	public static Item placeItem(BlockState want) {
		if (fluid(want)) return want.getFluidState().getType().getBucket();
		if (want.getBlock() instanceof FlowerPotBlock) return Items.FLOWER_POT;
		return want.getBlock().asItem();
	}

	/** What the first click should leave there (an empty pot for a potted plant; the rest is finishing touches). */
	public static BlockState placeShape(BlockState want) {
		if (want.getBlock() instanceof FlowerPotBlock) return Blocks.FLOWER_POT.defaultBlockState();
		return want;
	}

	/** The world already has exactly what the schematic wants here (block, way round, and the finishing touches). */
	public static boolean matches(BlockState world, BlockState want) {
		if (want.isAir()) return world.isAir();
		if (fluid(want)) return fluid(world) && world.getFluidState().getType().isSame(want.getFluidState().getType());
		// A tree's own leaves where the build has placed (lasting) ones: they wither once their tree's wood is gone (the
		// wizard tower's bushes in a wood: counted as in, then gone a minute later as he cleared the trunks, "damaged").
		if (want.hasProperty(BlockStateProperties.PERSISTENT) && want.getValue(BlockStateProperties.PERSISTENT)
				&& world.hasProperty(BlockStateProperties.PERSISTENT) && !world.getValue(BlockStateProperties.PERSISTENT)) return false;
		return world.getBlock() == want.getBlock() && same(world, want, false, false) && adjust(world, want) == null;
	}

	/**
	 * What this spot needs to end up exactly like the schematic. {@code replace}: a different block in the way may be
	 * knocked out (else the spot is left alone, BLOCKED).
	 */
	public static Job job(Level level, BlockPos pos, BlockState world, BlockState want, boolean replace) {
		if (want.isAir()) {
			// The schematic has nothing here: anything that's in the way goes (tall grass, a stray block).
			if (world.isAir() || world.getBlock() instanceof LiquidBlock) return Job.DONE;
			return replace && breakable(level, pos, world) ? Job.BREAK : Job.BLOCKED;
		}
		if (matches(world, want)) return Job.DONE;
		if (fluid(want)) {
			if (world.isAir() || world.canBeReplaced()) return Job.PLACE;
			return replace && breakable(level, pos, world) ? Job.BREAK : Job.BLOCKED;
		}
		if (slabToDouble(world, want)) return Job.PLACE;
		Adjust a = adjust(world, want);
		if (a != null) {
			// A hoe or a shovel only works with nothing on top (and farmland or a path wouldn't stay under a block anyway).
			if (tool(a) && !level.getBlockState(pos.above()).isAir()) return Job.BLOCKED;
			return Job.ADJUST;
		}
		if (world.isAir() || (world.canBeReplaced() && world.getBlock() != want.getBlock())) return Job.PLACE;
		// Something else there, or ours the wrong way round: knock it out and put it in again.
		return replace && breakable(level, pos, world) ? Job.BREAK : Job.BLOCKED;
	}

	/** Creative can knock it out with one click (not bedrock, a barrier, a fluid), and it doesn't hold anyone's things. */
	public static boolean breakable(Level level, BlockPos pos, BlockState s) {
		if (s.isAir() || s.getBlock() instanceof LiquidBlock) return false;
		if (s.getDestroySpeed(level, pos) < 0) return false;
		// A chest, barrel, furnace, shulker box... might have someone's stuff in it: never ours to break.
		return !(s.getBlock() instanceof BaseEntityBlock) || s.getBlock() instanceof net.minecraft.world.level.block.SignBlock
				|| s.getBlock() instanceof net.minecraft.world.level.block.BannerBlock;
	}

	/**
	 * The block is the right one, the right way round, but not finished: what to click it with. Null if nothing to do
	 * (or nothing we can do about it).
	 */
	public static Adjust adjust(BlockState world, BlockState want) {
		Block b = want.getBlock(), wb = world.getBlock();
		// Farmland and paths the way a player makes them: a hoe on dirt or grass, a shovel on grass or dirt. (Farmland
		// from the menu dries back to dirt in a minute or so until its water or crop is in: hoe it again.)
		if (b == Blocks.FARMLAND && (wb == Blocks.DIRT || wb == Blocks.GRASS_BLOCK || wb == Blocks.DIRT_PATH)) {
			return new Adjust(Items.IRON_HOE, "tilling it");
		}
		if (b == Blocks.DIRT_PATH && (wb == Blocks.GRASS_BLOCK || wb == Blocks.DIRT || wb == Blocks.COARSE_DIRT || wb == Blocks.PODZOL
				|| wb == Blocks.MYCELIUM || wb == Blocks.ROOTED_DIRT)) {
			return new Adjust(Items.IRON_SHOVEL, "making the path");
		}
		// An empty pot where a potted plant goes: click the plant into it.
		if (wb == Blocks.FLOWER_POT && b instanceof FlowerPotBlock pot && b != Blocks.FLOWER_POT) {
			Item plant = pot.getPotted().asItem();
			return plant == Items.AIR ? null : new Adjust(plant, "planting the pot");
		}
		if (wb != b || !same(world, want, false, false)) return null;
		// Crops: grown with bone meal.
		if (b instanceof CropBlock crop && crop.getAge(world) < crop.getAge(want)) return new Adjust(Items.BONE_MEAL, "growing it");
		// Candles, pickles, eggs, petals, snow: one more per click with the same item.
		for (IntegerProperty c : COUNTS) {
			if (want.hasProperty(c) && world.hasProperty(c) && world.getValue(c) < want.getValue(c)) return new Adjust(b.asItem(), "adding another");
		}
		// Open / shut (doors, trapdoors, gates a hand can work).
		if (want.hasProperty(BlockStateProperties.OPEN) && world.getValue(BlockStateProperties.OPEN) != want.getValue(BlockStateProperties.OPEN)
				&& handOpenable(world)) {
			return new Adjust(null, want.getValue(BlockStateProperties.OPEN) ? "opening it" : "shutting it");
		}
		// Settings clicked round: repeater delay, comparator mode, lever, daylight sensor, note block.
		for (Property<?> pr : List.of(BlockStateProperties.DELAY, BlockStateProperties.MODE_COMPARATOR, BlockStateProperties.INVERTED,
				BlockStateProperties.NOTE)) {
			if (want.hasProperty(pr) && !world.getValue(pr).equals(want.getValue(pr))) return new Adjust(null, "setting it");
		}
		if (b instanceof LeverBlock && world.getValue(BlockStateProperties.POWERED) != want.getValue(BlockStateProperties.POWERED)) {
			return new Adjust(null, "flipping it");
		}
		// Water in it (or out of it).
		if (want.hasProperty(BlockStateProperties.WATERLOGGED) && world.hasProperty(BlockStateProperties.WATERLOGGED)
				&& world.getValue(BlockStateProperties.WATERLOGGED) != want.getValue(BlockStateProperties.WATERLOGGED)
				&& b instanceof LiquidBlockContainer) {
			return want.getValue(BlockStateProperties.WATERLOGGED) ? new Adjust(Items.WATER_BUCKET, "watering it")
					: new Adjust(Items.BUCKET, "emptying the water out");
		}
		// Lit candles.
		if (b instanceof CandleBlock && want.getValue(BlockStateProperties.LIT) && !world.getValue(BlockStateProperties.LIT)) {
			return new Adjust(Items.FLINT_AND_STEEL, "lighting it");
		}
		return null;
	}

	/** A finishing touch done with a tool on the block (hoe, shovel). */
	public static boolean tool(Adjust a) {
		return a != null && (a.item() == Items.IRON_HOE || a.item() == Items.IRON_SHOVEL);
	}

	private static boolean handOpenable(BlockState s) {
		Block b = s.getBlock();
		if (b instanceof DoorBlock d) return d.type().canOpenByHand();
		if (b instanceof TrapDoorBlock) return !s.is(Blocks.IRON_TRAPDOOR);
		return b instanceof FenceGateBlock;
	}

	/**
	 * A half slab where the schematic wants a double one: a second click finishes it. (No wanted block - a hole that
	 * isn't the build's, a dirt pillar's - is no: that crashed the game on the hillside gate house.)
	 */
	public static boolean slabToDouble(BlockState world, BlockState want) {
		return want != null && want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
				&& world.is(want.getBlock()) && world.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
	}

	/**
	 * A click would put {@code got}: that's the wanted block the right way (any way at all when {@code relaxed}). For a
	 * double slab, either half is a fine first click.
	 */
	public static boolean sameWay(BlockState got, BlockState want, boolean relaxed) {
		return same(got, want, relaxed, true);
	}

	private static boolean same(BlockState got, BlockState want, boolean relaxed, boolean halfSlabOk) {
		// Bamboo put down on the ground comes up as a shoot; the next stalk on top of it turns it into bamboo. That's how a
		// player gets it there (the dining hall's six were "the wrong way round" from every spot, and left out).
		if (got != null && got.is(net.minecraft.world.level.block.Blocks.BAMBOO_SAPLING) && want.is(net.minecraft.world.level.block.Blocks.BAMBOO)) return true;
		if (got == null || got.getBlock() != want.getBlock()) return false;
		if (relaxed) return true;
		for (Property<?> pr : want.getProperties()) {
			String n = pr.getName();
			boolean slabType = n.equals("type") && want.getBlock() instanceof SlabBlock;
			if (!ORIENT.contains(n) && !slabType) continue;
			if (n.equals("axis") && barkAllRound(want)) continue;
			if (slabType && halfSlabOk && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) continue;
			if (!got.hasProperty(pr) || !got.getValue(pr).equals(want.getValue(pr))) return false;
		}
		return true;
	}

	/**
	 * Wood with bark all round (oak wood, stripped wood, hyphae - not logs, whose ends show): its axis doesn't matter, so
	 * any click is the right way round. (The giant tree's upright oak wood roots and trunk: a temporary glass block to
	 * click for nearly every one, 74 in two minutes - the user: "whys he placing a bunch of glass, glass, glass".)
	 */
	public static boolean barkAllRound(BlockState want) {
		if (!(want.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)) return false;
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
		return id.endsWith("_wood") || id.endsWith("_hyphae");
	}

	/** Has a way round set by how it's put down (facing, axis, half...): only then can the wrong click put it in wrong. */
	public static boolean oriented(BlockState want) {
		for (Property<?> pr : want.getProperties()) {
			if (pr.getName().equals("axis") && barkAllRound(want)) continue;
			if (ORIENT.contains(pr.getName())) return true;
		}
		return want.getBlock() instanceof SlabBlock;
	}

	/** The way round depends on where on the face we click (top or bottom half, door hinge side), not just the face. */
	public static boolean spotMatters(BlockState want) {
		return want.hasProperty(BlockStateProperties.HALF) || want.hasProperty(BlockStateProperties.DOOR_HINGE)
				|| want.getBlock() instanceof SlabBlock;
	}

	/** Facing set to one of 16 directions by the camera (signs, banners, heads): the yaw has to be just so. */
	public static boolean fineRotation(BlockState want) {
		return want.hasProperty(BlockStateProperties.ROTATION_16);
	}

	/** Small things that go on a finished structure (torches, flowers, signs, pots, lanterns, crops): done last. */
	public static boolean decoration(Level level, BlockPos pos, BlockState want) {
		Block b = want.getBlock();
		return want.getCollisionShape(level, pos).isEmpty() || b instanceof FlowerPotBlock || b instanceof CandleBlock
				|| b instanceof net.minecraft.world.level.block.LanternBlock || b instanceof CropBlock;
	}

	/**
	 * What clicking {@code hit} with {@code item} would put at {@code cell} with the camera at these angles, or null if
	 * the click wouldn't put anything there.
	 */
	public static BlockState predict(LocalPlayer p, BlockHitResult hit, Item item, BlockPos cell, float yRot, float xRot) {
		if (!(item instanceof BlockItem bi)) return null;
		float oy = p.getYRot(), ox = p.getXRot();
		p.setYRot(yRot);
		p.setXRot(xRot);
		try {
			BlockPlaceContext ctx = bi.updatePlacementContext(new BlockPlaceContext(p, InteractionHand.MAIN_HAND, new ItemStack(item), hit));
			if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(cell)) return null;
			return ((BlockItemInvoker) bi).humanautopilot$placementState(ctx);
		} catch (RuntimeException e) {
			return null;
		} finally {
			p.setYRot(oy);
			p.setXRot(ox);
		}
	}

	/**
	 * {@link #predict} as if we were there, eyes at {@code eye}: our body where it would be then, not where it is now
	 * (hovering with our head in a gap in the roof, every way of filling that gap came out "can't": we were in it).
	 */
	public static BlockState predictFrom(LocalPlayer p, BlockHitResult hit, Item item, BlockPos cell, Vec3 eye) {
		var was = p.getBoundingBox();
		p.setBoundingBox(bodyAt(p, eye));
		try {
			return predict(p, hit, item, cell, yawTo(eye, hit.getLocation()), pitchTo(eye, hit.getLocation()));
		} finally {
			p.setBoundingBox(was);
		}
	}

	private static net.minecraft.world.phys.AABB bodyAt(LocalPlayer p, Vec3 eye) {
		var was = p.getBoundingBox();
		double feet = eye.y - p.getEyeHeight(), w = was.getXsize() / 2;
		return new net.minecraft.world.phys.AABB(eye.x - w, feet, eye.z - w, eye.x + w, feet + was.getYsize(), eye.z + w);
	}

	/**
	 * What clicking the {@code side} face of a block at {@code against} (that isn't there yet: a temporary block we'd
	 * put there to click on) would put at {@code cell}, looking along {@code look}. Null if it wouldn't work.
	 */
	public static BlockState predictAgainst(LocalPlayer p, BlockPos against, Direction side, BlockPos cell, Item item, Vec3 eye, Vec3 offset) {
		if (!(item instanceof BlockItem bi)) return null;
		Vec3 pt = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5)).add(offset);
		float oy = p.getYRot(), ox = p.getXRot();
		var was = p.getBoundingBox();
		p.setYRot(yawTo(eye, pt));
		p.setXRot(pitchTo(eye, pt));
		p.setBoundingBox(bodyAt(p, eye));
		try {
			BlockPlaceContext ctx = new Hypothetical(p, new ItemStack(item), new BlockHitResult(pt, side, against, false), cell);
			return ((BlockItemInvoker) bi).humanautopilot$placementState(ctx);
		} catch (RuntimeException e) {
			return null;
		} finally {
			p.setYRot(oy);
			p.setXRot(ox);
			p.setBoundingBox(was);
		}
	}

	/** A placing click on a block that isn't there yet: lands in {@code cell}. */
	private static final class Hypothetical extends BlockPlaceContext {
		private final BlockPos cell;

		Hypothetical(LocalPlayer p, ItemStack stack, BlockHitResult hit, BlockPos cell) {
			super(p.level(), p, InteractionHand.MAIN_HAND, stack, hit);
			this.cell = cell;
		}

		@Override
		public BlockPos getClickedPos() {
			return cell;
		}

		@Override
		public boolean canPlace() {
			return true;
		}

		@Override
		public boolean replacingClickedOnBlock() {
			return false;
		}
	}

	public static float yawTo(Vec3 eye, Vec3 pt) {
		return (float) Math.toDegrees(Math.atan2(pt.z - eye.z, pt.x - eye.x)) - 90f;
	}

	public static float pitchTo(Vec3 eye, Vec3 pt) {
		double flat = Math.hypot(pt.x - eye.x, pt.z - eye.z);
		return (float) -Math.toDegrees(Math.atan2(pt.y - eye.y, flat));
	}

	/**
	 * Right-clicking this block does something of its own (opens a chest, flips a door, edits a sign, pots a flower)
	 * instead of putting our block next to it: don't click on it.
	 */
	public static boolean usable(BlockState s) {
		Block b = s.getBlock();
		return b instanceof BaseEntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
				|| b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock || b instanceof NoteBlock
				|| b instanceof DiodeBlock || b instanceof DaylightDetectorBlock || b instanceof CakeBlock || b instanceof BellBlock
				|| b instanceof FlowerPotBlock || b instanceof CandleBlock || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
				|| b instanceof net.minecraft.world.level.block.AbstractCauldronBlock || b instanceof net.minecraft.world.level.block.DragonEggBlock
				|| b instanceof net.minecraft.world.level.block.SweetBerryBushBlock
				|| s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.ANVIL) || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL)
				|| s.is(Blocks.GRINDSTONE) || s.is(Blocks.STONECUTTER) || s.is(Blocks.LOOM) || s.is(Blocks.CARTOGRAPHY_TABLE)
				|| s.is(Blocks.SMITHING_TABLE) || s.is(Blocks.ENCHANTING_TABLE) || s.is(Blocks.RESPAWN_ANCHOR) || s.is(Blocks.COMPOSTER);
	}

	/** A bucket emptied on this block would fill the block itself with water (stairs, slabs, fences...), not the spot next to it. */
	public static boolean takesWater(LocalPlayer p, BlockPos pos, BlockState s, BlockState fluid) {
		return s.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(p, p.level(), pos, s, fluid.getFluidState().getType());
	}
}
