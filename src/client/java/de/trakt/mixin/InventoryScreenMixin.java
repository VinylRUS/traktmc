package de.trakt.mixin;

import de.trakt.client.ui.InventoryJournalButtonLayout;
import de.trakt.client.ui.InventoryJournalCompat;
import de.trakt.client.ui.InventoryJournalTutorialState;
import de.trakt.client.ui.TutorialHintRenderer;
import net.minecraft.client.Minecraft;
import de.trakt.client.compat.GuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import de.trakt.Trakt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends Screen {
    @Unique
    private static final Identifier TRAKT$JOURNAL_BUTTON_TEXTURE =
            Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "textures/gui/journal_inventory_button.png");
    @Unique
    private static final int TRAKT$JOURNAL_ICON_SIZE = 14;
    @Unique
    private static final int TRAKT$JOURNAL_TEXTURE_SIZE = 14;
    @Unique
    private static final int TRAKT$FALLBACK_BUTTON_WIDTH = 20;
    @Unique
    private static final int TRAKT$FALLBACK_BUTTON_HEIGHT = 18;
    @Unique
    private static final int TRAKT$FALLBACK_BUTTON_X_OFFSET = 126;
    @Unique
    private static final int TRAKT$FALLBACK_BUTTON_Y_OFFSET = 61;
    @Unique
    private float trakt$journalRevealProgress = 0.0f;
    @Unique
    private Button trakt$journalFallbackButton;

    protected InventoryScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private InventoryJournalButtonLayout.Layout trakt$journalLayout() {
        return InventoryJournalButtonLayout.resolve((HandledScreenAccessor) (Object) this, trakt$useTopRightFallback());
    }

    @Unique
    private int trakt$inventoryLeft() {
        return ((HandledScreenAccessor) (Object) this).trakt$getX();
    }

    @Unique
    private int trakt$inventoryRight() {
        HandledScreenAccessor accessor = (HandledScreenAccessor) (Object) this;
        return accessor.trakt$getX() + accessor.trakt$getBackgroundWidth();
    }

    @Unique
    private int trakt$inventoryTop() {
        return ((HandledScreenAccessor) (Object) this).trakt$getY();
    }

    @Unique
    private boolean trakt$useTopRightFallback() {
        return InventoryJournalCompat.shouldUseTopRightFallback(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$isJournalButtonEnabled() {
        return InventoryJournalCompat.isInventoryJournalButtonEnabled(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$shouldRenderJournalOverlay() {
        return InventoryJournalCompat.shouldRenderInventoryJournalOverlay(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$shouldUseWidgetFallbackButton() {
        return InventoryJournalCompat.shouldUseWidgetFallbackButton(Minecraft.getInstance());
    }

    @Unique
    private boolean trakt$shouldShowJournalTutorial() {
        Minecraft client = Minecraft.getInstance();
        return client != null
                && (trakt$shouldRenderJournalOverlay() || trakt$shouldUseWidgetFallbackButton())
                && client.player != null
                && InventoryJournalTutorialState.shouldShowInventoryHint();
    }

    @Unique
    private void trakt$sendJournalCommand() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        InventoryJournalTutorialState.markInventoryHintSeen();
        client.player.connection.sendCommand("trakt journal open");
    }

    @Unique
    private void trakt$updateFallbackButton() {
        if (this.trakt$journalFallbackButton == null) {
            return;
        }
        boolean visible = trakt$shouldUseWidgetFallbackButton();
        this.trakt$journalFallbackButton.visible = visible;
        this.trakt$journalFallbackButton.active = visible;
        if (!visible) {
            return;
        }
        int x = trakt$inventoryLeft() + TRAKT$FALLBACK_BUTTON_X_OFFSET;
        int y = trakt$inventoryTop() + TRAKT$FALLBACK_BUTTON_Y_OFFSET;
        this.trakt$journalFallbackButton.setPosition(x, y);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void trakt$initFallbackJournalButton(CallbackInfo ci) {
        if (this.trakt$journalFallbackButton == null) {
            this.trakt$journalFallbackButton = Button.builder(
                            Component.literal("J"),
                            button -> trakt$sendJournalCommand()
                    )
                    .bounds(0, 0, TRAKT$FALLBACK_BUTTON_WIDTH, TRAKT$FALLBACK_BUTTON_HEIGHT)
                    .build();
            addRenderableWidget(this.trakt$journalFallbackButton);
        }
        trakt$updateFallbackButton();
    }

    @Unique
    private VisibleArea trakt$visibleArea(InventoryJournalButtonLayout.Layout layout, float revealProgress) {
        int renderX = layout.renderX(revealProgress);
        int renderY = layout.renderY(revealProgress);
        if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_RIGHT) {
            int x = Math.max(renderX, trakt$inventoryRight());
            int width = (renderX + layout.width()) - x;
            return new VisibleArea(x, renderY, width, layout.height());
        }
        if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_TOP_RIGHT) {
            int height = Math.min(layout.height(), trakt$inventoryTop() - renderY);
            return new VisibleArea(renderX, renderY, layout.width(), height);
        }
        return new VisibleArea(renderX, renderY, layout.width(), layout.height());
    }

    @Unique
    private boolean trakt$isHoveringJournalButton(double mouseX, double mouseY, float revealProgress) {
        if (!trakt$shouldRenderJournalOverlay()) {
            return false;
        }
        InventoryJournalButtonLayout.Layout layout = trakt$journalLayout();
        VisibleArea visibleArea = trakt$visibleArea(layout, revealProgress);
        if (visibleArea.width() <= 0 || visibleArea.height() <= 0) {
            return false;
        }
        return mouseX >= visibleArea.x()
                && mouseX < visibleArea.x() + visibleArea.width()
                && mouseY >= visibleArea.y()
                && mouseY < visibleArea.y() + visibleArea.height();
    }

    @Unique
    private boolean trakt$isHoveringJournalButton(double mouseX, double mouseY) {
        return trakt$isHoveringJournalButton(mouseX, mouseY, trakt$journalRevealProgress);
    }

    @Inject(
            method = "extractBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void trakt$renderJournalButtonBehindInventory(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        trakt$updateFallbackButton();
        if (!trakt$shouldRenderJournalOverlay()) {
            return;
        }
        try {
            InventoryJournalButtonLayout.Layout layout = trakt$journalLayout();
            boolean hovered = trakt$isHoveringJournalButton(mouseX, mouseY);
            boolean tutorialVisible = trakt$shouldShowJournalTutorial();
            trakt$journalRevealProgress = Mth.lerp(0.35f, trakt$journalRevealProgress, hovered || tutorialVisible ? 1.0f : 0.0f);
            if (trakt$journalRevealProgress < 0.02f) {
                trakt$journalRevealProgress = 0.0f;
            } else if (trakt$journalRevealProgress > 0.98f) {
                trakt$journalRevealProgress = 1.0f;
            }

            int x = layout.renderX(trakt$journalRevealProgress);
            int y = layout.renderY(trakt$journalRevealProgress);
            int width = layout.width();
            int height = layout.height();
            int border = hovered ? 0xFFF0D7A7 : 0xFF6F4A2C;
            int inner = hovered ? 0xCC3A2617 : 0xCC24170E;
            int accent = hovered ? 0x99F5E1B7 : 0x664A3420;

            if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_RIGHT) {
                int overlap = layout.overlap();
                int contentWidth = layout.contentWidth();
                int contentX = x + overlap;

                context.fill(x, y, x + width, y + height, border);
                context.fill(x + 1, y + 1, x + width - 1, y + height - 1, inner);
                context.fill(contentX + 1, y + 3, x + width - 2, y + 4, accent);
                context.fill(contentX + 1, y + height - 5, x + width - 2, y + height - 4, accent);
                context.fill(x + 2, y + 5, x + overlap + 1, y + 7, hovered ? 0xFFF0D7A7 : 0xFFB88A46);
                context.fill(x + 2, y + height - 8, x + overlap + 1, y + height - 6, hovered ? 0xFFF0D7A7 : 0xFFB88A46);
            } else if (layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_TOP_RIGHT) {
                int overlap = layout.overlap();
                int contentHeight = layout.contentHeight();
                int contentY = y;

                context.fill(x, y, x + width, y + height, border);
                context.fill(x + 1, y + 1, x + width - 1, y + height - 1, inner);
                context.fill(x + 3, contentY + 1, x + 4, y + contentHeight - 1, accent);
                context.fill(x + width - 5, contentY + 1, x + width - 4, y + contentHeight - 1, accent);
                context.fill(x + 5, y + height - overlap - 1, x + width - 5, y + height - overlap + 1, hovered ? 0xFFF0D7A7 : 0xFFB88A46);
            } else {
                context.fill(x, y, x + width, y + height, border);
                context.fill(x + 1, y + 1, x + width - 1, y + height - 1, inner);
            }

            int iconAreaWidth = layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_RIGHT
                    ? layout.contentWidth()
                    : width;
            int iconBaseX = layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_RIGHT
                    ? x + layout.overlap()
                    : x;
            int iconAreaHeight = layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_TOP_RIGHT
                    ? layout.contentHeight()
                    : height;
            int iconBaseY = layout.mode() == InventoryJournalButtonLayout.LayoutMode.BOOKMARK_TAB_TOP_RIGHT
                    ? y
                    : y;
            int iconX = iconBaseX + Math.max(1, (iconAreaWidth - TRAKT$JOURNAL_ICON_SIZE) / 2);
            int iconY = iconBaseY + Math.max(1, (iconAreaHeight - TRAKT$JOURNAL_ICON_SIZE) / 2);
            context.blit(
                    RenderPipelines.GUI_TEXTURED,
                    TRAKT$JOURNAL_BUTTON_TEXTURE,
                    iconX,
                    iconY,
                    0.0f,
                    0.0f,
                    TRAKT$JOURNAL_ICON_SIZE,
                    TRAKT$JOURNAL_ICON_SIZE,
                    TRAKT$JOURNAL_TEXTURE_SIZE,
                    TRAKT$JOURNAL_TEXTURE_SIZE
            );

        } catch (Throwable throwable) {
            InventoryJournalCompat.disableInventoryJournalOverlayForSession("inventory journal button render", throwable);
        }
    }

    @Unique
    private void trakt$renderJournalTutorial(GuiGraphicsExtractor context) {
        if (!trakt$shouldShowJournalTutorial()) {
            return;
        }

        int targetX;
        int targetY;
        int targetWidth;
        int targetHeight;
        if (trakt$shouldUseWidgetFallbackButton()
                && this.trakt$journalFallbackButton != null
                && this.trakt$journalFallbackButton.visible) {
            targetX = this.trakt$journalFallbackButton.getX();
            targetY = this.trakt$journalFallbackButton.getY();
            targetWidth = TRAKT$FALLBACK_BUTTON_WIDTH;
            targetHeight = TRAKT$FALLBACK_BUTTON_HEIGHT;
        } else if (trakt$shouldRenderJournalOverlay()) {
            InventoryJournalButtonLayout.Layout layout = trakt$journalLayout();
            targetX = layout.renderX(trakt$journalRevealProgress);
            targetY = layout.renderY(trakt$journalRevealProgress);
            targetWidth = layout.width();
            targetHeight = layout.height();
        } else {
            return;
        }

        TutorialHintRenderer.drawHint(
                new GuiGraphics(context),
                Minecraft.getInstance().font,
                Component.translatable("screen.trakt.inventory.journal_button_tutorial"),
                this.width,
                this.height,
                targetX,
                targetY,
                targetWidth,
                targetHeight,
                TutorialHintRenderer.Placement.RIGHT,
                true,
                (int) Math.round(Math.sin(System.currentTimeMillis() / 180.0d) * 2.0d)
        );
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void trakt$renderJournalTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        trakt$updateFallbackButton();
        trakt$renderJournalTutorial(context);
        if (!trakt$shouldRenderJournalOverlay()) {
            return;
        }
        boolean hovered = trakt$isHoveringJournalButton(mouseX, mouseY);
        if (hovered) {
            context.setTooltipForNextFrame(Minecraft.getInstance().font, Component.translatable("screen.trakt.inventory.journal_button"), mouseX, mouseY);
        }
    }

    @Unique
    private record VisibleArea(int x, int y, int width, int height) {}
}
