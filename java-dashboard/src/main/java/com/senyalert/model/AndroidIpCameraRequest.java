package com.senyalert.model;

/**
 * Safe, explicit parameters for making an Android IP Camera reachable through
 * Android Debug Bridge.  The dashboard passes these as process arguments; it
 * never invokes a shell with operator-entered text.
 */
public record AndroidIpCameraRequest(String adbConnection, int laptopPort, int cameraServerPort) {
    private static final String ADB_TARGET_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    public AndroidIpCameraRequest {
        adbConnection = adbConnection == null ? "" : adbConnection.trim();
        if (!adbConnection.matches(ADB_TARGET_PATTERN)) {
            throw new IllegalArgumentException(
                    "ADB connection must be a device serial or host:port, using only letters, numbers, dots, hyphens, colons, and underscores.");
        }
        laptopPort = validatePort(laptopPort, "Laptop listening port");
        cameraServerPort = validatePort(cameraServerPort, "Phone camera server port");
    }

    /** A network ADB target needs an explicit connect before it can be forwarded. */
    public boolean needsNetworkConnect() {
        return adbConnection.contains(":");
    }

    private static int validatePort(int value, String label) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535.");
        }
        return value;
    }
}
