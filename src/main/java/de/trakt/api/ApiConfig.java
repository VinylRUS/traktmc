package de.trakt.api;

import de.trakt.Trakt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Configuration for the embedded REST API used by the external control panel.
 *
 * <p>Stored in {@code config/trakt/api.properties}. The bearer token is generated
 * automatically on first start and can be rotated at runtime with {@code /trakt api token rotate}.
 */
public final class ApiConfig {
    private static final String FILE_NAME = "api.properties";

    private boolean enabled;
    private int port;
    private String bind;
    private String token;
    private int rateLimitPerMinute;
    private int maxEvents;
    private String defaultLocale;
    private int linkCodeTtlMinutes;
    private String transport;
    private String panelUrl;
    private String gatewayToken;
    private int pollSeconds;
    private String serverId;
    private int pushBatch;

    private ApiConfig() {}

    public static ApiConfig load() {
        Path path = path();
        ApiConfig config = new ApiConfig();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.isRegularFile(path)) {
                config.enabled = true;
                config.port = 8790;
                config.bind = "0.0.0.0";
                config.token = newToken();
                config.rateLimitPerMinute = 240;
                config.maxEvents = 5000;
                config.defaultLocale = "ru_ru";
                config.linkCodeTtlMinutes = 15;
                config.transport = "server";
                config.panelUrl = "";
                config.gatewayToken = newToken();
                config.pollSeconds = 2;
                config.serverId = "main";
                config.pushBatch = 128;
                config.write();
                return config;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            config.enabled = bool(properties, "api_enabled", true);
            config.port = bounded(intOf(properties, "api_port"), 1024, 65535, 8790);
            config.bind = value(properties, "api_bind", "0.0.0.0");
            config.token = value(properties, "api_token", "");
            config.rateLimitPerMinute = bounded(intOf(properties, "rate_limit_per_minute"), 10, 100000, 240);
            config.maxEvents = bounded(intOf(properties, "max_events"), 100, 100000, 5000);
            config.defaultLocale = value(properties, "default_locale", "ru_ru").toLowerCase(Locale.ROOT);
            config.linkCodeTtlMinutes = bounded(intOf(properties, "link_code_ttl_minutes"), 1, 1440, 15);
            config.transport = normalizeTransport(properties);
            config.panelUrl = normalizeUrl(properties);
            config.gatewayToken = value(properties, "gateway_token", "");
            config.pollSeconds = bounded(intOf(properties, "poll_seconds"), 1, 60, 2);
            config.serverId = normalizeServerId(properties);
            config.pushBatch = bounded(intOf(properties, "push_batch"), 16, 512, 128);
            if (config.token.isBlank()) {
                config.token = newToken();
                Trakt.LOGGER.warn("[trakt api] api_token missing; generated a new one");
            }
            if (config.gatewayToken.isBlank()) {
                config.gatewayToken = newToken();
                Trakt.LOGGER.warn("[trakt api] gateway_token missing; generated a new one");
            }
            if (!config.defaultLocale.matches("[a-z]{2}_[a-z]{2}")) {
                config.defaultLocale = "ru_ru";
            }
        } catch (IOException exception) {
            Trakt.LOGGER.warn("[trakt api] failed to load {}; API disabled", path, exception);
            config.enabled = false;
            config.port = 8790;
            config.bind = "0.0.0.0";
            config.token = newToken();
            config.rateLimitPerMinute = 240;
            config.maxEvents = 5000;
            config.defaultLocale = "ru_ru";
            config.linkCodeTtlMinutes = 15;
            config.transport = "server";
            config.panelUrl = "";
            config.gatewayToken = newToken();
            config.pollSeconds = 2;
            config.serverId = "main";
            config.pushBatch = 128;
        }
        return config;
    }

    static String newToken() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(48);
        for (int i = 0; i < 48; i++) {
            builder.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return builder.toString();
    }

    private static final class Holder {
        private static volatile ApiConfig instance = load();
    }

    public static ApiConfig get() {
        return Holder.instance;
    }

    /** Reloads the config from disk and returns the fresh instance. */
    public static ApiConfig reload() {
        ApiConfig fresh = load();
        if (fresh.token.isBlank()) {
            fresh.token = get().token();
        }
        Holder.instance = fresh;
        return fresh;
    }

    public void write() throws IOException {
        Path path = path();
        Files.createDirectories(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("api_enabled", Boolean.toString(enabled));
        properties.setProperty("api_port", Integer.toString(port));
        properties.setProperty("api_bind", bind);
        properties.setProperty("api_token", token);
        properties.setProperty("rate_limit_per_minute", Integer.toString(rateLimitPerMinute));
        properties.setProperty("max_events", Integer.toString(maxEvents));
        properties.setProperty("default_locale", defaultLocale);
        properties.setProperty("link_code_ttl_minutes", Integer.toString(linkCodeTtlMinutes));
        properties.setProperty("transport", transport);
        properties.setProperty("panel_url", panelUrl);
        properties.setProperty("gateway_token", gatewayToken);
        properties.setProperty("poll_seconds", Integer.toString(pollSeconds));
        properties.setProperty("server_id", serverId);
        properties.setProperty("push_batch", Integer.toString(pushBatch));
        properties.store(Files.newOutputStream(path), """
                Trakt REST API settings (see rest_api.md in the mod repository)
                transport     - server (embedded HTTP listener) or agent (outbound polling;
                                       use agent when the hosting blocks inbound ports, e.g. exarathon)
                api_enabled   - master switch for the API subsystem
                api_port      - TCP port for transport=server (default 8790)
                api_bind      - bind address; 0.0.0.0 exposes the API to the panel host
                api_token     - bearer token for transport=server; /trakt api token rotate
                panel_url     - transport=agent: public base URL of the panel gateway,
                                       e.g. https://mybot.bothost.tech
                gateway_token - transport=agent: shared secret sent to the panel
                poll_seconds  - transport=agent: gateway poll interval (default 2)
                server_id     - transport=agent: server label the panel knows this host by
                push_batch    - transport=agent: max events pushed per cycle (default 128)
                """);
    }

    public boolean enabled() {
        return enabled;
    }

    public int port() {
        return port;
    }

    public String bind() {
        return bind;
    }

    public String token() {
        return token;
    }

    public int rateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public int maxEvents() {
        return maxEvents;
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public int linkCodeTtlMinutes() {
        return linkCodeTtlMinutes;
    }

    /** "server" (embedded HTTP listener) or "agent" (outbound polling of a panel gateway). */
    public String transport() {
        return transport;
    }

    public String panelUrl() {
        return panelUrl;
    }

    public String gatewayToken() {
        return gatewayToken;
    }

    public int pollSeconds() {
        return pollSeconds;
    }

    public String serverId() {
        return serverId;
    }

    public int pushBatch() {
        return pushBatch;
    }

    void setEnabled(boolean value) {
        this.enabled = value;
    }

    void setPort(int value) {
        this.port = value;
    }

    void setBind(String value) {
        this.bind = value;
    }

    void setToken(String value) {
        this.token = value;
    }

    void setGatewayToken(String value) {
        this.gatewayToken = value;
    }

    private static String normalizeTransport(Properties properties) {
        String raw = properties.getProperty("transport");
        if (raw == null) {
            return "server";
        }
        return raw.trim().toLowerCase(Locale.ROOT).equals("agent") ? "agent" : "server";
    }

    private static String normalizeUrl(Properties properties) {
        String raw = properties.getProperty("panel_url");
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.isEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
            Trakt.LOGGER.warn("[trakt api] panel_url must start with http:// or https://; ignored");
            return "";
        }
        return value;
    }

    private static String normalizeServerId(Properties properties) {
        String raw = properties.getProperty("server_id");
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (value.isEmpty() || value.length() > 32) {
            return "main";
        }
        return value;
    }

    private static String value(Properties properties, String key, String fallback) {
        String raw = properties.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static Integer intOf(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int bounded(Integer raw, int minimum, int maximum, int fallback) {
        if (raw == null || raw < minimum || raw > maximum) {
            return fallback;
        }
        return raw;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(Trakt.MOD_ID)
                .resolve(FILE_NAME);
    }
}
