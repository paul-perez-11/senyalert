package com.senyalert.service;

import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.ArchiveExportSummary;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.MediaDeletionOptions;
import com.senyalert.repository.IncidentRepository;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Handles potentially slow media extraction and native playback outside the EDT. */
public final class MediaService {
    private final IncidentRepository repository;
    private final AppExecutors executors;
    private final Path cacheDirectory;

    public MediaService(IncidentRepository repository, AppExecutors executors, Path cacheDirectory) {
        this.repository = repository;
        this.executors = executors;
        this.cacheDirectory = cacheDirectory;
    }

    public CompletableFuture<Void> openNatively(long incidentId) {
        return repository.findEvidence(incidentId)
                .thenCompose(evidence -> CompletableFuture.runAsync(() -> {
                    IncidentEvidence value = requireEvidence(evidence, incidentId);
                    Path video = materializeVideo(value);
                    if (!Desktop.isDesktopSupported()) {
                        throw new IllegalStateException("Native video playback is not supported on this computer");
                    }
                    try {
                        Desktop.getDesktop().open(video.toFile());
                    } catch (IOException playbackFailure) {
                        throw new IllegalStateException("Could not open the incident video", playbackFailure);
                    }
                }, executors.media()));
    }

    public CompletableFuture<Void> exportVideo(long incidentId, Path destination) {
        return repository.findEvidence(incidentId)
                .thenCompose(evidence -> CompletableFuture.runAsync(() -> {
                    if (Files.exists(destination)) {
                        throw new IllegalStateException("Choose a new export filename; the selected file already exists.");
                    }
                    IncidentEvidence value = requireEvidence(evidence, incidentId);
                    if (!value.incident().mediaReady()) {
                        throw new IllegalStateException("The engine has not provided a playable video for this incident.");
                    }
                    try {
                        if (value.videoBytes() != null && value.videoBytes().length > 0) {
                            Files.write(destination, value.videoBytes());
                        } else {
                            Path source = fallbackPath(value);
                            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    } catch (IOException exportFailure) {
                        throw new IllegalStateException("Could not export the incident video", exportFailure);
                    }
                }, executors.media()));
    }

    /**
     * Removes only the explicitly selected, exact source files referenced by
     * an already-loaded incident. SQLite deletion is deliberately owned by
     * the controller and happens first, so a database failure never removes
     * source evidence. This method never traverses a directory.
     */
    public CompletableFuture<Void> deleteAssociatedMedia(
            IncidentEvidence evidence, MediaDeletionOptions options) {
        if (evidence == null || options == null || !options.deletesAnyMedia()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (options.deleteSnapshot()) {
                deleteExactRecordedFile(evidence.incident().snapshotPath(), "snapshot");
            }
            if (options.deleteVideo()) {
                deleteExactRecordedFile(evidence.incident().videoPath(), "video");
            }
        }, executors.media());
    }

    /**
     * Exports copies to a new timestamped folder. It never mutates SQLite and
     * never deletes or moves the engine's original evidence paths.
     */
    public CompletableFuture<ArchiveExportSummary> exportArchive(
            List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        List<Long> ids = incidentIds == null ? List.of() : incidentIds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(id -> id > 0)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        if (ids.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Choose one or more incident records to export."));
        }
        if (destinationDirectory == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Choose an export folder first."));
        }
        return CompletableFuture.supplyAsync(() -> exportArchiveBlocking(ids, destinationDirectory, mode), executors.media());
    }

    private ArchiveExportSummary exportArchiveBlocking(
            List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        if (!Files.isDirectory(destinationDirectory)) {
            throw new IllegalStateException("The selected export location is not a folder.");
        }
        try {
            Path output = createExportFolder(destinationDirectory);
            int reports = 0;
            int snapshots = 0;
            int videos = 0;
            for (long incidentId : incidentIds) {
                Optional<IncidentEvidence> optionalEvidence = repository.findEvidence(incidentId).join();
                if (optionalEvidence.isEmpty()) {
                    continue;
                }
                IncidentEvidence evidence = optionalEvidence.get();
                Incident incident = evidence.incident();
                Path incidentDirectory = output.resolve("incident-" + incident.id());
                Files.createDirectories(incidentDirectory);
                if (mode == ArchiveExportMode.RECORD_BUNDLE) {
                    Files.writeString(incidentDirectory.resolve("incident-report.txt"), formatReport(evidence));
                    reports++;
                }
                if (mode == ArchiveExportMode.RECORD_BUNDLE
                        || mode == ArchiveExportMode.SNAPSHOTS
                        || mode == ArchiveExportMode.MEDIA) {
                    if (copySnapshot(evidence, incidentDirectory)) {
                        snapshots++;
                    }
                }
                if (mode == ArchiveExportMode.RECORD_BUNDLE
                        || mode == ArchiveExportMode.VIDEOS
                        || mode == ArchiveExportMode.MEDIA) {
                    if (copyVideo(evidence, incidentDirectory)) {
                        videos++;
                    }
                }
            }
            return new ArchiveExportSummary(output, reports, snapshots, videos);
        } catch (IOException | java.util.concurrent.CompletionException failure) {
            throw new IllegalStateException("Could not export the selected evidence archive.", failure);
        }
    }

