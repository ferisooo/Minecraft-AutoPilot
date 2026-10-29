package dev.humanautopilot.mixin;

import dev.humanautopilot.HumanAutopilot;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's own "the player did something" calls (start digging, a block broken, a right-click on a block, a slot
 * clicked, a recipe clicked, a mob hit), passed to the recorder. Read-only: nothing is changed.
 */
@Mixin(MultiPlayerGameMode.class)
public class GameModeMixin {
	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void humanautopilot$digStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		HumanAutopilot.recorder().onDigStart(pos, face);
	}

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void humanautopilot$broke(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		HumanAutopilot.recorder().onBroke(pos);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void humanautopilot$useOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		HumanAutopilot.recorder().onUseOn(hand, hit);
	}

	@Inject(method = "handleContainerInput", at = @At("HEAD"))
	private void humanautopilot$click(int containerId, int slot, int button, ContainerInput type, Player player, CallbackInfo ci) {
		HumanAutopilot.recorder().onSlotClick(slot, button, type);
	}

	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"))
	private void humanautopilot$recipe(int containerId, RecipeDisplayId recipe, boolean useMaxItems, CallbackInfo ci) {
		HumanAutopilot.recorder().onRecipe(useMaxItems);
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void humanautopilot$attack(Player player, Entity target, CallbackInfo ci) {
		HumanAutopilot.recorder().onHit(target);
	}
}
