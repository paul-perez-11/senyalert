package com.senyalert.security;

import java.awt.*;
import java.util.Arrays;
import java.util.Optional;
import javax.swing.*;

/** Modal desktop login with explicit first-admin setup and no default credentials. */
public final class LoginDialog extends JDialog {
    private final SecurityService service;
    private final boolean bootstrap;
    private final JTextField username = new JTextField(24);
    private final JPasswordField password = new JPasswordField(24);
    private final JPasswordField confirmation = new JPasswordField(24);
    private final JCheckBox developer = new JCheckBox("Sign in as developer superadmin");
    private final JButton submit = SecurityUi.button("Sign in", "Authenticate and open the dashboard for your role.");
    private final JButton forgot = SecurityUi.button("Forgot password", "Show the safe local administrator reset process without revealing or bypassing a password.");
    private final JButton cancel = SecurityUi.button("Exit", "Close SenyAlert without signing in.");
    private final JLabel message = new JLabel(" ");
    private Session session;

    private LoginDialog(Window owner, SecurityService service) {
        super(owner, "SenyAlert | Sign in", ModalityType.APPLICATION_MODAL);
        this.service = service;
        bootstrap = service.needsBootstrap();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 16));
        content.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        JLabel intro = new JLabel(bootstrap
                ? "<html><h2>Set up this SenyAlert installation</h2>Create the first local administrator.<br>Use your own password of 12–128 characters.</html>"
                : "<html><h2>Sign in to SenyAlert</h2>Use the account assigned by your administrator.</html>");
        content.add(intro, BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5); g.fill = GridBagConstraints.HORIZONTAL;
        addField(fields, g, 0, "Username", username);
        addField(fields, g, 1, "Password", password);
        if (bootstrap) addField(fields, g, 2, "Confirm password", confirmation);
        username.setToolTipText("Your local username. Use superadmin for the separate developer dashboard.");
        password.setToolTipText("Enter your password. Passwords are hidden and stored only as salted hashes.");
        confirmation.setToolTipText("Repeat the new administrator password exactly.");
        developer.setToolTipText("Use the developer hash configured on this computer; this does not create a local admin.");
        developer.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        g.gridx = 0;
        g.gridy = bootstrap ? 3 : 2;
        g.gridwidth = 2;
        fields.add(developer, g);
        g.gridx = 0;
        g.gridy++;
        fields.add(message, g);
        content.add(fields, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(forgot); buttons.add(cancel); buttons.add(submit);
        content.add(buttons, BorderLayout.SOUTH);
        developer.addActionListener(e -> {
            username.setText(developer.isSelected() ? "superadmin" : "");
            confirmation.setEnabled(!developer.isSelected());
            submit.setText(developer.isSelected() || !bootstrap ? "Sign in" : "Create administrator");
        });
        if (bootstrap) submit.setText("Create administrator");
        submit.addActionListener(e -> signIn());
        forgot.addActionListener(e -> showForgotPasswordHelp());
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(submit);
        setContentPane(content); pack(); setMinimumSize(new Dimension(530, getHeight())); setLocationRelativeTo(owner);
    }

    public static Optional<Session> login(Window owner, SecurityService service) {
        if (!SwingUtilities.isEventDispatchThread()) {
            final java.util.concurrent.atomic.AtomicReference<Optional<Session>> result = new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
            try { SwingUtilities.invokeAndWait(() -> result.set(login(owner, service))); }
            catch (Exception failure) { throw new IllegalStateException("Could not display login", failure); }
            return result.get();
        }
        LoginDialog dialog = new LoginDialog(owner, service);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.session);
    }

    private void signIn() {
        String name = username.getText();
        char[] secret = password.getPassword();
        char[] repeated = confirmation.getPassword();
        boolean setup = bootstrap && !developer.isSelected();
        if (setup && !Arrays.equals(secret, repeated)) {
            Arrays.fill(secret, '\0'); Arrays.fill(repeated, '\0');
            message.setText("The two passwords do not match."); return;
        }
        Arrays.fill(repeated, '\0');
        submit.setEnabled(false); forgot.setEnabled(false); cancel.setEnabled(false); username.setEnabled(false); password.setEnabled(false); developer.setEnabled(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        message.setText("Checking credentials…");
        SecurityUi.work(this, () -> {
            try { return setup ? Optional.of(service.bootstrapAdmin(name, secret)) : service.authenticate(name, secret); }
            finally { Arrays.fill(secret, '\0'); }
        }, result -> {
            if (result.isPresent()) { session = result.get(); dispose(); }
            else message.setText("Sign-in failed. Check credentials or wait 60 seconds after repeated attempts.");
        }, () -> {
            password.setText(""); confirmation.setText("");
            submit.setEnabled(true); forgot.setEnabled(true); cancel.setEnabled(true); username.setEnabled(true); password.setEnabled(true); developer.setEnabled(true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        });
    }

    private void showForgotPasswordHelp() {
        JOptionPane.showMessageDialog(this,
                "Passwords cannot be recovered or bypassed.\n\n"
                        + "Ask a signed-in local administrator to open Settings > User management, select your account, and choose Reset password. "
                        + "The administrator sets a replacement password; your old session is then revoked.\n\n"
                        + "For the developer superadmin account, contact the developer who controls this installation's environment configuration.",
                "Forgot password", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void addField(JPanel panel, GridBagConstraints g, int row, String label, JComponent field) {
        g.gridy = row; g.gridx = 0; g.weightx = 0; g.gridwidth = 1;
        JLabel text = new JLabel(label); text.setLabelFor(field); panel.add(text, g);
        g.gridx = 1; g.weightx = 1; panel.add(field, g);
    }
}
