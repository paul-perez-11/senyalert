package com.senyalert.model;

/** Detailed incident evidence, requested only for the selected row. */
public record IncidentEvidence(
        Incident incident,
        byte[] snapshotBytes,
        String snapshotMimeType,
        byte[] videoBytes,
        String videoMimeType) {
}
