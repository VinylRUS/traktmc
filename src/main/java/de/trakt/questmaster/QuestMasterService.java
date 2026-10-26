package de.trakt.questmaster;

import de.trakt.data.PlayerQuestData;
import de.trakt.data.QuestState;
import de.trakt.entity.QuestMasterEntity;
import de.trakt.registry.ModEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

public final class QuestMasterService {
    private static final int MIN_SPAWN_DISTANCE = 4;
    private static final int MAX_SPAWN_DISTANCE = 10;
    private static final int MAX_SPAWN_ATTEMPTS = 12;
    private static final int VERTICAL_SEARCH_RADIUS = 12;
    private static final int VERTICAL_SCORE_WEIGHT = 10;
    private static final int SURFACE_FALLBACK_PENALTY = 24;
    private static final double MAX_INTERACT_DISTANCE_SQUARED = 64.0;
    private static final double NEARBY_QUESTMASTER_DISTANCE_SQUARED = 400.0;

    private QuestMasterService() {}

    private static PlayerQuestData data(ServerLevel world, UUID playerId) {
        return QuestState.get(world.getServer()).getPlayerData(playerId);
    }

    private static void markDirty(ServerLevel world) {
        if (world != null) {
            QuestState.get(world.getServer()).setDirty();
        }
    }

    public static boolean isEligibleSpawnTarget(Player player) {
        return player != null && player.isAlive() && !player.isSpectator();
    }

    public static QuestMasterEntity spawnNear(ServerLevel world, BlockPos origin, ServerPlayer arrivalAnchor) {
        if (world == null || origin == null) {
            return null;
        }
        ServerPlayer anchor = arrivalAnchor != null && isEligibleSpawnTarget(arrivalAnchor) ? arrivalAnchor : null;
        if (findNearbyQuestMaster(world, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) != null) {
            return null;
        }

        for (BlockPos spawnPos : findSpawnCandidates(world, origin)) {
            if (spawnPos == null) {
                continue;
            }
            if (!world.hasChunkAt(spawnPos)) {
                // never load a chunk just to place an entity (settlement spawns)
                continue;
            }

            QuestMasterEntity questMaster = new QuestMasterEntity(ModEntities.QUEST_MASTER, world);
            float yaw = world.getRandom().nextFloat() * 360.0f;
            questMaster.snapTo(
                    spawnPos.getX() + 0.5,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5,
                    yaw,
                    0.0f
            );

            if (!world.noCollision(questMaster)) {
                continue;
            }
            if (!world.addFreshEntity(questMaster)) {
                continue;
            }
            questMaster.beginArrivalTracking(anchor);
            return questMaster;
        }
        return null;
    }

