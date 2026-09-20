package com.senyalert.view;

import com.senyalert.model.Incident;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.table.AbstractTableModel;

final class IncidentTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "ID", "Camera", "Alert", "Confidence", "People", "Hands", "Signaler", "Status", "Time", "Evidence"
    };
    private final List<Incident> incidents = new ArrayList<>();
    private final Set<Long> selectedIncidentIds = new LinkedHashSet<>();
    private final boolean selectionColumn;

    IncidentTableModel() {
        this(false);
    }

    /** Archive tables opt into a checkbox column; the compact dispatch queue does not. */
    IncidentTableModel(boolean selectionColumn) {
        this.selectionColumn = selectionColumn;
    }

    void replaceAll(List<Incident> values) {
        incidents.clear();
        incidents.addAll(values);
        selectedIncidentIds.retainAll(idsOf(values));
        sort();
        fireTableDataChanged();
    }

    void upsert(Incident incident) {
        for (int index = 0; index < incidents.size(); index++) {
            if (incidents.get(index).id() == incident.id()) {
                incidents.set(index, incident);
                sort();
                fireTableDataChanged();
                return;
            }
        }
        incidents.add(incident);
        sort();
        fireTableDataChanged();
    }

    void remove(long incidentId) {
        boolean removed = incidents.removeIf(incident -> incident.id() == incidentId);
        selectedIncidentIds.remove(incidentId);
        if (removed) {
            fireTableDataChanged();
        }
    }

    Incident incidentAt(int row) {
        return row < 0 || row >= incidents.size() ? null : incidents.get(row);
    }

    int pendingCount() {
        return (int) incidents.stream().filter(incident -> incident.status() == com.senyalert.model.IncidentStatus.PENDING).count();
    }

    List<Incident> selectedIncidents() {
        return incidents.stream()
                .filter(incident -> selectedIncidentIds.contains(incident.id()))
                .toList();
    }

    List<Long> selectedIds() {
        return selectedIncidents().stream().map(Incident::id).toList();
    }

    List<Long> allIds() {
        return incidents.stream().map(Incident::id).toList();
    }

    boolean hasSelectedIncidents() {
        return !selectedIncidentIds.isEmpty();
    }

    void setAllSelected(boolean selected) {
        if (!selectionColumn) {
            return;
        }
        selectedIncidentIds.clear();
        if (selected) {
            selectedIncidentIds.addAll(allIds());
        }
        fireTableDataChanged();
    }

    void setSelected(long incidentId, boolean selected) {
        if (!selectionColumn) {
            return;
        }
        if (selected) {
            selectedIncidentIds.add(incidentId);
        } else {
            selectedIncidentIds.remove(incidentId);
        }
        int row = indexOf(incidentId);
        if (row >= 0) {
            fireTableCellUpdated(row, 0);
        }
    }

    @Override
    public int getRowCount() {
        return incidents.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length + (selectionColumn ? 1 : 0);
    }

    @Override
    public String getColumnName(int column) {
        return selectionColumn && column == 0 ? "Select" : COLUMNS[dataColumn(column)];
    }

    @Override
    public Object getValueAt(int row, int column) {
        Incident incident = incidents.get(row);
        if (selectionColumn && column == 0) {
            return selectedIncidentIds.contains(incident.id());
        }
        return switch (dataColumn(column)) {
            case 0 -> "#" + incident.id();
            case 1 -> incident.cameraId();
            case 2 -> incident.alertMode().displayName();
            case 3 -> String.format(Locale.ROOT, "%.0f%%", incident.confidence() * 100.0);
            case 4 -> incident.peopleCount();
            case 5 -> incident.handCount();
            case 6 -> incident.signalerTrackId().isBlank() ? incident.signalerCount() : incident.signalerTrackId();
            case 7 -> incident.status().name();
            case 8 -> incident.detectionTimestamp();
            case 9 -> incident.mediaReady() ? "Video ready" : incident.mediaStatus();
            default -> "";
        };
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return selectionColumn && columnIndex == 0 ? Boolean.class : Object.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return selectionColumn && columnIndex == 0;
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (!selectionColumn || column != 0 || row < 0 || row >= incidents.size()) {
            return;
        }
        setSelected(incidents.get(row).id(), Boolean.TRUE.equals(value));
    }

    private int dataColumn(int column) {
        return selectionColumn ? column - 1 : column;
    }

    private int indexOf(long incidentId) {
        for (int index = 0; index < incidents.size(); index++) {
            if (incidents.get(index).id() == incidentId) {
                return index;
            }
        }
        return -1;
    }

    private static Set<Long> idsOf(List<Incident> values) {
        return values.stream().map(Incident::id).collect(java.util.stream.Collectors.toSet());
    }

    private void sort() {
        incidents.sort(Comparator.comparingLong(Incident::id).reversed());
    }
}
