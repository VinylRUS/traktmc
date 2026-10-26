package de.trakt.reward;

import de.trakt.data.*;
import de.trakt.economy.CurrencyService;
import de.trakt.network.RewardPayloads;
import de.trakt.quest.repeatable.RepeatableTargetProfile;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One authoritative path for acceptance snapshots, extra currency and lossless item escrow. */
public final class VanillaRewardService {
    private record Pending(ServerLevel world, String identity, boolean admin, ServerLevel location, Vec3 position, long expires) { }
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final RewardChoiceSessions SESSIONS = new RewardChoiceSessions();
    public static PlayerRewardState state(ServerLevel world, UUID player) { return QuestState.get(world.getServer()).getPlayerData(player).vanillaRewards(); }
    public static String dailyIdentity(String type) { return type + ":" + de.trakt.util.TimeUtil.currentDay(); }
    public static String weeklyIdentity(String type) { return type + ":" + de.trakt.util.TimeUtil.currentWeekCycle(); }
    public static boolean prepare(ServerLevel world, ServerPlayer player, String slot, String key, String identity,
                                  RepeatableTargetProfile profile, Runnable resume) {
        return prepareWithOptions(world, player, slot, key, identity, VanillaRewardRegistry.options(key, profile));
    }

    /** Shared snapshot path for every acceptance-time reward reservation. */
    private static boolean prepareWithOptions(ServerLevel world, ServerPlayer player, String slot, String key,
                                              String identity, List<VanillaRewardRegistry.Option> all) {
        if (all.isEmpty()) {
            state(world,player.getUUID()).forget(slot); QuestState.get(world.getServer()).setDirty(); return true;
        }
        var state = state(world, player.getUUID());
        var existing = state.snapshot(slot, identity);
        if (!existing.isEmpty()) return state.writable() && !existing.getBooleanOr("redeemed", true);
        if (!state.canAccept()) { player.sendSystemMessage(Component.translatable(state.choiceCount() >= PlayerRewardState.MAX_OPEN_CHOICES
                ? "reward.trakt.backlog_full" : "reward.trakt.unavailable", PlayerRewardState.MAX_OPEN_CHOICES), false); return false; }
        var snapshot = new CompoundTag(); snapshot.putString("key", key); snapshot.putInt("version", 1);
        var choices = new ListTag();
        for (var option : all) {
            if (option.diamondGate() && !PlayerProgressionSignals.diamonds(player)) continue;
            var choice = new CompoundTag(); choice.putString("option", option.id()); choice.putLong("currency", option.currency());
            choice.put("items", RewardItems.encode(world, RewardItems.create(world, option.goods())));
            choice.put("summaryComponent", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, summary(option)).getOrThrow());
            var lines = new ListTag();
            for (var line : describe(option)) lines.add(ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, line).getOrThrow());
            choice.put("lines", lines); choices.add(choice);
        }
        snapshot.put("choices", choices);
        boolean accepted = state.accept(slot, identity, snapshot);
        if (accepted) QuestState.get(world.getServer()).setDirty();
        return accepted;
    }

    /**
     * Acceptance-time snapshot for a custom (panel) quest whose rewards are a
     * choice_of entry (F2a): the options come from the quest definition instead of
     * the built-in reward registry. Same slot mechanics as the daily flow — the
     * player picks an option at claim time through the reward-choice UI.
     */
    public static boolean prepareCustom(ServerLevel world, ServerPlayer player, String questId,
                                        de.trakt.quest.definition.QuestReward.ChoiceOf choice, String identity) {
        java.util.ArrayList<VanillaRewardRegistry.Option> options = new java.util.ArrayList<>();
        for (var option : choice.options()) {
            java.util.ArrayList<VanillaRewardRegistry.Goods> goods = new java.util.ArrayList<>();
            for (var item : option.items()) {
                goods.add(new VanillaRewardRegistry.Goods(item.item(), item.count(), "", java.util.Map.of()));
            }
            options.add(new VanillaRewardRegistry.Option(option.id(),
                    de.trakt.economy.CurrencyService.silver(option.silvermarks()),
                    List.copyOf(goods), "diamonds".equals(option.gate())));
        }
        return prepareWithOptions(world, player, "custom", "custom:" + questId, identity, options);
    }
    private static void openEarned(ServerLevel world, ServerPlayer player) { openEarned(world,player,null); }
    private static void openEarned(ServerLevel world, ServerPlayer player, String instance) {
        var state = state(world, player.getUUID());
        if (!state.writable() || !player.isAlive() || player.isSpectator() || state.earned().isEmpty()) return;
        var offer = instance == null ? (CompoundTag) state.earned().getFirst() : state.earned().stream()
                .map(raw -> (CompoundTag)raw).filter(saved -> saved.getStringOr("instance", "").equals(instance)).findFirst().orElse(null);
        if (offer == null) return;
        boolean admin = offer.getStringOr("slot", "").equals("admin_reward_test");
        if (admin && !world.getServer().getPlayerList().isOp(player.nameAndId())) return;
        var choices = new ArrayList<RewardPayloads.Choice>();
        for (var entry : offer.getListOrEmpty("choices")) {
            var choice = (CompoundTag) entry; var lines = new ArrayList<Component>();
            for (var line : choice.getListOrEmpty("lines"))
                lines.add(ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, line).getOrThrow());
            var items = new ArrayList<ItemStack>();
            for (var item : choice.getListOrEmpty("items")) items.add(RewardItems.decode(world, item));
            choices.add(new RewardPayloads.Choice(choice.getStringOr("option", ""), List.copyOf(lines), choice.getLongOr("currency", 0), items));
        }
        String identity = offer.getStringOr("instance", "");
        var ticket = SESSIONS.open(player.getUUID(), identity, choices.stream().map(RewardPayloads.Choice::id).collect(java.util.stream.Collectors.toSet()), world.getGameTime());
        PENDING.put(player.getUUID(), new Pending(world, identity, admin, player.level(), player.position(), ticket.expires()));
        String preferred = state.preference().isEmpty() ? PlayerProgressionSignals.recommendation(player) : state.preference();
        if (!ticket.options().contains(preferred)) preferred = choices.getFirst().id();
        ServerPlayNetworking.send(player, new RewardPayloads.View(ticket.token(), offer.getStringOr("key", ""), List.copyOf(choices), preferred));
    }
    public static void handle(ServerPlayer player, RewardPayloads.Action action) {
        var pending = PENDING.get(player.getUUID());
        if (pending == null || !player.isAlive() || player.isSpectator() || player.level() != pending.location()
                || player.position().distanceToSqr(pending.position()) > 144 || pending.world().getServer() != player.level().getServer()
                || (pending.admin() && !pending.world().getServer().getPlayerList().isOp(player.nameAndId()))) return;
        if (!SESSIONS.consume(player.getUUID(), action.session(), pending.identity(), action.option(), pending.world().getGameTime())) return;
        PENDING.remove(player.getUUID());
        var state = state(pending.world(), player.getUUID());
        var choice = state.takeChoice(pending.identity(), action.option());
        if (choice.isEmpty()) return;
        state.preference(action.option()); pay(pending.world(), player, choice);
        if (de.trakt.questmaster.QuestMasterUiService.isOpen(player.getUUID()))
            de.trakt.questmaster.QuestMasterUiService.refreshIfOpen(pending.world(),player);
        // The client closes the submitted screen; further earned choices reopen through /trakt rewards claim.
    }
    public static void onTick(MinecraftServer server) {
        PENDING.entrySet().removeIf(e -> {
            var pending = e.getValue();
            boolean remove = pending.world().getServer() != server || server.getPlayerList().getPlayer(e.getKey()) == null || pending.world().getGameTime() > pending.expires();
            if (remove) SESSIONS.clear(e.getKey()); return remove;
        });
    }
    public static void complete(ServerLevel world, ServerPlayer player, String slot, String identity) {
        var state = state(world, player.getUUID()); var snapshot = state.snapshot(slot, identity);
        if (!state.writable() || snapshot.isEmpty() || snapshot.getBooleanOr("redeemed", true)) return;
        if (snapshot.contains("choices")) {
            if (state.earn(slot, identity)) {
                QuestState.get(world.getServer()).setDirty();
                player.sendSystemMessage(Component.translatable("reward.trakt.choice_pending")
                        .withStyle(style -> style.withColor(ChatFormatting.GOLD).withClickEvent(new ClickEvent.RunCommand("/trakt rewards claim"))), false);
                openEarned(world, player);
            }
            return;
        }
        snapshot = state.redeem(slot, identity);
        if (!snapshot.isEmpty()) pay(world, player, snapshot);
    }
    private static void pay(ServerLevel world, ServerPlayer player, CompoundTag snapshot) {
        var state = state(world, player.getUUID()); var pending = state.pending(); var items = snapshot.getListOrEmpty("items");
        if (pending.size() + items.size() > PlayerRewardState.MAX_PENDING) throw new IllegalStateException("Reward queue reservation exceeded");
        for (var item : items) pending.add(item.copy());
        state.pending(pending); QuestState.get(world.getServer()).setDirty();
        long amount = snapshot.getLongOr("currency", 0);
        if (amount > 0) CurrencyService.addBalance(world, player.getUUID(), amount);
        player.sendSystemMessage(Component.translatable("reward.trakt.earned", snapshotSummary(snapshot)).withStyle(ChatFormatting.GOLD), false);
        deliverPending(world, player);
    }
    public static void claimChoice(ServerLevel world, ServerPlayer player, String instance) {
        if (instance == null || instance.isEmpty()) return;
        openEarned(world,player,instance);
    }
    public static int claim(ServerLevel world, ServerPlayer player) {
        int delivered = deliverPending(world, player); openEarned(world, player); return delivered;
    }
    private static int deliverPending(ServerLevel world, ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return 0;
        var state = state(world, player.getUUID()); if (!state.writable()) return 0;
        var pending = state.pending(); var remaining = new ListTag(); int delivered = 0;
        for (var raw : pending) {
            try {
                var stack = RewardItems.decode(world, raw); int before = stack.getCount();
                player.getInventory().add(stack); delivered += before - stack.getCount();
                if (!stack.isEmpty()) remaining.add(RewardItems.encode(world, List.of(stack)).getFirst());
            } catch (RuntimeException unavailableComponent) { remaining.add(raw.copy()); }
        }
        state.pending(remaining); QuestState.get(world.getServer()).setDirty();
        if (!remaining.isEmpty()) player.sendSystemMessage(Component.translatable("reward.trakt.pending", remaining.size())
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW).withClickEvent(new ClickEvent.RunCommand("/trakt rewards claim"))), false);
        return delivered;
    }
    public static void firstBonus(ServerLevel world, ServerPlayer player, String key) {
        var state = state(world, player.getUUID());
        if (state.first(key)) { CurrencyService.addBalance(world, player.getUUID(), CurrencyService.silver(8L)); QuestState.get(world.getServer()).setDirty();
            player.sendSystemMessage(Component.translatable("reward.trakt.first_bonus", CurrencyService.formatBalance(CurrencyService.silver(8L))), false); }
    }
    public static boolean enqueue(ServerLevel world, ServerPlayer player, List<ItemStack> items) {
        var state = state(world, player.getUUID()); if (!state.writable()) return false;
        var pending = state.pending(); var encoded = RewardItems.encode(world, items);
        if (!state.canEnqueue(encoded.size())) return false;
        for (var item : encoded) pending.add(item.copy());
        state.pending(pending); QuestState.get(world.getServer()).setDirty(); deliverPending(world,player); return true;
    }
    public static List<Component> withSaved(ServerLevel world, UUID player, String slot, List<Component> lines) {
        var result = new ArrayList<>(lines); var summary = savedSummary(world, player, slot, state(world,player).currentIdentity(slot));
        if (!summary.getString().isEmpty()) result.add(summary);
        return List.copyOf(result);
    }
    public static Component joinLines(List<Component> lines) {
        var result = Component.empty(); boolean first = true;
        for (var line : lines) { if (!first) result.append("\n"); result.append(line); first = false; } return result;
    }
    public static List<Component> describe(VanillaRewardRegistry.Option option) {
        List<Component> lines = new ArrayList<>();
        if (option.currency() > 0) lines.add(CurrencyService.formatBalance(option.currency()));
        for (var goods : option.goods()) {
            String key = goods.potion().isEmpty() ? (goods.item().equals("glass") || goods.item().equals("lantern") || goods.item().equals("rail") || goods.item().equals("powered_rail") || goods.item().equals("bookshelf") ? "block" : "item") + ".minecraft." + goods.item()
                    : "item.minecraft.potion.effect." + (goods.potion().contains("healing") ? "healing" : "fire_resistance");
            MutableComponent line = Component.literal(goods.count() + " × ").append(Component.translatable(key));
            if (!goods.potion().isEmpty()) line.append(Component.literal(goods.potion().equals("strong_healing") ? " II" : " (8:00)"));
            lines.add(line);
            for (var enchantment : new TreeMap<>(goods.enchantments()).entrySet()) lines.add(Component.translatable("enchantment.minecraft." + enchantment.getKey()).append(" ").append(Component.translatable("enchantment.level." + enchantment.getValue())));
        }
        return List.copyOf(lines);
    }
    public static Component summary(VanillaRewardRegistry.Option option) {
        var result = Component.empty(); for (var line : describe(option)) { if (!result.getString().isEmpty()) result.append(", "); result.append(line); } return result;
    }
    public static Component savedSummary(ServerLevel world, UUID player, String slot, String identity) {
        var snapshot = state(world, player).snapshot(slot, identity);
        return snapshot.isEmpty() || snapshot.getBooleanOr("redeemed", true) ? Component.empty() : snapshot.contains("choices") ? Component.translatable("reward.trakt.after_turn_in") : Component.translatable("reward.trakt.extra", snapshotSummary(snapshot));
    }
    private static Component snapshotSummary(CompoundTag snapshot) {
        var raw = snapshot.get("summaryComponent");
        return raw == null ? Component.literal(snapshot.getStringOr("summary", "")) : ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, raw).result().orElse(Component.empty());
    }
    public static void completeCurrent(ServerLevel world, ServerPlayer player, String slot) {
        complete(world, player, slot, state(world, player.getUUID()).currentIdentity(slot));
    }
    private VanillaRewardService() { }
}
