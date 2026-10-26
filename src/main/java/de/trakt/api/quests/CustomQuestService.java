package de.trakt.api.quests;

import com.google.gson.JsonObject;
import de.trakt.Trakt;
import de.trakt.api.ApiEventLog;
import de.trakt.data.PlayerQuestData;
import de.trakt.data.QuestState;
import de.trakt.economy.CurrencyService;
import de.trakt.economy.QuestExperienceService;
import de.trakt.quest.definition.QuestReward;
import de.trakt.quest.definition.QuestRewards;
import de.trakt.reputation.ReputationService;
import de.trakt.util.TimeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Runtime pipeline for panel-created custom quests. Fully parallel to the built-in daily and
 * weekly flows: acceptance through commands, progress through the shared event hooks, automatic
 * completion, direct reward payout. All per-player state lives in the save-safe generic
 * trade-route string maps with an {@code api.custom.} key prefix (see PLAN.md, extension seams).
 */
public final class CustomQuestService {
    private static final String ACTIVE_KEY = "api.custom.active";
    private static final String PROGRESS_PREFIX = "api.custom.";
    private static final String PROGRESS_SUFFIX = ".progress";
    private static final String BASE_SUFFIX = ".base";
    private static final String DONE_DAY_SUFFIX = ".done_day";
    private static final String DONE_FLAG_PREFIX = "api.custom.";
    private static final String DONE_FLAG_SUFFIX = ".done";
    /** Acceptance-time reward snapshot identity (choice_of quests), set at accept. */
    private static final String REWARD_IDENTITY_KEY = "api.custom.reward_identity";
    private static final String STATS_QUESTS_TOTAL = "api.stats.quests_total";
    private static final String STATS_CUSTOM_TOTAL = "api.stats.custom_total";

    public record ActiveQuest(CustomQuestDefinition definition, int progress, int target) {}

    private CustomQuestService() {}

    // ------------------------------------------------------------------ state helpers

    private static PlayerQuestData data(MinecraftServer server, UUID playerId) {
        return QuestState.get(server).getPlayerData(playerId);
    }

    private static String activeId(PlayerQuestData data) {
        String id = data.getTradeRouteString(ACTIVE_KEY);
        return id == null || id.isBlank() ? null : id;
    }

    private static String progressKey(String questId, String suffix) {
        return PROGRESS_PREFIX + questId + suffix;
    }

    private static String doneFlag(String questId) {
        return DONE_FLAG_PREFIX + questId + DONE_FLAG_SUFFIX;
    }

    // ------------------------------------------------------------------ queries

    public static ActiveQuest activeQuest(MinecraftServer server, UUID playerId) {
        if (server == null || playerId == null) {
            return null;
        }
        PlayerQuestData data = data(server, playerId);
        String id = activeId(data);
        if (id == null) {
            return null;
        }
        CustomQuestDefinition definition = CustomQuestManager.get().find(id).orElse(null);
        if (definition == null) {
            // Quest was deleted while active; clear the stale reference.
            data.setTradeRouteString(ACTIVE_KEY, "");
            return null;
        }
        int progress = data.getTradeRouteInt(progressKey(id, PROGRESS_SUFFIX));
        return new ActiveQuest(definition, Math.min(progress, definition.goal().amount()), definition.goal().amount());
    }

    /** Custom quests the player may currently accept. */
    public static List<CustomQuestDefinition> availableFor(MinecraftServer server, UUID playerId) {
        List<CustomQuestDefinition> result = new ArrayList<>();
        PlayerQuestData data = data(server, playerId);
        long today = TimeUtil.currentDay();
        for (CustomQuestDefinition definition : CustomQuestManager.get().all()) {
            if (!definition.enabled()) {
                continue;
            }
            if (CustomQuestDefinition.REPEAT_ONCE.equals(definition.repeat())
                    && data.hasTradeRouteFlag(doneFlag(definition.id()))) {
                continue;
            }
            if (CustomQuestDefinition.REPEAT_DAILY.equals(definition.repeat())
                    && data.getTradeRouteInt(progressKey(definition.id(), DONE_DAY_SUFFIX)) == today) {
                continue;
            }
            result.add(definition);
        }
        return result;
    }

    // ------------------------------------------------------------------ lifecycle

