"""Pure gesture geometry, scoring, and temporal tracking for SenyAlert.

This module intentionally starts from the groupmate prototype's successful
scale-normalized thresholds (0.05 finger margin and 0.55 thumb ratio), then
expresses them in a palm-local coordinate frame.  The old implementation
compared every finger to the camera's global Y axis, which made a rotated hand
look more folded than the same front-facing hand.  No OpenCV, MediaPipe task,
socket, evidence, or HUD dependency lives here, so these rules can be tested
with simple landmark fixtures.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
import math
from typing import Any, Deque, Dict, Iterable, List, Optional, Sequence, Tuple


FINGER_JOINTS: Dict[str, Tuple[int, int, int, int]] = {
    "index": (5, 6, 7, 8),
    "middle": (9, 10, 11, 12),
    "ring": (13, 14, 15, 16),
    "pinky": (17, 18, 19, 20),
}
PALM_MCP_INDICES = (5, 9, 13, 17)
FINGER_LANDMARK_INDICES: Dict[str, Tuple[int, ...]] = {
    "thumb": (1, 2, 3, 4),
    "index": (5, 6, 7, 8),
    "middle": (9, 10, 11, 12),
    "ring": (13, 14, 15, 16),
    "pinky": (17, 18, 19, 20),
}
ORIENTATION_SIGN_CONFIDENT = 0.35
LANDMARK_TO_FINGER = {
    index: finger
    for finger, indices in FINGER_LANDMARK_INDICES.items()
    for index in indices
}


def _unit(value: Any) -> float:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(numeric):
        return 0.0
    return max(0.0, min(1.0, numeric))


def _clamp(value: Any, minimum: float, maximum: float) -> float:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        numeric = minimum
    if not math.isfinite(numeric):
        numeric = minimum
    return max(minimum, min(maximum, numeric))


def _point2(landmark: Any) -> Tuple[float, float]:
    return float(landmark.x), float(landmark.y)


def _point3(landmark: Any) -> Tuple[float, float, float]:
    return float(landmark.x), float(landmark.y), float(landmark.z)


def _sub2(left: Tuple[float, float], right: Tuple[float, float]) -> Tuple[float, float]:
    return left[0] - right[0], left[1] - right[1]


def _length2(vector: Tuple[float, float]) -> float:
    return math.hypot(vector[0], vector[1])


def _distance2(left: Tuple[float, float], right: Tuple[float, float]) -> float:
    return _length2(_sub2(left, right))


def _dot2(left: Tuple[float, float], right: Tuple[float, float]) -> float:
    return left[0] * right[0] + left[1] * right[1]


def _normalized2(vector: Tuple[float, float]) -> Optional[Tuple[float, float]]:
    length = _length2(vector)
    if length < 1e-8:
        return None
    return vector[0] / length, vector[1] / length


def _length3(vector: Tuple[float, float, float]) -> float:
    return math.sqrt(sum(component * component for component in vector))


def _distance3(left: Tuple[float, float, float], right: Tuple[float, float, float]) -> float:
    return _length3(tuple(a - b for a, b in zip(left, right)))


def _joint_angle_2d(first: Tuple[float, float], vertex: Tuple[float, float], third: Tuple[float, float]) -> Optional[float]:
    left = _sub2(first, vertex)
    right = _sub2(third, vertex)
    left_length = _length2(left)
    right_length = _length2(right)
    if left_length < 1e-8 or right_length < 1e-8:
        return None
    cosine = _dot2(left, right) / (left_length * right_length)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _joint_angle_3d(
    first: Tuple[float, float, float], vertex: Tuple[float, float, float], third: Tuple[float, float, float]
) -> Optional[float]:
    left = tuple(a - b for a, b in zip(first, vertex))
    right = tuple(a - b for a, b in zip(third, vertex))
    left_length = _length3(left)
    right_length = _length3(right)
    if left_length < 1e-8 or right_length < 1e-8:
        return None
    cosine = sum(a * b for a, b in zip(left, right)) / (left_length * right_length)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _fingers(config: Dict[str, Any]) -> Dict[str, bool]:
    requested = config.get("fingers")
    if not isinstance(requested, dict):
        requested = {}
    return {name: bool(requested.get(name, True)) for name in FINGER_LANDMARK_INDICES}


def _required_landmark_indices(config: Dict[str, Any]) -> List[int]:
    fingers = _fingers(config)
    indices = {0, 5, 9, 13, 17}
    for finger, landmarks in FINGER_LANDMARK_INDICES.items():
        if fingers[finger]:
            indices.update(landmarks)
    return sorted(indices)


def landmark_confidence_status(
    screen_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]
) -> Dict[str, Any]:
    """Validate optional MediaPipe visibility/presence without fabricating it.

    HandLandmarker commonly omits both fields.  Missing metadata is therefore
    reported as unavailable, while a provided low value blocks the frame and
    reduces the chance that a guessed occluded thumb creates an alert.
    """
    if not screen_landmarks:
        return {
            "passed": False,
            "available": False,
            "reason": "screen_landmarks_missing",
            "quality": 0.0,
            "finger_quality": {},
        }

    fingers = _fingers(config)
    finger_values: Dict[str, List[float]] = {name: [] for name, enabled in fingers.items() if enabled}
    palm_values: List[float] = []
    observed = 0
    visibility_threshold = _clamp(config.get("landmark_visibility_threshold", 0.35), 0.0, 1.0)
    presence_threshold = _clamp(config.get("landmark_presence_threshold", 0.35), 0.0, 1.0)

    def summary() -> Dict[str, Any]:
        if observed == 0:
            return {"quality": 1.0, "finger_quality": {}, "observed_fingers": 0}
        per_finger = {
            finger: round(sum(values) / len(values), 4) if values else 0.0
            for finger, values in finger_values.items()
        }
        finger_average = sum(per_finger.values()) / len(per_finger) if per_finger else 0.0
        palm_average = sum(palm_values) / len(palm_values) if palm_values else finger_average
        return {
            "quality": round(_unit(0.85 * finger_average + 0.15 * palm_average), 4),
            "finger_quality": per_finger,
            "observed_fingers": sum(1 for values in finger_values.values() if values),
        }

    for index in _required_landmark_indices(config):
        if index >= len(screen_landmarks):
            return {
                "passed": False,
                "available": False,
                "reason": f"landmark_{index}_missing",
                "quality": 0.0,
                "finger_quality": {},
            }
        landmark = screen_landmarks[index]
        for attribute, threshold in (("visibility", visibility_threshold), ("presence", presence_threshold)):
            raw = getattr(landmark, attribute, None)
            if raw is None:
                continue
            try:
                numeric = float(raw)
            except (TypeError, ValueError):
                continue
            observed += 1
            target = LANDMARK_TO_FINGER.get(index)
            if target in finger_values:
                finger_values[target].append(_unit(numeric))
            else:
                palm_values.append(_unit(numeric))
            if numeric < threshold:
                return {
                    "passed": False,
                    "available": True,
                    "reason": f"{attribute}_below_threshold",
                    "landmark_index": index,
                    "value": numeric,
                    **summary(),
                }

    if observed == 0:
        return {
            "passed": True,
            "available": False,
            "reason": "visibility_and_presence_not_provided_by_hand_landmarker",
            **summary(),
        }
    return {"passed": True, "available": True, "checked_values": observed, **summary()}


def effective_thresholds(config: Dict[str, Any]) -> Dict[str, float]:
    """Return one coherent threshold set for phase, score, and bonuses.

    The 100% slider value exactly matches the groupmate prototype.  At 50% it
    is stricter; at 150% it is genuinely more permissive.  Unlike the prior
    implementation, the same values are used by feature flags, confidence,
    bonuses, and the full-close marker.
    """
    sensitivity = _clamp(config.get("gesture_sensitivity", 1.0), 0.50, 1.50)
    base_margin = _clamp(config.get("screen_finger_margin", 0.05), 0.005, 0.25)
    base_thumb_ratio = _clamp(config.get("screen_thumb_tucked_ratio", 0.55), 0.15, 1.25)
    return {
        "sensitivity": sensitivity,
        # .075 / .050 / .025 for the normal .05 groupmate margin.
        "finger_margin": base_margin * (2.0 - sensitivity),
        # .481 / .550 / .619 for the normal .55 thumb ratio.
        "thumb_ratio": base_thumb_ratio * (0.75 + 0.25 * sensitivity),
        "thumb_lateral_limit": 0.45 + 0.10 * (sensitivity - 1.0),
        "thumb_angle_limit_deg": 150.0 + 15.0 * (sensitivity - 1.0),
        "base_finger_margin": base_margin,
        "base_thumb_ratio": base_thumb_ratio,
    }


def _palm_frame(points: Sequence[Tuple[float, float]]) -> Optional[Dict[str, Any]]:
    wrist = points[0]
    middle_mcp = points[9]
    palm_axis = _normalized2(_sub2(middle_mcp, wrist))
    lateral_axis = _normalized2(_sub2(points[17], points[5]))
    scale = _distance2(wrist, middle_mcp)
    lateral_scale = _distance2(points[17], points[5])
    if palm_axis is None or lateral_axis is None or scale < 1e-6 or lateral_scale < 1e-6:
        return None
    center = (
        sum(points[index][0] for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
        sum(points[index][1] for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
    )
    return {
        "scale": scale,
        "center": center,
        "palm_axis": palm_axis,
        "lateral_axis": lateral_axis,
        "lateral_scale": lateral_scale,
        "width_ratio": lateral_scale / scale,
    }


def _world_normal_z(world_landmarks: Optional[Sequence[Any]]) -> Optional[float]:
    if not world_landmarks or len(world_landmarks) < 21:
        return None
    try:
        points = [_point3(landmark) for landmark in world_landmarks]
    except (AttributeError, TypeError, ValueError):
        return None
    across = tuple(points[17][axis] - points[5][axis] for axis in range(3))
    along = tuple(points[9][axis] - points[0][axis] for axis in range(3))
    normal = (
        across[1] * along[2] - across[2] * along[1],
        across[2] * along[0] - across[0] * along[2],
        across[0] * along[1] - across[1] * along[0],
    )
    length = _length3(normal)
    if length < 1e-8:
        return None
    return max(-1.0, min(1.0, normal[2] / length))


def _world_metrics(world_landmarks: Optional[Sequence[Any]]) -> Dict[str, Any]:
    if not world_landmarks or len(world_landmarks) < 21:
        return {"available": False, "palm_normal_z": None, "finger_angles": {}}
    try:
        points = [_point3(landmark) for landmark in world_landmarks]
    except (AttributeError, TypeError, ValueError):
        return {"available": False, "palm_normal_z": None, "finger_angles": {}}
    angles: Dict[str, Dict[str, Optional[float]]] = {}
    for name, (mcp, pip, dip, tip) in FINGER_JOINTS.items():
        angles[name] = {
            "pip_angle_deg": _joint_angle_3d(points[mcp], points[pip], points[dip]),
            "dip_angle_deg": _joint_angle_3d(points[pip], points[dip], points[tip]),
        }
    thumb_angle = _joint_angle_3d(points[2], points[3], points[4])
    return {
        "available": True,
        "palm_normal_z": _world_normal_z(world_landmarks),
        "finger_angles": angles,
        "thumb_ip_angle_deg": thumb_angle,
    }


def _quality_above(value: float, threshold: float, span: float) -> float:
    return _unit((value - (threshold - span)) / max(1e-6, 2.0 * span))


def _thumb_quality(
    ratio: float,
    lateral_offset: float,
    angle: Optional[float],
    thresholds: Dict[str, float],
) -> Tuple[bool, bool, float]:
    """Return effective tuck, calibrated tuck, and a gradual quality score.

    The groupmate prototype proved its thumb test in the actual demo: a thumb
    is tucked when its tip is inside the scale-normalized palm-radius gate.
    Keep that *binary* gate intact.  The lateral offset and joint angle remain
    diagnostics and shape the partial score, but they must not secretly make
    a clearly tucked, low-resolution, or briefly occluded thumb fail the
    original ``ratio < .55`` rule.
    """
    angle_value = angle if angle is not None else 180.0
    ratio_limit = thresholds["thumb_ratio"]
    lateral_limit = thresholds["thumb_lateral_limit"]
    angle_limit = thresholds["thumb_angle_limit_deg"]
    tucked = bool(ratio <= ratio_limit)

    base_ratio = thresholds["base_thumb_ratio"]
    calibrated = bool(ratio <= base_ratio)

    ratio_quality = _unit((ratio_limit * 1.20 - ratio) / max(1e-6, ratio_limit * 0.65))
    lateral_quality = _unit((lateral_limit * 1.55 - abs(lateral_offset)) / max(1e-6, lateral_limit * 1.15))
    angle_quality = _unit((angle_limit * 1.15 - angle_value) / max(1e-6, angle_limit * 0.35))
    quality = 1.0 if tucked else _unit(0.60 * ratio_quality + 0.25 * lateral_quality + 0.15 * angle_quality)
    return tucked, calibrated, quality


def analyze_screen_hand(screen_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Classify a hand with groupmate thresholds in a palm-local 2-D frame.

    This is the authoritative phase reader.  It does not depend on image Y;
    rotating the camera or hand rotates the palm axis too, so a reversed pose
    no longer receives accidental curl credit merely because its fingertips
    move down the screen.
    """
    if not screen_landmarks or len(screen_landmarks) < 21:
        return None
    try:
        points = [_point2(landmark) for landmark in screen_landmarks]
    except (AttributeError, TypeError, ValueError):
        return None
    frame = _palm_frame(points)
    if frame is None:
        return None
    thresholds = effective_thresholds(config)
    fingers = _fingers(config)
    enabled_fingers = [name for name in FINGER_JOINTS if fingers[name]]
    if not enabled_fingers:
        return None

    scale = float(frame["scale"])
    palm_axis = frame["palm_axis"]
    finger_metrics: Dict[str, Dict[str, Any]] = {}
    extended_count = 0
    folded_count = 0
    calibrated_folded_count = 0
    fold_quality_total = 0.0

    for name, (mcp, pip, dip, tip) in FINGER_JOINTS.items():
        # Positive extension means the fingertip lies forward of its PIP along
        # the wrist-to-finger axis. Positive fold means it has returned past
        # its MCP toward the wrist. These are the original Y equations in a
        # rotated palm coordinate system.
        extension_metric = _dot2(_sub2(points[tip], points[pip]), palm_axis) / scale
        fold_metric = -_dot2(_sub2(points[tip], points[mcp]), palm_axis) / scale
        extended = extension_metric > thresholds["finger_margin"]
        folded = fold_metric > thresholds["finger_margin"]
        calibrated_folded = fold_metric > thresholds["base_finger_margin"]
        local_pip_angle = _joint_angle_2d(points[mcp], points[pip], points[dip])
        local_dip_angle = _joint_angle_2d(points[pip], points[dip], points[tip])
        fold_quality = _quality_above(fold_metric, thresholds["finger_margin"], 0.16)
        if fingers[name]:
            extended_count += int(extended)
            folded_count += int(folded)
            calibrated_folded_count += int(calibrated_folded)
            fold_quality_total += fold_quality
        finger_metrics[name] = {
            "extended": extended,
            "folded": folded,
            "calibrated_folded": calibrated_folded,
            # Compatibility aliases retained for dashboard/debug consumers.
            "groupmate_extended": extended,
            "groupmate_curled": folded,
            "calibrated_curled": calibrated_folded,
            "extension_margin": round(extension_metric, 4),
            "curl_margin": round(fold_metric, 4),
            "local_pip_angle_deg": None if local_pip_angle is None else round(local_pip_angle, 1),
            "local_dip_angle_deg": None if local_dip_angle is None else round(local_dip_angle, 1),
            "fold_quality": round(fold_quality, 4),
            "phase_margin": round(thresholds["finger_margin"], 4),
            "calibrated_margin": round(thresholds["base_finger_margin"], 4),
        }

    thumb_tip = points[4]
    thumb_vector = _sub2(thumb_tip, frame["center"])
    thumb_ratio = _length2(thumb_vector) / scale
    thumb_lateral_offset = _dot2(thumb_vector, frame["lateral_axis"]) / float(frame["lateral_scale"])
    thumb_angle = _joint_angle_2d(points[2], points[3], points[4])
    thumb_tucked, calibrated_thumb_tucked, thumb_quality = _thumb_quality(
        thumb_ratio, thumb_lateral_offset, thumb_angle, thresholds
    )
    thumb_required = fingers["thumb"]
    thumb_requirement_met = thumb_tucked or not thumb_required
    selected_count = len(enabled_fingers)
    open_minimum = min(selected_count, int(config.get("screen_open_min_extended", 3)))
    closed_minimum = min(selected_count, int(config.get("screen_closed_min_curled", 4)))
    # Retain the old value for diagnostics only. It must not control phases.
    image_upright = points[0][1] > points[9][1]
    is_open = extended_count >= open_minimum
    # A foreshortened palm is less observable.  Treat this as an explicit
    # confidence cap below, not a hidden replacement for the groupmate close
    # phase; the HUD exposes the resulting palm-facing quality.
    palm_width_ratio = float(frame["width_ratio"])
    palm_orientation_quality = _unit((palm_width_ratio - 0.20) / 0.30)
    core_closed = thumb_requirement_met and folded_count >= closed_minimum
    # Preserve the original groupmate phase result once the configured thumb
    # and finger gates are met.  A narrow projected palm is reported and
    # confidence-capped below, rather than being a second hidden condition
    # that prevents an otherwise complete front-facing sign from reaching 1.0.
    is_closed = core_closed
    is_thumb_tuck_phase = (
        thumb_requirement_met and extended_count >= open_minimum and folded_count < closed_minimum
    )
    sos_like_close = (
        palm_orientation_quality >= 0.25
        and thumb_requirement_met
        and folded_count >= max(2, closed_minimum - 1)
    )

    index_required = fingers["index"]
    index_folded = bool(finger_metrics["index"]["folded"])
    thumb_bonus = _clamp(config.get("thumb_tuck_confidence_bonus", 0.10), 0.0, 0.30) if thumb_required and thumb_tucked else 0.0
    index_bonus = _clamp(config.get("index_fold_confidence_bonus", 0.10), 0.0, 0.30) if index_required and index_folded else 0.0
    finger_completion = fold_quality_total / selected_count
    # Palm observability contributes only to the partial score.  A complete
    # narrow/edge-on pose receives the transparent 60% cap below instead.
    partial_score = _unit(0.68 * finger_completion + 0.22 * thumb_quality + 0.10 * palm_orientation_quality)
    partial_score = min(0.89, partial_score + thumb_bonus + index_bonus)
    # The original detector publishes a confident 1.0 after the same complete
    # close. Do that here too: do not make it pass a second secret depth score.
    confidence = 1.0 if is_closed else partial_score
    if core_closed and palm_orientation_quality < 0.25:
        confidence = min(confidence, 0.60)
    strict_complete = bool(
        is_closed
        and (not thumb_required or calibrated_thumb_tucked)
        and calibrated_folded_count >= closed_minimum
    )
    sensitivity_assisted_complete = bool(is_closed and not strict_complete)

    return {
        "is_open": is_open,
        "is_closed": is_closed,
        "is_thumb_tuck_phase": is_thumb_tuck_phase,
        "sos_like_close": sos_like_close,
        "thumb_tucked": thumb_tucked,
        "calibrated_thumb_tucked": calibrated_thumb_tucked,
        "thumb_tuck_required": thumb_required,
        "thumb_requirement_met": thumb_requirement_met,
        "index_folded": index_folded,
        "index_fold_required": index_required,
        "strict_complete": strict_complete,
        "sensitivity_assisted_complete": sensitivity_assisted_complete,
        "confidence": round(confidence, 4),
        "thumb_tip_to_palm_ratio": round(thumb_ratio, 4),
        "thumb_lateral_offset": round(thumb_lateral_offset, 4),
        "thumb_ip_angle_deg": None if thumb_angle is None else round(thumb_angle, 1),
        "extended_count": extended_count,
        "curled_count": folded_count,
        "calibrated_curled_count": calibrated_folded_count,
        "required_finger_count": selected_count,
        "palm_width_ratio": round(palm_width_ratio, 4),
        "palm_facing_quality": round(palm_orientation_quality, 4),
        "image_upright": image_upright,
        "phase_driver": "groupmate_palm_local_frame",
        "legacy_open": is_open,
        "legacy_closed": is_closed,
        "legacy_extended_count": extended_count,
        "legacy_curled_count": folded_count,
        "normalized_open": is_open,
        "normalized_closed": is_closed,
        "normalized_extended_count": extended_count,
        "normalized_curled_count": folded_count,
        "finger_metrics": finger_metrics,
        "confidence_components": {
            "finger_completion": round(finger_completion, 4),
            "thumb_tuck": round(thumb_quality, 4),
            "thumb_tuck_bonus": round(thumb_bonus, 4),
            "index_fold_bonus": round(index_bonus, 4),
            "requirement_bonus_total": round(thumb_bonus + index_bonus, 4),
            "partial_score": round(partial_score, 4),
            "palm_orientation": round(palm_orientation_quality, 4),
            "gesture_sensitivity": round(thresholds["sensitivity"], 3),
            "phase_finger_margin": round(thresholds["finger_margin"], 4),
            "phase_thumb_tuck_ratio": round(thresholds["thumb_ratio"], 4),
            "folded_count": folded_count,
            "calibrated_folded_count": calibrated_folded_count,
            "strict_complete": strict_complete,
            "sensitivity_assisted_complete": sensitivity_assisted_complete,
        },
    }


