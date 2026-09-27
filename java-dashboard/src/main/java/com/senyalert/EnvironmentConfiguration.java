package com.senyalert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads an optional per-installation {@code senyalert.env} without placing
 * passwords, support keys, or camera credentials in SQLite configuration.
 * Process environment values take precedence over the file so deployment
 * tools can override one setting without editing it.
 */
public final class EnvironmentConfiguration {
    private static final String FILE_NAME = "senyalert.env";
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static volatile EnvironmentConfiguration loaded;

    private final Map<String, String> values;
    private final Path source;

    private EnvironmentConfiguration(Map<String, String> values, Path source) {
        this.values = Map.copyOf(values);
        this.source = source;
    }

    /** Loads once for this JVM. Invalid explicitly selected files fail closed. */
    public static EnvironmentConfiguration load() {
        EnvironmentConfiguration current = loaded;
        if (current != null) {
            return current;
        }
        synchronized (EnvironmentConfiguration.class) {
            if (loaded == null) {
                loaded = read(System.getenv(), findFile());
            }
            return loaded;
        }
    }

    public static String value(String name) {
        return load().values.get(name);
    }

    /** Complete merged child environment. Callers must remove secrets they do not need. */
    public static Map<String, String> values() {
        return load().values;
    }

    public static Optional<Path> source() {
        return Optional.ofNullable(load().source);
    }

    /**
     * Returns the normal per-installation file location when no file has been
     * loaded yet. It does not create the file.
     */
    public static Path defaultFile() {
        EnvironmentConfiguration configuration = load();
        if (configuration.source != null) {
            return configuration.source;
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("senyalert.env.example"))
                    || Files.isRegularFile(candidate.resolve("src").resolve("python-prototype.py"))) {
                return candidate.resolve(FILE_NAME);
            }
            if (candidate.getParent() == null || candidate.equals(current.getParent())) {
                break;
            }
        }
        return current.resolve(FILE_NAME);
    }

    /**
     * Persists a legacy installation ID only during an explicit migration. It
     * never replaces a configured nonblank value and does not expose it in
     * logs or returned messages.
     */
    public static synchronized void appendMigratedClientId(String clientId) {
        if (value("SENYALERT_CLIENT_ID") != null && !value("SENYALERT_CLIENT_ID").isBlank()) {
            return;
        }
        if (System.getenv().containsKey("SENYALERT_CLIENT_ID")) {
            throw new IllegalStateException("SENYALERT_CLIENT_ID is blank in the process environment. Set a nonblank value or remove that override before migrating the legacy client ID.");
        }
        Path target = defaultFile();
        String addition = (Files.exists(target) ? System.lineSeparator() : "")
                + "# Migrated from the legacy client-id.txt during an approved Remote Support start." + System.lineSeparator()
                + "SENYALERT_CLIENT_ID=" + clientId + System.lineSeparator();
        try {
            if (Files.exists(target)) {
                Files.writeString(target, addition, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(target,
                        "# SenyAlert local environment configuration" + System.lineSeparator() + addition,
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            Map<String, String> refreshed = new LinkedHashMap<>(load().values);
            refreshed.put("SENYALERT_CLIENT_ID", clientId);
            loaded = new EnvironmentConfiguration(refreshed, target);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the migrated client ID to " + target.getFileName() + ".", failure);
        }
    }

    public static boolean booleanValue(String name, boolean fallback) {
        String value = value(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalStateException(name + " must be true or false in the environment file.");
        };
    }

    private static EnvironmentConfiguration read(Map<String, String> processValues, Path file) {
        Map<String, String> merged = new LinkedHashMap<>(processValues);
        if (file == null) {
            return new EnvironmentConfiguration(merged, null);
        }
        try {
            int lineNumber = 0;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalStateException("Invalid " + FILE_NAME + " entry at line " + lineNumber + ". Use NAME=value.");
                }
                String key = line.substring(0, separator).trim();
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalStateException("Invalid " + FILE_NAME + " variable name at line " + lineNumber + ".");
                }
                String value = unquote(line.substring(separator + 1).trim(), lineNumber);
                // A real process environment has explicit deployment precedence,
                // including an intentionally blank value.
                if (!processValues.containsKey(key)) {
                    merged.put(key, value);
                }
            }
            return new EnvironmentConfiguration(merged, file);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + file.getFileName() + ".", failure);
        }
    }

    private static String unquote(String value, int lineNumber) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            throw new IllegalStateException("Unclosed quoted value in " + FILE_NAME + " at line " + lineNumber + ".");
        }
        return value;
    }

    private static Path findFile() {
        String explicit = System.getenv("SENYALERT_ENV_FILE");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getProperty("senyalert.env.file");
        }
        if (explicit != null && !explicit.isBlank()) {
            try {
                Path file = Path.of(explicit.trim()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("SENYALERT_ENV_FILE must name a readable file.");
                }
                return file;
            } catch (RuntimeException invalid) {
                if (invalid instanceof IllegalStateException) {
                    throw invalid;
                }
                throw new IllegalStateException("SENYALERT_ENV_FILE is not a valid file path.", invalid);
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                return file;
            }
            // The launcher and Maven direct run are at most one directory apart.
            if (candidate.getParent() == null || candidate.equals(current.getParent())) {
                break;
            }
        }
        return null;
    }
}
