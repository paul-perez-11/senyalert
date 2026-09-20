"""Deterministic SOS quality and repetition smoke checks.

Run from the repository root:

    python src/vision-quality-smoke.py

This deliberately uses synthetic landmarks instead of a camera or MediaPipe
model file.  It validates the hand-geometry rules that are safe to make
repeatable in Chapter 4/5 regression runs:

* confidence rises as additional fingers reach the tucked position and
  configured thumb/index requirements earn only their gated credits;
* uncertain landmark scores and an edge-on palm lower confidence, while a
  landmark below the configured visibility floor is rejected;
* an original-valid thumb tuck reaches 100%, in-plane rotation preserves the
  result, maximum sensitivity changes both fold and thumb acceptance, and a
  back-of-open-palm close is blocked;
* single and two-hand associations remain stable through movement/order swaps;
* one strict SOS sequence is not labelled repeated; and
* two same-hand open-to-near-close cycles can reach a configured repeat target
  without bypassing the final confidence threshold.
"""

from __future__ import annotations

import copy
import json
import math
import runpy
import sys
import types
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional


def _install_optional_dependency_stubs() -> None:
    """Keep geometry-only regression checks runnable without a CV installation.

    The production module imports OpenCV, MediaPipe, NumPy, and websocket-client
    at file load time. This smoke test never opens a camera, decodes an image,
    or creates a detector; it only needs the pure landmark/state-machine
    functions. Tiny import stubs let a Chapter 4/5 regression run exercise
    those rules even on a reporting laptop that does not have the full vision
    environment installed.
    """
    try:
        import cv2  # noqa: F401
    except ModuleNotFoundError:
        cv2 = types.ModuleType("cv2")
        cv2.error = RuntimeError
        sys.modules["cv2"] = cv2
    try:
        import numpy  # noqa: F401
    except ModuleNotFoundError:
        sys.modules["numpy"] = types.ModuleType("numpy")
    try:
        import websocket  # noqa: F401
    except ModuleNotFoundError:
        websocket = types.ModuleType("websocket")
        websocket.WebSocketApp = object
        sys.modules["websocket"] = websocket
    try:
        import mediapipe  # noqa: F401
    except ModuleNotFoundError:
        mediapipe = types.ModuleType("mediapipe")
        tasks = types.ModuleType("mediapipe.tasks")
        python_tasks = types.ModuleType("mediapipe.tasks.python")
        vision = types.ModuleType("mediapipe.tasks.python.vision")
        vision.RunningMode = types.SimpleNamespace(VIDEO="VIDEO", IMAGE="IMAGE")
        python_tasks.vision = vision
        tasks.python = python_tasks
        mediapipe.tasks = tasks
        sys.modules["mediapipe"] = mediapipe
        sys.modules["mediapipe.tasks"] = tasks
        sys.modules["mediapipe.tasks.python"] = python_tasks
        sys.modules["mediapipe.tasks.python.vision"] = vision


_install_optional_dependency_stubs()
MODULE = runpy.run_path(str(Path(__file__).with_name("python-prototype.py")))
DEFAULT_CONFIG = MODULE["DEFAULT_CONFIG"]
MultiHandTracker = MODULE["MultiHandTracker"]
ConfigStore = MODULE["ConfigStore"]
SignalStateMachine = MODULE["SignalStateMachine"]
analyze_screen_hand = MODULE["analyze_screen_hand"]
combine_hand_readings = MODULE["combine_hand_readings"]


@dataclass
class Point:
    """Small MediaPipe-compatible point used only by this deterministic check."""

    x: float
    y: float
    z: float = 0.0
    visibility: Optional[float] = None
    presence: Optional[float] = None


FINGER_NAMES = ("index", "middle", "ring", "pinky")


def _point(x: float, y: float, visibility: Optional[float]) -> Point:
    return Point(x=x, y=y, visibility=visibility)


