package dev.humanautopilot.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void humanautopilot$setXpos(double x);

	@Accessor("ypos")
	void humanautopilot$setYpos(double y);

	/** The real scroll-wheel handler (one notch = offset ±1), same path a physical wheel takes. */
	@org.spongepowered.asm.mixin.gen.Invoker("onScroll")
	void humanautopilot$scroll(long window, double xOffset, double yOffset);
}
