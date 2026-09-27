package com.senyalert.view;

import com.senyalert.service.BenchmarkService;
import com.senyalert.service.BenchmarkService.Type;
import com.senyalert.service.ExportDefaults;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.NativeFileDialogs;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Guided entry point for isolated Chapter 4 trials. All processes run off the Swing EDT. */
public final class BenchmarkPanel extends JPanel {
    private final BenchmarkService service;
    private final Path projectRoot;
    private final JComboBox<Type> test = new JComboBox<>(Type.values());
    private final JTextField outputFolder;
    private final JTextField python;
    private final JTextField trialLabel = field("scenario-1-trial-1", "Name this scenario and repetition, for example bright-near-trial-1.");
    private final JTextArea conditions = new JTextArea(3, 30);
    private final JPanel optionsPanel = new JPanel(new GridBagLayout());
    private final Map<String, JComponent> options = new LinkedHashMap<>();
    private final JEditorPane guide = new JEditorPane("text/html", "");
    private final JTextArea log = new JTextArea();
    private final JButton start = button("Run test", "Start this isolated test and save its settings, log, and outputs.");
    private final JButton cancel = button("Cancel test", "Stop this benchmark process. Partial files are retained and marked cancelled.");
    private final JButton openResults = button("Open latest results", "Open the folder of the last completed or cancelled trial.");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Ready. Choose a test and record the test conditions.");
    private final JPanel setup;
    private final Timer elapsedTimer;
    private BooleanSupplier authorized = () -> false;
    private BiConsumer<String, String> audit = (action, detail) -> { throw new IllegalStateException("Benchmark audit is not configured."); };
    private SwingWorker<BenchmarkService.Result, String> worker;
    private BenchmarkService.Cancellation cancellation;
    private Path latestResults;
    private long startedAt;
    private boolean shuttingDown;

