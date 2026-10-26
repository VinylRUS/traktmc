package de.trakt.network;

import de.trakt.caravan.TradeRouteService;
import de.trakt.caravan.RegionalDispatchService;
import de.trakt.config.ClientPreferenceService;
import de.trakt.economy.ProsperityService;
import de.trakt.pilgrim.PilgrimService;
import de.trakt.quest.QuestBookHelper;
import de.trakt.quest.QuestTrackerService;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.weekly.WeeklyQuestService;
import de.trakt.questmaster.QuestMasterUiService;
import de.trakt.shrine.VillageBondService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class QuestNetworking {
    private QuestNetworking() {}

    public static void register() {
        Payloads.register();
        ServerPlayNetworking.registerGlobalReceiver(RewardPayloads.Action.ID, (payload, context) ->
                context.server().execute(() -> de.trakt.reward.VanillaRewardService.handle(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ReputationPayloads.PagePayload.ID, (payload, context) ->
                context.server().execute(() -> de.trakt.reputation.ReputationInteractionService.page(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ReputationPayloads.OpenPayload.ID, (payload, context) ->
                context.server().execute(() -> de.trakt.reputation.ReputationInteractionService.open(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ReputationPayloads.ActionPayload.ID, (payload, context) ->
                context.server().execute(() -> de.trakt.reputation.ReputationInteractionService.handle(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(Payloads.JournalActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                ServerLevel world = (ServerLevel) player.level();
                if (payload.action() == Payloads.JournalActionPayload.ACTION_CANCEL_DAILY) {
                    if (DailyQuestService.isDailyActive(world, player.getUUID())) {
                        DailyQuestService.cancelToday(world, player.getUUID());
                        QuestBookHelper.refreshQuestBook(world, player);
                    }
                } else if (payload.action() == Payloads.JournalActionPayload.ACTION_CANCEL_WEEKLY) {
                    if (WeeklyQuestService.isWeeklyActive(world, player.getUUID())) {
                        WeeklyQuestService.cancelThisWeek(world, player.getUUID());
                        QuestBookHelper.refreshQuestBook(world, player);
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.PilgrimTradeActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> PilgrimService.handleTradeAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.PilgrimTradeSessionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> PilgrimService.handleTradeSession(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.EconomyActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> ProsperityService.handleAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.QuestMasterActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> QuestMasterUiService.handleAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.QuestMasterPartyActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> QuestMasterUiService.handlePartyAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.QuestMasterSessionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> QuestMasterUiService.handleSession(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.TradeRouteActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> TradeRouteService.handleMapAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.WayshrineTravelPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> VillageBondService.handleTravel(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.WayshrineRenamePayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> VillageBondService.handleRename(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.NoticeBoardActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> VillageBondService.handleNoticeBoardAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.NoticeJourneyActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> VillageBondService.handleNoticeJourneyAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.RegionalDispatchActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> RegionalDispatchService.handleAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillageNetworkPayloads.CaravanMasterActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> TradeRouteService.handleMasterAction(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.ClientPreferencesPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> {
                ClientPreferenceService.update(player, payload);
                QuestTrackerService.applyClientDefault(
                        context.server().overworld(), player, payload.questTrackerEnabledByDefault());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.QuestTrackerActionPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> QuestTrackerService.toggle(context.server().overworld(), player));
        });
    }
}
