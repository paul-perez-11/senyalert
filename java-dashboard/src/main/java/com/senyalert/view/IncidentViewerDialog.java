package com.senyalert.view;

import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
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
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/**
 * Modal archive viewer. It exposes only the operator workflow fields and
 * leaves all detection and evidence attributes read-only.
 */
final class IncidentViewerDialog extends JDialog {
    @FunctionalInterface
    interface ExportAction {
        void export(long incidentId, Path destinationDirectory, ArchiveExportMode mode);
    }

    private final JLabel title = new JLabel("Incident evidence");
    private final JTextArea recordDetails = readOnlyArea();
    private final JComboBox<IncidentStatus> status = new JComboBox<>(IncidentStatus.values());
    private final JTextArea operatorNotes = new JTextArea(5, 42);
    private final JLabel snapshot = new JLabel();
    private final JLabel videoStatus = new JLabel();
    private final JButton acknowledge = new StyledButton("Acknowledge", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);
    private final JButton resolve = new StyledButton("Resolve", FontAwesomeSolid.CHECK, BlueTheme.SUCCESS);
    private final JButton save = new StyledButton("Save operator update", FontAwesomeSolid.CHECK, BlueTheme.DEEP_BLUE);
    private final JButton play = new StyledButton("Play natively", FontAwesomeSolid.PLAY, BlueTheme.DEEP_BLUE);
    private final JButton exportRecord = new StyledButton("Export all…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
    private final JButton exportMedia = new StyledButton("Export all…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
    private final JButton deleteRecord = new StyledButton("Delete record…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);
    private final JButton deleteRecordFromMedia = new StyledButton("Delete record…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);

    private IncidentEvidence evidence;
    private BiConsumer<Long, OperatorIncidentUpdate> updateAction = (ignored, update) -> { };
    private LongConsumer deleteAction = ignored -> { };
    private LongConsumer playAction = ignored -> { };
    private ExportAction exportAction = (ignored, directory, mode) -> { };

    IncidentViewerDialog(Window owner) {
        super(owner, "Incident evidence", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 600));
        setPreferredSize(new Dimension(900, 690));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(BlueTheme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));

        title.setFont(BlueTheme.font(Font.BOLD, 18));
        title.setForeground(BlueTheme.TEXT);
        root.add(title, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(BlueTheme.font(Font.BOLD, 12));
        tabs.addTab("Record data", buildRecordTab());
        tabs.addTab("Media", buildMediaTab());
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
    }

    void setActions(
            BiConsumer<Long, OperatorIncidentUpdate> updateAction,
            LongConsumer deleteAction,
            LongConsumer playAction,
            ExportAction exportAction) {
        this.updateAction = updateAction;
        this.deleteAction = deleteAction;
        this.playAction = playAction;
        this.exportAction = exportAction;
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
        JPanel panel = transparentPanel(new BorderLayout(0, 10));
        JScrollPane detailsScroll = new JScrollPane(recordDetails);
        detailsScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        panel.add(detailsScroll, BorderLayout.CENTER);
        panel.add(buildOperatorEditor(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildOperatorEditor() {
        JPanel editor = transparentPanel(new BorderLayout(0, 8));
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        JLabel heading = new JLabel("Operator workflow fields");
        heading.setFont(BlueTheme.font(Font.BOLD, 13));
        heading.setForeground(BlueTheme.TEXT);
        JLabel immutable = new JLabel("Detection, counts, confidence, IDs, timestamps, and evidence are read-only.");
        immutable.setFont(BlueTheme.font(Font.PLAIN, 10));
        immutable.setForeground(BlueTheme.MUTED);
        JPanel labels = transparentPanel(new BorderLayout());
        labels.add(heading, BorderLayout.WEST);
        labels.add(immutable, BorderLayout.EAST);
        editor.add(labels, BorderLayout.NORTH);

        JPanel fields = transparentPanel(new BorderLayout(8, 0));
        JPanel statusField = transparentPanel(new BorderLayout(0, 4));
        JLabel statusLabel = new JLabel("Status");
        statusLabel.setFont(BlueTheme.font(Font.BOLD, 11));
        statusLabel.setForeground(BlueTheme.TEXT);
        statusField.add(statusLabel, BorderLayout.NORTH);
        status.setFont(BlueTheme.font(Font.PLAIN, 12));
        statusField.add(status, BorderLayout.CENTER);
        fields.add(statusField, BorderLayout.WEST);

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
        fields.add(notesField, BorderLayout.CENTER);
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
        recordDetails.setText(formatDetails(evidence));
        recordDetails.setCaretPosition(0);
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
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a folder for the evidence export");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            exportAction.export(evidence.incident().id(), chooser.getSelectedFile().toPath(), mode);
        }
    }

    private void confirmDelete() {
        if (evidence == null) {
            return;
        }
        long incidentId = evidence.incident().id();
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete incident #" + incidentId + " from the evidence archive?\n\n"
                        + "This removes only its SQLite row and stored BLOBs. It does not delete the source snapshot or video files.",
                "Delete database record",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            deleteAction.accept(incidentId);
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

    private static JTextArea readOnlyArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setFont(BlueTheme.font(Font.PLAIN, 13));
        area.setForeground(BlueTheme.TEXT);
        area.setBackground(Color.WHITE);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return area;
    }

    private static JPanel transparentPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static String formatDetails(IncidentEvidence evidence) {
        Incident incident = evidence.incident();
        return "Camera: " + incident.cameraId() + "\n"
                + "Location: " + incident.location() + "\n"
                + "Detected: " + incident.detectionTimestamp() + "\n"
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
