package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import io.github.bitshiftdev.mavendetective.idea.MavenDetectiveBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Orte im Format {@code pfad:zeile} (Zeile 1-basiert, optional) – so stehen sie in Links der
 * Detailtexte und in Quick-Fix-Parametern. Öffnen per Klick oder über F4 / Jump to Source.
 */
public final class Locations {

    private Locations() {
    }

    public static @NotNull String of(@NotNull Object file, int line) {
        return file + (line > 0 ? ":" + line : "");
    }

    /** Ohne Refresh des Dateisystems, darf in einer Read-Action laufen (Data-Context, {@code lazy}). */
    public static @Nullable Navigatable navigatable(@NotNull Project project, @Nullable String location) {
        Parsed p = parse(location);
        if (p == null) {
            return null;
        }
        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(p.path);
        return vf == null ? null : new OpenFileDescriptor(project, vf, p.line, 0);
    }

    /** Öffnet den Ort im Editor (EDT); fehlt die Datei, sagt eine Benachrichtigung das. */
    public static void open(@NotNull Project project, @NotNull String location) {
        Parsed p = parse(location);
        VirtualFile vf = p == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p.path);
        if (vf == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("Maven Detective")
                    .createNotification(MavenDetectiveBundle.message("notification.file.not.found", p == null ? location : p.path),
                            NotificationType.WARNING)
                    .notify(project);
            return;
        }
        new OpenFileDescriptor(project, vf, p.line, 0).navigate(true);
    }

    private record Parsed(Path path, int line) {
    }

    private static @Nullable Parsed parse(@Nullable String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location;
        int line = 0;
        int colon = location.lastIndexOf(':');
        if (colon > 1 && location.substring(colon + 1).matches("-?\\d+")) {
            path = location.substring(0, colon);
            line = Math.max(0, Integer.parseInt(location.substring(colon + 1)) - 1);
        }
        try {
            return new Parsed(Paths.get(path), line);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
