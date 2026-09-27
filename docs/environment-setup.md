# Environment configuration and managed engine startup

SenyAlert supports a per-installation `senyalert.env` file. Copy
[`senyalert.env.example`](../senyalert.env.example) to `senyalert.env` beside
the launcher, then set only the values needed by that installation. The real
file is ignored by the repository and must not be shared with evidence exports,
configuration templates, audit logs, or support links.

The launcher, Java dashboard, and standalone Python engine use the same simple
`NAME=value` format. Blank lines and full-line `#` comments are allowed. Values
may be wrapped in matching single or double quotes. Shell expansion and inline
comments are deliberately not interpreted. A process environment value takes
precedence over the file, including an intentionally blank value.

To use another location, set `SENYALERT_ENV_FILE` to an existing file path
before launching. Java also accepts `-Dsenyalert.env.file=<path>` for a direct
development launch. The normal search checks the current folder and its parent
so both `python senyalert.py run` and a Maven launch from `java-dashboard` find
the project-root file.

## Supported variables

| Variable | Purpose |
| --- | --- |
| `SENYALERT_DATA_DIR` | Existing local folder containing the SQLite databases and saved settings. SenyAlert never creates or moves this directory just because it is configured. |
| `SENYALERT_PROJECT_ROOT` | Project folder containing `src/python-prototype.py` and the benchmark tools when automatic discovery is unsuitable. |
| `SENYALERT_PYTHON` | Python executable used for the dashboard-owned ingestion engine and benchmark tools. Use an executable path only, without command arguments. |
| `SENYALERT_ENGINE_AUTOSTART` | `true` by default. Set `false` only when an operator deliberately starts a separate local engine. |
| `SENYALERT_WS_URL` | Python engine WebSocket endpoint. Leave the default `ws://localhost:8080` for the dashboard's local server. |
| `SENYALERT_CLIENT_ID` | Required stable Remote Support installation ID for a new client. Use 8-128 letters, digits, dashes, or underscores; it is not a password. |
| `SENYALERT_CLIENT_NAME` | Optional display label sent to the Superadmin Dashboard. |
| `SENYALERT_SUPPORT_SECRET` | Per-client Remote Support key, at least 32 characters. Keep it only in the client environment and the developer's password manager. |
| `SENYALERT_CLOUDFLARED` | `cloudflared` executable path, or `cloudflared` when it is on `PATH`. |
| `SENYALERT_SUPERADMIN_PASSWORD_HASH` | Developer-computer-only PBKDF2 verifier for the reserved `superadmin` login. |
| `CAMERA_*` or another chosen variable name | Camera source value referenced by `env:VARIABLE_NAME` in a saved camera configuration. |

`SENYALERT_ENV_FILE` selects the file itself. `SENYALERT_ENV_LOADED` is an
internal value used only between the dashboard and its owned Python child; do
not add it to the file.

## Startup and shutdown sequence

`python senyalert.py run` now starts the Java sign-in screen only. After a
normal local user or administrator signs in and the main dashboard is visible,
SenyAlert starts its own Python ingestion-engine child and the engine opens its
OpenCV window. The separate Superadmin Dashboard does not start an ingestion
engine.

When that dashboard is signed out or closed, SenyAlert stops the exact Python
process it launched and any descendants it owns, then closes the local
WebSocket server. It never searches for or terminates unrelated Python
processes. With `SENYALERT_ENGINE_AUTOSTART=false`, an externally launched
engine remains independent and is never stopped by the dashboard.

## Client-ID migration

Remote Support uses `SENYALERT_CLIENT_ID` from environment configuration. A
pre-existing installation may still contain a legacy `client-id.txt`. On the
first user-approved **Start support** action, SenyAlert reads a valid legacy ID
once, appends it to `senyalert.env`, records `CLIENT_ID_MIGRATED` in the audit
log, and leaves `client-id.txt` unchanged. It never generates a replacement ID
or writes to the legacy file.

If neither an environment ID nor a valid legacy ID is available, Remote Support
does not create a tunnel. Add a unique ID to `senyalert.env`, sign in again,
then start support.

## Portable camera settings

Use a camera source such as `env:CAMERA_ENTRY_SOURCE` in a portable camera
template, then set `CAMERA_ENTRY_SOURCE` in this file. SenyAlert resolves the
value only when sending the already validated camera configuration to the local
engine. This does not change capture, detection, or evidence logic.
