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

    /** Zeichen für Änderungen im Baum (Upgrade-Simulator): ↑ gehoben, ↓ gesenkt, + neu, − weg, ~ Scope. */
    public static final JBColor DELTA_UP = JBColor.namedColor("MavenDetective.deltaUp", new JBColor(0x2E7D32, 0x6FBF73));
    public static final JBColor DELTA_DOWN = JBColor.namedColor("MavenDetective.deltaDown", new JBColor(0xC62828, 0xF2726F));
    public static final JBColor DELTA_ADDED = JBColor.namedColor("MavenDetective.deltaAdded", new JBColor(0x1E63C2, 0x6AA6F8));
    public static final JBColor DELTA_REMOVED = JBColor.namedColor("MavenDetective.deltaRemoved", new JBColor(0x6E6E6E, 0x9A9A9A));
    public static final JBColor DELTA_SCOPE = JBColor.namedColor("MavenDetective.deltaScope", new JBColor(0x8A5A00, 0xD9A441));

    /**
     * Hintergründe für Versionsgruppen in einer Zeile der Konflikt-Matrix: gleiche Version, gleiche
     * Farbe. Die Version steht als Text in der Zelle; die Farbe macht nur Gruppen schneller sichtbar.
     */
    private static final JBColor[] VERSION_BACKGROUNDS = {
            JBColor.namedColor("MavenDetective.version1Background", new JBColor(0xDCEBFF, 0x24364F)),
            JBColor.namedColor("MavenDetective.version2Background", new JBColor(0xFFE8CC, 0x4A3720)),
            JBColor.namedColor("MavenDetective.version3Background", new JBColor(0xE6DDFB, 0x3A2F52)),
            JBColor.namedColor("MavenDetective.version4Background", new JBColor(0xD4F1EC, 0x1F443F)),
            JBColor.namedColor("MavenDetective.version5Background", new JBColor(0xFBDCEA, 0x4D2A3B)),
            JBColor.namedColor("MavenDetective.version6Background", new JBColor(0xEEF0C9, 0x3F4224)),
    };

    /** Farbe der {@code index}-ten Version einer Zeile (ab 0); wiederholt sich nach sechs. */
    public static JBColor versionBackground(int index) {
        return VERSION_BACKGROUNDS[Math.floorMod(index, VERSION_BACKGROUNDS.length)];
    }

    private DetectiveColors() {
    }
}
