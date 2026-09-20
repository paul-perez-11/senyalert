package com.senyalert.view;

import com.senyalert.model.Incident;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;

/**
 * Read-only page window over the shared incident model used by the dispatch queue.
 * The archive intentionally continues to use the complete model.
 */
final class PagedIncidentTableModel extends AbstractTableModel implements TableModelListener {
    private final IncidentTableModel source;
    private int pageSize = 10;
    private int pageIndex;

    PagedIncidentTableModel(IncidentTableModel source) {
        this.source = source;
        source.addTableModelListener(this);
    }

    int pageSize() {
        return pageSize;
    }

    int pageIndex() {
        return pageIndex;
    }

    int pageCount() {
        int total = source.getRowCount();
        return total == 0 ? 0 : (total + pageSize - 1) / pageSize;
    }

    int totalCount() {
        return source.getRowCount();
    }

    int firstVisibleNumber() {
        return getRowCount() == 0 ? 0 : firstSourceRow() + 1;
    }

    int lastVisibleNumber() {
        return getRowCount() == 0 ? 0 : firstSourceRow() + getRowCount();
    }

    boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    boolean hasNextPage() {
        return pageIndex + 1 < pageCount();
    }

    void setPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0 || requestedPageSize == pageSize) {
            return;
        }
        pageSize = requestedPageSize;
        pageIndex = 0;
        fireTableDataChanged();
    }

    void previousPage() {
        if (!hasPreviousPage()) {
            return;
        }
        pageIndex--;
        fireTableDataChanged();
    }

    void nextPage() {
        if (!hasNextPage()) {
            return;
        }
        pageIndex++;
        fireTableDataChanged();
    }

    /** New queue data should surface on the first page so urgent incidents are immediately visible. */
    void goToFirstPage() {
        if (pageIndex == 0) {
            return;
        }
        pageIndex = 0;
        fireTableDataChanged();
    }

    Incident incidentAt(int row) {
        return row < 0 || row >= getRowCount() ? null : source.incidentAt(firstSourceRow() + row);
    }

    @Override
    public int getRowCount() {
        return Math.max(0, Math.min(pageSize, source.getRowCount() - firstSourceRow()));
    }

    @Override
    public int getColumnCount() {
        return source.getColumnCount();
    }

    @Override
    public String getColumnName(int column) {
        return source.getColumnName(column);
    }

    @Override
    public Object getValueAt(int row, int column) {
        return source.getValueAt(firstSourceRow() + row, column);
    }

    @Override
    public void tableChanged(TableModelEvent event) {
        clampPageIndex();
        fireTableDataChanged();
    }

    private int firstSourceRow() {
        return pageIndex * pageSize;
    }

    private void clampPageIndex() {
        int pages = pageCount();
        if (pages == 0) {
            pageIndex = 0;
        } else if (pageIndex >= pages) {
            pageIndex = pages - 1;
        }
    }
}
