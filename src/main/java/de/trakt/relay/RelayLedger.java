package de.trakt.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-global ledger of relay shipments (F3b). Global on purpose: a shipment
 * binds two players, so duplicating it into both players' data would be a dupe
 * recipe. All mutations happen through RelayService with a world in hand.
 */
public final class RelayLedger extends SavedData {
    public static final int MAX_STACKS_PER_SHIPMENT = 3;
    public static final int MAX_ACTIVE_PER_SENDER = 3;
    public static final int HELD_TIMEOUT_DAYS = 3;
    public static final int TRANSIT_TIMEOUT_DAYS = 7;

    private static final String ID = "trakt_relay_shipments";

    public static final SavedDataType<RelayLedger> TYPE = new SavedDataType<>(
            Identifier.withDefaultNamespace(ID),
            RelayLedger::new,
            CompoundTag.CODEC.xmap(RelayLedger::fromNbt, RelayLedger::toNbt),
            DataFixTypes.LEVEL);

    private final List<RelayShipment> shipments = new ArrayList<>();

    RelayLedger() {}

    public static RelayLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public List<RelayShipment> view() {
        return RelayShipment.immutableView(shipments);
    }

    /** The sender's single open draft, regardless of which terminal it is bound to. */
    public Optional<RelayShipment> draftFor(UUID sender) {
        for (RelayShipment shipment : shipments) {
            if (shipment.senderUuid().equals(sender) && shipment.stage() == RelayStage.DRAFT) {
                return Optional.of(shipment);
            }
        }
        return Optional.empty();
    }

    /** Outbound quota counter: shipments still occupying a slot of the sender's allowance. */
    public long activeFor(UUID sender) {
        return shipments.stream()
                .filter(s -> s.senderUuid().equals(sender))
                .filter(s -> s.stage() == RelayStage.IN_TRANSIT || s.stage() == RelayStage.HELD_AT_DESTINATION)
                .count();
    }

    public List<RelayShipment> heldAtDestination(UUID recipient, String dimension, BlockPos pos) {
        List<RelayShipment> result = new ArrayList<>();
        for (RelayShipment shipment : shipments) {
            if (shipment.stage() == RelayStage.HELD_AT_DESTINATION
                    && recipient.equals(shipment.recipientUuid())
                    && shipment.toDimension() != null && shipment.toDimension().equals(dimension)
                    && shipment.toPos() != null && shipment.toPos().equals(pos)) {
                result.add(shipment);
            }
        }
        return List.copyOf(result);
    }

    public List<RelayShipment> heldAtSource(UUID sender, String dimension, BlockPos pos) {
        List<RelayShipment> result = new ArrayList<>();
        for (RelayShipment shipment : shipments) {
            if (shipment.stage() == RelayStage.HELD_AT_SOURCE
                    && shipment.senderUuid().equals(sender)
                    && shipment.fromDimension().equals(dimension)
                    && shipment.fromPos().equals(pos)) {
                result.add(shipment);
            }
        }
        return List.copyOf(result);
    }

    public List<RelayShipment> travellingOn(UUID sender, int routeIndex) {
        List<RelayShipment> result = new ArrayList<>();
        for (RelayShipment shipment : shipments) {
            if (shipment.senderUuid().equals(sender)
                    && shipment.routeIndex() == routeIndex
                    && shipment.stage().isTravelling()) {
                result.add(shipment);
            }
        }
        return List.copyOf(result);
    }

    void addDraft(RelayShipment draft) {
        shipments.add(draft);
        setDirty();
    }

    void removeDraft(UUID sender) {
        shipments.removeIf(s -> s.senderUuid().equals(sender) && s.stage() == RelayStage.DRAFT);
        setDirty();
    }

    /**
     * Timeout sweep (called from the relay tick): unclaimed shipments bounce back,
     * shipments stuck in transit for a week materialize at the sender's terminal.
     * Returns the shipments whose stage changed so the service can notify players.
     */
    List<RelayShipment> sweepTimeouts(long day) {
        List<RelayShipment> changed = new ArrayList<>();
        for (RelayShipment shipment : shipments) {
            if (shipment.stage() == RelayStage.HELD_AT_DESTINATION
                    && day - shipment.stageSinceDay() >= HELD_TIMEOUT_DAYS) {
                shipment.changeStage(RelayStage.RETURN_IN_TRANSIT, day, "timeout");
                changed.add(shipment);
            } else if (shipment.stage() == RelayStage.IN_TRANSIT
                    && day - shipment.stageSinceDay() >= TRANSIT_TIMEOUT_DAYS) {
                shipment.changeStage(RelayStage.HELD_AT_SOURCE, day, "timeout");
                changed.add(shipment);
            }
        }
        if (!changed.isEmpty()) {
            setDirty();
        }
        return changed;
    }

    /** A terminal disappeared: held and in-flight cargo for it turns back. */
    List<RelayShipment> onTerminalRemoved(String dimension, BlockPos pos, long day) {
        List<RelayShipment> changed = new ArrayList<>();
        String key = RelayKeys.terminalKey(dimension, pos);
        for (RelayShipment shipment : shipments) {
            boolean destinationGone = shipment.stage() == RelayStage.HELD_AT_DESTINATION
                    || shipment.stage() == RelayStage.IN_TRANSIT;
            if (destinationGone && shipment.toPos() != null
                    && key.equals(RelayKeys.terminalKey(shipment.toDimension(), shipment.toPos()))) {
                shipment.changeStage(RelayStage.RETURN_IN_TRANSIT, day, "terminal_destroyed");
                changed.add(shipment);
            }
        }
        if (!changed.isEmpty()) {
            setDirty();
        }
        return changed;
    }

    /** The carrying route is gone: cargo returns straight to the sender's terminal slot. */
    List<RelayShipment> onRouteRemoved(UUID sender, int routeIndex, long day) {
        List<RelayShipment> changed = new ArrayList<>();
        for (RelayShipment shipment : travellingOn(sender, routeIndex)) {
            shipment.changeStage(RelayStage.HELD_AT_SOURCE, day, "route_removed");
            changed.add(shipment);
        }
        if (!changed.isEmpty()) {
            setDirty();
        }
        return changed;
    }

    void markDirtyExternally() {
        setDirty();
    }

    static CompoundTag toNbt(RelayLedger state) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (RelayShipment shipment : state.shipments) {
            list.add(shipment.toNbt());
        }
        tag.put("shipments", list);
        tag.putInt("schema", 1);
        return tag;
    }

    static RelayLedger fromNbt(CompoundTag tag) {
        RelayLedger state = new RelayLedger();
        ListTag list = tag.getListOrEmpty("shipments");
        for (int index = 0; index < list.size(); index++) {
            RelayShipment shipment = RelayShipment.fromNbt(list.getCompoundOrEmpty(index));
            if (shipment != null) {
                state.shipments.add(shipment);
            }
        }
        return state;
    }
}
