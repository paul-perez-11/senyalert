package com.senyalert.infrastructure;

import com.senyalert.controller.DashboardController;
import com.senyalert.model.CameraPreview;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.service.EngineGateway;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

/** WebSocket adapter for the Python engine. It has no Swing dependencies. */
public class EngineWebSocketServer extends WebSocketServer implements EngineGateway {
    private final DashboardController controller;
    private final AtomicReference<WebSocket> engineConnection = new AtomicReference<>();

    public EngineWebSocketServer(int port, DashboardController controller) {
        super(new InetSocketAddress(port));
        this.controller = controller;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
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
        engineConnection.compareAndSet(connection, null);
        controller.onEngineStatus(EngineStatus.disconnected("Vision engine disconnected"));
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
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
                    controller.onEngineAcknowledgement(acknowledgement);
                }
                case "ENGINE_STATUS", "CAMERA_STATUS" -> controller.onEngineStatus(new EngineStatus(
                        json.optBoolean("connected", true),
                        json.optBoolean("paused", false),
                        statusMessage(json),
                        json.optString("cameraId", json.optString("camera_id", ""))));
                case "CAMERA_PREVIEW" -> controller.onCameraPreview(CameraPreview.fromJson(json));
                case "INCIDENT_MEDIA_READY" -> controller.onMediaReady(MediaReadyEvent.fromJson(json));
                case "DISTRESS_GESTURE_DETECTED" -> controller.onDistress(DistressEvent.fromJson(json));
                default -> {
                    // Older engine messages omitted event; preserve their alert behavior.
                    if (json.has("cameraId") || json.has("confidence")) {
                        controller.onDistress(DistressEvent.fromJson(json));
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

    private static String statusMessage(JSONObject json) {
        String summary = json.optString("status", json.optString("message", json.optString("state", "Engine status received")));
        String detail = json.optString("detail", "").trim();
        return detail.isBlank() ? summary : summary + ": " + detail;
    }
}
