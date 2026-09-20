package com.senyalert.model;

/** The operator-facing urgency chosen from the configured triage policy. */
public enum AlertMode {
    QUIET("Quiet watch"),
    AUDIBLE("Audible alarm");

    private final String displayName;

    AlertMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static AlertMode fromDatabase(String value) {
        try {
            return value == null ? AUDIBLE : AlertMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AUDIBLE;
        }
    }
}
