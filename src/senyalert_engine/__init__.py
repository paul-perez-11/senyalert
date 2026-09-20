"""Modular SenyAlert Python ingestion components.

The compatibility launcher remains :mod:`python-prototype`, while this package
owns the independently testable gesture and HUD layers.  Runtime capture,
WebSocket, evidence, people counting, and dashboard event contracts continue
to be orchestrated by the launcher during the staged refactor.
"""

from .gesture import (
    ConfiguredHandTracker,
    HandTrack,
    MultiHandTracker,
    RepeatedHandsignTracker,
    SignalStateMachine,
    analyze_screen_hand,
    analyze_world_hand,
    combine_hand_readings,
    effective_thresholds,
    landmark_confidence_status,
)

__all__ = [
    "ConfiguredHandTracker",
    "HandTrack",
    "MultiHandTracker",
    "RepeatedHandsignTracker",
    "SignalStateMachine",
    "analyze_screen_hand",
    "analyze_world_hand",
    "combine_hand_readings",
    "effective_thresholds",
    "landmark_confidence_status",
]
