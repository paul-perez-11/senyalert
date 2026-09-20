package com.senyalert.controller;

import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.OperatorIncidentUpdate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** User intents emitted by the Swing view. */
public interface DashboardActions {
    void refreshIncidents();

    void selectIncident(long incidentId);

    /** Loads incident evidence into the archive's modal viewer without changing dashboard tabs. */
    void selectIncidentForArchive(long incidentId);

    void acknowledgeIncident(long incidentId);

    void resolveIncident(long incidentId);

    void updateOperatorRecord(long incidentId, OperatorIncidentUpdate update);

    /** Applies allow-listed status/note changes to the explicitly supplied archive records. */
    void updateOperatorRecords(Map<Long, OperatorIncidentUpdate> updates);

    void deleteIncidentRecord(long incidentId);

    /** Removes only the selected SQLite rows/BLOBs; source files are never touched. */
    void deleteIncidentRecords(List<Long> incidentIds);

    void saveSettings(EngineSettings settings);

    void toggleEnginePause();

    void playVideoNatively(long incidentId);

    void exportVideo(long incidentId, Path destination);

    /** Writes a non-destructive archive bundle into a user-selected directory. */
    void exportArchive(List<Long> incidentIds, Path destinationDirectory, ArchiveExportMode mode);
}
