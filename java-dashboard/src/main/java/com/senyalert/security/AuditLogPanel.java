package com.senyalert.security;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;
import com.senyalert.service.ExportDefaults;
import com.senyalert.view.ui.NativeFileDialogs;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** Compact audit activity list. Reading it never writes an audit event. */
public final class AuditLogPanel extends JPanel {
    private static final String RESET_PHRASE = "RESET AUDIT LOG";
    private final SecurityContext context;
    private final JTextField filter = new JTextField(25);
    private final JLabel status = new JLabel(" ");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"UTC time", "Who", "Action", "Target"}, 0) {
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private List<AuditEntry> entries = List.of();

    public AuditLogPanel(SecurityContext context) {
        super(new BorderLayout(10, 10));
        this.context = context;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        boolean canView = context.can(Permission.VIEW_AUDIT);
        boolean canReset = context.can(Permission.RESET_AUDIT_LOGS);

        JPanel heading = new JPanel(new BorderLayout(8, 8));
        heading.add(new JLabel("<html><h2>Audit logs</h2>Compact activity history. Opening or searching this screen does not create an audit record.</html>"), BorderLayout.NORTH);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = SecurityUi.button("Refresh", "Load the latest matching audit events. Viewing never changes the audit history.");
        JButton details = SecurityUi.button("Show details", "Open the selected event's full saved details and integrity hashes.");
        JButton verify = SecurityUi.button("Verify chain", "Check the append-only audit chain for tampering and record that verification.");
        JButton export = SecurityUi.button("Export CSV", "Write up to 100,000 matching events to a new CSV file without replacing an existing file.");
        JButton reset = SecurityUi.button("Reset for demo", "Preserve a CSV snapshot, then reset the active audit list for a supervised demo. Requires the dedicated permission.");
        filter.setToolTipText("Filter by UTC time, username, action, target, or saved detail. Leave empty for recent events.");
        tools.add(new JLabel("Find")); tools.add(filter); tools.add(refresh); tools.add(details); tools.add(verify); tools.add(export);
        if (canReset) tools.add(reset);
        heading.add(tools, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        table.setRowHeight(27); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {245, 170, 255, 260};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setToolTipText("Select one event, then use Show details when the full saved payload is needed.");
        table.setEnabled(canView);
        JScrollPane scroll = new JScrollPane(table); scroll.setPreferredSize(new Dimension(760, 360));
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        refresh.addActionListener(e -> refresh()); filter.addActionListener(e -> refresh());
        details.addActionListener(e -> showDetails());
        verify.addActionListener(e -> SecurityUi.work(this, () -> context.service().verifyAuditChain(context.session()),
                valid -> status.setText(valid ? "Audit chain verified." : "Audit chain mismatch detected. Preserve the database for review."), null));
        export.addActionListener(e -> exportCsv());
        reset.addActionListener(e -> resetForDemo());
        refresh.setEnabled(canView); details.setEnabled(canView); verify.setEnabled(canView); export.setEnabled(canView); filter.setEnabled(canView); reset.setEnabled(canReset);
        if (canView) refresh();
        else if (canReset) status.setText("You may reset the active demo log, but this account cannot read audit details.");
        else status.setText("Audit access is not enabled for this account.");
    }

    public void refresh() {
        String query = filter.getText();
        SecurityUi.work(this, () -> context.service().listAudit(context.session(), query, 1000), values -> {
            entries = values; model.setRowCount(0);
            for (AuditEntry entry : entries) model.addRow(new Object[]{entry.timestamp(), entry.actor(), entry.action(), entry.target()});
            status.setText(entries.size() + " matching events displayed (latest 1,000 maximum). Times use UTC.");
        }, null);
    }

    private void showDetails() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= entries.size()) { status.setText("Select an audit event first."); return; }
        AuditEntry entry = entries.get(table.convertRowIndexToModel(row));
        JTextArea details = new JTextArea("Audit #" + entry.id() + " | " + entry.timestamp() + "\nActor: " + entry.actor() + " (" + entry.role() + ")\nAction: " + entry.action()
                + "\nTarget: " + entry.target() + "\n\n" + entry.details() + "\n\nPrevious hash: " + entry.previousHash() + "\nEntry hash: " + entry.entryHash(), 14, 76);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setToolTipText("Read-only saved audit details and integrity hashes.");
        JDialog dialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this), "Audit event details", Dialog.ModalityType.MODELESS);
        dialog.setContentPane(new JScrollPane(details)); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void exportCsv() {
        Path destination = NativeFileDialogs.saveFile(this, "Export audit CSV", ExportDefaults.Kind.AUDIT,
                "senyalert-audit-" + System.currentTimeMillis() + ".csv", List.of("csv")).orElse(null);
        if (destination == null) return;
        if (java.nio.file.Files.exists(destination)) {
            status.setText("Choose a new CSV filename; audit exports never replace an existing file.");
            return;
        }
        String query = filter.getText();
        SecurityUi.work(this, () -> { context.service().exportAuditCsv(context.session(), destination, query); return destination; },
                saved -> { status.setText("Audit CSV exported to " + saved.toAbsolutePath()); refresh(); }, null);
    }

    private void resetForDemo() {
        JTextField phrase = new JTextField(20);
        phrase.setToolTipText("Type the exact confirmation phrase shown in the prompt.");
        JPanel prompt = new JPanel(new BorderLayout(8, 8));
        prompt.add(new JLabel("<html>A protected CSV copy will be saved under <b>audit-reset-archives</b> before the active log is reset.<br>Type <b>"
                + RESET_PHRASE + "</b> to continue.</html>"), BorderLayout.NORTH);
        prompt.add(phrase, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, prompt, "Reset active audit log for demo", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (!RESET_PHRASE.equals(phrase.getText().trim())) {
            status.setText("Audit log reset was cancelled; the confirmation phrase did not match."); return;
        }
        SecurityUi.work(this, () -> context.service().resetAuditForDemo(context.session()), result -> {
            entries = List.of(); model.setRowCount(0);
            status.setText("Reset " + result.removedEntries() + " active entries. Protected CSV: " + result.preservedCsv().toAbsolutePath());
            if (context.can(Permission.VIEW_AUDIT)) refresh();
        }, null);
    }
}
