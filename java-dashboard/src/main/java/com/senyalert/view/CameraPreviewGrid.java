package com.senyalert.view;

import com.senyalert.model.CameraPreviewFrame;
import com.senyalert.model.CameraSettings;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Compact Swing-only grid of the newest preview frame from each camera. */
final class CameraPreviewGrid extends RoundedPanel {
    private static final DateTimeFormatter FRAME_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final JPanel tiles = new JPanel();
    private final Map<String, CameraTile> tileByCameraId = new LinkedHashMap<>();
    private Set<String> activeCameraIds = Set.of();
    private boolean configurationReceived;

    CameraPreviewGrid() {
        super(18);
        setLayout(new BorderLayout(0, 8));
        setBackground(BlueTheme.CARD);
        setBorder(BlueTheme.cardBorder());
        // The queue is the dispatcher's primary work surface. Keep previews
        // compact and vertically scrollable rather than allowing them to take
        // half of the dispatch page at normal desktop widths.
        setMinimumSize(new Dimension(230, 150));
        setPreferredSize(new Dimension(330, 185));

        JLabel heading = new JLabel("Latest camera previews", FontIcon.of(FontAwesomeSolid.CAMERA, 14, BlueTheme.PRIMARY), JLabel.LEFT);
        heading.setForeground(BlueTheme.TEXT);
        heading.setFont(BlueTheme.font(Font.BOLD, 14));
        heading.setIconTextGap(6);
        add(heading, BorderLayout.NORTH);

        tiles.setBackground(BlueTheme.CARD);
        tiles.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        JScrollPane scroll = new JScrollPane(tiles);
        scroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        add(scroll, BorderLayout.CENTER);
        rebuildTiles();
    }

    void showConfiguredCameras(List<CameraSettings> cameras) {
        List<CameraSettings> activeCameras = cameras.stream()
                .filter(CameraSettings::enabled)
                .toList();
        Set<String> configuredCameraIds = activeCameras.stream()
                .map(CameraSettings::cameraId)
                .collect(Collectors.toSet());
        activeCameraIds = configuredCameraIds;
        configurationReceived = true;
        // Reconcile settings changes so a removed phone camera does not remain
        // visible with its last frozen frame.
        tileByCameraId.keySet().removeIf(cameraId -> !configuredCameraIds.contains(cameraId));
        for (CameraSettings camera : activeCameras) {
            CameraTile tile = tileByCameraId.computeIfAbsent(camera.cameraId(), CameraTile::new);
            tile.configure(camera.location());
        }
        rebuildTiles();
    }

    void showPreview(CameraPreviewFrame preview) {
        // A late frame from a worker just disabled in the Cameras tab must not
        // recreate a stale tile after configuration reconciliation.
        if (configurationReceived && !activeCameraIds.contains(preview.cameraId())) {
            return;
        }
        CameraTile tile = tileByCameraId.computeIfAbsent(preview.cameraId(), CameraTile::new);
        tile.showPreview(preview);
        rebuildTiles();
    }

    private void rebuildTiles() {
        tiles.removeAll();
        if (tileByCameraId.isEmpty()) {
            tiles.setLayout(new GridLayout(1, 1));
            JLabel waiting = new JLabel("Awaiting configured camera previews", SwingConstants.CENTER);
            waiting.setForeground(BlueTheme.MUTED);
            waiting.setFont(BlueTheme.font(Font.PLAIN, 13));
            tiles.add(waiting);
        } else {
            int tileCount = tileByCameraId.size();
            int columns = tileCount == 1 ? 1 : tileCount <= 4 ? 2 : 3;
            tiles.setLayout(new GridLayout(0, columns, 8, 8));
            tileByCameraId.values().forEach(tiles::add);
        }
        tiles.revalidate();
        tiles.repaint();
    }

    private static final class CameraTile extends RoundedPanel {
        private final String cameraId;
        private final JLabel cameraLabel = new JLabel();
        private final JLabel captureLabel = new JLabel("Awaiting preview");
        private final PreviewSurface previewSurface = new PreviewSurface();

        CameraTile(String cameraId) {
            super(12);
            this.cameraId = cameraId;
            setLayout(new BorderLayout(0, 5));
            setBackground(new Color(247, 251, 255));
            setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
            setPreferredSize(new Dimension(190, 112));
            setMinimumSize(new Dimension(165, 104));

            cameraLabel.setText(cameraId);
            cameraLabel.setFont(BlueTheme.font(Font.BOLD, 11));
            cameraLabel.setForeground(BlueTheme.TEXT);
            captureLabel.setFont(BlueTheme.font(Font.PLAIN, 10));
            captureLabel.setForeground(BlueTheme.MUTED);
            JPanel labels = new JPanel(new BorderLayout());
            labels.setOpaque(false);
            labels.setBorder(BorderFactory.createEmptyBorder(5, 7, 0, 7));
            labels.add(cameraLabel, BorderLayout.WEST);
            labels.add(captureLabel, BorderLayout.EAST);
            add(labels, BorderLayout.NORTH);

            previewSurface.setBorder(BorderFactory.createEmptyBorder(2, 5, 5, 5));
            add(previewSurface, BorderLayout.CENTER);
        }

        void configure(String location) {
            cameraLabel.setText(location == null || location.isBlank() ? cameraId : cameraId + "  ·  " + location);
        }

        void showPreview(CameraPreviewFrame frame) {
            previewSurface.setImage(frame.image());
            captureLabel.setText("Updated · " + FRAME_TIME.format(Instant.ofEpochMilli(frame.timestampEpochMillis())));
        }
    }

    private static final class PreviewSurface extends JPanel {
        private BufferedImage image;

        PreviewSurface() {
            setOpaque(true);
            setBackground(BlueTheme.DEEP_BLUE);
            setPreferredSize(new Dimension(178, 76));
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
                    g2.setFont(BlueTheme.font(Font.PLAIN, 13));
                    String text = "Awaiting preview";
                    int width = g2.getFontMetrics().stringWidth(text);
                    g2.drawString(text, Math.max(8, (getWidth() - width) / 2), Math.max(22, getHeight() / 2));
                    return;
                }
                double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g2.drawImage(image, x, y, width, height, null);
            } finally {
                g2.dispose();
            }
        }
    }
}
