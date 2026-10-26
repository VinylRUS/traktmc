package de.trakt.quest.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.trakt.Trakt;
import de.trakt.economy.CurrencyService;
import de.trakt.economy.QuestExperienceService;
import de.trakt.reputation.ReputationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Parses, serializes and pays the reward list of a quest (F2a). Both authored forms
 * funnel into the same {@link QuestReward} entry model:
 *
 * <ul>
 *   <li><b>v1 object</b> (panel contract, unchanged): direct fields plus the new
 *       exclusive {@code choice_of} key — see {@link #parseObject};</li>
 *   <li><b>v2 array</b> of typed entries — see {@link #parseArray}.</li>
 * </ul>
 *
 * <p>Payment: {@link #grantDirect} pays everything except {@code choice_of}, which
 * callers route through the deferred reward-choice flow (snapshot at acceptance,
 * {@code /trakt rewards claim} UI at completion) — the same mechanics the daily
 * pipeline has used since 2.5.
 */
public final class QuestRewards {

    /** v1-shaped view of the direct entries (choice_of excluded), for compatibility surfaces. */
    public record DirectView(long silvermarks, int levels, String reputationTrack, int reputationAmount,
                             List<ItemRef> items) {
        public record ItemRef(String item, int count) {}
    }

    private QuestRewards() {}

    // ------------------------------------------------------------------ parsing

    /** Parses the {@code rewards} value of a quest (object v1, array v2, or absent). */
    public static List<QuestReward> parse(JsonElement element, List<String> errors) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (element.isJsonObject()) {
            return parseObject(element.getAsJsonObject(), errors);
        }
        if (element.isJsonArray()) {
            return parseArray(element.getAsJsonArray(), errors);
        }
        errors.add("rewards must be an object (v1) or an array of typed entries (v2)");
        return List.of();
    }

    /**
     * v1 object form: {@code {silvermarks, levels, reputation_track, reputation_amount,
     * items: [...]}} with the optional exclusive {@code choice_of: [...]}. When
     * {@code choice_of} is present the direct fields must be empty — a choice replaces
     * a fixed payout, it never adds to one.
     */
    public static List<QuestReward> parseObject(JsonObject object, List<String> errors) {
        if (object == null) {
            return List.of();
        }
        JsonElement choiceElement = object.get("choice_of");
        QuestReward.ChoiceOf choice = null;
        if (choiceElement != null && !choiceElement.isJsonNull()) {
            if (!choiceElement.isJsonArray()) {
                errors.add("choice_of must be an array of option objects");
                return List.of();
            }
            QuestReward parsed = QuestRewardTypes.parseChoiceOfOptions(choiceElement.getAsJsonArray(), errors);
            if (parsed instanceof QuestReward.ChoiceOf choiceOf) {
                choice = choiceOf;
            }
        }
        long silvermarks = QuestRewardTypes.longOf(object, "silvermarks", 0);
        int levels = QuestRewardTypes.intOf(object, "levels", 0);
        String track = QuestRewardTypes.normalized(QuestRewardTypes.stringOf(object, "reputation_track"));
        int reputationAmount = QuestRewardTypes.intOf(object, "reputation_amount", 0);

        if (silvermarks < 0 || silvermarks > QuestRewardTypes.MAX_SILVERMARKS) {
            errors.add("rewards.silvermarks must be between 0 and " + QuestRewardTypes.MAX_SILVERMARKS);
        }
        if (levels < 0 || levels > QuestRewardTypes.MAX_LEVELS) {
            errors.add("rewards.levels must be between 0 and " + QuestRewardTypes.MAX_LEVELS);
        }
        if (reputationAmount < 0 || reputationAmount > QuestRewardTypes.MAX_REPUTATION) {
            errors.add("rewards.reputation_amount must be between 0 and " + QuestRewardTypes.MAX_REPUTATION);
        }
        if (reputationAmount > 0 && track == null) {
            errors.add("rewards.reputation_track is required when reputation_amount > 0");
        }

        List<QuestReward.ItemEntry> items = new ArrayList<>();
        JsonElement itemsElement = object.get("items");
        if (itemsElement != null && !itemsElement.isJsonNull()) {
            if (!itemsElement.isJsonArray()) {
                errors.add("rewards.items must be an array");
            } else {
                JsonArray itemArray = itemsElement.getAsJsonArray();
                if (itemArray.size() > QuestRewardTypes.MAX_ITEM_ENTRIES) {
                    errors.add("rewards.items supports at most " + QuestRewardTypes.MAX_ITEM_ENTRIES + " entries");
                }
                for (JsonElement entry : itemArray) {
                    if (!entry.isJsonObject()) {
                        errors.add("rewards.items entries must be objects {item, count}");
                        continue;
                    }
                    JsonObject entryObject = entry.getAsJsonObject();
                    String item = QuestRewardTypes.normalized(QuestRewardTypes.stringOf(entryObject, "item"));
                    int count = QuestRewardTypes.intOf(entryObject, "count", 1);
                    if (item == null) {
                        errors.add("rewards.items[].item is required");
                    } else if (count < 1 || count > QuestRewardTypes.MAX_ITEMS_PER_STACK) {
                        errors.add("rewards.items[].count must be between 1 and " + QuestRewardTypes.MAX_ITEMS_PER_STACK);
                    } else {
                        items.add(new QuestReward.ItemEntry(item, count));
                    }
                }
            }
        }

        boolean hasDirect = silvermarks > 0 || levels > 0 || reputationAmount > 0 || !items.isEmpty();
        if (choice != null && hasDirect) {
            errors.add("choice_of cannot be combined with silvermarks/levels/reputation/items — pick one payout model");
        }
        if (!errors.isEmpty()) {
            return List.of();
        }
        if (choice != null) {
            return List.of(choice);
        }
        List<QuestReward> entries = new ArrayList<>();
        if (silvermarks > 0) {
            entries.add(new QuestReward.Waycoins(silvermarks));
        }
        if (levels > 0) {
            entries.add(new QuestReward.Levels(levels));
        }
        if (reputationAmount > 0) {
            entries.add(new QuestReward.Reputation(track.toUpperCase(Locale.ROOT), reputationAmount));
        }
        entries.addAll(items);
        return entries;
    }

    /** v2 array form: {@code [{kind: "waycoins", amount: 6}, {kind: "item", ...}, ...]}. */
    public static List<QuestReward> parseArray(JsonArray array, List<String> errors) {
        if (array == null) {
            return List.of();
        }
        if (array.size() > QuestRewardTypes.MAX_DIRECT_ENTRIES) {
            errors.add("rewards supports at most " + QuestRewardTypes.MAX_DIRECT_ENTRIES + " entries");
        }
        List<QuestReward> entries = new ArrayList<>();
        int itemCount = 0;
        int levelsTotal = 0;
        boolean hasChoice = false;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                errors.add("rewards entries must be objects with a kind field");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String kindId = QuestRewardTypes.normalized(QuestRewardTypes.stringOf(entry, "kind"));
            if (kindId == null) {
                errors.add("rewards entry needs a kind (one of: " + QuestRewardTypes.describeIds() + ")");
                continue;
            }
            if ("choice_of".equals(kindId)) {
                if (hasChoice) {
                    errors.add("at most one choice_of entry is supported");
                    continue;
                }
            }
            QuestRewardTypes.QuestRewardType type = QuestRewardTypes.find(kindId);
            if (type == null) {
                errors.add("rewards.kind must be one of: " + QuestRewardTypes.describeIds());
                continue;
            }
            QuestReward parsed = type.parser().parse(entry, errors);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof QuestReward.ChoiceOf) {
                hasChoice = true;
            }
            if (parsed instanceof QuestReward.ItemEntry) {
                itemCount++;
                if (itemCount > QuestRewardTypes.MAX_ITEM_ENTRIES) {
                    errors.add("rewards supports at most " + QuestRewardTypes.MAX_ITEM_ENTRIES + " item entries");
                    continue;
                }
            }
            if (parsed instanceof QuestReward.Levels levelsEntry) {
                levelsTotal += levelsEntry.amount();
            }
            entries.add(parsed);
        }
        if (levelsTotal > QuestRewardTypes.MAX_LEVELS) {
            errors.add("total levels across entries must not exceed " + QuestRewardTypes.MAX_LEVELS);
        }
        if (!errors.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries);
    }

    // ------------------------------------------------------------------ views

    /** v1-shaped aggregation of the direct entries (choice_of excluded). */
    public static DirectView view(List<QuestReward> entries) {
        long silvermarks = 0;
        int levels = 0;
        String track = null;
        int reputation = 0;
        List<DirectView.ItemRef> items = new ArrayList<>();
        for (QuestReward entry : entries) {
            switch (entry) {
                case QuestReward.Waycoins waycoins -> silvermarks += waycoins.amount();
                case QuestReward.Levels levelsEntry -> levels += levelsEntry.amount();
                case QuestReward.Reputation reputationEntry -> {
                    if (reputationEntry.amount() > 0 && track == null) {
                        track = reputationEntry.track();
                        reputation = reputationEntry.amount();
                    }
                }
                case QuestReward.ItemEntry item -> items.add(new DirectView.ItemRef(item.item(), item.count()));
                case QuestReward.ChoiceOf ignored -> { }
            }
        }
        return new DirectView(silvermarks, levels, track, reputation, List.copyOf(items));
    }

    /** Flat item references, including choice_of option items — for registry validation. */
    public static List<DirectView.ItemRef> allItems(List<QuestReward> entries) {
        List<DirectView.ItemRef> result = new ArrayList<>();
        for (QuestReward entry : entries) {
            switch (entry) {
                case QuestReward.ItemEntry item -> result.add(new DirectView.ItemRef(item.item(), item.count()));
                case QuestReward.ChoiceOf choice -> {
                    for (QuestReward.ChoiceOf.Option option : choice.options()) {
                        for (QuestReward.ItemEntry item : option.items()) {
                            result.add(new DirectView.ItemRef(item.item(), item.count()));
                        }
                    }
                }
                default -> { }
            }
        }
        return result;
    }

    /** The single choice_of entry, or null when rewards are direct payouts. */
    public static QuestReward.ChoiceOf choiceOf(List<QuestReward> entries) {
        for (QuestReward entry : entries) {
            if (entry instanceof QuestReward.ChoiceOf choice) {
                return choice;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ serialization

    /** v1 object form (including the choice_of key when present) — the panel contract. */
    public static JsonObject toObjectJson(List<QuestReward> entries) {
        JsonObject object = new JsonObject();
        QuestReward.ChoiceOf choice = choiceOf(entries);
        if (choice != null) {
            JsonArray options = new JsonArray();
            for (QuestReward.ChoiceOf.Option option : choice.options()) {
                options.add(optionJson(option));
            }
            object.add("choice_of", options);
            object.addProperty("silvermarks", 0L);
            object.addProperty("levels", 0);
            object.addProperty("reputation_amount", 0);
            object.add("items", new JsonArray());
            return object;
        }
        DirectView view = view(entries);
        object.addProperty("silvermarks", view.silvermarks());
        object.addProperty("levels", view.levels());
        if (view.reputationTrack() != null) {
            object.addProperty("reputation_track", view.reputationTrack());
        }
        object.addProperty("reputation_amount", view.reputationAmount());
        JsonArray items = new JsonArray();
        for (DirectView.ItemRef item : view.items()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item.item());
            entry.addProperty("count", item.count());
            items.add(entry);
        }
        object.add("items", items);
        return object;
    }

    /** v2 array form. */
    public static JsonArray toArrayJson(List<QuestReward> entries) {
        JsonArray array = new JsonArray();
        for (QuestReward entry : entries) {
            JsonObject object = new JsonObject();
            switch (entry) {
                case QuestReward.Waycoins waycoins -> {
                    object.addProperty("kind", "waycoins");
                    object.addProperty("amount", waycoins.amount());
                }
                case QuestReward.ItemEntry item -> {
                    object.addProperty("kind", "item");
                    object.addProperty("item", item.item());
                    object.addProperty("count", item.count());
                }
                case QuestReward.Levels levels -> {
                    object.addProperty("kind", "levels");
                    object.addProperty("amount", levels.amount());
                }
                case QuestReward.Reputation reputation -> {
                    object.addProperty("kind", "reputation");
                    object.addProperty("track", reputation.track());
                    object.addProperty("amount", reputation.amount());
                }
                case QuestReward.ChoiceOf choice -> {
                    object.addProperty("kind", "choice_of");
                    JsonArray options = new JsonArray();
                    for (QuestReward.ChoiceOf.Option option : choice.options()) {
                        options.add(optionJson(option));
                    }
                    object.add("options", options);
                }
            }
            array.add(object);
        }
        return array;
    }

    private static JsonObject optionJson(QuestReward.ChoiceOf.Option option) {
        JsonObject object = new JsonObject();
        object.addProperty("id", option.id());
        object.addProperty("silvermarks", option.silvermarks());
        JsonArray items = new JsonArray();
        for (QuestReward.ItemEntry item : option.items()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item.item());
            entry.addProperty("count", item.count());
            items.add(entry);
        }
        object.add("items", items);
        if (option.gate() != null) {
            object.addProperty("gate", option.gate());
        }
        return object;
    }

    // ------------------------------------------------------------------ payment

    /**
     * Pays the direct entries. {@code choice_of} must be routed by the caller through
     * the deferred reward-choice flow; reaching one here is a programming error.
     */
    public static void grantDirect(ServerLevel world, ServerPlayer player, List<QuestReward> entries) {
        for (QuestReward entry : entries) {
            switch (entry) {
                case QuestReward.Waycoins waycoins -> {
                    if (waycoins.amount() > 0) {
                        // Authored "silvermarks"/waycoins amounts (v1 panel contract) pay as silver.
                        CurrencyService.addBalance(world, player.getUUID(), CurrencyService.silver(waycoins.amount()));
                    }
                }
                case QuestReward.Levels levels -> QuestExperienceService.grant(player, levels.amount(), 0,
                        QuestExperienceService.RewardType.DAILY);
                case QuestReward.Reputation reputation -> {
                    try {
                        ReputationService.ReputationTrack track =
                                ReputationService.ReputationTrack.valueOf(reputation.track());
                        ReputationService.add(world, player.getUUID(), track, reputation.amount());
                    } catch (IllegalArgumentException exception) {
                        Trakt.LOGGER.warn("[trakt] unknown reputation track {} in quest reward",
                                reputation.track());
                    }
                }
                case QuestReward.ItemEntry itemEntry -> {
                    Item item = itemOf(itemEntry.item());
                    if (item == null) {
                        continue;
                    }
                    ItemStack stack = new ItemStack(item, itemEntry.count());
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false, Prediction.SERVER_ONLY);
                    }
                }
                case QuestReward.ChoiceOf choice ->
                        throw new IllegalStateException("choice_of rewards must go through the reward-choice flow");
            }
        }
    }

    private static Item itemOf(String rawId) {
        if (rawId == null || rawId.isBlank() || rawId.startsWith("#")) {
            return null;
        }
        try {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(rawId);
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                return null;
            }
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
