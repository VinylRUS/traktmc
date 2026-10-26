package de.trakt.reward;

import static org.junit.jupiter.api.Assertions.*;
import de.trakt.economy.CurrencyService;
import de.trakt.quest.definition.QuestDefinition;
import de.trakt.quest.definition.QuestDefinitionRegistry;
import de.trakt.quest.repeatable.RepeatableTargetProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F1b: extra reward packages. Daily extras resolve through quest definitions
 * (extra_choices) instead of the retired code table; the remaining Java
 * circuits (weekly/pilgrim/story/concord/intro) keep their deterministic
 * packages. Monetary option values are authored in silver and pay as copper.
 */
final class VanillaRewardRegistryTest {

    @BeforeAll
    static void builtinPack() {
        // the built-in reference pack ships as data files; tests read it from the source tree
        QuestDefinitionRegistry.get().loadBuiltinDirectoryForTests(
                Path.of("src/main/resources/data/trakt/quests"));
    }

    @Test
    void wheatDailyExtrasComeFromTheDefinition() {
        var options = VanillaRewardRegistry.options("daily:WHEAT_HARVEST", RepeatableTargetProfile.NORMAL);
        assertEquals(2, options.size());
        assertEquals("supplies", options.getFirst().id());
        assertEquals(12, options.getFirst().goods().getFirst().count());
        assertEquals("minecraft:golden_carrot", options.getFirst().goods().getFirst().item());
        assertEquals("coins", options.getLast().id());
        assertEquals(CurrencyService.silver(5), options.getLast().currency(),
                "authored silver converts to the copper base unit");
    }

    @Test
    void smithDailyExtrasKeepTheGatedDiamondsBundle() {
        var options = VanillaRewardRegistry.options("daily:SMITH_SMELTING", RepeatableTargetProfile.NORMAL);
        assertEquals(3, options.size());
        assertEquals("supplies", options.get(0).id());
        assertEquals("diamonds", options.get(1).id());
        assertTrue(options.get(1).diamondGate(), "the diamonds bundle stays gated behind the progression signal");
        assertEquals(2, options.get(1).goods().getFirst().count());
        assertEquals("coins", options.get(2).id());
        assertEquals(CurrencyService.silver(12), options.get(2).currency());
    }

    @Test
    void dailyExtrasScaleWithTheAcceptanceProfile() {
        var light = VanillaRewardRegistry.options("daily:SMITH_SMELTING", RepeatableTargetProfile.LIGHT);
        assertEquals(VanillaRewardRegistry.scale(CurrencyService.silver(12), RepeatableTargetProfile.LIGHT),
                light.get(2).currency());
        assertEquals(VanillaRewardRegistry.scale(16, RepeatableTargetProfile.LIGHT),
                light.getFirst().goods().getFirst().count());
        var heavy = VanillaRewardRegistry.options("daily:SMITH_SMELTING", RepeatableTargetProfile.HEAVY);
        assertEquals(VanillaRewardRegistry.scale(CurrencyService.silver(12), RepeatableTargetProfile.HEAVY),
                heavy.get(2).currency());
    }

    @Test
    void questsWithoutAuthoredExtrasStayEmpty() {
        for (String key : new String[]{"daily:VILLAGE_TRADING", "daily:WOOL_WEAVING", "daily:ZOMBIE_CULL",
                "daily:UNKNOWN_SLOT", "weekly:MARKET_WEEK", "freight", ""}) {
            assertTrue(VanillaRewardRegistry.options(key, RepeatableTargetProfile.NORMAL).isEmpty(), key);
        }
    }

    @Test
    void smithWeeklyUsesExplicitFiveSixSevenDiamonds() {
        for (var profile : RepeatableTargetProfile.values()) {
            var diamonds = VanillaRewardRegistry.options("weekly:SMITH_WEEK", profile).stream()
                    .filter(o -> o.id().equals("diamonds")).findFirst().orElseThrow();
            assertEquals(5 + profile.id(), diamonds.goods().getFirst().count());
            assertTrue(diamonds.diamondGate());
        }
    }

