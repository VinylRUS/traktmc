package de.trakt.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import de.trakt.client.ui.InventoryJournalButtonLayout;
import de.trakt.client.ui.InventoryJournalCompat;
import de.trakt.client.ui.InventoryJournalTutorialState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    protected HandledScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private boolean trakt$isPlayerInventoryScreen() {
        return (Object) this instanceof InventoryScreen;
    }

    @Unique
    private InventoryJournalButtonLayout.Layout trakt$journalLayout() {
        return InventoryJournalButtonLayout.resolve((HandledScreenAccessor) (Object) this, trakt$useTopRightFallback());
    }

    @Unique
    private boolean trakt$useTopRightFallback() {
        return InventoryJournalCompat.shouldUseTopRightFallback(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$isJournalButtonEnabled() {
        return InventoryJournalCompat.shouldRenderInventoryJournalOverlay(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$isHoveringJournalButton(double mouseX, double mouseY) {
        if (!trakt$isJournalButtonEnabled()) {
            return false;
        }
        InventoryJournalButtonLayout.Layout layout = trakt$journalLayout();
        int inventoryTop = this.topPos;
        int inventoryRight = this.leftPos + ((HandledScreenAccessor) (Object) this).trakt$getBackgroundWidth();
        int x = layout.expandedX();
        int y = layout.expandedY();
        int visibleWidth = layout.width();
        int visibleHeight = layout.height();
        if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_RIGHT) {
            x = Math.max(layout.expandedX(), inventoryRight);
            visibleWidth = (layout.expandedX() + layout.width()) - x;
        } else if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_TOP_RIGHT) {
            visibleHeight = Math.min(layout.height(), inventoryTop - y);
        }
        if (visibleWidth <= 0 || visibleHeight <= 0) {
            return false;
        }
        return mouseX >= x
                && mouseX < x + visibleWidth
                && mouseY >= y
                && mouseY < y + visibleHeight;
    }

    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
    private void trakt$clickJournalButton(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (!trakt$isPlayerInventoryScreen()
                || !trakt$isJournalButtonEnabled()
                || click.button() != InputConstants.MOUSE_BUTTON_LEFT
                || !trakt$isHoveringJournalButton(click.x(), click.y())) {
            return;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.connection != null) {
                InventoryJournalTutorialState.markInventoryHintSeen();
                client.player.connection.sendCommand("trakt journal open");
                cir.setReturnValue(true);
            }
        } catch (Throwable throwable) {
            InventoryJournalCompat.disableInventoryJournalOverlayForSession("inventory journal button click", throwable);
        }
    }
}
