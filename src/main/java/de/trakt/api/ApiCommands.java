package de.trakt.api;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.trakt.api.quests.CustomQuestDefinition;
import de.trakt.api.quests.CustomQuestManager;
import de.trakt.api.quests.CustomQuestService;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * In-game commands added by the API subsystem. Registers children under the existing
 * {@code /trakt} (alias {@code /trakt}) tree; Brigadier merges them with the nodes
 * registered by QuestCommands.
 *
 * <ul>
 *   <li>{@code /trakt custom} — list, accept, abandon panel-created custom quests</li>
 *   <li>{@code /trakt link} — Minecraft ↔ Discord account binding codes</li>
 *   <li>{@code /trakt api} — admin: API status, token rotation, config reload</li>
 * </ul>
 *
 * <p>Feedback strings are Russian literals on purpose: this fork targets a Russian server and
 * adding keys to the four localization files would churn the CI parity/ledger gates.
 */
final class ApiCommands {
    private ApiCommands() {}

    private static final SuggestionProvider<CommandSourceStack> CUSTOM_QUEST_SUGGESTIONS = (context, builder) -> {
        var source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return SharedSuggestionProvider.suggest(java.util.List.of(), builder);
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (CustomQuestDefinition definition : CustomQuestService.availableFor(
                source.getServer(), player.getUUID())) {
            ids.add(definition.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> customCommand = Commands.literal("custom")
                .executes(context -> listCustom(context.getSource()))
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(CUSTOM_QUEST_SUGGESTIONS)
                                .executes(context -> acceptCustom(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("abandon")
                        .executes(context -> abandonCustom(context.getSource())));

        LiteralArgumentBuilder<CommandSourceStack> linkCommand = Commands.literal("link")
                .executes(context -> startLink(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> linkStatus(context.getSource())))
                .then(Commands.literal("cancel")
                        .executes(context -> cancelLink(context.getSource())));

        LiteralArgumentBuilder<CommandSourceStack> apiCommand = Commands.literal("api")
                .requires(de.trakt.commands.AdminCommands::canManageRespawn)
                .executes(context -> apiStatus(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> apiStatus(context.getSource())))
                .then(Commands.literal("token")
                        .then(Commands.literal("rotate")
                                .executes(context -> rotateToken(context.getSource())))
                        .then(Commands.literal("show")
                                .executes(context -> showToken(context.getSource()))))
                .then(Commands.literal("reload")
                        .executes(context -> reloadConfig(context.getSource())));

        // Registers children on the existing /trakt node; /trakt redirect covers them too.
        dispatcher.register(Commands.literal("trakt")
                .then(customCommand)
                .then(linkCommand)
                .then(apiCommand));
    }

    // ------------------------------------------------------------------ /trakt custom

    private static int listCustom(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        String locale = ApiConfig.get().defaultLocale();
        var active = CustomQuestService.activeQuest(source.getServer(), player.getUUID());
        if (active != null) {
            source.sendSuccess(() -> Component.literal("Активное задание:")
                    .withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal("  " + active.definition().title(locale)
                    + " — " + active.progress() + "/" + active.target()
                    + (active.definition().goal().kind() == CustomQuestDefinition.GoalKind.VILLAGER_TRADE
                    || active.definition().goal().kind() == CustomQuestDefinition.GoalKind.SHEAR_SHEEP
                    ? "" : " (" + CustomQuestService.goalSubject(active.definition(), locale) + ")"))
                    .withStyle(ChatFormatting.YELLOW), false);
            source.sendSuccess(() -> Component.literal("  Отменить: /trakt custom abandon")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        var available = CustomQuestService.availableFor(source.getServer(), player.getUUID());
        if (available.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    active == null ? "Доступных заданий нет. Попросите админа создать их через панель."
                            : "Других доступных заданий нет.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Доступные задания (принять: /trakt custom accept <id>):")
                .withStyle(ChatFormatting.GREEN), false);
        for (CustomQuestDefinition definition : available) {
            String repeatNote = CustomQuestDefinition.REPEAT_DAILY.equals(definition.repeat())
                    ? " · ежедневно" : " · одноразовое";
            source.sendSuccess(() -> Component.literal("  " + definition.id() + " — "
                    + definition.title(locale) + repeatNote).withStyle(ChatFormatting.YELLOW), false);
            String offer = definition.offer(locale);
            if (!offer.isBlank()) {
                source.sendSuccess(() -> Component.literal("    " + offer).withStyle(ChatFormatting.GRAY), false);
            }
        }
        return available.size();
    }

    private static int acceptCustom(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        String error = CustomQuestService.accept(
                source.getServer().overworld(), player, id);
        if (error != null) {
            source.sendSuccess(() -> Component.literal(error).withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return 1;
    }

    private static int abandonCustom(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        if (!CustomQuestService.abandon(source.getServer().overworld(), player)) {
            source.sendSuccess(() -> Component.literal("У вас нет активного задания.")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        return 1;
    }

    // ------------------------------------------------------------------ /trakt link

    private static int startLink(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        UUID uuid = player.getUUID();
        ApiLinkStore.LinkEntry existing = ApiService.links().linkForMc(uuid);
        if (existing != null) {
            source.sendSuccess(() -> Component.literal(
                    "Аккаунт уже привязан к Discord ID " + existing.discordId()
                            + ". Перепривязка: отмените текущую (/trakt link cancel не отвязывает — отвязка через панель) и получите новый код.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        ApiLinkStore.PendingCode pending = ApiService.links().createCode(
                uuid, player.getGameProfile().name(),
                ApiConfig.get().linkCodeTtlMinutes() * 60_000L);
        source.sendSuccess(() -> Component.literal("Код привязки: " + pending.code())
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(
                "Отправьте его боту в Discord командой /linkmc " + pending.code()
                        + " в течение " + ApiConfig.get().linkCodeTtlMinutes() + " минут.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int linkStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        UUID uuid = player.getUUID();
        ApiLinkStore.LinkEntry link = ApiService.links().linkForMc(uuid);
        if (link != null) {
            String username = link.discordUsername().isBlank() ? "" : " (" + link.discordUsername() + ")";
            source.sendSuccess(() -> Component.literal(
                    "Привязан к Discord ID " + link.discordId() + username + ".")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        ApiLinkStore.PendingCode pending = ApiService.links().pendingFor(uuid);
        if (pending != null) {
            long minutesLeft = Math.max(1, (pending.expiresAtMs() - System.currentTimeMillis()) / 60_000L);
            source.sendSuccess(() -> Component.literal(
                    "Код " + pending.code() + " ждёт подтверждения (осталось ~" + minutesLeft + " мин).")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Аккаунт не привязан. Начните: /trakt link")
                .withStyle(ChatFormatting.GRAY), false);
        return 0;
    }

    private static int cancelLink(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSuccess(() -> Component.literal("Только для игроков."), false);
            return 0;
        }
        if (ApiService.links().cancelCode(player.getUUID())) {
            source.sendSuccess(() -> Component.literal("Код привязки отменён.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Активного кода нет.").withStyle(ChatFormatting.RED), false);
        return 0;
    }

    // ------------------------------------------------------------------ /trakt api (admin)

    private static String masked(String token) {
        return token.length() <= 8
                ? "****"
                : token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

    private static int apiStatus(CommandSourceStack source) {
        ApiConfig config = ApiConfig.get();
        boolean agent = "agent".equalsIgnoreCase(config.transport());
        boolean running = agent ? ApiAgent.get().running() : ApiServer.get().running();
        source.sendSuccess(() -> Component.literal("Trakt API: "
                + (config.enabled() ? (running ? "запущен" : "включён, но не запущен") : "выключен"))
                .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        if (agent) {
            ApiAgent state = ApiAgent.get();
            source.sendSuccess(() -> Component.literal(
                    "  Транспорт: агент (исходящие запросы к панели — порты не нужны)"), false);
            source.sendSuccess(() -> Component.literal("  Панель: " + config.panelUrl()
                    + " (server_id=" + config.serverId() + ", опрос каждые "
                    + config.pollSeconds() + " с)"), false);
            source.sendSuccess(() -> Component.literal("  Связь: "
                    + (state.connected() ? "установлена"
                            : "нет (" + (state.lastError().isBlank()
                                    ? "ещё не было цикла" : state.lastError()) + ")")
                    + (state.lastSuccessMs() > 0 ? ", последний успешный цикл "
                            + ((System.currentTimeMillis() - state.lastSuccessMs()) / 1000) + " с назад"
                            : "")), false);
            source.sendSuccess(() -> Component.literal("  Команд выполнено: " + state.commandsExecuted()
                    + ", события доставлены до id: " + state.lastAckedEventId()
                    + ", ошибок связи: " + state.errorsTotal()), false);
            source.sendSuccess(() -> Component.literal("  Токен гейтвея: " + masked(config.gatewayToken())
                    + " (полностью: /trakt api token show; этот же токен вводится в панели)"), false);
        } else {
            source.sendSuccess(() -> Component.literal("  Адрес: http://" + config.bind() + ":" + config.port()
                    + "/api — токен: " + masked(config.token())), false);
        }
        source.sendSuccess(() -> Component.literal("  Событий в журнале: "
                + ApiService.eventLog().size() + " (last id " + ApiService.eventLog().lastId() + ")"
                + ", кастомных квестов: " + CustomQuestManager.get().all().size()), false);
        source.sendSuccess(() -> Component.literal("  Лимиты: " + config.rateLimitPerMinute()
                + " запросов/мин. Ротация токена: /trakt api token rotate"), false);
        return 1;
    }

    private static int showToken(CommandSourceStack source) {
        ApiConfig config = ApiConfig.get();
        boolean agent = "agent".equalsIgnoreCase(config.transport());
        String token = agent ? config.gatewayToken() : config.token();
        source.sendSuccess(() -> Component.literal((agent
                ? "Токен гейтвея (вставьте его в настройках панели): "
                : "Токен API (укажите его в панели): ") + token)
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Не передавайте токен третьим лицам.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int rotateToken(CommandSourceStack source) {
        ApiConfig config = ApiConfig.get();
        boolean agent = "agent".equalsIgnoreCase(config.transport());
        if (agent) {
            config.setGatewayToken(ApiConfig.newToken());
        } else {
            config.setToken(ApiConfig.newToken());
        }
        try {
            config.write();
            String shown = agent ? config.gatewayToken() : config.token();
            source.sendSuccess(() -> Component.literal((agent
                    ? "Новый токен гейтвея (агент): " : "Новый токен (HTTP): ") + shown)
                    .withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal(
                    "Обновите токен в панели. Старый токен перестал действовать немедленно.")
                    .withStyle(ChatFormatting.GRAY), false);
            JsonObject payload = new JsonObject();
            payload.addProperty("by", source.getTextName());
            ApiService.emitEvent(ApiService.server(), "api_token_rotated", null, null, payload);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendSuccess(() -> Component.literal("Не удалось сохранить конфиг: "
                    + exception.getMessage()).withStyle(ChatFormatting.RED), false);
            return 0;
        }
    }

    private static int reloadConfig(CommandSourceStack source) {
        ApiConfig fresh = ApiConfig.reload();
        boolean agent = "agent".equalsIgnoreCase(fresh.transport());
        // Restart whichever transport is configured so bind/port/panel_url changes apply.
        ApiServer.get().stop();
        ApiAgent.get().stop();
        if (fresh.enabled()) {
            try {
                if (agent) {
                    ApiAgent.get().start(fresh);
                    source.sendSuccess(() -> Component.literal("Агент перезапущен: " + fresh.panelUrl()
                            + " (server_id=" + fresh.serverId() + ").")
                            .withStyle(ChatFormatting.GREEN), false);
                } else {
                    ApiServer.get().start(fresh);
                    source.sendSuccess(() -> Component.literal("API перезапущен на порту " + fresh.port() + ".")
                            .withStyle(ChatFormatting.GREEN), false);
                }
                return 1;
            } catch (Exception exception) {
                source.sendSuccess(() -> Component.literal("Не удалось перезапустить API: "
                        + exception.getMessage()).withStyle(ChatFormatting.RED), false);
                return 0;
            }
        }
        source.sendSuccess(() -> Component.literal("Конфиг перечитан (enabled=" + fresh.enabled() + ").")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
