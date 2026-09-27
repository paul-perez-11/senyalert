package com.senyalert.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Local protocol checks for the bounded support API. It starts only a loopback
 * HTTP server; it never starts cloudflared or opens a public connection.
 */
public final class RemoteSupportSmoke {
    private static int passed;

    private RemoteSupportSmoke() { }

    public static void main(String[] args) throws Exception {
        List<String> audit = new ArrayList<>();
        RemoteBackend backend = new RemoteBackend() {
            @Override public JSONObject snapshot(String sessionId) {
                return new JSONObject().put("clientId", "smoke-client").put("session", sessionId);
            }

            @Override public JSONObject execute(String action, JSONObject payload, String sessionId) {
                return new JSONObject().put("action", action).put("session", sessionId)
                        .put("payload", new JSONObject(payload.toString()));
            }

            @Override public void audit(String action, JSONObject details) {
                audit.add(action + ":" + details.toString());
            }
        };
        String supportKey = "remote-smoke-support-key-0123456789abcdef";
        RemoteSupportService service = new RemoteSupportService(backend, supportKey, "not-used", ignored -> { });
        try {
            URI local = service.startLocal(Duration.ofMinutes(1));
            String link = local + "/#" + service.localTestToken();
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(local.resolve("/api/snapshot"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            check(unauthorized.statusCode() == 401, "missing support credentials are rejected");

            RemoteClient client = new RemoteClient(link, supportKey, true);
            JSONObject snapshot = client.snapshot();
            check("smoke-client".equals(snapshot.getString("clientId")), "signed loopback snapshot succeeds");
            check(!snapshot.has("token"), "support snapshot does not expose the bearer token");

            JSONObject result = client.execute("settings.update", new JSONObject().put("mode", "smoke"));
            check("settings.update".equals(result.getString("action")), "signed allowed support action succeeds");

            expectFailure(() -> client.execute("shell.execute", new JSONObject()), "unsupported support action is rejected");
            check(audit.stream().anyMatch(item -> item.startsWith("remote.session.started:")), "session start is audited");
            check(audit.stream().anyMatch(item -> item.startsWith("remote.request.denied:")), "denied request is audited");
            check(audit.stream().anyMatch(item -> item.startsWith("remote.snapshot.requested:")), "snapshot request is audited");
            check(audit.stream().anyMatch(item -> item.startsWith("remote.action.completed:")), "completed action is audited");
        } finally {
            service.close();
        }
        check(audit.stream().anyMatch(item -> item.startsWith("remote.session.stopped:")), "session stop is audited");
        System.out.println("Remote support smoke passed: " + passed + " checks. No tunnel was started.");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }

    private static void expectFailure(ThrowingRunnable operation, String label) {
        boolean failed = false;
        try {
            operation.run();
        } catch (Exception expected) {
            failed = true;
        }
        check(failed, label);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
