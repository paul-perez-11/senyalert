package com.senyalert.security;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Administrator account editor. Deleted-account activity remains preserved in the append-only audit trail. */
public final class UserManagementPanel extends JPanel {
    private final SecurityContext context;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Username", "Role", "Enabled", "Permissions", "Last changed (UTC)"}, 0) {
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private List<UserAccount> users = List.of();
    private final JLabel status = new JLabel(" ");

    public UserManagementPanel(SecurityContext context) {
        super(new BorderLayout(12, 12)); this.context = context;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel help = new JLabel("<html><h2>User management</h2>Admins have full local access. Configure each user's permissions below.<br>Use Reset password for the safe local forgot-password process. Deleting an account removes its local sign-in while preserving its audit history.</html>");
        add(help, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(30);
        table.setToolTipText("Select an account and choose Edit account to change its role, permissions, password, or enabled state.");
        add(new JScrollPane(table), BorderLayout.CENTER);
        JButton add = SecurityUi.button("Add account", "Create a local admin or user with an individual password.");
        JButton edit = SecurityUi.button("Edit account", "Change the selected account. Leave password empty to keep it; disable instead of deleting its history.");
        JButton resetPassword = SecurityUi.button("Reset password", "Set a replacement password for the selected account. The old password is never displayed or recovered.");
        JButton delete = SecurityUi.button("Delete account", "Remove the selected local sign-in account after a typed confirmation. Its audit history remains preserved.");
        JButton refresh = SecurityUi.button("Refresh", "Reload accounts and current permissions from SQLite.");
        JPanel footer = new JPanel(new BorderLayout()); JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add); actions.add(edit); actions.add(resetPassword); actions.add(delete); actions.add(refresh); footer.add(actions, BorderLayout.NORTH); footer.add(status, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        boolean allowed = context.can(Permission.MANAGE_USERS); add.setEnabled(allowed); edit.setEnabled(allowed); resetPassword.setEnabled(allowed); delete.setEnabled(allowed); refresh.setEnabled(allowed);
        add.addActionListener(e -> showEditor(null));
        edit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { status.setText("Select an account first."); return; }
            UserAccount selected = users.get(table.convertRowIndexToModel(row));
            if (selected.id() == context.session().userId()) {
                status.setText("Use Account settings to change your own username or password. Another administrator manages your access."); return;
            }
            showEditor(selected);
        });
        resetPassword.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { status.setText("Select an account first."); return; }
            UserAccount selected = users.get(table.convertRowIndexToModel(row));
            if (selected.id() == context.session().userId()) {
                status.setText("Use Account settings to change your own password."); return;
            }
            showPasswordReset(selected);
        });
        delete.addActionListener(e -> deleteSelectedAccount());
        refresh.addActionListener(e -> refresh());
        if (allowed) refresh(); else status.setText("User management requires an administrator account.");
    }

    private void deleteSelectedAccount() {
        try {
            context.require(Permission.MANAGE_USERS);
        } catch (RuntimeException denied) {
            JOptionPane.showMessageDialog(this, denied.getMessage());
            return;
        }
        int row = table.getSelectedRow();
        if (row < 0) {
            status.setText("Select an account first.");
            return;
        }
        UserAccount selected = users.get(table.convertRowIndexToModel(row));
        if (selected.id() == context.session().userId()) {
            status.setText("You cannot delete the account currently signed in.");
            return;
        }
        JTextField confirmation = new JTextField(24);
        confirmation.setToolTipText("Type the selected username exactly to confirm account deletion.");
        JPanel prompt = new JPanel(new BorderLayout(8, 8));
        prompt.add(new JLabel("<html>Delete local sign-in account <b>" + selected.username()
                + "</b>? The account will no longer be able to sign in.<br>Incident data and append-only audit history are retained. Type the username to confirm.</html>"), BorderLayout.NORTH);
        prompt.add(confirmation, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, prompt, "Delete account", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (!selected.username().equalsIgnoreCase(confirmation.getText().strip())) {
            status.setText("Account deletion cancelled; the username confirmation did not match.");
            return;
        }
        SecurityUi.work(this, () -> context.service().deleteUser(context.session(), selected.id()), deleted -> {
            status.setText("Deleted account " + deleted.username() + ". Its audit history remains available.");
            refresh();
        }, null);
    }

    public void refresh() {
        SecurityUi.work(this, () -> context.service().listUsers(context.session()), values -> {
            users = values; model.setRowCount(0);
            for (UserAccount user : users) model.addRow(new Object[]{user.username(), user.role().name().toLowerCase(Locale.ROOT), user.enabled() ? "Yes" : "No",
                    user.role() == Role.ADMIN ? "All local permissions" : user.permissions().size() + " selected", user.updatedAt()});
            status.setText(users.size() + " accounts. Passwords cannot be recovered; set a new password if needed.");
        }, null);
    }

    private void showEditor(UserAccount existing) {
        try { context.require(Permission.MANAGE_USERS); }
        catch (RuntimeException denied) { JOptionPane.showMessageDialog(this, denied.getMessage()); return; }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), existing == null ? "Add account" : "Edit " + existing.username(), Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(12, 12)); content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 10));
        JTextField name = new JTextField(existing == null ? "" : existing.username()); name.setEditable(existing == null);
        name.setToolTipText("3–32 characters: letters, digits, dot, dash, underscore; start with a letter. Usernames stay stable for audit history.");
        JComboBox<Role> role = new JComboBox<>(new Role[]{Role.USER, Role.ADMIN}); role.setSelectedItem(existing == null ? Role.USER : existing.role());
        role.setToolTipText("Admin has every local permission. User has only the selected permissions."); role.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPasswordField password = new JPasswordField(); password.setToolTipText(existing == null ? "Set a new password of 12–128 characters." : "Optional new password of 12–128 characters; blank keeps the current password.");
        JPasswordField confirm = new JPasswordField(); confirm.setToolTipText("Repeat the new password exactly; leave empty when retaining it.");
        JCheckBox enabled = new JCheckBox("Account enabled", existing == null || existing.enabled()); enabled.setToolTipText("Disabled accounts cannot sign in; their actions remain in the audit log."); enabled.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fields.add(new JLabel("Username")); fields.add(name); fields.add(new JLabel("Role")); fields.add(role);
        fields.add(new JLabel(existing == null ? "Password" : "New password (optional)")); fields.add(password);
        fields.add(new JLabel("Confirm password")); fields.add(confirm); fields.add(new JLabel("Access")); fields.add(enabled);
        content.add(fields, BorderLayout.NORTH);
        JPanel grants = new JPanel(new GridLayout(0, 1)); Map<Permission, JCheckBox> boxes = new EnumMap<>(Permission.class);
        for (Permission permission : Permission.values()) {
            JCheckBox box = new JCheckBox(permission.description(), existing == null ? permission == Permission.VIEW_INCIDENTS : existing.permissions().contains(permission));
            box.setToolTipText(permission == Permission.MANAGE_USERS ? "Choose the admin role to allow user management." : "Allow this account to: " + permission.description().toLowerCase(Locale.ROOT));
            box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); boxes.put(permission, box); grants.add(box);
        }
        Runnable updateGrants = () -> boxes.forEach((permission, box) -> box.setEnabled(role.getSelectedItem() == Role.USER && permission != Permission.MANAGE_USERS));
        role.addActionListener(e -> updateGrants.run()); updateGrants.run();
        JScrollPane permissions = new JScrollPane(grants); permissions.setBorder(BorderFactory.createTitledBorder("Permissions for the user role")); permissions.setPreferredSize(new Dimension(540, 340));
        permissions.setToolTipText("Review every permission; unselected permissions are denied."); content.add(permissions, BorderLayout.CENTER);
        JButton save = SecurityUi.button("Save account", "Save this account and record exactly which settings changed in the audit log.");
        JButton cancel = SecurityUi.button("Cancel", "Close without changing this account.");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save); content.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            char[] secret = password.getPassword(), repeated = confirm.getPassword();
            if (!Arrays.equals(secret, repeated)) { Arrays.fill(secret, '\0'); Arrays.fill(repeated, '\0'); JOptionPane.showMessageDialog(dialog, "The two passwords do not match."); return; }
            Arrays.fill(repeated, '\0');
            Role selectedRole = (Role) role.getSelectedItem();
            Set<Permission> selected = EnumSet.noneOf(Permission.class);
            if (selectedRole == Role.USER) boxes.forEach((permission, box) -> { if (permission != Permission.MANAGE_USERS && box.isSelected()) selected.add(permission); });
            String selectedName = name.getText(); boolean selectedEnabled = enabled.isSelected();
            save.setEnabled(false); cancel.setEnabled(false);
            SecurityUi.work(dialog, () -> {
                try {
                    if (existing == null) {
                        UserAccount created = context.service().createUser(context.session(), selectedName, secret, selectedRole, selected);
                        if (!selectedEnabled) return context.service().updateUser(context.session(), created.id(), selectedRole, selected, false, null);
                        return created;
                    }
                    return context.service().updateUser(context.session(), existing.id(), selectedRole, selected, selectedEnabled, secret);
                } finally { Arrays.fill(secret, '\0'); }
            }, saved -> {
                dialog.dispose();
                if (saved.id() == context.session().userId()) { status.setText("Your account changed. Close SenyAlert and sign in again."); model.setRowCount(0); }
                else refresh();
            }, () -> { password.setText(""); confirm.setText(""); save.setEnabled(true); cancel.setEnabled(true); });
        });
        dialog.setContentPane(content); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void showPasswordReset(UserAccount existing) {
        try { context.require(Permission.MANAGE_USERS); }
        catch (RuntimeException denied) { JOptionPane.showMessageDialog(this, denied.getMessage()); return; }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Reset password: " + existing.username(), Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(10, 10)); content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.add(new JLabel("<html>Set a replacement password for <b>" + existing.username()
                + "</b>. The old password cannot be viewed or recovered. The account's existing role, permissions, and enabled state remain unchanged.</html>"), BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 10));
        JPasswordField password = new JPasswordField(24); JPasswordField confirmation = new JPasswordField(24);
        password.setToolTipText("Set a replacement password of 12–128 characters.");
        confirmation.setToolTipText("Repeat the replacement password exactly.");
        fields.add(new JLabel("Replacement password")); fields.add(password); fields.add(new JLabel("Confirm password")); fields.add(confirmation);
        content.add(fields, BorderLayout.CENTER);
        JButton cancel = SecurityUi.button("Cancel", "Close without changing the account password.");
        JButton save = SecurityUi.button("Reset password", "Save the replacement password and audit who performed the reset.");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(save); content.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            char[] replacement = password.getPassword(); char[] repeated = confirmation.getPassword();
            if (!Arrays.equals(replacement, repeated)) {
                Arrays.fill(replacement, '\0'); Arrays.fill(repeated, '\0');
                JOptionPane.showMessageDialog(dialog, "The two passwords do not match."); return;
            }
            Arrays.fill(repeated, '\0'); save.setEnabled(false); cancel.setEnabled(false);
            SecurityUi.work(dialog, () -> {
                try { return context.service().resetUserPassword(context.session(), existing.id(), replacement); }
                finally { Arrays.fill(replacement, '\0'); }
            }, updated -> {
                dialog.dispose();
                if (updated.id() == context.session().userId()) {
                    status.setText("Your password changed. Sign in again before continuing.");
                    model.setRowCount(0);
                } else {
                    status.setText("Password reset for " + updated.username() + ".");
                    refresh();
                }
            }, () -> { password.setText(""); confirmation.setText(""); save.setEnabled(true); cancel.setEnabled(true); });
        });
        dialog.setContentPane(content); dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
}
