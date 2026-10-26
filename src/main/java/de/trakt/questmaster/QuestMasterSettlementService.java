package de.trakt.questmaster;

import de.trakt.config.TraktServerConfig;
import de.trakt.content.story.ShadowsTradeRoadEncounterService;
import de.trakt.entity.QuestMasterEntity;
import de.trakt.quest.daily.DailyQuestService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Natural questmaster settlement in villages (F6a). Replaces the retired command
 * summon: questmasters appear when a player visits a village, one per village by
 * default (config {@code questmasters.perVillage}), and stay until the village
 * dies (no villagers left in its structure bounds). Settlements persist across
 * restarts — the binding survives, the entity is chunk-persistent; a lost settler
 * is respawned on the next visit.
 */
public final class QuestMasterSettlementService {

    private static final int PLAYER_VISIT_INTERVAL_TICKS = 100;
    private static final int VILLAGE_RECHECK_INTERVAL_TICKS = 200;
    private static final int MIN_VILLAGERS_FOR_SETTLEMENT = 1;
    private static final ConcurrentMap<UUID, Long> NEXT_PLAYER_CHECK = new ConcurrentHashMap<>();
    private static final ConcurrentMap<QuestMasterSettlementState.VillageKey, Long> NEXT_VILLAGE_CHECK =
            new ConcurrentHashMap<>();

    private QuestMasterSettlementService() {}

