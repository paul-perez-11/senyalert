package com.senyalert.model;

import org.json.JSONObject;

/** Follow-up message emitted when the engine has closed the incident video. */
public record MediaReadyEvent(
        String eventToken,
        String videoPath,
        String videoBase64,
        String videoMimeType,
        double durationSeconds,
        String mediaStatus) {

    public static MediaReadyEvent fromJson(JSONObject json) {
        return new MediaReadyEvent(
                json.optString("event_token", ""),
                json.optString("video_path", ""),
                json.optString("video_base64", ""),
                json.optString("video_mime_type", "video/mp4"),
                json.optDouble("video_duration_sec", json.optDouble("duration_seconds", 0.0)),
                json.optString("media_status", "READY"));
    }

    public boolean isReadyStatus() {
        return "READY".equalsIgnoreCase(mediaStatus)
                || "COMPLETE".equalsIgnoreCase(mediaStatus)
                || "AVAILABLE".equalsIgnoreCase(mediaStatus);
    }
}
