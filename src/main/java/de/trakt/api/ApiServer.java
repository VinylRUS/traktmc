package de.trakt.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.trakt.Trakt;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Embedded REST server (JDK HttpServer + virtual threads). All game-state access is marshalled
 * onto the main server thread through {@link #onServer(Supplier)}; handlers only touch static
 * config/link/event stores directly. See rest_api.md for the endpoint contract.
 */
public final class ApiServer {
    private static final int BODY_LIMIT_BYTES = 1 << 20; // 1 MiB
    private static final long SERVER_CALL_TIMEOUT_MS = 5_000;

    private HttpServer httpServer;
    private ExecutorService executor;
    private volatile long windowStartMs;
    private volatile int windowRequests;

    private ApiServer() {}

    private static final class Holder {
        private static final ApiServer INSTANCE = new ApiServer();
    }

    static ApiServer get() {
        return Holder.INSTANCE;
    }

    // ------------------------------------------------------------------ errors

    public static final class ApiError extends RuntimeException {
        private final int status;

        public ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        private final String code;

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public static ApiError badRequest(String message) {
        return new ApiError(400, "bad_request", message);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not_found", message);
    }

    // ------------------------------------------------------------------ lifecycle

    void start(ApiConfig config) throws IOException {
        stop();
        if (!config.enabled()) {
            Trakt.LOGGER.info("[trakt api] disabled in config; HTTP server not started");
            return;
        }
        httpServer = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
        httpServer.createContext("/", this::dispatch);
        httpServer.start();
        windowStartMs = System.currentTimeMillis();
        windowRequests = 0;
        Trakt.LOGGER.info("[trakt api] listening on {}:{} (rate limit {}/min)",
                config.bind(), config.port(), config.rateLimitPerMinute());
    }

    void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
    }

    public boolean running() {
        return httpServer != null;
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            if (!checkAuth(exchange)) {
                respond(exchange, 401, errorJson("unauthorized", "missing or invalid bearer token"));
                return;
            }
            if (!checkRateLimit()) {
                respond(exchange, 429, errorJson("rate_limited", "too many requests; slow down"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (!path.startsWith("/api")) {
                respond(exchange, 404, errorJson("not_found", "unknown path; try GET /api"));
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            JsonObject body = readBody(exchange);
            JsonObject result = ApiRoutes.dispatch(method, path, query, body);
            respond(exchange, 200, result);
        } catch (ApiError error) {
            respond(exchange, error.status(), errorJson(error.code(), error.getMessage()));
        } catch (Exception exception) {
            Trakt.LOGGER.warn("[trakt api] handler error on {} {}",
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exception);
            respond(exchange, 500, errorJson("internal_error", "see server log"));
        } finally {
            exchange.close();
        }
    }

    private boolean checkAuth(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String presented = header.substring("Bearer ".length()).trim();
        String expected = ApiConfig.get().token();
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs > 60_000L) {
            windowStartMs = now;
            windowRequests = 0;
        }
        windowRequests++;
        return windowRequests <= Math.max(1, ApiConfig.get().rateLimitPerMinute());
    }

    private JsonObject readBody(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                && !"PATCH".equalsIgnoreCase(exchange.getRequestMethod())
                && !"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            return null;
        }
        byte[] raw = exchange.getRequestBody().readNBytes(BODY_LIMIT_BYTES + 1);
        if (raw.length > BODY_LIMIT_BYTES) {
            throw badRequest("request body too large (max 1 MiB)");
        }
        if (raw.length == 0) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw badRequest("request body must be a JSON object");
        }
    }

    static void respond(HttpExchange exchange, int status, JsonObject payload) throws IOException {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static JsonObject errorJson(String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", code);
        error.addProperty("message", message == null ? "" : message);
        return error;
    }

    // ------------------------------------------------------------------ server thread marshalling

    /**
     * Runs the supplier on the main server thread and waits for the result. Throws 503 when the
     * server reference is missing or the server thread does not answer within the timeout.
     */
    public static <T> T onServer(Supplier<T> supplier) {
        net.minecraft.server.MinecraftServer server = ApiService.server();
        if (server == null) {
            throw new ApiError(503, "server_unavailable", "server is not running");
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(SERVER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new ApiError(503, "server_busy", "server thread did not answer in time");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiError(503, "interrupted", "request interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ApiError apiError) {
                throw apiError;
            }
            Trakt.LOGGER.warn("[trakt api] server-thread handler failure", cause);
            throw new ApiError(500, "internal_error", String.valueOf(cause.getMessage()));
        }
    }
}
