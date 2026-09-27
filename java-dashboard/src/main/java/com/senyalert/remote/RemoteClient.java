package com.senyalert.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.json.JSONObject;

/** No redirects: a support credential is never forwarded to another hostname. */
public final class RemoteClient {
    private final URI endpoint;
    private final String token;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public RemoteClient(String link, String key) { this(link, key, false); }

    RemoteClient(String link, String key, boolean allowLoopbackTest) {
        URI input = URI.create(link.trim());
        String host = input.getHost();
        boolean httpsTunnel = "https".equals(input.getScheme()) && host != null
                && host.matches("[a-z0-9-]+\\.trycloudflare\\.com") && input.getPort() == -1;
        boolean loopback = allowLoopbackTest && "http".equals(input.getScheme()) && "127.0.0.1".equals(host);
        if ((!httpsTunnel && !loopback) || input.getUserInfo() != null || input.getRawQuery() != null
                || !(input.getPath().isEmpty() || input.getPath().equals("/"))
                || input.getFragment() == null || !input.getFragment().matches("[A-Za-z0-9_-]{43}")
                || key == null || key.length() < 32)
            throw new IllegalArgumentException("Paste the complete support link and this client's support key (at least 32 characters).");
        token = input.getFragment(); this.key = key;
        endpoint = URI.create(input.getScheme() + "://" + input.getRawAuthority());
    }

    public JSONObject snapshot() throws Exception { return request("GET", "/api/snapshot", null); }
    public JSONObject execute(String action, JSONObject payload) throws Exception {
        return request("POST", "/api/action", new JSONObject().put("action", action).put("payload", payload));
    }

    private JSONObject request(String method, String path, JSONObject json) throws Exception {
        byte[] body = json == null ? new byte[0] : json.toString().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("X-SenyAlert-Time", timestamp)
                .header("X-SenyAlert-Nonce", nonce).header("X-SenyAlert-Signature",
                        SupportAuthentication.sign(key, method, path, timestamp, nonce, body))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.body().length() > 4_194_304) throw new IllegalStateException("Client response exceeded the support limit");
        JSONObject result;
        try { result = new JSONObject(response.body()); }
        catch (Exception invalid) { throw new IllegalStateException("Client is unavailable. Ask the client to generate a new support link."); }
        if (response.statusCode() != 200) throw new IllegalStateException(result.optString("error", "Support operation failed"));
        return result;
    }
}
