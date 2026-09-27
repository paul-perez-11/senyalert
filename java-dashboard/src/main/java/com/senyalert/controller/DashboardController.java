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
import com.senyalert.security.SecurityContext;
import com.senyalert.security.Permission;
import com.senyalert.service.IncidentReport;
import com.senyalert.service.ConfigurationFiles;
import org.json.JSONObject;
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
    private volatile java.util.function.Supplier<CompletableFuture<Void>> engineStarter =
            () -> CompletableFuture.failedFuture(new IllegalStateException("The dashboard-owned engine launcher is unavailable."));

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

    private SecurityContext security;

    public void attachSecurity(SecurityContext security) { this.security = java.util.Objects.requireNonNull(security); }

    @Override
    public boolean can(Permission permission) { return security != null && security.can(permission); }

    private boolean begin(Permission permission, String action, String target, String details) {
        try {
            if (security == null) throw new SecurityException("Sign in before using this action.");
            security.beginAction(permission, action, target, details);
            return true;
        } catch (RuntimeException denied) {
            onEdt(() -> view.showError("Action unavailable", denied.getMessage()));
            return false;
        }
    }

    /** Reading saved evidence is permitted at the service boundary but is not itself an audit event. */
    private boolean permitView(Permission permission) {
        if (security != null && security.can(permission)) return true;
        onEdt(() -> view.showError("Action unavailable", "Access denied: " + permission.description() + ". Sign in again if your permissions changed."));
        return false;
    }

    private void audit(String action, String target, String details) {
        if (security != null) security.audit(action, target, details);
    }

    private boolean permitUpdate(ArchiveScope scope, long id, OperatorIncidentUpdate update) {
        Incident before = cacheFor(scope).get(id);
        if (before == null || update == null) {
            onEdt(() -> view.showError("Update record", "Refresh and select an existing incident first."));
            return false;
        }
        if (!before.operatorNotes().equals(update.operatorNotes()) && !can(Permission.EDIT_NOTES)) {
            return begin(Permission.EDIT_NOTES, "INCIDENT_NOTE", "incident:" + id, "Permission check");
        }
        if (before.status() != update.status()) {
            Permission required = update.status() == IncidentStatus.ACKNOWLEDGED ? Permission.ACKNOWLEDGE_INCIDENTS
                    : Permission.RESOLVE_INCIDENTS;
            if (!can(required)) return begin(required, "INCIDENT_STATUS", "incident:" + id, "Permission check");
        }
        Permission required = before.status() != update.status()
                ? (update.status() == IncidentStatus.ACKNOWLEDGED ? Permission.ACKNOWLEDGE_INCIDENTS : Permission.RESOLVE_INCIDENTS)
                : Permission.EDIT_NOTES;
        return begin(required, "INCIDENT_UPDATE", scope + ":" + id,
                new JSONObject().put("before", new JSONObject().put("status", before.status()).put("note", before.operatorNotes()))
                        .put("after", new JSONObject().put("status", update.status()).put("note", update.operatorNotes())).toString());
    }

    @Override
    public void copyRecordData(ArchiveScope scope, long id) {
        if (!begin(Permission.EXPORT_EVIDENCE, "RECORD_COPY", scope + ":" + id, "Copy saved record to clipboard")) return;
        repositoryFor(scope).findEvidence(id).thenApplyAsync(found -> IncidentReport.text(found.orElseThrow()))
                .whenComplete((record, failure) -> {
                    if (failure != null) { showFailure("Copy record", failure); return; }
                    onEdt(() -> {
                        try {
                            security.require(Permission.EXPORT_EVIDENCE);
                            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(record), null);
                            audit("RECORD_COPY_COMPLETED", scope + ":" + id, "Saved record copied as plain text");
                            view.showInfo("Record data copied to clipboard.");
                        } catch (Exception unavailable) { showFailure("Copy record", unavailable); }
                    });
                });
    }

    @Override
    public void copySnapshot(ArchiveScope scope, long id) {
        if (!begin(Permission.EXPORT_EVIDENCE, "SNAPSHOT_COPY", scope + ":" + id, "Copy original snapshot to clipboard")) return;
        repositoryFor(scope).findEvidence(id).thenApplyAsync(found -> {
            var e = found.orElseThrow();
            try {
                java.awt.image.BufferedImage image = e.snapshotBytes() != null && e.snapshotBytes().length > 0
                        ? javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(e.snapshotBytes()))
                        : javax.imageio.ImageIO.read(Path.of(e.incident().snapshotPath()).toFile());
                if (image == null) throw new IllegalStateException("Snapshot is unavailable or invalid.");
                return image;
            } catch (java.io.IOException failure) { throw new IllegalStateException("Could not read snapshot", failure); }
        }).whenComplete((image, failure) -> {
            if (failure != null) { showFailure("Copy snapshot", failure); return; }
            onEdt(() -> {
                try {
                    security.require(Permission.EXPORT_EVIDENCE);
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.Transferable() {
                        public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor}; }
                        public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f); }
                        public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
                            if (!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f); return image;
                        }
                    }, null);
                    audit("SNAPSHOT_COPY_COMPLETED", scope + ":" + id, "Original image copied to clipboard");
                    view.showInfo("Snapshot copied to clipboard.");
                } catch (Exception unavailable) { showFailure("Copy snapshot", unavailable); }
            });
        });
    }

    @Override
    public void exportConfiguration(Path destination, boolean camerasOnly) {
        if (!begin(Permission.EXPORT_CONFIG, "CONFIG_EXPORT", destination.toString(), "camerasOnly=" + camerasOnly)) return;
        CompletableFuture.runAsync(() -> ConfigurationFiles.exportFile(settings, destination, camerasOnly))
                .whenComplete((unused, failure) -> {
                    if (failure != null) showFailure("Export configuration", failure);
                    else { audit("CONFIG_EXPORT_COMPLETED", destination.toString(), "camerasOnly=" + camerasOnly); onEdt(() -> view.showInfo("Configuration exported.")); }
                });
    }

    @Override
    public void importConfiguration(Path source, boolean camerasOnly) {
        if (!begin(camerasOnly ? Permission.CONFIGURE_CAMERAS : Permission.CONFIGURE_ENGINE,
                "CONFIG_IMPORT", source.toString(), "camerasOnly=" + camerasOnly)) return;
        CompletableFuture.supplyAsync(() -> ConfigurationFiles.importFile(settings, source, camerasOnly))
                .whenComplete((loaded, failure) -> { if (failure != null) showFailure("Import configuration", failure); else saveSettings(loaded); });
    }

    /** Used by authenticated support after its own audit and permission checks. */
    public CompletableFuture<EngineSettings> applyRemoteSettings(EngineSettings newSettings) {
        return persistSettings(newSettings);
    }

    public EngineSettings currentSettings() { return settings; }

    public void attachEngineGateway(EngineGateway gateway) {
        this.engineGateway = gateway;
        pushSavedSettingsIfConnected();
    }

    /** Installed by the application runtime because it owns the child process lifecycle. */
    public void attachEngineStarter(java.util.function.Supplier<CompletableFuture<Void>> starter) {
        this.engineStarter = starter == null
                ? () -> CompletableFuture.failedFuture(new IllegalStateException("The dashboard-owned engine launcher is unavailable."))
                : starter;
    }

    @Override
    public CompletableFuture<String> connectAndroidIpCamera(AndroidIpCameraRequest request) {
        if (!begin(Permission.CONFIGURE_CAMERAS, "CAMERA_ADB", "camera", "Connect and forward configured phone camera")) return CompletableFuture.failedFuture(new SecurityException("Permission denied"));
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter an ADB connection and valid ports first."));
        }
        return cameraControlService.connectAndForward(request).whenComplete((message, failure) -> {
            if (failure != null) {
                showFailure("Phone camera ADB", failure);
            } else {
                audit("CAMERA_CONTROL_COMPLETED", "camera", message);
                onEdt(() -> view.showInfo(message));
            }
        });
    }

    @Override
    public CompletableFuture<String> applyIpCameraZoom(IpCameraZoomRequest request) {
        if (!begin(Permission.CONFIGURE_CAMERAS, "CAMERA_ZOOM", "camera", String.valueOf(request))) return CompletableFuture.failedFuture(new SecurityException("Permission denied"));
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter an HTTP camera source and zoom value first."));
        }
        return cameraControlService.applyZoom(request).whenComplete((message, failure) -> {
            if (failure != null) {
                showFailure("Phone camera zoom", failure);
            } else {
                audit("CAMERA_CONTROL_COMPLETED", "camera", message);
                onEdt(() -> view.showInfo(message));
            }
        });
    }

    public CompletableFuture<EngineSettings> initialize() {
        CompletableFuture<EngineSettings> loadedSettings = settingsStore.load();
        loadedSettings.whenComplete((loaded, failure) -> {
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
        return loadedSettings;
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
        if (!permitView(Permission.VIEW_INCIDENTS)) return;
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
                IncidentStatus.ACKNOWLEDGED, existingNoteOr(incidentId, "")));
    }

    @Override
    public void resolveIncident(long incidentId) {
        updateOperatorRecord(incidentId, new OperatorIncidentUpdate(
                IncidentStatus.RESOLVED, existingNoteOr(incidentId, "")));
    }

    @Override
    public void updateOperatorRecord(long incidentId, OperatorIncidentUpdate update) {
        updateOperatorRecord(ArchiveScope.RECORDED, incidentId, update);
    }

    @Override
    public void updateOperatorRecord(ArchiveScope scope, long incidentId, OperatorIncidentUpdate update) {
        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        IncidentRepository repository = repositoryFor(safeScope);
        if (!permitUpdate(safeScope, incidentId, update)) return;
        repository.updateOperatorRecord(incidentId, update).whenComplete((updated, failure) -> {
            if (failure != null) {
                showFailure("Update operator record", failure);
                return;
            }
            updated.ifPresentOrElse(incident -> {
                audit("INCIDENT_UPDATE_COMPLETED", safeScope + ":" + incident.id(), new JSONObject().put("status", incident.status()).put("note", incident.operatorNotes()).toString());
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
        for (var entry : safeUpdates.entrySet()) if (!permitUpdate(safeScope, entry.getKey(), entry.getValue())) return;
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
            updated.forEach(incident -> {
                audit("INCIDENT_UPDATE_COMPLETED", safeScope + ":" + incident.id(), new JSONObject().put("status", incident.status()).put("note", incident.operatorNotes()).toString());
                cacheFor(safeScope).put(incident.id(), incident);
            });
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
        if (!begin(Permission.DELETE_RECORDS, "INCIDENT_DELETE", safeScope.toString(), "ids=" + ids + "; sourceMedia=" + safeOptions)) return;
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
            audit("INCIDENT_DELETE_COMPLETED", safeScope.toString(), "deletedIds=" + outcome.deletedIds() + "; mediaFailures=" + outcome.mediaFailures());
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
    public CompletableFuture<Boolean> resetNextIncidentId(ArchiveScope scope) {
        ArchiveScope safeScope = scope == null ? ArchiveScope.RECORDED : scope;
        if (!begin(Permission.DELETE_RECORDS, "ARCHIVE_RESET_ID", safeScope.toString(), "Reset only if archive is empty")) return CompletableFuture.failedFuture(new SecurityException("Permission denied"));
        return repositoryFor(safeScope).resetNextIncidentIdIfEmpty().whenComplete((reset, failure) -> {
            if (failure != null) {
                showFailure("Reset next incident ID", failure);
                return;
            }
            audit("ARCHIVE_RESET_ID_COMPLETED", safeScope.toString(), "reset=" + reset);
            if (!Boolean.TRUE.equals(reset)) {
                onEdt(() -> view.showInfo("Cannot reset the next "
                        + safeScope.displayName().toLowerCase()
                        + " ID while that archive still contains incident records. "
                        + "No records or media were changed."));
                return;
            }
            if (safeScope == ArchiveScope.UPLOADED) {
                refreshUploadedIncidents();
            } else {
                refreshIncidents();
            }
            onEdt(() -> view.showInfo("The next " + safeScope.displayName().toLowerCase()
                    + " incident ID will be 1. No records or media were changed."));
        });
    }

    @Override
    public void saveSettings(EngineSettings newSettings) {
        if (newSettings == null) return;
        JSONObject before = settings.toPersistedJson(), after = newSettings.toPersistedJson();
        if (!settings.cameras().equals(newSettings.cameras()) && !begin(Permission.CONFIGURE_CAMERAS,
                "CAMERA_SETTINGS_UPDATE", "configuration", ConfigurationFiles.diff(before, after))) return;
        if (!ConfigurationFiles.engineOnly(before).similar(ConfigurationFiles.engineOnly(after)) && !begin(Permission.CONFIGURE_ENGINE,
                "ENGINE_SETTINGS_UPDATE", "configuration", ConfigurationFiles.diff(before, after))) return;
        if (before.similar(after)) { onEdt(() -> view.showInfo("Settings already match the saved configuration.")); return; }
        persistSettings(newSettings).whenComplete((saved, failure) -> {
            if (failure != null) showFailure("Save settings", failure);
            else audit("SETTINGS_UPDATE_COMPLETED", "configuration", ConfigurationFiles.diff(before, saved.toPersistedJson()));
        });
    }

    private CompletableFuture<EngineSettings> persistSettings(EngineSettings newSettings) {
        return settingsStore.save(newSettings).thenApply(saved -> {
            settings = saved;
            settingsLoaded = true;
            EngineGateway gateway = engineGateway;
            boolean delivered = gateway != null && gateway.sendSettings(saved);
            onEdt(() -> {
                view.showSettings(saved);
                view.showInfo(delivered ? "Settings saved and sent to the vision engine."
                        : "Settings saved locally. Connect the vision engine to apply them live.");
            });
            return saved;
        });
    }

    @Override
    public void toggleEnginePause() {
        if (!begin(Permission.CONFIGURE_ENGINE, "ENGINE_PAUSE", "engine", "requestedPaused=" + !enginePaused)) return;
        EngineGateway gateway = engineGateway;
        boolean requestedState = !enginePaused;
        if (gateway == null || !gateway.setPaused(requestedState)) {
            onEdt(() -> view.showInfo("The vision engine is not connected."));
            return;
        }
        audit("ENGINE_PAUSE_COMMAND_SENT", "engine", "paused=" + requestedState);
        enginePaused = requestedState;
        onEdt(() -> view.showEngineStatus(new EngineStatus(true, requestedState,
                requestedState ? "Pause command sent to engine" : "Resume command sent to engine", "")));
    }

    @Override
    public void startEngine() {
        if (!begin(Permission.CONFIGURE_ENGINE, "ENGINE_START", "ingestion-engine", "Manual start requested")) return;
        java.util.function.Supplier<CompletableFuture<Void>> starter = engineStarter;
        CompletableFuture<Void> launched;
        try {
            launched = starter.get();
        } catch (RuntimeException failure) {
            showFailure("Start engine", failure);
            return;
        }
        launched.whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Start engine", failure);
                return;
            }
            audit("ENGINE_PROCESS_START_REQUESTED", "ingestion-engine", "Manual start request completed.");
            onEdt(() -> view.showInfo("Vision engine start requested."));
        });
    }

    @Override
    public void setStartEngineOnStartup(boolean enabled) {
        EngineSettings current = settings;
        if (current.startEngineOnStartup() == enabled) return;
        if (!begin(Permission.CONFIGURE_ENGINE, "ENGINE_AUTOSTART_UPDATE", "ingestion-engine",
                "startEngineOnStartup=" + enabled)) {
            onEdt(() -> view.showSettings(current));
            return;
        }
        EngineSettings updated = current.withStartEngineOnStartup(enabled);
        persistSettings(updated).whenComplete((saved, failure) -> {
            if (failure != null) {
                onEdt(() -> view.showSettings(current));
                showFailure("Start engine on startup", failure);
                return;
            }
            audit("ENGINE_AUTOSTART_UPDATE_COMPLETED", "ingestion-engine",
                    "startEngineOnStartup=" + saved.startEngineOnStartup());
        });
    }

    @Override
    public void restartEngine() {
        if (!begin(Permission.CONFIGURE_ENGINE, "ENGINE_RESTART", "engine", "Reconnect camera workers")) return;
        EngineGateway gateway = engineGateway;
        if (gateway == null || !gateway.restartEngine()) {
            onEdt(() -> view.showError("Restart engine",
                    "The vision engine is not connected. Start it, then try Restart Engine again."));
            return;
        }
        audit("ENGINE_RESTART_COMMAND_SENT", "engine", "Reconnect camera workers");
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
        if (!permitView(Permission.VIEW_INCIDENTS)) return;
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
        if (!begin(Permission.EXPORT_EVIDENCE, "VIDEO_EXPORT", scope + ":" + incidentId, destination.toString())) return;
        mediaServiceFor(scope).exportVideo(incidentId, destination).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Export video", failure);
                return;
            }
            audit("VIDEO_EXPORT_COMPLETED", scope + ":" + incidentId, destination.toString());
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
        if (!begin(Permission.EXPORT_EVIDENCE, "EVIDENCE_EXPORT", String.valueOf(scope), "ids=" + incidentIds + "; destination=" + destinationDirectory + "; mode=" + mode)) return;
        mediaServiceFor(scope).exportArchive(incidentIds, destinationDirectory, mode).whenComplete((summary, failure) -> {
            if (failure != null) {
                showFailure("Export evidence archive", failure);
                return;
            }
            audit("EVIDENCE_EXPORT_COMPLETED", String.valueOf(scope), formatArchiveExportSummary(summary));
            onEdt(() -> view.showInfo(formatArchiveExportSummary(summary)));
        });
    }

    @Override
    public void submitUploadedVideo(UploadedVideoRequest request) {
        onEdt(() -> view.showInfo("Video analysis has been retired. Use Benchmarking for evaluation runs."));
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
        try { audit("ACTION_FAILED", action, message); } catch (RuntimeException ignored) { /* Report the original error. */ }
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
