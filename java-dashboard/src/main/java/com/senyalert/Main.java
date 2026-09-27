package com.senyalert;

import com.senyalert.controller.DashboardController;
import com.senyalert.infrastructure.EngineWebSocketServer;
import com.senyalert.remote.RemoteSupportPanel;
import com.senyalert.remote.SuperadminDashboard;
import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.security.AuditLogPanel;
import com.senyalert.security.AccountSettingsPanel;
import com.senyalert.security.ExportSettingsPanel;
import com.senyalert.security.LoginDialog;
import com.senyalert.security.Permission;
import com.senyalert.security.Role;
import com.senyalert.security.SecurityContext;
import com.senyalert.security.SecurityService;
import com.senyalert.security.UserManagementPanel;
import com.senyalert.service.AlertPolicy;
import com.senyalert.service.AlertSoundService;
import com.senyalert.service.AppExecutors;
import com.senyalert.service.BenchmarkService;
import com.senyalert.service.CameraControlService;
import com.senyalert.service.CameraPreviewService;
import com.senyalert.service.DashboardRemoteBackend;
import com.senyalert.service.ExportDefaults;
import com.senyalert.service.MediaService;
import com.senyalert.service.ManagedEngineProcess;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.BenchmarkPanel;
import com.senyalert.view.DashboardFrame;
import com.senyalert.view.ui.InteractionHints;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import com.senyalert.model.EngineStatus;

