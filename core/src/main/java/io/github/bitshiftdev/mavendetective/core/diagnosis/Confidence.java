package io.github.bitshiftdev.mavendetective.core.diagnosis;

import io.github.bitshiftdev.mavendetective.core.Texts;

/** Wie sicher die Diagnose ist. Wird auf jeder Karte angezeigt. */
public enum Confidence {
    /** Muster eindeutig oder durch eine Prüfung (Datei, settings.xml, JDK …) bestätigt. */
    CERTAIN("sicher"),
    /** Muster passt, aber keine Prüfung konnte die Ursache bestätigen. */
    LIKELY("wahrscheinlich");

    private final String label;

    Confidence(String label) {
        this.label = label;
    }

    public String label() {
        return Texts.t("confidence." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
