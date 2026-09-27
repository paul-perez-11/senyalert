package com.senyalert.view;

import com.senyalert.model.CameraPreviewFrame;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** A compact, frozen snapshot surface for Live Dispatch with an on-demand larger view. */
final class LivePreviewPanel extends RoundedPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PreviewSurface surface = new PreviewSurface("Awaiting camera snapshot");
    private final JLabel source = new JLabel("Awaiting camera snapshot");
    private final JButton expand = new StyledButton("", FontAwesomeSolid.EXPAND_ARROWS_ALT, BlueTheme.DEEP_BLUE);
    private BufferedImage snapshot;
    private JDialog expandedDialog;
    private PreviewSurface expandedSurface;
    private JLabel expandedSource;

    LivePreviewPanel() {
        super(14);
        setLayout(new BorderLayout(0, 2));
        setBackground(BlueTheme.CARD);
        setBorder(BlueTheme.cardBorder());
        setMinimumSize(new Dimension(172, 70));
        setPreferredSize(new Dimension(184, 74));
        setToolTipText("A captured camera snapshot. Use the fullscreen button to inspect it in a larger window.");
        getAccessibleContext().setAccessibleName("Camera snapshot");
        getAccessibleContext().setAccessibleDescription(
                "A compact captured snapshot for dispatch. Use the fullscreen button to open a larger inspection view.");

        expand.setFont(BlueTheme.font(Font.BOLD, 10));
        expand.setPreferredSize(new Dimension(29, 24));
        expand.setMinimumSize(new Dimension(29, 24));
        expand.setToolTipText("Open this captured snapshot in a larger inspection window.");
        expand.getAccessibleContext().setAccessibleName("Expand snapshot");
        expand.getAccessibleContext().setAccessibleDescription(
                "Open the captured camera snapshot in a larger inspection window.");
        surface.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 4));
        surface.add(expand);
        surface.setPreferredSize(new Dimension(176, 58));
        add(surface, BorderLayout.CENTER);

        source.setFont(BlueTheme.font(Font.PLAIN, 9));
        source.setForeground(BlueTheme.MUTED);
        source.setBorder(BorderFactory.createEmptyBorder(0, 6, 3, 6));
        source.setHorizontalAlignment(SwingConstants.LEFT);
        add(source, BorderLayout.SOUTH);
        expand.addActionListener(event -> openExpandedView());
    }

    void showPreview(CameraPreviewFrame preview) {
        // Engine preview events can arrive at video speed.  Dispatch intentionally
        // keeps only its first received frame, making this a still snapshot rather
        // than a moving camera feed.
        if (snapshot != null || preview == null || preview.image() == null) {
            return;
        }
        String label = preview.cameraId() == null || preview.cameraId().isBlank() ? "Camera" : preview.cameraId();
        String capturedAt = TIME.format(Instant.ofEpochMilli(preview.timestampEpochMillis()));
        showSnapshot(preview.image(), "Snapshot · " + label + " · " + capturedAt);
    }

    /** Shows the saved evidence image when an operator selects a queue incident. */
    void showIncidentSnapshot(IncidentEvidence evidence) {
        if (evidence == null || evidence.incident() == null || evidence.snapshotBytes() == null
                || evidence.snapshotBytes().length == 0) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(evidence.snapshotBytes()));
            if (image == null) {
                return;
            }
            var incident = evidence.incident();
            String camera = incident.cameraId() == null || incident.cameraId().isBlank() ? "Camera" : incident.cameraId();
            String timestamp = incident.detectionTimestamp() == null || incident.detectionTimestamp().isBlank()
                    ? "saved evidence" : incident.detectionTimestamp();
            showSnapshot(image, "Incident #" + incident.id() + " · " + camera + " · " + timestamp);
        } catch (Exception ignored) {
            // Keep the prior valid snapshot available if one saved image cannot be decoded.
        }
    }

    private void showSnapshot(BufferedImage image, String description) {
        snapshot = image;
        surface.setImage(image);
        source.setText(description);
        source.setToolTipText(description);
        updateExpandedView();
    }

    private void openExpandedView() {
        if (expandedDialog == null || !expandedDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            expandedDialog = new JDialog(owner, "SenyAlert camera snapshot", Dialog.ModalityType.MODELESS);
            expandedDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            expandedDialog.setMinimumSize(new Dimension(720, 480));
            expandedDialog.setPreferredSize(new Dimension(900, 620));
            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBackground(BlueTheme.BACKGROUND);
            root.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
            JLabel title = new JLabel("Camera snapshot", FontIcon.of(FontAwesomeSolid.CAMERA, 17, BlueTheme.PRIMARY), JLabel.LEFT);
            title.setFont(BlueTheme.font(Font.BOLD, 17));
            title.setForeground(BlueTheme.TEXT);
            root.add(title, BorderLayout.NORTH);
            expandedSurface = new PreviewSurface("No snapshot captured");
            expandedSurface.setPreferredSize(new Dimension(860, 520));
            expandedSurface.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
            root.add(expandedSurface, BorderLayout.CENTER);
            expandedSource = new JLabel();
            expandedSource.setFont(BlueTheme.font(Font.PLAIN, 11));
            expandedSource.setForeground(BlueTheme.MUTED);
            root.add(expandedSource, BorderLayout.SOUTH);
            expandedDialog.setContentPane(root);
            expandedDialog.pack();
            expandedDialog.setLocationRelativeTo(owner);
        }
        updateExpandedView();
        expandedDialog.setVisible(true);
        expandedDialog.toFront();
    }

    private void updateExpandedView() {
        if (expandedSurface == null || expandedSource == null) {
            return;
        }
        expandedSurface.setImage(snapshot);
        expandedSource.setText(source.getText());
    }

    private static final class PreviewSurface extends JPanel {
        private final String emptyLabel;
        private BufferedImage image;

        PreviewSurface(String emptyLabel) {
            this.emptyLabel = emptyLabel;
            setOpaque(true);
            setBackground(BlueTheme.DEEP_BLUE);
        }

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (image == null) {
                    g2.setColor(new Color(188, 215, 242));
                    g2.setFont(BlueTheme.font(Font.PLAIN, 12));
                    int width = g2.getFontMetrics().stringWidth(emptyLabel);
                    g2.drawString(emptyLabel, Math.max(8, (getWidth() - width) / 2), Math.max(22, getHeight() / 2));
                    return;
                }
                double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                g2.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            } finally {
                g2.dispose();
            }
        }
    }
}
