package de.trakt.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.Trakt;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Outbound "agent" transport for hostings that do not allow inbound ports (exarathon).
 *
 * <p>Instead of listening on a TCP port, the mod connects OUT to the panel's public
 * HTTPS endpoint and speaks the small gateway protocol described in rest_api.md
 * ("Transport B"):
 *
 * <ul>
 *   <li>{@code GET  {panel}/trakt/commands?after_id=N} — heartbeat + fetch queued
 *       virtual HTTP requests (each command is a {@code {method, path, query, body}}
 *       triple executed through the very same {@link ApiRoutes#dispatch} used by the
 *       embedded server, so the endpoint contract is identical for both transports);</li>
 *   <li>{@code POST {panel}/trakt/results} — push command results back;</li>
 *   <li>{@code POST {panel}/trakt/events} — push new event-log entries, with ack.</li>
 * </ul>
 *
 * <p>Delivery semantics: commands execute at most once (the executed id is persisted
 * before results are posted); event pushes resume from the panel's acked id, so
 * nothing is lost across restarts; results are retried from a bounded queue. When the
 * hosting suspends the server, the poll loop dies with it — the panel detects the
 * silence and marks the server offline. The gateway state (last acked event id, last
 * executed command id) lives in {@code config/trakt/api/gateway.json}.
 */
public final class ApiAgent {
    private static final int MAX_PENDING_RESULTS = 256;
    private static final int MAX_COMMANDS_PER_CYCLE = 16;
    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private volatile HttpClient http;
    private volatile Thread worker;
    private volatile boolean running;
    private long lastAckedEventId;
    private long lastCommandId;
    private final Deque<JsonObject> pendingResults = new ArrayDeque<>();

    private volatile boolean connected;
    private volatile long lastSuccessMs;
    private volatile long commandsExecuted;
    private volatile long errorsTotal;
    private volatile int consecutiveErrors;
    private volatile String lastError = "";

    private ApiAgent() {}

    private static final class Holder {
        private static final ApiAgent INSTANCE = new ApiAgent();
    }

    static ApiAgent get() {
        return Holder.INSTANCE;
    }

    // ------------------------------------------------------------------ lifecycle

    void start(ApiConfig config) {
        stop();
        if (!config.enabled()) {
            Trakt.LOGGER.info("[trakt api] disabled in config; agent not started");
            return;
        }
        if (config.panelUrl().isBlank()) {
            Trakt.LOGGER.error("[trakt api] transport=agent but panel_url is empty; "
                    + "set panel_url in config/trakt/api.properties and run /trakt api reload");
            return;
        }
        loadState();
        synchronized (pendingResults) {
            pendingResults.clear();
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        connected = false;
        consecutiveErrors = 0;
        running = true;
        worker = Thread.ofVirtual().name("trakt-api-agent").start(this::loop);
        Trakt.LOGGER.info("[trakt api] agent transport: polling {} every {}s "
                        + "(server_id={}, resume events from id {}, commands after id {})",
                config.panelUrl(), config.pollSeconds(), config.serverId(),
                lastAckedEventId, lastCommandId);
    }

    void stop() {
        running = false;
        Thread current = worker;
        worker = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(3_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        HttpClient client = http;
        http = null;
        if (client != null) {
            try {
                flushOnce(client);
            } catch (Exception exception) {
                Trakt.LOGGER.debug("[trakt api] final gateway flush failed", exception);
            }
        }
        connected = false;
    }

    public boolean running() {
        return running;
    }

    public boolean connected() {
        return connected;
    }

    public long lastSuccessMs() {
        return lastSuccessMs;
    }

    public long commandsExecuted() {
        return commandsExecuted;
    }

    public long errorsTotal() {
        return errorsTotal;
    }

    public String lastError() {
        return lastError;
    }

    public long lastAckedEventId() {
        return lastAckedEventId;
    }

    // ------------------------------------------------------------------ main loop

    private void loop() {
        HttpClient client = http;
        while (running && Thread.currentThread() == worker) {
            try {
                cycle(client);
                connected = true;
                lastSuccessMs = System.currentTimeMillis();
                consecutiveErrors = 0;
                Thread.sleep(Math.max(1, ApiConfig.get().pollSeconds()) * 1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                connected = false;
                errorsTotal++;
                consecutiveErrors++;
                lastError = String.valueOf(exception.getMessage());
                if (consecutiveErrors == 1 || consecutiveErrors % 20 == 0) {
                    Trakt.LOGGER.warn("[trakt api] gateway unreachable "
                            + "(attempt {}): {}", consecutiveErrors, lastError);
                }
                long backoff = Math.min(MAX_BACKOFF_MS,
                        MIN_BACKOFF_MS << Math.min(consecutiveErrors - 1, 6));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void cycle(HttpClient client) throws Exception {
        ApiConfig config = ApiConfig.get();
        pollCommands(client, config);
        postResults(client, config);
        pushEvents(client, config);
    }

    // ------------------------------------------------------------------ commands

    private void pollCommands(HttpClient client, ApiConfig config) throws Exception {
        String uri = config.panelUrl() + "/trakt/commands"
                + "?after_id=" + lastCommandId
                + "&server_id=" + url(config.serverId())
                + "&online=" + onlinePlayers()
                + "&mod_version=" + url(ApiRoutes.modVersion());
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.gatewayToken())
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        requireOk(response, "commands poll");
        JsonObject body = parseObject(response.body());
        JsonArray commands = body.has("commands") && body.get("commands").isJsonArray()
                ? body.getAsJsonArray("commands") : null;
        if (commands == null || commands.isEmpty()) {
            return;
        }
        int executed = 0;
        for (var element : commands) {
            if (executed >= MAX_COMMANDS_PER_CYCLE) {
                Trakt.LOGGER.warn("[trakt api] {} commands queued; "
                        + "executing 16 this cycle, rest next cycle", commands.size());
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject command = element.getAsJsonObject();
            long id = command.has("id") && command.get("id").isJsonPrimitive()
                    ? command.get("id").getAsLong() : -1;
            if (id <= lastCommandId) {
                continue; // duplicate delivery; already executed
            }
            JsonObject result = executeCommand(id, command);
            synchronized (pendingResults) {
                pendingResults.addLast(result);
                while (pendingResults.size() > MAX_PENDING_RESULTS) {
                    pendingResults.removeFirst();
                }
            }
            // Persist BEFORE posting results: a command must never execute twice.
            lastCommandId = id;
            saveState();
            commandsExecuted++;
            executed++;
        }
    }

    private JsonObject executeCommand(long id, JsonObject command) {
        String method = command.has("method") && command.get("method").isJsonPrimitive()
                ? command.get("method").getAsString() : "GET";
        String path = command.has("path") && command.get("path").isJsonPrimitive()
                ? command.get("path").getAsString() : "/api";
        String query = command.has("query") && command.get("query").isJsonPrimitive()
                ? command.get("query").getAsString() : null;
        JsonObject body = command.has("body") && command.get("body").isJsonObject()
                ? command.getAsJsonObject("body") : new JsonObject();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("command_id", id);
        try {
            JsonObject result = ApiRoutes.dispatch(
                    method.toUpperCase(Locale.ROOT), path, query, body);
            envelope.addProperty("status", 200);
            envelope.add("body", result);
        } catch (ApiServer.ApiError error) {
            envelope.addProperty("status", error.status());
            envelope.add("body", ApiServer.errorJson(error.code(), error.getMessage()));
        } catch (Exception exception) {
            Trakt.LOGGER.warn("[trakt api] gateway command {} failed", id, exception);
            envelope.addProperty("status", 500);
            envelope.add("body", ApiServer.errorJson("internal_error",
                    String.valueOf(exception.getMessage())));
        }
        return envelope;
    }

    // ------------------------------------------------------------------ results

    private void postResults(HttpClient client, ApiConfig config) throws Exception {
        List<JsonObject> snapshot;
        synchronized (pendingResults) {
            if (pendingResults.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pendingResults);
        }
        JsonArray results = new JsonArray();
        snapshot.forEach(results::add);
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", config.serverId());
        payload.add("results", results);
        HttpResponse<String> response = sendJson(client, config, "POST", "/trakt/results", payload);
        requireOk(response, "results push");
        synchronized (pendingResults) {
            pendingResults.removeAll(snapshot);
        }
    }

    // ------------------------------------------------------------------ events

    private void pushEvents(HttpClient client, ApiConfig config) throws Exception {
        List<JsonObject> events = ApiService.eventLog().after(lastAckedEventId, config.pushBatch());
        if (events.isEmpty()) {
            return;
        }
        long firstId = events.get(0).get("id").getAsLong();
        long lastId = events.get(events.size() - 1).get("id").getAsLong();
        JsonArray array = new JsonArray();
        events.forEach(array::add);
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", config.serverId());
        payload.addProperty("first_event_id", firstId);
        payload.addProperty("last_event_id", lastId);
        payload.add("events", array);
        HttpResponse<String> response = sendJson(client, config, "POST", "/trakt/events", payload);
        requireOk(response, "events push");
        JsonObject body = parseObject(response.body());
        long delivered = body.has("acked_event_id") && body.get("acked_event_id").isJsonPrimitive()
                ? body.get("acked_event_id").getAsLong() : lastId;
        if (delivered > lastAckedEventId) {
            lastAckedEventId = delivered;
            saveState();
        }
    }

    /** Best-effort final push used from {@link #stop()} (server stopping / config reload). */
    private void flushOnce(HttpClient client) {
        try {
            ApiConfig config = ApiConfig.get();
            postResults(client, config);
            pushEvents(client, config);
        } catch (Exception exception) {
            Trakt.LOGGER.debug("[trakt api] gateway flush skipped: {}",
                    String.valueOf(exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------ http helpers

    private static HttpResponse<String> sendJson(HttpClient client, ApiConfig config, String method,
                                                 String path, JsonObject payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.panelUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + config.gatewayToken())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                        payload.toString(), StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void requireOk(HttpResponse<String> response, String what) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException(what + ": gateway rejected us (HTTP " + status + "; "
                    + (status == 401
                            ? "gateway_token mismatch — the same token must be configured in the panel"
                            : "unknown server_id") + ")");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(what + ": HTTP " + status);
        }
    }

    private static JsonObject parseObject(String raw) {
        try {
            return JsonParser.parseString(raw == null || raw.isBlank() ? "{}" : raw).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("gateway returned invalid JSON", exception);
        }
    }

    private static int onlinePlayers() {
        net.minecraft.server.MinecraftServer server = ApiService.server();
        return server == null ? 0 : server.getPlayerCount();
    }

    private static String url(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ gateway state

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(Trakt.MOD_ID).resolve("api").resolve("gateway.json");
    }

    private void loadState() {
        lastAckedEventId = 0;
        lastCommandId = 0;
        try {
            Path path = stateFile();
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonObject state = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            lastAckedEventId = state.has("last_acked_event_id")
                    ? state.get("last_acked_event_id").getAsLong() : 0;
            lastCommandId = state.has("last_command_id")
                    ? state.get("last_command_id").getAsLong() : 0;
        } catch (Exception exception) {
            Trakt.LOGGER.warn("[trakt api] failed to read gateway state; "
                    + "resuming from 0", exception);
        }
    }

    private void saveState() {
        try {
            Path path = stateFile();
            Files.createDirectories(path.getParent());
            JsonObject state = new JsonObject();
            state.addProperty("last_acked_event_id", lastAckedEventId);
            state.addProperty("last_command_id", lastCommandId);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, state.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to persist gateway state", exception);
        }
    }
}
