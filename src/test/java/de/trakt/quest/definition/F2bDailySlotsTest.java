package de.trakt.quest.definition;

import static org.junit.jupiter.api.Assertions.*;

import de.trakt.quest.daily.DailyQuestGenerator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2b part 1: the four daily slots that waited for new goal kinds return as data —
 * honey (harvest_hive), market_rounds (visit_villagers + villager_trade),
 * pet_collar (recolor_collar) and river_meal (catch_fish + tag smelt/turn-in).
 * Progress keys and tuning salts mirror the retired Java classes so 2.5 saves
 * keep their counters.
 */
final class F2bDailySlotsTest {

    @BeforeAll
    static void builtinPack() throws Exception {
        // registry validation touches BuiltInRegistries — vanilla must be booted first
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        QuestDefinitionRegistry.get().loadBuiltinDirectoryForTests(
                Path.of("src/main/resources/data/trakt/quests"));
    }

    @Test
    void allSeventeenDailySlotsHaveDefinitions() {
        assertEquals(17, QuestDefinitionRegistry.get().count(),
                "13 converted slots + the 4 F2b slots");
        assertEquals(17, DailyQuestGenerator.definitions().size(),
                "the generator offers every slot again");
    }

    @Test
    void honeySlotHarvestsHiveYieldsWithLegacyKeys() {
        QuestDefinition honey = QuestDefinitionRegistry.get().find("honey").orElseThrow();
        assertEquals(2, honey.goals().size());
        QuestGoal bottles = honey.goals().get(0);
        assertEquals("harvest_hive", bottles.kind().id());
        assertEquals("minecraft:honey_bottle", bottles.item());
        assertEquals(5, bottles.amount());
        assertEquals("daily.honey.progress", bottles.key());
        assertEquals("daily.honey.honey", bottles.salt());
        QuestGoal combs = honey.goals().get(1);
        assertEquals("minecraft:honeycomb", combs.item());
        assertEquals("daily.honeycomb.progress", combs.key());
        assertEquals("daily.honey.comb", combs.salt());
        assertEquals("automatic", honey.completionMode().id());
    }

    @Test
    void marketRoundsCountsDistinctVillagersAndTrades() {
        QuestDefinition market = QuestDefinitionRegistry.get().find("market_rounds").orElseThrow();
        assertEquals(2, market.goals().size());
        QuestGoal visits = market.goals().get(0);
        assertEquals("visit_villagers", visits.kind().id());
        assertEquals(10, visits.amount());
        assertEquals("daily.market_rounds.visits", visits.key());
        assertNull(visits.salt(), "the retired class used fixed targets — no tuning");
        QuestGoal trades = market.goals().get(1);
        assertEquals("villager_trade", trades.kind().id());
        assertEquals("daily.market_rounds.trades", trades.key());
        assertEquals("automatic", market.completionMode().id());
    }

    @Test
    void petCollarRecolorsTuneWithTheLegacySalt() {
        QuestDefinition collar = QuestDefinitionRegistry.get().find("pet_collar").orElseThrow();
        QuestGoal recolors = collar.goals().getFirst();
        assertEquals("recolor_collar", recolors.kind().id());
        assertEquals(3, recolors.amount());
        assertEquals("daily.pet_collar.progress", recolors.key());
        assertEquals("daily.pet_collar.recolors", recolors.salt());
    }

    @Test
    void riverMealFishStatAndTagTurnIn() {
        QuestDefinition river = QuestDefinitionRegistry.get().find("river_meal").orElseThrow();
        assertEquals(2, river.goals().size());
        QuestGoal fish = river.goals().get(0);
        assertEquals("catch_fish", fish.kind().id());
        assertTrue(fish.kind().statDelta());
        assertEquals("minecraft:fish_caught", fish.kind().statKey());
        assertEquals("daily.river.fish.progress", fish.key());
        assertEquals("daily.river.fish", fish.salt());
        assertEquals("daily.river.last_fish_caught", fish.baseKey());
        QuestGoal cooked = river.goals().get(1);
        assertEquals("smelt_item", cooked.kind().id());
        assertTrue(cooked.kind().stagedGate(), "cooking waits for the catch — retired behaviour");
        assertEquals("#trakt:cooked_fish", cooked.item());
        assertEquals("questmaster", river.completionMode().id());
        assertEquals(1, river.turnInItems().size());
        assertEquals("#trakt:cooked_fish", river.turnInItems().getFirst().item());
        assertEquals("daily.river.cooked", river.turnInItems().getFirst().salt());
    }

    @Test
    void newKindsListenToTheirEngineHooks() {
        assertTrue(QuestGoalTypes.find("harvest_hive").listensTo(QuestGoalType.Hook.BEE_NEST_INTERACT));
        assertTrue(QuestGoalTypes.find("visit_villagers").listensTo(QuestGoalType.Hook.ENTITY_USE));
        assertTrue(QuestGoalTypes.find("recolor_collar").listensTo(QuestGoalType.Hook.COLLAR_RECOLOR));
        assertTrue(QuestGoalTypes.find("catch_fish").listensTo(QuestGoalType.Hook.SERVER_TICK));
        assertNull(QuestGoalTypes.find("collect_item").statKey());
    }

    @Test
    void everySlotDefinitionCarriesBaseRewards() {
        List<String> missing = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().all()) {
            if (!DailyQuestGenerator.isDailySlot(definition.id())) {
                continue;
            }
            var rewards = definition.rewards();
            if (rewards.silvermarks() <= 0 || rewards.levels() <= 0 || rewards.reputationTrack() == null) {
                missing.add(definition.id());
            }
        }
        assertTrue(missing.isEmpty(), () -> "daily slots without base rewards: " + missing);
    }
}
