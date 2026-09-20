package com.senyalert.model;

/**
 * Defines the deliberately limited evidence export bundles available from the
 * archive. Export never changes the incident store or the engine's source
 * evidence paths.
 */
public enum ArchiveExportMode {
    /** Human-readable incident report plus every available image and video artifact. */
    RECORD_BUNDLE,
    /** Snapshot/image artifacts only. */
    SNAPSHOTS,
    /** Video artifacts only. */
    VIDEOS,
    /** Snapshot and video artifacts without the record report. */
    MEDIA
}
