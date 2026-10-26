package de.trakt.mixin;

import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.special.ShardRelicQuestService;
import net.minecraft.advancements.triggers.BrewedPotionTrigger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.alchemy.PotionContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewedPotionTrigger.class)
public abstract class BrewedPotionCriterionMixin {
    @Inject(method = "trigger", at = @At("TAIL"))
    private void trakt$trackShardRelicPotion(ServerPlayer player, PotionContents potion, CallbackInfo ci) {
        ShardRelicQuestService.onPotionBrewed(player);
        if (player.level() instanceof ServerLevel world) {
            DailyQuestService.onPotionBrewed(world, player);
        }
    }
}