    public BenchmarkPanel(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        service = new BenchmarkService(this.projectRoot);
        outputFolder = field(ExportDefaults.directory(ExportDefaults.Kind.BENCHMARK).toString(), "Choose a parent folder. Every trial creates a new subfolder; existing results are preserved.");
        python = field(BenchmarkService.defaultPython(this.projectRoot), "The Python executable with SenyAlert's dependencies; a program path only, without command arguments.");
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        BlueTheme.setPanelBackground(this);
        JLabel heading = new JLabel("Benchmarking · repeatable tests for Chapter 4");
        heading.setFont(BlueTheme.font(Font.BOLD, 20)); heading.setForeground(BlueTheme.TEXT);
        add(heading, BorderLayout.NORTH);

        setup = new JPanel(new GridBagLayout()); setup.setBackground(BlueTheme.CARD);
        setup.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        test.setToolTipText("Choose which part of SenyAlert to measure. Each test has a different measurement scope.");
        test.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addRow(setup, 0, "Test", test);
        addRow(setup, 1, "Trial label", trialLabel);
        addRow(setup, 2, "Results folder", withBrowse(outputFolder, true, "Choose results folder", null));
        addRow(setup, 3, "Python executable", withBrowse(python, false, "Choose Python executable", null));
        conditions.setLineWrap(true); conditions.setWrapStyleWord(true);
        conditions.setToolTipText("Record laptop/CPU/RAM, camera or video name, resolution, lighting, distance, other running apps, and trial number. Do not enter passwords.");
        conditions.setText("Laptop/CPU/RAM: \nCamera or video / resolution: \nLighting / distance / trial: ");
        addRow(setup, 4, "Test conditions", new JScrollPane(conditions));
        GridBagConstraints optionConstraints = constraints(0, 5); optionConstraints.gridwidth = 2;
        setup.add(optionsPanel, optionConstraints);
        optionsPanel.setOpaque(false);
        JScrollPane settingsScroll = new JScrollPane(setup); settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        settingsScroll.setMinimumSize(new Dimension(440, 220));

        guide.setEditable(false); guide.setBackground(BlueTheme.CARD);
        guide.setToolTipText("Step-by-step instructions, measurement scope, and limitations for the selected test.");
        JScrollPane guideScroll = new JScrollPane(guide); guideScroll.setMinimumSize(new Dimension(300, 220));
        JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, settingsScroll, guideScroll);
        upper.setResizeWeight(0.55); upper.setDividerLocation(620); upper.setContinuousLayout(true);
        upper.setToolTipText("Drag to adjust the space for test settings and instructions.");

        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setToolTipText("Live test messages and the final summary. The complete log is also saved in the trial folder.");
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Run log and result summary"));
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, logScroll);
        main.setResizeWeight(0.7); main.setDividerLocation(420); main.setContinuousLayout(true);
        main.setToolTipText("Drag to adjust the space for settings and the run log.");
        add(main, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6)); bottom.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); buttons.setOpaque(false);
        JButton fullGuide = button("Simple guide", "Open the nontechnical benchmarking guide with Chapter 4 reporting steps.");
        fullGuide.addActionListener(event -> showSimpleGuide());
        buttons.add(start); buttons.add(cancel); buttons.add(openResults); buttons.add(fullGuide);
        bottom.add(buttons, BorderLayout.NORTH);
        progress.setStringPainted(true); progress.setString("Ready");
        progress.setToolTipText("Tests report elapsed time because a reliable completion percentage is not available.");
        bottom.add(progress, BorderLayout.CENTER); bottom.add(status, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        cancel.setEnabled(false); openResults.setEnabled(false);
        test.addActionListener(event -> showOptions());
        start.addActionListener(event -> startRun());
        cancel.addActionListener(event -> cancelRun());
        openResults.addActionListener(event -> openResults());
        elapsedTimer = new Timer(1000, event -> progress.setString((cancellation != null && cancellation.requested() ? "Stopping" : "Running")
                + " · " + ((System.currentTimeMillis() - startedAt) / 1000) + " seconds"));
        showOptions();
        enhanceControls(this, "Change this benchmark option.");
    }

    public void setActions(BooleanSupplier authorized, BiConsumer<String, String> audit) {
        this.authorized = java.util.Objects.requireNonNull(authorized);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    public boolean isRunning() { return worker != null && !worker.isDone(); }

    /** Called when the dashboard/session closes; prevents a benchmark from outliving its operator. */
    public void shutdown() {
        shuttingDown = true;
        elapsedTimer.stop();
        if (cancellation != null && isRunning()) cancellation.cancel();
    }

    private void showOptions() {
        options.clear(); optionsPanel.removeAll();
        Type selected = (Type) test.getSelectedItem();
        if (selected == null) return;
        switch (selected) {
            case SYNTHETIC -> {
                number("duration", "Duration (seconds)", 30, 1, 3600, 1, "How long the artificial queue stress test runs.");
                number("fps", "Simulated camera FPS", 20, 1, 240, 1, "Artificial frame production rate; this does not read a camera.");
                number("processing", "Processing delay (ms)", 50, 0, 10000, 1, "Artificial work per frame. Increase this to test overload.");
            }
            case PIPELINE -> {
                textOption("source", "Video / camera source", "", "Choose a local video for repeatable results, or enter 0 for a webcam or a configured stream URL.", true, false);
                number("duration", "Time limit (seconds)", 60, 1, 3600, 1, "Always stops at this time limit, even for a live source.");
                number("frames", "Frame limit (0 = time only)", 900, 0, 100000, 1, "Stop after this many frames, or at the time limit, whichever comes first.");
                number("warmup", "Warm-up frames", 60, 0, 10000, 1, "Initial frames run the model but are excluded from measured samples and detections.");
                number("scale", "Processing scale", 0.75, 0.25, 1.0, 0.05, "Resize for this test only: 1 is full size and 0.75 is 75% of each dimension.");
                number("handEvery", "Hand detection every N frames", 2, 1, 30, 1, "Run hand inference once every N decoded frames.");
                number("maxHands", "Maximum hands", 4, 1, 8, 1, "Maximum hand landmarks detected per frame in this trial.");
                JCheckBox people = new JCheckBox("Include people detection"); people.setOpaque(false);
                people.setToolTipText("Profile synchronous people detection in this test. This does not modify saved engine settings.");
                option("people", "People detector", people);
                number("peopleEvery", "People detection every N frames", 8, 1, 120, 1, "Run people detection every N frames when included.");
            }
            case TRANSPORT -> {
                number("count", "Measured messages", 200, 1, 10000, 1, "Number of local benchmark ping/ack messages sent to this dashboard.");
                number("warmup", "Warm-up messages", 20, 0, 1000, 1, "Initial round trips excluded from the measurements.");
            }
            case PERSISTENCE -> {
                number("count", "Measured writes", 100, 1, 10000, 1, "Number of synthetic records written to a new benchmark database.");
                number("warmup", "Warm-up writes", 10, 0, 1000, 1, "Initial database writes excluded from measured latency.");
                number("interval", "Arrival interval (ms)", 0, 0, 1000, 1, "0 queues all records immediately; 250 simulates four arriving records per second.");
            }
            case EVALUATE -> {
                textOption("detections", "Pipeline detections.csv", "", "Select detections.csv from a completed video performance trial.", true, false);
                textOption("labels", "Human labels CSV", "", "Select the manually annotated positive event timestamps for that same video.", true, false);
                number("tolerance", "Match tolerance (ms)", 1000, 1, 60000, 1, "Maximum difference between a labelled event and a confirmed detection; keep fixed across comparisons.");
            }
            case SUMMARY -> textOption("inputs", "Results to include", outputFolder.getText(), "Select a folder containing completed trial summaries to include in the Chapter 4 index.", true, true);
        }
        guide.setText(html(guideFor(selected))); guide.setCaretPosition(0);
        enhanceControls(optionsPanel, "Change this benchmark option.");
        optionsPanel.revalidate(); optionsPanel.repaint();
    }

    private void startRun() {
        if (isRunning() || shuttingDown) return;
        try {
            requirePermission();
            Map<String, String> values = new LinkedHashMap<>();
            for (var entry : options.entrySet()) {
                JComponent component = entry.getValue();
                if (component instanceof JSpinner spinner) spinner.commitEdit();
                values.put(entry.getKey(), component instanceof JSpinner spinner ? spinner.getValue().toString()
                        : component instanceof JCheckBox check ? Boolean.toString(check.isSelected()) : ((JTextField) component).getText());
            }
            if (conditions.getText().equals("Laptop/CPU/RAM: \nCamera or video / resolution: \nLighting / distance / trial: ")) {
                throw new IllegalArgumentException("Fill in Test conditions before starting. This information is saved beside your results.");
            }
            BenchmarkService.Request request = new BenchmarkService.Request((Type) test.getSelectedItem(), Path.of(outputFolder.getText().trim()),
                    trialLabel.getText().trim(), python.getText().trim(), values, conditions.getText());
            service.validate(request);
            // The audit callback must commit successfully before any process or output is created.
            audit.accept("BENCHMARK_STARTED", "test=" + request.type().command() + "; label=" + request.label()
                    + "; output=" + request.outputRoot().toAbsolutePath().normalize());
            cancellation = new BenchmarkService.Cancellation();
            BenchmarkService.Cancellation thisRun = cancellation;
            log.setText(""); latestResults = null;
            setRunning(true); startedAt = System.currentTimeMillis(); elapsedTimer.start();
            worker = new SwingWorker<>() {
                @Override protected BenchmarkService.Result doInBackground() throws Exception {
                    return service.run(request, thisRun, this::publish);
                }
                @Override protected void process(List<String> lines) { lines.forEach(BenchmarkPanel.this::appendLog); }
                @Override protected void done() {
                    elapsedTimer.stop(); setRunning(false);
                    try {
                        BenchmarkService.Result result = get(); latestResults = result.directory();
                        appendLog(result.summary());
                        String outcome = result.cancelled() ? "CANCELLED" : result.exitCode() == 0 ? "COMPLETED" : "FAILED";
                        audit.accept("BENCHMARK_" + outcome, "test=" + request.type().command() + "; results=" + result.directory() + "; exit=" + result.exitCode());
                        status.setText(outcome + " · " + result.directory()); progress.setString(outcome);
                    } catch (Exception error) {
                        Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
                        String detail = BenchmarkService.redact(cause.getMessage()); appendLog("Test error: " + detail);
                        status.setText("Test failed. See the log and retained trial files."); progress.setString("FAILED");
                        try { audit.accept("BENCHMARK_FAILED", "test=" + request.type().command() + "; error=" + detail); }
                        catch (RuntimeException auditFailure) { appendLog("Audit could not be saved: " + BenchmarkService.redact(auditFailure.getMessage())); }
                    }
                    openResults.setEnabled(latestResults != null && !shuttingDown);
                }
            };
            worker.execute();
        } catch (Exception failure) { showError(failure); }
    }

    private void cancelRun() {
        if (!isRunning() || cancellation == null) return;
        try {
            requirePermission(); audit.accept("BENCHMARK_CANCEL_REQUESTED", "test=" + ((Type) test.getSelectedItem()).command());
            cancellation.cancel(); cancel.setEnabled(false); status.setText("Stopping. Partial result files will be retained.");
        } catch (Exception failure) { showError(failure); }
    }

    private void openResults() {
        try {
            requirePermission();
            if (latestResults == null || !Files.isDirectory(latestResults)) throw new IllegalStateException("The latest results folder is unavailable.");
            audit.accept("BENCHMARK_RESULTS_OPENED", "results=" + latestResults);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) throw new IllegalStateException("Open this results folder in your file manager: " + latestResults);
            Desktop.getDesktop().open(latestResults.toFile());
        } catch (Exception failure) { showError(failure); }
    }

    private void showSimpleGuide() {
        JEditorPane text = new JEditorPane("text/html", html("<h2>A simple first test</h2><ol><li>Choose <b>Frame queue stress</b>.</li><li>Choose a results folder and fill in the laptop, conditions, and trial number.</li><li>Keep the defaults and click <b>Run test</b>. Wait for COMPLETED.</li><li>Click <b>Open latest results</b>. Keep trial.json, run.log, summary.json and CSV files together.</li><li>Repeat three times with labels trial-1, trial-2 and trial-3.</li></ol><h2>To measure real camera processing</h2><p>Choose <b>Video / camera performance</b> and select a recorded test video. Start with the same video and settings for all three trials. For a live camera, stop the separate vision engine that already owns it before running the test. The benchmark will not pause monitoring for you.</p><h2>To measure accuracy</h2><p>A reviewer must mark the actual event times in the same video. Use those labels and the completed trial's detections.csv in <b>Detection accuracy</b>. Confidence percentages alone do not measure accuracy.</p><h2>For Chapter 4</h2><p>Use the median and p95 (95% of measurements are at or below p95), report all three trials, and state each test's scope. Local message response does not establish a comparison with request-response systems without an equivalent comparison experiment.</p><p>Full guide: " + escape(projectRoot.resolve("docs/benchmarking-simple-guide.md").toString()) + "</p>"));
        text.setEditable(false); text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(700, 540));
        JOptionPane.showMessageDialog(this, scroll, "Simple benchmarking guide", JOptionPane.INFORMATION_MESSAGE);
    }

    private void requirePermission() { if (!authorized.getAsBoolean()) throw new SecurityException("Your account does not have benchmarking permission."); }
    private void showError(Exception error) { JOptionPane.showMessageDialog(this, BenchmarkService.redact(error.getMessage()), "Benchmark setup", JOptionPane.WARNING_MESSAGE); }
    private void setRunning(boolean running) {
        setControlsEnabled(setup, !running && !shuttingDown); start.setEnabled(!running && !shuttingDown);
        cancel.setEnabled(running && !shuttingDown); openResults.setEnabled(!running && latestResults != null && !shuttingDown);
        progress.setIndeterminate(running); progress.setString(running ? "Starting…" : "Ready");
        if (running) status.setText("Test running. You may continue using other dashboard tabs.");
    }
    private void appendLog(String text) {
        log.append(BenchmarkService.redact(text) + System.lineSeparator());
        if (log.getDocument().getLength() > 120000) {
            try { log.getDocument().remove(0, log.getDocument().getLength() - 100000); } catch (javax.swing.text.BadLocationException ignored) { }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }
    private void number(String key, String label, Number initial, Number minimum, Number maximum, Number step, String tooltip) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, (Comparable<?>) minimum, (Comparable<?>) maximum, step));
        spinner.setToolTipText(tooltip); option(key, label, spinner);
    }
    private void textOption(String key, String label, String initial, String tooltip, boolean browse, boolean directory) {
        JTextField field = field(initial, tooltip); options.put(key, field);
        addRow(optionsPanel, options.size() - 1, label, browse ? withBrowse(field, directory, label, key.equals("source") ? "video" : key.equals("inputs") ? null : "csv") : field);
    }
    private void option(String key, String label, JComponent component) { options.put(key, component); addRow(optionsPanel, options.size() - 1, label, component); }
    private JPanel withBrowse(JTextField field, boolean directory, String title, String filter) {
        JPanel panel = new JPanel(new BorderLayout(6, 0)); panel.setOpaque(false); panel.add(field, BorderLayout.CENTER);
        JButton browse = button("Browse…", title + ". Selecting a path does not start a test.");
        browse.addActionListener(event -> {
            if (directory) {
                NativeFileDialogs.chooseDirectory(this, title, ExportDefaults.Kind.BENCHMARK)
                        .ifPresent(path -> field.setText(path.toString()));
            } else {
                NativeFileDialogs.openFile(this, title, ExportDefaults.Kind.BENCHMARK, extensionsFor(filter))
                        .ifPresent(path -> field.setText(path.toString()));
            }
        });
        panel.add(browse, BorderLayout.EAST); return panel;
    }
    private static List<String> extensionsFor(String filter) {
        if ("csv".equals(filter)) return List.of("csv");
        if ("video".equals(filter)) return List.of("mp4", "avi", "mkv", "mov", "wmv", "webm");
        return List.of();
    }
    private static JTextField field(String value, String tooltip) { JTextField field = new JTextField(value, 22); field.setToolTipText(tooltip); return field; }
    private static JButton button(String label, String tooltip) { JButton button = new JButton(label); button.setToolTipText(tooltip); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return button; }
    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints(); c.gridx = x; c.gridy = y; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, 0, 4, 8); c.fill = GridBagConstraints.HORIZONTAL; c.weightx = x == 0 ? 0 : 1; return c;
    }
    private static void addRow(JPanel panel, int row, String label, Component component) {
        JLabel title = new JLabel(label); title.setLabelFor(component); title.setFont(BlueTheme.font(Font.PLAIN, 12));
        panel.add(title, constraints(0, row)); panel.add(component, constraints(1, row));
    }
    private static void setControlsEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) for (Component child : container.getComponents()) setControlsEnabled(child, enabled);
    }
    private static void enhanceControls(Component component, String inheritedTip) {
        String tip = inheritedTip;
        if (component instanceof JComponent jc && jc.getToolTipText() != null) tip = jc.getToolTipText();
        if (component instanceof AbstractButton || component instanceof JComboBox<?>) {
            component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (component instanceof JComponent jc && jc.getToolTipText() == null) jc.setToolTipText(tip);
        }
        if (component instanceof javax.swing.text.JTextComponent text && text.getToolTipText() == null) text.setToolTipText(tip);
        if (component instanceof Container container) for (Component child : container.getComponents()) enhanceControls(child, tip);
    }
    private static String html(String body) { return "<html><body style='font-family:Segoe UI,sans-serif;font-size:11px;color:#142b49;margin:16px'>" + body + "</body></html>"; }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String guideFor(Type type) {
        String common = "<p><b>Every trial:</b> record the test conditions, use a unique label, and repeat the same scenario three times. Existing files are retained. Stop a run with Cancel test; incomplete outputs are not final measurements.</p>";
        return switch (type) {
            case SYNTHETIC -> "<h2>1. Frame queue stress</h2><p>Check how the one-frame queue behaves when processing becomes slower than incoming frames. No camera is opened.</p><ol><li>Start with 30 seconds, 20 FPS, and 50 ms delay.</li><li>Run the test and open the results.</li><li>Try an overload scenario with 30 FPS and 80 ms delay.</li></ol><p><b>Read:</b> stale_frames_dropped and producer_to_finish_ms median/p95.</p><p><b>Scope:</b> a synthetic queue model. These numbers do not measure recognition accuracy or actual camera latency.</p>" + common;
            case PIPELINE -> "<h2>2. Video / camera performance</h2><ol><li>Select a test video. For a live camera, enter its source and stop the separate engine that already uses it.</li><li>Keep limits and model settings the same across repetitions.</li><li>Run, then inspect frames.csv and summary.json. detections.csv feeds the accuracy test.</li></ol><p><b>Read:</b> measured_throughput_fps, hand_detect_ms, capture_to_decision_ms median/p95, and process memory.</p><p>The trial uses the benchmark's documented engine defaults plus the options here; it does not copy or change your saved engine settings.</p><p><b>Scope:</b> one source, local capture and vision processing. No incidents or evidence are created. It does not measure camera-sensor-to-alert delay or simultaneous-camera capacity.</p><p>Warm-up frames are excluded from both timing samples and confirmed detections. Place labelled events after warm-up or set warm-up to 0 for the accuracy pass.</p>" + common;
            case TRANSPORT -> "<h2>3. Local message response</h2><ol><li>Keep this dashboard open so its local benchmark endpoint is available.</li><li>Use 200 measured messages and 20 warm-up messages.</li><li>Run and inspect round_trip_ms median/p95 in summary.json.</li></ol><p><b>Scope:</b> one local WebSocket ping/ack round trip. It excludes gesture detection, SQLite writes, display updates, sound, and human response.</p><p>For the paper's event-driven versus request-response comparison, you also need an equivalent request-response experiment under the same workload. This test alone does not establish that comparison.</p>" + common;
            case PERSISTENCE -> "<h2>4. SQLite write performance</h2><ol><li>Start with 100 measured writes and 10 warm-up writes.</li><li>Set arrival interval to 0 for a burst, or 250 ms for paced arrivals.</li><li>Run and inspect successful_persistence_latency_ms median/p95.</li></ol><p>A new test SQLite database is created in the results folder. Synthetic records have no media and never enter the live evidence archive.</p><p><b>Scope:</b> queue wait, actual repository INSERT, and read-back. It excludes snapshot/video writes and total alert latency.</p>" + common;
            case EVALUATE -> "<h2>5. Detection accuracy</h2><ol><li>Run Video / camera performance on a labelled video and select its detections.csv here.</li><li>Select human labels for that exact video timeline.</li><li>Choose the agreed match tolerance and run.</li></ol><p><b>Labels CSV:</b></p><pre>timestamp_ms,event<br>12500,1<br>30400,1</pre><p>Example times show the format only. A reviewer must replace them with real event times: for example, the first frame where the SOS close is visibly complete. Keep that rule consistent.</p><p><b>Read:</b> true/false positives, false negatives, precision, recall, F1, and event delay. A missing value is undefined, not zero.</p><p>Include negative videos of normal hand movements. Event timestamps alone cannot calculate specificity. Confidence is not measured accuracy.</p>" + common;
            case SUMMARY -> "<h2>6. Chapter 4 result index</h2><ol><li>Select the folder containing the finished trial summaries.</li><li>Run to generate chapter-summary.md and chapter-summary.json.</li><li>Use the index to locate the original CSV/JSON files when preparing tables.</li></ol><p>The index contains summaries of existing reports. It does not pool results, calculate an overall accuracy, or replace the raw measurements.</p><p>Report the scenario, trial count, settings, median, p95, errors, and exclusions. Include all comparable trials and explain unusual results.</p>" + common;
        };
    }
}
