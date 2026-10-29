package dev.humanautopilot.survival;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.function.Predicate;

/** Inventory questions the survival planner asks: how many logs, best pickaxe tier, what's junk. */
public final class Inv {
	private Inv() {}

	public static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
	public static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS);
	public static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
	public static final Predicate<ItemStack> COBBLE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
	public static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);
	public static final Predicate<ItemStack> RAW_IRON = s -> s.is(Items.RAW_IRON);
	public static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
	public static final Predicate<ItemStack> DIAMOND = s -> s.is(Items.DIAMOND);
	public static final Predicate<ItemStack> TABLE = s -> s.is(Items.CRAFTING_TABLE);
	public static final Predicate<ItemStack> FURNACE = s -> s.is(Items.FURNACE);
	public static final Predicate<ItemStack> TORCH = s -> s.is(Items.TORCH);
	public static final Predicate<ItemStack> BED = s -> s.is(net.minecraft.tags.ItemTags.BEDS);
	public static final Predicate<ItemStack> CHEST = s -> s.is(Items.CHEST);

	/** What a player would keep on them rather than leave in the chest at home. */
	public static boolean keepOnPerson(ItemStack s) {
		return s.isDamageableItem() || s.has(net.minecraft.core.component.DataComponents.FOOD) || TORCH.test(s) || BED.test(s)
				|| TABLE.test(s) || FURNACE.test(s) || COAL.test(s) || STICK.test(s) || PLANK.test(s) || LOG.test(s)
				|| RAW_IRON.test(s) || IRON.test(s) || DIAMOND.test(s) || s.is(ItemTags.WOOL) || CHEST.test(s);
	}

	/** The wool colour we have the most of, if there are at least 3 of it. */
	public static net.minecraft.world.item.Item bedWool(LocalPlayer p) {
		java.util.Map<net.minecraft.world.item.Item, Integer> counts = new java.util.HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(ItemTags.WOOL)) counts.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return counts.entrySet().stream().filter(e -> e.getValue() >= 3).map(java.util.Map.Entry::getKey).findFirst().orElse(null);
	}
	public static final Predicate<ItemStack> RAW_MEAT = s -> s.is(Items.BEEF) || s.is(Items.PORKCHOP)
			|| s.is(Items.CHICKEN) || s.is(Items.MUTTON) || s.is(Items.RABBIT);

	private static final Map<Item, Integer> PICK_TIER = Map.of(
			Items.WOODEN_PICKAXE, 1, Items.GOLDEN_PICKAXE, 1, Items.STONE_PICKAXE, 2, Items.COPPER_PICKAXE, 2,
			Items.IRON_PICKAXE, 3, Items.DIAMOND_PICKAXE, 4, Items.NETHERITE_PICKAXE, 5);
	private static final Map<Item, Integer> AXE_TIER = Map.of(
			Items.WOODEN_AXE, 1, Items.GOLDEN_AXE, 1, Items.STONE_AXE, 2, Items.COPPER_AXE, 2,
			Items.IRON_AXE, 3, Items.DIAMOND_AXE, 4, Items.NETHERITE_AXE, 5);
	private static final Map<Item, Integer> SWORD_TIER = Map.of(
			Items.WOODEN_SWORD, 1, Items.GOLDEN_SWORD, 1, Items.STONE_SWORD, 2, Items.COPPER_SWORD, 2,
			Items.IRON_SWORD, 3, Items.DIAMOND_SWORD, 4, Items.NETHERITE_SWORD, 5);

	private static final Map<Item, Integer> SHOVEL_TIER = Map.of(
			Items.WOODEN_SHOVEL, 1, Items.GOLDEN_SHOVEL, 1, Items.STONE_SHOVEL, 2, Items.COPPER_SHOVEL, 2,
			Items.IRON_SHOVEL, 3, Items.DIAMOND_SHOVEL, 4, Items.NETHERITE_SHOVEL, 5);

	public static int shovelTier(LocalPlayer p) {
		return tier(p, SHOVEL_TIER);
	}

	/** 1 pickaxe, 2 axe, 3 sword, 4 shovel, 0 not a tool. */
	public static int toolKind(ItemStack s) {
		Item i = s.getItem();
		return PICK_TIER.containsKey(i) ? 1 : AXE_TIER.containsKey(i) ? 2 : SWORD_TIER.containsKey(i) ? 3 : SHOVEL_TIER.containsKey(i) ? 4 : 0;
	}

	/** Wooden/golden 1, stone/copper 2, iron 3, diamond 4, netherite 5 (0 if not a tool). */
	public static int toolTierOf(ItemStack s) {
		Item i = s.getItem();
		for (Map<Item, Integer> t : java.util.List.of(PICK_TIER, AXE_TIER, SWORD_TIER, SHOVEL_TIER)) if (t.containsKey(i)) return t.get(i);
		return 0;
	}

	public static boolean isShovel(ItemStack s) {
		return SHOVEL_TIER.containsKey(s.getItem());
	}

	public static int count(LocalPlayer p, Predicate<ItemStack> what) {
		var inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && what.test(s)) n += s.getCount();
		}
		ItemStack off = p.getOffhandItem();
		if (!off.isEmpty() && what.test(off)) n += off.getCount();
		return n;
	}

	public static boolean has(LocalPlayer p, Predicate<ItemStack> what) {
		return count(p, what) > 0;
	}

	/** In the main bag (not hotbar, not off-hand): somewhere sorting can move it from. */
	public static boolean inMain(LocalPlayer p, Predicate<ItemStack> what) {
		for (int i = 9; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && what.test(s)) return true;
		}
		return false;
	}

	public static boolean inHotbar(LocalPlayer p, Predicate<ItemStack> what) {
		for (int i = 0; i < 9; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && what.test(s)) return true;
		}
		return false;
	}

	public static int hotbarSlot(LocalPlayer p, Predicate<ItemStack> what) {
		for (int i = 0; i < 9; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && what.test(s)) return i;
		}
		return -1;
	}

	/** An old tool outclassed by a working one of the same kind we already carry (e.g. wooden pickaxe once we have stone). */
	public static boolean obsoleteTool(LocalPlayer p, ItemStack s) {
		for (Map<Item, Integer> table : java.util.List.of(PICK_TIER, AXE_TIER, SWORD_TIER, SHOVEL_TIER)) {
			Integer t = table.get(s.getItem());
			if (t == null) continue;
			int best = tier(p, table); // best tier among the tools of this kind that aren't worn out
			// Worn out (a few uses left) and a working one of the same kind is in the bag: done with it, toss it.
			boolean worn = s.getMaxDamage() - s.getDamageValue() <= dev.humanautopilot.HumanAutopilot.config().minToolDurability;
			if (worn && best > 0) return true;
			// Stone pickaxes stay even with an iron one: they're what we dig plain rock with (saving the good one).
			if (table == PICK_TIER && t == 2) return false;
			return t < best;
		}
		return false;
	}

	public static boolean hasObsoleteTools(LocalPlayer p) {
		for (int i = 0; i < 36; i++) if (obsoleteTool(p, p.getInventory().getItem(i))) return true;
		return false;
	}

	private static int tier(LocalPlayer p, Map<Item, Integer> table) {
		int best = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			Integer t = table.get(s.getItem());
			// A tool about to break doesn't count: time to make a new one.
			if (t != null && t > best && s.getMaxDamage() - s.getDamageValue() > dev.humanautopilot.HumanAutopilot.config().minToolDurability) best = t;
		}
		return best;
	}

	public static int pickaxeTier(LocalPlayer p) {
		return tier(p, PICK_TIER);
	}

	public static int swordTier(LocalPlayer p) {
		return tier(p, SWORD_TIER);
	}

	public static int axeTier(LocalPlayer p) {
		return tier(p, AXE_TIER);
	}

	public static boolean isAxe(ItemStack s) {
		return AXE_TIER.containsKey(s.getItem());
	}

	public static boolean isPickaxe(ItemStack s) {
		return PICK_TIER.containsKey(s.getItem());
	}

	public static boolean isSword(ItemStack s) {
		return SWORD_TIER.containsKey(s.getItem());
	}

	/** Total hunger points of edible (non-junk) food carried. */
	public static int foodPoints(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties f = s.get(DataComponents.FOOD);
			if (f != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO)) {
				n += f.nutrition() * s.getCount();
			}
		}
		return n;
	}

	// ---- Things we threw away, so we don't go and pick them straight back up.
	private record Tossed(Item item, net.minecraft.world.phys.Vec3 at, long tick) {}

	private static final java.util.List<Tossed> TOSSED = new java.util.ArrayList<>();
	private static final long TOSS_MEMORY = 20 * 300;

	public static void noteTossed(Item item, net.minecraft.world.phys.Vec3 at) {
		long now = dev.humanautopilot.HumanAutopilot.ticks();
		TOSSED.removeIf(t -> now - t.tick() > TOSS_MEMORY);
		TOSSED.add(new Tossed(item, at, now));
	}

	/** A dropped item lying near where we threw that kind of thing away a few minutes ago. */
	public static boolean tossedByUs(net.minecraft.world.entity.item.ItemEntity e) {
		long now = dev.humanautopilot.HumanAutopilot.ticks();
		for (Tossed t : TOSSED) {
			if (now - t.tick() <= TOSS_MEMORY && e.getItem().is(t.item()) && e.position().distanceTo(t.at()) < 10) return true;
		}
		return false;
	}

	/** Not worth walking over to pick up: junk, outclassed tools, or something we threw away ourselves. */
	public static boolean notWorthPickingUp(LocalPlayer p, net.minecraft.world.entity.item.ItemEntity e) {
		ItemStack s = e.getItem();
		return isJunk(s) || obsoleteTool(p, s) || tossedByUs(e);
	}

	/** Stuff a player tosses when their inventory fills up. */
	public static boolean isJunk(ItemStack s) {
		return s.is(Items.DIRT) || s.is(Items.COARSE_DIRT) || s.is(Items.ROOTED_DIRT) || s.is(Items.GRAVEL)
				|| s.is(Items.GRANITE) || s.is(Items.DIORITE) || s.is(Items.ANDESITE) || s.is(Items.TUFF)
				|| s.is(Items.CALCITE) || s.is(Items.DRIPSTONE_BLOCK) || s.is(Items.WHEAT_SEEDS)
				|| s.is(Items.NETHERRACK) || s.is(Items.ROTTEN_FLESH) || s.is(Items.SAND) || s.is(ItemTags.SAPLINGS);
	}

	/** The single item type (matching {@code what}) we have the most of, or null. Furnace slots take one type. */
	public static net.minecraft.world.item.Item mostOf(LocalPlayer p, Predicate<ItemStack> what) {
		java.util.Map<net.minecraft.world.item.Item, Integer> counts = new java.util.HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && what.test(s)) counts.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
	}

	public static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	public static boolean hasJunk(LocalPlayer p) {
		for (int i = 0; i < 36; i++) if (isJunk(p.getInventory().getItem(i))) return true;
		return excessStoneStacks(p) > 0;
	}

	/** Cobblestone beyond two stacks is just weight. */
	public static int excessStoneStacks(LocalPlayer p) {
		int stacks = 0;
		for (int i = 0; i < 36; i++) if (COBBLE.test(p.getInventory().getItem(i))) stacks++;
		return Math.max(0, stacks - 2);
	}
}
