package de.trakt.caravan;

import de.trakt.economy.CaravanArrivalEconomy;
import de.trakt.economy.CurrencyService;
import de.trakt.economy.DeliveryEconomy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * D8: freighted caravan arrivals pay a margin of the goods value (15-40%,
 * growing with route upgrades) plus the transport fee — not the full price.
 */
final class TradeContractQuoteTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void arrivalShareStaysBetween15And40PercentAndSaturates() {
        for (int upgrades = 0; upgrades <= 10; upgrades++) {
            double share = CaravanArrivalEconomy.arrivalShare(upgrades);
            assertTrue(share >= CaravanArrivalEconomy.BASE_SHARE, "share below base at " + upgrades);
            assertTrue(share <= CaravanArrivalEconomy.MAX_SHARE, "share above cap at " + upgrades);
        }
        assertEquals(CaravanArrivalEconomy.BASE_SHARE, CaravanArrivalEconomy.arrivalShare(0));
        assertEquals(0.40, CaravanArrivalEconomy.arrivalShare(5), 1e-12);
        assertEquals(0.40, CaravanArrivalEconomy.arrivalShare(6), "share must saturate at 40%");
        assertEquals(0.40, CaravanArrivalEconomy.arrivalShare(100));
    }

    @Test void arrivalShareGrowsMonotonicallyWithUpgrades() {
        double previous = -1.0;
        for (int upgrades = 0; upgrades <= 6; upgrades++) {
            double share = CaravanArrivalEconomy.arrivalShare(upgrades);
            assertTrue(share >= previous, "share must not decrease");
            previous = share;
        }
    }

    @Test void freightArrivalPaysMarginPlusFeeNeverFullPrice() {
        long goods = DeliveryEconomy.materialValue(Items.WHEAT, 64); // 6 silver = 600 copper
        assertEquals(600, goods);
        long fee = DeliveryEconomy.transportBonus(2000, true, false, false);

        long zeroUpgrades = CaravanArrivalEconomy.freightArrivalReward(Items.WHEAT, 64, 0, fee);
        long fiveUpgrades = CaravanArrivalEconomy.freightArrivalReward(Items.WHEAT, 64, 5, fee);

        // 15% of 600 = 90; 40% of 600 = 240; plus the same transport fee on both.
        assertEquals(90 + fee, zeroUpgrades);
        assertEquals(240 + fee, fiveUpgrades);
        // The margin alone never reaches the goods value (D8: not the full price).
        assertTrue(zeroUpgrades - fee < goods);
        assertTrue(fiveUpgrades - fee < goods);
    }

    @Test void freightArrivalRewardIsAtLeastOneCopper() {
        assertEquals(1L, CaravanArrivalEconomy.freightArrivalReward(Items.WHEAT, 1, 0, 0L));
    }

    @Test void waycoinRateIsTheStrictHundredLadder() {
        assertEquals(100L, CurrencyService.SILVERMARK);
        assertEquals(10_000L, CurrencyService.CROWN);
        assertEquals(600L, CurrencyService.silver(6L));
        assertEquals(6L, CurrencyService.toSilver(640L));
        assertEquals(0L, CurrencyService.toSilver(99L));
    }
}
