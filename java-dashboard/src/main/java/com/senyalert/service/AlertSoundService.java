package com.senyalert.service;

import com.senyalert.model.AlertMode;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentStatus;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Delivers bounded local desktop feedback for newly-created, actionable incidents.
 *
 * <p>It deliberately uses only JRE/Windows facilities: a normal system beep for quiet watch,
 * and a generated PCM siren for audible alarms. All potentially blocking audio work stays off
 * the Swing event-dispatch thread.</p>
 */
public final class AlertSoundService implements AutoCloseable {
    private static final AudioFormat SIREN_FORMAT = new AudioFormat(44_100.0f, 16, 1, true, false);
    private static final int SIREN_CYCLES = 4;
    private static final int HIGH_TONE_MS = 230;
    private static final int LOW_TONE_MS = 230;
    private static final int GAP_MS = 65;

    private final boolean enabled;
    private final ExecutorService feedbackExecutor;
    private final Set<Long> announcedIncidentIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean alarmActive = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean soundFailureReported = new AtomicBoolean();
    private final Object trayLock = new Object();
    private volatile Consumer<String> failureReporter = ignored -> { };
    private volatile TrayIcon trayIcon;

    /** Creates a desktop feedback service unless the runtime is headless. */
    public AlertSoundService() {
        this(!GraphicsEnvironment.isHeadless());
    }

    private AlertSoundService(boolean enabled) {
        this.enabled = enabled;
        this.feedbackExecutor = enabled
                ? Executors.newSingleThreadExecutor(namedFactory("senyalert-alert-feedback"))
                : null;
    }

    /** A no-op instance for non-desktop integrations and tests. */
    public static AlertSoundService disabled() {
        return new AlertSoundService(false);
    }

    /**
     * Receives non-fatal feedback failures. The caller should surface the message to the
     * operator; the service itself never opens Swing dialogs.
     */
    public void setFailureReporter(Consumer<String> reporter) {
        this.failureReporter = reporter == null ? ignored -> { } : reporter;
    }

    /**
     * Announces an incident at most once, and only while it is actionable.
     *
     * <p>Quiet-watch incidents receive a standard system beep and tray notification. Audible
     * incidents receive the same tray notification plus one bounded local siren. If several
     * audible incidents arrive while the siren is active, each still gets a tray notification
     * but the local alarm is not queued repeatedly.</p>
     */
    public void notifyNewActionableIncident(Incident incident) {
        if (!enabled
                || closed.get()
                || incident == null
                || incident.status() != IncidentStatus.PENDING
                || !announcedIncidentIds.add(incident.id())) {
            return;
        }

        showDesktopNotification(incident);
        if (incident.alertMode() == AlertMode.QUIET) {
            submit(this::playSystemNotificationSound);
        } else {
            submit(this::playAudibleAlarmIfIdle);
        }
    }

    private void playSystemNotificationSound() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (RuntimeException failure) {
            reportSoundFailure("The quiet alert sound could not be played: " + detail(failure));
        }
    }

    private void playAudibleAlarmIfIdle() {
        if (!alarmActive.compareAndSet(false, true)) {
            return;
        }
        try {
            playGeneratedSiren();
        } finally {
            alarmActive.set(false);
        }
    }

    private void playGeneratedSiren() {
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, SIREN_FORMAT);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(lineInfo)) {
            line.open(SIREN_FORMAT);
            line.start();
            for (int cycle = 0; cycle < SIREN_CYCLES && !Thread.currentThread().isInterrupted(); cycle++) {
                writeTone(line, 920.0, HIGH_TONE_MS);
                writeTone(line, 640.0, LOW_TONE_MS);
                writeSilence(line, GAP_MS);
            }
            line.drain();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException failure) {
            reportSoundFailure("The audible alarm could not access the local audio device ("
                    + detail(failure) + "). A standard system beep was used instead.");
            playSystemNotificationSound();
        }
    }

    private static void writeTone(SourceDataLine line, double frequencyHz, int durationMs) {
        byte[] samples = pcmTone(frequencyHz, durationMs);
        line.write(samples, 0, samples.length);
    }

    private static void writeSilence(SourceDataLine line, int durationMs) {
        int sampleCount = (int) (SIREN_FORMAT.getSampleRate() * durationMs / 1_000.0);
        byte[] silence = new byte[sampleCount * SIREN_FORMAT.getFrameSize()];
        line.write(silence, 0, silence.length);
    }

    private static byte[] pcmTone(double frequencyHz, int durationMs) {
        int sampleCount = (int) (SIREN_FORMAT.getSampleRate() * durationMs / 1_000.0);
        byte[] data = new byte[sampleCount * SIREN_FORMAT.getFrameSize()];
        double amplitude = Short.MAX_VALUE * 0.72;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            double phase = 2.0 * Math.PI * frequencyHz * sampleIndex / SIREN_FORMAT.getSampleRate();
            short sample = (short) (Math.sin(phase) * amplitude);
            int offset = sampleIndex * 2;
            data[offset] = (byte) (sample & 0xff);
            data[offset + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return data;
    }

    private void showDesktopNotification(Incident incident) {
        if (!SystemTray.isSupported()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                TrayIcon icon = trayIconFor(incident.alertMode());
                String urgency = incident.alertMode() == AlertMode.AUDIBLE ? "Audible alarm" : "Quiet watch";
                String message = "Incident #" + incident.id() + " · " + incident.peopleCount()
                        + " people · " + incident.signalerCount() + " SOS";
                icon.displayMessage("SenyAlert — " + urgency, message,
                        incident.alertMode() == AlertMode.AUDIBLE
                                ? TrayIcon.MessageType.ERROR
                                : TrayIcon.MessageType.INFO);
            } catch (AWTException | SecurityException failure) {
                reportSoundFailure("Desktop alert notification was unavailable: " + detail(failure));
            }
        });
    }

    private TrayIcon trayIconFor(AlertMode mode) throws AWTException {
        synchronized (trayLock) {
            if (trayIcon == null) {
                trayIcon = new TrayIcon(createTrayImage(mode));
                trayIcon.setImageAutoSize(true);
                trayIcon.setToolTip("SenyAlert incident monitor");
                SystemTray.getSystemTray().add(trayIcon);
            } else {
                trayIcon.setImage(createTrayImage(mode));
            }
            return trayIcon;
        }
    }

    private static Image createTrayImage(AlertMode mode) {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            Color background = mode == AlertMode.AUDIBLE ? new Color(190, 35, 50) : new Color(40, 100, 185);
            graphics.setColor(background);
            graphics.fillOval(2, 2, 28, 28);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(14, 7, 4, 13);
            graphics.fillOval(14, 23, 4, 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void submit(Runnable task) {
        if (feedbackExecutor == null || closed.get()) {
            return;
        }
        try {
            feedbackExecutor.execute(task);
        } catch (RuntimeException rejected) {
            reportSoundFailure("Alert feedback could not be scheduled: " + detail(rejected));
        }
    }

    private void reportSoundFailure(String message) {
        if (soundFailureReported.compareAndSet(false, true)) {
            failureReporter.accept(message);
        }
    }

    private static String detail(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (feedbackExecutor != null) {
            feedbackExecutor.shutdownNow();
        }
        if (!enabled || !SystemTray.isSupported()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            synchronized (trayLock) {
                if (trayIcon != null) {
                    SystemTray.getSystemTray().remove(trayIcon);
                    trayIcon = null;
                }
            }
        });
    }

    private static ThreadFactory namedFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
