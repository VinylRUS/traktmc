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

final class QuestDefinitionParseTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void v1CustomQuestSchemaParsesUnchanged() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "panel_quest",
                  "title": {"en_us": "Panel quest", "ru_ru": "Квест панели"},
                  "offer": {"en_us": "Offer text\\n\\nSecond paragraph"},
                  "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 12},
                  "rewards": {"silvermarks": 5, "levels": 1, "reputation_track": "FARMING", "reputation_amount": 3,
                              "items": [{"item": "minecraft:bread", "count": 2}]},
                  "repeat": "daily",
                  "enabled": true
                }
                """), QuestDefinition.Source.API, "panel_quest.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(definition);
        assertEquals("panel_quest", definition.id());
        assertEquals(1, definition.goals().size());
        assertEquals("collect_item", definition.goals().get(0).kind().id());
        assertFalse(definition.usesLangKeys());
        assertEquals(2, definition.offerParagraphs("en_us").size());
        assertEquals("quest.panel_quest.goal.progress", definition.goals().get(0).progressKey("panel_quest"));
        assertEquals(QuestDefinition.CompletionMode.AUTOMATIC, definition.completionMode());
    }

    @Test
    void builtinPackShapeWithLangKeysAndMultipleGoals() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "woodcutting",
                  "title_key": "quest.trakt.daily.wood.title",
                  "offer_keys": ["quest.trakt.daily.wood.offer.1", "quest.trakt.daily.wood.offer.2"],
                  "completion_keys": ["a", "b", "c"],
                  "progress": {"key": "quest.trakt.daily.wood.progress"},
                  "goals": [
                    {"id": "wood", "kind": "break_block", "block": "#minecraft:logs", "amount": 64,
                     "key": "daily.wood.progress", "salt": "daily.wood.log"},
                    {"id": "coal", "kind": "break_block", "block": "#minecraft:coal_ores", "amount": 32,
                     "key": "daily.coal.progress", "salt": "daily.coal.coal"}
                  ],
                  "completion_mode": "automatic",
                  "repeat": "daily"
                }
                """), QuestDefinition.Source.BUILTIN, "woodcutting.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(definition);
        assertTrue(definition.usesLangKeys());
        assertEquals(2, definition.goals().size());
        assertEquals("daily.wood.progress", definition.goals().get(0).progressKey("woodcutting"));
        assertTrue(definition.goals().get(0).isTagReference(definition.goals().get(0).block()));
        assertEquals("#minecraft:coal_ores", definition.goals().get(1).block());
    }

    @Test
    void mixedTextStylesAreRejected() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "mixed_quest",
                  "title": {"en_us": "Inline"},
                  "title_key": "quest.trakt.daily.wood.title",
                  "goals": [{"kind": "break_block", "block": "minecraft:stone", "amount": 4}]
                }
                """), QuestDefinition.Source.WORLD, "mixed.json", errors);
        assertNull(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("must not be mixed")));
    }

    @Test
    void turnInRequiresQuestmasterMode() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "turnin_auto",
                  "title": {"en_us": "Broken"},
                  "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 5},
                  "completion_mode": "automatic",
                  "turn_in": [{"item": "minecraft:wheat", "count": 5}]
                }
                """), QuestDefinition.Source.WORLD, "turnin.json", errors);
        assertNull(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("turn_in requires completion_mode 'questmaster'")));
    }

    @Test
    void unknownGoalKindIsReported() {
        List<String> errors = new ArrayList<>();
        QuestDefinition definition = QuestDefinition.parse(json("""
                {
                  "id": "never_a_kind",
                  "title": {"en_us": "Unknown kind probe"},
                  "goal": {"kind": "juggle_flaming_torches", "amount": 3}
                }
                """), QuestDefinition.Source.WORLD, "unknown.json", errors);
        assertNull(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("goal.kind must be one of")));
    }

    @Test
    void roundTripKeepsSemantics() {
        List<String> errors = new ArrayList<>();
        QuestDefinition first = QuestDefinition.parse(json("""
                {
                  "id": "round_trip",
                  "title": {"en_us": "Round trip"},
                  "goals": [
                    {"id": "a", "kind": "collect_item", "item": "minecraft:carrot", "amount": 30,
                     "key": "daily.carrot.progress", "salt": "daily.potato.carrot"},
                    {"id": "b", "kind": "collect_item", "item": "minecraft:potato", "amount": 30,
                     "key": "daily.potato.progress", "salt": "daily.potato.potato"}
                  ],
                  "progress": {"key": "quest.trakt.daily.potato.progress"},
                  "completion_mode": "automatic",
                  "repeat": "daily"
                }
                """), QuestDefinition.Source.BUILTIN, "round.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        JsonObject serialized = first.toJson();
        QuestDefinition second = QuestDefinition.parse(serialized, QuestDefinition.Source.BUILTIN, "round.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertEquals(first.goals().size(), second.goals().size());
        assertEquals(first.goals().get(0).progressKey("round_trip"), second.goals().get(0).progressKey("round_trip"));
        assertEquals(first.goals().get(1).salt(), second.goals().get(1).salt());
        assertEquals(first.completionMode(), second.completionMode());
    }
}
