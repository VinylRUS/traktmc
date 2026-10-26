package de.trakt.quest;

import de.trakt.archive.GuildArchiveService;
import de.trakt.config.ClientPreferenceService;
import de.trakt.caravan.TradeRouteService;
import de.trakt.content.story.ShadowsTradeRoadEncounterService;
import de.trakt.content.story.EmptyCaravanStoryService;
import de.trakt.content.story.ShrinesBetweenRoadsStoryArc;
import de.trakt.data.QuestState;
import de.trakt.party.QuestPartyService;
import de.trakt.painting.PaintingNameService;
import de.trakt.pilgrim.PilgrimContractService;
import de.trakt.pilgrim.PilgrimService;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.daily.FirstDailyChoiceService;
import de.trakt.quest.special.MerchantSealQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.special.SurveyorCompassQuestService;
import de.trakt.quest.story.StoryQuestService;
import de.trakt.quest.weekly.WeeklyQuestService;
import de.trakt.questmaster.QuestMasterUiService;
import de.trakt.reputation.ReputationService;
import de.trakt.recipe.TraktRecipeBookService;
import de.trakt.village.GuildCornerPlacementService;
import de.trakt.guildtown.GuildTownService;
import de.trakt.guild.VillageGuildState;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public final class QuestService {
    private QuestService() {}

    public static void registerEvents() {
        de.trakt.caravan.CaravanCrewLifecycle.activate();
        de.trakt.reputation.ReputationDamageAdapter.activate(TradeRouteService::socialVictim);
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof ServerLevel world) TradeRouteService.onCaravanDeath(world, entity);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            resetTransientRuntimeState();
            ShadowsTradeRoadEncounterService.despawnAll(server.overworld());
            TradeRouteService.despawnAll(server.overworld());
            EmptyCaravanStoryService.despawnAll(server.overworld());
            QuestState.get(server).applyToRuntime();
            de.trakt.reputation.ProtectedVillageIndex.primeHistorical(server);
            de.trakt.caravan.GuildConvoyService.reconcileTrust(server);
            de.trakt.reputation.ResettlementSupportService.reconcile(server);
            QuestPartyService.loadPersistentState(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ShadowsTradeRoadEncounterService.despawnAll(server.overworld());
            TradeRouteService.despawnAll(server.overworld());
            EmptyCaravanStoryService.despawnAll(server.overworld());
            QuestPartyService.persistRuntimeState(server);
            QuestState state = QuestState.get(server);
            state.updateFromRuntime();
            server.overworld().getDataStorage().saveAndJoin();
            resetTransientRuntimeState();
            PaintingNameService.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(QuestPartyService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(server -> QuestState.get(server).advanceSocialServerTick());
        ServerTickEvents.END_SERVER_TICK.register(QuestDropTracker::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(QuestHarvestTracker::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(DailyQuestService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(WeeklyQuestService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(StoryQuestService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(QuestAvailabilityNotifier::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(TradeRouteService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SpecialQuestService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(de.trakt.relay.RelayService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(PilgrimContractService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(PaintingNameService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(de.trakt.reputation.ReputationDamageAdapter::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(de.trakt.reputation.ReparationService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(de.trakt.reputation.ReputationInteractionService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(QuestBookHelper::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(QuestTrackerService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(PilgrimService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(TraktRecipeBookService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(GuildArchiveService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(GuildCornerPlacementService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(GuildTownService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(VillageGuildState::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    QuestState state = QuestState.get(server);
                    if (FirstDailyChoiceService.migratePre24Progress(
                            state.getPlayerData(handler.player.getUUID()))) {
                        state.setDirty();
                    }
                    QuestPartyService.handleJoin(handler.player);
                    QuestAvailabilityNotifier.handleJoin(handler.player);
                    TradeRouteService.backfillUnlockedLedger(server.overworld(), handler.player);
                    de.trakt.caravan.TradeGuildService.deliverPendingRefund(server.overworld(), handler.player);
                    ReputationService.backfillRoadwardenHorn(server.overworld(), handler.player);
                    GuildArchiveService.migrateInventoryOnJoin(server.overworld(), handler.player);
                    TraktRecipeBookService.unlockEligibleRecipes(handler.player);
                }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> handleDisconnect(handler.player)));

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                InteractionResult specialResult = SpecialQuestService.onUseBlock(sw, sp, hit.getBlockPos());
                if (specialResult != InteractionResult.PASS) {
                    return specialResult;
                }
                var pos = hit.getBlockPos();
                var state = world.getBlockState(pos);
                var stack = player.getItemInHand(hand);
                QuestHarvestTracker.onUseBlock(sw, sp, pos, state);
                StoryQuestService.onUseBlock(sw, sp, pos, state, stack);
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                boolean allowed = de.trakt.shrine.VillageBondService.canBreakWayshrine(sw, sp, pos, state)
                        && ShrinesBetweenRoadsStoryArc.canBreakActiveRuinMilestone(sw, sp, pos, state)
                        && SpecialQuestService.allowBlockBreak(sw, sp, pos);
                if (allowed) {
                    QuestHarvestTracker.onBlockBreakStart(sw, sp, pos, state);
                    QuestDropTracker.onBlockBreakStart(sw, sp, pos, state, blockEntity);
                }
                return allowed;
            }
            return true;
        });

        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                QuestHarvestTracker.onBlockBreakCanceled(sw, sp, pos);
                QuestDropTracker.onBlockBreakCanceled(sw, sp, pos);
            }
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                QuestState.get(sw.getServer()).markTerrainModified(pos);
                DailyQuestService.onBlockBreak(sw, sp, pos, state);
                StoryQuestService.onBlockBreak(sw, sp, pos, state);
                SpecialQuestService.onBlockBreak(sw, sp, pos, state);
                QuestHarvestTracker.onBlockBreakFinished(sw, sp, pos);
                QuestDropTracker.onBlockBreakFinished(sw, sp, pos);
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register(QuestDropTracker::onEntityLoad);
        ServerEntityEvents.ENTITY_LOAD.register(PaintingNameService::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(PaintingNameService::onEntityUnload);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newlyGenerated) ->
                de.trakt.reputation.ProtectedVillageIndex.onChunkLoad(world, chunk.getPos()));

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world instanceof ServerLevel sw && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                var stack = player.getItemInHand(hand);
                InteractionResult specialResult = SpecialQuestService.onUseEntity(sw, sp, hand, entity, stack);
                if (specialResult != InteractionResult.PASS) {
                    return specialResult;
                }
                QuestDropTracker.onEntityUse(sw, sp, entity, stack);
                InteractionResult routeResult = TradeRouteService.onEntityUse(sw, sp, entity);
                if (routeResult != InteractionResult.PASS) {
                    return routeResult;
                }
                DailyQuestService.onEntityUse(sw, sp, entity, stack);
                StoryQuestService.onEntityUse(sw, sp, entity, stack);
                PilgrimContractService.onEntityUse(sw, sp, entity, stack);
                de.trakt.guildtown.GuildTownService.onUseEntity(sw, sp, entity, stack);
                if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
                    de.trakt.shrine.VillageWelcomeService.onVillagerContact(sw, sp, villager);
                    de.trakt.shrine.VillageBondService.onVillagerContact(sw, sp, villager);
                }
            }
            return InteractionResult.PASS;
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity, damageSource) -> {
            if (world instanceof ServerLevel sw && entity instanceof net.minecraft.server.level.ServerPlayer sp) {
                QuestDropTracker.onKilledOtherEntity(sw, sp, killedEntity);
                DailyQuestService.onMonsterKill(sw, sp, killedEntity);
                StoryQuestService.onMonsterKill(sw, sp, killedEntity);
                TradeRouteService.onMonsterKill(sw, sp, killedEntity);
                SpecialQuestService.onMonsterKill(sw, sp, killedEntity);
                PilgrimContractService.onMonsterKill(sw, sp, killedEntity);
            }
        });
    }

    private static void handleDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        var playerId = player.getUUID();
        QuestAvailabilityNotifier.handleDisconnect(playerId);
        QuestBookHelper.handleDisconnect(playerId);
        QuestTrackerService.handleDisconnect(playerId);
        QuestMasterUiService.handleDisconnect(player);
        MerchantSealQuestService.handleDisconnect(playerId);
        SurveyorCompassQuestService.handleDisconnect(playerId);
        TradeRouteService.handleDisconnect(playerId);
        ClientPreferenceService.handleDisconnect(playerId);
        GuildCornerPlacementService.handleDisconnect(playerId);
        GuildTownService.handleDisconnect((ServerLevel) player.level(), playerId);
        QuestSoundFeedback.handleDisconnect(playerId);
        QuestPartyService.handleDisconnect(player);
    }

    private static void resetTransientRuntimeState() {
        QuestDropTracker.clear();
        QuestHarvestTracker.clear();
        QuestSoundFeedback.resetRuntimeState();
        QuestAvailabilityNotifier.resetRuntimeState();
        QuestBookHelper.resetAllSessions();
        QuestTrackerService.resetAllRuntimeState();
        QuestMasterUiService.resetAllSessions();
        MerchantSealQuestService.resetRuntimeState();
        SurveyorCompassQuestService.resetRuntimeState();
        ShadowsTradeRoadEncounterService.resetRuntimeState();
        EmptyCaravanStoryService.resetRuntimeState();
        TradeRouteService.resetRuntimeState();
        GuildArchiveService.resetTransientState();
        ClientPreferenceService.reset();
        GuildCornerPlacementService.resetRuntimeState();
        de.trakt.questmaster.QuestMasterSettlementService.resetRuntimeState();
        de.trakt.caravan.VillageLifeService.resetRuntimeState();
        de.trakt.caravan.CaravanProtectionService.resetRuntime();
        de.trakt.reputation.ReputationDamageAdapter.resetRuntime();
        de.trakt.reputation.ReputationInteractionService.resetRuntime();
    }
}
