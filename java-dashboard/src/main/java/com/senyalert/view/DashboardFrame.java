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
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
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
    private final EvidencePanel evidencePanel = new EvidencePanel();
    private final EvidenceArchivePanel evidenceArchivePanel = new EvidenceArchivePanel(ArchiveScope.RECORDED);
    private final EvidenceArchivePanel uploadedEvidenceArchivePanel = new EvidenceArchivePanel(ArchiveScope.UPLOADED);
    private final CameraPreviewGrid previewGrid = new CameraPreviewGrid();
    private final SettingsPanel settingsPanel = new SettingsPanel();
    private final CameraManagementPanel cameraManagementPanel = new CameraManagementPanel();
    private final VideoUploadPanel videoUploadPanel = new VideoUploadPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel engineStatus = new JLabel("Starting dashboard…");
    private final JLabel cameraStatus = new JLabel("Camera: awaiting engine");
    private final JLabel activeMetric = new JLabel("0");
    private final JLabel infoStrip = new JLabel("Ready");
    private final JPanel infoStripContainer = new JPanel(new BorderLayout(6, 0));
    private final JComboBox<Integer> dispatchPageSize = new JComboBox<>(new Integer[]{5, 10, 20, 50});
    private final JButton dispatchPreviousPage = paginationButton("‹ Previous");
    private final JButton dispatchNextPage = paginationButton("Next ›");
    private final JLabel dispatchPageStatus = new JLabel();
    private DashboardActions actions;

    public DashboardFrame() {
        setTitle("SenyAlert | Distress Dispatch & Triage");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 640));
        setSize(1220, 720);
        setLocationByPlatform(true);
        configureIncidentTable(dispatchTable, false);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BlueTheme.BACKGROUND);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildTabs(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        dispatchIncidentModel.addTableModelListener(event -> refreshDispatchPagination());
        refreshDispatchPagination();
    }

    @Override
    public void setActions(DashboardActions actions) {
        this.actions = actions;
        settingsPanel.setActions(actions::saveSettings, actions::toggleEnginePause, actions::restartEngine);
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
        uploadedEvidenceArchivePanel.setActions(actions);
        videoUploadPanel.setSubmitAction(actions::submitUploadedVideo);
    }

    @Override
    public void showSettings(EngineSettings settings) {
        settingsPanel.showSettings(settings);
        cameraManagementPanel.showSettings(settings);
        previewGrid.showConfiguredCameras(settings.cameras());
    }

    @Override
    public void showIncidents(List<Incident> incidents) {
        incidentModel.replaceAll(incidents);
        dispatchTable.setEmptyState(
                "No incidents waiting",
                "New SOS detections will appear here automatically.");
        evidenceArchivePanel.showIncidents(incidents);
        dispatchIncidentModel.goToFirstPage();
        updateActiveMetric();
    }

    @Override
    public void upsertIncident(Incident incident) {
        incidentModel.upsert(incident);
        dispatchTable.setEmptyState(
                "No incidents waiting",
                "New SOS detections will appear here automatically.");
        evidenceArchivePanel.upsertIncident(incident);
        dispatchIncidentModel.goToFirstPage();
        updateActiveMetric();
    }

    @Override
    public void showUploadedIncidents(List<Incident> incidents) {
        uploadedEvidenceArchivePanel.showIncidents(incidents);
    }

    @Override
    public void upsertUploadedIncident(Incident incident) {
        uploadedEvidenceArchivePanel.upsertIncident(incident);
    }

    @Override
    public void removeIncident(long incidentId) {
        incidentModel.remove(incidentId);
        evidencePanel.clearIfViewing(incidentId);
        evidenceArchivePanel.removeIncident(incidentId);
        updateActiveMetric();
    }

    @Override
    public void removeUploadedIncident(long incidentId) {
        uploadedEvidenceArchivePanel.removeIncident(incidentId);
    }

    @Override
    public void showOperatorRecordUpdated(Incident incident) {
        evidencePanel.showOperatorRecordUpdated(incident);
        evidenceArchivePanel.showOperatorRecordUpdated(incident);
    }

    @Override
    public void showUploadedOperatorRecordUpdated(Incident incident) {
        uploadedEvidenceArchivePanel.showOperatorRecordUpdated(incident);
    }

    @Override
    public void showIncidentEvidence(IncidentEvidence evidence) {
        evidencePanel.showEvidence(evidence);
    }

    @Override
    public void showArchiveIncidentEvidence(IncidentEvidence evidence) {
        showArchiveIncidentEvidence(ArchiveScope.RECORDED, evidence);
    }

    @Override
    public void showArchiveIncidentEvidence(ArchiveScope scope, IncidentEvidence evidence) {
        if (scope == ArchiveScope.UPLOADED) {
            uploadedEvidenceArchivePanel.showEvidence(evidence);
        } else {
            evidenceArchivePanel.showEvidence(evidence);
        }
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
        previewGrid.showPreview(preview);
    }

    @Override
    public void showUploadedVideoStatus(UploadedVideoStatus status) {
        videoUploadPanel.showAnalysisStatus(status);
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
        tabs.addTab(" Live dispatch", FontIcon.of(FontAwesomeSolid.BELL, 16, BlueTheme.PRIMARY), buildDispatchTab());
        tabs.addTab(" Cameras", FontIcon.of(FontAwesomeSolid.CAMERA, 16, BlueTheme.PRIMARY), cameraManagementPanel);
        tabs.addTab(" Video analysis", FontIcon.of(FontAwesomeSolid.FILE_VIDEO, 16, BlueTheme.PRIMARY), videoUploadPanel);
        tabs.addTab(" Evidence archive", FontIcon.of(FontAwesomeSolid.DATABASE, 16, BlueTheme.PRIMARY), buildArchiveTab());
        tabs.addTab(" Engine settings", FontIcon.of(FontAwesomeSolid.COG, 16, BlueTheme.PRIMARY), settingsPanel);
        tabs.setToolTipTextAt(0, "Triage the latest SOS detections and inspect current camera snapshots.");
        tabs.setToolTipTextAt(1, "Add, disable, and tune each camera source independently.");
        tabs.setToolTipTextAt(2, "Run the same vision pipeline against a selected local demonstration video.");
        tabs.setToolTipTextAt(3, "Review recorded and uploaded evidence separately.");
        tabs.setToolTipTextAt(4, "Configure detection, triage, and engine behaviour.");
        return tabs;
    }

    private JPanel buildDispatchTab() {
        JPanel panel = contentPanel();

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(buildDispatchSummary(), BorderLayout.NORTH);
        JSplitPane previewAndEvidence = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewGrid, evidencePanel);
        // Preview snapshots are situational awareness, while incident evidence and
        // queue actions need most of the dispatcher's working area.
        previewAndEvidence.setResizeWeight(0.26);
        previewAndEvidence.setDividerLocation(0.26);
        previewAndEvidence.setBorder(BorderFactory.createEmptyBorder());
        previewAndEvidence.setDividerSize(8);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, incidentTableCard("Live incident queue"), previewAndEvidence);
        split.setResizeWeight(0.66);
        split.setDividerLocation(0.66);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(10);
        center.add(split, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private javax.swing.JComponent buildArchiveTab() {
        JTabbedPane archiveSources = new JTabbedPane();
        archiveSources.setFont(BlueTheme.font(Font.BOLD, 12));
        archiveSources.addTab("Recorded", FontIcon.of(FontAwesomeSolid.CAMERA, 14, BlueTheme.PRIMARY), evidenceArchivePanel);
        archiveSources.addTab("Uploaded", FontIcon.of(FontAwesomeSolid.FILE_VIDEO, 14, BlueTheme.PRIMARY), uploadedEvidenceArchivePanel);
        archiveSources.setToolTipTextAt(0, "Incidents captured from active camera streams and stored in incidents.db.");
        archiveSources.setToolTipTextAt(1, "Offline video-analysis results stored separately in uploaded-incidents.db.");
        return archiveSources;
    }

    private RoundedPanel incidentTableCard(String headingText) {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new BorderLayout(0, 8));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());
        JLabel heading = new JLabel(headingText);
        heading.setFont(BlueTheme.font(Font.BOLD, 16));
        heading.setForeground(BlueTheme.TEXT);
        card.add(heading, BorderLayout.NORTH);
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
        table.setRowHeight(22);
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
        if (event.getValueIsAdjusting() || actions == null || table.getSelectedRow() < 0) {
            return;
        }
        int row = table.convertRowIndexToModel(table.getSelectedRow());
        Incident incident = openDispatchForEvidence
                ? incidentModel.incidentAt(row)
                : dispatchIncidentModel.incidentAt(row);
        if (incident != null) {
            if (openDispatchForEvidence) {
                tabs.setSelectedIndex(0);
            }
            actions.selectIncident(incident.id());
        }
    }

    private JPanel buildDispatchSummary() {
        JPanel summary = new JPanel(new BorderLayout(8, 0));
        summary.setOpaque(false);
        summary.add(buildMetricRow(), BorderLayout.CENTER);
        summary.add(alertBanner, BorderLayout.EAST);
        return summary;
    }

    private JPanel buildMetricRow() {
        JPanel row = new JPanel(new GridLayout(1, 3, 8, 0));
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
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        JLabel label = new JLabel(caption);
        label.setFont(BlueTheme.font(Font.BOLD, 9));
        label.setForeground(BlueTheme.MUTED);
        value.setFont(BlueTheme.font(Font.BOLD, 14));
        value.setForeground(accent);
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
        footer.add(infoStripContainer, BorderLayout.WEST);
        JLabel instruction = new JLabel("Select an incident to review its evidence.");
        instruction.setFont(BlueTheme.font(Font.PLAIN, 11));
        instruction.setForeground(BlueTheme.MUTED);
        footer.add(instruction, BorderLayout.EAST);
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
