package com.senyalert.view;

import com.senyalert.controller.DashboardActions;
import com.senyalert.model.ArchiveExportMode;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.view.ui.BlueTheme;
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
import javax.swing.JTabbedPane;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;

/**
 * Archive-only workspace. Checkboxes are independent from ordinary row focus,
 * so operators can inspect one incident without losing a bulk selection.
 */
final class EvidenceArchivePanel extends JPanel {
    private final IncidentTableModel archiveModel = new IncidentTableModel(true);
    private DashboardActions actions;
    private IncidentViewerDialog viewer;

    EvidenceArchivePanel() {
        setLayout(new BorderLayout());
        setBackground(BlueTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));

        JTabbedPane archiveTabs = new JTabbedPane();
        archiveTabs.setFont(BlueTheme.font(Font.BOLD, 13));
        archiveTabs.addTab("Records", buildArchiveTab(
                "Incident records",
                "Select rows with checkboxes for safe workflow updates, export, or database-only removal.",
                ArchiveExportMode.RECORD_BUNDLE,
                true));
        archiveTabs.addTab("Snapshots", buildArchiveTab(
                "Image snapshots",
                "Export copies of stored snapshots. Database removal never deletes the source image files.",
                ArchiveExportMode.SNAPSHOTS,
                false));
        archiveTabs.addTab("Video clips", buildArchiveTab(
                "Video evidence",
                "Export copies of video evidence. Database removal never deletes the source video files.",
                ArchiveExportMode.VIDEOS,
                false));
        add(archiveTabs, BorderLayout.CENTER);
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
                            actions.updateOperatorRecord(incidentId, update);
                        }
                    },
                    incidentId -> {
                        if (actions != null) {
                            actions.deleteIncidentRecord(incidentId);
                        }
                    },
                    incidentId -> {
                        if (actions != null) {
                            actions.playVideoNatively(incidentId);
                        }
                    },
                    (incidentId, destination, mode) -> {
                        if (actions != null) {
                            actions.exportArchive(List.of(incidentId), destination, mode);
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
            ArchiveExportMode exportMode,
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
        card.add(buildToolbar(table, exportMode, includeBulkUpdate), BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildToolbar(JTable table, ArchiveExportMode exportMode, boolean includeBulkUpdate) {
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
        StyledButton exportAll = new StyledButton("Export all…", FontAwesomeSolid.FILE_EXPORT, BlueTheme.DEEP_BLUE);
        exportAll.setToolTipText("Exports checked records, or every archive record when none are checked.");
        exportAll.addActionListener(event -> chooseArchiveExport(exportMode));
        StyledButton deleteAll = new StyledButton("Delete all…", FontAwesomeSolid.EXCLAMATION_TRIANGLE, BlueTheme.DANGER);
        deleteAll.setToolTipText("Deletes checked records, or requires a typed confirmation to remove all archive database records.");
        deleteAll.addActionListener(event -> confirmArchiveDelete());
        toolbar.add(exportAll);
        toolbar.add(deleteAll);
        return toolbar;
    }

    private JTable createArchiveTable() {
        JTable table = new JTable(archiveModel);
        table.setFont(BlueTheme.font(Font.PLAIN, 12));
        table.setForeground(BlueTheme.TEXT);
        table.setBackground(Color.WHITE);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setFont(BlueTheme.font(Font.BOLD, 11));
        table.getTableHeader().setBackground(new Color(229, 239, 249));
        table.getTableHeader().setForeground(BlueTheme.TEXT);
        table.getColumnModel().getColumn(0).setMinWidth(55);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
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
            actions.selectIncidentForArchive(incident.id());
        }
    }

    private void updateFocusedStatus(JTable table, IncidentStatus status) {
        Incident incident = focusedIncident(table);
        if (incident != null && actions != null) {
            actions.updateOperatorRecord(incident.id(), new OperatorIncidentUpdate(status, incident.operatorNotes()));
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
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Delete incident #" + incident.id() + " from SQLite?\n\n"
                        + "Its original snapshot and video files are not deleted.",
                "Delete database record",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            actions.deleteIncidentRecords(List.of(incident.id()));
        }
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
        actions.updateOperatorRecords(updates);
    }

    private void chooseArchiveExport(ArchiveExportMode mode) {
        List<Long> ids = archiveModel.hasSelectedIncidents() ? archiveModel.selectedIds() : archiveModel.allIds();
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no archive records to export.", "Export archive", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        chooseExportDirectory(ids, mode);
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
            actions.exportArchive(ids, chooser.getSelectedFile().toPath(), mode);
        }
    }

    private void confirmArchiveDelete() {
        if (actions == null) {
            return;
        }
        List<Long> selected = archiveModel.selectedIds();
        if (!selected.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Delete " + selected.size() + " selected database record(s)?\n\n"
                            + "This does not delete original snapshot or video files.",
                    "Delete selected records",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                actions.deleteIncidentRecords(selected);
            }
            return;
        }
        List<Long> allIds = archiveModel.allIds();
        if (allIds.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no archive records to delete.", "Delete all", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTextField phrase = new JTextField(16);
        JPanel confirmation = transparent(new BorderLayout(0, 8));
        confirmation.add(new JLabel("Type DELETE ALL to remove all " + allIds.size() + " database records."), BorderLayout.NORTH);
        confirmation.add(phrase, BorderLayout.CENTER);
        confirmation.add(new JLabel("Original image and video files will remain untouched."), BorderLayout.SOUTH);
        int choice = JOptionPane.showConfirmDialog(this, confirmation, "Delete entire evidence archive",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION && "DELETE ALL".equals(phrase.getText().strip())) {
            actions.deleteIncidentRecords(allIds);
        } else if (choice == JOptionPane.OK_OPTION) {
            JOptionPane.showMessageDialog(this, "Nothing was deleted. The confirmation phrase did not match.",
                    "Delete all cancelled", JOptionPane.INFORMATION_MESSAGE);
        }
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
