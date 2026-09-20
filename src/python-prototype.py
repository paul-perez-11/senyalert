"""SenyAlert local vision engine.

The engine deliberately keeps computer vision, transport, and evidence capture
separate.  Hand geometry is calculated from MediaPipe *world* landmarks in
three dimensions.  Normalized 2-D coordinates are retained only for the live
preview and inexpensive frame-to-frame hand association.
"""

from __future__ import annotations

import copy
import base64
import datetime as dt
import json
import math
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Deque, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.parse import urlsplit, urlunsplit

import cv2
import mediapipe as mp
import numpy as np
import websocket
from mediapipe.tasks import python
from mediapipe.tasks.python import vision


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
SNAPSHOT_DIR = PROJECT_ROOT / "snapshots"
WS_URL = os.environ.get("SENYALERT_WS_URL", "ws://localhost:8080")
_ADB_SERIAL_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
_UVC_DEVICE_INDEX_PATTERN = re.compile(r"\d{1,9}")

# Profiles keep configuration useful before a source is opened.  They are
# defaults only: the worker always uses an actual decoded frame's dimensions
# for geometry and HUD rendering, so a portrait phone image is never stretched
# into a desktop 16:9 canvas.
CAMERA_PROFILES: Dict[str, Dict[str, Any]] = {
    "cctv": {
        "label": "CCTV",
        "aspect_ratio": (16, 9),
        "resolution": (1920, 1080),
        "preview_resolution": (960, 540),
    },
    "laptop_webcam": {
        "label": "Laptop webcam (demo)",
        "aspect_ratio": (16, 9),
        "resolution": (1280, 720),
        "preview_resolution": (960, 540),
    },
    "phone_front": {
        "label": "Phone camera (front) (demo)",
        "aspect_ratio": (9, 16),
        "resolution": (1080, 1920),
        "preview_resolution": (540, 960),
    },
    "phone_rear": {
        "label": "Phone camera (rear) (demo)",
        "aspect_ratio": (9, 16),
        "resolution": (1080, 1920),
        "preview_resolution": (540, 960),
    },
    "custom": {
        "label": "Custom camera",
        "aspect_ratio": (16, 9),
        "resolution": (1280, 720),
        "preview_resolution": (960, 540),
    },
}
_CAMERA_TYPE_ALIASES = {
    "laptop": "laptop_webcam",
    "laptop_webcam_demo": "laptop_webcam",
    "phone_front_demo": "phone_front",
    "phone_rear_demo": "phone_rear",
}


DEFAULT_CONFIG: Dict[str, Any] = {
    # Camera input can be a local webcam index (0), a first-class raw UVC
    # source (uvc://0), or a network stream such as http://... or rtsp://....
    # adb:// remains an explicitly lower-frame-rate screen-capture fallback.
    "camera_source": 0,
    "camera_id": "CAM-01-LAPTOP",
    "location": "Public Intake Counter A",
    "camera_type": "laptop_webcam",
    "aspect_ratio": [16, 9],
    # The dashboard can provide up to four independent camera entries.  Keep
    # this empty by default so existing single-camera configuration files keep
    # working without migration.
    "cameras": [],
    "resolution": [1280, 720],
    # Per-camera processing can reduce detector load without lowering the
    # source/evidence capture or the high-resolution local HUD.
    "processing_scale": 1.0,
    "preview_resolution": [960, 540],
    "model_asset_path": "hand_landmarker.task",
    # HandLandmarker VIDEO mode settings.
    "max_hands": 4,
    "hand_detection_every_n_frames": 2,
    "min_hand_detection_confidence": 0.50,
    "min_hand_presence_confidence": 0.50,
    # VIDEO-mode's internal tracker can lose a hand during the open-to-fist
    # motion.  Our own wrist tracker preserves identity, so use a forgiving
    # MediaPipe tracking threshold and retry IMAGE mode after short dropouts.
    "min_hand_tracking_confidence": 0.35,
    "image_fallback_after_empty_video_frames": 2,
    "landmark_visibility_threshold": 0.35,
    "landmark_presence_threshold": 0.35,
    # SOS sequence and tracking settings.
    "confidence_threshold": 0.70,
    "fingers": {
        "thumb": True,
        "index": True,
        "middle": True,
        "ring": True,
        "pinky": True,
    },
    # Preserve the group's deliberate, visible Signal-for-Help sequence:
    # hold an open palm, tuck the thumb while the fingers remain extended,
    # then close the four fingers over it.  The separate tuck hold is an
    # anti-false-positive gate; it was previously configured but unused.
    "open_hold_frames": 2,
    "thumb_tuck_hold_frames": 1,
    "close_hold_frames": 3,
    "sequence_max_duration_sec": 4.0,
    "open_lost_grace_sec": 0.60,
    "hand_match_max_dist": 0.20,
    "hand_track_stale_sec": 1.0,
    "alert_cooldown_sec": 3.0,
    # World-space geometry is telemetry/soft corroboration only. The phase
    # driver intentionally uses the proven normalized 2-D temporal reading.
    "finger_extended_angle_deg": 155.0,
    "finger_curled_angle_deg": 115.0,
    "finger_extended_tip_palm_ratio": 1.15,
    "finger_curled_tip_palm_ratio": 1.20,
    "thumb_tucked_ratio": 0.95,
    # The groupmate's working screen-space thresholds, normalized by
    # wrist-to-middle-MCP length.  They are deliberately independent of
    # distance from the camera and remain available when world landmarks are
    # noisy around a tucked/occluded thumb.
    "screen_finger_margin": 0.05,
    "screen_thumb_tucked_ratio": 0.55,
    "screen_open_min_extended": 3,
    "screen_closed_min_curled": 4,
    # Evidence is sampled at this rate; the ring is time based so it still
    # represents the configured seconds if a phone camera changes FPS.
    "pre_event_sec": 5.0,
    "post_event_sec": 5.0,
    "recording_fps": 10.0,
    # Face-validated OpenCV occupancy detection runs on a separate throttled path.
    "people_detection_enabled": True,
    "people_detection_interval_frames": 8,
    "quiet_at_or_above_people": 4,
    "audible_alerts_enabled": True,
    # Legacy aliases are still accepted on inbound configuration, but the
    # dashboard protocol above is the canonical public spelling.
    "people_detection_interval_sec": 1.0,
    "people_detection_max_width": 640,
    "people_detection_stale_sec": 3.0,
    # Dashboard previews are deliberately lossy: a disconnected dashboard
    # must never accumulate a backlog of JPEG frames.
    "dashboard_preview_fps": 2.0,
    # A larger room occupancy asks the dashboard for a quiet/discreet alert.
    "quiet_people_threshold": 4,
    "paused": False,
}


def _as_bool(value: Any) -> Optional[bool]:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "on"}:
            return True
        if normalized in {"false", "0", "no", "off"}:
            return False
    return None


def _bounded_float(value: Any, minimum: float, maximum: float) -> Optional[float]:
    try:
        converted = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(converted) or not minimum <= converted <= maximum:
        return None
    return converted


def _bounded_int(value: Any, minimum: int, maximum: int) -> Optional[int]:
    try:
        converted = int(value)
    except (TypeError, ValueError):
        return None
    if not minimum <= converted <= maximum:
        return None
    return converted


def normalize_camera_type(value: Any) -> Tuple[Optional[str], Optional[str]]:
    """Return a canonical camera-profile name accepted by both applications."""
    if not isinstance(value, str) or not value.strip():
        return None, "camera_type must be one of: CCTV, laptop webcam, phone front, phone rear, or custom"
    normalized = re.sub(r"[\s-]+", "_", value.strip().casefold())
    normalized = _CAMERA_TYPE_ALIASES.get(normalized, normalized)
    if normalized not in CAMERA_PROFILES:
        return None, "camera_type must be one of: cctv, laptop_webcam, phone_front, phone_rear, or custom"
    return normalized, None


def profile_for_camera_type(camera_type: Any) -> Dict[str, Any]:
    """Use a laptop profile only when reading an older profile-free config."""
    normalized, _ = normalize_camera_type(camera_type)
    return CAMERA_PROFILES[normalized or "laptop_webcam"]


def normalize_aspect_ratio(value: Any, camera_type: Any) -> Tuple[Optional[List[int]], Optional[str]]:
    """Validate an intentional configuration aspect; real capture frames still win at runtime."""
    if value is None:
        default_width, default_height = profile_for_camera_type(camera_type)["aspect_ratio"]
        return [default_width, default_height], None
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        return None, "aspect_ratio must be [width, height]"
    width = _bounded_int(value[0], 1, 10_000)
    height = _bounded_int(value[1], 1, 10_000)
    if width is None or height is None:
        return None, "aspect_ratio values must be whole numbers between 1 and 10,000"
    return [width, height], None


def display_aspect_ratio(width: int, height: int) -> str:
    """Format actual or configured dimensions as a compact reduced aspect ratio."""
    if width <= 0 or height <= 0:
        return "unknown aspect"
    divisor = math.gcd(width, height)
    return f"{width // divisor}:{height // divisor}"


def camera_input_kind(source: Any) -> str:
    """Classify adapters without implying that ADB can provide raw camera pixels."""
    if isinstance(source, str):
        normalized = source.strip().casefold()
        if normalized.startswith("uvc://"):
            return "UVC/raw USB"
        if normalized.startswith("adb://"):
            return "ADB screen-capture fallback"
        if normalized.startswith(("rtsp://", "rtsps://", "http://", "https://")):
            return "network stream"
    return "local webcam"


def is_network_stream_source(source: Any) -> bool:
    """Return whether a source can build an OpenCV decode backlog.

    USB cameras are pulled directly by their driver, but HTTP MJPEG and RTSP
    sources often continue producing frames while MediaPipe is busy.  The
    network path therefore uses ``LatestFrameCapture`` below so the camera
    worker never acts on a queue of already-old frames.
    """
    if not isinstance(source, str):
        return False
    try:
        return urlsplit(source).scheme.lower() in {"http", "https", "rtsp", "rtsps"}
    except ValueError:
        return False


def validate_camera_source(value: Any) -> Tuple[Optional[Any], Optional[str]]:
    """Accept a webcam index, UVC/raw-USB input, stream URL, or existing ADB device."""
    if isinstance(value, bool):
        return None, "camera_source must be a webcam index or stream URL"
    if isinstance(value, int):
        return (value, None) if value >= 0 else (None, "camera index must be non-negative")
    if not isinstance(value, str):
        return None, "camera_source must be a webcam index or stream URL"

    source = value.strip()
    if source.isdigit():
        return int(source), None
    try:
        parsed = urlsplit(source)
    except ValueError:
        return None, "stream URL contains an invalid host or port"
    if parsed.scheme.lower() == "uvc":
        device_index = parsed.netloc
        if parsed.path not in {"", "/"} or parsed.query or parsed.fragment or "@" in device_index:
            return None, "uvc source must be formatted as uvc://<webcam-index>"
        if not _UVC_DEVICE_INDEX_PATTERN.fullmatch(device_index):
            return None, "uvc webcam index must be a non-negative whole number"
        return f"uvc://{int(device_index)}", None
    if parsed.scheme.lower() == "adb":
        serial = parsed.netloc
        if parsed.path not in {"", "/"} or parsed.query or parsed.fragment or "@" in serial:
            return None, "adb source must be formatted as adb://<device-serial>"
        if not _ADB_SERIAL_PATTERN.fullmatch(serial):
            return None, "adb device serial contains unsupported characters"
        return f"adb://{serial}", None
    try:
        # Accessing .port validates malformed values such as :not-a-port.
        _ = parsed.port
    except ValueError:
        return None, "stream URL contains an invalid host or port"
    if parsed.scheme.lower() not in {"http", "https", "rtsp", "rtsps"} or not parsed.hostname:
        return None, "camera_source must be a webcam index, uvc://<webcam-index>, http(s)/rtsp stream, or adb://<device-serial>"
    return source, None


def validate_camera_definition(value: Any, index: int) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """Validate one independently processed camera without exposing URL secrets."""
    if not isinstance(value, dict):
        return None, f"cameras[{index}] must be an object"

    source_value = value.get("camera_source", value.get("source"))
    source, source_error = validate_camera_source(source_value)
    if source_error:
        return None, f"cameras[{index}].{source_error}"

    camera_id = value.get("camera_id", value.get("id"))
    if not isinstance(camera_id, str) or not camera_id.strip():
        return None, f"cameras[{index}].camera_id must be a non-empty string"
    camera_id = camera_id.strip()
    if len(camera_id) > 80:
        return None, f"cameras[{index}].camera_id must be at most 80 characters"

    location = value.get("location", "Unassigned Zone")
    if not isinstance(location, str) or not location.strip():
        return None, f"cameras[{index}].location must be a non-empty string"

    enabled = _as_bool(value.get("enabled", True))
    if enabled is None:
        return None, f"cameras[{index}].enabled must be true or false"

    camera_type, type_error = normalize_camera_type(
        value.get("camera_type", value.get("cameraType", "laptop_webcam"))
    )
    if type_error:
        return None, f"cameras[{index}].{type_error}"
    assert camera_type is not None
    aspect_ratio, aspect_error = normalize_aspect_ratio(value.get("aspect_ratio"), camera_type)
    if aspect_error:
        return None, f"cameras[{index}].{aspect_error}"
    assert aspect_ratio is not None

    camera: Dict[str, Any] = {
        "camera_source": source,
        "camera_id": camera_id,
        "location": location.strip(),
        "enabled": enabled,
        "camera_type": camera_type,
        "aspect_ratio": aspect_ratio,
    }

    # Camera-specific tuning is intentionally optional so saved settings from
    # earlier versions retain the global defaults.  Each valid override is
    # merged into that camera worker's private effective configuration.
    processing_scale = value.get("processing_scale")
    if processing_scale is not None:
        normalized_scale = _bounded_float(processing_scale, 0.25, 1.0)
        if normalized_scale is None:
            return None, f"cameras[{index}].processing_scale must be between 0.25 and 1.0"
        camera["processing_scale"] = normalized_scale

    profile = profile_for_camera_type(camera_type)
    for field_name in ("resolution", "preview_resolution"):
        dimensions = value.get(field_name, profile[field_name])
        if not isinstance(dimensions, (list, tuple)) or len(dimensions) != 2:
            return None, f"cameras[{index}].{field_name} must be [width, height]"
        width = _bounded_int(dimensions[0], 160, 3840)
        height = _bounded_int(dimensions[1], 120, 2160)
        if width is None or height is None:
            return None, f"cameras[{index}].{field_name} is outside the supported range"
        camera[field_name] = [width, height]

    return camera, None


