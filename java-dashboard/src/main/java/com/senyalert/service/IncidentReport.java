package com.senyalert.service;

import com.senyalert.model.Incident;
import com.senyalert.model.IncidentEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.json.JSONObject;

/** One consistent evidence representation for the viewer, clipboard and exported sidecars. */
public final class IncidentReport {
    private IncidentReport() { }

    public static JSONObject json(IncidentEvidence evidence) {
        Incident i = evidence.incident();
        JSONObject detection = new JSONObject().put("timestamp_original", i.detectionTimestamp())
                .put("timestamp_epoch_seconds", i.eventTimestampEpochSeconds())
                .put("timestamp_utc", Instant.ofEpochSecond(i.eventTimestampEpochSeconds()).toString())
                .put("camera_id", i.cameraId()).put("location", i.location())
                .put("incident_type", i.incidentType()).put("confidence", i.confidence())
                .put("confidence_percent", i.confidence() * 100).put("triage_context", i.triageContext())
                .put("alert_mode", i.alertMode().name()).put("people_count", i.peopleCount())
                .put("people_count_stale", i.peopleCountStale()).put("occupancy_status", i.occupancyStatus())
                .put("hand_count", i.handCount()).put("signaler_count", i.signalerCount())
                .put("signaler_track_id", i.signalerTrackId()).put("signaler_bounds", i.signalerBounds());
        return new JSONObject().put("schema_version", 1).put("incident_id", i.id()).put("event_token", i.eventToken())
                .put("generated_at_utc", Instant.now().toString()).put("detection", detection)
                .put("operator_record", new JSONObject().put("status", i.status().name()).put("note", i.operatorNotes()))
                .put("evidence", new JSONObject()
                        .put("snapshot", media(evidence.snapshotBytes(), evidence.snapshotMimeType(), i.snapshotPath()))
                        .put("video", media(evidence.videoBytes(), evidence.videoMimeType(), i.videoPath()))
                        .put("media_ready", i.mediaReady()).put("media_status", i.mediaStatus())
                        .put("video_duration_seconds", i.videoDurationSeconds()));
    }

    public static String text(IncidentEvidence evidence) { return text(json(evidence)); }

    public static String text(JSONObject report) {
        JSONObject d = report.getJSONObject("detection"), o = report.getJSONObject("operator_record"), e = report.getJSONObject("evidence");
        StringBuilder s = new StringBuilder("SENYALERT INCIDENT RECORD\n========================\n");
        s.append("Incident #").append(report.getLong("incident_id")).append("  |  ").append(o.getString("status"))
                .append("\nEvent token: ").append(report.optString("event_token"))
                .append("\nReport generated (UTC): ").append(report.getString("generated_at_utc"))
                .append("\n\nDETECTION & LOCATION\n")
                .append("Detected (original): ").append(d.optString("timestamp_original"))
                .append("\nDetected (UTC): ").append(d.getString("timestamp_utc"))
                .append("\nEpoch seconds: ").append(d.getLong("timestamp_epoch_seconds"))
                .append("\nCamera: ").append(d.optString("camera_id"))
                .append("\nLocation: ").append(d.optString("location"))
                .append("\nType: ").append(d.optString("incident_type"))
                .append("\nConfidence: ").append(String.format(java.util.Locale.ROOT, "%.2f%%", d.getDouble("confidence_percent")))
                .append(" (raw: ").append(d.getDouble("confidence")).append(")")
                .append("\nAlert mode: ").append(d.optString("alert_mode"))
                .append("\nTriage context: ").append(d.optString("triage_context"))
                .append("\n\nSCENE & TRACKING\nPeople / hands / signalers: ").append(d.getInt("people_count"))
                .append(" / ").append(d.getInt("hand_count")).append(" / ").append(d.getInt("signaler_count"))
                .append("\nOccupancy: ").append(d.optString("occupancy_status"))
                .append("\nPeople count stale: ").append(d.getBoolean("people_count_stale"))
                .append("\nSignaler track: ").append(d.optString("signaler_track_id"))
                .append("\nBounds: ").append(d.optString("signaler_bounds"))
                .append("\n\nOPERATOR RECORD\nStatus: ").append(o.optString("status"))
                .append("\nNote:\n").append(o.optString("note").isBlank() ? "No operator note recorded." : o.optString("note"))
                .append("\n\nEVIDENCE INTEGRITY\nMedia status: ").append(e.optString("media_status"))
                .append("\nVideo duration: ").append(e.getDouble("video_duration_seconds")).append(" seconds\n");
        for (String kind : new String[]{"snapshot", "video"}) {
            JSONObject m = e.getJSONObject(kind);
            s.append(kind).append(": ").append(m.getBoolean("available") ? "available" : "unavailable")
                    .append(" | ").append(m.optString("mime_type")).append(" | ").append(m.getLong("bytes")).append(" bytes")
                    .append("\nSHA-256: ").append(m.optString("sha256", "unavailable")).append('\n');
        }
        return s.toString();
    }

    private static JSONObject media(byte[] bytes, String mime, String source) {
        JSONObject result = new JSONObject().put("mime_type", mime == null ? "" : mime).put("available", false).put("bytes", 0);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            if (bytes != null && bytes.length > 0) { digest.update(bytes); size = bytes.length; }
            else if (source != null && !source.isBlank() && Files.isRegularFile(Path.of(source))) {
                size = Files.size(Path.of(source));
                try (var input = Files.newInputStream(Path.of(source))) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
            } else return result;
            return result.put("available", true).put("bytes", size).put("sha256", HexFormat.of().formatHex(digest.digest()));
        } catch (Exception failure) { return result.put("integrity_status", "Could not read evidence"); }
    }
}
