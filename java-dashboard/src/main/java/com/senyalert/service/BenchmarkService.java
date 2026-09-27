package com.senyalert.service;

import com.senyalert.EnvironmentConfiguration;
import com.senyalert.tools.PersistenceBenchmark;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Runs existing benchmark programs in isolated child processes, never through a shell. */
public final class BenchmarkService {
    public enum Type {
        SYNTHETIC("Frame queue stress", "synthetic"),
        PIPELINE("Video / camera performance", "pipeline"),
        TRANSPORT("Local message response", "transport"),
        PERSISTENCE("SQLite write performance", "persistence"),
        EVALUATE("Detection accuracy", "evaluate-events"),
        SUMMARY("Chapter 4 result index", "summarize");

        private final String title;
        private final String command;
        Type(String title, String command) { this.title = title; this.command = command; }
        public String command() { return command; }
        @Override public String toString() { return title; }
    }

    public record Request(Type type, Path outputRoot, String label, String python,
                          Map<String, String> options, String conditions) {
        public Request {
            options = Map.copyOf(options);
            label = label == null ? "" : label;
            conditions = conditions == null ? "" : conditions;
        }
    }

    public record Result(Path directory, int exitCode, boolean cancelled, String summary) { }

    /** Cancellation is tied to one run, including a request made before the process starts. */
    public static final class Cancellation {
        private volatile boolean requested;
        private Process process;
        public synchronized void cancel() {
            requested = true;
            stopProcess();
        }
        public boolean requested() { return requested; }
        private synchronized void attach(Process value) {
            process = value;
            if (requested) stopProcess();
        }
        private void stopProcess() {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private final Path projectRoot;

    public BenchmarkService(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public static Path findProjectRoot() {
        String configured = EnvironmentConfiguration.value("SENYALERT_PROJECT_ROOT");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("tools/benchmark_senyalert.py"))) return candidate;
        }
        return current;
    }

    public static String defaultPython(Path root) {
        String configured = EnvironmentConfiguration.value("SENYALERT_PYTHON");
        if (configured != null && !configured.isBlank()) return configured;
        for (String candidate : List.of(".venv/Scripts/python.exe", "venv/Scripts/python.exe", ".venv/bin/python")) {
            Path executable = root.resolve(candidate);
            if (Files.isRegularFile(executable)) return executable.toString();
        }
        return "python";
    }

    public void validate(Request request) {
        if (request.type() == null || request.outputRoot() == null) throw new IllegalArgumentException("Select a test and results folder.");
        if (request.conditions().isBlank()) throw new IllegalArgumentException("Record the laptop, camera or video, lighting, distance, and trial number in Test conditions.");
        if (request.type() != Type.PERSISTENCE && !Files.isRegularFile(projectRoot.resolve("tools/benchmark_senyalert.py"))) {
            throw new IllegalArgumentException("Benchmark tools were not found. Set SENYALERT_PROJECT_ROOT to the SenyAlert project folder before opening the app.");
        }
        if (request.python() == null || request.python().isBlank()) throw new IllegalArgumentException("Choose the Python executable used by SenyAlert.");
        // Validate the same argument construction that execution uses before creating any files.
        command(request, request.outputRoot().toAbsolutePath().normalize());
    }