    public static String accept(ServerLevel world, ServerPlayer player, String questId) {
        CustomQuestDefinition definition = CustomQuestManager.get().find(questId).orElse(null);
        if (definition == null || !definition.enabled()) {
            return "Квест '" + questId + "' не найден или отключён. Список: /trakt custom";
        }
        PlayerQuestData data = data(world.getServer(), player.getUUID());
        if (activeId(data) != null) {
            return "Сначала завершите или отмените текущее задание: /trakt custom abandon";
        }
        if (CustomQuestDefinition.REPEAT_ONCE.equals(definition.repeat())
                && data.hasTradeRouteFlag(doneFlag(questId))) {
            return "Это задание уже выполнено — оно одноразовое.";
        }
        if (CustomQuestDefinition.REPEAT_DAILY.equals(definition.repeat())
                && data.getTradeRouteInt(progressKey(questId, DONE_DAY_SUFFIX)) == TimeUtil.currentDay()) {
            return "Это задание уже выполнено сегодня — приходите завтра.";
        }

        // choice_of quests reserve their reward snapshot at acceptance time (F2a):
        // the options are frozen, the player picks one at claim time. A full reward
        // backlog blocks acceptance — same rule as the daily flow.
        QuestReward.ChoiceOf choice = definition.choiceOf();
        String rewardIdentity = null;
        if (choice != null) {
            rewardIdentity = questId + ":" + TimeUtil.currentDay();
            if (!de.trakt.reward.VanillaRewardService.prepareCustom(world, player, questId, choice, rewardIdentity)) {
                // The reward service already messaged the player (backlog full / unavailable).
                return "Награда не зарезервирована — сперва заберите накопленные награды: /trakt rewards claim";
            }
        }

        data.setTradeRouteString(ACTIVE_KEY, questId);
        data.setTradeRouteInt(progressKey(questId, PROGRESS_SUFFIX), 0);
        data.setTradeRouteInt(progressKey(questId, BASE_SUFFIX), currentStatValue(player, definition));
        if (rewardIdentity != null) {
            data.setTradeRouteString(REWARD_IDENTITY_KEY, rewardIdentity);
        }
        QuestState.get(world.getServer()).setDirty();

        String locale = de.trakt.api.ApiConfig.get().defaultLocale();
        player.sendSystemMessage(Component.literal("✦ Принято: " + definition.title(locale))
                .withStyle(ChatFormatting.GREEN), false);
        String offer = definition.offer(locale);
        if (!offer.isBlank()) {
            player.sendSystemMessage(Component.literal(offer).withStyle(ChatFormatting.GRAY), false);
        }
        sendProgressActionbar(player, definition, 0);

        JsonObject payload = new JsonObject();
        payload.addProperty("quest_id", questId);
        payload.addProperty("quest_title", definition.title(locale));
        de.trakt.api.ApiService.emitEvent(world.getServer(), "custom_quest_accepted",
                player.getUUID(), player.getGameProfile().name(), payload);
        return null;
    }

    public static boolean abandon(ServerLevel world, ServerPlayer player) {
        PlayerQuestData data = data(world.getServer(), player.getUUID());
        String id = activeId(data);
        if (id == null) {
            return false;
        }
        data.setTradeRouteString(ACTIVE_KEY, "");
        QuestState.get(world.getServer()).setDirty();
        player.sendSystemMessage(Component.literal("Задание отменено.").withStyle(ChatFormatting.GRAY), false);
        JsonObject payload = new JsonObject();
        payload.addProperty("quest_id", id);
        de.trakt.api.ApiService.emitEvent(world.getServer(), "custom_quest_abandoned",
                player.getUUID(), player.getGameProfile().name(), payload);
        return true;
    }

    // ------------------------------------------------------------------ hooks (dispatched from DailyQuestService)

