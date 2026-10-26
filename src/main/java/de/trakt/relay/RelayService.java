package de.trakt.relay;

import com.google.gson.JsonObject;
import de.trakt.api.ApiService;
import de.trakt.caravan.TradeRouteService;
import de.trakt.caravan.TradeRouteStatus;
import de.trakt.config.TraktServerConfig;
import de.trakt.economy.CurrencyService;
import de.trakt.util.TimeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/**
 * Relay orchestration (F3b): terminal interaction, drafts, dispatch, delivery,
 * returns and timeouts. See RELAY_DESIGN.md — state lives in RelayLedger (global
 * SavedData) and RelayTerminalRegistry; this class owns no mutable state.
 */
public final class RelayService {
    public static final int CLAIM_DISTANCE = 8;
    public static final int ARRIVAL_TOLERANCE = 48;
    public static final int TERMINAL_INTERACTION_RADIUS = 8;

    private static final long CLAIM_DISTANCE_SQR = (long) CLAIM_DISTANCE * CLAIM_DISTANCE;
    private static final long ARRIVAL_TOLERANCE_SQR = (long) ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE;
    private static final int SWEEP_INTERVAL_TICKS = 100;
    private static long sweepTick;

    private RelayService() {}

    // ------------------------------------------------------------------ terminal lifecycle

