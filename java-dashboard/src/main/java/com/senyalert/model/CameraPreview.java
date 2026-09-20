package com.senyalert.model;

import org.json.JSONObject;

/**
 * Raw, short-lived camera-preview payload received from the vision engine.
 * JPEG decoding intentionally happens outside the WebSocket callback.
 */
public record CameraPreview(
        String cameraId,
        String cameraSource,
        long timestampEpochMillis,
        String jpegBase64) {

    public static CameraPreview fromJson(JSONObject json) {
        String cameraId = nonBlank(json.optString("cameraId", json.optString("camera_id", "")), "CAMERA");
        String source = json.optString("camera_source", json.optString("source", ""));
        String payload = firstNonBlank(
                json.optString("jpeg_base64", ""),
                json.optString("preview_jpeg_base64", ""),
                json.optString("preview_base64", ""));
        if (payload.isBlank()) {
            throw new IllegalArgumentException("Camera preview did not include JPEG data.");
        }
        int dataPrefix = payload.indexOf(',');
        if (payload.regionMatches(true, 0, "data:image", 0, "data:image".length()) && dataPrefix >= 0) {
            payload = payload.substring(dataPrefix + 1);
        }
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());
        // Engines that use Unix seconds remain readable by the dashboard.
        if (timestamp > 0 && timestamp < 100_000_000_000L) {
            timestamp *= 1_000L;
        }
        return new CameraPreview(cameraId, source, timestamp, payload.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
