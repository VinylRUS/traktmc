package de.trakt.quest;

import java.util.UUID;

public interface TrackedQuestDrop {
    UUID trakt$getTrackedPlayerId();

    void trakt$setTrackedPlayerId(UUID playerId);
}
