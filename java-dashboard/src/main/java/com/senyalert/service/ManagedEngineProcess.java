package com.senyalert.service;

import com.senyalert.EnvironmentConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns only the Python ingestion process launched for one signed-in dashboard
 * session. It never searches for or terminates an independently started
 * Python process.
 */
public final class ManagedEngineProcess implements AutoCloseable {
    private static final Duration STOP_WAIT = Duration.ofSeconds(3);

    private final Path projectRoot;
    private final String python;
    private final Consumer<String> status;
    private Process process;
    private boolean stopping;
    private boolean closed;

    public ManagedEngineProcess(Path projectRoot, String python, Consumer<String> status) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.python = python == null || python.isBlank() ? "python" : python.trim();
        this.status = status == null ? ignored -> { } : status;
    }

    public static ManagedEngineProcess forCurrentInstallation(Consumer<String> status) {
        Path root = BenchmarkService.findProjectRoot();
        return new ManagedEngineProcess(root, BenchmarkService.defaultPython(root), status);
    }

    /** Starts the owned engine off the Swing event thread. */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(this::startBlocking);
    }

    private synchronized void startBlocking() {
        if (closed) {
            throw new IllegalStateException("The dashboard session ended before the vision engine could start.");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        Path script = projectRoot.resolve("src").resolve("python-prototype.py");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Could not find src/python-prototype.py. Set SENYALERT_PROJECT_ROOT before signing in.");
        }
        ProcessBuilder command = new ProcessBuilder(List.of(python, "-u", script.toString()))
                .directory(projectRoot.toFile()).redirectErrorStream(true);
        Map<String, String> childEnvironment = command.environment();
        childEnvironment.putAll(EnvironmentConfiguration.values());
        childEnvironment.put("SENYALERT_PROJECT_ROOT", projectRoot.toString());
        EnvironmentConfiguration.source().ifPresent(file -> childEnvironment.put("SENYALERT_ENV_FILE", file.toString()));
        childEnvironment.put("SENYALERT_ENV_LOADED", "1");
        // The engine needs only its endpoint and any ordinary camera variables.
        // Keep developer and remote-support secrets out of a child process.
        childEnvironment.remove("SENYALERT_SUPERADMIN_PASSWORD_HASH");
        childEnvironment.remove("SENYALERT_SUPPORT_SECRET");
        childEnvironment.remove("SENYALERT_CLIENT_ID");
        Process launched;
        try {
            launched = command.start();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start the configured Python ingestion engine.", failure);
        }
        process = launched;
        stopping = false;
        status.accept("Starting the local vision engine after sign-in…");
        Thread watcher = new Thread(() -> watch(launched), "senyalert-engine-output");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watch(Process launched) {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                launched.getInputStream(), StandardCharsets.UTF_8))) {
            while (output.readLine() != null) {
                // Drain Python/OpenCV diagnostics so the owned process cannot
                // block. They can include local paths, so they are not copied
                // into the dashboard or audit history.
            }
        } catch (IOException ignored) {
            // Process shutdown closes the stream. The exit status below is sufficient.
        }
        try {
            int exit = launched.waitFor();
            synchronized (this) {
                if (process == launched) {
                    process = null;
                    if (!stopping) {
                        status.accept("Vision engine stopped before the dashboard session ended (exit " + exit + ").");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean running() {
        return process != null && process.isAlive();
    }

    /** Stops just this process and the descendants that it owns. */
    public synchronized void stop() {
        closed = true;
        stopping = true;
        Process owned = process;
        process = null;
        if (owned == null || !owned.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = owned.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        owned.destroy();
        try {
            if (!owned.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                owned.destroyForcibly();
                owned.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            owned.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        stop();
    }
}
