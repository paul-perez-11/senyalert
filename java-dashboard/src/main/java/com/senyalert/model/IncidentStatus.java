package com.senyalert.model;

public enum IncidentStatus {
    PENDING,
    ACKNOWLEDGED,
    RESOLVED;

    public static IncidentStatus fromDatabase(String value) {
        try {
            return value == null ? PENDING : IncidentStatus.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return PENDING;
        }
    }
}
