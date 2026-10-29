package dev.humanautopilot.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The creative menu's search box and how far its item grid is scrolled (to find a block in it like a player). */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeScreenAccessor {
	@Accessor("searchBox")
	EditBox humanautopilot$searchBox();

	@Accessor("scrollOffs")
	float humanautopilot$scrollOffs();
}
