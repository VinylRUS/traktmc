package de.trakt.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.api.quests.CustomQuestDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Headless validation of the custom-quest JSON contract used by the panel (rest_api.md). */
class CustomQuestDefinitionTest {

    private static JsonObject quest(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void parsesCompleteValidQuest() {
        List<String> errors = new ArrayList<>();
        CustomQuestDefinition.ParseResult result = CustomQuestDefinition.parse(quest("""
                {
                  "id": "gather_wheat",
                  "title": {"en_us": "Gather Wheat", "ru_ru": "Собери пшеницу"},
                  "offer": {"ru_ru": "Квестодатель ждёт."},
                  "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 24},
                  "rewards": {
                    "silvermarks": 12,
                    "levels": 3,
                    "reputation_track": "FARMING",
                    "reputation_amount": 5,
                    "items": [{"item": "minecraft:emerald", "count": 2}]
                  },
                  "repeat": "daily"
                }
                """), errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(result);
        CustomQuestDefinition definition = result.definition();
        assertEquals("gather_wheat", definition.id());
        assertEquals(CustomQuestDefinition.GoalKind.COLLECT_ITEM, definition.goal().kind());
        assertEquals("minecraft:wheat", definition.goal().item());
        assertEquals(24, definition.goal().amount());
        assertEquals(CustomQuestDefinition.REPEAT_DAILY, definition.repeat());
        assertTrue(definition.enabled());
        assertEquals(12L, definition.rewards().silvermarks());
        assertEquals(3, definition.rewards().levels());
        assertEquals("FARMING", definition.rewards().reputationTrack());
        assertEquals(1, definition.rewards().items().size());
        assertEquals("Собери пшеницу", definition.title("ru_ru"));
        assertEquals("Gather Wheat", definition.title("en_us"));
        // Unknown locale falls back to en_us.
        assertEquals("Gather Wheat", definition.title("fr_fr"));
        // Round-trip keeps the fields the panel relies on.
        JsonObject serialized = definition.toJson();
        assertEquals("gather_wheat", serialized.get("id").getAsString());
        assertEquals("collect_item", serialized.getAsJsonObject("goal").get("kind").getAsString());
    }

    @Test
    void rejectsBadIdAndMissingTitle() {
        List<String> errors = new ArrayList<>();
        assertNull(CustomQuestDefinition.parse(quest("""
                {"id": "Bad Id!", "goal": {"kind": "shear_sheep", "amount": 3}}
                """), errors));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("id must match")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("title")));
    }

    @Test
    void rejectsUnknownGoalKindAndBadAmount() {
        List<String> errors = new ArrayList<>();
        assertNull(CustomQuestDefinition.parse(quest("""
                {
                  "id": "broken",
                  "title": {"en_us": "Broken"},
                  "goal": {"kind": "mine_the_moon", "amount": 0}
                }
                """), errors));
        assertTrue(errors.stream().anyMatch(error -> error.contains("goal.kind")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("goal.amount")));
    }

    @Test
    void requiresItemForItemGoalsButNotForTradeGoal() {
        List<String> errors = new ArrayList<>();
        assertNull(CustomQuestDefinition.parse(quest("""
                {
                  "id": "no_item",
                  "title": {"en_us": "No Item"},
                  "goal": {"kind": "collect_item", "amount": 10}
                }
                """), errors));
        assertTrue(errors.stream().anyMatch(error -> error.contains("goal.item")));

        List<String> tradeErrors = new ArrayList<>();
        CustomQuestDefinition.ParseResult trade = CustomQuestDefinition.parse(quest("""
                {
                  "id": "trade_quest",
                  "title": {"en_us": "Trade"},
                  "goal": {"kind": "villager_trade", "amount": 5}
                }
                """), tradeErrors);
        assertTrue(tradeErrors.isEmpty(), () -> String.join("; ", tradeErrors));
        assertNotNull(trade);
        assertTrue(trade.definition().rewards().items().isEmpty());
        assertEquals(CustomQuestDefinition.REPEAT_ONCE, trade.definition().repeat());
    }

    @Test
    void killGoalRequiresEntityWhileBreedGoalDoesNot() {
        List<String> errors = new ArrayList<>();
        assertNull(CustomQuestDefinition.parse(quest("""
                {
                  "id": "kill_quest",
                  "title": {"en_us": "Kill"},
                  "goal": {"kind": "kill_entity", "amount": 5}
                }
                """), errors));
        assertTrue(errors.stream().anyMatch(error -> error.contains("goal.entity")));

        List<String> breedErrors = new ArrayList<>();
        assertNotNull(CustomQuestDefinition.parse(quest("""
                {
                  "id": "breed_quest",
                  "title": {"en_us": "Breed"},
                  "goal": {"kind": "breed_animal", "amount": 5}
                }
                """), breedErrors));
        assertTrue(breedErrors.isEmpty(), () -> String.join("; ", breedErrors));
    }
}
