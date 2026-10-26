package de.trakt.api.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.trakt.quest.definition.QuestReward;
import de.trakt.quest.definition.QuestRewards;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A player-facing quest defined by JSON and created through the panel (see rest_api.md).
 *
 * <p>Custom quests run on their own self-contained pipeline: they are accepted with
 * {@code /trakt custom accept <id>}, track progress through the same event hooks as built-in
 * quests, complete automatically when the goal is met and pay their rewards directly.
 * They intentionally do not enter the built-in daily/weekly enum flow.
 */
public final class CustomQuestDefinition {

    public enum GoalKind {
        COLLECT_ITEM("collect_item"),
        CRAFT_ITEM("craft_item"),
        SMELT_ITEM("smelt_item"),
        BREAK_BLOCK("break_block"),
        KILL_ENTITY("kill_entity"),
        SHEAR_SHEEP("shear_sheep"),
        VILLAGER_TRADE("villager_trade"),
        BREED_ANIMAL("breed_animal");

        private final String id;

        GoalKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static GoalKind fromId(String raw) {
            for (GoalKind kind : values()) {
                if (kind.id.equals(raw)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public record Goal(GoalKind kind, String item, String block, String entity, int amount) {}

    public record RewardItem(String item, int count) {}

    public record Rewards(long silvermarks, int levels, String reputationTrack, int reputationAmount,
                          List<RewardItem> items) {}

    public static final String REPEAT_ONCE = "once";
    public static final String REPEAT_DAILY = "daily";

    private final String id;
    private final Map<String, String> titles;
    private final Map<String, String> offers;
    private final Goal goal;
    private final Rewards rewards;
    private final List<QuestReward> rewardList;
    private final String repeat;
    private boolean enabled;
    private final long createdAt;
    private final String createdBy;

    private CustomQuestDefinition(String id, Map<String, String> titles, Map<String, String> offers,
                                  Goal goal, Rewards rewards, List<QuestReward> rewardList,
                                  String repeat, boolean enabled,
                                  long createdAt, String createdBy) {
        this.id = id;
        this.titles = titles;
        this.offers = offers;
        this.goal = goal;
        this.rewards = rewards;
        this.rewardList = rewardList;
        this.repeat = repeat;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.createdBy = createdBy == null ? "" : createdBy;
    }

    public String id() {
        return id;
    }

    public Map<String, String> titles() {
        return titles;
    }

    public Map<String, String> offers() {
        return offers;
    }

    public Goal goal() {
        return goal;
    }

    public Rewards rewards() {
        return rewards;
    }

    /** Typed reward entries (the parse model shared with the unified quest engine). */
    public List<QuestReward> rewardList() {
        return rewardList;
    }

    /** The choice_of entry, or null when rewards are direct payouts. */
    public QuestReward.ChoiceOf choiceOf() {
        return QuestRewards.choiceOf(rewardList);
    }

    public String repeat() {
        return repeat;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public long createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    /** Title in the requested locale with en_us fallback, then any locale fallback. */
    public String title(String locale) {
        return localized(titles, locale);
    }

    /** Offer paragraph in the requested locale (may be empty). */
    public String offer(String locale) {
        return localized(offers, locale);
    }

    private static String localized(Map<String, String> map, String locale) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        String direct = map.get(locale);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String en = map.get("en_us");
        if (en != null && !en.isBlank()) {
            return en;
        }
        return map.values().iterator().next();
    }

    // ------------------------------------------------------------------ parsing

    /** Parses and structurally validates a definition. Returns null and fills errors on failure. */
    public static ParseResult parse(JsonObject root, List<String> errors) {
        if (root == null) {
            errors.add("body must be a JSON object");
            return null;
        }
        String id = stringOf(root, "id");
        if (id == null || !id.matches("[a-z0-9_]{3,40}")) {
            errors.add("id must match [a-z0-9_]{3,40} (lowercase latin, digits, underscores)");
        }
        Map<String, String> titles = localizedMapOf(root, "title", errors, true);
        Map<String, String> offers = localizedMapOf(root, "offer", errors, false);

        Goal goal = null;
        JsonObject goalObject = objectOf(root, "goal", errors, true);
        if (goalObject != null) {
            String kindRaw = stringOf(goalObject, "kind");
            GoalKind kind = kindRaw == null ? null : GoalKind.fromId(kindRaw);
            if (kind == null) {
                errors.add("goal.kind must be one of: collect_item, craft_item, smelt_item, break_block, kill_entity, shear_sheep, villager_trade, breed_animal");
            }
            int amount = intOf(goalObject, "amount", -1);
            if (amount < 1 || amount > 100000) {
                errors.add("goal.amount must be between 1 and 100000");
            }
            String item = stringOf(goalObject, "item");
            String block = stringOf(goalObject, "block");
            String entity = stringOf(goalObject, "entity");
            if (kind != null) {
                switch (kind) {
                    case COLLECT_ITEM, CRAFT_ITEM, SMELT_ITEM -> {
                        if (item == null || item.isBlank()) {
                            errors.add("goal.item is required for kind " + kind.id());
                        }
                    }
                    case BREAK_BLOCK -> {
                        if (block == null || block.isBlank()) {
                            errors.add("goal.block is required for kind break_block");
                        }
                    }
                    case KILL_ENTITY, BREED_ANIMAL -> {
                        // entity is optional for breed_animal (any animal), required for kill_entity
                        if (kind == GoalKind.KILL_ENTITY && (entity == null || entity.isBlank())) {
                            errors.add("goal.entity is required for kind kill_entity");
                        }
                    }
                    default -> {
                        // shear_sheep / villager_trade need no extra fields
                    }
                }
            }
            if (errors.isEmpty()) {
                goal = new Goal(kind, normalizeId(item), normalizeId(block), normalizeId(entity), amount);
            }
        }

        // Rewards: v1 object form (shared parser with the unified engine), including
        // the exclusive choice_of key — see QuestRewards.parseObject for the contract.
        List<QuestReward> rewardList = List.of();
        Rewards rewards = defaultRewards();
        JsonObject rewardsObject = objectOf(root, "rewards", errors, false);
        if (rewardsObject != null) {
            rewardList = QuestRewards.parseObject(rewardsObject, errors);
        }
        if (errors.isEmpty()) {
            rewards = toRewards(QuestRewards.view(rewardList));
        }

        String repeat = stringOf(root, "repeat");
        if (repeat == null || repeat.isBlank()) {
            repeat = REPEAT_ONCE;
        }
        if (!REPEAT_ONCE.equals(repeat) && !REPEAT_DAILY.equals(repeat)) {
            errors.add("repeat must be 'once' or 'daily'");
        }

        boolean enabled = root.has("enabled") && root.get("enabled").isJsonPrimitive()
                ? root.get("enabled").getAsBoolean() : true;

        if (!errors.isEmpty()) {
            return null;
        }
        return new ParseResult(new CustomQuestDefinition(id, titles, offers, goal, rewards, rewardList, repeat, enabled,
                System.currentTimeMillis(), stringOf(root, "created_by")));
    }

    public static Rewards defaultRewards() {
        return new Rewards(0, 0, null, 0, List.of());
    }

    /** Maps the shared direct-entries view onto the public v1-shaped record. */
    private static Rewards toRewards(QuestRewards.DirectView view) {
        List<RewardItem> items = new ArrayList<>();
        for (QuestRewards.DirectView.ItemRef item : view.items()) {
            items.add(new RewardItem(item.item(), item.count()));
        }
        return new Rewards(view.silvermarks(), view.levels(), view.reputationTrack(),
                view.reputationAmount(), List.copyOf(items));
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    // ------------------------------------------------------------------ serialization

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.add("title", jsonOf(titles));
        root.add("offer", jsonOf(offers));
        JsonObject goalObject = new JsonObject();
        goalObject.addProperty("kind", goal.kind().id());
        if (goal.item() != null) {
            goalObject.addProperty("item", goal.item());
        }
        if (goal.block() != null) {
            goalObject.addProperty("block", goal.block());
        }
        if (goal.entity() != null) {
            goalObject.addProperty("entity", goal.entity());
        }
        goalObject.addProperty("amount", goal.amount());
        root.add("goal", goalObject);
        // v1 object form (panel contract) — includes the choice_of key when present.
        root.add("rewards", QuestRewards.toObjectJson(rewardList));
        root.addProperty("repeat", repeat);
        root.addProperty("enabled", enabled);
        root.addProperty("created_at", createdAt);
        if (!createdBy.isBlank()) {
            root.addProperty("created_by", createdBy);
        }
        return root;
    }

    private static JsonObject jsonOf(Map<String, String> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }

    // ------------------------------------------------------------------ json helpers

    public record ParseResult(CustomQuestDefinition definition) {}

    public static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    static int intOf(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && isNumeric(element)
                ? element.getAsInt() : fallback;
    }

    static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && isNumeric(element)
                ? element.getAsLong() : fallback;
    }

    private static boolean isNumeric(JsonElement element) {
        try {
            element.getAsNumber();
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static JsonObject objectOf(JsonObject object, String key, List<String> errors, boolean required) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            if (required) {
                errors.add(key + " is required");
            }
            return null;
        }
        if (!element.isJsonObject()) {
            errors.add(key + " must be a JSON object");
            return null;
        }
        return element.getAsJsonObject();
    }

    static Map<String, String> localizedMapOf(JsonObject object, String key, List<String> errors, boolean required) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            if (required) {
                errors.add(key + " is required (at least en_us)");
            }
            return result;
        }
        if (!element.isJsonObject()) {
            errors.add(key + " must be an object like {\"en_us\": \"...\", \"ru_ru\": \"...\"}");
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String locale = entry.getKey().toLowerCase(Locale.ROOT);
            if (!locale.matches("[a-z]{2}_[a-z]{2}")) {
                errors.add(key + " locale keys must look like en_us / ru_ru (got '" + entry.getKey() + "')");
                continue;
            }
            if (entry.getValue().isJsonPrimitive()) {
                result.put(locale, entry.getValue().getAsString());
            }
        }
        if (required && !result.containsKey("en_us")) {
            errors.add(key + ".en_us is required");
        }
        return result;
    }
}
