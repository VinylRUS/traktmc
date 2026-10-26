package de.trakt.quest.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unified quest definition (F1a): every quest in Trakt is data. A definition comes from one
 * of four layers (see {@code QuestDefinitionRegistry}): user data packs, the world folder
 * {@code <world>/trakt/quests/}, the legacy panel config folder
 * {@code config/trakt/api/quests/}, or the built-in reference pack shipped inside
 * the jar at {@code data/trakt/quests/}.
 *
 * <p>Two text styles are supported and may not be mixed:
 * <ul>
 *   <li><b>inline</b> — localized strings ({@code title: {en_us: ...}}), used by the panel
 *       and hand-written quests;</li>
 *   <li><b>lang keys</b> — references into the language files ({@code title_key},
 *       {@code offer_keys}, {@code completion_keys}, {@code progress.key}), used by the
 *       built-in pack so the language files stay the single source of truth for prose.</li>
 * </ul>
 *
 * <p>Schema is a strict superset of the custom-quest v1 schema: every v1 JSON file parses
 * unchanged (REST panel compatibility, RELEASE_PLAN §4 F7c keeps that contract).
 *
 * <p>F2a: goals parse into an objective tree ({@link QuestObjective}) — the v1
 * {@code goals}/{@code goal} forms become an implicit all_of root, the v2
 * {@code objective} form enables all_of/any_of/sequence nesting. Rewards parse into a
 * typed entry list ({@link QuestReward}) — the v1 object form (panel) and the v2
 * array form are both accepted, including {@code choice_of}.
 */
public final class QuestDefinition {

    public enum Source {
        BUILTIN("builtin"),
        DATAPACK("datapack"),
        WORLD("world"),
        API("api");
        private final String id;
        Source(String id) { this.id = id; }
        public String id() { return id; }
    }

    public enum CompletionMode {
        AUTOMATIC("automatic"),
        QUESTMASTER_TURN_IN("questmaster");
        private final String id;
        CompletionMode(String id) { this.id = id; }
        public String id() { return id; }
        public static CompletionMode fromId(String raw) {
            return "questmaster".equals(raw) ? QUESTMASTER_TURN_IN : AUTOMATIC;
        }
    }

    public record RewardItem(String item, int count) {}

    /** v1-shaped view of the direct reward entries (compat surface; choice_of excluded). */
    public record Rewards(long silvermarks, int levels, String reputationTrack, int reputationAmount,
                           List<RewardItem> items) {
        public static Rewards empty() {
            return new Rewards(0, 0, null, 0, List.of());
        }
    }

    public record TurnInItem(String item, int count, String salt) {
        public TurnInItem(String item, int count) {
            this(item, count, null);
        }
    }

    /** Progress presentation: one flat line with pairs (N/T per goal) or staged lines. */
    public record ProgressView(String key, List<String> stages, String blockedKey) {
        static ProgressView flat(String key) {
            return new ProgressView(key, List.of(), null);
        }
        static ProgressView staged(List<String> stages, String blockedKey) {
            return new ProgressView(null, List.copyOf(stages), blockedKey);
        }
    }

    public static final String REPEAT_ONCE = "once";
    public static final String REPEAT_DAILY = "daily";

    private final String id;
    private final Source source;
    private final String file;
    private final Map<String, String> titles;
    private final Map<String, String> offers;
    private final Map<String, List<String>> completions;
    private final String titleKey;
    private final List<String> offerKeys;
    private final List<String> completionKeys;
    private final ProgressView progressView;
    private final QuestObjective objective;
    private final boolean objectiveForm;
    private final CompletionMode completionMode;
    private final List<TurnInItem> turnInItems;
    private final List<QuestReward> rewardList;
    private final boolean rewardsArrayForm;
    private final List<QuestReward.ChoiceOf.Option> extraChoices;
    private final String repeat;
    private boolean enabled;
    private final long createdAt;
    private final String createdBy;

    /** Lazily flattened leaves (progress order) — shared by every view. */
    private List<QuestGoal> goalLeaves;
    /** Lazily computed per-leaf credit gates (see {@link QuestObjective#computeGates}). */
    private List<Set<Integer>> goalGateCache;

