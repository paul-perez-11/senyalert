package com.senyalert.service;

import com.senyalert.model.CameraPreview;
import com.senyalert.model.CameraPreviewFrame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Coalesces and decodes camera previews away from both the WebSocket callback
 * and Swing's event-dispatch thread. One slow tile never builds an unbounded
 * queue of obsolete frames.
 */
public final class CameraPreviewService {
    private static final int MAX_BASE64_CHARACTERS = 4_000_000;
    private static final int MAX_SOURCE_PIXELS = 16_000_000;
    private static final int MAX_PREVIEW_WIDTH = 640;
    private static final int MAX_PREVIEW_HEIGHT = 360;

    private final AppExecutors executors;
    private final ConcurrentHashMap<String, CameraPreview> latestByCamera = new ConcurrentHashMap<>();
    private final Set<String> drainingCameras = ConcurrentHashMap.newKeySet();

    public CameraPreviewService(AppExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public void submit(CameraPreview preview, Consumer<CameraPreviewFrame> listener) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(listener, "listener");
        String cameraId = preview.cameraId();
        latestByCamera.put(cameraId, preview);
        if (drainingCameras.add(cameraId)) {
            executors.media().execute(() -> drain(cameraId, listener));
        }
    }

    private void drain(String cameraId, Consumer<CameraPreviewFrame> listener) {
        while (true) {
            CameraPreview preview = latestByCamera.remove(cameraId);
            if (preview != null) {
                CameraPreviewFrame decoded = decode(preview);
                if (decoded != null) {
                    listener.accept(decoded);
                }
                continue;
            }

            drainingCameras.remove(cameraId);
            // If a frame arrived immediately after remove(), take ownership again.
            // Otherwise submit() observes no active drain and schedules the next one.
            if (latestByCamera.containsKey(cameraId) && drainingCameras.add(cameraId)) {
                continue;
            }
            return;
        }
    }

    private static CameraPreviewFrame decode(CameraPreview preview) {
        if (preview.jpegBase64().length() > MAX_BASE64_CHARACTERS) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(preview.jpegBase64());
            BufferedImage image = readPreview(bytes);
            return image == null ? null : new CameraPreviewFrame(
                    preview.cameraId(), preview.cameraSource(), preview.timestampEpochMillis(), image);
        } catch (IllegalArgumentException | IOException ignored) {
            // Preview delivery is best-effort. A malformed transient frame must not interrupt the engine session.
            return null;
        }
    }

    private static BufferedImage readPreview(byte[] bytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                if (sourceWidth <= 0 || sourceHeight <= 0
                        || (long) sourceWidth * sourceHeight > MAX_SOURCE_PIXELS) {
                    return null;
                }
                int subsampling = Math.max(1, Math.max(
                        divideRoundUp(sourceWidth, MAX_PREVIEW_WIDTH),
                        divideRoundUp(sourceHeight, MAX_PREVIEW_HEIGHT)));
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                return scaleToBounds(decoded);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleToBounds(BufferedImage source) {
        if (source.getWidth() <= MAX_PREVIEW_WIDTH && source.getHeight() <= MAX_PREVIEW_HEIGHT) {
            return source;
        }
        double ratio = Math.min(
                MAX_PREVIEW_WIDTH / (double) source.getWidth(),
                MAX_PREVIEW_HEIGHT / (double) source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static int divideRoundUp(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
