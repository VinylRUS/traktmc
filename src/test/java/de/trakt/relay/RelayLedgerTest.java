package de.trakt.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F3b: ledger invariants — full ItemStack escrow round-trip, draft slot caps,
 * the state machine (depart → hold → timeout/terminal/route bounce) and quotas.
 */
final class RelayLedgerTest {
    private static final BlockPos FROM = new BlockPos(0, 64, 0);
    private static final BlockPos TO = new BlockPos(500, 64, 500);

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private RelayLedger ledger;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void bindComponents() {
        var stackable = net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64).build();
        Items.IRON_INGOT.builtInRegistryHolder().bindComponents(stackable);
        Items.GOLD_INGOT.builtInRegistryHolder().bindComponents(stackable);
        Items.DIAMOND.builtInRegistryHolder().bindComponents(stackable);
        Items.EMERALD.builtInRegistryHolder().bindComponents(stackable);
        ledger = new RelayLedger();
    }

    private RelayShipment draftWithItems(ItemStack... stacks) {
        RelayShipment draft = new RelayShipment(UUID.randomUUID(), sender, "Sender",
                "minecraft:overworld", FROM);
        List<ItemStack> slots = new java.util.ArrayList<>();
        for (ItemStack stack : stacks) {
            RelayItems.insert(slots, stack, RelayLedger.MAX_STACKS_PER_SHIPMENT);
        }
        draft.updateItems(RelayItems.encode(VanillaRegistries.createWorldLookup(), slots));
        ledger.addDraft(draft);
        return draft;
    }

    @Test
    void itemComponentsSurviveTheEscrowRoundTrip() {
        var registries = VanillaRegistries.createWorldLookup();
        ItemStack named = new ItemStack(Items.IRON_INGOT, 17);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Партия из Гильдии"));
        ListTag encoded = RelayItems.encode(registries, List.of(named));
        List<ItemStack> decoded = RelayItems.decode(registries, encoded);
        assertEquals(1, decoded.size());
        assertTrue(ItemStack.isSameItemSameComponents(named, decoded.getFirst()));
        assertEquals(17, decoded.getFirst().getCount());
        assertEquals(named.get(DataComponents.CUSTOM_NAME), decoded.getFirst().get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void draftSlotsMergeCompatibleStacksAndCapAtThree() {
        List<ItemStack> slots = new java.util.ArrayList<>();
        RelayItems.insert(slots, new ItemStack(Items.IRON_INGOT, 32), 3);
        RelayItems.insert(slots, new ItemStack(Items.IRON_INGOT, 32), 3);
        assertEquals(1, slots.size());
        assertEquals(64, slots.getFirst().getCount());
        RelayItems.insert(slots, new ItemStack(Items.GOLD_INGOT, 5), 3);
        RelayItems.insert(slots, new ItemStack(Items.DIAMOND, 1), 3);
        assertEquals(3, slots.size());
        assertFalse(RelayItems.canInsert(slots, new ItemStack(Items.EMERALD, 1), 3));
        assertTrue(RelayItems.canInsert(slots, new ItemStack(Items.GOLD_INGOT, 3), 3));
    }

    @Test
    void departLocksTheOutboundQuotaSlot() {
        RelayShipment draft = draftWithItems(new ItemStack(Items.IRON_INGOT, 64));
        draft.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, true, 10, 600);
        assertEquals(1, ledger.activeFor(sender));
        assertEquals(RelayStage.IN_TRANSIT, draft.stage());
        assertEquals(1, ledger.travellingOn(sender, 0).size());
        assertTrue(ledger.draftFor(sender).isEmpty());
    }

    @Test
    void arrivalMatcherRequiresTheConfiguredRouteEndNearTheTerminal() {
        RelayShipment draft = draftWithItems(new ItemStack(Items.IRON_INGOT, 8));
        draft.depart(recipient, "Recipient", "minecraft:overworld", TO, 2, true, 10, 600);
        assertFalse(RelayService.arrivesAt(draft, false, TO));
        assertFalse(RelayService.arrivesAt(draft, true,
                new BlockPos(TO.getX() + RelayService.ARRIVAL_TOLERANCE + 1, 64, TO.getZ())));
        assertTrue(RelayService.arrivesAt(draft, true,
                new BlockPos(TO.getX() + RelayService.ARRIVAL_TOLERANCE, 64, TO.getZ())));
        assertTrue(RelayService.arrivesAt(draft, true, TO));
    }

    @Test
    void unclaimedCargoTimesOutAndReturns() {
        RelayShipment draft = draftWithItems(new ItemStack(Items.GOLD_INGOT, 3));
        draft.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, true, 10, 600);
        draft.changeStage(RelayStage.HELD_AT_DESTINATION, 12, null);
        List<RelayShipment> changed = ledger.sweepTimeouts(12 + RelayLedger.HELD_TIMEOUT_DAYS);
        assertEquals(1, changed.size());
        assertEquals(RelayStage.RETURN_IN_TRANSIT, draft.stage());
        assertEquals("timeout", draft.returnReason());
    }

    @Test
    void stuckTransitMaterializesAtTheSourceAfterAWeek() {
        RelayShipment draft = draftWithItems(new ItemStack(Items.GOLD_INGOT, 3));
        draft.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, true, 10, 600);
        List<RelayShipment> changed = ledger.sweepTimeouts(10 + RelayLedger.TRANSIT_TIMEOUT_DAYS - 1);
        assertTrue(changed.isEmpty());
        changed = ledger.sweepTimeouts(10 + RelayLedger.TRANSIT_TIMEOUT_DAYS);
        assertEquals(1, changed.size());
        assertEquals(RelayStage.HELD_AT_SOURCE, draft.stage());
    }

    @Test
    void destroyedTerminalBouncesHeldAndFlyingCargo() {
        RelayShipment held = draftWithItems(new ItemStack(Items.EMERALD, 12));
        held.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, true, 10, 600);
        held.changeStage(RelayStage.HELD_AT_DESTINATION, 10, null);
        RelayShipment flying = draftWithItems(new ItemStack(Items.DIAMOND, 2));
        flying.depart(recipient, "Recipient", "minecraft:overworld", TO, 1, true, 11, 600);
        List<RelayShipment> changed = ledger.onTerminalRemoved("minecraft:overworld", TO, 12);
        assertEquals(2, changed.size());
        assertEquals(RelayStage.RETURN_IN_TRANSIT, held.stage());
        assertEquals(RelayStage.RETURN_IN_TRANSIT, flying.stage());
        assertEquals("terminal_destroyed", held.returnReason());
    }

    @Test
    void removedRouteDumpsItsCargoAtTheSourceTerminal() {
        RelayShipment cargo = draftWithItems(new ItemStack(Items.EMERALD, 5));
        cargo.depart(recipient, "Recipient", "minecraft:overworld", TO, 3, false, 10, 600);
        List<RelayShipment> changed = ledger.onRouteRemoved(sender, 3, 10);
        assertEquals(1, changed.size());
        assertEquals(RelayStage.HELD_AT_SOURCE, cargo.stage());
        assertEquals("route_removed", cargo.returnReason());
        assertEquals(0, ledger.activeFor(sender));
    }

    @Test
    void heldQueriesAreScopedByRecipientAndTerminalPosition() {
        RelayShipment mine = draftWithItems(new ItemStack(Items.IRON_INGOT, 1));
        mine.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, true, 5, 600);
        mine.changeStage(RelayStage.HELD_AT_DESTINATION, 5, null);
        RelayShipment otherTerminal = draftWithItems(new ItemStack(Items.IRON_INGOT, 2));
        otherTerminal.depart(recipient, "Recipient", "minecraft:overworld",
                new BlockPos(900, 64, 900), 0, true, 5, 600);
        otherTerminal.changeStage(RelayStage.HELD_AT_DESTINATION, 5, null);
        assertEquals(1, ledger.heldAtDestination(recipient, "minecraft:overworld", TO).size());
        assertEquals(1, ledger.heldAtDestination(recipient, "minecraft:overworld",
                new BlockPos(900, 64, 900)).size());
        assertEquals(0, ledger.heldAtDestination(UUID.randomUUID(), "minecraft:overworld", TO).size());
        assertEquals(0, ledger.heldAtDestination(recipient, "minecraft:the_nether", TO).size());
    }

    @Test
    void ledgerSurvivesTheSaveLoadCycle() {
        var registries = VanillaRegistries.createWorldLookup();
        RelayShipment draft = draftWithItems(new ItemStack(Items.IRON_INGOT, 64));
        draft.depart(recipient, "Recipient", "minecraft:overworld", TO, 2, true, 10, 900);
        RelayShipment returning = draftWithItems(new ItemStack(Items.DIAMOND, 7));
        returning.depart(recipient, "Recipient", "minecraft:overworld", TO, 0, false, 11, 900);
        returning.changeStage(RelayStage.HELD_AT_SOURCE, 15, "timeout");
        CompoundTag saved = RelayLedger.toNbt(ledger);
        RelayLedger restored = RelayLedger.fromNbt(saved);
        assertEquals(2, restored.view().size());
        RelayShipment restoredDeparted = restored.view().stream()
                .filter(s -> s.stage() == RelayStage.IN_TRANSIT).findFirst().orElseThrow();
        assertEquals(recipient, restoredDeparted.recipientUuid());
        assertEquals(2, restoredDeparted.routeIndex());
        assertTrue(restoredDeparted.routeEndIsDestination());
        assertEquals(900, restoredDeparted.tariffPaid());
        List<ItemStack> items = RelayItems.decode(registries, restoredDeparted.items());
        assertEquals(1, items.size());
        assertEquals(Items.IRON_INGOT, items.getFirst().getItem());
        assertEquals(64, items.getFirst().getCount());
        RelayShipment restoredReturn = restored.view().stream()
                .filter(s -> s.stage() == RelayStage.HELD_AT_SOURCE).findFirst().orElseThrow();
        assertEquals("timeout", restoredReturn.returnReason());
        assertEquals(FROM, restoredReturn.fromPos());
    }
}
