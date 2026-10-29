package dev.humanautopilot.mixin;

import dev.humanautopilot.HumanAutopilot;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs once per rendered frame, right after vanilla applies real mouse movement.
 * The aim controller adds its own small per-frame turn here, so the camera moves
 * exactly like a mouse would instead of snapping once per tick.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	/** The bot is working in a menu: the real mouse doesn't move its cursor or click (no tug of war). */
	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void humanautopilot$move(long window, double x, double y, CallbackInfo ci) {
		if (HumanAutopilot.botOwnsScreen()) ci.cancel();
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void humanautopilot$button(long window, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
		if (HumanAutopilot.botOwnsScreen()) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void humanautopilot$scroll(long window, double x, double y, CallbackInfo ci) {
		if (HumanAutopilot.botOwnsScreen()) ci.cancel();
	}

	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void humanautopilot$onFrame(CallbackInfo ci) {
		HumanAutopilot.onFrame();
	}
}
