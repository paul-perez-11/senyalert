package com.senyalert.model;

import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One independently configurable camera shown by the dashboard and included
 * in the modern engine camera list.  Optional processing fields retain sane
 * defaults when opening older saved settings.
 */
public record CameraSettings(
        String source,
        String cameraId,
        String location,
        boolean enabled,
        double processingScale,
        int captureWidth,
        int captureHeight,
        int previewWidth,
        int previewHeight,
        CameraType cameraType,
        int aspectWidth,
        int aspectHeight) {
    public static final int DEFAULT_CAPTURE_WIDTH = 1280;
    public static final int DEFAULT_CAPTURE_HEIGHT = 720;
    public static final int DEFAULT_PREVIEW_WIDTH = 960;
    public static final int DEFAULT_PREVIEW_HEIGHT = 540;
    public static final double DEFAULT_PROCESSING_SCALE = 1.0;

    /**
     * A profile is deliberately a presentation/configuration hint, not a
     * claim about what a physical source can expose.  For example, Windows
     * selects the lens exposed by a USB/UVC phone; a phone-front profile gives
     * the dashboard portrait defaults but cannot switch an Android lens.
     */
    public enum CameraType {
        CCTV("cctv", "CCTV", 16, 9, 1920, 1080, 960, 540),
        LAPTOP_WEBCAM("laptop_webcam", "Laptop webcam (demo)", 16, 9, 1280, 720, 960, 540),
        PHONE_FRONT("phone_front", "Phone camera (front) (demo)", 9, 16, 1080, 1920, 540, 960),
        PHONE_REAR("phone_rear", "Phone camera (rear) (demo)", 9, 16, 1080, 1920, 540, 960),
        CUSTOM("custom", "Custom camera", 16, 9, 1280, 720, 960, 540);

        private final String wireName;
        private final String displayName;
        private final int aspectWidth;
        private final int aspectHeight;
        private final int captureWidth;
        private final int captureHeight;
        private final int previewWidth;
        private final int previewHeight;

        CameraType(
                String wireName,
                String displayName,
                int aspectWidth,
                int aspectHeight,
                int captureWidth,
                int captureHeight,
                int previewWidth,
                int previewHeight) {
            this.wireName = wireName;
            this.displayName = displayName;
            this.aspectWidth = aspectWidth;
            this.aspectHeight = aspectHeight;
            this.captureWidth = captureWidth;
            this.captureHeight = captureHeight;
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
        }

        public String wireName() {
            return wireName;
        }

        public int aspectWidth() {
            return aspectWidth;
        }

        public int aspectHeight() {
            return aspectHeight;
        }

        public int captureWidth() {
            return captureWidth;
        }

        public int captureHeight() {
            return captureHeight;
        }

        public int previewWidth() {
            return previewWidth;
        }

        public int previewHeight() {
            return previewHeight;
        }

        public static CameraType fromWireName(String value) {
            if (value != null) {
                for (CameraType candidate : values()) {
                    if (candidate.wireName.equalsIgnoreCase(value.trim())) {
                        return candidate;
                    }
                }
            }
            return LAPTOP_WEBCAM;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Backward-compatible convenience constructor for existing camera settings. */
    public CameraSettings(String source, String cameraId, String location) {
        this(source, cameraId, location, true, DEFAULT_PROCESSING_SCALE,
                DEFAULT_CAPTURE_WIDTH, DEFAULT_CAPTURE_HEIGHT,
                DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT,
                CameraType.LAPTOP_WEBCAM,
                CameraType.LAPTOP_WEBCAM.aspectWidth(), CameraType.LAPTOP_WEBCAM.aspectHeight());
    }

    /** Backward-compatible constructor for camera records saved before profiles existed. */
    public CameraSettings(
            String source,
            String cameraId,
            String location,
            boolean enabled,
            double processingScale,
            int captureWidth,
            int captureHeight,
            int previewWidth,
            int previewHeight) {
        this(source, cameraId, location, enabled, processingScale,
                captureWidth, captureHeight, previewWidth, previewHeight,
                CameraType.LAPTOP_WEBCAM,
                CameraType.LAPTOP_WEBCAM.aspectWidth(), CameraType.LAPTOP_WEBCAM.aspectHeight());
    }

    public CameraSettings {
        source = validateSource(source);
        cameraId = nonBlank(cameraId, "CAM-01-LAPTOP");
        location = nonBlank(location, "Unassigned Zone");
        cameraType = cameraType == null ? CameraType.LAPTOP_WEBCAM : cameraType;
        processingScale = clampScale(processingScale);
        captureWidth = clamp(captureWidth, 160, 3840, cameraType.captureWidth());
        captureHeight = clamp(captureHeight, 120, 2160, cameraType.captureHeight());
        previewWidth = clamp(previewWidth, 160, 3840, cameraType.previewWidth());
        previewHeight = clamp(previewHeight, 120, 2160, cameraType.previewHeight());
        aspectWidth = clamp(aspectWidth, 1, 10_000, cameraType.aspectWidth());
        aspectHeight = clamp(aspectHeight, 1, 10_000, cameraType.aspectHeight());
    }

    public static CameraSettings fromJson(JSONObject json) {
        JSONArray captureResolution = json.optJSONArray("resolution");
        JSONArray previewResolution = json.optJSONArray("preview_resolution");
        JSONArray aspectRatio = json.optJSONArray("aspect_ratio");
        CameraType type = CameraType.fromWireName(json.optString("camera_type", json.optString("cameraType", "laptop_webcam")));
        return new CameraSettings(
                json.optString("source", json.optString("camera_source", "0")),
                json.optString("camera_id", json.optString("cameraId", "CAM-01-LAPTOP")),
                json.optString("location", "Unassigned Zone"),
                json.optBoolean("enabled", true),
                json.has("processing_scale")
                        ? json.optDouble("processing_scale", DEFAULT_PROCESSING_SCALE)
                        : DEFAULT_PROCESSING_SCALE,
                resolutionPart(captureResolution, 0, type.captureWidth()),
                resolutionPart(captureResolution, 1, type.captureHeight()),
                resolutionPart(previewResolution, 0, type.previewWidth()),
                resolutionPart(previewResolution, 1, type.previewHeight()),
                type,
                resolutionPart(aspectRatio, 0, type.aspectWidth()),
                resolutionPart(aspectRatio, 1, type.aspectHeight()));
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("camera_source", source)
                .put("camera_id", cameraId)
                .put("location", location)
                .put("enabled", enabled)
                .put("processing_scale", processingScale)
                .put("resolution", new JSONArray().put(captureWidth).put(captureHeight))
                .put("preview_resolution", new JSONArray().put(previewWidth).put(previewHeight))
                .put("camera_type", cameraType.wireName())
                .put("aspect_ratio", new JSONArray().put(aspectWidth).put(aspectHeight));
    }

    static String validateSource(String value) {
        String source = nonBlank(value, "0");
        if (source.matches("\\d+")) {
            try {
                if (Integer.parseInt(source) >= 0) {
                    return source;
                }
            } catch (NumberFormatException ignored) {
                // Use the shared validation message below.
            }
        }

        try {
            URI uri = URI.create(source);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ("uvc".equals(scheme)) {
                String deviceIndex = uri.getHost();
                if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
                    throw new IllegalArgumentException("UVC source path is not supported");
                }
                if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null
                        || deviceIndex == null || !deviceIndex.matches("\\d+")) {
                    throw new IllegalArgumentException("UVC source must be formatted as uvc://<webcam-index>");
                }
                if (Integer.parseInt(deviceIndex) < 0) {
                    throw new IllegalArgumentException("UVC camera index must be non-negative");
                }
                return "uvc://" + deviceIndex;
            }
            if (("http".equals(scheme) || "https".equals(scheme)
                    || "rtsp".equals(scheme) || "rtsps".equals(scheme)
                    || "adb".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return source;
            }
        } catch (IllegalArgumentException ignored) {
            // Use the shared validation message below.
        }
        throw new IllegalArgumentException(
                "Camera source must be a non-negative webcam index, uvc://<webcam-index>, an http(s)/rtsp(s) stream URL, or adb://<serial>.");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int resolutionPart(JSONArray value, int index, int fallback) {
        return value == null ? fallback : value.optInt(index, fallback);
    }

    private static double clampScale(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return DEFAULT_PROCESSING_SCALE;
        }
        return Math.max(0.25, Math.min(1.0, value));
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
