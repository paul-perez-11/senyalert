package com.senyalert.security;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Self-service account screen. It deliberately exposes no role or permission controls. */
public final class AccountSettingsPanel extends JPanel {
    private final SecurityContext context;
    private final Runnable signOutAfterSave;
    private final JTextField username;
    private final JPasswordField currentPassword = new JPasswordField(24);
    private final JPasswordField newPassword = new JPasswordField(24);
    private final JPasswordField confirmPassword = new JPasswordField(24);
    private final JLabel status = new JLabel(" ");

    public AccountSettingsPanel(SecurityContext context, Runnable signOutAfterSave) {
        super(new BorderLayout(12, 12));
        this.context = Objects.requireNonNull(context);
        this.signOutAfterSave = Objects.requireNonNull(signOutAfterSave);
        this.username = new JTextField(context.session().username(), 24);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        add(new JLabel("<html><h2>Account</h2>Change only your own username or password. Saving signs you out so you can verify the new credentials.</html>"), BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 5, 6, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addField(fields, constraints, 0, "Username", username,
                "3–32 characters. This changes only your own signed-in account.");
        addField(fields, constraints, 1, "Current password", currentPassword,
                "Required to confirm this account change.");
        addField(fields, constraints, 2, "New password", newPassword,
                "Optional. Use 12–128 characters; leave blank to change only the username.");
        addField(fields, constraints, 3, "Confirm new password", confirmPassword,
                "Repeat the new password exactly, or leave blank when changing only the username.");
        add(fields, BorderLayout.CENTER);

        JButton save = SecurityUi.button("Save and sign out", "Audit this account change, revoke the current session, and return to the login screen.");
        JPanel footer = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(save);
        footer.add(status, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        save.addActionListener(event -> save(save));
    }

    private void save(JButton save) {
        char[] current = currentPassword.getPassword();
        char[] replacement = newPassword.getPassword();
        char[] confirmation = confirmPassword.getPassword();
        if (!Arrays.equals(replacement, confirmation)) {
            Arrays.fill(current, '\0'); Arrays.fill(replacement, '\0'); Arrays.fill(confirmation, '\0');
            status.setText("The new passwords do not match.");
            return;
        }
        Arrays.fill(confirmation, '\0');
        String requestedUsername = username.getText();
        save.setEnabled(false);
        SecurityUi.work(this, () -> {
            try {
                return context.service().updateOwnCredentials(context.session(), requestedUsername, current, replacement);
            } finally {
                Arrays.fill(current, '\0'); Arrays.fill(replacement, '\0');
            }
        }, saved -> {
            status.setText("Account updated for " + saved.username() + ". Returning to sign in.");
            currentPassword.setText(""); newPassword.setText(""); confirmPassword.setText("");
            SwingUtilities.invokeLater(signOutAfterSave);
        }, () -> save.setEnabled(true));
    }

    private static void addField(JPanel panel, GridBagConstraints constraints, int row, String label, javax.swing.JComponent field, String hint) {
        constraints.gridy = row; constraints.gridx = 0; constraints.weightx = 0;
        JLabel title = new JLabel(label); title.setLabelFor(field); panel.add(title, constraints);
        constraints.gridx = 1; constraints.weightx = 1;
        field.setToolTipText(hint); panel.add(field, constraints);
    }
}
