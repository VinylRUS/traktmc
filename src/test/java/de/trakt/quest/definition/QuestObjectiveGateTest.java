package de.trakt.quest.definition;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2a regression: the credit gates of the objective tree. The v1 flat forms must
 * reproduce the retired staged behaviour exactly (smelt/craft wait for every earlier
 * sibling), and composed trees must combine the staged flag with sequence ancestors.
 */
final class QuestObjectiveGateTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        QuestDefinitionRegistry.get().loadBuiltinDirectoryForTests(
                Path.of("src/main/resources/data/trakt/quests"));
    }

    private static QuestGoal goal(String raw) {
        List<String> errors = new ArrayList<>();
        QuestGoal parsed = QuestGoal.parse(com.google.gson.JsonParser.parseString(raw).getAsJsonObject(), errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        return parsed;
    }

    @Test
    void builtInPackKeepsV1StagedGates() {
        // smith: ingot (smelt, staged) waits for the ore quota
        QuestDefinition smith = QuestDefinitionRegistry.get().find("smith_smelting").orElseThrow();
        assertEquals(Set.of(0), smith.goalGates().get(1));
        assertTrue(smith.goalGates().get(1).contains(0));
        assertTrue(smith.goals().get(1).kind().stagedGate());

        // wheat: bread (craft, staged) waits for the wheat stage
        QuestDefinition wheat = QuestDefinitionRegistry.get().find("wheat_harvest").orElseThrow();
        assertEquals(Set.of(0), wheat.goalGates().get(1));

        // coal_mining: plain collect goals never gate (v1 parity)
        QuestDefinition coal = QuestDefinitionRegistry.get().find("coal_mining").orElseThrow();
        assertEquals(2, coal.goals().size());
        assertTrue(coal.goalGates().get(0).isEmpty());
        assertTrue(coal.goalGates().get(1).isEmpty());
    }

    @Test
    void sequenceGatesEveryLeafUnderIt() {
        // sequence[logs, any_of[apple, ingot]]:
        // apple gates on the sequence stage; ingot additionally gates on its staged
        // sibling apple inside the any_of group.
        List<String> errors = new ArrayList<>();
        QuestDefinition tree = QuestDefinition.parse(com.google.gson.JsonParser.parseString("""
                {
                  "id": "probe_tree",
                  "title_key": "quest.probe.title",
                  "objective": {
                    "sequence": [
                      {"kind": "break_block", "block": "#minecraft:logs", "amount": 8, "id": "logs"},
                      {"any_of": [
                        {"kind": "collect_item", "item": "minecraft:apple", "amount": 5, "id": "apple"},
                        {"kind": "smelt_item", "item": "minecraft:iron_ingot", "amount": 4, "id": "ingot"}
                      ]}
                    ]
                  }
                }
                """).getAsJsonObject(), QuestDefinition.Source.BUILTIN, "probe.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(tree);
        assertEquals(3, tree.goals().size());
        assertTrue(tree.goalGates().get(0).isEmpty(), "first sequence child has no gate");
        assertEquals(Set.of(0), tree.goalGates().get(1), "any_of leaf under sequence gates on the sequence stage");
        assertEquals(Set.of(0, 1), tree.goalGates().get(2), "staged smelt combines sequence gate and sibling gate");
    }

    @Test
    void anyOfCompletionNeedsASingleChild() {
        // completion semantics through the tree: all_of requires everything, any_of one.
        List<String> errors = new ArrayList<>();
        QuestDefinition tree = QuestDefinition.parse(com.google.gson.JsonParser.parseString("""
                {
                  "id": "probe_any",
                  "title_key": "quest.probe.title",
                  "objective": {
                    "any_of": [
                      {"kind": "break_block", "block": "#minecraft:logs", "amount": 8, "id": "logs"},
                      {"kind": "collect_item", "item": "minecraft:apple", "amount": 5, "id": "apple"}
                    ]
                  }
                }
                """).getAsJsonObject(), QuestDefinition.Source.BUILTIN, "probe.json", errors);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
        assertNotNull(tree);
        // none done -> false; second done alone -> true (any_of)
        assertFalse(QuestObjective.isComplete(tree.objective(), index -> false));
        assertTrue(QuestObjective.isComplete(tree.objective(), index -> index == 1));
        assertFalse(QuestObjective.isComplete(tree.objective(), index -> index == 0 && false));
        // all_of root (v1 form) still requires every leaf
        QuestDefinition coal = QuestDefinitionRegistry.get().find("coal_mining").orElseThrow();
        assertFalse(QuestObjective.isComplete(coal.objective(), index -> index == 0));
        assertTrue(QuestObjective.isComplete(coal.objective(), index -> true));
    }

    @Test
    void handBuiltFlatTreeMatchesV1Gates() {
        QuestGoal ore = goal("{\"kind\":\"collect_item\",\"item\":\"minecraft:raw_iron\",\"amount\":100}");
        QuestGoal ingot = goal("{\"kind\":\"smelt_item\",\"item\":\"minecraft:iron_ingot\",\"amount\":50}");
        QuestObjective flat = QuestObjective.ofGoals(List.of(ore, ingot));
        List<Set<Integer>> gates = QuestObjective.computeGates(flat);
        assertEquals(2, gates.size(), "one gate slot per leaf, no double population");
        assertTrue(gates.get(0).isEmpty());
        assertEquals(Set.of(0), gates.get(1));
        assertEquals(List.of(ore, ingot), QuestObjective.leaves(flat));
    }

    @Test
    void authoredLimitsAreEnforced() {
        List<String> errors = new ArrayList<>();
        assertNull(QuestObjective.parse(com.google.gson.JsonParser.parseString(
                "{\"all_of\": [{\"kind\":\"collect_item\",\"item\":\"minecraft:apple\",\"amount\":1}]}")
                .getAsJsonObject(), 1, errors), "explicit groups need >= 2 children");
        assertFalse(errors.isEmpty());

        List<String> depthErrors = new ArrayList<>();
        assertNull(QuestObjective.parse(com.google.gson.JsonParser.parseString("""
                {"all_of": [
                  {"all_of": [
                    {"all_of": [
                      {"kind":"collect_item","item":"minecraft:apple","amount":1},
                      {"kind":"collect_item","item":"minecraft:bread","amount":1}
                    ]},
                    {"kind":"collect_item","item":"minecraft:bread","amount":1}
                  ]},
                  {"kind":"collect_item","item":"minecraft:apple","amount":1}
                ]}
                """).getAsJsonObject(), 1, depthErrors), "nesting is capped at three levels");
        assertFalse(depthErrors.isEmpty());
    }
}
