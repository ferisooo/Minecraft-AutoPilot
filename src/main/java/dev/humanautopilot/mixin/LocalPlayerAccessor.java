package dev.humanautopilot.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The camera angles the server last heard from us: a block's facing is decided with those. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Accessor("yRotLast")
	float humanautopilot$yRotLast();

	@Accessor("xRotLast")
	float humanautopilot$xRotLast();
}
