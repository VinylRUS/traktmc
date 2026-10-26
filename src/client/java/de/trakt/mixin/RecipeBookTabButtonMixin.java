package de.trakt.mixin;

import de.trakt.registry.ModRecipeBookCategories;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBookTabButton.class)
public abstract class RecipeBookTabButtonMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void trakt$labelRecipeTab(int x, int y, RecipeBookComponent.TabInfo tabInfo,
                                              Button.OnPress onPress, CallbackInfo ci) {
        if (tabInfo.category() == ModRecipeBookCategories.TRAKT) {
            ((RecipeBookTabButton) (Object) this).setTooltip(Tooltip.create(
                    Component.translatable("gui.trakt.recipe_book.category")));
        }
    }
}
