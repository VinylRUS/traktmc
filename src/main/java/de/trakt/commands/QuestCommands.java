package de.trakt.commands;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import de.trakt.data.QuestState;
import de.trakt.caravan.TradeRouteService;
import de.trakt.caravan.TradeGuildService;
import de.trakt.caravan.TradeRouteEventType;
import de.trakt.caravan.TradeRouteSpecialization;
import de.trakt.caravan.TradeRouteUpgrade;
import de.trakt.caravan.TradeRouteIncidentApproach;
import de.trakt.economy.CurrencyService;
import de.trakt.economy.ProsperityService;
import de.trakt.party.QuestPartyService;
import de.trakt.pilgrim.PilgrimContractService;
import de.trakt.pilgrim.PilgrimService;
import de.trakt.quest.QuestDropTracker;
import de.trakt.quest.QuestHarvestTracker;
import de.trakt.quest.QuestSoundFeedback;
import de.trakt.quest.QuestTrackerService;
import de.trakt.questmaster.QuestMasterService;
import de.trakt.questmaster.QuestMasterUiService;
import de.trakt.quest.QuestBookHelper;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.quest.daily.FirstDailyChoiceService;
import de.trakt.quest.special.AdminCoreTestQuestService;
import de.trakt.quest.special.MerchantSealQuestService;
import de.trakt.quest.special.ShardRelicQuestService;
import de.trakt.quest.special.SpecialQuestService;
import de.trakt.quest.special.SurveyorCompassQuestService;
import de.trakt.content.story.ShadowsTradeRoadEncounterService;
import de.trakt.content.story.EmptyCaravanStoryService;
import de.trakt.quest.story.StoryQuestService;
import de.trakt.quest.story.VillageProjectService;
import de.trakt.quest.story.VillageProjectType;
import de.trakt.quest.weekly.WeeklyQuestService;
import de.trakt.reputation.ReputationService;
import de.trakt.shrine.VillageBondService;
import de.trakt.shrine.VillageBondLevel;
import de.trakt.village.LivingVillageNetworkState;
import de.trakt.guild.VillageGuildState;
import de.trakt.guildtown.GuildTownSharedState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class QuestCommands {
    private static final SuggestionProvider<CommandSourceStack> QUEST_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("honey");
        builder.suggest("pet");
        builder.suggest("bakery");
        builder.suggest("kitchen");
        builder.suggest("workshop");
        builder.suggest("smith");
        builder.suggest("wool");
        builder.suggest("river");
        builder.suggest("harvest");
        builder.suggest("smelt");
        builder.suggest("stall");
        builder.suggest("trade");
        builder.suggest("zombie");
        builder.suggest("skeleton");
        builder.suggest("spider");
        builder.suggest("creeper");
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> WALLET_UNIT_SUGGESTIONS = (ctx, builder) -> {
        for (CurrencyService.CurrencyUnit unit : CurrencyService.CurrencyUnit.values()) {
            builder.suggest(unit.id());
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> REPUTATION_TRACK_SUGGESTIONS = (ctx, builder) -> {
        for (ReputationService.ReputationTrack track : ReputationService.ReputationTrack.values()) {
            builder.suggest(track.id());
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> PROJECT_SUGGESTIONS = (ctx, builder) -> {
        for (VillageProjectType project : VillageProjectType.values()) {
            if (!project.alwaysUnlocked()) {
                builder.suggest(project.id());
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ROUTE_SPECIALIZATION_SUGGESTIONS = (ctx, builder) -> {
        for (TradeRouteSpecialization value : TradeRouteSpecialization.values()) builder.suggest(value.key());
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ROUTE_UPGRADE_SUGGESTIONS = (ctx, builder) -> {
        for (TradeRouteUpgrade value : TradeRouteUpgrade.values()) builder.suggest(value.key());
        return builder.buildFuture();
    };

    /** F7b: the three canonical unit words of {@code CurrencyService.parseUnit}. */
    private static final SuggestionProvider<CommandSourceStack> CURRENCY_UNIT_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("copper");
        builder.suggest("silvermark");
        builder.suggest("crown");
        return builder.buildFuture();
    };

    /** F3b: online player names as relay send targets (offline terminal owners are typed by hand). */
    private static final SuggestionProvider<CommandSourceStack> RELAY_TARGET_SUGGESTIONS = (ctx, builder) -> {
        for (var online : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            builder.suggest(online.getGameProfile().name());
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ROUTE_EVENT_SUGGESTIONS = (ctx, builder) -> {
        builder.suggest("clear");
        for (TradeRouteEventType value : TradeRouteEventType.values()) builder.suggest(value.key());
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ROUTE_APPROACH_SUGGESTIONS = (ctx, builder) -> {
        for (TradeRouteIncidentApproach value : TradeRouteIncidentApproach.values()) builder.suggest(value.key());
        return builder.buildFuture();
    };

    private QuestCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> setQuestCommand = literal("setquest")
                    .requires(AdminCommands::canManageRespawn)
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.translatable("command.trakt.setquest.usage").withStyle(ChatFormatting.GRAY), false);
                        return 0;
                    })
                    .then(argument("quest", StringArgumentType.word())
                            .suggests(QUEST_SUGGESTIONS)
                            .executes(ctx -> {
                                var player = ctx.getSource().getPlayer();
                                if (player instanceof ServerPlayer sp) {
                                    var world = ctx.getSource().getServer().overworld();
                                    String arg = StringArgumentType.getString(ctx, "quest");
                                    DailyQuestService.DailyQuestType chosen = DailyQuestService.questFromString(arg);
                                    if (chosen == null) {
                                        sp.sendSystemMessage(Component.translatable("command.trakt.setquest.invalid").withStyle(ChatFormatting.RED), false);
                                        return 0;
                                    }
                                    DailyQuestService.setQuestChoiceForToday(world, sp.getUUID(), chosen);
                                    sp.sendSystemMessage(Component.translatable("command.trakt.setquest.success", DailyQuestService.displayKey(chosen)).withStyle(ChatFormatting.GREEN), false);
                                }
                                return 1;
                            }));

            LiteralArgumentBuilder<CommandSourceStack> questAdminCommand = literal("admin")
                    .requires(AdminCommands::canManageRespawn)
                    .then(ReputationCommands.command())
                    .then(setQuestCommand)
                    .then(literal("resetdaily")
                            .executes(ctx -> resetDailyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> resetDailyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("nextdaily")
                            .executes(ctx -> prepareNextDailyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> prepareNextDailyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("resetweekly")
                            .executes(ctx -> resetWeeklyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> resetWeeklyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("nextweekly")
                            .executes(ctx -> prepareNextWeeklyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> prepareNextWeeklyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("completeweekly")
                            .executes(ctx -> completeWeeklyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> completeWeeklyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("story")
                            .then(literal("show")
                                    .executes(ctx -> showStoryForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> showStoryForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("reset")
                                    .executes(ctx -> resetStoryForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> resetStoryForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("complete")
                                    .executes(ctx -> completeStoryForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> completeStoryForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("shadows")
                                    .then(literal("unlock")
                                            .executes(ctx -> unlockShadowsForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                            .then(argument("player", EntityArgument.player())
                                                    .executes(ctx -> unlockShadowsForPlayer(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player")
                                                    ))))
                                    .then(literal("testrescue")
                                            .executes(ctx -> prepareShadowsEncounterTest(ctx.getSource(), ctx.getSource().getPlayer(), false))
                                            .then(argument("player", EntityArgument.player())
                                                    .executes(ctx -> prepareShadowsEncounterTest(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            false
                                                    ))))
                                    .then(literal("testfinal")
                                            .executes(ctx -> prepareShadowsEncounterTest(ctx.getSource(), ctx.getSource().getPlayer(), true))
                                            .then(argument("player", EntityArgument.player())
                                                    .executes(ctx -> prepareShadowsEncounterTest(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            true
                                                    ))))))
                    .then(literal("completedaily")
                            .executes(ctx -> completeDailyForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> completeDailyForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("reset")
                            .then(literal("complete")
                                    .executes(ctx -> resetComplete(ctx.getSource()))))
                    .then(literal("project")
                            .then(literal("show")
                                    .executes(ctx -> showProjectsForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> showProjectsForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("unlock")
                                    .then(argument("player", EntityArgument.player())
                                            .then(argument("project", StringArgumentType.word())
                                                    .suggests(PROJECT_SUGGESTIONS)
                                                    .executes(ctx -> setProjectForPlayer(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            parseProject(StringArgumentType.getString(ctx, "project")),
                                                            true
                                                    )))))
                            .then(literal("lock")
                                    .then(argument("player", EntityArgument.player())
                                            .then(argument("project", StringArgumentType.word())
                                                    .suggests(PROJECT_SUGGESTIONS)
                                                    .executes(ctx -> setProjectForPlayer(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            parseProject(StringArgumentType.getString(ctx, "project")),
                                                    false
                                                    ))))))
                    .then(literal("routes")
                            .then(literal("testsetup")
                                    .executes(ctx -> setupRouteTest(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> setupRouteTest(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("testevent")
                                    .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                            .then(argument("event", StringArgumentType.word())
                                                    .suggests(ROUTE_EVENT_SUGGESTIONS)
                                                    .executes(ctx -> setRouteTestEvent(
                                                            ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "route"),
                                                            StringArgumentType.getString(ctx, "event")
                                                    )))))
                            .then(literal("reset")
                                    .executes(ctx -> resetRoutesForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> resetRoutesForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            )))))
                    .then(literal("economy")
                            .then(literal("testsetup")
                                    .executes(ctx -> setupEconomyTest(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> setupEconomyTest(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            )))))
                    .then(literal("shrines")
                            .then(literal("testsetup")
                                    .executes(ctx -> setupShrineTest(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> setupShrineTest(
                                                    ctx.getSource(), EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("reverse")
                                    .executes(ctx -> reverseShrineTest(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> reverseShrineTest(
                                                    ctx.getSource(), EntityArgument.getPlayer(ctx, "player")
                                            )))))
                            .then(buildUiTestCommand())
                    .then(literal("shardcache")
                            .executes(ctx -> startShardCacheForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> startShardCacheForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("shardcachetp")
                            .executes(ctx -> teleportToShardCacheForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> teleportToShardCacheForPlayer(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("coretest")
                            .then(literal("start")
                                    .executes(ctx -> startCoreTestForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> startCoreTestForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("reset")
                                    .executes(ctx -> resetCoreTestForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> resetCoreTestForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("show")
                                    .executes(ctx -> showCoreTestForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> showCoreTestForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            )))))
                    .then(literal("pilgrim")
                            .then(literal("spawn")
                                    .executes(ctx -> spawnPilgrimForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                                    .then(argument("player", EntityArgument.player())
                                            .executes(ctx -> spawnPilgrimForPlayer(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(literal("rumor")
                                    .then(literal("unlock")
                                            .executes(ctx -> setPilgrimRumorOverride(ctx.getSource(), ctx.getSource().getPlayer(), true))
                                            .then(argument("player", EntityArgument.player())
                                                    .executes(ctx -> setPilgrimRumorOverride(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            true
                                                    ))))
                                    .then(literal("lock")
                                            .executes(ctx -> setPilgrimRumorOverride(ctx.getSource(), ctx.getSource().getPlayer(), false))
                                            .then(argument("player", EntityArgument.player())
                                                    .executes(ctx -> setPilgrimRumorOverride(
                                                            ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            false
                                                    )))))
                            .then(literal("despawn")
                                    .executes(ctx -> despawnPilgrims(ctx.getSource()))))
                    .then(buildAdminWalletCommand())
                    .then(buildAdminReputationCommand());

            LiteralArgumentBuilder<CommandSourceStack> journalCommand = literal("journal")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayer();
                        if (player instanceof ServerPlayer sp) {
                            var world = ctx.getSource().getServer().overworld();
                            QuestBookHelper.toggleJournal(world, sp);
                        }
                        return 1;
                    })
                    .then(literal("open").executes(ctx -> {
                        var player = ctx.getSource().getPlayer();
                        if (player instanceof ServerPlayer sp) {
                            QuestBookHelper.openJournal(ctx.getSource().getServer().overworld(), sp);
                        }
                        return 1;
                    }))
                    .then(literal("close").executes(ctx -> {
                        var player = ctx.getSource().getPlayer();
                        if (player instanceof ServerPlayer sp) {
                            QuestBookHelper.closeJournal(sp);
                        }
                        return 1;
                    }));

            LiteralArgumentBuilder<CommandSourceStack> prosperityCommand = literal("prosperity")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayer();
                        if (player instanceof ServerPlayer sp) {
                            QuestBookHelper.closeJournal(sp);
                            ProsperityService.open(ctx.getSource().getServer().overworld(), sp);
                        }
                        return 1;
                    });

            LiteralArgumentBuilder<CommandSourceStack> questTrackerCommand = literal("questtracker")
                    .executes(ctx -> toggleQuestTracker(ctx.getSource()))
                    .then(literal("on").executes(ctx -> setQuestTracker(ctx.getSource(), true)))
                    .then(literal("off").executes(ctx -> setQuestTracker(ctx.getSource(), false)));

            LiteralArgumentBuilder<CommandSourceStack> diagnoseCommand = literal("diagnose")
                    .executes(ctx -> diagnose(ctx.getSource(), ctx.getSource().getPlayer()))
                    .then(argument("player", EntityArgument.player())
                            .requires(AdminCommands::canManageRespawn)
                            .executes(ctx -> diagnose(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))));

            LiteralArgumentBuilder<CommandSourceStack> dailyQuestCommand = literal("daily")
                    .then(literal("accept").executes(ctx -> acceptQuest(ctx.getSource())))
                    .then(literal("choose")
                            .then(argument("quest", StringArgumentType.word())
                                    .executes(ctx -> chooseFirstDaily(
                                            ctx.getSource(),
                                            StringArgumentType.getString(ctx, "quest")))))
                    .then(literal("reroll").executes(ctx -> rerollDailyQuest(ctx.getSource())));

            LiteralArgumentBuilder<CommandSourceStack> partyCommand = literal("party")
                    .executes(ctx -> showParty(ctx.getSource()))
                    .then(literal("show").executes(ctx -> showParty(ctx.getSource())))
                    .then(literal("invite")
                            .then(argument("player", EntityArgument.player())
                                    .executes(ctx -> inviteToParty(
                                            ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")
                                    ))))
                    .then(literal("accept").executes(ctx -> acceptPartyInvite(ctx.getSource())))
                    .then(literal("decline").executes(ctx -> declinePartyInvite(ctx.getSource())))
                    .then(literal("share")
                            .then(literal("daily")
                                    .then(literal("accept").executes(ctx -> acceptPartyDailyShare(ctx.getSource())))
                                    .then(literal("decline").executes(ctx -> declinePartyDailyShare(ctx.getSource()))))
                            .then(literal("weekly")
                                    .then(literal("accept").executes(ctx -> acceptPartyWeeklyShare(ctx.getSource())))
                                    .then(literal("decline").executes(ctx -> declinePartyWeeklyShare(ctx.getSource()))))
                            .then(literal("story")
                                    .then(literal("accept").executes(ctx -> acceptPartyStoryShare(ctx.getSource())))
                                    .then(literal("decline").executes(ctx -> declinePartyStoryShare(ctx.getSource()))))
                            .then(literal("pilgrim")
                                    .then(literal("accept").executes(ctx -> acceptPartyPilgrimShare(ctx.getSource())))
                                    .then(literal("decline").executes(ctx -> declinePartyPilgrimShare(ctx.getSource())))))
                    .then(literal("leave").executes(ctx -> leaveParty(ctx.getSource())))
                    .then(literal("disband").executes(ctx -> disbandParty(ctx.getSource())));

            LiteralArgumentBuilder<CommandSourceStack> walletCommand = literal("wallet")
                    .executes(ctx -> showWallet(ctx.getSource()));

            // F7b: player-to-player transfers and the physical coin exchange.
            LiteralArgumentBuilder<CommandSourceStack> payCommand = literal("pay")
                    .then(argument("player", EntityArgument.player())
                            .then(argument("amount", LongArgumentType.longArg(1))
                                    .executes(ctx -> payPlayer(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player"),
                                            LongArgumentType.getLong(ctx, "amount"), null))
                                    .then(argument("unit", StringArgumentType.word())
                                            .suggests(CURRENCY_UNIT_SUGGESTIONS)
                                            .executes(ctx -> payPlayer(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player"),
                                                    LongArgumentType.getLong(ctx, "amount"),
                                                    StringArgumentType.getString(ctx, "unit"))))));

            LiteralArgumentBuilder<CommandSourceStack> exchangeCommand = literal("exchange")
                    .executes(ctx -> exchangeUsage(ctx.getSource()))
                    .then(literal("deposit").executes(ctx -> exchangeDeposit(ctx.getSource())))
                    .then(literal("withdraw")
                            .then(argument("amount", LongArgumentType.longArg(1))
                                    .executes(ctx -> exchangeWithdraw(ctx.getSource(),
                                            LongArgumentType.getLong(ctx, "amount"), null))
                                    .then(argument("unit", StringArgumentType.word())
                                            .suggests(CURRENCY_UNIT_SUGGESTIONS)
                                            .executes(ctx -> exchangeWithdraw(ctx.getSource(),
                                                    LongArgumentType.getLong(ctx, "amount"),
                                                    StringArgumentType.getString(ctx, "unit"))))));

            LiteralArgumentBuilder<CommandSourceStack> reputationCommand = literal("reputation")
                    .executes(ctx -> showReputation(ctx.getSource()));

            LiteralArgumentBuilder<CommandSourceStack> relayCommand = literal("relay")
                    .executes(ctx -> relayUsage(ctx.getSource()))
                    .then(literal("send")
                            .then(argument("player", StringArgumentType.word())
                                    .suggests(RELAY_TARGET_SUGGESTIONS)
                                    .executes(ctx -> relaySend(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "player")))))
                    .then(literal("cancel").executes(ctx -> relayCancel(ctx.getSource())))
                    .then(literal("status").executes(ctx -> relayStatus(ctx.getSource())));

            LiteralArgumentBuilder<CommandSourceStack> routesCommand = literal("routes")
                    .executes(ctx -> openRoutes(ctx.getSource()))
                    .then(literal("minimap").executes(ctx -> toggleRouteMinimap(ctx.getSource())))
                    .then(literal("guild").executes(ctx -> showTradeGuild(ctx.getSource())))
                    .then(literal("approach")
                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                    .then(argument("approach", StringArgumentType.word())
                                            .suggests(ROUTE_APPROACH_SUGGESTIONS)
                                            .executes(ctx -> setRouteApproach(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "route"),
                                                    StringArgumentType.getString(ctx, "approach"))))))
                    .then(literal("specialize")
                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                    .then(argument("specialization", StringArgumentType.word())
                                            .suggests(ROUTE_SPECIALIZATION_SUGGESTIONS)
                                            .executes(ctx -> specializeRoute(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "route"),
                                                    StringArgumentType.getString(ctx, "specialization"))))))
                    .then(literal("upgrade")
                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                    .then(argument("upgrade", StringArgumentType.word())
                                            .suggests(ROUTE_UPGRADE_SUGGESTIONS)
                                            .executes(ctx -> upgradeRoute(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "route"),
                                                    StringArgumentType.getString(ctx, "upgrade"))))))
                    .then(literal("contracts")
                            .executes(ctx -> showTradeContracts(ctx.getSource()))
                            .then(literal("accept")
                                    .then(argument("offer", IntegerArgumentType.integer(1, 3))
                                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                                    .executes(ctx -> acceptTradeContract(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "offer"),
                                                            IntegerArgumentType.getInteger(ctx, "route"))))))
                            .then(literal("supply").executes(ctx -> supplyTradeContract(ctx.getSource()))))
                    .then(literal("register").executes(ctx -> registerRoute(ctx.getSource())))
                    .then(literal("yard").executes(ctx -> registerYard(ctx.getSource())))
                    .then(literal("rename")
                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                    .then(argument("name", StringArgumentType.greedyString())
                                            .executes(ctx -> renameRoute(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "route"),
                                                    StringArgumentType.getString(ctx, "name"))))))
                    .then(literal("remove")
                            .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                    .executes(ctx -> removeRoute(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "route")))))
                    .then(literal("survey")
                            .then(literal("start")
                                    .then(argument("route", IntegerArgumentType.integer(1, TradeRouteService.MAX_ROUTES))
                                            .executes(ctx -> startRouteSurvey(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "route")))))
                            .then(literal("mark").executes(ctx -> markRouteSurvey(ctx.getSource())))
                            .then(literal("finish").executes(ctx -> finishRouteSurvey(ctx.getSource())))
                            .then(literal("cancel").executes(ctx -> cancelRouteSurvey(ctx.getSource()))));

            CommandNode<CommandSourceStack> traktCommand = dispatcher.register(literal("trakt")
                    .then(literal("rewards").then(literal("claim").executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        return player == null ? 0 : de.trakt.reward.VanillaRewardService.claim(ctx.getSource().getServer().overworld(), player);
                    })))
                    .then(literal("quests")
                            .then(literal("reload")
                                    .requires(AdminCommands::canManageRespawn)
                                    .executes(ctx -> reloadQuestDefinitions(ctx.getSource())))
                            .then(literal("list")
                                    .requires(AdminCommands::canManageRespawn)
                                    .executes(ctx -> listQuestDefinitions(ctx.getSource()))))
                    .then(questAdminCommand)
                    .then(journalCommand)
                    .then(prosperityCommand)
                    .then(questTrackerCommand)
                    .then(diagnoseCommand)
                    .then(dailyQuestCommand)
                    .then(partyCommand)
                    .then(walletCommand)
                    .then(payCommand)
                    .then(exchangeCommand)
                    .then(relayCommand)
                    .then(reputationCommand)
                    .then(routesCommand)
                    .then(VillageNetworkCommands.townCommand())
                    .then(VillageNetworkCommands.networkCommand())
                    .then(VillageNetworkCommands.guildCommand()));
            dispatcher.register(literal("tr").redirect(traktCommand));
        });
    }

    private static int reloadQuestDefinitions(CommandSourceStack source) {
        var report = de.trakt.quest.definition.QuestDefinitionRegistry.get().reloadFromDisk();
        de.trakt.quest.definition.QuestDefinitionRegistry.get().emitChangeEvent();
        source.sendSuccess(() -> Component.translatable("command.trakt.quests.reload.success",
                        report.total(), report.builtin(), report.datapack(), report.world(), report.api())
                .withStyle(ChatFormatting.GREEN), false);
        if (!report.problems().isEmpty()) {
            final int problems = report.problems().size();
            source.sendFailure(Component.translatable("command.trakt.quests.reload.problems", problems));
        }
        source.sendSystemMessage(Component.translatable("command.trakt.quests.reload.datapacks")
                .withStyle(ChatFormatting.GRAY));
        return report.problems().isEmpty() ? 1 : 0;
    }

    /** F1b: registry overview — every definition with its layer, state and rewards. */
    private static int listQuestDefinitions(CommandSourceStack source) {
        var registry = de.trakt.quest.definition.QuestDefinitionRegistry.get();
        var definitions = registry.all();
        final int enabledCount = (int) definitions.stream().filter(de.trakt.quest.definition.QuestDefinition::enabled).count();
        final int dailySlotCount = (int) definitions.stream()
                .filter(definition -> de.trakt.quest.daily.DailyQuestGenerator.isDailySlot(definition.id()))
                .count();
        source.sendSuccess(() -> Component.translatable("command.trakt.quests.list.header",
                        definitions.size(), enabledCount, dailySlotCount)
                .withStyle(ChatFormatting.GOLD), false);
        for (var definition : definitions) {
            String state = definition.enabled() ? "enabled" : "disabled";
            String cycle = de.trakt.quest.daily.DailyQuestGenerator.isDailySlot(definition.id()) ? "daily" : "custom";
            source.sendSystemMessage(Component.translatable("command.trakt.quests.list.entry",
                    definition.id(), definition.source().id(), cycle,
                    Component.translatable("command.trakt.quests.list.state." + state),
                    definitionTitle(definition)));
        }
        var problems = registry.problems();
        if (!problems.isEmpty()) {
            source.sendFailure(Component.translatable("command.trakt.quests.reload.problems", problems.size()));
            for (var entry : problems.entrySet()) {
                source.sendSystemMessage(Component.literal("  " + entry.getKey() + ": " + entry.getValue())
                        .withStyle(ChatFormatting.RED));
            }
        }
        return definitions.isEmpty() ? 0 : 1;
    }

    private static Component definitionTitle(de.trakt.quest.definition.QuestDefinition definition) {
        if (definition.usesLangKeys()) {
            return Component.translatable(definition.titleKey());
        }
        String locale = de.trakt.api.ApiConfig.get().defaultLocale();
        String title = definition.titles().get(locale);
        if (title == null || title.isBlank()) {
            title = definition.titles().get("en_us");
        }
        return title == null || title.isBlank() ? Component.literal(definition.id()) : Component.literal(title);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAdminWalletCommand() {
        return literal("wallet")
                .then(literal("show")
                        .executes(ctx -> showWalletForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                        .then(argument("player", EntityArgument.player())
                                .executes(ctx -> showWalletForPlayer(
                                        ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")
                                ))))
                .then(literal("add")
                        .then(argument("player", EntityArgument.player())
                                .then(argument("amount", LongArgumentType.longArg(1L))
                                        .executes(ctx -> adjustWalletForPlayer(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"),
                                                CurrencyService.CurrencyUnit.SILVERMARK,
                                                WalletAdjustmentMode.ADD
                                        ))
                                        .then(argument("unit", StringArgumentType.word())
                                                .suggests(WALLET_UNIT_SUGGESTIONS)
                                                .executes(ctx -> adjustWalletForPlayer(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        LongArgumentType.getLong(ctx, "amount"),
                                                        parseCurrencyUnit(StringArgumentType.getString(ctx, "unit")),
                                                        WalletAdjustmentMode.ADD
                                                ))))))
                .then(literal("remove")
                        .then(argument("player", EntityArgument.player())
                                .then(argument("amount", LongArgumentType.longArg(1L))
                                        .executes(ctx -> adjustWalletForPlayer(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"),
                                                CurrencyService.CurrencyUnit.SILVERMARK,
                                                WalletAdjustmentMode.REMOVE
                                        ))
                                        .then(argument("unit", StringArgumentType.word())
                                                .suggests(WALLET_UNIT_SUGGESTIONS)
                                                .executes(ctx -> adjustWalletForPlayer(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        LongArgumentType.getLong(ctx, "amount"),
                                                        parseCurrencyUnit(StringArgumentType.getString(ctx, "unit")),
                                                        WalletAdjustmentMode.REMOVE
                                                ))))))
                .then(literal("set")
                        .then(argument("player", EntityArgument.player())
                                .then(argument("amount", LongArgumentType.longArg(0L))
                                        .executes(ctx -> setWalletForPlayer(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"),
                                                CurrencyService.CurrencyUnit.SILVERMARK
                                        ))
                                        .then(argument("unit", StringArgumentType.word())
                                                .suggests(WALLET_UNIT_SUGGESTIONS)
                                                .executes(ctx -> setWalletForPlayer(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        LongArgumentType.getLong(ctx, "amount"),
                                                        parseCurrencyUnit(StringArgumentType.getString(ctx, "unit"))
                                                ))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAdminReputationCommand() {
        return literal("reputation")
                .then(literal("show")
                        .executes(ctx -> showReputationForPlayer(ctx.getSource(), ctx.getSource().getPlayer()))
                        .then(argument("player", EntityArgument.player())
                                .executes(ctx -> showReputationForPlayer(
                                        ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")
                                ))))
                .then(literal("add")
                        .then(argument("player", EntityArgument.player())
                                .then(argument("track", StringArgumentType.word())
                                        .suggests(REPUTATION_TRACK_SUGGESTIONS)
                                        .then(argument("amount", LongArgumentType.longArg(1L))
                                                .executes(ctx -> adjustReputationForPlayer(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        parseReputationTrack(StringArgumentType.getString(ctx, "track")),
                                                        LongArgumentType.getLong(ctx, "amount"),
                                                        false
                                                ))))))
                .then(literal("set")
                        .then(argument("player", EntityArgument.player())
                                .then(argument("track", StringArgumentType.word())
                                        .suggests(REPUTATION_TRACK_SUGGESTIONS)
                                        .then(argument("amount", LongArgumentType.longArg(0L))
                                                .executes(ctx -> adjustReputationForPlayer(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        parseReputationTrack(StringArgumentType.getString(ctx, "track")),
                                                        LongArgumentType.getLong(ctx, "amount"),
                                                        true
                                                ))))));
    }

    private static int acceptQuest(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player instanceof ServerPlayer sp) {
            var world = source.getServer().overworld();
            if (SpecialQuestService.acceptPendingOffer(world, sp)) {
            } else if (DailyQuestService.consumePendingShardOffer(world, sp.getUUID())) {
                DailyQuestService.activateShardBonusQuestOffer(world, sp);
            } else if (DailyQuestService.consumePendingBonusOffer(world, sp.getUUID())) {
                DailyQuestService.acceptBonusQuest(world, sp);
            } else if (DailyQuestService.consumePendingDailyOffer(world, sp.getUUID())) {
                DailyQuestService.acceptQuest(world, sp);
            }
        }
        return 1;
    }

    private static int chooseFirstDaily(CommandSourceStack source, String choiceKey) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        DailyQuestService.DailyQuestType choice = DailyQuestService.questFromString(choiceKey);
        return FirstDailyChoiceService.accept(source.getServer().overworld(), player, choice) ? 1 : 0;
    }

    private static int resetDailyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        DailyQuestService.adminResetDailyState(world, target.getUUID());
        VillageBondService.adminResetDailyState(world, target.getUUID());
        refreshQuestUi(world, target);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.resetdaily.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.resetdaily.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.resetdaily.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int prepareNextDailyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        DailyQuestService.DailyQuestType previousChoice = DailyQuestService.adminPrepareNextDaily(world, target.getUUID());
        refreshQuestUi(world, target);
        Component previousName = previousChoice == null
                ? Component.literal("-")
                : DailyQuestService.displayKey(previousChoice);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.nextdaily.self", previousName).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.nextdaily.other", target.getDisplayName(), previousName).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.nextdaily.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int completeDailyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        DailyQuestService.DailyQuestType activeQuest = DailyQuestService.activeQuestType(world, target.getUUID());
        if (activeQuest == null || !DailyQuestService.adminForceCompleteDaily(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completedaily.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        Component questName = DailyQuestService.displayKey(activeQuest);
        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completedaily.self", questName).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completedaily.other", target.getDisplayName(), questName).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.completedaily.notify", questName).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int resetWeeklyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        WeeklyQuestService.adminResetWeeklyState(world, target.getUUID());
        refreshQuestUi(world, target);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.resetweekly.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.resetweekly.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.resetweekly.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int prepareNextWeeklyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        WeeklyQuestService.WeeklyQuestType previousChoice = WeeklyQuestService.adminPrepareNextWeekly(world, target.getUUID());
        refreshQuestUi(world, target);
        Component previousName = previousChoice == null ? Component.literal("-") : WeeklyQuestService.displayName(previousChoice);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.nextweekly.self", previousName).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.nextweekly.other", target.getDisplayName(), previousName).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.nextweekly.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int completeWeeklyForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        WeeklyQuestService.WeeklyQuestType activeQuest = WeeklyQuestService.activeQuestType(world, target.getUUID());
        if (activeQuest == null || !WeeklyQuestService.adminForceCompleteWeekly(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completeweekly.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        Component questName = WeeklyQuestService.displayName(activeQuest);
        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completeweekly.self", questName).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.completeweekly.other", target.getDisplayName(), questName).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.completeweekly.notify", questName).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int showStoryForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        var world = source.getServer().overworld();
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.header", target.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
        for (Component line : StoryQuestService.buildOverview(world, target.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int resetStoryForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!StoryQuestService.adminResetStoryState(world, target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.reset.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        ShadowsTradeRoadEncounterService.adminResetPlayer(world, target.getUUID());
        refreshQuestUi(world, target);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.reset.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.reset.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.story.reset.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int completeStoryForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!StoryQuestService.adminForceCompleteActiveChapter(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.complete.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.complete.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.complete.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.story.complete.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int unlockShadowsForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!ShadowsTradeRoadEncounterService.adminUnlockForTesting(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.shadows.unlock.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.story.shadows.unlock.success",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int prepareShadowsEncounterTest(CommandSourceStack source, ServerPlayer target, boolean finalConvoy) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!ShadowsTradeRoadEncounterService.adminPrepareEncounterTest(world, target, finalConvoy)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.story.shadows.test.failed").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                finalConvoy
                        ? "command.trakt.questadmin.story.shadows.test.final"
                        : "command.trakt.questadmin.story.shadows.test.rescue",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int showProjectsForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        var world = source.getServer().overworld();
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.project.header", target.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
        for (Component line : VillageProjectService.buildOverview(world, target.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int setProjectForPlayer(CommandSourceStack source, ServerPlayer target, VillageProjectType project, boolean unlocked) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (project == null || project.alwaysUnlocked()) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.project.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!VillageProjectService.setUnlocked(world, target.getUUID(), project, unlocked)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.project.no_change").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (unlocked && project == VillageProjectType.CARAVAN_YARD) {
            TradeRouteService.initializeCaravanYard(world, target);
        } else if (unlocked && project == VillageProjectType.MARKET_CHARTER) {
            TradeRouteService.initializeProvisionalNetwork(world, target);
        }
        refreshQuestUi(world, target);

        source.sendSuccess(() -> Component.translatable(
                unlocked
                        ? "command.trakt.questadmin.project.unlock"
                        : "command.trakt.questadmin.project.lock",
                target.getDisplayName(),
                Component.translatable("quest.trakt.project." + project.id() + ".title")
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int showWallet(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        long balance = CurrencyService.getBalance(source.getServer().overworld(), player.getUUID());
        source.sendSuccess(() -> Component.empty()
                .append(Component.translatable("command.trakt.wallet.balance").withStyle(ChatFormatting.GRAY))
                .append(CurrencyService.formatBalance(balance)), false);
        return 1;
    }

    /** F7b: /trakt pay <player> <amount> [unit] — balance transfer between online players. */
    private static int payPlayer(CommandSourceStack source, ServerPlayer target, long amount, String rawUnit) {
        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (target.getUUID().equals(sender.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.pay.self").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        CurrencyService.CurrencyUnit unit = CurrencyService.parseUnit(rawUnit);
        if (unit == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.wallet.unit.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        long base = CurrencyService.toBase(amount, unit);
        ServerLevel world = source.getServer().overworld();
        if (!CurrencyService.transfer(world, sender.getUUID(), target.getUUID(), base)) {
            source.sendSuccess(() -> Component.empty()
                    .append(Component.translatable("command.trakt.wallet.insufficient",
                            CurrencyService.formatBalance(CurrencyService.getBalance(world, sender.getUUID())))
                            .withStyle(ChatFormatting.RED)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.trakt.pay.sent",
                CurrencyService.formatBalance(base), target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.pay.received",
                CurrencyService.formatBalance(base), sender.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** F3b: /trakt relay — usage line. */
    private static int relayUsage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.trakt.relay.usage").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** F3b: /trakt relay send — dispatch the terminal draft to another player's terminal. */
    private static int relaySend(CommandSourceStack source, String targetName) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        ServerLevel world = (ServerLevel) player.level();
        return de.trakt.relay.RelayService.send(world, player, targetName) ? 1 : 0;
    }

    /** F3b: /trakt relay cancel — take the draft back or recall a shipment in transit. */
    private static int relayCancel(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return de.trakt.relay.RelayService.cancel((ServerLevel) player.level(), player) ? 1 : 0;
    }

    /** F3b: /trakt relay status — everything this player sends, awaits or can pick up. */
    private static int relayStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        de.trakt.relay.RelayService.sendStatus((ServerLevel) player.level(), player);
        return 1;
    }

    /** F7b: /trakt exchange — usage line plus the current balance. */
    private static int exchangeUsage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.trakt.exchange.usage").withStyle(ChatFormatting.GRAY), false);
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            long balance = CurrencyService.getBalance(source.getServer().overworld(), player.getUUID());
            source.sendSuccess(() -> Component.empty()
                    .append(Component.translatable("command.trakt.wallet.balance").withStyle(ChatFormatting.GRAY))
                    .append(CurrencyService.formatBalance(balance)), false);
        }
        return 1;
    }

    /** F7b: /trakt exchange deposit — every coin item in the inventory becomes balance. */
    private static int exchangeDeposit(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        CurrencyService.LegacyCoinConversion conversion =
                CurrencyService.convertLegacyCoins(source.getServer().overworld(), player);
        if (conversion.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.trakt.wallet.convert.none").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.trakt.wallet.convert.success",
                CurrencyService.formatLegacyCoinBreakdown(conversion.copperCount(), conversion.ironCount(), conversion.goldCount()),
                CurrencyService.formatDelta(conversion.addedAmount()),
                CurrencyService.formatBalance(conversion.newBalance())).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * F7b: /trakt exchange withdraw <amount> [unit] — balance to physical coins.
     * With an explicit unit every minted item is of that denomination; without one
     * the amount is read in silvermarks and minted as the optimal mix.
     */
    private static int exchangeWithdraw(CommandSourceStack source, long amount, String rawUnit) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        CurrencyService.CurrencyUnit unit = CurrencyService.parseUnit(rawUnit);
        if (unit == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.wallet.unit.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        ServerLevel world = source.getServer().overworld();
        long base = CurrencyService.toBase(amount, unit);
        if (!CurrencyService.removeBalance(world, player.getUUID(), base)) {
            source.sendSuccess(() -> Component.empty()
                    .append(Component.translatable("command.trakt.wallet.insufficient",
                            CurrencyService.formatBalance(CurrencyService.getBalance(world, player.getUUID())))
                            .withStyle(ChatFormatting.RED)), false);
            return 0;
        }
        CurrencyService.CoinMix mix = rawUnit == null || rawUnit.isEmpty()
                ? CurrencyService.optimalMix(base)
                : CurrencyService.exactUnit(amount, unit);
        CurrencyService.mintIntoInventory(player, mix);
        source.sendSuccess(() -> Component.translatable("command.trakt.exchange.withdrawn",
                CurrencyService.formatLegacyCoinBreakdown(
                        (int) mix.coppers(), (int) mix.silvermarks(), (int) mix.crowns()),
                CurrencyService.formatBalance(CurrencyService.getBalance(world, player.getUUID())))
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int diagnose(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("command.trakt.diagnose.player_required"));
            return 0;
        }
        ServerLevel world = source.getServer().overworld();
        for (Component line : TradeRouteService.diagnostics(world, player)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int openRoutes(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        QuestBookHelper.closeJournal(player);
        if (!TradeRouteService.hasRouteAccess(source.getServer().overworld(), player.getUUID())) {
            player.sendSystemMessage(Component.translatable("message.trakt.trade_route.locked").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        TradeRouteService.openMap(source.getServer().overworld(), player);
        return 1;
    }

    private static int registerRoute(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.registerCurrentVillage(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int registerYard(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.registerPlayerYard(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int rerollDailyQuest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && DailyQuestService.rerollQuestForToday(
                source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int showTradeGuild(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        for (Component line : TradeGuildService.overview(source.getServer().overworld(), player.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int specializeRoute(CommandSourceStack source, int routeNumber, String rawSpecialization) {
        ServerPlayer player = source.getPlayer();
        TradeRouteSpecialization specialization = TradeRouteSpecialization.fromName(rawSpecialization);
        return player != null && specialization != null && TradeRouteService.specialize(
                source.getServer().overworld(), player, routeNumber - 1, specialization) ? 1 : 0;
    }

    private static int upgradeRoute(CommandSourceStack source, int routeNumber, String rawUpgrade) {
        ServerPlayer player = source.getPlayer();
        TradeRouteUpgrade upgrade = TradeRouteUpgrade.fromName(rawUpgrade);
        return player != null && upgrade != null && TradeRouteService.buyUpgrade(
                source.getServer().overworld(), player, routeNumber - 1, upgrade) ? 1 : 0;
    }

    private static int showTradeContracts(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        for (Component line : TradeGuildService.contractBoard(source.getServer().overworld(), player.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int acceptTradeContract(CommandSourceStack source, int offer, int routeNumber) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeGuildService.acceptContract(
                source.getServer().overworld(), player, offer, routeNumber - 1) ? 1 : 0;
    }

    private static int supplyTradeContract(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeGuildService.supplyContract(
                source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int removeRoute(CommandSourceStack source, int routeNumber) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.removeRoute(
                source.getServer().overworld(), player, routeNumber - 1) ? 1 : 0;
    }

    private static int renameRoute(CommandSourceStack source, int routeNumber, String name) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.renameRoute(
                source.getServer().overworld(), player, routeNumber - 1, name) ? 1 : 0;
    }

    private static int startRouteSurvey(CommandSourceStack source, int routeNumber) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.startRouteSurvey(
                source.getServer().overworld(), player, routeNumber - 1) ? 1 : 0;
    }

    private static int markRouteSurvey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.markSurveyWaypoint(
                source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int finishRouteSurvey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.finishRouteSurvey(
                source.getServer().overworld(), player, -1) ? 1 : 0;
    }

    private static int cancelRouteSurvey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && TradeRouteService.cancelRouteSurvey(
                source.getServer().overworld(), player, -1) ? 1 : 0;
    }

    private static int resetRoutesForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        TradeRouteService.resetRoutes(source.getServer().overworld(), target.getUUID());
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.routes.reset", target.getDisplayName())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int setupRouteTest(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        var world = source.getServer().overworld();
        StoryQuestService.adminUnlockEmptyCaravanForTesting(world, target.getUUID());
        TradeRouteService.adminCreateTestNetwork(world, target);
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.routes.testsetup", target.getDisplayName())
                .withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.routes.testsetup.hint")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int setupEconomyTest(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        var world = source.getServer().overworld();
        ProsperityService.resetForTesting(world, target.getUUID());
        VillageProjectService.unlock(world, target.getUUID(), VillageProjectType.APIARY_CHARTER);
        VillageProjectService.unlock(world, target.getUUID(), VillageProjectType.FORGE_CHARTER);
        VillageProjectService.unlock(world, target.getUUID(), VillageProjectType.MARKET_CHARTER);
        VillageProjectService.unlock(world, target.getUUID(), VillageProjectType.PASTURE_CHARTER);
        VillageProjectService.unlock(world, target.getUUID(), VillageProjectType.WATCH_BELL);
        StoryQuestService.adminUnlockEmptyCaravanForTesting(world, target.getUUID());
        TradeRouteService.adminCreateTestNetwork(world, target);
        CurrencyService.setBalance(world, target.getUUID(), CurrencyService.CROWN * 500L);
        ProsperityService.open(world, target);
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.economy.testsetup", target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable(
                "command.trakt.questadmin.economy.testsetup.hint"
        ).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int setupShrineTest(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        de.trakt.shrine.VillageBondService.adminTestSetup(source.getServer().overworld(), target);
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shrines.testsetup",
                target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.shrines.testsetup.hint")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int reverseShrineTest(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        VillageBondService.AdminReverseResult result = VillageBondService.adminReverseTestSetup(
                source.getServer().overworld(), target);
        if (!result.reversed()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.trakt.questadmin.shrines.reverse.no_test", target.getDisplayName()
            ).withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.shrines.reverse",
                target.getDisplayName(), CurrencyService.formatBalance(result.currencyRemoved())
        ).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable(
                "command.trakt.questadmin.shrines.reverse.hint"
        ).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int setRouteTestEvent(CommandSourceStack source, int routeNumber, String eventKey) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        boolean changed = TradeRouteService.adminSetTestEvent(
                source.getServer().overworld(), player, routeNumber - 1, eventKey);
        return changed ? 1 : 0;
    }

    private static int toggleRouteMinimap(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        TradeRouteService.toggleMinimap(source.getServer().overworld(), player);
        return 1;
    }

    private static int showReputation(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        QuestBookHelper.closeJournal(player);

        var world = source.getServer().overworld();
        source.sendSuccess(() -> Component.translatable("command.trakt.reputation.header").withStyle(ChatFormatting.GOLD), false);
        for (Component line : ReputationService.buildOverview(world, player.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int showReputationForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        var world = source.getServer().overworld();
        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.reputation.header", target.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
        for (Component line : ReputationService.buildOverview(world, target.getUUID())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int adjustReputationForPlayer(CommandSourceStack source,
                                                 ServerPlayer target,
                                                 ReputationService.ReputationTrack track,
                                                 long amount,
                                                 boolean setValue) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (track == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.reputation.track.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        long clamped = Math.max(0L, Math.min(Integer.MAX_VALUE, amount));
        int current = ReputationService.get(world, target.getUUID(), track);
        int next = setValue ? (int) clamped : current + (int) clamped;
        if (setValue) {
            int delta = next - current;
            if (delta != 0) {
                ReputationService.add(world, target.getUUID(), track, delta);
            }
        } else {
            ReputationService.add(world, target.getUUID(), track, (int) clamped);
        }

        source.sendSuccess(() -> Component.translatable(
                setValue ? "command.trakt.questadmin.reputation.set" : "command.trakt.questadmin.reputation.add",
                target.getDisplayName(),
                ReputationService.displayName(track),
                next
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> buildUiTestCommand() {
        return literal("uitest").executes(ctx -> AdminUiPreviewService.start(ctx.getSource()));
    }

    private static int toggleQuestTracker(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        boolean enabled = QuestTrackerService.toggle(source.getServer().overworld(), player);
        source.sendSuccess(() -> Component.translatable(
                enabled ? "command.trakt.questtracker.enabled" : "command.trakt.questtracker.disabled"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int setRouteApproach(CommandSourceStack source, int routeNumber, String rawApproach) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        TradeRouteIncidentApproach approach = TradeRouteIncidentApproach.byKey(rawApproach);
        if (approach == null || !TradeRouteService.setIncidentApproach(
                source.getServer().overworld(), player, routeNumber - 1, approach)) {
            player.sendSystemMessage(Component.translatable("command.trakt.routes.approach.invalid")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        player.sendSystemMessage(Component.translatable("command.trakt.routes.approach.set",
                routeNumber, approach.label(), approach.terms()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int setQuestTracker(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        QuestTrackerService.setEnabled(source.getServer().overworld(), player, enabled);
        source.sendSuccess(() -> Component.translatable(
                enabled ? "command.trakt.questtracker.enabled" : "command.trakt.questtracker.disabled"
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int startShardCacheForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!ShardRelicQuestService.adminStartCacheHunt(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcache.failed").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcache.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcache.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.shardcache.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int teleportToShardCacheForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!ShardRelicQuestService.adminTeleportToCache(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcachetp.failed").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcachetp.self").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.shardcachetp.other", target.getDisplayName()).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable("command.trakt.questadmin.shardcachetp.notify").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int startCoreTestForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (!AdminCoreTestQuestService.adminStart(world, target)) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.coretest.show.none", target.getDisplayName()).withStyle(ChatFormatting.RED), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.coretest.start",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int resetCoreTestForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        AdminCoreTestQuestService.adminReset(world, target);
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.coretest.reset",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int showCoreTestForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        var status = AdminCoreTestQuestService.openStatus(world, target.getUUID());
        if (status == null) {
            source.sendSuccess(() -> Component.translatable(
                    "command.trakt.questadmin.coretest.show.none",
                    target.getDisplayName()
            ).withStyle(ChatFormatting.RED), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.coretest.show.header",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> status.title().copy().withStyle(ChatFormatting.AQUA), false);
        for (Component line : status.lines()) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int showWalletForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        long balance = CurrencyService.getBalance(source.getServer().overworld(), target.getUUID());
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.wallet.show",
                target.getDisplayName(),
                CurrencyService.formatBalance(balance)
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int adjustWalletForPlayer(CommandSourceStack source,
                                             ServerPlayer target,
                                             long amount,
                                             CurrencyService.CurrencyUnit unit,
                                             WalletAdjustmentMode mode) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (unit == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.wallet.unit.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        long delta = CurrencyService.toBase(amount, unit);
        long newBalance;
        if (mode == WalletAdjustmentMode.ADD) {
            newBalance = CurrencyService.addBalance(world, target.getUUID(), delta);
        } else {
            long currentBalance = CurrencyService.getBalance(world, target.getUUID());
            if (!CurrencyService.removeBalance(world, target.getUUID(), delta)) {
                source.sendSuccess(() -> Component.translatable(
                        "command.trakt.questadmin.wallet.remove.insufficient",
                        target.getDisplayName(),
                        CurrencyService.formatBalance(currentBalance)
                ).withStyle(ChatFormatting.RED), false);
                return 0;
            }
            newBalance = CurrencyService.getBalance(world, target.getUUID());
            delta = -delta;
        }

        long finalDelta = delta;
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.wallet.adjust",
                target.getDisplayName(),
                CurrencyService.formatDelta(finalDelta),
                CurrencyService.formatBalance(newBalance)
        ).withStyle(ChatFormatting.GREEN), false);
        if (shouldNotifyWalletTarget(source, target)) {
            target.sendSystemMessage(Component.translatable(
                    "command.trakt.questadmin.wallet.adjust.notify",
                    CurrencyService.formatDelta(finalDelta),
                    CurrencyService.formatBalance(newBalance)
            ).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int setWalletForPlayer(CommandSourceStack source,
                                          ServerPlayer target,
                                          long amount,
                                          CurrencyService.CurrencyUnit unit) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (unit == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.wallet.unit.invalid").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        long newBalance = CurrencyService.setBalance(world, target.getUUID(), CurrencyService.toBase(amount, unit));
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.wallet.set",
                target.getDisplayName(),
                CurrencyService.formatBalance(newBalance)
        ).withStyle(ChatFormatting.GREEN), false);
        if (shouldNotifyWalletTarget(source, target)) {
            target.sendSystemMessage(Component.translatable(
                    "command.trakt.questadmin.wallet.set.notify",
                    CurrencyService.formatBalance(newBalance)
            ).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static boolean shouldNotifyWalletTarget(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer executor = source.getPlayer();
        return executor == null || !executor.getUUID().equals(target.getUUID());
    }

    private static int spawnPilgrimForPlayer(CommandSourceStack source, ServerPlayer target) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        if (PilgrimService.findActivePilgrim(world) != null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.pilgrim.spawn.exists").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var pilgrim = PilgrimService.spawnNearPlayer(world, target, true);
        if (pilgrim == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.pilgrim.spawn.failed").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.pilgrim.spawn.success",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int despawnPilgrims(CommandSourceStack source) {
        int removed = PilgrimService.despawnAll(source.getServer().overworld());
        if (removed <= 0) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.pilgrim.despawn.none").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.pilgrim.despawn.success",
                removed
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int resetComplete(CommandSourceStack source) {
        var server = source.getServer();
        var world = server.overworld();
        int onlinePlayers = server.getPlayerList().getPlayers().size();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestMasterUiService.close(player);
            QuestBookHelper.closeJournal(player);
            QuestTrackerService.closeTracker(player);
            PilgrimService.closeTrade(player);
        }

        QuestBookHelper.resetAllSessions();
        QuestTrackerService.resetAllRuntimeState();
        QuestMasterUiService.resetAllSessions();
        QuestDropTracker.clear();
        QuestHarvestTracker.clear();
        QuestSoundFeedback.resetRuntimeState();
        QuestPartyService.resetRuntimeState();
        MerchantSealQuestService.resetRuntimeState();
        SurveyorCompassQuestService.resetRuntimeState();
        ShadowsTradeRoadEncounterService.resetRuntimeState();
        TradeRouteService.despawnAll(world);
        EmptyCaravanStoryService.despawnAll(world);
        int removedQuestMasters = QuestMasterService.despawnAll(world);
        int removedPilgrims = PilgrimService.despawnAll(world);
        int removedTradeRoad = ShadowsTradeRoadEncounterService.despawnAll(world);
        QuestState.get(server).resetAllProgress();
        LivingVillageNetworkState.get(server).resetAllProgress();
        VillageGuildState.get(server).resetAllProgress();
        GuildTownSharedState.get(server).resetAllProgress();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.translatable("command.trakt.questadmin.reset.complete.notify").withStyle(ChatFormatting.GRAY), false);
        }

        source.sendSuccess(() -> Component.translatable(
                "command.trakt.questadmin.reset.complete.success",
                onlinePlayers,
                removedQuestMasters,
                removedPilgrims + removedTradeRoad
        ).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int setPilgrimRumorOverride(CommandSourceStack source, ServerPlayer target, boolean enabled) {
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        var world = source.getServer().overworld();
        PilgrimContractService.adminSetRumorUnlocked(world, target, enabled);
        refreshQuestUi(world, target);

        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID())) {
            source.sendSuccess(() -> Component.translatable(
                    enabled
                            ? "command.trakt.questadmin.pilgrim.rumor.unlock.self"
                            : "command.trakt.questadmin.pilgrim.rumor.lock.self"
            ).withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable(
                enabled
                        ? "command.trakt.questadmin.pilgrim.rumor.unlock.other"
                        : "command.trakt.questadmin.pilgrim.rumor.lock.other",
                target.getDisplayName()
        ).withStyle(ChatFormatting.GREEN), false);
        target.sendSystemMessage(Component.translatable(
                enabled
                        ? "command.trakt.questadmin.pilgrim.rumor.unlock.notify"
                        : "command.trakt.questadmin.pilgrim.rumor.lock.notify"
        ).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static CurrencyService.CurrencyUnit parseCurrencyUnit(String raw) {
        return CurrencyService.parseUnit(raw);
    }

    private static int showParty(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        if (!QuestPartyService.isEnabled(source.getServer())) {
            source.sendSuccess(() -> Component.translatable("message.trakt.party.unavailable").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        QuestPartyService.PartySnapshot snapshot = QuestPartyService.snapshot(player);
        source.sendSuccess(() -> snapshot.summary().copy(), false);
        if (!snapshot.hasParty()) {
            source.sendSuccess(() -> Component.translatable("command.trakt.party.none").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        for (QuestPartyService.PartyMemberView member : snapshot.members()) {
            Component line = member.name().copy();
            if (member.leader()) {
                line = line.copy().append(Component.literal(" ")).append(Component.translatable("command.trakt.party.member.leader").withStyle(ChatFormatting.GOLD));
            }
            if (member.self()) {
                line = line.copy().append(Component.literal(" ")).append(Component.translatable("command.trakt.party.member.self").withStyle(ChatFormatting.GRAY));
            }
            Component finalLine = line;
            source.sendSuccess(() -> finalLine, false);
        }
        return 1;
    }

    private static int inviteToParty(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer player = source.getPlayer();
        if (player == null || target == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.invite(source.getServer().overworld(), player, target) ? 1 : 0;
    }

    private static int acceptPartyInvite(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.acceptInvite(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int declinePartyInvite(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.declineInvite(source.getServer(), player) ? 1 : 0;
    }

    private static int leaveParty(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.leave(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int acceptPartyDailyShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.acceptDailyOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int declinePartyDailyShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.declineDailyOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int acceptPartyWeeklyShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.acceptWeeklyOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int declinePartyWeeklyShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.declineWeeklyOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int acceptPartyStoryShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.acceptStoryOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int declinePartyStoryShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.declineStoryOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int acceptPartyPilgrimShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.acceptPilgrimOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int declinePartyPilgrimShare(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.declinePilgrimOffer(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static int disbandParty(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.translatable("command.trakt.questadmin.player_required").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return QuestPartyService.disband(source.getServer().overworld(), player) ? 1 : 0;
    }

    private static ReputationService.ReputationTrack parseReputationTrack(String raw) {
        return ReputationService.parseTrack(raw);
    }

    private static VillageProjectType parseProject(String raw) {
        return VillageProjectType.fromId(raw);
    }

    private static void refreshQuestUi(net.minecraft.server.level.ServerLevel world, ServerPlayer player) {
        if (world == null || player == null) {
            return;
        }
        QuestBookHelper.refreshQuestBook(world, player);
        QuestTrackerService.refresh(world, player);
        QuestMasterUiService.refreshIfOpen(world, player);
        PilgrimService.refreshIfTrading(world, player);
    }

    private enum WalletAdjustmentMode {
        ADD,
        REMOVE
    }
}
