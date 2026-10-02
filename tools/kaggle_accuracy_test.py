from __future__ import annotations

import argparse
import copy
import csv
import importlib.util
import json
import re
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


# ---------------------------------------------------------
# PATHS / DATASET FORMAT
# ---------------------------------------------------------

ROOT = Path(__file__).resolve().parents[1]

NO_SIGNAL = "1. No Signal"
SOS = "2. Signal for Help"

# Example:
# VID_000000.mp4_frame_0041.jpg
FRAME_RE = re.compile(
    r"^(?P<video>.+\.(?:mp4|avi|mov|mkv|webm|m4v))_frame_(?P<frame>\d+)$",
    re.IGNORECASE,
)


# ---------------------------------------------------------
# LOAD THE REAL SENYALERT ENGINE
# ---------------------------------------------------------

def load_engine():
    """
    Load src/python-prototype.py exactly like the existing
    tools/benchmark_senyalert.py benchmark does.
    """

    path = ROOT / "src" / "python-prototype.py"

    if not path.is_file():
        raise RuntimeError(
            f"Could not find SenyAlert engine:\n{path}"
        )

    spec = importlib.util.spec_from_file_location(
        "senyalert_kaggle_engine",
        path,
    )

    if spec is None or spec.loader is None:
        raise RuntimeError(
            f"Could not load {path}"
        )

    module = importlib.util.module_from_spec(spec)

    sys.modules[spec.name] = module
    spec.loader.exec_module(module)

    return module


# ---------------------------------------------------------
# DATASET GROUPING
# ---------------------------------------------------------

def group_frames(folder: Path):
    """
    Turn:

        VID_000000.mp4_frame_0041.jpg
        VID_000000.mp4_frame_0042.jpg
        VID_000001.mp4_frame_0001.jpg

    into:

        VID_000000.mp4 -> ordered list of frames
        VID_000001.mp4 -> ordered list of frames
    """

    videos = defaultdict(list)

    for path in folder.iterdir():

        if not path.is_file():
            continue

        if path.suffix.lower() not in {
            ".jpg",
            ".jpeg",
            ".png",
        }:
            continue

        match = FRAME_RE.match(path.stem)

        if match is None:
            print(
                f"[SKIP] Unexpected filename: {path.name}"
            )
            continue

        video_name = match.group("video")
        frame_number = int(
            match.group("frame")
        )

        videos[video_name].append(
            (frame_number, path)
        )

    # VERY IMPORTANT:
    # Preserve chronological frame order.
    for frames in videos.values():
        frames.sort(
            key=lambda item: item[0]
        )

    return dict(videos)


def clamp01(value):
    return max(
        0.0,
        min(1.0, float(value)),
    )


# ---------------------------------------------------------
# EXACT SENYALERT SEQUENCE RUNNER
# ---------------------------------------------------------

