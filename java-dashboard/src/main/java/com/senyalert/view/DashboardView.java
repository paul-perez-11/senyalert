package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.CameraPreviewFrame;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import java.util.List;

/** Presentation boundary: implementations never call JDBC, WebSocket, or file APIs. */
public interface DashboardView {
    void setActions(DashboardActions actions);

    void showSettings(EngineSettings settings);

    void showIncidents(List<Incident> incidents);

    void upsertIncident(Incident incident);

    void removeIncident(long incidentId);

    void showOperatorRecordUpdated(Incident incident);

    void showIncidentEvidence(IncidentEvidence evidence);

    /** Shows evidence in the archive modal and deliberately leaves the current tab unchanged. */
    void showArchiveIncidentEvidence(IncidentEvidence evidence);

    void showAlert(Incident incident);

    void clearAlert();

    void showEngineStatus(EngineStatus status);

    void showCameraPreview(CameraPreviewFrame preview);

    void showInfo(String message);

    void showError(String title, String message);
}
