package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.State;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Die Zustandsübergänge von {@link AnalysisResults} ohne Projekt und Plattform: nicht geprüft → läuft →
 * fertig → (nach Maven-Import) veraltet; Abbruch kehrt zum Stand vor der Rechnung zurück. Jede Änderung
 * geht an {@code onChange}.
 */
final class AnalysisStates {

    private final Map<Analysis, Summary> summaries = new EnumMap<>(Analysis.class);
    /** Stand vor {@link #running}, für Abbruch und Fehler. */
    private final Map<Analysis, Summary> before = new EnumMap<>(Analysis.class);
    private final BiConsumer<Analysis, Summary> onChange;

    AnalysisStates(@NotNull BiConsumer<Analysis, Summary> onChange) {
        this.onChange = onChange;
    }

    @NotNull Summary get(@NotNull Analysis analysis) {
        return summaries.getOrDefault(analysis, Summary.NOT_CHECKED);
    }

    /** Eine Rechnung beginnt; der bisherige Stand bleibt für {@link #aborted} gemerkt. */
    void running(@NotNull Analysis analysis) {
        Summary now = get(analysis);
        if (now.state() != State.RUNNING) {
            before.put(analysis, now);
        }
        put(analysis, now.with(State.RUNNING));
    }

    void set(@NotNull Analysis analysis, @NotNull Summary summary) {
        before.remove(analysis);
        put(analysis, summary);
    }

    /** Abgebrochen oder fehlgeschlagen: zurück zum Stand vor der Rechnung. */
    void aborted(@NotNull Analysis analysis) {
        Summary prev = before.remove(analysis);
        if (get(analysis).state() == State.RUNNING) {
            put(analysis, prev != null ? prev : Summary.NOT_CHECKED);
        }
    }

    /**
     * Nach dem Import: Fertiges ist veraltet (die Zahl verschwindet). Läuft gerade eine Rechnung, wird
     * der gemerkte Stand veraltet – ein Abbruch darf danach kein „fertig“ von vor dem Import zurückbringen.
     */
    void imported() {
        for (Analysis a : Analysis.values()) {
            Summary s = get(a);
            if (s.state() == State.DONE) {
                put(a, s.with(State.STALE));
            } else if (s.state() == State.RUNNING && before.get(a) != null && before.get(a).state() == State.DONE) {
                before.put(a, before.get(a).with(State.STALE));
            }
        }
    }

    private void put(Analysis analysis, Summary summary) {
        summaries.put(analysis, summary);
        onChange.accept(analysis, summary);
    }
}
