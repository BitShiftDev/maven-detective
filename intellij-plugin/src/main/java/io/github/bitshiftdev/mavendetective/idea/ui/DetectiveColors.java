package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.JBColor;

/**
 * Hintergründe für Befunde in Tabellen. Benannte Farben: Themes können sie über
 * {@code MavenDetective.warningBackground} bzw. {@code MavenDetective.errorBackground} überschreiben.
 * Farbe ist nie das einzige Merkmal – Text oder Fettdruck sagen dasselbe.
 */
public final class DetectiveColors {

    /** Auffällig, aber nicht gefährlich: abweichende Version, nur transitiv vorhanden. */
    public static final JBColor WARNING_BACKGROUND =
            JBColor.namedColor("MavenDetective.warningBackground", new JBColor(0xFFF1D6, 0x4D3B1F));

    /** Gefahr zur Laufzeit: verschiedene Klassen-Fassungen, gesenkte Version, neuer Konflikt. */
    public static final JBColor ERROR_BACKGROUND =
            JBColor.namedColor("MavenDetective.errorBackground", new JBColor(0xFDE2E1, 0x5A2A2A));

    /** Bestätigt: Sicherheit „sicher“, Build nach einem Fix wieder grün. */
    public static final JBColor SUCCESS_BACKGROUND =
            JBColor.namedColor("MavenDetective.successBackground", new JBColor(0xDDF3E2, 0x2D4733));

    /** Hintergrund von Logzeilen in Belegen – wie ein Codeblock. */
    public static final JBColor CODE_BACKGROUND =
            JBColor.namedColor("MavenDetective.codeBackground", new JBColor(0xF2F3F5, 0x2B2D30));

    private DetectiveColors() {
    }
}
