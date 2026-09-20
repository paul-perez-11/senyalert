package com.senyalert;

import com.senyalert.controller.DashboardController;
import com.senyalert.infrastructure.EngineWebSocketServer;
import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.service.AlertPolicy;
import com.senyalert.service.AppExecutors;
import com.senyalert.service.CameraPreviewService;
import com.senyalert.service.MediaService;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.DashboardFrame;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Application composition root. Views are created on the EDT; storage starts off it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppExecutors executors = new AppExecutors();
        Path dashboardDirectory = Path.of("").toAbsolutePath();
        SqliteIncidentRepository repository = new SqliteIncidentRepository(dashboardDirectory.resolve("incidents.db"), executors);
        SettingsStore settingsStore = new SettingsStore(dashboardDirectory.resolve("senyalert-config.json"), executors);
        MediaService mediaService = new MediaService(repository, executors, dashboardDirectory.resolve("evidence-cache"));

        repository.initialize().whenComplete((unused, failure) -> {
            if (failure != null) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "SenyAlert could not open its SQLite incident store:\n" + failure.getMessage(),
                        "Startup failed",
                        JOptionPane.ERROR_MESSAGE));
                executors.close();
                return;
            }

            SwingUtilities.invokeLater(() -> launchDashboard(repository, settingsStore, mediaService, executors));
        });
    }

    private static void launchDashboard(
            SqliteIncidentRepository repository,
            SettingsStore settingsStore,
            MediaService mediaService,
            AppExecutors executors) {
        DashboardFrame dashboard = new DashboardFrame();
        CameraPreviewService cameraPreviewService = new CameraPreviewService(executors);
        DashboardController controller = new DashboardController(
                dashboard, repository, settingsStore, new AlertPolicy(), mediaService, cameraPreviewService);
        dashboard.setActions(controller);

        EngineWebSocketServer server = new EngineWebSocketServer(8080, controller);
        controller.attachEngineGateway(server);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stopGracefully();
            repository.close();
            executors.close();
        }, "senyalert-shutdown"));

        dashboard.setVisible(true);
        server.start();
        controller.initialize();
    }
}