    /** Placement hook: binds the block to the placer, enforcing the per-player cap. */
    public static void onTerminalPlaced(ServerLevel world, ServerPlayer player, BlockPos pos) {
        RelayTerminalRegistry registry = RelayTerminalRegistry.get(world.getServer());
        if (!registry.register(world, player, pos, (int) TimeUtil.currentDay())) {
            world.destroyBlock(pos, true);
            player.sendSystemMessage(Component.translatable("message.trakt.relay.terminal.limit")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        player.sendSystemMessage(Component.translatable("message.trakt.relay.terminal.placed")
                .withStyle(ChatFormatting.GREEN), false);
    }

    /** Removal hook: frees the slot and bounces cargo that referenced this terminal. */
    public static void onTerminalRemoved(ServerLevel world, BlockPos pos) {
        RelayTerminalRegistry registry = RelayTerminalRegistry.get(world.getServer());
        String dimension = RelayKeys.dimensionKey(world);
        registry.unregister(dimension, pos);
        RelayLedger ledger = RelayLedger.get(world.getServer());
        List<RelayShipment> changed = ledger.onTerminalRemoved(dimension, pos, TimeUtil.currentDay());
        notifyChanged(world, changed);
    }

    /** The nearest registered relay terminal of this dimension within the interaction radius. */
    public static BlockPos nearbyRegisteredTerminal(ServerLevel world, BlockPos pos) {
        List<RelayTerminalRegistry.Terminal> nearby = RelayTerminalRegistry.get(world.getServer())
                .nearby(RelayKeys.dimensionKey(world), pos, TERMINAL_INTERACTION_RADIUS);
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (RelayTerminalRegistry.Terminal terminal : nearby) {
            long distance = RelayKeys.horizontalDistanceSqr(terminal.pos(), pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = terminal.pos();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ terminal use

    /** Plain use: claim everything waiting at this terminal, otherwise show the status summary. */
    public static InteractionResult useTerminal(ServerLevel world, ServerPlayer player, BlockPos pos) {
        if (RelayTerminalRegistry.get(world.getServer()).at(RelayKeys.dimensionKey(world), pos).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (tryClaim(world, player, pos)) {
            return InteractionResult.SUCCESS;
        }
        sendTerminalSummary(world, player, pos);
        return InteractionResult.SUCCESS;
    }

    /** Sneak-use with a stack: deposit the whole held stack into the terminal draft. */
    public static InteractionResult depositToDraft(ServerLevel world, ServerPlayer player,
                                                   BlockPos pos, InteractionHand hand) {
        var registryAt = RelayTerminalRegistry.get(world.getServer())
                .at(RelayKeys.dimensionKey(world), pos);
        if (registryAt.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!registryAt.get().owner().equals(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.terminal.not_yours")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.FAIL;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            return InteractionResult.PASS;
        }
        String dimension = RelayKeys.dimensionKey(world);
        RelayLedger ledger = RelayLedger.get(world.getServer());
        RelayShipment draft = ledger.draftFor(player.getUUID()).orElse(null);
        if (draft == null) {
            draft = new RelayShipment(UUID.randomUUID(), player.getUUID(),
                    player.getGameProfile().name(), dimension, pos.immutable());
            draft.updateSenderName(player.getGameProfile().name());
            ledger.addDraft(draft);
        } else if (!RelayKeys.terminalKey(draft.fromDimension(), draft.fromPos())
                .equals(RelayKeys.terminalKey(dimension, pos))) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.draft.wrong_terminal",
                    draft.fromPos().getX(), draft.fromPos().getY(), draft.fromPos().getZ())
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.FAIL;
        }
        List<ItemStack> slots = RelayItems.decode(world.registryAccess(), draft.items());
        if (!RelayItems.canInsert(slots, held, RelayLedger.MAX_STACKS_PER_SHIPMENT)) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.deposit.full")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.FAIL;
        }
        RelayItems.insert(slots, held, RelayLedger.MAX_STACKS_PER_SHIPMENT);
        player.setItemInHand(hand, ItemStack.EMPTY);
        draft.updateItems(RelayItems.encode(world.registryAccess(), slots));
        ledger.markDirtyExternally();
        player.sendSystemMessage(Component.translatable("message.trakt.relay.deposit.success",
                RelayItems.summarize(slots), slots.size(), RelayLedger.MAX_STACKS_PER_SHIPMENT)
                .withStyle(ChatFormatting.GREEN), false);
        world.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.PLAYERS, 0.7f, 1.0f);
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ dispatch

    /** /trakt relay send <player>: quote, charge and dispatch the open draft. */
    public static boolean send(ServerLevel world, ServerPlayer player, String targetName) {
        RelayLedger ledger = RelayLedger.get(world.getServer());
        RelayShipment draft = ledger.draftFor(player.getUUID()).orElse(null);
        if (draft == null || draft.items().isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.no_draft")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        String dimension = RelayKeys.dimensionKey(world);
        if (!draft.fromDimension().equals(dimension)
                || RelayKeys.horizontalDistanceSqr(draft.fromPos(), player.blockPosition())
                > CLAIM_DISTANCE_SQR) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.wrong_spot",
                            draft.fromPos().getX(), draft.fromPos().getY(), draft.fromPos().getZ())
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        Target target = resolveTarget(world, targetName);
        if (target == null) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.player_not_found",
                    targetName).withStyle(ChatFormatting.RED), false);
            return false;
        }
        List<RelayTerminalRegistry.Terminal> terminals =
                RelayTerminalRegistry.get(world.getServer()).ownedBy(target.uuid());
        if (terminals.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.no_terminal",
                    target.name()).withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (ledger.activeFor(player.getUUID()) >= RelayLedger.MAX_ACTIVE_PER_SENDER) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.quota",
                    RelayLedger.MAX_ACTIVE_PER_SENDER).withStyle(ChatFormatting.RED), false);
            return false;
        }
        RouteMatch match = findRouteToTerminal(world, player.getUUID(), terminals);
        if (match == null) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.no_route",
                    target.name()).withStyle(ChatFormatting.RED), false);
            return false;
        }
        long tariff = RelayTariff.compute(
                TradeRouteService.routeEconomyDistanceBlocks(world, player.getUUID(), match.routeIndex()),
                match.dangerous(),
                TraktServerConfig.get().adventureProfile()
                        == TraktServerConfig.AdventureProfile.HARDENED);
        if (!CurrencyService.removeBalance(world, player.getUUID(), tariff)) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.send.poor",
                    CurrencyService.formatBalance(tariff)).withStyle(ChatFormatting.RED), false);
            return false;
        }
        long day = TimeUtil.currentDay();
        draft.updateSenderName(player.getGameProfile().name());
        draft.depart(target.uuid(), target.name(), match.dimension(), match.terminal(),
                match.routeIndex(), match.routeEndIsDestination(), day, tariff);
        ledger.markDirtyExternally();
        List<ItemStack> items = RelayItems.decode(world.registryAccess(), draft.items());
        player.sendSystemMessage(Component.translatable("message.trakt.relay.send.success",
                        target.name(), CurrencyService.formatBalance(tariff))
                .withStyle(ChatFormatting.GREEN), false);
        world.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_LEATHERWORKER,
                SoundSource.PLAYERS, 0.8f, 1.0f);
        emit(world, "relay_sent", player, payload -> {
            payload.addProperty("recipient", target.name());
            payload.addProperty("items", RelayItems.summarize(items));
            payload.addProperty("tariff_paid", tariff);
        });
        ServerPlayer recipient = world.getServer().getPlayerList().getPlayer(target.uuid());
        if (recipient != null) {
            recipient.sendSystemMessage(Component.translatable(
                            "message.trakt.relay.notify.in_transit",
                            player.getGameProfile().name())
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return true;
    }

    /** /trakt relay cancel: hand the draft back, or recall a shipment that has not arrived yet. */
    public static boolean cancel(ServerLevel world, ServerPlayer player) {
        RelayLedger ledger = RelayLedger.get(world.getServer());
        RelayShipment draft = ledger.draftFor(player.getUUID()).orElse(null);
        if (draft != null) {
            giveItems(world, player, RelayItems.decode(world.registryAccess(), draft.items()));
            ledger.removeDraft(player.getUUID());
            player.sendSystemMessage(Component.translatable("message.trakt.relay.cancel.draft")
                    .withStyle(ChatFormatting.GREEN), false);
            return true;
        }
        long day = TimeUtil.currentDay();
        boolean recalled = false;
        for (RelayShipment shipment : ledger.view()) {
            if (shipment.senderUuid().equals(player.getUUID())
                    && shipment.stage() == RelayStage.IN_TRANSIT) {
                shipment.changeStage(RelayStage.RETURN_IN_TRANSIT, day, "cancelled");
                recalled = true;
            }
        }
        if (recalled) {
            ledger.markDirtyExternally();
            player.sendSystemMessage(Component.translatable("message.trakt.relay.cancel.in_transit")
                    .withStyle(ChatFormatting.YELLOW), false);
        } else {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.cancel.nothing")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return recalled;
    }

    /** /trakt relay status: everything this player sends, awaits or can pick up. */
    public static void sendStatus(ServerLevel world, ServerPlayer player) {
        RelayLedger ledger = RelayLedger.get(world.getServer());
        UUID uuid = player.getUUID();
        player.sendSystemMessage(Component.translatable("message.trakt.relay.status.header")
                .withStyle(ChatFormatting.GOLD), false);
        boolean any = false;
        for (RelayShipment shipment : ledger.view()) {
            List<ItemStack> items = RelayItems.decode(world.registryAccess(), shipment.items());
            switch (shipment.stage()) {
                case DRAFT -> {
                    if (shipment.senderUuid().equals(uuid)) {
                        any = true;
                        player.sendSystemMessage(Component.translatable(
                                "message.trakt.relay.status.draft",
                                RelayItems.summarize(items)), false);
                    }
                }
                case IN_TRANSIT -> {
                    if (shipment.senderUuid().equals(uuid)) {
                        any = true;
                        player.sendSystemMessage(Component.translatable(
                                "message.trakt.relay.status.in_transit",
                                shipment.recipientName(), RelayItems.summarize(items)), false);
                    }
                }
                case HELD_AT_DESTINATION -> {
                    if (shipment.recipientUuid() != null && shipment.recipientUuid().equals(uuid)) {
                        any = true;
                        player.sendSystemMessage(Component.translatable(
                                "message.trakt.relay.status.incoming",
                                shipment.senderName(), RelayItems.summarize(items)), false);
                    }
                }
                case RETURN_IN_TRANSIT, HELD_AT_SOURCE -> {
                    if (shipment.senderUuid().equals(uuid)) {
                        any = true;
                        player.sendSystemMessage(Component.translatable(
                                "message.trakt.relay.status.return",
                                RelayItems.summarize(items)), false);
                    }
                }
                default -> { }
            }
        }
        if (!any) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.status.empty")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    // ------------------------------------------------------------------ caravan hooks

    /**
     * Arrival hook wired from TradeRouteService.onDispatchArrival: a shipment in transit
     * lands at the recipient's terminal when the route reaches the matching end;
     * a return simply rides along with any arrival.
     */
    public static void onRouteArrival(ServerLevel world, UUID ownerId, int routeIndex,
                                      boolean atDestinationEnd, BlockPos endpointPos) {
        RelayLedger ledger = RelayLedger.get(world.getServer());
        long day = TimeUtil.currentDay();
        boolean changed = false;
        for (RelayShipment shipment : ledger.view()) {
            if (!shipment.senderUuid().equals(ownerId) || shipment.routeIndex() != routeIndex) {
                continue;
            }
            if (shipment.stage() == RelayStage.IN_TRANSIT) {
                if (arrivesAt(shipment, atDestinationEnd, endpointPos)) {
                    shipment.changeStage(RelayStage.HELD_AT_DESTINATION, day, null);
                    changed = true;
                    ServerPlayer recipient = world.getServer().getPlayerList().getPlayer(shipment.recipientUuid());
                    if (recipient != null) {
                        recipient.sendSystemMessage(Component.translatable(
                                        "message.trakt.relay.notify.held", shipment.senderName())
                                .withStyle(ChatFormatting.GOLD), false);
                    }
                }
            } else if (shipment.stage() == RelayStage.RETURN_IN_TRANSIT) {
                shipment.changeStage(RelayStage.HELD_AT_SOURCE, day, null);
                changed = true;
                ServerPlayer sender = world.getServer().getPlayerList().getPlayer(ownerId);
                if (sender != null) {
                    sender.sendSystemMessage(Component.translatable(
                                    "message.trakt.relay.notify.return")
                            .withStyle(ChatFormatting.GOLD), false);
                }
            }
        }
        if (changed) {
            ledger.markDirtyExternally();
        }
    }

    /** Route removal hook: cargo on the removed route returns to the sender's terminal slot. */
    public static void onRouteRemoved(ServerLevel world, UUID ownerId, int routeIndex) {
        RelayLedger ledger = RelayLedger.get(world.getServer());
        notifyChanged(world, ledger.onRouteRemoved(ownerId, routeIndex, TimeUtil.currentDay()));
    }

    public static void onServerTick(MinecraftServer server) {
        if ((sweepTick++ % SWEEP_INTERVAL_TICKS) != 0) {
            return;
        }
        RelayLedger ledger = RelayLedger.get(server);
        List<RelayShipment> changed = ledger.sweepTimeouts(TimeUtil.currentDay());
        if (!changed.isEmpty()) {
            for (RelayShipment shipment : changed) {
                ServerPlayer sender = server.getPlayerList().getPlayer(shipment.senderUuid());
                if (sender != null) {
                    sender.sendSystemMessage(Component.translatable(
                                    "message.trakt.relay.notify.timeout")
                            .withStyle(ChatFormatting.YELLOW), false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private static boolean tryClaim(ServerLevel world, ServerPlayer player, BlockPos pos) {
        String dimension = RelayKeys.dimensionKey(world);
        RelayLedger ledger = RelayLedger.get(world.getServer());
        boolean claimed = false;
        for (RelayShipment shipment : ledger.heldAtDestination(player.getUUID(), dimension, pos)) {
            claimed |= handOut(world, player, shipment, RelayStage.DELIVERED,
                    "message.trakt.relay.claim.delivered", shipment.senderName());
            if (shipment.stage() == RelayStage.HELD_AT_DESTINATION) {
                break; // inventory filled up mid-claim
            }
        }
        for (RelayShipment shipment : ledger.heldAtSource(player.getUUID(), dimension, pos)) {
            claimed |= handOut(world, player, shipment, RelayStage.RETURNED,
                    "message.trakt.relay.claim.returned", shipment.recipientName());
            if (shipment.stage() == RelayStage.HELD_AT_SOURCE) {
                break;
            }
        }
        if (claimed) {
            world.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 1.4f);
        }
        return claimed;
    }

    private static boolean handOut(ServerLevel world, ServerPlayer player, RelayShipment shipment,
                                   RelayStage done, String messageKey, String counterpartName) {
        List<ItemStack> items = RelayItems.decode(world.registryAccess(), shipment.items());
        List<ItemStack> remaining = new ArrayList<>();
        boolean overflow = false;
        for (ItemStack stack : items) {
            if (overflow) {
                remaining.add(stack);
                continue;
            }
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                overflow = true;
                remaining.add(stack);
            }
        }
        RelayLedger ledger = RelayLedger.get(world.getServer());
        if (overflow) {
            shipment.updateItems(RelayItems.encode(world.registryAccess(), remaining));
            ledger.markDirtyExternally();
            player.sendSystemMessage(Component.translatable("message.trakt.relay.claim.inventory_full")
                    .withStyle(ChatFormatting.RED), false);
            return true;
        }
        shipment.changeStage(done, TimeUtil.currentDay(), null);
        ledger.markDirtyExternally();
        player.sendSystemMessage(Component.translatable(messageKey, counterpartName,
                RelayItems.summarize(items)).withStyle(ChatFormatting.GREEN), false);
        if (done == RelayStage.DELIVERED) {
            emit(world, "relay_delivered", player, payload -> {
                payload.addProperty("sender", shipment.senderName());
                payload.addProperty("items", RelayItems.summarize(items));
                payload.addProperty("travel_days", TimeUtil.currentDay() - shipment.createdDay());
            });
        } else {
            emit(world, "relay_returned", player, payload -> {
                payload.addProperty("reason", shipment.returnReason());
                payload.addProperty("items", RelayItems.summarize(items));
            });
        }
        return true;
    }

    private static void sendTerminalSummary(ServerLevel world, ServerPlayer player, BlockPos pos) {
        String dimension = RelayKeys.dimensionKey(world);
        RelayLedger ledger = RelayLedger.get(world.getServer());
        long incoming = ledger.heldAtDestination(player.getUUID(), dimension, pos).size();
        long returns = ledger.heldAtSource(player.getUUID(), dimension, pos).size();
        RelayShipment draft = ledger.draftFor(player.getUUID()).orElse(null);
        boolean ownsDraftHere = draft != null && draft.fromDimension().equals(dimension)
                && draft.fromPos().equals(pos);
        if (incoming == 0 && returns == 0 && !ownsDraftHere) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.claim.nothing")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        if (ownsDraftHere) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.status.draft",
                    RelayItems.summarize(RelayItems.decode(world.registryAccess(), draft.items()))), false);
        }
        if (incoming > 0) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.status.incoming_count",
                    incoming).withStyle(ChatFormatting.GOLD), false);
        }
        if (returns > 0) {
            player.sendSystemMessage(Component.translatable("message.trakt.relay.status.return_count",
                    returns).withStyle(ChatFormatting.GOLD), false);
        }
    }

    private static void giveItems(ServerLevel world, ServerPlayer player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false, Prediction.SERVER_ONLY);
            }
        }
    }

    /** Pure arrival matcher: the route reached the shipment's expected end near the target terminal. */
    static boolean arrivesAt(RelayShipment shipment, boolean atDestinationEnd, BlockPos endpointPos) {
        boolean matchingEnd = atDestinationEnd == shipment.routeEndIsDestination();
        return matchingEnd && shipment.toPos() != null && endpointPos != null
                && RelayKeys.horizontalDistanceSqr(endpointPos, shipment.toPos()) <= ARRIVAL_TOLERANCE_SQR;
    }

    private record Target(UUID uuid, String name) {}

    private static Target resolveTarget(ServerLevel world, String name) {
        ServerPlayer online = world.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new Target(online.getUUID(), online.getGameProfile().name());
        }
        return RelayTerminalRegistry.get(world.getServer()).resolveOwner(name)
                .map(terminal -> new Target(terminal.owner(), terminal.ownerName()))
                .orElse(null);
    }

    private record RouteMatch(int routeIndex, boolean routeEndIsDestination,
                              String dimension, BlockPos terminal, boolean dangerous) {}

    /** Finds the sender route whose destination or home end stops near one of the target terminals. */
    private static RouteMatch findRouteToTerminal(ServerLevel world, UUID sender,
                                                  List<RelayTerminalRegistry.Terminal> terminals) {
        int count = TradeRouteService.routeCount(world, sender);
        RouteMatch best = null;
        long bestDistance = Long.MAX_VALUE;
        for (RelayTerminalRegistry.Terminal terminal : terminals) {
            for (int i = 0; i < count; i++) {
                BlockPos dest = new BlockPos(TradeRouteService.routeDestinationX(world, sender, i), 64,
                        TradeRouteService.routeDestinationZ(world, sender, i));
                long distance = RelayKeys.horizontalDistanceSqr(dest, terminal.pos());
                if (distance <= ARRIVAL_TOLERANCE_SQR && distance < bestDistance) {
                    best = new RouteMatch(i, true, terminal.dimension(), terminal.pos(),
                            routeIsDangerous(world, sender, i));
                    bestDistance = distance;
                }
                BlockPos home = TradeRouteService.homePosition(world, sender);
                if (home != null) {
                    long homeDistance = RelayKeys.horizontalDistanceSqr(home, terminal.pos());
                    if (homeDistance <= ARRIVAL_TOLERANCE_SQR && homeDistance < bestDistance) {
                        best = new RouteMatch(i, false, terminal.dimension(), terminal.pos(),
                                routeIsDangerous(world, sender, i));
                        bestDistance = homeDistance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean routeIsDangerous(ServerLevel world, UUID sender, int routeIndex) {
        TradeRouteStatus status = TradeRouteService.routeStatus(world, sender, routeIndex);
        return status == TradeRouteStatus.DANGEROUS;
    }

    private static void notifyChanged(ServerLevel world, List<RelayShipment> changed) {
        for (RelayShipment shipment : changed) {
            ServerPlayer sender = world.getServer().getPlayerList().getPlayer(shipment.senderUuid());
            if (sender != null) {
                sender.sendSystemMessage(Component.translatable(
                                "message.trakt.relay.notify.return")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }
    }

    private static void emit(ServerLevel world, String type, ServerPlayer player,
                             java.util.function.Consumer<JsonObject> data) {
        JsonObject payload = new JsonObject();
        data.accept(payload);
        ApiService.emitEvent(world.getServer(), type, player.getUUID(),
                player.getGameProfile().name(), payload);
    }
}
