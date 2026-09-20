# SenyAlert

SenyAlert is a local, event-driven distress-triage demo. A Python vision engine
recognizes the Signal for Help sequence (open palm -> tucked thumb -> closed
fist), then publishes a structured event to a Java Swing dispatch dashboard.
The dashboard creates a responsive incident record, chooses an audible or
quiet alert from configured occupancy policy, and keeps snapshot/video evidence
with the incident.

## What this version includes

- A Swing-only blue dispatch dashboard with a clear active-alert banner,
  Font Awesome icons, incident queue, evidence viewer, and settings cards.
- MVC separation in Java: Swing views are presentation-only; controller,
  service, repository, and WebSocket infrastructure run outside the EDT.
- Persistent, reconnecting WebSocket control for live engine settings and
  pause/resume.
- Multi-hand tracking with one temporal state machine per hand, so a static
  fist alone does not trigger an SOS event.
- A conservative groupmate-compatible, scale-normalized 2-D
  **open-palm → thumb-tucked closed fist** sequence drives the alert. Static
  fists and open-to-fist movements without a tucked thumb cannot alert.
  World-landmark 3-D angles and optional
  visibility/presence values remain diagnostic evidence rather than loosening
  the temporal gate.
- **Gesture sensitivity** is independently configurable from 50–150%.  The
  default 150% reduces the phase margin to 25% of its calibrated value for a
  clear compact phone-camera close. At maximum it also gives an explicitly
  reported, bounded score assist only after a near-complete closed phase; a
  clear front-facing strict or one-finger-near strict SOS can show 100%.
  Palm/reliability guards and the configured confidence threshold remain in
  force.
- Repeated same-hand near-SOS cycles can escalate from a raw score of at least
  50% to an explicit configured target inside a short time window. The engine
  publishes raw and effective confidence, and still applies the normal final
  confidence threshold before creating the incident.
- VIDEO-mode HandLandmarker detection falls back to IMAGE mode after short
  tracker dropouts and reports `NO HANDS FROM MEDIAPIPE` instead of silently
  remaining in IDLE.
- A throttled, face-validated OpenCV occupancy counter rejects HOG-only boxes
  (such as the bottle in the demo screenshot). It uses camera-local,
  transient tracks only; it never identifies people.
- Up to four concurrent laptop, raw USB/UVC-phone, ADB-screen-fallback, MJPEG, or RTSP cameras.
  Each has independent hand tracks, people count, alert cooldown, and evidence
  ring buffer. The Python ingestion window opens maximized and renders a
  high-resolution HUD over a separately downscaled video layer.
- A JPEG snapshot immediately on alert plus a time-based pre/post-event MP4
  clip. SQLite stores the evidence bytes, paths, evidence status, and incident
  metadata asynchronously. The dashboard previews the snapshot directly and
  can play/export the stored clip.

## Requirements

- JDK 17 or newer
- Maven
- A Python environment compatible with the packages in `requirements.txt`
- A webcam, a Windows-exposed USB/UVC camera, an accessible MJPEG/RTSP stream,
  or Android Platform Tools for an ADB screen-capture fallback demo

Install the Python dependencies from the repository root:

```powershell
python -m pip install -r requirements.txt
```

## Benchmarking and Chapter 4/5 evidence

Repeatable local profiling, stress, transport, SQLite-persistence, and
ground-truth event-evaluation tools are documented in
[docs/benchmarking.md](docs/benchmarking.md). Each run writes raw CSV samples
and a scope-labelled JSON summary; synthetic queue results and local component
timings must not be presented as real camera-to-alert latency.

## Run the local demo

```powershell
python senyalert.py run
```

The launcher starts the Python engine first and then the Java dashboard. The
dashboard listens on `ws://localhost:8080`; the engine reconnects until it is
available. Do not use Maven's `clean` phase for normal demo starts.

If you prefer to start the dashboard separately:

```powershell
Set-Location java-dashboard
mvn compile exec:java
```

## Use a phone as the demo camera

### Raw USB/UVC phone camera — preferred no-app route

Use this when the phone's native USB Webcam/UVC mode is exposed by Windows as a
camera device. This is a real video-capture input for the vision engine, not a
screen capture, and it needs no third-party Android app. Device support varies:
SenyAlert cannot turn a phone into a USB webcam by itself or select the front
versus rear lens from Windows. Choose the lens/mode on the phone, then select
the matching profile in SenyAlert for portrait-friendly defaults.

1. Connect the phone through USB and enable its native **Webcam** / **USB
   Webcam** mode if the device provides it. Confirm that Windows' Camera app
   can see it.
2. In **Cameras**, use **Add camera**, choose **Phone camera (front) (demo)**
   or **Phone camera (rear) (demo)**, and set a source such as `uvc://0`.
   The number is the Windows/OpenCV camera index; try the next index if `0` is
   the laptop webcam. Save the camera changes.
