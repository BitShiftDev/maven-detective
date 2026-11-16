package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Letzter Stand jeder Dependency-Analyse eines Projekts – für die Zähler in der Navigation des Tabs
 * „Dependencies“ und die Kacheln der Übersicht. Nach jedem Maven-Import werden fertige Ergebnisse als
 * {@link State#STALE veraltet} markiert; danach erfahren die Seiten vom Import und rechnen selbst neu
 * (günstige) oder zeigen einen Hinweisbalken (teure). Zustand nur auf dem EDT lesen und schreiben.
 */
@Service(Service.Level.PROJECT)
public final class AnalysisResults implements Disposable {

    /** Die Analysen von Modul A, die einen Stand haben. */
    public enum Analysis {
        /** A2 – günstig, rechnet von selbst. */
        CONFLICTS,
        /** A3 – teuer, nur auf Klick. */
        DUPLICATES,
        /** A4 – günstig (nur POMs), rechnet die Übersicht. */
        EXCLUSIONS,
        /** A5 – teuer, nur auf Klick. */
        USAGE,
        /** A6 – nach Eingabe. */
        UPGRADE
    }

    /** Ein Zustand, eine Bedeutung (UI-Plan, Leitlinie 4). */
    public enum State { NOT_CHECKED, RUNNING, DONE, STALE }

    /**
     * Stand einer Analyse.
     *
     * @param count    Zahl der Befunde; negativ = keine Zahl (z. B. Upgrade-Simulation)
     * @param text     kurze Zusammenfassung für Kachel und Tooltip; {@code null} = keine
     * @param location {@code pfad:zeile} des ersten Befunds, falls es einen Sprung gibt
     */
    public record Summary(@NotNull State state, int count, @Nls @Nullable String text, @Nullable String location) {

        static final Summary NOT_CHECKED = new Summary(State.NOT_CHECKED, -1, null, null);

        public static @NotNull Summary done(int count, @Nls @Nullable String text) {
            return new Summary(State.DONE, count, text, null);
        }

        public @NotNull Summary at(@Nullable String location) {
            return new Summary(state, count, text, location);
        }

        @NotNull Summary with(@NotNull State s) {
            return new Summary(s, count, text, location);
        }
    }

    public interface Listener {
        /** Stand einer Analyse hat sich geändert (EDT). */
        void changed(@NotNull Analysis analysis, @NotNull Summary summary);

        /** Ein Maven-Import ist fertig; fertige Stände sind schon als veraltet markiert (EDT). */
        default void mavenImported() {
        }
    }

    private final Project project;
    private final AnalysisStates states = new AnalysisStates(this::changed);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public AnalysisResults(@NotNull Project project) {
        this.project = project;
        IdeMaven.onImport(project, this, () -> ApplicationManager.getApplication().invokeLater(this::imported, project.getDisposed()));
    }

    public static @NotNull AnalysisResults getInstance(@NotNull Project project) {
        return project.getService(AnalysisResults.class);
    }

    public @NotNull Summary get(@NotNull Analysis analysis) {
        return states.get(analysis);
    }

    /** Meldet sich ab, sobald {@code parent} entsorgt wird. */
    public void addListener(@NotNull Listener l, @NotNull Disposable parent) {
        listeners.add(l);
        Disposer.register(parent, () -> listeners.remove(l));
    }

    /** Eine Rechnung beginnt; der bisherige Stand bleibt für {@link #aborted} gemerkt. */
    public void running(@NotNull Analysis analysis) {
        if (!project.isDisposed()) {
            states.running(analysis);
        }
    }

    public void set(@NotNull Analysis analysis, @NotNull Summary summary) {
        if (!project.isDisposed()) {
            states.set(analysis, summary);
        }
    }

    /** Abgebrochen oder fehlgeschlagen: zurück zum Stand vor der Rechnung. */
    public void aborted(@NotNull Analysis analysis) {
        if (!project.isDisposed()) {
            states.aborted(analysis);
        }
    }

    /** Nach dem Import: Fertiges ist veraltet (die Zahl verschwindet), dann reagieren die Seiten. */
    void imported() {
        if (project.isDisposed()) {
            return;
        }
        states.imported();
        for (Listener l : listeners) {
            l.mavenImported();
        }
    }

    private void changed(Analysis analysis, Summary summary) {
        for (Listener l : listeners) {
            l.changed(analysis, summary);
        }
    }

    @Override
    public void dispose() {
        listeners.clear();
    }
}