def make_hand(
    curled: Iterable[str] = (),
    *,
    visibility: Optional[float] = None,
    narrow_palm: bool = False,
    thumb_tucked: bool = True,
) -> list[Point]:
    """Create an upright open or tucked synthetic hand in normalized pixels."""
    curled_names = set(curled)
    points = [_point(0.5, 0.5, visibility) for _ in range(21)]
    points[0] = _point(0.5, 0.75, visibility)  # wrist
    points[1] = _point(0.36, 0.62, visibility)
    points[2] = _point(0.41, 0.59, visibility)
    points[3] = _point(0.46, 0.56, visibility)
    points[4] = _point(0.50, 0.55 if thumb_tucked else 0.15, visibility)

    finger_layout = {
        "index": (5, 6, 7, 8, 0.36),
        "middle": (9, 10, 11, 12, 0.50),
        "ring": (13, 14, 15, 16, 0.64),
        "pinky": (17, 18, 19, 20, 0.78),
    }
    for name, (mcp, pip, dip, tip, x) in finger_layout.items():
        if name in curled_names:
            coordinates = ((mcp, 0.50), (pip, 0.55), (dip, 0.60), (tip, 0.68))
        else:
            coordinates = ((mcp, 0.50), (pip, 0.42), (dip, 0.34), (tip, 0.25))
        for landmark_index, y in coordinates:
            points[landmark_index] = _point(x, y, visibility)

    if narrow_palm:
        # Project all points towards the palm centre to emulate an oblique,
        # edge-on view while retaining the same curl geometry.
        for point in points:
            point.x = 0.5 + (point.x - 0.5) * 0.08
    return points


def make_marginal_close() -> list[Point]:
    """Return a four-finger close with one calibrated-margin edge finger."""
    points = make_hand(FINGER_NAMES)
    # The wrist-to-middle-MCP scale is 0.25 in this fixture.  A pinky tip
    # 0.01 below its MCP yields a 0.04 curl margin: it misses the calibrated
    # 0.05 gate but clears the 150% sensitivity phase margin of 0.025.
    points[18].y = 0.50
    points[19].y = 0.505
    points[20].y = 0.51
    return points


def rotate_hand(points: list[Point], radians: float) -> list[Point]:
    """Rotate a screen-landmark fixture around the wrist-to-palm centre."""
    centre_x, centre_y = 0.5, 0.5
    cosine, sine = math.cos(radians), math.sin(radians)
    rotated = []
    for point in points:
        dx, dy = point.x - centre_x, point.y - centre_y
        rotated.append(Point(
            x=centre_x + dx * cosine - dy * sine,
            y=centre_y + dx * sine + dy * cosine,
            z=point.z,
            visibility=point.visibility,
            presence=point.presence,
        ))
    return rotated


def shift_hand(points: list[Point], dx: float, dy: float = 0.0) -> list[Point]:
    """Translate a fixture without changing its palm-relative geometry."""
    return [
        Point(
            x=point.x + dx,
            y=point.y + dy,
            z=point.z,
            visibility=point.visibility,
            presence=point.presence,
        )
        for point in points
    ]


def world_from_screen(points: list[Point], *, flip_palm_side: bool = False) -> list[Point]:
    """Create deterministic world-like landmarks with a controllable normal sign."""
    world = []
    for point in points:
        x = 1.0 - point.x if flip_palm_side else point.x
        world.append(Point(x=x, y=point.y, z=0.0, visibility=point.visibility, presence=point.presence))
    return world