3. The engine reports the actual decoded width, height, and aspect ratio in
   its camera status after the first frame. It preserves that real aspect even
   if the profile's requested portrait resolution cannot be negotiated.

### ADB phone-screen fallback — no third-party phone app

ADB cannot expose generic raw phone-camera pixels to OpenCV. This fallback
captures the foreground phone screen, so open the stock **Camera** app and
leave it in the foreground. It is useful when no UVC mode is available, but it
has lower frame rate and higher latency than the raw USB/UVC route above.

1. Install Android Platform Tools on the laptop. On Android 11+, enable
   **Developer options → Wireless debugging**; pair both devices through a
   trusted local network such as a private hotspot. USB debugging also works.
2. In Wireless debugging, choose **Pair device with pairing code**, then run:

   ```powershell
   adb pair <phone-ip>:<pairing-port>
   adb connect <phone-ip>:<debug-port>
   adb devices
   ```

   Use the address and ports displayed by Android. `adb devices` must show
   the phone as `device`, not `unauthorized` or `offline`. If `adb` is not on
   PATH on Windows, use
   `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"` in place of
   `adb` in each command.
3. Open the phone's stock Camera app. In the dashboard, open **Cameras**, use
   **Add camera**, and set a source such as `adb://<serial>` or
   `adb://<phone-ip>:<debug-port>`. Give it a camera ID such as
   `CAM-02-PHONE`, then select **Save camera changes**.
4. Keep `0` in the primary camera tab for the laptop webcam if you want both
   feeds. Each camera gets its own live panel, hand tracking, people count,
   alert cooldown, and evidence capture.

For Android IP Camera, the **Cameras** tab now has an optional **Android IP
Camera controls** section on every camera tab. Enter the wireless ADB target
(for example `192.168.68.113:42573`) or an already-authorized USB serial, the
laptop listening port, and the phone camera server port. **Connect & forward**
runs the equivalent safe argument-based ADB commands in the background; **Use
forwarded stream** fills in `http://127.0.0.1:<laptop-port>/video/mjpeg` for
you. You must still pair/authorize Android debugging on the phone first—the
dashboard never changes phone debugging or permission settings.

Those bridge fields are intentionally session-only: they remain filled in if
you save or reload another camera setting during the same dashboard session,
but they are not written to the normal camera configuration. This avoids
leaving a transient wireless-debugging target in a settings file. Added camera
tabs default to different localhost ports (`17170`, `17171`, and so on); the
dashboard rejects duplicate ADB listening ports when you save.

### Raw Android IP Camera through ADB

For an Android 12 phone without native USB Webcam/UVC support, use the
open-source **Android IP Camera** app to serve the selected phone lens as MJPEG
instead of using `adb://` screen capture. Start its local server, then either
use the dashboard controls above or forward its default phone port manually:

```powershell
adb -s <device-serial> forward tcp:17170 tcp:4444
```

Use `http://127.0.0.1:17170/video/mjpeg` as the SenyAlert camera source. Start
at 1280 x 720, 15–20 FPS, medium JPEG quality, with audio and the app's own
recording disabled. The Python engine continuously drains network streams and
delivers only the latest decoded frame to detection, so a slow detector drops
stale frames instead of growing visible latency. Reduce the camera's
`processing_scale` to 0.5–0.75 if the local computer still cannot keep up.

If the selected phone lens supports remote zoom, enter a value from `1.0×` to
`20.0×` in the same camera tab and select **Apply zoom**. SenyAlert sends the
control request through the localhost ADB forward, so it does not need the
phone's LAN address in the camera source. A rejected request means the phone
app or selected lens does not expose that zoom range.

The zoom control accepts a trusted HTTP/HTTPS endpoint and will not bypass an
untrusted/self-signed TLS certificate. If Android IP Camera Basic Auth is
enabled, use a valid authenticated source URL; the zoom command reuses its
Basic Auth credentials for the single control request and never logs them.

For a secure demo, prefer a USB data cable plus USB debugging. If the phone
server uses HTTP without app authentication, keep it off public/campus Wi-Fi;
ADB forwarding does not stop other devices on the phone's active network from
reaching the app's server directly. Stop the server and remove the ADB forward
after the demo.

### SenyAlert Camera Bridge — required where USB Webcam is unavailable

`SenyAlert Camera Bridge` is the proposed **first-party Android companion**
for phones that cannot expose a native USB/UVC webcam. It is not a third-party
app and is not yet an Android module in this repository.

It must use CameraX/Camera2 to read the selected front or rear camera directly,
then expose only these phone-local endpoints while the user has explicitly
started a visible foreground capture session:

- `GET /status` — capture state, selected lens, actual width, height, FPS, and
  aspect ratio; it returns no image data.
- `GET /mjpeg?token=<per-launch-token>` — the real encoded camera stream for
  the Python engine. It must never stream a screen capture.