    public static void interact(ServerLevel world, ServerPlayer player, QuestMasterEntity questMaster) {
        if (world == null || player == null || questMaster == null) {
            return;
        }
        if (player.distanceToSqr(questMaster) > MAX_INTERACT_DISTANCE_SQUARED) {
            player.sendSystemMessage(Component.translatable("message.trakt.questmaster.too_far").withStyle(ChatFormatting.RED), false);
            return;
        }

        questMaster.beginInteraction(player);
        questMaster.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ());
        QuestMasterUiService.open(world, player, questMaster);
    }

    /**
     * F6b: a player with high combined reputation mastery invites a settled village
     * questmaster to their own caravan yard. The village binding is released (the village
     * re-settles on its next visit), the entity binds to the player and teleports to the
     * yard anchor — the same direct teleport caravan merchants use for their waypoints.
     */
    public static boolean inviteToPlayerYard(ServerLevel world, ServerPlayer player, QuestMasterEntity questMaster) {
        if (world == null || player == null || questMaster == null) {
            return false;
        }
        if (questMaster.invitedBy() != null) {
            player.sendSystemMessage(Component.translatable("message.trakt.questmaster.invite.already_invited")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (questMaster.settledVillageKey() == null) {
            player.sendSystemMessage(Component.translatable("message.trakt.questmaster.invite.not_settled")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        int requiredMastery = de.trakt.config.TraktServerConfig.get().questmasters().inviteMastery();
        int mastery = de.trakt.reputation.ReputationService.totalMastery(world, player.getUUID());
        if (mastery < requiredMastery) {
            player.sendSystemMessage(Component.translatable("message.trakt.questmaster.invite.reputation",
                            requiredMastery, mastery)
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        BlockPos yard = de.trakt.caravan.TradeRouteService.playerYardAnchor(world, player.getUUID());
        if (yard == null) {
            player.sendSystemMessage(Component.translatable("message.trakt.questmaster.invite.no_yard")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }

        String previousVillageKey = questMaster.settledVillageKey();
        QuestMasterSettlementService.onSettledDeath(world, questMaster); // releases the village binding
        questMaster.markInvited(player.getUUID());
        questMaster.teleportTo(yard.getX() + 0.5, yard.getY(), yard.getZ() + 0.5);
        // F6c: the invite is world-persistent so the panel REST list survives restarts
        QuestMasterSettlementState.get(world.getServer()).recordInvite(
                questMaster.getUUID(), player.getUUID(), player.getGameProfile().name(), yard);

        player.sendSystemMessage(Component.translatable("message.trakt.questmaster.invite.success",
                        yard.getX(), yard.getY(), yard.getZ())
                .withStyle(ChatFormatting.GOLD), false);
        world.playSound(null, player.blockPosition(),
                net.minecraft.sounds.SoundEvents.VILLAGER_YES, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.05f);

        com.google.gson.JsonObject eventData = new com.google.gson.JsonObject();
        eventData.addProperty("village_key", previousVillageKey == null ? "" : previousVillageKey);
        eventData.addProperty("yard_x", yard.getX());
        eventData.addProperty("yard_y", yard.getY());
        eventData.addProperty("yard_z", yard.getZ());
        de.trakt.api.ApiService.emitEvent(world.getServer(), "questmaster_invited", player.getUUID(),
                player.getGameProfile().name(), eventData);
        return true;
    }

    public static QuestMasterEntity findNearbyQuestMaster(ServerLevel world, double x, double y, double z) {
        if (world == null) {
            return null;
        }
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof QuestMasterEntity questMaster
                    && questMaster.isAlive()
                    && questMaster.distanceToSqr(x, y, z) <= NEARBY_QUESTMASTER_DISTANCE_SQUARED) {
                return questMaster;
            }
        }
        return null;
    }

    public static List<QuestMasterEntity> getQuestMasters(ServerLevel world) {
        List<QuestMasterEntity> questMasters = new ArrayList<>();
        if (world == null) {
            return questMasters;
        }
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof QuestMasterEntity questMaster && questMaster.isAlive()) {
                questMasters.add(questMaster);
            }
        }
        return questMasters;
    }

    public static int despawnAll(ServerLevel world) {
        if (world == null) {
            return 0;
        }
        List<QuestMasterEntity> questMasters = getQuestMasters(world);
        for (QuestMasterEntity questMaster : questMasters) {
            ServerPlayer customer = questMaster.getCustomer();
            if (customer != null) {
                QuestMasterUiService.close(customer);
                questMaster.clearCustomer();
            }
            questMaster.discard();
        }
        return questMasters.size();
    }

    public static double getMaxInteractDistanceSquared() {
        return MAX_INTERACT_DISTANCE_SQUARED;
    }

    private static List<BlockPos> findSpawnCandidates(ServerLevel world, BlockPos origin) {
        int minDistanceSquared = MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE;
        int maxDistanceSquared = MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE;
        Map<BlockPos, SpawnCandidate> candidates = new HashMap<>();

        for (int dx = -MAX_SPAWN_DISTANCE; dx <= MAX_SPAWN_DISTANCE; dx++) {
            for (int dz = -MAX_SPAWN_DISTANCE; dz <= MAX_SPAWN_DISTANCE; dz++) {
                int horizontalDistanceSquared = dx * dx + dz * dz;
                if (horizontalDistanceSquared < minDistanceSquared || horizontalDistanceSquared > maxDistanceSquared) {
                    continue;
                }

                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                addCandidate(candidates, origin, findNearbyVerticalSpawnPos(world, origin.getY(), x, z), 0, world.getRandom().nextInt());

                int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                addCandidate(candidates, origin, validateSpawnPos(world, new BlockPos(x, surfaceY, z)), SURFACE_FALLBACK_PENALTY, world.getRandom().nextInt());
            }
        }

        return candidates.values().stream()
                .sorted(Comparator
                        .comparingInt(SpawnCandidate::score)
                        .thenComparingInt(SpawnCandidate::verticalDistance)
                        .thenComparingInt(SpawnCandidate::tieBreaker))
                .limit(MAX_SPAWN_ATTEMPTS)
                .map(SpawnCandidate::pos)
                .toList();
    }

    private static BlockPos findNearbyVerticalSpawnPos(ServerLevel world, int originY, int x, int z) {
        int minY = Math.max(world.getMinY() + 1, originY - VERTICAL_SEARCH_RADIUS);
        int maxY = Math.min(world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), originY + VERTICAL_SEARCH_RADIUS);
        for (int offset = 0; offset <= VERTICAL_SEARCH_RADIUS; offset++) {
            BlockPos lowerPos = validateSpawnPos(world, new BlockPos(x, originY - offset, z));
            if (originY - offset >= minY && lowerPos != null) {
                return lowerPos;
            }
            if (offset == 0) {
                continue;
            }

            BlockPos upperPos = validateSpawnPos(world, new BlockPos(x, originY + offset, z));
            if (originY + offset <= maxY && upperPos != null) {
                return upperPos;
            }
        }
        return null;
    }

    private static BlockPos validateSpawnPos(ServerLevel world, BlockPos feetPos) {
        BlockPos groundPos = feetPos.below();
        if (!world.getWorldBorder().isWithinBounds(feetPos)) {
            return null;
        }
        if (feetPos.getY() <= world.getMinY()) {
            return null;
        }
        if (!world.getBlockState(groundPos).isFaceSturdy(world, groundPos, Direction.UP)) {
            return null;
        }
        if (!world.getBlockState(feetPos).isAir() || !world.getBlockState(feetPos.above()).isAir()) {
            return null;
        }
        return feetPos;
    }

    private static void addCandidate(Map<BlockPos, SpawnCandidate> candidates,
                                     BlockPos origin,
                                     BlockPos candidatePos,
                                     int extraPenalty,
                                     int tieBreaker) {
        if (candidatePos == null) {
            return;
        }

        int dx = candidatePos.getX() - origin.getX();
        int dy = candidatePos.getY() - origin.getY();
        int dz = candidatePos.getZ() - origin.getZ();
        int score = dx * dx + dz * dz + Math.abs(dy) * VERTICAL_SCORE_WEIGHT + extraPenalty;
        int verticalDistance = Math.abs(dy);

        SpawnCandidate current = candidates.get(candidatePos);
        if (current == null
                || score < current.score()
                || (score == current.score() && verticalDistance < current.verticalDistance())) {
            candidates.put(candidatePos, new SpawnCandidate(candidatePos, score, verticalDistance, tieBreaker));
        }
    }

    private record SpawnCandidate(BlockPos pos, int score, int verticalDistance, int tieBreaker) {}
}
