package de.trakt.quest.daily;

import de.trakt.quest.definition.JsonDailyQuestAdapter;
import de.trakt.quest.definition.QuestDefinition;
import de.trakt.quest.definition.QuestDefinitionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;

/**
 * Daily quest pool (F1a rewrite). The seventeen retired Java quest classes are gone;
 * every definition now comes from the unified {@link QuestDefinitionRegistry} — a slot
 * is offered only when the registry holds an enabled definition whose id matches the
 * slot name (for example {@code DailyQuestType.WOODCUTTING} -> quest id {@code woodcutting}).
 *
 * <p>Slots without a definition stay invisible to {@link #pick} until F2b adds the missing
 * goal kinds (honey harvest, fishing, market rounds, pet collar) and the reference pack
 * grows them back as JSON.
 */
public final class DailyQuestGenerator {

    private DailyQuestGenerator() {}

    /** Registry id of a daily slot. */
    public static String slotId(DailyQuestService.DailyQuestType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /** True when a registry id addresses one of the daily slots (F1b: REST/UI registry view). */
    public static boolean isDailySlot(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (DailyQuestService.DailyQuestType type : DailyQuestService.DailyQuestType.values()) {
            if (slotId(type).equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Adapter for a slot, or null when the registry has no enabled definition for it. */
    public static DailyQuestDefinition definition(DailyQuestService.DailyQuestType type) {
        if (type == null) {
            return null;
        }
        return adapterFor(type);
    }

    public static DailyQuestDefinition pick(ServerLevel world) {
        return pick(world, null);
    }

    public static DailyQuestDefinition pick(ServerLevel world, DailyQuestService.DailyQuestType excludedType) {
        return pick(world, excludedType, null);
    }

    public static DailyQuestDefinition pick(ServerLevel world,
                                            DailyQuestService.DailyQuestType excludedType,
                                            DailyQuestService.DailyQuestCategory excludedCategory) {
        List<DailyQuestDefinition> candidates = coreCandidates(excludedType, excludedCategory);
        if (world == null || candidates.isEmpty()) {
            // fall back to combat candidates so an empty core pool never blocks the daily roll
            candidates = allCandidates(excludedType);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(world.getRandom().nextInt(candidates.size()));
    }

    /** All currently available definitions, for the REST API listing (read-only view). */
    public static List<DailyQuestDefinition> definitions() {
        List<DailyQuestDefinition> result = new ArrayList<>();
        for (DailyQuestService.DailyQuestType type : DailyQuestService.DailyQuestType.values()) {
            DailyQuestDefinition adapter = adapterFor(type);
            if (adapter != null) {
                result.add(adapter);
            }
        }
        return result;
    }

    public static int count() {
        return definitions().size();
    }

    // ------------------------------------------------------------------ internals

    private static List<DailyQuestDefinition> coreCandidates(DailyQuestService.DailyQuestType excludedType,
                                                             DailyQuestService.DailyQuestCategory excludedCategory) {
        List<DailyQuestDefinition> result = new ArrayList<>();
        for (DailyQuestService.DailyQuestType type : DailyQuestService.DailyQuestType.values()) {
            if (type.category() == DailyQuestService.DailyQuestCategory.COMBAT) {
                continue;
            }
            if (excludedType != null && type == excludedType) {
                continue;
            }
            if (excludedCategory != null && type.category() == excludedCategory) {
                continue;
            }
            DailyQuestDefinition adapter = adapterFor(type);
            if (adapter != null) {
                result.add(adapter);
            }
        }
        return result;
    }

    private static List<DailyQuestDefinition> allCandidates(DailyQuestService.DailyQuestType excludedType) {
        List<DailyQuestDefinition> result = new ArrayList<>();
        for (DailyQuestService.DailyQuestType type : DailyQuestService.DailyQuestType.values()) {
            if (excludedType != null && type == excludedType && DailyQuestService.DailyQuestType.values().length > 1) {
                continue;
            }
            DailyQuestDefinition adapter = adapterFor(type);
            if (adapter != null) {
                result.add(adapter);
            }
        }
        return result;
    }

    private static DailyQuestDefinition adapterFor(DailyQuestService.DailyQuestType type) {
        QuestDefinition definition = QuestDefinitionRegistry.get().find(slotId(type)).orElse(null);
        if (definition == null) {
            return null;
        }
        return new JsonDailyQuestAdapter(type, definition);
    }
}