class SequenceTester:
    """
    Runs the actual SenyAlert vision components without
    starting Swing, WebSockets, SQLite, evidence recording,
    or occupancy detection.

    Those systems happen after gesture qualification and do
    not determine whether the gesture itself becomes an SOS.
    """

    def __init__(
        self,
        engine,
        config,
        fps,
    ):

        self.engine = engine
        self.config = copy.deepcopy(config)

        self.fps = float(fps)

        # Same VIDEO detector as CameraWorker.
        self.detector = (
            engine.create_hand_detector(
                self.config,
                engine.vision.RunningMode.VIDEO,
            )
        )

        # Same fallback behavior used by CameraWorker.
        self.image_detector = None

        # This is your real temporal gesture tracker.
        self.tracker = (
            engine.MultiHandTracker()
        )

        self.empty_video_results = 0

        self.last_timestamp_ms = -1


    # -----------------------------------------------------
    # CLEANUP
    # -----------------------------------------------------

    def close(self):

        for detector in (
            self.detector,
            self.image_detector,
        ):

            if detector is None:
                continue

            try:
                detector.close()
            except Exception:
                pass


    # -----------------------------------------------------
    # MEDIAPIPE DETECTION
    # -----------------------------------------------------

    def detect_hands(
        self,
        image,
        timestamp_ms,
    ):
        """
        Mirrors CameraWorker._detect_hands():

        VIDEO
          ↓
        no result?
          ↓
        IMAGE fallback
        """

        try:

            result = (
                self.detector.detect_for_video(
                    image,
                    timestamp_ms,
                )
            )

            screen = (
                result.hand_landmarks or []
            )

            world = (
                result.hand_world_landmarks or []
            )

        except Exception:

            screen = []
            world = []


        # VIDEO mode succeeded.
        if screen:

            self.empty_video_results = 0

            return (
                screen,
                world,
                "VIDEO",
            )


        self.empty_video_results += 1

        fallback_after = int(
            self.config[
                "image_fallback_after_empty_video_frames"
            ]
        )


        # Same grace period as live SenyAlert.
        if (
            self.empty_video_results
            < fallback_after
        ):

            return (
                screen,
                world,
                "VIDEO_EMPTY",
            )


        # Create fallback detector only when needed.
        if self.image_detector is None:

            self.image_detector = (
                self.engine.create_hand_detector(
                    self.config,
                    self.engine.vision.RunningMode.IMAGE,
                )
            )


        try:

            result = (
                self.image_detector.detect(
                    image
                )
            )

            screen = (
                result.hand_landmarks or []
            )

            world = (
                result.hand_world_landmarks or []
            )

            if screen:

                return (
                    screen,
                    world,
                    "IMAGE_FALLBACK",
                )

        except Exception:
            pass


        return (
            [],
            [],
            "NO_HANDS",
        )


    # -----------------------------------------------------
    # FINAL SENYALERT EVENT GATE
    # -----------------------------------------------------

    def qualifies_as_event(
        self,
        result,
        now,
    ):
        """
        Mirrors the qualification logic in CameraWorker.run():

        temporal sequence
             ↓
        strict/repeat confirmation
             ↓
        cooldown
             ↓
        confidence threshold
             ↓
        SOS event
        """

        reading = result.get(
            "reading"
        )

        strict_confirmed = bool(
            result.get("confirmed")
        )

        repeat_ready = bool(
            result.get(
                "repeat_escalation_ready"
            )
        )


        if reading is None:
            return None

        if not (
            strict_confirmed
            or repeat_ready
        ):
            return None


        track = result["track"]

        repetition = (
            result.get("repetition")
            or {}
        )


        try:

            cycle_at = float(
                repetition.get(
                    "latest_cycle_at"
                )
                or 0.0
            )

        except (
            TypeError,
            ValueError,
        ):

            cycle_at = 0.0


        repeated_cycle_ready = (

            bool(
                repetition.get(
                    "is_repeated"
                )
            )

            and

            cycle_at
            >
            track.last_repeated_cycle_alerted_at

        )


        raw_conf = clamp01(

            result.get(

                "raw_confidence",

                reading.get(
                    "confidence",
                    0.0,
                ),

            )

        )


        if repeat_ready:

            effective_conf = clamp01(

                result.get(
                    "effective_confidence",
                    raw_conf,
                )

            )

        else:

            effective_conf = raw_conf


        # Avoid duplicate repeated-hand alerts.
        if (

            strict_confirmed

            and bool(
                repetition.get(
                    "is_repeated"
                )
            )

            and cycle_at > 0.0

            and cycle_at
            <= track.last_repeated_cycle_alerted_at

        ):

            return None


        # Same cooldown logic as the live engine.
        if (

            not repeated_cycle_ready

            and

            now - track.last_trigger_at

            < self.config[
                "alert_cooldown_sec"
            ]

        ):

            return None


        # Final confidence gate.
        if (

            effective_conf

            < self.config[
                "confidence_threshold"
            ]

        ):

            return None


        # Same state mutation as CameraWorker.
        track.last_trigger_at = now


        if repeated_cycle_ready:

            track.last_repeated_cycle_alerted_at = (
                cycle_at
            )


        return {

            "track_id":
                result.get("track_id"),

            "state":
                result.get("state"),

            "source":
                result.get(
                    "detection_source"
                ),

            "raw_confidence":
                raw_conf,

            "effective_confidence":
                effective_conf,

            "repeated":
                bool(
                    repetition.get(
                        "is_repeated"
                    )
                ),

        }


    # -----------------------------------------------------
    # TEST ONE ORIGINAL VIDEO SEQUENCE
    # -----------------------------------------------------

    def run(
        self,
        frames,
    ):

        cv2 = self.engine.cv2
        mp = self.engine.mp


        every_n = int(

            self.config[
                "hand_detection_every_n_frames"
            ]

        )


        processing_scale = float(

            self.config.get(
                "processing_scale",
                1.0,
            )

        )


        # The actual uploaded-video CameraWorker uses
        # a source-time based monotonic clock.
        base_time = time.monotonic()


        hand_inference_frames = 0

        frames_with_hands = 0


        for (
            sequence_index,
            (
                dataset_frame,
                frame_path,
            ),
        ) in enumerate(
            frames,
            start=1,
        ):

            frame = cv2.imread(
                str(frame_path)
            )


            if frame is None:

                raise RuntimeError(
                    f"Could not read "
                    f"{frame_path}"
                )


            # Same offline timing idea as CameraWorker.
            now = (

                base_time

                +

                (
                    sequence_index - 1
                )

                / self.fps

            )


            # Same processing-scale behavior.
            if processing_scale < 0.999:

                width = max(
                    1,
                    round(
                        frame.shape[1]
                        * processing_scale
                    ),
                )

                height = max(
                    1,
                    round(
                        frame.shape[0]
                        * processing_scale
                    ),
                )

                processing_frame = (
                    cv2.resize(
                        frame,
                        (
                            width,
                            height,
                        ),
                        interpolation=(
                            cv2.INTER_AREA
                        ),
                    )
                )

            else:

                processing_frame = frame


            # Match the actual SenyAlert frame schedule.
            if (
                sequence_index
                % every_n
                != 0
            ):

                continue


            hand_inference_frames += 1


            timestamp_ms = int(
                now * 1000
            )


            if (
                timestamp_ms
                <= self.last_timestamp_ms
            ):

                timestamp_ms = (
                    self.last_timestamp_ms
                    + 1
                )


            self.last_timestamp_ms = (
                timestamp_ms
            )


            try:

                rgb = cv2.cvtColor(
                    processing_frame,
                    cv2.COLOR_BGR2RGB,
                )


                image = mp.Image(
                    image_format=(
                        mp.ImageFormat.SRGB
                    ),
                    data=rgb,
                )


                (
                    screen,
                    world,
                    source,
                ) = self.detect_hands(
                    image,
                    timestamp_ms,
                )


                if screen:
                    frames_with_hands += 1


                # THIS IS YOUR REAL
                # OPEN -> TUCK -> FIST TRACKER.
                results = (
                    self.tracker.update(
                        screen,
                        world,
                        now,
                        self.config,
                        source,
                    )
                )


                for result in results:

                    event = (
                        self.qualifies_as_event(
                            result,
                            now,
                        )
                    )


                    if event is None:
                        continue


                    # First actual SOS event means the
                    # entire video is classified as SOS.
                    return {

                        "detected": True,

                        "total_frames":
                            len(frames),

                        "hand_inference_frames":
                            hand_inference_frames,

                        "frames_with_hands":
                            frames_with_hands,

                        "detection_dataset_frame":
                            dataset_frame,

                        "detection_sequence_index":
                            sequence_index,

                        **event,

                    }


            except Exception as exc:

                # Same principle as CameraWorker:
                # one bad inference frame does not
                # kill the entire stream.
                print(

                    f"[WARN] "
                    f"{frame_path.name}: "
                    f"{type(exc).__name__}: "
                    f"{exc}"

                )


        # Video ended without an SOS event.
        return {

            "detected": False,

            "total_frames":
                len(frames),

            "hand_inference_frames":
                hand_inference_frames,

            "frames_with_hands":
                frames_with_hands,

            "detection_dataset_frame":
                None,

            "detection_sequence_index":
                None,

            "track_id":
                None,

            "state":
                None,

            "source":
                None,

            "raw_confidence":
                None,

            "effective_confidence":
                None,

            "repeated":
                False,

        }


