package com.senyalert.security;

import com.senyalert.EnvironmentConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local SQLite identities and append-only audit. No plaintext password is persisted. */
public final class SecurityService {
    private static final String GENESIS = "0".repeat(64);
    private final Path databasePath;
    private final String superadminHash;
    private final Map<String, IssuedSession> sessions = new HashMap<>();
    private final String dummyHash = PasswordHasher.hash(UUID.randomUUID().toString().toCharArray());
    private boolean initialized;

    private record IssuedSession(Session session, Instant expires, Session grantor) { }
    private interface Work<T> { T run(Connection connection) throws Exception; }

    public SecurityService(Path databasePath) {
        this(databasePath, EnvironmentConfiguration.value("SENYALERT_SUPERADMIN_PASSWORD_HASH"));
    }

    /** Explicit verifier injection is useful for an isolated installation and smoke checks. */
    public SecurityService(Path databasePath, String superadminHash) {
        this.databasePath = Objects.requireNonNull(databasePath).toAbsolutePath().normalize();
        this.superadminHash = superadminHash;
    }

    public Path databasePath() { return databasePath; }
    public boolean isSuperadminConfigured() { return PasswordHasher.isEncodedHash(superadminHash); }

    public synchronized void initialize() {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS security_users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        role TEXT NOT NULL CHECK(role IN ('ADMIN','USER')),
                        password_hash TEXT NOT NULL, permissions TEXT NOT NULL DEFAULT '[]',
                        enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
                        auth_version INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                        """);
                s.execute("""
                        CREATE TABLE IF NOT EXISTS security_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_utc TEXT NOT NULL,
                        actor TEXT NOT NULL, role TEXT NOT NULL, action TEXT NOT NULL,
                        target TEXT NOT NULL, details TEXT NOT NULL,
                        previous_hash TEXT NOT NULL, entry_hash TEXT NOT NULL)
                        """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_security_audit_time ON security_audit(timestamp_utc)");
                s.execute("""
                        CREATE TRIGGER IF NOT EXISTS security_audit_no_update BEFORE UPDATE ON security_audit
                        BEGIN SELECT RAISE(ABORT, 'Audit entries are append-only'); END
                        """);
                // The ordinary audit trail remains append-only.  A supervised demo
                // reset is the one exception and is guarded inside a single service
                // transaction; a separately chained reset history preserves proof of it.
                s.execute("""
                        CREATE TABLE IF NOT EXISTS security_audit_reset_guard (
                        id INTEGER PRIMARY KEY CHECK(id=1), authorized INTEGER NOT NULL DEFAULT 0 CHECK(authorized IN (0,1)))
                        """);
                s.execute("INSERT OR IGNORE INTO security_audit_reset_guard(id,authorized) VALUES(1,0)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS security_audit_resets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_utc TEXT NOT NULL,
                        actor TEXT NOT NULL, role TEXT NOT NULL, removed_entries INTEGER NOT NULL,
                        previous_audit_tip_hash TEXT NOT NULL, archive_file TEXT NOT NULL,
                        previous_hash TEXT NOT NULL, entry_hash TEXT NOT NULL)
                        """);
                s.execute("CREATE INDEX IF NOT EXISTS idx_security_audit_resets_time ON security_audit_resets(timestamp_utc)");
                s.execute("DROP TRIGGER IF EXISTS security_audit_no_delete");
                s.execute("""
                        CREATE TRIGGER security_audit_no_delete BEFORE DELETE ON security_audit
                        WHEN COALESCE((SELECT authorized FROM security_audit_reset_guard WHERE id=1), 0) != 1
                        BEGIN SELECT RAISE(ABORT, 'Audit entries are append-only'); END
                        """);
                s.execute("""
                        CREATE TRIGGER IF NOT EXISTS security_audit_resets_no_update BEFORE UPDATE ON security_audit_resets
                        BEGIN SELECT RAISE(ABORT, 'Audit reset history is append-only'); END
                        """);
                s.execute("""
                        CREATE TRIGGER IF NOT EXISTS security_audit_resets_no_delete BEFORE DELETE ON security_audit_resets
                        BEGIN SELECT RAISE(ABORT, 'Audit reset history is append-only'); END
                        """);
                s.execute("""
                        CREATE TABLE IF NOT EXISTS security_login_throttle (
                        username TEXT PRIMARY KEY, failures INTEGER NOT NULL DEFAULT 0,
                        locked_until INTEGER NOT NULL DEFAULT 0)
                        """);
            }
            if (!verifyAuditChainInternal() || !verifyAuditResetHistoryInternal()) {
                throw new IllegalStateException("Audit chain verification failed. Preserve the database and investigate before using the app.");
            }
            initialized = true;
        } catch (Exception failure) { throw storageFailure(failure); }
    }

    public synchronized boolean needsBootstrap() {
        ready();
        return read(c -> { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM security_users")) { return r.next() && r.getLong(1) == 0; } });
    }

    public synchronized Session bootstrapAdmin(String username, char[] password) {
        ready();
        String name = validateUsername(username);
        String hash = PasswordHasher.hash(password);
        long id = transaction(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM security_users")) {
                if (!r.next() || r.getLong(1) != 0) throw new SecurityException("First administrator setup is already complete.");
            }
            long created = insertUser(c, name, hash, Role.ADMIN, Set.of());
            append(c, name, "ADMIN", "ADMIN_BOOTSTRAPPED", "user:" + created,
                    new JSONObject().put("username", name).put("role", "ADMIN").put("password", "[REDACTED]").toString());
            append(c, name, "ADMIN", "LOGIN_SUCCEEDED", "local-login", "First administrator session");
            return created;
        });
        return issue(id, name, Role.ADMIN, Set.of(), 1, null, null);
    }

    public synchronized Optional<Session> authenticate(String username, char[] password) {
        ready();
        String name = normalizeUsername(username);
        if (name.length() > 128) name = "invalid-username";
        final String loginName = name;
        return transaction(c -> {
            long now = Instant.now().getEpochSecond();
            int failures = 0;
            long lockedUntil = 0;
            try (PreparedStatement s = c.prepareStatement("SELECT failures, locked_until FROM security_login_throttle WHERE username=?")) {
                s.setString(1, loginName);
                try (ResultSet r = s.executeQuery()) { if (r.next()) { failures = r.getInt(1); lockedUntil = r.getLong(2); } }
            }
            if (lockedUntil > now) {
                append(c, loginName, "UNAUTHENTICATED", "LOGIN_BLOCKED", "local-login", "Temporary rate limit after repeated failed login attempts");
                return Optional.empty();
            }
            if (lockedUntil > 0) failures = 0;
            UserAccount account = null;
            String hash = dummyHash;
            long version = 0;
            boolean developer = "superadmin".equals(loginName);
            if (developer && isSuperadminConfigured()) hash = superadminHash;
            if (!developer) {
                try (PreparedStatement s = c.prepareStatement("SELECT * FROM security_users WHERE username=?")) {
                    s.setString(1, loginName);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) { account = mapUser(r); hash = r.getString("password_hash"); version = r.getLong("auth_version"); }
                    }
                }
            }
            boolean matches = PasswordHasher.verify(password, hash);
            boolean valid = matches && ((developer && isSuperadminConfigured()) || (account != null && account.enabled()));
            if (!valid) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO security_login_throttle(username,failures,locked_until) VALUES(?,?,?) ON CONFLICT(username) DO UPDATE SET failures=excluded.failures,locked_until=excluded.locked_until")) {
                    s.setString(1, loginName); s.setInt(2, failures + 1); s.setLong(3, failures + 1 >= 5 ? now + 60 : 0); s.executeUpdate();
                }
                append(c, loginName, "UNAUTHENTICATED", "LOGIN_FAILED", "local-login", "Invalid credentials or disabled account");
                return Optional.empty();
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE security_login_throttle SET failures=0,locked_until=0 WHERE username=?")) { s.setString(1, loginName); s.executeUpdate(); }
            Role role = developer ? Role.SUPERADMIN : account.role();
            append(c, loginName, role.name(), "LOGIN_SUCCEEDED", "local-login", "Authenticated desktop session");
            return Optional.of(issue(developer ? -1 : account.id(), loginName, role,
                    developer ? Set.of() : account.permissions(), version, null, null));
        });
    }

    public synchronized SecurityContext remoteContext(SecurityContext grantor, String remoteSessionId) {
        Objects.requireNonNull(grantor);
        if (grantor.service() != this) throw new SecurityException("Support grant belongs to another installation.");
        require(grantor.session(), Permission.REMOTE_SUPPORT);
        if (remoteSessionId == null || !remoteSessionId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid remote session identifier");
        Session session = issue(-2, "remote-superadmin:" + remoteSessionId, Role.SUPERADMIN,
                Set.of(), 0, Instant.now().plusSeconds(1800), grantor.session());
        try { audit(session, "REMOTE_PRINCIPAL_AUTHORIZED", "support:" + remoteSessionId, "Authenticated remote developer; local grant is limited to 30 minutes"); }
        catch (RuntimeException failure) { sessions.remove(session.sessionId()); throw failure; }
        return new SecurityContext(this, session);
    }

    public synchronized boolean can(Session session, Permission permission) {
        if (session == null || permission == null || !initialized) return false;
        return read(c -> active(c, session) && session.can(permission));
    }

    public synchronized void require(Session session, Permission permission) {
        ready();
        if (!can(session, permission)) {
            transaction(c -> { append(c, safeActor(session), session == null ? "UNAUTHENTICATED" : session.role().name(),
                    "ACCESS_DENIED", permission.name(), "Permission missing or session revoked"); return null; });
            throw new SecurityException("Access denied: " + permission.description() + ". Sign in again if your permissions changed.");
        }
    }

    public synchronized void audit(Session session, String action, String target, String details) {
        ready();
        transaction(c -> {
            if (!active(c, session)) throw new SecurityException("Your session expired or was revoked. Sign in again.");
            append(c, session.username(), session.role().name(), action, target, withOrigin(session, details));
            return null;
        });
    }

    public synchronized void logout(Session session) {
        if (session == null) return;
        // A user-management change intentionally revokes the current session.  Signing
        // out must still release that stale session so the application can return to
        // the login screen; an expired or revoked identity cannot append a LOGOUT row.
        try { if (can(session, Permission.VIEW_INCIDENTS) || sessions.containsKey(session.sessionId())) audit(session, "LOGOUT", "local-session", "Session closed"); }
        catch (RuntimeException ignored) { /* A revoked or unavailable session still must be released. */ }
        finally { sessions.remove(session.sessionId()); sessions.values().removeIf(value -> session.equals(value.grantor())); }
    }

    public synchronized List<UserAccount> listUsers(Session actor) {
        require(actor, Permission.MANAGE_USERS);
        return read(c -> {
            List<UserAccount> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM security_users ORDER BY username")) { while (r.next()) result.add(mapUser(r)); }
            return List.copyOf(result);
        });
    }

    public synchronized UserAccount createUser(Session actor, String username, char[] password, Role role, Set<Permission> permissions) {
        require(actor, Permission.MANAGE_USERS);
        validateRoleAndGrants(role, permissions);
        String name = validateUsername(username);
        String hash = PasswordHasher.hash(password);
        return transaction(c -> {
            ensureActive(c, actor, Permission.MANAGE_USERS);
            long id = insertUser(c, name, hash, role, permissions);
            UserAccount after = getUser(c, id);
            append(c, actor.username(), actor.role().name(), "USER_CREATED", "user:" + id,
                    withOrigin(actor, new JSONObject().put("after", jsonUser(after)).put("passwordSet", true).toString()));
            return after;
        });
    }

    public synchronized UserAccount updateUser(Session actor, long id, Role role, Set<Permission> permissions, boolean enabled, char[] newPasswordOrNull) {
        require(actor, Permission.MANAGE_USERS);
        validateRoleAndGrants(role, permissions);
        String newHash = newPasswordOrNull == null || newPasswordOrNull.length == 0 ? null : PasswordHasher.hash(newPasswordOrNull);
        return transaction(c -> {
            ensureActive(c, actor, Permission.MANAGE_USERS);
            UserAccount before = getUser(c, id);
            if (before == null) throw new IllegalArgumentException("Account no longer exists");
            if (!actor.isSuperadmin() && before.id() == actor.userId()) {
                throw new IllegalArgumentException("Use Account settings to change your own username or password. Another administrator must change your access.");
            }
            if (before.role() == Role.ADMIN && before.enabled() && (role != Role.ADMIN || !enabled)) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM security_users WHERE role='ADMIN' AND enabled=1")) {
                    if (!r.next() || r.getInt(1) <= 1) throw new IllegalArgumentException("Keep at least one enabled local administrator.");
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE security_users SET role=?, permissions=?, enabled=?, password_hash=COALESCE(?,password_hash), auth_version=auth_version+1,updated_at=? WHERE id=?")) {
                s.setString(1, role.name()); s.setString(2, grantsJson(role, permissions).toString()); s.setInt(3, enabled ? 1 : 0);
                s.setString(4, newHash); s.setString(5, Instant.now().toString()); s.setLong(6, id); s.executeUpdate();
            }
            UserAccount after = getUser(c, id);
            append(c, actor.username(), actor.role().name(), "USER_UPDATED", "user:" + id,
                    withOrigin(actor, new JSONObject().put("before", jsonUser(before)).put("after", jsonUser(after)).put("passwordChanged", newHash != null).toString()));
            return after;
        });
    }

    /**
     * Removes an unused local sign-in account while retaining its complete,
     * immutable audit attribution. The developer superadmin is never stored in
     * this table and cannot be deleted through this path.
     */
    public synchronized UserAccount deleteUser(Session actor, long id) {
        require(actor, Permission.MANAGE_USERS);
        UserAccount deleted = transaction(c -> {
            ensureActive(c, actor, Permission.MANAGE_USERS);
            UserAccount before = getUser(c, id);
            if (before == null) {
                throw new IllegalArgumentException("Account no longer exists.");
            }
            if (before.id() == actor.userId()) {
                throw new IllegalArgumentException("You cannot delete the account currently signed in. Use another administrator, then sign in again.");
            }
            if (before.role() == Role.SUPERADMIN || "superadmin".equalsIgnoreCase(before.username())) {
                throw new IllegalArgumentException("The developer superadmin account is managed outside the local account list.");
            }
            if (before.role() == Role.ADMIN && before.enabled()) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                        "SELECT COUNT(*) FROM security_users WHERE role='ADMIN' AND enabled=1")) {
                    if (!r.next() || r.getInt(1) <= 1) {
                        throw new IllegalArgumentException("Keep at least one enabled local administrator.");
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM security_users WHERE id=?")) {
                s.setLong(1, id);
                if (s.executeUpdate() != 1) {
                    throw new IllegalArgumentException("Account no longer exists.");
                }
            }
            append(c, actor.username(), actor.role().name(), "USER_DELETED", "user:" + id,
                    withOrigin(actor, new JSONObject().put("deleted", jsonUser(before))
                            .put("password", "[REDACTED]")
                            .put("auditHistoryRetained", true).toString()));
            return before;
        });
        revokeSessionsForUser(id);
        return deleted;
    }

    /**
     * Changes only the signed-in local account's username and/or password.
     * The current password is required and the old session is revoked after
     * the audited transaction so a stale identity cannot retain access.
     */
    public synchronized UserAccount updateOwnCredentials(
            Session actor, String requestedUsername, char[] currentPassword, char[] newPasswordOrNull) {
        ready();
        Objects.requireNonNull(actor, "Sign in before changing account credentials.");
        if (actor.isSuperadmin() || actor.userId() <= 0) {
            throw new SecurityException("The developer superadmin password is configured outside the application and cannot be changed here.");
        }
        if (currentPassword == null || currentPassword.length == 0) {
            throw new IllegalArgumentException("Enter your current password to change your account.");
        }
        String username = validateUsername(requestedUsername);
        boolean passwordChanged = newPasswordOrNull != null && newPasswordOrNull.length > 0;
        String replacementHash = passwordChanged ? PasswordHasher.hash(newPasswordOrNull) : null;
        UserAccount updated = transaction(c -> {
            if (!active(c, actor)) throw new SecurityException("Your session expired or was revoked. Sign in again.");
            UserAccount before = getUser(c, actor.userId());
            if (before == null || !before.enabled() || !PasswordHasher.verify(currentPassword, passwordHash(c, before.id()))) {
                throw new SecurityException("Your current password could not be verified.");
            }
            if (before.username().equals(username) && !passwordChanged) {
                throw new IllegalArgumentException("Enter a new username or password before saving.");
            }
            try (PreparedStatement existing = c.prepareStatement("SELECT id FROM security_users WHERE username=? AND id<>?")) {
                existing.setString(1, username); existing.setLong(2, before.id());
                try (ResultSet found = existing.executeQuery()) {
                    if (found.next()) throw new IllegalArgumentException("That username is already in use.");
                }
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE security_users SET username=?, password_hash=COALESCE(?,password_hash), auth_version=auth_version+1,updated_at=? WHERE id=?")) {
                s.setString(1, username); s.setString(2, replacementHash); s.setString(3, Instant.now().toString()); s.setLong(4, before.id()); s.executeUpdate();
            }
            UserAccount after = getUser(c, before.id());
            append(c, actor.username(), actor.role().name(), "ACCOUNT_SELF_UPDATED", "user:" + before.id(),
                    withOrigin(actor, new JSONObject().put("beforeUsername", before.username()).put("afterUsername", after.username())
                            .put("passwordChanged", passwordChanged).toString()));
            return after;
        });
        // Updating auth_version invalidates the old session. The desktop returns
        // to login rather than silently replacing its signed-in identity.
        sessions.remove(actor.sessionId());
        sessions.values().removeIf(value -> actor.equals(value.grantor()));
        return updated;
    }

    /** Administrator password-reset path used for local "Forgot password" help. */
    public synchronized UserAccount resetUserPassword(Session actor, long id, char[] replacementPassword) {
        require(actor, Permission.MANAGE_USERS);
        String replacementHash = PasswordHasher.hash(replacementPassword);
        return transaction(c -> {
            ensureActive(c, actor, Permission.MANAGE_USERS);
            UserAccount before = getUser(c, id);
            if (before == null) throw new IllegalArgumentException("Account no longer exists");
            if (!actor.isSuperadmin() && before.id() == actor.userId()) {
                throw new IllegalArgumentException("Use Account settings to change your own password.");
            }
            try (PreparedStatement s = c.prepareStatement("UPDATE security_users SET password_hash=?, auth_version=auth_version+1,updated_at=? WHERE id=?")) {
                s.setString(1, replacementHash); s.setString(2, Instant.now().toString()); s.setLong(3, id); s.executeUpdate();
            }
            UserAccount after = getUser(c, id);
            append(c, actor.username(), actor.role().name(), "USER_PASSWORD_RESET", "user:" + id,
                    withOrigin(actor, new JSONObject().put("username", after.username()).put("passwordChanged", true).toString()));
            return after;
        });
    }

    /** Only authenticated developer sessions may receive verifier snapshots for client inventory. */
    public synchronized JSONArray userSnapshot(Session actor) {
        require(actor, Permission.MANAGE_USERS);
        if (!actor.isSuperadmin()) throw new SecurityException("Password verifier snapshots are reserved for the developer dashboard.");
        return transaction(c -> {
            ensureActive(c, actor, Permission.MANAGE_USERS);
            JSONArray result = new JSONArray();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM security_users ORDER BY username")) {
                while (r.next()) result.put(jsonUser(mapUser(r)).put("passwordHash", r.getString("password_hash")));
            }
            append(c, actor.username(), actor.role().name(), "USER_HASH_SNAPSHOT_EXPORTED", "users", withOrigin(actor, "Account metadata and salted password verifiers shared with authenticated developer; plaintext passwords are never stored"));
            return result;
        });
    }

    public synchronized List<AuditEntry> listAudit(Session actor, String filter, int limit) {
        require(actor, Permission.VIEW_AUDIT);
        return read(c -> {
            List<AuditEntry> result = new ArrayList<>();
            String term = "%" + (filter == null ? "" : filter.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM security_audit WHERE actor LIKE ? ESCAPE '\\' OR action LIKE ? ESCAPE '\\' OR target LIKE ? ESCAPE '\\' OR details LIKE ? ESCAPE '\\' OR timestamp_utc LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?")) {
                for (int i = 1; i <= 5; i++) s.setString(i, term);
                s.setInt(6, Math.max(1, Math.min(limit, 100_000)));
                try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(mapAudit(r)); }
            }
            return List.copyOf(result);
        });
    }

    public synchronized boolean verifyAuditChain(Session actor) {
        require(actor, Permission.VIEW_AUDIT);
        boolean valid = verifyAuditChainInternal();
        audit(actor, "AUDIT_CHAIN_CHECKED", "security-audit", "valid=" + valid);
        return valid;
    }

    public synchronized void exportAudit(Session actor, Path destination, String filter) {
        require(actor, Permission.VIEW_AUDIT);
        audit(actor, "AUDIT_EXPORT_REQUESTED", destination.toAbsolutePath().toString(), "filter=" + Objects.toString(filter, ""));
        List<AuditEntry> entries = listAudit(actor, filter, 100_000);
        JSONArray data = new JSONArray();
        for (AuditEntry e : entries) data.put(new JSONObject().put("id", e.id()).put("timestampUtc", e.timestamp()).put("actor", e.actor())
                .put("role", e.role()).put("action", e.action()).put("target", e.target()).put("details", e.details()).put("previousHash", e.previousHash()).put("entryHash", e.entryHash()));
        try {
            Files.writeString(destination, new JSONObject().put("format", "SenyAlert audit v1").put("exportedAtUtc", Instant.now().toString()).put("filter", Objects.toString(filter, ""))
                    .put("rowLimit", 100_000).put("entries", data).toString(2), StandardCharsets.UTF_8);
            audit(actor, "AUDIT_EXPORTED", destination.toAbsolutePath().toString(), "entries=" + entries.size());
        } catch (IOException failure) {
            audit(actor, "AUDIT_EXPORT_FAILED", destination.toAbsolutePath().toString(), failure.getClass().getSimpleName());
            throw new IllegalStateException("Could not export audit history", failure);
        }
    }

    /** Writes a portable, spreadsheet-friendly audit export without replacing an existing file. */
    public synchronized void exportAuditCsv(Session actor, Path destination, String filter) {
        require(actor, Permission.VIEW_AUDIT);
        Path target = requireNewExportPath(destination);
        audit(actor, "AUDIT_CSV_EXPORT_REQUESTED", target.toString(), "filter=" + Objects.toString(filter, ""));
        List<AuditEntry> entries = listAudit(actor, filter, 100_000);
        try {
            writeAuditCsv(target, entries);
            audit(actor, "AUDIT_CSV_EXPORTED", target.toString(), "entries=" + entries.size());
        } catch (IOException failure) {
            audit(actor, "AUDIT_CSV_EXPORT_FAILED", target.toString(), failure.getClass().getSimpleName());
            throw new IllegalStateException("Could not export audit history as CSV", failure);
        }
    }

    /**
     * Clears the active demo log only after preserving a CSV snapshot in the
     * installation folder. The reset itself stays in the new log and in a
     * separate append-only reset-history chain.
     */
    public synchronized AuditResetResult resetAuditForDemo(Session actor) {
        ready();
        require(actor, Permission.RESET_AUDIT_LOGS);
        List<AuditEntry> preserved = read(c -> {
            ensureActive(c, actor, Permission.RESET_AUDIT_LOGS);
            return allAuditEntries(c);
        });
        Path archive = nextAuditResetArchive();
        try {
            writeAuditCsv(archive, preserved);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not preserve the audit CSV before reset. No audit entries were removed.", failure);
        }
        return transaction(c -> {
            ensureActive(c, actor, Permission.RESET_AUDIT_LOGS);
            int removed = 0;
            String previousAuditTip = GENESIS;
            try (Statement count = c.createStatement(); ResultSet r = count.executeQuery("SELECT COUNT(*) FROM security_audit")) {
                if (r.next()) removed = r.getInt(1);
            }
            try (Statement tip = c.createStatement(); ResultSet r = tip.executeQuery("SELECT entry_hash FROM security_audit ORDER BY id DESC LIMIT 1")) {
                if (r.next()) previousAuditTip = r.getString(1);
            }
            appendAuditResetHistory(c, actor, removed, previousAuditTip, archive.getFileName().toString());
            try (PreparedStatement permit = c.prepareStatement("UPDATE security_audit_reset_guard SET authorized=1 WHERE id=1")) {
                permit.executeUpdate();
            }
            try (Statement remove = c.createStatement()) {
                remove.executeUpdate("DELETE FROM security_audit");
            }
            try (PreparedStatement revoke = c.prepareStatement("UPDATE security_audit_reset_guard SET authorized=0 WHERE id=1")) {
                revoke.executeUpdate();
            }
            append(c, actor.username(), actor.role().name(), "AUDIT_LOG_RESET", "security-audit",
                    withOrigin(actor, new JSONObject().put("removedEntries", removed).put("preservedCsv", archive.getFileName().toString())
                            .put("previousAuditTipHash", previousAuditTip).toString()));
            return new AuditResetResult(removed, archive, previousAuditTip);
        });
    }

    public record AuditResetResult(int removedEntries, Path preservedCsv, String previousAuditTipHash) { }

    private Session issue(long id, String username, Role role, Set<Permission> permissions, long version, Instant expires, Session grantor) {
        Session session = new Session(id, username, role, permissions, version, UUID.randomUUID().toString());
        sessions.put(session.sessionId(), new IssuedSession(session, expires, grantor));
        return session;
    }

    /** Remove cached local and support-derived sessions after an account is removed. */
    private void revokeSessionsForUser(long userId) {
        Set<String> revoked = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (var entry : new ArrayList<>(sessions.entrySet())) {
                Session candidate = entry.getValue().session();
                Session grantor = entry.getValue().grantor();
                if (candidate.userId() == userId || (grantor != null && revoked.contains(grantor.sessionId()))) {
                    if (sessions.remove(entry.getKey()) != null) {
                        revoked.add(candidate.sessionId());
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    private boolean active(Connection c, Session session) throws SQLException {
        if (session == null) return false;
        IssuedSession issued = sessions.get(session.sessionId());
        if (issued == null || !issued.session().equals(session) || (issued.expires() != null && !Instant.now().isBefore(issued.expires()))) return false;
        if (issued.grantor() != null) return active(c, issued.grantor()) && issued.grantor().can(Permission.REMOTE_SUPPORT);
        if (session.role() == Role.SUPERADMIN) return session.userId() == -1 && isSuperadminConfigured();
        try (PreparedStatement s = c.prepareStatement("SELECT enabled,auth_version FROM security_users WHERE id=?")) {
            s.setLong(1, session.userId());
            try (ResultSet r = s.executeQuery()) { return r.next() && r.getInt(1) == 1 && r.getLong(2) == session.authVersion(); }
        }
    }

    private void ensureActive(Connection c, Session actor, Permission permission) throws SQLException {
        if (!active(c, actor) || !actor.can(permission)) throw new SecurityException("Session revoked or permission denied.");
    }

    private String withOrigin(Session session, String details) {
        // Redact before a remote action's detail is nested and JSON-escaped.
        // The final append also redacts defensively, but at that point inner
        // quotation marks would already be escaped.
        String safeDetails = redact(Objects.toString(details, ""));
        IssuedSession issued = sessions.get(session.sessionId());
        if (issued != null && issued.grantor() != null) return new JSONObject().put("localGrantor", issued.grantor().username()).put("remoteSession", session.username()).put("details", safeDetails).toString();
        return safeDetails;
    }

    private long insertUser(Connection c, String name, String hash, Role role, Set<Permission> permissions) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement s = c.prepareStatement("INSERT INTO security_users(username,role,password_hash,permissions,created_at,updated_at) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, name); s.setString(2, role.name()); s.setString(3, hash); s.setString(4, grantsJson(role, permissions).toString()); s.setString(5, now); s.setString(6, now); s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) { if (r.next()) return r.getLong(1); }
        }
        throw new SQLException("Account ID was not returned");
    }

    private UserAccount getUser(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM security_users WHERE id=?")) { s.setLong(1, id); try (ResultSet r = s.executeQuery()) { return r.next() ? mapUser(r) : null; } }
    }

    private String passwordHash(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT password_hash FROM security_users WHERE id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return r.getString(1);
            }
        }
        throw new IllegalArgumentException("Account no longer exists");
    }

    private static UserAccount mapUser(ResultSet r) throws SQLException {
        EnumSet<Permission> grants = EnumSet.noneOf(Permission.class);
        JSONArray values = new JSONArray(r.getString("permissions"));
        for (int i = 0; i < values.length(); i++) grants.add(Permission.valueOf(values.getString(i)));
        return new UserAccount(r.getLong("id"), r.getString("username"), Role.valueOf(r.getString("role")), grants, r.getInt("enabled") == 1, r.getString("created_at"), r.getString("updated_at"));
    }

    private static JSONObject jsonUser(UserAccount user) {
        return new JSONObject().put("id", user.id()).put("username", user.username()).put("role", user.role().name()).put("permissions", grantsJson(user.role(), user.permissions()))
                .put("enabled", user.enabled()).put("createdAtUtc", user.createdAt()).put("updatedAtUtc", user.updatedAt());
    }

    private static JSONArray grantsJson(Role role, Set<Permission> grants) {
        JSONArray result = new JSONArray();
        if (role == Role.USER) grants.stream().sorted().forEach(value -> result.put(value.name()));
        return result;
    }

    private static void validateRoleAndGrants(Role role, Set<Permission> permissions) {
        if (role == null || role == Role.SUPERADMIN) throw new SecurityException("Superadmin is a reserved developer login and cannot be assigned to an account.");
        Objects.requireNonNull(permissions, "Permissions are required");
        if (role == Role.USER && permissions.contains(Permission.MANAGE_USERS)) throw new IllegalArgumentException("User management is reserved for administrators; choose the admin role.");
    }

    private static String validateUsername(String username) {
        String value = normalizeUsername(username);
        if ("superadmin".equals(value)) throw new IllegalArgumentException("The superadmin username is reserved for the developer.");
        if (!value.matches("[a-z][a-z0-9._-]{2,31}")) throw new IllegalArgumentException("Username: 3 to 32 characters, starting with a letter; use letters, numbers, dot, dash, or underscore.");
        return value;
    }
    private static String normalizeUsername(String username) { return username == null ? "" : username.strip().toLowerCase(Locale.ROOT); }
    private static String safeActor(Session session) { return session == null ? "anonymous" : session.username(); }

    private void append(Connection c, String actor, String role, String action, String target, String details) throws SQLException {
        if (action == null || action.isBlank() || action.length() > 150) throw new IllegalArgumentException("An audit action is required (maximum 150 characters)");
        String previous = GENESIS;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT entry_hash FROM security_audit ORDER BY id DESC LIMIT 1")) { if (r.next()) previous = r.getString(1); }
        String timestamp = Instant.now().toString();
        String safeTarget = redact(Objects.toString(target, ""));
        String safeDetails = redact(Objects.toString(details, ""));
        String hash = digest(timestamp, actor, role, action, safeTarget, safeDetails, previous);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO security_audit(timestamp_utc,actor,role,action,target,details,previous_hash,entry_hash) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setString(1, timestamp); s.setString(2, actor); s.setString(3, role); s.setString(4, action); s.setString(5, safeTarget); s.setString(6, safeDetails); s.setString(7, previous); s.setString(8, hash); s.executeUpdate();
        }
    }

    /** Best effort defense in depth; callers must supply redacted before/after configuration. */
    private static String redact(String text) {
        return text.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
                .replaceAll("(?i)(\\\"(?:password|passwordHash|password_hash|secret|token|accessToken|supportLink|supportSecret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1[REDACTED]$2")
                .replaceAll("(?i)([?&](?:password|secret|token|key)=)[^&\\s\\\"]+", "$1[REDACTED]");
    }

    private boolean verifyAuditChainInternal() {
        return read(c -> {
            String previous = GENESIS;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM security_audit ORDER BY id")) {
                while (r.next()) {
                    AuditEntry e = mapAudit(r);
                    if (!previous.equals(e.previousHash()) || !digest(e.timestamp(), e.actor(), e.role(), e.action(), e.target(), e.details(), e.previousHash()).equals(e.entryHash())) return false;
                    previous = e.entryHash();
                }
            }
            return true;
        });
    }

    private boolean verifyAuditResetHistoryInternal() {
        return read(c -> {
            String previous = GENESIS;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM security_audit_resets ORDER BY id")) {
                while (r.next()) {
                    String timestamp = r.getString("timestamp_utc");
                    String actor = r.getString("actor");
                    String role = r.getString("role");
                    int removed = r.getInt("removed_entries");
                    String tip = r.getString("previous_audit_tip_hash");
                    String archive = r.getString("archive_file");
                    String entryPrevious = r.getString("previous_hash");
                    String entryHash = r.getString("entry_hash");
                    if (!previous.equals(entryPrevious)
                            || !digest(timestamp, actor, role, "AUDIT_LOG_RESET", archive, removed + "|" + tip, entryPrevious).equals(entryHash)) {
                        return false;
                    }
                    previous = entryHash;
                }
            }
            return true;
        });
    }

    private void appendAuditResetHistory(Connection c, Session actor, int removedEntries, String previousAuditTip, String archiveFile) throws SQLException {
        String previous = GENESIS;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT entry_hash FROM security_audit_resets ORDER BY id DESC LIMIT 1")) {
            if (r.next()) previous = r.getString(1);
        }
        String timestamp = Instant.now().toString();
        String archive = Objects.requireNonNull(archiveFile, "Archive file is required");
        String tip = previousAuditTip == null || previousAuditTip.isBlank() ? GENESIS : previousAuditTip;
        String entryHash = digest(timestamp, actor.username(), actor.role().name(), "AUDIT_LOG_RESET", archive,
                removedEntries + "|" + tip, previous);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO security_audit_resets(timestamp_utc,actor,role,removed_entries,previous_audit_tip_hash,archive_file,previous_hash,entry_hash) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setString(1, timestamp); s.setString(2, actor.username()); s.setString(3, actor.role().name()); s.setInt(4, removedEntries);
            s.setString(5, tip); s.setString(6, archive); s.setString(7, previous); s.setString(8, entryHash); s.executeUpdate();
        }
    }

    private static List<AuditEntry> allAuditEntries(Connection c) throws SQLException {
        List<AuditEntry> entries = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM security_audit ORDER BY id")) {
            while (r.next()) entries.add(mapAudit(r));
        }
        return List.copyOf(entries);
    }

    private Path nextAuditResetArchive() {
        Path parent = databasePath.getParent();
        if (parent == null) throw new IllegalStateException("Security database has no installation directory.");
        Path archiveDirectory = parent.resolve("audit-reset-archives").normalize();
        try {
            Files.createDirectories(archiveDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create the protected audit reset archive folder.", failure);
        }
        return archiveDirectory.resolve("audit-before-demo-reset-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".csv");
    }

    private static Path requireNewExportPath(Path destination) {
        if (destination == null) throw new IllegalArgumentException("Choose a CSV destination first.");
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IllegalArgumentException("Choose a new CSV filename; existing files are never replaced.");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("Choose a CSV destination inside an existing folder.");
        return target;
    }

    private static void writeAuditCsv(Path destination, List<AuditEntry> entries) throws IOException {
        try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("id,timestamp_utc,actor,role,action,target,details,previous_hash,entry_hash");
            writer.newLine();
            for (AuditEntry entry : entries) {
                writer.write(csv(entry.id())); writer.write(',');
                writer.write(csv(entry.timestamp())); writer.write(',');
                writer.write(csv(entry.actor())); writer.write(',');
                writer.write(csv(entry.role())); writer.write(',');
                writer.write(csv(entry.action())); writer.write(',');
                writer.write(csv(entry.target())); writer.write(',');
                writer.write(csv(entry.details())); writer.write(',');
                writer.write(csv(entry.previousHash())); writer.write(',');
                writer.write(csv(entry.entryHash())); writer.newLine();
            }
        }
    }

    private static String csv(Object value) {
        return "\"" + Objects.toString(value, "").replace("\"", "\"\"") + "\"";
    }

    private static AuditEntry mapAudit(ResultSet r) throws SQLException {
        return new AuditEntry(r.getLong("id"), r.getString("timestamp_utc"), r.getString("actor"), r.getString("role"), r.getString("action"), r.getString("target"), r.getString("details"), r.getString("previous_hash"), r.getString("entry_hash"));
    }
    private static String digest(String... fields) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new JSONArray(Arrays.asList(fields)).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Audit digest unavailable", failure); }
    }
    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement s = connection.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA synchronous=FULL"); }
        return connection;
    }
    private <T> T read(Work<T> work) {
        try (Connection c = open()) { return work.run(c); }
        catch (Exception failure) { throw storageFailure(failure); }
    }
    private <T> T transaction(Work<T> work) {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try { T result = work.run(c); s.execute("COMMIT"); return result; }
            catch (Exception failure) { try { s.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); } throw failure; }
        } catch (Exception failure) { throw storageFailure(failure); }
    }
    private static RuntimeException storageFailure(Exception failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Security/audit storage unavailable. The action was stopped.", failure);
    }
    private void ready() { if (!initialized) throw new IllegalStateException("Initialize security storage before use."); }
}
