package de.trakt.economy;

import de.trakt.data.PlayerQuestData;
import de.trakt.data.QuestState;
import de.trakt.registry.ModItems;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Waycoin currency (D7): internal unit is the copper waycoin (long).
 * 1 silver waycoin = 100 copper, 1 gold waycoin = 100 silver = 10 000 copper.
 * Balances saved by 2.5 (denominated in silvermarks) are migrated x100 on load
 * (see {@link CurrencyMigration}), so the visual silver amount is preserved.
 */
public final class CurrencyService {
    public static final long COPPER = 1L;
    public static final long SILVERMARK = 100L;
    public static final long CROWN = 10_000L;
    /** Save marker: "currencyScale" == 100 means amounts are already in copper waycoins. */
    public static final int CURRENCY_SCALE = 100;
    private static final long LEGACY_COPPER = COPPER;
    private static final long LEGACY_IRON = SILVERMARK;
    private static final long LEGACY_GOLD = CROWN;

    private CurrencyService() {}

    public static long getBalance(ServerLevel world, UUID playerId) {
        if (world == null || playerId == null) {
            return 0L;
        }
        return QuestState.get(world.getServer()).getPlayerData(playerId).getCurrencyBalance();
    }

    public static long addBalance(ServerLevel world, UUID playerId, long amount) {
        if (world == null || playerId == null || amount == 0L) {
            return getBalance(world, playerId);
        }

        PlayerQuestData data = QuestState.get(world.getServer()).getPlayerData(playerId);
        long next = clampAdd(data.getCurrencyBalance(), amount);
        if (next != data.getCurrencyBalance()) {
            long appliedDelta = next - data.getCurrencyBalance();
            data.setCurrencyBalance(next);
            ProsperityService.recordCurrencyDelta(world, playerId, appliedDelta);
            QuestState.get(world.getServer()).setDirty();
        }
        return next;
    }

    public static long setBalance(ServerLevel world, UUID playerId, long amount) {
        if (world == null || playerId == null) {
            return 0L;
        }

        PlayerQuestData data = QuestState.get(world.getServer()).getPlayerData(playerId);
        long next = Math.max(0L, amount);
        if (next != data.getCurrencyBalance()) {
            data.setCurrencyBalance(next);
            QuestState.get(world.getServer()).setDirty();
        }
        return next;
    }

    public static boolean canAfford(ServerLevel world, UUID playerId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        return getBalance(world, playerId) >= amount;
    }

    public static boolean removeBalance(ServerLevel world, UUID playerId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (!canAfford(world, playerId, amount)) {
            return false;
        }
        addBalance(world, playerId, -amount);
        return true;
    }

    public static Component formatBalance(long amount) {
        long clamped = Math.max(0L, amount);
        if (clamped == 0L) {
            return Component.empty()
                    .append(Component.literal("0").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("text.trakt.currency.unit.copper.other").withStyle(ChatFormatting.GRAY));
        }
        long crowns = clamped / CROWN;
        long silvermarks = (clamped % CROWN) / SILVERMARK;
        long coppers = clamped % SILVERMARK;

        MutableComponent text = Component.empty();
        boolean appended = false;
        appended = appendUnit(text, appended, crowns,
                "text.trakt.currency.unit.crown.one",
                "text.trakt.currency.unit.crown.other",
                ChatFormatting.GOLD);
        appended = appendUnit(text, appended, silvermarks,
                "text.trakt.currency.unit.silvermark.one",
                "text.trakt.currency.unit.silvermark.other",
                ChatFormatting.GRAY);
        appended = appendUnit(text, appended, coppers,
                "text.trakt.currency.unit.copper.one",
                "text.trakt.currency.unit.copper.other",
                ChatFormatting.DARK_GRAY);

        if (!appended) {
            return Component.empty()
                    .append(Component.literal("0").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("text.trakt.currency.unit.copper.other").withStyle(ChatFormatting.GRAY));
        }
        return text;
    }

    public static Component formatDelta(long amount) {
        long absolute = Math.abs(amount);
        MutableComponent text = Component.empty();
        if (amount > 0L) {
            text.append(Component.literal("+").withStyle(ChatFormatting.GREEN));
        } else if (amount < 0L) {
            text.append(Component.literal("-").withStyle(ChatFormatting.RED));
        }
        text.append(formatBalance(absolute));
        return text;
    }

