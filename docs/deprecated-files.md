# Deprecated-file review

The following UI source file is no longer reachable from the application. It
has the required delete-safe marker at its first line:

- `java-dashboard/src/main/java/com/senyalert/view/VideoUploadPanel.java`

The former Video analysis and Uploaded Evidence UI entries are removed from
the current dashboard. The remaining uploaded-video protocol code is retained
because the Python engine can still send historical uploaded-video status
messages, and preserving that compatibility does not expose an uploaded UI.

The public compatibility facades in `com.senyalert` and the preserved
historical evidence directories are deliberately retained. They must not be
treated as safe to remove without checking external users and retained data.
