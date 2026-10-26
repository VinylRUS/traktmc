package de.trakt.relay;

/**
 * Relay shipment lifecycle (F3b design doc §2.3). DRAFT lives at the sender's
 * terminal; the two HELD_* stages are claimable at the respective terminal
 * position; DELIVERED/RETURNED are terminal states kept for the event history.
 */
public enum RelayStage {
    DRAFT("draft"),
    IN_TRANSIT("in_transit"),
    HELD_AT_DESTINATION("held_at_destination"),
    RETURN_IN_TRANSIT("return_in_transit"),
    HELD_AT_SOURCE("held_at_source"),
    DELIVERED("delivered"),
    RETURNED("returned");

    private final String id;

    RelayStage(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED;
    }

    public boolean isTravelling() {
        return this == IN_TRANSIT || this == RETURN_IN_TRANSIT;
    }

    public static RelayStage fromId(String id) {
        if (id != null) {
            for (RelayStage stage : values()) {
                if (stage.id.equals(id)) {
                    return stage;
                }
            }
        }
        return null;
    }
}
