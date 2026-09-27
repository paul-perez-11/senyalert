package com.senyalert.view;

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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/** Detail-only presentation component. It delegates every state-changing action. */
final class EvidencePanel extends RoundedPanel {
    private final JLabel preview = new JLabel();
    private final JLabel title = new JLabel("Select an incident");
    private final JTextArea metadata = new JTextArea();
    private final JTextArea operatorNotes = new JTextArea(3, 28);
    private final JLabel operatorStatus = new JLabel("Status: —");
    private final JButton acknowledge = new StyledButton("Acknowledge", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);
    private final JButton resolve = new StyledButton("Resolve", FontAwesomeSolid.CHECK, BlueTheme.SUCCESS);
    private final JButton saveOperatorRecord = new StyledButton("Save note", FontAwesomeSolid.CHECK, BlueTheme.DEEP_BLUE);
    private final JButton play = new StyledButton("Play Native", FontAwesomeSolid.PLAY, BlueTheme.DEEP_BLUE);
    private final JButton export = new StyledButton("Export Video", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
    private final JButton deleteRecord = new StyledButton("Delete record", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);
    private final JPanel actionBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    private final JPanel dispatchActionsHost = new JPanel(new BorderLayout());
    private final JPanel bottomControls = new JPanel(new BorderLayout(0, 6));
    private final JPanel workflowButtons = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
    private final JPanel evidenceButtons = new JPanel(new java.awt.GridLayout(1, 3, 4, 0));
    private java.util.function.Predicate<com.senyalert.security.Permission> allowed = ignored -> false;
    void setPermissions(java.util.function.Predicate<com.senyalert.security.Permission> allowed) { this.allowed = allowed; }
    private IncidentEvidence currentEvidence;
    private JPanel operatorEditor;
    private boolean dispatchWorkflowControlsVisible = true;

    private BiConsumer<Long, OperatorIncidentUpdate> operatorUpdateAction = (ignored, update) -> { };
    private BiConsumer<Long, MediaDeletionOptions> deleteAction = (ignored, options) -> { };
    private LongConsumer playAction = ignored -> { };
    private BiConsumer<Long, Path> exportAction = (ignored, path) -> { };

    EvidencePanel() {
        super(18);
        setLayout(new BorderLayout(8, 8));
        setBackground(BlueTheme.CARD);
        setBorder(BlueTheme.cardBorder());

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        title.setFont(BlueTheme.font(Font.BOLD, 16));
        title.setForeground(BlueTheme.TEXT);
        heading.add(title, BorderLayout.WEST);
        add(heading, BorderLayout.NORTH);

        preview.setHorizontalAlignment(JLabel.CENTER);
        preview.setVerticalAlignment(JLabel.CENTER);
        preview.setOpaque(true);
        preview.setBackground(new Color(229, 239, 249));
        preview.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        preview.setPreferredSize(new Dimension(280, 158));

        metadata.setEditable(false);
        metadata.setFocusable(false);
        metadata.setFont(BlueTheme.font(Font.PLAIN, 13));
        metadata.setForeground(BlueTheme.TEXT);
        metadata.setLineWrap(true);
        metadata.setWrapStyleWord(true);
        metadata.setBackground(BlueTheme.CARD);
        metadata.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        operatorNotes.setFont(BlueTheme.font(Font.PLAIN, 12));
        operatorNotes.setForeground(BlueTheme.TEXT);
        operatorNotes.setLineWrap(true);
        operatorNotes.setWrapStyleWord(true);
        operatorNotes.setBackground(Color.WHITE);
        operatorNotes.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

        // This panel sits beside the live queue.  Keep its saved snapshot and
        // evidence facts in one vertical column so the queue retains width.
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(preview, BorderLayout.NORTH);
        JPanel workflow = new JPanel(new BorderLayout(0, 6)); workflow.setOpaque(false);
        JScrollPane detailsScroll = new JScrollPane(metadata);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailsScroll.setOpaque(false);
        detailsScroll.getViewport().setOpaque(false);
        detailsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScroll.getVerticalScrollBar().setUnitIncrement(16);
        workflow.add(detailsScroll, BorderLayout.CENTER);
        operatorEditor = buildOperatorEditor();
        workflow.add(operatorEditor, BorderLayout.SOUTH);
        content.add(workflow, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        actionBar.setOpaque(false);
        dispatchActionsHost.setOpaque(false);
        bottomControls.setOpaque(false);
        workflowButtons.setOpaque(false);
        workflowButtons.add(acknowledge);
        workflowButtons.add(resolve);
        workflowButtons.add(saveOperatorRecord);
        evidenceButtons.setOpaque(false);
        evidenceButtons.add(play);
        evidenceButtons.add(export);
        evidenceButtons.add(deleteRecord);
        evidenceButtons.setPreferredSize(new Dimension(294, 29));
        compactEvidenceButton(play);
        compactEvidenceButton(export);
        compactEvidenceButton(deleteRecord);
        rebuildActionBar();
        bottomControls.add(dispatchActionsHost, BorderLayout.NORTH);
        bottomControls.add(actionBar, BorderLayout.SOUTH);
        add(bottomControls, BorderLayout.SOUTH);

        acknowledge.addActionListener(event -> saveOperatorUpdate(IncidentStatus.ACKNOWLEDGED));
        resolve.addActionListener(event -> saveOperatorUpdate(IncidentStatus.RESOLVED));
        saveOperatorRecord.addActionListener(event -> saveOperatorUpdate(currentStatus()));
        play.addActionListener(event -> withCurrent(playAction));
        export.addActionListener(event -> chooseExport());
        deleteRecord.addActionListener(event -> confirmDelete());
        export.setToolTipText("Exports the video and an adjacent folder containing JSON and plaintext record data. This action is audited.");
        showEmptyState();
    }

    /**
     * Live Dispatch keeps acknowledgement, resolution, and notes directly beside
     * its queue. Evidence Archive continues to expose its own operator dialog.
     */
    void setDispatchWorkflowControlsVisible(boolean visible) {
        dispatchWorkflowControlsVisible = visible;
        if (operatorEditor != null) {
            operatorEditor.setVisible(visible);
        }
        rebuildActionBar();
        revalidate();
        repaint();
    }

    /** Places the live acknowledgement, resolution, and note controls beside saved evidence. */
    void setLiveDispatchActions(DispatchActionPanel dispatchActions) {
        dispatchActionsHost.removeAll();
        if (dispatchActions != null) {
            dispatchActionsHost.add(dispatchActions, BorderLayout.CENTER);
        }
        dispatchActionsHost.revalidate();
        dispatchActionsHost.repaint();
    }

    void setActions(
            BiConsumer<Long, OperatorIncidentUpdate> operatorUpdateAction,
            BiConsumer<Long, MediaDeletionOptions> deleteAction,
            LongConsumer playAction,
            BiConsumer<Long, Path> exportAction) {
        this.operatorUpdateAction = operatorUpdateAction;
        this.deleteAction = deleteAction;
        this.playAction = playAction;
        this.exportAction = exportAction;
    }

    void showEvidence(IncidentEvidence evidence) {
        currentEvidence = evidence;
        Incident incident = evidence.incident();
        title.setText("Incident #" + incident.id() + " evidence");
        metadata.setText(formatMetadata(incident, evidence));
        setPreview(evidence.snapshotBytes());
        showOperatorControls(incident);
    }

    /** Applies an asynchronously persisted safe operator-field update to the currently viewed evidence. */
    void showOperatorRecordUpdated(Incident updated) {
        if (currentEvidence == null || currentEvidence.incident().id() != updated.id()) {
            return;
        }
        currentEvidence = new IncidentEvidence(
                updated,
                currentEvidence.snapshotBytes(),
                currentEvidence.snapshotMimeType(),
                currentEvidence.videoBytes(),
                currentEvidence.videoMimeType());
        title.setText("Incident #" + updated.id() + " evidence");
        metadata.setText(formatMetadata(updated, currentEvidence));
        showOperatorControls(updated);
    }

    void clearIfViewing(long incidentId) {
        if (currentEvidence != null && currentEvidence.incident().id() == incidentId) {
            showEmptyState();
        }
    }

    private void showEmptyState() {
        currentEvidence = null;
        title.setText("Select an incident");
        metadata.setText("Choose an incident to review its direct snapshot preview, signaler metadata, and video evidence status.");
        preview.setIcon(new ImageIcon(placeholderImage("No incident selected")));
        operatorStatus.setText("Status: —");
        operatorNotes.setText("");
        operatorNotes.setEditable(false);
        acknowledge.setEnabled(false);
        resolve.setEnabled(false);
        saveOperatorRecord.setEnabled(false);
        play.setEnabled(false);
        export.setEnabled(false);
        deleteRecord.setEnabled(false);
    }

    private void rebuildActionBar() {
        actionBar.removeAll();
        if (dispatchWorkflowControlsVisible) {
            actionBar.add(workflowButtons);
        }
        actionBar.add(evidenceButtons);
    }

    private static void compactEvidenceButton(JButton button) {
        button.setFont(BlueTheme.font(Font.BOLD, 10));
        button.setPreferredSize(new Dimension(94, 28));
    }

    private JPanel buildOperatorEditor() {
        JPanel editor = new JPanel(new BorderLayout(0, 5));
        editor.setOpaque(false);
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)));

