package de.trakt.quest.definition;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F2a regression: the typed reward model. Both authored forms funnel into the same
 * entry list; choice_of is exclusive with direct payouts and caps at four options.
 */
final class QuestRewardsTest {

    private static JsonElement json(String raw) {
        return JsonParser.parseString(raw);
    }

    private static List<QuestReward> parseOk(JsonElement element) {
        List<String> errors = new ArrayList<>();
        List<QuestReward> entries = QuestRewards.parse(element, errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        return entries;
    }

    @Test
    void v1ObjectFormParsesUnchanged() {
        List<QuestReward> entries = parseOk(json("""
                {"silvermarks": 5, "levels": 2, "reputation_track": "FARMING",
                 "reputation_amount": 3, "items": [{"item": "minecraft:bread", "count": 2}]}
                """));
        assertEquals(4, entries.size());
        assertInstanceOf(QuestReward.Waycoins.class, entries.get(0));
        assertEquals(5, ((QuestReward.Waycoins) entries.get(0)).amount());
        assertInstanceOf(QuestReward.Levels.class, entries.get(1));
        assertInstanceOf(QuestReward.Reputation.class, entries.get(2));
        assertEquals("FARMING", ((QuestReward.Reputation) entries.get(2)).track());
        assertInstanceOf(QuestReward.ItemEntry.class, entries.get(3));
        // v1 view aggregation
        QuestRewards.DirectView view = QuestRewards.view(entries);
        assertEquals(5, view.silvermarks());
        assertEquals(2, view.levels());
        assertEquals("FARMING", view.reputationTrack());
        assertEquals(3, view.reputationAmount());
        assertEquals(1, view.items().size());
        assertEquals("minecraft:bread", view.items().get(0).item());
    }

    @Test
    void v2ArrayFormParsesTypedEntries() {
        List<QuestReward> entries = parseOk(json("""
                [
                  {"kind": "waycoins", "amount": 6},
                  {"kind": "item", "item": "minecraft:iron_ingot", "count": 16},
                  {"kind": "levels", "amount": 4},
                  {"kind": "reputation", "track": "trade", "amount": 5}
                ]
                """));
        assertEquals(4, entries.size());
        assertEquals("TRADE", ((QuestReward.Reputation) entries.get(3)).track());
        // array form round-trips through toArrayJson
        List<String> errors = new ArrayList<>();
        List<QuestReward> again = QuestRewards.parse(QuestRewards.toArrayJson(entries), errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertEquals(entries, again);
    }

    @Test
    void choiceOfIsExclusiveAndCapped() {
        // mixing choice_of with direct payouts is rejected
        List<String> errors = new ArrayList<>();
        QuestRewards.parseObject((com.google.gson.JsonObject) json("""
                {"silvermarks": 5, "choice_of": [
                  {"silvermarks": 6, "items": [{"item": "minecraft:iron_ingot", "count": 16}]},
                  {"items": [{"item": "minecraft:coal", "count": 32}]}
                ]}
                """), errors);
        assertFalse(errors.isEmpty());

        // 5 options exceeds the cap
        List<String> capErrors = new ArrayList<>();
        QuestRewards.parseObject((com.google.gson.JsonObject) json("""
                {"choice_of": [
                  {"silvermarks": 1}, {"silvermarks": 2}, {"silvermarks": 3},
                  {"silvermarks": 4}, {"silvermarks": 5}
                ]}
                """), capErrors);
        assertFalse(capErrors.isEmpty());

        // a valid choice round-trips through the object form
        List<QuestReward> entries = parseOk(json("""
                {"choice_of": [
                  {"silvermarks": 6, "items": [{"item": "minecraft:iron_ingot", "count": 16}]},
                  {"items": [{"item": "minecraft:coal", "count": 32}, {"item": "minecraft:bread", "count": 4}]}
                ]}
                """));
        QuestReward.ChoiceOf choice = QuestRewards.choiceOf(entries);
        assertNotNull(choice);
        assertEquals(2, choice.options().size());
        assertEquals("option-1", choice.options().get(0).id(), "ids default to option-N in authoring order");
        assertEquals(2, choice.options().get(1).items().size());
        // serialization keeps the v1 object shape with the choice_of key
        com.google.gson.JsonObject back = QuestRewards.toObjectJson(entries);
        assertTrue(back.has("choice_of"));
        assertEquals(0L, back.get("silvermarks").getAsLong());
        // allItems includes option items for registry validation
        assertEquals(3, QuestRewards.allItems(entries).size());
        // the v1 view of a choice-only list is empty (choice_of excluded)
        QuestRewards.DirectView view = QuestRewards.view(entries);
        assertEquals(0, view.silvermarks());
        assertTrue(view.items().isEmpty());
    }

    @Test
    void questDefinitionRoundTripsBothForms() {
        // v1 "goals" + object rewards stay v1-shaped after toJson
        List<String> errors = new ArrayList<>();
        QuestDefinition v1 = QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "round_trip_v1",
                  "title_key": "quest.probe.title",
                  "goals": [
                    {"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64}
                  ],
                  "rewards": {"silvermarks": 6, "levels": 4,
                               "items": [{"item": "minecraft:iron_ingot", "count": 16}]},
                  "repeat": "daily"
                }
                """), QuestDefinition.Source.BUILTIN, "v1.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        com.google.gson.JsonObject back = v1.toJson();
        assertTrue(back.has("goals"));
        assertFalse(back.has("objective"));
        assertTrue(back.get("rewards").isJsonObject());
        QuestDefinition v1Again = QuestDefinition.parse(back, QuestDefinition.Source.BUILTIN, "v1.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertEquals(v1.goals(), v1Again.goals());
        assertEquals(v1.rewardsList(), v1Again.rewardsList());

        // v2 "objective" + array rewards keep their authored form
        List<String> v2Errors = new ArrayList<>();
        QuestDefinition v2 = QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "round_trip_v2",
                  "title_key": "quest.probe.title",
                  "objective": {
                    "any_of": [
                      {"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64},
                      {"id": "trade", "kind": "villager_trade", "amount": 3}
                    ]
                  },
                  "rewards": [
                    {"kind": "waycoins", "amount": 6},
                    {"kind": "choice_of", "options": [
                      {"silvermarks": 0, "items": [{"item": "minecraft:iron_ingot", "count": 16}]},
                      {"silvermarks": 12, "items": []}
                    ]}
                  ]
                }
                """), QuestDefinition.Source.BUILTIN, "v2.json", v2Errors);
        assertTrue(v2Errors.isEmpty(), () -> String.join("; ", v2Errors));
        com.google.gson.JsonObject v2Back = v2.toJson();
        assertTrue(v2Back.has("objective"));
        assertFalse(v2Back.has("goals"));
        assertTrue(v2Back.get("rewards").isJsonArray());
        QuestDefinition v2Again = QuestDefinition.parse(v2Back, QuestDefinition.Source.BUILTIN, "v2.json", v2Errors);
        assertTrue(v2Errors.isEmpty(), () -> String.join("; ", v2Errors));
        assertNotNull(v2Again.choiceOf());
        assertEquals(v2.goals(), v2Again.goals());
        assertEquals(v2.rewardsList(), v2Again.rewardsList());

        // "objective" is mutually exclusive with the v1 forms
        List<String> clash = new ArrayList<>();
        QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "clash_probe",
                  "title_key": "quest.probe.title",
                  "goals": [{"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64}],
                  "objective": {"all_of": [
                    {"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64},
                    {"id": "trade", "kind": "villager_trade", "amount": 3}
                  ]}
                }
                """), QuestDefinition.Source.BUILTIN, "clash.json", clash);
        assertFalse(clash.isEmpty());
    }

    @Test
    void unknownKindsAreReportedWithRegistryIds() {
        List<String> errors = new ArrayList<>();
        QuestRewards.parse(json("[{\"kind\": \"ponies\", \"amount\": 1}]"), errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("waycoins")), errors::toString);
        List<String> goalErrors = new ArrayList<>();
        QuestGoal.parse((com.google.gson.JsonObject) json(
                "{\"kind\": \"juggle\", \"item\": \"minecraft:apple\", \"amount\": 1}"), goalErrors);
        assertTrue(goalErrors.stream().anyMatch(error -> error.contains("collect_item")), goalErrors::toString);
    }

    @Test
    void extraChoicesParseGateAndRoundTrip() {
        // F1b: the daily circuit's extra reward packages — same option schema as
        // choice_of, gates validated against the known progression signals
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "extras_probe",
                  "title_key": "quest.probe.title",
                  "goals": [
                    {"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64}
                  ],
                  "rewards": {"silvermarks": 6, "levels": 4},
                  "extra_choices": [
                    {"id": "supplies", "items": [{"item": "minecraft:iron_ingot", "count": 16}]},
                    {"id": "diamonds", "items": [{"item": "minecraft:diamond", "count": 2}], "gate": "diamonds"},
                    {"id": "coins", "silvermarks": 12}
                  ],
                  "repeat": "daily"
                }
                """), QuestDefinition.Source.BUILTIN, "extras.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertEquals(3, definition.extraChoices().size());
        assertNull(definition.extraChoices().get(0).gate());
        assertEquals("diamonds", definition.extraChoices().get(1).gate());
        assertEquals(12, definition.extraChoices().get(2).silvermarks());
        assertTrue(definition.rewardsList().stream().noneMatch(entry -> entry instanceof QuestReward.ChoiceOf),
                "extra_choices never collide with the exclusive choice_of model");

        // round-trip keeps the authored extras and the gate
        com.google.gson.JsonObject back = definition.toJson();
        assertTrue(back.has("extra_choices"));
        List<String> againErrors = new ArrayList<>();
        QuestDefinition again = QuestDefinition.parse(back, QuestDefinition.Source.BUILTIN, "extras.json", againErrors);
        assertTrue(againErrors.isEmpty(), () -> String.join("; ", againErrors));
        assertEquals(definition.extraChoices(), again.extraChoices());
    }

    @Test
    void extraChoicesRejectUnknownGatesAndSingletons() {
        List<String> gateErrors = new ArrayList<>();
        QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "bad_gate",
                  "title_key": "quest.probe.title",
                  "goals": [{"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64}],
                  "extra_choices": [
                    {"silvermarks": 1},
                    {"items": [{"item": "minecraft:diamond", "count": 1}], "gate": "moon_phase"}
                  ]
                }
                """), QuestDefinition.Source.BUILTIN, "bad.json", gateErrors);
        assertTrue(gateErrors.stream().anyMatch(error -> error.contains("gate")), gateErrors::toString);

        List<String> singletonErrors = new ArrayList<>();
        QuestDefinition.parse((com.google.gson.JsonObject) json("""
                {
                  "id": "one_option",
                  "title_key": "quest.probe.title",
                  "goals": [{"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64}],
                  "extra_choices": [{"silvermarks": 3}]
                }
                """), QuestDefinition.Source.BUILTIN, "one.json", singletonErrors);
        assertTrue(singletonErrors.stream().anyMatch(error -> error.contains("between 2")), singletonErrors::toString);
    }
}
