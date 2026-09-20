package com.senyalert.model;

public record EngineStatus(boolean connected, boolean paused, String message, String cameraId) {
    public static EngineStatus disconnected(String message) {
        return new EngineStatus(false, false, message, "");
    }
}
