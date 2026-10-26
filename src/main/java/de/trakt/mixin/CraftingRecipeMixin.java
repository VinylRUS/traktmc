package de.trakt.mixin;

import de.trakt.registry.ModRecipeBookCategories;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CraftingRecipe.class)
public interface CraftingRecipeMixin {
    @Inject(method = "recipeBookCategory", at = @At("HEAD"), cancellable = true)
    private void trakt$useTraktCategory(CallbackInfoReturnable<RecipeBookCategory> cir) {
        CraftingRecipe recipe = (CraftingRecipe) (Object) this;
        if ("trakt".equals(recipe.group()) && ModRecipeBookCategories.TRAKT != null) {
            cir.setReturnValue(ModRecipeBookCategories.TRAKT);
        }
    }
}
