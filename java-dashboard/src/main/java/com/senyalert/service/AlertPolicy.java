package com.senyalert.service;

import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;

/** Keeps triage policy out of the WebSocket layer and the Swing view. */
public final class AlertPolicy {
    public AlertMode decide(DistressEvent event, EngineSettings settings) {
        // The Python engine evaluates the same dashboard policy for each
        // camera.  Its QUIET decision is a conservative one-way safety hint:
        // honor it if a reconnect or a save/ack race leaves this dashboard
        // momentarily behind, but never let an engine LOUD hint bypass the
        // locally configured threshold or audible-alarm switch.
        if (event.engineRequestedQuiet()) {
            return AlertMode.QUIET;
        }
        boolean crowdRequiresQuietWatch = settings.peopleDetectionEnabled()
                && event.peopleCount() >= settings.quietAtOrAbovePeople();
        boolean occupancyIsStale = event.peopleCountStale()
                || "STALE".equalsIgnoreCase(event.occupancyStatus());

        // Do not turn an alert audible when occupancy data is stale: alert quietly until a fresh count arrives.
        if (crowdRequiresQuietWatch || occupancyIsStale || !settings.audibleAlertsEnabled()) {
            return AlertMode.QUIET;
        }
        return AlertMode.AUDIBLE;
    }
}