def analyze_world_hand(
    world_landmarks: Optional[Sequence[Any]],
    screen_landmarks: Optional[Sequence[Any]],
    config: Dict[str, Any],
) -> Optional[Dict[str, Any]]:
    """Expose world-space angles/orientation as telemetry, never a hidden gate."""
    visibility = landmark_confidence_status(screen_landmarks, config)
    if not visibility["passed"]:
        return None
    metrics = _world_metrics(world_landmarks)
    if not metrics["available"]:
        return None
    return {
        "palm_normal_z": metrics["palm_normal_z"],
        "thumb_ip_angle_deg": metrics["thumb_ip_angle_deg"],
        "finger_angles": metrics["finger_angles"],
        "landmark_confidence": visibility,
    }


def combine_hand_readings(
    screen_landmarks: Optional[Sequence[Any]], world_landmarks: Optional[Sequence[Any]], config: Dict[str, Any]
) -> Optional[Dict[str, Any]]:
    """Combine pure local-frame classification with optional confidence metadata.

    World landmarks are deliberately diagnostic/corroborative.  They cannot
    multiply a valid groupmate-compatible front-facing close down to 55%.
    """
    visibility = landmark_confidence_status(screen_landmarks, config)
    if not visibility["passed"]:
        return None
    reading = analyze_screen_hand(screen_landmarks, config)
    if reading is None:
        return None
    world = _world_metrics(world_landmarks)
    world_angles = world.get("finger_angles", {})
    for name, metrics in reading["finger_metrics"].items():
        angle_metrics = world_angles.get(name)
        if angle_metrics:
            metrics["world_pip_angle_deg"] = (
                None if angle_metrics["pip_angle_deg"] is None else round(angle_metrics["pip_angle_deg"], 1)
            )
            metrics["world_dip_angle_deg"] = (
                None if angle_metrics["dip_angle_deg"] is None else round(angle_metrics["dip_angle_deg"], 1)
            )
    normal = world.get("palm_normal_z")
    reading["palm_normal_z"] = None if normal is None else round(float(normal), 4)
    reading["world_landmarks_available"] = bool(world.get("available"))
    reading["world_geometry"] = {
        "palm_normal_z": reading["palm_normal_z"],
        "thumb_ip_angle_deg": world.get("thumb_ip_angle_deg"),
    }
    reading["landmark_confidence"] = visibility
    # MediaPipe HandLandmarker often does not expose visibility/presence; in
    # that case there is no invented penalty.  When it *does* expose those
    # values, a pass just above the hard reject threshold is still less
    # trustworthy than a fully visible hand.  Make that reduction explicit in
    # the published score, instead of allowing an occluded finger to remain a
    # silent 100% close.  At 90%+ observed quality a clear complete gesture
    # still receives the groupmate-compatible 100% result.
    observed_quality = _unit(visibility.get("quality", 1.0))
    high_visibility = not visibility.get("available") or observed_quality >= 0.90
    reliability_cap = 1.0 if high_visibility else observed_quality
    if reliability_cap < 1.0:
        reading["confidence"] = round(min(float(reading.get("confidence", 0.0)), reliability_cap), 4)
    components = reading.get("confidence_components")
    if isinstance(components, dict):
        components["landmark_visibility_quality"] = round(observed_quality, 4)
        components["landmark_reliability_cap"] = round(reliability_cap, 4)
    reading["visibility_reliable_for_repeat"] = high_visibility
    reading["orientation_consistent"] = True
    reading["orientation_status"] = "UNREFERENCED"
    return reading


