"""Visual-only OpenCV HUD for the SenyAlert ingestion preview.

This module consumes already-computed readings.  It never evaluates finger
geometry, changes a confidence score, or advances a gesture state machine.
That separation makes a bad overlay incapable of changing detection behavior
and keeps the vision layer testable without OpenCV.
"""

from __future__ import annotations

from typing import Any, Dict, Optional, Sequence, Tuple

import cv2
import numpy as np


def _clamp_fraction(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, number))


def _sensitivity(config: Dict[str, Any]) -> float:
    try:
        value = float(config.get("gesture_sensitivity", 1.0))
    except (TypeError, ValueError):
        value = 1.0
    return max(0.50, min(1.50, value))


def _repeat_policy(config: Dict[str, Any]) -> Tuple[float, float]:
    try:
        minimum = float(config.get("repeated_handsign_min_confidence", 0.50))
    except (TypeError, ValueError):
        minimum = 0.50
    try:
        target = float(config.get("repeated_handsign_confidence_target", 0.75))
    except (TypeError, ValueError):
        target = 0.75
    minimum = max(0.50, min(0.95, minimum))
    return minimum, max(minimum, min(1.00, target))


def hand_diagnostic_lines(result: Dict[str, Any], config: Dict[str, Any]) -> Tuple[str, str]:
    """Return compact, ASCII-only diagnostics for one tracked hand.

    ASCII avoids the unsupported checkbox/arrow glyphs that showed as ``??``
    in the live Windows OpenCV window.  These lines intentionally display the
    same flags that scoring used, so an operator can see why a close did not
    qualify instead of reverse-engineering a single percentage.
    """
    reading = result.get("reading") or {}
    if not reading:
        return "LANDMARKS NOT SCORABLE", "Waiting for a reliable hand frame"
    metrics = reading.get("finger_metrics") or {}
    index = metrics.get("index") or {}
    components = reading.get("confidence_components") or {}
    thumb_flag = "YES" if reading.get("thumb_tucked") else "NO"
    index_flag = "YES" if reading.get("index_folded") else "NO"
    folded = reading.get("curled_count", 0)
    required = reading.get("required_finger_count", 4)
    thumb_ratio = reading.get("thumb_tip_to_palm_ratio")
    thumb_threshold = components.get("phase_thumb_tuck_ratio")
    index_margin = index.get("curl_margin")
    finger_threshold = components.get("phase_finger_margin")
    orientation = str(reading.get("orientation_status", "UNREFERENCED")).replace("_", " ")
    line_one = f"TUCK {thumb_flag}  INDEX {index_flag}  FOLD {folded}/{required}"
    thumb_text = "-" if thumb_ratio is None else f"{float(thumb_ratio):.2f}"
    thumb_gate = "-" if thumb_threshold is None else f"{float(thumb_threshold):.2f}"
    index_text = "-" if index_margin is None else f"{float(index_margin):.2f}"
    index_gate = "-" if finger_threshold is None else f"{float(finger_threshold):.2f}"
    line_two = f"T {thumb_text}/{thumb_gate}  I {index_text}/{index_gate}  {orientation}"
    return line_one, line_two