The service listens on `127.0.0.1:17170` on the phone, requires a fresh
per-launch token, shows a persistent foreground notification, and stops when
the user selects **Stop**. This keeps it local: no cloud relay, open LAN port,
or background recording.

With USB debugging already authorized, forward that local port to the laptop:

```powershell
adb -s <serial> forward tcp:17170 tcp:17170
```

Then add an HTTP camera in **Cameras** with a source such as
`http://127.0.0.1:17170/mjpeg?token=<token>`. For more than one phone, use a
different laptop-side port for each device (for example `17170` and `17171`)
while each phone still listens on its own local `17170`.

This is an optional first-party alternative to Android IP Camera for the
current Android 12 Galaxy S10 demo device. It needs an explicitly approved
Android companion-app implementation and installation before it can be used.

### HTTP/RTSP alternative

An existing USB webcam, MJPEG, or RTSP camera may still be configured in an
**Add camera** tab using its full `http(s)` or `rtsp(s)` URL. A phone IP
address by itself is not a video source.

### Bluetooth boundary

Pairing a phone over Bluetooth alone cannot expose its camera as an OpenCV
video source. A Bluetooth implementation would require a separate Android
companion application that advertises a custom RFCOMM service and a matching
Windows-side transport; it is not a generic camera connection and is not
implemented in this Swing-only project. Use USB tethering/USB webcam for the
reliable live-demo path. Bluetooth may be added later as a **control** channel
only after that companion-app scope is approved.

## Demo flow

1. Confirm the header shows a connected engine and an online camera.
2. Perform the Signal for Help sequence in view: open palm, then tuck thumb
   and close the fingers around it. The preview shows the per-hand phase and
   explicitly reports when MediaPipe has no hand landmarks.
3. A new pending incident appears immediately with its snapshot and signaler
   track metadata. The MP4 is marked ready only after the post-event capture
   finishes.
4. Select an incident to inspect the embedded snapshot and associated data.
   Use **Play video natively** or **Export video** after the media status is
   ready.
5. Acknowledge or resolve the incident from the evidence panel.

## Settings and alert policy

The **Engine settings** screen controls confidence, pre/post capture duration,
maximum hands, people-detection cadence, and the quiet threshold. The dedicated
**Cameras** screen holds a primary tab plus up to three added camera tabs; IDs
must be unique. Each camera selects a CCTV, laptop-webcam, phone-front,
phone-rear, or custom profile. Profiles set sensible landscape/portrait
resolution and aspect defaults; Custom accepts a width × height aspect, while
the engine always preserves the actual opened frame dimensions. Each camera can
be enabled/disabled without losing its setup, and independently controls
requested capture resolution, vision-processing scale, and downscaled
video/dashboard-preview resolution. The processing scale does not reduce stored
evidence or HUD text quality. With people detection enabled, the dashboard
selects a quiet alert at or above the configured occupancy threshold. It also
selects quiet while the asynchronous occupancy result is stale, rather than
assuming the room is empty. Disabling audible alarms makes all alerts quiet.

Gesture-related JSON settings are bounded and echoed in `CONFIG_ACK` and the
live HUD: `gesture_sensitivity` (0.50–1.50),
`repeated_handsign_min_confidence` (0.50–0.95),
`repeated_handsign_confidence_target` (0.50–1.00), and the existing
`repeated_handsign_window_sec`. `thumb_tuck_confidence_bonus` and
`index_fold_confidence_bonus` are each 0.00–0.30 (10% is `0.10`). A bonus is
applied only when the matching `fingers.thumb` or `fingers.index` requirement
is enabled and its calibrated feature is detected; the dashboard disables the
associated control while that requirement is unchecked.

## Evidence and scope notes

`java-dashboard/incidents.db` receives additive schema updates for snapshots,
clips, paths, occupancy/hand metadata, and media state. New live media is kept
under `evidence/recorded/<camera-id>/videos` and `snapshots`; uploaded-video
media is kept under `evidence/uploaded/<job-id>/videos` and `snapshots`.
The legacy media present during this update was moved without deletion into
`evidence/recorded/legacy/videos` and `evidence/recorded/legacy/snapshots`.
The engine does not automatically relocate later files or apply retention or
deletion behavior.

The evidence panel lets operators update only the incident workflow status and
an operator note. Detection facts, timestamps, confidence, people/hand data,
alert mode, and evidence locations remain read-only. **Delete record** requires
confirmation and removes only the SQLite row/BLOBs; it deliberately leaves the
source snapshot and video files untouched.

The face-validated occupancy detector and temporal gesture classifier are
practical demo heuristics, not biometric identification or production-grade
crowd analytics. The occupancy counter is intentionally conservative and can
under-count a person whose frontal face is not visible. Calibrate the setup
with the actual room, laptop, and phone stream before presenting. No deployed
CCTV integration is included in this phase.
