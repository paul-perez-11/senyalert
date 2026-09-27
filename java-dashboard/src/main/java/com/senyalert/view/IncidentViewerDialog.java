package com.senyalert.view;

import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.MediaDeletionOptions;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.NativeFileDialogs;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import org.json.JSONObject;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/**
 * Modal archive viewer. It exposes only the operator workflow fields and
 * leaves all detection and evidence attributes read-only.
 */
final class IncidentViewerDialog extends JDialog {
    @FunctionalInterface
    interface ExportAction {
        void export(long incidentId, Path destinationDirectory, ArchiveExportMode mode);
    }

    @FunctionalInterface
    interface DeleteAction {
        void delete(long incidentId, MediaDeletionOptions options);
    }

    private final JLabel title = new JLabel("Incident evidence");
    /** Structured labels are intentionally not selectable; Copy record data is the audit-aware export path. */
    private final JPanel recordDetails = new ViewportWidthPanel(new java.awt.GridBagLayout());
    private final JComboBox<IncidentStatus> status = new JComboBox<>(IncidentStatus.values());
    private final JTextArea operatorNotes = new JTextArea(4, 42);
    private final JLabel snapshot = new JLabel();
    private final JLabel videoStatus = new JLabel();
    private final JButton acknowledge = new StyledButton("Acknowledge", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);
    private final JButton resolve = new StyledButton("Resolve", FontAwesomeSolid.CHECK, BlueTheme.SUCCESS);
    private final JButton save = new StyledButton("Save operator update", FontAwesomeSolid.CHECK, BlueTheme.DEEP_BLUE);
    private final JButton play = new StyledButton("Play natively", FontAwesomeSolid.PLAY, BlueTheme.DEEP_BLUE);
    private final JButton exportRecord = new StyledButton("Export report + media…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
    private final JButton exportMedia = new StyledButton("Export all media…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
    private final JButton deleteRecord = new StyledButton("Delete record…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);
    private final JButton deleteRecordFromMedia = new StyledButton("Delete record…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);

    private final JButton copyRecord = new StyledButton("Copy record data", FontAwesomeSolid.COPY, BlueTheme.PRIMARY);
    private final JButton copyImage = new StyledButton("Copy snapshot", FontAwesomeSolid.COPY, BlueTheme.PRIMARY);
    private LongConsumer copyRecordAction = ignored -> { };
    private LongConsumer copyImageAction = ignored -> { };
    private java.util.function.Predicate<com.senyalert.security.Permission> allowed = ignored -> false;
    private IncidentEvidence evidence;
    private BiConsumer<Long, OperatorIncidentUpdate> updateAction = (ignored, update) -> { };
    private DeleteAction deleteAction = (ignored, options) -> { };
    private LongConsumer playAction = ignored -> { };
    private ExportAction exportAction = (ignored, directory, mode) -> { };

    IncidentViewerDialog(Window owner) {
        super(owner, "Incident evidence", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(820, 640));
        setPreferredSize(new Dimension(1000, 760));

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(BlueTheme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

        title.setFont(BlueTheme.font(Font.BOLD, 18));
        title.setForeground(BlueTheme.TEXT);
        root.add(title, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(BlueTheme.font(Font.BOLD, 12));
        tabs.addTab(" Record data", FontIcon.of(FontAwesomeSolid.CLIPBOARD_LIST, 15, BlueTheme.PRIMARY), buildRecordTab());
        tabs.addTab(" Media", FontIcon.of(FontAwesomeSolid.IMAGE, 15, BlueTheme.PRIMARY), buildMediaTab());
        root.add(tabs, BorderLayout.CENTER);
        setContentPane(root);

        acknowledge.addActionListener(event -> applyStatus(IncidentStatus.ACKNOWLEDGED));
        resolve.addActionListener(event -> applyStatus(IncidentStatus.RESOLVED));
        save.addActionListener(event -> saveOperatorUpdate());
        play.addActionListener(event -> withEvidence(playAction));
        exportRecord.addActionListener(event -> chooseExport(ArchiveExportMode.RECORD_BUNDLE));
        exportMedia.addActionListener(event -> chooseExport(ArchiveExportMode.MEDIA));
        deleteRecord.addActionListener(event -> confirmDelete());
        deleteRecordFromMedia.addActionListener(event -> confirmDelete());
        copyRecord.addActionListener(event -> withEvidence(copyRecordAction));
        copyImage.addActionListener(event -> withEvidence(copyImageAction));
        copyRecord.setToolTipText("Copy the saved incident record, timestamps, confidence, notes and evidence hashes. This action is audited.");
        copyImage.setToolTipText("Copy the original incident snapshot to the clipboard. This action is audited.");
        exportRecord.setToolTipText("Export the incident's JSON and plaintext record data with every available image and video. This action is audited.");
        exportMedia.setToolTipText("Export every available image and video with JSON and plaintext record data. This action is audited.");
        recordDetails.setToolTipText("Read the formatted saved incident data. Use Copy record data to place the clean report on the clipboard.");
        snapshot.setToolTipText("Original incident snapshot preview. Use Copy snapshot to place the full image on the clipboard.");
        operatorNotes.setToolTipText("Save factual observations about this incident; each saved change records your username in the audit log.");
        status.setToolTipText("Change the incident workflow status using your assigned permissions.");
        recordDetails.getAccessibleContext().setAccessibleName("Formatted saved incident record data");
        recordDetails.getAccessibleContext().setAccessibleDescription(
                "Formatted, read-only incident facts, timestamps, confidence, operator record, and evidence integrity hashes. Use Copy record data to copy it.");
        snapshot.getAccessibleContext().setAccessibleName("Incident snapshot preview");
        snapshot.getAccessibleContext().setAccessibleDescription(
                "Preview of the original incident image. The Copy snapshot control copies the full image.");
        tabs.setToolTipTextAt(0, "Detection facts, evidence integrity and the saved operator record.");
        tabs.setToolTipTextAt(1, "Inspect, copy or export the original incident snapshot and video.");
    }

    void setActions(
            BiConsumer<Long, OperatorIncidentUpdate> updateAction,
            DeleteAction deleteAction,
            LongConsumer playAction,
            ExportAction exportAction) {
        this.updateAction = updateAction;
        this.deleteAction = deleteAction;
        this.playAction = playAction;
        this.exportAction = exportAction;
    }

    void setCopyActions(LongConsumer record, LongConsumer image,
            java.util.function.Predicate<com.senyalert.security.Permission> allowed) {
        copyRecordAction = record; copyImageAction = image; this.allowed = allowed;
    }

    void showEvidence(IncidentEvidence newEvidence) {
        evidence = newEvidence;
        refreshFromEvidence();
        pack();
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    void showOperatorRecordUpdated(Incident updated) {
        if (evidence == null || evidence.incident().id() != updated.id()) {
            return;
        }
        evidence = new IncidentEvidence(
                updated,
                evidence.snapshotBytes(),
                evidence.snapshotMimeType(),
                evidence.videoBytes(),
                evidence.videoMimeType());
        refreshFromEvidence();
    }

    void closeIfViewing(long incidentId) {
        if (evidence != null && evidence.incident().id() == incidentId) {
            dispose();
            evidence = null;
        }
    }

    private JPanel buildRecordTab() {
        JPanel panel = transparentPanel(new BorderLayout(0, 6));
        JScrollPane detailsScroll = new JScrollPane(recordDetails);
        detailsScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        detailsScroll.getViewport().setBackground(BlueTheme.BACKGROUND);
        detailsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        detailsScroll.setWheelScrollingEnabled(true);
        detailsScroll.getVerticalScrollBar().setUnitIncrement(28);
        detailsScroll.getVerticalScrollBar().setBlockIncrement(196);
        JPanel heading = transparentPanel(new BorderLayout(8, 0));
        heading.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        JLabel headingLabel = new JLabel("Saved incident facts · timestamps · confidence · evidence integrity");
        headingLabel.setFont(BlueTheme.font(Font.BOLD, 12));
        headingLabel.setForeground(BlueTheme.TEXT);
        heading.add(headingLabel, BorderLayout.CENTER);
        heading.add(copyRecord, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(detailsScroll, BorderLayout.CENTER);
        panel.add(buildOperatorEditor(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildOperatorEditor() {
        JPanel editor = transparentPanel(new BorderLayout(0, 6));
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(7, 9, 8, 9)));

        JLabel heading = new JLabel("Operator workflow fields");
        heading.setFont(BlueTheme.font(Font.BOLD, 13));
        heading.setForeground(BlueTheme.TEXT);
        JLabel immutable = new JLabel("Detection, counts, confidence, IDs, timestamps, and evidence are read-only.");
        immutable.setFont(BlueTheme.font(Font.PLAIN, 10));
        immutable.setForeground(BlueTheme.MUTED);
        JPanel labels = transparentPanel(new java.awt.GridLayout(0, 1, 0, 2));
        labels.add(heading);
        labels.add(immutable);
        editor.add(labels, BorderLayout.NORTH);

        JPanel fields = transparentPanel(new java.awt.GridBagLayout());
        JPanel statusField = transparentPanel(new BorderLayout(0, 4));
        JLabel statusLabel = new JLabel("Status");
        statusLabel.setFont(BlueTheme.font(Font.BOLD, 11));
        statusLabel.setForeground(BlueTheme.TEXT);
        statusField.add(statusLabel, BorderLayout.NORTH);
        status.setFont(BlueTheme.font(Font.PLAIN, 12));
        statusField.add(status, BorderLayout.CENTER);
        GridBagConstraints statusConstraints = recordConstraints(0, 0, 1, 1.0, 0.0);
        statusConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        fields.add(statusField, statusConstraints);

        operatorNotes.setFont(BlueTheme.font(Font.PLAIN, 12));
        operatorNotes.setForeground(BlueTheme.TEXT);
        operatorNotes.setLineWrap(true);
        operatorNotes.setWrapStyleWord(true);
        operatorNotes.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        JPanel notesField = transparentPanel(new BorderLayout(0, 4));
        JLabel noteLabel = new JLabel("Operator note (max 2,000 characters)");
        noteLabel.setFont(BlueTheme.font(Font.BOLD, 11));
        noteLabel.setForeground(BlueTheme.TEXT);
        notesField.add(noteLabel, BorderLayout.NORTH);
        JScrollPane notesScroll = new JScrollPane(operatorNotes);
        notesScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        notesField.add(notesScroll, BorderLayout.CENTER);
        GridBagConstraints notesConstraints = recordConstraints(1, 0, 1, 1.0, 1.0);
        notesConstraints.insets = new java.awt.Insets(0, 0, 0, 0);
        notesConstraints.fill = java.awt.GridBagConstraints.BOTH;
        fields.add(notesField, notesConstraints);
        editor.add(fields, BorderLayout.CENTER);

        JPanel actions = transparentPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        actions.add(acknowledge);
        actions.add(resolve);
        actions.add(save);
        actions.add(exportRecord);
        actions.add(deleteRecord);
        editor.add(actions, BorderLayout.SOUTH);
        return editor;
    }

    private JPanel buildMediaTab() {
        JPanel panel = transparentPanel(new BorderLayout(0, 10));
        snapshot.setHorizontalAlignment(JLabel.CENTER);
        snapshot.setVerticalAlignment(JLabel.CENTER);
        snapshot.setOpaque(true);
        snapshot.setBackground(new Color(230, 240, 250));
        snapshot.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        snapshot.setPreferredSize(new Dimension(700, 395));
        panel.add(snapshot, BorderLayout.CENTER);

        JPanel footer = transparentPanel(new BorderLayout(0, 8));
        videoStatus.setFont(BlueTheme.font(Font.PLAIN, 12));
        videoStatus.setForeground(BlueTheme.TEXT);
        footer.add(videoStatus, BorderLayout.NORTH);
        JPanel actions = transparentPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        actions.add(copyImage);
        actions.add(play);
        actions.add(exportMedia);
        actions.add(deleteRecordFromMedia);
        footer.add(actions, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshFromEvidence() {
        if (evidence == null) {
            return;
        }
        Incident incident = evidence.incident();
        setTitle("Incident #" + incident.id() + " evidence");
        title.setText("Incident #" + incident.id() + " evidence");
        IncidentEvidence current = evidence;
        showRecordLoading("Preparing timestamps and evidence integrity hashes…");
        new javax.swing.SwingWorker<JSONObject, Void>() {
            protected JSONObject doInBackground() { return com.senyalert.service.IncidentReport.json(current); }
            protected void done() {
                if (evidence != current) return;
                try { renderRecord(get()); }
                catch (Exception failure) { showRecordLoading("Could not prepare the record data. Refresh the incident to retry."); }
            }
        }.execute();
        status.setSelectedItem(incident.status());
        operatorNotes.setText(incident.operatorNotes());
        operatorNotes.setCaretPosition(0);
        boolean snapshotAvailable = (evidence.snapshotBytes() != null && evidence.snapshotBytes().length > 0)
                || !incident.snapshotPath().isBlank();
        boolean videoAvailable = (evidence.videoBytes() != null && evidence.videoBytes().length > 0)
                || (!incident.videoPath().isBlank() && incident.mediaReady());
        boolean videoExportAvailable = (evidence.videoBytes() != null && evidence.videoBytes().length > 0)
                || !incident.videoPath().isBlank();
        play.setEnabled(videoAvailable);
        exportRecord.setEnabled(true);
        exportMedia.setEnabled(snapshotAvailable || videoExportAvailable);
        videoStatus.setText(videoAvailable
                ? "Video evidence is available. Open it natively or export a copy."
                : "No playable video has been supplied for this incident yet.");
        setSnapshot(evidence.snapshotBytes());
        var exportAllowed = allowed.test(com.senyalert.security.Permission.EXPORT_EVIDENCE);
        copyRecord.setEnabled(exportAllowed);
        copyImage.setEnabled(exportAllowed && snapshotAvailable);
        exportRecord.setEnabled(exportAllowed);
        exportMedia.setEnabled(exportAllowed && (snapshotAvailable || videoExportAvailable));
        acknowledge.setEnabled(allowed.test(com.senyalert.security.Permission.ACKNOWLEDGE_INCIDENTS));
        resolve.setEnabled(allowed.test(com.senyalert.security.Permission.RESOLVE_INCIDENTS));
        operatorNotes.setEditable(allowed.test(com.senyalert.security.Permission.EDIT_NOTES));
        status.setEnabled(allowed.test(com.senyalert.security.Permission.ACKNOWLEDGE_INCIDENTS) || allowed.test(com.senyalert.security.Permission.RESOLVE_INCIDENTS));
        save.setEnabled(operatorNotes.isEditable() || status.isEnabled());
        deleteRecord.setEnabled(allowed.test(com.senyalert.security.Permission.DELETE_RECORDS));
        deleteRecordFromMedia.setEnabled(deleteRecord.isEnabled());
    }

    private void applyStatus(IncidentStatus requestedStatus) {
        status.setSelectedItem(requestedStatus);
        saveOperatorUpdate();
    }

    private void saveOperatorUpdate() {
        if (evidence == null) {
            return;
        }
        try {
            updateAction.accept(evidence.incident().id(), new OperatorIncidentUpdate(
                    (IncidentStatus) status.getSelectedItem(), operatorNotes.getText()));
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(), "Check operator update", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void chooseExport(ArchiveExportMode mode) {
        if (evidence == null) {
            return;
        }
        NativeFileDialogs.chooseDirectory(this, "Choose an evidence export folder",
                com.senyalert.service.ExportDefaults.Kind.EVIDENCE)
                .ifPresent(destination -> exportAction.export(evidence.incident().id(), destination, mode));
    }

    private void confirmDelete() {
        if (evidence == null) {
            return;
        }
        long incidentId = evidence.incident().id();
        JCheckBox deleteSnapshot = new JCheckBox("Delete the associated image snapshot");
        JCheckBox deleteVideo = new JCheckBox("Delete the associated video clip");
        JPanel content = transparentPanel(new BorderLayout(0, 8));
        content.add(new JLabel("Delete incident #" + incidentId + " from the evidence archive?"), BorderLayout.NORTH);
        JPanel mediaOptions = transparentPanel(new java.awt.GridLayout(0, 1, 0, 3));
        mediaOptions.add(new JLabel("Optional source-media cleanup (unchecked files remain on disk):"));
        mediaOptions.add(deleteSnapshot);
        mediaOptions.add(deleteVideo);
        content.add(mediaOptions, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, content, "Delete database record",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            deleteAction.delete(incidentId,
                    new MediaDeletionOptions(deleteSnapshot.isSelected(), deleteVideo.isSelected()));
        }
    }

    private void withEvidence(LongConsumer action) {
        if (evidence != null) {
            action.accept(evidence.incident().id());
        }
    }

    private void setSnapshot(byte[] bytes) {
        BufferedImage image = null;
        if (bytes != null && bytes.length > 0) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (Exception ignored) {
                // Keep record details available when one image payload is malformed.
            }
        }
        snapshot.setIcon(new ImageIcon(scale(
                image == null ? placeholder("Snapshot not delivered") : image,
                700,
                395)));
    }

    private void showRecordLoading(String message) {
        recordDetails.removeAll();
        GridBagConstraints constraints = recordConstraints(0, 0, 1, 1.0, 1.0);
        JLabel label = new JLabel(message, FontIcon.of(FontAwesomeSolid.DATABASE, 16, BlueTheme.PRIMARY), JLabel.LEFT);
        label.setFont(BlueTheme.font(Font.PLAIN, 13));
        label.setForeground(BlueTheme.MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        recordDetails.add(label, constraints);
        recordDetails.revalidate();
        recordDetails.repaint();
    }

    private void renderRecord(JSONObject report) {
        JSONObject detection = report.getJSONObject("detection");
        JSONObject operator = report.getJSONObject("operator_record");
        JSONObject evidenceData = report.getJSONObject("evidence");
        recordDetails.removeAll();

        addRecordCard(recordSummary(report, detection, operator), 0);
        addRecordCard(recordCard("Recorded timing", FontAwesomeSolid.CLOCK, new String[][]{
                {"Detected (UTC)", detection.optString("timestamp_utc", "Not recorded")},
                {"Detected (original)", detection.optString("timestamp_original", "Not recorded")},
                {"Epoch seconds", Long.toString(detection.optLong("timestamp_epoch_seconds"))},
                {"Record prepared (UTC)", report.optString("generated_at_utc", "Not recorded")}}), 1);
        addRecordCard(recordCard("Detection & triage", FontAwesomeSolid.BELL, new String[][]{
                {"Camera", detection.optString("camera_id", "Not reported")},
                {"Location", detection.optString("location", "Not reported")},
                {"Incident type", detection.optString("incident_type", "Not reported")},
                {"Confidence", String.format(java.util.Locale.ROOT, "%.2f%% (raw %.3f)", detection.optDouble("confidence_percent"), detection.optDouble("confidence"))},
                {"Alert mode", detection.optString("alert_mode", "Not reported")},
                {"Triage context", detection.optString("triage_context", "Not reported")}}), 2);
        addRecordCard(recordCard("Scene & tracking", FontAwesomeSolid.USERS, new String[][]{
                {"People / hands / signalers", detection.optInt("people_count") + " / " + detection.optInt("hand_count") + " / " + detection.optInt("signaler_count")},
                {"Occupancy", detection.optString("occupancy_status", "Not reported")},
                {"People count stale", Boolean.toString(detection.optBoolean("people_count_stale"))},
                {"Signaler track", detection.optString("signaler_track_id", "Not reported")},
                {"Signaler bounds", detection.optString("signaler_bounds", "Not reported")}}), 3);
        addRecordCard(recordCard("Operator record", FontAwesomeSolid.CHECK, new String[][]{
                {"Current status", operator.optString("status", "Not reported")},
                {"Operator note", display(operator.optString("note"))}}), 4);
        addRecordCard(recordCard("Evidence integrity", FontAwesomeSolid.SHIELD_ALT, new String[][]{
                {"Capture status", evidenceData.optString("media_status", "Not reported")},
                {"Video duration", String.format(java.util.Locale.ROOT, "%.2f seconds", evidenceData.optDouble("video_duration_seconds"))},
                {"Snapshot", mediaDetails(evidenceData.getJSONObject("snapshot"))},
                {"Video", mediaDetails(evidenceData.getJSONObject("video"))}}), 5);
        GridBagConstraints spacer = recordConstraints(6, 0, 1, 1.0, 1.0);
        spacer.fill = java.awt.GridBagConstraints.BOTH;
        spacer.insets = new java.awt.Insets(0, 0, 0, 0);
        recordDetails.add(transparentPanel(new BorderLayout()), spacer);
        recordDetails.revalidate();
        recordDetails.repaint();
    }

    private JPanel recordSummary(JSONObject report, JSONObject detection, JSONObject operator) {
        RoundedPanel card = new RoundedPanel(16);
        card.setLayout(new BorderLayout(0, 5));
        card.setBackground(BlueTheme.INFO_TINT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)));
        JLabel identity = new JLabel("Incident #" + report.optLong("incident_id") + " · " + operator.optString("status", "Not reported"),
                FontIcon.of(FontAwesomeSolid.SHIELD_ALT, 18, BlueTheme.PRIMARY), JLabel.LEFT);
        identity.setIconTextGap(7);
        identity.setFont(BlueTheme.font(Font.BOLD, 16));
        identity.setForeground(BlueTheme.TEXT);
        JLabel summary = new JLabel(html("Camera " + detection.optString("camera_id", "Not reported") + " · "
                + String.format(java.util.Locale.ROOT, "%.2f%% confidence", detection.optDouble("confidence_percent"))
                + " · Event token " + display(report.optString("event_token"))));
        summary.setFont(BlueTheme.font(Font.PLAIN, 12));
        summary.setForeground(BlueTheme.MUTED);
        card.add(identity, BorderLayout.NORTH);
        card.add(summary, BorderLayout.CENTER);
        return card;
    }

    private JPanel recordCard(String heading, org.kordamp.ikonli.Ikon icon, String[][] rows) {
        RoundedPanel card = new RoundedPanel(14);
        card.setLayout(new BorderLayout(0, 8));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel title = new JLabel(heading, FontIcon.of(icon, 14, BlueTheme.PRIMARY), JLabel.LEFT);
        title.setIconTextGap(7);
        title.setFont(BlueTheme.font(Font.BOLD, 13));
        title.setForeground(BlueTheme.TEXT);
        card.add(title, BorderLayout.NORTH);
        JPanel rowsPanel = transparentPanel(new java.awt.GridBagLayout());
        for (int index = 0; index < rows.length; index++) {
            GridBagConstraints labelConstraints = recordConstraints(index * 2, 0, 1, 1.0, 0.0);
            labelConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
            labelConstraints.insets = new java.awt.Insets(index == 0 ? 0 : 5, 0, 1, 0);
            JLabel label = new JLabel(rows[index][0]);
            label.setFont(BlueTheme.font(Font.BOLD, 11));
            label.setForeground(BlueTheme.MUTED);
            rowsPanel.add(label, labelConstraints);
            GridBagConstraints valueConstraints = recordConstraints(index * 2 + 1, 0, 1, 1.0, 0.0);
            valueConstraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
            valueConstraints.insets = new java.awt.Insets(0, 0, 0, 0);
            JLabel value = new JLabel(html(rows[index][1]));
            value.setFont(BlueTheme.font(Font.PLAIN, 12));
            value.setForeground(BlueTheme.TEXT);
            value.setToolTipText(rows[index][1]);
            value.getAccessibleContext().setAccessibleName(rows[index][0] + ": " + rows[index][1]);
            rowsPanel.add(value, valueConstraints);
        }
        card.add(rowsPanel, BorderLayout.CENTER);
        return card;
    }

    private void addRecordCard(JPanel card, int row) {
        GridBagConstraints constraints = recordConstraints(row, 0, 1, 1.0, 0.0);
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
        recordDetails.add(card, constraints);
    }

    private static java.awt.GridBagConstraints recordConstraints(int row, int column, int width, double weightX, double weightY) {
        java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.weightx = weightX;
        constraints.weighty = weightY;
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.insets = new java.awt.Insets(3, 3, 3, 3);
        return constraints;
    }

    private static String mediaDetails(JSONObject media) {
        if (!media.optBoolean("available")) return "Unavailable";
        String hash = media.optString("sha256", "Integrity hash unavailable");
        return media.optString("mime_type", "Unknown type") + " · " + media.optLong("bytes") + " bytes · SHA-256 " + hash;
    }

    private static String html(String value) {
        String safe = value == null ? "" : breakLongTokens(value)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        safe = safe.replace("\n", "<br>");
        if (safe.length() > 72) safe = safe.replace(" · ", " ·<br>").replace(" SHA-256 ", "<br>SHA-256 ");
        return "<html><div style='width: 520px'>" + safe + "</div></html>";
    }

    /** Allows long integrity hashes and event tokens to wrap without changing copied report data. */
    private static String breakLongTokens(String value) {
        StringBuilder result = new StringBuilder(value.length() + value.length() / 16);
        int consecutive = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            result.append(current);
            if (Character.isLetterOrDigit(current)) {
                consecutive++;
                if (consecutive == 16) {
                    result.append('\u200B');
                    consecutive = 0;
                }
            } else {
                consecutive = 0;
            }
        }
        return result.toString();
    }

    private static JPanel transparentPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /** Keeps the formatted record to the viewport width, so its scroll pane never needs a horizontal bar. */
    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        private ViewportWidthPanel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? 28 : 1;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? 196 : Math.max(1, visibleRect.width - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static String formatDetails(IncidentEvidence evidence) {
        Incident incident = evidence.incident();
        return "Camera: " + incident.cameraId() + "\n"
                + "Location: " + incident.location() + "\n"
                + "Detected: " + incident.detectionTimestamp() + "\n"
                + "Incident type: " + display(incident.incidentType()) + "\n"
                + "Confidence: " + String.format(java.util.Locale.ROOT, "%.1f%%", incident.confidence() * 100.0) + "\n"
                + "Alert policy: " + incident.alertMode().displayName() + "\n"
                + "People / hands / SOS signalers: " + incident.peopleCount() + " / " + incident.handCount()
                + " / " + incident.signalerCount() + "\n"
                + "Occupancy: " + (incident.peopleCountStale() ? "stale" : incident.occupancyStatus()) + "\n"
                + "Signaler track: " + display(incident.signalerTrackId()) + "\n"
                + "Signaler bounds: " + display(incident.signalerBounds()) + "\n"
                + "Snapshot: " + ((evidence.snapshotBytes() == null || evidence.snapshotBytes().length == 0)
                        ? "not stored" : evidence.snapshotMimeType()) + "\n"
                + "Video: " + videoDescription(evidence) + "\n"
                + "Current status: " + incident.status() + "\n"
                + "Operator note: " + display(incident.operatorNotes());
    }

    private static String videoDescription(IncidentEvidence evidence) {
        Incident incident = evidence.incident();
        if (evidence.videoBytes() != null && evidence.videoBytes().length > 0) {
            return "stored in SQLite (" + evidence.videoBytes().length / 1024 + " KB)";
        }
        return incident.mediaReady() && !incident.videoPath().isBlank()
                ? "available at recorded path"
                : incident.mediaStatus();
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "not reported" : value;
    }

    private static BufferedImage placeholder(String text) {
        BufferedImage image = new BufferedImage(700, 395, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(230, 240, 250));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(BlueTheme.PRIMARY);
        graphics.setFont(BlueTheme.font(Font.BOLD, 23));
        int width = graphics.getFontMetrics().stringWidth(text);
        graphics.drawString(text, (image.getWidth() - width) / 2, image.getHeight() / 2);
        graphics.dispose();
        return image;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setColor(new Color(230, 240, 250));
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double factor = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
        int drawWidth = (int) (source.getWidth() * factor);
        int drawHeight = (int) (source.getHeight() * factor);
        graphics.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
        graphics.dispose();
        return scaled;
    }
}