    public Result run(Request request, Cancellation cancellation, Consumer<String> output) throws Exception {
        validate(request);
        Path parent = request.outputRoot().toAbsolutePath().normalize();
        Files.createDirectories(parent);
        String label = request.label().replaceAll("[^a-zA-Z0-9_-]", "-");
        if (label.length() > 48) label = label.substring(0, 48);
        Path directory = Files.createTempDirectory(parent, "gui-" + request.type().command() + "-" + (label.isBlank() ? "" : label + "-"));
        JSONObject manifest = new JSONObject()
                .put("test", request.type().command()).put("started_at_utc", Instant.now().toString())
                .put("label", request.label()).put("test_conditions", request.conditions())
                .put("status", "RUNNING").put("options", safeOptions(request.options()));
        Path manifestPath = directory.resolve("trial.json");
        write(manifestPath, manifest);
        output.accept("Results folder: " + directory);
        output.accept("Starting " + request.type() + ". The live incident archive is not used.");
        var watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "benchmark-time-limit"); thread.setDaemon(true); return thread;
        });
        Process process = null;
        try {
            if (cancellation.requested()) {
                manifest.put("status", "CANCELLED").put("finished_at_utc", Instant.now().toString());
                write(manifestPath, manifest);
                return new Result(directory, -1, true, "Cancelled before the test started.");
            }
            ProcessBuilder builder = new ProcessBuilder(command(request, directory));
            builder.directory(projectRoot.toFile()).redirectErrorStream(true);
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            cancellation.attach(process);
            watchdog.schedule(cancellation::cancel, 2, TimeUnit.HOURS);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var log = Files.newBufferedWriter(directory.resolve("run.log"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String safe = redact(line);
                    log.write(safe); log.newLine();
                    output.accept(safe);
                }
            }
            int exit = process.waitFor();
            boolean cancelled = cancellation.requested();
            String summary = cancelled ? "Cancelled. Incomplete files are retained and must not be used as final results."
                    : exit == 0 ? readSummary(directory) : "The test failed (exit " + exit + "). Read the log; do not report it as a successful trial.";
            manifest.put("status", cancelled ? "CANCELLED" : exit == 0 ? "COMPLETED" : "FAILED")
                    .put("exit_code", exit).put("finished_at_utc", Instant.now().toString());
            write(manifestPath, manifest);
            return new Result(directory, exit, cancelled, summary);
        } catch (Exception failure) {
            manifest.put("status", cancellation.requested() ? "CANCELLED" : "FAILED")
                    .put("error", redact(failure.getMessage())).put("finished_at_utc", Instant.now().toString());
            write(manifestPath, manifest);
            if (cancellation.requested()) return new Result(directory, -1, true, "Cancelled. Incomplete files are retained.");
            return new Result(directory, -1, false, "The test failed: " + redact(failure.getMessage())
                    + "\nThe trial folder and log are retained. Do not use this trial as a successful result.");
        } finally {
            watchdog.shutdownNow();
            if (process != null && process.isAlive()) cancellation.cancel();
        }
    }

    private List<String> command(Request request, Path output) {
        Map<String, String> options = request.options();
        Set<String> supported = switch (request.type()) {
            case SYNTHETIC -> Set.of("duration", "fps", "processing");
            case PIPELINE -> Set.of("source", "duration", "frames", "warmup", "scale", "handEvery", "maxHands", "people", "peopleEvery");
            case TRANSPORT -> Set.of("count", "warmup");
            case PERSISTENCE -> Set.of("count", "warmup", "interval");
            case EVALUATE -> Set.of("detections", "labels", "tolerance");
            case SUMMARY -> Set.of("inputs");
        };
        if (!supported.containsAll(options.keySet())) throw new IllegalArgumentException("Unknown benchmark option.");
        List<String> args = new ArrayList<>();
        if (request.type() == Type.PERSISTENCE) {
            args.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
            args.add("-cp"); args.add(javaClasspath()); args.add(PersistenceBenchmark.class.getName());
            add(args, "--operations", number(options, "count", 1, 10000, true));
            add(args, "--warmup", number(options, "warmup", 0, 1000, true));
            add(args, "--arrival-interval-ms", number(options, "interval", 0, 1000, true));
            add(args, "--output", output.toString());
            return args;
        }
        args.add(request.python().trim()); args.add("-u"); args.add(projectRoot.resolve("tools/benchmark_senyalert.py").toString());
        args.add(request.type().command());
        switch (request.type()) {
            case SYNTHETIC -> {
                add(args, "--duration-sec", number(options, "duration", 1, 3600, false));
                add(args, "--producer-fps", number(options, "fps", 1, 240, false));
                add(args, "--processing-ms", number(options, "processing", 0, 10000, false));
            }
            case PIPELINE -> {
                String source = required(options, "source");
                if (!(source.matches("[0-9]+") || source.matches("(?i)^(uvc|http|https|rtsp|adb)://.+") || Files.isRegularFile(Path.of(source)))) {
                    throw new IllegalArgumentException("Choose an existing video, camera number (such as 0), or supported camera URL.");
                }
                add(args, "--source", source);
                add(args, "--duration-sec", number(options, "duration", 1, 3600, false));
                add(args, "--max-frames", number(options, "frames", 0, 100000, true));
                add(args, "--warmup-frames", number(options, "warmup", 0, 10000, true));
                add(args, "--processing-scale", number(options, "scale", 0.25, 1, false));
                add(args, "--hand-every-n", number(options, "handEvery", 1, 30, true));
                add(args, "--max-hands", number(options, "maxHands", 1, 8, true));
                if (Boolean.parseBoolean(options.get("people"))) args.add("--people-detection");
                add(args, "--people-every-n", number(options, "peopleEvery", 1, 120, true));
                if (Integer.parseInt(options.get("frames")) > 0 && Integer.parseInt(options.get("warmup")) >= Integer.parseInt(options.get("frames"))) {
                    throw new IllegalArgumentException("Warm-up frames must be fewer than the frame limit.");
                }
            }
            case TRANSPORT -> {
                add(args, "--count", number(options, "count", 1, 10000, true));
                add(args, "--warmup", number(options, "warmup", 0, 1000, true));
            }
            case EVALUATE -> {
                add(args, "--detections", existingFile(options, "detections"));
                add(args, "--ground-truth", existingFile(options, "labels"));
                add(args, "--tolerance-ms", number(options, "tolerance", 1, 60000, false));
            }
            case SUMMARY -> {
                Path input = Path.of(required(options, "inputs")).toAbsolutePath().normalize();
                if (!Files.exists(input)) throw new IllegalArgumentException("Select an existing folder containing finished benchmark results.");
                args.add(input.toString());
            }
            default -> throw new IllegalArgumentException("Unsupported test.");
        }
        add(args, "--output-dir", output.toString()); add(args, "--label", request.label());
        return args;
    }

    private static String javaClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        for (String name : List.of(PersistenceBenchmark.class.getName(), "org.sqlite.JDBC", "org.json.JSONObject", "org.slf4j.LoggerFactory", "org.slf4j.simple.SimpleServiceProvider")) {
            try {
                var source = Class.forName(name).getProtectionDomain().getCodeSource();
                if (source != null) entries.add(Path.of(source.getLocation().toURI()).toString());
            } catch (Exception failure) {
                if (!name.equals("org.slf4j.simple.SimpleServiceProvider")) throw new IllegalStateException("Missing benchmark dependency: " + name, failure);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void add(List<String> args, String key, String value) { args.add(key); args.add(value); }
    private static String required(Map<String, String> options, String key) {
        String value = options.getOrDefault(key, "").trim();
        if (value.isBlank() || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Enter a valid value for " + key + ".");
        return value;
    }
    private static String existingFile(Map<String, String> options, String key) {
        Path path = Path.of(required(options, key)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Select an existing " + key + " CSV file.");
        return path.toString();
    }
    private static String number(Map<String, String> options, String key, double min, double max, boolean integer) {
        String value = required(options, key);
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < min || parsed > max || (integer && parsed != Math.rint(parsed))) throw new NumberFormatException();
            return integer ? Integer.toString((int) parsed) : Double.toString(parsed);
        } catch (NumberFormatException failure) { throw new IllegalArgumentException(key + " must be " + (integer ? "a whole number " : "a number ") + "from " + min + " to " + max + "."); }
    }
    private static JSONObject safeOptions(Map<String, String> options) {
        JSONObject safe = new JSONObject();
        options.forEach((key, value) -> safe.put(key, redact(value)));
        return safe;
    }
    public static String redact(String text) {
        if (text == null) return "Unknown error";
        return text.replaceAll("(?i)(https?|rtsp)://[^\\s\"'<>]+", "[camera URL hidden]");
    }
    private static void write(Path path, JSONObject value) throws IOException { Files.writeString(path, value.toString(2) + System.lineSeparator(), StandardCharsets.UTF_8); }

    private static String readSummary(Path directory) throws IOException {
        try (var paths = Files.walk(directory, 3)) {
            Path summary = paths.filter(path -> path.getFileName().toString().endsWith("summary.json")).findFirst().orElse(null);
            if (summary == null) return "Completed. Open the results folder to inspect the generated files.";
            JSONObject report = new JSONObject(Files.readString(summary, StandardCharsets.UTF_8));
            String scope = report.optString("measurement_scope", "An index of completed trials; refer to each original summary for scope.");
            return "Completed. Summary: " + summary + "\n\nWhat was measured: " + scope + "\n\n" + report.toString(2);
        }
    }
}
