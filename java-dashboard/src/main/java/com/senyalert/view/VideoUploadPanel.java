package com.senyalert.view;

import com.senyalert.model.UploadedVideoRequest;
import com.senyalert.model.UploadedVideoStatus;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/** Local-file picker for offline analysis by the connected Python engine. */
final class VideoUploadPanel extends JPanel {
    private final JTextField source = new JTextField();
    private final JTextField cameraId = new JTextField("UPLOADED-VIDEO");
    private final JTextField location = new JTextField("Uploaded video analysis");
    private final JLabel status = new JLabel("Choose a local video, then queue it for the connected vision engine.");
    private final JLabel analysisState = new JLabel("No analysis running");
    private final JLabel analysisDetail = new JLabel(
            "A record appears in Uploaded Evidence only after a handsign passes the current confidence gate.");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private StyledButton analyzeButton;
    private Consumer<UploadedVideoRequest> submitAction = ignored -> { };

    VideoUploadPanel() {
        setLayout(new BorderLayout());
        setBackground(BlueTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        add(buildCard(), BorderLayout.NORTH);
    }

    void setSubmitAction(Consumer<UploadedVideoRequest> submitAction) {
        this.submitAction = submitAction == null ? ignored -> { } : submitAction;
    }

    private JPanel buildCard() {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new BorderLayout(0, 12));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());

