package com.senyalert.remote;

import com.senyalert.EnvironmentConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** A deliberately bounded, temporary application support API, bound only to loopback. */
public final class RemoteSupportService implements AutoCloseable {
    private static final int MAX_BODY = 262_144;
    public static final int MIN_SESSION_MINUTES = 1;
    public static final int DEFAULT_SESSION_MINUTES = 30;
    public static final int MAX_SESSION_MINUTES = 120;
    private static final Pattern TUNNEL_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Set<String> ACTIONS = Set.of("settings.update", "cameras.update",
            "incident.acknowledge", "incident.resolve", "incident.note", "users.create",
            "users.update", "users.resetPassword", "database.backup");
    private final RemoteBackend backend;
    private final String supportKey;
    private final String executable;
    private final Consumer<String> status;
    private final Map<String, Long> usedNonces = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "support-expiry"));
    private ExecutorService workers;
    private HttpServer server;
    private Process tunnel;
    private String token;
    private String link;
    private String sessionId;
    private Instant expiresAt;
    private int deniedRequests;
    private volatile boolean active;

    public RemoteSupportService(RemoteBackend backend, Consumer<String> status) {
        this(backend, EnvironmentConfiguration.value("SENYALERT_SUPPORT_SECRET"),
                java.util.Optional.ofNullable(EnvironmentConfiguration.value("SENYALERT_CLOUDFLARED"))
                        .filter(value -> !value.isBlank()).orElse("cloudflared"), status);
    }

    RemoteSupportService(RemoteBackend backend, String supportKey, String executable, Consumer<String> status) {
        this.backend = backend;
        this.supportKey = supportKey;
        this.executable = executable;
        this.status = status;
        scheduler.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    /** Starts a public tunnel only after the client's explicit Start support action. */
    public CompletableFuture<Void> start() {
        return start(Duration.ofMinutes(DEFAULT_SESSION_MINUTES));
    }

    /** Starts a public tunnel for the client-approved duration, bounded to one to 120 minutes. */
    public CompletableFuture<Void> start(Duration requestedLifetime) {
        Duration lifetime = validatedLifetime(requestedLifetime);
        return CompletableFuture.runAsync(() -> {
            try {
                backend.validateReady();
                URI local = startLocal(lifetime);
                String startingSession;
                synchronized (this) {
                    startingSession = sessionId;
                    ProcessBuilder command = new ProcessBuilder(executable, "tunnel", "--no-autoupdate", "--url", local.toString())
                            .redirectErrorStream(true);
                    // cloudflared only needs the loopback URL. Do not pass either local
                    // support credential to the child process through its environment.
                    command.environment().remove("SENYALERT_SUPPORT_SECRET");
                    command.environment().remove("SENYALERT_SUPERADMIN_PASSWORD_HASH");
                    tunnel = command.start();
                }
                Process current;
                synchronized (this) { current = tunnel; }
                try (BufferedReader output = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        Matcher matcher = TUNNEL_URL.matcher(line);
                        if (matcher.find()) {
                            synchronized (this) {
                                if (active && sessionId.equals(startingSession) && link == null) {
                                    link = matcher.group() + "/#" + token;
                                    audit("remote.link.generated", new JSONObject().put("expiresAt", expiresAt.toString()));
                                    status.accept("Support ready until " + expiresAt + ". Copy the link and send it privately to your developer.");
                                }
                            }
                        }
                        // cloudflared output contains the public hostname; never put it in the app log.
                    }
                }
                synchronized (this) {
                    if (active && sessionId.equals(startingSession)) stop("tunnel_closed");
                }
            } catch (Exception failure) {
                stop("start_failed");
                status.accept("Support could not start. Check cloudflared installation, the client support key, and Internet access.");
                throw new IllegalStateException("Remote support could not start. See the setup guide.", failure);
            }
        });
    }

    /** Package-private for isolated tests. Never starts cloudflared. */
    synchronized URI startLocal(Duration lifetime) throws IOException {
        if (active) throw new IllegalStateException("A support session is already active");
        if (supportKey == null || supportKey.length() < 32)
            throw new IllegalStateException("SENYALERT_SUPPORT_SECRET must contain at least 32 characters");
        lifetime = validatedLifetime(lifetime);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        sessionId = UUID.randomUUID().toString();
        expiresAt = Instant.now().plus(lifetime);
        link = null;
        deniedRequests = 0;
        usedNonces.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        workers = Executors.newFixedThreadPool(2, r -> daemon(r, "support-http"));
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        audit("remote.session.started", new JSONObject().put("expiresAt", expiresAt.toString()));
        active = true;
        server.start();
        status.accept("Starting temporary support connection...");
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    synchronized String localTestToken() { return token; }
    public synchronized boolean ready() { return active && link != null; }
    public synchronized boolean active() { return active; }
    public synchronized Instant expiresAt() { return expiresAt; }

    /** This is the only production link accessor. Audit must succeed before clipboard release. */
    public synchronized void copyLink() {
        if (!ready()) throw new IllegalStateException("Generate a support link first");
        audit("remote.link.copied", new JSONObject().put("destination", "system_clipboard"));
        copyToClipboard(link);
    }

    /** Copies the configured client ID only after an audit entry is committed. */
    public synchronized void copyClientId() {
        try {
            String clientId = backend.supportClientId();
            if (clientId == null || clientId.isBlank()) throw new IllegalStateException("A client installation ID is not configured.");
            audit("remote.client_id.copied", new JSONObject().put("destination", "system_clipboard"));
            copyToClipboard(clientId);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not copy the client installation ID.", failure);
        }
    }

    /** Copies the configured support key only after an audit entry is committed. */
    public synchronized void copySupportKey() {
        if (supportKey == null || supportKey.length() < 32)
            throw new IllegalStateException("SENYALERT_SUPPORT_SECRET must contain at least 32 characters");
        audit("remote.support_key.copied", new JSONObject().put("destination", "system_clipboard"));
        copyToClipboard(supportKey);
    }

    public synchronized void stop(String reason) {
        if (!active && server == null && tunnel == null) return;
        active = false;
        link = null;
        token = null;
        if (tunnel != null) { tunnel.destroy(); tunnel = null; }
        if (server != null) { server.stop(0); server = null; }
        if (workers != null) { workers.shutdownNow(); workers = null; }
        usedNonces.clear();
        try { audit("remote.session.stopped", new JSONObject().put("reason", reason)); }
        finally { status.accept("Remote support stopped. The copied link is no longer valid."); }
    }

    private void expire() {
        synchronized (this) {
            if (active && Instant.now().isAfter(expiresAt)) stop("expired");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String operation = "unknown";
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { reply(exchange, 413, new JSONObject().put("error", "Request is too large")); return; }
            if (!authenticated(exchange, body)) {
                synchronized (this) {
                    deniedRequests++;
                    if (deniedRequests <= 5 || deniedRequests % 100 == 0)
                        audit("remote.request.denied", new JSONObject().put("count", deniedRequests));
                }
                reply(exchange, 401, new JSONObject().put("error", "Support credentials are invalid or the session expired"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/snapshot".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                operation = "snapshot";
                audit("remote.snapshot.requested", new JSONObject());
                JSONObject result = backend.snapshot(sessionId);
                result.put("supportSession", sessionId).put("supportExpiresAt", expiresAt.toString());
                reply(exchange, 200, result);
                return;
            }
            if (!"/api/action".equals(path) || !"POST".equals(exchange.getRequestMethod())) {
                reply(exchange, 404, new JSONObject().put("error", "Unknown support operation")); return;
            }
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            operation = request.getString("action");
            if (!ACTIONS.contains(operation)) throw new IllegalArgumentException("Unsupported action");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            audit("remote.action.requested", new JSONObject().put("operation", operation)
                    .put("details", redact(payload)));
            JSONObject result = backend.execute(operation, payload, sessionId);
            audit("remote.action.completed", new JSONObject().put("operation", operation));
            reply(exchange, 200, result == null ? new JSONObject().put("ok", true) : result);
        } catch (SecurityException failure) {
            audit("remote.action.denied", new JSONObject().put("operation", operation));
            reply(exchange, 403, new JSONObject().put("error", "The initiating account is not permitted to perform this action"));
        } catch (IllegalArgumentException | org.json.JSONException failure) {
            audit("remote.action.failed", new JSONObject().put("operation", operation).put("reason", "invalid_request"));
            reply(exchange, 400, new JSONObject().put("error", "Invalid request; check required fields and configuration values"));
        } catch (Exception failure) {
            try { audit("remote.action.failed", new JSONObject().put("operation", operation).put("reason", "operation_failed")); }
            catch (RuntimeException ignored) { }
            reply(exchange, 500, new JSONObject().put("error", "Operation failed; check the client application"));
        } finally { exchange.close(); }
    }

    private synchronized boolean authenticated(HttpExchange request, byte[] body) {
        if (!active || token == null || Instant.now().isAfter(expiresAt)) return false;
        if (request.getRequestHeaders().containsKey("Origin")) return false;
        if (request.getRequestURI().getRawQuery() != null) return false;
        if (!SupportAuthentication.equal("Bearer " + token, request.getRequestHeaders().getFirst("Authorization"))) return false;
        String timestamp = request.getRequestHeaders().getFirst("X-SenyAlert-Time");
        String nonce = request.getRequestHeaders().getFirst("X-SenyAlert-Nonce");
        String signature = request.getRequestHeaders().getFirst("X-SenyAlert-Signature");
        long now = Instant.now().getEpochSecond();
        try {
            long sent = Long.parseLong(timestamp);
            if (sent < now - 60 || sent > now + 60) return false;
        } catch (Exception invalid) { return false; }
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{20,80}")) return false;
        usedNonces.values().removeIf(seen -> seen < now - 120);
        if (usedNonces.containsKey(nonce) || usedNonces.size() >= 2048) return false;
        String expected = SupportAuthentication.sign(supportKey, request.getRequestMethod(),
                request.getRequestURI().getPath(), timestamp, nonce, body);
        if (!SupportAuthentication.equal(expected, signature)) return false;
        usedNonces.put(nonce, now);
        return true;
    }

    private static JSONObject redact(JSONObject payload) {
        JSONObject safe = new JSONObject();
        for (String key : payload.keySet()) {
            if (key.toLowerCase().contains("password") || key.toLowerCase().contains("secret")
                    || key.toLowerCase().contains("token")) safe.put(key, "[redacted]");
            else if ("settings".equals(key) || "cameras".equals(key)) safe.put(key, "configuration supplied; see application audit");
            else safe.put(key, payload.get(key));
        }
        return safe;
    }

    private void audit(String action, JSONObject details) {
        backend.audit(action, details.put("supportSession", sessionId == null ? "none" : sessionId)
                .put("remotePrincipal", action.startsWith("remote.action.") || action.startsWith("remote.snapshot.")
                        ? "superadmin" : "local grantor or unauthenticated request"));
    }

    private static Duration validatedLifetime(Duration lifetime) {
        if (lifetime == null) throw new IllegalArgumentException("Choose a support session length.");
        long minutes = lifetime.toMinutes();
        if (minutes < MIN_SESSION_MINUTES || minutes > MAX_SESSION_MINUTES
                || !lifetime.equals(Duration.ofMinutes(minutes))) {
            throw new IllegalArgumentException("Support sessions must last from " + MIN_SESSION_MINUTES
                    + " to " + MAX_SESSION_MINUTES + " whole minutes.");
        }
        return lifetime;
    }

    private static void copyToClipboard(String value) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(value), null);
    }

    private static void reply(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override public void close() { stop("application_closed"); scheduler.shutdownNow(); }
}
