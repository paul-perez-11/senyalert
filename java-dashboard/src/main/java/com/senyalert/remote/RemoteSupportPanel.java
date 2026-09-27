package com.senyalert.remote;

import com.senyalert.view.ui.BlueTheme;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Client-initiated grant. The capability is never placed in a selectable or revealable field. */
public final class RemoteSupportPanel extends JPanel implements AutoCloseable {
    private final RemoteSupportService service;
    private final JLabel state = new JLabel("Remote support is off.");
    private final JLabel masked = new JLabel("No active link");
    private final JButton start = button("Start support", "Create a temporary connection for your developer; expires after 30 minutes.");
    private final JButton copy = button("Copy Link", "Audit this copy and place the private support link on the clipboard.");
    private final JButton stop = button("Stop support", "Immediately revoke the connection and invalidate its link.");
    private final Timer refresh;

    public RemoteSupportPanel(RemoteBackend backend) {
        super(new BorderLayout(12, 18));
        setBackground(BlueTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        service = new RemoteSupportService(backend, message -> SwingUtilities.invokeLater(() -> state.setText(message)));
        JLabel guide = new JLabel("<html><h2>Remote Support</h2>"
                + "Invite your developer to help with SenyAlert settings, cameras, users and incident records.<br><br>"
                + "1. Contact your developer and agree on a support session.<br>"
                + "2. Press <b>Start support</b>, then <b>Copy Link</b>.<br>"
                + "3. Send the link privately in your usual messaging app.<br>"
                + "4. Press <b>Stop support</b> when the work is finished.<br><br>"
                + "The developer must also have this client's separate support key. The connection expires after 30 minutes.<br>"
                + "Every support action is recorded in Audit Logs. Your files remain on this computer.<br><br>"
                + "<b>For security reasons you can only copy the link by pressing Copy Link.</b><br>"
                + "This records the copy in the audit log. The link cannot be selected or revealed here.</html>");
        add(guide, BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        controls.setBorder(BlueTheme.cardBorder());
        masked.setToolTipText("The support link is hidden. Use Copy Link to copy it with an audit record.");
        controls.add(masked); controls.add(start); controls.add(copy); controls.add(stop);
        add(controls, BorderLayout.CENTER);
        state.setToolTipText("Current support connection state and expiry time.");
        add(state, BorderLayout.SOUTH);
        start.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Allow your developer to manage this client's settings, users and incidents for up to 30 minutes?\n"
                    + "You can stop support at any time.", "Start remote support", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) return;
            start.setEnabled(false);
            service.start().whenComplete((nothing, failure) -> SwingUtilities.invokeLater(() -> {
                if (failure != null) JOptionPane.showMessageDialog(this,
                        "Remote support could not start. Install cloudflared and provision SENYALERT_SUPPORT_SECRET first.\n"
                        + "See docs/remote-support.md for setup.", "Support setup required", JOptionPane.WARNING_MESSAGE);
                updateButtons();
            }));
        });
        copy.addActionListener(event -> {
            try { service.copyLink(); state.setText("Link copied and recorded in Audit Logs. Send it privately to your developer."); }
            catch (RuntimeException failure) { JOptionPane.showMessageDialog(this, failure.getMessage(), "Copy failed", JOptionPane.ERROR_MESSAGE); }
        });
        stop.addActionListener(event -> { service.stop("client_revoked"); updateButtons(); });
        refresh = new Timer(1000, event -> updateButtons());
        refresh.start();
        updateButtons();
    }

    private void updateButtons() {
        start.setEnabled(!service.active()); copy.setEnabled(service.ready()); stop.setEnabled(service.active());
        masked.setText(service.ready() ? "●●●●●●●●●●●●●●●●●●●●●●●●" : service.active() ? "Preparing link..." : "No active link");
    }

    private static JButton button(String text, String tooltip) {
        JButton button = new JButton(text); button.setToolTipText(tooltip);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return button;
    }

    @Override public void close() { refresh.stop(); service.close(); }
}
