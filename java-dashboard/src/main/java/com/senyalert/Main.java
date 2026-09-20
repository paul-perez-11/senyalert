package com.senyalert;

import com.senyalert.controller.DashboardController;
import com.senyalert.infrastructure.EngineWebSocketServer;
import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.service.AlertPolicy;
import com.senyalert.service.AlertSoundService;
import com.senyalert.service.AppExecutors;
import com.senyalert.service.CameraPreviewService;
import com.senyalert.service.CameraControlService;
import com.senyalert.service.MediaService;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.DashboardFrame;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Application composition root. Views are created on the EDT; storage starts off it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppExecutors executors = new AppExecutors();
        AlertSoundService alertSoundService = new AlertSoundService();
        CameraControlService cameraControlService = new CameraControlService();
        Path dashboardDirectory = Path.of("").toAbsolutePath();
        SqliteIncidentRepository repository = new SqliteIncidentRepository(dashboardDirectory.resolve("incidents.db"), executors);
        SqliteIncidentRepository uploadedRepository = new SqliteIncidentRepository(
                dashboardDirectory.resolve("uploaded-incidents.db"), executors);
        SettingsStore settingsStore = new SettingsStore(dashboardDirectory.resolve("senyalert-config.json"), executors);
        MediaService mediaService = new MediaService(repository, executors, dashboardDirectory.resolve("evidence-cache"));
        MediaService uploadedMediaService = new MediaService(
                uploadedRepository, executors, dashboardDirectory.resolve("uploaded-evidence-cache"));

        CompletableFuture.allOf(repository.initialize(), uploadedRepository.initialize()).whenComplete((unused, failure) -> {
            if (failure != null) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "SenyAlert could not open its SQLite incident store:\n" + failure.getMessage(),
                        "Startup failed",
                        JOptionPane.ERROR_MESSAGE));
                executors.close();
                alertSoundService.close();
                cameraControlService.close();
                return;
            }

            SwingUtilities.invokeLater(() -> launchDashboard(
                    repository, uploadedRepository, settingsStore, mediaService, uploadedMediaService,
                    alertSoundService, cameraControlService, executors));
        });
    }

    private static void launchDashboard(
            SqliteIncidentRepository repository,
            SqliteIncidentRepository uploadedRepository,
            SettingsStore settingsStore,
            MediaService mediaService,
            MediaService uploadedMediaService,
            AlertSoundService alertSoundService,
            CameraControlService cameraControlService,
            AppExecutors executors) {
        DashboardFrame dashboard = new DashboardFrame();
        CameraPreviewService cameraPreviewService = new CameraPreviewService(executors);
        DashboardController controller = new DashboardController(
                dashboard, repository, uploadedRepository, settingsStore, new AlertPolicy(), mediaService,
                uploadedMediaService, cameraPreviewService,
                alertSoundService, cameraControlService);
        dashboard.setActions(controller);

        EngineWebSocketServer server = new EngineWebSocketServer(8080, controller);
        controller.attachEngineGateway(server);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stopGracefully();
            repository.close();
            uploadedRepository.close();
            alertSoundService.close();
            cameraControlService.close();
            executors.close();
        }, "senyalert-shutdown"));

        dashboard.setVisible(true);
        server.start();
        controller.initialize();
    }
}
