package com.senyalert.service;

import com.senyalert.EnvironmentConfiguration;
import com.senyalert.model.EngineSettings;
import java.nio.file.*;
import org.json.*;

/** Portable camera references stay unresolved in saved configuration and exported templates. */
public final class ConfigurationFiles {
    private ConfigurationFiles() { }
    public static JSONObject engineOnly(JSONObject source) {
        JSONObject copy = new JSONObject(source.toString());
        for (String key : new String[]{"cameras", "camera_source", "source_url", "camera_id", "location", "camera_type", "aspect_ratio", "resolution", "preview_resolution"}) copy.remove(key);
        return copy;
    }
    public static JSONObject redacted(JSONObject source) {
        JSONObject copy = new JSONObject(source.toString());
        for (String key : copy.keySet()) {
            Object value = copy.get(key);
            if (value instanceof JSONObject child) copy.put(key, redacted(child));
            else if (value instanceof JSONArray items) {
                JSONArray clean = new JSONArray();
                for (Object item : items) clean.put(item instanceof JSONObject object ? redacted(object) : item);
                copy.put(key, clean);
            } else if (value instanceof String text && (key.equals("source") || key.equals("camera_source") || key.equals("source_url"))) {
                // Keep valid ENV references and camera indexes. Credentials never enter the audit trail.
                if (!text.startsWith("env:") && !text.matches("\\d+") && !text.startsWith("uvc:")) copy.put(key, "[private camera source]");
            }
        }
        return copy;
    }
    public static String diff(JSONObject before, JSONObject after) {
        JSONObject changes = new JSONObject();
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        JSONObject b = redacted(before), a = redacted(after);
        for (String key : keys) if (!java.util.Objects.equals(String.valueOf(before.opt(key)), String.valueOf(after.opt(key))))
            changes.put(key, new JSONObject().put("before", b.opt(key)).put("after", a.opt(key)));
        return changes.toString();
    }
    public static void exportFile(EngineSettings settings, Path destination, boolean camerasOnly) {
        JSONObject json = settings.toPersistedJson();
        if (camerasOnly) json = new JSONObject().put("cameras", json.getJSONArray("cameras"));
        try { Files.writeString(destination, json.toString(2), StandardOpenOption.CREATE_NEW); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Choose a new, writable configuration filename.", failure); }
    }
    public static EngineSettings importFile(EngineSettings existing, Path source, boolean camerasOnly) {
        try {
            if (Files.size(source) > 1024 * 1024) throw new IllegalArgumentException("Configuration must be smaller than 1 MB.");
            JSONObject json = new JSONObject(Files.readString(source));
            validate(json, camerasOnly);
            if (camerasOnly) json = existing.toPersistedJson().put("cameras", json.getJSONArray("cameras"));
            return EngineSettings.fromJson(json);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Could not read configuration.", failure); }
    }
    public static void validate(JSONObject json, boolean camerasOnly) {
        JSONArray cameras = json.optJSONArray("cameras");
        if (cameras == null || cameras.isEmpty() || cameras.length() > 4) throw new IllegalArgumentException("Supply between 1 and 4 cameras. Disable a camera to keep an inactive placeholder.");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Object entry : cameras) {
            if (!(entry instanceof JSONObject camera)) throw new IllegalArgumentException("Each camera must be an object.");
            var parsed = com.senyalert.model.CameraSettings.fromJson(camera);
            if (!ids.add(parsed.cameraId().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Camera IDs must be unique.");
        }
        if (!camerasOnly && !json.has("confidence_threshold")) throw new IllegalArgumentException("This file is not a full engine configuration. Import it as camera settings.");
    }
    public static JSONObject resolveCameraEnvironment(JSONObject source) {
        JSONObject copy = new JSONObject(source.toString());
        for (String key : copy.keySet()) {
            Object value = copy.get(key);
            if (value instanceof JSONObject child) copy.put(key, resolveCameraEnvironment(child));
            else if (value instanceof JSONArray items) {
                JSONArray resolved = new JSONArray();
                for (Object item : items) resolved.put(item instanceof JSONObject object ? resolveCameraEnvironment(object) : item);
                copy.put(key, resolved);
            } else if (value instanceof String text && (key.equals("camera_source") || key.equals("source")) && text.startsWith("env:")) {
                String resolved = EnvironmentConfiguration.value(text.substring(4));
                if (resolved == null || resolved.isBlank() || resolved.startsWith("env:")) throw new IllegalStateException("Set camera environment variable " + text.substring(4) + " before starting SenyAlert.");
                // Reuse existing source validation without modifying capture or detection behavior.
                new com.senyalert.model.CameraSettings(resolved, "ENV-CHECK", "Validation");
                copy.put(key, resolved);
            }
        }
        return copy;
    }
}
