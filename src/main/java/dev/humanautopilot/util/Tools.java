package dev.humanautopilot.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

/** Picks hotbar items the way a player would: best tool, best weapon, best food. Hotbar only. */
public final class Tools {
	public static final int NONE = -1;

	private Tools() {}

	public static int durabilityLeft(ItemStack s) {
		return s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : Integer.MAX_VALUE;
	}

	/**
	 * Best hotbar slot to break {@code state}. Returns {@link #NONE} if the block needs a specific
	 * tool for drops and no usable one is in the hotbar.
	 */
	public static int bestToolSlot(LocalPlayer p, BlockState state, int minDurability) {
		var inv = p.getInventory();
		boolean needsTool = state.requiresCorrectToolForDrops();
		if (needsTool) {
			// Save the good pickaxe: for rock a stone pickaxe can handle, use the cheapest one that works (stone
			// before iron before diamond), like a player keeping their iron pickaxe for ore. Wooden/golden only
			// if nothing better is there.
			int cheap = NONE;
			float cheapSpeed = Float.MAX_VALUE;
			for (int i = 0; i < 9; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || durabilityLeft(s) <= minDurability || !s.isCorrectToolForDrops(state)) continue;
				float speed = s.getDestroySpeed(state);
				if (speed >= 3.9f && speed < 10f && speed < cheapSpeed) { // stone 4, iron 6, diamond 8 (gold 12 is too fragile)
					cheapSpeed = speed;
					cheap = i;
				}
			}
			if (cheap != NONE) return cheap;
		}
		int best = NONE;
		float bestSpeed = needsTool ? 0f : 1.0f;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || durabilityLeft(s) <= minDurability) continue;
			if (needsTool && !s.isCorrectToolForDrops(state)) continue;
			float speed = s.getDestroySpeed(state);
			if (speed > bestSpeed || (needsTool && best == NONE)) {
				bestSpeed = speed;
				best = i;
			}
		}
		if (best == NONE && needsTool) {
			// Only a worn-out tool can do it: use it till it breaks, like a player would (a new one gets made meanwhile).
			for (int i = 0; i < 9; i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty() && s.isCorrectToolForDrops(state)) return i;
			}
		}
		if (best == NONE && !needsTool) {
			// Nothing better than a fist; prefer a slot that won't waste a tool's durability.
			int current = inv.getSelectedSlot();
			if (!inv.getItem(current).isDamageableItem()) return current;
			for (int i = 0; i < 9; i++) if (!inv.getItem(i).isDamageableItem()) return i;
			return current;
		}
		return best;
	}

	public static double meleeDps(ItemStack s) {
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double dmg = mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
		double speed = mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
		return dmg * Math.max(0.25, speed);
	}

	public static int bestWeaponSlot(LocalPlayer p) {
		var inv = p.getInventory();
		int best = inv.getSelectedSlot();
		double bestDps = meleeDps(inv.getItem(best));
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || durabilityLeft(s) <= 2) continue;
			double dps = meleeDps(s);
			if (dps > bestDps + 0.01) {
				bestDps = dps;
				best = i;
			}
		}
		return best;
	}

	private static boolean isBadFood(ItemStack s) {
		return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO)
				|| s.is(Items.PUFFERFISH) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.CHORUS_FRUIT);
	}

	private static boolean isPrecious(ItemStack s) {
		return s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_CARROT);
	}

	/** Best food in the hotbar. Junk food only when starving; golden food only in an emergency. */
	public static int bestFoodSlot(LocalPlayer p, boolean starving, boolean emergency) {
		var inv = p.getInventory();
		int best = NONE;
		double bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food == null) continue;
			if (isBadFood(s) && !starving) continue;
			if (isPrecious(s) && !emergency && !s.is(Items.GOLDEN_CARROT)) continue;
			double score = food.nutrition() + food.saturation();
			if (isBadFood(s)) score -= 20; // last resort even when starving
			if (emergency && isPrecious(s)) score += 50;
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	public static boolean hasShieldInOffhand(LocalPlayer p) {
		return p.getOffhandItem().has(DataComponents.BLOCKS_ATTACKS);
	}
}