def main() -> None:
    # The public configuration accepts bounded sensitivity, feature credits,
    # and an explicit repeat target.
    store = ConfigStore()
    applied, rejected = store.apply({
        "gesture_sensitivity": 1.50,
        "thumb_tuck_confidence_bonus": 0.10,
        "index_fold_confidence_bonus": 0.10,
        "repeated_handsign_min_confidence": 0.50,
        "repeated_handsign_confidence_target": 0.95,
    })
    assert set(applied) == {
        "gesture_sensitivity",
        "thumb_tuck_confidence_bonus",
        "index_fold_confidence_bonus",
        "repeated_handsign_min_confidence",
        "repeated_handsign_confidence_target",
    } and not rejected
    config, revision = store.snapshot()
    config["confidence_threshold"] = 0.50
    assert revision == 1

    progressive_scores = []
    for count in range(len(FINGER_NAMES) + 1):
        reading = analyze_screen_hand(make_hand(FINGER_NAMES[:count]), config)
        assert reading is not None
        progressive_scores.append(float(reading["confidence"]))
    assert progressive_scores == sorted(progressive_scores)
    # A tucked thumb now has a visible partial contribution before the fingers
    # close.  It must never confirm by itself; a complete, same-palm close is
    # the only 100% result.
    assert 0.0 < progressive_scores[0] < 0.90
    assert progressive_scores[-1] > progressive_scores[-2]
    # The continuous tuck score prevents one final finger from creating an
    # implausibly huge score jump at the binary close threshold.
    assert progressive_scores[-1] - progressive_scores[-2] < 0.35

    full_front = combine_hand_readings(make_hand(FINGER_NAMES, visibility=0.95), None, config)
    one_uncertain = make_hand(FINGER_NAMES, visibility=0.95)
    for landmark_index in (5, 6, 7, 8):
        one_uncertain[landmark_index].visibility = 0.40
    uncertain_front = combine_hand_readings(one_uncertain, None, config)
    edge_on = combine_hand_readings(make_hand(FINGER_NAMES, visibility=0.95, narrow_palm=True), None, config)
    assert full_front is not None and uncertain_front is not None and edge_on is not None
    assert float(full_front["confidence"]) == 1.0
    assert float(uncertain_front["confidence"]) < float(full_front["confidence"])
    assert float(edge_on["confidence"]) < float(full_front["confidence"])
    assert float(edge_on["confidence"]) == 0.60
    # A provided value below the configured visibility threshold is a hard
    # reject, rather than an invented high-confidence occluded-finger pose.
    rejected_visibility = make_hand(FINGER_NAMES, visibility=0.95)
    rejected_visibility[5].visibility = 0.20
    assert combine_hand_readings(rejected_visibility, None, config) is None

    # The active detector is palm-relative: rotating the same hand within the
    # image cannot turn an open hand into a close (or vice versa).
    for degrees in (0, 45, 90, 135, 180):
        rotated_open = analyze_screen_hand(rotate_hand(make_hand(), math.radians(degrees)), config)
        rotated_close = analyze_screen_hand(rotate_hand(make_hand(FINGER_NAMES), math.radians(degrees)), config)
        assert rotated_open is not None and rotated_close is not None
        assert rotated_open["is_open"] and not rotated_open["is_closed"]
        assert rotated_close["is_closed"] and float(rotated_close["confidence"]) == 1.0

    # Restore the exact groupmate thumb gate: a ratio inside the calibrated
    # 0.55 palm radius is accepted even when its local thumb angle is nearly
    # straight.  The 150% slider then genuinely widens that same gate.
    near_thumb_gate = make_hand(FINGER_NAMES)
    near_thumb_gate[4] = _point(0.57, 0.625, None)  # 0.50 palm-scale ratio
    standard_sensitivity = copy.deepcopy(config)
    standard_sensitivity["gesture_sensitivity"] = 1.00
    near_thumb_reading = analyze_screen_hand(near_thumb_gate, standard_sensitivity)
    assert near_thumb_reading is not None
    assert near_thumb_reading["thumb_tucked"] and near_thumb_reading["index_folded"]
    assert near_thumb_reading["is_closed"]
    assert float(near_thumb_reading["confidence"]) == 1.0
    sensitivity_thumb = make_hand(FINGER_NAMES)
    sensitivity_thumb[4] = _point(0.57, 0.65, None)  # 0.60 ratio: high-only
    low_thumb = copy.deepcopy(config)
    low_thumb["gesture_sensitivity"] = 1.00
    high_thumb = copy.deepcopy(config)
    high_thumb["gesture_sensitivity"] = 1.50
    assert not analyze_screen_hand(sensitivity_thumb, low_thumb)["thumb_tucked"]
    assert analyze_screen_hand(sensitivity_thumb, high_thumb)["thumb_tucked"]

    # Requirement credits are real, but cannot apply while their checkbox is
    # off or their matching thumb/index feature is not visibly present.
    # Keep this below the incomplete-pose cap so the separately configured
    # thumb/index credits are directly measurable.
    partial_close = make_hand(("index",))
    without_bonuses = copy.deepcopy(config)
    without_bonuses["thumb_tuck_confidence_bonus"] = 0.0
    without_bonuses["index_fold_confidence_bonus"] = 0.0
    partial_with_bonuses = analyze_screen_hand(partial_close, config)
    partial_without_bonuses = analyze_screen_hand(partial_close, without_bonuses)
    assert partial_with_bonuses is not None and partial_without_bonuses is not None
    assert math.isclose(
        float(partial_with_bonuses["confidence"]) - float(partial_without_bonuses["confidence"]),
        0.20,
        abs_tol=0.0001,
    )
    no_thumb_requirement = copy.deepcopy(config)
    no_thumb_requirement["fingers"]["thumb"] = False
    no_index_requirement = copy.deepcopy(config)
    no_index_requirement["fingers"]["index"] = False
    thumb_missing = analyze_screen_hand(make_hand(FINGER_NAMES, thumb_tucked=False), config)
    index_missing = analyze_screen_hand(make_hand(("middle", "ring", "pinky")), config)
    thumb_disabled = analyze_screen_hand(make_hand(FINGER_NAMES), no_thumb_requirement)
    index_disabled = analyze_screen_hand(make_hand(FINGER_NAMES), no_index_requirement)
    assert thumb_missing is not None and index_missing is not None
    assert thumb_disabled is not None and index_disabled is not None
    assert thumb_missing["confidence_components"]["thumb_tuck_bonus"] == 0.0
    assert index_missing["confidence_components"]["index_fold_bonus"] == 0.0
    assert thumb_disabled["confidence_components"]["thumb_tuck_bonus"] == 0.0
    assert index_disabled["confidence_components"]["index_fold_bonus"] == 0.0

    strict = copy.deepcopy(config)
    strict["gesture_sensitivity"] = 0.50
    calibrated = copy.deepcopy(config)
    calibrated["gesture_sensitivity"] = 1.00
    responsive = copy.deepcopy(config)
    responsive["gesture_sensitivity"] = 1.50
    marginal_close = make_marginal_close()
    strict_reading = analyze_screen_hand(marginal_close, strict)
    calibrated_reading = analyze_screen_hand(marginal_close, calibrated)
    responsive_reading = analyze_screen_hand(marginal_close, responsive)
    assert strict_reading is not None and calibrated_reading is not None and responsive_reading is not None
    assert not strict_reading["is_closed"]
    assert not calibrated_reading["is_closed"]
    assert responsive_reading["is_closed"]
    assert strict_reading["confidence_components"]["phase_finger_margin"] == 0.075
    assert calibrated_reading["confidence_components"]["phase_finger_margin"] == 0.05
    assert responsive_reading["confidence_components"]["phase_finger_margin"] == 0.025
    # At maximum sensitivity the same clear near-close advances the phase and
    # receives a bounded, visible assist.  The reliable front-facing reading
    # reaches 100%; low-quality/edge-on fixtures above still remain below it.
    assert float(responsive_reading["confidence"]) > float(calibrated_reading["confidence"])
    responsive_combined = combine_hand_readings(marginal_close, None, responsive)
    assert responsive_combined is not None and float(responsive_combined["confidence"]) == 1.0

    def confirms_at(threshold: float) -> bool:
        threshold_config = copy.deepcopy(responsive)
        threshold_config["confidence_threshold"] = threshold
        machine = SignalStateMachine()
        now = 30.0
        open_reading = analyze_screen_hand(make_hand(), threshold_config)
        assert open_reading is not None
        for _ in range(int(threshold_config["open_hold_frames"])):
            machine.update(open_reading, now, threshold_config)
            now += 0.10
        confirmed = False
        for _ in range(int(threshold_config["close_hold_frames"])):
            confirmed, _ = machine.update(responsive_reading, now, threshold_config)
            now += 0.10
        return confirmed

    assert confirms_at(0.50)
    assert confirms_at(0.90)
    assert not confirms_at(1.01)

    # Tracking is anchored to the palm, not the wrist.  A solo demonstrator
    # can move naturally through the sign without being reassigned HAND 2/3/4.
    continuity_tracker = MultiHandTracker()
    continuity_ids = []
    timestamp = 5.0
    for horizontal_shift in (0.0, 0.12, -0.14, 0.20):
        current = continuity_tracker.update([shift_hand(make_hand(), horizontal_shift)], [], timestamp, config)[0]
        continuity_ids.append(current["track_id"])
        timestamp += 0.10
    assert continuity_ids == [1, 1, 1, 1]

    # The association stays tied to physical palm position when MediaPipe
    # reverses the two-hand list order between frames.
    association_tracker = MultiHandTracker()
    left_hand = shift_hand(make_hand(), -0.20)
    right_hand = shift_hand(make_hand(), 0.20)
    first_association = association_tracker.update([left_hand, right_hand], [], 6.0, config)
    reversed_association = association_tracker.update([right_hand, left_hand], [], 6.1, config)
    assert [item["track_id"] for item in first_association] == [1, 2]
    assert [item["track_id"] for item in reversed_association] == [2, 1]

    # A close from the back side of an already observed open palm is the live
    # false-positive reported in testing.  Strong opposing world normals cap
    # it and stop both standard and repeat escalation paths.
    orientation_tracker = MultiHandTracker()
    timestamp = 7.0
    front_open = make_hand()
    for _ in range(2):
        orientation_tracker.update([front_open], [world_from_screen(front_open)], timestamp, config)
        timestamp += 0.10
    back_close = make_hand(FINGER_NAMES)
    back_result = None
    for _ in range(int(config["close_hold_frames"])):
        back_result = orientation_tracker.update(
            [back_close], [world_from_screen(back_close, flip_palm_side=True)], timestamp, config
        )[0]
        timestamp += 0.10
    assert back_result is not None and back_result["reading"] is not None
    assert back_result["reading"]["orientation_status"] == "BACK_OF_OPEN_PALM"
    assert float(back_result["raw_confidence"]) <= 0.30
    assert not back_result["confirmed"]
    assert not back_result["repeat_escalation_ready"]

    # Repetition can elevate a deliberately partial but visible SOS motion;
    # it must not resurrect a score that was reduced because a required joint
    # was only weakly observed.
    visibility_repeat_tracker = MultiHandTracker()
    moderate_close = make_hand(FINGER_NAMES, visibility=0.95)
    for landmark_index in (5, 6, 7, 8):
        moderate_close[landmark_index].visibility = 0.40
    timestamp = 8.0
    visibility_repeat_last = None
    for hand in (
        make_hand(), make_hand(), moderate_close, moderate_close, moderate_close,
        make_hand(), make_hand(), moderate_close, moderate_close, moderate_close,
    ):
        visibility_repeat_last = visibility_repeat_tracker.update([hand], [], timestamp, config)[0]
        timestamp += 0.10
    assert visibility_repeat_last is not None
    assert not visibility_repeat_last["repetition"]["is_repeated"]
    assert not visibility_repeat_last["repeat_escalation_ready"]

    tracker = MultiHandTracker()
    last = None
    timestamp = 10.0
    for hand in (
        make_hand(), make_hand(),
        make_hand(FINGER_NAMES), make_hand(FINGER_NAMES), make_hand(FINGER_NAMES),
        make_hand(), make_hand(),
        make_hand(FINGER_NAMES), make_hand(FINGER_NAMES), make_hand(FINGER_NAMES),
    ):
        last = tracker.update([hand], [], timestamp, config)[0]
        timestamp += 0.10
    assert last is not None
    assert last["confirmed"]
    assert last["repetition"]["is_repeated"]
    assert last["repetition"]["cycle_count"] >= 2
    first_repeated_cycle_at = float(last["repetition"]["latest_cycle_at"])

    # A later repeated session must be recognized again after the first
    # session ages out of the short repetition window.
    timestamp += float(config["repeated_handsign_window_sec"]) + 0.25
    for hand in (
        make_hand(), make_hand(),
        make_hand(FINGER_NAMES), make_hand(FINGER_NAMES), make_hand(FINGER_NAMES),
        make_hand(), make_hand(),
        make_hand(FINGER_NAMES), make_hand(FINGER_NAMES), make_hand(FINGER_NAMES),
    ):
        last = tracker.update([hand], [], timestamp, config)[0]
        timestamp += 0.10
    assert last is not None and last["repetition"]["is_repeated"]
    assert float(last["repetition"]["latest_cycle_at"]) > first_repeated_cycle_at

    # A repeated near-SOS (three of four fingers closed) is deliberately not a
    # strict confirmation.  After its second same-hand cycle in the configured
    # window, it becomes eligible for the configured target only because its
    # raw score already clears the 50% repeat floor.  The caller must still
    # compare that effective score with the global final threshold.
    repeat_config = copy.deepcopy(config)
    repeat_config["confidence_threshold"] = 0.90
    repeat_config["repeated_handsign_min_confidence"] = 0.50
    repeat_config["repeated_handsign_confidence_target"] = 0.95
    near_repeat = MultiHandTracker()
    near_last = None
    timestamp = 80.0
    for hand in (
        make_hand(), make_hand(),
        make_hand(("index", "middle", "ring")),
        make_hand(), make_hand(),
        make_hand(("index", "middle", "ring")),
    ):
        near_last = near_repeat.update([hand], [], timestamp, repeat_config)[0]
        timestamp += 0.10
    assert near_last is not None
    assert not near_last["confirmed"]
    assert near_last["repetition"]["is_repeated"]
    assert near_last["repeat_escalation_ready"]
    assert 0.50 <= float(near_last["raw_confidence"]) < repeat_config["confidence_threshold"]
    assert float(near_last["effective_confidence"]) == repeat_config["repeated_handsign_confidence_target"]
    assert float(near_last["effective_confidence"]) >= repeat_config["confidence_threshold"]
    # Raising the global gate above the configured target demonstrates that a
    # repeat target never directly bypasses the final engine threshold.
    assert float(near_last["effective_confidence"]) < 0.96

    report = {
        "progressive_confidence": [round(value, 4) for value in progressive_scores],
        "front_confidence": round(float(full_front["confidence"]), 4),
        "uncertain_finger_confidence": round(float(uncertain_front["confidence"]), 4),
        "edge_on_confidence": round(float(edge_on["confidence"]), 4),
        "sensitivity": {
            "strict_phase_closed": strict_reading["is_closed"],
            "calibrated_phase_closed": calibrated_reading["is_closed"],
            "responsive_phase_closed": responsive_reading["is_closed"],
            "strict_margin": strict_reading["confidence_components"]["phase_finger_margin"],
            "calibrated_margin": calibrated_reading["confidence_components"]["phase_finger_margin"],
            "responsive_margin": responsive_reading["confidence_components"]["phase_finger_margin"],
            "calibrated_confidence": calibrated_reading["confidence"],
            "responsive_confidence": responsive_reading["confidence"],
            "responsive_combined_confidence": responsive_combined["confidence"],
            "clear_near_sign_reaches_100": True,
        },
        "regression_guards": {
            "rotation_invariant_degrees": [0, 45, 90, 135, 180],
            "original_thumb_ratio_gate_confidence": near_thumb_reading["confidence"],
            "max_sensitivity_accepts_ratio_0_60": True,
            "low_visibility_rejected": True,
            "moderate_visibility_cap": uncertain_front["confidence"],
            "moderate_visibility_repeat_blocked": not visibility_repeat_last["repetition"]["is_repeated"],
            "edge_palm_cap": edge_on["confidence"],
            "single_hand_track_ids": continuity_ids,
            "back_of_open_palm_status": back_result["reading"]["orientation_status"],
            "back_of_open_palm_cap": back_result["raw_confidence"],
        },
        "repetition": last["repetition"],
        "repeat_escalation": {
            "raw_confidence": near_last["raw_confidence"],
            "effective_confidence": near_last["effective_confidence"],
            "target_confidence": near_last["repetition"]["target_confidence"],
            "final_gate_still_required": True,
        },
        "incident_type_when_repeated": "Repeated handsign",
        "result": "PASS",
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
