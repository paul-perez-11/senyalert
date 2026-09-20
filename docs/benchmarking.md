# SenyAlert benchmark and evaluation guide

`tools/benchmark_senyalert.py` creates a new timestamped folder for every
run under `benchmark-results/`. Its CSV files hold raw observations; its JSON
file records p50/median, p95, p99, mean, minimum, and maximum values together
with the exact scope of the measurement. Never report a number without the
scope and test conditions saved alongside it.

The benchmark tooling does not change the dashboard's normal archive. The
SQLite benchmark creates a separate timestamped database in its result folder.

## 1. Record the test conditions first

For every trial, record the date, laptop model/CPU/RAM, Windows version,
Python and OpenCV versions, camera model/source, actual capture dimensions,
camera FPS, phone connection method, processing scale, confidence threshold,
hand cadence, people cadence, and whether the source is an offline clip or a
live feed. Keep the same conditions for the three repetitions of one scenario.

Do not run a normal Python engine at the same time as a pipeline trial against
the same webcam or phone stream. Two readers would compete for frames and make
the result unreliable.

## 2. Synthetic stale-frame stress test

This checks the expected behaviour of the engine's one-frame latest-frame
policy under a controlled artificial processing delay. It is a queue-policy
test, not a camera, computer-vision, or phone-latency result.

```powershell
python .\tools\benchmark_senyalert.py synthetic --duration-sec 60 --producer-fps 20 --processing-ms 50 --label baseline
```

Try a deliberately overloaded condition as a separate stress scenario:

```powershell
python .\tools\benchmark_senyalert.py synthetic --duration-sec 60 --producer-fps 30 --processing-ms 80 --processing-jitter-ms 15 --label overload
```

Report `stale_frames_dropped`, `stale_drop_rate`, and the p50/p95/p99
`producer_to_finish_ms`. A stale drop means a decoded frame was intentionally
replaced by a newer one before processing; it is **not** proof that the camera
or network lost a frame.

## 3. Real vision pipeline speed

Use a local test video for repeatable comparisons. This command opens the
source, runs OpenCV capture, optional resize, MediaPipe VIDEO hand detection,
the same `MultiHandTracker` state machine, and optional synchronous
face-validated people detection. It does not send WebSocket messages, create
incidents, write evidence, or open a UI.

```powershell
python .\tools\benchmark_senyalert.py pipeline `
  --source "C:\TestVideos\signal-for-help.mp4" `
  --max-frames 900 `
  --warmup-frames 60 `
  --processing-scale 0.75 `
  --hand-every-n 2 `
  --people-detection `
  --people-every-n 8 `
  --label video-075
```

For a 60-second phone-camera trial through the ADB forwarded Android IP Camera
stream, use the same parameters but specify a time limit. The phone app must
already be running and the ADB forward must already exist.

```powershell
python .\tools\benchmark_senyalert.py pipeline `
  --source "http://127.0.0.1:17170/video/mjpeg" `
  --duration-sec 60 `
  --warmup-frames 60 `
  --processing-scale 0.75 `
  --people-detection `
  --label phone-mjpeg
```

Useful output fields:

| Result | Meaning |
| --- | --- |
| `measured_throughput_fps` | Frames processed per benchmark wall-clock second after warm-up. |
| `capture_read_ms` | Time spent waiting for and decoding the next frame in this process. |
| `hand_detect_ms` | MediaPipe HandLandmarker VIDEO inference only on scheduled hand frames. |
| `tracker_ms` | Multi-hand association, gesture geometry, confidence, and state-machine time. |
| `people_detect_ms` | Face-validated people detector time on scheduled people frames when enabled. |
| `pipeline_processing_ms` | Local work after the frame is returned from the capture adapter. |
| `capture_to_decision_ms` | Local `capture.read()` start through the current decision; it includes local waiting for a live frame. |
| `network_capture_metrics` | For MJPEG/RTSP, latest-frame adapter decoded/delivered/stale-replaced counters. |
| `working_set_rss_peak_bytes` | Python process working set, not GPU memory. |

