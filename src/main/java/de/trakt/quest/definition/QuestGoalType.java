package de.trakt.quest.definition;

import java.util.EnumSet;
import java.util.Set;

/**
 * One registered goal kind of the unified quest engine (F2a). Instances live in the
 * {@link QuestGoalTypes} registry so that new kinds (F2b and beyond) plug in without
 * touching the engine: the adapter dispatches every event through the descriptor
 * below, never through a switch over enum constants.
 *
 * <p>Descriptor fields, in the order the adapter consults them:
 * <ul>
 *   <li>{@code required} — the registry reference a leaf of this kind must carry
 *       (item/block/entity). Validation lives in {@link QuestGoal#parse}.</li>
 *   <li>{@code matcher} — how an engine event is matched against the leaf's
 *       reference. {@code OPTIONAL_*} kinds count every event when the reference
 *       is absent (any trade, any animal).</li>
 *   <li>{@code statDelta} — the leaf advances from a vanilla statistics baseline
 *       on the shared server tick (craft goals).</li>
 *   <li>{@code stagedGate} — the leaf only credits once every previous sibling
 *       objective is complete (v1 behaviour of the retired smelt/craft classes).</li>
 *   <li>{@code statKey} — for statDelta kinds: the custom-stat identifier
 *       (e.g. {@code minecraft:fish_caught}) read instead of a crafted-item stat
 *       (F2b catch_fish; {@code null} for the v1 crafted-item kinds).</li>
 *   <li>{@code hooks} — the engine events this kind reacts to.</li>
 * </ul>
 */
public record QuestGoalType(String id, Field required, Matcher matcher,
                            boolean statDelta, boolean stagedGate,
                            String statKey, Set<Hook> hooks) {

    public enum Field {
        NONE, ITEM, BLOCK, ENTITY
    }

    public enum Matcher {
        /** Matches the leaf {@code item} reference (collect/craft/smelT). */
        ITEM,
        /** Matches the leaf {@code block} reference. */
        BLOCK,
        /** Matches the leaf {@code entity} reference. */
        ENTITY,
        /** Counts the event when no {@code item} reference is set; filters otherwise. */
        OPTIONAL_ITEM,
        /** Counts the event when no {@code entity} reference is set; filters otherwise. */
        OPTIONAL_ENTITY,
        /** Always counts the event. */
        ALWAYS
    }

    /** Engine events, mirroring the {@code DailyQuestDefinition} hooks. */
    public enum Hook {
        BLOCK_BREAK,
        ITEM_PICKUP,
        FURNACE_OUTPUT,
        MONSTER_KILL,
        SHEEP_SHEARED,
        VILLAGER_TRADE,
        ANIMAL_LOVE,
        SERVER_TICK,
        BEE_NEST_INTERACT,
        ENTITY_USE,
        COLLAR_RECOLOR,
        ANIMAL_TAMED,
        ITEM_ENCHANTED,
        POTION_BREWED,
        VILLAGE_VISITED,
        CARAVAN_ESCORTED,
        ITEM_DELIVERED
    }

    public boolean listensTo(Hook hook) {
        return hooks.contains(hook);
    }

    static QuestGoalType of(String id, Field required, Matcher matcher,
                            boolean statDelta, boolean stagedGate,
            Hook... hooks) {
        Set<Hook> set = hooks.length == 0 ? EnumSet.noneOf(Hook.class) : EnumSet.of(hooks[0], hooks);
        return new QuestGoalType(id, required, matcher, statDelta, stagedGate, null, Set.copyOf(set));
    }

    /**
     * F2b custom-stat kind: advances from a vanilla custom statistic baseline
     * (e.g. {@code minecraft:fish_caught}) on the shared server tick.
     */
    static QuestGoalType ofCustomStat(String id, Matcher matcher, String statKey) {
        return new QuestGoalType(id, Field.NONE, matcher, true, false, statKey,
                Set.copyOf(EnumSet.of(Hook.SERVER_TICK)));
    }
}
