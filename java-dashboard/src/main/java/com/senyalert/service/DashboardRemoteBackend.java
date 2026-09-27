package com.senyalert.service;

import com.senyalert.controller.DashboardController;
import com.senyalert.EnvironmentConfiguration;
import com.senyalert.model.*;
import com.senyalert.remote.RemoteBackend;
import com.senyalert.repository.IncidentRepository;
import com.senyalert.security.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Narrow, authenticated support operations. All file locations belong to this installation. */
public final class DashboardRemoteBackend implements RemoteBackend {
    private final DashboardController controller;
    private final IncidentRepository repository;
    private final SecurityContext grantor;
    private final Path directory;
    private volatile String clientId;
    private final Map<String, SecurityContext> sessions = new ConcurrentHashMap<>();
    public DashboardRemoteBackend(DashboardController controller, IncidentRepository repository, SecurityContext grantor, Path directory) {
        this.controller = controller; this.repository = repository; this.grantor = grantor; this.directory = directory;
    }

    /** Resolves or explicitly migrates a legacy ID before a support endpoint exists. */
    @Override public synchronized void validateReady() {
        if (clientId != null) return;
        InstallationIdentity.Resolution resolved = InstallationIdentity.require(directory);
        clientId = resolved.clientId();
        if (resolved.migratedLegacyId()) {
            grantor.audit("CLIENT_ID_MIGRATED", "remote-support",
                    "Copied the existing legacy installation ID into senyalert.env; client-id.txt was preserved unchanged.");
        }
    }

    private String clientId() {
        if (clientId == null) validateReady();
        return clientId;
    }

    @Override public synchronized String supportClientId() {
        validateReady();
        return clientId;
    }

