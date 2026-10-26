package de.trakt.api;

import com.google.gson.JsonObject;
import de.trakt.Trakt;
import de.trakt.api.quests.CustomQuestManager;
import de.trakt.api.quests.CustomQuestService;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Bootstrap of the REST API subsystem: config, persistent stores, embedded HTTP server,
 * in-game commands and the event feed. Registered from {@code Trakt.onInitialize()}.
 *
 * <p>All state exposed through the API is server-authoritative; handlers marshal game reads
 * onto the main server thread (see {@link ApiServer#onServer}).
 */
public final class ApiService {
    private static volatile MinecraftServer server;
    private static volatile long startedAtMs;
    private static volatile ApiEventLog eventLog;
    private static volatile ApiLinkStore links;

    private ApiService() {}

    public static void register() {
        ApiLang.load();
        eventLog = ApiEventLog.load(ApiConfig.get().maxEvents());
        links = ApiLinkStore.load();
        for (String problem : CustomQuestManager.get().loadAll()) {
            Trakt.LOGGER.warn("[trakt api] quest file problem: {}", problem);
        }

        ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> {
            server = startedServer;
            startedAtMs = System.currentTimeMillis();
            ApiLang.load();
            for (String problem : CustomQuestManager.get().loadAll()) {
                Trakt.LOGGER.warn("[trakt api] quest file problem: {}", problem);
            }
            try {
                if ("agent".equalsIgnoreCase(ApiConfig.get().transport())) {
                    ApiAgent.get().start(ApiConfig.get());
                } else {
                    ApiServer.get().start(ApiConfig.get());
                }
            } catch (Exception exception) {
                Trakt.LOGGER.error("[trakt api] failed to start transport '{}' (target {})",
                        ApiConfig.get().transport(),
                        "agent".equalsIgnoreCase(ApiConfig.get().transport())
                                ? ApiConfig.get().panelUrl()
                                : ApiConfig.get().bind() + ":" + ApiConfig.get().port(),
                        exception);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("online_players", startedServer.getPlayerList().getPlayerCount());
            eventLog().emit("server_started", null, null, payload);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(stoppingServer -> {
            // Emit the stopping event FIRST so the agent transport can flush it to the panel,
            // then tear down both transports before the world saves (see QuestService stop hook).
            eventLog().emit("server_stopping", null, null, new JsonObject());
            ApiServer.get().stop();
            ApiAgent.get().stop();
            server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(CustomQuestService::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) ->
                joinedServer.execute(() -> {
                    UUID uuid = handler.player.getUUID();
                    String name = handler.player.getGameProfile().name();
                    links().rememberName(uuid, name);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("online_after", joinedServer.getPlayerList().getPlayerCount());
                    eventLog().emit("player_joined", uuid, name, payload);
                }));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, leftServer) ->
                leftServer.execute(() -> {
                    UUID uuid = handler.player.getUUID();
                    String name = handler.player.getGameProfile().name();
                    JsonObject payload = new JsonObject();
                    payload.addProperty("online_after", Math.max(0, leftServer.getPlayerList().getPlayerCount() - 1));
                    eventLog().emit("player_left", uuid, name, payload);
                }));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ApiCommands.register(dispatcher));
    }

    public static MinecraftServer server() {
        return server;
    }

    public static long startedAtMs() {
        return startedAtMs;
    }

    public static ApiEventLog eventLog() {
        return eventLog;
    }

    public static ApiLinkStore links() {
        return links;
    }

    /** Thread-safe event emission used from server-thread hooks. */
    public static void emitEvent(MinecraftServer target, String type, UUID playerUuid,
                                 String playerName, JsonObject data) {
        ApiEventLog log = eventLog;
        if (log != null) {
            log.emit(type, playerUuid, playerName, data);
        }
    }

    /**
     * Unified quest-completion hook: appends a {@code quest_completed} event and bumps the
     * permanent per-player counter used by leaderboards. Called from the daily, weekly and
     * custom completion paths.
     */
    public static void onQuestCompleted(MinecraftServer target, UUID playerId, String playerName,
                                        String questId, String cycle, JsonObject extraData) {
        if (target == null || playerId == null) {
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("quest_id", questId);
        data.addProperty("cycle", cycle);
        if (extraData != null) {
            for (String key : extraData.keySet()) {
                data.add(key, extraData.get(key));
            }
        }
        emitEvent(target, "quest_completed", playerId, playerName, data);
        try {
            de.trakt.data.QuestState.get(target).getPlayerData(playerId)
                    .addTradeRouteInt("api.stats.quests_total", 1);
        } catch (RuntimeException exception) {
            Trakt.LOGGER.debug("[trakt api] counter update failed", exception);
        }
    }

    /** True when the player name should be resolved through the running server. */
    public static boolean isRunning() {
        return server != null;
    }
}
