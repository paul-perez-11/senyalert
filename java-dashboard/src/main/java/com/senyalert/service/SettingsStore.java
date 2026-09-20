package com.senyalert.service;

import com.senyalert.model.EngineSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Async, local persistence for dashboard configuration. */
public final class SettingsStore {
    private final Path configPath;
    private final AppExecutors executors;

    public SettingsStore(Path configPath, AppExecutors executors) {
        this.configPath = configPath;
        this.executors = executors;
    }

    public CompletableFuture<EngineSettings> load() {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isRegularFile(configPath)) {
                return EngineSettings.defaults();
            }
            try {
                return EngineSettings.fromJson(new JSONObject(Files.readString(configPath, StandardCharsets.UTF_8)));
            } catch (Exception invalidConfig) {
                return EngineSettings.defaults();
            }
        }, executors.media());
    }

    public CompletableFuture<EngineSettings> save(EngineSettings settings) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.writeString(configPath, settings.toPersistedJson().toString(2), StandardCharsets.UTF_8);
                return settings;
            } catch (IOException writeFailure) {
                throw new IllegalStateException("Could not save engine settings", writeFailure);
            }
        }, executors.media());
    }
}
