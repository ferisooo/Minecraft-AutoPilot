package dev.humanautopilot.mixin;

import dev.humanautopilot.modules.BoxFillModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the scoreboard sidebar while box fill is running (the user wants a clean screen on stream). */
@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void humanautopilot$hideSidebar(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		if (BoxFillModule.get().on()) ci.cancel();
	}
}
