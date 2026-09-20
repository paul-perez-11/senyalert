package com.senyalert.view;

import com.senyalert.model.CameraSettings;
import com.senyalert.model.EngineSettings;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Settings presentation for engine policy, gesture tuning, and people triage. */
final class SettingsPanel extends JPanel {
    private final JSlider confidence = new JSlider(50, 99, 70);
    private final JLabel confidenceValue = new JLabel();
    private final JSlider gestureSensitivity = new JSlider(50, 150, 150);
    private final JLabel gestureSensitivityValue = new JLabel();
    private final JSlider thumbTuckBonus = new JSlider(0, 30, 10);
    private final JLabel thumbTuckBonusValue = new JLabel();
    private final JSlider indexFoldBonus = new JSlider(0, 30, 10);
    private final JLabel indexFoldBonusValue = new JLabel();
    private final JSlider repeatedHandsignMinimum = new JSlider(50, 95, 50);
    private final JLabel repeatedHandsignMinimumValue = new JLabel();
    private final JSlider repeatedHandsignTarget = new JSlider(50, 100, 75);
    private final JLabel repeatedHandsignTargetValue = new JLabel();
    private final JSpinner preEvent = spinner(12, 1, 60);
    private final JSpinner postEvent = spinner(12, 1, 60);
    private final JCheckBox requireThumb = check("Require tucked thumb");
    private final JCheckBox requireIndex = check("Require folded index");
    private final JSpinner maxHands = spinner(2, 1, 8);
    private final JCheckBox peopleEnabled = check("Enable people detection");
    private final JSpinner peopleInterval = spinner(8, 1, 120);
    private final JSlider quietThreshold = new JSlider(1, 30, 4);
    private final JLabel quietThresholdValue = new JLabel();
    private final JCheckBox audibleEnabled = check("Allow audible alarms below the quiet threshold");
    private final StyledButton saveButton = new StyledButton("Save & Apply", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);
    private final StyledButton pauseButton = new StyledButton("Pause Engine", FontAwesomeSolid.PAUSE, BlueTheme.DEEP_BLUE);
    private final StyledButton restartButton = new StyledButton("Restart Engine", FontAwesomeSolid.SYNC, BlueTheme.DEEP_BLUE);

    private Consumer<EngineSettings> saveAction = ignored -> { };
    private Runnable pauseAction = () -> { };
    private Runnable restartAction = () -> { };
    private List<CameraSettings> configuredCameras = EngineSettings.defaults().cameras();

    SettingsPanel() {
        setLayout(new BorderLayout());
        BlueTheme.setPanelBackground(this);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(BlueTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));

        GridBagConstraints layout = new GridBagConstraints();
        layout.gridx = 0;
        layout.gridy = 0;
        layout.gridwidth = 2;
        layout.weightx = 1;
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.anchor = GridBagConstraints.NORTHWEST;
        layout.insets = new Insets(0, 0, 9, 0);
        content.add(intro(), layout);

        layout.gridy = 1;
        layout.gridwidth = 1;
        layout.weightx = 0.5;
        layout.fill = GridBagConstraints.BOTH;
        layout.insets = new Insets(0, 0, 0, 6);
        content.add(detectionCard(), layout);
        layout.gridx = 1;
        layout.insets = new Insets(0, 6, 0, 0);
        content.add(triageCard(), layout);

