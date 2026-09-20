package com.senyalert.model;

/** Summary data used by the incident table. Binary evidence is loaded only on selection. */
public record Incident(
        long id,
        String eventToken,
        String cameraId,
        String location,
        String detectionTimestamp,
        long eventTimestampEpochSeconds,
        double confidence,
        String triageContext,
        AlertMode alertMode,
        IncidentStatus status,
        String operatorNotes,
        int peopleCount,
        boolean peopleCountStale,
        String occupancyStatus,
        int handCount,
        int signalerCount,
        String signalerTrackId,
        String signalerBounds,
        String snapshotPath,
        String videoPath,
        boolean mediaReady,
        double videoDurationSeconds,
        String mediaStatus) {
}
