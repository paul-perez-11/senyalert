package com.senyalert.model;

/**
 * Defines the deliberately limited evidence export bundles available from the
 * archive. Export never changes the incident store or the engine's source
 * evidence paths.
 */
public enum ArchiveExportMode {
    /** JSON and plaintext record data plus every available image and video artifact. */
    RECORD_BUNDLE,
    /** Snapshot/image artifacts with JSON and plaintext record data. */
    SNAPSHOTS,
    /** Video artifacts with JSON and plaintext record data. */
    VIDEOS,
    /** Snapshot and video artifacts, each accompanied by JSON and plaintext record data. */
    MEDIA
}