    private QuestDefinition(String id, Source source, String file,
                            Map<String, String> titles, Map<String, String> offers,
                            Map<String, List<String>> completions,
                            String titleKey, List<String> offerKeys, List<String> completionKeys,
                            ProgressView progressView,
                            QuestObjective objective, boolean objectiveForm, CompletionMode completionMode,
                            List<TurnInItem> turnInItems, List<QuestReward> rewardList, boolean rewardsArrayForm,
                            List<QuestReward.ChoiceOf.Option> extraChoices,
                            String repeat, boolean enabled, long createdAt, String createdBy) {
        this.id = id;
        this.source = source;
        this.file = file;
        this.titles = titles;
        this.offers = offers;
        this.completions = completions;
        this.titleKey = titleKey;
        this.offerKeys = offerKeys;
        this.completionKeys = completionKeys;
        this.progressView = progressView;
        this.objective = objective;
        this.objectiveForm = objectiveForm;
        this.completionMode = completionMode;
        this.turnInItems = List.copyOf(turnInItems);
        this.rewardList = List.copyOf(rewardList);
        this.rewardsArrayForm = rewardsArrayForm;
        this.extraChoices = List.copyOf(extraChoices == null ? List.of() : extraChoices);
        this.repeat = repeat;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.createdBy = createdBy == null ? "" : createdBy;
    }

    // ------------------------------------------------------------------ accessors

    public String id() { return id; }
    public Source source() { return source; }
    public String file() { return file; }
    public Map<String, String> titles() { return titles; }
    public Map<String, String> offers() { return offers; }
    public Map<String, List<String>> completions() { return completions; }
    public String titleKey() { return titleKey; }
    public List<String> offerKeys() { return offerKeys; }
    public List<String> completionKeys() { return completionKeys; }
    public ProgressView progressView() { return progressView; }
    public QuestObjective objective() { return objective; }
    public CompletionMode completionMode() { return completionMode; }
    public List<TurnInItem> turnInItems() { return turnInItems; }
    public List<QuestReward> rewardsList() { return rewardList; }

    /**
     * Daily-circuit extra reward packages (F1b): the optional choice bundles a
     * player picks from on top of the base payout. Authored as
     * {@code extra_choices: [{id, silvermarks, items, gate?}, ...]} — the same
     * option schema as {@code choice_of}, paid through the same reward-choice
     * UI snapshot at acceptance time.
     */
    public List<QuestReward.ChoiceOf.Option> extraChoices() { return extraChoices; }
    public String repeat() { return repeat; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { this.enabled = value; }
    public long createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }

    public boolean usesLangKeys() { return titleKey != null; }

    /** Objective leaves in progress order — the v1 {@code goals()} surface. */
    public List<QuestGoal> goals() {
        List<QuestGoal> leaves = goalLeaves;
        if (leaves == null) {
            goalLeaves = leaves = QuestObjective.leaves(objective);
        }
        return leaves;
    }

    /** v1-shaped rewards view of the direct entries (compat surface; choice_of excluded). */
    public Rewards rewards() {
        QuestRewards.DirectView view = QuestRewards.view(rewardList);
        List<RewardItem> items = new ArrayList<>();
        for (QuestRewards.DirectView.ItemRef item : view.items()) {
            items.add(new RewardItem(item.item(), item.count()));
        }
        return new Rewards(view.silvermarks(), view.levels(), view.reputationTrack(),
                view.reputationAmount(), List.copyOf(items));
    }

    /** The choice_of entry, or null when rewards are direct payouts. */
    public QuestReward.ChoiceOf choiceOf() {
        return QuestRewards.choiceOf(rewardList);
    }

    /** Per-leaf credit gates (computed once; see {@link QuestObjective#computeGates}). */
    public List<Set<Integer>> goalGates() {
        List<Set<Integer>> gates = goalGateCache;
        if (gates == null) {
            goalGateCache = gates = QuestObjective.computeGates(objective);
        }
        return gates;
    }

    /** Title in the requested locale with en_us fallback (inline style). */
    public String title(String locale) { return localized(titles, locale); }

