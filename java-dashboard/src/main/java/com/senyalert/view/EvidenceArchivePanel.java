package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.ArchiveScope;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.MediaDeletionOptions;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.EmptyStateTable;
import com.senyalert.view.ui.RoundedPanel;
import com.senyalert.view.ui.StyledButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/**
 * Archive-only workspace. Checkboxes are independent from ordinary row focus,
 * so operators can inspect one incident without losing a bulk selection.
 */
final class EvidenceArchivePanel extends JPanel {
    private final ArchiveScope scope;
    private final IncidentTableModel archiveModel = new IncidentTableModel(true);
    private DashboardActions actions;
    private IncidentViewerDialog viewer;

    EvidenceArchivePanel(ArchiveScope scope) {
        this.scope = scope == null ? ArchiveScope.RECORDED : scope;
        setLayout(new BorderLayout());
        setBackground(BlueTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));

        String sourceText = this.scope == ArchiveScope.UPLOADED
                ? "offline-upload results"
                : "live recorded incidents";
        add(buildArchiveTab(
                this.scope.displayName(),
                "Select rows with checkboxes for safe workflow updates, complete evidence export, or removal of "
                        + sourceText + ".",
                true), BorderLayout.CENTER);
    }

    void setActions(DashboardActions actions) {
        this.actions = actions;
    }

    void showIncidents(List<Incident> incidents) {
        archiveModel.replaceAll(incidents);
    }

    void upsertIncident(Incident incident) {
        archiveModel.upsert(incident);
    }

    void removeIncident(long incidentId) {
        archiveModel.remove(incidentId);
        if (viewer != null) {
            viewer.closeIfViewing(incidentId);
        }
    }

    void showEvidence(IncidentEvidence evidence) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (viewer == null || !viewer.isDisplayable()) {
            viewer = new IncidentViewerDialog(owner);
            viewer.setActions(
                    (incidentId, update) -> {
                        if (actions != null) {
                            actions.updateOperatorRecord(scope, incidentId, update);
                        }
                    },
                    (incidentId, options) -> {
                        if (actions != null) {
                            actions.deleteIncidentRecords(scope, List.of(incidentId), options);
                        }
                    },
                    incidentId -> {
                        if (actions != null) {
                            actions.playVideoNatively(scope, incidentId);
                        }
                    },
                    (incidentId, destination, mode) -> {
                        if (actions != null) {
                            actions.exportArchive(scope, List.of(incidentId), destination, mode);
                        }
                    });
        }
        viewer.showEvidence(evidence);
    }

    void showOperatorRecordUpdated(Incident incident) {
        archiveModel.upsert(incident);
        if (viewer != null && viewer.isDisplayable()) {
            viewer.showOperatorRecordUpdated(incident);
        }
    }

    private JPanel buildArchiveTab(
            String title,
            String help,
            boolean includeBulkUpdate) {
        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new BorderLayout(0, 10));
        card.setBackground(BlueTheme.CARD);
        card.setBorder(BlueTheme.cardBorder());

        JPanel heading = transparent(new BorderLayout(8, 0));
        JPanel headingText = transparent(new BorderLayout(0, 2));
        JLabel headingLabel = new JLabel(title);
        headingLabel.setFont(BlueTheme.font(Font.BOLD, 16));
        headingLabel.setForeground(BlueTheme.TEXT);
        JLabel helpLabel = new JLabel(help + " Right-click a row for incident actions.");
        helpLabel.setFont(BlueTheme.font(Font.PLAIN, 11));
        helpLabel.setForeground(BlueTheme.MUTED);
        headingText.add(headingLabel, BorderLayout.NORTH);
        headingText.add(helpLabel, BorderLayout.SOUTH);
        heading.add(headingText, BorderLayout.CENTER);
        StyledButton refresh = new StyledButton("Refresh", FontAwesomeSolid.SYNC, BlueTheme.DEEP_BLUE);
        refresh.addActionListener(event -> {
            if (actions != null) {
                actions.refreshIncidents();
            }
        });
        heading.add(refresh, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);

        JTable table = createArchiveTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        card.add(scroll, BorderLayout.CENTER);
        card.add(buildToolbar(table, includeBulkUpdate), BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildToolbar(JTable table, boolean includeBulkUpdate) {
        JPanel toolbar = transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        StyledButton view = new StyledButton("View / update", FontAwesomeSolid.DATABASE, BlueTheme.PRIMARY);
        view.addActionListener(event -> openForView(table));
        StyledButton selectAll = new StyledButton("Select all", FontAwesomeSolid.CHECK, BlueTheme.DEEP_BLUE);
        selectAll.addActionListener(event -> archiveModel.setAllSelected(true));
        StyledButton clearSelection = new StyledButton("Clear", null, BlueTheme.DEEP_BLUE);
        clearSelection.addActionListener(event -> archiveModel.setAllSelected(false));
        toolbar.add(view);
        toolbar.add(selectAll);
        toolbar.add(clearSelection);

        if (includeBulkUpdate) {
            StyledButton bulkUpdate = new StyledButton("Update selected", FontAwesomeSolid.CHECK, BlueTheme.SUCCESS);
            bulkUpdate.addActionListener(event -> showBulkUpdateDialog());
            toolbar.add(bulkUpdate);
        }
        StyledButton exportAll = new StyledButton("Export complete bundle…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
        exportAll.setToolTipText("Exports the record report plus every available snapshot and video for checked records, or all records when none are checked.");
        exportAll.addActionListener(event -> chooseArchiveExport());
        StyledButton deleteAll = new StyledButton("Delete all…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);
        deleteAll.setToolTipText("Deletes checked records, or requires a typed confirmation to remove all archive database records.");
        deleteAll.addActionListener(event -> confirmArchiveDelete());
        StyledButton resetNextId = new StyledButton("Reset next ID…", FontAwesomeSolid.SYNC, BlueTheme.DEEP_BLUE);
        resetNextId.setToolTipText("Does not delete records or media. Available only when this archive is empty.");
        resetNextId.addActionListener(event -> confirmResetNextId(resetNextId));
        toolbar.add(exportAll);
        toolbar.add(deleteAll);
        toolbar.add(resetNextId);
        return toolbar;
    }

    private JTable createArchiveTable() {
        JTable table = new EmptyStateTable(
                archiveModel,
                "No archived incidents",
                "When a detection is stored, its record and evidence will be available here.");
        table.setFont(BlueTheme.font(Font.PLAIN, 12));
        table.setForeground(BlueTheme.TEXT);
        table.setBackground(Color.WHITE);
        table.setRowHeight(28);
        table.setSelectionBackground(new Color(211, 231, 249));
        table.setSelectionForeground(BlueTheme.TEXT);
        table.setGridColor(new Color(229, 237, 246));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setFont(BlueTheme.font(Font.BOLD, 11));
        table.getTableHeader().setBackground(new Color(229, 239, 249));
        table.getTableHeader().setForeground(BlueTheme.TEXT);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMinWidth(55);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
        table.getAccessibleContext().setAccessibleName("Evidence archive");
        table.getAccessibleContext().setAccessibleDescription(
                "Archive records with checkboxes for safe bulk actions. Open a record to see read-only detection data and editable operator fields.");
        installPopupMenu(table);
        return table;
    }

    private void installPopupMenu(JTable table) {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowPopup(event);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openForView(table);
                }
            }

            private void maybeShowPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0) {
                    return;
                }
                table.setRowSelectionInterval(row, row);
                buildContextMenu(table).show(table, event.getX(), event.getY());
            }
        });
    }

    private JPopupMenu buildContextMenu(JTable table) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem view = new JMenuItem("View / update incident");
        view.addActionListener(event -> openForView(table));
        JMenuItem toggle = new JMenuItem("Toggle bulk selection");
        toggle.addActionListener(event -> {
            Incident incident = focusedIncident(table);
            if (incident != null) {
                archiveModel.setSelected(incident.id(), !archiveModel.selectedIds().contains(incident.id()));
            }
        });
        JMenuItem acknowledge = new JMenuItem("Mark acknowledged");
        acknowledge.addActionListener(event -> updateFocusedStatus(table, IncidentStatus.ACKNOWLEDGED));
        JMenuItem resolve = new JMenuItem("Mark resolved");
        resolve.addActionListener(event -> updateFocusedStatus(table, IncidentStatus.RESOLVED));
        JMenuItem export = new JMenuItem("Export record bundle…");
        export.addActionListener(event -> exportFocused(table));
        JMenuItem delete = new JMenuItem("Delete database record…");
        delete.addActionListener(event -> deleteFocused(table));
        menu.add(view);
        menu.add(toggle);
        menu.addSeparator();
        menu.add(acknowledge);
        menu.add(resolve);
        menu.addSeparator();
        menu.add(export);
        menu.add(delete);
        return menu;
    }

    private void openForView(JTable table) {
        if (actions == null) {
            return;
        }
        List<Incident> selected = archiveModel.selectedIncidents();
        Incident incident = selected.size() == 1 ? selected.get(0) : focusedIncident(table);
        if (selected.size() > 1) {
            JOptionPane.showMessageDialog(this, "Choose one checked incident to view, or clear the bulk selection.",
                    "View incident", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (incident != null) {
            actions.selectIncidentForArchive(scope, incident.id());
        }
    }

    private void updateFocusedStatus(JTable table, IncidentStatus status) {
        Incident incident = focusedIncident(table);
        if (incident != null && actions != null) {
            actions.updateOperatorRecord(scope, incident.id(), new OperatorIncidentUpdate(status, incident.operatorNotes()));
        }
    }

    private void exportFocused(JTable table) {
        Incident incident = focusedIncident(table);
        if (incident == null) {
            return;
        }
        chooseExportDirectory(List.of(incident.id()), ArchiveExportMode.RECORD_BUNDLE);
    }

    private void deleteFocused(JTable table) {
        Incident incident = focusedIncident(table);
        if (incident == null || actions == null) {
            return;
        }
        confirmDeleteRecords(List.of(incident.id()), false);
    }

    private void showBulkUpdateDialog() {
        List<Incident> selected = archiveModel.selectedIncidents();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Check one or more records first.", "Bulk update", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> statusChoice = new JComboBox<>(new String[] {
                "Keep existing status", IncidentStatus.PENDING.name(), IncidentStatus.ACKNOWLEDGED.name(), IncidentStatus.RESOLVED.name()
        });
        JCheckBox replaceNotes = new JCheckBox("Replace operator note for every selected record");
        JTextArea notes = new JTextArea(4, 34);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setEnabled(false);
        replaceNotes.addActionListener(event -> notes.setEnabled(replaceNotes.isSelected()));

        JPanel form = transparent(new BorderLayout(0, 8));
        form.add(new JLabel("Updating " + selected.size() + " selected record(s)."), BorderLayout.NORTH);
        JPanel controls = transparent(new BorderLayout(0, 6));
        controls.add(statusChoice, BorderLayout.NORTH);
        controls.add(replaceNotes, BorderLayout.CENTER);
        JScrollPane notesScroll = new JScrollPane(notes);
        notesScroll.setBorder(BorderFactory.createLineBorder(BlueTheme.BORDER));
        controls.add(notesScroll, BorderLayout.SOUTH);
        form.add(controls, BorderLayout.CENTER);
        int choice = JOptionPane.showConfirmDialog(this, form, "Safe bulk operator update",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION || actions == null) {
            return;
        }
        String requested = (String) statusChoice.getSelectedItem();
        Map<Long, OperatorIncidentUpdate> updates = new LinkedHashMap<>();
        try {
            for (Incident incident : selected) {
                IncidentStatus newStatus = "Keep existing status".equals(requested)
                        ? incident.status() : IncidentStatus.valueOf(requested);
                String newNotes = replaceNotes.isSelected() ? notes.getText() : incident.operatorNotes();
                updates.put(incident.id(), new OperatorIncidentUpdate(newStatus, newNotes));
            }
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(), "Check operator update", JOptionPane.WARNING_MESSAGE);
            return;
        }
        actions.updateOperatorRecords(scope, updates);
    }

    private void chooseArchiveExport() {
        List<Long> ids = archiveModel.hasSelectedIncidents() ? archiveModel.selectedIds() : archiveModel.allIds();
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no archive records to export.", "Export archive", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        chooseExportDirectory(ids, ArchiveExportMode.RECORD_BUNDLE);
    }

    private void chooseExportDirectory(List<Long> ids, ArchiveExportMode mode) {
        if (actions == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an export folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            actions.exportArchive(scope, ids, chooser.getSelectedFile().toPath(), mode);
        }
    }

    private void confirmArchiveDelete() {
        if (actions == null) {
            return;
        }
        List<Long> selected = archiveModel.selectedIds();
        if (!selected.isEmpty()) {
            confirmDeleteRecords(selected, false);
            return;
        }
        List<Long> allIds = archiveModel.allIds();
        if (allIds.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no archive records to delete.", "Delete all", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        confirmDeleteRecords(allIds, true);
    }

    private void confirmDeleteRecords(List<Long> ids, boolean requireAllPhrase) {
        if (actions == null || ids == null || ids.isEmpty()) {
            return;
        }
        JCheckBox deleteSnapshot = new JCheckBox("Delete associated image snapshots");
        JCheckBox deleteVideo = new JCheckBox("Delete associated video clips");
        deleteSnapshot.setOpaque(false);
        deleteVideo.setOpaque(false);
        JPanel choices = transparent(new BorderLayout(0, 7));
        String count = ids.size() == 1 ? "this database record" : ids.size() + " database records";
        choices.add(new JLabel("Delete " + count + " from " + scope.displayName().toLowerCase() + "?"), BorderLayout.NORTH);
        JPanel mediaChoices = transparent(new java.awt.GridLayout(0, 1, 0, 3));
        mediaChoices.add(new JLabel("Optional source-media cleanup (only checked exact file paths are removed):"));
        mediaChoices.add(deleteSnapshot);
        mediaChoices.add(deleteVideo);
        choices.add(mediaChoices, BorderLayout.CENTER);
        JTextField phrase = null;
        if (requireAllPhrase) {
            phrase = new JTextField(16);
            JPanel allConfirmation = transparent(new BorderLayout(0, 3));
            allConfirmation.add(new JLabel("Type DELETE ALL to confirm every record in this archive."), BorderLayout.NORTH);
            allConfirmation.add(phrase, BorderLayout.SOUTH);
            choices.add(allConfirmation, BorderLayout.SOUTH);
        }
        int choice = JOptionPane.showConfirmDialog(this, choices,
                requireAllPhrase ? "Delete entire " + scope.displayName() : "Delete evidence record",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        if (requireAllPhrase && (phrase == null || !"DELETE ALL".equals(phrase.getText().strip()))) {
            JOptionPane.showMessageDialog(this, "Nothing was deleted. The confirmation phrase did not match.",
                    "Delete all cancelled", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        actions.deleteIncidentRecords(scope, ids,
                new MediaDeletionOptions(deleteSnapshot.isSelected(), deleteVideo.isSelected()));
    }

    private void confirmResetNextId(StyledButton trigger) {
        if (actions == null) {
            return;
        }
        JTextField phrase = new JTextField(16);
        JPanel confirmation = transparent(new BorderLayout(0, 7));
        confirmation.add(new JLabel("This only resets the next " + scope.displayName().toLowerCase()
                + " incident ID to 1. It never deletes records, snapshots, or video clips."), BorderLayout.NORTH);
        JPanel phrasePanel = transparent(new BorderLayout(0, 3));
        phrasePanel.add(new JLabel("This is allowed only when the archive is empty. Type RESET ID to continue."),
                BorderLayout.NORTH);
        phrasePanel.add(phrase, BorderLayout.SOUTH);
        confirmation.add(phrasePanel, BorderLayout.CENTER);
        int choice = JOptionPane.showConfirmDialog(this, confirmation,
                "Reset next " + scope.displayName() + " incident ID",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        if (!"RESET ID".equals(phrase.getText().strip())) {
            JOptionPane.showMessageDialog(this, "Nothing changed. The confirmation phrase did not match.",
                    "Reset next ID cancelled", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        trigger.setEnabled(false);
        actions.resetNextIncidentId(scope).whenComplete((ignored, failure) ->
                SwingUtilities.invokeLater(() -> trigger.setEnabled(true)));
    }

    private Incident focusedIncident(JTable table) {
        int selectedRow = table.getSelectedRow();
        if (selectedRow < 0) {
            return null;
        }
        return archiveModel.incidentAt(table.convertRowIndexToModel(selectedRow));
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }
}
