package com.senyalert.security;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Isolated checks. Every generated file is retained; never opens a production archive. */
public final class SecuritySmoke {
    private static int passed;
    private SecuritySmoke() { }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("target", "security-smoke-" + System.currentTimeMillis()) : Path.of(args[0]);
        if (Files.exists(root)) throw new IllegalArgumentException("Use a new empty smoke directory, not an existing installation.");
        Files.createDirectories(root);
        char[] adminPassword = "Smoke-only admin password 27!".toCharArray();
        char[] userPassword = "Smoke-only operator password 27!".toCharArray();
        char[] developerPassword = "Smoke-only developer password 27!".toCharArray();
        String developerHash = PasswordHasher.hash(developerPassword);
        SecurityService service = new SecurityService(root.resolve("security.db"), developerHash);
        service.initialize();
        check(service.needsBootstrap(), "fresh installation needs explicit bootstrap");
        Session admin = service.bootstrapAdmin("local-admin", adminPassword);
        check(!service.needsBootstrap(), "bootstrap creates one local administrator");
        expectFailure(() -> service.bootstrapAdmin("another-admin", adminPassword), "bootstrap cannot run twice");
        check(service.authenticate("local-admin", "wrong password".toCharArray()).isEmpty(), "incorrect login rejected");
        check(service.authenticate("LOCAL-ADMIN", adminPassword).isPresent(), "username case normalized");
        UserAccount user = service.createUser(admin, "operator", userPassword, Role.USER, EnumSet.of(Permission.VIEW_INCIDENTS, Permission.ACKNOWLEDGE_INCIDENTS));
        UserAccount removable = service.createUser(admin, "removable", userPassword, Role.USER, Set.of(Permission.VIEW_INCIDENTS));
        Session removableSession = service.authenticate("removable", userPassword).orElseThrow();
        UserAccount deleted = service.deleteUser(admin, removable.id());
        check("removable".equals(deleted.username()), "administrator can delete another local account");
        check(!service.can(removableSession, Permission.VIEW_INCIDENTS), "deleted account sessions are revoked");
        check(service.authenticate("removable", userPassword).isEmpty(), "deleted account cannot authenticate");
        check(service.listAudit(admin, "USER_DELETED", 10).stream().anyMatch(entry -> entry.target().equals("user:" + removable.id())),
                "account deletion retains an audited attribution record");
        expectFailure(() -> service.deleteUser(admin, admin.userId()), "signed-in account cannot be deleted");
        Session operator = service.authenticate("operator", userPassword).orElseThrow();
        Session initialOperator = operator;
        check(service.can(operator, Permission.ACKNOWLEDGE_INCIDENTS), "explicit grant allowed");
        check(!service.can(operator, Permission.DELETE_RECORDS), "ungranted action denied");
        expectFailure(() -> service.require(initialOperator, Permission.DELETE_RECORDS), "action boundary denies missing permission");
        expectFailure(() -> service.createUser(initialOperator, "escalated", userPassword, Role.ADMIN, Set.of()), "user cannot grant admin");
        expectFailure(() -> service.createUser(admin, "superadmin", userPassword, Role.ADMIN, Set.of()), "reserved username denied");
        expectFailure(() -> service.createUser(admin, "developer", userPassword, Role.SUPERADMIN, Set.of()), "superadmin role cannot be assigned");
        expectFailure(() -> service.createUser(admin, "manager", userPassword, Role.USER, Set.of(Permission.MANAGE_USERS)), "users cannot receive permission to promote themselves");
        expectFailure(() -> service.updateUser(admin, admin.userId(), Role.USER, Set.of(), true, null), "last administrator cannot be demoted");
        check(!PasswordHasher.hash(userPassword).equals(PasswordHasher.hash(userPassword)), "random salts produce distinct verifiers");
        check(!PasswordHasher.verify(userPassword, "pbkdf2-sha256$1$bad$bad"), "weak or malformed verifier rejected");
        check(service.authenticate("superadmin", developerPassword).orElseThrow().isSuperadmin(), "developer verifier authenticates separate role");
        expectFailure(() -> service.userSnapshot(admin), "ordinary admin cannot export password hashes");
        Session developer = service.authenticate("superadmin", developerPassword).orElseThrow();
        check(service.userSnapshot(developer).getJSONObject(0).has("passwordHash"), "developer snapshot includes salted hash only");
        expectFailure(() -> service.deleteUser(developer, admin.userId()), "last enabled local administrator cannot be deleted");
        Session forged = new Session(admin.userId(), admin.username(), Role.ADMIN, Set.of(), admin.authVersion(), UUID.randomUUID().toString());
        check(!service.can(forged, Permission.DELETE_RECORDS), "unissued sessions rejected");
        service.updateUser(admin, user.id(), Role.USER, Set.of(Permission.VIEW_INCIDENTS), true, null);
        check(!service.can(operator, Permission.VIEW_INCIDENTS), "permission changes revoke existing session");
        service.logout(operator);
        check(!service.can(operator, Permission.VIEW_INCIDENTS), "sign-out releases a revoked session without disrupting the login flow");
        operator = service.authenticate("operator", userPassword).orElseThrow();
        check(!service.can(operator, Permission.ACKNOWLEDGE_INCIDENTS), "next login has updated grants");
        SecurityContext grantor = new SecurityContext(service, admin);
        SecurityContext remote = service.remoteContext(grantor, "smoke-support-session");
        check(remote.can(Permission.CONFIGURE_ENGINE), "authenticated remote context receives support privileges");
        remote.audit("REMOTE_TEST", "engine", "Requested by test developer");
        check(service.listAudit(admin, "REMOTE_TEST", 10).get(0).details().contains("local-admin"), "remote audit preserves local grantor");
        remote.audit("REMOTE_REDACTION_TEST", "support", "{\"password\":\"remote-plaintext\",\"url\":\"rtsp://camera:secret@localhost/feed\"}");
        String remoteDetails = service.listAudit(admin, "REMOTE_REDACTION_TEST", 1).get(0).details();
        check(!remoteDetails.contains("remote-plaintext") && !remoteDetails.contains("camera:secret"), "remote audit details redacted before JSON nesting");
        service.audit(admin, "REDACTION_TEST", "camera", "{\"password\":\"never-persist-this\",\"url\":\"rtsp://camera:secret@localhost/feed\"}");
        String details = service.listAudit(admin, "REDACTION_TEST", 1).get(0).details();
        check(!details.contains("never-persist-this") && !details.contains("camera:secret"), "password and camera credentials redacted");
        int count = service.listUsers(admin).size();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + service.databasePath()); Statement s = c.createStatement()) {
            s.execute("CREATE TRIGGER smoke_audit_failure BEFORE INSERT ON security_audit WHEN NEW.action='USER_CREATED' BEGIN SELECT RAISE(ABORT, 'simulated unavailable audit'); END");
        }
        expectFailure(() -> service.createUser(admin, "must-rollback", userPassword, Role.USER, Set.of()), "audit failure blocks account mutation");
        check(service.listUsers(admin).size() == count, "failed audited mutation rolled back");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + service.databasePath()); Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER smoke_audit_failure");
            boolean rejected = false;
            try { s.executeUpdate("UPDATE security_audit SET details='changed' WHERE id=1"); } catch (SQLException expected) { rejected = true; }
            check(rejected, "SQL audit update rejected by append-only trigger");
        }
        check(service.verifyAuditChain(admin), "audit chain verifies");
        service.exportAudit(admin, root.resolve("audit-export.json"), "");
        check(Files.size(root.resolve("audit-export.json")) > 0, "JSON audit export written");
        for (int i = 0; i < 5; i++) service.authenticate("operator", "incorrect".toCharArray());
        check(service.authenticate("operator", userPassword).isEmpty(), "repeated bad logins trigger temporary lockout");
        service.logout(admin);
        check(!remote.can(Permission.CONFIGURE_ENGINE), "remote session revokes with grantor logout");
        SecurityService reopened = new SecurityService(service.databasePath(), developerHash); reopened.initialize();
        check(reopened.authenticate("local-admin", adminPassword).isPresent(), "accounts and audit survive reopen");
        check(reopened.authenticate("operator", userPassword).isEmpty(), "login throttle survives restart");
        SecurityService noDeveloper = new SecurityService(root.resolve("unconfigured.db"), null); noDeveloper.initialize();
        check(noDeveloper.authenticate("superadmin", developerPassword).isEmpty(), "no built-in developer password");
        SecurityService tamper = new SecurityService(root.resolve("tamper-check.db"), null); tamper.initialize(); tamper.bootstrapAdmin("tamper-admin", adminPassword);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tamper.databasePath()); Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER security_audit_no_update");
            s.executeUpdate("UPDATE security_audit SET details='tampered' WHERE id=1");
        }
        expectFailure(() -> new SecurityService(tamper.databasePath(), null).initialize(), "startup rejects modified audit chain");

        SecurityService accountService = new SecurityService(root.resolve("account-audit.db"), null);
        accountService.initialize();
        Session accountAdmin = accountService.bootstrapAdmin("account-admin", adminPassword);
        char[] accountPassword = "Smoke-only account password 27!".toCharArray();
        char[] changedPassword = "Smoke-only changed password 27!".toCharArray();
        char[] resetPassword = "Smoke-only reset password 27!".toCharArray();
        expectFailure(() -> accountService.resetUserPassword(accountAdmin, accountAdmin.userId(), resetPassword), "self password changes use the dedicated account path");
        UserAccount account = accountService.createUser(accountAdmin, "self-account", accountPassword, Role.USER, EnumSet.of(Permission.VIEW_INCIDENTS));
        Session self = accountService.authenticate("self-account", accountPassword).orElseThrow();
        expectFailure(() -> accountService.updateOwnCredentials(self, "self-renamed", "wrong password".toCharArray(), changedPassword), "self-service account update requires current password");
        expectFailure(() -> accountService.updateOwnCredentials(self, "account-admin", accountPassword, changedPassword), "self-service account update cannot reuse another account username");
        UserAccount renamed = accountService.updateOwnCredentials(self, "self-renamed", accountPassword, changedPassword);
        check("self-renamed".equals(renamed.username()), "self-service update changes only the signed-in account name");
        check(!accountService.can(self, Permission.VIEW_INCIDENTS), "self-service account update revokes the old session");
        check(accountService.authenticate("self-account", changedPassword).isEmpty(), "old username cannot authenticate after self-service update");
        Session renamedSession = accountService.authenticate("self-renamed", changedPassword).orElseThrow();
        expectFailure(() -> accountService.resetUserPassword(renamedSession, accountAdmin.userId(), resetPassword), "non-admin cannot use the forgot-password reset path");
        accountService.resetUserPassword(accountAdmin, account.id(), resetPassword);
        check(accountService.authenticate("self-renamed", changedPassword).isEmpty(), "administrator password reset revokes the old password");
        Session resetSession = accountService.authenticate("self-renamed", resetPassword).orElseThrow();
        expectFailure(() -> accountService.resetAuditForDemo(resetSession), "dedicated audit-reset permission is enforced at the service boundary");
        Path csv = root.resolve("audit-export.csv");
        accountService.exportAuditCsv(accountAdmin, csv, "");
        check(Files.size(csv) > 0, "CSV audit export written");
        expectFailure(() -> accountService.exportAuditCsv(accountAdmin, csv, ""), "CSV audit export never replaces an existing file");
        SecurityService.AuditResetResult reset = accountService.resetAuditForDemo(accountAdmin);
        check(reset.removedEntries() > 0 && Files.size(reset.preservedCsv()) > 0, "audit reset preserves a CSV before clearing active entries");
        check(accountService.listAudit(accountAdmin, "AUDIT_LOG_RESET", 10).stream().anyMatch(entry -> entry.action().equals("AUDIT_LOG_RESET")), "new active audit trail records the reset");
        check(accountService.verifyAuditChain(accountAdmin), "audit chain verifies after supervised demo reset");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + accountService.databasePath()); Statement s = c.createStatement()) {
            boolean activeDeleteRejected = false;
            try { s.executeUpdate("DELETE FROM security_audit"); } catch (SQLException expected) { activeDeleteRejected = true; }
            check(activeDeleteRejected, "direct SQL audit delete remains blocked outside the guarded reset transaction");
            boolean resetHistoryRejected = false;
            try { s.executeUpdate("UPDATE security_audit_resets SET archive_file='changed' WHERE id=1"); } catch (SQLException expected) { resetHistoryRejected = true; }
            check(resetHistoryRejected, "audit reset history is append-only");
        }
        SecurityService resetReopened = new SecurityService(accountService.databasePath(), null);
        resetReopened.initialize();
        check(resetReopened.authenticate("account-admin", adminPassword).isPresent(), "audit reset history verifies after restart");
        Arrays.fill(accountPassword, '\0'); Arrays.fill(changedPassword, '\0'); Arrays.fill(resetPassword, '\0');
        Arrays.fill(adminPassword, '\0'); Arrays.fill(userPassword, '\0'); Arrays.fill(developerPassword, '\0');
        System.out.println("Security smoke passed: " + passed + " checks. Isolated artifacts retained at " + root.toAbsolutePath());
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static void expectFailure(Runnable operation, String label) {
        boolean failed = false;
        try { operation.run(); } catch (RuntimeException expected) { failed = true; }
        check(failed, label);
    }
}
