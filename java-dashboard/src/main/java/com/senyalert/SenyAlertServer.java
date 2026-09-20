package com.senyalert;

import com.senyalert.controller.DashboardController;
import com.senyalert.infrastructure.EngineWebSocketServer;

/** @deprecated Use EngineWebSocketServer from the infrastructure package. */
@Deprecated
public final class SenyAlertServer extends EngineWebSocketServer {
    public SenyAlertServer(int port, DashboardController controller) {
        super(port, controller);
    }
}
