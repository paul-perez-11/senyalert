package com.senyalert.view.ui;

import com.senyalert.service.ExportDefaults;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.swing.SwingUtilities;

/**
 * Native platform pickers for operator-selected files. Windows file selection
 * uses {@link FileDialog}; folder selection uses the native Windows Forms
 * folder dialog because AWT has no directory-only mode. A configured default
 * folder may be created when an operator opens its picker; no export file is
 * created or replaced by the picker itself.
 */
public final class NativeFileDialogs {
    private static final Duration WINDOWS_FOLDER_TIMEOUT = Duration.ofMinutes(10);

    private NativeFileDialogs() { }

    public static Optional<Path> openFile(
            Component parent, String title, ExportDefaults.Kind kind, List<String> extensions) {
        FileDialog dialog = dialog(parent, title, FileDialog.LOAD);
        dialog.setDirectory(existingDirectory(ExportDefaults.directory(kind)).toString());
        installFilter(dialog, extensions);
        dialog.setVisible(true);
        return selectedFile(dialog);
    }

    public static Optional<Path> saveFile(
            Component parent, String title, ExportDefaults.Kind kind, String suggestedFilename, List<String> extensions) {
        FileDialog dialog = dialog(parent, title, FileDialog.SAVE);
        dialog.setDirectory(existingDirectory(ExportDefaults.directory(kind)).toString());
        dialog.setFile(suggestedFilename == null || suggestedFilename.isBlank() ? "senyalert-export" : suggestedFilename);
        installFilter(dialog, extensions);
        dialog.setVisible(true);
        return selectedFile(dialog);
    }

    /**
     * Choose a destination folder. On Windows this opens the actual Windows
     * folder dialog. Other systems retain a native AWT save-dialog fallback
     * and use its selected directory only.
     */
    public static Optional<Path> chooseDirectory(Component parent, String title, ExportDefaults.Kind kind) {
        Path initial = existingDirectory(ExportDefaults.directory(kind));
        if (isWindows()) {
            FolderDialogResponse response = windowsFolderDialog(title, initial);
            if (response.handled()) {
                return response.selection();
            }
        }
        return awtDirectoryFallback(parent, title, initial);
    }

    private static FolderDialogResponse windowsFolderDialog(String title, Path initial) {
        String script = "Add-Type -AssemblyName System.Windows.Forms\n"
                + "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)\n"
                + "$decode = { param([string]$value) [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }\n"
                + "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog\n"
                + "$dialog.Description = & $decode '" + base64(title) + "'\n"
                + "$dialog.ShowNewFolderButton = $true\n"
                + "$dialog.SelectedPath = & $decode '" + base64(initial.toString()) + "'\n"
                + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($dialog.SelectedPath) }\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = null;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded).redirectErrorStream(true).start();
            boolean finished = process.waitFor(WINDOWS_FOLDER_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return FolderDialogResponse.unavailable();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return FolderDialogResponse.unavailable();
            }
            return output.isBlank()
                    ? FolderDialogResponse.cancelled()
                    : FolderDialogResponse.selected(Path.of(output).toAbsolutePath().normalize());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FolderDialogResponse.unavailable();
        } catch (IOException | IllegalArgumentException unavailable) {
            return FolderDialogResponse.unavailable();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Optional<Path> awtDirectoryFallback(Component parent, String title, Path initial) {
        FileDialog dialog = dialog(parent, title, FileDialog.SAVE);
        dialog.setDirectory(initial.toString());
        // The file name is intentionally ignored; FileDialog reports the folder
        // the operator navigated to and does not create this placeholder file.
        dialog.setFile("SenyAlert exports");
        dialog.setVisible(true);
        String directory = dialog.getDirectory();
        return directory == null || directory.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(directory).toAbsolutePath().normalize());
    }

    private static FileDialog dialog(Component parent, String title, int mode) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner instanceof Dialog dialog) {
            return new FileDialog(dialog, title, mode);
        }
        if (owner instanceof Frame frame) {
            return new FileDialog(frame, title, mode);
        }
        return new FileDialog((Frame) null, title, mode);
    }

    private static void installFilter(FileDialog dialog, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return;
        }
        List<String> allowed = extensions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        if (allowed.isEmpty()) {
            return;
        }
        dialog.setFilenameFilter((directory, name) -> {
            File candidate = new File(directory, name);
            if (candidate.isDirectory()) {
                return true;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            return allowed.stream().anyMatch(extension -> lower.endsWith("." + extension));
        });
    }

    private static Optional<Path> selectedFile(FileDialog dialog) {
        String directory = dialog.getDirectory();
        String file = dialog.getFile();
        if (directory == null || directory.isBlank() || file == null || file.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(directory, file).toAbsolutePath().normalize());
    }

    private static Path existingDirectory(Path requested) {
        Path candidate = requested == null ? Path.of(System.getProperty("user.home", ".")) : requested.toAbsolutePath().normalize();
        try {
            Files.createDirectories(candidate);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        } catch (IOException | SecurityException ignored) {
            // An inaccessible configured default falls back to its nearest
            // existing parent. The dialog still lets the operator choose one.
        }
        while (candidate != null && !Files.isDirectory(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate == null ? Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize() : candidate;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private record FolderDialogResponse(boolean handled, Optional<Path> selection) {
        static FolderDialogResponse unavailable() { return new FolderDialogResponse(false, Optional.empty()); }
        static FolderDialogResponse cancelled() { return new FolderDialogResponse(true, Optional.empty()); }
        static FolderDialogResponse selected(Path path) { return new FolderDialogResponse(true, Optional.of(path)); }
    }
}