# ---------------------------------------------------------
# METRICS
# ---------------------------------------------------------

def classify(
    expected,
    predicted,
):

    if expected and predicted:
        return "TP"

    if (
        not expected
        and not predicted
    ):
        return "TN"

    if (
        not expected
        and predicted
    ):
        return "FP"

    return "FN"


def calculate_metrics(
    tp,
    tn,
    fp,
    fn,
):

    total = (
        tp + tn + fp + fn
    )


    accuracy = (

        (tp + tn) / total

        if total

        else 0.0

    )


    precision = (

        tp / (tp + fp)

        if tp + fp

        else 0.0

    )


    recall = (

        tp / (tp + fn)

        if tp + fn

        else 0.0

    )


    f1 = (

        2
        * precision
        * recall
        / (
            precision
            + recall
        )

        if (
            precision
            + recall
        )

        else 0.0

    )


    return {

        "total_sequences":
            total,

        "TP":
            tp,

        "TN":
            tn,

        "FP":
            fp,

        "FN":
            fn,

        "accuracy":
            accuracy,

        "precision":
            precision,

        "recall":
            recall,

        "f1":
            f1,

    }


# ---------------------------------------------------------
# MAIN
# ---------------------------------------------------------

def main():

    parser = (
        argparse.ArgumentParser()
    )


    parser.add_argument(

        "dataset",

        help=(
            "Dataset folder containing "
            "'1. No Signal' and "
            "'2. Signal for Help'"
        ),

    )


    parser.add_argument(

        "--fps",

        type=float,

        default=30.0,

        help=(
            "Assumed original video FPS "
            "for temporal timing "
            "(default: 30)"
        ),

    )


    args = parser.parse_args()


    if args.fps <= 0:

        raise RuntimeError(
            "--fps must be greater than 0"
        )


    dataset = (
        Path(args.dataset)
        .expanduser()
        .resolve()
    )


    no_signal_dir = (
        dataset
        / NO_SIGNAL
    )


    sos_dir = (
        dataset
        / SOS
    )


    if not no_signal_dir.is_dir():

        raise RuntimeError(
            f"Missing folder:\n"
            f"{no_signal_dir}"
        )


    if not sos_dir.is_dir():

        raise RuntimeError(
            f"Missing folder:\n"
            f"{sos_dir}"
        )


    # Reconstruct the original videos
    # from their flat JPG frame sequences.
    no_signal_videos = (
        group_frames(
            no_signal_dir
        )
    )


    sos_videos = (
        group_frames(
            sos_dir
        )
    )


    print(

        f"{NO_SIGNAL}: "
        f"{len(no_signal_videos)} videos, "
        f"{sum(map(len, no_signal_videos.values()))} frames"

    )


    print(

        f"{SOS}: "
        f"{len(sos_videos)} videos, "
        f"{sum(map(len, sos_videos.values()))} frames"

    )


    if not no_signal_videos:

        raise RuntimeError(
            "No No-Signal videos were found."
        )


    if not sos_videos:

        raise RuntimeError(
            "No Signal-for-Help videos were found."
        )


    # Load actual project engine.
    engine = load_engine()


    # Start from the REAL CURRENT defaults.
    config = copy.deepcopy(
        engine.DEFAULT_CONFIG
    )


    # Make model location explicit.
    config[
        "model_asset_path"
    ] = str(

        (
            ROOT
            / "src"
            / "hand_landmarker.task"
        ).resolve()

    )


    config["paused"] = False


    print(
        "\nSenyAlert settings used:"
    )


    keys_to_print = (

        "hand_detection_every_n_frames",

        "image_fallback_after_empty_video_frames",

        "confidence_threshold",

        "gesture_sensitivity",

        "open_hold_frames",

        "thumb_tuck_hold_frames",

        "close_hold_frames",

        "sequence_max_duration_sec",

    )


    for key in keys_to_print:

        print(
            f"  {key} = "
            f"{config[key]}"
        )


    print(
        f"  dataset FPS = "
        f"{args.fps}"
    )


    # Each run gets its own evidence folder.
    output_dir = (

        ROOT
        / "benchmark-results"
        / (
            "kaggle-sfh-"
            + datetime.now(
                timezone.utc
            ).strftime(
                "%Y%m%d-%H%M%S"
            )
        )

    )


    output_dir.mkdir(
        parents=True,
        exist_ok=False,
    )


    rows = []

    tp = 0
    tn = 0
    fp = 0
    fn = 0


    groups = [

        (
            NO_SIGNAL,
            no_signal_videos,
            False,
        ),

        (
            SOS,
            sos_videos,
            True,
        ),

    ]


    total_videos = sum(

        len(videos)

        for (
            _,
            videos,
            _,
        ) in groups

    )


    finished = 0


    # -----------------------------------------------------
    # RUN ALL VIDEOS
    # -----------------------------------------------------

    for (
        folder_name,
        videos,
        expected_sos,
    ) in groups:


        print(
            f"\n--- "
            f"{folder_name} "
            f"---"
        )


        for video_name in sorted(
            videos
        ):


            finished += 1


            # New tracker + new MediaPipe detector
            # for every original video.
            #
            # This prevents state leaking from
            # one video into another.
            tester = SequenceTester(
                engine,
                config,
                args.fps,
            )


            try:

                result = tester.run(
                    videos[
                        video_name
                    ]
                )

            finally:

                tester.close()


            predicted_sos = bool(
                result["detected"]
            )


            result_type = classify(
                expected_sos,
                predicted_sos,
            )


            if result_type == "TP":
                tp += 1

            elif result_type == "TN":
                tn += 1

            elif result_type == "FP":
                fp += 1

            else:
                fn += 1


            row = {

                "folder":
                    folder_name,

                "video":
                    video_name,

                "expected":
                    (
                        "SOS"
                        if expected_sos
                        else "NO_SOS"
                    ),

                "predicted":
                    (
                        "SOS"
                        if predicted_sos
                        else "NO_SOS"
                    ),

                "outcome":
                    result_type,

                **result,

            }


            rows.append(row)


            trigger = ""

            if (
                result[
                    "detection_dataset_frame"
                ]
                is not None
            ):

                trigger = (

                    " frame="
                    + str(
                        result[
                            "detection_dataset_frame"
                        ]
                    )

                )


            print(

                f"["
                f"{finished:02d}"
                f"/"
                f"{total_videos:02d}"
                f"] "
                f"{result_type} "
                f"{video_name}"
                f"{trigger}"

            )


    # -----------------------------------------------------
    # WRITE PER-VIDEO CSV
    # -----------------------------------------------------

    fields = [

        "folder",

        "video",

        "expected",

        "predicted",

        "outcome",

        "detected",

        "total_frames",

        "hand_inference_frames",

        "frames_with_hands",

        "detection_dataset_frame",

        "detection_sequence_index",

        "track_id",

        "state",

        "source",

        "raw_confidence",

        "effective_confidence",

        "repeated",

    ]


    csv_path = (
        output_dir
        / "sequence_results.csv"
    )


    with csv_path.open(
        "w",
        newline="",
        encoding="utf-8",
    ) as file:


        writer = csv.DictWriter(

            file,

            fieldnames=fields,

            extrasaction="ignore",

        )


        writer.writeheader()

        writer.writerows(
            rows
        )


    # -----------------------------------------------------
    # SUMMARY METRICS
    # -----------------------------------------------------

    summary = (
        calculate_metrics(
            tp,
            tn,
            fp,
            fn,
        )
    )


    summary[
        "dataset"
    ] = str(dataset)


    summary[
        "fps_assumption"
    ] = args.fps


    summary[
        "no_signal_videos"
    ] = len(
        no_signal_videos
    )


    summary[
        "signal_videos"
    ] = len(
        sos_videos
    )


    summary[
        "tested_at_utc"
    ] = datetime.now(
        timezone.utc
    ).isoformat()


    # Store the important detector settings
    # alongside the result so the experiment
    # is reproducible later.
    summary[
        "config"
    ] = {

        key:
            config[key]

        for key in (

            "hand_detection_every_n_frames",

            "image_fallback_after_empty_video_frames",

            "confidence_threshold",

            "gesture_sensitivity",

            "open_hold_frames",

            "thumb_tuck_hold_frames",

            "close_hold_frames",

            "sequence_max_duration_sec",

            "open_lost_grace_sec",

            "alert_cooldown_sec",

            "min_hand_detection_confidence",

            "min_hand_presence_confidence",

            "min_hand_tracking_confidence",

        )

    }


    json_path = (
        output_dir
        / "summary.json"
    )


    json_path.write_text(

        json.dumps(
            summary,
            indent=2,
        )
        + "\n",

        encoding="utf-8",

    )


    # -----------------------------------------------------
    # DISPLAY RESULTS
    # -----------------------------------------------------

    print(
        "\n"
        + "=" * 50
    )


    print(
        "KAGGLE SIGNAL-FOR-HELP RESULTS"
    )


    print(
        "=" * 50
    )


    print(
        f"TP: {tp}"
    )


    print(
        f"TN: {tn}"
    )


    print(
        f"FP: {fp}"
    )


    print(
        f"FN: {fn}"
    )


    print(
        "-" * 50
    )


    print(

        f"Accuracy : "
        f"{summary['accuracy'] * 100:.2f}%"

    )


    print(

        f"Precision: "
        f"{summary['precision'] * 100:.2f}%"

    )


    print(

        f"Recall   : "
        f"{summary['recall'] * 100:.2f}%"

    )


    print(

        f"F1 Score : "
        f"{summary['f1'] * 100:.2f}%"

    )


    print(
        "=" * 50
    )


    mistakes = [

        row

        for row in rows

        if row[
            "outcome"
        ]
        in {
            "FP",
            "FN",
        }

    ]


    if mistakes:

        print(
            "\nMisclassified videos:"
        )


        for row in mistakes:

            print(

                f"  "
                f"{row['outcome']}  "
                f"{row['folder']}"
                f"/"
                f"{row['video']}"

            )


    print(
        f"\nCSV:\n{csv_path}"
    )


    print(
        f"\nJSON:\n{json_path}"
    )


if __name__ == "__main__":
    main()