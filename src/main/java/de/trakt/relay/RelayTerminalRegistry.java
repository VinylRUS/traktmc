package de.trakt.relay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-global registry of placed relay terminals (F3b). One record per placed
 * relay_post block: owner identity plus the last known display name, so offline
 * players can still be addressed as relay recipients by name.
 */
public final class RelayTerminalRegistry extends SavedData {
    static final int MAX_PER_PLAYER = 2;
    private static final String ID = "trakt_relay_terminals";

    public static final SavedDataType<RelayTerminalRegistry> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace(ID),
            RelayTerminalRegistry::new,
            CompoundTag.CODEC.xmap(RelayTerminalRegistry::fromNbt, RelayTerminalRegistry::toNbt),
            DataFixTypes.LEVEL);

    public record Terminal(BlockPos pos, String dimension, UUID owner, String ownerName, int placedDay) {}

    private final Map<String, Terminal> terminals = new LinkedHashMap<>();

    RelayTerminalRegistry() {}

    public static RelayTerminalRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int size() {
        return terminals.size();
    }

    /** Registers (or re-registers after a same-spot replacement) a terminal for the placer. */
    public boolean register(ServerLevel world, ServerPlayer player, BlockPos pos, int day) {
        UUID owner = player.getUUID();
        long owned = terminals.values().stream().filter(t -> t.owner().equals(owner)).count();
        if (owned >= MAX_PER_PLAYER && at(RelayKeys.dimensionKey(world), pos).isEmpty()) {
            return false;
        }
        terminals.put(RelayKeys.terminalKey(RelayKeys.dimensionKey(world), pos),
                new Terminal(pos.immutable(), RelayKeys.dimensionKey(world), owner,
                        player.getGameProfile().name(), day));
        setDirty();
        return true;
    }

    public void unregister(String dimension, BlockPos pos) {
        if (terminals.remove(RelayKeys.terminalKey(dimension, pos)) != null) {
            setDirty();
        }
    }

    public Optional<Terminal> at(String dimension, BlockPos pos) {
        return Optional.ofNullable(terminals.get(RelayKeys.terminalKey(dimension, pos)));
    }

    public List<Terminal> ownedBy(UUID owner) {
        List<Terminal> result = new ArrayList<>();
        for (Terminal terminal : terminals.values()) {
            if (terminal.owner().equals(owner)) {
                result.add(terminal);
            }
        }
        return List.copyOf(result);
    }

    /** Case-insensitive owner lookup used as the offline fallback for send targeting. */
    public Optional<Terminal> resolveOwner(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (Terminal terminal : terminals.values()) {
            if (terminal.ownerName() != null && terminal.ownerName().equalsIgnoreCase(name)) {
                return Optional.of(terminal);
            }
        }
        return Optional.empty();
    }

    /** Terminals of one dimension within the given horizontal radius of a position. */
    public List<Terminal> nearby(String dimension, BlockPos pos, int radius) {
        long radiusSqr = (long) radius * radius;
        List<Terminal> result = new ArrayList<>();
        for (Terminal terminal : terminals.values()) {
            if (terminal.dimension().equals(dimension)
                    && RelayKeys.horizontalDistanceSqr(terminal.pos(), pos) <= radiusSqr) {
                result.add(terminal);
            }
        }
        return List.copyOf(result);
    }

    static CompoundTag toNbt(RelayTerminalRegistry state) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Terminal terminal : state.terminals.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", terminal.pos().getX());
            entry.putInt("y", terminal.pos().getY());
            entry.putInt("z", terminal.pos().getZ());
            entry.putString("dim", terminal.dimension());
            entry.putString("owner", terminal.owner().toString());
            entry.putString("owner_name", terminal.ownerName());
            entry.putInt("placed_day", terminal.placedDay());
            list.add(entry);
        }
        tag.put("terminals", list);
        tag.putInt("schema", 1);
        return tag;
    }

    static RelayTerminalRegistry fromNbt(CompoundTag tag) {
        RelayTerminalRegistry state = new RelayTerminalRegistry();
        ListTag list = tag.getListOrEmpty("terminals");
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompoundOrEmpty(index);
            UUID owner;
            try {
                owner = UUID.fromString(entry.getStringOr("owner", ""));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            BlockPos pos = new BlockPos(
                    entry.getIntOr("x", 0),
                    entry.getIntOr("y", 0),
                    entry.getIntOr("z", 0));
            String dim = entry.getStringOr("dim", "");
            if (dim.isBlank()) {
                continue;
            }
            state.terminals.put(RelayKeys.terminalKey(dim, pos),
                    new Terminal(pos, dim, owner,
                            entry.getStringOr("owner_name", ""), entry.getIntOr("placed_day", 0)));
        }
        return state;
    }
}