    public static void onBlockBreak(ServerLevel world, ServerPlayer player, BlockPos pos, BlockState state) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.BREAK_BLOCK) {
            return;
        }
        String blockId = active.definition().goal().block();
        BlockState target = blockStateOf(blockId);
        if (target == null || !state.is(target.getBlock())) {
            return;
        }
        credit(world, player, active, 1);
    }

    public static void onMonsterKill(ServerLevel world, ServerPlayer player, Entity killedEntity) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.KILL_ENTITY) {
            return;
        }
        String entityId = active.definition().goal().entity();
        var type = entityTypeOf(entityId);
        if (type == null || killedEntity.getType() != type) {
            return;
        }
        credit(world, player, active, 1);
    }

    public static void onSheepSheared(ServerLevel world, ServerPlayer player, Sheep sheep) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.SHEAR_SHEEP) {
            return;
        }
        credit(world, player, active, 1);
    }

    public static void onFurnaceOutput(ServerLevel world, ServerPlayer player, ItemStack stack) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.SMELT_ITEM) {
            return;
        }
        Item item = itemOf(active.definition().goal().item());
        if (item == null || stack.isEmpty() || !stack.is(item)) {
            return;
        }
        credit(world, player, active, stack.getCount());
    }

    public static void onVillagerTrade(ServerLevel world, ServerPlayer player, ItemStack stack) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.VILLAGER_TRADE) {
            return;
        }
        credit(world, player, active, 1);
    }

    public static void onAnimalLove(ServerLevel world, ServerPlayer player, Animal animal) {
        ActiveQuest active = activeQuest(world.getServer(), player.getUUID());
        if (active == null || active.definition().goal().kind() != CustomQuestDefinition.GoalKind.BREED_ANIMAL) {
            return;
        }
        String entityId = active.definition().goal().entity();
        if (entityId != null && !entityId.isBlank()) {
            var type = entityTypeOf(entityId);
            if (type == null || animal.getType() != type) {
                return;
            }
        }
        credit(world, player, active, 1);
    }

    // ------------------------------------------------------------------ tick (stat-delta goals)

    public static void onServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ActiveQuest active = activeQuest(server, player.getUUID());
            if (active == null) {
                continue;
            }
            CustomQuestDefinition.GoalKind kind = active.definition().goal().kind();
            if (kind != CustomQuestDefinition.GoalKind.COLLECT_ITEM
                    && kind != CustomQuestDefinition.GoalKind.CRAFT_ITEM) {
                continue;
            }
            PlayerQuestData data = data(server, player.getUUID());
            String questId = active.definition().id();
            int baseline = data.getTradeRouteInt(progressKey(questId, BASE_SUFFIX));
            int delta = currentStatValue(player, active.definition()) - baseline;
            if (delta < 0) {
                delta = 0;
            }
            int stored = data.getTradeRouteInt(progressKey(questId, PROGRESS_SUFFIX));
            int capped = Math.min(delta, active.definition().goal().amount());
            if (capped > stored) {
                data.setTradeRouteInt(progressKey(questId, PROGRESS_SUFFIX), capped);
                QuestState.get(server).setDirty();
                sendProgressActionbar(player, active.definition(), capped);
                tryComplete(server, player, active.definition(), capped);
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private static void credit(ServerLevel world, ServerPlayer player, ActiveQuest active, int amount) {
        if (amount <= 0) {
            return;
        }
        String questId = active.definition().id();
        PlayerQuestData data = data(world.getServer(), player.getUUID());
        int current = data.getTradeRouteInt(progressKey(questId, PROGRESS_SUFFIX));
        int target = active.definition().goal().amount();
        if (current >= target) {
            return;
        }
        int updated = Math.min(target, current + amount);
        data.setTradeRouteInt(progressKey(questId, PROGRESS_SUFFIX), updated);
        QuestState.get(world.getServer()).setDirty();
        sendProgressActionbar(player, active.definition(), updated);
        tryComplete(world.getServer(), player, active.definition(), updated);
    }

    private static int currentStatValue(ServerPlayer player, CustomQuestDefinition definition) {
        return switch (definition.goal().kind()) {
            case COLLECT_ITEM -> statValue(player, definition.goal().item(), Stats.ITEM_PICKED_UP);
            case CRAFT_ITEM -> statValue(player, definition.goal().item(), Stats.ITEM_CRAFTED);
            default -> 0;
        };
    }

    private static int statValue(ServerPlayer player, String itemId, net.minecraft.stats.StatType<Item> type) {
        Item item = itemOf(itemId);
        if (item == null) {
            return 0;
        }
        return player.getStats().getValue(type.get(item));
    }

    private static void tryComplete(MinecraftServer server, ServerPlayer player,
                                    CustomQuestDefinition definition, int progress) {
        if (progress < definition.goal().amount()) {
            return;
        }
        PlayerQuestData data = data(server, player.getUUID());
        String questId = definition.id();
        data.setTradeRouteString(ACTIVE_KEY, "");
        data.setTradeRouteInt(progressKey(questId, DONE_DAY_SUFFIX), (int) TimeUtil.currentDay());
        data.setTradeRouteFlag(doneFlag(questId), true);
        data.addTradeRouteInt(STATS_CUSTOM_TOTAL, 1);
        QuestState.get(server).setDirty();

        ServerLevel world = server.overworld();
        if (definition.choiceOf() != null) {
            // Deferred payout through the reward-choice flow: the snapshot was frozen
            // at acceptance; complete() queues it as an earned choice and opens the UI.
            String identity = data.getTradeRouteString(REWARD_IDENTITY_KEY);
            if (identity == null || identity.isBlank()) {
                identity = questId + ":" + TimeUtil.currentDay();
            }
            de.trakt.reward.VanillaRewardService.complete(world, player, "custom", identity);
        } else {
            de.trakt.quest.definition.QuestRewards.grantDirect(world, player, definition.rewardList());
        }

        String locale = de.trakt.api.ApiConfig.get().defaultLocale();
        player.sendSystemMessage(Component.literal("✔ Выполнено: " + definition.title(locale))
                .withStyle(ChatFormatting.GOLD), false);
        player.sendSystemMessage(rewardsSummary(definition, locale), false);

        JsonObject payload = new JsonObject();
        payload.addProperty("quest_title", definition.title(locale));
        payload.add("rewards", rewardsJson(definition));
        de.trakt.api.ApiService.onQuestCompleted(server, player.getUUID(),
                player.getGameProfile().name(), "custom/" + questId, "custom", payload);
    }

    private static Component rewardsSummary(CustomQuestDefinition definition, String locale) {
        List<String> parts = new ArrayList<>();
        QuestReward.ChoiceOf choice = definition.choiceOf();
        if (choice != null) {
            return Component.literal("Награда: выбор из " + choice.options().size()
                    + " вариантов — /trakt rewards claim").withStyle(ChatFormatting.GRAY);
        }
        CustomQuestDefinition.Rewards rewards = definition.rewards();
        if (rewards.silvermarks() > 0) {
            parts.add(de.trakt.api.ApiText.flatten(
                    CurrencyService.formatDelta(CurrencyService.silver(rewards.silvermarks())), locale));
        }
        if (rewards.levels() > 0) {
            parts.add(rewards.levels() + " ур. опыта");
        }
        if (rewards.reputationAmount() > 0 && rewards.reputationTrack() != null) {
            parts.add("+" + rewards.reputationAmount() + " репутации");
        }
        for (CustomQuestDefinition.RewardItem rewardItem : rewards.items()) {
            Item item = itemOf(rewardItem.item());
            String name = item == null ? rewardItem.item()
                    : de.trakt.api.ApiText.flatten(new ItemStack(item).getHoverName(), locale);
            parts.add(rewardItem.count() + "× " + name);
        }
        if (parts.isEmpty()) {
            return Component.literal("Награда: безвозмездно, то есть даром.").withStyle(ChatFormatting.GRAY);
        }
        return Component.literal("Награда: " + String.join(", ", parts)).withStyle(ChatFormatting.GRAY);
    }

    private static JsonObject rewardsJson(CustomQuestDefinition definition) {
        CustomQuestDefinition.Rewards rewards = definition.rewards();
        JsonObject object = new JsonObject();
        QuestReward.ChoiceOf choice = definition.choiceOf();
        if (choice != null) {
            object.addProperty("mode", "choice_of");
            com.google.gson.JsonArray options = new com.google.gson.JsonArray();
            for (QuestReward.ChoiceOf.Option option : choice.options()) {
                JsonObject optionJson = new JsonObject();
                optionJson.addProperty("id", option.id());
                optionJson.addProperty("silvermarks", option.silvermarks());
                com.google.gson.JsonArray items = new com.google.gson.JsonArray();
                for (QuestReward.ItemEntry item : option.items()) {
                    JsonObject itemJson = new JsonObject();
                    itemJson.addProperty("item", item.item());
                    itemJson.addProperty("count", item.count());
                    items.add(itemJson);
                }
                optionJson.add("items", items);
                options.add(optionJson);
            }
            object.add("choice_of", options);
            return object;
        }
        object.addProperty("silvermarks", rewards.silvermarks());
        object.addProperty("levels", rewards.levels());
        if (rewards.reputationAmount() > 0 && rewards.reputationTrack() != null) {
            object.addProperty("reputation_track", rewards.reputationTrack());
            object.addProperty("reputation_amount", rewards.reputationAmount());
        }
        if (!rewards.items().isEmpty()) {
            object.addProperty("items", rewards.items().size() + " stack(s)");
        }
        return object;
    }

    static void sendProgressActionbar(ServerPlayer player, CustomQuestDefinition definition, int progress) {
        String locale = de.trakt.api.ApiConfig.get().defaultLocale();
        String subject = goalSubject(definition, locale);
        player.sendSystemMessage(
                Component.literal(definition.title(locale) + ": " + progress + "/" + definition.goal().amount()
                        + (subject.isBlank() ? "" : " · " + subject)).withStyle(ChatFormatting.YELLOW),
                true);
    }

    /** Human-readable goal subject, e.g. the localized item name. */
    public static String goalSubject(CustomQuestDefinition definition, String locale) {
        return switch (definition.goal().kind()) {
            case COLLECT_ITEM, CRAFT_ITEM, SMELT_ITEM -> {
                Item item = itemOf(definition.goal().item());
                yield item == null ? "" : de.trakt.api.ApiText.flatten(new ItemStack(item).getHoverName(), locale);
            }
            case BREAK_BLOCK -> {
                var state = blockStateOf(definition.goal().block());
                yield state == null ? "" : de.trakt.api.ApiText.flatten(state.getBlock().getName(), locale);
            }
            case KILL_ENTITY, BREED_ANIMAL -> {
                var type = entityTypeOf(definition.goal().entity());
                yield type == null ? "" : de.trakt.api.ApiText.flatten(type.getDescription(), locale);
            }
            case SHEAR_SHEEP -> "овцы";
            case VILLAGER_TRADE -> "сделки с жителями";
        };
    }

    // ------------------------------------------------------------------ registry lookups

    public static Item itemOf(String rawId) {
        return registryValue(BuiltInRegistries.ITEM, rawId);
    }

    public static BlockState blockStateOf(String rawId) {
        var block = registryValue(BuiltInRegistries.BLOCK, rawId);
        return block == null ? null : block.defaultBlockState();
    }

    public static net.minecraft.world.entity.EntityType<?> entityTypeOf(String rawId) {
        return registryValue(BuiltInRegistries.ENTITY_TYPE, rawId);
    }

    private static <T> T registryValue(net.minecraft.core.Registry<T> registry, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            Identifier id = Identifier.parse(rawId);
            if (!registry.containsKey(id)) {
                return null;
            }
            return registry.getValue(id);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Registry-aware validation used by the REST layer. Must run on the server thread. */
    public static void validateRegistryReferences(CustomQuestDefinition definition, List<String> errors) {
        CustomQuestDefinition.Goal goal = definition.goal();
        switch (goal.kind()) {
            case COLLECT_ITEM, CRAFT_ITEM, SMELT_ITEM -> {
                if (!BuiltInRegistries.ITEM.containsKey(parseId(goal.item(), errors, "goal.item"))) {
                    errors.add("goal.item '" + goal.item() + "' is not a known item");
                }
            }
            case BREAK_BLOCK -> {
                if (!BuiltInRegistries.BLOCK.containsKey(parseId(goal.block(), errors, "goal.block"))) {
                    errors.add("goal.block '" + goal.block() + "' is not a known block");
                }
            }
            case KILL_ENTITY, BREED_ANIMAL -> {
                if (goal.entity() != null && !goal.entity().isBlank()
                        && !BuiltInRegistries.ENTITY_TYPE.containsKey(parseId(goal.entity(), errors, "goal.entity"))) {
                    errors.add("goal.entity '" + goal.entity() + "' is not a known entity type");
                }
            }
            default -> {
            }
        }
        // Reward item references across the typed entry list, including choice_of options.
        for (de.trakt.quest.definition.QuestRewards.DirectView.ItemRef rewardItem
                : de.trakt.quest.definition.QuestRewards.allItems(definition.rewardList())) {
            if (!BuiltInRegistries.ITEM.containsKey(parseId(rewardItem.item(), errors, "rewards"))) {
                errors.add("rewards item '" + rewardItem.item() + "' is not a known item");
            }
        }
        // Direct-entry reputation tracks keep the v1 enum contract.
        CustomQuestDefinition.Rewards rewards = definition.rewards();
        String track = rewards.reputationTrack();
        if (track != null && !track.isBlank()) {
            try {
                ReputationService.ReputationTrack.valueOf(track);
            } catch (IllegalArgumentException exception) {
                errors.add("rewards.reputation_track must be one of: FARMING, CRAFTING, ANIMALS, TRADE, MONSTER_HUNTING");
            }
        }
    }

    private static Identifier parseId(String raw, List<String> errors, String field) {
        try {
            return Identifier.parse(raw);
        } catch (RuntimeException exception) {
            errors.add(field + " '" + raw + "' is not a valid identifier");
            return Identifier.withDefaultNamespace("air");
        }
    }

    // ------------------------------------------------------------------ misc

    /** Reads the permanent per-player quest counters used by leaderboards. */
    public static int statQuestsTotal(PlayerQuestData data) {
        return data.getTradeRouteInt(STATS_QUESTS_TOTAL);
    }

    public static int statCustomTotal(PlayerQuestData data) {
        return data.getTradeRouteInt(STATS_CUSTOM_TOTAL);
    }
}
