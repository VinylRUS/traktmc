package de.trakt.economy;

import net.minecraft.world.item.Item;

/**
 * D8 caravan arrival model: a freighted arrival pays a margin of the goods
 * value, not its full price. The share starts at 15% and grows by 5% per
 * owned route upgrade (TradeRouteUpgrade bits), saturating at 40%.
 * Transport fee (DeliveryEconomy.transportBonus) is paid on top of the margin.
 */
public final class CaravanArrivalEconomy {
    public static final double BASE_SHARE = 0.15;
    public static final double PER_UPGRADE_SHARE = 0.05;
    public static final double MAX_SHARE = 0.40;

    private CaravanArrivalEconomy() {}

    /** Arrival margin share for the given number of owned route upgrades. */
    public static double arrivalShare(int ownedUpgrades) {
        double share = BASE_SHARE + PER_UPGRADE_SHARE * Math.max(0, ownedUpgrades);
        return Math.min(MAX_SHARE, share);
    }

    /**
     * Payout for a freighted caravan arrival: share of the goods value
     * (grows with upgrades bought while the contract runs) plus the transport fee.
     */
    public static long freightArrivalReward(Item item, int amount, int ownedUpgrades, long transportFee) {
        long goodsValue = DeliveryEconomy.materialValue(item, amount);
        long margin = Math.round(goodsValue * arrivalShare(ownedUpgrades));
        return Math.max(1L, margin + Math.max(0L, transportFee));
    }
}
