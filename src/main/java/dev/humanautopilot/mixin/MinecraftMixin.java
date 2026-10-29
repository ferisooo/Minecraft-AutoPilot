package dev.humanautopilot.mixin;

import dev.humanautopilot.HumanAutopilot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the game running when its window loses focus (clicking another window, alt-tab): vanilla opens the pause
 * menu then, which also stops the autopilot. Leaves the "Pause on lost focus" option itself alone, so turning
 * {@code keepRunningUnfocused} off brings the normal behaviour straight back.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void humanautopilot$keepRunning(CallbackInfo ci) {
		var config = HumanAutopilot.config();
		if (config != null && config.keepRunningUnfocused) ci.cancel();
	}
}
