package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.CameraPreviewFrame;
import com.senyalert.model.ArchiveScope;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.UploadedVideoStatus;
import java.util.List;

/** Presentation boundary: implementations never call JDBC, WebSocket, or file APIs. */
public interface DashboardView {
    void setActions(DashboardActions actions);

    void showSettings(EngineSettings settings);

    void showIncidents(List<Incident> incidents);

    /** Results from offline uploads remain isolated from live-recorded incidents. */
    void showUploadedIncidents(List<Incident> incidents);

    void upsertIncident(Incident incident);

    void upsertUploadedIncident(Incident incident);

    void removeIncident(long incidentId);

    void removeUploadedIncident(long incidentId);

    void showOperatorRecordUpdated(Incident incident);

    void showUploadedOperatorRecordUpdated(Incident incident);

    void showIncidentEvidence(IncidentEvidence evidence);

    /** Shows evidence in the archive modal and deliberately leaves the current tab unchanged. */
    void showArchiveIncidentEvidence(IncidentEvidence evidence);

    void showArchiveIncidentEvidence(ArchiveScope scope, IncidentEvidence evidence);

    void showAlert(Incident incident);

    void clearAlert();

    void showEngineStatus(EngineStatus status);

    void showCameraPreview(CameraPreviewFrame preview);

    /** Shows lifecycle/progress for the current offline video-analysis job. */
    default void showUploadedVideoStatus(UploadedVideoStatus status) {
        // Optional for narrow DashboardView implementations used outside Swing.
    }

    void showInfo(String message);

    void showError(String title, String message);
}
