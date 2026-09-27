package com.senyalert.service;

import com.senyalert.EnvironmentConfiguration;
import com.senyalert.model.EngineSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Async, local persistence for dashboard configuration. */
public final class SettingsStore {
    private static final String EXPORT_DEFAULTS_KEY = "exportDefaults";
    private final Path configPath;
    private final AppExecutors executors;
    private final Object writeLock = new Object();
    private final ExportSettings defaultExportSettings;

    public SettingsStore(Path configPath, AppExecutors executors) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.executors = executors;
        Path parent = this.configPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Configuration path must have a parent directory.");
        }
        this.defaultExportSettings = ExportSettings.defaults(parent);
    }

    public ExportSettings defaultExportSettings() {
        return defaultExportSettings;
    }

    public CompletableFuture<EngineSettings> load() {
        return CompletableFuture.supplyAsync(() -> {
            boolean legacyStartupDefault = EnvironmentConfiguration.booleanValue("SENYALERT_ENGINE_AUTOSTART", true);
            if (!Files.isRegularFile(configPath)) {
                return EngineSettings.defaults(legacyStartupDefault);
            }
            try {
                return EngineSettings.fromJson(readJson(), legacyStartupDefault);
            } catch (Exception invalidConfig) {
                throw new IllegalStateException("Could not read the existing configuration. It was not changed; restore or replace it deliberately before saving settings.", invalidConfig);
            }
        }, executors.media());
    }

    public CompletableFuture<EngineSettings> save(EngineSettings settings) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                synchronized (writeLock) {
                    JSONObject persisted = settings.toPersistedJson();
                    JSONObject existing = readJsonOrEmpty();
                    if (existing.has(EXPORT_DEFAULTS_KEY)) {
                        persisted.put(EXPORT_DEFAULTS_KEY, existing.get(EXPORT_DEFAULTS_KEY));
                    }
                    savePreservingPrevious(persisted.toString(2));
                }
                return settings;
            } catch (IOException writeFailure) {
                throw new IllegalStateException("Could not save engine settings", writeFailure);
            }
        }, executors.media());
    }

    /** Reads the optional export-folder preferences from the existing local settings file. */
    public CompletableFuture<ExportSettings> loadExportSettings() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ExportSettings loaded;
                synchronized (writeLock) {
                    loaded = ExportSettings.fromJson(readJsonOrEmpty().optJSONObject(EXPORT_DEFAULTS_KEY), defaultExportSettings);
                }
                ExportDefaults.set(loaded);
                return loaded;
            } catch (Exception invalidConfig) {
                throw new IllegalStateException("Could not read the existing export settings. They were not changed.", invalidConfig);
            }
        }, executors.media());
    }

    /** Saves only operator export destinations and retains the current engine and camera configuration verbatim. */
    public CompletableFuture<ExportSettings> saveExportSettings(ExportSettings exportSettings) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                synchronized (writeLock) {
                    JSONObject existing = readJsonOrEmpty();
                    existing.put(EXPORT_DEFAULTS_KEY, exportSettings.toJson());
                    savePreservingPrevious(existing.toString(2));
                }
                ExportDefaults.set(exportSettings);
                return exportSettings;
            } catch (IOException writeFailure) {
                throw new IllegalStateException("Could not save export settings", writeFailure);
            }
        }, executors.media());
    }

    private JSONObject readJsonOrEmpty() throws IOException {
        return Files.isRegularFile(configPath) ? readJson() : new JSONObject();
    }

    private JSONObject readJson() throws IOException {
        return new JSONObject(Files.readString(configPath, StandardCharsets.UTF_8));
    }

    /**
     * A deliberate settings save keeps an immutable copy of the previous file
     * before replacing it. Staging occurs beside the live file so an incomplete
     * write cannot truncate the historical configuration.
     */
    private void savePreservingPrevious(String json) throws IOException {
        Path parent = configPath.getParent();
        if (parent == null) {
            throw new IOException("Configuration path must have a parent directory.");
        }
        Files.createDirectories(parent);
        if (Files.isRegularFile(configPath)) {
            Path backups = parent.resolve("config-backups");
            Files.createDirectories(backups);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = backups.resolve("senyalert-config-" + timestamp + "-" + UUID.randomUUID() + ".json");
            Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path staged = Files.createTempFile(parent, configPath.getFileName() + ".", ".pending");
        Files.writeString(staged, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(staged, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
