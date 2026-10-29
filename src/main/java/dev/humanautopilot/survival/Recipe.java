package dev.humanautopilot.survival;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * A crafting pattern laid out exactly as a player would place it in the grid (3x3, row-major).
 * Patterns that fit in the top-left 2x2 can be made in the inventory without a table.
 */
public record Recipe(String name, Predicate<ItemStack>[] grid, Predicate<ItemStack> result) {
	private static final Predicate<ItemStack> P = Inv.PLANK, S = Inv.STICK, C = Inv.COBBLE, I = Inv.IRON,
			D = Inv.DIAMOND, L = Inv.LOG;

	@SafeVarargs
	private static Predicate<ItemStack>[] g(Predicate<ItemStack>... cells) {
		return cells;
	}

	public static final Recipe PLANKS = new Recipe("planks", g(L, null, null, null, null, null, null, null, null), Inv.PLANK);
	public static final Recipe STICKS = new Recipe("sticks", g(P, null, null, P, null, null, null, null, null), Inv.STICK);
	public static final Recipe TABLE = new Recipe("crafting table", g(P, P, null, P, P, null, null, null, null), Inv.TABLE);

	public static final Recipe WOOD_PICK = pick("wooden pickaxe", P, Items.WOODEN_PICKAXE);
	public static final Recipe STONE_PICK = pick("stone pickaxe", C, Items.STONE_PICKAXE);
	public static final Recipe IRON_PICK = pick("iron pickaxe", I, Items.IRON_PICKAXE);
	public static final Recipe DIAMOND_PICK = pick("diamond pickaxe", D, Items.DIAMOND_PICKAXE);

	public static final Recipe WOOD_SWORD = sword("wooden sword", P, Items.WOODEN_SWORD);
	public static final Recipe STONE_SWORD = sword("stone sword", C, Items.STONE_SWORD);
	public static final Recipe IRON_SWORD = sword("iron sword", I, Items.IRON_SWORD);
	public static final Recipe DIAMOND_SWORD = sword("diamond sword", D, Items.DIAMOND_SWORD);

	public static final Recipe STONE_AXE = new Recipe("stone axe", g(C, C, null, C, S, null, null, S, null),
			s -> s.is(Items.STONE_AXE));
	public static final Recipe WOOD_AXE = new Recipe("wooden axe", g(P, P, null, P, S, null, null, S, null),
			s -> s.is(Items.WOODEN_AXE));
	public static final Recipe WOOD_SHOVEL = new Recipe("wooden shovel", g(P, null, null, S, null, null, S, null, null),
			s -> s.is(Items.WOODEN_SHOVEL));
	public static final Recipe STONE_SHOVEL = new Recipe("stone shovel", g(C, null, null, S, null, null, S, null, null),
			s -> s.is(Items.STONE_SHOVEL));
	public static final Recipe TORCH = new Recipe("torches", g(Inv.COAL, null, null, S, null, null, null, null, null), Inv.TORCH);
	public static final Recipe SHIELD = new Recipe("shield", g(P, I, P, P, P, P, null, P, null), s -> s.is(Items.SHIELD));
	public static final Recipe IRON_HELMET = new Recipe("iron helmet", g(I, I, I, I, null, I, null, null, null), s -> s.is(Items.IRON_HELMET));
	public static final Recipe IRON_CHEST = new Recipe("iron chestplate", g(I, null, I, I, I, I, I, I, I), s -> s.is(Items.IRON_CHESTPLATE));
	public static final Recipe IRON_LEGS = new Recipe("iron leggings", g(I, I, I, I, null, I, I, null, I), s -> s.is(Items.IRON_LEGGINGS));
	public static final Recipe IRON_BOOTS = new Recipe("iron boots", g(I, null, I, I, null, I, null, null, null), s -> s.is(Items.IRON_BOOTS));
	public static final Recipe CHEST = new Recipe("chest", g(P, P, P, P, null, P, P, P, P), s -> s.is(Items.CHEST));
	public static final Recipe FURNACE = new Recipe("furnace", g(C, C, C, C, null, C, C, C, C), Inv.FURNACE);

	/** A bed needs three wool of one colour. */
	public static Recipe bed(net.minecraft.world.item.Item wool) {
		Predicate<ItemStack> w = s -> s.is(wool);
		return new Recipe("bed", g(w, w, w, P, P, P, null, null, null), s -> s.is(net.minecraft.tags.ItemTags.BEDS));
	}

	private static Recipe pick(String name, Predicate<ItemStack> m, net.minecraft.world.item.Item out) {
		return new Recipe(name, g(m, m, m, null, S, null, null, S, null), s -> s.is(out));
	}

	private static Recipe sword(String name, Predicate<ItemStack> m, net.minecraft.world.item.Item out) {
		return new Recipe(name, g(null, m, null, null, m, null, null, S, null), s -> s.is(out));
	}

	private static final net.minecraft.world.item.Item[] ICONS = {Items.OAK_PLANKS, Items.STICK, Items.CRAFTING_TABLE,
			Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.WOODEN_SWORD,
			Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.WOODEN_AXE, Items.STONE_AXE, Items.WOODEN_SHOVEL, Items.STONE_SHOVEL,
			Items.TORCH, Items.SHIELD, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
			Items.CHEST, Items.FURNACE, Items.WHITE_BED};

	/** The item this makes (one of them, for the "any kind" recipes), to look it up by name; null if unknown. */
	public net.minecraft.world.item.Item item() {
		for (var i : ICONS) if (result.test(new ItemStack(i))) return i;
		return null;
	}

	/** Fits in the 2x2 inventory grid (nothing in the third row or column). */
	public boolean fitsInInventory() {
		for (int i = 0; i < 9; i++) {
			if (grid[i] != null && (i % 3 == 2 || i / 3 == 2)) return false;
		}
		return true;
	}

	/** How many of each ingredient one craft uses, keyed by the predicate instance. */
	public int uses(Predicate<ItemStack> ingredient) {
		int n = 0;
		for (Predicate<ItemStack> c : grid) if (c == ingredient) n++;
		return n;
	}
}
