package de.trakt.quest.daily;

import de.trakt.quest.QuestCompletionMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public interface DailyQuestDefinition {
    DailyQuestService.DailyQuestType type();

    Component title();

    Component offerParagraph1();

    Component offerParagraph2();

    Component progressLine(ServerLevel world, UUID playerId);

    boolean isComplete(ServerLevel world, ServerPlayer player);

    DailyQuestCompletion buildCompletion(ServerLevel world);

    default QuestCompletionMode completionMode() { return QuestCompletionMode.AUTOMATIC; }

    default boolean consumeCompletionRequirements(ServerLevel world, ServerPlayer player) { return true; }

    default Component claimBlockedMessage(ServerLevel world, ServerPlayer player) { return null; }

    default void onAccepted(ServerLevel world, ServerPlayer player) {}

    default void onServerTick(ServerLevel world, ServerPlayer player) {}

    default void onBlockBreak(ServerLevel world, ServerPlayer player, BlockPos pos, BlockState state) {}

    default void onBeeNestInteract(ServerLevel world, ServerPlayer player, BlockState state, ItemStack inHand) {}

    default void onEntityUse(ServerLevel world, ServerPlayer player, Entity entity, ItemStack inHand) {}

    /** F2b: a wolf/cat collar was successfully recolored by the player. */
    default void onCollarRecolor(ServerLevel world, ServerPlayer player) {}

    /** F2b part 2: an animal was successfully tamed (TamableAnimalMixin). */
    default void onAnimalTamed(ServerLevel world, ServerPlayer player, TamableAnimal animal) {}

    /** F2b part 2: an anvil combine produced a newly enchanted item (Anvil/Forging mixins). */
    default void onItemEnchanted(ServerLevel world, ServerPlayer player, ItemStack output) {}

    /** F2b part 2: the player finished brewing a potion (BrewedPotionCriterionMixin). */
    default void onPotionBrewed(ServerLevel world, ServerPlayer player) {}

    /**
     * F2b part 2: the player stands inside a detected village. {@code villageKey} is a
     * stable identity (dimension, centre coordinates) for distinct-visit tracking.
     */
    default void onVillageVisited(ServerLevel world, ServerPlayer player, String villageKey) {}

    /** F2b part 2: the player escorted a guild convoy arrival in person (GuildConvoyService). */
    default void onCaravanEscorted(ServerLevel world, ServerPlayer player) {}

    /** F2b part 2: a trade-guild freight contract arrived supplied (TradeGuildService). */
    default void onItemDelivered(ServerLevel world, ServerPlayer player, ItemStack cargo) {}

    default void onSheepSheared(ServerLevel world, ServerPlayer player, Sheep sheep) {}

    default void onTrackedItemPickup(ServerLevel world, ServerPlayer player, ItemStack stack, int count) {}

    default void onFurnaceOutput(ServerLevel world, ServerPlayer player, ItemStack stack) {}

    default void onVillagerTrade(ServerLevel world, ServerPlayer player, ItemStack stack) {}

    default void onAnimalLove(ServerLevel world, ServerPlayer player, Animal animal) {}

    default void onMonsterKill(ServerLevel world, ServerPlayer player, Entity killedEntity) {}
}
