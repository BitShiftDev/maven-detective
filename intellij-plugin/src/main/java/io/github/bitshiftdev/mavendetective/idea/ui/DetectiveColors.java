package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.ui.JBColor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hintergründe für Befunde in Tabellen. Benannte Farben: Themes können sie über
 * {@code MavenDetective.warningBackground} bzw. {@code MavenDetective.errorBackground} überschreiben.
 * Farbe ist nie das einzige Merkmal – Text oder Fettdruck sagen dasselbe.
 *
 * <p>Kontrast: {@code DetectiveColorsTest} prüft Hell- und Dunkel-Variante jeder Farbe gegen die
 * Textfarben der Themes (WCAG: Text 4.5:1, Zeichen neben einem Wort 3:1). High Contrast nutzt die
 * Dunkel-Variante gegen weißen Text.
 */
public final class DetectiveColors {

    /** Hell/Dunkel-Werte je Name (Reihenfolge der Deklaration) – für den Kontrast-Test. */
    private static final Map<String, int[]> PALETTE = new LinkedHashMap<>();

    /** Auffällig, aber nicht gefährlich: abweichende Version, nur transitiv vorhanden. */
    public static final JBColor WARNING_BACKGROUND =
            named("warningBackground", 0xFFF1D6, 0x4D3B1F);

    /** Gefahr zur Laufzeit: verschiedene Klassen-Fassungen, gesenkte Version, neuer Konflikt. */
    public static final JBColor ERROR_BACKGROUND =
            named("errorBackground", 0xFDE2E1, 0x5A2A2A);

    /** Bestätigt: Sicherheit „sicher“, Build nach einem Fix wieder grün. */
    public static final JBColor SUCCESS_BACKGROUND =
            named("successBackground", 0xDDF3E2, 0x2D4733);

    /** Hintergrund von Logzeilen in Belegen – wie ein Codeblock. */
    public static final JBColor CODE_BACKGROUND =
            named("codeBackground", 0xF2F3F5, 0x2B2D30);

    /**
     * Nebentext (klein, grau) auf einem der Hintergründe oben, z. B. „verwaltet“ in einer eingefärbten
     * Zelle der Konflikt-Matrix: dunkler als das übliche Grau, damit er auch dort 4.5:1 erreicht.
     */
    public static final JBColor TINTED_SECONDARY = named("tintedSecondaryForeground", 0x55585F, 0xB4B8BF);

    /** Zeichen für Änderungen im Baum (Upgrade-Simulator): ↑ gehoben, ↓ gesenkt, + neu, − weg, ~ Scope. */
    public static final JBColor DELTA_UP = named("deltaUp", 0x2E7D32, 0x6FBF73);
    public static final JBColor DELTA_DOWN = named("deltaDown", 0xC62828, 0xF2726F);
    public static final JBColor DELTA_ADDED = named("deltaAdded", 0x1E63C2, 0x6AA6F8);
    public static final JBColor DELTA_REMOVED = named("deltaRemoved", 0x6E6E6E, 0x9A9A9A);
    public static final JBColor DELTA_SCOPE = named("deltaScope", 0x8A5A00, 0xD9A441);

    /**
     * Hintergründe für Versionsgruppen in einer Zeile der Konflikt-Matrix: gleiche Version, gleiche
     * Farbe. Die Version steht als Text in der Zelle; die Farbe macht nur Gruppen schneller sichtbar.
     */
    private static final JBColor[] VERSION_BACKGROUNDS = {
            named("version1Background", 0xDCEBFF, 0x24364F),
            named("version2Background", 0xFFE8CC, 0x4A3720),
            named("version3Background", 0xE6DDFB, 0x3A2F52),
            named("version4Background", 0xD4F1EC, 0x1F443F),
            named("version5Background", 0xFBDCEA, 0x4D2A3B),
            named("version6Background", 0xEEF0C9, 0x3F4224),
    };

    /** Farbe der {@code index}-ten Version einer Zeile (ab 0); wiederholt sich nach sechs. */
    public static JBColor versionBackground(int index) {
        return VERSION_BACKGROUNDS[Math.floorMod(index, VERSION_BACKGROUNDS.length)];
    }

    /** Hell/Dunkel-Werte aller Farben (Name ohne Präfix → {hell, dunkel}). */
    static Map<String, int[]> palette() {
        return Collections.unmodifiableMap(PALETTE);
    }

    private static JBColor named(String name, int light, int dark) {
        PALETTE.put(name, new int[] {light, dark});
        return JBColor.namedColor("MavenDetective." + name, new JBColor(light, dark));
    }

    private DetectiveColors() {
    }
}
