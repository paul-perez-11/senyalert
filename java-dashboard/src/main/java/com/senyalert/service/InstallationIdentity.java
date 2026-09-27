package com.senyalert.service;

import com.senyalert.EnvironmentConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Resolves the Remote Support installation identity without ever creating a new ID. */
public final class InstallationIdentity {
    private static final String KEY = "SENYALERT_CLIENT_ID";

    private InstallationIdentity() { }

    public record Resolution(String clientId, boolean migratedLegacyId) { }

    /**
     * Uses the environment-file value first. A valid legacy client-id.txt is
     * read once and copied into the environment file during an explicit Remote
     * Support start. The legacy file is never rewritten or removed.
     */
    public static Resolution require(Path dataDirectory) {
        String configured = normalized(EnvironmentConfiguration.value(KEY));
        if (configured != null) {
            return new Resolution(validate(configured), false);
        }
        Path legacy = dataDirectory.resolve("client-id.txt");
        Optional<String> legacyId = readLegacy(legacy);
        if (legacyId.isPresent()) {
            String id = validate(legacyId.get());
            EnvironmentConfiguration.appendMigratedClientId(id);
            String migrated = normalized(EnvironmentConfiguration.value(KEY));
            if (migrated == null) {
                throw new IllegalStateException("Could not activate the migrated environment client ID.");
            }
            return new Resolution(validate(migrated), true);
        }
        throw new IllegalStateException("Remote Support needs " + KEY
                + " in senyalert.env. Copy senyalert.env.example, set a unique installation ID, then sign in again.");
    }

    private static Optional<String> readLegacy(Path legacy) {
        if (!Files.isRegularFile(legacy)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(normalized(Files.readString(legacy, StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read the existing legacy client ID for migration.", failure);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String validate(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")) {
            throw new IllegalStateException(KEY + " must be 8 to 128 letters, digits, dashes, or underscores.");
        }
        return value;
    }
}
