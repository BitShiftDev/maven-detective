package io.github.bitshiftdev.mavendetective.core.testkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dieselbe Maven-Logzeile so, wie sie verschiedene Umgebungen ausgeben: blanke Konsole, mit
 * ANSI-Farben, mit Zeitstempeln von GitHub Actions, Jenkins und Logback, mit GitLab-Stream-Kennung
 * und mit Windows-Zeilenende. Für {@code @MethodSource}: Name der Variante → Zeile.
 */
public final class TestLogs {

    private static final String ESC = "\u001B";

    private TestLogs() {
    }

    /** Varianten von {@code [level] text}; {@code level} ohne Klammern, z. B. {@code ERROR}. */
    public static Map<String, String> variants(String level, String text) {
        String plain = "[" + level + "] " + text;
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("konsole", plain);
        out.put("ansi", "[" + ESC + "[1;31m" + level + ESC + "[m] " + ESC + "[1m" + text + ESC + "[m");
        out.put("github-actions", "2026-11-12T06:48:01.1234567Z " + plain);
        out.put("jenkins", "[2026-11-12T06:48:01.123Z] " + plain);
        out.put("uhrzeit", "06:48:01 " + plain);
        out.put("logback", "2026-11-12 06:48:01,123 [main] " + plain);
        out.put("gitlab", "2026-11-12T06:48:01.123456Z 01O " + plain);
        out.put("gitlab-fortsetzung", "2026-11-12T06:48:01.123456Z 00E+ " + plain);
        out.put("millisekunden", "1234 [main] " + plain);
        return out;
    }

    /** Zeilen mit {@code \n} verbinden, am Ende ein Zeilenumbruch. */
    public static String log(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** Wie {@link #log}, aber mit Windows-Zeilenenden. */
    public static String windowsLog(String... lines) {
        return String.join("\r\n", lines) + "\r\n";
    }
}
