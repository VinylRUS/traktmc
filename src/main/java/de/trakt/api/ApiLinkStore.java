package de.trakt.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.Trakt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent Minecraft <-> Discord account links plus pending link codes.
 *
 * <p>Storage: {@code config/trakt/api/links.json} (survives world resets on purpose).
 * Flow: the player runs {@code /trakt link} in game which stores a pending 6-digit code bound to
 * their UUID; the panel/bot then claims that code via {@code POST /api/links/claim}.
 */
public final class ApiLinkStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record LinkEntry(long discordId, String discordUsername, UUID mcUuid, String mcName, long linkedAt) {}

    public record PendingCode(String code, UUID mcUuid, String mcName, long expiresAtMs) {}

    private final Path file;
    private final Map<Long, LinkEntry> linksByDiscord = new HashMap<>();
    private final Map<UUID, LinkEntry> linksByMc = new HashMap<>();
    private final Map<UUID, PendingCode> pendingByUuid = new HashMap<>();
    private final Map<UUID, String> knownNames = new HashMap<>();

    private ApiLinkStore(Path file) {
        this.file = file;
    }

    public static ApiLinkStore load() {
        Path path = dir().resolve("links.json");
        ApiLinkStore store = new ApiLinkStore(path);
        try {
            if (Files.isRegularFile(path)) {
                JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement element : jsonArray(root, "links")) {
                    LinkEntry entry = entryOf(element.getAsJsonObject());
                    store.linksByDiscord.put(entry.discordId(), entry);
                    store.linksByMc.put(entry.mcUuid(), entry);
                    store.knownNames.put(entry.mcUuid(), entry.mcName());
                }
                for (JsonElement element : jsonArray(root, "pending_codes")) {
                    JsonObject object = element.getAsJsonObject();
                    PendingCode code = new PendingCode(
                            object.get("code").getAsString(),
                            UUID.fromString(object.get("mc_uuid").getAsString()),
                            object.get("mc_name").getAsString(),
                            object.get("expires_at_ms").getAsLong());
                    store.pendingByUuid.put(code.mcUuid(), code);
                }
                for (Map.Entry<String, JsonElement> entry : jsonObject(root, "known_names").entrySet()) {
                    try {
                        store.knownNames.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
                    } catch (IllegalArgumentException ignored) {
                        // Corrupt uuid in cache file; skip.
                    }
                }
            }
        } catch (Exception exception) {
            Trakt.LOGGER.warn("[trakt api] failed to read {}; starting empty", path, exception);
        }
        return store;
    }

    /** Creates (or refreshes) a pending link code for the given player. Thread-safe. */
    public synchronized PendingCode createCode(UUID mcUuid, String mcName, long ttlMs) {
        purgeExpired();
        PendingCode existing = pendingByUuid.get(mcUuid);
        if (existing != null && existing.expiresAtMs() - System.currentTimeMillis() > ttlMs / 2) {
            return existing;
        }
        String code;
        do {
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (codeInUse(code));
        PendingCode pending = new PendingCode(code, mcUuid, mcName, System.currentTimeMillis() + ttlMs);
        pendingByUuid.put(mcUuid, pending);
        rememberName(mcUuid, mcName);
        save();
        return pending;
    }

    /** Claims a pending code on behalf of a Discord user. Returns the created link or null. */
    public synchronized LinkEntry claim(String code, long discordId, String discordUsername) {
        purgeExpired();
        PendingCode pending = null;
        for (PendingCode candidate : pendingByUuid.values()) {
            if (candidate.code().equals(code)) {
                pending = candidate;
                break;
            }
        }
        if (pending == null) {
            return null;
        }
        pendingByUuid.remove(pending.mcUuid());
        // Re-linking the same Minecraft account to another Discord id removes the old binding.
        LinkEntry previous = linksByMc.get(pending.mcUuid());
        if (previous != null) {
            linksByDiscord.remove(previous.discordId());
        }
        LinkEntry entry = new LinkEntry(discordId, discordUsername == null ? "" : discordUsername,
                pending.mcUuid(), pending.mcName(), System.currentTimeMillis());
        linksByDiscord.put(discordId, entry);
        linksByMc.put(pending.mcUuid(), entry);
        rememberName(pending.mcUuid(), pending.mcName());
        save();
        return entry;
    }

    public synchronized LinkEntry linkForDiscord(long discordId) {
        return linksByDiscord.get(discordId);
    }

    public synchronized LinkEntry linkForMc(UUID mcUuid) {
        return linksByMc.get(mcUuid);
    }

    public synchronized boolean unlinkDiscord(long discordId) {
        LinkEntry entry = linksByDiscord.remove(discordId);
        if (entry == null) {
            return false;
        }
        linksByMc.remove(entry.mcUuid());
        save();
        return true;
    }

    public synchronized boolean cancelCode(UUID mcUuid) {
        PendingCode removed = pendingByUuid.remove(mcUuid);
        if (removed == null) {
            return false;
        }
        save();
        return true;
    }

    public synchronized PendingCode pendingFor(UUID mcUuid) {
        purgeExpired();
        return pendingByUuid.get(mcUuid);
    }

    public synchronized void rememberName(UUID mcUuid, String name) {
        if (mcUuid == null || name == null || name.isBlank()) {
            return;
        }
        if (name.equals(knownNames.get(mcUuid))) {
            return;
        }
        knownNames.put(mcUuid, name);
        save();
    }

    public synchronized String nameFor(UUID mcUuid) {
        return knownNames.get(mcUuid);
    }

    public synchronized Map<UUID, String> namesView() {
        return new HashMap<>(knownNames);
    }

    public synchronized List<LinkEntry> linksView() {
        return new ArrayList<>(linksByDiscord.values());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = pendingByUuid.values().removeIf(code -> code.expiresAtMs() <= now);
        if (changed) {
            save();
        }
    }

    private boolean codeInUse(String code) {
        for (PendingCode pending : pendingByUuid.values()) {
            if (pending.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            JsonArray links = new JsonArray();
            for (LinkEntry entry : linksByDiscord.values()) {
                JsonObject object = new JsonObject();
                object.addProperty("discord_id", entry.discordId());
                object.addProperty("discord_username", entry.discordUsername());
                object.addProperty("mc_uuid", entry.mcUuid().toString());
                object.addProperty("mc_name", entry.mcName());
                object.addProperty("linked_at", Instant.ofEpochMilli(entry.linkedAt()).toString());
                links.add(object);
            }
            root.add("links", links);
            JsonArray pending = new JsonArray();
            for (PendingCode code : pendingByUuid.values()) {
                JsonObject object = new JsonObject();
                object.addProperty("code", code.code());
                object.addProperty("mc_uuid", code.mcUuid().toString());
                object.addProperty("mc_name", code.mcName());
                object.addProperty("expires_at_ms", code.expiresAtMs());
                pending.add(object);
            }
            root.add("pending_codes", pending);
            JsonObject names = new JsonObject();
            for (Map.Entry<UUID, String> entry : knownNames.entrySet()) {
                names.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("known_names", names);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to save {}", file, exception);
        }
    }

    private static JsonArray jsonArray(JsonObject root, String name) {
        JsonElement element = root.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject jsonObject(JsonObject root, String name) {
        JsonElement element = root.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static LinkEntry entryOf(JsonObject object) {
        return new LinkEntry(
                object.get("discord_id").getAsLong(),
                object.has("discord_username") ? object.get("discord_username").getAsString() : "",
                UUID.fromString(object.get("mc_uuid").getAsString()),
                object.get("mc_name").getAsString(),
                object.has("linked_at") ? Instant.parse(object.get("linked_at").getAsString()).toEpochMilli() : 0L);
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(Trakt.MOD_ID).resolve("api");
    }
}
