package com.senyalert.view;

import com.senyalert.model.CameraSettings;
import com.senyalert.model.EngineSettings;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Dedicated camera workspace; each tab maps one-to-one to an engine worker. */
final class CameraManagementPanel extends JPanel {
    private static final int MAX_CAMERAS = 4;

    private final JTabbedPane cameraTabs = new JTabbedPane();
    private final List<CameraSetupPanel> cameraEditors = new ArrayList<>();
    private final StyledButton addCameraButton = new StyledButton("Add camera", FontAwesomeSolid.PLUS, BlueTheme.PRIMARY);
    private final StyledButton removeCameraButton = new StyledButton("Remove selected", FontAwesomeSolid.MINUS, BlueTheme.DEEP_BLUE);
    private final StyledButton saveButton = new StyledButton("Save camera changes", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);

    private Consumer<EngineSettings> saveAction = ignored -> { };
    private EngineSettings currentSettings = EngineSettings.defaults();

    CameraManagementPanel() {
        setLayout(new BorderLayout());
        BlueTheme.setPanelBackground(this);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(BlueTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(18, 22, 24, 22));
        content.add(intro(), BorderLayout.NORTH);

        cameraTabs.setFont(BlueTheme.font(Font.BOLD, 13));
        cameraTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        cameraTabs.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        content.add(cameraTabs, BorderLayout.CENTER);
        content.add(actions(), BorderLayout.SOUTH);
        add(content, BorderLayout.CENTER);

        addCameraButton.addActionListener(event -> addCameraEditor(defaultCamera(cameraEditors.size()), true));
        removeCameraButton.addActionListener(event -> removeSelectedCamera());
        saveButton.addActionListener(event -> saveCameraChanges());
        showSettings(EngineSettings.defaults());
    }

    void setSaveAction(Consumer<EngineSettings> saveAction) {
        this.saveAction = saveAction;
    }

    void showSettings(EngineSettings settings) {
        currentSettings = settings;
        replaceCameraEditors(settings.cameras());
    }

