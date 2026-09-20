package com.senyalert.view.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.border.Border;

/** Centralized visual language for the Swing dashboard. */
public final class BlueTheme {
    public static final Color NAVY = new Color(10, 30, 58);
    public static final Color DEEP_BLUE = new Color(19, 53, 95);
    public static final Color PRIMARY = new Color(30, 116, 202);
    public static final Color PRIMARY_HOVER = new Color(46, 135, 226);
    public static final Color BACKGROUND = new Color(241, 247, 253);
    public static final Color CARD = Color.WHITE;
    public static final Color TEXT = new Color(25, 45, 72);
    public static final Color MUTED = new Color(100, 121, 147);
    public static final Color BORDER = new Color(211, 224, 238);
    public static final Color QUIET = new Color(203, 137, 23);
    public static final Color DANGER = new Color(206, 57, 72);
    public static final Color SUCCESS = new Color(36, 151, 104);

    private BlueTheme() {
    }

    public static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(16, 18, 16, 18));
    }

    public static void setPanelBackground(JComponent component) {
        component.setBackground(BACKGROUND);
        component.setOpaque(true);
    }
}
