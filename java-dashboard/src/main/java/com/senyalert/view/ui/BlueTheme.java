package com.senyalert.view.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.border.Border;

/** Centralized visual language for the Swing dashboard. */
public final class BlueTheme {
    public static final Color NAVY = new Color(9, 31, 61);
    public static final Color DEEP_BLUE = new Color(18, 57, 105);
    public static final Color PRIMARY = new Color(21, 108, 194);
    public static final Color PRIMARY_HOVER = new Color(42, 132, 222);
    public static final Color BACKGROUND = new Color(244, 248, 253);
    public static final Color CARD = Color.WHITE;
    public static final Color SURFACE_TINT = new Color(234, 242, 251);
    public static final Color TEXT = new Color(20, 43, 73);
    public static final Color MUTED = new Color(79, 103, 132);
    public static final Color BORDER = new Color(199, 216, 234);
    public static final Color FOCUS = new Color(17, 122, 223);
    public static final Color INFO = new Color(15, 91, 165);
    public static final Color INFO_TINT = new Color(229, 241, 252);
    public static final Color QUIET = new Color(169, 106, 8);
    public static final Color QUIET_TINT = new Color(255, 246, 222);
    public static final Color DANGER = new Color(184, 42, 58);
    public static final Color DANGER_TINT = new Color(255, 235, 238);
    public static final Color SUCCESS = new Color(22, 126, 82);
    public static final Color SUCCESS_TINT = new Color(229, 246, 237);

    private BlueTheme() {
    }

    public static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(14, 16, 14, 16));
    }

    public static Border focusBorder(Border inner) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FOCUS, 2),
                inner);
    }

    public static void setPanelBackground(JComponent component) {
        component.setBackground(BACKGROUND);
        component.setOpaque(true);
    }
}
