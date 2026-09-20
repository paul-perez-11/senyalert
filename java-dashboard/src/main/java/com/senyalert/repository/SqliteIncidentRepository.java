package com.senyalert.repository;

import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import com.senyalert.model.IncidentStatus;
import com.senyalert.model.MediaReadyEvent;
import com.senyalert.model.OperatorIncidentUpdate;
import com.senyalert.service.AppExecutors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** SQLite implementation that never exposes Swing classes or runs JDBC on the EDT. */
public final class SqliteIncidentRepository implements IncidentRepository {
    private static final String SUMMARY_COLUMNS = """
            incident_id, event_token, camera_id, location, detection_timestamp, event_timestamp_epoch,
            confidence, triage_level, alert_mode, status, operator_notes, people_count, people_count_stale,
            occupancy_status, hand_count, signaler_count, signaler_track_id, signaler_bounds, snapshot_path,
            video_path, media_ready, media_status, video_duration_sec
            """;

    private final String databaseUrl;
    private final AppExecutors executors;

    public SqliteIncidentRepository(Path databasePath, AppExecutors executors) {
        this.databaseUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        this.executors = executors;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = openConnection()) {
                SchemaMigrator.migrate(connection);
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not initialize incident storage", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<Incident> create(DistressEvent event, AlertMode alertMode) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] snapshot = resolveBytes(event.snapshotBase64(), event.snapshotPath());
            String insert = """
                    INSERT INTO incident_logs (
                        event_token, camera_id, location, detection_timestamp, event_timestamp_epoch,
                        confidence, triage_level, alert_mode, status, people_count, people_count_stale,
                        occupancy_status, hand_count, signaler_count, signaler_track_id, signaler_bounds,
                        snapshot_path, snapshot_blob, snapshot_mime_type, media_ready, media_status
                    ) VALUES (
                        ?, ?, ?, datetime(?, 'unixepoch', 'localtime'), ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING'
                    )
                    """;
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, event.eventToken());
                statement.setString(2, event.cameraId());
                statement.setString(3, event.location());
                statement.setLong(4, event.timestampEpochSeconds());
                statement.setLong(5, event.timestampEpochSeconds());
                statement.setDouble(6, event.confidence());
                statement.setString(7, event.triageContext());
                statement.setString(8, alertMode.name());
                statement.setString(9, IncidentStatus.PENDING.name());
                statement.setInt(10, Math.max(0, event.peopleCount()));
                statement.setInt(11, event.peopleCountStale() ? 1 : 0);
                statement.setString(12, event.occupancyStatus());
                statement.setInt(13, Math.max(0, event.handCount()));
                statement.setInt(14, Math.max(0, event.signalerCount()));
                statement.setString(15, event.signalerTrackId());
                statement.setString(16, event.signalerBounds());
                statement.setString(17, emptyToNull(event.snapshotPath()));
                statement.setBytes(18, snapshot);
                statement.setString(19, event.snapshotMimeType());
                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No incident ID returned by SQLite");
                    }
                    return findIncident(connection, keys.getLong(1))
                            .orElseThrow(() -> new SQLException("Created incident could not be reloaded"));
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not persist distress event", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<Optional<Incident>> attachMedia(MediaReadyEvent event) {
        return CompletableFuture.supplyAsync(() -> {
            if (event.eventToken().isBlank()) {
                return Optional.empty();
            }
            byte[] video = resolveBytes(event.videoBase64(), event.videoPath());
            boolean mediaAvailable = (video != null && video.length > 0) || isReadableFile(event.videoPath());
            boolean mediaReady = event.isReadyStatus() && mediaAvailable;
            String mediaStatus = mediaReady ? "READY" : (event.isReadyStatus() ? "UNAVAILABLE" : event.mediaStatus());
            String update = """
                    UPDATE incident_logs
                    SET video_path = ?, video_blob = ?, video_mime_type = ?, media_ready = ?,
                        video_duration_sec = ?, media_status = ?
                    WHERE event_token = ?
                    """;
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, emptyToNull(event.videoPath()));
                statement.setBytes(2, video);
                statement.setString(3, event.videoMimeType());
                statement.setInt(4, mediaReady ? 1 : 0);
                statement.setDouble(5, Math.max(0, event.durationSeconds()));
                statement.setString(6, mediaStatus);
                statement.setString(7, event.eventToken());
                if (statement.executeUpdate() == 0) {
                    return Optional.empty();
                }
                return findIncidentByToken(connection, event.eventToken());
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not attach incident media", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<List<Incident>> listRecent() {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT " + SUMMARY_COLUMNS + " FROM incident_logs ORDER BY incident_id DESC LIMIT 500";
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(query)) {
                List<Incident> incidents = new ArrayList<>();
                while (resultSet.next()) {
                    incidents.add(mapIncident(resultSet));
                }
                return incidents;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not load incidents", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<Optional<IncidentEvidence>> findEvidence(long incidentId) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT " + SUMMARY_COLUMNS
                    + ", snapshot_blob, snapshot_mime_type, video_blob, video_mime_type"
                    + " FROM incident_logs WHERE incident_id = ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setLong(1, incidentId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    Incident incident = mapIncident(resultSet);
                    return Optional.of(new IncidentEvidence(
                            incident,
                            resultSet.getBytes("snapshot_blob"),
                            resultSet.getString("snapshot_mime_type"),
                            resultSet.getBytes("video_blob"),
                            resultSet.getString("video_mime_type")));
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not load incident evidence", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<Optional<Incident>> updateOperatorRecord(
            long incidentId, OperatorIncidentUpdate operatorUpdate) {
        java.util.Objects.requireNonNull(operatorUpdate, "operatorUpdate");
        return CompletableFuture.supplyAsync(() -> {
            if (incidentId <= 0) {
                return Optional.empty();
            }
            // This allowlist intentionally excludes all detector, timestamp, identity,
            // snapshot, path, BLOB, and media state columns.
            String sql = "UPDATE incident_logs SET status = ?, operator_notes = ? WHERE incident_id = ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, operatorUpdate.status().name());
                statement.setString(2, emptyToNull(operatorUpdate.operatorNotes()));
                statement.setLong(3, incidentId);
                if (statement.executeUpdate() == 0) {
                    return Optional.empty();
                }
                return findIncident(connection, incidentId);
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not update operator incident record", failure);
            }
        }, executors.database());
    }

    @Override
    public CompletableFuture<Boolean> deleteRecord(long incidentId) {
        return CompletableFuture.supplyAsync(() -> {
            if (incidentId <= 0) {
                return false;
            }
            // Deliberately no Files calls: this removes the DB row/BLOBs only and
            // leaves any engine-created snapshot/video paths untouched.
            String delete = "DELETE FROM incident_logs WHERE incident_id = ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(delete)) {
                statement.setLong(1, incidentId);
                return statement.executeUpdate() > 0;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not delete incident record", failure);
            }
        }, executors.database());
    }

    @Override
    public void close() {
        // Connections are operation-scoped; AppExecutors owns the worker lifecycle.
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    private Optional<Incident> findIncident(Connection connection, long incidentId) throws SQLException {
        String query = "SELECT " + SUMMARY_COLUMNS + " FROM incident_logs WHERE incident_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, incidentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapIncident(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<Incident> findIncidentByToken(Connection connection, String eventToken) throws SQLException {
        String query = "SELECT " + SUMMARY_COLUMNS
                + " FROM incident_logs WHERE event_token = ? ORDER BY incident_id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, eventToken);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapIncident(resultSet)) : Optional.empty();
            }
        }
    }

    private static Incident mapIncident(ResultSet row) throws SQLException {
        return new Incident(
                row.getLong("incident_id"),
                nullToEmpty(row.getString("event_token")),
                nullToEmpty(row.getString("camera_id")),
                nullToEmpty(row.getString("location")),
                nullToEmpty(row.getString("detection_timestamp")),
                row.getLong("event_timestamp_epoch"),
                row.getDouble("confidence"),
                nullToEmpty(row.getString("triage_level")),
                AlertMode.fromDatabase(row.getString("alert_mode")),
                IncidentStatus.fromDatabase(row.getString("status")),
                nullToEmpty(row.getString("operator_notes")),
                row.getInt("people_count"),
                row.getInt("people_count_stale") != 0,
                nullToEmpty(row.getString("occupancy_status")),
                row.getInt("hand_count"),
                row.getInt("signaler_count"),
                nullToEmpty(row.getString("signaler_track_id")),
                nullToEmpty(row.getString("signaler_bounds")),
                nullToEmpty(row.getString("snapshot_path")),
                nullToEmpty(row.getString("video_path")),
                row.getInt("media_ready") != 0,
                row.getDouble("video_duration_sec"),
                nullToEmpty(row.getString("media_status")));
    }

    private static byte[] resolveBytes(String base64, String path) {
        if (base64 != null && !base64.isBlank()) {
            try {
                return Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path localPath = Path.of(path);
            return Files.isRegularFile(localPath) ? Files.readAllBytes(localPath) : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isReadableFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(value));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
