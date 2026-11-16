package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.State;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Zustände der Dependency-Analysen: nicht geprüft → läuft → fertig → veraltet, Abbruch zurück. */
class AnalysisStatesTest {

    private final List<String> events = new ArrayList<>();
    private final AnalysisStates states = new AnalysisStates((a, s) -> events.add(a + "=" + s.state()));

    @Test
    void startsUncheckedAndRunsToDone() {
        assertEquals(State.NOT_CHECKED, states.get(Analysis.CONFLICTS).state());
        states.running(Analysis.CONFLICTS);
        assertEquals(State.RUNNING, states.get(Analysis.CONFLICTS).state());
        states.set(Analysis.CONFLICTS, Summary.done(3, "3 Konflikte").at("/p/pom.xml:12"));
        Summary done = states.get(Analysis.CONFLICTS);
        assertEquals(State.DONE, done.state());
        assertEquals(3, done.count());
        assertEquals("/p/pom.xml:12", done.location());
        assertEquals(List.of("CONFLICTS=RUNNING", "CONFLICTS=DONE"), events);
    }

    @Test
    void abortReturnsToThePreviousResult() {
        states.set(Analysis.USAGE, Summary.done(2, "2 Funde"));
        states.running(Analysis.USAGE);
        assertEquals(2, states.get(Analysis.USAGE).count(), "die Zahl bleibt während der Rechnung sichtbar");
        states.aborted(Analysis.USAGE);
        assertEquals(State.DONE, states.get(Analysis.USAGE).state());
        assertEquals("2 Funde", states.get(Analysis.USAGE).text());

        states.running(Analysis.DUPLICATES);
        states.aborted(Analysis.DUPLICATES);
        assertEquals(State.NOT_CHECKED, states.get(Analysis.DUPLICATES).state(), "ohne früheres Ergebnis: nicht geprüft");
    }

    @Test
    void runningTwiceKeepsTheStateBeforeTheFirstRun() {
        states.set(Analysis.EXCLUSIONS, Summary.done(1, null));
        states.running(Analysis.EXCLUSIONS);
        states.running(Analysis.EXCLUSIONS);   // zweiter Klick, während die erste Rechnung läuft
        states.aborted(Analysis.EXCLUSIONS);
        assertEquals(State.DONE, states.get(Analysis.EXCLUSIONS).state());
    }

    @Test
    void lateAbortAfterResultChangesNothing() {
        states.running(Analysis.UPGRADE);
        states.set(Analysis.UPGRADE, Summary.done(-1, "fertig"));
        events.clear();
        states.aborted(Analysis.UPGRADE);
        assertEquals(State.DONE, states.get(Analysis.UPGRADE).state());
        assertEquals(List.of(), events);
    }

    @Test
    void importMakesDoneResultsStale() {
        states.set(Analysis.CONFLICTS, Summary.done(3, null));
        states.running(Analysis.USAGE);
        events.clear();
        states.imported();
        assertEquals(State.STALE, states.get(Analysis.CONFLICTS).state());
        assertEquals(3, states.get(Analysis.CONFLICTS).count());
        assertEquals(State.RUNNING, states.get(Analysis.USAGE).state(), "laufende Rechnung bleibt laufend");
        assertEquals(State.NOT_CHECKED, states.get(Analysis.DUPLICATES).state());
        assertEquals(List.of("CONFLICTS=STALE"), events);
    }

    @Test
    void abortAfterImportDoesNotBringBackAFreshLookingResult() {
        states.set(Analysis.DUPLICATES, Summary.done(5, null));
        states.running(Analysis.DUPLICATES);
        states.imported();                       // POMs geändert, während gerechnet wird
        states.aborted(Analysis.DUPLICATES);
        assertEquals(State.STALE, states.get(Analysis.DUPLICATES).state());
        assertNull(states.get(Analysis.DUPLICATES).location());
    }

    /** Review #23: eine Rechnung, die vor einem Import begann, liefert ein veraltetes Ergebnis. */
    @Test
    void resultOfARunStartedBeforeAnImportIsStale() {
        states.running(Analysis.USAGE);
        int started = states.imports();
        states.imported();
        assertEquals(started + 1, states.imports());
        states.set(Analysis.USAGE, Summary.done(4, null));
        assertEquals(State.STALE, states.get(Analysis.USAGE).state());
        assertEquals(4, states.get(Analysis.USAGE).count());

        states.running(Analysis.USAGE);
        states.set(Analysis.USAGE, Summary.done(1, null));
        assertEquals(State.DONE, states.get(Analysis.USAGE).state(), "ohne Import dazwischen frisch");
    }
}
