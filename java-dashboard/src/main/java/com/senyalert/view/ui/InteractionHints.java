package com.senyalert.view.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Applies accessible hints to existing and dynamically-created Swing controls. */
public final class InteractionHints {
    private static final String POINTER_LISTENER_KEY = InteractionHints.class.getName() + ".pointerListener";
    private static final String TEXT_LISTENER_KEY = InteractionHints.class.getName() + ".textListener";
    private static final String TAB_LISTENER_KEY = InteractionHints.class.getName() + ".tabListener";

    private InteractionHints() { }
    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof WindowEvent w && w.getID() == WindowEvent.WINDOW_OPENED) apply(w.getWindow());
            if (event instanceof ContainerEvent c && c.getID() == ContainerEvent.COMPONENT_ADDED) apply(c.getChild());
        }, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.CONTAINER_EVENT_MASK);
    }
    public static void apply(Component component) {
        if (component instanceof JComponent c) {
            // A hand means an immediate action or navigation. Tables and lists select
            // information, so preserve their normal cursor instead of implying a button.
            boolean clickable = c instanceof AbstractButton || c instanceof JComboBox<?>;
            boolean tabbed = c instanceof JTabbedPane;
            boolean selectable = c instanceof JTable || c instanceof JList<?> || c instanceof JSlider || c instanceof JSpinner;
            boolean text = c instanceof JTextComponent;
            if (clickable || text) installPointerCursor(c);
            if (tabbed) installTabCursor((JTabbedPane) c);
            if (text) installTextCursor((JTextComponent) c);
            if (selectable) c.setCursor(Cursor.getDefaultCursor());
            if ((clickable || tabbed || selectable || text) && (c.getToolTipText() == null || c.getToolTipText().isBlank())) {
                String hint = c.getAccessibleContext() == null ? null : c.getAccessibleContext().getAccessibleDescription();
                if (hint == null || hint.isBlank()) {
                    if (c instanceof AbstractButton b) hint = b.getText() == null || b.getText().isBlank() ? "Activate this control." : b.getText();
                    else if (c instanceof JPasswordField) hint = "Enter the password; characters remain hidden.";
                    else if (c instanceof JTextComponent t) hint = t.isEditable() ? "Enter or edit this value." : "Read the displayed information.";
                    else if (c instanceof JComboBox<?>) hint = "Choose an option from this list.";
                    else if (c instanceof JTable) hint = "Select a row to inspect it; use the column headers to identify each field.";
                    else if (c instanceof JTabbedPane) hint = "Choose a tab to open that workspace.";
                    else hint = "Adjust or select this value.";
                }
                c.setToolTipText(hint);
            }
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) apply(child);
    }

    private static void installPointerCursor(JComponent component) {
        if (component instanceof JTextComponent) return;
        updatePointerCursor(component);
        if (Boolean.TRUE.equals(component.getClientProperty(POINTER_LISTENER_KEY))) {
            return;
        }
        component.putClientProperty(POINTER_LISTENER_KEY, Boolean.TRUE);
        component.addPropertyChangeListener("enabled", event -> updatePointerCursor(component));
    }

    private static void updatePointerCursor(JComponent component) {
        component.setCursor(Cursor.getPredefinedCursor(
                component.isEnabled() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private static void installTabCursor(JTabbedPane tabs) {
        if (Boolean.TRUE.equals(tabs.getClientProperty(TAB_LISTENER_KEY))) {
            return;
        }
        tabs.putClientProperty(TAB_LISTENER_KEY, Boolean.TRUE);
        tabs.setCursor(Cursor.getDefaultCursor());
        tabs.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                tabs.setCursor(Cursor.getPredefinedCursor(
                        tabs.isEnabled() && tabs.indexAtLocation(event.getX(), event.getY()) >= 0
                                ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent event) { tabs.setCursor(Cursor.getDefaultCursor()); }
        });
    }

    private static void installTextCursor(JTextComponent component) {
        updateTextCursor(component);
        if (Boolean.TRUE.equals(component.getClientProperty(TEXT_LISTENER_KEY))) {
            return;
        }
        component.putClientProperty(TEXT_LISTENER_KEY, Boolean.TRUE);
        component.addPropertyChangeListener("enabled", event -> updateTextCursor(component));
        component.addPropertyChangeListener("editable", event -> updateTextCursor(component));
    }

    private static void updateTextCursor(JTextComponent component) {
        component.setCursor(Cursor.getPredefinedCursor(
                component.isEnabled() && component.isEditable() ? Cursor.TEXT_CURSOR : Cursor.DEFAULT_CURSOR));
    }
}