    private static boolean appendUnit(MutableComponent text,
                                      boolean appended,
                                      long amount,
                                      String singularKey,
                                      String pluralKey,
                                      ChatFormatting color) {
        if (amount <= 0L) {
            return appended;
        }
        if (appended) {
            text.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        }
        text.append(Component.literal(Long.toString(amount)).withStyle(color));
        text.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        text.append(Component.translatable(amount == 1L ? singularKey : pluralKey).withStyle(color));
        return true;
    }

    public static CurrencyUnit parseUnit(String value) {
        if (value == null || value.isEmpty()) {
            return CurrencyUnit.SILVERMARK;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "copper", "coppers", "penny", "pennies", "waycoin", "waycoins", "cp" -> CurrencyUnit.COPPER;
            case "silvermark", "silvermarks", "silver", "silberling", "silberlinge", "mark", "marks", "iron_mark", "iron_marks", "sm", "m" -> CurrencyUnit.SILVERMARK;
            case "crown", "crowns", "krone", "kronen", "gold_crown", "gold_crowns", "cr", "k" -> CurrencyUnit.CROWN;
            default -> null;
        };
    }

    /** Converts a silver-denominated amount (authored values, v1 REST/JSON) into base copper. */
    public static long silver(long silverAmount) {
        return toBase(silverAmount, CurrencyUnit.SILVERMARK);
    }

    /** Converts a base copper amount into whole silver waycoins (v1 REST reporting, floor). */
    public static long toSilver(long baseAmount) {
        if (baseAmount <= 0L) {
            return 0L;
        }
        return baseAmount / SILVERMARK;
    }

    public static long toBase(long amount, CurrencyUnit unit) {
        if (amount <= 0L || unit == null) {
            return 0L;
        }
        long factor = unit.value();
        if (amount > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return amount * factor;
    }

    public static LegacyCoinConversion convertLegacyCoins(ServerLevel world, ServerPlayer player) {
        if (world == null || player == null) {
            return new LegacyCoinConversion(0, 0, 0, 0L, 0L);
        }

        Inventory inventory = player.getInventory();
        int copperCount = 0;
        int ironCount = 0;
        int goldCount = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.LEGACY_COPPER_PENNY)) {
                copperCount += stack.getCount();
                inventory.setItem(i, ItemStack.EMPTY);
                continue;
            }
            if (stack.is(ModItems.SILVERMARK)) {
                ironCount += stack.getCount();
                inventory.setItem(i, ItemStack.EMPTY);
                continue;
            }
            if (stack.is(ModItems.CROWN)) {
                goldCount += stack.getCount();
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }

