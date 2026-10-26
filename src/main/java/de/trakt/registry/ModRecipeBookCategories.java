package de.trakt.registry;

import de.trakt.Trakt;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeBookCategory;

public final class ModRecipeBookCategories {
    public static RecipeBookCategory TRAKT;

    private ModRecipeBookCategories() {}

    public static void register() {
        if (TRAKT != null) return;
        TRAKT = Registry.register(
                BuiltInRegistries.RECIPE_BOOK_CATEGORY,
                Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "trakt"),
                new RecipeBookCategory());
        Trakt.LOGGER.info("Registered Trakt recipe-book category");
    }
}
