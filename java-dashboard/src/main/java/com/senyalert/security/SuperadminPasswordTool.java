package com.senyalert.security;

import java.io.Console;
import java.util.Arrays;
import javax.swing.*;

/** Run locally to create an environment verifier; never pass a password on the command line. */
public final class SuperadminPasswordTool {
    private SuperadminPasswordTool() { }
    public static void main(String[] args) {
        char[] password = null;
        char[] confirmation = null;
        try {
            Console console = System.console();
            if (console != null) {
                password = console.readPassword("New developer password (12-128 characters): ");
                confirmation = console.readPassword("Repeat developer password: ");
            } else {
                JPasswordField first = new JPasswordField(28), second = new JPasswordField(28);
                first.setToolTipText("Choose your own developer password. It is never written to disk.");
                second.setToolTipText("Repeat the developer password.");
                int result = JOptionPane.showConfirmDialog(null, new Object[]{"New developer password (12–128 characters)", first, "Repeat password", second},
                        "Create developer password verifier", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (result != JOptionPane.OK_OPTION) return;
                password = first.getPassword(); confirmation = second.getPassword(); first.setText(""); second.setText("");
            }
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords do not match.");
            String hash = PasswordHasher.hash(password);
            System.out.println("Set SENYALERT_SUPERADMIN_PASSWORD_HASH on the developer computer to this verifier:");
            System.out.println(hash);
            System.out.println("Keep your password private. Do not place this verifier in a public repository or client configuration export.");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
