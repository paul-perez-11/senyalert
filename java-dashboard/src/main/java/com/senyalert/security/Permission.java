package com.senyalert.security;

/** Each grant is enforced again at the action boundary, beyond disabled controls. */
public enum Permission {
    VIEW_INCIDENTS("View and select incidents"),
    ACKNOWLEDGE_INCIDENTS("Acknowledge incidents"),
    RESOLVE_INCIDENTS("Resolve incidents"),
    EDIT_NOTES("Edit incident notes"),
    DELETE_RECORDS("Delete incident records / reset an empty archive ID"),
    EXPORT_EVIDENCE("Export media and copy evidence or record data"),
    CONFIGURE_CAMERAS("Add, edit, remove, and control cameras"),
    CONFIGURE_ENGINE("Change engine settings"),
    MANAGE_USERS("Manage local admin/user accounts and permissions"),
    BENCHMARK("Run isolated benchmarks and export results"),
    REMOTE_SUPPORT("Start, copy, and stop a remote support session"),
    VIEW_AUDIT("Read and export audit history"),
    RESET_AUDIT_LOGS("Reset the active audit log for a supervised demo after a protected CSV archive"),
    EXPORT_CONFIG("Export camera and engine configuration"),
    CONFIGURE_EXPORT_SETTINGS("Change default export folders");

    private final String description;
    Permission(String description) { this.description = description; }
    public String description() { return description; }
}