    private SecurityContext context(String id) {
        grantor.require(Permission.REMOTE_SUPPORT);
        if (id == null || !id.matches("[A-Za-z0-9-]{8,128}")) throw new SecurityException("Invalid support session.");
        return sessions.computeIfAbsent(id, key -> grantor.service().remoteContext(grantor, key));
    }
    @Override public JSONObject snapshot(String remoteSessionId) {
        SecurityContext remote = context(remoteSessionId);
        remote.require(Permission.VIEW_INCIDENTS);
        String identity = clientId();
        JSONArray incidents = new JSONArray();
        repository.listRecent().join().stream().limit(1000).forEach(i -> incidents.put(new JSONObject()
                .put("id", i.id()).put("cameraId", i.cameraId()).put("timestamp", i.detectionTimestamp())
                .put("confidence", i.confidence()).put("status", i.status().name()).put("note", i.operatorNotes())));
        return new JSONObject().put("clientId", identity)
                .put("clientName", Optional.ofNullable(EnvironmentConfiguration.value("SENYALERT_CLIENT_NAME"))
                        .filter(name -> !name.isBlank()).orElse("SenyAlert " + identity.substring(0, 8)))
                .put("settings", ConfigurationFiles.redacted(controller.currentSettings().toPersistedJson()))
                .put("users", grantor.service().userSnapshot(remote.session())).put("incidents", incidents)
                .put("database", new JSONObject().put("engine", "SQLite").put("recentIncidentCount", incidents.length())
                        .put("backupLocation", "support-backups (on client computer)"));
    }
    @Override public JSONObject execute(String action, JSONObject payload, String remoteSessionId) throws Exception {
        SecurityContext remote = context(remoteSessionId);
        switch (action) {
            case "settings.update", "cameras.update" -> {
                boolean camerasOnly = action.equals("cameras.update");
                remote.require(camerasOnly ? Permission.CONFIGURE_CAMERAS : Permission.CONFIGURE_ENGINE);
                if (!camerasOnly) remote.require(Permission.CONFIGURE_CAMERAS);
                JSONObject before = controller.currentSettings().toPersistedJson();
                JSONObject requested = camerasOnly ? new JSONObject(before.toString()).put("cameras", payload.getJSONArray("cameras"))
                        : new JSONObject(payload.getJSONObject("settings").toString());
                restorePrivateSources(before, requested);
                ConfigurationFiles.validate(requested, camerasOnly);
                EngineSettings settings = EngineSettings.fromJson(requested);
                remote.audit("REMOTE_CONFIG_UPDATE_REQUESTED", "configuration", ConfigurationFiles.diff(before, settings.toPersistedJson()));
                controller.applyRemoteSettings(settings).join();
                remote.audit("REMOTE_CONFIG_UPDATE_COMPLETED", "configuration", ConfigurationFiles.diff(before, settings.toPersistedJson()));
                return new JSONObject().put("saved", true);
            }
            case "incident.acknowledge", "incident.resolve", "incident.note" -> {
                long id = payload.getLong("id");
                Incident before = repository.findEvidence(id).join().orElseThrow(() -> new IllegalArgumentException("Incident not found")).incident();
                Permission permission = action.equals("incident.note") ? Permission.EDIT_NOTES
                        : action.equals("incident.resolve") ? Permission.RESOLVE_INCIDENTS : Permission.ACKNOWLEDGE_INCIDENTS;
                IncidentStatus status = action.equals("incident.note") ? before.status()
                        : action.equals("incident.resolve") ? IncidentStatus.RESOLVED : IncidentStatus.ACKNOWLEDGED;
                String note = action.equals("incident.note") ? payload.getString("note") : before.operatorNotes();
                OperatorIncidentUpdate update = new OperatorIncidentUpdate(status, note);
                JSONObject change = new JSONObject().put("beforeStatus", before.status()).put("beforeNote", before.operatorNotes())
                        .put("afterStatus", status).put("afterNote", update.operatorNotes());
                remote.beginAction(permission, "REMOTE_INCIDENT_UPDATE", "incident:" + id, change.toString());
                Incident saved = repository.updateOperatorRecord(id, update).join().orElseThrow();
                remote.audit("REMOTE_INCIDENT_UPDATE_COMPLETED", "incident:" + id, change.toString());
                controller.refreshIncidents();
                return new JSONObject().put("id", saved.id()).put("status", saved.status()).put("note", saved.operatorNotes());
            }
            case "users.create", "users.update", "users.resetPassword" -> {
                remote.require(Permission.MANAGE_USERS);
                String username = payload.getString("username");
                UserAccount existing = grantor.service().listUsers(remote.session()).stream().filter(u -> u.username().equalsIgnoreCase(username)).findFirst().orElse(null);
                Role role = Role.valueOf(payload.optString("role", existing == null ? "USER" : existing.role().name()).toUpperCase(Locale.ROOT));
                if (role == Role.SUPERADMIN) throw new SecurityException("Superadmin credentials are provisioned only on the developer computer.");
                Set<Permission> permissions = existing == null ? EnumSet.noneOf(Permission.class) : new HashSet<>(existing.permissions());
                if (payload.has("permissions")) {
                    permissions.clear(); for (Object value : payload.getJSONArray("permissions")) permissions.add(Permission.valueOf(value.toString()));
                }
                char[] password = payload.has("password") ? payload.getString("password").toCharArray() : null;
                try {
                    UserAccount account;
                    if (action.equals("users.create")) {
                        if (password == null) throw new IllegalArgumentException("A new account needs a password.");
                        account = grantor.service().createUser(remote.session(), username, password, role, permissions);
                    } else {
                        if (existing == null) throw new IllegalArgumentException("User not found.");
                        if (action.equals("users.resetPassword") && password == null) throw new IllegalArgumentException("Enter a replacement password.");
                        account = grantor.service().updateUser(remote.session(), existing.id(), role, permissions, payload.optBoolean("enabled", existing.enabled()), password);
                    }
                    return new JSONObject().put("username", account.username()).put("role", account.role());
                } finally { if (password != null) Arrays.fill(password, '\0'); payload.remove("password"); }
            }
            case "database.backup" -> {
                remote.beginAction(Permission.EXPORT_EVIDENCE, "DATABASE_BACKUP", "SQLite", "Create consistent copies inside client support-backups");
                Path output = Files.createDirectories(directory.resolve("support-backups")).resolve("backup-" + UUID.randomUUID());
                Files.createDirectory(output);
                for (String name : List.of("incidents.db", "security.db")) {
                    Path source = directory.resolve(name), target = output.resolve(name);
                    if (!Files.isRegularFile(source)) continue;
                    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source); var statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=10000");
                        statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
                    }
                }
                remote.audit("DATABASE_BACKUP_COMPLETED", "SQLite", output.getFileName().toString());
                return new JSONObject().put("backup", output.getFileName().toString()).put("location", "Client computer: support-backups");
            }
            default -> throw new IllegalArgumentException("Unsupported support operation.");
        }
    }
    @Override public void audit(String action, JSONObject details) {
        String id = details.optString("supportSession");
        if (action.equals("remote.session.stopped")) {
            grantor.audit(action, "remote-support", details.toString());
            SecurityContext old = sessions.remove(id); if (old != null) old.logout();
        } else if (action.startsWith("remote.action.") || action.startsWith("remote.snapshot.")) context(id).audit(action, "remote-support", details.toString());
        else {
            if (action.equals("remote.session.started") || action.equals("remote.link.generated") || action.equals("remote.link.copied")
                    || action.equals("remote.client_id.copied") || action.equals("remote.support_key.copied")) {
                grantor.require(Permission.REMOTE_SUPPORT);
            }
            grantor.audit(action, "remote-support", details.toString());
        }
    }
    private static void restorePrivateSources(JSONObject before, JSONObject requested) {
        JSONArray cameras = requested.optJSONArray("cameras");
        if (cameras == null) return;
        for (Object value : cameras) if (value instanceof JSONObject camera) {
            String sourceKey = "[private camera source]".equals(camera.optString("camera_source")) ? "camera_source"
                    : "[private camera source]".equals(camera.optString("source")) ? "source" : null;
            if (sourceKey == null) continue;
            JSONObject original = null;
            for (Object old : before.getJSONArray("cameras")) if (old instanceof JSONObject c && c.optString("camera_id").equals(camera.optString("camera_id"))) original = c;
            if (original == null) throw new IllegalArgumentException("Set an env: camera source for a new camera.");
            camera.put(sourceKey, original.getString("camera_source"));
        }
        if ("[private camera source]".equals(requested.optString("camera_source"))) requested.put("camera_source", before.getString("camera_source"));
    }
}
