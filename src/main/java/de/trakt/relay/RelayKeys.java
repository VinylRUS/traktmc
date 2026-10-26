package de.trakt.relay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Shared key/geometry helpers for the relay subsystem (F3b). */
public final class RelayKeys {
    static final String OVERWORLD = "minecraft:overworld";

    private RelayKeys() {}

    public static String dimensionKey(ServerLevel world) {
        return world == null ? OVERWORLD : world.dimension().identifier().toString();
    }

    /** Stable identity of a terminal slot: dimension plus the packed block position. */
    public static String terminalKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.asLong();
    }

    /** Horizontal-only distance: relay legs never care about height differences. */
    public static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return Long.MAX_VALUE;
        }
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
