package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.ArchiveScope;
import com.senyalert.model.CameraPreviewFrame;
import com.senyalert.model.EngineSettings;
import com.senyalert.model.EngineStatus;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.UploadedVideoStatus;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.EmptyStateTable;
import com.senyalert.view.ui.NativeFileDialogs;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Swing-only presentation shell. All data access and engine commands are delegated to DashboardActions. */
public class DashboardFrame extends JFrame implements DashboardView {
    private final IncidentTableModel incidentModel = new IncidentTableModel();
    private final PagedIncidentTableModel dispatchIncidentModel = new PagedIncidentTableModel(incidentModel);
    private final EmptyStateTable dispatchTable = new EmptyStateTable(
            dispatchIncidentModel,
            "Loading the incident queue",
            "Checking recent events in the local incident store.");
    private final AlertBanner alertBanner = new AlertBanner();
    private final LivePreviewPanel livePreview = new LivePreviewPanel();
    private final DispatchActionPanel dispatchActions = new DispatchActionPanel();
    private final EvidencePanel evidencePanel = new EvidencePanel();
    private final EvidenceArchivePanel evidenceArchivePanel = new EvidenceArchivePanel(ArchiveScope.RECORDED);
    private final SettingsPanel settingsPanel = new SettingsPanel();
    private final CameraManagementPanel cameraManagementPanel = new CameraManagementPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTabbedPane settingsTabs = new JTabbedPane();
    private final JLabel engineStatus = new JLabel("Starting dashboard…");
    private final JLabel cameraStatus = new JLabel("Camera: awaiting engine");
    private final JLabel activeMetric = new JLabel("0");
    private final JLabel infoStrip = new JLabel("Ready");
    private final JPanel infoStripContainer = new JPanel(new BorderLayout(6, 0));
    private final JComboBox<Integer> dispatchPageSize = new JComboBox<>(new Integer[]{5, 10, 20, 50});
    private final JButton dispatchPreviousPage = paginationButton("‹ Previous");
    private final JButton dispatchNextPage = paginationButton("Next ›");
    private final JLabel dispatchPageStatus = new JLabel();
    private final Timer dispatchRefreshTimer = new Timer(15_000, event -> refreshLiveDispatch());
    private JButton cameraExportConfiguration;
    private JButton cameraImportConfiguration;
    private JButton engineExportConfiguration;
    private JButton engineImportConfiguration;
    private DashboardActions actions;
    private long selectedDispatchIncidentId = -1L;
    private boolean synchronizingDispatchSelection;
    private int liveDispatchTabIndex;
    private int camerasTabIndex;
    private int evidenceArchiveTabIndex;
    private int settingsTabIndex;
    private int engineSettingsTabIndex;

