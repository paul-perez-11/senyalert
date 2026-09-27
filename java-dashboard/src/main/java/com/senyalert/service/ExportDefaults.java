package com.senyalert.service;

import java.nio.file.Path;
import java.util.Objects;

/** Process-local view of the saved export folders, shared by native picker callers. */
public final class ExportDefaults {
    public enum Kind { EVIDENCE, AUDIT, CONFIGURATION, BENCHMARK }

    private static volatile ExportSettings current = ExportSettings.defaults(Path.of(System.getProperty("user.home", ".")));

    private ExportDefaults() { }

    public static ExportSettings current() {
        return current;
    }

    public static void set(ExportSettings settings) {
        current = Objects.requireNonNull(settings, "Export settings");
    }

    public static Path directory(Kind kind) {
        ExportSettings settings = current;
        return switch (Objects.requireNonNull(kind, "Export kind")) {
            case EVIDENCE -> settings.evidenceDirectory();
            case AUDIT -> settings.auditDirectory();
            case CONFIGURATION -> settings.configurationDirectory();
            case BENCHMARK -> settings.benchmarkDirectory();
        };
    }
}
