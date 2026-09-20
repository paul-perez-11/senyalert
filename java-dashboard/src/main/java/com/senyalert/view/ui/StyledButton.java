package com.senyalert.view.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JButton;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

/** Consistent icon-first button styling without remote runtime assets. */
public class StyledButton extends JButton {
    private final Color baseBackground;
    private final Border normalBorder;

    public StyledButton(String text, Ikon icon, Color background) {
        super(text);
        baseBackground = background;
        normalBorder = new CompoundBorder(new LineBorder(darken(background, 0.18f)), new EmptyBorder(8, 12, 8, 13));
        setFont(BlueTheme.font(java.awt.Font.BOLD, 13));
        setForeground(Color.WHITE);
        setBackground(background);
        setFocusPainted(false);
        setBorder(normalBorder);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new Insets(0, 0, 0, 0));
        setRolloverEnabled(true);
        setOpaque(true);
        getAccessibleContext().setAccessibleName(text);
        getAccessibleContext().setAccessibleDescription("Action button: " + text);
        if (icon != null) {
            setIcon(FontIcon.of(icon, 15, Color.WHITE));
            setIconTextGap(8);
        }
        ChangeListener visualStateListener = this::updateVisualState;
        getModel().addChangeListener(visualStateListener);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                setBorder(BlueTheme.focusBorder(normalBorder));
            }

            @Override
            public void focusLost(FocusEvent event) {
                setBorder(normalBorder);
            }
        });
        updateVisualState(null);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (baseBackground != null) {
            updateVisualState(null);
        }
    }

    private void updateVisualState(ChangeEvent ignored) {
        if (baseBackground == null) {
            return;
        }
        if (!isEnabled()) {
            setBackground(soften(baseBackground, 0.56f));
            setForeground(new Color(245, 248, 252));
            setCursor(Cursor.getDefaultCursor());
            return;
        }
        if (getModel().isPressed()) {
            setBackground(darken(baseBackground, 0.14f));
        } else if (getModel().isRollover()) {
            setBackground(soften(baseBackground, 0.12f));
        } else {
            setBackground(baseBackground);
        }
        setForeground(Color.WHITE);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static Color soften(Color color, float amount) {
        return new Color(
                Math.round(color.getRed() + (255 - color.getRed()) * amount),
                Math.round(color.getGreen() + (255 - color.getGreen()) * amount),
                Math.round(color.getBlue() + (255 - color.getBlue()) * amount));
    }

    private static Color darken(Color color, float amount) {
        return new Color(
                Math.round(color.getRed() * (1.0f - amount)),
                Math.round(color.getGreen() * (1.0f - amount)),
                Math.round(color.getBlue() * (1.0f - amount)));
    }
}
