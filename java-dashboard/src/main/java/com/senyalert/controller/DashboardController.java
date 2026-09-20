package com.senyalert.controller;

import com.senyalert.model.AlertMode;
import com.senyalert.model.AndroidIpCameraRequest;
import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.ArchiveExportSummary;
import com.senyalert.model.ArchiveScope;
import com.senyalert.model.CameraPreview;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.IpCameraZoomRequest;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.model.MediaDeletionOptions;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.model.UploadedVideoRequest;
import com.senyalert.model.UploadedVideoStatus;
import com.senyalert.repository.IncidentRepository;
import com.senyalert.service.AlertPolicy;
import com.senyalert.service.AlertSoundService;
import com.senyalert.service.CameraPreviewService;
import com.senyalert.service.CameraControlService;
import com.senyalert.service.EngineGateway;
import com.senyalert.service.MediaService;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.DashboardView;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;

/** Coordinates the model/services and marshals every presentation update to the EDT. */
public final class DashboardController implements DashboardActions {
    private final DashboardView view;
    private final IncidentRepository incidents;
    private final IncidentRepository uploadedIncidents;
    private final SettingsStore settingsStore;
    private final AlertPolicy alertPolicy;
    private final AlertSoundService alertSoundService;
    private final MediaService mediaService;
    private final MediaService uploadedMediaService;
    private final CameraPreviewService cameraPreviewService;
    private final CameraControlService cameraControlService;
    private final Map<Long, Incident> incidentCache = new ConcurrentHashMap<>();
    private final Map<Long, Incident> uploadedIncidentCache = new ConcurrentHashMap<>();
    private final Object settingsLock = new Object();

    private volatile EngineSettings settings = EngineSettings.defaults();
    private volatile EngineGateway engineGateway;
    private volatile boolean enginePaused;
    private volatile boolean settingsLoaded;

    public DashboardController(
            DashboardView view,
            IncidentRepository incidents,
            SettingsStore settingsStore,
            AlertPolicy alertPolicy,
            MediaService mediaService,
            CameraPreviewService cameraPreviewService) {
        this(view, incidents, incidents, settingsStore, alertPolicy, mediaService, mediaService,
                cameraPreviewService, AlertSoundService.disabled(), new CameraControlService());
    }

    public DashboardController(
            DashboardView view,
            IncidentRepository incidents,
            SettingsStore settingsStore,
            AlertPolicy alertPolicy,
            MediaService mediaService,
            CameraPreviewService cameraPreviewService,
            AlertSoundService alertSoundService) {
        this(view, incidents, incidents, settingsStore, alertPolicy, mediaService, mediaService,
                cameraPreviewService, alertSoundService, new CameraControlService());
    }

    /**
     * Full composition constructor. Uploaded-video results intentionally use
     * a different repository and media cache so they cannot mix with live
     * recorded incidents.
     */
    public DashboardController(
            DashboardView view,
            IncidentRepository incidents,
            IncidentRepository uploadedIncidents,
            SettingsStore settingsStore,
            AlertPolicy alertPolicy,
            MediaService mediaService,
            MediaService uploadedMediaService,
            CameraPreviewService cameraPreviewService,
            AlertSoundService alertSoundService) {
        this(view, incidents, uploadedIncidents, settingsStore, alertPolicy, mediaService, uploadedMediaService,
                cameraPreviewService, alertSoundService, new CameraControlService());
    }

    /** Full composition including non-blocking optional Android IP Camera controls. */
    public DashboardController(
            DashboardView view,
            IncidentRepository incidents,
            IncidentRepository uploadedIncidents,
            SettingsStore settingsStore,
            AlertPolicy alertPolicy,
            MediaService mediaService,
            MediaService uploadedMediaService,
            CameraPreviewService cameraPreviewService,
            AlertSoundService alertSoundService,
            CameraControlService cameraControlService) {
        this.view = view;
        this.incidents = incidents;
        this.uploadedIncidents = uploadedIncidents == null ? incidents : uploadedIncidents;
        this.settingsStore = settingsStore;
        this.alertPolicy = alertPolicy;
        this.mediaService = mediaService;
        this.uploadedMediaService = uploadedMediaService == null ? mediaService : uploadedMediaService;
        this.cameraPreviewService = cameraPreviewService;
        this.cameraControlService = cameraControlService == null ? new CameraControlService() : cameraControlService;
        this.alertSoundService = alertSoundService == null ? AlertSoundService.disabled() : alertSoundService;
        this.alertSoundService.setFailureReporter(message ->
                onEdt(() -> view.showError("Alert feedback", message)));
    }

