package de.trakt.quest.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of goal kinds (F2a). Replaces the retired {@code QuestGoalKind} enum:
 * the eight v1 kinds are registered up front with exactly the semantics the retired
 * Java quest classes had, and {@link #register} lets later stages (F2b kinds,
 * third-party addons) extend the set without engine changes.
 *
 * <p>Registry ids are stable schema surface: JSON files reference kinds by id and
 * unknown ids are reported as parse errors listing everything registered.
 */
public final class QuestGoalTypes {

    private static final Map<String, QuestGoalType> TYPES = new LinkedHashMap<>();

    static {
        // The v1 kinds — descriptor mirrors the retired content classes one-to-one:
        // collect filters pickups; craft is a stat-delta with the staged baseline
        // trick; smelt is a staged furnace hook; the rest are direct event hooks.
        register(QuestGoalType.of("collect_item",
                QuestGoalType.Field.ITEM, QuestGoalType.Matcher.ITEM,
                false, false, QuestGoalType.Hook.ITEM_PICKUP));
        register(QuestGoalType.of("craft_item",
                QuestGoalType.Field.ITEM, QuestGoalType.Matcher.ITEM,
                true, true, QuestGoalType.Hook.SERVER_TICK));
        register(QuestGoalType.of("smelt_item",
                QuestGoalType.Field.ITEM, QuestGoalType.Matcher.ITEM,
                false, true, QuestGoalType.Hook.FURNACE_OUTPUT));
        register(QuestGoalType.of("break_block",
                QuestGoalType.Field.BLOCK, QuestGoalType.Matcher.BLOCK,
                false, false, QuestGoalType.Hook.BLOCK_BREAK));
        register(QuestGoalType.of("kill_entity",
                QuestGoalType.Field.ENTITY, QuestGoalType.Matcher.ENTITY,
                false, false, QuestGoalType.Hook.MONSTER_KILL));
        register(QuestGoalType.of("shear_sheep",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.SHEEP_SHEARED));
        register(QuestGoalType.of("villager_trade",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.OPTIONAL_ITEM,
                false, false, QuestGoalType.Hook.VILLAGER_TRADE));
        register(QuestGoalType.of("breed_animal",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.OPTIONAL_ENTITY,
                false, false, QuestGoalType.Hook.ANIMAL_LOVE));

        // F2b kinds — closing the four remaining daily slots. Descriptors mirror the
        // retired Java classes one-to-one (hive yield, distinct-villager visits,
        // collar recolors, custom-stat fishing).
        register(QuestGoalType.of("harvest_hive",
                QuestGoalType.Field.ITEM, QuestGoalType.Matcher.ITEM,
                false, false, QuestGoalType.Hook.BEE_NEST_INTERACT));
        register(QuestGoalType.of("visit_villagers",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.ENTITY_USE));
        register(QuestGoalType.of("recolor_collar",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.COLLAR_RECOLOR));
        register(QuestGoalType.ofCustomStat("catch_fish",
                QuestGoalType.Matcher.ALWAYS, "minecraft:fish_caught"));

        // F2b part 2 — the remaining kinds from the F2 plan. The event sources already
        // exist (mixins and caravan/guild services); these descriptors plug them into
        // the unified engine so datapack authors and later conversions can use them:
        // taming (TamableAnimalMixin), anvil enchanting (Anvil/ForgingScreenHandler
        // mixins), brewing (BrewedPotionCriterionMixin), distinct village visits
        // (QuestMasterSettlementService tick), guild convoy escorts (GuildConvoyService),
        // trade-guild freight deliveries (TradeGuildService.onRouteArrival) and the
        // vanilla sleep statistic (shared stat-delta cycle, no extra hook source).
        register(QuestGoalType.of("tame_animal",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.OPTIONAL_ENTITY,
                false, false, QuestGoalType.Hook.ANIMAL_TAMED));
        register(QuestGoalType.of("enchant_item",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.OPTIONAL_ITEM,
                false, false, QuestGoalType.Hook.ITEM_ENCHANTED));
        register(QuestGoalType.of("brew_potion",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.POTION_BREWED));
        register(QuestGoalType.of("visit_village",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.VILLAGE_VISITED));
        register(QuestGoalType.of("escort_caravan",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.ALWAYS,
                false, false, QuestGoalType.Hook.CARAVAN_ESCORTED));
        register(QuestGoalType.of("deliver_item",
                QuestGoalType.Field.NONE, QuestGoalType.Matcher.OPTIONAL_ITEM,
                false, false, QuestGoalType.Hook.ITEM_DELIVERED));
        register(QuestGoalType.ofCustomStat("sleep_in_bed",
                QuestGoalType.Matcher.ALWAYS, "minecraft:sleep_in_bed"));
    }

    private QuestGoalTypes() {}

    public static QuestGoalType find(String id) {
        if (id == null) {
            return null;
        }
        return TYPES.get(id);
    }

    /** Comma-separated ids for parse error messages, in registration order. */
    public static String describeIds() {
        return String.join(", ", new ArrayList<>(TYPES.keySet()));
    }

    public static List<QuestGoalType> all() {
        return List.copyOf(TYPES.values());
    }

    /**
     * Registers a goal kind. ids are lowercase schema surface; collisions with an
     * already registered kind are rejected at the call site.
     */
    public static void register(QuestGoalType type) {
        if (type == null || type.id() == null || type.id().isBlank()) {
            throw new IllegalArgumentException("goal type id must not be blank");
        }
        String id = type.id().trim();
        if (!id.equals(type.id()) || !id.matches("[a-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("goal type id must match [a-z0-9_]{3,32}: " + type.id());
        }
        if (TYPES.containsKey(id)) {
            throw new IllegalArgumentException("goal type already registered: " + id);
        }
        TYPES.put(id, type);
    }
}
