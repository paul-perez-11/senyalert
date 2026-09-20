package com.senyalert.model;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings owned by the dashboard and sent to the vision engine.  The JSON
 * keys deliberately retain the existing Python protocol names.
 */
public record EngineSettings(
        double confidenceThreshold,
        int preEventSeconds,
        int postEventSeconds,
        boolean requireThumb,
        boolean requireIndex,
        String cameraSource,
        String cameraId,
        String cameraLocation,
        int maxHands,
        boolean peopleDetectionEnabled,
        int peopleDetectionIntervalFrames,
        int quietAtOrAbovePeople,
        boolean audibleAlertsEnabled,
        List<CameraSettings> cameras) {

    public EngineSettings {
        confidenceThreshold = clamp(confidenceThreshold, 0.10, 0.99);
        preEventSeconds = clamp(preEventSeconds, 1, 60);
        postEventSeconds = clamp(postEventSeconds, 1, 60);
        cameraSource = CameraSettings.validateSource(cameraSource);
        cameraId = nonBlank(cameraId, "CAM-01-LAPTOP");
        cameraLocation = nonBlank(cameraLocation, "Unassigned Zone");
        maxHands = clamp(maxHands, 1, 8);
        peopleDetectionIntervalFrames = clamp(peopleDetectionIntervalFrames, 1, 120);
        quietAtOrAbovePeople = clamp(quietAtOrAbovePeople, 1, 100);
        cameras = normalizeCameras(cameras, cameraSource, cameraId, cameraLocation);
        // Scalar fields remain for older engine builds. Prefer an active feed
        // when one exists so disabling the first tab does not make legacy
        // consumers point at a deliberately disabled camera.
        CameraSettings primary = cameras.stream()
                .filter(CameraSettings::enabled)
                .findFirst()
                .orElse(cameras.get(0));
        cameraSource = primary.source();
        cameraId = primary.cameraId();
        cameraLocation = primary.location();
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
                0.70, 12, 12, true, true,
                "0", "CAM-01-LAPTOP", "Public Intake Counter A",
                2, true, 8, 4, true,
                List.of(new CameraSettings("0", "CAM-01-LAPTOP", "Public Intake Counter A")));
    }

    public static EngineSettings fromJson(JSONObject json) {
        EngineSettings fallback = defaults();
        JSONObject fingers = json.optJSONObject("fingers");
        List<CameraSettings> cameras = parseCameras(json.optJSONArray("cameras"));
        if (cameras.isEmpty()) {
            CameraSettings.CameraType type = CameraSettings.CameraType.fromWireName(
                    json.optString("camera_type", json.optString("cameraType", "laptop_webcam")));
            JSONArray resolution = json.optJSONArray("resolution");
            JSONArray previewResolution = json.optJSONArray("preview_resolution");
            JSONArray aspectRatio = json.optJSONArray("aspect_ratio");
            cameras = List.of(new CameraSettings(
                    json.optString("camera_source", json.optString("source_url", fallback.cameraSource())),
                    json.optString("camera_id", fallback.cameraId()),
                    json.optString("location", fallback.cameraLocation()),
                    true,
                    CameraSettings.DEFAULT_PROCESSING_SCALE,
                    resolutionPart(resolution, 0, type.captureWidth()),
                    resolutionPart(resolution, 1, type.captureHeight()),
                    resolutionPart(previewResolution, 0, type.previewWidth()),
                    resolutionPart(previewResolution, 1, type.previewHeight()),
                    type,
                    resolutionPart(aspectRatio, 0, type.aspectWidth()),
                    resolutionPart(aspectRatio, 1, type.aspectHeight())));
        }
        return new EngineSettings(
                json.optDouble("confidence_threshold", fallback.confidenceThreshold()),
                json.optInt("pre_event_sec", fallback.preEventSeconds()),
                json.optInt("post_event_sec", fallback.postEventSeconds()),
                fingers == null || fingers.optBoolean("thumb", fallback.requireThumb()),
                fingers == null || fingers.optBoolean("index", fallback.requireIndex()),
                json.optString("camera_source", json.optString("source_url", fallback.cameraSource())),
                json.optString("camera_id", fallback.cameraId()),
                json.optString("location", fallback.cameraLocation()),
                json.optInt("max_hands", fallback.maxHands()),
                json.optBoolean("people_detection_enabled", fallback.peopleDetectionEnabled()),
                json.optInt("people_detection_interval_frames", fallback.peopleDetectionIntervalFrames()),
                json.optInt("quiet_at_or_above_people", fallback.quietAtOrAbovePeople()),
                json.optBoolean("audible_alerts_enabled", fallback.audibleAlertsEnabled()),
                cameras);
    }

    public JSONObject toEngineJson() {
        JSONObject fingers = new JSONObject();
        fingers.put("thumb", requireThumb);
        fingers.put("index", requireIndex);
        // These remain required by the existing engine protocol.
        fingers.put("middle", true);
        fingers.put("ring", true);
        fingers.put("pinky", true);

        CameraSettings primary = cameras.stream()
                .filter(CameraSettings::enabled)
                .findFirst()
                .orElse(cameras.get(0));
        JSONObject payload = new JSONObject()
                .put("confidence_threshold", confidenceThreshold)
                .put("pre_event_sec", preEventSeconds)
                .put("post_event_sec", postEventSeconds)
                .put("fingers", fingers)
                .put("camera_source", cameraSource)
                .put("camera_id", cameraId)
                .put("location", cameraLocation)
                .put("camera_type", primary.cameraType().wireName())
                .put("aspect_ratio", new JSONArray().put(primary.aspectWidth()).put(primary.aspectHeight()))
                .put("resolution", new JSONArray().put(primary.captureWidth()).put(primary.captureHeight()))
                .put("preview_resolution", new JSONArray().put(primary.previewWidth()).put(primary.previewHeight()))
                .put("max_hands", maxHands)
                .put("people_detection_enabled", peopleDetectionEnabled)
                .put("people_detection_interval_frames", peopleDetectionIntervalFrames)
                .put("quiet_at_or_above_people", quietAtOrAbovePeople)
                .put("audible_alerts_enabled", audibleAlertsEnabled);
        // Retain scalar fields for older engines, while always sending the complete
        // bounded camera list so a newer engine can stop a removed worker.
        JSONArray serializedCameras = new JSONArray();
        cameras.stream().limit(4).forEach(camera -> serializedCameras.put(camera.toJson()));
        payload.put("cameras", serializedCameras);
        return payload;
    }

    /** Persists the complete configuration locally; scalar primary fields remain for legacy engines. */
    public JSONObject toPersistedJson() {
        JSONArray serializedCameras = new JSONArray();
        cameras.forEach(camera -> serializedCameras.put(camera.toJson()));
        return toEngineJson().put("cameras", serializedCameras);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<CameraSettings> parseCameras(JSONArray rawCameras) {
        if (rawCameras == null || rawCameras.isEmpty()) {
            return List.of();
        }
        List<CameraSettings> parsed = new ArrayList<>();
        for (int index = 0; index < rawCameras.length(); index++) {
            JSONObject raw = rawCameras.optJSONObject(index);
            if (raw == null) {
                continue;
            }
            try {
                parsed.add(CameraSettings.fromJson(raw));
            } catch (IllegalArgumentException ignored) {
                // One invalid persisted optional camera does not hide a valid primary camera.
            }
        }
        return parsed;
    }

    private static int resolutionPart(JSONArray value, int index, int fallback) {
        return value == null ? fallback : value.optInt(index, fallback);
    }

    private static List<CameraSettings> normalizeCameras(
            List<CameraSettings> requested,
            String primarySource,
            String primaryId,
            String primaryLocation) {
        List<CameraSettings> normalized = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            for (CameraSettings camera : requested) {
                if (normalized.size() < 4 && camera != null && normalized.stream().noneMatch(
                        existing -> existing.cameraId().equalsIgnoreCase(camera.cameraId()))) {
                    normalized.add(camera);
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(new CameraSettings(primarySource, primaryId, primaryLocation));
        }
        return List.copyOf(normalized);
    }
}
