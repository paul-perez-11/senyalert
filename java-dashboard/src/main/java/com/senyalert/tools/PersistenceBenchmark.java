package com.senyalert.tools;

import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.repository.SqliteIncidentRepository;
import com.senyalert.service.AppExecutors;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Repeatable local SQLite persistence benchmark for Chapter 4/5 measurements.
 *
 * <p>It exercises the same {@link SqliteIncidentRepository#create} path used
 * by a real incoming distress event, including the dashboard's single database
 * executor and the read-back after INSERT. It deliberately creates a new,
 * timestamped benchmark database in the selected output directory instead of
 * touching the dashboard's incident archive. It does not measure camera,
 * MediaPipe, WebSocket, Swing, evidence media writes, or end-to-end alert
 * latency.</p>
 */
public final class PersistenceBenchmark {
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private PersistenceBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        String runId = "persistence-" + RUN_TIME.format(Instant.now());
        Path outputDirectory = options.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        Path databasePath = uniquePath(outputDirectory.resolve(runId + ".db"));
        Path csvPath = outputDirectory.resolve(runId + "-samples.csv");
        Path jsonPath = outputDirectory.resolve(runId + "-summary.json");

        AppExecutors executors = new AppExecutors();
        try {
            SqliteIncidentRepository repository = new SqliteIncidentRepository(databasePath, executors);
            long initializeStarted = System.nanoTime();
            repository.initialize().join();
            double initializeMs = elapsedMs(initializeStarted);

            for (int index = 0; index < options.warmupOperations(); index++) {
                repository.create(eventFor(runId, -index - 1), AlertMode.AUDIBLE).join();
            }

            List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch completed = new CountDownLatch(options.operations());
            long runStarted = System.nanoTime();
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (int index = 0; index < options.operations(); index++) {
                waitForArrival(runStarted, index, options.arrivalIntervalMs());
                int operation = index + 1;
                long submittedAt = System.nanoTime();
                CompletableFuture<?> future = repository.create(eventFor(runId, operation), AlertMode.AUDIBLE)
                        .whenComplete((incident, failure) -> {
                            long completedAt = System.nanoTime();
                            String error = failure == null ? "" : rootMessage(failure);
                            samples.add(new Sample(operation, elapsedMs(submittedAt, completedAt), error));
                            if (!error.isBlank()) {
                                failures.add(error);
                            }
                            completed.countDown();
                        });
                futures.add(future);
            }

            boolean allCompleted = completed.await(options.timeoutSeconds(), TimeUnit.SECONDS);
            long runFinished = System.nanoTime();
            if (!allCompleted) {
                failures.add("Timed out waiting for " + completed.getCount() + " operation(s)");
            }
            // Force already-completed futures to surface no hidden failure; do
            // not block longer than the bounded latch above.
            for (CompletableFuture<?> future : futures) {
                if (future.isDone()) {
                    try {
                        future.join();
                    } catch (RuntimeException ignored) {
                        // The callback already records the root failure.
                    }
                }
            }

            samples.sort(Comparator.comparingInt(Sample::operation));
            writeSamples(csvPath, samples);
            JSONObject report = reportFor(
                    runId, options, databasePath, initializeMs, runStarted, runFinished,
                    samples, failures, allCompleted);
            Files.writeString(jsonPath, report.toString(2) + System.lineSeparator(), StandardCharsets.UTF_8);

            System.out.println("Persistence benchmark complete.");
            System.out.println("Samples: " + csvPath);
            System.out.println("Summary: " + jsonPath);
            System.out.println("Test database: " + databasePath);
        } finally {
            executors.close();
        }
    }

    private static DistressEvent eventFor(String runId, int operation) {
        return new DistressEvent(
                runId + "-op-" + operation,
                "BENCHMARK-CAMERA",
                0.80,
                System.currentTimeMillis() / 1_000L,
                "Local benchmark only",
                "BENCHMARK",
                "Benchmark",
                1,
                false,
                "CURRENT",
                false,
                1,
                1,
                "benchmark-hand",
                "",
                "",
                "",
                "image/jpeg");
    }

    private static void waitForArrival(long runStarted, int zeroBasedOperation, long intervalMs)
            throws InterruptedException {
        if (intervalMs <= 0) {
            return;
        }
        long target = runStarted + TimeUnit.MILLISECONDS.toNanos(intervalMs * (long) zeroBasedOperation);
        long remaining = target - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static JSONObject reportFor(
            String runId,
            Options options,
            Path databasePath,
            double initializeMs,
            long runStarted,
            long runFinished,
            List<Sample> samples,
            List<String> failures,
            boolean allCompleted) {
        List<Double> successLatencies = samples.stream()
                .filter(Sample::successful)
                .map(Sample::latencyMs)
                .toList();
        long elapsedNs = Math.max(1L, runFinished - runStarted);
        double elapsedSeconds = elapsedNs / 1_000_000_000.0;
        JSONObject report = new JSONObject();
        report.put("benchmark", "java_repository_persistence");
        report.put("run_id", runId);
        report.put("created_at_utc", Instant.now().toString());
        report.put("database_path", databasePath.toString());
        report.put("measurement_scope",
                "enqueue-to-create-completion through SqliteIncidentRepository and its single database executor");
        report.put("not_measured",
                "camera capture, MediaPipe, WebSocket transport, Swing rendering, sound, and snapshot/video media writes");
        report.put("operations_requested", options.operations());
        report.put("warmup_operations", options.warmupOperations());
        report.put("arrival_interval_ms", options.arrivalIntervalMs());
        report.put("timeout_seconds", options.timeoutSeconds());
        report.put("all_operations_completed", allCompleted);
        report.put("operations_completed", samples.size());
        report.put("operations_succeeded", successLatencies.size());
        report.put("operations_failed", samples.size() - successLatencies.size());
        report.put("initialization_ms", round(initializeMs));
        report.put("wall_duration_sec", round(elapsedSeconds));
        report.put("completed_throughput_ops_per_sec", round(samples.size() / elapsedSeconds));
        report.put("successful_persistence_latency_ms", statistics(successLatencies));
        JSONArray errors = new JSONArray();
        failures.stream().distinct().limit(10).forEach(errors::put);
        report.put("errors", errors);
        return report;
    }

    private static void writeSamples(Path path, List<Sample> samples) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("operation,success,enqueue_to_persist_complete_ms,error");
            writer.newLine();
            for (Sample sample : samples) {
                writer.write(sample.operation() + "," + sample.successful() + ","
                        + String.format(Locale.ROOT, "%.3f", sample.latencyMs()) + ","
                        + csv(sample.error()));
                writer.newLine();
            }
        }
    }

    private static JSONObject statistics(List<Double> samples) {
        JSONObject result = new JSONObject();
        result.put("unit", "ms");
        result.put("count", samples.size());
        if (samples.isEmpty()) {
            result.put("min", JSONObject.NULL);
            result.put("mean", JSONObject.NULL);
            result.put("median", JSONObject.NULL);
            result.put("p95", JSONObject.NULL);
            result.put("p99", JSONObject.NULL);
            result.put("max", JSONObject.NULL);
            return result;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        result.put("min", round(sorted.get(0)));
        result.put("mean", round(mean));
        result.put("median", round(percentile(sorted, 0.50)));
        result.put("p95", round(percentile(sorted, 0.95)));
        result.put("p99", round(percentile(sorted, 0.99)));
        result.put("max", round(sorted.get(sorted.size() - 1)));
        return result;
    }

    private static double percentile(List<Double> sorted, double proportion) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double index = proportion * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = index - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }

    private static Path uniquePath(Path candidate) {
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = candidate.getFileName().toString().replaceFirst("\\.db$", "");
        for (int number = 2; number < 10_000; number++) {
            Path alternative = candidate.resolveSibling(base + "-" + number + ".db");
            if (!Files.exists(alternative)) {
                return alternative;
            }
        }
        throw new IllegalStateException("Could not choose a unique benchmark database path");
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static double elapsedMs(long startedAt) {
        return elapsedMs(startedAt, System.nanoTime());
    }

    private static double elapsedMs(long startedAt, long finishedAt) {
        return (finishedAt - startedAt) / 1_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + '"';
    }

    private record Sample(int operation, double latencyMs, String error) {
        boolean successful() {
            return error == null || error.isBlank();
        }
    }

    private record Options(
            int operations,
            int warmupOperations,
            long arrivalIntervalMs,
            int timeoutSeconds,
            Path outputDirectory) {
        static Options parse(String[] args) {
            int operations = 100;
            int warmup = 10;
            long intervalMs = 0;
            int timeoutSeconds = 60;
            Path output = Path.of("benchmark-results");
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    printUsageAndExit();
                }
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + argument);
                }
                String value = args[++index];
                switch (argument) {
                    case "--operations" -> operations = boundedInt(value, 1, 100_000, argument);
                    case "--warmup" -> warmup = boundedInt(value, 0, 10_000, argument);
                    case "--arrival-interval-ms" -> intervalMs = boundedLong(value, 0, 60_000, argument);
                    case "--timeout-sec" -> timeoutSeconds = boundedInt(value, 1, 3_600, argument);
                    case "--output" -> output = Path.of(value);
                    default -> throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            return new Options(operations, warmup, intervalMs, timeoutSeconds, output);
        }

        private static int boundedInt(String value, int minimum, int maximum, String option) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(option + " must be an integer", invalid);
            }
        }

        private static long boundedLong(String value, long minimum, long maximum, String option) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(option + " must be an integer", invalid);
            }
        }

        private static void printUsageAndExit() {
            System.out.println("Usage: PersistenceBenchmark [--operations N] [--warmup N]"
                    + " [--arrival-interval-ms N] [--timeout-sec N] [--output DIRECTORY]");
            System.exit(0);
        }
    }
}