def _repeat_policy(config: Dict[str, Any]) -> Dict[str, float]:
    minimum = _clamp(config.get("repeated_handsign_min_confidence", 0.50), 0.50, 0.95)
    target = max(minimum, _clamp(config.get("repeated_handsign_confidence_target", 0.75), 0.50, 1.00))
    return {"minimum_confidence": minimum, "confidence_target": target}


class SignalStateMachine:
    """The groupmate-proven open -> tucked close temporal sequence."""

    IDLE = "IDLE"
    WAITING_CLOSE = "WAITING_CLOSE"

    def __init__(self) -> None:
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.sequence_started_at: Optional[float] = None
        self.last_seen_at: Optional[float] = None
        self.tuck_seen = False

    def reset(self) -> None:
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.sequence_started_at = None
        self.tuck_seen = False

    def update(self, reading: Optional[Dict[str, Any]], now: float, config: Dict[str, Any]) -> Tuple[bool, str]:
        if reading is None:
            if self.last_seen_at is not None and now - self.last_seen_at > float(config.get("open_lost_grace_sec", 0.60)):
                self.reset()
            return False, self.state

        self.last_seen_at = now
        if self.state == self.IDLE:
            self.open_streak = self.open_streak + 1 if reading.get("is_open") else 0
            if self.open_streak >= int(config.get("open_hold_frames", 2)):
                self.state = self.WAITING_CLOSE
                self.sequence_started_at = now
                self.close_streak = 0
            return False, self.state

        if self.sequence_started_at is None or now - self.sequence_started_at > float(config.get("sequence_max_duration_sec", 4.0)):
            self.reset()
            return False, self.state

        if reading.get("is_thumb_tuck_phase"):
            self.tuck_seen = True
        # Preserve the original successful open->close behavior: a separately
        # observed tuck is useful feedback, but not a new mandatory phase.
        complete = bool(reading.get("is_closed")) and bool(reading.get("orientation_consistent", True))
        qualified = complete and float(reading.get("confidence", 0.0)) >= float(config.get("confidence_threshold", 0.70))
        if qualified:
            self.close_streak += 1
        else:
            # Decay rather than immediately abandon a normal thumb-tuck phase.
            self.close_streak = max(0, self.close_streak - 1)
        if self.close_streak >= int(config.get("close_hold_frames", 3)):
            self.reset()
            return True, "CONFIRMED"
        return False, self.state


