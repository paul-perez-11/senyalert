"""Repeatable, local SenyAlert benchmarks for Chapter 4/5 evidence.

The commands here intentionally write a new timestamped result directory for
each run. They do not start the Swing dashboard, create an incident, write
evidence media, or alter the normal incident database. Each JSON report names
its exact measurement scope and what it cannot measure.

Examples (from repository root):

    python tools/benchmark_senyalert.py synthetic --duration-sec 30
    python tools/benchmark_senyalert.py pipeline --source C:\\Videos\\sos.mp4 --max-frames 900
    python tools/benchmark_senyalert.py transport --count 200
    python tools/benchmark_senyalert.py evaluate-events --detections <run>/detections.csv \
        --ground-truth labels.csv
"""

from __future__ import annotations

import argparse
import copy
import csv
import ctypes
import importlib.util
import json
import math
import os
import platform
import random
import statistics
import sys
import threading
import time
import tracemalloc
from datetime import datetime, timezone
from pathlib import Path
from types import ModuleType
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULTS_ROOT = PROJECT_ROOT / "benchmark-results"


class BenchmarkError(RuntimeError):
    """A clear, non-destructive benchmark setup or runtime error."""


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")[:-3]


def safe_label(value: str) -> str:
    compact = "".join(character if character.isalnum() or character in "-_" else "-" for character in value)
    compact = compact.strip("-")
    return compact[:48] or "run"


def create_run_directory(parent: str | Path, benchmark: str, label: str = "") -> Path:
    root = Path(parent).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    stem = f"{safe_label(benchmark)}-{utc_timestamp()}"
    if label:
        stem += f"-{safe_label(label)}"
    for suffix in range(0, 10_000):
        candidate = root / (stem if suffix == 0 else f"{stem}-{suffix}")
        try:
            candidate.mkdir()
            return candidate
        except FileExistsError:
            continue
    raise BenchmarkError(f"Could not create a unique result directory under {root}")


