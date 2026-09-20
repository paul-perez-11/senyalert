package com.senyalert.view.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.border.EmptyBorder;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

/** Consistent icon-first button styling without remote runtime assets. */
public class StyledButton extends JButton {
    public StyledButton(String text, Ikon icon, Color background) {
        super(text);
        setFont(BlueTheme.font(java.awt.Font.BOLD, 13));
        setForeground(Color.WHITE);
        setBackground(background);
        setFocusPainted(false);
        setBorder(new EmptyBorder(9, 13, 9, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new Insets(0, 0, 0, 0));
        if (icon != null) {
            setIcon(FontIcon.of(icon, 15, Color.WHITE));
            setIconTextGap(8);
        }
    }
}
