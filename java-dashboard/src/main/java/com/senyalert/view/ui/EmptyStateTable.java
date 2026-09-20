package com.senyalert.view.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JTable;
import javax.swing.table.TableModel;

/** A table that remains informative while its model is empty or still loading. */
public final class EmptyStateTable extends JTable {
    private String emptyTitle;
    private String emptyDetail;

    public EmptyStateTable(TableModel model, String emptyTitle, String emptyDetail) {
        super(model);
        setEmptyState(emptyTitle, emptyDetail);
    }

    public void setEmptyState(String title, String detail) {
        emptyTitle = title == null || title.isBlank() ? "Nothing to show" : title;
        emptyDetail = detail == null ? "" : detail;
        if (getAccessibleContext().getAccessibleName() == null) {
            getAccessibleContext().setAccessibleName(emptyTitle);
        }
        getAccessibleContext().setAccessibleDescription(emptyDetail);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getRowCount() != 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int panelWidth = Math.min(Math.max(260, getWidth() - 36), 430);
            int panelHeight = 78;
            int left = Math.max(18, (getWidth() - panelWidth) / 2);
            int top = Math.max(18, (getHeight() - panelHeight) / 2);
            g2.setColor(BlueTheme.SURFACE_TINT);
            g2.fillRoundRect(left, top, panelWidth, panelHeight, 14, 14);
            g2.setColor(BlueTheme.BORDER);
            g2.drawRoundRect(left, top, panelWidth - 1, panelHeight - 1, 14, 14);
            g2.setColor(BlueTheme.PRIMARY);
            g2.fillOval(left + 16, top + 26, 26, 26);
            g2.setColor(Color.WHITE);
            g2.setFont(BlueTheme.font(Font.BOLD, 15));
            g2.drawString("i", left + 26, top + 46);
            g2.setColor(BlueTheme.TEXT);
            g2.setFont(BlueTheme.font(Font.BOLD, 13));
            g2.drawString(emptyTitle, left + 56, top + 32);
            if (!emptyDetail.isBlank()) {
                g2.setColor(BlueTheme.MUTED);
                g2.setFont(BlueTheme.font(Font.PLAIN, 11));
                g2.drawString(emptyDetail, left + 56, top + 53);
            }
        } finally {
            g2.dispose();
        }
    }
}
