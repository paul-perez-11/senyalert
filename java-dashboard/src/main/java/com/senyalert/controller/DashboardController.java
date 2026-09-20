package com.senyalert.controller;

import com.senyalert.model.AlertMode;
import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.ArchiveExportSummary;
import com.senyalert.model.CameraPreview;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.repository.IncidentRepository;
import com.senyalert.service.AlertPolicy;
import com.senyalert.service.CameraPreviewService;
import com.senyalert.service.EngineGateway;
import com.senyalert.service.MediaService;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.DashboardView;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final SettingsStore settingsStore;
    private final AlertPolicy alertPolicy;
    private final MediaService mediaService;
    private final CameraPreviewService cameraPreviewService;
    private final Map<Long, Incident> incidentCache = new ConcurrentHashMap<>();
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
        this.view = view;
        this.incidents = incidents;
        this.settingsStore = settingsStore;
        this.alertPolicy = alertPolicy;
        this.mediaService = mediaService;
        this.cameraPreviewService = cameraPreviewService;
    }

    public void attachEngineGateway(EngineGateway gateway) {
        this.engineGateway = gateway;
        pushSavedSettingsIfConnected();
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
            onEdt(() -> {
                view.upsertIncident(incident);
                if (incident.status() == IncidentStatus.PENDING) {
                    view.showAlert(incident);
                }
            });
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
    }

    @Override
    public void selectIncident(long incidentId) {
        loadIncidentEvidence(incidentId, false);
    }

    @Override
    public void selectIncidentForArchive(long incidentId) {
        loadIncidentEvidence(incidentId, true);
    }

    private void loadIncidentEvidence(long incidentId, boolean archiveModal) {
        incidents.findEvidence(incidentId).whenComplete((evidence, failure) -> {
            if (failure != null) {
                showFailure("Incident evidence", failure);
                return;
            }
            evidence.ifPresentOrElse(
                    item -> onEdt(() -> {
                        if (archiveModal) {
                            view.showArchiveIncidentEvidence(item);
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
        incidents.updateOperatorRecord(incidentId, update).whenComplete((updated, failure) -> {
            if (failure != null) {
                showFailure("Update operator record", failure);
                return;
            }
            updated.ifPresentOrElse(incident -> {
                incidentCache.put(incident.id(), incident);
                onEdt(() -> {
                    view.upsertIncident(incident);
                    view.showOperatorRecordUpdated(incident);
                    refreshVisibleAlert();
                    view.showInfo("Operator record saved for incident #" + incident.id() + ".");
                });
            }, () -> onEdt(() -> view.showInfo("That incident is no longer available.")));
        });
    }

    @Override
    public void updateOperatorRecords(Map<Long, OperatorIncidentUpdate> updates) {
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

        List<CompletableFuture<Optional<Incident>>> writes = new ArrayList<>();
        safeUpdates.forEach((incidentId, update) -> writes.add(incidents.updateOperatorRecord(incidentId, update)));
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Bulk operator update", failure);
                refreshIncidents();
                return;
            }
            List<Incident> updated = writes.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .toList();
            updated.forEach(incident -> incidentCache.put(incident.id(), incident));
            onEdt(() -> {
                updated.forEach(incident -> {
                    view.upsertIncident(incident);
                    view.showOperatorRecordUpdated(incident);
                });
                refreshVisibleAlert();
                view.showInfo(updated.size() + " operator record(s) updated. Detection and evidence stayed read-only.");
            });
        });
    }

    @Override
    public void deleteIncidentRecord(long incidentId) {
        incidents.deleteRecord(incidentId).whenComplete((deleted, failure) -> {
            if (failure != null) {
                showFailure("Delete incident record", failure);
                return;
            }
            if (!Boolean.TRUE.equals(deleted)) {
                onEdt(() -> view.showInfo("That incident is no longer available."));
                return;
            }
            incidentCache.remove(incidentId);
            onEdt(() -> {
                view.removeIncident(incidentId);
                refreshVisibleAlert();
                view.showInfo("Incident #" + incidentId
                        + " was removed from SQLite. Source snapshot and video files were not deleted.");
            });
        });
    }

    @Override
    public void deleteIncidentRecords(List<Long> incidentIds) {
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
        List<CompletableFuture<Boolean>> deletes = ids.stream().map(incidents::deleteRecord).toList();
        CompletableFuture.allOf(deletes.toArray(CompletableFuture[]::new)).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Bulk delete incident records", failure);
                refreshIncidents();
                return;
            }
            List<Long> deletedIds = new ArrayList<>();
            for (int index = 0; index < deletes.size(); index++) {
                if (Boolean.TRUE.equals(deletes.get(index).join())) {
                    deletedIds.add(ids.get(index));
                }
            }
            deletedIds.forEach(incidentCache::remove);
            onEdt(() -> {
                deletedIds.forEach(view::removeIncident);
                refreshVisibleAlert();
                view.showInfo(deletedIds.size()
                        + " database record(s) removed. Source snapshot and video files were not deleted.");
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
    public void playVideoNatively(long incidentId) {
        mediaService.openNatively(incidentId).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Native playback", failure);
            }
        });
    }

    @Override
    public void exportVideo(long incidentId, Path destination) {
        mediaService.exportVideo(incidentId, destination).whenComplete((unused, failure) -> {
            if (failure != null) {
                showFailure("Export video", failure);
                return;
            }
            onEdt(() -> view.showInfo("Video exported to " + destination.getFileName()));
        });
    }

    @Override
    public void exportArchive(List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        if (incidentIds == null || incidentIds.isEmpty() || destinationDirectory == null || mode == null) {
            return;
        }
        mediaService.exportArchive(incidentIds, destinationDirectory, mode).whenComplete((summary, failure) -> {
            if (failure != null) {
                showFailure("Export evidence archive", failure);
                return;
            }
            onEdt(() -> view.showInfo(formatArchiveExportSummary(summary)));
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
