package io.github.bitshiftdev.mavendetective.core.testkit;

import io.github.bitshiftdev.mavendetective.core.check.CheckResult;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Assertions für Prüfergebnisse und Diagnosen: Status, Fix-Typ mit Parametern und Fundstelle
 * (Datei + Zeile) statt wörtlicher Texte.
 */
public final class Diagnoses {

    private Diagnoses() {
    }

    public static void assertConfirmed(CheckResult r) {
        assertEquals(CheckResult.Status.CONFIRMED, r.getStatus(), describe(r));
    }

    public static void assertInfo(CheckResult r) {
        assertEquals(CheckResult.Status.INFO, r.getStatus(), describe(r));
    }

    public static void assertNotApplicable(CheckResult r) {
        assertEquals(CheckResult.Status.NOT_APPLICABLE, r.getStatus(), describe(r));
    }

    /**
     * Der erste Fix hat den Typ und die Parameter ({@code key, value, key, value …}); gibt ihn zurück.
     */
    public static FixAction assertFirstFix(List<FixAction> fixes, String type, String... params) {
        assertFalse(fixes.isEmpty(), "kein Quick-Fix");
        FixAction f = fixes.get(0);
        assertEquals(type, f.getType(), fixes.toString());
        assertParams(f, params);
        return f;
    }

    /** Irgendein Fix hat den Typ und die Parameter; gibt den ersten passenden zurück. */
    public static FixAction assertFix(List<FixAction> fixes, String type, String... params) {
        for (FixAction f : fixes) {
            if (type.equals(f.getType())) {
                assertParams(f, params);
                return f;
            }
        }
        return fail("kein Fix vom Typ " + type + ": " + fixes);
    }

    public static void assertNoFix(List<FixAction> fixes, String type) {
        for (FixAction f : fixes) {
            assertFalse(type.equals(f.getType()), "unerwarteter Fix: " + f);
        }
    }

    /** Ein Beleg zeigt auf genau diese Datei und Zeile. */
    public static Evidence assertEvidence(List<Evidence> evidence, Path file, int line) {
        for (Evidence e : evidence) {
            if (file.toString().equals(e.getFile()) && e.getLine() == line) {
                return e;
            }
        }
        return fail("kein Beleg für " + file + ":" + line + " in " + evidence);
    }

    /** Ein Beleg der Art enthält den Text. */
    public static Evidence assertEvidenceText(List<Evidence> evidence, Evidence.Kind kind, String part) {
        for (Evidence e : evidence) {
            if (e.getKind() == kind && e.getText().contains(part)) {
                return e;
            }
        }
        return fail("kein Beleg " + kind + " mit '" + part + "' in " + evidence);
    }

    public static void assertNoEvidenceText(List<Evidence> evidence, String part) {
        for (Evidence e : evidence) {
            assertFalse(e.getText().contains(part), "unerwarteter Beleg: " + e);
        }
    }

    private static void assertParams(FixAction f, String... params) {
        assertTrue(params.length % 2 == 0, "Parameter paarweise angeben");
        for (int i = 0; i < params.length; i += 2) {
            assertEquals(params[i + 1], f.param(params[i]), "Parameter " + params[i] + " von " + f);
        }
    }

    private static String describe(CheckResult r) {
        return r.getStatus() + " cause=" + r.getCause() + " vars=" + r.getVars()
                + " evidence=" + r.getEvidence() + " fixes=" + r.getFixes();
    }
}