class RepeatedHandsignTracker:
    """Escalate a second qualifying same-hand motion inside a short window."""

    WATCHING_OPEN = "WATCHING_OPEN"
    WATCHING_CLOSE = "WATCHING_CLOSE"

    def __init__(self) -> None:
        self.state = self.WATCHING_OPEN
        self.open_streak = 0
        self.open_started_at: Optional[float] = None
        self.cycle_times: Deque[float] = deque()

    def reset(self) -> None:
        self.state = self.WATCHING_OPEN
        self.open_streak = 0
        self.open_started_at = None
        self.cycle_times.clear()

    def _discard_expired(self, now: float, config: Dict[str, Any]) -> None:
        cutoff = now - float(config.get("repeated_handsign_window_sec", 7.0))
        while self.cycle_times and self.cycle_times[0] < cutoff:
            self.cycle_times.popleft()

    def _snapshot(
        self,
        reading: Optional[Dict[str, Any]],
        config: Dict[str, Any],
        *,
        new_cycle: bool = False,
    ) -> Dict[str, Any]:
        policy = _repeat_policy(config)
        raw = _unit((reading or {}).get("confidence", 0.0))
        qualifying = bool(
            reading
            and reading.get("sos_like_close")
            and reading.get("orientation_consistent", True)
            and reading.get("visibility_reliable_for_repeat", True)
            and raw >= policy["minimum_confidence"]
        )
        minimum_cycles = max(2, int(config.get("repeated_handsign_min_cycles", 2)))
        repeated = len(self.cycle_times) >= minimum_cycles
        escalation_ready = repeated and new_cycle and qualifying
        effective = max(raw, policy["confidence_target"]) if escalation_ready else raw
        return {
            "state": self.state,
            "cycle_count": len(self.cycle_times),
            "latest_cycle_at": self.cycle_times[-1] if self.cycle_times else None,
            "minimum_cycles": minimum_cycles,
            "window_sec": float(config.get("repeated_handsign_window_sec", 7.0)),
            "is_repeated": repeated,
            "minimum_confidence": round(policy["minimum_confidence"], 4),
            "target_confidence": round(policy["confidence_target"], 4),
            "raw_confidence": round(raw, 4),
            "effective_confidence": round(_unit(effective), 4),
            "qualifying_reading": qualifying,
            "escalation_ready": escalation_ready,
            "new_cycle": new_cycle,
            "newly_repeated": escalation_ready,
        }

    def update(self, reading: Optional[Dict[str, Any]], now: float, config: Dict[str, Any]) -> Dict[str, Any]:
        self._discard_expired(now, config)
        if reading is None:
            return self._snapshot(reading, config)
        if self.state == self.WATCHING_OPEN:
            self.open_streak = self.open_streak + 1 if reading.get("is_open") else 0
            if self.open_streak >= int(config.get("open_hold_frames", 2)):
                self.state = self.WATCHING_CLOSE
                self.open_started_at = now
            return self._snapshot(reading, config)
        if self.open_started_at is None or now - self.open_started_at > float(config.get("sequence_max_duration_sec", 4.0)):
            self.state = self.WATCHING_OPEN
            self.open_streak = 1 if reading.get("is_open") else 0
            self.open_started_at = None
            return self._snapshot(reading, config)
        policy = _repeat_policy(config)
        raw = _unit(reading.get("confidence", 0.0))
        if (
            not reading.get("sos_like_close")
            or not reading.get("orientation_consistent", True)
            or not reading.get("visibility_reliable_for_repeat", True)
            or raw < policy["minimum_confidence"]
        ):
            return self._snapshot(reading, config)
        self.cycle_times.append(now)
        self._discard_expired(now, config)
        self.state = self.WATCHING_OPEN
        self.open_streak = 0
        self.open_started_at = None
        return self._snapshot(reading, config, new_cycle=True)


