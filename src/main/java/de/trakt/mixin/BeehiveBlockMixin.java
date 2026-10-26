package de.trakt.mixin;

import de.trakt.pilgrim.PilgrimContractService;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.story.StoryQuestService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BeehiveBlock.class)
public abstract class BeehiveBlockMixin {
    @Inject(
            method = "useItemOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void trakt$trackSuccessfulHoneycombCut(ItemStack stack,
                                                           BlockState state,
                                                           Level level,
                                                           BlockPos pos,
                                                           Player player,
                                                           InteractionHand hand,
                                                           BlockHitResult hit,
                                                           CallbackInfoReturnable<InteractionResult> cir) {
        trakt$trackSuccessfulHarvest(level, player, state, Items.SHEARS.getDefaultInstance());
    }

    @Inject(
            method = "useItemOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V",
                    shift = At.Shift.AFTER
            )
    )
    private void trakt$trackSuccessfulHoneyBottleFill(ItemStack stack,
                                                             BlockState state,
                                                             Level level,
                                                             BlockPos pos,
                                                             Player player,
                                                             InteractionHand hand,
                                                             BlockHitResult hit,
                                                             CallbackInfoReturnable<InteractionResult> cir) {
        trakt$trackSuccessfulHarvest(level, player, state, Items.GLASS_BOTTLE.getDefaultInstance());
    }

    private static void trakt$trackSuccessfulHarvest(Level level, Player player, BlockState state, ItemStack tool) {
        if (!(level instanceof ServerLevel world) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        DailyQuestService.onBeeNestInteract(world, serverPlayer, state, tool);
        StoryQuestService.onBeeNestInteract(world, serverPlayer, state, tool);
        SpecialQuestService.onBeeNestInteract(world, serverPlayer, state, tool);
        PilgrimContractService.onBeeNestInteract(world, serverPlayer, state, tool);
        de.trakt.guildtown.GuildTownService.onHoneyHarvest(world, serverPlayer);
    }
}