`capture_to_decision_ms` is not true camera-sensor-to-laptop latency. A phone
camera, MJPEG encoder, ADB tunnel, OpenCV backend, and display have no shared
timestamp in this project. To measure true end-to-end delay, film a stopwatch
that is visible to both the camera and laptop display, then manually analyse
the recording. State this limitation in the paper.

The `frames.csv` file also includes hand confidence, `palm_facing_quality`,
`landmark_reliability`, repetition state/cycle count, detected hands, and
people-count samples. These help explain a missed or slow sample without
claiming that a single frame establishes accuracy.

## 4. Local event-transport response time

Start the Java dashboard first, then use its special local-only benchmark
endpoint. It neither replaces the Python engine connection nor creates an
incident.

```powershell
Set-Location .\java-dashboard
mvn compile exec:java
```

In a second terminal at repository root:

```powershell
python .\tools\benchmark_senyalert.py transport --count 200 --warmup 20 --label local-ws
```

This measures only one local `BENCHMARK_PING` / `BENCHMARK_ACK` WebSocket
round trip. It does not include detection, incident persistence, Swing paint,
sound, a phone, ADB, Wi-Fi, or campus-network latency.

## 5. Java repository persistence test

The persistence runner uses the actual `SqliteIncidentRepository.create`
method and the dashboard's single database executor. It writes a separate
test database, with blank snapshot/video fields, so it cannot affect
`incidents.db` or delete media.

```powershell
Set-Location .\java-dashboard
mvn -q compile exec:java `
  "-Dexec.mainClass=com.senyalert.tools.PersistenceBenchmark" `
  "-Dexec.args=--operations 100 --warmup 10 --arrival-interval-ms 0 --output ..\benchmark-results"
```

For a paced arrival scenario, change `--arrival-interval-ms` to a defensible
value such as `250`. The JSON reports `enqueue-to-create-completion` timing:
queue waiting plus SQLite INSERT and repository read-back. It does not measure
real snapshot/video BLOB writes, WebSocket transport, dashboard painting, or
human response time.

## 6. Detection accuracy and response-delay evaluation

Run the pipeline benchmark against a labelled video. It produces
`detections.csv` containing only confirmed SOS state-machine events. Create a
ground-truth CSV in the same video timeline, for example:

```csv
timestamp_ms,event
12500,1
30400,1
```

Use the timestamp at the agreed observable event point, such as the first
frame where the SOS close is visibly complete. Define that convention in the
methodology and apply it consistently.

```powershell
python .\tools\benchmark_senyalert.py evaluate-events `
  --detections ".\benchmark-results\pipeline-...\detections.csv" `
  --ground-truth ".\labels\signal-for-help.csv" `
  --tolerance-ms 1000 `
  --label signal-for-help
```

The result contains true positives, false positives, false negatives,
precision, recall, F1, and signed/absolute event delay. It matches each
ground-truth event to at most one confirmed detection within the tolerance.
It cannot calculate specificity or a true-negative rate from event timestamps
alone. That requires a separately annotated set of negative frames/clips.

For a defensible Chapter 4 evaluation, include at least:

1. Positive clips containing the intended Signal for Help sequence at varied
   distance, lighting, orientation, and camera sources.
2. Negative clips containing ordinary waving, open hands, static fists,
   typing, reaching, and people entering/exiting the room.
3. A written annotation rule, video identifiers, and a fixed match tolerance.
4. Three repeat runs per performance scenario, plus median and p95 instead of
   only the most favourable mean.

Do not describe a test-video result as deployed-CCTV accuracy. The current
project has no production CCTV validation or identity recognition.

## 7. Build a chapter result index

After the trials, create a lightweight index of the raw summaries:

```powershell
python .\tools\benchmark_senyalert.py summarize .\benchmark-results --label chapter-4-5
```

The generated `chapter-summary.md` is an index, not a replacement for the raw
CSV/JSON files. Preserve the source files and quote their scope/exclusions in
the chapter tables and conclusions.
