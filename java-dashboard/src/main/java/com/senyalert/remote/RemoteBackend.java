package com.senyalert.remote;

import org.json.JSONObject;

/** The application owns persistence, validation, permission checks and engine reloads. */
public interface RemoteBackend {
    /** Rejects a support session before any localhost endpoint or tunnel is created. */
    default void validateReady() throws Exception { }
    JSONObject snapshot(String remoteSessionId) throws Exception;
    JSONObject execute(String action, JSONObject payload, String remoteSessionId) throws Exception;
    void audit(String action, JSONObject details);
}
