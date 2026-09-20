package com.senyalert.service;

import com.senyalert.model.AndroidIpCameraRequest;
import com.senyalert.model.IpCameraZoomRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;

/**
 * Runs optional phone-camera control actions off Swing's event thread.
 *
 * <p>The ADB target is validated by {@link AndroidIpCameraRequest} and passed
 * to ProcessBuilder as individual arguments, so dashboard text cannot become
 * shell syntax. Zoom uses the Android IP Camera root control endpoint and
 * uses source-URL Basic Auth only in memory for the current request; it never
 * logs credentials or request bodies.</p>
 */
public final class CameraControlService implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(7);
    private static final Duration ADB_TIMEOUT = Duration.ofSeconds(12);

    private final ExecutorService commands;
    private final HttpClient http;

    public CameraControlService() {
        this(Executors.newSingleThreadExecutor(namedFactory("senyalert-camera-control")),
                HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    CameraControlService(ExecutorService commands, HttpClient http) {
        this.commands = commands;
        this.http = http;
    }

    /** Connects a network device when needed, then exposes phonePort at localhost:laptopPort. */
    public CompletableFuture<String> connectAndForward(AndroidIpCameraRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            if (request.needsNetworkConnect()) {
                runAdb(List.of("connect", request.adbConnection()));
            }
            runAdb(List.of(
                    "-s", request.adbConnection(),
                    "forward",
                    "tcp:" + request.laptopPort(),
                    "tcp:" + request.cameraServerPort()));
            return "ADB forwarding is ready: http://127.0.0.1:" + request.laptopPort()
                    + " → phone port " + request.cameraServerPort() + ".";
        }, commands);
    }

    /** Sends Android IP Camera's documented root zoom control without blocking the dashboard. */
    public CompletableFuture<String> applyZoom(IpCameraZoomRequest request) {
        URI controlUri = zoomUri(request.source(), request.zoom());
        HttpRequest.Builder commandBuilder = HttpRequest.newBuilder(controlUri)
                .timeout(REQUEST_TIMEOUT)
                .GET();
        basicAuthorization(request.source()).ifPresent(value -> commandBuilder.header("Authorization", value));
        return http.sendAsync(commandBuilder.build(), HttpResponse.BodyHandlers.discarding())
                .orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = rootCause(failure);
                        if (cause instanceof SSLHandshakeException) {
                            throw new CompletionException(new IllegalStateException(
                                    "The camera TLS certificate is self-signed or untrusted. SenyAlert will not bypass TLS; "
                                            + "use a trusted certificate or a local ADB-forwarded HTTP route.", cause));
                        }
                        throw new CompletionException(cause);
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        if (response.statusCode() == 401) {
                            throw new CompletionException(new IllegalStateException(
                                    "Camera rejected zoom (HTTP 401). Check its credentials or use a source URL containing valid Basic Auth credentials."));
                        }
                        throw new CompletionException(new IllegalStateException(
                                "Camera rejected zoom (HTTP " + response.statusCode() + "). Check the phone app's remote-control settings."));
                    }
                    return String.format(Locale.ROOT, "Camera zoom set to %.1f×.", request.zoom());
                });
    }

    static URI zoomUri(String source, double zoom) {
        URI stream = URI.create(source);
        String host = stream.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Zoom needs a camera host and port.");
        }
        String authority = host.contains(":") ? "[" + host + "]" : host;
        if (stream.getPort() >= 0) {
            authority += ":" + stream.getPort();
        }
        String encodedZoom = URLEncoder.encode(String.format(Locale.ROOT, "%.1f", zoom), StandardCharsets.UTF_8);
        return URI.create(stream.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + "/?zoom=" + encodedZoom);
    }

    /** Uses credentials embedded in an existing source URL only for this request; they are never logged. */
    private static Optional<String> basicAuthorization(String source) {
        String userInfo = URI.create(source).getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            return Optional.empty();
        }
        return Optional.of("Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8)));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static void runAdb(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(findAdbExecutable());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(ADB_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("ADB did not respond within " + ADB_TIMEOUT.toSeconds() + " seconds.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String detail = output.isBlank() ? "no diagnostic returned" : output;
                throw new IllegalStateException("ADB command failed: " + detail);
            }
        } catch (IOException missingAdb) {
            throw new CompletionException(new IllegalStateException(
                    "Android Platform Tools (adb) was not found. Install it or add adb to PATH, then retry.", missingAdb));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IllegalStateException("ADB setup was interrupted.", interrupted));
        }
    }

    private static String findAdbExecutable() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path bundled = Path.of(localAppData, "Android", "Sdk", "platform-tools", "adb.exe");
            if (Files.isRegularFile(bundled)) {
                return bundled.toString();
            }
        }
        return "adb";
    }

    @Override
    public void close() {
        commands.shutdownNow();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
