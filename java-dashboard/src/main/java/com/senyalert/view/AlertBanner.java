package com.senyalert.view;

import com.senyalert.model.AlertMode;
import com.senyalert.model.Incident;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.RoundedPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.LongConsumer;
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
    private LongConsumer selectionAction = ignored -> { };

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
        installSelectionHandler(this);
        clearAlert();
    }

    /** Opens the active alert's row in the live queue when the banner is pressed. */
    void setSelectionAction(LongConsumer selectionAction) {
        this.selectionAction = selectionAction == null ? ignored -> { } : selectionAction;
        updateSelectionHint();
    }

    void showAlert(Incident incident) {
        if (currentIncident != null && currentIncident.id() == incident.id()) {
            return;
        }
        currentIncident = incident;
        brightPhase = true;
        updateColors();
        updateSelectionHint();
        pulseTimer.start();
    }

    void clearAlert() {
        pulseTimer.stop();
        currentIncident = null;
        brightPhase = false;
        setBackground(new Color(228, 244, 237));
        icon.setIcon(FontIcon.of(FontAwesomeSolid.CHECK, 15, BlueTheme.SUCCESS));
        title.setForeground(BlueTheme.SUCCESS.darker());
        detail.setForeground(BlueTheme.TEXT);
        title.setText("MONITORING");
        detail.setText("All clear");
        updateSelectionHint();
    }

    private void pulse() {
        if (currentIncident == null) {
            return;
        }
        brightPhase = !brightPhase;
        updateColors();
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

    private void installSelectionHandler(Component component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (currentIncident != null) {
                    selectionAction.accept(currentIncident.id());
                }
            }
        });
        if (component instanceof javax.swing.JComponent target) {
            target.setCursor(Cursor.getDefaultCursor());
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                installSelectionHandler(child);
            }
        }
    }

    private void updateSelectionHint() {
        boolean selectable = currentIncident != null;
        String hint = selectable
                ? "Open incident #" + currentIncident.id() + " in the live incident queue."
                : "No active alert. Monitoring continues automatically.";
        setToolTipText(hint);
        getAccessibleContext().setAccessibleDescription(hint);
        Cursor cursor = Cursor.getPredefinedCursor(selectable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR);
        setCursor(cursor);
        for (Component child : getComponents()) {
            applySelectionCursor(child, cursor, hint);
        }
    }

    private static void applySelectionCursor(Component component, Cursor cursor, String hint) {
        component.setCursor(cursor);
        if (component instanceof javax.swing.JComponent target) {
            target.setToolTipText(hint);
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                applySelectionCursor(child, cursor, hint);
            }
        }
    }

    private static Color soften(Color color, float amount) {
        int red = (int) (color.getRed() + (255 - color.getRed()) * amount);
        int green = (int) (color.getGreen() + (255 - color.getGreen()) * amount);
        int blue = (int) (color.getBlue() + (255 - color.getBlue()) * amount);
        return new Color(Math.min(255, red), Math.min(255, green), Math.min(255, blue));
    }
}
