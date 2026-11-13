package io.github.bitshiftdev.mavendetective.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texte des Kerns (Diagnosen, Belege, Fix-Bezeichnungen, Befunde von Modul A, Berichte, CLI) in Englisch
 * ({@code texts.properties}, Standard) und Deutsch ({@code texts_de.properties}).
 *
 * <p>Sprache: eine gesetzte Quelle ({@link #setSource}, das IntelliJ-Plugin hängt seine Einstellung an),
 * sonst {@code -DmavenDetective.language=en|de}, sonst {@code MAVEN_DETECTIVE_LANGUAGE}, sonst die Sprache
 * der JVM (Deutsch nur bei {@code de}, alles andere Englisch).
 *
 * <p>Platzhalter: {@code {0}} wird durch den Wert ersetzt (ohne Zahlenformat, Zeilennummern bleiben
 * „1234“), {@code {0|eins|mehr}} wählt nach dem Zahlwert ({@code 1} → erstes Wort). Platzhalter der
 * Katalog-Vorlagen ({@code {artifact|…}}) haben Namen statt Ziffern und bleiben unberührt.
 * Selbst geschrieben statt {@code MessageFormat}: keine Apostroph-Regeln, keine Tausenderpunkte.
 */
public final class Texts {

    public enum Language {
        EN("en"), DE("de");

        private final String code;

        Language(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** {@code "de"}, {@code "deutsch"}, {@code "de_DE"} → DE; {@code "en"}… → EN; sonst {@code null}. */
        public static Language parse(String s) {
            if (s == null) {
                return null;
            }
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.startsWith("de")) {
                return DE;
            }
            if (v.startsWith("en")) {
                return EN;
            }
            return null;
        }
    }

    public static final String PROPERTY = "mavenDetective.language";
    public static final String ENV = "MAVEN_DETECTIVE_LANGUAGE";

    private static final String BASE = "/io/github/bitshiftdev/mavendetective/core/texts";
    /** {@code {0|eins|mehr}}; die Wörter dürfen selbst {@code {0}} enthalten. */
    private static final Pattern PLURAL = Pattern.compile("\\{(\\d)\\|((?:[^|{}]|\\{\\d\\})*)\\|((?:[^|{}]|\\{\\d\\})*)\\}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)\\}");

    private static volatile Callable<String> source;
    private static volatile Properties english;
    private static volatile Properties german;

    private Texts() {
    }

    /** Sprachquelle der Umgebung (z. B. Einstellung der IDE); {@code null} = Property/Umgebung/JVM. */
    public static void setSource(Callable<String> s) {
        source = s;
    }

    public static Language language() {
        Callable<String> s = source;
        if (s != null) {
            try {
                Language l = Language.parse(s.call());
                if (l != null) {
                    return l;
                }
            } catch (Exception ignored) {
                // Quelle nicht bereit: weiter mit Property/Umgebung
            }
        }
        Language l = Language.parse(System.getProperty(PROPERTY));
        if (l == null) {
            l = Language.parse(System.getenv(ENV));
        }
        if (l == null) {
            l = Language.parse(Locale.getDefault().getLanguage());
        }
        return l == null ? Language.EN : l;
    }

    /** Text zum Schlüssel in der aktuellen Sprache; fehlt er, der Schlüssel selbst (fällt in Tests auf). */
    public static String t(String key, Object... args) {
        return format(raw(language(), key), args);
    }

    /** Text in einer bestimmten Sprache (Tests, Vergleich der Dateien). */
    public static String t(Language language, String key, Object... args) {
        return format(raw(language, key), args);
    }

    static String raw(Language language, String key) {
        String v = null;
        if (language == Language.DE) {
            v = german().getProperty(key);
        }
        if (v == null) {
            v = english().getProperty(key);
        }
        return v == null ? key : v;
    }

    /**
     * Katalogtext in der aktuellen Sprache: {@code variants} hält {@code feld_en}/{@code feld_de} je
     * Sprachcode; ohne passende Variante gilt der Grundtext.
     */
    public static String pick(String base, Map<String, String> variants) {
        if (variants == null || variants.isEmpty()) {
            return base;
        }
        String v = variants.get(language().code());
        return v != null ? v : base;
    }

    public static String format(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0 || pattern.indexOf('{') < 0) {
            return pattern;
        }
        // Erst Einzahl/Mehrzahl wählen, dann Werte einsetzen (Werte werden nicht erneut gelesen)
        Matcher p = PLURAL.matcher(pattern);
        StringBuffer chosen = new StringBuffer();
        while (p.find()) {
            int i = p.group(1).charAt(0) - '0';
            String out = i >= args.length ? p.group() : isOne(args[i]) ? p.group(2) : p.group(3);
            p.appendReplacement(chosen, Matcher.quoteReplacement(out));
        }
        p.appendTail(chosen);
        Matcher m = PLACEHOLDER.matcher(chosen);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int i = m.group(1).charAt(0) - '0';
            m.appendReplacement(sb, Matcher.quoteReplacement(i >= args.length ? m.group() : String.valueOf(args[i])));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isOne(Object a) {
        if (a instanceof Number) {
            return ((Number) a).longValue() == 1 && ((Number) a).doubleValue() == 1d;
        }
        return "1".equals(String.valueOf(a));
    }

    private static Properties english() {
        Properties p = english;
        if (p == null) {
            p = load(BASE + ".properties");
            english = p;
        }
        return p;
    }

    private static Properties german() {
        Properties p = german;
        if (p == null) {
            p = load(BASE + "_de.properties");
            german = p;
        }
        return p;
    }

    /** UTF-8 statt ISO-8859-1 (ResourceBundle in Java 8 liest Latin-1). */
    static Properties load(String resource) {
        Properties p = new Properties();
        InputStream in = Texts.class.getResourceAsStream(resource);
        if (in == null) {
            return p;
        }
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            // ohne Texte bleiben die Schlüssel sichtbar
        }
        return p;
    }
}
