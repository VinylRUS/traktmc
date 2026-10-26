package de.trakt.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("leftPos")
    int trakt$getX();

    @Accessor("topPos")
    int trakt$getY();

    @Accessor("imageWidth")
    int trakt$getBackgroundWidth();

    @Accessor("imageHeight")
    int trakt$getBackgroundHeight();
}
