package de.trakt.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.trakt.Trakt;
import de.trakt.api.ApiServer.ApiError;
import de.trakt.api.quests.CustomQuestDefinition;
import de.trakt.api.quests.CustomQuestManager;
import de.trakt.api.quests.CustomQuestService;
import de.trakt.caravan.TradeRouteService;
import de.trakt.data.PlayerQuestData;
import de.trakt.data.QuestState;
import de.trakt.economy.CurrencyService;
import de.trakt.guild.VillageGuildState;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.weekly.WeeklyQuestService;
import de.trakt.quest.weekly.WeeklyQuestStatus;
import de.trakt.reputation.ReputationService;
import de.trakt.shrine.VillageBondService;
import de.trakt.village.LivingVillageNetworkState;
import de.trakt.village.VillageCondition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Route table of the embedded REST API. Handlers that read game state wrap their work in
 * {@link ApiServer#onServer(Supplier)}; the full contract lives in rest_api.md.
 */
final class ApiRoutes {
    private ApiRoutes() {}

    private record Route(String method, String[] segments) {
        boolean matches(String method, String[] path) {
            if (!this.method.equalsIgnoreCase(method)) {
                return false;
            }
            if (this.segments.length != path.length) {
                return false;
            }
            for (int i = 0; i < segments.length; i++) {
                if (!segments[i].startsWith("{") && !segments[i].equals(path[i])) {
                    return false;
                }
            }
            return true;
        }

        Map<String, String> params(String[] path) {
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < segments.length; i++) {
                if (segments[i].startsWith("{")) {
                    params.put(segments[i].substring(1, segments[i].length() - 1), path[i]);
                }
            }
            return params;
        }
    }

    private static final List<Route> ROUTES = List.of(
            new Route("GET", new String[]{"api"}),
            new Route("GET", new String[]{"api", "health"}),
            new Route("GET", new String[]{"api", "players"}),
            new Route("GET", new String[]{"api", "players", "{uuid}"}),
            new Route("GET", new String[]{"api", "players", "by-discord", "{discordId}"}),
            new Route("GET", new String[]{"api", "quests"}),
            new Route("GET", new String[]{"api", "quests", "{id}"}),
            new Route("GET", new String[]{"api", "quests", "{cycle}", "{shortId}"}),
            new Route("POST", new String[]{"api", "quests"}),
            new Route("PATCH", new String[]{"api", "quests", "{id}"}),
            new Route("PATCH", new String[]{"api", "quests", "{cycle}", "{shortId}"}),
            new Route("DELETE", new String[]{"api", "quests", "{id}"}),
            new Route("DELETE", new String[]{"api", "quests", "{cycle}", "{shortId}"}),
            new Route("GET", new String[]{"api", "quest-definitions"}),
            new Route("GET", new String[]{"api", "quest-definitions", "{id}"}),
            new Route("GET", new String[]{"api", "questmasters"}),
            new Route("GET", new String[]{"api", "villages"}),
            new Route("GET", new String[]{"api", "caravans"}),
            new Route("GET", new String[]{"api", "economy", "summary"}),
            new Route("GET", new String[]{"api", "leaderboards", "{type}"}),
            new Route("GET", new String[]{"api", "events"}),
            new Route("POST", new String[]{"api", "links", "claim"}),
            new Route("GET", new String[]{"api", "links", "{discordId}"}),
            new Route("DELETE", new String[]{"api", "links", "{discordId}"}));

    static JsonObject dispatch(String method, String path, String rawQuery, JsonObject body) {
        String[] segments = splitPath(path);
        for (Route route : ROUTES) {
            if (route.matches(method, segments)) {
                return handle(route, segments, queryParams(rawQuery), body);
            }
        }
        // Distinguish 404 (unknown path) from 405 (known path, wrong method).
        for (Route route : ROUTES) {
            if (route.matches("GET", segments) || route.matches("POST", segments)
                    || route.matches("PATCH", segments) || route.matches("DELETE", segments)) {
                throw new ApiError(405, "method_not_allowed", method + " is not supported on " + path);
            }
        }
        throw ApiServer.notFound("unknown endpoint; GET /api lists all routes");
    }

    private static String[] splitPath(String path) {
        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                parts.add(segment);
            }
        }
        return parts.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ handler entry

    private static JsonObject handle(Route route, String[] segments, Map<String, String> query, JsonObject body) {
        Map<String, String> params = route.params(segments);
        return switch (String.join("/", route.segments()) + " " + route.method()) {
            case "api GET" -> index();
            case "api/health GET" -> health();
            case "api/players GET" -> players(query);
            case "api/players/{uuid} GET" -> playerCard(params.get("uuid"));
            case "api/players/by-discord/{discordId} GET" -> playerCardByDiscord(params.get("discordId"));
            case "api/quests GET" -> questList();
            case "api/quests/{id} GET" -> questDetail(params.get("id"));
            case "api/quests/{cycle}/{shortId} GET" -> questDetail(
                    params.get("cycle") + "/" + params.get("shortId"));
            case "api/quests POST" -> questCreate(body);
            case "api/quests/{id} PATCH" -> questUpdate(params.get("id"), body);
            case "api/quests/{cycle}/{shortId} PATCH" -> questUpdate(
                    params.get("cycle") + "/" + params.get("shortId"), body);
            case "api/quests/{id} DELETE" -> questDelete(params.get("id"));
            case "api/quests/{cycle}/{shortId} DELETE" -> questDelete(
                    params.get("cycle") + "/" + params.get("shortId"));
            case "api/quest-definitions GET" -> definitionList();
            case "api/quest-definitions/{id} GET" -> definitionDetail(params.get("id"));
            case "api/questmasters GET" -> questmasters();
            case "api/villages GET" -> villages(query);
            case "api/caravans GET" -> caravans();
            case "api/economy/summary GET" -> economySummary();
            case "api/leaderboards/{type} GET" -> leaderboard(params.get("type"), query);
            case "api/events GET" -> events(query);
            case "api/links/claim POST" -> linkClaim(body);
            case "api/links/{discordId} GET" -> linkStatus(params.get("discordId"));
            case "api/links/{discordId} DELETE" -> linkDelete(params.get("discordId"));
            default -> throw ApiServer.notFound("unknown endpoint");
        };
    }

    // ------------------------------------------------------------------ index & health

    private static JsonObject index() {
        JsonObject result = new JsonObject();
        result.addProperty("name", "Trakt REST API");
        result.addProperty("mod_id", Trakt.MOD_ID);
        result.addProperty("docs", "rest_api.md in the trakt-remake repository");
        JsonArray endpoints = new JsonArray();
        for (Route route : ROUTES) {
            endpoints.add(route.method() + " /" + String.join("/", route.segments));
        }
        result.add("endpoints", endpoints);
        return result;
    }

    private static JsonObject health() {
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            JsonObject result = new JsonObject();
            result.addProperty("status", "ok");
            result.addProperty("mod_version", modVersion());
            result.addProperty("online_players", server.getPlayerList().getPlayerCount());
            result.addProperty("max_players", server.getMaxPlayers());
            result.addProperty("uptime_seconds", (System.currentTimeMillis() - ApiService.startedAtMs()) / 1000L);
            result.addProperty("events_last_id", ApiService.eventLog().lastId());
            result.addProperty("custom_quests", CustomQuestManager.get().all().size());
            return result;
        });
    }

    static String modVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(Trakt.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // ------------------------------------------------------------------ players

    private static JsonObject players(Map<String, String> query) {
        boolean all = "true".equalsIgnoreCase(query.getOrDefault("all", "false"));
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            JsonArray list = new JsonArray();
            if (!all) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("uuid", player.getUUID().toString());
                    entry.addProperty("name", player.getGameProfile().name());
                    entry.addProperty("online", true);
                    list.add(entry);
                }
                JsonObject result = new JsonObject();
                result.addProperty("count", list.size());
                result.add("players", list);
                return result;
            }
            java.util.LinkedHashMap<UUID, Boolean> known = new java.util.LinkedHashMap<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                known.put(player.getUUID(), true);
            }
            for (UUID uuid : QuestState.get(server).getPlayersView().keySet()) {
                known.putIfAbsent(uuid, false);
            }
            for (Map.Entry<UUID, Boolean> entry : known.entrySet()) {
                JsonObject object = new JsonObject();
                object.addProperty("uuid", entry.getKey().toString());
                ServerPlayer onlinePlayer = entry.getValue() ? server.getPlayerList().getPlayer(entry.getKey()) : null;
                object.addProperty("name", onlinePlayer != null
                        ? onlinePlayer.getGameProfile().name() : nameOf(entry.getKey()));
                object.addProperty("online", entry.getValue());
                list.add(object);
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", list.size());
            result.add("players", list);
            return result;
        });
    }

    private static JsonObject playerCardByDiscord(String discordIdRaw) {
        long discordId = parseLong(discordIdRaw, "discord id must be numeric");
        ApiLinkStore.LinkEntry link = ApiService.links().linkForDiscord(discordId);
        if (link == null) {
            throw ApiServer.notFound("no minecraft account linked to discord id " + discordId);
        }
        JsonObject card = playerCard(link.mcUuid().toString());
        JsonObject result = new JsonObject();
        result.addProperty("discord_id", discordId);
        result.add("link", linkJson(link));
        result.add("player", card);
        return result;
    }

    private static JsonObject playerCard(String uuidRaw) {
        UUID uuid = parseUuid(uuidRaw);
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            ServerLevel world = server.overworld();
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            PlayerQuestData data = QuestState.get(server).getPlayerData(uuid);

            JsonObject result = new JsonObject();
            result.addProperty("uuid", uuid.toString());
            result.addProperty("name", online != null ? online.getGameProfile().name() : nameOf(uuid));
            result.addProperty("online", online != null);
            // v1 contract: money fields are reported in silver waycoins (F7c adds waycoin fields).
            result.addProperty("balance", CurrencyService.toSilver(CurrencyService.getBalance(world, uuid)));

            // Daily quest
            JsonObject daily = new JsonObject();
            DailyQuestService.QuestStatus status = DailyQuestService.openQuestStatus(world, uuid);
            daily.addProperty("active", DailyQuestService.isDailyActive(world, uuid));
            daily.addProperty("completed_today", DailyQuestService.isDailyCompleted(world, uuid));
            DailyQuestService.DailyAdminInfo admin = DailyQuestService.adminInfo(world, uuid);
            if (admin != null && admin.quest() != null) {
                daily.addProperty("quest_id", "daily/" + admin.quest().name().toLowerCase(java.util.Locale.ROOT));
                daily.addProperty("state", admin.state().name().toLowerCase(java.util.Locale.ROOT));
                daily.addProperty("title", flatten(status == null ? null : status.title()));
                if (status != null) {
                    daily.addProperty("progress_line", flatten(status.progressLine()));
                }
            }
            result.add("daily", daily);

            // Weekly quest
            JsonObject weekly = new JsonObject();
            WeeklyQuestStatus weeklyStatus = WeeklyQuestService.openStatus(world, uuid);
            weekly.addProperty("active", WeeklyQuestService.isWeeklyActive(world, uuid));
            weekly.addProperty("completed_count", WeeklyQuestService.completedCount(world, uuid));
            if (weeklyStatus != null) {
                weekly.addProperty("title", flatten(weeklyStatus.title()));
                JsonArray lines = new JsonArray();
                for (net.minecraft.network.chat.Component line : weeklyStatus.lines()) {
                    lines.add(flatten(line));
                }
                weekly.add("progress_lines", lines);
            }
            result.add("weekly", weekly);

            // Custom quest
            JsonObject custom = new JsonObject();
            CustomQuestService.ActiveQuest active = CustomQuestService.activeQuest(server, uuid);
            if (active != null) {
                custom.addProperty("id", active.definition().id());
                custom.addProperty("title", active.definition().title(ApiConfig.get().defaultLocale()));
                custom.addProperty("progress", active.progress());
                custom.addProperty("target", active.target());
                custom.addProperty("subject", CustomQuestService.goalSubject(active.definition(), ApiConfig.get().defaultLocale()));
            }
            custom.addProperty("available_count", CustomQuestService.availableFor(server, uuid).size());
            result.add("custom", custom);

            // Reputation
            JsonObject reputation = new JsonObject();
            reputation.addProperty("total", ReputationService.total(world, uuid));
            JsonObject tracks = new JsonObject();
            for (ReputationService.ReputationTrack track : ReputationService.ReputationTrack.values()) {
                tracks.addProperty(track.name().toLowerCase(java.util.Locale.ROOT),
                        ReputationService.get(world, uuid, track));
            }
            reputation.add("tracks", tracks);
            result.add("reputation", reputation);

            // Trade routes
            JsonObject routes = new JsonObject();
            routes.addProperty("count", TradeRouteService.routeCount(world, uuid));
            routes.addProperty("total_successes", TradeRouteService.totalRouteSuccesses(world, uuid));
            routes.addProperty("income_today", TradeRouteService.incomeToday(world, uuid));
            routes.addProperty("escrow", TradeRouteService.escrow(world, uuid));
            result.add("routes", routes);

            // Villages & guild
            result.addProperty("village_count", VillageBondService.villageCount(world, uuid));
            result.addProperty("in_guild", VillageGuildState.get(server).guildFor(uuid) != null);

            // Quest counters
            result.addProperty("quests_completed_total", CustomQuestService.statQuestsTotal(data));

            // Discord link
            ApiLinkStore.LinkEntry link = ApiService.links().linkForMc(uuid);
            result.add("discord_link", link == null ? null : linkJson(link));

            return result;
        });
    }

    // ------------------------------------------------------------------ quests

    private static JsonObject questList() {
        JsonObject result = new JsonObject();
        JsonArray quests = new JsonArray();
        String locale = ApiConfig.get().defaultLocale();
        for (var definition : de.trakt.quest.daily.DailyQuestGenerator.definitions()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", "daily/" + definition.type().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("cycle", "daily");
            object.addProperty("source", "builtin");
            object.addProperty("category", definition.type().category().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("title_en", flattenEn(definition.title()));
            object.addProperty("title_ru", flattenRu(definition.title()));
            object.addProperty("completion_mode", definition.completionMode().name());
            quests.add(object);
        }
        for (var definition : de.trakt.quest.weekly.WeeklyQuestGenerator.definitions()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", "weekly/" + definition.type().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("cycle", "weekly");
            object.addProperty("source", "builtin");
            object.addProperty("category", definition.type().category().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("title_en", flattenEn(definition.title()));
            object.addProperty("title_ru", flattenRu(definition.title()));
            object.addProperty("completion_mode", definition.completionMode().name());
            quests.add(object);
        }
        for (CustomQuestDefinition definition : CustomQuestManager.get().all()) {
            quests.add(customQuestJson(definition, locale));
        }
        result.addProperty("count", quests.size());
        result.add("quests", quests);
        return result;
    }

    private static JsonObject customQuestJson(CustomQuestDefinition definition, String locale) {
        JsonObject object = new JsonObject();
        object.addProperty("id", "custom/" + definition.id());
        object.addProperty("short_id", definition.id());
        object.addProperty("cycle", "custom");
        object.addProperty("source", "custom");
        object.addProperty("enabled", definition.enabled());
        object.addProperty("title_en", definition.title("en_us"));
        object.addProperty("title_ru", definition.title("ru_ru"));
        JsonObject titles = new JsonObject();
        definition.titles().forEach(titles::addProperty);
        object.add("titles", titles);
        String offer = definition.offer(locale);
        if (!offer.isBlank()) {
            object.addProperty("offer", offer);
        }
        JsonObject goal = new JsonObject();
        goal.addProperty("kind", definition.goal().kind().id());
        if (definition.goal().item() != null) {
            goal.addProperty("item", definition.goal().item());
        }
        if (definition.goal().block() != null) {
            goal.addProperty("block", definition.goal().block());
        }
        if (definition.goal().entity() != null) {
            goal.addProperty("entity", definition.goal().entity());
        }
        goal.addProperty("amount", definition.goal().amount());
        object.add("goal", goal);
        CustomQuestDefinition.Rewards rewards = definition.rewards();
        JsonObject rewardsJson = new JsonObject();
        de.trakt.quest.definition.QuestReward.ChoiceOf choice = definition.choiceOf();
        if (choice != null) {
            // F2a: deferred choice rewards — options are listed for panel rendering;
            // the player picks one at claim time (/trakt rewards claim).
            rewardsJson.addProperty("mode", "choice_of");
            JsonArray options = new JsonArray();
            for (de.trakt.quest.definition.QuestReward.ChoiceOf.Option option : choice.options()) {
                JsonObject optionJson = new JsonObject();
                optionJson.addProperty("id", option.id());
                optionJson.addProperty("silvermarks", option.silvermarks());
                JsonArray optionItems = new JsonArray();
                for (de.trakt.quest.definition.QuestReward.ItemEntry item : option.items()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", item.item());
                    entry.addProperty("count", item.count());
                    optionItems.add(entry);
                }
                optionJson.add("items", optionItems);
                options.add(optionJson);
            }
            rewardsJson.add("choice_of", options);
            rewardsJson.addProperty("silvermarks", 0L);
            rewardsJson.addProperty("levels", 0);
            rewardsJson.add("items", new JsonArray());
        } else {
            rewardsJson.addProperty("silvermarks", rewards.silvermarks());
            rewardsJson.addProperty("levels", rewards.levels());
            if (rewards.reputationTrack() != null) {
                rewardsJson.addProperty("reputation_track", rewards.reputationTrack());
                rewardsJson.addProperty("reputation_amount", rewards.reputationAmount());
            }
            JsonArray items = new JsonArray();
            for (CustomQuestDefinition.RewardItem item : rewards.items()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("item", item.item());
                entry.addProperty("count", item.count());
                items.add(entry);
            }
            rewardsJson.add("items", items);
        }
        object.add("rewards", rewardsJson);
        object.addProperty("repeat", definition.repeat());
        object.addProperty("subject", CustomQuestService.goalSubject(definition, locale));
        return object;
    }

    private static JsonObject questDetail(String id) {
        String locale = ApiConfig.get().defaultLocale();
        if (id.startsWith("custom/")) {
            CustomQuestDefinition definition = CustomQuestManager.get().find(id.substring("custom/".length()))
                    .orElseThrow(() -> ApiServer.notFound("unknown custom quest " + id));
            return customQuestJson(definition, locale);
        }
        // Builtin: reuse the listing and pick the entry.
        JsonObject list = questList();
        for (var element : list.getAsJsonArray("quests")) {
            if (element.isJsonObject() && id.equals(element.getAsJsonObject().get("id").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw ApiServer.notFound("unknown quest " + id);
    }

    private static JsonObject questCreate(JsonObject body) {
        if (body == null || body.isJsonNull()) {
            throw ApiServer.badRequest("request body required");
        }
        List<String> errors = new ArrayList<>();
        CustomQuestDefinition.ParseResult parsed = CustomQuestDefinition.parse(body, errors);
        if (parsed == null || parsed.definition() == null) {
            throw ApiServer.badRequest(String.join("; ", errors));
        }
        CustomQuestDefinition definition = parsed.definition();
        // Registry validation must happen on the server thread.
        ApiServer.onServer(() -> {
            CustomQuestService.validateRegistryReferences(definition, errors);
            return Boolean.TRUE;
        });
        if (!errors.isEmpty()) {
            throw ApiServer.badRequest(String.join("; ", errors));
        }
        if (CustomQuestManager.get().exists(definition.id())) {
            throw new ApiError(409, "conflict", "custom quest '" + definition.id() + "' already exists");
        }
        try {
            CustomQuestManager.get().save(definition);
        } catch (java.io.IOException exception) {
            throw new ApiError(500, "storage_error", "failed to persist quest: " + exception.getMessage());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("quest_id", definition.id());
        payload.addProperty("quest_title", definition.title(ApiConfig.get().defaultLocale()));
        ApiService.emitEvent(ApiService.server(), "custom_quest_created", null, null, payload);
        JsonObject result = customQuestJson(definition, ApiConfig.get().defaultLocale());
        result.addProperty("created", true);
        return result;
    }

    private static JsonObject questUpdate(String id, JsonObject body) {
        if (!id.startsWith("custom/")) {
            throw ApiServer.badRequest("built-in quests cannot be modified; create a custom quest instead");
        }
        String shortId = id.substring("custom/".length());
        CustomQuestDefinition definition = CustomQuestManager.get().find(shortId)
                .orElseThrow(() -> ApiServer.notFound("unknown custom quest " + id));
        if (body == null) {
            throw ApiServer.badRequest("request body required");
        }
        if (body.has("enabled") && body.get("enabled").isJsonPrimitive()) {
            definition.setEnabled(body.get("enabled").getAsBoolean());
        }
        List<String> errors = new ArrayList<>();
        if (body.has("goal") || body.has("title") || body.has("rewards")) {
            JsonObject replacement = definition.toJson();
            for (String key : List.of("title", "offer", "goal", "rewards", "repeat")) {
                if (body.has(key)) {
                    replacement.add(key, body.get(key));
                }
            }
            CustomQuestDefinition.ParseResult parsed = CustomQuestDefinition.parse(replacement, errors);
            if (parsed == null || parsed.definition() == null) {
                throw ApiServer.badRequest(String.join("; ", errors));
            }
            definition = parsed.definition();
            CustomQuestDefinition finalDefinition = definition;
            ApiServer.onServer(() -> {
                CustomQuestService.validateRegistryReferences(finalDefinition, errors);
                return Boolean.TRUE;
            });
            if (!errors.isEmpty()) {
                throw ApiServer.badRequest(String.join("; ", errors));
            }
        }
        try {
            CustomQuestManager.get().save(definition);
        } catch (java.io.IOException exception) {
            throw new ApiError(500, "storage_error", "failed to persist quest: " + exception.getMessage());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("quest_id", definition.id());
        ApiService.emitEvent(ApiService.server(), "custom_quest_updated", null, null, payload);
        return customQuestJson(definition, ApiConfig.get().defaultLocale());
    }

    private static JsonObject questDelete(String id) {
        if (!id.startsWith("custom/")) {
            throw ApiServer.badRequest("built-in quests cannot be deleted");
        }
        String shortId = id.substring("custom/".length());
        if (!CustomQuestManager.get().delete(shortId)) {
            throw ApiServer.notFound("unknown custom quest " + id);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("quest_id", shortId);
        ApiService.emitEvent(ApiService.server(), "custom_quest_deleted", null, null, payload);
        JsonObject result = new JsonObject();
        result.addProperty("deleted", true);
        result.addProperty("id", id);
        return result;
    }

    // ------------------------------------------------------------------ quest definition registry (F1b)

    /**
     * GET /api/quest-definitions — the unified definition registry (4 layers,
     * datapack > world > panel legacy config > builtin). One row per effective
     * definition; {@code source} names the layer that won.
     */
    private static JsonObject definitionList() {
        var registry = de.trakt.quest.definition.QuestDefinitionRegistry.get();
        String locale = ApiConfig.get().defaultLocale();
        JsonArray definitions = new JsonArray();
        for (var definition : registry.all()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", definition.id());
            object.addProperty("source", definition.source().id());
            object.addProperty("cycle", de.trakt.quest.daily.DailyQuestGenerator.isDailySlot(definition.id())
                    ? "daily" : "custom");
            object.addProperty("enabled", definition.enabled());
            object.addProperty("title", definitionTitle(definition, locale));
            object.addProperty("completion_mode", definition.completionMode().id());
            object.addProperty("repeat", definition.repeat());
            object.addProperty("goals", definition.goals().size());
            object.addProperty("rewards", de.trakt.quest.definition.QuestRewards
                    .toObjectJson(definition.rewardsList()).toString());
            if (!definition.extraChoices().isEmpty()) {
                object.addProperty("extra_choices", definition.extraChoices().size());
            }
            if (!definition.turnInItems().isEmpty()) {
                object.addProperty("turn_in_items", definition.turnInItems().size());
            }
            definitions.add(object);
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", definitions.size());
        var problems = registry.problems();
        result.addProperty("problems", problems.size());
        result.add("definitions", definitions);
        return result;
    }

    /** GET /api/quest-definitions/{id} — one definition in its authored (round-trip) form. */
    private static JsonObject definitionDetail(String id) {
        var registry = de.trakt.quest.definition.QuestDefinitionRegistry.get();
        var definition = registry.find(id)
                .orElseThrow(() -> ApiServer.notFound("unknown quest definition '" + id + "'"));
        JsonObject result = new JsonObject();
        result.addProperty("id", definition.id());
        result.addProperty("source", definition.source().id());
        result.addProperty("cycle", de.trakt.quest.daily.DailyQuestGenerator.isDailySlot(definition.id())
                ? "daily" : "custom");
        result.addProperty("enabled", definition.enabled());
        result.addProperty("file", definition.file());
        result.add("definition", definition.toJson());
        return result;
    }

    private static String definitionTitle(de.trakt.quest.definition.QuestDefinition definition, String locale) {
        if (definition.usesLangKeys()) {
            return definition.titleKey();
        }
        String title = definition.titles().get(locale);
        if (title == null || title.isBlank()) {
            title = definition.titles().get("en_us");
        }
        return title == null || title.isBlank() ? definition.id() : title;
    }

    // ------------------------------------------------------------------ world & economy

    private static JsonObject villages(Map<String, String> query) {
        int limit = intParam(query, "limit", 200, 1, 1000);
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            String locale = ApiConfig.get().defaultLocale();
            JsonArray villages = new JsonArray();
            for (var entry : LivingVillageNetworkState.get(server).villagesView().entrySet()) {
                var key = entry.getKey();
                var snapshot = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("owner_uuid", key.ownerId().toString());
                object.addProperty("owner_name", nameOf(key.ownerId()));
                object.addProperty("index", key.index());
                object.addProperty("x", snapshot.x());
                object.addProperty("z", snapshot.z());
                object.addProperty("type", snapshot.type().name().toLowerCase(java.util.Locale.ROOT));
                object.addProperty("need", snapshot.need() == null ? "" : snapshot.need().key());
                object.addProperty("need_label", snapshot.need() == null ? ""
                        : flatten(snapshot.need().label()));
                object.addProperty("support", snapshot.support());
                object.addProperty("condition", snapshot.condition() == null ? ""
                        : snapshot.condition().name().toLowerCase(java.util.Locale.ROOT));
                object.addProperty("cycles_completed", snapshot.cyclesCompleted());
                object.addProperty("route_arrivals", snapshot.routeArrivals());
                object.addProperty("route_failures", snapshot.routeFailures());
                villages.add(object);
                if (villages.size() >= limit) {
                    break;
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", villages.size());
            result.add("villages", villages);
            return result;
        });
    }

    /** F6c: every settled and invited questmaster — where they stand and whose yard they live at. */
    private static JsonObject questmasters() {
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            de.trakt.questmaster.QuestMasterSettlementState state =
                    de.trakt.questmaster.QuestMasterSettlementState.get(server);
            JsonObject result = new JsonObject();
            JsonArray settled = new JsonArray();
            for (var entry : state.settlementsView().entrySet()) {
                de.trakt.questmaster.QuestMasterSettlementState.VillageKey key = entry.getKey();
                de.trakt.questmaster.QuestMasterSettlementState.Binding binding = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("village_key", key.dimension() + ":" + key.anchorX() + ":" + key.anchorZ());
                object.addProperty("dimension", key.dimension());
                object.addProperty("entity_uuid", binding.entityUuid().toString());
                object.addProperty("x", binding.settlePos().getX());
                object.addProperty("y", binding.settlePos().getY());
                object.addProperty("z", binding.settlePos().getZ());
                settled.add(object);
            }
            JsonArray invited = new JsonArray();
            for (var entry : state.invitedView().entrySet()) {
                de.trakt.questmaster.QuestMasterSettlementState.Invite invite = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("entity_uuid", entry.getKey().toString());
                object.addProperty("player_uuid", invite.playerUuid().toString());
                object.addProperty("player_name", invite.playerName());
                object.addProperty("yard_x", invite.yardPos().getX());
                object.addProperty("yard_y", invite.yardPos().getY());
                object.addProperty("yard_z", invite.yardPos().getZ());
                invited.add(object);
            }
            result.addProperty("settled_count", settled.size());
            result.addProperty("invited_count", invited.size());
            result.add("settled", settled);
            result.add("invited", invited);
            return result;
        });
    }

    private static JsonObject caravans() {
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            ServerLevel world = server.overworld();
            String locale = ApiConfig.get().defaultLocale();
            JsonArray players = new JsonArray();
            for (var entry : QuestState.get(server).getPlayersView().entrySet()) {
                UUID uuid = entry.getKey();
                int routeCount = TradeRouteService.routeCount(world, uuid);
                if (routeCount <= 0) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("uuid", uuid.toString());
                object.addProperty("name", nameOf(uuid));
                object.addProperty("online", server.getPlayerList().getPlayer(uuid) != null);
                object.addProperty("total_successes", TradeRouteService.totalRouteSuccesses(world, uuid));
                object.addProperty("income_today", TradeRouteService.incomeToday(world, uuid));
                object.addProperty("escrow", TradeRouteService.escrow(world, uuid));
                JsonArray routes = new JsonArray();
                for (int i = 0; i < routeCount; i++) {
                    JsonObject route = new JsonObject();
                    route.addProperty("index", i);
                    route.addProperty("name", flatten(TradeRouteService.routeDisplayName(world, uuid, i)));
                    route.addProperty("destination_x", TradeRouteService.routeDestinationX(world, uuid, i));
                    route.addProperty("destination_z", TradeRouteService.routeDestinationZ(world, uuid, i));
                    route.addProperty("active_event", TradeRouteService.hasActiveRouteEvent(world, uuid));
                    route.addProperty("successes", TradeRouteService.routeSuccesses(world, uuid, i));
                    routes.add(route);
                }
                object.add("routes", routes);
                players.add(object);
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", players.size());
            result.add("players", players);
            return result;
        });
    }

    private static JsonObject economySummary() {
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            ServerLevel world = server.overworld();
            long moneySupply = 0;
            long incomeToday = 0;
            long escrow = 0;
            long questsTotal = 0;
            int players = 0;
            for (var entry : QuestState.get(server).getPlayersView().entrySet()) {
                players++;
                moneySupply += CurrencyService.getBalance(world, entry.getKey());
                incomeToday += TradeRouteService.incomeToday(world, entry.getKey());
                escrow += TradeRouteService.escrow(world, entry.getKey());
                questsTotal += CustomQuestService.statQuestsTotal(entry.getValue());
            }
            JsonObject result = new JsonObject();
            result.addProperty("players_tracked", players);
            result.addProperty("online_players", server.getPlayerList().getPlayerCount());
            // v1 contract: money fields are reported in silver waycoins (F7c adds waycoin fields).
            result.addProperty("money_supply", CurrencyService.toSilver(moneySupply));
            result.addProperty("income_today", incomeToday);
            result.addProperty("escrow", CurrencyService.toSilver(escrow));
            result.addProperty("quests_completed_total", questsTotal);
            result.addProperty("currency_unit",
                    "waycoin (1 gold = 100 silver = 10000 copper); v1 fields in silver");
            return result;
        });
    }

    private static JsonObject leaderboard(String type, Map<String, String> query) {
        int limit = intParam(query, "limit", 10, 1, 100);
        return ApiServer.onServer(() -> {
            MinecraftServer server = ApiService.server();
            ServerLevel world = server.overworld();
            List<long[]> rows = new ArrayList<>(); // [value, ...] uses index 0 as value
            List<UUID> uuids = new ArrayList<>();
            for (var entry : QuestState.get(server).getPlayersView().entrySet()) {
                UUID uuid = entry.getKey();
                long value = switch (type) {
                    // v1 contract: wealth is reported in silver waycoins (D7).
                    case "wealth" -> CurrencyService.toSilver(CurrencyService.getBalance(world, uuid));
                    case "reputation" -> ReputationService.total(world, uuid);
                    case "quests_total" -> CustomQuestService.statQuestsTotal(entry.getValue());
                    case "route_successes" -> TradeRouteService.totalRouteSuccesses(world, uuid);
                    case "weekly_completed" -> WeeklyQuestService.completedCount(world, uuid);
                    default -> throw ApiServer.badRequest(
                            "unknown leaderboard type '" + type + "'; valid: wealth, reputation, quests_total, route_successes, weekly_completed");
                };
                rows.add(new long[]{value});
                uuids.add(uuid);
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.<Integer>comparingLong(i -> rows.get(i)[0]).reversed());
            JsonArray list = new JsonArray();
            int rank = 1;
            for (int index : order) {
                if (list.size() >= limit) {
                    break;
                }
                long value = rows.get(index)[0];
                if (value <= 0) {
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("rank", rank++);
                row.addProperty("uuid", uuids.get(index).toString());
                row.addProperty("name", nameOf(uuids.get(index)));
                row.addProperty("value", value);
                list.add(row);
            }
            JsonObject result = new JsonObject();
            result.addProperty("type", type);
            result.add("entries", list);
            return result;
        });
    }

    // ------------------------------------------------------------------ events & links

    private static JsonObject events(Map<String, String> query) {
        long afterId = longParam(query, "after_id", 0L);
        int limit = intParam(query, "limit", 100, 1, 1000);
        JsonObject result = new JsonObject();
        result.addProperty("last_id", ApiService.eventLog().lastId());
        JsonArray events = new JsonArray();
        for (JsonObject event : ApiService.eventLog().after(afterId, limit)) {
            events.add(event);
        }
        result.add("events", events);
        result.addProperty("count", events.size());
        return result;
    }

    private static JsonObject linkClaim(JsonObject body) {
        if (body == null) {
            throw ApiServer.badRequest("request body required: {code, discord_id, discord_username}");
        }
        String code = CustomQuestDefinition.stringOf(body, "code");
        Long discordId = optionalLong(body, "discord_id");
        String discordUsername = CustomQuestDefinition.stringOf(body, "discord_username");
        if (code == null || code.isBlank()) {
            throw ApiServer.badRequest("code is required");
        }
        if (discordId == null) {
            throw ApiServer.badRequest("discord_id is required (numeric)");
        }
        ApiLinkStore.LinkEntry link = ApiService.links().claim(code.trim(), discordId, discordUsername);
        if (link == null) {
            throw ApiServer.notFound("code not found or expired; the player must run /trakt link in game first");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("discord_id", discordId);
        payload.addProperty("mc_uuid", link.mcUuid().toString());
        payload.addProperty("mc_name", link.mcName());
        ApiService.emitEvent(ApiService.server(), "link_claimed", link.mcUuid(), link.mcName(), payload);
        JsonObject result = new JsonObject();
        result.add("link", linkJson(link));
        return result;
    }

    private static JsonObject linkStatus(String discordIdRaw) {
        long discordId = parseLong(discordIdRaw, "discord id must be numeric");
        ApiLinkStore.LinkEntry link = ApiService.links().linkForDiscord(discordId);
        if (link == null) {
            throw ApiServer.notFound("no minecraft account linked to discord id " + discordId);
        }
        JsonObject result = new JsonObject();
        result.add("link", linkJson(link));
        return result;
    }

    private static JsonObject linkDelete(String discordIdRaw) {
        long discordId = parseLong(discordIdRaw, "discord id must be numeric");
        if (!ApiService.links().unlinkDiscord(discordId)) {
            throw ApiServer.notFound("no minecraft account linked to discord id " + discordId);
        }
        JsonObject result = new JsonObject();
        result.addProperty("deleted", true);
        return result;
    }

    private static JsonObject linkJson(ApiLinkStore.LinkEntry link) {
        JsonObject object = new JsonObject();
        object.addProperty("discord_id", link.discordId());
        if (!link.discordUsername().isBlank()) {
            object.addProperty("discord_username", link.discordUsername());
        }
        object.addProperty("mc_uuid", link.mcUuid().toString());
        object.addProperty("mc_name", link.mcName());
        object.addProperty("linked_at", link.linkedAt());
        return object;
    }

    // ------------------------------------------------------------------ helpers

    private static String flatten(net.minecraft.network.chat.Component component) {
        return ApiText.flatten(component, ApiConfig.get().defaultLocale());
    }

    private static String flattenEn(net.minecraft.network.chat.Component component) {
        return ApiText.flatten(component, "en_us");
    }

    private static String flattenRu(net.minecraft.network.chat.Component component) {
        return ApiText.flatten(component, "ru_ru");
    }

    private static String nameOf(UUID uuid) {
        String name = ApiService.links().nameFor(uuid);
        return name == null ? "unknown" : name;
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return params;
    }

    private static String urlDecode(String raw) {
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static int intParam(Map<String, String> query, String key, int fallback, int minimum, int maximum) {
        String raw = query.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long longParam(Map<String, String> query, String key, long fallback) {
        String raw = query.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Long optionalLong(JsonObject object, String key) {
        try {
            return object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long parseLong(String raw, String message) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw ApiServer.badRequest(message);
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw ApiServer.badRequest("invalid uuid: " + raw);
        }
    }
}
