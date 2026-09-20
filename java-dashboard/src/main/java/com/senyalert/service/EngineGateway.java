package com.senyalert.service;

import com.senyalert.model.EngineSettings;

/** Controller-facing command boundary for the Python vision engine. */
public interface EngineGateway {
    boolean sendSettings(EngineSettings settings);

    boolean setPaused(boolean paused);

    boolean isConnected();
}
