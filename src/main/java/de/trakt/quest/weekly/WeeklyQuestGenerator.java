package de.trakt.quest.weekly;

import de.trakt.content.weekly.BakehouseStockWeeklyQuest;
import de.trakt.content.weekly.HarvestForVillageWeeklyQuest;
import de.trakt.content.weekly.MarketWeekWeeklyQuest;
import de.trakt.content.weekly.NightWatchWeeklyQuest;
import de.trakt.content.weekly.RoadWardenWeeklyQuest;
import de.trakt.content.weekly.SmithWeekWeeklyQuest;
import de.trakt.content.weekly.StallAndPastureWeeklyQuest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

public final class WeeklyQuestGenerator {
    private static final List<WeeklyQuestDefinition> DEFINITIONS = List.of(
            new HarvestForVillageWeeklyQuest(),
            new BakehouseStockWeeklyQuest(),
            new SmithWeekWeeklyQuest(),
            new StallAndPastureWeeklyQuest(),
            new MarketWeekWeeklyQuest(),
            new NightWatchWeeklyQuest(),
            new RoadWardenWeeklyQuest()
    );
    private static final List<WeeklyQuestDefinition> CORE_DEFINITIONS = DEFINITIONS.stream()
            .filter(definition -> definition.type().category() != WeeklyQuestService.WeeklyQuestCategory.COMBAT)
            .toList();

    private WeeklyQuestGenerator() {}

    public static WeeklyQuestDefinition pick(ServerLevel world) {
        return pick(world, null, null);
    }

    public static WeeklyQuestDefinition pick(ServerLevel world,
                                             WeeklyQuestService.WeeklyQuestType excludedType,
                                             WeeklyQuestService.WeeklyQuestCategory excludedCategory) {
        return pick(world, excludedType, excludedCategory, true);
    }

    public static WeeklyQuestDefinition pick(ServerLevel world,
                                             WeeklyQuestService.WeeklyQuestType excludedType,
                                             WeeklyQuestService.WeeklyQuestCategory excludedCategory,
                                             boolean allowMarketWeek) {
        if (world == null || DEFINITIONS.isEmpty()) {
            return null;
        }

        List<WeeklyQuestDefinition> candidates = withoutType(CORE_DEFINITIONS, excludedType);
        candidates = withoutCategory(candidates, excludedCategory);
        if (!allowMarketWeek) {
            candidates = withoutType(candidates, WeeklyQuestService.WeeklyQuestType.MARKET_WEEK);
        }
        return candidates.get(world.getRandom().nextInt(candidates.size()));
    }

    public static WeeklyQuestDefinition definition(WeeklyQuestService.WeeklyQuestType type) {
        if (type == null) {
            return null;
        }
        for (WeeklyQuestDefinition definition : DEFINITIONS) {
            if (definition.type() == type) {
                return definition;
            }
        }
        return null;
    }

    /** All built-in definitions, for the REST API listing (read-only view). */
    public static List<WeeklyQuestDefinition> definitions() {
        return DEFINITIONS;
    }

    public static int count() {
        return DEFINITIONS.size();
    }

    private static List<WeeklyQuestDefinition> withoutType(List<WeeklyQuestDefinition> source,
                                                           WeeklyQuestService.WeeklyQuestType excludedType) {
        if (excludedType == null || source.size() <= 1) {
            return source;
        }

        List<WeeklyQuestDefinition> filtered = new ArrayList<>();
        for (WeeklyQuestDefinition definition : source) {
            if (definition.type() != excludedType) {
                filtered.add(definition);
            }
        }
        return filtered.isEmpty() ? source : filtered;
    }

    private static List<WeeklyQuestDefinition> withoutCategory(List<WeeklyQuestDefinition> source,
                                                               WeeklyQuestService.WeeklyQuestCategory excludedCategory) {
        if (excludedCategory == null || source.size() <= 1) {
            return source;
        }

        List<WeeklyQuestDefinition> filtered = new ArrayList<>();
        for (WeeklyQuestDefinition definition : source) {
            if (definition.type().category() != excludedCategory) {
                filtered.add(definition);
            }
        }
        return filtered.isEmpty() ? source : filtered;
    }
}
