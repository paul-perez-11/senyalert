package com.senyalert.model;

import java.net.URI;

/** A validated zoom request for Android IP Camera's HTTP control endpoint. */
public record IpCameraZoomRequest(String source, double zoom) {
    public IpCameraZoomRequest {
        source = source == null ? "" : source.trim();
        try {
            URI uri = URI.create(source);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Zoom needs an http(s) Android IP Camera source, such as http://127.0.0.1:17170/video/mjpeg.");
        }
        if (Double.isNaN(zoom) || Double.isInfinite(zoom) || zoom < 1.0 || zoom > 20.0) {
            throw new IllegalArgumentException("Zoom must be between 1.0× and 20.0×.");
        }
    }
}
