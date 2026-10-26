package de.trakt.quest.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One measurable objective of a {@link QuestDefinition}. A goal is always a leaf of the
 * objective tree; its kind comes from the {@link QuestGoalTypes} registry (F2a), which
 * replaced the retired enum so that future kinds register without engine changes.
 *
 * <p>Registry references ({@code item}, {@code block}, {@code entity}) accept either a plain
 * identifier ({@code minecraft:iron_ingot}) or a tag reference with the {@code #} prefix
 * ({@code #minecraft:logs}). Tag references keep parity with the retired Java quest classes,
 * which matched whole vanilla families (any log, any coal ore, any zombie variant).
 *
 * <p>The optional {@code key} field pins the per-player progress counter. The built-in JSON pack
 * reuses the legacy {@code DailyQuestKeys} strings so that in-flight quest progress on a save
 * survives the switch from Java classes to JSON data. The optional {@code salt} feeds the
 * target tuning ({@code RepeatableTargetTuning}) the same way the retired code did.
 *
 * <p>F2b part 2 — optional {@code modifiers} gate every credit of the leaf:
 * {@code at_night}, {@code raining} and {@code in_biome:<id|#tag>} (several
 * {@code in_biome} entries are OR-ed). The check runs at the single credit choke point
 * of {@link JsonDailyQuestAdapter}, so it applies to every kind uniformly.
 */
public record QuestGoal(String id, QuestGoalType kind, String item, String block, String entity,
                        int amount, String key, String salt, String baseKey, List<String> modifiers) {

    public static QuestGoal parse(JsonObject root, List<String> errors) {
        QuestGoalType kind = QuestGoalTypes.find(stringOf(root, "kind"));
        if (kind == null) {
            errors.add("goal.kind must be one of: " + QuestGoalTypes.describeIds());
            return null;
        }
        int amount = intOf(root, "amount", -1);
        if (amount < 1 || amount > 100000) {
            errors.add("goal.amount must be between 1 and 100000 (got " + amount + ")");
        }
        String item = normalized(stringOf(root, "item"));
        String block = normalized(stringOf(root, "block"));
        String entity = normalized(stringOf(root, "entity"));
        switch (kind.required()) {
            case ITEM -> {
                if (item == null) {
                    errors.add("goal.item is required for kind " + kind.id());
                }
            }
            case BLOCK -> {
                if (block == null) {
                    errors.add("goal.block is required for kind " + kind.id());
                }
            }
            case ENTITY -> {
                if (entity == null) {
                    errors.add("goal.entity is required for kind " + kind.id());
                }
            }
            case NONE -> {
                // optional filters: any trade (item omitted), any animal (entity omitted); shear always counts
            }
        }
        if (!errors.isEmpty()) {
            return null;
        }
        String id = normalized(stringOf(root, "id"));
        String key = normalized(stringOf(root, "key"));
        String salt = normalized(stringOf(root, "salt"));
        String baseKey = normalized(stringOf(root, "base_key"));
        List<String> modifiers = parseModifiers(root, errors);
        if (!errors.isEmpty()) {
            return null;
        }
        return new QuestGoal(id == null ? "goal" : id, kind, item, block, entity, amount, key, salt, baseKey, modifiers);
    }

    /**
     * F2b part 2: {@code "modifiers": ["at_night", "in_biome:minecraft:dark_forest"]}.
     * Unknown tokens and malformed biome references are parse errors — a typo'd
     * modifier must never silently pass every check.
     */
    private static List<String> parseModifiers(JsonObject root, List<String> errors) {
        List<String> raw = stringList(root, "modifiers", errors);
        List<String> modifiers = new ArrayList<>();
        for (String token : raw) {
            String trimmed = token.trim();
            if (trimmed.equals("at_night") || trimmed.equals("raining")) {
                modifiers.add(trimmed);
                continue;
            }
            if (trimmed.startsWith("in_biome:")) {
                String ref = trimmed.substring("in_biome:".length()).trim();
                if (ref.isEmpty() || !ref.matches("#?[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                    errors.add("goal.modifiers in_biome needs an identifier or #tag reference: " + trimmed);
                    continue;
                }
                modifiers.add("in_biome:" + ref);
                continue;
            }
            errors.add("goal.modifiers must be at_night, raining or in_biome:<id|#tag> (got '" + trimmed + "')");
        }
        return List.copyOf(modifiers);
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        if (id != null && !id.equals("goal")) {
            root.addProperty("id", id);
        }
        root.addProperty("kind", kind.id());
        if (item != null) {
            root.addProperty("item", item);
        }
        if (block != null) {
            root.addProperty("block", block);
        }
        if (entity != null) {
            root.addProperty("entity", entity);
        }
        root.addProperty("amount", amount);
        if (key != null) {
            root.addProperty("key", key);
        }
        if (salt != null) {
            root.addProperty("salt", salt);
        }
        if (baseKey != null) {
            root.addProperty("base_key", baseKey);
        }
        if (!modifiers.isEmpty()) {
            JsonArray array = new JsonArray();
            modifiers.forEach(array::add);
            root.add("modifiers", array);
        }
        return root;
    }

    /** Progress counter key: explicit legacy key or generated default. */
    public String progressKey(String questId) {
        if (key != null && !key.isBlank()) {
            return key;
        }
        return "quest." + questId + "." + id + ".progress";
    }

    /** Tuning salt: explicit legacy salt or generated default. */
    public String tuningSalt(String questId) {
        if (salt != null && !salt.isBlank()) {
            return salt;
        }
        return "quest." + questId + "." + id;
    }

    /** Stat baseline key for craft goals (see JsonDailyQuestAdapter#onAccepted). */
    public String baselineKey(String questId) {
        if (baseKey != null && !baseKey.isBlank()) {
            return baseKey;
        }
        return "quest." + questId + "." + id + ".base";
    }

    public boolean isTagReference(String raw) {
        return raw != null && raw.startsWith("#");
    }

    public String tagNamespace(String raw) {
        String body = raw.substring(1);
        return body.contains(":") ? body.substring(0, body.indexOf(':')) : "minecraft";
    }

    public String tagPath(String raw) {
        String body = raw.substring(1);
        return body.contains(":") ? body.substring(body.indexOf(':') + 1) : body;
    }

    // ------------------------------------------------------------------ json helpers

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

    static List<String> stringList(JsonObject object, String key, List<String> errors) {
        List<String> values = new ArrayList<>();
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return values;
        }
        if (!element.isJsonArray()) {
            errors.add(key + " must be an array of strings");
            return values;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonPrimitive()) {
                String value = entry.getAsString();
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }
}
