package com.senyalert.model;

/** Identifies the intentionally separate incident stores shown in the archive. */
public enum ArchiveScope {
    RECORDED("Recorded evidence"),
    UPLOADED("Uploaded evidence");

    private final String displayName;

    ArchiveScope(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
