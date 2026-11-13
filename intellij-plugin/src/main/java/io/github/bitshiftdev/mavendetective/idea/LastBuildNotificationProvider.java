package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;

/**
 * Editor-Banner (UI-Plan U4) über einer Datei, an der der letzte Build aus der IDE gescheitert ist:
 * „Der letzte Build scheiterte an dieser Datei (Zeile 42) – #3 Parent-POM nicht auflösbar“ mit
 * „Diagnose anzeigen“. Grundlage sind Belege und Quick-Fix-Parameter der Diagnose (Datei, Zeile).
 * Verschwindet mit dem nächsten Lauf, der diese Datei nicht nennt, oder per „Ausblenden“.
 */
public final class LastBuildNotificationProvider implements EditorNotificationProvider, DumbAware {

    /** Die Diagnose, die die Datei nennt, und die Zeile (0 = keine). */
    record Hit(@NotNull Diagnosis diagnosis, int line) {
    }

    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {
        if (!file.isInLocalFileSystem()) {
            return null;
        }
        BuildDoctorService service = BuildDoctorService.getInstance(project);
        // getLastRun() ist volatile und nennt nur Läufe aus der IDE; der Verlauf gehört dem EDT
        BuildDoctorService.Run run = service.getLastRun();
        if (run == null || run.green() || run.result() == null || run.fromClipboard() || run.at() == service.getBannerDismissed()) {
            return null;
        }
        Hit hit = find(run.result().diagnoses(), file.toNioPath(), service.projectDir());
        if (hit == null) {
            return null;
        }
        String text = text(hit);
        long at = run.at();
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Error);
            panel.setText(text);
            panel.createActionLabel(MavenDetectiveBundle.message("banner.show"), () -> Navigator.buildDoctor(project, null));
            panel.createActionLabel(MavenDetectiveBundle.message("banner.dismiss"), () -> service.dismissBanner(at));
            return panel;
        };
    }

    static @Nls @NotNull String text(@NotNull Hit hit) {
        String what = DiagnosisCard.headline(hit.diagnosis());
        return hit.line() > 0 ? MavenDetectiveBundle.message("banner.text.line", hit.line(), what)
                : MavenDetectiveBundle.message("banner.text", what);
    }

    /**
     * Erste Diagnose, deren Belege (Datei) oder Quick-Fixes (Parameter {@code file}) {@code file} nennen;
     * relative Pfade gelten ab {@code base}. Mit Zeile, wenn eine genannt ist.
     */
    static @Nullable Hit find(@NotNull List<Diagnosis> diagnoses, @NotNull Path file, @Nullable Path base) {
        Path target = normalize(file.toString(), null);
        if (target == null) {
            return null;
        }
        for (Diagnosis d : diagnoses) {
            Hit found = null;
            for (Evidence e : d.getEvidence()) {
                if (target.equals(normalize(e.getFile(), base))) {
                    found = better(found, new Hit(d, Math.max(0, e.getLine())));
                }
            }
            for (FixAction f : d.getFixes()) {
                if (target.equals(normalize(f.param("file"), base))) {
                    found = better(found, new Hit(d, Math.max(0, parse(f.param("line")))));
                }
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Ein Treffer mit Zeile ist besser als einer ohne. */
    private static Hit better(@Nullable Hit a, Hit b) {
        return a == null || a.line() == 0 && b.line() > 0 ? b : a;
    }

    private static @Nullable Path normalize(@Nullable String file, @Nullable Path base) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(file.trim());
            if (!p.isAbsolute() && base != null) {
                p = base.resolve(p);
            }
            return p.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
