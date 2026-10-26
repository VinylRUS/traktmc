package de.trakt.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.Trakt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent, append-only event feed with monotonically increasing ids.
 *
 * <p>The panel polls {@code GET /api/events?after_id=N} and remembers the last id it has seen.
 * Storage: {@code config/trakt/api/events.jsonl} (one JSON object per line); the file is
 * trimmed to the configured capacity. Events survive server restarts and world resets, which is
 * what lets the panel catch up after the hosting provider (exarathon) wakes the server back up.
 */
public final class ApiEventLog {
    private record StoredEvent(long id, JsonObject payload) {}

    private final Path file;
    private final int capacity;
    private final Deque<StoredEvent> events = new ArrayDeque<>();
    private long lastId;

    private ApiEventLog(Path file, int capacity) {
        this.file = file;
        this.capacity = Math.max(100, capacity);
    }

    public static ApiEventLog load(int capacity) {
        Path path = dir().resolve("events.jsonl");
        ApiEventLog log = new ApiEventLog(path, capacity);
        try {
            if (Files.isRegularFile(path)) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonObject payload = JsonParser.parseString(line).getAsJsonObject();
                        long id = payload.get("id").getAsLong();
                        log.lastId = Math.max(log.lastId, id);
                        log.events.addLast(new StoredEvent(id, payload));
                        while (log.events.size() > log.capacity) {
                            log.events.removeFirst();
                        }
                    } catch (RuntimeException ignored) {
                        // Skip damaged line.
                    }
                }
            }
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to read {}", path, exception);
        }
        Trakt.LOGGER.info("[trakt api] event log: {} events retained, last id {}",
                log.events.size(), log.lastId);
        return log;
    }

    public long lastId() {
        synchronized (this) {
            return lastId;
        }
    }

    public int size() {
        synchronized (this) {
            return events.size();
        }
    }

    /** Appends an event. Callers are expected to be on the server thread; safe from any thread. */
    public JsonObject emit(String type, UUID playerUuid, String playerName, JsonObject data) {
        JsonObject payload = new JsonObject();
        long id;
        synchronized (this) {
            id = ++lastId;
        }
        payload.addProperty("id", id);
        payload.addProperty("ts", System.currentTimeMillis());
        payload.addProperty("type", type);
        if (playerUuid != null) {
            payload.addProperty("player_uuid", playerUuid.toString());
        }
        if (playerName != null && !playerName.isBlank()) {
            payload.addProperty("player_name", playerName);
        }
        payload.add("data", data == null ? new JsonObject() : data);
        synchronized (this) {
            events.addLast(new StoredEvent(id, payload));
            while (events.size() > capacity) {
                events.removeFirst();
            }
        }
        appendLine(payload);
        return payload;
    }

    /** Returns up to {@code limit} events strictly after {@code afterId}. */
    public List<JsonObject> after(long afterId, int limit) {
        List<JsonObject> result = new ArrayList<>();
        synchronized (this) {
            for (StoredEvent event : events) {
                if (event.id() <= afterId) {
                    continue;
                }
                result.add(event.payload());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private void appendLine(JsonObject payload) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, payload.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            maybeTrim();
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to append event", exception);
        }
    }

    private void maybeTrim() {
        if (events.size() < capacity * 2) {
            return;
        }
        List<String> retained = new ArrayList<>();
        synchronized (this) {
            for (StoredEvent event : events) {
                retained.add(event.payload().toString());
            }
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, retained, StandardCharsets.UTF_8);
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to trim event log", exception);
        }
    }

    public static JsonObject data(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    public static JsonObject data(String key, long value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    public static JsonObject data(String key, JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return object;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(Trakt.MOD_ID).resolve("api");
    }
}