def display_camera_source(source: Any) -> str:
    """Avoid sending stream credentials back to the dashboard in status events."""
    if not isinstance(source, str):
        return str(source)
    try:
        parsed = urlsplit(source)
        if parsed.scheme.lower() == "uvc" and parsed.netloc:
            return f"uvc://{parsed.netloc}"
        if parsed.scheme.lower() == "adb" and parsed.netloc:
            return f"adb://{parsed.netloc}"
        port = parsed.port
    except ValueError:
        return "invalid-stream-url"
    if not parsed.hostname:
        return source
    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    if port:
        host = f"{host}:{port}"
    # Stream query parameters often carry phone-camera tokens, so status
    # events intentionally omit them along with user/password credentials.
    return urlunsplit((parsed.scheme, host, parsed.path, "", ""))


class ConfigStore:
    """Thread-safe settings shared by the camera loop and WebSocket callbacks."""

    _FLOAT_RANGES = {
        "confidence_threshold": (0.0, 1.0),
        "min_hand_detection_confidence": (0.0, 1.0),
        "min_hand_presence_confidence": (0.0, 1.0),
        "min_hand_tracking_confidence": (0.0, 1.0),
        "landmark_visibility_threshold": (0.0, 1.0),
        "landmark_presence_threshold": (0.0, 1.0),
        "sequence_max_duration_sec": (0.25, 30.0),
        "open_lost_grace_sec": (0.0, 5.0),
        "hand_match_max_dist": (0.02, 1.0),
        "hand_track_stale_sec": (0.1, 20.0),
        "alert_cooldown_sec": (0.0, 300.0),
        "finger_extended_angle_deg": (120.0, 180.0),
        "finger_curled_angle_deg": (20.0, 150.0),
        "finger_extended_tip_palm_ratio": (0.25, 4.0),
        "finger_curled_tip_palm_ratio": (0.10, 4.0),
        "thumb_tucked_ratio": (0.10, 4.0),
        "screen_finger_margin": (0.0, 1.0),
        "screen_thumb_tucked_ratio": (0.10, 4.0),
        "pre_event_sec": (0.0, 60.0),
        "post_event_sec": (0.25, 60.0),
        "recording_fps": (1.0, 30.0),
        "people_detection_interval_sec": (0.20, 30.0),
        "people_detection_stale_sec": (0.5, 60.0),
        "dashboard_preview_fps": (0.2, 5.0),
        "processing_scale": (0.25, 1.0),
    }
    _INT_RANGES = {
        "max_hands": (1, 8),
        "hand_detection_every_n_frames": (1, 30),
        "image_fallback_after_empty_video_frames": (1, 30),
        "screen_open_min_extended": (1, 4),
        "screen_closed_min_curled": (1, 4),
        "open_hold_frames": (1, 20),
        "thumb_tuck_hold_frames": (1, 20),
        "close_hold_frames": (1, 30),
        "people_detection_interval_frames": (1, 120),
        "people_detection_max_width": (160, 1920),
        "quiet_people_threshold": (0, 1000),
        "quiet_at_or_above_people": (1, 1000),
    }

    def __init__(self) -> None:
        self._data = copy.deepcopy(DEFAULT_CONFIG)
        self._lock = threading.RLock()
        self._revision = 0

    def snapshot(self) -> Tuple[Dict[str, Any], int]:
        with self._lock:
            return copy.deepcopy(self._data), self._revision

    def set_paused(self, value: Any) -> Tuple[bool, str]:
        paused = _as_bool(value)
        if paused is None:
            return False, "PAUSE state must be true or false"
        with self._lock:
            if self._data["paused"] != paused:
                self._data["paused"] = paused
                self._revision += 1
        return True, "Engine detection is now PAUSED." if paused else "Engine detection is now RESUMED."

    def apply(self, incoming: Any) -> Tuple[List[str], Dict[str, str]]:
        if not isinstance(incoming, dict):
            return [], {"config": "config must be a JSON object"}

        applied: List[str] = []
        rejected: Dict[str, str] = {}
        with self._lock:
            updated = copy.deepcopy(self._data)
            for key, value in incoming.items():
                if key in self._FLOAT_RANGES:
                    candidate = _bounded_float(value, *self._FLOAT_RANGES[key])
                    if candidate is None:
                        rejected[key] = "outside its allowed numeric range"
                    else:
                        if key == "people_detection_interval_sec":
                            updated[key] = candidate
                            # Compatibility mapping for older clients; new
                            # dashboard messages use interval *frames*.
                            updated["people_detection_interval_frames"] = max(
                                1, min(120, round(candidate * updated["recording_fps"]))
                            )
                            applied.append("people_detection_interval_frames")
                        else:
                            updated[key] = candidate
                            applied.append(key)
                elif key in self._INT_RANGES:
                    candidate = _bounded_int(value, *self._INT_RANGES[key])
                    if candidate is None:
                        rejected[key] = "outside its allowed integer range"
                    else:
                        if key == "quiet_people_threshold":
                            updated["quiet_people_threshold"] = candidate
                            updated["quiet_at_or_above_people"] = max(1, candidate)
                            applied.append("quiet_at_or_above_people")
                        elif key == "quiet_at_or_above_people":
                            updated["quiet_at_or_above_people"] = candidate
                            updated["quiet_people_threshold"] = candidate
                            applied.append(key)
                        else:
                            updated[key] = candidate
                            applied.append(key)
                elif key == "camera_source":
                    candidate, error = validate_camera_source(value)
                    if error:
                        rejected[key] = error
                    else:
                        updated[key] = candidate
                        applied.append(key)
                elif key == "cameras":
                    if not isinstance(value, list) or not 1 <= len(value) <= 4:
                        rejected[key] = "must be an array containing one to four cameras"
                        continue
                    cameras: List[Dict[str, Any]] = []
                    seen_camera_ids = set()
                    for index, raw_camera in enumerate(value):
                        camera, error = validate_camera_definition(raw_camera, index)
                        if error:
                            rejected[key] = error
                            cameras = []
                            break
                        assert camera is not None
                        normalized_id = camera["camera_id"].casefold()
                        if normalized_id in seen_camera_ids:
                            rejected[key] = f"cameras[{index}].camera_id must be unique"
                            cameras = []
                            break
                        seen_camera_ids.add(normalized_id)
                        cameras.append(camera)
                    if cameras:
                        updated[key] = cameras
                        # Legacy scalar fields remain synchronized with the
                        # first configured camera for old engine consumers.
                        primary = next((camera for camera in cameras if camera["enabled"]), cameras[0])
                        updated["camera_source"] = primary["camera_source"]
                        updated["camera_id"] = primary["camera_id"]
                        updated["location"] = primary["location"]
                        updated["camera_type"] = primary["camera_type"]
                        updated["aspect_ratio"] = copy.deepcopy(primary["aspect_ratio"])
                        updated["resolution"] = copy.deepcopy(primary["resolution"])
                        updated["preview_resolution"] = copy.deepcopy(primary["preview_resolution"])
                        applied.append(key)
                elif key in {"camera_id", "location", "model_asset_path"}:
                    if not isinstance(value, str) or not value.strip():
                        rejected[key] = "must be a non-empty string"
                    else:
                        updated[key] = value.strip()
                        applied.append(key)
                elif key == "camera_type":
                    candidate, error = normalize_camera_type(value)
                    if error:
                        rejected[key] = error
                    else:
                        assert candidate is not None
                        updated[key] = candidate
                        profile = profile_for_camera_type(candidate)
                        updated["aspect_ratio"] = list(profile["aspect_ratio"])
                        updated["resolution"] = list(profile["resolution"])
                        updated["preview_resolution"] = list(profile["preview_resolution"])
                        applied.append(key)
                elif key == "aspect_ratio":
                    candidate, error = normalize_aspect_ratio(value, updated["camera_type"])
                    if error:
                        rejected[key] = error
                    else:
                        updated[key] = candidate
                        applied.append(key)
                elif key in {"resolution", "preview_resolution"}:
                    if not isinstance(value, (list, tuple)) or len(value) != 2:
                        rejected[key] = "must be [width, height]"
                        continue
                    width = _bounded_int(value[0], 160, 3840)
                    height = _bounded_int(value[1], 120, 2160)
                    if width is None or height is None:
                        rejected[key] = "width or height is outside its allowed range"
                    else:
                        updated[key] = [width, height]
                        applied.append(key)
                elif key == "fingers":
                    if not isinstance(value, dict):
                        rejected[key] = "must be an object of finger booleans"
                        continue
                    finger_values = copy.deepcopy(updated["fingers"])
                    invalid = False
                    for finger, setting in value.items():
                        if finger not in finger_values:
                            rejected[f"fingers.{finger}"] = "unknown finger"
                            invalid = True
                            continue
                        enabled = _as_bool(setting)
                        if enabled is None:
                            rejected[f"fingers.{finger}"] = "must be true or false"
                            invalid = True
                        else:
                            finger_values[finger] = enabled
                    if not invalid:
                        updated["fingers"] = finger_values
                        applied.append(key)
                elif key == "paused":
                    paused = _as_bool(value)
                    if paused is None:
                        rejected[key] = "must be true or false"
                    else:
                        updated[key] = paused
                        applied.append(key)
                elif key in {"people_detection_enabled", "audible_alerts_enabled"}:
                    enabled = _as_bool(value)
                    if enabled is None:
                        rejected[key] = "must be true or false"
                    else:
                        updated[key] = enabled
                        applied.append(key)
                else:
                    rejected[key] = "unknown setting"

            if applied:
                self._data = updated
                self._revision += 1
        return applied, rejected


CONFIG = ConfigStore()


class EngineSocketClient:
    """One persistent WebSocket connection with ordered reconnect buffering."""

    def __init__(self, url: str, config: ConfigStore) -> None:
        self.url = url
        self.config = config
        self._stop = threading.Event()
        self._connection_lock = threading.RLock()
        self._send_lock = threading.Lock()
        self._app: Optional[websocket.WebSocketApp] = None
        self._pending: Deque[str] = deque(maxlen=200)
        self._thread = threading.Thread(target=self._run, name="senyalert-websocket", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        with self._connection_lock:
            app = self._app
        if app is not None:
            try:
                app.close()
            except Exception:
                pass

    def send(self, payload: Dict[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":"), default=str)
        with self._connection_lock:
            app = self._app
        if app is not None and getattr(app, "sock", None) and app.sock.connected:
            try:
                with self._send_lock:
                    app.send(encoded)
                return
            except Exception as exc:
                print(f"[SOCKET] Send deferred until reconnect: {exc}")
        with self._connection_lock:
            self._pending.append(encoded)

    def send_volatile(self, payload: Dict[str, Any]) -> bool:
        """Send a replaceable live-preview message only while connected.

        Incident events use :meth:`send` and are retained across reconnects.
        JPEG previews are intentionally dropped instead: sending old video
        frames after a reconnect would be both misleading and memory-heavy.
        """
        encoded = json.dumps(payload, separators=(",", ":"), default=str)
        with self._connection_lock:
            app = self._app
        if app is None or not getattr(app, "sock", None) or not app.sock.connected:
            return False
        try:
            with self._send_lock:
                app.send(encoded)
            return True
        except Exception:
            return False

    def _flush_pending(self, app: websocket.WebSocketApp) -> None:
        while not self._stop.is_set():
            with self._connection_lock:
                if not self._pending:
                    return
                encoded = self._pending[0]
            try:
                with self._send_lock:
                    app.send(encoded)
            except Exception:
                return
            with self._connection_lock:
                if self._pending and self._pending[0] == encoded:
                    self._pending.popleft()

    def _on_open(self, app: websocket.WebSocketApp) -> None:
        print(f"[SOCKET] Connected to {self.url}")
        self.send({"event": "ENGINE_STATUS", "status": "CONNECTED", "timestamp": int(time.time())})
        self._flush_pending(app)

    def _on_message(self, _app: websocket.WebSocketApp, message: str) -> None:
        try:
            data = json.loads(message)
            action = data.get("action")
            if action == "UPDATE_SETTINGS":
                applied, rejected = self.config.apply(data.get("config", {}))
                status = "Engine configuration successfully reloaded."
                if rejected:
                    status = "Configuration applied with validation warnings."
                self.send({
                    "event": "CONFIG_ACK",
                    "status": status,
                    "applied_fields": applied,
                    "rejected_fields": rejected,
                })
            elif action == "PAUSE":
                ok, status = self.config.set_paused(data.get("state"))
                self.send({"event": "CONFIG_ACK", "status": status, "ok": ok})
            else:
                self.send({"event": "CONFIG_ACK", "status": "Unknown engine action ignored.", "ok": False})
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            self.send({"event": "CONFIG_ACK", "status": f"Invalid command ignored: {exc}", "ok": False})

    @staticmethod
    def _on_error(_app: websocket.WebSocketApp, error: Any) -> None:
        print(f"[SOCKET] {error}")

    @staticmethod
    def _on_close(_app: websocket.WebSocketApp, code: Any, reason: Any) -> None:
        print(f"[SOCKET] Disconnected ({code}): {reason}")

    def _run(self) -> None:
        while not self._stop.is_set():
            app = websocket.WebSocketApp(
                self.url,
                on_open=self._on_open,
                on_message=self._on_message,
                on_error=self._on_error,
                on_close=self._on_close,
            )
            with self._connection_lock:
                self._app = app
            try:
                app.run_forever(ping_interval=20, ping_timeout=5)
            except Exception as exc:
                print(f"[SOCKET] Reconnect loop error: {exc}")
            finally:
                with self._connection_lock:
                    if self._app is app:
                        self._app = None
            self._stop.wait(3.0)


FINGER_JOINTS = {
    # Finger name: (MCP, PIP, DIP, tip), shared by the strict 2-D gate and
    # 3-D diagnostic telemetry.
    "index": (5, 6, 7, 8),
    "middle": (9, 10, 11, 12),
    "ring": (13, 14, 15, 16),
    "pinky": (17, 18, 19, 20),
}
PALM_MCP_INDICES = (5, 9, 13, 17)


def _point3(landmark: Any) -> Tuple[float, float, float]:
    return float(landmark.x), float(landmark.y), float(landmark.z)


def _distance3(first: Tuple[float, float, float], second: Tuple[float, float, float]) -> float:
    return math.sqrt(sum((a - b) ** 2 for a, b in zip(first, second)))


def _joint_angle_deg(first: Tuple[float, float, float], vertex: Tuple[float, float, float], third: Tuple[float, float, float]) -> Optional[float]:
    left = tuple(a - b for a, b in zip(first, vertex))
    right = tuple(a - b for a, b in zip(third, vertex))
    left_length = math.sqrt(sum(component * component for component in left))
    right_length = math.sqrt(sum(component * component for component in right))
    if left_length < 1e-8 or right_length < 1e-8:
        return None
    cosine = sum(a * b for a, b in zip(left, right)) / (left_length * right_length)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _required_landmark_indices(config: Dict[str, Any]) -> List[int]:
    indices = {0, 5, 9, 13, 17}
    if config["fingers"].get("thumb", True):
        indices.update((2, 3, 4))
    for finger, joint_indices in FINGER_JOINTS.items():
        if config["fingers"].get(finger, True):
            indices.update(joint_indices)
    return sorted(indices)


def landmark_confidence_status(
    screen_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]
) -> Dict[str, Any]:
    """Use visibility/presence only when this MediaPipe result actually exposes them.

    HandLandmarker versions commonly omit these optional fields.  An omitted
    field is represented as unavailable rather than fabricated as a confidence
    score, while an available low score causes that frame to be skipped.
    """
    if not screen_landmarks:
        return {"passed": False, "available": False, "reason": "screen_landmarks_missing"}

    observed = 0
    for index in _required_landmark_indices(config):
        if index >= len(screen_landmarks):
            return {"passed": False, "available": False, "reason": f"landmark_{index}_missing"}
        landmark = screen_landmarks[index]
        for attribute, threshold_key in (
            ("visibility", "landmark_visibility_threshold"),
            ("presence", "landmark_presence_threshold"),
        ):
            value = getattr(landmark, attribute, None)
            if value is None:
                continue
            try:
                numeric = float(value)
            except (TypeError, ValueError):
                continue
            observed += 1
            if numeric < config[threshold_key]:
                return {
                    "passed": False,
                    "available": True,
                    "reason": f"{attribute}_below_threshold",
                    "landmark_index": index,
                    "value": numeric,
                }

    if observed == 0:
        return {
            "passed": True,
            "available": False,
            "reason": "visibility_and_presence_not_provided_by_hand_landmarker",
        }
    return {"passed": True, "available": True, "checked_values": observed}


