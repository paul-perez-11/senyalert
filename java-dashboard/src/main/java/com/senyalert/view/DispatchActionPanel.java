package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.security.Permission;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/**
 * Compact, permission-aware controls for the selected live-queue incident.
 * The queue is intentionally the only Live Dispatch surface that changes an
 * incident's workflow state; evidence below it remains an inspection surface.
 */
final class DispatchActionPanel extends JPanel {
    private final JLabel selectedLabel = new JLabel("Select an active incident to handle it");
    private final JTextArea note = new JTextArea(2, 34);
    private final JButton acknowledge = compactButton("Acknowledge", FontAwesomeSolid.CHECK, BlueTheme.PRIMARY);
    private final JButton resolve = compactButton("Resolve", FontAwesomeSolid.CHECK, BlueTheme.SUCCESS);
    private final JButton saveNote = compactButton("Save note", FontAwesomeSolid.CHECK, BlueTheme.DEEP_BLUE);
    private DashboardActions actions;
    private Predicate<Permission> allowed = ignored -> false;
    private Incident selected;

    DispatchActionPanel() {
        setLayout(new BorderLayout(6, 4));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BlueTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 0, 0, 0)));

        selectedLabel.setFont(BlueTheme.font(Font.BOLD, 11));
        selectedLabel.setForeground(BlueTheme.TEXT);
        selectedLabel.setToolTipText("The live incident selected for acknowledgement, resolution, or an operator note.");
        add(selectedLabel, BorderLayout.NORTH);

        note.setFont(BlueTheme.font(Font.PLAIN, 12));
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setBackground(java.awt.Color.WHITE);
        note.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        note.setToolTipText("Enter a factual operator note for the selected incident. Saving a note is audited.");
        note.getAccessibleContext().setAccessibleName("Selected incident operator note");
        note.getAccessibleContext().setAccessibleDescription(
                "Editable factual note for the selected live incident. Saving records the signed-in user in the audit log.");
        JScrollPane noteScroll = new JScrollPane(note);
        noteScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        noteScroll.setPreferredSize(new Dimension(300, 42));

        JPanel editor = new JPanel(new BorderLayout(6, 0));
        editor.setOpaque(false);
        JPanel noteBlock = new JPanel(new BorderLayout(0, 3));
        noteBlock.setOpaque(false);
        JLabel noteLabel = new JLabel("Operator note");
        noteLabel.setFont(BlueTheme.font(Font.BOLD, 10));
        noteLabel.setForeground(BlueTheme.MUTED);
        noteBlock.add(noteLabel, BorderLayout.NORTH);
        noteBlock.add(noteScroll, BorderLayout.CENTER);
        editor.add(noteBlock, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(3, 1, 0, 3));
        buttons.setOpaque(false);
        buttons.add(acknowledge);
        buttons.add(resolve);
        buttons.add(saveNote);
        editor.add(buttons, BorderLayout.EAST);
        add(editor, BorderLayout.CENTER);

        acknowledge.addActionListener(event -> save(IncidentStatus.ACKNOWLEDGED));
        resolve.addActionListener(event -> save(IncidentStatus.RESOLVED));
        saveNote.addActionListener(event -> save(currentStatus()));
        clearSelection();
    }

    void setActions(DashboardActions actions) {
        this.actions = actions;
    }

    void setPermissions(Predicate<Permission> allowed) {
        this.allowed = allowed == null ? ignored -> false : allowed;
        updateControls();
    }

    void showIncident(Incident incident) {
        if (incident == null || incident.status() != IncidentStatus.PENDING) {
            clearSelection();
            return;
        }
        selected = incident;
        selectedLabel.setText("Incident #" + incident.id() + " · " + incident.cameraId() + " · "
                + String.format(java.util.Locale.ROOT, "%.0f%% confidence", incident.confidence() * 100));
        note.setText(incident.operatorNotes());
        note.setCaretPosition(0);
        updateControls();
    }

    void clearSelection() {
        selected = null;
        selectedLabel.setText("Select an active incident to handle it");
        note.setText("");
        updateControls();
    }

    boolean isShowingIncident(long incidentId) {
        return selected != null && selected.id() == incidentId;
    }

    private IncidentStatus currentStatus() {
        return selected == null ? IncidentStatus.PENDING : selected.status();
    }

    private void save(IncidentStatus status) {
        if (actions == null || selected == null) {
            return;
        }
        try {
            actions.updateOperatorRecord(selected.id(), new OperatorIncidentUpdate(status, note.getText()));
        } catch (IllegalArgumentException invalidRecord) {
            JOptionPane.showMessageDialog(this, invalidRecord.getMessage(), "Check operator note", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void updateControls() {
        boolean hasSelected = selected != null;
        boolean mayEditNote = hasSelected && allowed.test(Permission.EDIT_NOTES);
        boolean mayAcknowledge = hasSelected && selected.status() == IncidentStatus.PENDING
                && allowed.test(Permission.ACKNOWLEDGE_INCIDENTS);
        boolean mayResolve = hasSelected && selected.status() != IncidentStatus.RESOLVED
                && allowed.test(Permission.RESOLVE_INCIDENTS);
        note.setEditable(mayEditNote);
        acknowledge.setEnabled(mayAcknowledge);
        resolve.setEnabled(mayResolve);
        saveNote.setEnabled(mayEditNote);
        acknowledge.setToolTipText(mayAcknowledge
                ? "Mark the selected active incident as acknowledged. This removes it from the live queue and records an audit entry."
                : "Requires an active selected incident and the Acknowledge incidents permission.");
        resolve.setToolTipText(mayResolve
                ? "Mark the selected active incident as resolved. This removes it from the live queue and records an audit entry."
                : "Requires an active selected incident and the Resolve incidents permission.");
        saveNote.setToolTipText(mayEditNote
                ? "Save the factual operator note for the selected incident. This action is audited."
                : "Requires an active selected incident and the Edit notes permission.");
    }

    private static JButton compactButton(String label, org.kordamp.ikonli.Ikon icon, java.awt.Color color) {
        JButton button = new StyledButton(label, icon, color);
        button.setFont(BlueTheme.font(Font.BOLD, 10));
        button.setPreferredSize(new Dimension(108, 24));
        button.setMinimumSize(new Dimension(104, 24));
        return button;
    }
}
