package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.ConsoleFolding;
import com.intellij.openapi.project.Project;
import io.github.bitshiftdev.mavendetective.core.io.LogOutline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Log-Navigator in der Konsole: Mavens Download-Zeilen ({@code Downloading from …}, {@code Downloaded
 * from …}, {@code Progress (1): …}) klappen zu einer Zeile „… 120 Downloads …“ zusammen – der Fehler
 * steht dann nicht mehr hinter Hunderten Zeilen Rauschen. Abschaltbar in den Einstellungen.
 * Registriert in plugin.xml ({@code console.folding}).
 */
public final class DownloadConsoleFolding extends ConsoleFolding {

    @Override
    public boolean shouldFoldLine(@NotNull Project project, @NotNull String line) {
        return DetectiveSettings.getInstance().isFoldDownloads() && LogOutline.isDownloadNoise(line);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull Project project, @NotNull List<String> lines) {
        return MavenDetectiveBundle.message("console.fold.downloads", lines.size(), String.valueOf(lines.size()));
    }
}
