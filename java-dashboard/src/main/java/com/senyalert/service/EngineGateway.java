package com.senyalert.service;

import com.senyalert.model.EngineSettings;
import com.senyalert.model.UploadedVideoRequest;

/** Controller-facing command boundary for the Python vision engine. */
public interface EngineGateway {
    boolean sendSettings(EngineSettings settings);

    boolean setPaused(boolean paused);

    /** Reconnects the engine's live camera workers; it does not erase settings or evidence. */
    default boolean restartEngine() {
        return false;
    }

    /**
     * Starts offline analysis of a local video on the machine running the
     * Python engine. The source path is never treated as a network upload.
     */
    default boolean submitUploadedVideo(UploadedVideoRequest request) {
        return false;
    }

    boolean isConnected();
}
