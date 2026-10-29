package dev.humanautopilot.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int humanautopilot$getClickCount();

	@Accessor("clickCount")
	void humanautopilot$setClickCount(int count);
}
