package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ergebnis einer Prüfung.
 * <ul>
 * <li>{@link Status#CONFIRMED}: Ursache bestätigt – die Diagnose wird „sicher“, eine Ursache der Prüfung ersetzt die der Regel.</li>
 * <li>{@link Status#INFO}: liefert Belege, Variablen oder Quick-Fixes, bestätigt aber nichts.</li>
 * <li>{@link Status#NOT_APPLICABLE}: hat nichts beizutragen.</li>
 * </ul>
 */
public final class CheckResult {

    public enum Status { CONFIRMED, INFO, NOT_APPLICABLE }

    private final Status status;
    private String cause;
    private final List<Evidence> evidence = new ArrayList<Evidence>();
    private final List<FixAction> fixes = new ArrayList<FixAction>();
    private final Map<String, String> vars = new LinkedHashMap<String, String>();

    private CheckResult(Status status) {
        this.status = status;
    }

    public static CheckResult notApplicable() {
        return new CheckResult(Status.NOT_APPLICABLE);
    }

    public static CheckResult info() {
        return new CheckResult(Status.INFO);
    }

    public static CheckResult confirmed() {
        return new CheckResult(Status.CONFIRMED);
    }

    /** Ursache als Vorlage; Platzhalter wie {@code {artifact}} werden später ersetzt. */
    public CheckResult cause(String template) {
        this.cause = template;
        return this;
    }

    public CheckResult evidence(Evidence e) {
        evidence.add(e);
        return this;
    }

    public CheckResult fix(FixAction f) {
        if (f != null) {
            fixes.add(f);
        }
        return this;
    }

    public CheckResult var(String name, String value) {
        if (value != null) {
            vars.put(name, value);
        }
        return this;
    }

    public Status getStatus() { return status; }

    public String getCause() { return cause; }

    public List<Evidence> getEvidence() { return Collections.unmodifiableList(evidence); }

    public List<FixAction> getFixes() { return Collections.unmodifiableList(fixes); }

    public Map<String, String> getVars() { return Collections.unmodifiableMap(vars); }
}
