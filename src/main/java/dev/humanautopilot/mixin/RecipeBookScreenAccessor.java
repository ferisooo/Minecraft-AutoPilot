package dev.humanautopilot.mixin;

import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The recipe book inside the crafting / inventory screen, and where its green book button sits. */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookScreenAccessor {
	@Accessor("recipeBookComponent")
	RecipeBookComponent<?> humanautopilot$book();

	@Invoker("getRecipeBookButtonPosition")
	ScreenPosition humanautopilot$bookButtonPos();
}
