package de.trakt.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProsperityServiceBalanceTest {
    @Test
    void serverboundEconomyActionsRequireProsperityAccess() {
        assertFalse(ProsperityService.isActionAuthorized(false, "prosperity:market"));
        assertFalse(ProsperityService.isActionAuthorized(false, "service:village_festival"));
        assertTrue(ProsperityService.isActionAuthorized(true, "prosperity:market"));
        assertFalse(ProsperityService.isActionAuthorized(true, ""));
    }

    @Test
    void festivalDeliversItsAdvertisedFourSilvermarkMinimum() {
        long totalMinimumBonus = 3L * ProsperityService.festivalBonusAmount(1L);
        assertEquals(1200L, totalMinimumBonus); // 3 x 4 silver minimum, copper (D7)
        assertEquals(400L, ProsperityService.festivalBonusAmount(3L));
        assertEquals(2500L, ProsperityService.festivalBonusAmount(10_000L)); // 25% of 1 gold
    }

    @Test
    void ceremonyDeliversItsAdvertisedEightSilvermarkMinimum() {
        long totalMinimumBonus = 3L * ProsperityService.ceremonyBonusAmount(1L);
        assertEquals(2400L, totalMinimumBonus); // 3 x 8 silver minimum, copper (D7)
        assertEquals(800L, ProsperityService.ceremonyBonusAmount(20L));
        assertEquals(2500L, ProsperityService.ceremonyBonusAmount(10_000L)); // 25% of 1 gold
    }
}