/** Application composition root. All access begins with a local signed-in session. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        InteractionHints.install();
        final AppRuntime runtime;
        try {
            EnvironmentConfiguration.load();
            EnvironmentConfiguration.booleanValue("SENYALERT_ENGINE_AUTOSTART", true);
            runtime = AppRuntime.create();
        } catch (RuntimeException failure) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "SenyAlert could not read its startup configuration:\n" + rootMessage(failure),
                    "Startup failed", JOptionPane.ERROR_MESSAGE));
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "senyalert-shutdown"));
        runtime.initialize().whenComplete((unused, failure) -> {
            if (failure != null) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null,
                            "SenyAlert could not open its local SQLite data:\n" + rootMessage(failure),
                            "Startup failed", JOptionPane.ERROR_MESSAGE);
                    runtime.close();
                });
                return;
            }
            SwingUtilities.invokeLater(runtime::showLogin);
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** Keeps local stores available while a signed-in user explicitly signs out. */
    private static final class AppRuntime implements AutoCloseable {
        private final Path directory;
        private final AppExecutors executors;
        private final AlertSoundService alertSound;
        private final CameraControlService cameraControl;
        private final SqliteIncidentRepository incidents;
        private final SqliteIncidentRepository uploadedIncidents;
        private final SettingsStore settings;
        private final MediaService media;
        private final MediaService uploadedMedia;
        private final SecurityService security;
        private volatile ActiveSession active;
        private volatile boolean closed;

        private AppRuntime(Path directory, AppExecutors executors, AlertSoundService alertSound,
                CameraControlService cameraControl, SqliteIncidentRepository incidents,
                SqliteIncidentRepository uploadedIncidents, SettingsStore settings, MediaService media,
                MediaService uploadedMedia, SecurityService security) {
            this.directory = directory;
            this.executors = executors;
            this.alertSound = alertSound;
            this.cameraControl = cameraControl;
            this.incidents = incidents;
            this.uploadedIncidents = uploadedIncidents;
            this.settings = settings;
            this.media = media;
            this.uploadedMedia = uploadedMedia;
            this.security = security;
        }

        static AppRuntime create() {
            // Resolve this before constructing repositories so a source-tree
            // launch cannot accidentally initialize a second, empty data set.
            Path directory = AppDataDirectory.resolve();
            AppExecutors executors = new AppExecutors();
            SqliteIncidentRepository incidents = new SqliteIncidentRepository(directory.resolve("incidents.db"), executors);
            SqliteIncidentRepository uploaded = new SqliteIncidentRepository(directory.resolve("uploaded-incidents.db"), executors);
            SettingsStore settings = new SettingsStore(directory.resolve("senyalert-config.json"), executors);
            ExportDefaults.set(settings.defaultExportSettings());
            return new AppRuntime(directory, executors, new AlertSoundService(), new CameraControlService(),
                    incidents, uploaded, settings,
                    new MediaService(incidents, executors, directory.resolve("evidence-cache")),
                    new MediaService(uploaded, executors, directory.resolve("uploaded-evidence-cache")),
                    new SecurityService(directory.resolve("security.db")));
        }

        CompletableFuture<Void> initialize() {
            return CompletableFuture.allOf(incidents.initialize(), uploadedIncidents.initialize(),
                    CompletableFuture.runAsync(security::initialize, executors.database()),
                    settings.loadExportSettings().exceptionally(ignored -> settings.defaultExportSettings())
                            .thenAccept(ExportDefaults::set));
        }

        void showLogin() {
            if (closed) return;
            Optional<com.senyalert.security.Session> signedIn = LoginDialog.login(null, security);
            if (signedIn.isEmpty()) {
                close();
                return;
            }
            SecurityContext context = new SecurityContext(security, signedIn.get());
            if (context.session().role() == Role.SUPERADMIN) launchSuperadmin(context);
            else launchDashboard(context);
        }

        private void launchSuperadmin(SecurityContext context) {
            SuperadminDashboard dashboard = new SuperadminDashboard(directory.resolve("superadmin-dashboard.db"),
                    (action, details) -> context.audit(action, "superadmin-dashboard", details.toString()));
            ActiveSession session = new ActiveSession(context, null, null, null, null, dashboard);
            active = session;
            dashboard.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { finishSession(session); }
            });
            dashboard.setVisible(true);
        }

        private void launchDashboard(SecurityContext context) {
            DashboardFrame dashboard = new DashboardFrame();
            DashboardController controller = new DashboardController(
                    dashboard, incidents, uploadedIncidents, settings, new AlertPolicy(), media, uploadedMedia,
                    new CameraPreviewService(executors), alertSound, cameraControl);
            controller.attachSecurity(context);
            EngineWebSocketServer server = new EngineWebSocketServer(8080, controller);
            controller.attachEngineGateway(server);
            dashboard.setActions(controller);
            ManagedEngineProcess engine = EnvironmentConfiguration.booleanValue("SENYALERT_ENGINE_AUTOSTART", true)
                    ? ManagedEngineProcess.forCurrentInstallation(message ->
                            controller.onEngineStatus(EngineStatus.disconnected(message)))
                    : null;

            BenchmarkPanel benchmark = null;
            RemoteSupportPanel remote = null;
            if (context.can(Permission.BENCHMARK)) {
                benchmark = new BenchmarkPanel(BenchmarkService.findProjectRoot());
                benchmark.setActions(() -> context.can(Permission.BENCHMARK),
                        (action, detail) -> context.audit(action, "benchmark", detail));
                dashboard.addWorkspace("Benchmarking", benchmark,
                        "Run documented, isolated Chapter 4 performance and accuracy trials.");
            }
            if (context.can(Permission.MANAGE_USERS)) {
                dashboard.addSettingsWorkspace("User management", new UserManagementPanel(context),
                        "Create local accounts and choose their access permissions.");
            }
            if (context.can(Permission.VIEW_AUDIT) || context.can(Permission.RESET_AUDIT_LOGS)) {
                dashboard.addWorkspace("Audit logs", new AuditLogPanel(context),
                        "Review, export, or reset the protected local audit history according to your permissions.");
            }
            if (context.can(Permission.REMOTE_SUPPORT)) {
                remote = new RemoteSupportPanel(new DashboardRemoteBackend(controller, incidents, context, directory));
                dashboard.addSettingsWorkspace("Remote support", remote,
                        "Create a time-limited, client-approved developer support connection.");
            }
            dashboard.addSettingsWorkspace("Export settings", new ExportSettingsPanel(context, settings),
                    "Choose default folders for evidence, audit, configuration, and benchmark exports.");

            ActiveSession session = new ActiveSession(context, server, engine, benchmark, remote, dashboard);
            active = session;
            dashboard.addSettingsWorkspace("Account", new AccountSettingsPanel(context, () -> finishSession(session)),
                    "Change only your username or password. Saving signs out so you can use the new credentials.");
            dashboard.applyPermissions(context);
            dashboard.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { finishSession(session); }
            });
            dashboard.setVisible(true);
            server.start();
            controller.initialize();
            if (engine == null) {
                controller.onEngineStatus(EngineStatus.disconnected(
                        "Automatic engine launch is disabled. Start an external local engine when needed."));
            } else {
                // Defer until the visible dashboard has had a complete Swing turn.
                // This keeps the sign-in and dashboard visibly first, before OpenCV opens.
                SwingUtilities.invokeLater(() -> startEngineAfterDashboardVisible(session, controller));
            }
        }

        private void startEngineAfterDashboardVisible(ActiveSession session, DashboardController controller) {
            if (closed || active != session || !session.window().isDisplayable() || session.engine() == null) {
                return;
            }
            session.engine().start().whenComplete((unused, failure) -> {
                if (active != session || closed) {
                    return;
                }
                if (failure != null) {
                    controller.onEngineStatus(EngineStatus.disconnected(
                            "Could not start the local vision engine. Check Python setup and project root."));
                    try {
                        session.context().audit("ENGINE_PROCESS_START_FAILED", "ingestion-engine",
                                "Managed engine did not start after dashboard login.");
                    } catch (RuntimeException ignored) { }
                    return;
                }
                try {
                    session.context().audit("ENGINE_PROCESS_STARTED", "ingestion-engine",
                            "Started only after the signed-in dashboard became visible.");
                } catch (RuntimeException ignored) { }
            });
        }

        private void finishSession(ActiveSession session) {
            if (active != session) return;
            active = null;
            stopOwnedEngine(session);
            if (session.benchmark() != null) session.benchmark().shutdown();
            if (session.remote() != null) session.remote().close();
            if (session.server() != null) session.server().stopGracefully();
            session.context().logout();
            if (session.window().isDisplayable()) session.window().dispose();
            if (!closed) SwingUtilities.invokeLater(this::showLogin);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            ActiveSession session = active;
            active = null;
            if (session != null) {
                stopOwnedEngine(session);
                if (session.benchmark() != null) session.benchmark().shutdown();
                if (session.remote() != null) session.remote().close();
                if (session.server() != null) session.server().stopGracefully();
                session.context().logout();
            }
            incidents.close();
            uploadedIncidents.close();
            alertSound.close();
            cameraControl.close();
            executors.close();
        }

        private void stopOwnedEngine(ActiveSession session) {
            if (session.engine() == null) return;
            boolean running = session.engine().running();
            session.engine().stop();
            if (running) {
                try {
                    session.context().audit("ENGINE_PROCESS_STOPPED", "ingestion-engine",
                            "Stopped the dashboard-owned ingestion engine when the session ended.");
                } catch (RuntimeException ignored) { }
            }
        }

        private record ActiveSession(SecurityContext context, EngineWebSocketServer server, ManagedEngineProcess engine,
                BenchmarkPanel benchmark, RemoteSupportPanel remote, Window window) { }
    }
}
