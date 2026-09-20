package com.senyalert.model;

/** Explicit, user-confirmed source-media removal choices for an incident record. */
public record MediaDeletionOptions(boolean deleteSnapshot, boolean deleteVideo) {
    public static MediaDeletionOptions recordsOnly() {
        return new MediaDeletionOptions(false, false);
    }

    public boolean deletesAnyMedia() {
        return deleteSnapshot || deleteVideo;
    }
}
