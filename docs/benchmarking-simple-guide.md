# SenyAlert benchmarking: a simple guide for Chapter 4

Use the **Benchmarking** tab while signed in with benchmarking permission.
These tests create their own result folders. The SQLite test uses its own test
database. Your evidence archive is not used for the experiments.

## Before testing

1. Choose a place to save results, such as `benchmark-results` in the project.
2. Write down the laptop model, CPU, RAM, Windows version, camera/video name,
   resolution, lighting, distance, and which other programs are running.
3. Enter those details in **Test conditions**. Name the run clearly, such as
   `bright-near-trial-1`. The app saves these notes in `trial.json`.
4. Keep the same source and settings for three repetitions of each scenario.
   Change only the factor you intend to compare, such as lighting or distance.

The app normally finds Python automatically. If it reports missing vision
dependencies, choose the Python executable used to run SenyAlert. The developer
can set `SENYALERT_PYTHON` to that executable before opening the app. This field
takes a program path, without command arguments.

## A. Try your first test: frame queue stress

This is a safe way to learn the controls without using a camera.

1. Choose **Frame queue stress**.
2. Keep the defaults: 30 seconds, 20 simulated FPS, and 50 milliseconds of
   simulated processing per frame.
3. Fill in **Test conditions** and click **Run test**.
4. Wait for **COMPLETED**, then click **Open latest results**.
5. Repeat twice, using `trial-2` and `trial-3` in the labels.
6. As a separate overload scenario, try 30 FPS and 80 milliseconds. Repeat
   that scenario three times too.

This test simulates a frame queue. It does **not** measure camera quality,
gesture accuracy, or real camera delay. A dropped stale frame means the queue
replaced an older frame; it does not prove the network lost a frame.

## B. Measure real video or camera processing

Use a recorded test video first because it is easier to repeat fairly.

1. Choose **Video / camera performance**.
2. Click **Browse** beside the source and choose a test video.
3. Keep the model options the same across repetitions. The options shown apply
   to this benchmark only. The other detector settings use the benchmark's
   engine defaults, which are separate from saved dashboard settings.
4. Run the test. It stops at the time limit, frame limit, or video end,
   whichever comes first.
5. Open its results and keep `frames.csv`, `detections.csv`, and `summary.json`.
6. Repeat with exactly the same video and settings, then test a new condition.

For a live webcam, enter its camera number, such as `0`. For an existing camera
stream, enter its configured source. Stop the separate vision engine that
already uses that camera before starting; two programs competing for the same
camera would make the results unreliable. The Benchmarking tab does not stop
normal monitoring automatically or change camera configuration.

The first **warm-up frames** are excluded from timings and detected events.
Use a clip long enough to contain measured frames. For an accuracy pass, put
the labelled events after warm-up or choose zero warm-up frames.

This measures local capture and vision work for **one source**. It does not
measure simultaneous-camera capacity, evidence-video saving, display delay,
or the full delay from the camera sensor to an audible alert.

## C. Measure detection accuracy

A person must first identify the real events in the video. The confidence
displayed by the detector is not an accuracy result.

1. Agree on an annotation rule. For example: mark the first video frame where
   the Signal for Help closed-hand step is visibly complete.
2. Have a reviewer watch the video and write each real event's video timestamp
   in milliseconds. Use the same rule for every clip.
3. Save a CSV with the columns below. The numbers are format examples only;
   replace them with observations from the real video.

   ```csv
   timestamp_ms,event
   12500,1
   30400,1
   ```

4. Run **Video / camera performance** on that exact video.
5. Choose **Detection accuracy**. Select its `detections.csv` and your human
   labels CSV.
6. Keep the agreed **match tolerance**, such as 1,000 milliseconds, fixed
   throughout the comparison. Run the evaluation.
7. Review `matches.csv` alongside the video to explain misses and false alerts.

Test both positive clips with the intended hand sequence and negative clips
with normal actions such as waving, typing, reaching, or a static fist. A
negative clip with no real events can use a labels file containing only the
header `timestamp_ms,event`.

| Result | In plain language |
| --- | --- |
| True positives | Labelled events that matched a detection. |
| False positives | Detections without a matching labelled event. |
| False negatives | Labelled events the detector missed. |
| Precision | How often a detected event matched a real labelled event. |
| Recall | How many of the real labelled events were detected. |
| F1 | A combined measure of precision and recall. |
| Signed delay | Detection time minus the labelled event time; negative means earlier. |

An undefined result appears as `null`; do not replace it with zero. Event
timestamps alone do not measure specificity or a true-negative rate. That
requires a separately annotated set of negative frames or intervals.

## D. Measure messaging and SQLite

- **Local message response:** keep the dashboard open, use the defaults, and
  run three trials. This measures a local WebSocket ping/ack round trip.
- **SQLite write performance:** use 100 measured writes and 10 warm-up writes.
  An arrival interval of zero is a burst; 250 milliseconds is four arrivals
  per second. Each run creates a separate test database with synthetic records
  and no media.

The SQLite timing includes queue waiting, record insertion, and read-back. The
message timing excludes vision, storage, display, and sound. Do not add
separately measured medians together and call the result total alert delay.

The draft paper discusses event-driven versus request-response operation.
The local message test measures the existing event-driven path only. A fair
comparison needs a separate request-response implementation with the same
payload, workload, hardware, and measurement boundary; these tests do not
establish that comparison on their own.

## E. Prepare the Chapter 4 table

1. Choose **Chapter 4 result index** and select your results folder.
2. Run it to create `chapter-summary.md` and `chapter-summary.json`. The index
   includes completed, successful trials and links to the original reports.
3. For each scenario, record all three repetitions, the exact measurement,
   median, p95, errors, and test conditions. State the number of samples.
4. Preserve the raw CSV/JSON files with the paper so another person can check
   how each number was obtained.

**Median** is the middle measurement. **p95** is a value at or below which 95%
of measurements fall. Lower milliseconds usually mean a faster operation;
higher processed FPS means more frames processed per second. Always compare
the same metric under the same conditions.

| Scenario | Trial | Measurement | Samples | Median | p95 | Conditions / notes |
| --- | --- | --- | --- | --- | --- | --- |
| Fill from an actual trial | 1 | Name from its summary | Actual count | Actual value | Actual value | Source, settings, errors |

Do not report an example, cancelled run, empty measurement, or failed run as a
result. If you cancel a test, the app retains its files and marks `trial.json`
as cancelled. Failed runs retain their log and status so you can investigate.

## If something goes wrong

- **No source opens:** check the selected file/camera and stop its other reader.
- **Missing Python module:** choose the project's Python environment; ask the
  developer to install the existing `requirements.txt` into that environment.
- **No frames after warm-up:** use a longer trial/video or fewer warm-up frames.
- **Local message test cannot connect:** keep the dashboard's local engine
  server available; its benchmark endpoint uses the normal local server port.
- **Test takes too long:** click **Cancel test**. Keep the failed/cancelled
  trial separate from reported results, adjust the setup, then run a new trial.

Technical measurement details and command-line equivalents are in
[benchmarking.md](benchmarking.md).
