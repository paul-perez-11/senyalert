package com.senyalert.view;

import com.senyalert.model.AlertMode;
import com.senyalert.model.Incident;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** A bounded, cancellable Swing pulse rather than an uncontrolled alert loop. */
final class AlertBanner extends RoundedPanel {
    private final JLabel icon = new JLabel();
    private final JLabel title = new JLabel();
    private final JLabel detail = new JLabel();
    private final Timer pulseTimer;
    private Incident currentIncident;
    private boolean brightPhase;
    private int beepsRemaining;

    AlertBanner() {
        super(14);
        setLayout(new BorderLayout(7, 0));
        setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        setMinimumSize(new java.awt.Dimension(132, 58));
        setPreferredSize(new java.awt.Dimension(144, 62));

        icon.setHorizontalAlignment(JLabel.CENTER);
        icon.setPreferredSize(new java.awt.Dimension(25, 25));
        add(icon, BorderLayout.WEST);

        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new javax.swing.BoxLayout(labels, javax.swing.BoxLayout.Y_AXIS));
        title.setFont(BlueTheme.font(Font.BOLD, 11));
        detail.setFont(BlueTheme.font(Font.PLAIN, 10));
        labels.add(title);
        labels.add(detail);
        add(labels, BorderLayout.CENTER);

        pulseTimer = new Timer(480, event -> pulse());
        clearAlert();
    }

    void showAlert(Incident incident) {
        if (currentIncident != null && currentIncident.id() == incident.id()) {
            return;
        }
        currentIncident = incident;
        brightPhase = true;
        beepsRemaining = incident.alertMode() == AlertMode.AUDIBLE ? 3 : 0;
        updateColors();
        if (beepsRemaining > 0) {
            Toolkit.getDefaultToolkit().beep();
            beepsRemaining--;
        }
        pulseTimer.start();
    }

    void clearAlert() {
        pulseTimer.stop();
        currentIncident = null;
        brightPhase = false;
        beepsRemaining = 0;
        setBackground(new Color(228, 244, 237));
        icon.setIcon(FontIcon.of(FontAwesomeSolid.CHECK, 15, BlueTheme.SUCCESS));
        title.setForeground(BlueTheme.SUCCESS.darker());
        detail.setForeground(BlueTheme.TEXT);
        title.setText("MONITORING");
        detail.setText("All clear");
    }

    private void pulse() {
        if (currentIncident == null) {
            return;
        }
        brightPhase = !brightPhase;
        updateColors();
        if (currentIncident.alertMode() == AlertMode.AUDIBLE && beepsRemaining > 0 && brightPhase) {
            Toolkit.getDefaultToolkit().beep();
            beepsRemaining--;
        }
    }

    private void updateColors() {
        boolean audible = currentIncident.alertMode() == AlertMode.AUDIBLE;
        Color accent = audible ? BlueTheme.DANGER : BlueTheme.QUIET;
        setBackground(brightPhase ? soften(accent, 0.15f) : soften(accent, 0.05f));
        icon.setIcon(FontIcon.of(audible ? FontAwesomeSolid.EXCLAMATION_TRIANGLE : FontAwesomeSolid.EYE,
                16, accent));
        title.setForeground(accent.darker());
        detail.setForeground(BlueTheme.TEXT);
        title.setText(audible ? "AUDIBLE ALERT" : "QUIET WATCH");
        detail.setText("#" + currentIncident.id() + " · " + currentIncident.peopleCount() + " people · "
                + currentIncident.signalerCount() + " SOS");
    }

    private static Color soften(Color color, float amount) {
        int red = (int) (color.getRed() + (255 - color.getRed()) * amount);
        int green = (int) (color.getGreen() + (255 - color.getGreen()) * amount);
        int blue = (int) (color.getBlue() + (255 - color.getBlue()) * amount);
        return new Color(Math.min(255, red), Math.min(255, green), Math.min(255, blue));
    }
}
