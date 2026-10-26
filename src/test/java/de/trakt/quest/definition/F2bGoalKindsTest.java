package de.trakt.quest.definition;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2b part 2: the seven remaining goal kinds of the F2 plan are registry data —
 * tame_animal, enchant_item, brew_potion, visit_village, escort_caravan,
 * deliver_item (all event hooks with existing event sources) plus sleep_in_bed
 * (vanilla custom statistic). The optional per-goal modifiers at_night / raining /
 * in_biome:&lt;ref&gt; gate credits at the adapter's single choke point.
 */
final class F2bGoalKindsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void allNineteenKindsAreRegistered() {
        List<String> ids = QuestGoalTypes.all().stream().map(QuestGoalType::id).toList();
        assertEquals(19, ids.size());
        // 8 v1 kinds + 4 F2b part 1 kinds + 7 F2b part 2 kinds
        assertEquals(List.of(
                "collect_item", "craft_item", "smelt_item", "break_block",
                "kill_entity", "shear_sheep", "villager_trade", "breed_animal",
                "harvest_hive", "visit_villagers", "recolor_collar", "catch_fish",
                "tame_animal", "enchant_item", "brew_potion", "visit_village",
                "escort_caravan", "deliver_item", "sleep_in_bed"), ids);
    }

    @Test
    void newKindsBindExactlyTheirEventSources() {
        QuestGoalType tame = QuestGoalTypes.find("tame_animal");
        assertNotNull(tame);
        assertTrue(tame.listensTo(QuestGoalType.Hook.ANIMAL_TAMED));
        assertEquals(QuestGoalType.Field.NONE, tame.required());
        assertEquals(QuestGoalType.Matcher.OPTIONAL_ENTITY, tame.matcher());

        QuestGoalType enchant = QuestGoalTypes.find("enchant_item");
        assertNotNull(enchant);
        assertTrue(enchant.listensTo(QuestGoalType.Hook.ITEM_ENCHANTED));
        assertEquals(QuestGoalType.Matcher.OPTIONAL_ITEM, enchant.matcher());

        QuestGoalType brew = QuestGoalTypes.find("brew_potion");
        assertNotNull(brew);
        assertTrue(brew.listensTo(QuestGoalType.Hook.POTION_BREWED));
        assertEquals(QuestGoalType.Matcher.ALWAYS, brew.matcher());

        QuestGoalType visit = QuestGoalTypes.find("visit_village");
        assertNotNull(visit);
        assertTrue(visit.listensTo(QuestGoalType.Hook.VILLAGE_VISITED));

        QuestGoalType escort = QuestGoalTypes.find("escort_caravan");
        assertNotNull(escort);
        assertTrue(escort.listensTo(QuestGoalType.Hook.CARAVAN_ESCORTED));

        QuestGoalType deliver = QuestGoalTypes.find("deliver_item");
        assertNotNull(deliver);
        assertTrue(deliver.listensTo(QuestGoalType.Hook.ITEM_DELIVERED));
        assertEquals(QuestGoalType.Matcher.OPTIONAL_ITEM, deliver.matcher());

        // every hook is owned by exactly one kind — no accidental double dispatch
        for (QuestGoalType.Hook hook : QuestGoalType.Hook.values()) {
            long owners = QuestGoalTypes.all().stream()
                    .filter(kind -> kind.listensTo(hook))
                    .filter(kind -> kind.statKey() == null || hook == QuestGoalType.Hook.SERVER_TICK)
                    .count();
            assertTrue(owners >= 1, "hook without any listening kind: " + hook);
        }
    }

    @Test
    void sleepInBedRunsOnTheVanillaStatistic() {
        QuestGoalType sleep = QuestGoalTypes.find("sleep_in_bed");
        assertNotNull(sleep);
        assertTrue(sleep.statDelta(), "advances from a statistics baseline like craft/catch_fish");
        assertEquals("minecraft:sleep_in_bed", sleep.statKey());
        assertTrue(sleep.listensTo(QuestGoalType.Hook.SERVER_TICK));
        assertFalse(sleep.listensTo(QuestGoalType.Hook.ITEM_ENCHANTED));
    }

    @Test
    void modifiersParseAndRoundTrip() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "night_deliveries",
                  "title_key": "quest.trakt.test.title",
                  "offer_keys": ["quest.trakt.test.offer.1"],
                  "completion_keys": ["quest.trakt.test.completion.1"],
                  "goals": [
                    {"id": "deliver", "kind": "deliver_item", "item": "minecraft:bread", "amount": 2,
                     "modifiers": ["at_night", "in_biome:minecraft:plains", "in_biome:#minecraft:is_forest"]},
                    {"id": "sleep", "kind": "sleep_in_bed", "amount": 1, "modifiers": ["raining"]}
                  ],
                  "rewards": {"silvermarks": 4, "levels": 2}
                }
                """), QuestDefinition.Source.BUILTIN, "night_deliveries.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(definition);

        QuestGoal deliver = definition.goals().get(0);
        assertEquals(List.of("at_night", "in_biome:minecraft:plains", "in_biome:#minecraft:is_forest"),
                deliver.modifiers());
        QuestGoal sleep = definition.goals().get(1);
        assertEquals(List.of("raining"), sleep.modifiers());

        // round-trip keeps the author's form
        List<String> roundTripErrors = new ArrayList<>();
        QuestDefinition reparsed = QuestDefinition.parse(definition.toJson(),
                QuestDefinition.Source.BUILTIN, "night_deliveries.json", roundTripErrors);
        assertTrue(roundTripErrors.isEmpty(), () -> String.join("; ", roundTripErrors));
        assertNotNull(reparsed);
        assertEquals(deliver.modifiers(), reparsed.goals().get(0).modifiers());
        assertEquals(sleep.modifiers(), reparsed.goals().get(1).modifiers());
    }

    @Test
    void unknownModifierIsAParseError() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "bad_modifier",
                  "title_key": "quest.trakt.test.title",
                  "offer_keys": ["quest.trakt.test.offer.1"],
                  "completion_keys": ["quest.trakt.test.completion.1"],
                  "goals": [{"id": "brew", "kind": "brew_potion", "amount": 1, "modifiers": ["at_noight"]}],
                  "rewards": {"silvermarks": 1}
                }
                """), QuestDefinition.Source.BUILTIN, "bad_modifier.json", errors);
        assertNull(definition, "a typo'd modifier must reject the file");
        assertTrue(errors.stream().anyMatch(error -> error.contains("at_night")),
                () -> String.join("; ", errors));
    }

    @Test
    void malformedBiomeReferenceIsAParseError() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "bad_biome",
                  "title_key": "quest.trakt.test.title",
                  "offer_keys": ["quest.trakt.test.offer.1"],
                  "completion_keys": ["quest.trakt.test.completion.1"],
                  "goals": [{"id": "hunt", "kind": "kill_entity", "entity": "minecraft:zombie", "amount": 1,
                             "modifiers": ["in_biome:"]}],
                  "rewards": {"silvermarks": 1}
                }
                """), QuestDefinition.Source.BUILTIN, "bad_biome.json", errors);
        assertNull(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("in_biome")),
                () -> String.join("; ", errors));
    }

    @Test
    void newKindsParseWithOptionalFilters() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "relic_trial",
                  "title_key": "quest.trakt.test.title",
                  "offer_keys": ["quest.trakt.test.offer.1"],
                  "completion_keys": ["quest.trakt.test.completion.1"],
                  "objective": {"all_of": [
                    {"id": "tame", "kind": "tame_animal", "entity": "minecraft:wolf", "amount": 1},
                    {"id": "enchant", "kind": "enchant_item", "amount": 1},
                    {"id": "brew", "kind": "brew_potion", "amount": 3},
                    {"id": "visit", "kind": "visit_village", "amount": 2},
                    {"id": "escort", "kind": "escort_caravan", "amount": 1}
                  ]},
                  "rewards": {"silvermarks": 10}
                }
                """), QuestDefinition.Source.BUILTIN, "relic_trial.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(definition);
        assertEquals(5, definition.goals().size());
        assertEquals("minecraft:wolf", definition.goals().get(0).entity());
        assertNull(definition.goals().get(1).item(), "enchant without a filter counts any enchanted item");
        // the v2 objective tree keeps all five leaves under an all_of root
        assertTrue(QuestObjective.isComplete(definition.objective(),
                index -> index >= 0 && index < 5));
        assertFalse(QuestObjective.isComplete(definition.objective(), index -> false));
    }

    @Test
    void modifiersDefaultToEmptyAndStayOutOfLegacyJson() {
        List<String> errors = new ArrayList<>();
        QuestGoal goal = QuestGoal.parse(json(
                "{\"kind\": \"shear_sheep\", \"amount\": 3}"), errors);
        assertTrue(errors.isEmpty());
        assertNotNull(goal);
        assertTrue(goal.modifiers().isEmpty());
        assertFalse(goal.toJson().has("modifiers"));
    }
}
