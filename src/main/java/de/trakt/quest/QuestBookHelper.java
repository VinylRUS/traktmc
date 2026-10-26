package de.trakt.quest;

import de.trakt.network.Payloads;
import de.trakt.network.Payloads.JournalPayload;
import de.trakt.economy.CurrencyService;
import de.trakt.pilgrim.PilgrimContractService;
import de.trakt.registry.ModItems;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.special.SpecialQuestStatus;
import de.trakt.quest.story.StoryQuestService;
import de.trakt.quest.story.StoryQuestStatus;
import de.trakt.quest.story.VillageProjectService;
import de.trakt.quest.story.VillageProjectType;
import de.trakt.quest.weekly.WeeklyQuestService;
import de.trakt.quest.weekly.WeeklyQuestStatus;
import de.trakt.questmaster.QuestMasterUiService;
import de.trakt.reputation.ReputationService;
import de.trakt.shrine.VillageBondService;
import de.trakt.shrine.VillageWelcomeService;
import de.trakt.village.LivingVillageNetworkState;
import de.trakt.village.LivingVillageNetworkService;
import de.trakt.config.TraktServerConfig;
import de.trakt.guild.VillageGuildService;
import de.trakt.guildtown.GuildTownService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestBookHelper {
    private static final Set<UUID> JOURNAL_ENABLED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_JOURNAL_REFRESH = new ConcurrentHashMap<>();

    private QuestBookHelper() {}

    private static JournalPayload buildPayload(ServerLevel world, ServerPlayer player, int action) {
        UUID pid = player.getUUID();

        DailyQuestService.QuestStatus daily = DailyQuestService.openQuestStatus(world, pid);
        boolean dailyActive = daily != null;
        Component dailyTitle = dailyActive ? daily.title() : Component.empty();
        Component dailyProgress = dailyActive ? daily.progressLine() : Component.empty();
        WeeklyQuestStatus weekly = WeeklyQuestService.openStatus(world, pid);
        boolean weeklyActive = weekly != null;
        Component weeklyTitle = weeklyActive ? weekly.title() : Component.empty();
        Component weeklyProgress = weeklyActive
                ? de.trakt.reward.VanillaRewardService.joinLines(weekly.lines())
                : Component.empty();
        StoryQuestStatus story = StoryQuestService.openStatus(world, pid);
        boolean storyActive = story != null;
        Component storyTitle = storyActive ? story.title() : Component.empty();
        Component storyProgress = storyActive
                ? de.trakt.reward.VanillaRewardService.joinLines(story.lines())
                : Component.empty();
        PilgrimContractService.PilgrimContractStatus pilgrim = PilgrimContractService.openStatus(world, pid);
        boolean pilgrimActive = pilgrim != null;
        Component pilgrimTitle = pilgrimActive ? pilgrim.title() : Component.empty();
        Component pilgrimProgress = pilgrimActive
                ? de.trakt.reward.VanillaRewardService.joinLines(pilgrim.lines())
                : Component.empty();
        SpecialQuestStatus special = SpecialQuestService.openStatus(world, pid);
        boolean specialActive = special != null;
        Component specialTitle = specialActive ? special.title() : Component.empty();
        Component specialProgress = specialActive
                ? de.trakt.reward.VanillaRewardService.joinLines(special.lines())
                : Component.empty();

        int total = DailyQuestService.getDailyQuestCount()
                + WeeklyQuestService.getWeeklyQuestCount()
                + StoryQuestService.getStoryArcCount()
                + SpecialQuestService.TOTAL_SPECIAL_QUESTS;
        int discovered = (DailyQuestService.hasDiscoveredDaily(world, pid) ? 1 : 0)
                + WeeklyQuestService.discoveredCount(world, pid)
                + StoryQuestService.discoveredCount(world, pid)
                + SpecialQuestService.discoveredCount(world, pid);
        int completed = (DailyQuestService.isDailyCompleted(world, pid) ? 1 : 0)
                + WeeklyQuestService.completedCount(world, pid)
                + StoryQuestService.completedCount(world, pid)
                + SpecialQuestService.completedCount(world, pid);
        int active = (DailyQuestService.isDailyActive(world, pid) ? 1 : 0)
                + WeeklyQuestService.activeCount(world, pid)
                + StoryQuestService.activeCount(world, pid)
                + (pilgrimActive ? 1 : 0)
                + SpecialQuestService.activeCount(world, pid);
        long currencyBalance = CurrencyService.getBalance(world, pid);
        int farmingReputation = ReputationService.get(world, pid, ReputationService.ReputationTrack.FARMING);
        int craftingReputation = ReputationService.get(world, pid, ReputationService.ReputationTrack.CRAFTING);
        int animalReputation = ReputationService.get(world, pid, ReputationService.ReputationTrack.ANIMALS);
        int tradeReputation = ReputationService.get(world, pid, ReputationService.ReputationTrack.TRADE);
        int monsterReputation = ReputationService.get(world, pid, ReputationService.ReputationTrack.MONSTER_HUNTING);
        boolean hasStarreachRing = hasInventoryItem(player, ModItems.STARREACH_RING);
        boolean hasMerchantSeal = hasInventoryItem(player, ModItems.MERCHANT_SEAL);
        boolean hasShepherdFlute = hasInventoryItem(player, ModItems.SHEPHERD_FLUTE);
        boolean hasApiaristSmoker = hasInventoryItem(player, ModItems.APIARISTS_SMOKER);
        boolean hasSurveyorCompass = hasInventoryItem(player, ModItems.SURVEYORS_COMPASS);
        boolean hasCaravanLedger = hasInventoryItem(player, ModItems.CARAVAN_LEDGER);
        boolean hasVillageLedgerProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.VILLAGE_LEDGER);
        boolean hasApiaryCharterProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.APIARY_CHARTER);
        boolean hasForgeCharterProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.FORGE_CHARTER);
        boolean hasMarketCharterProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.MARKET_CHARTER);
        boolean hasPastureCharterProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.PASTURE_CHARTER);
        boolean hasWatchBellProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.WATCH_BELL);
        boolean hasCaravanYardProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.CARAVAN_YARD);
        boolean hasWayshrineNetworkProject = VillageProjectService.isUnlocked(world, pid, VillageProjectType.WAYSHRINE_NETWORK);
        LivingVillageNetworkState.NetworkSnapshot networkProgress =
                LivingVillageNetworkState.get(world.getServer()).network(pid);
        List<VillageBondService.VillageBondView> networkVillages = VillageBondService.villages(world, pid);
        Payloads.NetworkSummaryData networkSummary = new Payloads.NetworkSummaryData(
                networkProgress.rank(),
                networkProgress.renown(),
                networkProgress.nextRankThreshold(),
                LivingVillageNetworkService.honorLabel(networkProgress.rank()),
                networkProgress.specialization().label(),
                Component.translatable("text.trakt.adventure_profile."
                        + TraktServerConfig.get().adventureProfile().name().toLowerCase(Locale.ROOT)));
        var social = de.trakt.data.QuestState.get(world.getServer()).getPlayerData(pid).socialReputation();
        String socialDimension = world.dimension().identifier().toString();
        List<Payloads.NetworkVillageData> networkVillageData = networkVillages.stream()
                .map(village -> new Payloads.NetworkVillageData(
                        village.index(),
                        village.type().label(),
                        village.level().label(),
                        village.network().condition().label(),
                        village.network().condition().key(),
                        village.network().need().label(),
                        village.network().support(),
                        village.network().energyProgress(),
                        social.localTrust(new de.trakt.village.VillageLifeState.VillageKey(socialDimension, village.x(), village.z())),
                        de.trakt.reputation.SocialReputationService.enabled() && social.activeCase() != null
                                && social.activeCase().affected().contains(new de.trakt.village.VillageLifeState.VillageKey(socialDimension, village.x(), village.z()))))
                .toList();
        List<Component> networkGuildLines = VillageGuildService.statusLines(world, pid);
        VillageBondService.VillageBondView priorityVillage = networkVillages.stream()
                .min(Comparator.comparingInt(village -> village.network().support())).orElse(null);
        Component introductionNextAction = VillageWelcomeService.journalNextAction(world, pid);
        Component guildTownNextAction = GuildTownService.journalNextAction(world, pid);
        Component networkNextAction = !introductionNextAction.getString().isBlank()
                ? introductionNextAction
                : !guildTownNextAction.getString().isBlank()
                        ? guildTownNextAction
                : priorityVillage == null
                        ? Component.translatable("screen.trakt.journal.network.next.discover")
                        : Component.translatable("screen.trakt.journal.network.next.supply",
                        priorityVillage.type().label(), priorityVillage.network().need().label(),
                        priorityVillage.network().support(), 100);

        return new JournalPayload(
                action,
                total,
                discovered,
                completed,
                active,
                currencyBalance,
                farmingReputation,
                craftingReputation,
                animalReputation,
                tradeReputation,
                monsterReputation,
                hasStarreachRing,
                hasMerchantSeal,
                hasShepherdFlute,
                hasApiaristSmoker,
                hasSurveyorCompass,
                hasCaravanLedger,
                dailyActive,
                dailyTitle,
                dailyProgress,
                weeklyActive,
                weeklyTitle,
                weeklyProgress,
                storyActive,
                storyTitle,
                storyProgress,
                pilgrimActive,
                pilgrimTitle,
                pilgrimProgress,
                specialActive,
                specialTitle,
                specialProgress,
                hasVillageLedgerProject,
                hasApiaryCharterProject,
                hasForgeCharterProject,
                hasMarketCharterProject,
                hasPastureCharterProject,
                hasWatchBellProject,
                hasCaravanYardProject,
                hasWayshrineNetworkProject,
                QuestMasterUiService.buildGuildPathNodes(world, player),
                networkNextAction,
                networkSummary,
                networkVillageData,
                networkGuildLines
        );
    }

    public static boolean openJournal(ServerLevel world, ServerPlayer player) {
        UUID pid = player.getUUID();
        JOURNAL_ENABLED.add(pid);
        sendPayload(player, buildPayload(world, player, JournalPayload.ACTION_OPEN));
        return true;
    }

    public static boolean isJournalOpen(UUID player) { return JOURNAL_ENABLED.contains(player); }

    public static boolean toggleJournal(ServerLevel world, ServerPlayer player) {
        if (JOURNAL_ENABLED.contains(player.getUUID())) {
            closeJournal(player);
            return false;
        }
        return openJournal(world, player);
    }

    public static void closeJournal(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID pid = player.getUUID();
        if (!JOURNAL_ENABLED.remove(pid)) {
            return;
        }
        LAST_JOURNAL_REFRESH.remove(pid);
        sendPayload(player, new JournalPayload(
                JournalPayload.ACTION_CLOSE,
                0,
                0,
                0,
                0,
                0L,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Component.empty(),
                Component.empty(),
                false,
                Component.empty(),
                Component.empty(),
                false,
                Component.empty(),
                Component.empty(),
                false,
                Component.empty(),
                Component.empty(),
                false,
                Component.empty(),
                Component.empty(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                java.util.List.of(),
                Component.empty(),
                new Payloads.NetworkSummaryData(0, 0, 0,
                        Component.empty(), Component.empty(), Component.empty()),
                java.util.List.of(),
                java.util.List.of()
        ));
    }

    public static void resetAllSessions() {
        JOURNAL_ENABLED.clear();
        LAST_JOURNAL_REFRESH.clear();
    }

    public static void handleDisconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        JOURNAL_ENABLED.remove(playerId);
        de.trakt.reputation.ReputationInteractionService.remove(playerId);
        LAST_JOURNAL_REFRESH.remove(playerId);
    }

    public static void onServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID pid = player.getUUID();
            if (!JOURNAL_ENABLED.contains(pid)) {
                continue;
            }
            ServerLevel world = (ServerLevel) player.level();
            long now = world.getGameTime();
            long last = LAST_JOURNAL_REFRESH.getOrDefault(pid, -200L);
            if ((now - last) >= 40L) {
                refreshQuestBook(world, player);
                LAST_JOURNAL_REFRESH.put(pid, now);
            }
        }
    }

    public static void refreshQuestBook(ServerLevel world, ServerPlayer player) {
        if (!JOURNAL_ENABLED.contains(player.getUUID())) {
            return;
        }
        sendPayload(player, buildPayload(world, player, JournalPayload.ACTION_UPDATE));
    }

    private static void sendPayload(ServerPlayer player, JournalPayload payload) {
        ServerPlayNetworking.send(player, payload);
        if (payload.action() != JournalPayload.ACTION_CLOSE) de.trakt.reputation.ReputationInteractionService.sendJournal(player);
    }

    private static boolean hasInventoryItem(ServerPlayer player, Item item) {
        if (player == null || item == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return true;
            }
        }
        return player.getOffhandItem().is(item);
    }
}
