package com.senyalert.remote;

import org.json.JSONObject;

/** The application owns persistence, validation, permission checks and engine reloads. */
public interface RemoteBackend {
    /** Rejects a support session before any localhost endpoint or tunnel is created. */
    default void validateReady() throws Exception { }

    /**
     * Returns the configured installation ID only for a deliberate, audited
     * local clipboard copy. It must never be displayed in the client panel.
     */
    default String supportClientId() throws Exception {
        throw new UnsupportedOperationException("The client installation ID is unavailable.");
    }

    JSONObject snapshot(String remoteSessionId) throws Exception;
    JSONObject execute(String action, JSONObject payload, String remoteSessionId) throws Exception;
    void audit(String action, JSONObject details);
}
