package de.trakt.questmaster;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-global bindings between villages and their settled questmasters (F6a).
 * One record per village: the entity UUID and the position it was spawned at, so
 * the settlement service can verify the entity after chunk reloads and respawn it
 * when it died. Stores no player data — per-player progress lives in PlayerQuestData.
 */
public final class QuestMasterSettlementState extends SavedData {

    static final int CURRENT_SCHEMA_VERSION = 1;
    static final int MAX_SETTLEMENTS = 4096;
    private static final String ID = "trakt_questmaster_settlements";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final Pattern DIMENSION_PATTERN = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");

    public static final SavedDataType<QuestMasterSettlementState> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace(ID), QuestMasterSettlementState::new,
            CompoundTag.CODEC.xmap(QuestMasterSettlementState::fromNbt, QuestMasterSettlementState::toNbt),
            DataFixTypes.LEVEL);

    private final Map<VillageKey, Binding> settlements = new LinkedHashMap<>();
    private final Map<UUID, Invite> invited = new LinkedHashMap<>();
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** F6c: a questmaster relocated to a player's yard by invitation. */
    public record Invite(UUID playerUuid, String playerName, BlockPos yardPos) {}

    QuestMasterSettlementState() {}

    public static QuestMasterSettlementState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static String dimensionKey(ServerLevel world) {
        return world == null ? OVERWORLD : world.dimension().identifier().toString();
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int size() {
        return settlements.size();
    }

    public Map<VillageKey, Binding> settlementsView() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(settlements));
    }

    /** The binding for a village, or empty when the village has no settled questmaster. */
    public Optional<Binding> binding(VillageKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(settlements.get(key));
    }

    /** Records (or replaces) the binding for a village. */
    public void put(VillageKey key, UUID entityUuid, BlockPos settlePos) {
        if (key == null || entityUuid == null || settlePos == null) {
            return;
        }
        settlements.put(key, new Binding(entityUuid, settlePos.immutable()));
        setDirty();
    }

    /** Drops the binding — the village gets a fresh questmaster on its next visit. */
    public void clear(VillageKey key) {
        if (key != null && settlements.remove(key) != null) {
            setDirty();
        }
    }

    /** F6c: records a questmaster invited to a player's yard (replaces an older invite). */
    public void recordInvite(UUID entityUuid, UUID playerUuid, String playerName, BlockPos yardPos) {
        if (entityUuid == null || playerUuid == null || yardPos == null) {
            return;
        }
        invited.put(entityUuid, new Invite(playerUuid,
                playerName == null ? "" : playerName, yardPos.immutable()));
        setDirty();
    }

    /** F6c: invited questmasters by entity uuid. */
    public Map<UUID, Invite> invitedView() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(invited));
    }

    /** F6c: drops one invited record (entity gone for good). */
    public void clearInvite(UUID entityUuid) {
        if (entityUuid != null && invited.remove(entityUuid) != null) {
            setDirty();
        }
    }

    /** Wipes every binding (admin reset path). */
    public void clearAll() {
        boolean changed = !settlements.isEmpty();
        settlements.clear();
        changed |= !invited.isEmpty();
        invited.clear();
        if (changed) {
            setDirty();
        }
    }

    static QuestMasterSettlementState fromNbt(CompoundTag root) {
        QuestMasterSettlementState state = new QuestMasterSettlementState();
        if (root == null || root.isEmpty()) return state;
        int sourceVersion = Math.max(0, root.getIntOr("schemaVersion", 0));
        if (sourceVersion > CURRENT_SCHEMA_VERSION) return state;
        ListTag entries = root.getListOrEmpty("settlements");
        for (int index = 0; index < entries.size() && state.settlements.size() < MAX_SETTLEMENTS; index++) {
            CompoundTag entry = entries.getCompoundOrEmpty(index);
            VillageKey key = parseKey(entry);
            if (key == null || state.settlements.containsKey(key)) continue;
            String encodedUuid = entry.getStringOr("entity", "");
            UUID entityUuid;
            try {
                entityUuid = UUID.fromString(encodedUuid);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            BlockPos settlePos = new BlockPos(
                    entry.getIntOr("x", 0),
                    entry.getIntOr("y", 0),
                    entry.getIntOr("z", 0));
            state.settlements.put(key, new Binding(entityUuid, settlePos));
        }
        ListTag invites = root.getListOrEmpty("invited");
        for (int index = 0; index < invites.size() && state.invited.size() < MAX_SETTLEMENTS; index++) {
            CompoundTag entry = invites.getCompoundOrEmpty(index);
            UUID entityUuid = parseUuid(entry.getStringOr("entity", ""));
            UUID playerUuid = parseUuid(entry.getStringOr("player", ""));
            if (entityUuid == null || playerUuid == null) continue;
            BlockPos yardPos = new BlockPos(
                    entry.getIntOr("x", 0),
                    entry.getIntOr("y", 0),
                    entry.getIntOr("z", 0));
            state.invited.put(entityUuid, new Invite(playerUuid,
                    entry.getStringOr("player_name", ""), yardPos));
        }
        state.schemaVersion = CURRENT_SCHEMA_VERSION;
        if (sourceVersion != CURRENT_SCHEMA_VERSION) state.setDirty();
        return state;
    }

    static CompoundTag toNbt(QuestMasterSettlementState state) {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", CURRENT_SCHEMA_VERSION);
        ListTag entries = new ListTag();
        state.settlements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(mapEntry -> {
                    VillageKey key = mapEntry.getKey();
                    Binding binding = mapEntry.getValue();
                    CompoundTag entry = new CompoundTag();
                    entry.putString("dimension", key.dimension());
                    entry.putInt("villageX", key.anchorX());
                    entry.putInt("villageZ", key.anchorZ());
                    entry.putString("entity", binding.entityUuid().toString());
                    entry.putInt("x", binding.settlePos().getX());
                    entry.putInt("y", binding.settlePos().getY());
                    entry.putInt("z", binding.settlePos().getZ());
                    entries.add(entry);
                });
        root.put("settlements", entries);
        ListTag invites = new ListTag();
        state.invited.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(mapEntry -> {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("entity", mapEntry.getKey().toString());
                    Invite invite = mapEntry.getValue();
                    entry.putString("player", invite.playerUuid().toString());
                    entry.putString("player_name", invite.playerName());
                    entry.putInt("x", invite.yardPos().getX());
                    entry.putInt("y", invite.yardPos().getY());
                    entry.putInt("z", invite.yardPos().getZ());
                    invites.add(entry);
                });
        root.put("invited", invites);
        return root;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static VillageKey parseKey(CompoundTag entry) {
        String dimension = entry.getStringOr("dimension", "");
        if (dimension == null || dimension.length() > 128
                || !DIMENSION_PATTERN.matcher(dimension).matches()) {
            return null;
        }
        return new VillageKey(dimension,
                entry.getIntOr("villageX", 0), entry.getIntOr("villageZ", 0));
    }

    /** Village identity: dimension plus the structure centre the visit pipeline reports. */
    public record VillageKey(String dimension, int anchorX, int anchorZ) implements Comparable<VillageKey> {
        public VillageKey {
            if (dimension == null || dimension.length() > 128
                    || !DIMENSION_PATTERN.matcher(dimension).matches()) {
                throw new IllegalArgumentException("Invalid dimension key");
            }
        }

        public static VillageKey overworld(int anchorX, int anchorZ) {
            return new VillageKey(OVERWORLD, anchorX, anchorZ);
        }

        @Override
        public int compareTo(VillageKey other) {
            int dimensionOrder = dimension.compareTo(other.dimension);
            if (dimensionOrder != 0) return dimensionOrder;
            int xOrder = Integer.compare(anchorX, other.anchorX);
            return xOrder != 0 ? xOrder : Integer.compare(anchorZ, other.anchorZ);
        }
    }

    /** One settled questmaster: its entity UUID and the position it occupies. */
    public record Binding(UUID entityUuid, BlockPos settlePos) {}
}