    public void attachEngineGateway(EngineGateway gateway) {
        this.engineGateway = gateway;
        pushSavedSettingsIfConnected();
    }

    @Override
    public CompletableFuture<String> connectAndroidIpCamera(AndroidIpCameraRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter an ADB connection and valid ports first."));
        }
        return cameraControlService.connectAndForward(request).whenComplete((message, failure) -> {
            if (failure != null) {
                showFailure("Phone camera ADB", failure);
            } else {
                onEdt(() -> view.showInfo(message));
            }
        });
    }

    @Override
    public CompletableFuture<String> applyIpCameraZoom(IpCameraZoomRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter an HTTP camera source and zoom value first."));
        }
        return cameraControlService.applyZoom(request).whenComplete((message, failure) -> {
            if (failure != null) {
                showFailure("Phone camera zoom", failure);
            } else {
                onEdt(() -> view.showInfo(message));
            }
        });
    }

    public void initialize() {
        settingsStore.load().whenComplete((loaded, failure) -> {
            if (failure != null) {
                showFailure("Settings", failure);
                return;
            }
            settings = loaded;
            settingsLoaded = true;
            onEdt(() -> view.showSettings(loaded));
            pushSavedSettingsIfConnected();
        });
        refreshIncidents();
    }

    public void onDistress(DistressEvent event) {
        AlertMode alertMode = alertPolicy.decide(event, settings);
        incidents.create(event, alertMode).whenComplete((incident, failure) -> {
            if (failure != null) {
                showFailure("Incident persistence", failure);
                return;
            }
            incidentCache.put(incident.id(), incident);
            if (incident.status() == IncidentStatus.PENDING) {
                alertSoundService.notifyNewActionableIncident(incident);
            }
            onEdt(() -> {
                view.upsertIncident(incident);
                if (incident.status() == IncidentStatus.PENDING) {
                    view.showAlert(incident);
                }
            });
        });
    }

    /**
     * Offline video analysis persists to uploaded-incidents.db and deliberately
     * does not trigger the live dispatch banner or laptop alarm.
     */
    public void onUploadedDistress(DistressEvent event) {
        AlertMode alertMode = alertPolicy.decide(event, settings);
        uploadedIncidents.create(event, alertMode).whenComplete((incident, failure) -> {
            if (failure != null) {
                showFailure("Uploaded incident persistence", failure);
                return;
            }
            uploadedIncidentCache.put(incident.id(), incident);
            onEdt(() -> view.upsertUploadedIncident(incident));
        });
    }

    public void onMediaReady(MediaReadyEvent event) {
        incidents.attachMedia(event).whenComplete((updated, failure) -> {
            if (failure != null) {
                showFailure("Incident media", failure);
                return;
            }
            updated.ifPresent(incident -> {
                incidentCache.put(incident.id(), incident);
                onEdt(() -> view.upsertIncident(incident));
            });
        });
    }

    public void onUploadedMediaReady(MediaReadyEvent event) {
        uploadedIncidents.attachMedia(event).whenComplete((updated, failure) -> {
            if (failure != null) {
                showFailure("Uploaded incident media", failure);
                return;
            }
            updated.ifPresent(incident -> {
                uploadedIncidentCache.put(incident.id(), incident);
                onEdt(() -> view.upsertUploadedIncident(incident));
            });
        });
    }

    /** Receives explicit lifecycle/progress updates for an offline upload job. */
    public void onUploadedVideoStatus(UploadedVideoStatus status) {
        if (status == null) {
            return;
        }
        onEdt(() -> view.showUploadedVideoStatus(status));
        // The same single database executor receives event writes before this
        // refresh.  A terminal refresh therefore makes the isolated archive
        // authoritative even if a live upsert was missed while the UI was busy.
        if (status.isComplete() || status.isFailure()) {
            refreshUploadedIncidents();
        }
    }

    public void onEngineAcknowledgement(String message) {
        onEdt(() -> view.showInfo(message));
    }

    /** Called by the WebSocket adapter after its connection reference is available. */
    public void onEngineConnected() {
        pushSavedSettingsIfConnected();
    }

    public void onEngineStatus(EngineStatus status) {
        enginePaused = status.paused();
        onEdt(() -> view.showEngineStatus(status));
    }

    /** Receives a raw engine frame and leaves decoding/coalescing to the media worker. */
    public void onCameraPreview(CameraPreview preview) {
        cameraPreviewService.submit(preview, frame -> onEdt(() -> view.showCameraPreview(frame)));
    }

    @Override
    public void refreshIncidents() {
        incidents.listRecent().whenComplete((loaded, failure) -> {
            if (failure != null) {
                showFailure("Incident history", failure);
                return;
            }
            incidentCache.clear();
            loaded.forEach(incident -> incidentCache.put(incident.id(), incident));
            onEdt(() -> {
                view.showIncidents(loaded);
                refreshVisibleAlert();
            });
        });
        refreshUploadedIncidents();
    }

    private void refreshUploadedIncidents() {
        uploadedIncidents.listRecent().whenComplete((loaded, failure) -> {
            if (failure != null) {
                showFailure("Uploaded incident history", failure);
                return;
            }
            uploadedIncidentCache.clear();
            loaded.forEach(incident -> uploadedIncidentCache.put(incident.id(), incident));
            onEdt(() -> view.showUploadedIncidents(loaded));
        });
    }

    @Override
    public void selectIncident(long incidentId) {
        loadIncidentEvidence(incidents, ArchiveScope.RECORDED, incidentId, false);
    }

    @Override
    public void selectIncidentForArchive(long incidentId) {
        selectIncidentForArchive(ArchiveScope.RECORDED, incidentId);
    }

    @Override
    public void selectIncidentForArchive(ArchiveScope scope, long incidentId) {
        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        loadIncidentEvidence(repositoryFor(safeScope), safeScope, incidentId, true);
    }

    private void loadIncidentEvidence(
            IncidentRepository repository, ArchiveScope scope, long incidentId, boolean archiveModal) {
        repository.findEvidence(incidentId).whenComplete((evidence, failure) -> {
            if (failure != null) {
                showFailure("Incident evidence", failure);
                return;
            }
            evidence.ifPresentOrElse(
                    item -> onEdt(() -> {
                        if (archiveModal) {
                            view.showArchiveIncidentEvidence(scope, item);
                        } else {
                            view.showIncidentEvidence(item);
                        }
                    }),
                    () -> onEdt(() -> view.showInfo("That incident is no longer available.")));
        });
    }

    @Override
    public void acknowledgeIncident(long incidentId) {
        updateOperatorRecord(incidentId, new OperatorIncidentUpdate(
                IncidentStatus.ACKNOWLEDGED, existingNoteOr(incidentId, "Acknowledged by operator")));
    }

    @Override
    public void resolveIncident(long incidentId) {
        updateOperatorRecord(incidentId, new OperatorIncidentUpdate(
                IncidentStatus.RESOLVED, existingNoteOr(incidentId, "Resolved by operator")));
    }

    @Override
    public void updateOperatorRecord(long incidentId, OperatorIncidentUpdate update) {
        updateOperatorRecord(ArchiveScope.RECORDED, incidentId, update);
    }

    @Override
    public void updateOperatorRecord(ArchiveScope scope, long incidentId, OperatorIncidentUpdate update) {
        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        IncidentRepository repository = repositoryFor(safeScope);
        repository.updateOperatorRecord(incidentId, update).whenComplete((updated, failure) -> {
            if (failure != null) {
                showFailure("Update operator record", failure);
                return;
            }
            updated.ifPresentOrElse(incident -> {
                cacheFor(safeScope).put(incident.id(), incident);
                onEdt(() -> {
                    if (safeScope == ArchiveScope.UPLOADED) {
                        view.upsertUploadedIncident(incident);
                        view.showUploadedOperatorRecordUpdated(incident);
                    } else {
                        view.upsertIncident(incident);
                        view.showOperatorRecordUpdated(incident);
                        refreshVisibleAlert();
                    }
                    view.showInfo("Operator record saved for " + safeScope.displayName().toLowerCase()
                            + " incident #" + incident.id() + ".");
                });
            }, () -> onEdt(() -> view.showInfo("That incident is no longer available.")));
        });
    }

    @Override
    public void updateOperatorRecords(Map<Long, OperatorIncidentUpdate> updates) {
        updateOperatorRecords(ArchiveScope.RECORDED, updates);
    }

    @Override
    public void updateOperatorRecords(ArchiveScope scope, Map<Long, OperatorIncidentUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<Long, OperatorIncidentUpdate> safeUpdates = new LinkedHashMap<>();
        updates.forEach((incidentId, update) -> {
            if (incidentId != null && incidentId > 0 && update != null) {
                safeUpdates.put(incidentId, update);
            }
        });
        if (safeUpdates.isEmpty()) {
            return;
        }

        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        IncidentRepository repository = repositoryFor(safeScope);
        List<CompletableFuture<Optional<Incident>>> writes = new ArrayList<>();
        safeUpdates.forEach((incidentId, update) -> writes.add(repository.updateOperatorRecord(incidentId, update)));
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Bulk operator update", failure);
                if (safeScope == ArchiveScope.UPLOADED) {
                    refreshUploadedIncidents();
                } else {
                    refreshIncidents();
                }
                return;
            }
            List<Incident> updated = writes.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .toList();
            updated.forEach(incident -> cacheFor(safeScope).put(incident.id(), incident));
            onEdt(() -> {
                updated.forEach(incident -> {
                    if (safeScope == ArchiveScope.UPLOADED) {
                        view.upsertUploadedIncident(incident);
                        view.showUploadedOperatorRecordUpdated(incident);
                    } else {
                        view.upsertIncident(incident);
                        view.showOperatorRecordUpdated(incident);
                    }
                });
                if (safeScope == ArchiveScope.RECORDED) {
                    refreshVisibleAlert();
                }
                view.showInfo(updated.size() + " " + safeScope.displayName().toLowerCase()
                        + " operator record(s) updated. Detection and evidence stayed read-only.");
            });
        });
    }

    @Override
    public void deleteIncidentRecord(long incidentId) {
        deleteIncidentRecords(ArchiveScope.RECORDED, List.of(incidentId), MediaDeletionOptions.recordsOnly());
    }

    @Override
    public void deleteIncidentRecords(List<Long> incidentIds) {
        deleteIncidentRecords(ArchiveScope.RECORDED, incidentIds, MediaDeletionOptions.recordsOnly());
    }

    @Override
    public void deleteIncidentRecords(
            ArchiveScope scope, List<Long> incidentIds, MediaDeletionOptions options) {
        if (incidentIds == null || incidentIds.isEmpty()) {
            return;
        }
        List<Long> ids = incidentIds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        MediaDeletionOptions safeOptions = options == null ? MediaDeletionOptions.recordsOnly() : options;
        IncidentRepository repository = repositoryFor(safeScope);
        MediaService scopedMediaService = mediaServiceFor(safeScope);
        List<CompletableFuture<Optional<com.senyalert.model.IncidentEvidence>>> evidenceLoads = ids.stream()
                .map(repository::findEvidence)
                .toList();
        CompletableFuture.allOf(evidenceLoads.toArray(CompletableFuture[]::new)).thenCompose(unused -> {
            List<Optional<com.senyalert.model.IncidentEvidence>> evidence = evidenceLoads.stream()
                    .map(CompletableFuture::join)
                    .toList();
            List<CompletableFuture<Boolean>> deletes = ids.stream().map(repository::deleteRecord).toList();
            return CompletableFuture.allOf(deletes.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
                List<Long> deletedIds = new ArrayList<>();
                for (int index = 0; index < deletes.size(); index++) {
                    if (Boolean.TRUE.equals(deletes.get(index).join())) {
                        deletedIds.add(ids.get(index));
                    }
                }
                if (!safeOptions.deletesAnyMedia() || deletedIds.isEmpty()) {
                    return CompletableFuture.completedFuture(new DeleteOutcome(deletedIds, List.of(), safeOptions));
                }
                List<String> mediaFailures = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> mediaDeletes = new ArrayList<>();
                for (int index = 0; index < deletedIds.size(); index++) {
                    long deletedId = deletedIds.get(index);
                    int originalIndex = ids.indexOf(deletedId);
                    Optional<com.senyalert.model.IncidentEvidence> optional = originalIndex >= 0
                            ? evidence.get(originalIndex) : Optional.empty();
                    optional.ifPresent(item -> mediaDeletes.add(scopedMediaService
                            .deleteAssociatedMedia(item, safeOptions)
                            .exceptionally(failure -> {
                                mediaFailures.add(rootMessage(failure));
                                return null;
                            })));
                }
                return CompletableFuture.allOf(mediaDeletes.toArray(CompletableFuture[]::new))
                        .thenApply(ignoredAgain -> new DeleteOutcome(deletedIds, List.copyOf(mediaFailures), safeOptions));
            });
        }).whenComplete((outcome, failure) -> {
            if (failure != null) {
                showFailure("Bulk delete incident records", failure);
                if (safeScope == ArchiveScope.UPLOADED) {
                    refreshUploadedIncidents();
                } else {
                    refreshIncidents();
                }
                return;
            }
            if (outcome.deletedIds().isEmpty()) {
                onEdt(() -> view.showInfo("Those incident records are no longer available."));
                return;
            }
            outcome.deletedIds().forEach(cacheFor(safeScope)::remove);
            onEdt(() -> {
                if (safeScope == ArchiveScope.UPLOADED) {
                    outcome.deletedIds().forEach(view::removeUploadedIncident);
                } else {
                    outcome.deletedIds().forEach(view::removeIncident);
                    refreshVisibleAlert();
                }
                String mediaMessage = outcome.options().deletesAnyMedia()
                        ? " Selected associated media files were requested for deletion."
                        : " Associated snapshot and video files were left untouched.";
                if (!outcome.mediaFailures().isEmpty()) {
                    mediaMessage += " " + outcome.mediaFailures().size()
                            + " media file(s) could not be removed; check the status message.";
                }
                view.showInfo(outcome.deletedIds().size() + " " + safeScope.displayName().toLowerCase()
                        + " database record(s) removed." + mediaMessage);
                if (!outcome.mediaFailures().isEmpty()) {
                    view.showError("Associated media cleanup", outcome.mediaFailures().get(0));
                }
            });
        });
    }

    @Override
    public void saveSettings(EngineSettings newSettings) {
        settingsStore.save(newSettings).whenComplete((saved, failure) -> {
            if (failure != null) {
                showFailure("Save settings", failure);
                return;
            }
            settings = saved;
            settingsLoaded = true;
            EngineGateway gateway = engineGateway;
            boolean delivered = gateway != null && gateway.sendSettings(saved);
            onEdt(() -> {
                view.showSettings(saved);
                view.showInfo(delivered
                        ? "Settings saved and sent to the vision engine."
                        : "Settings saved locally. Connect the vision engine to apply them live.");
            });
        });
    }

    @Override
    public void toggleEnginePause() {
        EngineGateway gateway = engineGateway;
        boolean requestedState = !enginePaused;
        if (gateway == null || !gateway.setPaused(requestedState)) {
            onEdt(() -> view.showInfo("The vision engine is not connected."));
            return;
        }
        enginePaused = requestedState;
        onEdt(() -> view.showEngineStatus(new EngineStatus(true, requestedState,
                requestedState ? "Pause command sent to engine" : "Resume command sent to engine", "")));
    }

    @Override
    public void restartEngine() {
        EngineGateway gateway = engineGateway;
        if (gateway == null || !gateway.restartEngine()) {
            onEdt(() -> view.showError("Restart engine",
                    "The vision engine is not connected. Start it, then try Restart Engine again."));
            return;
        }
        onEdt(() -> {
            view.showEngineStatus(new EngineStatus(true, enginePaused,
                    "Restart command sent. Enabled camera workers are reconnecting; settings and evidence are retained.", ""));
            view.showInfo("Restart requested. Camera workers will reconnect without clearing saved settings or evidence.");
        });
    }

    @Override
    public void playVideoNatively(long incidentId) {
        playVideoNatively(ArchiveScope.RECORDED, incidentId);
    }

    @Override
    public void playVideoNatively(ArchiveScope scope, long incidentId) {
        mediaServiceFor(scope).openNatively(incidentId).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Native playback", failure);
            }
        });
    }

    @Override
    public void exportVideo(long incidentId, Path destination) {
        exportVideo(ArchiveScope.RECORDED, incidentId, destination);
    }

    @Override
    public void exportVideo(ArchiveScope scope, long incidentId, Path destination) {
        mediaServiceFor(scope).exportVideo(incidentId, destination).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Export video", failure);
                return;
            }
            onEdt(() -> view.showInfo("Video exported to " + destination.getFileName()));
        });
    }

    @Override
    public void exportArchive(List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        exportArchive(ArchiveScope.RECORDED, incidentIds, destinationDirectory, mode);
    }

    @Override
    public void exportArchive(
            ArchiveScope scope, List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        if (incidentIds == null || incidentIds.isEmpty() || destinationDirectory == null || mode == null) {
            return;
        }
        mediaServiceFor(scope).exportArchive(incidentIds, destinationDirectory, mode).whenComplete((summary, failure) -> {
            if (failure != null) {
                showFailure("Export evidence archive", failure);
                return;
            }
            onEdt(() -> view.showInfo(formatArchiveExportSummary(summary)));
        });
    }

    @Override
    public void submitUploadedVideo(UploadedVideoRequest request) {
        if (request == null) {
            return;
        }
        if (!Files.isRegularFile(request.source())) {
            String message = "The selected file is no longer available. Choose a readable local video file.";
            onEdt(() -> {
                view.showUploadedVideoStatus(new UploadedVideoStatus("", "", "", "FAILED", message, 0, 0, 0));
                view.showError("Video upload", message);
            });
            return;
        }
        EngineGateway gateway = engineGateway;
        if (gateway == null || !gateway.isConnected()) {
            String message = "The vision engine is offline. Start the Python ingestion engine, then upload the video again.";
            onEdt(() -> {
                view.showUploadedVideoStatus(new UploadedVideoStatus("", "", request.source().getFileName().toString(),
                        "FAILED", message, 0, 0, 0));
                view.showError("Video upload", message);
            });
            return;
        }
        if (!gateway.submitUploadedVideo(request)) {
            String message = "The upload command could not be sent. Confirm that the local engine is still connected.";
            onEdt(() -> {
                view.showUploadedVideoStatus(new UploadedVideoStatus("", "", request.source().getFileName().toString(),
                        "FAILED", message, 0, 0, 0));
                view.showError("Video upload", message);
            });
            return;
        }
        onEdt(() -> {
            view.showUploadedVideoStatus(new UploadedVideoStatus("", "", request.source().getFileName().toString(),
                    "SUBMITTED", "Waiting for the local engine to accept the analysis job.", 0, 0, 0));
            view.showInfo("Sent " + request.source().getFileName()
                    + " for offline analysis. The Video analysis tab will show progress and outcome.");
        });
    }

    private static String formatArchiveExportSummary(ArchiveExportSummary summary) {
        return "Exported " + summary.recordsExported() + " report(s), "
                + summary.snapshotsExported() + " image(s), and " + summary.videosExported()
                + " video(s) to " + summary.destination().getFileName() + ".";
    }

    private String existingNoteOr(long incidentId, String fallback) {
        Incident incident = incidentCache.get(incidentId);
        return incident == null || incident.operatorNotes().isBlank() ? fallback : incident.operatorNotes();
    }

    private IncidentRepository repositoryFor(ArchiveScope scope) {
        return scope == ArchiveScope.UPLOADED ? uploadedIncidents : incidents;
    }

    private MediaService mediaServiceFor(ArchiveScope scope) {
        return scope == ArchiveScope.UPLOADED ? uploadedMediaService : mediaService;
    }

    private Map<Long, Incident> cacheFor(ArchiveScope scope) {
        return scope == ArchiveScope.UPLOADED ? uploadedIncidentCache : incidentCache;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null || cause.getMessage() == null ? "Media cleanup failed." : cause.getMessage();
    }

    private record DeleteOutcome(
            List<Long> deletedIds, List<String> mediaFailures, MediaDeletionOptions options) {
    }

    private void refreshVisibleAlert() {
        incidentCache.values().stream()
                .filter(incident -> incident.status() == IncidentStatus.PENDING)
                .max(Comparator.comparingLong(Incident::id))
                .ifPresentOrElse(view::showAlert, view::clearAlert);
    }

    private void pushSavedSettingsIfConnected() {
        EngineGateway gateway;
        EngineSettings savedSettings;
        synchronized (settingsLock) {
            if (!settingsLoaded) {
                return;
            }
            gateway = engineGateway;
            savedSettings = settings;
        }
        if (gateway != null && gateway.isConnected()) {
            gateway.sendSettings(savedSettings);
        }
    }

    private void showFailure(String action, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        onEdt(() -> view.showError(action, message));
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
