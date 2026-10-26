package de.trakt.relay;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * One relay shipment (F3b). Items live as a pre-encoded NBT list — the escrow is
 * the single encoded copy, decoded only for merging, summaries and the final handout.
 */
public final class RelayShipment {
    private final UUID id;
    private final UUID senderUuid;
    private String senderName;
    private UUID recipientUuid;
    private String recipientName;
    private final String fromDimension;
    private final BlockPos fromPos;
    private String toDimension;
    private BlockPos toPos;
    private int routeIndex;
    private boolean routeEndIsDestination;
    private ListTag items;
    private RelayStage stage;
    private long createdDay;
    private long stageSinceDay;
    private long tariffPaid;
    private String returnReason;

    RelayShipment(UUID id, UUID senderUuid, String senderName, String fromDimension, BlockPos fromPos) {
        this.id = id;
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.fromDimension = fromDimension;
        this.fromPos = fromPos;
        this.items = new ListTag();
        this.stage = RelayStage.DRAFT;
        this.returnReason = "";
    }

    public UUID id() {
        return id;
    }

    public UUID senderUuid() {
        return senderUuid;
    }

    public String senderName() {
        return senderName;
    }

    public UUID recipientUuid() {
        return recipientUuid;
    }

    public String recipientName() {
        return recipientName;
    }

    public String fromDimension() {
        return fromDimension;
    }

    public BlockPos fromPos() {
        return fromPos;
    }

    public String toDimension() {
        return toDimension;
    }

    public BlockPos toPos() {
        return toPos;
    }

    public int routeIndex() {
        return routeIndex;
    }

    public boolean routeEndIsDestination() {
        return routeEndIsDestination;
    }

    public ListTag items() {
        return items;
    }

    public RelayStage stage() {
        return stage;
    }

    public long createdDay() {
        return createdDay;
    }

    public long stageSinceDay() {
        return stageSinceDay;
    }

    public long tariffPaid() {
        return tariffPaid;
    }

    public String returnReason() {
        return returnReason;
    }

    void updateSenderName(String name) {
        if (name != null && !name.isBlank()) {
            this.senderName = name;
        }
    }

    void updateItems(ListTag encoded) {
        this.items = encoded == null ? new ListTag() : encoded;
    }

    void depart(UUID recipientUuid, String recipientName, String toDimension, BlockPos toPos,
                int routeIndex, boolean routeEndIsDestination, long day, long tariffPaid) {
        this.recipientUuid = recipientUuid;
        this.recipientName = recipientName;
        this.toDimension = toDimension;
        this.toPos = toPos;
        this.routeIndex = routeIndex;
        this.routeEndIsDestination = routeEndIsDestination;
        this.tariffPaid = tariffPaid;
        this.stage = RelayStage.IN_TRANSIT;
        this.stageSinceDay = day;
    }

    void changeStage(RelayStage stage, long day, String returnReason) {
        this.stage = stage;
        this.stageSinceDay = day;
        if (returnReason != null) {
            this.returnReason = returnReason;
        }
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());
        tag.putString("sender", senderUuid.toString());
        tag.putString("sender_name", senderName);
        if (recipientUuid != null) {
            tag.putString("recipient", recipientUuid.toString());
            tag.putString("recipient_name", recipientName);
        }
        tag.putString("from_dim", fromDimension);
        tag.putInt("from_x", fromPos.getX());
        tag.putInt("from_y", fromPos.getY());
        tag.putInt("from_z", fromPos.getZ());
        if (toPos != null) {
            tag.putString("to_dim", toDimension);
            tag.putInt("to_x", toPos.getX());
            tag.putInt("to_y", toPos.getY());
            tag.putInt("to_z", toPos.getZ());
        }
        tag.putInt("route", routeIndex);
        tag.putBoolean("route_end_dest", routeEndIsDestination);
        tag.put("items", items.copy());
        tag.putString("stage", stage.id());
        tag.putLong("created_day", createdDay);
        tag.putLong("stage_day", stageSinceDay);
        tag.putLong("tariff", tariffPaid);
        tag.putString("return_reason", returnReason);
        return tag;
    }

    static RelayShipment fromNbt(CompoundTag tag) {
        UUID id = parseUuid(tag.getStringOr("id", ""));
        UUID sender = parseUuid(tag.getStringOr("sender", ""));
        BlockPos fromPos = new BlockPos(
                tag.getIntOr("from_x", 0),
                tag.getIntOr("from_y", 0),
                tag.getIntOr("from_z", 0));
        if (id == null || sender == null) {
            return null;
        }
        RelayShipment shipment = new RelayShipment(id, sender,
                tag.getStringOr("sender_name", ""), tag.getStringOr("from_dim", ""), fromPos);
        UUID recipient = parseUuid(tag.getStringOr("recipient", ""));
        if (recipient != null) {
            shipment.recipientUuid = recipient;
            shipment.recipientName = tag.getStringOr("recipient_name", "");
        }
        int toX = tag.getIntOr("to_x", Integer.MIN_VALUE);
        if (toX != Integer.MIN_VALUE) {
            shipment.toDimension = tag.getStringOr("to_dim", "");
            shipment.toPos = new BlockPos(toX,
                    tag.getIntOr("to_y", 0), tag.getIntOr("to_z", 0));
        }
        shipment.routeIndex = tag.getIntOr("route", 0);
        shipment.routeEndIsDestination = tag.getBooleanOr("route_end_dest", false);
        ListTag items = tag.getListOrEmpty("items");
        shipment.items = items.copy();
        RelayStage stage = RelayStage.fromId(tag.getStringOr("stage", ""));
        shipment.stage = stage == null ? RelayStage.DRAFT : stage;
        shipment.createdDay = tag.getLongOr("created_day", 0);
        shipment.stageSinceDay = tag.getLongOr("stage_day", 0);
        shipment.tariffPaid = tag.getLongOr("tariff", 0);
        shipment.returnReason = tag.getStringOr("return_reason", "");
        return shipment;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static List<RelayShipment> immutableView(List<RelayShipment> shipments) {
        return List.copyOf(shipments);
    }
}
