package de.trakt.caravan;

import de.trakt.data.PlayerQuestData;
import de.trakt.guildtown.GuildTownProgress;
import de.trakt.village.VillageLifeState;
import java.util.UUID;

/** Keeps settlement history in the existing personal Chronicle, once per physical event cycle. */
public final class VillageLifeChronicle {
    private VillageLifeChronicle() {}

    public static boolean record(PlayerQuestData data, UUID ownerId, VillageLifeState.VillageKey village,
                                 int cycle, boolean restored, long gameTime) {
        if (data == null || ownerId == null || village == null || cycle <= 0) {
            return false;
        }
        String eventId = "village." + (restored ? "restored." : "abandoned.")
                + village.dimension().replace(':', '.') + ".cycle." + cycle;
        return GuildTownProgress.addPersonalChronicle(data,
                new GuildTownProgress.VillageIdentity(ownerId, village.anchorX(), village.anchorZ()),
                eventId, false, gameTime);
    }
}
