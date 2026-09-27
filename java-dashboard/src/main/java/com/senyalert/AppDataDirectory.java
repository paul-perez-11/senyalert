package com.senyalert;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the one existing local data directory before any SQLite store is opened. */
final class AppDataDirectory {
    private AppDataDirectory() { }

    static Path resolve() {
        return resolve(Path.of(""), EnvironmentConfiguration.value("SENYALERT_DATA_DIR"));
    }

    /**
     * Resolves a source-tree launch to {@code java-dashboard}, where this project
     * has always stored its local databases and configuration. A packaged launch
     * keeps its current directory. This method never creates, moves, or copies data.
     */
    static Path resolve(Path workingDirectory, String configuredDirectory) {
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            final Path configured;
            try {
                configured = Path.of(configuredDirectory.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("SENYALERT_DATA_DIR is not a valid folder path.", invalid);
            }
            if (!Files.isDirectory(configured)) {
                throw new IllegalStateException("SENYALERT_DATA_DIR must name an existing folder. SenyAlert will not create a new data location automatically.");
            }
            return configured;
        }

        Path current = workingDirectory.toAbsolutePath().normalize();
        Path sourceDashboard = current.resolve("java-dashboard");
        boolean sourceProject = Files.isRegularFile(sourceDashboard.resolve("pom.xml"));

        // A source project may have a stray root-level store left by an old
        // launch command. Prefer the established java-dashboard store whenever
        // it already contains SenyAlert data.
        if (sourceProject && hasStore(sourceDashboard)) {
            return sourceDashboard;
        }
        if (hasStore(current)) {
            return current;
        }
        if (sourceProject) {
            return sourceDashboard;
        }
        return current;
    }

    private static boolean hasStore(Path directory) {
        return Files.isRegularFile(directory.resolve("incidents.db"))
                || Files.isRegularFile(directory.resolve("uploaded-incidents.db"))
                || Files.isRegularFile(directory.resolve("senyalert-config.json"))
                || Files.isRegularFile(directory.resolve("security.db"));
    }
}
