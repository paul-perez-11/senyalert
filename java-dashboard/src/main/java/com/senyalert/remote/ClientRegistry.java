package com.senyalert.remote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

/** Developer-laptop SQLite storage. Never stores support links, bearer tokens or support keys. */
public final class ClientRegistry {
    private final Path database;

    public ClientRegistry(Path database) {
        this.database = database.toAbsolutePath().normalize();
        try {
            if (this.database.getParent() != null) Files.createDirectories(this.database.getParent());
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS support_clients (client_id TEXT PRIMARY KEY, name TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', snapshot_json TEXT NOT NULL DEFAULT '{}', received_at TEXT)");
                statement.execute("CREATE TABLE IF NOT EXISTS support_templates (name TEXT PRIMARY KEY, settings_json TEXT NOT NULL, updated_at TEXT NOT NULL)");
            }
        } catch (Exception failure) { throw new IllegalStateException("Could not open the superadmin client registry", failure); }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    public synchronized JSONArray clients() throws Exception {
        JSONArray clients = new JSONArray();
        try (Connection connection = open(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT client_id,name,notes,snapshot_json,received_at FROM support_clients ORDER BY lower(name)")) {
            while (rows.next()) clients.put(new JSONObject().put("clientId", rows.getString(1))
                    .put("clientName", rows.getString(2)).put("notes", rows.getString(3))
                    .put("snapshot", new JSONObject(rows.getString(4)))
                    .put("receivedAt", rows.getString(5) == null ? "Never connected" : rows.getString(5)));
        }
        return clients;
    }

    public synchronized void add(String id, String name) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Client name is required");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO support_clients(client_id,name) VALUES(?,?)")) {
            statement.setString(1, id); statement.setString(2, name.trim()); statement.executeUpdate();
        }
    }

    public synchronized void snapshot(JSONObject snapshot) throws Exception {
        String id = snapshot.getString("clientId");
        String name = snapshot.optString("clientName", id);
        JSONObject saved = new JSONObject(snapshot.toString());
        saved.remove("supportSession"); saved.remove("supportExpiresAt");
        JSONObject settings = saved.optJSONObject("settings");
        if (settings != null) saved.put("settings", com.senyalert.service.ConfigurationFiles.redacted(settings));
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO support_clients(client_id,name,snapshot_json,received_at) VALUES(?,?,?,?) "
                + "ON CONFLICT(client_id) DO UPDATE SET name=excluded.name,snapshot_json=excluded.snapshot_json,received_at=excluded.received_at")) {
            statement.setString(1, id); statement.setString(2, name); statement.setString(3, saved.toString());
            statement.setString(4, Instant.now().toString()); statement.executeUpdate();
        }
    }

    public synchronized void notes(String id, String notes) throws Exception {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE support_clients SET notes=? WHERE client_id=?")) {
            statement.setString(1, notes); statement.setString(2, id); statement.executeUpdate();
        }
    }

    public synchronized JSONArray templates() throws Exception {
        JSONArray values = new JSONArray();
        try (Connection connection = open(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT name,settings_json,updated_at FROM support_templates ORDER BY lower(name)")) {
            while (rows.next()) values.put(new JSONObject().put("name", rows.getString(1))
                    .put("settings", new JSONObject(rows.getString(2))).put("updatedAt", rows.getString(3)));
        }
        return values;
    }

    public synchronized void template(String name, JSONObject settings) throws Exception {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Template name is required");
        JSONObject portable = com.senyalert.service.ConfigurationFiles.redacted(settings);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO support_templates(name,settings_json,updated_at) VALUES(?,?,?) ON CONFLICT(name) DO UPDATE SET settings_json=excluded.settings_json,updated_at=excluded.updated_at")) {
            statement.setString(1, name.trim()); statement.setString(2, portable.toString());
            statement.setString(3, Instant.now().toString()); statement.executeUpdate();
        }
    }
}