    private static Path createExportFolder(Path destinationDirectory) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path output = destinationDirectory.resolve("SenyAlert-evidence-" + timestamp);
        int suffix = 2;
        while (Files.exists(output)) {
            output = destinationDirectory.resolve("SenyAlert-evidence-" + timestamp + "-" + suffix++);
        }
        return Files.createDirectory(output);
    }

    private static boolean copySnapshot(IncidentEvidence evidence, Path outputDirectory) throws IOException {
        byte[] bytes = evidence.snapshotBytes();
        String extension = extension(evidence.snapshotMimeType(), evidence.incident().snapshotPath(), ".jpg");
        Path destination = outputDirectory.resolve("snapshot" + extension);
        if (bytes != null && bytes.length > 0) {
            Files.write(destination, bytes);
            return true;
        }
        return copyFallback(evidence.incident().snapshotPath(), destination);
    }

    private static boolean copyVideo(IncidentEvidence evidence, Path outputDirectory) throws IOException {
        byte[] bytes = evidence.videoBytes();
        String extension = extension(evidence.videoMimeType(), evidence.incident().videoPath(), ".mp4");
        Path destination = outputDirectory.resolve("incident-video" + extension);
        if (bytes != null && bytes.length > 0) {
            Files.write(destination, bytes);
            return true;
        }
        return copyFallback(evidence.incident().videoPath(), destination);
    }

    private static boolean copyFallback(String sourceValue, Path destination) throws IOException {
        if (sourceValue == null || sourceValue.isBlank()) {
            return false;
        }
        try {
            Path source = Path.of(sourceValue);
            if (!Files.isRegularFile(source)) {
                return false;
            }
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private static String extension(String mimeType, String fallbackPath, String defaultExtension) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.contains("png")) {
            return ".png";
        }
        if (mime.contains("jpeg") || mime.contains("jpg")) {
            return ".jpg";
        }
        if (mime.contains("webp")) {
            return ".webp";
        }
        if (mime.contains("avi")) {
            return ".avi";
        }
        if (mime.contains("webm")) {
            return ".webm";
        }
        if (fallbackPath != null) {
            int dot = fallbackPath.lastIndexOf('.');
            int slash = Math.max(fallbackPath.lastIndexOf('/'), fallbackPath.lastIndexOf('\\'));
            if (dot > slash && dot < fallbackPath.length() - 1) {
                String candidate = fallbackPath.substring(dot);
                if (candidate.length() <= 8 && candidate.matches("\\.[A-Za-z0-9]+")) {
                    return candidate.toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        return defaultExtension;
    }

    private static String formatReport(IncidentEvidence evidence) {
        Incident incident = evidence.incident();
        return "SenyAlert incident evidence report\n"
                + "===============================\n"
                + "Incident ID: " + incident.id() + "\n"
                + "Camera: " + incident.cameraId() + "\n"
                + "Location: " + incident.location() + "\n"
                + "Detected: " + incident.detectionTimestamp() + "\n"
                + "Incident type: " + emptyDisplay(incident.incidentType()) + "\n"
                + "Confidence: " + String.format(java.util.Locale.ROOT, "%.1f%%", incident.confidence() * 100.0) + "\n"
                + "Alert policy: " + incident.alertMode().displayName() + "\n"
                + "Status: " + incident.status() + "\n"
                + "People / hands / signalers: " + incident.peopleCount() + " / " + incident.handCount()
                + " / " + incident.signalerCount() + "\n"
                + "Occupancy: " + (incident.peopleCountStale() ? "stale" : incident.occupancyStatus()) + "\n"
                + "Signaler track: " + emptyDisplay(incident.signalerTrackId()) + "\n"
                + "Signaler bounds: " + emptyDisplay(incident.signalerBounds()) + "\n"
                + "Snapshot supplied: " + ((evidence.snapshotBytes() != null && evidence.snapshotBytes().length > 0)
                        || readablePath(incident.snapshotPath())) + "\n"
                + "Video supplied: " + ((evidence.videoBytes() != null && evidence.videoBytes().length > 0)
                        || readablePath(incident.videoPath())) + "\n"
                + "Operator note: " + emptyDisplay(incident.operatorNotes()) + "\n\n"
                + "This export is a copy. The dashboard database and source media were not changed.\n";
    }

    private static String emptyDisplay(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static boolean readablePath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Path materializeVideo(IncidentEvidence evidence) {
        try {
            if (!evidence.incident().mediaReady()) {
                throw new IllegalStateException("The engine has not provided a playable video for this incident.");
            }
            if (evidence.videoBytes() == null || evidence.videoBytes().length == 0) {
                return fallbackPath(evidence);
            }
            Files.createDirectories(cacheDirectory);
            Path output = cacheDirectory.resolve("incident-" + evidence.incident().id() + "-video.mp4");
            Files.write(output, evidence.videoBytes());
            return output;
        } catch (IOException extractionFailure) {
            throw new IllegalStateException("Could not prepare the incident video", extractionFailure);
        }
    }

    private static IncidentEvidence requireEvidence(Optional<IncidentEvidence> evidence, long incidentId) {
        return evidence.orElseThrow(() -> new IllegalStateException("Incident #" + incidentId + " was not found"));
    }

    private static Path fallbackPath(IncidentEvidence evidence) {
        String path = evidence.incident().videoPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("This incident does not have a completed video yet.");
        }
        Path source = Path.of(path);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("The video file is unavailable at its recorded path.");
        }
        return source;
    }

    private static void deleteExactRecordedFile(String sourceValue, String mediaLabel) {
        if (sourceValue == null || sourceValue.isBlank()) {
            return;
        }
        final Path target;
        try {
            target = Path.of(sourceValue).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            throw new IllegalStateException("The recorded " + mediaLabel + " path is invalid.", invalidPath);
        }
        try {
            // Resolve and inspect the exact target before deletion. There is
            // no wildcard, parent directory, or recursive operation here.
            if (!Files.isRegularFile(target)) {
                return;
            }
            Files.delete(target);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not delete the selected " + mediaLabel
                    + " file at " + target.getFileName() + ".", failure);
        }
    }
}