    private JPanel intro() {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new BorderLayout(12, 0));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());

        JLabel icon = new JLabel(FontIcon.of(FontAwesomeSolid.CAMERA, 22, BlueTheme.PRIMARY));
        card.add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("Cameras");
        heading.setFont(BlueTheme.font(Font.BOLD, 22));
        heading.setForeground(BlueTheme.TEXT);
        JLabel helper = new JLabel("Each tab is one camera worker. Disable a feed without losing its setup, then tune capture and processing per camera.");
        helper.setFont(BlueTheme.font(Font.PLAIN, 13));
        helper.setForeground(BlueTheme.MUTED);
        text.add(heading);
        text.add(Box.createVerticalStrut(4));
        text.add(helper);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JPanel cameraActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        cameraActions.setOpaque(false);
        cameraActions.add(addCameraButton);
        cameraActions.add(removeCameraButton);
        panel.add(cameraActions, BorderLayout.WEST);
        panel.add(saveButton, BorderLayout.EAST);
        return panel;
    }

    private void saveCameraChanges() {
        try {
            saveAction.accept(readSettings());
        } catch (IllegalArgumentException invalidSetting) {
            JOptionPane.showMessageDialog(
                    this,
                    invalidSetting.getMessage(),
                    "Check camera setup",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private EngineSettings readSettings() {
        if (cameraEditors.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one camera before saving.");
        }
        List<CameraSettings> cameras = new ArrayList<>();
        for (CameraSetupPanel editor : cameraEditors) {
            CameraSettings camera = editor.toCameraSettings();
            if (cameras.stream().anyMatch(existing -> existing.cameraId().equalsIgnoreCase(camera.cameraId()))) {
                throw new IllegalArgumentException("Each camera ID must be unique, including letter case.");
            }
            cameras.add(camera);
        }
        CameraSettings primary = cameras.stream().filter(CameraSettings::enabled).findFirst().orElse(cameras.get(0));
        return new EngineSettings(
                currentSettings.confidenceThreshold(),
                currentSettings.preEventSeconds(),
                currentSettings.postEventSeconds(),
                currentSettings.requireThumb(),
                currentSettings.requireIndex(),
                primary.source(),
                primary.cameraId(),
                primary.location(),
                currentSettings.maxHands(),
                currentSettings.peopleDetectionEnabled(),
                currentSettings.peopleDetectionIntervalFrames(),
                currentSettings.quietAtOrAbovePeople(),
                currentSettings.audibleAlertsEnabled(),
                cameras);
    }

    private void replaceCameraEditors(List<CameraSettings> cameras) {
        cameraEditors.clear();
        cameraTabs.removeAll();
        List<CameraSettings> visibleCameras = cameras == null || cameras.isEmpty()
                ? List.of(EngineSettings.defaults().cameras().get(0))
                : cameras;
        visibleCameras.stream().limit(MAX_CAMERAS).forEach(camera -> addCameraEditor(camera, false));
        if (cameraTabs.getTabCount() > 0) {
            cameraTabs.setSelectedIndex(0);
        }
        updateCameraButtons();
    }

    private void addCameraEditor(CameraSettings camera, boolean selectNewCamera) {
        if (cameraEditors.size() >= MAX_CAMERAS) {
            JOptionPane.showMessageDialog(
                    this,
                    "SenyAlert can process up to four cameras at once.",
                    "Camera limit reached",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        CameraSetupPanel editor = new CameraSetupPanel(camera, this::refreshCameraTabTitles);
        cameraEditors.add(editor);
        JScrollPane editorScroll = new JScrollPane(editor);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);
        cameraTabs.addTab("Camera", editorScroll);
        refreshCameraTabTitles();
        if (selectNewCamera) {
            cameraTabs.setSelectedIndex(cameraTabs.getTabCount() - 1);
        }
        updateCameraButtons();
    }

    private void removeSelectedCamera() {
        if (cameraEditors.size() <= 1) {
            JOptionPane.showMessageDialog(
                    this,
                    "Keep at least one camera configuration. You can disable it instead.",
                    "One camera is required",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int selectedIndex = cameraTabs.getSelectedIndex();
        if (selectedIndex < 0) {
            return;
        }
        cameraEditors.remove(selectedIndex);
        cameraTabs.removeTabAt(selectedIndex);
        if (cameraTabs.getTabCount() > 0) {
            cameraTabs.setSelectedIndex(Math.max(0, selectedIndex - 1));
        }
        refreshCameraTabTitles();
        updateCameraButtons();
    }

    private void refreshCameraTabTitles() {
        for (int index = 0; index < cameraEditors.size(); index++) {
            CameraSetupPanel editor = cameraEditors.get(index);
            String prefix = index == 0 ? "Primary" : "Camera " + (index + 1);
            String state = editor.isCameraEnabled() ? "" : " · disabled";
            cameraTabs.setTitleAt(index, prefix + " · " + editor.cameraIdForDisplay() + state);
        }
    }

    private void updateCameraButtons() {
        addCameraButton.setEnabled(cameraEditors.size() < MAX_CAMERAS);
        removeCameraButton.setEnabled(cameraEditors.size() > 1);
    }

    private static CameraSettings defaultCamera(int existingCameraCount) {
        int number = existingCameraCount + 1;
        CameraSettings.CameraType type = CameraSettings.CameraType.LAPTOP_WEBCAM;
        return new CameraSettings(
                "0", String.format("CAM-%02d-CAMERA", number), "Unassigned Zone", true,
                CameraSettings.DEFAULT_PROCESSING_SCALE,
                type.captureWidth(), type.captureHeight(),
                type.previewWidth(), type.previewHeight(),
                type, type.aspectWidth(), type.aspectHeight());
    }

    /** Independent and deliberately explicit form rows avoid shared GridBag state/overlap. */
    private static final class CameraSetupPanel extends JPanel {
        private final JCheckBox enabled = check("Enable ingestion for this camera");
        private final JLabel status = new JLabel();
        private final JTextField source = new JTextField(34);
        private final JTextField cameraId = new JTextField(34);
        private final JTextField location = new JTextField(34);
        private final JComboBox<CameraSettings.CameraType> cameraType = new JComboBox<>(CameraSettings.CameraType.values());
        private final JTextField aspectWidth = new JTextField(5);
        private final JTextField aspectHeight = new JTextField(5);
        private final JLabel profileHint = new JLabel();
        private final JComboBox<ResolutionPreset> captureResolution = new JComboBox<>(ResolutionPreset.captureChoices());
        private final JComboBox<ScalePreset> processingScale = new JComboBox<>(ScalePreset.values());
        private final JComboBox<ResolutionPreset> previewResolution = new JComboBox<>(ResolutionPreset.previewChoices());
        private final Runnable onChanged;
        private boolean applyingProfile;

        CameraSetupPanel(CameraSettings camera, Runnable onChanged) {
            super(new BorderLayout());
            this.onChanged = onChanged;
            setBackground(BlueTheme.BACKGROUND);
            setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            setPreferredSize(new Dimension(760, 580));

            RoundedPanel card = new RoundedPanel(18);
            card.setLayout(new GridBagLayout());
            card.setBackground(BlueTheme.CARD);
            card.setBorder(BlueTheme.cardBorder());
            add(card, BorderLayout.NORTH);

            enabled.setSelected(camera.enabled());
            source.setText(camera.source());
            cameraId.setText(camera.cameraId());
            location.setText(camera.location());
            cameraType.setSelectedItem(camera.cameraType());
            aspectWidth.setText(String.valueOf(camera.aspectWidth()));
            aspectHeight.setText(String.valueOf(camera.aspectHeight()));
            chooseResolution(captureResolution, camera.captureWidth(), camera.captureHeight());
            processingScale.setSelectedItem(ScalePreset.nearest(camera.processingScale()));
            chooseResolution(previewResolution, camera.previewWidth(), camera.previewHeight());
            captureResolution.setEditable(true);
            previewResolution.setEditable(true);

            source.setToolTipText("0 = laptop webcam; uvc://0 = a raw Windows UVC/USB camera; adb://<serial> = Android screen-capture fallback.");
            cameraId.setToolTipText("Unique identifier shown in incidents and the live-camera grid.");
            location.setToolTipText("Human-readable camera location shown to dispatchers.");
            cameraType.setToolTipText("Sets layout-friendly defaults only. A Windows UVC source decides which physical phone lens is exposed.");
            aspectWidth.setToolTipText("Custom aspect numerator. Use the Custom camera type to edit this value.");
            aspectHeight.setToolTipText("Custom aspect denominator. Use the Custom camera type to edit this value.");
            captureResolution.setToolTipText("Choose a size or type a custom value such as 1080 × 1920. Sources may keep their native image size.");
            processingScale.setToolTipText("Only the vision-analysis image is downscaled; stored video and the HUD stay sharp.");
            previewResolution.setToolTipText("Choose a size or type a custom value such as 540 × 960. HUD text is rendered separately at high resolution.");

            int row = 0;
            addSection(card, row++, "Camera identity & source", FontAwesomeSolid.CAMERA);
            addFullWidth(card, row++, enabled);
            addField(card, row++, "Camera profile", cameraType);
            addField(card, row++, "Source", source);
            addField(card, row++, "Camera ID", cameraId);
            addField(card, row++, "Location", location);
            addField(card, row++, "Custom aspect (W × H)", aspectFields());
            profileHint.setFont(BlueTheme.font(Font.PLAIN, 11));
            profileHint.setForeground(BlueTheme.MUTED);
            addFullWidth(card, row++, profileHint);
            addSection(card, row++, "Capture & performance", FontAwesomeSolid.COG);
            addField(card, row++, "Capture resolution", captureResolution);
            addField(card, row++, "Vision processing scale", processingScale);
            addField(card, row++, "Video downscale / dashboard preview", previewResolution);

            JLabel guidance = new JLabel("<html><b>Source examples:</b> <code>0</code> = laptop webcam; "
                    + "<code>uvc://0</code> = actual Windows UVC/USB camera; "
                    + "<code>rtsp://...</code> or <code>https://...</code> = stream.<br>"
                    + "A phone only has a raw no-app route when Windows exposes its native USB Webcam/UVC mode. "
                    + "The Phone front/rear profiles label your demo and use portrait defaults; they cannot select a lens from the PC.<br>"
                    + "<code>adb://&lt;serial&gt;</code> is a lower-frame-rate foreground-screen fallback, not raw camera access.</html>");
            guidance.setFont(BlueTheme.font(Font.PLAIN, 11));
            guidance.setForeground(BlueTheme.MUTED);
            addFullWidth(card, row++, guidance);
            status.setHorizontalAlignment(SwingConstants.LEFT);
            status.setFont(BlueTheme.font(Font.BOLD, 12));
            addFullWidth(card, row, status);
            updateEnabledStatus();

            enabled.addActionListener(event -> {
                updateEnabledStatus();
                this.onChanged.run();
            });
            cameraType.addActionListener(event -> {
                if (!applyingProfile) {
                    applyProfileDefaults();
                    this.onChanged.run();
                }
            });
            cameraId.getDocument().addDocumentListener(changeListener(this.onChanged));
            updateProfileControls();
        }

        CameraSettings toCameraSettings() {
            CameraSettings.CameraType type = selectedCameraType();
            ResolutionPreset capture = selectedResolution(captureResolution, type.captureWidth(), type.captureHeight(), "Capture resolution");
            ResolutionPreset preview = selectedResolution(previewResolution, type.previewWidth(), type.previewHeight(), "Video downscale / dashboard preview");
            ScalePreset scale = (ScalePreset) processingScale.getSelectedItem();
            return new CameraSettings(
                    source.getText(), cameraId.getText(), location.getText(), enabled.isSelected(),
                    scale == null ? CameraSettings.DEFAULT_PROCESSING_SCALE : scale.value(),
                    capture == null ? type.captureWidth() : capture.width(),
                    capture == null ? type.captureHeight() : capture.height(),
                    preview == null ? type.previewWidth() : preview.width(),
                    preview == null ? type.previewHeight() : preview.height(),
                    type,
                    type == CameraSettings.CameraType.CUSTOM
                            ? positiveAspectPart(aspectWidth.getText(), "Custom aspect width")
                            : type.aspectWidth(),
                    type == CameraSettings.CameraType.CUSTOM
                            ? positiveAspectPart(aspectHeight.getText(), "Custom aspect height")
                            : type.aspectHeight());
        }

        boolean isCameraEnabled() {
            return enabled.isSelected();
        }

        String cameraIdForDisplay() {
            String value = cameraId.getText().trim();
            return value.isEmpty() ? "Unnamed" : value;
        }

        private void updateEnabledStatus() {
            boolean active = enabled.isSelected();
            status.setText(active
                    ? "Enabled — this worker starts or updates when you save camera changes."
                    : "Disabled — the engine will stop this worker after you save, while retaining this setup.");
            status.setForeground(active ? BlueTheme.SUCCESS : BlueTheme.MUTED);
        }

        private JPanel aspectFields() {
            JPanel fields = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0));
            fields.setOpaque(false);
            fields.add(aspectWidth);
            JLabel multiply = new JLabel("×");
            multiply.setForeground(BlueTheme.MUTED);
            fields.add(multiply);
            fields.add(aspectHeight);
            return fields;
        }

        private CameraSettings.CameraType selectedCameraType() {
            CameraSettings.CameraType selected = (CameraSettings.CameraType) cameraType.getSelectedItem();
            return selected == null ? CameraSettings.CameraType.LAPTOP_WEBCAM : selected;
        }

        private void applyProfileDefaults() {
            CameraSettings.CameraType type = selectedCameraType();
            applyingProfile = true;
            try {
                chooseResolution(captureResolution, type.captureWidth(), type.captureHeight());
                chooseResolution(previewResolution, type.previewWidth(), type.previewHeight());
                aspectWidth.setText(String.valueOf(type.aspectWidth()));
                aspectHeight.setText(String.valueOf(type.aspectHeight()));
            } finally {
                applyingProfile = false;
            }
            updateProfileControls();
        }

        private void updateProfileControls() {
            CameraSettings.CameraType type = selectedCameraType();
            boolean custom = type == CameraSettings.CameraType.CUSTOM;
            aspectWidth.setEnabled(custom);
            aspectHeight.setEnabled(custom);
            String ratio = type.aspectWidth() + " × " + type.aspectHeight();
            profileHint.setText(custom
                    ? "Custom profile — enter the source's intended aspect above. The engine preserves the actual frame aspect when it opens."
                    : "Profile default: " + ratio + ". The engine reports the actual opened frame dimensions and preserves them without stretching.");
        }

        private static int positiveAspectPart(String value, String label) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < 1 || parsed > 10_000) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(label + " must be a whole number between 1 and 10,000.");
            }
        }

        private static ResolutionPreset selectedResolution(
                JComboBox<ResolutionPreset> combo,
                int fallbackWidth,
                int fallbackHeight,
                String label) {
            Object selected = combo.getEditor().getItem();
            if (selected == null || selected.toString().isBlank()) {
                selected = combo.getSelectedItem();
            }
            if (selected instanceof ResolutionPreset preset) {
                return preset;
            }
            ResolutionPreset parsed = ResolutionPreset.parse(String.valueOf(selected));
            if (parsed == null) {
                throw new IllegalArgumentException(
                        label + " must be formatted as width × height, for example 1080 × 1920.");
            }
            if (parsed.width() < 160 || parsed.width() > 3840 || parsed.height() < 120 || parsed.height() > 2160) {
                throw new IllegalArgumentException(label + " must be between 160 × 120 and 3840 × 2160.");
            }
            return parsed;
        }

        private static DocumentListener changeListener(Runnable onChanged) {
            return new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    onChanged.run();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    onChanged.run();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    onChanged.run();
                }
            };
        }

        private static void addSection(RoundedPanel panel, int row, String text, org.kordamp.ikonli.Ikon icon) {
            JLabel label = new JLabel(text, FontIcon.of(icon, 16, BlueTheme.PRIMARY), JLabel.LEFT);
            label.setFont(BlueTheme.font(Font.BOLD, 15));
            label.setForeground(BlueTheme.TEXT);
            label.setIconTextGap(8);
            GridBagConstraints constraints = fullWidthConstraints(row);
            constraints.insets = new Insets(row == 0 ? 0 : 15, 0, 8, 0);
            panel.add(label, constraints);
        }

        private static void addField(RoundedPanel panel, int row, String labelText, java.awt.Component field) {
            JLabel label = new JLabel(labelText);
            label.setFont(BlueTheme.font(Font.PLAIN, 13));
            label.setForeground(BlueTheme.MUTED);

            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.weightx = 0.28;
            labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.fill = GridBagConstraints.NONE;
            labelConstraints.insets = new Insets(5, 0, 5, 16);
            panel.add(label, labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = row;
            fieldConstraints.weightx = 0.72;
            fieldConstraints.anchor = GridBagConstraints.WEST;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.insets = new Insets(5, 0, 5, 0);
            panel.add(field, fieldConstraints);
        }

        private static void addFullWidth(RoundedPanel panel, int row, java.awt.Component component) {
            panel.add(component, fullWidthConstraints(row));
        }

        private static GridBagConstraints fullWidthConstraints(int row) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = row;
            constraints.gridwidth = 2;
            constraints.weightx = 1;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.insets = new Insets(5, 0, 5, 0);
            return constraints;
        }

        private static JCheckBox check(String text) {
            JCheckBox checkBox = new JCheckBox(text);
            checkBox.setOpaque(false);
            checkBox.setFont(BlueTheme.font(Font.PLAIN, 13));
            checkBox.setForeground(BlueTheme.TEXT);
            return checkBox;
        }

        private static void chooseResolution(JComboBox<ResolutionPreset> combo, int width, int height) {
            ResolutionPreset requested = new ResolutionPreset(width, height);
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (requested.equals(combo.getItemAt(index))) {
                    combo.setSelectedIndex(index);
                    return;
                }
            }
            combo.addItem(requested);
            combo.setSelectedItem(requested);
        }
    }

    private record ResolutionPreset(int width, int height) {
        static ResolutionPreset[] captureChoices() {
            return new ResolutionPreset[] {
                    new ResolutionPreset(1080, 1920),
                    new ResolutionPreset(720, 1280),
                    new ResolutionPreset(1920, 1080),
                    new ResolutionPreset(1280, 720),
                    new ResolutionPreset(960, 540),
                    new ResolutionPreset(640, 480),
                    new ResolutionPreset(480, 360)
            };
        }

        static ResolutionPreset[] previewChoices() {
            return new ResolutionPreset[] {
                    new ResolutionPreset(540, 960),
                    new ResolutionPreset(360, 640),
                    new ResolutionPreset(1280, 720),
                    new ResolutionPreset(960, 540),
                    new ResolutionPreset(854, 480),
                    new ResolutionPreset(640, 360),
                    new ResolutionPreset(480, 360)
            };
        }

        @Override
        public String toString() {
            return width + " × " + height;
        }

        static ResolutionPreset parse(String value) {
            if (value == null) {
                return null;
            }
            String[] pieces = value.trim().split("\\s*[x×]\\s*", -1);
            if (pieces.length != 2) {
                return null;
            }
            try {
                return new ResolutionPreset(Integer.parseInt(pieces[0].trim()), Integer.parseInt(pieces[1].trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private record ScalePreset(double value, String label) {
        static ScalePreset[] values() {
            return new ScalePreset[] {
                    new ScalePreset(1.0, "100% — best gesture detail"),
                    new ScalePreset(0.75, "75% — balanced"),
                    new ScalePreset(0.50, "50% — lower CPU")
            };
        }

        static ScalePreset nearest(double value) {
            ScalePreset best = values()[0];
            for (ScalePreset option : values()) {
                if (Math.abs(option.value - value) < Math.abs(best.value - value)) {
                    best = option;
                }
            }
            return best;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
