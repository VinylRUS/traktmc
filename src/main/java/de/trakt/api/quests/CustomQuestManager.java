package de.trakt.api.quests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.Trakt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Registry of {@link CustomQuestDefinition}s backed by JSON files in
 * {@code config/trakt/api/quests/*.json}. Files survive restarts and world resets;
 * changes made through the API apply immediately to the running server.
 */
public final class CustomQuestManager {
    private static final CustomQuestManager INSTANCE = new CustomQuestManager();

    private final Map<String, CustomQuestDefinition> quests = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public static CustomQuestManager get() {
        return INSTANCE;
    }

    private CustomQuestManager() {}

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(Trakt.MOD_ID).resolve("api").resolve("quests");
    }

    /** Loads (or reloads) all definitions from disk. Damaged files are skipped with a log entry. */
    public synchronized List<String> loadAll() {
        List<String> problems = new ArrayList<>();
        quests.clear();
        try {
            Files.createDirectories(dir());
            try (var stream = Files.list(dir())) {
                for (Path file : stream.sorted().filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        JsonObject root = JsonParser.parseString(
                                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                        List<String> errors = new ArrayList<>();
                        CustomQuestDefinition.ParseResult parsed = CustomQuestDefinition.parse(root, errors);
                        if (parsed == null || parsed.definition() == null) {
                            problems.add(file.getFileName() + ": " + String.join("; ", errors));
                            continue;
                        }
                        CustomQuestDefinition definition = parsed.definition();
                        quests.put(definition.id(), definition);
                    } catch (Exception exception) {
                        problems.add(file.getFileName() + ": " + exception.getMessage());
                        Trakt.LOGGER.warn("[trakt api] damaged custom quest file {}", file, exception);
                    }
                }
            }
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to list custom quests", exception);
        }
        loaded = true;
        Trakt.LOGGER.info("[trakt api] loaded {} custom quest(s)", quests.size());
        return problems;
    }

    private void ensureLoaded() {
        if (!loaded) {
            loadAll();
        }
    }

    public List<CustomQuestDefinition> all() {
        ensureLoaded();
        return new ArrayList<>(quests.values());
    }

    public Optional<CustomQuestDefinition> find(String id) {
        ensureLoaded();
        return Optional.ofNullable(quests.get(id));
    }

    public boolean exists(String id) {
        ensureLoaded();
        return quests.containsKey(id);
    }

    /** Persists a new or updated definition. The caller is responsible for registry validation. */
    public synchronized void save(CustomQuestDefinition definition) throws IOException {
        Path file = dir().resolve(definition.id() + ".json");
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, definition.toJson().toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        quests.put(definition.id(), definition);
    }

    public synchronized boolean delete(String id) {
        ensureLoaded();
        if (quests.remove(id) == null) {
            return false;
        }
        try {
            Files.deleteIfExists(dir().resolve(id + ".json"));
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to delete custom quest {}", id, exception);
        }
        return true;
    }
}
