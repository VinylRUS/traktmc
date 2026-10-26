package de.trakt.mixin;

import de.trakt.access.ForgingQuickMoveState;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.special.ShardRelicQuestService;
import de.trakt.quest.story.StoryQuestService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilScreenHandlerMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void trakt$trackAnvilEnchant(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer) || stack.isEmpty()) {
            return;
        }
        if ((Object) this instanceof ForgingQuickMoveState quickMove
                && quickMove.trakt$isQuickMovingResult()) {
            return;
        }

        ForgingScreenHandlerAccessor accessor = (ForgingScreenHandlerAccessor) this;
        ItemStack leftInput = accessor.trakt$getInput().getItem(0).copy();
        ItemStack rightInput = accessor.trakt$getInput().getItem(1).copy();
        ServerLevel world = (ServerLevel) serverPlayer.level();
        StoryQuestService.onAnvilOutput(world, serverPlayer, leftInput, rightInput, stack.copy());
        SpecialQuestService.onAnvilOutput(world, serverPlayer, leftInput, rightInput, stack.copy());
        ShardRelicQuestService.onAnvilOutput(serverPlayer, leftInput, rightInput, stack.copy());
        DailyQuestService.onAnvilOutput(world, serverPlayer, leftInput, rightInput, stack.copy());
    }
}
