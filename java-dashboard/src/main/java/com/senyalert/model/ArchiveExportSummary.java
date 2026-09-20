package com.senyalert.model;

import java.nio.file.Path;

/** Result returned after archive assets have been copied to a user-selected folder. */
public record ArchiveExportSummary(
        Path destination,
        int recordsExported,
        int snapshotsExported,
        int videosExported) {
}