def draw_preview(
    frame: Any,
    hand_results: Sequence[Dict[str, Any]],
    people: Dict[str, Any],
    context: Optional[Dict[str, Any]],
    config: Dict[str, Any],
    paused: bool,
    hand_status: str = "",
) -> Any:
    """Render video and HUD on separate layers without touching CV state."""
    target_width, target_height = config.get("preview_resolution", (960, 540))
    source_height, source_width = frame.shape[:2]
    video_scale = min(1.0, float(target_width) / max(1, source_width), float(target_height) / max(1, source_height))
    video_width = max(1, round(source_width * video_scale))
    video_height = max(1, round(source_height * video_scale))
    video = frame if (video_width, video_height) == (source_width, source_height) else cv2.resize(
        frame, (video_width, video_height), interpolation=cv2.INTER_AREA
    )

    canvas_scale = min(1.0, 1920 / max(1, source_width), 1080 / max(1, source_height))
    width = max(1, round(source_width * canvas_scale))
    height = max(1, round(source_height * canvas_scale))
    preview = np.full((height, width, 3), (22, 29, 35), dtype=np.uint8)
    fit = min(width / max(1, video_width), height / max(1, video_height))
    content_width = max(1, round(video_width * fit))
    content_height = max(1, round(video_height * fit))
    content_left = (width - content_width) // 2
    content_top = (height - content_height) // 2
    preview[content_top:content_top + content_height, content_left:content_left + content_width] = cv2.resize(
        video,
        (content_width, content_height),
        interpolation=cv2.INTER_LINEAR if fit > 1.0 else cv2.INTER_AREA,
    )

    ui = max(0.58, min(width / 1280.0, height / 720.0))

    def px(value: float) -> int:
        return max(1, round(value * ui))

    navy = (26, 35, 42)
    cyan = (244, 198, 48)
    blue = (255, 166, 43)
    white = (246, 249, 252)
    muted = (196, 205, 214)
    green = (88, 214, 82)
    amber = (38, 182, 255)
    red = (64, 75, 232)

    def glass(left: int, top: int, right: int, bottom: int, color: Tuple[int, int, int], opacity: float = 0.82) -> None:
        left = max(0, min(width - 1, left))
        top = max(0, min(height - 1, top))
        right = max(left + 1, min(width, right))
        bottom = max(top + 1, min(height, bottom))
        region = preview[top:bottom, left:right]
        tint = np.full_like(region, color)
        cv2.addWeighted(tint, opacity, region, 1.0 - opacity, 0, region)

    def text(value: str, point: Tuple[int, int], scale: float, color: Tuple[int, int, int], thickness: int = 1) -> None:
        cv2.putText(
            preview,
            value,
            point,
            cv2.FONT_HERSHEY_SIMPLEX,
            scale * ui,
            color,
            max(1, round(thickness * ui)),
            cv2.LINE_AA,
        )

    def clipped(value: str, maximum: int) -> str:
        return value if len(value) <= maximum else value[: max(1, maximum - 3)] + "..."

    header = px(68)
    margin = px(16)
    card_top = header + px(12)
    card_height = px(58)
    gap = px(12)
    cards = 3 if context else 2
    card_width = max(px(135), (width - margin * 2 - gap * (cards - 1)) // cards)
    glass(0, 0, width, header, navy, 0.88)
    live_color = red if paused else green
    cv2.circle(preview, (margin + px(7), px(31)), px(7), live_color, cv2.FILLED)
    text("SenyAlert", (margin + px(24), px(29)), 0.72, white, 2)
    text("VISION INGESTION", (margin + px(25), px(51)), 0.34, cyan, 1)
    camera_label = clipped(str(config.get("camera_id", "CAMERA")), 28)
    camera_x = max(width // 2, width - max(px(220), len(camera_label) * px(10)))
    text(camera_label, (camera_x, px(28)), 0.45, white, 1)
    text("PAUSED" if paused else f"LIVE  SENS {_sensitivity(config):.0%}", (camera_x, px(50)), 0.34, live_color, 1)

    def card(index: int, label: str, value: str, accent: Tuple[int, int, int]) -> None:
        left = margin + index * (card_width + gap)
        glass(left, card_top, left + card_width, card_top + card_height, navy, 0.80)
        cv2.rectangle(preview, (left, card_top), (left + px(4), card_top + card_height), accent, cv2.FILLED)
        text(label, (left + px(14), card_top + px(20)), 0.32, muted, 1)
        text(clipped(value, 24), (left + px(14), card_top + px(44)), 0.54, white, 2)

    detected_people = max(0, int(people.get("count", 0)))
    effective_people = max(detected_people, int((context or {}).get("people_count", detected_people)))
    people_value = f"{effective_people} seen" if not people.get("stale") else f"{effective_people} pending"
    card(0, "OCCUPANCY", people_value, cyan if not people.get("stale") else amber)
    card(1, "HANDS", f"{len(hand_results)} / {config.get('max_hands', 0)}", blue)
    if context:
        mode = str(context.get("alert_mode", "LOUD"))
        card(2, "NEXT ALERT", mode, amber if mode == "QUIET" else red)

    footer_height = px(42)
    footer_top = height - footer_height
    for person in people.get("transient_tracks", []):
        box = person.get("box") or {}
        x = content_left + int(float(box.get("x", 0.0)) * content_width)
        y = content_top + int(float(box.get("y", 0.0)) * content_height)
        box_width = int(float(box.get("width", 0.0)) * content_width)
        box_height = int(float(box.get("height", 0.0)) * content_height)
        cv2.rectangle(preview, (x, y), (x + box_width, y + box_height), cyan, px(2), cv2.LINE_AA)
        label = f"PERSON {str(person.get('transient_track_id', '?')).rsplit('-', 1)[-1]}"
        label_top = max(header + px(4), y - px(22))
        glass(x, label_top, x + max(px(80), len(label) * px(8)), label_top + px(20), navy)
        text(label, (x + px(6), label_top + px(15)), 0.34, cyan, 1)

    connections = (
        (0, 1), (1, 2), (2, 3), (3, 4),
        (0, 5), (5, 6), (6, 7), (7, 8),
        (0, 9), (9, 10), (10, 11), (11, 12),
        (0, 13), (13, 14), (14, 15), (15, 16),
        (0, 17), (17, 18), (18, 19), (19, 20),
    )
    for result in hand_results:
        reading = result.get("reading") or {}
        repetition = result.get("repetition") or {}
        confirmed = result.get("state") in {"WAITING_CLOSE", "CONFIRMED"} or bool(repetition.get("is_repeated"))
        color = green if confirmed else amber
        landmarks = result.get("screen_landmarks") or []
        for first, second in connections:
            if first < len(landmarks) and second < len(landmarks):
                cv2.line(
                    preview,
                    (content_left + int(landmarks[first].x * content_width), content_top + int(landmarks[first].y * content_height)),
                    (content_left + int(landmarks[second].x * content_width), content_top + int(landmarks[second].y * content_height)),
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
        if not landmarks:
            continue
        wrist = landmarks[0]
        hand_x = content_left + int(wrist.x * content_width)
        hand_y = content_top + int(wrist.y * content_height)
        raw = _clamp_fraction(result.get("raw_confidence", reading.get("confidence", 0.0)))
        effective = _clamp_fraction(result.get("effective_confidence", raw))
        state = "REPEATED" if repetition.get("is_repeated") else str(result.get("state", "IDLE"))
        first_line, second_line = hand_diagnostic_lines(result, config)
        label_top = max(card_top + card_height + px(8), hand_y - px(64))
        glass(hand_x, label_top, min(width - px(4), hand_x + px(300)), label_top + px(56), navy, 0.84)
        text(f"HAND {result.get('track_id', '?')}  {state}", (hand_x + px(8), label_top + px(16)), 0.35, color, 1)
        text(clipped(first_line, 42), (hand_x + px(8), label_top + px(33)), 0.27, white, 1)
        text(clipped(second_line, 42), (hand_x + px(8), label_top + px(49)), 0.25, muted, 1)

        base = landmarks[5] if len(landmarks) > 5 else wrist
        confidence_x = content_left + int(base.x * content_width)
        confidence_y = content_top + int(base.y * content_height)
        confidence_label = f"CONF {raw:.0%}" if effective <= raw + 1e-6 else f"CONF {raw:.0%}->{effective:.0%}"
        confidence_color = green if reading.get("is_closed") and effective >= float(config.get("confidence_threshold", 0.70)) else amber
        label_width = cv2.getTextSize(confidence_label, cv2.FONT_HERSHEY_SIMPLEX, 0.34 * ui, px(1))[0][0]
        left = min(width - label_width - px(12), max(px(4), confidence_x + px(10)))
        top = min(footer_top - px(24), max(card_top + card_height + px(8), confidence_y - px(24)))
        glass(left - px(5), top - px(15), left + label_width + px(6), top + px(5), navy, 0.88)
        text(confidence_label, (left, top), 0.34, confidence_color, 1)

    glass(0, footer_top, width, height, navy, 0.88)
    if paused:
        footer, footer_color = "Detection paused - preview remains visible", red
    elif not hand_results and hand_status:
        footer, footer_color = hand_status, amber
    else:
        repeat_minimum, repeat_target = _repeat_policy(config)
        footer = (
            f"Hand tracking  SENS {_sensitivity(config):.0%}  "
            f"repeat >={repeat_minimum:.0%}->{repeat_target:.0%}/{float(config.get('repeated_handsign_window_sec', 7.0)):.0f}s  ESC closes preview"
        )
        footer_color = green
    text(clipped(footer, 128), (margin, height - px(14)), 0.38, footer_color, 1)
    return preview

