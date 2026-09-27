package com.senyalert.security;

import java.awt.Cursor;
import java.awt.Component;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;

final class SecurityUi {
    private SecurityUi() { }
    static JButton button(String text, String help) {
        JButton button = new JButton(text);
        button.setToolTipText(help);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
    static <T> void work(Component owner, Callable<T> work, Consumer<T> success, Runnable finish) {
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return work.call(); }
            protected void done() {
                try { success.accept(get()); }
                catch (Exception failure) {
                    Throwable cause = failure;
                    while (cause.getCause() != null) cause = cause.getCause();
                    JOptionPane.showMessageDialog(owner, cause.getMessage(), "SenyAlert", JOptionPane.ERROR_MESSAGE);
                } finally { if (finish != null) finish.run(); }
            }
        }.execute();
    }
}