def write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_csv(path: Path, fieldnames: Sequence[str], rows: Iterable[Dict[str, Any]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(fieldnames), extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({name: "" if row.get(name) is None else row.get(name) for name in fieldnames})


def percentile(sorted_values: Sequence[float], proportion: float) -> Optional[float]:
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return sorted_values[0]
    index = max(0.0, min(1.0, proportion)) * (len(sorted_values) - 1)
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return sorted_values[lower]
    fraction = index - lower
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * fraction


def numeric_summary(values: Iterable[float], unit: str = "ms") -> Dict[str, Any]:
    numbers = sorted(float(value) for value in values if value is not None and math.isfinite(float(value)))
    result: Dict[str, Any] = {"unit": unit, "count": len(numbers)}
    if not numbers:
        result.update({"min": None, "mean": None, "median": None, "p95": None, "p99": None, "max": None})
        return result
    result.update({
        "min": round(numbers[0], 3),
        "mean": round(statistics.fmean(numbers), 3),
        "median": round(percentile(numbers, 0.50) or 0.0, 3),
        "p95": round(percentile(numbers, 0.95) or 0.0, 3),
        "p99": round(percentile(numbers, 0.99) or 0.0, 3),
        "max": round(numbers[-1], 3),
    })
    return result


def rss_bytes() -> Optional[int]:
    """Return current process working set when the platform exposes it.

    This is process RSS/working-set only. It is not a GPU-memory metric and
    should not be reported as one.
    """
    try:
        if os.name == "nt":
            class ProcessMemoryCountersEx(ctypes.Structure):
                _fields_ = [
                    ("cb", ctypes.c_ulong),
                    ("PageFaultCount", ctypes.c_ulong),
                    ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t),
                    ("PrivateUsage", ctypes.c_size_t),
                ]

            counters = ProcessMemoryCountersEx()
            counters.cb = ctypes.sizeof(counters)
            process = ctypes.windll.kernel32.GetCurrentProcess()
            success = ctypes.windll.psapi.GetProcessMemoryInfo(
                process, ctypes.byref(counters), counters.cb
            )
            return int(counters.WorkingSetSize) if success else None
        import resource  # Unix-only stdlib module.

        raw = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return int(raw if sys.platform == "darwin" else raw * 1024)
    except Exception:
        return None


def common_environment() -> Dict[str, Any]:
    return {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "project_root": str(PROJECT_ROOT),
        "python": sys.version.split()[0],
        "platform": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or None,
        "cpu_count": os.cpu_count(),
    }


def load_engine() -> ModuleType:
    path = PROJECT_ROOT / "src" / "python-prototype.py"
    if not path.is_file():
        raise BenchmarkError(f"Could not find vision engine at {path}")
    module_name = "senyalert_benchmark_engine"
    existing = sys.modules.get(module_name)
    if existing is not None:
        return existing
    specification = importlib.util.spec_from_file_location(module_name, path)
    if specification is None or specification.loader is None:
        raise BenchmarkError("Could not load the vision engine for pipeline profiling")
    module = importlib.util.module_from_spec(specification)
    sys.modules[module_name] = module
    try:
        specification.loader.exec_module(module)
    except ModuleNotFoundError as error:
        sys.modules.pop(module_name, None)
        raise BenchmarkError(
            f"Vision dependency '{error.name}' is unavailable to this Python interpreter. "
            "Activate the project environment, then run: python -m pip install -r requirements.txt"
        ) from error
    except Exception as error:
        sys.modules.pop(module_name, None)
        raise BenchmarkError(f"Could not initialize the vision engine for profiling: {type(error).__name__}: {error}") from error
    return module


def parse_capture_source(raw: str) -> Any:
    candidate = raw.strip()
    if candidate and candidate.lstrip("+-").isdigit():
        return int(candidate)
    return candidate


def is_file_source(source: Any) -> bool:
    return isinstance(source, str) and Path(source).expanduser().is_file()


def cap_property(capture: Any, property_id: int) -> float:
    try:
        value = float(capture.get(property_id))
        return value if math.isfinite(value) else 0.0
    except Exception:
        return 0.0


def source_timestamp_ms(capture: Any, cv2: Any, fallback_ms: float) -> float:
    value = cap_property(capture, cv2.CAP_PROP_POS_MSEC)
    return value if value > 0.0 else fallback_ms


def capture_metrics(capture: Any) -> Dict[str, Any]:
    method = getattr(capture, "metrics_snapshot", None)
    if not callable(method):
        return {
            "capture_adapter": "direct_opencv_or_adb",
            "captured_frames": None,
            "delivered_frames": None,
            "dropped_stale_frames": None,
            "latest_frame_age_ms": None,
            "last_error": getattr(capture, "last_error", "") or "",
        }
    try:
        snapshot = method()
        return snapshot if isinstance(snapshot, dict) else {"capture_adapter": "unknown"}
    except Exception as error:
        return {"capture_adapter": "unknown", "metrics_error": str(error)}


def config_for_pipeline(engine: ModuleType, args: argparse.Namespace, source: Any) -> Dict[str, Any]:
    config = copy.deepcopy(engine.DEFAULT_CONFIG)
    config["camera_source"] = source
    config["model_asset_path"] = str((PROJECT_ROOT / "src" / "hand_landmarker.task").resolve())
    config["processing_scale"] = args.processing_scale
    config["hand_detection_every_n_frames"] = args.hand_every_n
    config["max_hands"] = args.max_hands
    config["people_detection_max_width"] = args.people_max_width
    return config


def run_pipeline(args: argparse.Namespace) -> int:
    source = parse_capture_source(args.source)
    if not is_file_source(source) and args.duration_sec <= 0 and args.max_frames <= 0:
        raise BenchmarkError("Live/network sources require --duration-sec or --max-frames so the benchmark stops safely")
    run_directory = create_run_directory(args.output_dir, "pipeline", args.label)
    engine = load_engine()
    cv2 = engine.cv2
    environment = common_environment()
    environment["opencv"] = getattr(cv2, "__version__", None)
    environment["mediapipe"] = getattr(engine.mp, "__version__", None)
    config = config_for_pipeline(engine, args, source)
    source_label = engine.display_camera_source(source)
    detector = None
    people_detector = None
    capture = None
    frame_rows: List[Dict[str, Any]] = []
    detection_rows: List[Dict[str, Any]] = []
    failures: List[str] = []
    timings: Dict[str, List[float]] = {
        "capture_read_ms": [], "resize_ms": [], "rgb_input_ms": [], "hand_detect_ms": [],
        "tracker_ms": [], "people_detect_ms": [], "pipeline_processing_ms": [],
        "capture_to_decision_ms": [],
    }
    rss_samples: List[int] = []
    frame_count = 0
    measured_frames = 0
    hand_detection_frames = 0
    people_detection_frames = 0
    confirmed_count = 0
    hand_observation_count = 0
    source_fps = 0.0
    last_results: List[Dict[str, Any]] = []
    last_video_timestamp_ms = -1
    initialized_ms = 0.0
    start_wall_ns = time.perf_counter_ns()
    start_cpu_s = time.process_time()
    trace_started = False
    peak_rss: Optional[int] = None
    base_analysis_clock = time.monotonic()
    tracker = None
    try:
        if args.trace_python_memory:
            tracemalloc.start()
            trace_started = True
        initialize_started = time.perf_counter_ns()
        detector = engine.create_hand_detector(config)
        tracker = engine.MultiHandTracker()
        if args.people_detection:
            people_detector = engine.PeopleDetector(start_worker=False)
        initialized_ms = (time.perf_counter_ns() - initialize_started) / 1_000_000.0
        capture = engine.open_camera(source, config)
        if capture is None:
            raise BenchmarkError(f"OpenCV could not open source: {source_label}")
        source_fps = cap_property(capture, cv2.CAP_PROP_FPS)
        source_started_ns = time.perf_counter_ns()

        while True:
            if args.duration_sec > 0 and (time.perf_counter_ns() - source_started_ns) / 1_000_000_000.0 >= args.duration_sec:
                break
            if args.max_frames > 0 and frame_count >= args.max_frames:
                break
            frame_started_ns = time.perf_counter_ns()
            capture_started_ns = frame_started_ns
            ok, frame = capture.read()
            capture_finished_ns = time.perf_counter_ns()
            if not ok or frame is None:
                # EOF is normal for an offline clip. For a live source, retain
                # the adapter's error in the report rather than silently
                # pretending an incomplete trial is a performance result.
                if is_file_source(source):
                    break
                detail = capture_metrics(capture).get("last_error", "Frame read failed")
                failures.append(str(detail or "Frame read failed"))
                break

            frame_count += 1
            frame_height, frame_width = frame.shape[:2]
            fallback_source_ms = (time.perf_counter_ns() - source_started_ns) / 1_000_000.0
            frame_source_ms = source_timestamp_ms(capture, cv2, fallback_source_ms)
            analysis_now = base_analysis_clock + frame_source_ms / 1_000.0
            processing_frame = frame
            resize_started_ns = time.perf_counter_ns()
            if args.processing_scale < 0.999:
                processing_width = max(1, round(frame_width * args.processing_scale))
                processing_height = max(1, round(frame_height * args.processing_scale))
                processing_frame = cv2.resize(
                    frame, (processing_width, processing_height), interpolation=cv2.INTER_AREA
                )
            processing_height, processing_width = processing_frame.shape[:2]
            resize_finished_ns = time.perf_counter_ns()

            should_detect_hands = frame_count % args.hand_every_n == 0
            rgb_input_ms: Optional[float] = None
            hand_detect_ms: Optional[float] = None
            tracker_ms: Optional[float] = None
            people_detect_ms: Optional[float] = None
            hands_detected = len(last_results)
            max_confidence: Optional[float] = None
            states = ""
            detection_source = "NOT_SCHEDULED"
            people_count: Optional[int] = None
            repeated_handsign = False
            repetition_cycle_count: Optional[int] = None
            repetition_state = ""
            palm_facing_quality: Optional[float] = None
            landmark_reliability: Optional[float] = None

            if should_detect_hands:
                input_started_ns = time.perf_counter_ns()
                rgb_frame = cv2.cvtColor(processing_frame, cv2.COLOR_BGR2RGB)
                image = engine.mp.Image(image_format=engine.mp.ImageFormat.SRGB, data=rgb_frame)
                input_finished_ns = time.perf_counter_ns()
                timestamp_ms = int(round(frame_source_ms))
                if timestamp_ms <= last_video_timestamp_ms:
                    timestamp_ms = last_video_timestamp_ms + 1
                last_video_timestamp_ms = timestamp_ms
                hand_started_ns = time.perf_counter_ns()
                detection = detector.detect_for_video(image, timestamp_ms)
                hand_finished_ns = time.perf_counter_ns()
                screen_hands = detection.hand_landmarks or []
                world_hands = detection.hand_world_landmarks or []
                rgb_input_ms = (input_finished_ns - input_started_ns) / 1_000_000.0
                hand_detect_ms = (hand_finished_ns - hand_started_ns) / 1_000_000.0
                detection_source = "VIDEO"

            if should_detect_hands:
                tracker_started_ns = time.perf_counter_ns()
                assert tracker is not None
                last_results = tracker.update(screen_hands, world_hands, analysis_now, config, "VIDEO")
                tracker_finished_ns = time.perf_counter_ns()
                tracker_ms = (tracker_finished_ns - tracker_started_ns) / 1_000_000.0
                hands_detected = len(last_results)
                if frame_count > args.warmup_frames:
                    hand_observation_count += hands_detected
                confidence_values = [
                    float(item["reading"].get("confidence", 0.0))
                    for item in last_results if item.get("reading") is not None
                ]
                max_confidence = max(confidence_values) if confidence_values else None
                states = "|".join(str(item.get("state", "")) for item in last_results)
                repetitions = [item.get("repetition") or {} for item in last_results]
                repeated_handsign = any(
                    bool(item.get("repeated_handsign"))
                    or bool((item.get("reading") or {}).get("repeated_handsign"))
                    or bool((item.get("repetition") or {}).get("is_repeated"))
                    for item in last_results
                )
                repetition_cycles = [
                    int(item["cycle_count"]) for item in repetitions
                    if isinstance(item.get("cycle_count"), (int, float))
                ]
                repetition_cycle_count = max(repetition_cycles) if repetition_cycles else None
                repetition_state = "|".join(str(item.get("state", "")) for item in repetitions if item.get("state") is not None)
                palm_values = [
                    float((item.get("reading") or {}).get("palm_facing_quality"))
                    for item in last_results
                    if isinstance((item.get("reading") or {}).get("palm_facing_quality"), (int, float))
                ]
                reliability_values = [
                    float((item.get("reading") or {}).get("landmark_reliability"))
                    for item in last_results
                    if isinstance((item.get("reading") or {}).get("landmark_reliability"), (int, float))
                ]
                palm_facing_quality = max(palm_values) if palm_values else None
                landmark_reliability = min(reliability_values) if reliability_values else None
                for result in last_results:
                    # Warm-up frames intentionally initialise MediaPipe and
                    # the temporal state machine but must not become test
                    # detections in a Chapter 4/5 accuracy comparison.
                    if frame_count <= args.warmup_frames or not result.get("confirmed"):
                        continue
                    confirmed_count += 1
                    reading = result.get("reading") or {}
                    detection_rows.append({
                        "event_index": confirmed_count,
                        "frame_index": frame_count,
                        "source_timestamp_ms": round(frame_source_ms, 3),
                        "track_id": result.get("track_id"),
                        "confidence": reading.get("confidence"),
                        "state": result.get("state"),
                        "detection_source": "VIDEO",
                        "repeated_handsign": bool((result.get("repetition") or {}).get("is_repeated")),
                        "repetition_cycle_count": (result.get("repetition") or {}).get("cycle_count"),
                    })

            if people_detector is not None and frame_count % args.people_every_n == 0:
                people_started_ns = time.perf_counter_ns()
                people = people_detector.detect_now(processing_frame, analysis_now, args.people_max_width)
                people_finished_ns = time.perf_counter_ns()
                people_detect_ms = (people_finished_ns - people_started_ns) / 1_000_000.0
                people_count = len(people)
                if frame_count > args.warmup_frames:
                    people_detection_frames += 1

            frame_finished_ns = time.perf_counter_ns()
            if frame_count <= args.warmup_frames:
                continue
            measured_frames += 1
            capture_read_ms = (capture_finished_ns - capture_started_ns) / 1_000_000.0
            resize_ms = (resize_finished_ns - resize_started_ns) / 1_000_000.0
            pipeline_processing_ms = (frame_finished_ns - capture_finished_ns) / 1_000_000.0
            capture_to_decision_ms = (frame_finished_ns - frame_started_ns) / 1_000_000.0
            timings["capture_read_ms"].append(capture_read_ms)
            timings["resize_ms"].append(resize_ms)
            timings["pipeline_processing_ms"].append(pipeline_processing_ms)
            timings["capture_to_decision_ms"].append(capture_to_decision_ms)
            if rgb_input_ms is not None:
                timings["rgb_input_ms"].append(rgb_input_ms)
            if hand_detect_ms is not None:
                timings["hand_detect_ms"].append(hand_detect_ms)
                hand_detection_frames += 1
            if tracker_ms is not None:
                timings["tracker_ms"].append(tracker_ms)
            if people_detect_ms is not None:
                timings["people_detect_ms"].append(people_detect_ms)
            current_rss = rss_bytes()
            if current_rss is not None:
                rss_samples.append(current_rss)
                peak_rss = max(peak_rss or 0, current_rss)
            frame_rows.append({
                "frame_index": frame_count,
                "source_timestamp_ms": round(frame_source_ms, 3),
                "capture_width": frame_width,
                "capture_height": frame_height,
                "processing_width": processing_width,
                "processing_height": processing_height,
                "hand_detection_ran": should_detect_hands,
                "people_detection_ran": people_detect_ms is not None,
                "capture_read_ms": round(capture_read_ms, 3),
                "resize_ms": round(resize_ms, 3),
                "rgb_input_ms": None if rgb_input_ms is None else round(rgb_input_ms, 3),
                "hand_detect_ms": None if hand_detect_ms is None else round(hand_detect_ms, 3),
                "tracker_ms": None if tracker_ms is None else round(tracker_ms, 3),
                "people_detect_ms": None if people_detect_ms is None else round(people_detect_ms, 3),
                "pipeline_processing_ms": round(pipeline_processing_ms, 3),
                "capture_to_decision_ms": round(capture_to_decision_ms, 3),
                "hands_detected": hands_detected,
                "max_hand_confidence": None if max_confidence is None else round(max_confidence, 4),
                "states": states,
                "people_count": people_count,
                "repeated_handsign": repeated_handsign,
                "repetition_cycle_count": repetition_cycle_count,
                "repetition_state": repetition_state,
                "palm_facing_quality": None if palm_facing_quality is None else round(palm_facing_quality, 4),
                "landmark_reliability": None if landmark_reliability is None else round(landmark_reliability, 4),
                "rss_bytes": current_rss,
            })
    except Exception as error:
        failures.append(f"{type(error).__name__}: {error}")
    finally:
        if capture is not None:
            try:
                capture.release()
            except Exception:
                pass
        if detector is not None:
            try:
                detector.close()
            except Exception:
                pass
        if people_detector is not None:
            people_detector.stop()

    wall_seconds = (time.perf_counter_ns() - start_wall_ns) / 1_000_000_000.0
    cpu_seconds = time.process_time() - start_cpu_s
    python_current_bytes: Optional[int] = None
    python_peak_bytes: Optional[int] = None
    if trace_started:
        python_current_bytes, python_peak_bytes = tracemalloc.get_traced_memory()
        tracemalloc.stop()
    if capture is not None:
        adapter_metrics = capture_metrics(capture)
    else:
        adapter_metrics = {"capture_adapter": "not_opened"}
    fields = [
        "frame_index", "source_timestamp_ms", "capture_width", "capture_height", "processing_width",
        "processing_height", "hand_detection_ran", "people_detection_ran", "capture_read_ms", "resize_ms",
        "rgb_input_ms", "hand_detect_ms", "tracker_ms", "people_detect_ms", "pipeline_processing_ms",
        "capture_to_decision_ms", "hands_detected", "max_hand_confidence", "states", "people_count",
        "repeated_handsign", "repetition_cycle_count", "repetition_state", "palm_facing_quality",
        "landmark_reliability", "rss_bytes",
    ]
    write_csv(run_directory / "frames.csv", fields, frame_rows)
    write_csv(
        run_directory / "detections.csv",
        ["event_index", "frame_index", "source_timestamp_ms", "track_id", "confidence", "state",
         "detection_source", "repeated_handsign", "repetition_cycle_count"],
        detection_rows,
    )
    report: Dict[str, Any] = {
        "benchmark": "python_vision_pipeline",
        "run_directory": str(run_directory),
        "environment": environment,
        "source": source_label,
        "source_is_offline_file": is_file_source(source),
        "source_reported_fps": round(source_fps, 3) if source_fps > 0 else None,
        "config": {
            "processing_scale": args.processing_scale,
            "hand_detection_every_n_frames": args.hand_every_n,
            "max_hands": args.max_hands,
            "people_detection_profiled": bool(args.people_detection),
            "people_every_n_frames": args.people_every_n if args.people_detection else None,
            "people_max_width": args.people_max_width if args.people_detection else None,
            "confidence_threshold": config["confidence_threshold"],
        },
        "measurement_scope": (
            "OpenCV frame acquisition, optional resize, MediaPipe VIDEO hand detection, "
            "stateful MultiHandTracker, and optionally synchronous PeopleDetector.detect_now. "
            "No WebSocket, Swing, alert sound, incident persistence, snapshot, or video write occurs."
        ),
        "not_measured": (
            "True camera-sensor-to-laptop latency cannot be inferred without a shared timestamp or filmed timer. "
            "For live streams, source_timestamp_ms is a local fallback if the backend does not expose one."
        ),
        "warmup_frames_excluded": args.warmup_frames,
        "frames_read": frame_count,
        "frames_measured": measured_frames,
        "hand_detection_frames_measured": hand_detection_frames,
        "people_detection_frames_measured": people_detection_frames,
        "hand_observations": hand_observation_count,
        "state_machine_confirmations": confirmed_count,
        "wall_duration_sec": round(wall_seconds, 3),
        "measured_throughput_fps": round(measured_frames / wall_seconds, 3) if wall_seconds > 0 else None,
        "process_cpu_seconds": round(cpu_seconds, 3),
        "process_cpu_to_wall_ratio": round(cpu_seconds / wall_seconds, 3) if wall_seconds > 0 else None,
        "initialization_ms": round(initialized_ms, 3),
        "timings": {name: numeric_summary(values) for name, values in timings.items()},
        "memory": {
            "working_set_rss_start_or_samples_min_bytes": min(rss_samples) if rss_samples else None,
            "working_set_rss_peak_bytes": peak_rss,
            "python_tracemalloc_current_bytes": python_current_bytes,
            "python_tracemalloc_peak_bytes": python_peak_bytes,
            "notes": "RSS includes native process working set when Windows exposes it; tracemalloc covers Python allocations only.",
        },
        "network_capture_metrics": adapter_metrics,
        "errors": failures,
        "files": {"frames": "frames.csv", "detections": "detections.csv"},
    }
    write_json(run_directory / "summary.json", report)
    print(f"Pipeline benchmark results: {run_directory}")
    return 0 if not failures else 2


class LatestFrameStress:
    """Synthetic producer/consumer model of the engine's one-frame network slot."""

    def __init__(self, producer_fps: float, processing_ms: float, jitter_ms: float, duration_sec: float, seed: int) -> None:
        self.producer_fps = producer_fps
        self.processing_ms = processing_ms
        self.jitter_ms = jitter_ms
        self.duration_sec = duration_sec
        self.random = random.Random(seed)
        self.condition = threading.Condition()
        self.slot: Optional[Tuple[int, int]] = None
        self.stop_producing = False
        self.produced = 0
        self.dropped = 0
        self.rows: List[Dict[str, Any]] = []

    def _producer(self, started_ns: int) -> None:
        interval_ns = int(1_000_000_000 / self.producer_fps)
        sequence = 0
        while True:
            target_ns = started_ns + sequence * interval_ns
            remaining_ns = target_ns - time.perf_counter_ns()
            if remaining_ns > 0:
                time.sleep(remaining_ns / 1_000_000_000.0)
            produced_at_ns = time.perf_counter_ns()
            if (produced_at_ns - started_ns) / 1_000_000_000.0 >= self.duration_sec:
                break
            with self.condition:
                if self.slot is not None:
                    self.dropped += 1
                self.slot = (sequence + 1, produced_at_ns)
                self.produced += 1
                self.condition.notify_all()
            sequence += 1
        with self.condition:
            self.stop_producing = True
            self.condition.notify_all()

    def run(self) -> List[Dict[str, Any]]:
        started_ns = time.perf_counter_ns()
        producer = threading.Thread(target=self._producer, args=(started_ns,), name="benchmark-frame-producer")
        producer.start()
        while True:
            with self.condition:
                while self.slot is None and not self.stop_producing:
                    self.condition.wait(timeout=0.25)
                if self.slot is None and self.stop_producing:
                    break
                sequence, produced_at_ns = self.slot
                self.slot = None
            processing_started_ns = time.perf_counter_ns()
            jitter = self.random.uniform(-self.jitter_ms, self.jitter_ms) if self.jitter_ms else 0.0
            applied_processing_ms = max(0.0, self.processing_ms + jitter)
            if applied_processing_ms:
                time.sleep(applied_processing_ms / 1_000.0)
            processing_finished_ns = time.perf_counter_ns()
            self.rows.append({
                "sequence": sequence,
                "producer_to_start_ms": round((processing_started_ns - produced_at_ns) / 1_000_000.0, 3),
                "producer_to_finish_ms": round((processing_finished_ns - produced_at_ns) / 1_000_000.0, 3),
                "simulated_processing_ms": round((processing_finished_ns - processing_started_ns) / 1_000_000.0, 3),
            })
        producer.join(timeout=2.0)
        return self.rows


def run_synthetic(args: argparse.Namespace) -> int:
    run_directory = create_run_directory(args.output_dir, "synthetic-latest-frame", args.label)
    environment = common_environment()
    started_ns = time.perf_counter_ns()
    stress = LatestFrameStress(
        args.producer_fps, args.processing_ms, args.processing_jitter_ms, args.duration_sec, args.seed
    )
    rows = stress.run()
    elapsed_seconds = (time.perf_counter_ns() - started_ns) / 1_000_000_000.0
    write_csv(
        run_directory / "frames.csv",
        ["sequence", "producer_to_start_ms", "producer_to_finish_ms", "simulated_processing_ms"],
        rows,
    )
    drop_rate = stress.dropped / stress.produced if stress.produced else None
    report = {
        "benchmark": "synthetic_latest_frame_stress",
        "run_directory": str(run_directory),
        "environment": environment,
        "measurement_scope": (
            "Synthetic bounded latest-frame producer/consumer model. It validates the expected stale-frame "
            "drop behaviour of a one-frame slot under a controlled processing delay."
        ),
        "not_measured": "No camera, decoder, MediaPipe, PeopleDetector, WebSocket, database, or Swing code runs.",
        "configuration": {
            "producer_fps": args.producer_fps,
            "simulated_processing_ms": args.processing_ms,
            "processing_jitter_ms": args.processing_jitter_ms,
            "duration_sec": args.duration_sec,
            "random_seed": args.seed,
            "queue_capacity": 1,
        },
        "frames_produced": stress.produced,
        "frames_processed": len(rows),
        "stale_frames_dropped": stress.dropped,
        "stale_drop_rate": round(drop_rate, 5) if drop_rate is not None else None,
        "wall_duration_sec": round(elapsed_seconds, 3),
        "processed_throughput_fps": round(len(rows) / elapsed_seconds, 3) if elapsed_seconds else None,
        "timings": {
            "producer_to_start_ms": numeric_summary([row["producer_to_start_ms"] for row in rows]),
            "producer_to_finish_ms": numeric_summary([row["producer_to_finish_ms"] for row in rows]),
            "simulated_processing_ms": numeric_summary([row["simulated_processing_ms"] for row in rows]),
        },
        "files": {"frames": "frames.csv"},
    }
    write_json(run_directory / "summary.json", report)
    print(f"Synthetic stress results: {run_directory}")
    return 0


def run_transport(args: argparse.Namespace) -> int:
    try:
        import websocket
    except ImportError as error:
        raise BenchmarkError("websocket-client is required; run: python -m pip install -r requirements.txt") from error
    run_directory = create_run_directory(args.output_dir, "websocket-transport", args.label)
    rows: List[Dict[str, Any]] = []
    failures: List[str] = []
    connection = None
    try:
        connection = websocket.create_connection(args.ws_url, timeout=args.timeout_sec)
        total = args.warmup + args.count
        padding = "x" * max(0, args.payload_bytes)
        for index in range(total):
            sequence = index + 1
            request = {"event": "BENCHMARK_PING", "sequence": sequence, "padding": padding}
            encoded = json.dumps(request, separators=(",", ":"))
            started_ns = time.perf_counter_ns()
            connection.send(encoded)
            reply = connection.recv()
            finished_ns = time.perf_counter_ns()
            payload = json.loads(reply)
            if payload.get("event") != "BENCHMARK_ACK" or int(payload.get("sequence", -1)) != sequence:
                raise BenchmarkError(f"Unexpected benchmark reply at sequence {sequence}: {payload}")
            if index >= args.warmup:
                rows.append({
                    "sequence": sequence - args.warmup,
                    "round_trip_ms": round((finished_ns - started_ns) / 1_000_000.0, 3),
                    "request_bytes": len(encoded.encode("utf-8")),
                    "server_received_epoch_ms": payload.get("server_received_epoch_ms"),
                })
            if args.interval_ms:
                time.sleep(args.interval_ms / 1_000.0)
    except Exception as error:
        failures.append(f"{type(error).__name__}: {error}")
    finally:
        if connection is not None:
            try:
                connection.close()
            except Exception:
                pass
    write_csv(
        run_directory / "samples.csv",
        ["sequence", "round_trip_ms", "request_bytes", "server_received_epoch_ms"], rows,
    )
    report = {
        "benchmark": "local_websocket_round_trip",
        "run_directory": str(run_directory),
        "environment": common_environment(),
        "websocket_url": args.ws_url,
        "measurement_scope": (
            "Client round-trip time for BENCHMARK_PING/ACK on the dashboard's isolated /benchmark endpoint. "
            "The endpoint is loopback-only and creates no incidents or database writes."
        ),
        "not_measured": "Camera-to-engine latency, Java persistence, Swing rendering, alert sound, and remote network latency.",
        "count_requested": args.count,
        "warmup_excluded": args.warmup,
        "payload_padding_bytes": args.payload_bytes,
        "interval_ms": args.interval_ms,
        "samples_recorded": len(rows),
        "round_trip_ms": numeric_summary([row["round_trip_ms"] for row in rows]),
        "errors": failures,
        "files": {"samples": "samples.csv"},
    }
    write_json(run_directory / "summary.json", report)
    print(f"WebSocket transport results: {run_directory}")
    return 0 if not failures else 2


def truthy_event(value: Any) -> bool:
    if value is None:
        return True
    normalized = str(value).strip().casefold()
    return normalized not in {"", "0", "false", "no", "none", "negative", "background", "idle"}


def first_field(row: Dict[str, str], candidates: Sequence[str]) -> Optional[str]:
    for candidate in candidates:
        value = row.get(candidate)
        if value is not None and value.strip() != "":
            return value
    return None


def read_event_csv(path: Path, kind: str) -> List[Dict[str, Any]]:
    if not path.is_file():
        raise BenchmarkError(f"{kind} CSV does not exist: {path}")
    events: List[Dict[str, Any]] = []
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames:
            raise BenchmarkError(f"{kind} CSV has no header row: {path}")
        for row_number, row in enumerate(reader, start=2):
            timestamp = first_field(row, ("source_timestamp_ms", "timestamp_ms", "time_ms", "timestamp"))
            if timestamp is None:
                raise BenchmarkError(
                    f"{kind} CSV needs source_timestamp_ms, timestamp_ms, time_ms, or timestamp (row {row_number})"
                )
            try:
                timestamp_ms = float(timestamp)
            except ValueError as error:
                raise BenchmarkError(f"Invalid {kind} timestamp on row {row_number}: {timestamp}") from error
            flag = first_field(row, ("confirmed", "event", "label", "is_event", "positive"))
            if not truthy_event(flag):
                continue
            events.append({"timestamp_ms": timestamp_ms, "row_number": row_number, "row": row})
    return sorted(events, key=lambda item: item["timestamp_ms"])


def safe_ratio(numerator: int, denominator: int) -> Optional[float]:
    return round(numerator / denominator, 4) if denominator else None


def run_event_evaluation(args: argparse.Namespace) -> int:
    run_directory = create_run_directory(args.output_dir, "event-evaluation", args.label)
    detections = read_event_csv(Path(args.detections).expanduser(), "detections")
    ground_truth = read_event_csv(Path(args.ground_truth).expanduser(), "ground truth")
    unmatched_detection_indices = set(range(len(detections)))
    matches: List[Dict[str, Any]] = []
    true_positives = 0
    false_negatives = 0
    delays: List[float] = []
    for expected in ground_truth:
        candidates = [
            index for index in unmatched_detection_indices
            if abs(detections[index]["timestamp_ms"] - expected["timestamp_ms"]) <= args.tolerance_ms
        ]
        if not candidates:
            false_negatives += 1
            matches.append({
                "outcome": "false_negative",
                "ground_truth_timestamp_ms": expected["timestamp_ms"],
                "detection_timestamp_ms": None,
                "delay_ms": None,
                "ground_truth_row": expected["row_number"],
                "detection_row": None,
            })
            continue
        closest = min(candidates, key=lambda index: abs(detections[index]["timestamp_ms"] - expected["timestamp_ms"]))
        unmatched_detection_indices.remove(closest)
        detected = detections[closest]
        delay = detected["timestamp_ms"] - expected["timestamp_ms"]
        delays.append(delay)
        true_positives += 1
        matches.append({
            "outcome": "true_positive",
            "ground_truth_timestamp_ms": expected["timestamp_ms"],
            "detection_timestamp_ms": detected["timestamp_ms"],
            "delay_ms": round(delay, 3),
            "ground_truth_row": expected["row_number"],
            "detection_row": detected["row_number"],
        })
    for index in sorted(unmatched_detection_indices):
        detected = detections[index]
        matches.append({
            "outcome": "false_positive",
            "ground_truth_timestamp_ms": None,
            "detection_timestamp_ms": detected["timestamp_ms"],
            "delay_ms": None,
            "ground_truth_row": None,
            "detection_row": detected["row_number"],
        })
    false_positives = len(unmatched_detection_indices)
    precision = safe_ratio(true_positives, true_positives + false_positives)
    recall = safe_ratio(true_positives, true_positives + false_negatives)
    f1 = None if precision is None or recall is None or precision + recall == 0 else round(2 * precision * recall / (precision + recall), 4)
    write_csv(
        run_directory / "matches.csv",
        ["outcome", "ground_truth_timestamp_ms", "detection_timestamp_ms", "delay_ms", "ground_truth_row", "detection_row"],
        matches,
    )
    report = {
        "benchmark": "event_detection_evaluation",
        "run_directory": str(run_directory),
        "environment": common_environment(),
        "measurement_scope": (
            "One-to-one event matching between positive ground-truth timestamps and confirmed detection timestamps. "
            "Each event can match at most one counterpart within the configured tolerance."
        ),
        "not_measured": (
            "Specificity/true-negative rate is unavailable from event timestamps alone. Use frame-level negative annotations "
            "for a separate specificity analysis."
        ),
        "detections_file": str(Path(args.detections).resolve()),
        "ground_truth_file": str(Path(args.ground_truth).resolve()),
        "tolerance_ms": args.tolerance_ms,
        "true_positives": true_positives,
        "false_positives": false_positives,
        "false_negatives": false_negatives,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "signed_detection_delay_ms": numeric_summary(delays),
        "absolute_detection_delay_ms": numeric_summary([abs(delay) for delay in delays]),
        "files": {"matches": "matches.csv"},
    }
    write_json(run_directory / "summary.json", report)
    print(f"Event evaluation results: {run_directory}")
    return 0


def summary_line(report: Dict[str, Any]) -> str:
    kind = str(report.get("benchmark", "unknown"))
    if kind == "python_vision_pipeline":
        return (
            f"frames={report.get('frames_measured')}, fps={report.get('measured_throughput_fps')}, "
            f"p95 pipeline ms={((report.get('timings') or {}).get('pipeline_processing_ms') or {}).get('p95')}"
        )
    if kind == "synthetic_latest_frame_stress":
        return f"produced={report.get('frames_produced')}, dropped={report.get('stale_frames_dropped')}, rate={report.get('stale_drop_rate')}"
    if kind == "local_websocket_round_trip":
        return f"samples={report.get('samples_recorded')}, p95 RTT ms={((report.get('round_trip_ms') or {}).get('p95'))}"
    if kind == "event_detection_evaluation":
        return f"precision={report.get('precision')}, recall={report.get('recall')}, F1={report.get('f1')}"
    if kind == "java_repository_persistence":
        return f"ops={report.get('operations_succeeded')}, p95 persistence ms={((report.get('successful_persistence_latency_ms') or {}).get('p95'))}"
    return "See the source summary JSON for scope and metrics."


def run_summarize(args: argparse.Namespace) -> int:
    output_directory = create_run_directory(args.output_dir, "benchmark-index", args.label)
    reports: List[Tuple[Path, Dict[str, Any]]] = []
    for raw_input in args.inputs:
        input_path = Path(raw_input).expanduser().resolve()
        candidates = [input_path] if input_path.is_file() else sorted(input_path.rglob("*summary.json"))
        for candidate in candidates:
            if candidate.name == "chapter-summary.json":
                continue
            try:
                payload = json.loads(candidate.read_text(encoding="utf-8"))
                if isinstance(payload, dict) and payload.get("benchmark"):
                    reports.append((candidate, payload))
            except (OSError, json.JSONDecodeError):
                continue
    if not reports:
        raise BenchmarkError("No benchmark summary JSON files found in the supplied input path(s)")
    index = {
        "benchmark": "benchmark_result_index",
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "report_count": len(reports),
        "reports": [{"path": str(path), "benchmark": report.get("benchmark"), "run_directory": report.get("run_directory")} for path, report in reports],
    }
    write_json(output_directory / "chapter-summary.json", index)
    lines = [
        "# SenyAlert benchmark result index",
        "",
        "This file indexes local benchmark outputs. Quote each result only with the measurement scope and exclusions in its source JSON.",
        "",
    ]
    for path, report in reports:
        lines.extend([
            f"## {report.get('benchmark', 'unknown')}",
            "",
            f"- Summary: `{path}`",
            f"- {summary_line(report)}",
            f"- Scope: {report.get('measurement_scope', 'Not recorded')}",
            "",
        ])
    (output_directory / "chapter-summary.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Benchmark result index: {output_directory}")
    return 0


def positive_float(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a number") from error
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def nonnegative_float(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a number") from error
    if not math.isfinite(parsed) or parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def nonnegative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


def add_output_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--output-dir", default=str(DEFAULT_RESULTS_ROOT), help="Parent directory for a new timestamped result folder")
    parser.add_argument("--label", default="", help="Optional short label added to the result folder name")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Repeatable local SenyAlert profiling, stress, transport, and event-evaluation tools."
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    pipeline = subcommands.add_parser("pipeline", help="Profile real OpenCV + MediaPipe + tracker processing from a file or camera source")
    pipeline.add_argument("--source", required=True, help="Video file, OpenCV camera index, uvc://N, HTTP MJPEG/RTSP URL, or adb:// serial")
    pipeline.add_argument("--duration-sec", type=nonnegative_float, default=0.0, help="Stop after wall-clock seconds; required for unlimited live inputs")
    pipeline.add_argument("--max-frames", type=nonnegative_int, default=0, help="Stop after this many decoded frames; 0 means no frame limit")
    pipeline.add_argument("--warmup-frames", type=nonnegative_int, default=20, help="Frames executed but excluded from timing summaries")
    pipeline.add_argument("--processing-scale", type=positive_float, default=1.0, help="Vision resize scale from 0.25 through 1.0")
    pipeline.add_argument("--hand-every-n", type=positive_int, default=2, help="Run hand inference every Nth decoded frame")
    pipeline.add_argument("--max-hands", type=positive_int, default=4, help="MediaPipe hand limit")
    pipeline.add_argument("--people-detection", action="store_true", help="Also profile synchronous face-validated people detection")
    pipeline.add_argument("--people-every-n", type=positive_int, default=8, help="People detector cadence when enabled")
    pipeline.add_argument("--people-max-width", type=positive_int, default=640, help="Maximum width for people detection")
    pipeline.add_argument("--trace-python-memory", action="store_true", help="Include Python allocation peak; native/GPU allocations are excluded")
    add_output_options(pipeline)
    pipeline.set_defaults(handler=run_pipeline)

    synthetic = subcommands.add_parser("synthetic", help="Stress a synthetic one-frame latest-frame queue")
    synthetic.add_argument("--duration-sec", type=positive_float, default=30.0)
    synthetic.add_argument("--producer-fps", type=positive_float, default=20.0)
    synthetic.add_argument("--processing-ms", type=nonnegative_float, default=50.0)
    synthetic.add_argument("--processing-jitter-ms", type=nonnegative_float, default=0.0)
    synthetic.add_argument("--seed", type=nonnegative_int, default=42)
    add_output_options(synthetic)
    synthetic.set_defaults(handler=run_synthetic)

    transport = subcommands.add_parser("transport", help="Measure local dashboard WebSocket ping/ack round-trip time")
    transport.add_argument("--ws-url", default="ws://127.0.0.1:8080/benchmark", help="Must target the dashboard's local /benchmark endpoint")
    transport.add_argument("--count", type=positive_int, default=200)
    transport.add_argument("--warmup", type=nonnegative_int, default=20)
    transport.add_argument("--payload-bytes", type=nonnegative_int, default=0, help="Extra JSON padding bytes per ping")
    transport.add_argument("--interval-ms", type=nonnegative_float, default=0.0)
    transport.add_argument("--timeout-sec", type=positive_float, default=5.0)
    add_output_options(transport)
    transport.set_defaults(handler=run_transport)

    evaluation = subcommands.add_parser("evaluate-events", help="Compare confirmed event timestamps against manually labelled ground truth")
    evaluation.add_argument("--detections", required=True, help="Pipeline detections.csv or CSV with a timestamp column")
    evaluation.add_argument("--ground-truth", required=True, help="CSV with positive event timestamp(s), in the same video timeline")
    evaluation.add_argument("--tolerance-ms", type=positive_float, default=1000.0, help="Maximum absolute timestamp difference for a one-to-one match")
    add_output_options(evaluation)
    evaluation.set_defaults(handler=run_event_evaluation)

    summarize = subcommands.add_parser("summarize", help="Create a Chapter 4/5-friendly index from benchmark summary JSON files")
    summarize.add_argument("inputs", nargs="+", help="Result folders or individual summary JSON files")
    add_output_options(summarize)
    summarize.set_defaults(handler=run_summarize)
    return parser


def validate_args(args: argparse.Namespace) -> None:
    if getattr(args, "processing_scale", 1.0) < 0.25 or getattr(args, "processing_scale", 1.0) > 1.0:
        raise BenchmarkError("--processing-scale must be between 0.25 and 1.0 to match SenyAlert's supported configuration")
    if getattr(args, "max_hands", 1) > 8:
        raise BenchmarkError("--max-hands must be between 1 and 8 to match SenyAlert's supported configuration")
    if getattr(args, "people_max_width", 160) < 160 or getattr(args, "people_max_width", 160) > 1920:
        raise BenchmarkError("--people-max-width must be between 160 and 1920")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        validate_args(args)
        return int(args.handler(args))
    except BenchmarkError as error:
        print(f"Benchmark setup failed: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Benchmark interrupted. Any completed result files were left intact.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
