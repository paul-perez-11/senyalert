package com.senyalert.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import org.json.JSONObject;

/**
 * Local destination preferences for files deliberately exported by an operator.
 * These values never change where SenyAlert stores its SQLite databases or source
 * evidence; they only preselect a destination in the native save dialogs.
 */
public record ExportSettings(
        Path evidenceDirectory,
        Path auditDirectory,
        Path configurationDirectory,
        Path benchmarkDirectory) {

    public ExportSettings {
        evidenceDirectory = normalized(evidenceDirectory, "Evidence export folder");
        auditDirectory = normalized(auditDirectory, "Audit export folder");
        configurationDirectory = normalized(configurationDirectory, "Configuration export folder");
        benchmarkDirectory = normalized(benchmarkDirectory, "Benchmark results folder");
    }

    /** Uses a separate exports area beside the installation data until an operator chooses another path. */
    public static ExportSettings defaults(Path installationDirectory) {
        Path root = normalized(Objects.requireNonNull(installationDirectory, "Installation directory"),
                "Installation directory").resolve("exports");
        return new ExportSettings(
                root.resolve("evidence"),
                root.resolve("audit"),
                root.resolve("configuration"),
                root.resolve("benchmarking"));
    }

    /** Invalid manually-edited optional values safely fall back without changing the saved configuration. */
    public static ExportSettings fromJson(JSONObject json, ExportSettings fallback) {
        Objects.requireNonNull(fallback, "Fallback export settings");
        if (json == null) {
            return fallback;
        }
        return new ExportSettings(
                configuredPath(json, "evidenceDirectory", fallback.evidenceDirectory()),
                configuredPath(json, "auditDirectory", fallback.auditDirectory()),
                configuredPath(json, "configurationDirectory", fallback.configurationDirectory()),
                configuredPath(json, "benchmarkDirectory", fallback.benchmarkDirectory()));
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("evidenceDirectory", evidenceDirectory.toString())
                .put("auditDirectory", auditDirectory.toString())
                .put("configurationDirectory", configurationDirectory.toString())
                .put("benchmarkDirectory", benchmarkDirectory.toString());
    }

    private static Path configuredPath(JSONObject json, String key, Path fallback) {
        String value = json.optString(key, "").strip();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return normalized(Path.of(value), key);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static Path normalized(Path value, String label) {
        try {
            return Objects.requireNonNull(value, label + " is required").toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException(label + " is not a valid folder path.", failure);
        }
    }
}
