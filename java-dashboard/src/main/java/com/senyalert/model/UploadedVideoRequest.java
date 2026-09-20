package com.senyalert.model;

import java.nio.file.Path;

/** A local video chosen in Swing for offline ingestion by the local Python engine. */
public record UploadedVideoRequest(Path source, String cameraId, String location) {
    public UploadedVideoRequest {
        if (source == null) {
            throw new IllegalArgumentException("Choose a video file first.");
        }
        source = source.toAbsolutePath().normalize();
        cameraId = nonBlank(cameraId, "UPLOADED-VIDEO");
        location = nonBlank(location, "Uploaded video analysis");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
