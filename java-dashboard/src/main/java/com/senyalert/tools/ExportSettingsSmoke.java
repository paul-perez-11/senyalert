package com.senyalert.tools;

import com.senyalert.model.EngineSettings;
import com.senyalert.service.AppExecutors;
import com.senyalert.service.ExportDefaults;
import com.senyalert.service.ExportSettings;
import com.senyalert.service.SettingsStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Isolated persistence checks; requires a new empty directory and never opens live data. */
public final class ExportSettingsSmoke {
    private ExportSettingsSmoke() { }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("target", "export-settings-smoke-" + System.currentTimeMillis()) : Path.of(args[0]);
        if (Files.exists(root)) {
            throw new IllegalArgumentException("Use a new empty smoke directory, not an existing installation.");
        }
        Files.createDirectories(root);
        try (AppExecutors executors = new AppExecutors()) {
            Path config = root.resolve("senyalert-config.json");
            SettingsStore store = new SettingsStore(config, executors);
            ExportSettings defaults = store.loadExportSettings().join();
            check(defaults.evidenceDirectory().startsWith(root.toAbsolutePath().normalize()),
                    "defaults stay beside the isolated installation");

            EngineSettings engine = EngineSettings.defaults();
            store.save(engine).join();
            ExportSettings requested = new ExportSettings(
                    root.resolve("operator-exports").resolve("evidence"),
                    root.resolve("operator-exports").resolve("audit"),
                    root.resolve("operator-exports").resolve("configuration"),
                    root.resolve("operator-exports").resolve("benchmarking"));
            store.saveExportSettings(requested).join();
            check(store.loadExportSettings().join().equals(requested), "saved export defaults round-trip");
            check(ExportDefaults.directory(ExportDefaults.Kind.AUDIT).equals(requested.auditDirectory()),
                    "shared picker defaults update after save");

            store.save(engine).join();
            JSONObject persisted = new JSONObject(Files.readString(config, StandardCharsets.UTF_8));
            check(persisted.has("exportDefaults"), "engine save retains export defaults");
            check(EngineSettings.fromJson(persisted).equals(engine), "export settings do not alter engine configuration");
        }
        System.out.println("Export settings smoke passed. Isolated artifacts retained at " + root.toAbsolutePath());
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        System.out.println("PASS " + label);
    }
}
