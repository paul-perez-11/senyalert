package com.senyalert.tools;

import com.senyalert.model.AlertMode;
import com.senyalert.model.DistressEvent;
import com.senyalert.model.EngineSettings;
import com.senyalert.service.AlertPolicy;

/**
 * Repeatable no-camera check for the two-camera crowd-policy contract.
 *
 * <p>Run with {@code mvn -q compile exec:java
 * -Dexec.mainClass=com.senyalert.tools.CrowdPolicySmoke}.  It creates no
 * database records and does not start a Swing window or an alarm.</p>
 */
public final class CrowdPolicySmoke {
    private CrowdPolicySmoke() {
    }

    public static void main(String[] args) {
        AlertPolicy policy = new AlertPolicy();
        EngineSettings quietAtOne = new EngineSettings(
                0.70, 1.50, 0.10, 0.10, 0.50, 0.75, 5, 5, true, true, "0", "CAM-LAPTOP", "Desk", 2,
                true, 8, 1, true, EngineSettings.defaults().cameras());
        EngineSettings quietAtFour = new EngineSettings(
                0.70, 1.50, 0.10, 0.10, 0.50, 0.75, 5, 5, true, true, "0", "CAM-LAPTOP", "Desk", 2,
                true, 8, 4, true, EngineSettings.defaults().cameras());
        EngineSettings audibleDisabled = new EngineSettings(
                0.70, 1.50, 0.10, 0.10, 0.50, 0.75, 5, 5, true, true, "0", "CAM-LAPTOP", "Desk", 2,
                true, 8, 4, false, EngineSettings.defaults().cameras());

        expect("phone signaler floor honored", AlertMode.QUIET,
                policy.decide(event("CAM-PHONE", 1, false, false), quietAtOne));
        expect("normal low-occupancy alert remains audible", AlertMode.AUDIBLE,
                policy.decide(event("CAM-PHONE", 1, false, false), quietAtFour));
        expect("engine quiet hint cannot be re-escalated", AlertMode.QUIET,
                policy.decide(event("CAM-PHONE", 0, false, true), quietAtFour));
        expect("audible alarm switch applies to every camera", AlertMode.QUIET,
                policy.decide(event("CAM-PHONE", 0, false, false), audibleDisabled));

        System.out.println("Crowd-policy smoke passed: camera-independent quiet policy verified.");
    }

    private static DistressEvent event(
            String cameraId, int peopleCount, boolean stale, boolean engineRequestedQuiet) {
        return new DistressEvent(
                "crowd-policy-smoke-" + cameraId + "-" + peopleCount,
                cameraId,
                0.80,
                System.currentTimeMillis() / 1_000L,
                "Smoke test",
                "STANDARD",
                "SOS handsign",
                peopleCount,
                stale,
                stale ? "STALE" : "CURRENT",
                engineRequestedQuiet,
                1,
                1,
                "hand-smoke",
                "",
                "",
                "",
                "image/jpeg");
    }

    private static void expect(String label, AlertMode expected, AlertMode actual) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }
}
