package com.senyalert.model;

import java.util.Objects;

/**
 * The only dashboard-editable incident fields. Detection, identity, and media
 * evidence are intentionally absent so this type cannot alter them.
 */
public record OperatorIncidentUpdate(IncidentStatus status, String operatorNotes) {
    public static final int MAX_NOTE_CHARACTERS = 2_000;

    public OperatorIncidentUpdate {
        status = Objects.requireNonNull(status, "status");
        operatorNotes = operatorNotes == null ? "" : operatorNotes.strip();
        if (operatorNotes.length() > MAX_NOTE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Operator notes must be " + MAX_NOTE_CHARACTERS + " characters or fewer.");
        }
    }
}
