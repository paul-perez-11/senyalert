package com.senyalert.model;

import java.awt.image.BufferedImage;

/** A decoded, display-sized camera frame ready for presentation on the EDT. */
public record CameraPreviewFrame(
        String cameraId,
        String cameraSource,
        long timestampEpochMillis,
        BufferedImage image) {
}