        layout.gridx = 0;
        layout.gridy = 2;
        layout.gridwidth = 2;
        layout.weightx = 1;
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.insets = new Insets(10, 0, 0, 0);
        content.add(actions(), layout);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        confidence.setPaintTicks(true);
        confidence.setMajorTickSpacing(10);
        confidence.addChangeListener(event -> confidenceValue.setText(confidence.getValue() + "%"));
        confidence.setToolTipText("Minimum measured gesture quality required before an incident can be created.");
        gestureSensitivity.setPaintTicks(true);
        gestureSensitivity.setMajorTickSpacing(25);
        gestureSensitivity.setToolTipText(
                "100% uses the calibrated gesture rule. 150% allows up to 50% more geometry tolerance; confidence remains a separate incident gate.");
        gestureSensitivity.addChangeListener(event -> updateGestureSensitivityLabel());
        thumbTuckBonus.setPaintTicks(true);
        thumbTuckBonus.setMajorTickSpacing(10);
        thumbTuckBonus.setToolTipText("Extra measured-confidence credit when a required thumb is visibly tucked.");
        thumbTuckBonus.addChangeListener(event -> updateConfidenceTuningLabels());
        indexFoldBonus.setPaintTicks(true);
        indexFoldBonus.setMajorTickSpacing(10);
        indexFoldBonus.setToolTipText("Extra measured-confidence credit when a required index finger is visibly folded.");
        indexFoldBonus.addChangeListener(event -> updateConfidenceTuningLabels());
        repeatedHandsignMinimum.setPaintTicks(true);
        repeatedHandsignMinimum.setMajorTickSpacing(10);
        repeatedHandsignMinimum.setToolTipText("Minimum raw confidence a repeated handsign must reach before it can escalate.");
        repeatedHandsignMinimum.addChangeListener(event -> {
            repeatedHandsignTarget.setMinimum(repeatedHandsignMinimum.getValue());
            updateConfidenceTuningLabels();
        });
        repeatedHandsignTarget.setPaintTicks(true);
        repeatedHandsignTarget.setMajorTickSpacing(10);
        repeatedHandsignTarget.setToolTipText("Confidence target used by a qualified repeated-handsign escalation.");
        repeatedHandsignTarget.addChangeListener(event -> updateConfidenceTuningLabels());
        quietThreshold.addChangeListener(event -> quietThresholdValue.setText(quietThreshold.getValue() + " people"));
        peopleEnabled.addActionListener(event -> updatePeopleControlState());
        requireThumb.addActionListener(event -> updateFingerBonusControlState());
        requireIndex.addActionListener(event -> updateFingerBonusControlState());
        saveButton.addActionListener(event -> {
            try {
                saveAction.accept(readSettings());
            } catch (IllegalArgumentException invalidSetting) {
                JOptionPane.showMessageDialog(
                        this,
                        invalidSetting.getMessage(),
                        "Check camera setup",
                        JOptionPane.WARNING_MESSAGE);
            }
        });
        pauseButton.addActionListener(event -> pauseAction.run());
        restartButton.setToolTipText("Reconnect enabled camera workers without deleting evidence or changing saved settings.");
        restartButton.addActionListener(event -> confirmEngineRestart());
        showSettings(EngineSettings.defaults());
    }

    void setActions(Consumer<EngineSettings> saveAction, Runnable pauseAction, Runnable restartAction) {
        this.saveAction = saveAction;
        this.pauseAction = pauseAction;
        this.restartAction = restartAction;
    }

    void showSettings(EngineSettings settings) {
        confidence.setValue((int) Math.round(settings.confidenceThreshold() * 100));
        gestureSensitivity.setValue((int) Math.round(settings.gestureSensitivity() * 100));
        thumbTuckBonus.setValue((int) Math.round(settings.thumbTuckConfidenceBonus() * 100));
        indexFoldBonus.setValue((int) Math.round(settings.indexFoldConfidenceBonus() * 100));
        repeatedHandsignMinimum.setValue((int) Math.round(settings.repeatedHandsignMinConfidence() * 100));
        repeatedHandsignTarget.setMinimum(repeatedHandsignMinimum.getValue());
        repeatedHandsignTarget.setValue((int) Math.round(settings.repeatedHandsignConfidenceTarget() * 100));
        preEvent.setValue(settings.preEventSeconds());
        postEvent.setValue(settings.postEventSeconds());
        requireThumb.setSelected(settings.requireThumb());
        requireIndex.setSelected(settings.requireIndex());
        configuredCameras = List.copyOf(settings.cameras());
        maxHands.setValue(settings.maxHands());
        peopleEnabled.setSelected(settings.peopleDetectionEnabled());
        peopleInterval.setValue(settings.peopleDetectionIntervalFrames());
        quietThreshold.setValue(settings.quietAtOrAbovePeople());
        audibleEnabled.setSelected(settings.audibleAlertsEnabled());
        confidenceValue.setText(confidence.getValue() + "%");
        updateGestureSensitivityLabel();
        updateConfidenceTuningLabels();
        updateFingerBonusControlState();
        quietThresholdValue.setText(quietThreshold.getValue() + " people");
        updatePeopleControlState();
    }

    void setPaused(boolean paused) {
        pauseButton.setText(paused ? "Resume Engine" : "Pause Engine");
        pauseButton.setIcon(FontIcon.of(paused ? FontAwesomeSolid.PLAY : FontAwesomeSolid.PAUSE, 15, java.awt.Color.WHITE));
    }

    private JPanel intro() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel heading = new JLabel("Engine & triage settings");
        heading.setFont(BlueTheme.font(Font.BOLD, 18));
        heading.setForeground(BlueTheme.TEXT);
        JLabel subheading = new JLabel("Settings are saved locally, then sent to the connected vision engine.");
        subheading.setFont(BlueTheme.font(Font.PLAIN, 12));
        subheading.setForeground(BlueTheme.MUTED);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new javax.swing.BoxLayout(labels, javax.swing.BoxLayout.Y_AXIS));
        labels.add(heading);
        labels.add(subheading);
        panel.add(labels, BorderLayout.WEST);
        return panel;
    }

    private RoundedPanel detectionCard() {
        RoundedPanel card = card();
        GridBagConstraints constraints = constraints();
        addTitle(card, constraints, "Gesture detection", FontAwesomeSolid.HAND_PAPER);
        addRow(card, constraints, "Confidence threshold", sliderWithValue(confidence, confidenceValue));
        addRow(card, constraints, "Gesture sensitivity", sliderWithValue(gestureSensitivity, gestureSensitivityValue));
        addRow(card, constraints, "Tucked-thumb confidence bonus", sliderWithValue(thumbTuckBonus, thumbTuckBonusValue));
        addRow(card, constraints, "Folded-index confidence bonus", sliderWithValue(indexFoldBonus, indexFoldBonusValue));
        addRow(card, constraints, "Repeated handsign minimum", sliderWithValue(repeatedHandsignMinimum, repeatedHandsignMinimumValue));
        addRow(card, constraints, "Repeated handsign target", sliderWithValue(repeatedHandsignTarget, repeatedHandsignTargetValue));
        addRow(card, constraints, "Pre-event recording", preEvent);
        addRow(card, constraints, "Post-event recording", postEvent);
        addRow(card, constraints, "Maximum tracked hands", maxHands);
        addRow(card, constraints, "Finger checks", requireThumb);
        addRow(card, constraints, "", requireIndex);
        return card;
    }

    private RoundedPanel triageCard() {
        RoundedPanel card = card();
        GridBagConstraints constraints = constraints();
        addTitle(card, constraints, "People-aware triage", FontAwesomeSolid.USERS);
        addRow(card, constraints, "", peopleEnabled);
        addRow(card, constraints, "Detection interval (frames)", peopleInterval);
        addRow(card, constraints, "Quiet at or above", sliderWithValue(quietThreshold, quietThresholdValue));
        addRow(card, constraints, "", audibleEnabled);
        return card;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        panel.add(saveButton);
        panel.add(pauseButton);
        panel.add(restartButton);
        return panel;
    }

    private void confirmEngineRestart() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restarting reconnects every enabled camera worker and clears incomplete hand-sign sequences.\n"
                        + "Saved settings and existing evidence are retained. Unsaved changes will not be applied.\n\n"
                        + "Restart the vision engine now?",
                "Restart Engine",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            restartAction.run();
        }
    }

    private EngineSettings readSettings() {
        if (configuredCameras.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one camera from the Cameras tab before saving.");
        }
        CameraSettings primary = configuredCameras.stream()
                .filter(CameraSettings::enabled)
                .findFirst()
                .orElse(configuredCameras.get(0));
        return new EngineSettings(
                confidence.getValue() / 100.0,
                gestureSensitivity.getValue() / 100.0,
                thumbTuckBonus.getValue() / 100.0,
                indexFoldBonus.getValue() / 100.0,
                repeatedHandsignMinimum.getValue() / 100.0,
                repeatedHandsignTarget.getValue() / 100.0,
                (Integer) preEvent.getValue(),
                (Integer) postEvent.getValue(),
                requireThumb.isSelected(),
                requireIndex.isSelected(),
                primary.source(),
                primary.cameraId(),
                primary.location(),
                (Integer) maxHands.getValue(),
                peopleEnabled.isSelected(),
                (Integer) peopleInterval.getValue(),
                quietThreshold.getValue(),
                audibleEnabled.isSelected(),
                configuredCameras);
    }

    private void updatePeopleControlState() {
        boolean enabled = peopleEnabled.isSelected();
        peopleInterval.setEnabled(enabled);
        quietThreshold.setEnabled(enabled);
        quietThresholdValue.setEnabled(enabled);
    }

    private void updateGestureSensitivityLabel() {
        int value = gestureSensitivity.getValue();
        String description = value == 100 ? "calibrated" : value > 100 ? "more responsive" : "more strict";
        gestureSensitivityValue.setText(value + "% — " + description);
    }

    private void updateConfidenceTuningLabels() {
        thumbTuckBonusValue.setText(thumbTuckBonus.getValue() + "%");
        indexFoldBonusValue.setText(indexFoldBonus.getValue() + "%");
        repeatedHandsignMinimumValue.setText(repeatedHandsignMinimum.getValue() + "%");
        repeatedHandsignTargetValue.setText(repeatedHandsignTarget.getValue() + "%");
    }

    /** Preserve the configured value, but do not offer an inapplicable credit control. */
    private void updateFingerBonusControlState() {
        boolean thumbRequired = requireThumb.isSelected();
        thumbTuckBonus.setEnabled(thumbRequired);
        thumbTuckBonusValue.setEnabled(thumbRequired);
        thumbTuckBonusValue.setText(thumbRequired ? thumbTuckBonus.getValue() + "%" : "Not used");

        boolean indexRequired = requireIndex.isSelected();
        indexFoldBonus.setEnabled(indexRequired);
        indexFoldBonusValue.setEnabled(indexRequired);
        indexFoldBonusValue.setText(indexRequired ? indexFoldBonus.getValue() + "%" : "Not used");
    }

    private static RoundedPanel card() {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new GridBagLayout());
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());
        card.setAlignmentX(LEFT_ALIGNMENT);
        return card;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 0, 2, 8);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        return constraints;
    }

    private static void addTitle(JPanel panel, GridBagConstraints constraints, String text, org.kordamp.ikonli.Ikon icon) {
        JLabel title = new JLabel(text, FontIcon.of(icon, 17, BlueTheme.PRIMARY), JLabel.LEFT);
        title.setFont(BlueTheme.font(Font.BOLD, 16));
        title.setForeground(BlueTheme.TEXT);
        title.setIconTextGap(8);
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 0, 7, 0);
        panel.add(title, constraints);
        constraints.gridwidth = 1;
        constraints.gridy = 1;
        constraints.insets = new Insets(2, 0, 2, 8);
    }

    private static void addRow(JPanel panel, GridBagConstraints constraints, String label, java.awt.Component component) {
        constraints.gridx = 0;
        constraints.weightx = 0.34;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(BlueTheme.font(Font.PLAIN, 12));
        labelComponent.setForeground(BlueTheme.MUTED);
        panel.add(labelComponent, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0.66;
        panel.add(component, constraints);
        constraints.gridy++;
    }

    private static JSpinner spinner(int initial, int min, int max) {
        return new JSpinner(new SpinnerNumberModel(initial, min, max, 1));
    }

    private static JPanel sliderWithValue(JSlider slider, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(7, 0));
        panel.setOpaque(false);
        value.setFont(BlueTheme.font(Font.BOLD, 12));
        value.setForeground(BlueTheme.PRIMARY.darker());
        panel.add(slider, BorderLayout.CENTER);
        panel.add(value, BorderLayout.EAST);
        return panel;
    }

    private static JCheckBox check(String text) {
        JCheckBox checkBox = new JCheckBox(text);
        checkBox.setOpaque(false);
        checkBox.setFont(BlueTheme.font(Font.PLAIN, 12));
        checkBox.setForeground(BlueTheme.TEXT);
        return checkBox;
    }

}
