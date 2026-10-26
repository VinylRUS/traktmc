package de.trakt.economy;

import de.trakt.caravan.RegionalDispatchLedger;
import de.trakt.data.PlayerQuestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** D7 one-shot migration: 2.5 silvermark amounts become copper waycoins (x100). */
final class CurrencyMigrationTest {

    private static PlayerQuestData legacyPlayer() {
        PlayerQuestData data = new PlayerQuestData();
        data.setCurrencyBalance(25L);                          // 25 silver
        data.setTradeRouteInt("network_escrow", 7);            // escrow
        data.setTradeRouteInt("guild_contract_reward_quote", 14);
        data.setTradeRouteInt("route_0_earnings", 120);
        data.setTradeRouteInt("route_12_earnings", 5);
        data.setPilgrimInt("prosperity.stat.earned", 340);
        data.setPilgrimInt("prosperity.stat.spent", 90);
        // Non-monetary state must stay untouched.
        data.setTradeRouteInt("network_income_online_window_paid", 7); // rate limiter, silver marks
        data.setTradeRouteInt("caravan_trade_X_count", 3);
        data.setTradeRouteInt("route_0_quality", 55);
        data.setPilgrimInt("pilgrim_progress", 2);
        return data;
    }

    @Test void monetaryAmountsAreScaledExactlyOnce() {
        PlayerQuestData data = legacyPlayer();
        CurrencyMigration.migrateLegacyAmounts(data);

        assertEquals(2500L, data.getCurrencyBalance());            // 25 silver preserved visually
        assertEquals(700, data.getTradeRouteInt("network_escrow"));
        assertEquals(1400, data.getTradeRouteInt("guild_contract_reward_quote"));
        assertEquals(12_000, data.getTradeRouteInt("route_0_earnings"));
        assertEquals(500, data.getTradeRouteInt("route_12_earnings"));
        assertEquals(34_000, data.getPilgrimInt("prosperity.stat.earned"));
        assertEquals(9_000, data.getPilgrimInt("prosperity.stat.spent"));
    }

    @Test void ratesAndCountersAndProgressAreNotScaled() {
        PlayerQuestData data = legacyPlayer();
        CurrencyMigration.migrateLegacyAmounts(data);

        assertEquals(7, data.getTradeRouteInt("network_income_online_window_paid"));
        assertEquals(3, data.getTradeRouteInt("caravan_trade_X_count"));
        assertEquals(55, data.getTradeRouteInt("route_0_quality"));
        assertEquals(2, data.getPilgrimInt("pilgrim_progress"));
    }

    @Test void doubleScalingWouldDoubleValuesSoTheMarkerMatters() {
        PlayerQuestData data = legacyPlayer();
        CurrencyMigration.migrateLegacyAmounts(data);
        assertEquals(2500L, data.getCurrencyBalance());
        // The scaling itself is NOT idempotent by design: QuestState guards it with the
        // "currencyScale" save marker so it runs exactly once per world.
        CurrencyMigration.migrateLegacyAmounts(data);
        assertEquals(250_000L, data.getCurrencyBalance());
    }

    @Test void zeroAndEmptyStatesAreSafe() {
        PlayerQuestData data = new PlayerQuestData();
        CurrencyMigration.migrateLegacyAmounts(data);
        assertEquals(0L, data.getCurrencyBalance());
    }

    @Test void inFlightRegionalDispatchRewardIsScaled() {
        PlayerQuestData data = legacyPlayer();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        RegionalDispatchLedger.start(data, new RegionalDispatchLedger.Dispatch(
                UUID.randomUUID(), source, target, "minecraft:overworld",
                0, 0, 100, 0, 1, 2, "minecraft:wheat", 8, 22,
                RegionalDispatchLedger.Stage.WAITING_AT_SOURCE));
        CurrencyMigration.migrateLegacyAmounts(data);
        RegionalDispatchLedger.Dispatch migrated = RegionalDispatchLedger.read(data);
        assertNotNull(migrated);
        assertEquals(2200, migrated.reward());
        assertEquals(8, migrated.amount());
        assertEquals(RegionalDispatchLedger.Stage.WAITING_AT_SOURCE, migrated.stage());
    }
}