    public DashboardFrame() {
        setTitle("SenyAlert | Distress Dispatch & Triage");
        // Closing a signed-in window returns to the local sign-in dialog, where
        // Exit performs the complete application shutdown.
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 640));
        setSize(1220, 720);
        setLocationByPlatform(true);
        configureIncidentTable(dispatchTable, false);
        alertBanner.setSelectionAction(this::selectAlertedIncident);
        evidencePanel.setDispatchWorkflowControlsVisible(false);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BlueTheme.BACKGROUND);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildTabs(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        dispatchIncidentModel.addTableModelListener(event -> refreshDispatchPagination());
        refreshDispatchPagination();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                dispatchRefreshTimer.start();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                dispatchRefreshTimer.stop();
            }
        });
    }

    @Override
    public void setActions(DashboardActions actions) {
        this.actions = actions;
        settingsPanel.setActions(actions::saveSettings, actions::setStartEngineOnStartup,
                actions::startEngine, actions::toggleEnginePause, actions::restartEngine);
        cameraManagementPanel.setSaveAction(actions::saveSettings);
        cameraManagementPanel.setPhoneCameraActions(
                actions::connectAndroidIpCamera,
                actions::applyIpCameraZoom);
        evidencePanel.setActions(
                actions::updateOperatorRecord,
                (incidentId, options) -> actions.deleteIncidentRecords(
                        ArchiveScope.RECORDED, List.of(incidentId), options),
                actions::playVideoNatively,
                actions::exportVideo);
        evidenceArchivePanel.setActions(actions);
        evidencePanel.setPermissions(actions::can);
        dispatchActions.setActions(actions);
        dispatchActions.setPermissions(actions::can);
    }

    @Override
    public void showSettings(EngineSettings settings) {
        settingsPanel.showSettings(settings);
        cameraManagementPanel.showSettings(settings);
    }

    @Override
    public void showIncidents(List<Incident> incidents) {
        dispatchTable.clearSelection();
        incidentModel.replaceAll(incidents);
        dispatchTable.setEmptyState(
                "No incidents waiting",
                "New SOS detections will appear here automatically.");
        evidenceArchivePanel.showIncidents(incidents);
        reconcileDispatchSelection();
        updateActiveMetric();
    }

    @Override
    public void upsertIncident(Incident incident) {
        dispatchTable.clearSelection();
        incidentModel.upsert(incident);
        dispatchTable.setEmptyState(
                "No incidents waiting",
                "New SOS detections will appear here automatically.");
        evidenceArchivePanel.upsertIncident(incident);
        reconcileDispatchSelection();
        updateActiveMetric();
    }

    @Override
    public void showUploadedIncidents(List<Incident> incidents) {
        // Historical uploaded data remains on disk.
    }

    @Override
    public void upsertUploadedIncident(Incident incident) {
        // Offline analysis no longer appears in the operator dashboard.
    }

    @Override
    public void removeIncident(long incidentId) {
        incidentModel.remove(incidentId);
        evidencePanel.clearIfViewing(incidentId);
        if (selectedDispatchIncidentId == incidentId) {
            selectedDispatchIncidentId = -1L;
            dispatchActions.clearSelection();
            dispatchTable.clearSelection();
        }
        evidenceArchivePanel.removeIncident(incidentId);
        updateActiveMetric();
    }

    @Override
    public void removeUploadedIncident(long incidentId) {

    }

    @Override
    public void showOperatorRecordUpdated(Incident incident) {
        evidencePanel.showOperatorRecordUpdated(incident);
        if (dispatchActions.isShowingIncident(incident.id())) {
            dispatchActions.showIncident(incident);
        }
        evidenceArchivePanel.showOperatorRecordUpdated(incident);
    }

    @Override
    public void showUploadedOperatorRecordUpdated(Incident incident) {

    }

    @Override
    public void showIncidentEvidence(IncidentEvidence evidence) {
        evidencePanel.showEvidence(evidence);
        // The dispatch thumbnail is deliberately a still image.  When an
        // operator selects an incident, show that saved snapshot rather than
        // repainting a continuous camera feed.
        livePreview.showIncidentSnapshot(evidence);
    }

    @Override
    public void showArchiveIncidentEvidence(IncidentEvidence evidence) {
        showArchiveIncidentEvidence(ArchiveScope.RECORDED, evidence);
    }

    @Override
    public void showArchiveIncidentEvidence(ArchiveScope scope, IncidentEvidence evidence) {
        if (scope != ArchiveScope.UPLOADED) evidenceArchivePanel.showEvidence(evidence);
    }

    @Override
    public void showAlert(Incident incident) {
        alertBanner.showAlert(incident);
    }

    @Override
    public void clearAlert() {
        alertBanner.clearAlert();
    }

    @Override
    public void showEngineStatus(EngineStatus status) {
        boolean connected = status.connected();
        Color statusColor = connected ? (status.paused() ? BlueTheme.QUIET : BlueTheme.SUCCESS) : BlueTheme.DANGER;
        engineStatus.setText(connected
                ? (status.paused() ? "Engine paused" : "Engine connected")
                : "Engine offline");
        engineStatus.setForeground(statusColor);
        engineStatus.setIcon(FontIcon.of(connected && !status.paused()
                ? FontAwesomeSolid.CHECK : connected ? FontAwesomeSolid.PAUSE : FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                11, statusColor));
        engineStatus.setIconTextGap(6);
        engineStatus.getAccessibleContext().setAccessibleDescription(
                status.connected() ? "Vision engine status: " + engineStatus.getText() : "Vision engine is offline");
        String detail = status.cameraId().isBlank() ? status.message() : "Camera " + status.cameraId() + " · " + status.message();
        cameraStatus.setText(detail == null || detail.isBlank()
                ? (connected ? "Waiting for the first camera update" : "Start the vision engine or check the Cameras tab")
                : detail);
        settingsPanel.setPaused(status.paused());
    }

    @Override
    public void showCameraPreview(CameraPreviewFrame preview) {
        livePreview.showPreview(preview);
    }

    @Override
    public void showUploadedVideoStatus(UploadedVideoStatus status) {

    }

    @Override
    public void showInfo(String message) {
        showStatusMessage(BlueTheme.INFO, BlueTheme.INFO_TINT, FontAwesomeSolid.CHECK, message);
    }

    @Override
    public void showError(String title, String message) {
        String safeTitle = title == null || title.isBlank() ? "Dashboard action" : title;
        String safeMessage = message == null || message.isBlank() ? "An unexpected problem occurred." : message;
        showStatusMessage(BlueTheme.DANGER, BlueTheme.DANGER_TINT, FontAwesomeSolid.EXCLAMATION_TRIANGLE,
                safeTitle + " failed: " + safeMessage);
        JOptionPane.showMessageDialog(
                this,
                safeMessage + "\n\nThe dashboard remains open. Check the engine or storage connection and try again.",
                safeTitle + " failed",
                JOptionPane.ERROR_MESSAGE);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(BlueTheme.NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JLabel brandIcon = new JLabel(FontIcon.of(FontAwesomeSolid.SHIELD_ALT, 24, Color.WHITE));
        JLabel title = new JLabel("SenyAlert");
        title.setFont(BlueTheme.font(Font.BOLD, 21));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("SILENT DISTRESS DISPATCH & TRIAGE");
        subtitle.setFont(BlueTheme.font(Font.BOLD, 10));
        subtitle.setForeground(new Color(164, 205, 244));

        JPanel brand = new JPanel(new BorderLayout(8, 0));
        brand.setOpaque(false);
        brand.add(brandIcon, BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
        text.add(title);
        text.add(subtitle);
        brand.add(text, BorderLayout.CENTER);
        header.add(brand, BorderLayout.WEST);

        engineStatus.setFont(BlueTheme.font(Font.BOLD, 13));
        engineStatus.setForeground(new Color(170, 231, 198));
        cameraStatus.setFont(BlueTheme.font(Font.PLAIN, 12));
        cameraStatus.setForeground(new Color(193, 215, 238));
        JPanel system = new JPanel();
        system.setOpaque(false);
        system.setLayout(new javax.swing.BoxLayout(system, javax.swing.BoxLayout.Y_AXIS));
        engineStatus.setAlignmentX(RIGHT_ALIGNMENT);
        cameraStatus.setAlignmentX(RIGHT_ALIGNMENT);
        system.add(engineStatus);
        system.add(cameraStatus);
        header.add(system, BorderLayout.EAST);
        return header;
    }

    private JTabbedPane buildTabs() {
        tabs.setFont(BlueTheme.font(Font.BOLD, 13));
        tabs.setBackground(BlueTheme.BACKGROUND);
        tabs.setForeground(BlueTheme.TEXT);
        liveDispatchTabIndex = tabs.getTabCount();
        tabs.addTab(" Live dispatch", FontIcon.of(FontAwesomeSolid.BELL, 16, BlueTheme.PRIMARY), buildDispatchTab());
        camerasTabIndex = tabs.getTabCount();
        tabs.addTab(" Cameras", FontIcon.of(FontAwesomeSolid.CAMERA, 16, BlueTheme.PRIMARY), configurationPanel(cameraManagementPanel, true));
        evidenceArchiveTabIndex = tabs.getTabCount();
        tabs.addTab(" Evidence archive", FontIcon.of(FontAwesomeSolid.DATABASE, 16, BlueTheme.PRIMARY), evidenceArchivePanel);
        settingsTabIndex = tabs.getTabCount();
        tabs.addTab(" Settings", FontIcon.of(FontAwesomeSolid.COGS, 16, BlueTheme.PRIMARY), buildSettingsWorkspace());
        tabs.setToolTipTextAt(0, "Triage incidents in the full-width queue and inspect current evidence.");
        tabs.setToolTipTextAt(1, "Manage camera sources and export or import camera settings.");
        tabs.setToolTipTextAt(2, "Review saved incidents, record data, images and video.");
        tabs.setToolTipTextAt(settingsTabIndex, "Configure your account, engine, users, and remote support.");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        return tabs;
    }

    private JTabbedPane buildSettingsWorkspace() {
        settingsTabs.setFont(BlueTheme.font(Font.BOLD, 12));
        settingsTabs.setBackground(BlueTheme.BACKGROUND);
        settingsTabs.setForeground(BlueTheme.TEXT);
        engineSettingsTabIndex = settingsTabs.getTabCount();
        settingsTabs.addTab(" Engine settings", FontIcon.of(FontAwesomeSolid.COG, 15, BlueTheme.PRIMARY),
                configurationPanel(settingsPanel, false));
        settingsTabs.setToolTipTextAt(engineSettingsTabIndex,
                "Configure detection and engine behavior, or export a setup file.");
        settingsTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        return settingsTabs;
    }

    private JPanel buildDispatchTab() {
        JPanel panel = contentPanel();

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(buildDispatchSummary(), BorderLayout.NORTH);

        // Dispatch is table-first.  Evidence stays alongside the active queue
        // so an operator can inspect the saved snapshot and use its media
        // actions without sacrificing the queue's working area.
        evidencePanel.setMinimumSize(new Dimension(310, 330));
        evidencePanel.setPreferredSize(new Dimension(340, 460));
        evidencePanel.setLiveDispatchActions(dispatchActions);
        var queue = incidentTableCard("Live incident queue");
        queue.setMinimumSize(new Dimension(620, 330));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, queue, evidencePanel);
        split.setResizeWeight(0.74);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(8);
        split.setContinuousLayout(true);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.74));
        center.add(split, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    public void addWorkspace(String name, javax.swing.JComponent panel, String hint) {
        int index = settingsTabIndex;
        tabs.insertTab(" " + name, FontIcon.of(workspaceIcon(name), 16, BlueTheme.PRIMARY), panel, hint, index);
        settingsTabIndex++;
    }

    public void addSettingsWorkspace(String name, javax.swing.JComponent panel, String hint) {
        settingsTabs.addTab(" " + name, FontIcon.of(settingsWorkspaceIcon(name), 15, BlueTheme.PRIMARY), panel);
        settingsTabs.setToolTipTextAt(settingsTabs.getTabCount() - 1, hint);
    }

    public void applyPermissions(com.senyalert.security.SecurityContext context) {
        cameraManagementPanel.setPermissions(context::can);
        settingsPanel.setPermissions(context::can);
        evidencePanel.setPermissions(context::can);
        evidenceArchivePanel.setPermissions(context::can);
        dispatchActions.setPermissions(context::can);
        applyConfigurationPermissions(context);
        tabs.setEnabledAt(liveDispatchTabIndex, context.can(com.senyalert.security.Permission.VIEW_INCIDENTS));
        tabs.setEnabledAt(evidenceArchiveTabIndex, context.can(com.senyalert.security.Permission.VIEW_INCIDENTS));
        boolean cameraAllowed = context.can(com.senyalert.security.Permission.CONFIGURE_CAMERAS)
                || context.can(com.senyalert.security.Permission.EXPORT_CONFIG);
        tabs.setEnabledAt(camerasTabIndex, cameraAllowed);
        boolean engineAllowed = context.can(com.senyalert.security.Permission.CONFIGURE_ENGINE)
                || context.can(com.senyalert.security.Permission.EXPORT_CONFIG);
        settingsTabs.setEnabledAt(engineSettingsTabIndex, engineAllowed);
        // Account remains available to every signed-in person, even if they have no operational grants.
        tabs.setEnabledAt(settingsTabIndex, true);
        int selectedSettings = settingsTabs.getSelectedIndex();
        if (selectedSettings < 0 || !settingsTabs.isEnabledAt(selectedSettings)) {
            for (int index = 0; index < settingsTabs.getTabCount(); index++) {
                if (settingsTabs.isEnabledAt(index)) {
                    settingsTabs.setSelectedIndex(index);
                    break;
                }
            }
        }
        for (int index = 0; index < tabs.getTabCount(); index++) if (tabs.isEnabledAt(index)) { tabs.setSelectedIndex(index); break; }
        setTitle("SenyAlert | " + context.session().username() + " (" + context.session().role() + ")");
    }

    private static Ikon workspaceIcon(String name) {
        return switch (name) {
            case "Benchmarking" -> FontAwesomeSolid.CHART_LINE;
            case "Audit logs" -> FontAwesomeSolid.CLIPBOARD_LIST;
            default -> FontAwesomeSolid.FILE;
        };
    }

    private static Ikon settingsWorkspaceIcon(String name) {
        return switch (name) {
            case "Account" -> FontAwesomeSolid.USER_COG;
            case "User management" -> FontAwesomeSolid.USERS_COG;
            case "Remote support" -> FontAwesomeSolid.HEADSET;
            case "Export settings" -> FontAwesomeSolid.FOLDER_OPEN;
            default -> FontAwesomeSolid.COG;
        };
    }

    private void applyConfigurationPermissions(com.senyalert.security.SecurityContext context) {
        setPermissionState(
                cameraExportConfiguration,
                context.can(com.senyalert.security.Permission.EXPORT_CONFIG),
                "Export saved camera settings as JSON.",
                "Requires the Export configuration permission.");
        setPermissionState(
                cameraImportConfiguration,
                context.can(com.senyalert.security.Permission.CONFIGURE_CAMERAS),
                "Choose a camera-settings JSON file and review it before applying it.",
                "Requires the Configure cameras permission.");
        setPermissionState(
                engineExportConfiguration,
                context.can(com.senyalert.security.Permission.EXPORT_CONFIG),
                "Export saved camera and engine settings as JSON.",
                "Requires the Export configuration permission.");
        setPermissionState(
                engineImportConfiguration,
                context.can(com.senyalert.security.Permission.CONFIGURE_ENGINE),
                "Choose a configuration JSON file and review it before applying it.",
                "Requires the Configure engine settings permission.");
    }

    private static void setPermissionState(JButton button, boolean permitted, String enabledHint, String deniedHint) {
        if (button == null) {
            return;
        }
        button.setEnabled(permitted);
        button.setToolTipText(permitted ? enabledHint : deniedHint);
    }

    private JPanel configurationPanel(javax.swing.JComponent content, boolean camerasOnly) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        JButton export = new StyledButton(camerasOnly ? "Export camera settings…" : "Export configuration…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
        JButton load = new StyledButton(camerasOnly ? "Import camera settings…" : "Import configuration…", FontAwesomeSolid.FILE_IMPORT, BlueTheme.DEEP_BLUE);
        export.setToolTipText("Export saved settings as JSON. Stream credentials are included if present; use env: references for portable templates.");
        load.setToolTipText("Choose a JSON setup file and review confirmation before applying settings.");
        if (camerasOnly) {
            cameraExportConfiguration = export;
            cameraImportConfiguration = load;
        } else {
            engineExportConfiguration = export;
            engineImportConfiguration = load;
        }
        export.addActionListener(e -> {
            if (actions == null) return;
            NativeFileDialogs.saveFile(this,
                    camerasOnly ? "Export camera settings" : "Export SenyAlert configuration",
                    com.senyalert.service.ExportDefaults.Kind.CONFIGURATION,
                    camerasOnly ? "senyalert-cameras.json" : "senyalert-config.json",
                    java.util.List.of("json"))
                    .ifPresent(destination -> actions.exportConfiguration(destination, camerasOnly));
        });
        load.addActionListener(e -> {
            if (actions == null) return;
            NativeFileDialogs.openFile(this,
                    camerasOnly ? "Import camera settings" : "Import SenyAlert configuration",
                    com.senyalert.service.ExportDefaults.Kind.CONFIGURATION,
                    java.util.List.of("json"))
                    .filter(source -> JOptionPane.showConfirmDialog(this, "Apply settings from " + source.getFileName() + "?",
                            "Import configuration", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                    .ifPresent(source -> actions.importConfiguration(source, camerasOnly));
        });
        buttons.add(load); buttons.add(export); wrapper.add(buttons, BorderLayout.SOUTH); return wrapper;
    }

    private RoundedPanel incidentTableCard(String headingText) {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new BorderLayout(0, 8));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());
        JLabel heading = new JLabel(headingText);
        heading.setFont(BlueTheme.font(Font.BOLD, 16));
        heading.setForeground(BlueTheme.TEXT);
        JLabel hint = new JLabel("Pending only · select a row to handle it");
        hint.setFont(BlueTheme.font(Font.PLAIN, 10));
        hint.setForeground(BlueTheme.MUTED);
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(heading, BorderLayout.WEST);
        header.add(hint, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(dispatchTable);
        scroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        card.add(scroll, BorderLayout.CENTER);
        card.add(buildDispatchPagination(), BorderLayout.SOUTH);
        return card;
    }

    private void configureIncidentTable(JTable table, boolean openDispatchForEvidence) {
        table.setFont(BlueTheme.font(Font.PLAIN, 11));
        table.setForeground(BlueTheme.TEXT);
        table.setBackground(Color.WHITE);
        table.setRowHeight(30);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {48, 130, 125, 78, 86, 62, 58, 115, 112, 145, 105};
        for (int index = 0; index < widths.length; index++) { table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]); table.getColumnModel().getColumn(index).setMinWidth(widths[index]); }
        table.setSelectionBackground(new Color(211, 231, 249));
        table.setSelectionForeground(BlueTheme.TEXT);
        table.setGridColor(new Color(229, 237, 246));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // The source model is already newest-first. Sorting a page independently
        // would make its navigation misleading, so retain sorting in Archive only.
        table.setAutoCreateRowSorter(openDispatchForEvidence);
        table.getTableHeader().setFont(BlueTheme.font(Font.BOLD, 10));
        table.getTableHeader().setBackground(new Color(229, 239, 249));
        table.getTableHeader().setForeground(BlueTheme.TEXT);
        table.getTableHeader().setReorderingAllowed(false);
        table.getAccessibleContext().setAccessibleName("Live incident queue");
        table.getAccessibleContext().setAccessibleDescription(
                "The newest SOS incidents, with alert, confidence, occupancy, and evidence state.");
        table.getSelectionModel().addListSelectionListener(event -> selectIncident(event, table, openDispatchForEvidence));
    }

    private void selectIncident(ListSelectionEvent event, JTable table, boolean openDispatchForEvidence) {
        if (event.getValueIsAdjusting() || actions == null || table.getSelectedRow() < 0
                || (!openDispatchForEvidence && synchronizingDispatchSelection)) {
            return;
        }
        int row = table.convertRowIndexToModel(table.getSelectedRow());
        Incident incident = openDispatchForEvidence
                ? incidentModel.incidentAt(row)
                : dispatchIncidentModel.incidentAt(row);
        if (incident != null) {
            if (openDispatchForEvidence) {
                tabs.setSelectedIndex(liveDispatchTabIndex);
            } else {
                selectedDispatchIncidentId = incident.id();
                dispatchActions.showIncident(incident);
            }
            actions.selectIncident(incident.id());
        }
    }

    /** The banner intentionally navigates to the same row an operator would select manually. */
    private void selectAlertedIncident(long incidentId) {
        if (actions == null) {
            return;
        }
        int row = dispatchIncidentModel.showIncident(incidentId);
        if (row < 0) {
            showInfo("That alert is no longer waiting in the live incident queue.");
            return;
        }
        boolean alreadySelected = selectedDispatchIncidentId == incidentId && dispatchTable.getSelectedRow() == row;
        dispatchTable.setRowSelectionInterval(row, row);
        dispatchTable.scrollRectToVisible(dispatchTable.getCellRect(row, 0, true));
        dispatchTable.requestFocusInWindow();
        if (alreadySelected) {
            Incident incident = dispatchIncidentModel.incidentAt(row);
            if (incident != null) {
                dispatchActions.showIncident(incident);
                actions.selectIncident(incident.id());
            }
        }
    }

    /** Keeps the selected active record stable as automatic refreshes replace the source rows. */
    private void reconcileDispatchSelection() {
        if (selectedDispatchIncidentId <= 0) {
            dispatchIncidentModel.goToFirstPage();
            return;
        }
        int row = dispatchIncidentModel.showIncident(selectedDispatchIncidentId);
        if (row < 0) {
            selectedDispatchIncidentId = -1L;
            dispatchActions.clearSelection();
            return;
        }
        synchronizingDispatchSelection = true;
        try {
            dispatchTable.setRowSelectionInterval(row, row);
            Rectangle bounds = dispatchTable.getCellRect(row, 0, true);
            dispatchTable.scrollRectToVisible(bounds);
            Incident selected = dispatchIncidentModel.incidentAt(row);
            if (selected != null) {
                dispatchActions.showIncident(selected);
            }
        } finally {
            synchronizingDispatchSelection = false;
        }
    }

    private void refreshLiveDispatch() {
        if (actions != null && isShowing() && tabs.getSelectedIndex() == liveDispatchTabIndex) {
            actions.refreshIncidents();
        }
    }

    private JPanel buildDispatchSummary() {
        JPanel summary = new JPanel(new BorderLayout(8, 0));
        summary.setOpaque(false);
        summary.setMinimumSize(new Dimension(0, 76));
        summary.setPreferredSize(new Dimension(0, 82));
        summary.add(buildMetricRow(), BorderLayout.CENTER);
        alertBanner.setPreferredSize(new Dimension(156, 64));
        alertBanner.setMinimumSize(new Dimension(148, 60));
        livePreview.setPreferredSize(new Dimension(184, 74));
        livePreview.setMinimumSize(new Dimension(172, 70));
        JPanel alertAndPreview = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        alertAndPreview.setOpaque(false);
        alertAndPreview.add(alertBanner);
        alertAndPreview.add(livePreview);
        summary.add(alertAndPreview, BorderLayout.EAST);
        return summary;
    }

    private JPanel buildMetricRow() {
        JPanel row = new JPanel(new GridLayout(1, 3, 6, 0));
        row.setOpaque(false);
        row.add(metricCard("UNACKNOWLEDGED", activeMetric, BlueTheme.DANGER));
        JLabel mode = new JLabel("Crowd-aware policy", SwingConstants.CENTER);
        row.add(metricCard("TRIAGE POLICY", mode, BlueTheme.PRIMARY));
        JLabel media = new JLabel("Snapshot + video", SwingConstants.CENTER);
        row.add(metricCard("EVIDENCE CAPTURE", media, BlueTheme.SUCCESS));
        return row;
    }

    private RoundedPanel metricCard(String caption, JLabel value, Color accent) {
        RoundedPanel card = new RoundedPanel(12);
        card.setLayout(new BorderLayout(0, 1));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 8, 4, 8)));
        JLabel label = new JLabel(caption);
        label.setFont(BlueTheme.font(Font.BOLD, 9));
        label.setForeground(BlueTheme.MUTED);
        value.setFont(BlueTheme.font(Font.BOLD, 14));
        value.setForeground(accent);
        value.setVerticalAlignment(SwingConstants.TOP);
        value.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        card.add(label, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildDispatchPagination() {
        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);

        JPanel pageSize = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0));
        pageSize.setOpaque(false);
        JLabel rows = new JLabel("Rows");
        rows.setFont(BlueTheme.font(Font.BOLD, 10));
        rows.setForeground(BlueTheme.MUTED);
        dispatchPageSize.setSelectedItem(dispatchIncidentModel.pageSize());
        dispatchPageSize.setFont(BlueTheme.font(Font.PLAIN, 11));
        dispatchPageSize.setFocusable(false);
        dispatchPageSize.setToolTipText("Incidents shown per dispatch page");
        dispatchPageSize.addActionListener(event -> {
            Integer selected = (Integer) dispatchPageSize.getSelectedItem();
            if (selected != null) {
                dispatchIncidentModel.setPageSize(selected);
                refreshDispatchPagination();
            }
        });
        pageSize.add(rows);
        pageSize.add(dispatchPageSize);
        controls.add(pageSize, BorderLayout.WEST);

        dispatchPageStatus.setHorizontalAlignment(SwingConstants.CENTER);
        dispatchPageStatus.setFont(BlueTheme.font(Font.PLAIN, 10));
        dispatchPageStatus.setForeground(BlueTheme.MUTED);
        controls.add(dispatchPageStatus, BorderLayout.CENTER);

        JPanel navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 5, 0));
        navigation.setOpaque(false);
        dispatchPreviousPage.addActionListener(event -> dispatchIncidentModel.previousPage());
        dispatchNextPage.addActionListener(event -> dispatchIncidentModel.nextPage());
        navigation.add(dispatchPreviousPage);
        navigation.add(dispatchNextPage);
        controls.add(navigation, BorderLayout.EAST);
        return controls;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BlueTheme.BACKGROUND);
        footer.setBorder(BorderFactory.createEmptyBorder(5, 12, 7, 12));
        infoStrip.setFont(BlueTheme.font(Font.PLAIN, 11));
        infoStrip.setForeground(BlueTheme.INFO);
        infoStrip.setIcon(FontIcon.of(FontAwesomeSolid.CHECK, 11, BlueTheme.INFO));
        infoStrip.setIconTextGap(6);
        infoStrip.getAccessibleContext().setAccessibleName("Dashboard status");
        infoStripContainer.setBackground(BlueTheme.INFO_TINT);
        infoStripContainer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(184, 215, 244)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        infoStripContainer.add(infoStrip, BorderLayout.CENTER);
        footer.add(infoStripContainer, BorderLayout.CENTER);
        JLabel instruction = new JLabel("Select an incident to review its evidence.");
        instruction.setFont(BlueTheme.font(Font.PLAIN, 11));
        instruction.setForeground(BlueTheme.MUTED);

        return footer;
    }

    private static JPanel contentPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(BlueTheme.BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        return panel;
    }

    private void updateActiveMetric() {
        activeMetric.setText(String.valueOf(incidentModel.pendingCount()));
    }

    private void refreshDispatchPagination() {
        Runnable update = () -> {
            int total = dispatchIncidentModel.totalCount();
            int pages = dispatchIncidentModel.pageCount();
            if (total == 0) {
                dispatchPageStatus.setText("No incidents");
            } else {
                dispatchPageStatus.setText(dispatchIncidentModel.firstVisibleNumber() + "–"
                        + dispatchIncidentModel.lastVisibleNumber() + " of " + total
                        + " · Page " + (dispatchIncidentModel.pageIndex() + 1) + " of " + pages);
            }
            dispatchPreviousPage.setEnabled(dispatchIncidentModel.hasPreviousPage());
            dispatchNextPage.setEnabled(dispatchIncidentModel.hasNextPage());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private static JButton paginationButton(String label) {
        JButton button = new JButton(label);
        button.setFont(BlueTheme.font(Font.BOLD, 10));
        button.setForeground(Color.WHITE);
        button.setBackground(BlueTheme.DEEP_BLUE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        return button;
    }

    private void showStatusMessage(Color foreground, Color background, Ikon icon, String message) {
        String safeMessage = message == null || message.isBlank() ? "Ready" : message;
        infoStrip.setForeground(foreground);
        infoStrip.setIcon(FontIcon.of(icon, 11, foreground));
        infoStrip.setText(safeMessage);
        infoStrip.setToolTipText(safeMessage);
        infoStrip.getAccessibleContext().setAccessibleDescription(safeMessage);
        infoStripContainer.setBackground(background);
        infoStripContainer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(foreground, 1),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        infoStripContainer.revalidate();
        infoStripContainer.repaint();
    }
}
