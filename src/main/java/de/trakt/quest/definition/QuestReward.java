package de.trakt.quest.definition;

import java.util.List;

/**
 * One typed reward entry of a quest (F2a). Replaces the fixed v1 rewards struct:
 * rewards are now a list of typed entries whose kinds live in the
 * {@link QuestRewardTypes} registry, so later stages (F2b lore items, third-party
 * addons) register new kinds without touching the payment code.
 *
 * <p>Two authored forms exist and both parse into this model:
 * <ul>
 *   <li><b>v1 object</b> — {@code rewards: {silvermarks, levels, reputation_track,
 *       reputation_amount, items: [...]}} plus the new exclusive
 *       {@code choice_of: [...]};</li>
 *   <li><b>v2 array</b> — {@code rewards: [{kind: "waycoins", amount: 6}, {kind:
 *       "item", item: "...", count: 3}, ...]}.</li>
 * </ul>
 *
 * <p>Monetary amounts are authored in <b>silver</b> units (the v1 panel contract
 * "silvermarks"): {@code amount: 6} pays six silver waycoins. The grant converts
 * through {@link de.trakt.economy.CurrencyService#silver(long)}.
 */
public sealed interface QuestReward {

    /** Direct payout: waycoins. {@code amount} is authored in silver units. */
    record Waycoins(long amount) implements QuestReward {}

    /** Direct payout: one item stack. */
    record ItemEntry(String item, int count) implements QuestReward {}

    /** Direct payout: experience levels. */
    record Levels(int amount) implements QuestReward {}

    /** Direct payout: reputation with a track. */
    record Reputation(String track, int amount) implements QuestReward {}

    /**
     * Deferred payout: the player picks one option when claiming the reward
     * (existing reward-choice UI, {@code /trakt rewards claim}). Options carry
     * silvermarks and up to two item stacks each.
     */
    record ChoiceOf(List<Option> options) implements QuestReward {
        public ChoiceOf {
            options = List.copyOf(options);
        }

        /**
         * One selectable bundle. {@code gate} optionally names a progression
         * signal that must be satisfied before the option is even offered —
         * the only gate today is {@code "diamonds"} (the player has found
         * diamonds). {@code null} means the option is always offered.
         */
        public record Option(String id, long silvermarks, List<ItemEntry> items, String gate) {
            public Option {
                items = List.copyOf(items);
                gate = gate == null || gate.isBlank() ? null : gate;
            }

            /** Ungated convenience factory (the common authored form). */
            public static Option of(String id, long silvermarks, List<ItemEntry> items) {
                return new Option(id, silvermarks, items, null);
            }
        }
    }
}
