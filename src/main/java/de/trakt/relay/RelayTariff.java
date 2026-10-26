package de.trakt.relay;

import de.trakt.economy.CurrencyService;

/**
 * F3b relay tariff: a flat base, a distance component and a risk component, charged to
 * the sender in copper. The whole amount is burned (guild share flavour) — the road
 * itself already earns D8 freight money, so paying the sender twice would be a money
 * printer. Numbers are calibration-free MVP values; F3d owns the tuning.
 */
public final class RelayTariff {
    static final long BASE_SILVER = 5;
    static final long MAX_DISTANCE_SILVER = 10;
    static final long BLOCKS_PER_DISTANCE_STEP = 750;
    static final long DANGEROUS_ROUTE_SILVER = 2;
    static final long HARDENED_PROFILE_SILVER = 1;

    private RelayTariff() {}

    public static long compute(long distanceBlocks, boolean dangerousRoute, boolean hardenedProfile) {
        long steps = distanceBlocks <= 0 ? 0
                : (distanceBlocks + BLOCKS_PER_DISTANCE_STEP - 1) / BLOCKS_PER_DISTANCE_STEP;
        long distance = Math.min(MAX_DISTANCE_SILVER, steps);
        long risk = (dangerousRoute ? DANGEROUS_ROUTE_SILVER : 0)
                + (hardenedProfile ? HARDENED_PROFILE_SILVER : 0);
        return CurrencyService.silver(BASE_SILVER + distance + risk);
    }

    /** Pure silver breakdown for messages and the panel event payload. */
    public static long silverBreakdown(long distanceBlocks, boolean dangerousRoute, boolean hardenedProfile) {
        return CurrencyService.toSilver(compute(distanceBlocks, dangerousRoute, hardenedProfile));
    }
}