def _point2(landmark: Any) -> Tuple[float, float]:
    return float(landmark.x), float(landmark.y)


def _distance2(first: Tuple[float, float], second: Tuple[float, float]) -> float:
    return math.hypot(first[0] - second[0], first[1] - second[1])


def _projection2(vector: Tuple[float, float], axis: Tuple[float, float]) -> float:
    return vector[0] * axis[0] + vector[1] * axis[1]


def analyze_screen_hand(screen_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Read the groupmate's strict, distance-normalized SOS hand phases.

    The authoritative phase rules intentionally use the group's original
    screen-space geometry: the wrist must be below the middle MCP, each
    finger is compared to its own PIP/MCP along the image Y axis, and every
    distance is divided by wrist-to-middle-MCP length.  That scale ratio keeps
    the rule stable as a hand moves nearer or farther from the camera without
    broadening it to unrelated palm-axis poses.

    World-space angles are still collected as diagnostic metadata elsewhere,
    but never loosen this temporal gate.  A single frame can therefore be
    classified as OPEN, THUMB_TUCK, CLOSED, or none; confirmation is handled
    by :class:`SignalStateMachine` only after all three visible phases occur
    in order.
    """
    if not screen_landmarks or len(screen_landmarks) < 21:
        return None

    try:
        points = [_point2(landmark) for landmark in screen_landmarks]
        palm_scale = _distance2(points[0], points[9])
    except (AttributeError, TypeError, ValueError):
        return None
    if palm_scale < 1e-6:
        return None

    palm_center = (
        sum(points[index][0] for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
        sum(points[index][1] for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
    )
    margin = float(config["screen_finger_margin"])
    enabled_fingers = [name for name in FINGER_JOINTS if config["fingers"].get(name, True)]
    if not enabled_fingers:
        return None

    extended_count = 0
    curled_count = 0
    finger_metrics: Dict[str, Dict[str, Any]] = {}
    for name, (mcp, pip, _dip, tip) in FINGER_JOINTS.items():
        # This is the exact groupmate shape test, adapted only to this file's
        # MCP/PIP/DIP/tip tuple order.  A fist must put every fingertip below
        # its MCP -- merely bending below the PIP is not enough.
        extended = (points[pip][1] - points[tip][1]) / palm_scale > margin
        curled = (points[tip][1] - points[mcp][1]) / palm_scale > margin
        if name in enabled_fingers:
            extended_count += int(extended)
            curled_count += int(curled)
        finger_metrics[name] = {
            "groupmate_extended": extended,
            "groupmate_curled": curled,
        }

    thumb_tip_to_palm = _distance2(points[4], palm_center) / palm_scale
    thumb_tuck_required = bool(config["fingers"].get("thumb", True))
    thumb_tucked = thumb_tip_to_palm < float(config["screen_thumb_tucked_ratio"])
    thumb_requirement_met = thumb_tucked or not thumb_tuck_required
    selected_count = len(enabled_fingers)
    open_minimum = min(selected_count, int(config["screen_open_min_extended"]))
    closed_minimum = min(selected_count, int(config["screen_closed_min_curled"]))
    upright = points[0][1] > points[9][1]

    is_open = upright and extended_count >= open_minimum
    is_closed = upright and thumb_requirement_met and curled_count >= closed_minimum
    # The thumb must be visibly tucked *before* the fingers form a complete
    # fist.  Keeping at least the configured open-finger count extended here
    # prevents an ordinary open-to-fist movement from skipping this phase.
    is_thumb_tuck_phase = (
        upright
        and thumb_requirement_met
        and extended_count >= open_minimum
        and curled_count < closed_minimum
    )

    # A completed screen-space sequence should not be rejected by an unrelated
    # world-angle score.  The old demo intentionally treated this as a strong
    # positive after the temporal open->close safeguards had been satisfied.
    closed_fraction = min(1.0, curled_count / max(1, closed_minimum))
    thumb_quality = 1.0 if not thumb_tuck_required else float(thumb_tucked)
    confidence = (0.75 * closed_fraction + 0.25 * thumb_quality) if is_closed else 0.0
    return {
        "is_open": is_open,
        "is_closed": is_closed,
        "thumb_tucked": thumb_tucked,
        "thumb_tuck_required": thumb_tuck_required,
        "thumb_requirement_met": thumb_requirement_met,
        "confidence": confidence,
        "phase_driver": "screen_2d_groupmate_strict",
        "thumb_tip_to_palm_ratio": round(thumb_tip_to_palm, 3),
        "thumb_ip_angle_deg": None,
        "extended_count": extended_count,
        "curled_count": curled_count,
        "required_finger_count": selected_count,
        "is_thumb_tuck_phase": is_thumb_tuck_phase,
        # Keep these compatibility keys for consumers which already display
        # the old diagnostic field names.  There is no permissive palm-axis
        # alternative in the strict groupmate gate.
        "legacy_extended_count": extended_count,
        "legacy_curled_count": curled_count,
        "normalized_extended_count": None,
        "normalized_curled_count": None,
        "legacy_open": is_open,
        "normalized_open": False,
        "legacy_closed": is_closed,
        "normalized_closed": False,
        "finger_metrics": finger_metrics,
        "landmark_confidence": {"passed": True, "available": False, "reason": "screen_landmarks_phase_driver"},
    }


def combine_hand_readings(
    screen_landmarks: Optional[Sequence[Any]], world_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]
) -> Optional[Dict[str, Any]]:
    """Keep world-space output as diagnostics without allowing it to loosen phases."""
    visibility = landmark_confidence_status(screen_landmarks, config)
    if not visibility["passed"]:
        # When a MediaPipe build supplies low landmark confidence, do not let
        # guessed/occluded joints advance an SOS sequence.  Builds which do
        # not expose these fields remain supported by the status helper.
        return None
    screen = analyze_screen_hand(screen_landmarks, config)
    world = analyze_world_hand(world_landmarks, screen_landmarks, config)
    if screen is None:
        return None
    reading = dict(screen)
    reading["landmark_confidence"] = visibility
    reading["world_landmarks_available"] = world_landmarks is not None and len(world_landmarks) >= 21
    reading["world_geometry"] = None if world is None else {
        "confidence": world.get("confidence"),
        "is_open": world.get("is_open"),
        "is_closed": world.get("is_closed"),
        "thumb_tip_to_palm_ratio": world.get("thumb_tip_to_palm_ratio"),
        "thumb_ip_angle_deg": world.get("thumb_ip_angle_deg"),
        "extended_count": world.get("extended_count"),
        "curled_count": world.get("curled_count"),
        "landmark_confidence": world.get("landmark_confidence"),
    }
    return reading


def analyze_world_hand(
    world_landmarks: Optional[Sequence[Any]],
    screen_landmarks: Optional[Sequence[Any]],
    config: Dict[str, Any],
) -> Optional[Dict[str, Any]]:
    """Classify one frame using world-space angles and palm-relative geometry."""
    visibility = landmark_confidence_status(screen_landmarks, config)
    if not visibility["passed"]:
        return None
    if not world_landmarks or len(world_landmarks) < 21:
        return None

    try:
        points = [_point3(landmark) for landmark in world_landmarks]
        palm_center = tuple(sum(points[index][axis] for index in PALM_MCP_INDICES) / 4.0 for axis in range(3))
        palm_scale = _distance3(points[0], points[9])
    except (AttributeError, TypeError, ValueError):
        return None
    if palm_scale < 1e-6:
        return None

    finger_metrics: Dict[str, Dict[str, float]] = {}
    extended_count = 0
    curled_count = 0
    enabled_fingers = [name for name in FINGER_JOINTS if config["fingers"].get(name, True)]
    if not enabled_fingers:
        return None

    for name, (mcp, pip, dip, tip) in FINGER_JOINTS.items():
        pip_angle = _joint_angle_deg(points[mcp], points[pip], points[dip])
        dip_angle = _joint_angle_deg(points[pip], points[dip], points[tip])
        if pip_angle is None or dip_angle is None:
            return None
        tip_to_palm = _distance3(points[tip], palm_center) / palm_scale
        extended = (
            pip_angle >= config["finger_extended_angle_deg"]
            and dip_angle >= config["finger_extended_angle_deg"] - 8.0
            and tip_to_palm >= config["finger_extended_tip_palm_ratio"]
        )
        curled = (
            pip_angle <= config["finger_curled_angle_deg"]
            and tip_to_palm <= config["finger_curled_tip_palm_ratio"]
        )
        if name in enabled_fingers:
            extended_count += int(extended)
            curled_count += int(curled)
        finger_metrics[name] = {
            "pip_angle_deg": round(pip_angle, 1),
            "dip_angle_deg": round(dip_angle, 1),
            "tip_to_palm_ratio": round(tip_to_palm, 3),
            "extended": extended,
            "curled": curled,
        }

    thumb_tip_to_palm = _distance3(points[4], palm_center) / palm_scale
    thumb_ip_angle = _joint_angle_deg(points[2], points[3], points[4])
    thumb_tucked = thumb_tip_to_palm <= config["thumb_tucked_ratio"]
    thumb_tuck_required = bool(config["fingers"].get("thumb", True))
    thumb_requirement_met = thumb_tucked or not thumb_tuck_required
    selected_count = len(enabled_fingers)
    is_open = extended_count == selected_count
    is_closed = curled_count == selected_count

    # Quality is intentionally calculated from measurable 3-D geometry; it is
    # diagnostic metadata, not a gate for the screen-space temporal sequence.
    curled_fraction = curled_count / selected_count
    thumb_quality = 1.0 if not thumb_tuck_required else max(
        0.0,
        min(1.0, (config["thumb_tucked_ratio"] * 1.35 - thumb_tip_to_palm) / (config["thumb_tucked_ratio"] * 0.70)),
    )
    curled_angle_quality = sum(
        max(0.0, min(1.0, (config["finger_curled_angle_deg"] * 1.15 - details["pip_angle_deg"]) / config["finger_curled_angle_deg"]))
        for name, details in finger_metrics.items()
        if name in enabled_fingers
    ) / selected_count
    confidence = max(0.0, min(1.0, 0.45 * curled_fraction + 0.30 * thumb_quality + 0.25 * curled_angle_quality))

    return {
        "is_open": is_open,
        "thumb_tucked": thumb_tucked,
        "thumb_tuck_required": thumb_tuck_required,
        "thumb_requirement_met": thumb_requirement_met,
        "is_closed": is_closed,
        "confidence": confidence,
        "thumb_tip_to_palm_ratio": round(thumb_tip_to_palm, 3),
        "thumb_ip_angle_deg": round(thumb_ip_angle, 1) if thumb_ip_angle is not None else None,
        "extended_count": extended_count,
        "curled_count": curled_count,
        "required_finger_count": selected_count,
        "finger_metrics": finger_metrics,
        "landmark_confidence": visibility,
    }


class SignalStateMachine:
    """Strict groupmate-compatible open-palm -> tucked-thumb fist guard.

    The original successful prototype did not require a separately observed
    fully-open thumb-tuck frame.  It gates a complete tucked-thumb fist behind
    a sustained open palm instead.  That preserves the group's proven motion
    while the consecutive-close requirement below removes jittery false
    accumulation.
    """

    IDLE = "IDLE"
    WAITING_CLOSE = "WAITING_CLOSE"

    def __init__(self) -> None:
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.sequence_started_at: Optional[float] = None
        self.last_seen_at: Optional[float] = None

    def reset(self) -> None:
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.sequence_started_at = None

    def update(self, reading: Optional[Dict[str, Any]], now: float, config: Dict[str, Any]) -> Tuple[bool, str]:
        if reading is None:
            if self.last_seen_at is not None and now - self.last_seen_at > config["open_lost_grace_sec"]:
                self.reset()
            return False, self.state

        self.last_seen_at = now
        if self.state == self.IDLE:
            self.open_streak = self.open_streak + 1 if reading["is_open"] else 0
            if self.open_streak >= config["open_hold_frames"]:
                self.state = self.WAITING_CLOSE
                self.sequence_started_at = now
                self.close_streak = 0
            return False, self.state

        if self.sequence_started_at is None or now - self.sequence_started_at > config["sequence_max_duration_sec"]:
            self.reset()
            return False, self.state

        if self.state == self.WAITING_CLOSE:
            # This is the group's complete-fist rule: all selected fingers
            # curled below their MCPs and the thumb tucked near the palm.
            # Requiring consecutive readings is the one conservative
            # refinement: jitter cannot add up to a false confirmation.
            if reading["is_closed"] and not reading["is_open"]:
                self.close_streak += 1
            else:
                self.close_streak = 0
            if self.close_streak >= config["close_hold_frames"]:
                self.reset()
                return True, "CONFIRMED"
        return False, self.state


@dataclass
class HandTrack:
    track_id: int
    state_machine: SignalStateMachine = field(default_factory=SignalStateMachine)
    last_pos: Optional[Tuple[float, float]] = None
    last_seen_at: Optional[float] = None
    last_trigger_at: float = 0.0


class MultiHandTracker:
    """Track hands only by their normalized 2-D wrist location for continuity."""

    def __init__(self) -> None:
        self.tracks: Dict[int, HandTrack] = {}
        self._next_track_id = 1

    def clear_sequence_state(self) -> None:
        for track in self.tracks.values():
            track.state_machine.reset()

    def update(
        self,
        screen_hands: Sequence[Sequence[Any]],
        world_hands: Sequence[Sequence[Any]],
        now: float,
        config: Dict[str, Any],
        detection_source: str = "VIDEO",
    ) -> List[Dict[str, Any]]:
        positions = [
            (float(hand[0].x), float(hand[0].y)) if hand else (float("nan"), float("nan"))
            for hand in screen_hands
        ]
        pairs: List[Tuple[float, int, int]] = []
        for hand_index, position in enumerate(positions):
            if not all(math.isfinite(component) for component in position):
                continue
            for track_id, track in self.tracks.items():
                if track.last_pos is None:
                    continue
                distance = math.hypot(position[0] - track.last_pos[0], position[1] - track.last_pos[1])
                if distance <= config["hand_match_max_dist"]:
                    pairs.append((distance, hand_index, track_id))

        assignments: Dict[int, int] = {}
        assigned_tracks = set()
        for _distance, hand_index, track_id in sorted(pairs):
            if hand_index not in assignments and track_id not in assigned_tracks:
                assignments[hand_index] = track_id
                assigned_tracks.add(track_id)

        for hand_index in range(len(screen_hands)):
            if hand_index not in assignments:
                track_id = self._next_track_id
                self._next_track_id += 1
                self.tracks[track_id] = HandTrack(track_id=track_id)
                assignments[hand_index] = track_id

        seen_track_ids = set()
        outputs: List[Dict[str, Any]] = []
        for hand_index, screen_hand in enumerate(screen_hands):
            track_id = assignments[hand_index]
            track = self.tracks[track_id]
            position = positions[hand_index]
            if all(math.isfinite(component) for component in position):
                track.last_pos = position
            track.last_seen_at = now
            seen_track_ids.add(track_id)
            world_hand = world_hands[hand_index] if hand_index < len(world_hands) else None
            reading = combine_hand_readings(screen_hand, world_hand, config)
            confirmed, state = track.state_machine.update(reading, now, config)
            outputs.append({
                "track": track,
                "track_id": track_id,
                "screen_landmarks": screen_hand,
                "world_landmarks_available": world_hand is not None,
                "detection_source": detection_source,
                "reading": reading,
                "confirmed": confirmed,
                "state": state,
            })

        for track_id, track in list(self.tracks.items()):
            if track_id in seen_track_ids:
                continue
            track.state_machine.update(None, now, config)
            if track.last_seen_at is not None and now - track.last_seen_at > config["hand_track_stale_sec"]:
                del self.tracks[track_id]
        return outputs


@dataclass
class PersonBox:
    track_id: str
    x: float
    y: float
    width: float
    height: float
    score: float
    updated_at: float

    def center(self) -> Tuple[float, float]:
        return self.x + self.width / 2.0, self.y + self.height / 2.0

    def to_json(self) -> Dict[str, Any]:
        return {
            "transient_track_id": self.track_id,
            "box": {
                "x": round(self.x, 4),
                "y": round(self.y, 4),
                "width": round(self.width, 4),
                "height": round(self.height, 4),
            },
            "score": round(self.score, 3),
        }


def _intersection_over_union(first: PersonBox, second: PersonBox) -> float:
    left = max(first.x, second.x)
    top = max(first.y, second.y)
    right = min(first.x + first.width, second.x + second.width)
    bottom = min(first.y + first.height, second.y + second.height)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    if intersection <= 0.0:
        return 0.0
    union = first.width * first.height + second.width * second.height - intersection
    return intersection / union if union > 0.0 else 0.0


def _nms_people(candidates: List[PersonBox], threshold: float = 0.45) -> List[PersonBox]:
    kept: List[PersonBox] = []
    for candidate in sorted(candidates, key=lambda person: person.score, reverse=True):
        if all(_intersection_over_union(candidate, existing) < threshold for existing in kept):
            kept.append(candidate)
    return kept


class PeopleDetector:
    """Low-rate, face-validated occupancy counter using bundled OpenCV assets.

    HOG alone is too eager for this demo (for example, a bottle can resemble a
    standing person).  A candidate must contain a conservative frontal-face
    detection.  When HOG misses a clearly visible frontal face, a deliberately
    broad face-derived occupancy box prevents the actual person from becoming
    an uncounted false negative.  Tracks remain camera-local and transient.
    """

    _LABEL = "opencv_hog_face_validated_with_haar_face_fallback"
    _UNAVAILABLE_LABEL = "opencv_haar_face_validation_unavailable"

    def __init__(self) -> None:
        self._hog = cv2.HOGDescriptor()
        self._hog.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())
        cascade_dir = getattr(getattr(cv2, "data", None), "haarcascades", "")
        # alt2 retains a frontal face in the project's dim webcam screenshot
        # where OpenCV's older default cascade misses it after downscaling.
        # Fall back to the default cascade for minimal OpenCV installations.
        cascade_path = None
        if cascade_dir:
            preferred = Path(cascade_dir) / "haarcascade_frontalface_alt2.xml"
            fallback = Path(cascade_dir) / "haarcascade_frontalface_default.xml"
            cascade_path = preferred if preferred.is_file() else fallback
        self._face_cascade: Optional[Any] = None
        if cascade_path is not None and cascade_path.is_file():
            cascade = cv2.CascadeClassifier(str(cascade_path))
            if not cascade.empty():
                self._face_cascade = cascade
        self._detector_label = self._LABEL if self._face_cascade is not None else self._UNAVAILABLE_LABEL
        if self._face_cascade is None:
            print("[PEOPLE] Bundled Haar frontal-face cascade unavailable; occupancy will fail closed.")
        self._queue: "queue.Queue[Tuple[Any, float, int]]" = queue.Queue(maxsize=1)
        self._lock = threading.RLock()
        self._tracks: Dict[str, PersonBox] = {}
        self._latest: List[PersonBox] = []
        self._latest_at = 0.0
        self._next_track_id = 1
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="senyalert-people-face-validated", daemon=True)
        self._thread.start()

    def submit(self, frame: Any, now: float, max_width: int) -> None:
        task = (frame.copy(), now, max_width)
        try:
            self._queue.put_nowait(task)
        except queue.Full:
            try:
                self._queue.get_nowait()
            except queue.Empty:
                pass
            try:
                self._queue.put_nowait(task)
            except queue.Full:
                pass

    def snapshot(self, now: float, stale_after: float) -> Dict[str, Any]:
        with self._lock:
            people = [person.to_json() for person in self._latest]
            updated_at = self._latest_at
        return {
            "count": len(people),
            "detector": self._detector_label,
            "enabled": True,
            "updated_at_ms": int(updated_at * 1000) if updated_at else None,
            "stale": not bool(updated_at) or now - updated_at > stale_after,
            "transient_tracks": people,
        }

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=1.5)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                frame, observed_at, max_width = self._queue.get(timeout=0.25)
            except queue.Empty:
                continue
            try:
                people = self._detect(frame, observed_at, max_width)
                with self._lock:
                    self._latest = people
                    self._latest_at = observed_at
            except Exception as exc:
                print(f"[PEOPLE] Face-validated occupancy detection failed: {exc}")

    @staticmethod
    def _normalized_box(
        x: float, y: float, box_width: float, box_height: float,
        frame_width: int, frame_height: int, scale: float, score: float, observed_at: float,
    ) -> Optional[PersonBox]:
        left = max(0.0, x * scale / frame_width)
        top = max(0.0, y * scale / frame_height)
        right = min(1.0, (x + box_width) * scale / frame_width)
        bottom = min(1.0, (y + box_height) * scale / frame_height)
        if right <= left or bottom <= top:
            return None
        return PersonBox(
            track_id="",
            x=left,
            y=top,
            width=right - left,
            height=bottom - top,
            score=float(score),
            updated_at=observed_at,
        )

    @staticmethod
    def _face_validates_hog(face: Tuple[int, int, int, int], rectangle: Tuple[int, int, int, int]) -> bool:
        face_x, face_y, face_width, _face_height = face
        x, y, box_width, box_height = rectangle
        if box_width <= 0 or box_height <= 0:
            return False
        face_center_x = face_x + face_width / 2.0
        face_width_ratio = face_width / float(box_width)
        return (
            x - 0.12 * box_width <= face_center_x <= x + 1.12 * box_width
            and y - 0.08 * box_height <= face_y <= y + 0.55 * box_height
            and 0.06 <= face_width_ratio <= 0.70
        )

    @staticmethod
    def _face_derived_box(face: Tuple[int, int, int, int], frame_width: int, frame_height: int) -> Tuple[float, float, float, float]:
        """Estimate a broad upper-body box from a visible face, never identity."""
        x, y, face_width, face_height = face
        box_width = 3.0 * face_width
        box_height = 6.0 * face_height
        return (
            max(0.0, x + face_width / 2.0 - box_width / 2.0),
            max(0.0, y - 0.25 * face_height),
            min(box_width, float(frame_width)),
            min(box_height, float(frame_height)),
        )

    def _detect(self, frame: Any, observed_at: float, max_width: int) -> List[PersonBox]:
        height, width = frame.shape[:2]
        if width <= 0 or height <= 0:
            return []
        scale = 1.0
        detection_frame = frame
        if width > max_width:
            scale = width / float(max_width)
            detection_frame = cv2.resize(frame, (max_width, max(1, round(height / scale))))
        if self._face_cascade is None:
            return []

        detection_height, detection_width = detection_frame.shape[:2]
        gray = cv2.cvtColor(detection_frame, cv2.COLOR_BGR2GRAY)
        minimum_face = max(24, round(min(detection_width, detection_height) * 0.04))
        raw_faces = self._face_cascade.detectMultiScale(
            gray,
            scaleFactor=1.05,
            minNeighbors=5,
            minSize=(minimum_face, minimum_face),
        )
        faces = [tuple(int(value) for value in face) for face in raw_faces]
        # Without a frontal face there is no evidence that can validate an HOG
        # rectangle, so skip that relatively expensive detector and fail closed.
        if not faces:
            return self._assign_transient_tracks([], observed_at)
        rectangles, weights = self._hog.detectMultiScale(
            detection_frame,
            winStride=(8, 8),
            padding=(8, 8),
            scale=1.05,
        )
        candidates: List[PersonBox] = []
        face_validated_indices = set()
        for index, rectangle in enumerate(rectangles):
            x, y, box_width, box_height = rectangle
            score = float(weights[index]) if index < len(weights) else 0.0
            rectangle_tuple = (int(x), int(y), int(box_width), int(box_height))
            matching_faces = [
                face_index for face_index, face in enumerate(faces)
                if self._face_validates_hog(face, rectangle_tuple)
            ]
            if not matching_faces:
                continue
            face_validated_indices.update(matching_faces)
            candidate = self._normalized_box(x, y, box_width, box_height, width, height, scale, score, observed_at)
            if candidate is not None:
                candidates.append(candidate)

        # A visible frontal face is stronger person evidence than a HOG-only
        # rectangle.  Add one conservative body-sized box only when no HOG box
        # already validated against that face.
        for face_index, face in enumerate(faces):
            if face_index in face_validated_indices:
                continue
            face_box = self._face_derived_box(face, detection_width, detection_height)
            candidate = self._normalized_box(*face_box, width, height, scale, 1.0, observed_at)
            if candidate is not None:
                candidates.append(candidate)
        return self._assign_transient_tracks(_nms_people(candidates), observed_at)

    def _assign_transient_tracks(self, detections: List[PersonBox], now: float) -> List[PersonBox]:
        with self._lock:
            active = {
                track_id: person
                for track_id, person in self._tracks.items()
                if now - person.updated_at <= 3.0
            }
            assigned: set[str] = set()
            for detection in detections:
                best_id: Optional[str] = None
                best_score = -1.0
                for track_id, prior in active.items():
                    if track_id in assigned:
                        continue
                    center_distance = math.hypot(detection.center()[0] - prior.center()[0], detection.center()[1] - prior.center()[1])
                    score = _intersection_over_union(detection, prior)
                    if score >= 0.20 or center_distance <= 0.12:
                        combined = score - center_distance * 0.10
                        if combined > best_score:
                            best_score = combined
                            best_id = track_id
                if best_id is None:
                    best_id = f"transient-person-{self._next_track_id}"
                    self._next_track_id += 1
                detection.track_id = best_id
                assigned.add(best_id)
                active[best_id] = detection
            self._tracks = active
            return detections


def associate_hand_to_person(screen_landmarks: Sequence[Any], people: Dict[str, Any]) -> Dict[str, Any]:
    """Associate a wrist with a current face-validated occupancy box, never identity."""
    if not screen_landmarks:
        return {"associated": False, "transient_track_id": None, "association": "NO_WRIST"}
    wrist_x = float(screen_landmarks[0].x)
    wrist_y = float(screen_landmarks[0].y)
    candidates = people.get("transient_tracks", [])
    containing = []
    for person in candidates:
        box = person["box"]
        if box["x"] <= wrist_x <= box["x"] + box["width"] and box["y"] <= wrist_y <= box["y"] + box["height"]:
            containing.append(person)
    if containing:
        selected = min(
            containing,
            key=lambda person: math.hypot(
                wrist_x - (person["box"]["x"] + person["box"]["width"] / 2.0),
                wrist_y - (person["box"]["y"] + person["box"]["height"] / 2.0),
            ),
        )
        return {
            "associated": True,
            "transient_track_id": selected["transient_track_id"],
            "association": "WRIST_INSIDE_OCCUPANCY_BOX",
            "bounds": selected["box"],
            "identity_note": "Transient camera track only; not a person identity.",
        }
    return {
        "associated": False,
        "transient_track_id": None,
        "association": "NO_CONTAINING_OCCUPANCY_BOX",
        "bounds": None,
        "identity_note": "No identity inference is performed.",
    }


def hand_landmark_bounds(screen_landmarks: Sequence[Any]) -> Dict[str, float]:
    """A normalized landmark box used only when no occupancy box contains the wrist."""
    xs = [float(landmark.x) for landmark in screen_landmarks]
    ys = [float(landmark.y) for landmark in screen_landmarks]
    left, right = max(0.0, min(xs)), min(1.0, max(xs))
    top, bottom = max(0.0, min(ys)), min(1.0, max(ys))
    return {
        "x": round(left, 4),
        "y": round(top, 4),
        "width": round(max(0.0, right - left), 4),
        "height": round(max(0.0, bottom - top), 4),
    }


def alert_context(people: Dict[str, Any], config: Dict[str, Any]) -> Dict[str, Any]:
    count = int(people["count"])
    threshold = int(config["quiet_at_or_above_people"])
    people_detection_enabled = bool(config["people_detection_enabled"])
    audible_alerts_enabled = bool(config["audible_alerts_enabled"])
    # An asynchronous face-validated count is not proof that a room is empty. Keep the
    # preview/payload aligned with the dashboard's conservative policy while
    # a fresh occupancy result is still pending.
    occupancy_stale = people_detection_enabled and bool(people["stale"])
    quiet = (
        not audible_alerts_enabled
        or occupancy_stale
        or (people_detection_enabled and count >= threshold)
    )
    reason = (
        "AUDIBLE_ALERTS_DISABLED" if not audible_alerts_enabled
        else "OCCUPANCY_PENDING_OR_STALE" if occupancy_stale
        else "OCCUPANCY_AT_OR_ABOVE_QUIET_THRESHOLD" if quiet
        else "OCCUPANCY_BELOW_QUIET_THRESHOLD"
    )
    return {
        "triage_context": "HIGH_TRAFFIC" if quiet else "LOW_TRAFFIC",
        "alert_mode": "QUIET" if quiet else "LOUD",
        "quiet_people_threshold": threshold,
        "quiet_at_or_above_people": threshold,
        "people_detection_enabled": people_detection_enabled,
        "audible_alerts_enabled": audible_alerts_enabled,
        "alert_reason": reason,
        "people_count": count,
        "people_count_stale": occupancy_stale,
        "occupancy_status": "DISABLED" if not people_detection_enabled else "STALE" if occupancy_stale else "CURRENT",
    }


@dataclass
class ActiveRecording:
    event_token: str
    video_path: str
    writer: Any
    started_at: float
    end_at: float
    last_sample_at: float
    size: Tuple[int, int]
    fps: float
    pre_event_seconds_recorded: float
    metadata: Dict[str, Any]


class EvidenceRecorder:
    """Time-based pre-event ring buffer and concurrent post-event MP4 writers."""

    def __init__(self, complete_callback: Callable[[ActiveRecording, str], None]) -> None:
        self._complete_callback = complete_callback
        self._buffer: Deque[Tuple[float, Any]] = deque()
        self._last_buffer_sample_at = 0.0
        self._active: Dict[str, ActiveRecording] = {}
        self._lock = threading.RLock()

    @staticmethod
    def _sample_interval(config: Dict[str, Any]) -> float:
        return 1.0 / max(1.0, float(config["recording_fps"]))

    def ingest(self, frame: Any, now: float, config: Dict[str, Any]) -> None:
        interval = self._sample_interval(config)
        with self._lock:
            if not self._buffer or now - self._last_buffer_sample_at >= interval:
                self._buffer.append((now, frame.copy()))
                self._last_buffer_sample_at = now
            cutoff = now - float(config["pre_event_sec"])
            while self._buffer and self._buffer[0][0] < cutoff:
                self._buffer.popleft()

            completed: List[Tuple[ActiveRecording, str]] = []
            for event_token, recording in list(self._active.items()):
                if now - recording.last_sample_at >= 1.0 / recording.fps:
                    output = frame
                    if (frame.shape[1], frame.shape[0]) != recording.size:
                        output = cv2.resize(frame, recording.size)
                    recording.writer.write(output)
                    recording.last_sample_at = now
                if now >= recording.end_at:
                    recording.writer.release()
                    del self._active[event_token]
                    completed.append((recording, "READY"))
        for recording, status in completed:
            self._complete_callback(recording, status)

    def begin(
        self,
        event_token: str,
        frame: Any,
        now: float,
        config: Dict[str, Any],
        metadata: Dict[str, Any],
    ) -> Tuple[str, bool, Optional[str]]:
        # Keep the project's existing incident_<timestamp>.mp4 convention while
        # adding a collision-resistant event token for two simultaneous hands.
        video_path = str(PROJECT_ROOT / f"incident_{event_token}.mp4")
        width, height = frame.shape[1], frame.shape[0]
        fps = float(config["recording_fps"])
        writer = cv2.VideoWriter(video_path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
        if not writer.isOpened():
            try:
                writer.release()
            except Exception:
                pass
            return video_path, False, "OpenCV could not open the MP4 writer"

        with self._lock:
            # Ensure the trigger frame itself is part of the evidence, even if
            # the normal sample interval did not fall on this camera frame.
            if not self._buffer or self._buffer[-1][0] < now:
                self._buffer.append((now, frame.copy()))
                self._last_buffer_sample_at = now
            cutoff = now - float(config["pre_event_sec"])
            while self._buffer and self._buffer[0][0] < cutoff:
                self._buffer.popleft()
            pre_event_seconds_recorded = max(0.0, now - self._buffer[0][0]) if self._buffer else 0.0
            for _timestamp, buffered_frame in self._buffer:
                output = buffered_frame
                if (buffered_frame.shape[1], buffered_frame.shape[0]) != (width, height):
                    output = cv2.resize(buffered_frame, (width, height))
                writer.write(output)
            self._active[event_token] = ActiveRecording(
                event_token=event_token,
                video_path=video_path,
                writer=writer,
                started_at=now,
                end_at=now + float(config["post_event_sec"]),
                last_sample_at=now,
                size=(width, height),
                fps=fps,
                pre_event_seconds_recorded=pre_event_seconds_recorded,
                metadata=copy.deepcopy(metadata),
            )
        return video_path, True, None

    def close_all(self, status: str = "PARTIAL") -> None:
        with self._lock:
            recordings = list(self._active.values())
            self._active.clear()
        for recording in recordings:
            try:
                recording.writer.release()
            finally:
                self._complete_callback(recording, status)


def save_snapshot(frame: Any, event_token: str) -> Tuple[Optional[str], Optional[str]]:
    """Persist a JPEG snapshot without any automatic cleanup or retention policy."""
    try:
        SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
        snapshot_path = SNAPSHOT_DIR / f"snapshot_{event_token}.jpg"
        if cv2.imwrite(str(snapshot_path), frame):
            return str(snapshot_path.resolve()), None
        return None, "OpenCV could not write the JPEG snapshot"
    except OSError as exc:
        return None, f"Snapshot write failed: {exc}"


def resolve_model_path(configured_path: str) -> str:
    candidate = Path(configured_path)
    candidates = [candidate, PROJECT_ROOT / candidate, SCRIPT_DIR / candidate, Path.cwd() / candidate]
    for path in candidates:
        if path.is_file():
            return str(path.resolve())
    raise FileNotFoundError(f"HandLandmarker model not found: {configured_path}")


def create_hand_detector(config: Dict[str, Any], running_mode: Any = vision.RunningMode.VIDEO) -> Any:
    base_options = python.BaseOptions(model_asset_path=resolve_model_path(config["model_asset_path"]))
    options = vision.HandLandmarkerOptions(
        base_options=base_options,
        running_mode=running_mode,
        num_hands=int(config["max_hands"]),
        min_hand_detection_confidence=float(config["min_hand_detection_confidence"]),
        min_hand_presence_confidence=float(config["min_hand_presence_confidence"]),
        min_tracking_confidence=float(config["min_hand_tracking_confidence"]),
    )
    return vision.HandLandmarker.create_from_options(options)


class CameraSourceError(RuntimeError):
    """A validated source could not be opened by its local capture adapter."""


class AdbScreenCapture:
    """OpenCV-like capture adapter for a USB-authorized Android screen.

    This intentionally captures the foreground screen only. For a live phone
    demo, the operator opens Android's stock Camera application on the phone;
    no Android app is installed, launched, or network ADB connection created
    by SenyAlert. The device serial must already be visible to ``adb devices``.
    """

    _DEFAULT_TIMEOUT_SECONDS = 5.0

    def __init__(self, serial: str, timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS) -> None:
        self.serial = serial
        self.timeout_seconds = timeout_seconds
        self.adb_path = self._resolve_adb()
        self._process_lock = threading.RLock()
        self._active_process: Optional[Any] = None
        self._opened = False
        self._released = False
        self._last_error = ""
        self._last_width = 0
        self._last_height = 0
        if self.adb_path is None:
            raise CameraSourceError(
                "ADB was not found on PATH or under LOCALAPPDATA/Android/Sdk/platform-tools"
            )
        self._probe_device()

    @staticmethod
    def _resolve_adb() -> Optional[str]:
        # Prefer the user's PATH, then the standard Android SDK location on
        # Windows. No user-specific path is hard-coded.
        path_adb = shutil.which("adb") or shutil.which("adb.exe")
        if path_adb:
            return path_adb
        local_app_data = os.environ.get("LOCALAPPDATA")
        if not local_app_data:
            return None
        sdk_adb = Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        return str(sdk_adb) if sdk_adb.is_file() else None

    @staticmethod
    def _compact_error(output: bytes) -> str:
        text = output.decode("utf-8", errors="replace")
        return " ".join(text.split())[:240]

    def _invoke(self, arguments: Sequence[str], timeout_seconds: float) -> Tuple[int, bytes, bytes]:
        process: Optional[Any] = None
        try:
            with self._process_lock:
                if self._released:
                    raise CameraSourceError("ADB screen capture was released")
                creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
                process = subprocess.Popen(
                    [self.adb_path, "-s", self.serial, *arguments],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    creationflags=creation_flags,
                )
                self._active_process = process
            stdout, stderr = process.communicate(timeout=timeout_seconds)
            return process.returncode, stdout, stderr
        except subprocess.TimeoutExpired:
            try:
                process.kill()
                process.communicate(timeout=1.0)
            except (OSError, subprocess.TimeoutExpired):
                pass
            raise CameraSourceError(
                f"ADB command timed out after {timeout_seconds:.1f}s for device {self.serial}"
            )
        except OSError as exc:
            raise CameraSourceError(f"Could not run ADB for device {self.serial}: {exc}") from exc
        finally:
            with self._process_lock:
                if process is not None and self._active_process is process:
                    self._active_process = None

    def _probe_device(self) -> None:
        try:
            return_code, stdout, stderr = self._invoke(("get-state",), min(3.0, self.timeout_seconds))
        except CameraSourceError as exc:
            self._last_error = str(exc)
            raise
        state = stdout.decode("utf-8", errors="replace").strip().lower()
        if return_code != 0 or state != "device":
            detail = self._compact_error(stderr) or state or "device is unavailable"
            self._last_error = f"ADB device {self.serial} is not ready: {detail}"
            raise CameraSourceError(self._last_error)
        self._opened = True
        self._last_error = ""

    @property
    def last_error(self) -> str:
        with self._process_lock:
            return self._last_error

    def isOpened(self) -> bool:
        with self._process_lock:
            return self._opened and not self._released

    def read(self) -> Tuple[bool, Optional[Any]]:
        if not self.isOpened():
            return False, None
        try:
            return_code, png_bytes, stderr = self._invoke(
                ("exec-out", "screencap", "-p"), self.timeout_seconds
            )
        except CameraSourceError as exc:
            with self._process_lock:
                self._opened = False
                self._last_error = str(exc)
            return False, None
        if return_code != 0:
            detail = self._compact_error(stderr) or "ADB screencap returned an error"
            with self._process_lock:
                self._opened = False
                self._last_error = f"ADB screen capture failed for {self.serial}: {detail}"
            return False, None
        if not png_bytes:
            with self._process_lock:
                self._opened = False
                self._last_error = f"ADB screen capture returned no image for {self.serial}"
            return False, None
        image = cv2.imdecode(np.frombuffer(png_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            with self._process_lock:
                self._opened = False
                self._last_error = f"ADB screen capture returned an invalid PNG for {self.serial}"
            return False, None
        with self._process_lock:
            self._last_height, self._last_width = image.shape[:2]
            self._last_error = ""
        return True, image

    def get(self, property_id: int) -> float:
        with self._process_lock:
            if property_id == cv2.CAP_PROP_FRAME_WIDTH:
                return float(self._last_width)
            if property_id == cv2.CAP_PROP_FRAME_HEIGHT:
                return float(self._last_height)
        return 0.0

    def release(self) -> None:
        with self._process_lock:
            self._released = True
            self._opened = False
            process = self._active_process
        if process is not None and process.poll() is None:
            try:
                process.terminate()
            except OSError:
                pass


class LatestFrameCapture:
    """Continuously drain a network capture and expose only its newest frame.

    OpenCV may buffer several HTTP MJPEG or RTSP frames while a synchronous
    consumer is busy running MediaPipe.  Returning every one of those frames
    makes the engine visibly lag behind the phone even when the transport is
    fast.  This adapter owns a dedicated reader thread and a one-frame slot:
    anything superseded before the worker asks for it is deliberately dropped.

    It is intentionally used only for network streams.  Local/UVC cameras keep
    their normal OpenCV path, and ADB screen capture already has its own
    request-per-frame implementation.
    """

    _FRAME_WAIT_SECONDS = 3.0

    def __init__(self, capture: Any, source_label: str) -> None:
        self._capture = capture
        self._source_label = source_label
        self._condition = threading.Condition(threading.RLock())
        self._released = False
        self._reader_finished = False
        self._latest_frame: Optional[Any] = None
        self._latest_sequence = 0
        self._delivered_sequence = 0
        self._last_error = ""
        self._reader = threading.Thread(
            target=self._reader_loop,
            name="senyalert-network-frame-reader",
            daemon=True,
        )
        self._reader.start()

    @property
    def last_error(self) -> str:
        with self._condition:
            return self._last_error

    def isOpened(self) -> bool:
        with self._condition:
            return not self._released and bool(self._capture.isOpened())

    def _reader_loop(self) -> None:
        while True:
            with self._condition:
                if self._released:
                    self._reader_finished = True
                    self._condition.notify_all()
                    return
            try:
                ok, frame = self._capture.read()
            except Exception as exc:
                with self._condition:
                    if not self._released:
                        self._last_error = f"Network stream read failed for {self._source_label}: {exc}"
                    self._reader_finished = True
                    self._condition.notify_all()
                return

            with self._condition:
                if self._released:
                    self._reader_finished = True
                    self._condition.notify_all()
                    return
                if not ok or frame is None:
                    self._last_error = f"Network stream ended or produced no frame for {self._source_label}"
                    self._reader_finished = True
                    self._condition.notify_all()
                    return
                # Assignment replaces the previous frame atomically while the
                # lock is held.  A stalled detector therefore cannot make this
                # queue grow beyond one decoded frame.
                self._latest_frame = frame
                self._latest_sequence += 1
                self._last_error = ""
                self._condition.notify_all()

    def read(self) -> Tuple[bool, Optional[Any]]:
        deadline = time.monotonic() + self._FRAME_WAIT_SECONDS
        with self._condition:
            while (
                not self._released
                and not self._reader_finished
                and self._latest_sequence <= self._delivered_sequence
            ):
                remaining = deadline - time.monotonic()
                if remaining <= 0.0:
                    self._last_error = f"Timed out waiting for a new network frame from {self._source_label}"
                    return False, None
                self._condition.wait(remaining)

            if self._released:
                return False, None
            if self._latest_frame is None or self._latest_sequence <= self._delivered_sequence:
                if not self._last_error:
                    self._last_error = f"Network stream stopped before a new frame arrived from {self._source_label}"
                return False, None

            self._delivered_sequence = self._latest_sequence
            # The reader immediately asks OpenCV for another frame after it
            # leaves the lock.  A defensive copy makes the consumer independent
            # of an OpenCV backend that reuses its decode buffer.
            return True, self._latest_frame.copy()

    def get(self, property_id: int) -> float:
        try:
            return float(self._capture.get(property_id))
        except Exception:
            return 0.0

    def release(self) -> None:
        with self._condition:
            if self._released:
                return
            self._released = True
            self._condition.notify_all()
        try:
            # Releasing from this thread is the supported way to unblock a
            # blocking VideoCapture.read() when a phone stream disappears.
            self._capture.release()
        except Exception:
            pass
        if threading.current_thread() is not self._reader:
            self._reader.join(timeout=0.35)


def _configure_network_capture_for_low_latency(capture: Any) -> None:
    """Ask supporting OpenCV backends for a one-frame decode buffer.

    CAP_PROP_BUFFERSIZE is backend-dependent (and may return False on some
    Windows FFmpeg builds), so the latest-frame reader remains the correctness
    mechanism.  This best-effort setting can still eliminate an extra native
    buffer where the backend supports it.
    """
    buffer_property = getattr(cv2, "CAP_PROP_BUFFERSIZE", None)
    if buffer_property is None:
        return
    try:
        capture.set(buffer_property, 1)
    except cv2.error:
        pass
    except Exception:
        pass


def open_camera(source: Any, config: Dict[str, Any]) -> Any:
    if isinstance(source, str) and source.startswith("adb://"):
        serial = source[len("adb://"):]
        return AdbScreenCapture(serial)
    capture_source = source
    if isinstance(source, str) and source.startswith("uvc://"):
        # Windows has already exposed this physical device as a capture index.
        # This is raw UVC/USB input, unlike adb:// which is screen capture.
        try:
            capture_source = int(source[len("uvc://"):])
        except ValueError as exc:
            raise CameraSourceError("UVC source must use uvc://<webcam-index>") from exc
    network_source = is_network_stream_source(capture_source)
    capture = cv2.VideoCapture(capture_source)
    if isinstance(capture_source, int):
        width, height = config["resolution"]
        capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    if not capture.isOpened():
        capture.release()
        return None
    if network_source:
        _configure_network_capture_for_low_latency(capture)
        return LatestFrameCapture(capture, display_camera_source(capture_source))
    return capture


def draw_preview(
    frame: Any,
    hand_results: Sequence[Dict[str, Any]],
    people: Dict[str, Any],
    context: Optional[Dict[str, Any]],
    config: Dict[str, Any],
    paused: bool,
    hand_status: str = "",
) -> Any:
    """Render sharp HUD graphics over a separately downscaled video layer.

    ``preview_resolution`` controls the size of the *video* layer only.  The
    HUD canvas remains at the native camera size (capped at Full HD) before
    the labels and overlays are drawn.  This prevents text from becoming
    blurry just because a phone feed is downscaled for transport.
    """
    video_target_width, video_target_height = config["preview_resolution"]
    source_height, source_width = frame.shape[:2]

    # Downscale the camera pixels first, never the subsequently rendered HUD.
    video_scale = min(
        1.0,
        video_target_width / max(1, source_width),
        video_target_height / max(1, source_height),
    )
    video_width = max(1, round(source_width * video_scale))
    video_height = max(1, round(source_height * video_scale))
    if (video_width, video_height) == (source_width, source_height):
        video_layer = frame
    else:
        video_layer = cv2.resize(frame, (video_width, video_height), interpolation=cv2.INTER_AREA)

    # A native-sized canvas keeps the ingestion-engine HUD crisp.  Capping
    # this avoids a 4K stream turning the local OpenCV display into a costly
    # full-resolution compositor.
    hud_scale_down = min(1.0, 1920 / max(1, source_width), 1080 / max(1, source_height))
    width = max(1, round(source_width * hud_scale_down))
    height = max(1, round(source_height * hud_scale_down))
    preview = np.full((height, width, 3), (18, 15, 11), dtype=np.uint8)
    content_scale = min(width / max(1, video_width), height / max(1, video_height))
    content_width = max(1, round(video_width * content_scale))
    content_height = max(1, round(video_height * content_scale))
    content_left = (width - content_width) // 2
    content_top = (height - content_height) // 2
    preview[content_top:content_top + content_height, content_left:content_left + content_width] = cv2.resize(
        video_layer,
        (content_width, content_height),
        interpolation=cv2.INTER_LINEAR if content_scale > 1.0 else cv2.INTER_AREA,
    )

    # Scale layout measurements with the high-resolution canvas.  Font pixels
    # are now generated at their final local-display size, rather than being
    # enlarged after a low-resolution overlay has already been rasterized.
    ui_scale = max(0.55, min(width / 1280.0, height / 720.0))

    def px(value: float) -> int:
        return max(1, round(value * ui_scale))

    navy = (35, 30, 20)
    blue = (242, 157, 42)
    cyan = (255, 210, 70)
    white = (246, 249, 252)
    muted = (196, 205, 214)
    green = (88, 214, 82)
    amber = (38, 182, 255)
    red = (64, 75, 232)

    def glass_rect(left: int, top: int, right: int, bottom: int, color: Tuple[int, int, int], opacity: float) -> None:
        left = max(0, min(width - 1, left))
        top = max(0, min(height - 1, top))
        right = max(left + 1, min(width, right))
        bottom = max(top + 1, min(height, bottom))
        region = preview[top:bottom, left:right]
        tint = np.full_like(region, color)
        cv2.addWeighted(tint, opacity, region, 1.0 - opacity, 0, region)

    def text(value: str, origin: Tuple[int, int], scale: float, color: Tuple[int, int, int], thickness: int = 1) -> None:
        cv2.putText(
            preview,
            value,
            origin,
            cv2.FONT_HERSHEY_SIMPLEX,
            scale * ui_scale,
            color,
            max(1, round(thickness * ui_scale)),
            cv2.LINE_AA,
        )

    def clipped(value: str, maximum: int) -> str:
        return value if len(value) <= maximum else value[: max(1, maximum - 1)] + "…"

    header_height = px(68)
    card_top = header_height + px(12)
    card_height = px(58)
    margin = px(16)
    gap = px(12)
    metric_count = 3 if context else 2
    card_width = max(px(122), (width - (margin * 2) - (gap * (metric_count - 1))) // metric_count)

    def metric_card(index: int, label: str, value: str, accent: Tuple[int, int, int]) -> None:
        left = margin + index * (card_width + gap)
        glass_rect(left, card_top, left + card_width, card_top + card_height, navy, 0.78)
        cv2.rectangle(preview, (left, card_top), (left + px(4), card_top + card_height), accent, cv2.FILLED)
        text(label, (left + px(14), card_top + px(20)), 0.34, muted, 1)
        text(clipped(value, 22), (left + px(14), card_top + px(45)), 0.56, white, 2)

    # The masthead is kept visually light so more of the live camera image
    # remains available for the people and gesture overlays.
    glass_rect(0, 0, width, header_height, navy, 0.84)
    online_color = red if paused else green
    cv2.circle(preview, (margin + px(7), px(31)), px(7), online_color, cv2.FILLED)
    text("SenyAlert", (margin + px(24), px(29)), 0.72, white, 2)
    text("VISION INGESTION", (margin + px(25), px(51)), 0.36, cyan, 1)
    camera_name = clipped(str(config.get("camera_id", "CAMERA")), 32)
    camera_x = max(width // 2, width - max(px(230), len(camera_name) * px(9)))
    text(camera_name, (camera_x, px(28)), 0.46, white, 1)
    text("PAUSED" if paused else "LIVE", (camera_x, px(50)), 0.36, online_color, 1)

    people_value = str(people["count"]) + (" pending" if people["stale"] else " seen")
    metric_card(0, "OCCUPANCY", people_value, cyan if not people["stale"] else amber)
    metric_card(1, "HANDS", f"{len(hand_results)} / {config['max_hands']}", blue)
    if context:
        alert_accent = amber if context["alert_mode"] == "QUIET" else red
        metric_card(2, "NEXT ALERT", context["alert_mode"], alert_accent)

    for person in people["transient_tracks"]:
        box = person["box"]
        x = content_left + int(box["x"] * content_width)
        y = content_top + int(box["y"] * content_height)
        box_width = int(box["width"] * content_width)
        box_height = int(box["height"] * content_height)
        cv2.rectangle(preview, (x, y), (x + box_width, y + box_height), cyan, px(2), cv2.LINE_AA)
        label = f"PERSON  {person['transient_track_id'].rsplit('-', 1)[-1]}"
        label_width = max(px(82), cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.36 * ui_scale, px(1))[0][0] + px(12))
        label_top = max(header_height + px(4), y - px(23))
        glass_rect(x, label_top, x + label_width, label_top + px(20), navy, 0.84)
        text(label, (x + px(6), label_top + px(15)), 0.36, cyan, 1)

    for result in hand_results:
        color = green if result["state"] in {SignalStateMachine.WAITING_CLOSE, "CONFIRMED"} else amber
        landmarks = result["screen_landmarks"]
        for first, second in (
            (0, 1), (1, 2), (2, 3), (3, 4),
            (0, 5), (5, 6), (6, 7), (7, 8),
            (0, 9), (9, 10), (10, 11), (11, 12),
            (0, 13), (13, 14), (14, 15), (15, 16),
            (0, 17), (17, 18), (18, 19), (19, 20),
        ):
            if first < len(landmarks) and second < len(landmarks):
                cv2.line(
                    preview,
                    (
                        content_left + int(landmarks[first].x * content_width),
                        content_top + int(landmarks[first].y * content_height),
                    ),
                    (
                        content_left + int(landmarks[second].x * content_width),
                        content_top + int(landmarks[second].y * content_height),
                    ),
                    color,
                    px(2),
                    cv2.LINE_AA,
                )
        for landmark in landmarks:
            cv2.circle(
                preview,
                (content_left + int(landmark.x * content_width), content_top + int(landmark.y * content_height)),
                px(3),
                color,
                cv2.FILLED,
            )
        wrist = landmarks[0]
        reading = result["reading"]
        hand_x = content_left + int(wrist.x * content_width)
        hand_y = content_top + int(wrist.y * content_height)
        label = f"HAND {result['track_id']}  {result['state']}"
        if reading is None:
            detail = "landmarks not scorable"
        else:
            detail = (
                f"open {int(reading['is_open'])} · closed {int(reading['is_closed'])}"
                f" · {result.get('detection_source', 'VIDEO')}"
            )
        label_top = max(card_top + card_height + px(8), hand_y - px(52))
        glass_rect(hand_x, label_top, min(width, hand_x + px(225)), label_top + px(43), navy, 0.82)
        text(label, (hand_x + px(8), label_top + px(17)), 0.38, color, 1)
        text(clipped(detail, 36), (hand_x + px(8), label_top + px(35)), 0.32, white, 1)

    footer_height = px(42)
    footer_top = height - footer_height
    glass_rect(0, footer_top, width, height, navy, 0.84)
    if paused:
        footer = "Detection paused — camera preview remains visible"
        footer_color = red
    elif not hand_results and hand_status:
        footer = hand_status
        footer_color = amber
    else:
        footer = "Hand tracking active · ESC closes the multi-camera preview"
        footer_color = green
    text(clipped(footer, 120), (margin, height - px(14)), 0.42, footer_color, 1)
    return preview


class CameraWorker:
    """One independently stateful camera pipeline managed by the multi-camera engine."""

    def __init__(self, camera: Dict[str, Any], socket: EngineSocketClient) -> None:
        self.camera = copy.deepcopy(camera)
        self.socket = socket
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self.run,
            name=f"senyalert-camera-{self.camera['camera_id']}",
            daemon=True,
        )
        self._preview_lock = threading.RLock()
        self._latest_preview: Optional[Any] = None
        self._last_preview_sent_at = 0.0
        self.tracker = MultiHandTracker()
        self.people_detector = PeopleDetector()
        self.recorder = EvidenceRecorder(self._on_media_complete)
        self.detector: Optional[Any] = None
        self.image_fallback_detector: Optional[Any] = None
        self.capture: Optional[Any] = None
        self._applied_detector_key: Optional[Tuple[Any, ...]] = None
        self._failed_detector_key: Optional[Tuple[Any, ...]] = None
        self._next_detector_retry_at = 0.0
        self._next_image_fallback_retry_at = 0.0
        self._applied_camera_input: Any = object()
        self._last_people_submission_frame = -1_000_000
        self._last_pause_state = False
        self._frame_count = 0
        self._last_video_timestamp_ms = -1
        self._last_hand_results: List[Dict[str, Any]] = []
        self._consecutive_empty_video_results = 0
        self._hand_status = "Waiting for MediaPipe hand landmarks"
        self._last_hand_debug: Dict[int, Tuple[Any, ...]] = {}
        self._actual_capture_dimensions: Tuple[int, int] = (0, 0)

    @property
    def camera_id(self) -> str:
        return str(self.camera["camera_id"])

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

        # VideoCapture.read() can block indefinitely while an RTSP/MJPEG
        # source disappears. Releasing from the controller thread asks OpenCV
        # to unblock that read so an obsolete worker cannot keep emitting
        # camera events after its source is removed.
        capture = self.capture
        if capture is not None:
            try:
                capture.release()
            except Exception:
                pass

    def join(self, timeout: float = 2.5) -> None:
        self._thread.join(timeout=timeout)

    def is_alive(self) -> bool:
        return self._thread.is_alive()

    def latest_preview(self) -> Optional[Any]:
        with self._preview_lock:
            return None if self._latest_preview is None else self._latest_preview.copy()

    def _effective_config(self, global_config: Dict[str, Any]) -> Dict[str, Any]:
        """Apply this worker's camera identity without sharing track state."""
        config = copy.deepcopy(global_config)
        for key in (
            "camera_source",
            "camera_id",
            "location",
            "camera_type",
            "aspect_ratio",
            "processing_scale",
            "resolution",
            "preview_resolution",
        ):
            if key in self.camera:
                config[key] = copy.deepcopy(self.camera[key])
        return config

    def _publish_preview(self, preview: Any, config: Dict[str, Any], now: float) -> None:
        with self._preview_lock:
            self._latest_preview = preview.copy()

        interval = 1.0 / max(0.2, float(config["dashboard_preview_fps"]))
        if now - self._last_preview_sent_at < interval:
            return
        # A compact JPEG lets Swing render the latest preview for every camera
        # without transferring a continuous full-resolution recording stream.
        source_height, source_width = preview.shape[:2]
        max_width = 480
        if source_width > max_width:
            scale = max_width / float(source_width)
            outbound = cv2.resize(preview, (max_width, max(1, round(source_height * scale))))
        else:
            outbound = preview
        encoded_ok, encoded = cv2.imencode(
            ".jpg", outbound, [int(cv2.IMWRITE_JPEG_QUALITY), 62]
        )
        if not encoded_ok:
            return
        sent = self.socket.send_volatile({
            "event": "CAMERA_PREVIEW",
            "cameraId": config["camera_id"],
            "camera_source": display_camera_source(config["camera_source"]),
            "location": config["location"],
            "timestamp": int(time.time() * 1000),
            "jpeg_base64": base64.b64encode(encoded.tobytes()).decode("ascii"),
        })
        if sent:
            self._last_preview_sent_at = now

    def _send_camera_status(
        self,
        status: str,
        config: Dict[str, Any],
        detail: Optional[str] = None,
        actual_dimensions: Optional[Tuple[int, int]] = None,
    ) -> None:
        configured_aspect = config.get("aspect_ratio", [16, 9])
        profile = profile_for_camera_type(config.get("camera_type"))
        payload = {
            # Keep camera validation/status inside the existing EngineStatus
            # protocol so the Java server does not mistake it for an incident.
            "event": "ENGINE_STATUS",
            "status": f"CAMERA_{status}",
            "camera_status": status,
            # Camera failure does not mean the WebSocket engine itself is
            # offline.  Keeping this true lets Swing present a connected
            # engine with a clear camera-specific error.
            "connected": True,
            "paused": bool(config["paused"]),
            "camera_source": display_camera_source(config["camera_source"]),
            "cameraId": config["camera_id"],
            "camera_type": config.get("camera_type", "laptop_webcam"),
            "camera_type_label": profile["label"],
            "configured_aspect_ratio": configured_aspect,
            "input_kind": camera_input_kind(config["camera_source"]),
            "timestamp": int(time.time()),
        }
        dimensions = actual_dimensions or self._actual_capture_dimensions
        if dimensions[0] > 0 and dimensions[1] > 0:
            payload["capture_width"] = dimensions[0]
            payload["capture_height"] = dimensions[1]
            payload["capture_aspect_ratio"] = display_aspect_ratio(dimensions[0], dimensions[1])
        if detail:
            payload["detail"] = detail
        self.socket.send(payload)

    def _report_actual_capture_dimensions(self, frame: Any, config: Dict[str, Any]) -> None:
        """Publish the decoded-frame size once per source/open instead of trusting requested properties."""
        height, width = frame.shape[:2]
        dimensions = (int(width), int(height))
        if dimensions == self._actual_capture_dimensions:
            return
        self._actual_capture_dimensions = dimensions
        profile = profile_for_camera_type(config.get("camera_type"))
        source_kind = camera_input_kind(config["camera_source"])
        aspect = display_aspect_ratio(*dimensions)
        if source_kind == "ADB screen-capture fallback":
            detail = (
                f"ADB screen-capture fallback frame is {width}x{height} ({aspect}); "
                "it is not raw phone-camera access. Keep the stock Camera app foreground."
            )
        elif source_kind == "UVC/raw USB":
            detail = (
                f"Windows UVC/raw USB frame is {width}x{height} ({aspect}) for {profile['label']}. "
                "The actual UVC device/lens is selected by Windows or the phone."
            )
        else:
            detail = f"{source_kind.capitalize()} frame is {width}x{height} ({aspect}) for {profile['label']}."
        self._send_camera_status("ONLINE", config, detail, dimensions)

    @staticmethod
    def _detector_key(config: Dict[str, Any]) -> Tuple[Any, ...]:
        return (
            config["model_asset_path"],
            config["max_hands"],
            config["min_hand_detection_confidence"],
            config["min_hand_presence_confidence"],
            config["min_hand_tracking_confidence"],
        )

    def _ensure_detector(self, config: Dict[str, Any]) -> bool:
        key = self._detector_key(config)
        if self.detector is not None and key == self._applied_detector_key:
            return True
        if self.detector is None and key == self._failed_detector_key and time.monotonic() < self._next_detector_retry_at:
            return False
        for detector in (self.detector, self.image_fallback_detector):
            if detector is None:
                continue
            try:
                detector.close()
            except Exception:
                pass
        self.detector = None
        self.image_fallback_detector = None
        try:
            self.detector = create_hand_detector(config)
            self._applied_detector_key = key
            self._failed_detector_key = None
            self._next_detector_retry_at = 0.0
            self._last_video_timestamp_ms = -1
            self._consecutive_empty_video_results = 0
            self.socket.send({"event": "ENGINE_STATUS", "status": "HAND_TRACKER_READY", "max_hands": config["max_hands"]})
            return True
        except Exception as exc:
            self._applied_detector_key = None
            self._failed_detector_key = key
            self._next_detector_retry_at = time.monotonic() + 5.0
            self.socket.send({"event": "ENGINE_STATUS", "status": "HAND_TRACKER_ERROR", "detail": str(exc)})
            print(f"[ENGINE] Hand tracker unavailable: {exc}")
            return False

    def _set_hand_status(self, status: str) -> None:
        if status == self._hand_status:
            return
        self._hand_status = status
        print(f"[HAND] {status}")

    def _ensure_image_fallback_detector(self, config: Dict[str, Any]) -> Optional[Any]:
        if self.image_fallback_detector is not None:
            return self.image_fallback_detector
        if time.monotonic() < self._next_image_fallback_retry_at:
            return None
        try:
            self.image_fallback_detector = create_hand_detector(config, vision.RunningMode.IMAGE)
            print("[HAND] IMAGE fallback detector ready")
            return self.image_fallback_detector
        except Exception as exc:
            self._next_image_fallback_retry_at = time.monotonic() + 5.0
            self._set_hand_status(f"IMAGE fallback unavailable: {exc}")
            return None

    def _detect_hands(
        self, image: Any, timestamp_ms: int, config: Dict[str, Any]
    ) -> Tuple[Sequence[Sequence[Any]], Sequence[Sequence[Any]], str]:
        """Prefer VIDEO mode, then recover from repeated empty results in IMAGE mode."""
        assert self.detector is not None
        video_error: Optional[Exception] = None
        try:
            detection = self.detector.detect_for_video(image, timestamp_ms)
            screen_hands = detection.hand_landmarks or []
            world_hands = detection.hand_world_landmarks or []
        except Exception as exc:
            video_error = exc
            screen_hands, world_hands = [], []

        if screen_hands:
            self._consecutive_empty_video_results = 0
            self._set_hand_status(f"VIDEO hands: {len(screen_hands)}")
            return screen_hands, world_hands, "VIDEO"

        self._consecutive_empty_video_results += 1
        fallback_after = int(config["image_fallback_after_empty_video_frames"])
        if self._consecutive_empty_video_results < fallback_after:
            suffix = f"; VIDEO error: {video_error}" if video_error is not None else ""
            self._set_hand_status(
                f"NO HANDS FROM MEDIAPIPE (VIDEO {self._consecutive_empty_video_results}/{fallback_after}){suffix}"
            )
            return screen_hands, world_hands, "VIDEO_EMPTY"

        fallback = self._ensure_image_fallback_detector(config)
        if fallback is not None:
            try:
                fallback_detection = fallback.detect(image)
                fallback_screen = fallback_detection.hand_landmarks or []
                fallback_world = fallback_detection.hand_world_landmarks or []
                if fallback_screen:
                    self._set_hand_status(f"IMAGE fallback hands: {len(fallback_screen)}")
                    return fallback_screen, fallback_world, "IMAGE_FALLBACK"
            except Exception as exc:
                video_error = exc

        suffix = f"; error: {video_error}" if video_error is not None else ""
        self._set_hand_status(f"NO HANDS FROM MEDIAPIPE (VIDEO + IMAGE fallback){suffix}")
        return screen_hands, world_hands, "NO_HANDS"

    def _log_hand_results(self, hand_results: Sequence[Dict[str, Any]]) -> None:
        current: Dict[int, Tuple[Any, ...]] = {}
        for result in hand_results:
            reading = result.get("reading")
            if reading is None:
                summary: Tuple[Any, ...] = (result["state"], "unscored", result.get("detection_source"))
            else:
                summary = (
                    result["state"],
                    int(bool(reading["is_open"])),
                    int(bool(reading["is_closed"])),
                    reading["extended_count"],
                    reading["curled_count"],
                    result.get("detection_source"),
                )
            track_id = int(result["track_id"])
            current[track_id] = summary
            if self._last_hand_debug.get(track_id) != summary:
                print(f"[HAND #{track_id}] state={summary}")
        self._last_hand_debug = current

    def _ensure_camera(self, config: Dict[str, Any]) -> bool:
        camera_input = (
            config["camera_source"],
            tuple(config["resolution"]),
            config.get("camera_type", "laptop_webcam"),
            tuple(config.get("aspect_ratio", [16, 9])),
        )
        if self.capture is not None and camera_input == self._applied_camera_input:
            return True
        if self.capture is not None:
            self.capture.release()
            self.capture = None
        self._applied_camera_input = camera_input
        self.tracker = MultiHandTracker()
        self._last_hand_results = []
        self._actual_capture_dimensions = (0, 0)
        self._send_camera_status("CONNECTING", config)
        try:
            self.capture = open_camera(config["camera_source"], config)
        except CameraSourceError as exc:
            self.capture = None
            self._send_camera_status("ERROR", config, str(exc))
            return False
        if self.capture is None:
            self._send_camera_status(
                "ERROR",
                config,
                "OpenCV could not open this webcam index, UVC/raw USB input, or stream URL",
            )
            return False
        if isinstance(self.capture, AdbScreenCapture):
            self._send_camera_status(
                "ONLINE",
                config,
                "ADB screen-capture fallback is ready; waiting for its first frame. It is not raw phone-camera access.",
            )
            return True
        source_kind = camera_input_kind(config["camera_source"])
        if is_network_stream_source(config["camera_source"]):
            detail = (
                f"{source_kind} opened with newest-frame delivery; "
                "waiting for the first decoded frame to report actual dimensions."
            )
        else:
            detail = f"{source_kind} opened; waiting for the first decoded frame to report actual dimensions."
        self._send_camera_status("ONLINE", config, detail)
        return True

    def _on_media_complete(self, recording: ActiveRecording, status: str) -> None:
        payload = copy.deepcopy(recording.metadata)
        recorded_post_seconds = min(
            recording.end_at - recording.started_at,
            max(0.0, time.monotonic() - recording.started_at),
        )
        video_duration_sec = round(recording.pre_event_seconds_recorded + recorded_post_seconds, 2)
        payload.update({
            "event": "INCIDENT_MEDIA_READY",
            "event_token": recording.event_token,
            "video_path": recording.video_path,
            "media_status": status,
            "recording_started_at": payload.get("timestamp"),
            "recording_started_monotonic_ms": int(recording.started_at * 1000),
            "recording_completed_at": int(time.time()),
            "pre_event_seconds_recorded": round(recording.pre_event_seconds_recorded, 2),
            "post_event_seconds_requested": round(recording.end_at - recording.started_at, 2),
            "recording_fps": recording.fps,
            # Java's MediaReadyEvent parser reads this exact field.
            "video_duration_sec": video_duration_sec,
            "duration_seconds": video_duration_sec,
            "video_mime_type": "video/mp4",
        })
        self.socket.send(payload)
        print(f"[RECORDING] {status}: {recording.video_path}")

    def _trigger_alert(
        self,
        frame: Any,
        result: Dict[str, Any],
        people: Dict[str, Any],
        config: Dict[str, Any],
        now: float,
    ) -> None:
        reading = result["reading"] or {}
        event_token = f"evt-{dt.datetime.now().strftime('%Y%m%d_%H%M%S_%f')}-{uuid.uuid4().hex[:8]}"
        snapshot_path, snapshot_error = save_snapshot(frame, event_token)
        person = associate_hand_to_person(result["screen_landmarks"], people)
        context = alert_context(people, config)
        hand_count = len(result.get("all_hand_results", []))
        # Prefer the associated occupancy track when available. It is still
        # camera-local and transient - never an identity - but it lets the
        # dashboard distinguish the signaler's person box from their hand ID.
        signaler_track_id = person.get("transient_track_id") or f"hand-{result['track_id']}"
        signaler_bounds = person.get("bounds") or hand_landmark_bounds(result["screen_landmarks"])
        base_payload: Dict[str, Any] = {
            "event_token": event_token,
            "cameraId": config["camera_id"],
            "camera_source": display_camera_source(config["camera_source"]),
            "confidence": round(float(reading.get("confidence", 0.0)), 3),
            "timestamp": int(time.time()),
            "timestamp_readable": dt.datetime.now().isoformat(timespec="seconds"),
            "location": config["location"],
            "triage_context": context["triage_context"],
            "alert_mode": context["alert_mode"],
            "quiet_people_threshold": context["quiet_people_threshold"],
            "quiet_at_or_above_people": context["quiet_at_or_above_people"],
            "audible_alerts_enabled": context["audible_alerts_enabled"],
            "alert_reason": context["alert_reason"],
            "people_count_stale": context["people_count_stale"],
            "occupancy_status": context["occupancy_status"],
            "snapshot_path": snapshot_path,
            "snapshot_mime_type": "image/jpeg",
            "snapshot_error": snapshot_error,
            # Flat protocol fields are consumed by Java DistressEvent.
            "people_count": context["people_count"],
            "hand_count": hand_count,
            "signaler_count": 1,
            "signaler_track_id": signaler_track_id,
            "signaler_bounds": signaler_bounds,
            # Explicit fields let the Swing dashboard show occupancy and the
            # signal source without treating a transient track as identity.
            "people": people,
            "hands": {
                "detected_count": hand_count,
                "max_hands": config["max_hands"],
                "signal_hand_track_id": result["track_id"],
            },
            "signal_person": person,
            "signal_hand": {
                "track_id": result["track_id"],
                "gesture_state": result["state"],
                "world_landmarks_used": bool(result["world_landmarks_available"]),
                "landmark_confidence": reading.get("landmark_confidence"),
                "geometry": {
                    "thumb_tip_to_palm_ratio": reading.get("thumb_tip_to_palm_ratio"),
                    "thumb_ip_angle_deg": reading.get("thumb_ip_angle_deg"),
                    "extended_count": reading.get("extended_count"),
                    "curled_count": reading.get("curled_count"),
                },
            },
        }
        video_path, recording_started, recording_error = self.recorder.begin(event_token, frame, now, config, base_payload)
        immediate_payload = copy.deepcopy(base_payload)
        immediate_payload.update({
            "event": "DISTRESS_GESTURE_DETECTED",
            "video_path": video_path,
            "media_status": "RECORDING" if recording_started else "FAILED",
            "recording_error": recording_error,
        })
        # This is intentionally sent before the post-event clip finishes.
        self.socket.send(immediate_payload)
        if not recording_started:
            ready_payload = copy.deepcopy(base_payload)
            ready_payload.update({
                "event": "INCIDENT_MEDIA_READY",
                "video_path": video_path,
                "media_status": "FAILED",
                "recording_error": recording_error,
                "video_duration_sec": 0.0,
                "video_mime_type": "video/mp4",
            })
            self.socket.send(ready_payload)

    def run(self) -> None:
        next_camera_retry_at = 0.0
        try:
            while not self._stop.is_set():
                global_config, _revision = CONFIG.snapshot()
                config = self._effective_config(global_config)
                if config["paused"] != self._last_pause_state:
                    self.tracker.clear_sequence_state()
                    self._last_pause_state = config["paused"]

                detector_ready = self._ensure_detector(config)
                now = time.monotonic()
                if self.capture is None and now < next_camera_retry_at:
                    self._stop.wait(0.05)
                    continue
                if not self._ensure_camera(config):
                    # Do not hammer a phone stream or absent webcam while it
                    # is unavailable; retry after a short, visible backoff.
                    next_camera_retry_at = now + 2.0
                    self._applied_camera_input = object()
                    self._stop.wait(0.05)
                    continue

                assert self.capture is not None
                ok, frame = self.capture.read()
                if not ok or frame is None:
                    detail = getattr(self.capture, "last_error", "") or "Frame read failed; reconnecting"
                    self._send_camera_status("ERROR", config, detail)
                    self.capture.release()
                    self.capture = None
                    self._applied_camera_input = object()
                    continue

                now = time.monotonic()
                self._frame_count += 1
                self._report_actual_capture_dimensions(frame, config)
                # Capture and evidence remain at the selected camera
                # resolution.  Only the computer-vision workload may be
                # downscaled per camera, which keeps the stored incident
                # image and high-resolution HUD independent of this setting.
                processing_scale = float(config.get("processing_scale", 1.0))
                if processing_scale < 0.999:
                    processing_width = max(1, round(frame.shape[1] * processing_scale))
                    processing_height = max(1, round(frame.shape[0] * processing_scale))
                    processing_frame = cv2.resize(
                        frame,
                        (processing_width, processing_height),
                        interpolation=cv2.INTER_AREA,
                    )
                else:
                    processing_frame = frame
                self.recorder.ingest(frame, now, config)

                if config["people_detection_enabled"]:
                    if self._frame_count - self._last_people_submission_frame >= config["people_detection_interval_frames"]:
                        self.people_detector.submit(processing_frame, now, config["people_detection_max_width"])
                        self._last_people_submission_frame = self._frame_count
                    people = self.people_detector.snapshot(now, config["people_detection_stale_sec"])
                else:
                    # Do not reuse a previous occupancy result while the setting is
                    # disabled: alerts must not claim a current person count.
                    people = {
                        "count": 0,
                        "detector": PeopleDetector._LABEL,
                        "enabled": False,
                        "updated_at_ms": None,
                        "stale": True,
                        "transient_tracks": [],
                    }
                hand_results: List[Dict[str, Any]] = self._last_hand_results

                if not config["paused"] and detector_ready and self._frame_count % config["hand_detection_every_n_frames"] == 0:
                    timestamp_ms = int(now * 1000)
                    if timestamp_ms <= self._last_video_timestamp_ms:
                        timestamp_ms = self._last_video_timestamp_ms + 1
                    self._last_video_timestamp_ms = timestamp_ms
                    try:
                        rgb_frame = cv2.cvtColor(processing_frame, cv2.COLOR_BGR2RGB)
                        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
                        screen_hands, world_hands, detection_source = self._detect_hands(image, timestamp_ms, config)
                        hand_results = self.tracker.update(
                            screen_hands,
                            world_hands,
                            now,
                            config,
                            detection_source,
                        )
                        self._last_hand_results = hand_results
                        self._log_hand_results(hand_results)
                        for result in hand_results:
                            result["all_hand_results"] = hand_results
                            reading = result["reading"]
                            if not result["confirmed"] or reading is None:
                                continue
                            track: HandTrack = result["track"]
                            if now - track.last_trigger_at < config["alert_cooldown_sec"]:
                                continue
                            if reading["confidence"] < config["confidence_threshold"]:
                                continue
                            track.last_trigger_at = now
                            self._trigger_alert(frame, result, people, config, now)
                    except Exception as exc:
                        self.socket.send({"event": "ENGINE_STATUS", "status": "HAND_DETECTION_ERROR", "detail": str(exc)})
                        print(f"[ENGINE] Hand detection error: {exc}")

                preview_context = alert_context(people, config)
                preview = draw_preview(
                    frame, hand_results, people, preview_context, config, config["paused"], self._hand_status
                )
                self._publish_preview(preview, config, now)
        finally:
            self.recorder.close_all()
            if self.capture is not None:
                self.capture.release()
            if self.detector is not None:
                try:
                    self.detector.close()
                except Exception:
                    pass
            if self.image_fallback_detector is not None:
                try:
                    self.image_fallback_detector.close()
                except Exception:
                    pass
            self.people_detector.stop()
            with self._preview_lock:
                self._latest_preview = None


def configured_cameras(config: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Return normalized camera definitions, including legacy single-camera settings."""
    configured = config.get("cameras")
    if isinstance(configured, list) and configured:
        return [copy.deepcopy(camera) for camera in configured if camera.get("enabled", True)]
    return [{
        "camera_source": config["camera_source"],
        "camera_id": config["camera_id"],
        "location": config["location"],
        "camera_type": config.get("camera_type", "laptop_webcam"),
        "aspect_ratio": copy.deepcopy(config.get("aspect_ratio", [16, 9])),
        "enabled": True,
    }]


class MultiCameraVisionEngine:
    """Own the dashboard connection and a maximum of four camera workers."""

    def __init__(self) -> None:
        self.socket = EngineSocketClient(WS_URL, CONFIG)
        self._workers: Dict[str, CameraWorker] = {}
        self._camera_fingerprint: Tuple[Tuple[Any, ...], ...] = ()

    @staticmethod
    def _fingerprint(cameras: Sequence[Dict[str, Any]]) -> Tuple[Tuple[Any, ...], ...]:
        return tuple(
            (
                str(camera["camera_id"]),
                str(camera["camera_source"]),
                str(camera["location"]),
                str(camera.get("camera_type", "laptop_webcam")),
                tuple(camera.get("aspect_ratio", [16, 9])),
                bool(camera.get("enabled", True)),
            )
            for camera in cameras
        )

    def _reconcile_workers(self, cameras: Sequence[Dict[str, Any]]) -> None:
        desired = {str(camera["camera_id"]): camera for camera in cameras}
        current = dict(self._workers)
        for camera_id, worker in current.items():
            desired_camera = desired.get(camera_id)
            if desired_camera is None or worker.camera != desired_camera:
                worker.stop()
                # Do not start a replacement with the same ID until a stalled
                # phone stream has actually released its old worker.
                worker.join(timeout=0.35)
                if not worker.is_alive():
                    self._workers.pop(camera_id, None)

        for camera in cameras:
            camera_id = str(camera["camera_id"])
            if camera_id in self._workers:
                continue
            worker = CameraWorker(camera, self.socket)
            self._workers[camera_id] = worker
            worker.start()

    @staticmethod
    def _placeholder(camera: Dict[str, Any], width: int, height: int) -> Any:
        tile = np.full((height, width, 3), (18, 31, 50), dtype=np.uint8)
        cv2.putText(tile, str(camera["camera_id"]), (18, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.68, (110, 205, 255), 2)
        cv2.putText(tile, "Connecting to camera...", (18, 68), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (220, 230, 240), 1)
        return tile

    def _compose_preview(self, cameras: Sequence[Dict[str, Any]]) -> Any:
        # Do not shrink a single high-resolution HUD to a tiny fixed tile.
        # The fullscreen local ingestion view receives its native HUD canvas;
        # multi-camera layouts use 960x540 tiles so each HUD remains readable.
        tile_width, tile_height = 960, 540
        previews: List[Tuple[Dict[str, Any], Optional[Any]]] = []
        for camera in cameras:
            worker = self._workers.get(str(camera["camera_id"]))
            preview = None if worker is None else worker.latest_preview()
            previews.append((camera, preview))

        if len(previews) == 1 and previews[0][1] is not None:
            return previews[0][1]

        tiles: List[Any] = []
        for camera, preview in previews:
            if preview is None:
                tile = self._placeholder(camera, tile_width, tile_height)
            else:
                # Preserve the aspect ratio of portrait phone captures as
                # well as landscape webcams.  The letterbox is intentional;
                # stretching would distort hand geometry on the local HUD.
                tile = np.full((tile_height, tile_width, 3), (18, 15, 11), dtype=np.uint8)
                source_height, source_width = preview.shape[:2]
                scale = min(tile_width / max(1, source_width), tile_height / max(1, source_height))
                fitted_width = max(1, round(source_width * scale))
                fitted_height = max(1, round(source_height * scale))
                fitted = cv2.resize(
                    preview,
                    (fitted_width, fitted_height),
                    interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR,
                )
                left = (tile_width - fitted_width) // 2
                top = (tile_height - fitted_height) // 2
                tile[top:top + fitted_height, left:left + fitted_width] = fitted
                cv2.rectangle(tile, (0, 0), (tile_width - 1, tile_height - 1), (70, 180, 255), 1)
                cv2.putText(
                    tile,
                    str(camera["camera_id"]),
                    (12, tile_height - 12),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.48,
                    (255, 255, 255),
                    2,
                )
                cv2.putText(
                    tile,
                    str(camera["camera_id"]),
                    (12, tile_height - 12),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.48,
                    (30, 75, 110),
                    1,
                )
            tiles.append(tile)

        if not tiles:
            return self._placeholder(
                {"camera_id": "No enabled cameras"}, tile_width, tile_height
            )

        columns = 1 if len(tiles) == 1 else 2
        while len(tiles) % columns:
            tiles.append(np.full((tile_height, tile_width, 3), (10, 18, 30), dtype=np.uint8))
        rows = [np.hstack(tiles[index:index + columns]) for index in range(0, len(tiles), columns)]
        composed = np.vstack(rows)
        if columns == 2 and len(rows) == 1:
            # Keep a two-camera layout on a 16:9 canvas.  A fullscreen
            # backend can then retain 960x540 HUD tiles instead of scaling a
            # very short 32:9 image to fit the monitor.
            canvas = np.full((tile_height * 2, tile_width * 2, 3), (10, 18, 30), dtype=np.uint8)
            top = (canvas.shape[0] - composed.shape[0]) // 2
            canvas[top:top + composed.shape[0], :composed.shape[1]] = composed
            return canvas
        return composed

    def run(self) -> None:
        window_name = "SenyAlert - Vision Engine (Multi-Camera)"
        self.socket.start()
        cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
        try:
            # OpenCV does not expose a portable "maximize" operation.  On
            # Windows this gives the local ingestion display the requested
            # maximized/fullscreen working area; ESC remains the explicit
            # close control below.
            cv2.setWindowProperty(window_name, cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
        except cv2.error:
            # A backend without fullscreen support still gets a substantially
            # larger initial canvas instead of the old 480x270 window.
            try:
                cv2.resizeWindow(window_name, 1280, 720)
            except cv2.error:
                pass
        try:
            while True:
                config, _revision = CONFIG.snapshot()
                cameras = configured_cameras(config)
                fingerprint = self._fingerprint(cameras)
                # Reconcile every UI tick, not only when the requested
                # fingerprint changes.  A released but stalled phone worker
                # can terminate just after a settings update; this next pass
                # then removes it and starts the requested replacement.
                self._reconcile_workers(cameras)
                self._camera_fingerprint = fingerprint

                cv2.imshow(window_name, self._compose_preview(cameras))
                key = cv2.waitKey(25) & 0xFF
                if key == 27:
                    break
                try:
                    if cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
                        break
                except cv2.error:
                    break
        finally:
            for worker in list(self._workers.values()):
                worker.stop()
            for worker in list(self._workers.values()):
                worker.join()
            self._workers.clear()
            self.socket.stop()
            cv2.destroyAllWindows()


# Compatibility name for scripts that previously imported VisionEngine.
VisionEngine = MultiCameraVisionEngine


if __name__ == "__main__":
    MultiCameraVisionEngine().run()
