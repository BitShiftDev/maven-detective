package io.github.bitshiftdev.mavendetective.idea.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Kontrast der eigenen Farben in Light, Dark und High Contrast (UI-Plan U6, Barrierefreiheit) nach
 * WCAG 2.1: Text mindestens 4.5:1, Zeichen, die neben einem Wort stehen (↑ ↓ + − ~), mindestens 3:1.
 * Gemessen gegen die Text- und Panel-Farben der Standard-Themes (Int UI Light/Dark, High Contrast).
 */
class DetectiveColorsTest {

    private static final int LIGHT_TEXT = 0x000000;
    private static final int DARK_TEXT = 0xDFE1E5;
    private static final int HIGH_CONTRAST_TEXT = 0xFFFFFF;
    private static final int[] LIGHT_PANELS = {0xFFFFFF, 0xF7F8FA};
    private static final int[] DARK_PANELS = {0x2B2D30, 0x1E1F22, 0x000000};

    private static final double TEXT = 4.5;
    private static final double SYMBOL = 3.0;

    @Test
    void textAufAllenHintergruendenLesbar() {
        List<String> low = new ArrayList<>();
        for (Map.Entry<String, int[]> e : backgrounds()) {
            int[] c = e.getValue();
            check(low, e.getKey() + " hell", LIGHT_TEXT, c[0], TEXT);
            check(low, e.getKey() + " dunkel", DARK_TEXT, c[1], TEXT);
            check(low, e.getKey() + " high contrast", HIGH_CONTRAST_TEXT, c[1], TEXT);
            int[] secondary = DetectiveColors.palette().get("tintedSecondaryForeground");
            check(low, "Nebentext auf " + e.getKey() + " hell", secondary[0], c[0], TEXT);
            check(low, "Nebentext auf " + e.getKey() + " dunkel", secondary[1], c[1], TEXT);
        }
        assertEquals(List.of(), low);
    }

    @Test
    void aenderungszeichenLesbar() {
        List<String> low = new ArrayList<>();
        int[] warning = DetectiveColors.palette().get("warningBackground");
        int[] error = DetectiveColors.palette().get("errorBackground");
        for (Map.Entry<String, int[]> e : DetectiveColors.palette().entrySet()) {
            if (!e.getKey().startsWith("delta")) {
                continue;
            }
            int[] c = e.getValue();
            for (int panel : LIGHT_PANELS) {
                check(low, e.getKey() + " hell auf " + Integer.toHexString(panel), c[0], panel, TEXT);
            }
            for (int panel : DARK_PANELS) {
                check(low, e.getKey() + " dunkel auf " + Integer.toHexString(panel), c[1], panel, TEXT);
            }
            // Zeilen mit Risiko sind hinterlegt; dort steht das Zeichen neben dem Wort
            check(low, e.getKey() + " hell auf Fehler", c[0], error[0], SYMBOL);
            check(low, e.getKey() + " dunkel auf Fehler", c[1], error[1], SYMBOL);
            check(low, e.getKey() + " hell auf Warnung", c[0], warning[0], SYMBOL);
            check(low, e.getKey() + " dunkel auf Warnung", c[1], warning[1], SYMBOL);
        }
        assertEquals(List.of(), low);
    }

    @Test
    void kontrastRechnungStimmt() {
        assertEquals(21.0, contrast(0x000000, 0xFFFFFF), 0.01);
        assertEquals(1.0, contrast(0x777777, 0x777777), 0.01);
        assertEquals(4.48, contrast(0x777777, 0xFFFFFF), 0.01);
    }

    private static List<Map.Entry<String, int[]>> backgrounds() {
        List<Map.Entry<String, int[]>> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : DetectiveColors.palette().entrySet()) {
            if (e.getKey().endsWith("Background")) {
                out.add(e);
            }
        }
        assertFalse(out.isEmpty());
        return out;
    }

    private static void check(List<String> low, String what, int fg, int bg, double min) {
        double c = contrast(fg, bg);
        if (c < min) {
            low.add(what + String.format(": %.2f < %.1f", c, min));
        }
    }

    /** WCAG-Kontrastverhältnis zweier sRGB-Farben (1 bis 21). */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int v) {
        double c = (v & 0xFF) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