    /** Bounded online-player visit check (registered from Trakt.onInitialize). */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel world)) continue;
            long gameTime = world.getGameTime();
            if (gameTime < NEXT_PLAYER_CHECK.getOrDefault(player.getUUID(), 0L)) continue;
            NEXT_PLAYER_CHECK.put(player.getUUID(), gameTime + PLAYER_VISIT_INTERVAL_TICKS);
            ShadowsTradeRoadEncounterService.VillageMarker marker =
                    ShadowsTradeRoadEncounterService.currentVillage(world, player.blockPosition());
            if (marker != null) {
                // F2b part 2: quest engine village-visit event — independent of the
                // settlement config below; the key is the stable dimension/centre identity.
                DailyQuestService.onVillageVisited(world, player,
                        QuestMasterSettlementState.dimensionKey(world) + ":" + marker.centerX() + ":" + marker.centerZ());
                handleVillageVisit(world, player, marker, gameTime);
            }
        }
    }

    /** Package-visible so a visit hook can drive it directly (see GuildCornerPlacementService). */
    static void handleVillageVisit(ServerLevel world, ServerPlayer player,
                                   ShadowsTradeRoadEncounterService.VillageMarker marker, long gameTime) {
        if (world == null || marker == null) return;
        QuestMasterSettlementState.VillageKey key = new QuestMasterSettlementState.VillageKey(
                QuestMasterSettlementState.dimensionKey(world), marker.centerX(), marker.centerZ());
        if (gameTime < NEXT_VILLAGE_CHECK.getOrDefault(key, 0L)) return;
        NEXT_VILLAGE_CHECK.put(key, gameTime + VILLAGE_RECHECK_INTERVAL_TICKS);

        if (TraktServerConfig.get().questmasters().perVillage() < 1) return;

        QuestMasterSettlementState state = QuestMasterSettlementState.get(world.getServer());
        if (countVillagers(world, marker) < MIN_VILLAGERS_FOR_SETTLEMENT) {
            // the village died: release its settler and drop the binding
            releaseSettler(world, state, key);
            return;
        }
        QuestMasterSettlementState.Binding binding = state.binding(key).orElse(null);
        if (binding != null) {
            QuestMasterEntity existing = findSettledEntity(world, binding);
            if (existing != null) return;               // alive and loaded
            if (!world.hasChunkAt(binding.settlePos())) return;  // cannot verify, retry later
            // the settler died or was discarded while away — fall through and respawn
        }
        spawnSettler(world, player, marker, state, key);
    }

    /** Entity death hook (QuestMasterEntity.die): drop the binding so the village re-settles. */
    public static void onSettledDeath(ServerLevel world, QuestMasterEntity settler) {
        if (world == null || settler == null) return;
        QuestMasterSettlementState state = QuestMasterSettlementState.get(world.getServer());
        UUID dead = settler.getUUID();
        for (var entry : state.settlementsView().entrySet()) {
            if (entry.getValue().entityUuid().equals(dead)) {
                state.clear(entry.getKey());
                return;
            }
        }
    }

    private static QuestMasterEntity findSettledEntity(ServerLevel world,
                                                       QuestMasterSettlementState.Binding binding) {
        for (QuestMasterEntity master : QuestMasterService.getQuestMasters(world)) {
            if (master.isAlive() && master.getUUID().equals(binding.entityUuid())) {
                return master;
            }
        }
        return null;
    }

    private static void spawnSettler(ServerLevel world, ServerPlayer visitor,
                                     ShadowsTradeRoadEncounterService.VillageMarker marker,
                                     QuestMasterSettlementState state,
                                     QuestMasterSettlementState.VillageKey key) {
        // the visitor is inside the village; anchor the spawn search at the structure
        // centre on the visitor's height, so no chunk is ever loaded for the scan
        BlockPos origin = BlockPos.containing(
                marker.centerX(), visitor.blockPosition().getY(), marker.centerZ());
        if (QuestMasterService.findNearbyQuestMaster(world,
                origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) != null) {
            return;   // someone is already standing there (summon-egg leftovers)
        }
        QuestMasterEntity spawned = QuestMasterService.spawnNear(world, origin, visitor);
        if (spawned == null) {
            return;   // no loaded candidate spot — the next visit retries
        }
        spawned.markSettled(settledKey(key));
        state.put(key, spawned.getUUID(), spawned.blockPosition());
        // F6c: panel event — a new questmaster settled in this village
        com.google.gson.JsonObject settled = new com.google.gson.JsonObject();
        settled.addProperty("village_key", settledKey(key));
        settled.addProperty("x", spawned.blockPosition().getX());
        settled.addProperty("y", spawned.blockPosition().getY());
        settled.addProperty("z", spawned.blockPosition().getZ());
        de.trakt.api.ApiService.emitEvent(world.getServer(), "questmaster_settled",
                visitor.getUUID(), visitor.getGameProfile().name(), settled);
    }

    private static void releaseSettler(ServerLevel world, QuestMasterSettlementState state,
                                       QuestMasterSettlementState.VillageKey key) {
        QuestMasterSettlementState.Binding binding = state.binding(key).orElse(null);
        if (binding == null) return;
        QuestMasterEntity existing = findSettledEntity(world, binding);
        if (existing != null) {
            existing.discard();
        }
        state.clear(key);
    }

    private static int countVillagers(ServerLevel world,
                                      ShadowsTradeRoadEncounterService.VillageMarker marker) {
        AABB bounds = new AABB(
                Math.min(marker.minX(), marker.maxX()), world.getMinY(),
                Math.min(marker.minZ(), marker.maxZ()),
                Math.max(marker.minX(), marker.maxX()), world.getMaxY(),
                Math.max(marker.minZ(), marker.maxZ()));
        List<Villager> villagers = world.getEntitiesOfClass(Villager.class, bounds);
        return villagers.size();
    }

    /** Stable string form stored on the entity (informational; bindings are UUID-keyed). */
    static String settledKey(QuestMasterSettlementState.VillageKey key) {
        return key.dimension() + ":" + key.anchorX() + ":" + key.anchorZ();
    }

    /** Drops the in-memory throttles (admin reset path). Bindings in the save stay. */
    public static void resetRuntimeState() {
        NEXT_PLAYER_CHECK.clear();
        NEXT_VILLAGE_CHECK.clear();
    }
}
