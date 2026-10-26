package de.trakt.quest.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of reward kinds (F2a). The five v2 kinds are registered up front; the
 * per-kind field parsing lives with the type so {@link QuestRewards#parseArray}
 * stays generic. Later stages register new kinds (F2b {@code lore_item}, addons)
 * through {@link #register} — schema surface is the lowercase id.
 */
public final class QuestRewardTypes {

    /** Shared authoring caps (v1 contract, kept identical). */
    public static final long MAX_SILVERMARKS = 1_000_000L;
    public static final int MAX_LEVELS = 100;
    public static final int MAX_REPUTATION = 1000;
    public static final int MAX_ITEMS_PER_STACK = 64;
    public static final int MAX_ITEM_ENTRIES = 10;
    public static final int MAX_DIRECT_ENTRIES = 8;
    public static final int MAX_CHOICE_OPTIONS = 4;

    /** Parses one typed entry of the array form. Field errors go into {@code errors}. */
    public interface EntryParser {
        QuestReward parse(JsonObject entry, List<String> errors);
    }

    public record QuestRewardType(String id, EntryParser parser) {}

    private static final Map<String, QuestRewardType> TYPES = new LinkedHashMap<>();

    static {
        register(new QuestRewardType("waycoins", QuestRewardTypes::parseWaycoins));
        register(new QuestRewardType("item", QuestRewardTypes::parseItem));
        register(new QuestRewardType("levels", QuestRewardTypes::parseLevels));
        register(new QuestRewardType("reputation", QuestRewardTypes::parseReputation));
        register(new QuestRewardType("choice_of", QuestRewardTypes::parseChoiceOf));
    }

    private QuestRewardTypes() {}

    public static QuestRewardType find(String id) {
        if (id == null) {
            return null;
        }
        return TYPES.get(id);
    }

    /** Comma-separated ids for parse error messages, in registration order. */
    public static String describeIds() {
        return String.join(", ", new ArrayList<>(TYPES.keySet()));
    }

    public static void register(QuestRewardType type) {
        if (type == null || type.id() == null || type.id().isBlank()) {
            throw new IllegalArgumentException("reward type id must not be blank");
        }
        String id = type.id().trim();
        if (!id.equals(type.id()) || !id.matches("[a-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("reward type id must match [a-z0-9_]{3,32}: " + type.id());
        }
        if (type.parser() == null) {
            throw new IllegalArgumentException("reward type parser must not be null: " + type.id());
        }
        if (TYPES.containsKey(id)) {
            throw new IllegalArgumentException("reward type already registered: " + id);
        }
        TYPES.put(id, type);
    }

    // ------------------------------------------------------------------ entry parsers

    private static QuestReward parseWaycoins(JsonObject entry, List<String> errors) {
        long amount = longOf(entry, "amount", -1);
        if (amount < 0 || amount > MAX_SILVERMARKS) {
            errors.add("waycoins.amount must be between 0 and " + MAX_SILVERMARKS);
            return null;
        }
        return new QuestReward.Waycoins(amount);
    }

    private static QuestReward parseItem(JsonObject entry, List<String> errors) {
        String item = normalized(stringOf(entry, "item"));
        int count = intOf(entry, "count", 1);
        if (item == null) {
            errors.add("item.item is required");
            return null;
        }
        if (count < 1 || count > MAX_ITEMS_PER_STACK) {
            errors.add("item.count must be between 1 and " + MAX_ITEMS_PER_STACK);
            return null;
        }
        return new QuestReward.ItemEntry(item, count);
    }

    private static QuestReward parseLevels(JsonObject entry, List<String> errors) {
        int amount = intOf(entry, "amount", -1);
        if (amount < 1 || amount > MAX_LEVELS) {
            errors.add("levels.amount must be between 1 and " + MAX_LEVELS);
            return null;
        }
        return new QuestReward.Levels(amount);
    }

    private static QuestReward parseReputation(JsonObject entry, List<String> errors) {
        String track = stringOf(entry, "track");
        int amount = intOf(entry, "amount", -1);
        if (amount < 1 || amount > MAX_REPUTATION) {
            errors.add("reputation.amount must be between 1 and " + MAX_REPUTATION);
            return null;
        }
        if (track == null || track.isBlank()) {
            errors.add("reputation.track is required");
            return null;
        }
        return new QuestReward.Reputation(track.trim().toUpperCase(Locale.ROOT), amount);
    }

    /**
     * Parses {@code choice_of} in either form: as an array-form entry
     * ({@code {kind: "choice_of", options: [...]}}) or as the object-form value
     * ({@code rewards.choice_of: [...]}) — the options array is read from
     * {@code entry.options} or from the element itself.
     */
    private static QuestReward parseChoiceOf(JsonObject entry, List<String> errors) {
        JsonElement optionsElement = entry.get("options");
        if (optionsElement == null || !optionsElement.isJsonArray()) {
            errors.add("choice_of.options must be an array of 2-" + MAX_CHOICE_OPTIONS + " option objects");
            return null;
        }
        return parseChoiceOfOptions(optionsElement.getAsJsonArray(), errors);
    }

    /** Parses the raw options array (both authored forms funnel here). */
    public static QuestReward parseChoiceOfOptions(JsonArray optionsArray, List<String> errors) {
        if (optionsArray.size() < 2 || optionsArray.size() > MAX_CHOICE_OPTIONS) {
            errors.add("choice_of needs between 2 and " + MAX_CHOICE_OPTIONS + " options (got " + optionsArray.size() + ")");
            return null;
        }
        List<QuestReward.ChoiceOf.Option> options = parseOptions(optionsArray, errors);
        if (options == null || !errors.isEmpty()) {
            return null;
        }
        return new QuestReward.ChoiceOf(options);
    }

    /**
     * Shared option-array parser (used by {@code choice_of} and by the daily
     * circuit's {@code extra_choices}). Returns null on any error.
     */
    public static List<QuestReward.ChoiceOf.Option> parseOptions(JsonArray optionsArray, List<String> errors) {
        if (optionsArray.size() < 2 || optionsArray.size() > MAX_CHOICE_OPTIONS) {
            errors.add("options array needs between 2 and " + MAX_CHOICE_OPTIONS + " entries (got " + optionsArray.size() + ")");
            return null;
        }
        List<QuestReward.ChoiceOf.Option> options = new ArrayList<>();
        for (int index = 0; index < optionsArray.size(); index++) {
            JsonElement element = optionsArray.get(index);
            if (!element.isJsonObject()) {
                errors.add("options entries must be objects");
                continue;
            }
            JsonObject optionObject = element.getAsJsonObject();
            long silvermarks = longOf(optionObject, "silvermarks", 0);
            if (silvermarks < 0 || silvermarks > MAX_SILVERMARKS) {
                errors.add("options[].silvermarks must be between 0 and " + MAX_SILVERMARKS);
                continue;
            }
            List<QuestReward.ItemEntry> items = new ArrayList<>();
            JsonElement itemsElement = optionObject.get("items");
            if (itemsElement != null && !itemsElement.isJsonNull()) {
                if (!itemsElement.isJsonArray()) {
                    errors.add("options[].items must be an array");
                    continue;
                }
                if (itemsElement.getAsJsonArray().size() > 2) {
                    errors.add("options[].items supports at most 2 stacks");
                    continue;
                }
                for (JsonElement itemElement : itemsElement.getAsJsonArray()) {
                    if (!itemElement.isJsonObject()) {
                        errors.add("options[].items entries must be objects {item, count}");
                        continue;
                    }
                    JsonObject itemObject = itemElement.getAsJsonObject();
                    String item = normalized(stringOf(itemObject, "item"));
                    int count = intOf(itemObject, "count", 1);
                    if (item == null) {
                        errors.add("option item is required");
                        continue;
                    }
                    if (count < 1 || count > MAX_ITEMS_PER_STACK) {
                        errors.add("option item count must be between 1 and " + MAX_ITEMS_PER_STACK);
                        continue;
                    }
                    items.add(new QuestReward.ItemEntry(item, count));
                }
            }
            if (silvermarks == 0 && items.isEmpty()) {
                errors.add("option needs silvermarks or items");
                continue;
            }
            String gate = normalized(stringOf(optionObject, "gate"));
            if (gate != null && !KNOWN_GATES.contains(gate)) {
                errors.add("option.gate must be one of: " + String.join(", ", KNOWN_GATES) + " (got '" + gate + "')");
                continue;
            }
            String id = normalized(stringOf(optionObject, "id"));
            if (id == null) {
                id = "option-" + (index + 1);
            }
            options.add(new QuestReward.ChoiceOf.Option(id, silvermarks, items, gate));
        }
        if (!errors.isEmpty()) {
            return null;
        }
        return List.copyOf(options);
    }

    /** Progression-signal gates an option can require before it is offered. */
    public static final java.util.Set<String> KNOWN_GATES = java.util.Set.of("diamonds");

    // ------------------------------------------------------------------ shared json helpers (local copies keep this package self-contained)

    static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    static int intOf(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsLong();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static String normalized(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
