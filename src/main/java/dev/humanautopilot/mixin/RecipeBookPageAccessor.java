package dev.humanautopilot.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The recipe buttons shown on the current page of the recipe book. */
@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {
	@Accessor("buttons")
	List<RecipeButton> humanautopilot$buttons();
}
