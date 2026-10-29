package dev.humanautopilot.schematic;

import dev.humanautopilot.pathing.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the next build of the folder goes (the user: "if its done building schematic 1, it goes to 2nd automatically"):
 * open ground beside the last one - only the world's own ground, trees, plants, snow and water in the way, never
 * somebody's build (a build with Replace on knocks out what's in the way).
 */
final class OpenGround {
	private OpenGround() {}

	/** What a spot turned out to be. */
	enum Verdict { OPEN, STEEP, NOT_LOADED, BUILT_ON, WATER }

	record Look(Verdict verdict, int y, BlockPos why) {}

	/**
	 * Look at a spot {@code nx} by {@code nz} with its corner at x0, z0: the corner's height (one over the ground's middle
	 * height there, like looking at the grass and right-clicking) and whether it's all nature from the ground up to the
	 * build's top.
	 */
	static Look look(int x0, int z0, int nx, int nz, int height, int skip) {
		ClientLevel lvl = Terrain.level();
		if (lvl == null) return new Look(Verdict.NOT_LOADED, 0, null);
		int step = Math.max(1, Math.min(nx, nz) / 10);
		List<Integer> grounds = new ArrayList<>();
		int samples = 0, wet = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = x0; x < x0 + nx; x += step) {
			for (int z = z0; z < z0 + nz; z += step) {
				samples++;
				if (!Terrain.loaded(x, z)) continue;
				int g = ground(lvl, x, z, m);
				if (g == Integer.MIN_VALUE) continue;
				grounds.add(g);
				if (!lvl.getBlockState(m.set(x, g, z)).getFluidState().isEmpty()) wet++;
			}
		}
		// (Half of it not loaded: too far to tell what's there.)
		if (grounds.isEmpty() || grounds.size() * 2 < samples) return new Look(Verdict.NOT_LOADED, 0, null);
		if (wet * 10 > grounds.size() * 3) return new Look(Verdict.WATER, 0, null);
		Collections.sort(grounds);
		int y = grounds.get(grounds.size() / 2) + 1;
		// (A hillside: a lot of the world's ground to dig out first. Used if there's nowhere flatter.)
		int spread = grounds.get(grounds.size() * 9 / 10) - grounds.get(grounds.size() / 10);
		int lo = Math.max(lvl.getMinY(), y - 1), hi = Math.min(lvl.getMaxY(), y - skip + height - 1);
		for (int x = x0; x < x0 + nx; x++) {
			for (int z = z0; z < z0 + nz; z++) {
				if (!Terrain.loaded(x, z)) continue;
				// (Over the column's top block there's only air: no need to look at it.)
				int top = Math.min(hi, lvl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
				for (int yy = lo; yy <= top; yy++) {
					BlockState b = lvl.getBlockState(m.set(x, yy, z));
					if (!wild(b)) return new Look(Verdict.BUILT_ON, y, m.immutable());
				}
			}
		}
		return new Look(spread > 10 ? Verdict.STEEP : Verdict.OPEN, y, null);
	}

	/** The ground's top in a column: the first block down that isn't air, a tree or a plant (water counts), or MIN_VALUE. */
	private static int ground(ClientLevel lvl, int x, int z, BlockPos.MutableBlockPos m) {
		for (int y = lvl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; y > lvl.getMinY(); y--) {
			BlockState b = lvl.getBlockState(m.set(x, y, z));
			if (b.isAir()) continue;
			if (b.getBlock() instanceof LiquidBlock || SchematicMode.terrain(b)) return y; // (grass_block isn't a plant)
			if (b.is(BlockTags.LEAVES) || b.is(BlockTags.LOGS) || b.canBeReplaced() || plant(path(b))) continue;
			return y;
		}
		return Integer.MIN_VALUE;
	}

	/** Nature, not something somebody built: ground, water, a tree, a plant, snow, ice. */
	static boolean wild(BlockState b) {
		if (b.isAir()) return true;
		if (b.getBlock() instanceof LiquidBlock) return b.getFluidState().is(FluidTags.WATER); // (not lava)
		if (SchematicMode.terrain(b)) return true;
		// A tree's leaves (a player's are lasting: persistent) and its logs (not stripped ones).
		if (b.is(BlockTags.LEAVES)) {
			return !(b.hasProperty(BlockStateProperties.PERSISTENT) && b.getValue(BlockStateProperties.PERSISTENT));
		}
		String id = path(b);
		if (b.is(BlockTags.LOGS)) return !id.startsWith("stripped_");
		if (b.canBeReplaced()) return !id.equals("light"); // (grass, ferns, snow, vines; not a light block)
		if (id.endsWith("_ore") || id.equals("ice") || id.equals("packed_ice") || id.equals("blue_ice") || id.equals("powder_snow")
				|| id.equals("mossy_cobblestone") || id.equals("obsidian") || id.equals("magma_block")) return true;
		return plant(id);
	}

	/** Flowers, mushrooms, cacti, kelp, coral... (by name: there are a lot of them). */
	private static boolean plant(String id) {
		if (id.startsWith("potted_") || id.equals("flower_pot") || id.startsWith("carved_") || id.startsWith("dried_")) return false;
		if (id.startsWith("mossy_")) return false; // (stone bricks: built)
		if (id.equals("bamboo") || id.equals("bamboo_sapling")) return true;
		if (id.contains("bamboo")) return false; // (planks, blocks, stairs)
		for (String s : PLANTS) if (id.contains(s)) return true;
		return false;
	}

	private static final String[] PLANTS = {"flower", "mushroom", "sapling", "grass", "fern", "bush", "vine", "lichen", "dripleaf",
			"azalea", "roots", "kelp", "seagrass", "coral", "pickle", "lily_pad", "cactus", "sugar_cane", "pumpkin", "melon", "cocoa",
			"bee_nest", "dripstone", "moss", "leaf_litter", "snow", "tulip", "orchid", "allium", "bluet", "daisy", "dandelion", "poppy",
			"cornflower", "lily_of_the_valley", "rose", "peony", "lilac", "pitcher", "spore_blossom", "petals", "eyeblossom", "propagule",
			"berry", "sprouts", "fungus", "wart_block", "shroomlight"};

	private static String path(BlockState b) {
		return BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
	}
}
