package com.senyalert.model;

import org.json.JSONObject;

/**
 * A lifecycle update for one offline video-analysis job.
 *
 * <p>This is intentionally separate from the general engine status so the
 * upload screen can distinguish a queued job, an unreadable source, a
 * completed scan with no qualifying signals, and a completed scan that wrote
 * evidence.</p>
 */
public record UploadedVideoStatus(
        String jobId,
        String cameraId,
        String sourceName,
        String stage,
        String detail,
        int processedFrames,
        int totalFrames,
        int detectedIncidents) {

    public static UploadedVideoStatus fromJson(JSONObject json) {
        return new UploadedVideoStatus(
                json.optString("upload_job_id", ""),
                json.optString("cameraId", json.optString("camera_id", "")),
                json.optString("source_name", ""),
                json.optString("stage", json.optString("status", "QUEUED")),
                json.optString("detail", json.optString("message", "")),
                nonNegative(json.optInt("processed_frames", 0)),
                nonNegative(json.optInt("total_frames", 0)),
                nonNegative(json.optInt("detected_incidents", 0)));
    }

    public boolean hasKnownProgress() {
        return totalFrames > 0;
    }

    public int progressPercent() {
        if (!hasKnownProgress()) {
            return 0;
        }
        return Math.max(0, Math.min(100, (int) Math.round(processedFrames * 100.0 / totalFrames)));
    }

    public boolean isFailure() {
        return "FAILED".equalsIgnoreCase(stage) || "ERROR".equalsIgnoreCase(stage);
    }

    public boolean isComplete() {
        return "COMPLETE".equalsIgnoreCase(stage);
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
