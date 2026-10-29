package dev.humanautopilot.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The recipe book's page of recipe buttons, its search box and its tabs. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
	@Accessor("recipeBookPage")
	RecipeBookPage humanautopilot$page();

	@Accessor("searchBox")
	EditBox humanautopilot$searchBox();

	@Accessor("tabButtons")
	List<RecipeBookTabButton> humanautopilot$tabs();

	@Accessor("selectedTab")
	RecipeBookTabButton humanautopilot$selectedTab();
}
