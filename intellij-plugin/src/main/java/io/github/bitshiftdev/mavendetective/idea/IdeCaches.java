package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.util.LowMemoryWatcher;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import org.jetbrains.annotations.NotNull;

/**
 * Die langlebigen Caches des Plugins, je einer für die ganze IDE: gelesene POMs (Editor-Hinweise, Inspections,
 * Reactor-Suche) und JAR-Inhalte (Diagnose, „Doppelte Klassen“, „Nutzung“, Live-Inspection). Früher hielt jede
 * Stelle ihren eigenen – dieselben JARs lagen doppelt, dieselben POMs dreifach im Speicher, bis die IDE endete.
 * Geleert, wenn der Speicher knapp wird und wenn ein Projekt geschlossen wird.
 */
final class IdeCaches {

    static final PomCache POMS = new PomCache();
    /** Derselbe Index wie in der Diagnose des Kerns ({@link JarIndex#shared()}). */
    static final JarIndex JARS = JarIndex.shared();

    static {
        var app = ApplicationManager.getApplication();
        if (app != null) {   // Unit-Tests ohne Plattform
            LowMemoryWatcher.register(IdeCaches::clear, app);
            app.getMessageBus().connect(app).subscribe(ProjectCloseListener.TOPIC, new ProjectCloseListener() {
                @Override
                public void projectClosed(@NotNull Project project) {
                    clear();
                }
            });
        }
    }

    private IdeCaches() {
    }

    static void clear() {
        POMS.clear();
        JARS.clear();
    }
}