@dataclass
class HandTrack:
    track_id: int
    state_machine: SignalStateMachine = field(default_factory=SignalStateMachine)
    repetition_tracker: RepeatedHandsignTracker = field(default_factory=RepeatedHandsignTracker)
    last_pos: Optional[Tuple[float, float]] = None
    last_seen_at: Optional[float] = None
    palm_normal_reference: Optional[float] = None
    last_trigger_at: float = 0.0
    last_repeated_cycle_alerted_at: float = 0.0


class MultiHandTracker:
    """Maintain independent sequence state for each detected hand.

    A single live hand is deliberately retained across a larger wrist motion;
    folding naturally moves wrist/palm landmarks and MediaPipe may briefly
    reacquire it. Multi-hand assignments stay one-to-one and nearest-neighbour.
    """

    def __init__(self) -> None:
        self.tracks: Dict[int, HandTrack] = {}
        self._next_track_id = 1

    def clear_sequence_state(self) -> None:
        for track in self.tracks.values():
            track.state_machine.reset()
            track.repetition_tracker.reset()
            track.palm_normal_reference = None
            track.last_repeated_cycle_alerted_at = 0.0

    def _assign(self, positions: Sequence[Tuple[float, float]], now: float, config: Dict[str, Any]) -> Dict[int, int]:
        live_tracks = [
            track for track in self.tracks.values()
            if track.last_seen_at is not None and now - track.last_seen_at <= float(config.get("hand_track_stale_sec", 1.0))
        ]
        assignments: Dict[int, int] = {}
        if len(positions) == 1 and len(live_tracks) == 1:
            # Prevent a normal demonstrator movement from resetting the only
            # open->close sequence and creating HAND 2/HAND 3/HAND 4.
            assignments[0] = live_tracks[0].track_id
            return assignments

        configured_max = _clamp(config.get("hand_match_max_dist", 0.32), 0.08, 0.60)
        pairs: List[Tuple[float, int, int]] = []
        for hand_index, position in enumerate(positions):
            if not all(math.isfinite(value) for value in position):
                continue
            for track in live_tracks:
                if track.last_pos is None:
                    continue
                distance = _distance2(position, track.last_pos)
                if distance <= configured_max:
                    pairs.append((distance, hand_index, track.track_id))
        assigned_tracks = set()
        for _distance, hand_index, track_id in sorted(pairs):
            if hand_index not in assignments and track_id not in assigned_tracks:
                assignments[hand_index] = track_id
                assigned_tracks.add(track_id)
        return assignments

    @staticmethod
    def _apply_orientation_reference(reading: Dict[str, Any], track: HandTrack) -> Dict[str, Any]:
        normal = reading.get("palm_normal_z")
        try:
            normal_value = float(normal) if normal is not None else None
        except (TypeError, ValueError):
            normal_value = None
        if normal_value is None or abs(normal_value) < ORIENTATION_SIGN_CONFIDENT:
            reading["orientation_status"] = "UNKNOWN" if normal_value is None else "EDGE"
            return reading
        if reading.get("is_open"):
            track.palm_normal_reference = normal_value
            reading["orientation_status"] = "OPEN_REFERENCE"
            return reading
        reference = track.palm_normal_reference
        if reference is None or abs(reference) < ORIENTATION_SIGN_CONFIDENT:
            reading["orientation_status"] = "UNREFERENCED"
            return reading
        if normal_value * reference >= 0.0:
            reading["orientation_status"] = "PALM_SIDE_MATCH"
            return reading
        # The sign flipped after the open hand: this is the rotated/back-side
        # false-positive case reported in live testing. Lower the displayed
        # score and prevent both normal and repeated alert confirmation.
        corrected = dict(reading)
        corrected["confidence_components"] = dict(reading.get("confidence_components", {}))
        corrected["confidence_components"]["orientation_mismatch_cap"] = 0.30
        corrected["confidence"] = round(min(float(reading.get("confidence", 0.0)), 0.30), 4)
        corrected["is_closed"] = False
        corrected["sos_like_close"] = False
        corrected["orientation_consistent"] = False
        corrected["orientation_status"] = "BACK_OF_OPEN_PALM"
        return corrected

    def update(
        self,
        screen_hands: Sequence[Sequence[Any]],
        world_hands: Sequence[Sequence[Any]],
        now: float,
        config: Dict[str, Any],
        detection_source: str = "VIDEO",
    ) -> List[Dict[str, Any]]:
        positions: List[Tuple[float, float]] = []
        for hand in screen_hands:
            if not hand:
                positions.append((float("nan"), float("nan")))
                continue
            try:
                # Palm centre is less volatile than a wrist point during a fist.
                positions.append((
                    sum(float(hand[index].x) for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
                    sum(float(hand[index].y) for index in PALM_MCP_INDICES) / len(PALM_MCP_INDICES),
                ))
            except (AttributeError, IndexError, TypeError, ValueError):
                positions.append((float("nan"), float("nan")))
        assignments = self._assign(positions, now, config)
        for index in range(len(screen_hands)):
            if index not in assignments:
                track_id = self._next_track_id
                self._next_track_id += 1
                self.tracks[track_id] = HandTrack(track_id=track_id)
                assignments[index] = track_id

        outputs: List[Dict[str, Any]] = []
        seen = set()
        for index, screen_hand in enumerate(screen_hands):
            track_id = assignments[index]
            track = self.tracks[track_id]
            position = positions[index]
            if all(math.isfinite(value) for value in position):
                track.last_pos = position
            track.last_seen_at = now
            seen.add(track_id)
            world_hand = world_hands[index] if index < len(world_hands) else None
            reading = combine_hand_readings(screen_hand, world_hand, config)
            if reading is not None:
                reading = self._apply_orientation_reference(reading, track)
            confirmed, state = track.state_machine.update(reading, now, config)
            repetition = track.repetition_tracker.update(reading, now, config)
            raw = _unit((reading or {}).get("confidence", 0.0))
            effective = _unit(repetition.get("effective_confidence", raw))
            outputs.append({
                "track": track,
                "track_id": track_id,
                "screen_landmarks": screen_hand,
                "world_landmarks_available": world_hand is not None,
                "detection_source": detection_source,
                "reading": reading,
                "confirmed": confirmed,
                "state": state,
                "repetition": repetition,
                "raw_confidence": round(raw, 4),
                "effective_confidence": round(effective, 4),
                "repeat_escalation_ready": bool(repetition.get("escalation_ready")),
            })

        stale_after = float(config.get("hand_track_stale_sec", 1.0))
        for track_id, track in list(self.tracks.items()):
            if track_id in seen:
                continue
            track.state_machine.update(None, now, config)
            track.repetition_tracker.update(None, now, config)
            if track.last_seen_at is not None and now - track.last_seen_at > stale_after:
                del self.tracks[track_id]
        return outputs


# Kept as an explicit name for callers that want the modular tracker type.
ConfiguredHandTracker = MultiHandTracker
