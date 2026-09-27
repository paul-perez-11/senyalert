package com.senyalert.security;

import com.senyalert.service.ExportDefaults;
import com.senyalert.service.ExportSettings;
import com.senyalert.service.SettingsStore;
import com.senyalert.view.ui.NativeFileDialogs;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/** Operator-controlled defaults for export dialogs; never changes live data locations. */
public final class ExportSettingsPanel extends JPanel {
    private final SecurityContext context;
    private final SettingsStore settingsStore;
    private final JTextField evidenceDirectory = pathField("Folder preselected for evidence bundles, media, and record-data exports.");
    private final JTextField auditDirectory = pathField("Folder preselected for audit CSV exports.");
    private final JTextField configurationDirectory = pathField("Folder preselected for camera and engine configuration exports.");
    private final JTextField benchmarkDirectory = pathField("Folder preselected for new benchmarking result folders.");
    private final JLabel status = new JLabel(" ");
    private final JButton save = SecurityUi.button("Save export settings", "Save the displayed default folders. The exact folder paths are not stored in the audit log.");
    private final JButton reload = SecurityUi.button("Reload", "Reload the saved default export folders without changing them.");
    private final List<JButton> browseButtons = new ArrayList<>();
    private ExportSettings displayed;

    public ExportSettingsPanel(SecurityContext context, SettingsStore settingsStore) {
        super(new BorderLayout(10, 10));
        this.context = context;
        this.settingsStore = settingsStore;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel heading = new JLabel("<html><h2>Export settings</h2>Choose default folders for files you explicitly export. "
                + "These preferences never move or alter SenyAlert databases, source media, or camera settings.</html>");
        add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Default folders"),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        addPathRow(form, 0, "Evidence exports", evidenceDirectory, ExportDefaults.Kind.EVIDENCE);
        addPathRow(form, 1, "Audit CSV exports", auditDirectory, ExportDefaults.Kind.AUDIT);
        addPathRow(form, 2, "Configuration exports", configurationDirectory, ExportDefaults.Kind.CONFIGURATION);
        addPathRow(form, 3, "Benchmark results", benchmarkDirectory, ExportDefaults.Kind.BENCHMARK);
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(reload);
        actions.add(save);
        footer.add(actions, BorderLayout.EAST);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        save.addActionListener(event -> save());
        reload.addActionListener(event -> load());
        applyPermissionState();
        load();
    }

    private void addPathRow(JPanel form, int row, String label, JTextField field, ExportDefaults.Kind kind) {
        GridBagConstraints left = constraints(0, row);
        left.weightx = 0;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(field);
        form.add(labelComponent, left);

        GridBagConstraints middle = constraints(1, row);
        middle.weightx = 1;
        form.add(field, middle);

        JButton browse = SecurityUi.button("Browse…", "Open the operating system folder picker for " + label.toLowerCase() + ".");
        browse.addActionListener(event -> NativeFileDialogs.chooseDirectory(this, "Choose " + label, kind)
                .ifPresent(path -> field.setText(path.toString())));
        browseButtons.add(browse);
        GridBagConstraints right = constraints(2, row);
        right.weightx = 0;
        form.add(browse, right);
    }

    private void load() {
        SecurityUi.work(this, () -> settingsStore.loadExportSettings().join(), this::showSettings, null);
    }

    private void showSettings(ExportSettings settings) {
        displayed = settings;
        evidenceDirectory.setText(settings.evidenceDirectory().toString());
        auditDirectory.setText(settings.auditDirectory().toString());
        configurationDirectory.setText(settings.configurationDirectory().toString());
        benchmarkDirectory.setText(settings.benchmarkDirectory().toString());
        if (context.can(Permission.CONFIGURE_EXPORT_SETTINGS)) {
            status.setText("Choose folders, then save. New folders are created only when a matching export is performed.");
        }
    }

    private void save() {
        if (!context.can(Permission.CONFIGURE_EXPORT_SETTINGS)) {
            status.setText("Export settings require the Configure export settings permission.");
            return;
        }
        final ExportSettings requested;
        try {
            requested = new ExportSettings(
                    requiredPath(evidenceDirectory.getText()),
                    requiredPath(auditDirectory.getText()),
                    requiredPath(configurationDirectory.getText()),
                    requiredPath(benchmarkDirectory.getText()));
        } catch (RuntimeException invalid) {
            status.setText("Enter valid folder paths for all export destinations.");
            return;
        }
        ExportSettings before = displayed == null ? settingsStore.defaultExportSettings() : displayed;
        String changed = describeChanges(before, requested);
        if (changed.isEmpty()) {
            status.setText("Export settings already match the saved folders.");
            return;
        }
        save.setEnabled(false);
        SecurityUi.work(this, () -> {
            context.beginAction(Permission.CONFIGURE_EXPORT_SETTINGS, "EXPORT_SETTINGS_UPDATE", "export-settings",
                    "changed=" + changed + "; folder values are not recorded");
            ExportSettings saved = settingsStore.saveExportSettings(requested).join();
            context.audit("EXPORT_SETTINGS_UPDATE_COMPLETED", "export-settings",
                    "changed=" + changed + "; folder values are not recorded");
            return saved;
        }, saved -> {
            showSettings(saved);
            status.setText("Default export folders saved.");
            save.setEnabled(true);
        }, () -> save.setEnabled(context.can(Permission.CONFIGURE_EXPORT_SETTINGS)));
    }

    private void applyPermissionState() {
        boolean allowed = context.can(Permission.CONFIGURE_EXPORT_SETTINGS);
        evidenceDirectory.setEditable(allowed);
        auditDirectory.setEditable(allowed);
        configurationDirectory.setEditable(allowed);
        benchmarkDirectory.setEditable(allowed);
        save.setEnabled(allowed);
        browseButtons.forEach(button -> button.setEnabled(allowed));
        String denied = "Requires the Configure export settings permission.";
        if (!allowed) {
            evidenceDirectory.setToolTipText(denied);
            auditDirectory.setToolTipText(denied);
            configurationDirectory.setToolTipText(denied);
            benchmarkDirectory.setToolTipText(denied);
            save.setToolTipText(denied);
            status.setText(denied);
        }
    }

    private static JTextField pathField(String tooltip) {
        JTextField field = new JTextField(42);
        field.setToolTipText(tooltip);
        return field;
    }

    private static Path requiredPath(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Choose a folder path.");
        }
        return Path.of(trimmed);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = x;
        value.gridy = y;
        value.insets = new Insets(6, 0, 6, x == 2 ? 0 : 8);
        value.anchor = GridBagConstraints.WEST;
        value.fill = x == 1 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        return value;
    }

    private static String describeChanges(ExportSettings before, ExportSettings after) {
        List<String> changed = new ArrayList<>();
        if (!before.evidenceDirectory().equals(after.evidenceDirectory())) changed.add("evidence");
        if (!before.auditDirectory().equals(after.auditDirectory())) changed.add("audit");
        if (!before.configurationDirectory().equals(after.configurationDirectory())) changed.add("configuration");
        if (!before.benchmarkDirectory().equals(after.benchmarkDirectory())) changed.add("benchmark");
        return String.join(",", changed);
    }
}
