# Local access and audit records

SenyAlert opens with a local sign-in dialog. On a new installation, create the
first local administrator there. Passwords are stored only as salted PBKDF2
verifiers in `security.db`.

Use [environment configuration](environment-setup.md) for the developer
superadmin verifier, Remote Support client identity, and other process-level
settings. These values are separate from SQLite and engine configuration files.

## Roles

| Role | Access |
| --- | --- |
| Admin | Full local dashboard access, including user management and audit-log review. |
| User | Only the individual permissions assigned in **User management**. |
| Superadmin | Developer-only local login that opens the separate SenyAlert Superadmin Dashboard. Its verifier is supplied through `SENYALERT_SUPERADMIN_PASSWORD_HASH` on the developer computer. |

User permissions cover incident viewing, acknowledgement, resolution, notes,
record deletion, evidence export, cameras, engine settings, user management,
benchmarking, remote support, audit review, and configuration export. Changes
to a user revoke that person's existing session so the new access takes effect
at the next sign-in.

## Audit history

The **Audit logs** tab records sign-in attempts, permission denials, incident
workflow updates, notes, media and record-data copies/exports, configuration
changes, user changes, benchmark runs, and Remote Support activity. Each entry
has a timestamp, actor, role, action, target, details, and a chained hash.
SQLite triggers prevent normal update or deletion of audit entries. Passwords,
support secrets, and camera credentials are redacted from audit details.

Use the tab's export control when an authorised reviewer needs a JSON copy of
the audit history. Preserve the source `security.db` with the export when the
chain needs later verification.