    /** Offer paragraphs in the requested locale; a long text may carry two paragraphs split by a blank line. */
    public List<String> offerParagraphs(String locale) {
        String raw = localized(offers, locale);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("\\n\\n");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part.strip());
            }
        }
        return result;
    }

    /** Completion lines (exactly what the retired Java classes passed into buildCompletion). */
    public List<String> completionLines(String locale) {
        List<String> fromMap = completions.get(locale);
        if (fromMap != null && !fromMap.isEmpty()) {
            return fromMap;
        }
        return List.of("", "", "");
    }

    // ------------------------------------------------------------------ parsing

    public static QuestDefinition parse(JsonObject root, Source source, String file, List<String> errors) {
        if (root == null) {
            errors.add("body must be a JSON object");
            return null;
        }
        String id = QuestGoal.stringOf(root, "id");
        if (id == null || !id.matches("[a-z0-9_]{3,40}")) {
            errors.add("id must match [a-z0-9_]{3,40} (lowercase latin, digits, underscores)");
        }

        Map<String, String> titles = localizedMapOf(root, "title", errors, false);
        Map<String, String> offers = localizedMapOf(root, "offer", errors, false);
        Map<String, List<String>> completions = localizedListMapOf(root, "completion", errors);

        String titleKey = QuestGoal.normalized(QuestGoal.stringOf(root, "title_key"));
        List<String> offerKeys = QuestGoal.stringList(root, "offer_keys", errors);
        List<String> completionKeys = QuestGoal.stringList(root, "completion_keys", errors);

        boolean hasInline = !titles.isEmpty() || !offers.isEmpty() || !completions.isEmpty();
        boolean hasKeys = titleKey != null || !offerKeys.isEmpty() || !completionKeys.isEmpty();
        if (hasInline && hasKeys) {
            errors.add("inline texts (title/offer/completion) and lang keys (title_key/offer_keys/completion_keys) must not be mixed");
        }
        if (!hasKeys && titles.isEmpty()) {
            errors.add("title is required (inline strings or title_key)");
        }

        // ---- progress view
        ProgressView progressView = parseProgressView(root, errors);

        // ---- objectives: v2 tree (exclusive) or v1 flat forms
        JsonElement goalsElement = root.get("goals");
        JsonElement goalElement = root.get("goal");
        JsonElement objectiveElement = root.get("objective");
        QuestObjective objectiveTree = null;
        boolean objectiveForm = false;
        List<QuestGoal> goals = new ArrayList<>();
        if (objectiveElement != null && !objectiveElement.isJsonNull()) {
            if (goalsElement != null || goalElement != null) {
                errors.add("objective is mutually exclusive with the v1 goals/goal forms");
            } else if (!objectiveElement.isJsonObject()) {
                errors.add("objective must be an object: a leaf (kind) or a group (all_of/any_of/sequence)");
            } else {
                objectiveForm = true;
                objectiveTree = QuestObjective.parse(objectiveElement.getAsJsonObject(), 1, errors);
            }
        } else if (goalsElement != null && goalsElement.isJsonArray()) {
            for (JsonElement entry : goalsElement.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    errors.add("goals entries must be objects");
                    continue;
                }
                List<String> goalErrors = new ArrayList<>();
                QuestGoal goal = QuestGoal.parse(entry.getAsJsonObject(), goalErrors);
                if (goal == null) {
                    errors.addAll(goalErrors);
                } else {
                    goals.add(goal);
                }
            }
        } else if (goalElement != null && goalElement.isJsonObject()) {
            // v1 single-goal form
            List<String> goalErrors = new ArrayList<>();
            QuestGoal goal = QuestGoal.parse(goalElement.getAsJsonObject(), goalErrors);
            if (goal == null) {
                errors.addAll(goalErrors);
            } else {
                goals.add(goal);
            }
        } else if (goalsElement == null && goalElement == null) {
            errors.add("goals is required (array of 1-4 objectives) or objective (all_of/any_of/sequence)");
        }

        int leafCount = objectiveForm
                ? (objectiveTree == null ? 0 : QuestObjective.leaves(objectiveTree).size())
                : goals.size();
        if (objectiveForm) {
            if (objectiveTree == null || leafCount == 0) {
                errors.add("objective needs at least one leaf");
            } else if (leafCount > QuestObjective.MAX_LEAVES) {
                errors.add("objective supports at most " + QuestObjective.MAX_LEAVES + " leaves");
            }
        } else {
            if (!goals.isEmpty() && goals.size() > 4) {
                errors.add("at most 4 goals are supported per quest");
            }
            if (goalsElement != null && goals.isEmpty()) {
                errors.add("at least one goal is required");
            }
        }

        // ---- completion mode & turn-in
        String modeRaw = QuestGoal.stringOf(root, "completion_mode");
        CompletionMode mode = CompletionMode.fromId(modeRaw);
        if (modeRaw != null && !"automatic".equals(modeRaw) && !"questmaster".equals(modeRaw)) {
            errors.add("completion_mode must be 'automatic' or 'questmaster'");
        }
        List<TurnInItem> turnInItems = new ArrayList<>();
        JsonElement turnInElement = root.get("turn_in");
        if (turnInElement != null && turnInElement.isJsonArray()) {
            for (JsonElement entry : turnInElement.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    errors.add("turn_in entries must be objects {item, count}");
                    continue;
                }
                JsonObject entryObject = entry.getAsJsonObject();
                String item = QuestGoal.normalized(QuestGoal.stringOf(entryObject, "item"));
                int count = QuestGoal.intOf(entryObject, "count", -1);
                String salt = QuestGoal.normalized(QuestGoal.stringOf(entryObject, "salt"));
                if (item == null) {
                    errors.add("turn_in[].item is required");
                } else if (count < 1 || count > 10000) {
                    errors.add("turn_in[].count must be between 1 and 10000");
                } else {
                    turnInItems.add(new TurnInItem(item, count, salt));
                }
            }
        }
        if (!turnInItems.isEmpty() && mode != CompletionMode.QUESTMASTER_TURN_IN) {
            errors.add("turn_in requires completion_mode 'questmaster'");
        }
        if (!turnInItems.isEmpty() && turnInItems.size() > 4) {
            errors.add("turn_in supports at most 4 item stacks");
        }

        // ---- rewards (v1 object / v2 array, both via the typed model)
        boolean rewardsArrayForm = false;
        List<QuestReward> rewardList = new ArrayList<>();
        JsonElement rewardsElement = root.get("rewards");
        if (rewardsElement != null && !rewardsElement.isJsonNull()) {
            if (rewardsElement.isJsonArray()) {
                rewardsArrayForm = true;
            }
            rewardList = QuestRewards.parse(rewardsElement, errors);
        }

        // ---- extra_choices (daily circuit: choice packages on top of the base payout)
        List<QuestReward.ChoiceOf.Option> extraChoices = List.of();
        JsonElement extraElement = root.get("extra_choices");
        if (extraElement != null && !extraElement.isJsonNull()) {
            if (!extraElement.isJsonArray()) {
                errors.add("extra_choices must be an array of option objects");
            } else {
                extraChoices = QuestRewardTypes.parseOptions(extraElement.getAsJsonArray(), errors);
                if (extraChoices == null) {
                    extraChoices = List.of();
                }
            }
        }

        // ---- repeat / enabled / audit
        String repeat = QuestGoal.stringOf(root, "repeat");
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
        QuestObjective resolved = objectiveForm
                ? objectiveTree
                : QuestObjective.ofGoals(goals);
        return new QuestDefinition(id, source, file,
                titles, offers, completions,
                titleKey, offerKeys, completionKeys, progressView,
                resolved, objectiveForm, mode, turnInItems, rewardList, rewardsArrayForm,
                extraChoices,
                repeat, enabled, longOf(root, "created_at", System.currentTimeMillis()),
                QuestGoal.stringOf(root, "created_by"));
    }

    private static ProgressView parseProgressView(JsonObject root, List<String> errors) {
        JsonElement element = root.get("progress");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            errors.add("progress must be an object like {\"key\": ...} or {\"stages\": [...]}");
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        List<String> stages = QuestGoal.stringList(object, "stages", errors);
        String key = QuestGoal.normalized(QuestGoal.stringOf(object, "key"));
        String blockedKey = QuestGoal.normalized(QuestGoal.stringOf(object, "blocked_key"));
        if (key == null && stages.isEmpty()) {
            errors.add("progress needs either key (flat pairs mode) or stages (staged mode)");
            return null;
        }
        if (key != null) {
            return ProgressView.flat(key);
        }
        if (stages.size() > 5) {
            errors.add("progress.stages supports at most 5 lines");
        }
        return ProgressView.staged(stages, blockedKey);
    }

    // ------------------------------------------------------------------ serialization

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        if (titleKey != null) {
            root.addProperty("title_key", titleKey);
            if (!offerKeys.isEmpty()) {
                JsonArray keys = new JsonArray();
                offerKeys.forEach(keys::add);
                root.add("offer_keys", keys);
            }
            if (!completionKeys.isEmpty()) {
                JsonArray keys = new JsonArray();
                completionKeys.forEach(keys::add);
                root.add("completion_keys", keys);
            }
        } else {
            root.add("title", jsonOf(titles));
            root.add("offer", jsonOf(offers));
            JsonObject completionObject = new JsonObject();
            for (Map.Entry<String, List<String>> entry : completions.entrySet()) {
                JsonArray lines = new JsonArray();
                entry.getValue().forEach(lines::add);
                completionObject.add(entry.getKey(), lines);
            }
            if (!completionObject.isEmpty()) {
                root.add("completion", completionObject);
            }
        }
        if (progressView != null) {
            JsonObject progress = new JsonObject();
            if (progressView.key() != null) {
                progress.addProperty("key", progressView.key());
            }
            if (!progressView.stages().isEmpty()) {
                JsonArray stages = new JsonArray();
                progressView.stages().forEach(stages::add);
                progress.add("stages", stages);
            }
            if (progressView.blockedKey() != null) {
                progress.addProperty("blocked_key", progressView.blockedKey());
            }
            root.add("progress", progress);
        }
        if (objectiveForm) {
            root.add("objective", QuestObjective.toJson(objective));
        } else {
            JsonArray goalsArray = new JsonArray();
            for (QuestGoal goal : goals()) {
                goalsArray.add(goal.toJson());
            }
            root.add("goals", goalsArray);
        }
        root.addProperty("completion_mode", completionMode.id());
        if (!turnInItems.isEmpty()) {
            JsonArray turnIn = new JsonArray();
            for (TurnInItem item : turnInItems) {
                JsonObject entry = new JsonObject();
                entry.addProperty("item", item.item());
                entry.addProperty("count", item.count());
                if (item.salt() != null) {
                    entry.addProperty("salt", item.salt());
                }
                turnIn.add(entry);
            }
            root.add("turn_in", turnIn);
        }
        if (rewardsArrayForm) {
            root.add("rewards", QuestRewards.toArrayJson(rewardList));
        } else {
            root.add("rewards", QuestRewards.toObjectJson(rewardList));
        }
        if (!extraChoices.isEmpty()) {
            JsonArray extras = new JsonArray();
            for (QuestReward.ChoiceOf.Option option : extraChoices) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", option.id());
                entry.addProperty("silvermarks", option.silvermarks());
                JsonArray items = new JsonArray();
                for (QuestReward.ItemEntry item : option.items()) {
                    JsonObject itemEntry = new JsonObject();
                    itemEntry.addProperty("item", item.item());
                    itemEntry.addProperty("count", item.count());
                    items.add(itemEntry);
                }
                entry.add("items", items);
                if (option.gate() != null) {
                    entry.addProperty("gate", option.gate());
                }
                extras.add(entry);
            }
            root.add("extra_choices", extras);
        }
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

    // ------------------------------------------------------------------ shared helpers

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

    static Map<String, String> localizedMapOf(JsonObject object, String key, List<String> errors, boolean required) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
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

    private static Map<String, List<String>> localizedListMapOf(JsonObject object, String key, List<String> errors) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return result;
        }
        if (!element.isJsonObject()) {
            errors.add(key + " must be an object like {\"en_us\": [\"line\", \"line\", \"line\"]}");
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String locale = entry.getKey().toLowerCase(Locale.ROOT);
            if (!locale.matches("[a-z]{2}_[a-z]{2}")) {
                errors.add(key + " locale keys must look like en_us / ru_ru (got '" + entry.getKey() + "')");
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                errors.add(key + "." + locale + " must be an array of lines");
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement line : entry.getValue().getAsJsonArray()) {
                if (line.isJsonPrimitive()) {
                    lines.add(line.getAsString());
                }
            }
            result.put(locale, lines);
        }
        return result;
    }

    private static long longOf(JsonObject object, String key, long fallback) {
        return QuestGoal.longOf(object, key, fallback);
    }
}
