package de.trakt.economy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * F7b: /trakt pay and /trakt exchange. The transfer moves the full amount or nothing
 * (money supply invariant), and every withdrawal mints a coin mix whose value
 * re-sums to exactly the withdrawn amount — pure math, no server needed.
 */
final class CurrencyExchangeF7bTest {

    @Test
    void optimalMixUsesAsFewCoinsAsPossible() {
        CurrencyService.CoinMix empty = CurrencyService.optimalMix(0L);
        assertTrue(empty.isEmpty());
        assertEquals(0L, empty.totalValue());

        // 12 345 copper = 1 gold + 23 silver + 45 copper
        CurrencyService.CoinMix mix = CurrencyService.optimalMix(12_345L);
        assertEquals(1L, mix.crowns());
        assertEquals(23L, mix.silvermarks());
        assertEquals(45L, mix.coppers());
        assertEquals(12_345L, mix.totalValue());
    }

    @Test
    void optimalMixAlwaysPreservesValue() {
        long[] samples = {1L, 99L, 100L, 101L, 9_999L, 10_000L, 10_001L,
                123_456_789L, Long.MAX_VALUE / 4, Long.MAX_VALUE};
        for (long sample : samples) {
            assertEquals(sample, CurrencyService.optimalMix(sample).totalValue(),
                    "value must survive the denomination split");
        }
        assertEquals(0L, CurrencyService.optimalMix(-5L).totalValue());
    }

    @Test
    void exactUnitMintsOnlyTheRequestedDenomination() {
        CurrencyService.CoinMix crowns = CurrencyService.exactUnit(7L, CurrencyService.CurrencyUnit.CROWN);
        assertEquals(7L, crowns.crowns());
        assertEquals(0L, crowns.silvermarks());
        assertEquals(0L, crowns.coppers());
        assertEquals(70_000L, crowns.totalValue());

        CurrencyService.CoinMix silver = CurrencyService.exactUnit(150L, CurrencyService.CurrencyUnit.SILVERMARK);
        assertEquals(150L, silver.silvermarks());
        assertEquals(15_000L, silver.totalValue());

        CurrencyService.CoinMix copper = CurrencyService.exactUnit(64L, CurrencyService.CurrencyUnit.COPPER);
        assertEquals(64L, copper.coppers());
        assertEquals(64L, copper.totalValue());

        assertTrue(CurrencyService.exactUnit(0L, CurrencyService.CurrencyUnit.COPPER).isEmpty());
        assertTrue(CurrencyService.exactUnit(5L, null).isEmpty());
    }

    @Test
    void transferRejectsDegenerateInputsWithoutTouchingBalances() {
        assertFalse(CurrencyService.transfer(null, null, null, 100L));
        // self-transfer and non-positive amounts are guarded before any balance access
        java.util.UUID id = java.util.UUID.randomUUID();
        assertFalse(CurrencyService.transfer(null, id, id, 100L));
        assertFalse(CurrencyService.transfer(null, id, java.util.UUID.randomUUID(), 0L));
        assertFalse(CurrencyService.transfer(null, id, java.util.UUID.randomUUID(), -10L));
    }

    @Test
    void unitParserAcceptsTheSuggestedWords() {
        assertEquals(CurrencyService.CurrencyUnit.COPPER, CurrencyService.parseUnit("copper"));
        assertEquals(CurrencyService.CurrencyUnit.SILVERMARK, CurrencyService.parseUnit("silvermark"));
        assertEquals(CurrencyService.CurrencyUnit.CROWN, CurrencyService.parseUnit("crown"));
        // the default of /trakt pay and /trakt exchange is the silvermark (2.5 authoring unit)
        assertEquals(CurrencyService.CurrencyUnit.SILVERMARK, CurrencyService.parseUnit(""));
        assertNull(CurrencyService.parseUnit("rubles"));
    }

    @Test
    void toBaseSaturatesInsteadOfOverflowing() {
        assertEquals(500L, CurrencyService.toBase(5L, CurrencyService.CurrencyUnit.SILVERMARK));
        assertEquals(Long.MAX_VALUE, CurrencyService.toBase(Long.MAX_VALUE, CurrencyService.CurrencyUnit.CROWN));
        assertEquals(0L, CurrencyService.toBase(-1L, CurrencyService.CurrencyUnit.CROWN));
    }
}
