package io.github.bitshiftdev.mavendetective.core.diagnosis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Inhalt einer Diagnose-Karte: was, warum, Belege, Sicherheit und Quick-Fixes. */
public final class Diagnosis {

    public static final String UNKNOWN_RULE = "unknown";

    private final String ruleId;
    private final int number;
    private final String category;
    private final String cause;
    private final Confidence confidence;
    private final List<Evidence> evidence;
    private final List<FixAction> fixes;

    public Diagnosis(String ruleId, int number, String category, String cause, Confidence confidence,
                     List<Evidence> evidence, List<FixAction> fixes) {
        this.ruleId = ruleId;
        this.number = number;
        this.category = category;
        this.cause = cause;
        this.confidence = confidence;
        this.evidence = Collections.unmodifiableList(new ArrayList<Evidence>(evidence));
        this.fixes = Collections.unmodifiableList(new ArrayList<FixAction>(fixes));
    }

    public String getRuleId() { return ruleId; }

    /** Nummer der Kategorie im Fehlerkatalog (1–12 in Version 1), 0 für unbekannt. */
    public int getNumber() { return number; }

    public String getCategory() { return category; }

    /** Ursache in einem Satz. */
    public String getCause() { return cause; }

    public Confidence getConfidence() { return confidence; }

    public List<Evidence> getEvidence() { return evidence; }

    /** Erster Eintrag ist der empfohlene Quick-Fix, weitere sind Alternativen. */
    public List<FixAction> getFixes() { return fixes; }

    public FixAction getPrimaryFix() { return fixes.isEmpty() ? null : fixes.get(0); }

    public boolean isUnknown() { return UNKNOWN_RULE.equals(ruleId); }

    @Override
    public String toString() {
        return "#" + number + " " + category + " (" + confidence.label() + "): " + cause;
    }
}
