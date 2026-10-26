package de.trakt.reward;

import de.trakt.economy.CurrencyService;
import de.trakt.quest.daily.DailyQuestGenerator;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.definition.QuestDefinition;
import de.trakt.quest.definition.QuestDefinitionRegistry;
import de.trakt.quest.repeatable.RepeatableTargetProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry-free, deterministic extra packages for the Java-driven circuits
 * (intro onboarding, weekly, pilgrim, story, concord). Daily-circuit extras
 * moved into quest definitions (F1b): {@code daily:*} keys resolve through
 * {@link QuestDefinition#extraChoices()} — the builtin pack is the reference.
 *
 * <p>Monetary option values are authored in <b>silver</b> (the v1 panel
 * contract) and converted here through {@link CurrencyService#silver(long)}
 * — the internal unit is copper (F7a, D7).
 */
public final class VanillaRewardRegistry {
    public record Goods(String item, int count, String potion, Map<String, Integer> enchantments) { }
    public record Option(String id, long currency, List<Goods> goods, boolean diamondGate) { }

    public static List<Option> options(String key, RepeatableTargetProfile profile) {
        if (key != null && key.startsWith("daily:")) {
            return dailyOptions(key, profile);
        }
        List<Option> options = switch (key) {
            case "intro" -> alternatives(2, goods("carrot", 6));
            case "weekly:HARVEST_FOR_VILLAGE" -> List.of(pack("supplies", goods("golden_carrot", 48)), pack("work", goods("glass", 64), goods("lantern", 16)), coins(20));
            case "weekly:BAKEHOUSE_STOCK" -> List.of(pack("supplies", goods("golden_carrot", 64)), pack("work", goods("rail", 64), goods("powered_rail", 8)), coins(26));
            case "weekly:SMITH_WEEK" -> withDiamonds(alternatives(32, goods("golden_carrot", 64), goods("golden_apple", 2)), 6);
            case "weekly:STALL_AND_PASTURE" -> List.of(pack("supplies", goods("golden_carrot", 48)), pack("work", goods("leather", 48)), coins(20));
            case "pilgrim:quench_for_the_hall", "pilgrim:wool_before_rain", "pilgrim:tracks_in_the_dark", "pilgrim:fangs_by_the_hedgerow" -> alternatives(11, goods("golden_apple", 1), potion("strong_healing", 2));
            case "pilgrim:ash_on_the_pass", "pilgrim:smoke_over_blackstone" -> alternatives(16, potion("long_fire_resistance", 2), goods("golden_carrot", 16));
            case "pilgrim:stillness_beyond_the_gate" -> alternatives(20, goods("shulker_shell", 2));
            case "story:failing_harvest", "story:restless_pens" -> key.endsWith("restless_pens")
                    ? List.of(pack("supplies", goods("golden_carrot", 48)), pack("work", goods("bookshelf", 16)), coins(20)) : alternatives(20, goods("golden_carrot", 48));
            case "story:silent_forge" -> withDiamonds(alternatives(32, enchanted("diamond_pickaxe", Map.of("efficiency", 4, "unbreaking", 3))), 8);
            case "story:market_road_troubles" -> withDiamonds(alternatives(24, goods("rail", 64), goods("powered_rail", 16), goods("lantern", 16)), 6);
            case "story:night_bells" -> withDiamonds(alternatives(32, enchanted("diamond_boots", Map.of("feather_falling", 4, "unbreaking", 3))), 8);
            case "story:shadows_on_the_trade_road" -> withDiamonds(alternatives(24, enchanted("bow", Map.of("power", 4, "unbreaking", 3)), enchanted("shield", Map.of("unbreaking", 3))), 6);
            case "story:the_empty_caravan" -> withDiamonds(alternatives(32, goods("golden_carrot", 48), goods("golden_apple", 2), potion("strong_healing", 2)), 8);
            case "story:shrines_between_roads" -> withDiamonds(alternatives(32, enchanted("diamond_pickaxe", Map.of("silk_touch", 1, "efficiency", 4, "unbreaking", 3))), 8);
            case "concord" -> withDiamonds(alternatives(48, goods("rail", 128), goods("powered_rail", 16), goods("lantern", 32)), 12);
            default -> List.of();
        };
        if (!key.startsWith("weekly:") || profile == RepeatableTargetProfile.NORMAL) return options;
        return options.stream().map(o -> new Option(o.id(), scale(o.currency(), profile),
                o.goods().stream().map(g -> new Goods(g.item(), key.equals("weekly:SMITH_WEEK") && o.id().equals("diamonds")
                        ? 5 + profile.id() : scale(g.count(), profile), g.potion(), g.enchantments())).toList(), o.diamondGate())).toList();
    }

    /**
     * F1b: daily extras resolve through the quest definition's
     * {@code extra_choices} — authored silver amounts convert to copper here,
     * and the classic per-acceptance profile scaling applies (the retired
     * {@code daily:*} code table behaved identically).
     */
    private static List<Option> dailyOptions(String key, RepeatableTargetProfile profile) {
        DailyQuestService.DailyQuestType type = parseDailyType(key.substring("daily:".length()));
        if (type == null) {
            return List.of();
        }
        QuestDefinition definition = QuestDefinitionRegistry.get()
                .find(DailyQuestGenerator.slotId(type)).orElse(null);
        if (definition == null || definition.extraChoices().isEmpty()) {
            return List.of();
        }
        boolean normal = profile == null || profile == RepeatableTargetProfile.NORMAL;
        List<Option> result = new ArrayList<>();
        for (var option : definition.extraChoices()) {
            long currency = option.silvermarks() <= 0 ? 0L : CurrencyService.silver(option.silvermarks());
            List<Goods> goodsList = new ArrayList<>();
            for (var item : option.items()) {
                goodsList.add(goods(item.item(), normal ? item.count() : scale(item.count(), profile)));
            }
            result.add(new Option(option.id(), normal ? currency : scale(currency, profile),
                    List.copyOf(goodsList), "diamonds".equals(option.gate())));
        }
        return List.copyOf(result);
    }

    private static DailyQuestService.DailyQuestType parseDailyType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DailyQuestService.DailyQuestType.valueOf(raw);
        } catch (IllegalArgumentException unknownSlot) {
            return null;
        }
    }

    /** Profile scaling for counts and money: LIGHT 88%, HEAVY 118% (rounded). */
    public static int scale(int count, RepeatableTargetProfile profile) {
        if (count == 0) return 0;
        return (int) Math.max(1L, scaleLong(count, profile));
    }

    /** Long-safe variant of {@link #scale} for copper-valued currency fields. */
    public static long scale(long value, RepeatableTargetProfile profile) {
        return scaleLong(value, profile);
    }

    private static long scaleLong(long value, RepeatableTargetProfile profile) {
        if (value == 0) return 0;
        return Math.max(1L, (value * (profile == RepeatableTargetProfile.LIGHT ? 88
                : profile == RepeatableTargetProfile.HEAVY ? 118 : 100) + 50) / 100);
    }

    public static Goods goods(String item, int count) { return new Goods(item, count, "", Map.of()); }
    private static Goods potion(String id, int count) { return new Goods("potion", count, id, Map.of()); }
    private static Goods enchanted(String item, Map<String, Integer> enchantments) { return new Goods(item, 1, "", Map.copyOf(enchantments)); }
    private static Option pack(String id, Goods... goods) { return new Option(id, 0, List.of(goods), false); }
    /** {@code silverAmount} is authored in silver — paid as copper through {@link CurrencyService#silver(long)}. */
    private static Option coins(long silverAmount) { return new Option("coins", CurrencyService.silver(silverAmount), List.of(), false); }
    private static List<Option> alternatives(int silverAmount, Goods... goods) { return List.of(pack("supplies", goods), coins(silverAmount)); }
    private static List<Option> withDiamonds(List<Option> options, int amount) {
        var result = new ArrayList<>(options);
        result.add(result.size() - 1, new Option("diamonds", 0, List.of(goods("diamond", amount)), true));
        return List.copyOf(result);
    }
    private VanillaRewardRegistry() { }
}