        JLabel heading = new JLabel("Operator workflow record (editable)");
        heading.setFont(BlueTheme.font(Font.BOLD, 12));
        heading.setForeground(BlueTheme.TEXT);
        operatorStatus.setFont(BlueTheme.font(Font.BOLD, 11));
        operatorStatus.setForeground(BlueTheme.PRIMARY.darker());
        JPanel labels = new JPanel(new BorderLayout());
        labels.setOpaque(false);
        labels.add(heading, BorderLayout.WEST);
        labels.add(operatorStatus, BorderLayout.EAST);
        editor.add(labels, BorderLayout.NORTH);

        JScrollPane notesScroll = new JScrollPane(operatorNotes);
        notesScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        notesScroll.setPreferredSize(new Dimension(260, 64));
        editor.add(notesScroll, BorderLayout.CENTER);
        JLabel help = new JLabel("Only status and this operator note can be changed. Detection and evidence remain read-only.");
        help.setFont(BlueTheme.font(Font.PLAIN, 10));
        help.setForeground(BlueTheme.MUTED);
        editor.add(help, BorderLayout.SOUTH);
        return editor;
    }

    private void showOperatorControls(Incident incident) {
        operatorStatus.setText("Status: " + incident.status().name());
        operatorNotes.setText(incident.operatorNotes());
        operatorNotes.setCaretPosition(0);
        operatorNotes.setEditable(allowed.test(com.senyalert.security.Permission.EDIT_NOTES));
        boolean completed = incident.status() == IncidentStatus.RESOLVED;
        acknowledge.setEnabled(incident.status() == IncidentStatus.PENDING && allowed.test(com.senyalert.security.Permission.ACKNOWLEDGE_INCIDENTS));
        resolve.setEnabled(!completed && allowed.test(com.senyalert.security.Permission.RESOLVE_INCIDENTS));
        saveOperatorRecord.setEnabled(allowed.test(com.senyalert.security.Permission.EDIT_NOTES));
        play.setEnabled(incident.mediaReady());
        export.setEnabled(incident.mediaReady() && allowed.test(com.senyalert.security.Permission.EXPORT_EVIDENCE));
        deleteRecord.setEnabled(allowed.test(com.senyalert.security.Permission.DELETE_RECORDS));
    }

    private IncidentStatus currentStatus() {
        return currentEvidence == null ? IncidentStatus.PENDING : currentEvidence.incident().status();
    }

    private void saveOperatorUpdate(IncidentStatus status) {
        if (currentEvidence == null) {
            return;
        }
        try {
            operatorUpdateAction.accept(
                    currentEvidence.incident().id(),
                    new OperatorIncidentUpdate(status, operatorNotes.getText()));
        } catch (IllegalArgumentException invalidRecord) {
            JOptionPane.showMessageDialog(this, invalidRecord.getMessage(), "Check operator note", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void confirmDelete() {
        if (currentEvidence == null) {
            return;
        }
        long incidentId = currentEvidence.incident().id();
        javax.swing.JCheckBox deleteSnapshot = new javax.swing.JCheckBox("Delete the associated image snapshot");
        javax.swing.JCheckBox deleteVideo = new javax.swing.JCheckBox("Delete the associated video clip");
        JPanel confirmation = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        confirmation.add(new JLabel("Delete incident #" + incidentId + " from the dashboard database?"));
        confirmation.add(new JLabel("Optional source-media cleanup (unchecked files remain on disk):"));
        confirmation.add(deleteSnapshot);
        confirmation.add(deleteVideo);
        int choice = JOptionPane.showConfirmDialog(this, confirmation, "Delete database record",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            deleteAction.accept(incidentId,
                    new MediaDeletionOptions(deleteSnapshot.isSelected(), deleteVideo.isSelected()));
        }
    }

    private void setPreview(byte[] snapshotBytes) {
        BufferedImage image = null;
        if (snapshotBytes != null && snapshotBytes.length > 0) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(snapshotBytes));
            } catch (Exception ignored) {
                // The remaining details are still available even if one image is malformed.
            }
        }
        preview.setIcon(new ImageIcon(scale(image == null ? placeholderImage("Snapshot not delivered") : image, 280, 160)));
    }

    private static String formatMetadata(Incident incident, IncidentEvidence evidence) {
        String signaler = incident.signalerTrackId().isBlank() ? "not reported" : incident.signalerTrackId();
        String bounds = incident.signalerBounds().isBlank() ? "not reported" : incident.signalerBounds();
        String snapshot = evidence.snapshotBytes() == null || evidence.snapshotBytes().length == 0
                ? "No stored snapshot" : evidence.snapshotMimeType();
        String video = evidence.videoBytes() != null && evidence.videoBytes().length > 0
                ? "Stored in SQLite (" + evidence.videoBytes().length / 1024 + " KB)"
                : (incident.mediaReady() && !incident.videoPath().isBlank()
                        ? "Path fallback: " + incident.videoPath()
                        : "Media status: " + incident.mediaStatus());
        return "Camera: " + incident.cameraId() + "\n"
                + "Location: " + incident.location() + "\n"
                + "Detected: " + incident.detectionTimestamp() + "\n"
                + "Incident type: " + (incident.incidentType().isBlank() ? "SOS handsign" : incident.incidentType()) + "\n"
                + "Confidence: " + String.format("%.1f%%", incident.confidence() * 100) + "\n"
                + "Alert policy: " + incident.alertMode().displayName() + "\n"
                + "People / hands / SOS signalers: " + incident.peopleCount() + " / " + incident.handCount()
                + " / " + incident.signalerCount() + "\n"
                + "Occupancy freshness: " + (incident.peopleCountStale() ? "stale" : incident.occupancyStatus()) + "\n"
                + "Signaler track: " + signaler + "\n"
                + "Signaler bounds: " + bounds + "\n"
                + "Snapshot: " + snapshot + "\n"
                + "Video: " + video + "\n"
                + "Status: " + incident.status() + "\n"
                + (incident.operatorNotes().isBlank() ? "" : "Operator note: " + incident.operatorNotes());
    }

    private void chooseExport() {
        if (currentEvidence == null) {
            return;
        }
        NativeFileDialogs.saveFile(this,
                "Save video; a JSON and plaintext record-data folder is also created",
                com.senyalert.service.ExportDefaults.Kind.EVIDENCE,
                "SenyAlert-incident-" + currentEvidence.incident().id() + ".mp4",
                java.util.List.of("mp4", "avi", "mkv", "mov", "wmv", "webm"))
                .ifPresent(destination -> exportAction.accept(currentEvidence.incident().id(), destination));
    }

    private void withCurrent(LongConsumer action) {
        if (currentEvidence != null) {
            action.accept(currentEvidence.incident().id());
        }
    }

    private static BufferedImage placeholderImage(String text) {
        BufferedImage image = new BufferedImage(780, 450, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(226, 239, 250));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(BlueTheme.PRIMARY);
        graphics.setFont(BlueTheme.font(Font.BOLD, 27));
        int width = graphics.getFontMetrics().stringWidth(text);
        graphics.drawString(text, (image.getWidth() - width) / 2, image.getHeight() / 2);
        graphics.dispose();
        return image;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
        int drawWidth = (int) (source.getWidth() * scale);
        int drawHeight = (int) (source.getHeight() * scale);
        int x = (width - drawWidth) / 2;
        int y = (height - drawHeight) / 2;
        graphics.setColor(new Color(229, 239, 249));
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(source, x, y, drawWidth, drawHeight, null);
        graphics.dispose();
        return scaled;
    }
}