        JPanel heading = transparent(new BorderLayout(0, 3));
        JLabel title = new JLabel("Offline video analysis");
        title.setFont(BlueTheme.font(Font.BOLD, 18));
        title.setForeground(BlueTheme.TEXT);
        JLabel help = new JLabel("The local Python engine analyzes the original video with the same hand, people, confidence, and evidence pipeline. Results are saved only in Uploaded Evidence.");
        help.setFont(BlueTheme.font(Font.PLAIN, 11));
        help.setForeground(BlueTheme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(help, BorderLayout.SOUTH);
        card.add(heading, BorderLayout.NORTH);

        JPanel form = transparent(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 0, 4, 8);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;
        addLabel(form, constraints, "Video file");
        source.setEditable(false);
        source.setToolTipText("The selected file must be readable by the computer running the Python engine.");
        constraints.gridx = 1;
        constraints.weightx = 1;
        form.add(source, constraints);
        StyledButton browse = new StyledButton("Choose video…", FontAwesomeSolid.FILE_VIDEO, BlueTheme.DEEP_BLUE);
        browse.addActionListener(event -> chooseVideo());
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.insets = new Insets(4, 0, 4, 0);
        form.add(browse, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(4, 0, 4, 8);
        addLabel(form, constraints, "Analysis camera ID");
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        form.add(cameraId, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(4, 0, 4, 8);
        addLabel(form, constraints, "Location label");
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        form.add(location, constraints);
        card.add(form, BorderLayout.CENTER);

        JPanel actions = transparent(new BorderLayout(10, 0));
        status.setFont(BlueTheme.font(Font.PLAIN, 11));
        status.setForeground(BlueTheme.MUTED);
        status.getAccessibleContext().setAccessibleName("Offline analysis upload status");
        actions.add(status, BorderLayout.CENTER);
        analyzeButton = new StyledButton("Analyze video", FontAwesomeSolid.PLAY, BlueTheme.PRIMARY);
        analyzeButton.addActionListener(event -> submit());
        actions.add(analyzeButton, BorderLayout.EAST);
        JPanel footer = transparent(new BorderLayout(0, 10));
        footer.add(buildAnalysisProgress(), BorderLayout.NORTH);
        footer.add(actions, BorderLayout.SOUTH);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildAnalysisProgress() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setBackground(BlueTheme.SURFACE_TINT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        analysisState.setFont(BlueTheme.font(Font.BOLD, 12));
        analysisState.setForeground(BlueTheme.TEXT);
        panel.add(analysisState, BorderLayout.NORTH);

        progress.setValue(0);
        progress.setStringPainted(true);
        progress.setString("Waiting for a video");
        progress.setForeground(BlueTheme.PRIMARY);
        progress.setBackground(Color.WHITE);
        progress.setToolTipText("Frame progress reported by the local vision engine.");
        progress.getAccessibleContext().setAccessibleName("Offline video analysis progress");
        panel.add(progress, BorderLayout.CENTER);

        analysisDetail.setFont(BlueTheme.font(Font.PLAIN, 11));
        analysisDetail.setForeground(BlueTheme.MUTED);
        analysisDetail.getAccessibleContext().setAccessibleName("Offline analysis result detail");
        panel.add(analysisDetail, BorderLayout.SOUTH);
        return panel;
    }

    /** Receives authoritative job lifecycle updates sent by the Python engine. */
    void showAnalysisStatus(UploadedVideoStatus update) {
        if (update == null) {
            return;
        }
        String stage = update.stage() == null || update.stage().isBlank()
                ? "QUEUED" : update.stage().trim().toUpperCase(java.util.Locale.ROOT);
        String sourceName = update.sourceName() == null || update.sourceName().isBlank()
                ? "video" : update.sourceName();
        String detail = update.detail() == null ? "" : update.detail().trim();

        if (update.isFailure()) {
            analysisState.setText("Analysis failed — no Uploaded Evidence record was created");
            analysisState.setForeground(BlueTheme.DANGER);
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString("Analysis failed");
            progress.setForeground(BlueTheme.DANGER);
            analysisDetail.setText(detail.isBlank()
                    ? "The engine could not read or analyze this source. Choose another readable video and try again."
                    : detail);
            analysisDetail.setForeground(BlueTheme.DANGER);
            status.setForeground(BlueTheme.DANGER);
            status.setText("Analysis failed. See the diagnostic above, correct the source or engine issue, then try again.");
            setAnalysisActive(false);
            return;
        }

        if (update.isComplete()) {
            analysisState.setText(update.detectedIncidents() > 0
                    ? "Analysis complete — " + update.detectedIncidents() + " qualifying incident(s) found"
                    : "Analysis complete — no qualifying handsign found");
            analysisState.setForeground(update.detectedIncidents() > 0 ? BlueTheme.SUCCESS : BlueTheme.QUIET);
            progress.setIndeterminate(false);
            progress.setValue(100);
            progress.setString("Complete");
            progress.setForeground(update.detectedIncidents() > 0 ? BlueTheme.SUCCESS : BlueTheme.QUIET);
            analysisDetail.setText(update.detectedIncidents() > 0
                    ? "Detected evidence has been saved to Uploaded Evidence. " + nonBlank(detail,
                            "Refresh its Uploaded tab if it is already open.")
                    : "Uploaded Evidence remains empty because no frame completed the required handsign sequence "
                            + "and confidence gate. Review sensitivity and confidence settings. " + nonBlank(detail, ""));
            analysisDetail.setForeground(update.detectedIncidents() > 0 ? BlueTheme.SUCCESS : BlueTheme.QUIET);
            status.setForeground(update.detectedIncidents() > 0 ? BlueTheme.SUCCESS : BlueTheme.QUIET);
            status.setText(update.detectedIncidents() > 0
                    ? "Analysis completed with evidence in Uploaded Evidence."
                    : "Analysis completed with no qualifying incident to archive.");
            setAnalysisActive(false);
            return;
        }

        boolean knownProgress = update.hasKnownProgress();
        progress.setIndeterminate(!knownProgress);
        if (knownProgress) {
            // Container metadata can under-report a frame count. Reserve 100%
            // for an explicit terminal event instead of falsely implying that
            // persistence and evidence finalization have already completed.
            int displayedProgress = Math.min(99, update.progressPercent());
            progress.setValue(displayedProgress);
            progress.setString(displayedProgress + "% · " + update.processedFrames() + " / "
                    + update.totalFrames() + " frames");
        } else {
            progress.setString("Reading video metadata…");
        }
        progress.setForeground(BlueTheme.PRIMARY);
        analysisState.setForeground(BlueTheme.INFO);
        analysisState.setText(switch (stage) {
            case "SUBMITTED" -> "Waiting for the vision engine to accept " + sourceName;
            case "QUEUED" -> "Queued for analysis: " + sourceName;
            case "OPENING", "STARTING" -> "Opening video for offline analysis: " + sourceName;
            case "DETECTION" -> "Qualifying handsign detected — saving Uploaded Evidence";
            default -> "Analyzing " + sourceName;
        });
        analysisDetail.setText(nonBlank(detail,
                "The original video is unchanged. Detected incidents are isolated in Uploaded Evidence."));
        analysisDetail.setForeground(BlueTheme.MUTED);
        status.setForeground(BlueTheme.INFO);
        status.setText("Offline analysis is active. Progress is reported by the connected local engine.");
        setAnalysisActive(true);
    }

    private void setAnalysisActive(boolean active) {
        if (analyzeButton != null) {
            analyzeButton.setEnabled(!active);
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void addLabel(JPanel panel, GridBagConstraints constraints, String text) {
        JLabel label = new JLabel(text);
        label.setFont(BlueTheme.font(Font.BOLD, 11));
        label.setForeground(BlueTheme.TEXT);
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(label, constraints);
    }

    private void chooseVideo() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a video for offline SenyAlert analysis");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Video files", "mp4", "avi", "mov", "mkv", "webm", "m4v"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        source.setText(selected.toString());
        status.setForeground(BlueTheme.INFO);
        status.setText(Files.isRegularFile(selected)
                ? "Ready: " + selected.getFileName() + " will be analyzed without changing the original video."
                : "The selected file is not readable. Choose another video.");
    }

    private void submit() {
        if (source.getText().isBlank()) {
            status.setForeground(BlueTheme.DANGER);
            status.setText("Choose a local video file before starting analysis.");
            return;
        }
        final Path selected;
        try {
            selected = Path.of(source.getText()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            status.setForeground(BlueTheme.DANGER);
            status.setText("The selected video path is invalid. Choose the file again.");
            return;
        }
        if (!Files.isRegularFile(selected)) {
            status.setForeground(BlueTheme.DANGER);
            status.setText("The selected file is no longer readable. Choose the video again.");
            return;
        }
        status.setForeground(BlueTheme.INFO);
        status.setText("Sending " + selected.getFileName() + " to the engine…");
        showAnalysisStatus(new UploadedVideoStatus(
                "", "", selected.getFileName().toString(), "SUBMITTED",
                "The dashboard is waiting for the engine to accept this local analysis job.",
                0, 0, 0));
        try {
            submitAction.accept(new UploadedVideoRequest(selected, cameraId.getText(), location.getText()));
        } catch (IllegalArgumentException invalid) {
            status.setForeground(BlueTheme.DANGER);
            status.setText(invalid.getMessage());
            showAnalysisStatus(new UploadedVideoStatus(
                    "", "", selected.getFileName().toString(), "FAILED", invalid.getMessage(), 0, 0, 0));
        }
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }
}
