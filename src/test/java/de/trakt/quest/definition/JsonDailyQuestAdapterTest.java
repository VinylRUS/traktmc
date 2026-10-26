package de.trakt.quest.definition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.trakt.data.PlayerQuestData;
import de.trakt.data.QuestState;
import de.trakt.quest.QuestSoundFeedback;
import de.trakt.quest.daily.DailyQuestGenerator;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.util.TimeUtil;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/**
 * Runs the JSON built-in pack through the retired Java quest behaviour on the shared
 * daily engine: concrete block targets, concrete entity types, staged smelt goals,
 * questmaster turn-in gating and the flat pairs progress line.
 *
 * <p>Tag references ({@code #minecraft:logs}, {@code #minecraft:zombies}) are resolved
 * by the server resource manager at data load; in this unit scope only registry-backed
 * concrete targets are exercised.
 */
final class JsonDailyQuestAdapterTest {

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var item : java.util.List.of(Items.RAW_IRON, Items.COAL, Items.IRON_INGOT,
                Items.WHEAT, Items.BREAD)) {
            item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        }
        QuestDefinitionRegistry.get().loadBuiltinDirectoryForTests(
                Path.of("src/main/resources/data/trakt/quests"));
    }

    @AfterAll
    static void resetSoundFeedback() {
        // progress hooks leave feedback entries behind; drop them for neighbouring tests
        QuestSoundFeedback.resetRuntimeState();
    }

    private record Fixture(ServerLevel world, ServerPlayer player, UUID id, PlayerQuestData data,
                            AutoCloseable statics) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            statics.close();
        }
    }

    private static QuestState freshState() throws Exception {
        Method load = QuestState.class.getDeclaredMethod("fromNbt", CompoundTag.class);
        load.setAccessible(true);
        return (QuestState) load.invoke(null, new CompoundTag());
    }

    /** world + player mocks with the given daily quest accepted for today. */
    private static Fixture acceptedToday(DailyQuestService.DailyQuestType type) throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var player = mock(ServerPlayer.class, Answers.RETURNS_DEEP_STUBS);
        var playerList = mock(net.minecraft.server.players.PlayerList.class);
        when(world.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(playerList);
        doReturn(player).when(playerList).getPlayer(any(UUID.class));
        when(player.level()).thenReturn(world);
        // progress hooks refresh the tracker UI; give the send path a listener stub
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class,
                Answers.RETURNS_DEEP_STUBS);
        when(player.getStats().getValue(any())).thenReturn(0);
        QuestState state = freshState();
        UUID id = UUID.randomUUID();
        PlayerQuestData data = state.getPlayerData(id);
        when(player.getUUID()).thenReturn(id);
        data.setDailyChoice(type);
        data.setDailyChoiceDay(TimeUtil.currentDay());
        data.setAcceptedDay(TimeUtil.currentDay());
        var access = mockStatic(QuestState.class);
        access.when(() -> QuestState.get(server)).thenReturn(state);
        return new Fixture(world, player, id, data, access);
    }

    private static JsonDailyQuestAdapter adapter(DailyQuestService.DailyQuestType type) {
        var definition = QuestDefinitionRegistry.get().find(DailyQuestGenerator.slotId(type)).orElseThrow();
        return new JsonDailyQuestAdapter(type, definition);
    }

    @Test
    void autumnHarvestAdvancesOnPumpkinAndMelonBreaks() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.AUTUMN_HARVEST)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.AUTUMN_HARVEST);
            adapter.onBlockBreak(fixture.world(), fixture.player(), BlockPos.ZERO, Blocks.PUMPKIN.defaultBlockState());
            assertEquals(1, fixture.data().getDailyInt("daily.autumn.pumpkin.progress"));
            adapter.onBlockBreak(fixture.world(), fixture.player(), BlockPos.ZERO, Blocks.MELON.defaultBlockState());
            assertEquals(1, fixture.data().getDailyInt("daily.autumn.melon.progress"));
            adapter.onBlockBreak(fixture.world(), fixture.player(), BlockPos.ZERO, Blocks.STONE.defaultBlockState());
            assertEquals(1, fixture.data().getDailyInt("daily.autumn.pumpkin.progress"));
            assertEquals(1, fixture.data().getDailyInt("daily.autumn.melon.progress"));
        }
    }

    @Test
    void creeperWatchCountsOnlyCreepers() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.CREEPER_WATCH)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.CREEPER_WATCH);
            Entity creeper = mock(Entity.class);
            doReturn(BuiltInRegistries.ENTITY_TYPE.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "creeper"))).when(creeper).getType();
            adapter.onMonsterKill(fixture.world(), fixture.player(), creeper);
            assertEquals(1, fixture.data().getDailyInt("daily.creeper_watch.progress"));
            Entity cow = mock(Entity.class);
            doReturn(BuiltInRegistries.ENTITY_TYPE.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "cow"))).when(cow).getType();
            adapter.onMonsterKill(fixture.world(), fixture.player(), cow);
            assertEquals(1, fixture.data().getDailyInt("daily.creeper_watch.progress"));
        }
    }

    @Test
    void smithSmeltGoalIsStagedUntilOreCollected() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.SMITH_SMELTING)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.SMITH_SMELTING);
            // ingots smelted before the ore quota do not count yet
            adapter.onFurnaceOutput(fixture.world(), fixture.player(), new ItemStack(Items.IRON_INGOT, 8));
            assertEquals(0, fixture.data().getDailyInt("daily.smith_smelt.ingot.progress"));
            // raw iron pickups fill the ore quota exactly to the tuned cap
            int oreTarget = de.trakt.quest.repeatable.RepeatableTargetTuning.adjust(
                    100, de.trakt.quest.repeatable.RepeatableTargetProfile.NORMAL, "daily.smelting.ore");
            adapter.onTrackedItemPickup(fixture.world(), fixture.player(), new ItemStack(Items.RAW_IRON, 200), 200);
            assertEquals(oreTarget, fixture.data().getDailyInt("daily.smith_smelt.ore.progress"));
            // now smelting counts
            adapter.onFurnaceOutput(fixture.world(), fixture.player(), new ItemStack(Items.IRON_INGOT, 8));
            assertEquals(8, fixture.data().getDailyInt("daily.smith_smelt.ingot.progress"));
        }
    }

    @Test
    void turnInQuestBlocksCompletionUntilItemsAreCarried() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.COAL_MINING)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.COAL_MINING);
            // fill both goals beyond their tuned targets (pin the progress day first,
            // otherwise ensureCurrentProgressDay wipes the counters on first read)
            fixture.data().setProgressDay(TimeUtil.currentDay());
            fixture.data().setDailyInt("daily.iron.progress", 100);
            fixture.data().setDailyInt("daily.smith.coal.progress", 100);
            // empty inventory: completion is blocked with the "bring the items" line
            assertNotNull(adapter.claimBlockedMessage(fixture.world(), fixture.player()));
            assertFalse(adapter.isComplete(fixture.world(), fixture.player()));
            // carrying the required stacks unblocks it (tuned counts stay below 60)
            when(fixture.player().getInventory().getContainerSize()).thenReturn(2);
            when(fixture.player().getInventory().getItem(0)).thenReturn(new ItemStack(Items.RAW_IRON, 60));
            when(fixture.player().getInventory().getItem(1)).thenReturn(new ItemStack(Items.COAL, 60));
            assertNull(adapter.claimBlockedMessage(fixture.world(), fixture.player()));
            assertTrue(adapter.isComplete(fixture.world(), fixture.player()));
        }
    }

    @Test
    void pairsProgressLineCarriesPerGoalArguments() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.AUTUMN_HARVEST)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.AUTUMN_HARVEST);
            adapter.onBlockBreak(fixture.world(), fixture.player(), BlockPos.ZERO, Blocks.PUMPKIN.defaultBlockState());
            var line = adapter.progressLine(fixture.world(), fixture.id());
            var contents = (net.minecraft.network.chat.contents.TranslatableContents) line.getContents();
            assertEquals("quest.trakt.daily.autumn.progress", contents.getKey());
            Object[] args = contents.getArgs();
            assertEquals(4, args.length);
            assertEquals(1, ((Number) args[0]).intValue());
            assertTrue(((Number) args[1]).intValue() >= 11);
            assertEquals(0, ((Number) args[2]).intValue());
            assertTrue(((Number) args[3]).intValue() >= 11);
        }
    }

    @Test
    void breadCraftGoalWaitsForTheWheatStage() throws Exception {
        try (Fixture fixture = acceptedToday(DailyQuestService.DailyQuestType.WHEAT_HARVEST)) {
            JsonDailyQuestAdapter adapter = adapter(DailyQuestService.DailyQuestType.WHEAT_HARVEST);
            adapter.onAccepted(fixture.world(), fixture.player());
            // wheat not collected yet: bread progress must stay zero even if bread is crafted
            adapter.onServerTick(fixture.world(), fixture.player());
            assertEquals(0, fixture.data().getDailyInt("daily.bread.progress"));
            // deliver the wheat stage
            adapter.onTrackedItemPickup(fixture.world(), fixture.player(), new ItemStack(Items.WHEAT, 64), 64);
            assertTrue(fixture.data().getDailyInt("daily.wheat.progress") >= 42);
        }
    }
}
