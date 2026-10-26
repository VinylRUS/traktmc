package de.trakt.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F3b: terminal registry — ownership caps, name resolution, radius queries, save/load. */
final class RelayTerminalRegistryTest {
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));

    private ServerLevel world;
    private ServerPlayer player;
    private final UUID playerId = UUID.randomUUID();
    private RelayTerminalRegistry registry;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUpMocks() {
        world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(OVERWORLD);
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Traktir"));
        registry = new RelayTerminalRegistry();
    }

    @Test
    void capsTerminalsPerPlayerAtTwo() {
        assertTrue(registry.register(world, player, new BlockPos(0, 64, 0), 1));
        assertTrue(registry.register(world, player, new BlockPos(100, 64, 0), 2));
        assertFalse(registry.register(world, player, new BlockPos(200, 64, 0), 3));
        assertEquals(2, registry.ownedBy(playerId).size());
    }

    @Test
    void anotherPlayerIsNotAffectedByTheCap() {
        assertTrue(registry.register(world, player, new BlockPos(0, 64, 0), 1));
        assertTrue(registry.register(world, player, new BlockPos(100, 64, 0), 2));
        ServerPlayer other = mock(ServerPlayer.class);
        UUID otherId = UUID.randomUUID();
        when(other.getUUID()).thenReturn(otherId);
        when(other.getGameProfile()).thenReturn(new GameProfile(otherId, "Putnik"));
        assertTrue(registry.register(world, other, new BlockPos(200, 64, 0), 3));
        assertTrue(registry.register(world, other, new BlockPos(300, 64, 0), 4));
        assertFalse(registry.register(world, other, new BlockPos(400, 64, 0), 5));
        assertEquals(2, registry.ownedBy(otherId).size());
    }

    @Test
    void unregisterFreesTheSlotForRebinding() {
        BlockPos pos = new BlockPos(10, 64, 10);
        assertTrue(registry.register(world, player, pos, 1));
        assertTrue(registry.register(world, player, new BlockPos(20, 64, 20), 2));
        registry.unregister("minecraft:overworld", pos);
        assertTrue(registry.at("minecraft:overworld", pos).isEmpty());
        assertTrue(registry.register(world, player, pos, 3));
        assertEquals(2, registry.ownedBy(playerId).size());
    }

    @Test
    void resolvesOwnersByNameCaseInsensitively() {
        assertTrue(registry.register(world, player, new BlockPos(0, 64, 0), 1));
        var resolved = registry.resolveOwner("traktir");
        assertTrue(resolved.isPresent());
        assertEquals(playerId, resolved.get().owner());
        assertTrue(registry.resolveOwner("Neznakomets").isEmpty());
    }

    @Test
    void nearbyQueryIsScopedByDimensionAndRadius() {
        assertTrue(registry.register(world, player, new BlockPos(0, 64, 0), 1));
        assertEquals(1, registry.nearby("minecraft:overworld", new BlockPos(4, 70, 4), 8).size());
        assertEquals(0, registry.nearby("minecraft:overworld", new BlockPos(9, 64, 0), 8).size());
        assertEquals(0, registry.nearby("minecraft:the_nether", new BlockPos(0, 64, 0), 8).size());
    }

    @Test
    void registrySurvivesTheSaveLoadCycle() {
        assertTrue(registry.register(world, player, new BlockPos(-5, 71, 12), 3));
        ServerPlayer other = mock(ServerPlayer.class);
        UUID otherId = UUID.randomUUID();
        when(other.getUUID()).thenReturn(otherId);
        when(other.getGameProfile()).thenReturn(new GameProfile(otherId, "Putnik"));
        assertTrue(registry.register(world, other, new BlockPos(64, 64, -32), 4));

        CompoundTag saved = RelayTerminalRegistry.toNbt(registry);
        RelayTerminalRegistry restored = RelayTerminalRegistry.fromNbt(saved);
        assertEquals(2, restored.size());
        var atFirst = restored.at("minecraft:overworld", new BlockPos(-5, 71, 12)).orElseThrow();
        assertEquals(playerId, atFirst.owner());
        assertEquals("Traktir", atFirst.ownerName());
        assertEquals(3, atFirst.placedDay());
        assertEquals(1, restored.ownedBy(otherId).size());
        assertEquals(otherId, restored.resolveOwner("putnik").orElseThrow().owner());
    }
}
