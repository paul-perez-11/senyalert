# Remote Support Setup

Remote Support creates a temporary SenyAlert API on `127.0.0.1` and asks
`cloudflared` to expose only that temporary API through an HTTPS Quick Tunnel.
It does not expose a local file browser, a shell, or the client computer's
desktop. The client chooses a session length from 1 to 120 minutes and can stop
the session at any time.

## One-time setup

Each client computer needs these prerequisites before the **Start support**
button can work:

Use [environment configuration](environment-setup.md) to set these values in
`senyalert.env` without placing them in the saved camera/engine JSON.

1. Install Cloudflare's `cloudflared` executable and make it available as
   `cloudflared` on the application process `PATH`. If it is installed
   elsewhere, set `SENYALERT_CLOUDFLARED` to its full executable path.
2. Allow the client computer to make outbound HTTPS connections to Cloudflare.
   The temporary tunnel uses an outbound connection; it does not require a
   router port-forward or inbound firewall rule.
3. Generate a different high-entropy support key for every client installation
   and set it in that client's launch environment as
   `SENYALERT_SUPPORT_SECRET`. It must be at least 32 characters. Store this
   key in the developer's password manager with the client record. Do not put
   it in a support link, configuration export, source repository, note, or
   message to the client.

`SENYALERT_CLIENT_ID` is required for a new client installation before Remote
Support can start. `SENYALERT_CLIENT_NAME` is optional; when absent, the
developer dashboard displays a short form of the client ID. Existing
installations with a valid legacy `client-id.txt` migrate that value into
`senyalert.env` during the first user-approved **Start support** action; the
legacy file remains untouched.

Set `SENYALERT_SUPERADMIN_PASSWORD_HASH` only on the developer computer. It
enables the separate **SenyAlert Superadmin Dashboard** after the `superadmin`
login succeeds. Do not configure that verifier on a client computer. The
developer password can be turned into a verifier with
`com.senyalert.security.SuperadminPasswordTool`; it never stores a plaintext
password.

The Remote Support tab conceals both the client ID and support key. **Copy
Client ID** and **Copy Support Key** are the only in-app ways to place those
values on the local clipboard, and each copy is written to the client audit
log. For a real client installation, keep the support key separate from the
support link. Do not send both in one message.

## Client support session

1. The client confirms a support session with the developer, chooses a whole
   number of minutes from 1 to 120, then opens **Remote Support** and presses
   **Start support**.
2. When the state says the connection is ready, the client presses **Copy
   Link**. The link stays masked in the app and cannot be selected or revealed.
   Pressing the button creates an audit entry.
3. The client sends the whole copied link privately, including the part after
   `#`, to the developer. The fragment is a one-time session capability and is
   never displayed in the dashboard.
4. The developer signs in locally as `superadmin`, opens the Superadmin
   Dashboard, and pastes both the complete link and that client's separately
   provisioned support key. Neither value is saved in the client registry.
5. The client presses **Stop support** when the work is complete. Stopping,
   expiry, or closing SenyAlert revokes the temporary endpoint.

The support link and support key are both required. The link alone is not
sufficient to read or change the client.

## What the developer can do during a session

The Superadmin Dashboard can read the client snapshot, save a local client
record, manage local client accounts, update engine and camera configuration,
acknowledge or resolve incidents, update notes, and create a consistent SQLite
backup in the client's `support-backups` directory. It cannot browse arbitrary
files or restore a database remotely.

Camera stream credentials are masked in snapshots and are never placed in the
developer registry or audit logs. A configuration copied from an existing
client keeps the masked source until the client validates it and restores its
own matching source. Use an `env:VARIABLE_NAME` camera source for a portable
new-camera template.

Every session start/stop, link generation/copy, denied request, snapshot, and
remote action is recorded in the client audit log. The developer dashboard also
records its client and template actions in its local audit store.

## One-computer demonstration

Run a client instance and a developer instance at the same time. They must use
different existing data folders and different environment files, otherwise
they would be the same installation rather than two demo roles.

1. Create two existing empty folders, for example `demo-client-data` and
   `demo-developer-data`.
2. Create a client environment file containing its data folder,
   `SENYALERT_CLIENT_ID`, `SENYALERT_SUPPORT_SECRET`, and
   `SENYALERT_CLOUDFLARED`. Do not put the superadmin verifier in this file.
3. Create a developer environment file containing its different data folder
   and `SENYALERT_SUPERADMIN_PASSWORD_HASH`. Do not put the client support key
   in this file.
4. In terminal A, select the client file with `SENYALERT_ENV_FILE`, run
   `python senyalert.py run`, sign in as a normal local administrator, then
   start Remote Support for a short demonstration session. Copy the link and,
   for this local demo, use the audited **Copy Support Key** button.
5. In terminal B, select the developer file with `SENYALERT_ENV_FILE`, change
   into `java-dashboard`, and run `mvn exec:java`. Sign in as `superadmin`,
   choose **Connect client**, and paste the copied link and key.
6. The Quick Tunnel still uses Cloudflare even though both roles are on the
   same computer. Stop the client session when the demonstration is complete.
