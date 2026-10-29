package dev.humanautopilot.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What a block item would put down for a click (schematic mode checks a click gives the right facing first). */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
	@Invoker("getPlacementState")
	BlockState humanautopilot$placementState(BlockPlaceContext ctx);
}
