package de.trakt.quest.definition;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.trakt.Trakt;
import de.trakt.api.ApiService;
import de.trakt.mixin.MinecraftServerStorageAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Unified quest definition registry (F1a). Merges four layers, highest priority first:
 *
 * <ol>
 *   <li><b>data packs</b> — any {@code data/<namespace>/quests/<id>.json} visible to the
 *       server resource manager (user packs override the built-in pack through the vanilla
 *       pack stacking order, which {@code listResources} already resolves);</li>
 *   <li><b>world folder</b> — {@code <world>/trakt/quests/*.json}, editable by hand
 *       and by the panel (D9);</li>
 *   <li><b>panel config</b> — legacy {@code config/trakt/api/quests/*.json} written
 *       by the REST v1 contract (kept until F1b moves the panel onto the world folder);</li>
 *   <li><b>built-in pack</b> — {@code data/trakt/quests/*.json} inside the mod jar,
 *       the reference pack for the daily slots.</li>
 * </ol>
 *
 * <p>Override semantics: for a given quest id the highest layer that mentions the id wins;
 * a definition with {@code enabled:false} in a higher layer disables the quest — lower
 * layers are never re-consulted.
 */
public final class QuestDefinitionRegistry {

    /** Resource directory inside every data pack: {@code data/<ns>/quests/<id>.json}. */
    public static final String RESOURCE_DIRECTORY = "quests";

    private static final QuestDefinitionRegistry INSTANCE = new QuestDefinitionRegistry();

    public static QuestDefinitionRegistry get() {
        return INSTANCE;
    }

    public record ReloadReport(int total, int builtin, int datapack, int world, int api,
                                int disabled, Map<String, String> problems) {}

    /** Quest id -> enabled definition after the last reload. */
    private final Map<String, QuestDefinition> merged = new ConcurrentHashMap<>();
    /** Resource layer as resolved by the last data pack reload. */
    private final Map<String, QuestDefinition> resourceLayer = new ConcurrentHashMap<>();
    private final Map<String, String> problemsByFile = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private QuestDefinitionRegistry() {}

    // ------------------------------------------------------------------ queries

    public Optional<QuestDefinition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(merged.get(id));
    }

    /** Definitions a player can currently be offered: enabled only. */
    public List<QuestDefinition> available() {
        List<QuestDefinition> result = new ArrayList<>();
        for (QuestDefinition definition : merged.values()) {
            if (definition.enabled()) {
                result.add(definition);
            }
        }
        result.sort((a, b) -> a.id().compareTo(b.id()));
        return result;
    }

    public List<QuestDefinition> all() {
        List<QuestDefinition> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> a.id().compareTo(b.id()));
        return result;
    }

    public Map<String, String> problems() {
        return new LinkedHashMap<>(problemsByFile);
    }

    public int count() {
        return merged.size();
    }

    // ------------------------------------------------------------------ reload

    /** Attaches the running server so the world folder layer becomes readable. */
    public void attachServer(MinecraftServer attachedServer) {
        this.server = attachedServer;
    }

    /** Full reload: data pack layer from the resource manager, then the disk layers. */
    public synchronized ReloadReport reloadFromResources(ResourceManager manager, MinecraftServer attachedServer) {
        if (attachedServer != null) {
            this.server = attachedServer;
        }
        problemsByFile.clear();
        Map<String, QuestDefinition> fresh = new LinkedHashMap<>();
        Map<Identifier, Resource> found = manager.listResources(RESOURCE_DIRECTORY,
                identifier -> identifier.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
            String fileName = entry.getKey().toString();
            try {
                JsonObject root = JsonParser.parseString(entry.getValue().readAllAsString()).getAsJsonObject();
                List<String> errors = new ArrayList<>();
                QuestDefinition parsed = QuestDefinition.parse(root, sourceOf(entry.getValue()), fileName, errors);
                if (parsed == null) {
                    problemsByFile.put(fileName, String.join("; ", errors));
                    Trakt.LOGGER.warn("[quests] damaged quest resource {}: {}", fileName, String.join("; ", errors));
                    continue;
                }
                validateRegistryReferences(parsed, errors);
                if (!errors.isEmpty()) {
                    problemsByFile.put(fileName, String.join("; ", errors));
                    continue;
                }
                fresh.put(parsed.id(), parsed);
            } catch (Exception exception) {
                problemsByFile.put(fileName, String.valueOf(exception.getMessage()));
                Trakt.LOGGER.warn("[quests] failed to read quest resource {}", fileName, exception);
            }
        }
        resourceLayer.clear();
        resourceLayer.putAll(fresh);
        return rebuild();
    }

    /** Disk-only reload (used by {@code /trakt quests reload}); the data pack layer keeps its cache. */
    public synchronized ReloadReport reloadFromDisk() {
        problemsByFile.clear();
        resourceLayer.keySet().forEach(id -> problemsByFile.remove(id));
        return rebuild();
    }

    private QuestDefinition.Source sourceOf(Resource resource) {
        String packId = resource.sourcePackId() == null ? "" : resource.sourcePackId();
        if (packId.startsWith("mod:") || packId.contains("trakt")) {
            return QuestDefinition.Source.BUILTIN;
        }
        return QuestDefinition.Source.DATAPACK;
    }

    /** Rebuilds the merged view from all layers in ascending priority order. */
    private ReloadReport rebuild() {
        Map<String, QuestDefinition> result = new LinkedHashMap<>();
        int disabled = 0;
        // 1. built-in pack entries from the resource layer
        for (QuestDefinition definition : resourceLayer.values()) {
            if (definition.source() == QuestDefinition.Source.BUILTIN) {
                result.put(definition.id(), definition);
            }
        }
        // 2. legacy panel config
        putAll(result, readDirectory(apiConfigDir(), QuestDefinition.Source.API));
        // 3. world folder
        putAll(result, readDirectory(worldQuestsDir(), QuestDefinition.Source.WORLD));
        // 4. data pack overrides from the resource layer
        for (QuestDefinition definition : resourceLayer.values()) {
            if (definition.source() != QuestDefinition.Source.BUILTIN) {
                result.put(definition.id(), definition);
            }
        }

        Map<String, QuestDefinition> enabled = new LinkedHashMap<>();
        for (Map.Entry<String, QuestDefinition> entry : result.entrySet()) {
            if (entry.getValue().enabled()) {
                enabled.put(entry.getKey(), entry.getValue());
            } else {
                disabled++;
                Trakt.LOGGER.info("[quests] '{}' disabled by override (source {})",
                        entry.getKey(), entry.getValue().source().id());
            }
        }
        merged.clear();
        merged.putAll(enabled);
        Trakt.LOGGER.info("[quests] registry rebuilt: {} active definition(s)", merged.size());
        return report(disabled);
    }

    private static void putAll(Map<String, QuestDefinition> target, List<QuestDefinition> layer) {
        for (QuestDefinition definition : layer) {
            target.put(definition.id(), definition);
        }
    }

    // ------------------------------------------------------------------ disk layers

    private Path worldQuestsDir() {
        if (server == null) {
            return null;
        }
        LevelStorageSource.LevelStorageAccess access;
        try {
            access = ((MinecraftServerStorageAccessor) server).trakt$storageSource();
        } catch (ClassCastException exception) {
            return null;
        }
        if (access == null) {
            return null;
        }
        return access.getLevelPath(new LevelResource("trakt/quests"));
    }

    private Path apiConfigDir() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve(Trakt.MOD_ID).resolve("api").resolve("quests");
        } catch (RuntimeException exception) {
            // unit tests run without a Fabric loader; the panel layer is simply skipped
            return null;
        }
    }

    private List<QuestDefinition> readDirectory(Path dir, QuestDefinition.Source source) {
        List<QuestDefinition> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.sorted().filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String fileName = dir.relativize(file).toString();
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    List<String> errors = new ArrayList<>();
                    QuestDefinition parsed = QuestDefinition.parse(root, source, fileName, errors);
                    if (parsed == null) {
                        problemsByFile.put(fileName, String.join("; ", errors));
                        Trakt.LOGGER.warn("[quests] damaged quest file {}: {}", fileName, String.join("; ", errors));
                        continue;
                    }
                    validateRegistryReferences(parsed, errors);
                    if (!errors.isEmpty()) {
                        problemsByFile.put(fileName, String.join("; ", errors));
                        continue;
                    }
                    result.add(parsed);
                } catch (Exception exception) {
                    problemsByFile.put(fileName, String.valueOf(exception.getMessage()));
                    Trakt.LOGGER.warn("[quests] failed to read quest file {}", file, exception);
                }
            }
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[quests] failed to list quest folder {}", dir, exception);
        }
        return result;
    }

    // ------------------------------------------------------------------ validation

    private void validateRegistryReferences(QuestDefinition definition, List<String> errors) {
        for (QuestGoal goal : definition.goals()) {
            validateReference(goal.item(), "goal.item", errors);
            validateReference(goal.block(), "goal.block", errors);
            validateReference(goal.entity(), "goal.entity", errors);
        }
        for (QuestDefinition.TurnInItem item : definition.turnInItems()) {
            if (item.item().startsWith("#")) {
                continue; // tag bundles resolve lazily in the adapter (F2b)
            }
            if (!isKnownItem(item.item())) {
                errors.add("turn_in item '" + item.item() + "' is not a known item");
            }
        }
        // Flat item references across the reward list, including choice_of options.
        for (QuestRewards.DirectView.ItemRef item : QuestRewards.allItems(definition.rewardsList())) {
            if (!isKnownItem(item.item())) {
                errors.add("rewards item '" + item.item() + "' is not a known item");
            }
        }
    }

    private void validateReference(String raw, String field, List<String> errors) {
        if (raw == null || raw.isBlank() || raw.startsWith("#")) {
            return; // tag references are resolved lazily by the adapter
        }
        if (field.endsWith("item") && !isKnownItem(raw)) {
            errors.add(field + " '" + raw + "' is not a known item");
        } else if (field.endsWith("block") && !isKnownBlock(raw)) {
            errors.add(field + " '" + raw + "' is not a known block");
        } else if (field.endsWith("entity") && !isKnownEntity(raw)) {
            errors.add(field + " '" + raw + "' is not a known entity type");
        }
    }

    private static boolean isKnownItem(String raw) {
        try {
            return BuiltInRegistries.ITEM.containsKey(Identifier.parse(raw));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isKnownBlock(String raw) {
        try {
            return BuiltInRegistries.BLOCK.containsKey(Identifier.parse(raw));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isKnownEntity(String raw) {
        try {
            return BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(raw));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // ------------------------------------------------------------------ report & event

    private ReloadReport report(int disabled) {
        int builtin = 0, datapack = 0, world = 0, api = 0;
        for (QuestDefinition definition : merged.values()) {
            switch (definition.source()) {
                case BUILTIN -> builtin++;
                case DATAPACK -> datapack++;
                case WORLD -> world++;
                case API -> api++;
            }
        }
        return new ReloadReport(merged.size(), builtin, datapack, world, api, disabled,
                new LinkedHashMap<>(problemsByFile));
    }

    /** Emits the panel event after a reload changed the active set. */
    public void emitChangeEvent() {
        if (server == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("total", merged.size());
        List<String> ids = new ArrayList<>(merged.keySet());
        ids.sort(String::compareTo);
        payload.addProperty("ids", String.join(",", ids));
        ApiService.emitEvent(server, "quest_definition_changed", null, null, payload);
    }

    // ------------------------------------------------------------------ world writes (panel, D9)

    /** Saves a definition into the world folder layer. */
    public synchronized void saveToWorld(QuestDefinition definition) throws IOException {
        Path dir = worldQuestsDir();
        if (dir == null) {
            throw new IOException("level storage unavailable");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(definition.id() + ".json");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, definition.toJson().toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Deletes a definition file from the world folder layer. */
    public synchronized boolean deleteFromWorld(String id) {
        Path dir = worldQuestsDir();
        if (dir == null || id == null || id.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[quests] failed to delete quest file for {}", id, exception);
            return false;
        }
    }

    /**
     * Test hook: loads the built-in pack from the source tree (tests run from the project
     * root and have no resource manager). Production always goes through
     * {@link #reloadFromResources}.
     */
    public synchronized void loadBuiltinDirectoryForTests(Path dir) {
        resourceLayer.clear();
        for (QuestDefinition definition : readDirectory(dir, QuestDefinition.Source.BUILTIN)) {
            resourceLayer.put(definition.id(), definition);
        }
        rebuild();
    }

    // ------------------------------------------------------------------ misc

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