        long addedAmount = (long) copperCount * LEGACY_COPPER
                + (long) ironCount * LEGACY_IRON
                + (long) goldCount * LEGACY_GOLD;
        long newBalance = getBalance(world, player.getUUID());
        if (addedAmount > 0L) {
            newBalance = addBalance(world, player.getUUID(), addedAmount);
            player.inventoryMenu.broadcastChanges();
        }
        return new LegacyCoinConversion(copperCount, ironCount, goldCount, addedAmount, newBalance);
    }

    /**
     * F7b: moves balance between two players. The operation is check-then-move:
     * it either transfers the full amount or nothing — no partial states, and the
     * money supply (sum of both balances) is invariant.
     */
    public static boolean transfer(ServerLevel world, UUID fromId, UUID toId, long amount) {
        if (world == null || fromId == null || toId == null || amount <= 0L || fromId.equals(toId)) {
            return false;
        }
        if (!canAfford(world, fromId, amount)) {
            return false;
        }
        removeBalance(world, fromId, amount);
        addBalance(world, toId, amount);
        return true;
    }

    /**
     * F7b: the optimal physical representation of a base amount — as few coin
     * items as possible (gold first, then silver, copper remainder). Pure math,
     * unit-tested: the mix always re-sums to the original value.
     */
    public static CoinMix optimalMix(long baseAmount) {
        long clamped = Math.max(0L, baseAmount);
        return new CoinMix(clamped / CROWN, (clamped % CROWN) / SILVERMARK, clamped % SILVERMARK);
    }

    /** F7b: an exact-denomination withdrawal — every coin item is of {@code unit}. */
    public static CoinMix exactUnit(long amount, CurrencyUnit unit) {
        if (amount <= 0L || unit == null) {
            return new CoinMix(0, 0, 0);
        }
        return switch (unit) {
            case CROWN -> new CoinMix(amount, 0, 0);
            case SILVERMARK -> new CoinMix(0, amount, 0);
            case COPPER -> new CoinMix(0, 0, amount);
        };
    }

    /**
     * F7b: mints a mix into the player's inventory in vanilla stack sizes;
     * whatever does not fit drops at the player's feet (never silently lost).
     */
    public static void mintIntoInventory(ServerPlayer player, CoinMix mix) {
        if (player == null || mix == null || mix.isEmpty()) {
            return;
        }
        giveCoinStacks(player, ModItems.CROWN, mix.crowns());
        giveCoinStacks(player, ModItems.SILVERMARK, mix.silvermarks());
        giveCoinStacks(player, ModItems.LEGACY_COPPER_PENNY, mix.coppers());
        player.inventoryMenu.broadcastChanges();
    }

    private static void giveCoinStacks(ServerPlayer player, Item item, long count) {
        if (item == null) {
            return; // registration order guard — items exist long before commands run
        }
        long remaining = count;
        while (remaining > 0L) {
            int stackCount = (int) Math.min(remaining, 64L);
            ItemStack stack = new ItemStack(item, stackCount);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
            remaining -= stackCount;
        }
    }

    public static Component formatLegacyCoinBreakdown(int copperCount, int ironCount, int goldCount) {
        MutableComponent text = Component.empty();
        boolean appended = false;
        appended = appendLegacyCount(text, appended, goldCount, "item.trakt.crown", ChatFormatting.GOLD);
        appended = appendLegacyCount(text, appended, ironCount, "item.trakt.silvermark", ChatFormatting.GRAY);
        appended = appendLegacyCount(text, appended, copperCount, "item.trakt.legacy_copper_penny", ChatFormatting.GRAY);
        if (!appended) {
            return Component.empty()
                    .append(Component.literal("0").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("item.trakt.legacy_copper_penny").withStyle(ChatFormatting.GRAY));
        }
        return text;
    }

    private static boolean appendLegacyCount(MutableComponent text,
                                             boolean appended,
                                             int count,
                                             String translationKey,
                                             ChatFormatting color) {
        if (count <= 0) {
            return appended;
        }
        if (appended) {
            text.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
        }
        text.append(Component.literal(Integer.toString(count)).withStyle(color));
        text.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
        text.append(Component.translatable(translationKey).withStyle(color));
        return true;
    }

    private static long clampAdd(long current, long delta) {
        if (delta > 0L && current > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        if (delta < 0L && current < -delta) {
            return 0L;
        }
        return Math.max(0L, current + delta);
    }

    public enum CurrencyUnit {
        COPPER("copper", CurrencyService.COPPER),
        SILVERMARK("silvermark", CurrencyService.SILVERMARK),
        CROWN("crown", CurrencyService.CROWN);

        private final String id;
        private final long value;

        CurrencyUnit(String id, long value) {
            this.id = id;
            this.value = value;
        }

        public String id() {
            return id;
        }

        public long value() {
            return value;
        }
    }

    public record LegacyCoinConversion(int copperCount,
                                       int ironCount,
                                       int goldCount,
                                       long addedAmount,
                                       long newBalance) {
        public boolean isEmpty() {
            return addedAmount <= 0L;
        }
    }

    /** F7b: physical coins minted for a withdrawal — gold/silver/copper item counts. */
    public record CoinMix(long crowns, long silvermarks, long coppers) {

        /** The base-copper value of the mix — always equals the withdrawn amount. */
        public long totalValue() {
            return crowns * CROWN + silvermarks * SILVERMARK + coppers * COPPER;
        }

        public boolean isEmpty() {
            return crowns <= 0L && silvermarks <= 0L && coppers <= 0L;
        }
    }
}
