package de.trakt.questmaster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * F6a regression: the settlement state survives save/load — bindings round-trip
 * through NBT, invalid entries are skipped, and the caps hold.
 */
final class QuestMasterSettlementStateTest {

    @Test
    void bindingsRoundTripThroughNbt() {
        QuestMasterSettlementState state = new QuestMasterSettlementState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.put(QuestMasterSettlementState.VillageKey.overworld(100, -200), first,
                new BlockPos(96, 64, -195));
        state.put(new QuestMasterSettlementState.VillageKey("minecraft:overworld", 0, 0), second,
                new BlockPos(1, 70, 2));
        assertEquals(2, state.size());

        CompoundTag saved = QuestMasterSettlementState.toNbt(state);
        QuestMasterSettlementState restored = QuestMasterSettlementState.fromNbt(saved);
        assertEquals(2, restored.size());
        assertEquals(1, restored.schemaVersion());
        assertEquals(first, restored.binding(QuestMasterSettlementState.VillageKey.overworld(100, -200))
                .orElseThrow().entityUuid());
        assertEquals(new BlockPos(96, 64, -195),
                restored.binding(QuestMasterSettlementState.VillageKey.overworld(100, -200)).orElseThrow().settlePos());
        assertEquals(second, restored.binding(new QuestMasterSettlementState.VillageKey("minecraft:overworld", 0, 0))
                .orElseThrow().entityUuid());
    }

    @Test
    void clearedBindingsStayCleared() {
        QuestMasterSettlementState state = new QuestMasterSettlementState();
        QuestMasterSettlementState.VillageKey key = QuestMasterSettlementState.VillageKey.overworld(10, 10);
        state.put(key, UUID.randomUUID(), new BlockPos(10, 64, 10));
        state.clear(key);
        assertTrue(state.binding(key).isEmpty());
        // clear on an absent key is a no-op, not a failure
        state.clear(key);
        assertEquals(0, state.size());
    }

    @Test
    void invalidNbtEntriesAreSkipped() {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", 1);
        net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
        CompoundTag valid = new CompoundTag();
        valid.putString("dimension", "minecraft:overworld");
        valid.putInt("villageX", 5);
        valid.putInt("villageZ", 7);
        valid.putString("entity", UUID.randomUUID().toString());
        valid.putInt("x", 5);
        valid.putInt("y", 64);
        valid.putInt("z", 7);
        entries.add(valid);
        CompoundTag badUuid = new CompoundTag();
        badUuid.putString("dimension", "minecraft:overworld");
        badUuid.putInt("villageX", 9);
        badUuid.putInt("villageZ", 9);
        badUuid.putString("entity", "not-a-uuid");
        badUuid.putInt("x", 9);
        badUuid.putInt("y", 64);
        badUuid.putInt("z", 9);
        entries.add(badUuid);
        CompoundTag badDimension = new CompoundTag();
        badDimension.putString("dimension", "not a dimension!");
        badDimension.putInt("villageX", 11);
        badDimension.putInt("villageZ", 11);
        badDimension.putString("entity", UUID.randomUUID().toString());
        entries.add(badDimension);
        root.put("settlements", entries);

        QuestMasterSettlementState restored = QuestMasterSettlementState.fromNbt(root);
        assertEquals(1, restored.size());
        assertTrue(restored.binding(QuestMasterSettlementState.VillageKey.overworld(5, 7)).isPresent());
        assertTrue(restored.binding(QuestMasterSettlementState.VillageKey.overworld(9, 9)).isEmpty());
        assertTrue(restored.binding(QuestMasterSettlementState.VillageKey.overworld(11, 11)).isEmpty());
    }

    @Test
    void dimensionKeyValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuestMasterSettlementState.VillageKey("invalid dimension", 0, 0));
        assertEquals("minecraft:overworld", QuestMasterSettlementState.dimensionKey(null));
    }

    /** F6c: invited questmasters survive save/load next to the settled bindings. */
    @Test
    void invitedQuestmastersRoundTripThroughNbt() {
        QuestMasterSettlementState state = new QuestMasterSettlementState();
        UUID entity = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        state.recordInvite(entity, host, "Traktir", new BlockPos(12, 64, -8));
        state.recordInvite(UUID.randomUUID(), UUID.randomUUID(), "Ghost", new BlockPos(0, 64, 0));

        CompoundTag saved = QuestMasterSettlementState.toNbt(state);
        QuestMasterSettlementState restored = QuestMasterSettlementState.fromNbt(saved);
        assertEquals(2, restored.invitedView().size());
        QuestMasterSettlementState.Invite invite = restored.invitedView().get(entity);
        assertEquals(host, invite.playerUuid());
        assertEquals("Traktir", invite.playerName());
        assertEquals(new BlockPos(12, 64, -8), invite.yardPos());

        restored.clearInvite(entity);
        assertEquals(1, restored.invitedView().size());
        restored.clearAll();
        assertTrue(restored.invitedView().isEmpty());
        assertTrue(restored.settlementsView().isEmpty());
    }

    /** F6c: malformed invite entries are skipped without dropping the healthy ones. */
    @Test
    void malformedInvitesAreSkippedOnLoad() {
        QuestMasterSettlementState state = new QuestMasterSettlementState();
        UUID entity = UUID.randomUUID();
        state.recordInvite(entity, UUID.randomUUID(), "Traktir", new BlockPos(1, 2, 3));
        state.recordInvite(null, UUID.randomUUID(), "Broken", new BlockPos(0, 0, 0));

        CompoundTag saved = QuestMasterSettlementState.toNbt(state);
        QuestMasterSettlementState restored = QuestMasterSettlementState.fromNbt(saved);
        assertEquals(1, restored.invitedView().size());
        assertTrue(restored.invitedView().containsKey(entity));
    }
}
