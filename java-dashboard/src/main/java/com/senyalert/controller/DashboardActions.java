package com.senyalert.controller;

import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.ArchiveScope;
import com.senyalert.model.AndroidIpCameraRequest;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.IpCameraZoomRequest;
import com.senyalert.model.MediaDeletionOptions;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.model.UploadedVideoRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** User intents emitted by the Swing view. */
public interface DashboardActions {
    void refreshIncidents();

    void selectIncident(long incidentId);

    /** Loads incident evidence into the archive's modal viewer without changing dashboard tabs. */
    void selectIncidentForArchive(long incidentId);

    default void selectIncidentForArchive(ArchiveScope scope, long incidentId) {
        selectIncidentForArchive(incidentId);
    }

    void acknowledgeIncident(long incidentId);

    void resolveIncident(long incidentId);

    void updateOperatorRecord(long incidentId, OperatorIncidentUpdate update);

    default void updateOperatorRecord(ArchiveScope scope, long incidentId, OperatorIncidentUpdate update) {
        updateOperatorRecord(incidentId, update);
    }

    /** Applies allow-listed status/note changes to the explicitly supplied archive records. */
    void updateOperatorRecords(Map<Long, OperatorIncidentUpdate> updates);

    default void updateOperatorRecords(ArchiveScope scope, Map<Long, OperatorIncidentUpdate> updates) {
        updateOperatorRecords(updates);
    }

    void deleteIncidentRecord(long incidentId);

    /** Removes only the selected SQLite rows/BLOBs; source files are never touched. */
    void deleteIncidentRecords(List<Long> incidentIds);

    /** Deletes database records and optionally the exact selected source media paths. */
    default void deleteIncidentRecords(
            ArchiveScope scope, List<Long> incidentIds, MediaDeletionOptions options) {
        deleteIncidentRecords(incidentIds);
    }

    /**
     * Resets the next incident ID for an empty archive only. No records or
     * source-media files are deleted as part of this action.
     */
    default CompletableFuture<Boolean> resetNextIncidentId(ArchiveScope scope) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Archive ID reset is not available in this dashboard session."));
    }

    void saveSettings(EngineSettings settings);

    void toggleEnginePause();

    /** Safely reconnects live camera workers without changing saved settings or evidence. */
    default void restartEngine() {
    }

    void playVideoNatively(long incidentId);

    default void playVideoNatively(ArchiveScope scope, long incidentId) {
        playVideoNatively(incidentId);
    }

    void exportVideo(long incidentId, Path destination);

    default void exportVideo(ArchiveScope scope, long incidentId, Path destination) {
        exportVideo(incidentId, destination);
    }

    /** Writes a non-destructive archive bundle into a user-selected directory. */
    void exportArchive(List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode);

    default void exportArchive(
            ArchiveScope scope, List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode) {
        exportArchive(incidentIds, destinationDirectory, mode);
    }

    /** Queues a local video for offline analysis by the connected Python engine. */
    default void submitUploadedVideo(UploadedVideoRequest request) {
    }

    /** Creates a localhost Android IP Camera tunnel without blocking Swing's EDT. */
    default CompletableFuture<String> connectAndroidIpCamera(AndroidIpCameraRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Phone-camera ADB controls are not available in this dashboard session."));
    }

    /** Sends a validated remote zoom request without blocking Swing's EDT. */
    default CompletableFuture<String> applyIpCameraZoom(IpCameraZoomRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Phone-camera zoom controls are not available in this dashboard session."));
    }
}