    @Test
    void weeklyCoinsPaySilverNotCopper() {
        // F7a regression: registry coins are authored in silver and must convert
        var options = VanillaRewardRegistry.options("weekly:HARVEST_FOR_VILLAGE", RepeatableTargetProfile.NORMAL);
        var coins = options.stream().filter(o -> o.id().equals("coins")).findFirst().orElseThrow();
        assertEquals(CurrencyService.silver(20), coins.currency());
    }

    @Test
    void potionsAndStoryEquipmentAreNeverScaled() {
        var a = VanillaRewardRegistry.options("pilgrim:ash_on_the_pass", RepeatableTargetProfile.HEAVY);
        assertEquals(2, a.getFirst().goods().getFirst().count());
        assertEquals("long_fire_resistance", a.getFirst().goods().getFirst().potion());
        var forge = VanillaRewardRegistry.options("story:silent_forge", RepeatableTargetProfile.NORMAL);
        assertEquals(4, forge.getFirst().goods().getFirst().enchantments().get("efficiency"));
        assertEquals(3, forge.getFirst().goods().getFirst().enchantments().get("unbreaking"));
        assertEquals(forge, VanillaRewardRegistry.options("story:silent_forge", RepeatableTargetProfile.HEAVY));
    }

    @Test
    void profilesRoundHalfUpWithoutStackCap() {
        assertEquals(56, VanillaRewardRegistry.scale(64, RepeatableTargetProfile.LIGHT));
        assertEquals(76, VanillaRewardRegistry.scale(64, RepeatableTargetProfile.HEAVY));
        assertEquals(1, VanillaRewardRegistry.scale(1, RepeatableTargetProfile.LIGHT));
    }

    @Test
    void builtinDailyDefinitionsAuthorBaseRewards() {
        // every enabled daily slot must carry its base rewards in the definition —
        // the retired code table is gone, a missing rewards block would silently pay nothing
        List<String> missing = new ArrayList<>();
        for (QuestDefinition definition : QuestDefinitionRegistry.get().all()) {
            var rewards = definition.rewards();
            if (rewards.silvermarks() <= 0 || rewards.levels() <= 0
                    || rewards.reputationTrack() == null || rewards.reputationAmount() <= 0) {
                missing.add(definition.id());
            }
        }
        assertTrue(missing.isEmpty(), () -> "definitions without full base rewards: " + missing);
    }

    @Test
    void builtinDailyBaseRewardsMatchTheRetiredCodeTable() {
        // parity snapshot of DailyQuestCatalog.rewardProfile + ReputationService tables (2.5)
        String[][] expected = {
                {"wheat_harvest", "3", "2", "FARMING", "10"},
                {"potato_harvest", "3", "2", "FARMING", "10"},
                {"woodcutting", "6", "4", "CRAFTING", "15"},
                {"coal_mining", "12", "6", "CRAFTING", "20"},
                {"smith_smelting", "12", "6", "CRAFTING", "20"},
                {"wool_weaving", "6", "4", "ANIMALS", "15"},
                {"village_trading", "12", "6", "TRADE", "20"},
                {"autumn_harvest", "6", "4", "FARMING", "15"},
                {"stall_new_life", "3", "2", "ANIMALS", "10"},
                {"zombie_cull", "6", "4", "MONSTER_HUNTING", "15"},
                {"skeleton_patrol", "12", "6", "MONSTER_HUNTING", "20"},
                {"spider_sweep", "6", "4", "MONSTER_HUNTING", "15"},
                {"creeper_watch", "12", "6", "MONSTER_HUNTING", "20"},
        };
        for (String[] row : expected) {
            QuestDefinition definition = QuestDefinitionRegistry.get().find(row[0]).orElseThrow();
            var rewards = definition.rewards();
            assertEquals(Long.parseLong(row[1]), rewards.silvermarks(), row[0] + " silver");
            assertEquals(Integer.parseInt(row[2]), rewards.levels(), row[0] + " levels");
            assertEquals(row[3], rewards.reputationTrack(), row[0] + " track");
            assertEquals(Integer.parseInt(row[4]), rewards.reputationAmount(), row[0] + " reputation");
        }
    }
}
