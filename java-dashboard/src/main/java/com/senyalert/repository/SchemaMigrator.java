package com.senyalert.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Additive SQLite migration only; existing incident data is never replaced. */
final class SchemaMigrator {
    private SchemaMigrator() {
    }

    static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cameras (
                        camera_id TEXT PRIMARY KEY,
                        zone_name TEXT NOT NULL,
                        threat_profile TEXT DEFAULT 'STANDARD'
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS incident_logs (
                        incident_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        camera_id TEXT NOT NULL,
                        detection_timestamp TEXT DEFAULT (datetime('now', 'localtime')),
                        confidence REAL NOT NULL,
                        triage_level TEXT NOT NULL,
                        status TEXT DEFAULT 'PENDING',
                        operator_notes TEXT,
                        event_token TEXT,
                        location TEXT DEFAULT 'Unassigned Zone',
                        event_timestamp_epoch INTEGER DEFAULT 0,
                        alert_mode TEXT DEFAULT 'AUDIBLE',
                        people_count INTEGER DEFAULT 0,
                        people_count_stale INTEGER DEFAULT 0,
                        occupancy_status TEXT DEFAULT 'CURRENT',
                        hand_count INTEGER DEFAULT 0,
                        signaler_count INTEGER DEFAULT 0,
                        signaler_track_id TEXT,
                        signaler_bounds TEXT,
                        snapshot_path TEXT,
                        snapshot_blob BLOB,
                        snapshot_mime_type TEXT,
                        video_path TEXT,
                        video_blob BLOB,
                        video_mime_type TEXT,
                        media_ready INTEGER DEFAULT 0,
                        media_status TEXT DEFAULT 'PENDING',
                        video_duration_sec REAL DEFAULT 0
                    )
                    """);

            Set<String> columns = incidentColumns(connection);
            addColumnIfMissing(statement, columns, "status", "TEXT DEFAULT 'PENDING'");
            addColumnIfMissing(statement, columns, "operator_notes", "TEXT");
            addColumnIfMissing(statement, columns, "event_token", "TEXT");
            addColumnIfMissing(statement, columns, "location", "TEXT DEFAULT 'Unassigned Zone'");
            addColumnIfMissing(statement, columns, "event_timestamp_epoch", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "alert_mode", "TEXT DEFAULT 'AUDIBLE'");
            addColumnIfMissing(statement, columns, "people_count", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "people_count_stale", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "occupancy_status", "TEXT DEFAULT 'CURRENT'");
            addColumnIfMissing(statement, columns, "hand_count", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "signaler_count", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "signaler_track_id", "TEXT");
            addColumnIfMissing(statement, columns, "signaler_bounds", "TEXT");
            addColumnIfMissing(statement, columns, "snapshot_path", "TEXT");
            addColumnIfMissing(statement, columns, "snapshot_blob", "BLOB");
            addColumnIfMissing(statement, columns, "snapshot_mime_type", "TEXT");
            addColumnIfMissing(statement, columns, "video_path", "TEXT");
            addColumnIfMissing(statement, columns, "video_blob", "BLOB");
            addColumnIfMissing(statement, columns, "video_mime_type", "TEXT");
            addColumnIfMissing(statement, columns, "media_ready", "INTEGER DEFAULT 0");
            addColumnIfMissing(statement, columns, "media_status", "TEXT DEFAULT 'PENDING'");
            addColumnIfMissing(statement, columns, "video_duration_sec", "REAL DEFAULT 0");

            statement.execute("CREATE INDEX IF NOT EXISTS idx_incident_logs_event_token ON incident_logs(event_token)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_incident_logs_timestamp ON incident_logs(incident_id DESC)");
            statement.execute("INSERT OR IGNORE INTO cameras (camera_id, zone_name, threat_profile) "
                    + "VALUES ('CAM-01-LAPTOP', 'Public Intake Counter A', 'HIGH_TRAFFIC')");
        }
    }

    private static Set<String> incidentColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(incident_logs)")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private static void addColumnIfMissing(Statement statement, Set<String> columns, String name, String definition)
            throws SQLException {
        if (!columns.contains(name)) {
            statement.execute("ALTER TABLE incident_logs ADD COLUMN " + name + " " + definition);
            columns.add(name);
        }
    }
}
