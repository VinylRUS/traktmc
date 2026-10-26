package de.trakt.mixin;

import de.trakt.access.ForgingQuickMoveState;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.special.ShardRelicQuestService;
import de.trakt.quest.story.StoryQuestService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemCombinerMenu.class)
public abstract class ForgingScreenHandlerMixin implements ForgingQuickMoveState {
    @Shadow @Final protected Container inputSlots;

    @Unique private ItemStack trakt$anvilQuickMoveLeftInput = ItemStack.EMPTY;
    @Unique private ItemStack trakt$anvilQuickMoveRightInput = ItemStack.EMPTY;
    @Unique private boolean trakt$quickMovingResult;

    @Inject(method = "quickMoveStack", at = @At("HEAD"))
    private void trakt$captureAnvilQuickMoveInputs(Player player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        trakt$clearAnvilQuickMoveInputs();
        trakt$quickMovingResult = false;
        if (!((Object) this instanceof AnvilMenu handler)) {
            return;
        }
        if (slot != handler.getResultSlot()) {
            return;
        }

        trakt$quickMovingResult = true;
        trakt$anvilQuickMoveLeftInput = this.inputSlots.getItem(0).copy();
        trakt$anvilQuickMoveRightInput = this.inputSlots.getItem(1).copy();
    }

    @Inject(method = "quickMoveStack", at = @At("RETURN"))
    private void trakt$trackAnvilQuickMoveOutput(Player player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        try {
            if (!((Object) this instanceof AnvilMenu handler)) {
                return;
            }
            if (slot != handler.getResultSlot()) {
                return;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            ItemStack output = cir.getReturnValue();
            if (output == null || output.isEmpty()) {
                return;
            }

            ServerLevel world = (ServerLevel) serverPlayer.level();
            StoryQuestService.onAnvilOutput(world, serverPlayer, trakt$anvilQuickMoveLeftInput, trakt$anvilQuickMoveRightInput, output.copy());
            SpecialQuestService.onAnvilOutput(world, serverPlayer, trakt$anvilQuickMoveLeftInput, trakt$anvilQuickMoveRightInput, output.copy());
            ShardRelicQuestService.onAnvilOutput(serverPlayer, trakt$anvilQuickMoveLeftInput, trakt$anvilQuickMoveRightInput, output.copy());
            DailyQuestService.onAnvilOutput(world, serverPlayer, trakt$anvilQuickMoveLeftInput, trakt$anvilQuickMoveRightInput, output.copy());
        } finally {
            trakt$clearAnvilQuickMoveInputs();
            trakt$quickMovingResult = false;
        }
    }

    @Override
    public boolean trakt$isQuickMovingResult() {
        return trakt$quickMovingResult;
    }

    @Unique
    private void trakt$clearAnvilQuickMoveInputs() {
        trakt$anvilQuickMoveLeftInput = ItemStack.EMPTY;
        trakt$anvilQuickMoveRightInput = ItemStack.EMPTY;
    }
}
