package de.trakt.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.trakt.economy.CurrencyService;
import org.junit.jupiter.api.Test;

/** F3b: tariff math — flat base, capped distance component, additive risk component. */
final class RelayTariffTest {

    private static long silver(long amount) {
        return CurrencyService.silver(amount);
    }

    @Test
    void baseTariffWithoutDistanceOrRisk() {
        assertEquals(silver(5), RelayTariff.compute(0, false, false));
    }

    @Test
    void distanceStepsRoundUpPer750Blocks() {
        assertEquals(silver(6), RelayTariff.compute(1, false, false));
        assertEquals(silver(6), RelayTariff.compute(749, false, false));
        assertEquals(silver(6), RelayTariff.compute(750, false, false));
        assertEquals(silver(7), RelayTariff.compute(1500, false, false));
        assertEquals(silver(8), RelayTariff.compute(1501, false, false));
    }

    @Test
    void distanceComponentIsCapped() {
        assertEquals(silver(15), RelayTariff.compute(10_000, false, false));
        assertEquals(silver(15), RelayTariff.compute(1_000_000, false, false));
    }

    @Test
    void riskComponentsStackAdditively() {
        assertEquals(silver(7), RelayTariff.compute(0, true, false));
        assertEquals(silver(6), RelayTariff.compute(0, false, true));
        assertEquals(silver(8), RelayTariff.compute(0, true, true));
        assertEquals(silver(18), RelayTariff.compute(10_000, true, true));
    }

    @Test
    void negativeDistanceIsClampedToBase() {
        assertEquals(silver(5), RelayTariff.compute(-100, false, false));
    }

    @Test
    void silverBreakdownMatchesCopperTariff() {
        long copper = RelayTariff.compute(2_300, true, true);
        assertEquals(12, RelayTariff.silverBreakdown(2_300, true, true));
        assertEquals(silver(12), copper);
    }
}
