package com.senyalert.model;

import java.util.UUID;
import org.json.JSONObject;

/** Immutable representation of an initial DISTRESS_GESTURE_DETECTED message. */
public record DistressEvent(
        String eventToken,
        String cameraId,
        double confidence,
        long timestampEpochSeconds,
        String location,
        String triageContext,
        int peopleCount,
        boolean peopleCountStale,
        String occupancyStatus,
        int handCount,
        int signalerCount,
        String signalerTrackId,
        String signalerBounds,
        String snapshotPath,
        String snapshotBase64,
        String snapshotMimeType) {

    public static DistressEvent fromJson(JSONObject json) {
        String bounds = "";
        Object rawBounds = json.opt("signaler_bounds");
        if (rawBounds != null && rawBounds != JSONObject.NULL) {
            bounds = String.valueOf(rawBounds);
        }

        long timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000L);
        // Accept an engine that sends milliseconds without creating 1970 dates.
        if (timestamp > 100_000_000_000L) {
            timestamp /= 1000L;
        }

        return new DistressEvent(
                json.optString("event_token", UUID.randomUUID().toString()),
                json.optString("cameraId", json.optString("camera_id", "UNKNOWN_CAM")),
                json.optDouble("confidence", 0.0),
                timestamp,
                json.optString("location", "Unassigned Zone"),
                json.optString("triage_context", "STANDARD"),
                json.optInt("people_count", json.optInt("person_count", 0)),
                json.optBoolean("people_count_stale", false),
                json.optString("occupancy_status", "CURRENT"),
                json.optInt("hand_count", json.optInt("hands_detected", 0)),
                json.optInt("signaler_count", json.optInt("sos_hand_count", 1)),
                json.optString("signaler_track_id", json.optString("signaler_id", "")),
                bounds,
                json.optString("snapshot_path", ""),
                json.optString("snapshot_base64", ""),
                json.optString("snapshot_mime_type", "image/jpeg"));
    }
}
