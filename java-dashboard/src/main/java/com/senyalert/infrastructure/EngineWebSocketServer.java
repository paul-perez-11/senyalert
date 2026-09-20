package com.senyalert.infrastructure;

import com.senyalert.controller.DashboardController;
import com.senyalert.model.CameraPreview;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.model.UploadedVideoRequest;
import com.senyalert.model.UploadedVideoStatus;
import com.senyalert.service.EngineGateway;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

/** WebSocket adapter for the Python engine. It has no Swing dependencies. */
public class EngineWebSocketServer extends WebSocketServer implements EngineGateway {
    private final DashboardController controller;
    private final AtomicReference<WebSocket> engineConnection = new AtomicReference<>();
    /**
     * Local benchmark clients use a separate WebSocket path. They must never
     * replace the real Python engine connection or cause a visible engine
     * disconnect when a timing run finishes.
     */
    private final Set<WebSocket> benchmarkConnections = ConcurrentHashMap.newKeySet();

    public EngineWebSocketServer(int port, DashboardController controller) {
        super(new InetSocketAddress(port));
        this.controller = controller;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        if (isBenchmarkRequest(handshake)) {
            if (!isLoopback(connection)) {
                connection.close(1008, "Benchmark endpoint is local only");
                return;
            }
            benchmarkConnections.add(connection);
            return;
        }
        engineConnection.set(connection);
        controller.onEngineStatus(new EngineStatus(
                true,
                false,
                "Vision engine connected from " + connection.getRemoteSocketAddress(),
                ""));
        controller.onEngineConnected();
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        if (benchmarkConnections.remove(connection)) {
            return;
        }
        engineConnection.compareAndSet(connection, null);
        controller.onEngineStatus(EngineStatus.disconnected("Vision engine disconnected"));
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        if (benchmarkConnections.contains(connection)) {
            replyToBenchmarkPing(connection, message);
            return;
        }
        try {
            JSONObject json = new JSONObject(message);
            String eventType = json.optString("event", "DISTRESS_GESTURE_DETECTED");
            switch (eventType) {
                case "CONFIG_ACK" -> {
                    String acknowledgement = json.optString("status", "Engine applied the configuration.");
                    JSONObject rejected = json.optJSONObject("rejected_fields");
                    if (rejected != null && rejected.length() > 0) {
                        acknowledgement += " Rejected fields: " + rejected;
                    }
                    if (json.has("config_revision")) {
                        acknowledgement += " Active engine policy: "
                                + (json.optBoolean("people_detection_enabled", true)
                                        ? "people detection on" : "people detection off")
                                + ", quiet at " + json.optInt("quiet_at_or_above_people", 0) + "+ people"
                                + ", audible alarms "
                                + (json.optBoolean("audible_alerts_enabled", true) ? "on" : "off")
                                + ", revision " + json.optInt("config_revision", 0) + ".";
                        org.json.JSONArray enabledCameras = json.optJSONArray("enabled_camera_ids");
                        if (enabledCameras != null && enabledCameras.length() > 0) {
                            String cameras = enabledCameras.toList().stream()
                                    .map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(", "));
                            acknowledgement += " Enabled cameras: " + cameras + ".";
                        }
                    }
                    controller.onEngineAcknowledgement(acknowledgement);
                }
                case "ENGINE_STATUS", "CAMERA_STATUS" -> controller.onEngineStatus(new EngineStatus(
                        json.optBoolean("connected", true),
                        json.optBoolean("paused", false),
                        statusMessage(json),
                        json.optString("cameraId", json.optString("camera_id", ""))));
                case "CAMERA_PREVIEW" -> controller.onCameraPreview(CameraPreview.fromJson(json));
                case "UPLOADED_VIDEO_STATUS" -> controller.onUploadedVideoStatus(UploadedVideoStatus.fromJson(json));
                case "INCIDENT_MEDIA_READY" -> {
                    if (isUploadedEvidence(json)) {
                        controller.onUploadedMediaReady(MediaReadyEvent.fromJson(json));
                    } else {
                        controller.onMediaReady(MediaReadyEvent.fromJson(json));
                    }
                }
                case "DISTRESS_GESTURE_DETECTED" -> {
                    if (isUploadedEvidence(json)) {
                        controller.onUploadedDistress(DistressEvent.fromJson(json));
                    } else {
                        controller.onDistress(DistressEvent.fromJson(json));
                    }
                }
                default -> {
                    // Older engine messages omitted event; preserve their alert behavior.
                    if (json.has("cameraId") || json.has("confidence")) {
                        if (isUploadedEvidence(json)) {
                            controller.onUploadedDistress(DistressEvent.fromJson(json));
                        } else {
                            controller.onDistress(DistressEvent.fromJson(json));
                        }
                    } else {
                        controller.onEngineStatus(new EngineStatus(true, false,
                                "Ignored unsupported engine message: " + eventType, ""));
                    }
                }
            }
        } catch (Exception malformedMessage) {
            controller.onEngineStatus(new EngineStatus(true, false,
                    "Could not read an engine message: " + malformedMessage.getMessage(), ""));
        }
    }

    @Override
    public boolean sendSettings(EngineSettings settings) {
        return sendCommand(new JSONObject().put("action", "UPDATE_SETTINGS").put("config", settings.toEngineJson()));
    }

    @Override
    public boolean setPaused(boolean paused) {
        return sendCommand(new JSONObject().put("action", "PAUSE").put("state", paused));
    }

    @Override
    public boolean restartEngine() {
        return sendCommand(new JSONObject().put("action", "RESTART_ENGINE"));
    }

    @Override
    public boolean submitUploadedVideo(UploadedVideoRequest request) {
        if (request == null) {
            return false;
        }
        return sendCommand(new JSONObject()
                .put("action", "PROCESS_UPLOADED_VIDEO")
                .put("source_path", request.source().toString())
                .put("camera_id", request.cameraId())
                .put("location", request.location()));
    }

    @Override
    public boolean isConnected() {
        WebSocket connection = engineConnection.get();
        return connection != null && connection.isOpen();
    }

    public void stopGracefully() {
        try {
            stop(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        if (connection != null && benchmarkConnections.contains(connection)) {
            return;
        }
        controller.onEngineStatus(new EngineStatus(isConnected(), false,
                "WebSocket error: " + exception.getMessage(), ""));
    }

    @Override
    public void onStart() {
        controller.onEngineStatus(new EngineStatus(isConnected(), false,
                "Waiting for the vision engine on port " + getPort(), ""));
    }

    private boolean sendCommand(JSONObject command) {
        WebSocket connection = engineConnection.get();
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        try {
            connection.send(command.toString());
            return true;
        } catch (RuntimeException sendFailure) {
            controller.onEngineStatus(new EngineStatus(false, false,
                    "Could not send a command to the engine", ""));
            return false;
        }
    }

    private static boolean isBenchmarkRequest(ClientHandshake handshake) {
        if (handshake == null) {
            return false;
        }
        String resource = handshake.getResourceDescriptor();
        return "/benchmark".equals(resource) || "/benchmark/".equals(resource);
    }

    private static boolean isLoopback(WebSocket connection) {
        InetSocketAddress remote = connection == null ? null : connection.getRemoteSocketAddress();
        return remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    /**
     * A deliberately narrow timing endpoint. It only acknowledges local
     * benchmark pings; it neither creates incidents nor updates dashboard
     * state, so transport measurements cannot contaminate a demo archive.
     */
    private static void replyToBenchmarkPing(WebSocket connection, String message) {
        try {
            JSONObject request = new JSONObject(message);
            if (!"BENCHMARK_PING".equals(request.optString("event"))) {
                connection.send(new JSONObject()
                        .put("event", "BENCHMARK_ERROR")
                        .put("message", "Only BENCHMARK_PING is accepted on /benchmark")
                        .toString());
                return;
            }
            connection.send(new JSONObject()
                    .put("event", "BENCHMARK_ACK")
                    .put("sequence", request.optLong("sequence", -1L))
                    .put("server_received_epoch_ms", System.currentTimeMillis())
                    .toString());
        } catch (RuntimeException malformedMessage) {
            connection.send(new JSONObject()
                    .put("event", "BENCHMARK_ERROR")
                    .put("message", "Malformed benchmark message")
                    .toString());
        }
    }

    private static String statusMessage(JSONObject json) {
        String summary = json.optString("status", json.optString("message", json.optString("state", "Engine status received")));
        String detail = json.optString("detail", "").trim();
        return detail.isBlank() ? summary : summary + ": " + detail;
    }

    private static boolean isUploadedEvidence(JSONObject json) {
        return "UPLOADED".equalsIgnoreCase(json.optString("archive_source", ""));
    }
}
