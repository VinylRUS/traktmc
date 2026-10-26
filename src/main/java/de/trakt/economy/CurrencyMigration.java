package de.trakt.economy;

import de.trakt.caravan.RegionalDispatchLedger;
import de.trakt.data.PlayerQuestData;
import java.util.Map;

/**
 * One-shot migration of 2.5 saves to the D7 waycoin scale: every persisted
 * monetary amount (denominated in silvermarks by 2.5) is multiplied by 100 so
 * it reads as the same figure in silver waycoins. Rates and counters
 * (online-hour budgets, purchase counts, days) are deliberately not scaled.
 *
 * Called once from QuestState.readDailyQuestData when the save lacks the
 * "currencyScale" marker; the marker is written on the next save.
 */
public final class CurrencyMigration {
    /** Trade-route ints that store money (silvermarks in 2.5). */
    private static boolean isMonetaryTradeRouteKey(String key) {
        if (key == null) return false;
        if (key.equals("network_escrow") || key.equals("guild_contract_reward_quote")) return true;
        // route_<n>_earnings — lifetime earnings statistic per route slot.
        if (key.startsWith("route_") && key.endsWith("_earnings")) {
            String middle = key.substring("route_".length(), key.length() - "_earnings".length());
            return !middle.isEmpty() && middle.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    /** Pilgrim ints that store money (prosperity earned/spent statistics). */
    private static boolean isMonetaryPilgrimKey(String key) {
        return "prosperity.stat.earned".equals(key) || "prosperity.stat.spent".equals(key);
    }

    public static void migrateLegacyAmounts(PlayerQuestData data) {
        if (data == null) return;
        long balance = data.getCurrencyBalance();
        if (balance > 0L) {
            data.setCurrencyBalance(scale(balance));
        }
        for (Map.Entry<String, Integer> entry : data.getTradeRouteIntState().entrySet()) {
            if (entry.getValue() != null && entry.getValue() != 0 && isMonetaryTradeRouteKey(entry.getKey())) {
                data.setTradeRouteInt(entry.getKey(), scaleInt(entry.getValue()));
            }
        }
        for (Map.Entry<String, Integer> entry : data.getPilgrimIntState().entrySet()) {
            if (entry.getValue() != null && entry.getValue() != 0 && isMonetaryPilgrimKey(entry.getKey())) {
                data.setPilgrimInt(entry.getKey(), scaleInt(entry.getValue()));
            }
        }
        RegionalDispatchLedger.migrateLegacyCurrency(data);
    }

    private static long scale(long legacyAmount) {
        if (legacyAmount <= 0L) return legacyAmount;
        long scaled = legacyAmount * CurrencyService.CURRENCY_SCALE;
        return scaled < 0L ? Long.MAX_VALUE : scaled; // overflow clamp
    }

    private static int scaleInt(int legacyAmount) {
        if (legacyAmount <= 0) return legacyAmount;
        long scaled = (long) legacyAmount * CurrencyService.CURRENCY_SCALE;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }
}
