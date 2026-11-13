package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundle in zwei Sprachen: Englisch (Standard) und Deutsch ({@code _de}). Jeder Schlüssel, den der Code
 * oder {@code plugin.xml} nennt, steht in beiden; beide haben dieselben Schlüssel und Platzhalter. Ein
 * fehlender Schlüssel fiele sonst erst zur Laufzeit auf (MissingResourceException beim Öffnen des Tool-Windows).
 */
class MavenDetectiveBundleTest {

    private static final Path SOURCES = Paths.get("src/main/java");
    private static final Path ENGLISH = Paths.get("src/main/resources/messages/MavenDetectiveBundle.properties");
    private static final Path GERMAN = Paths.get("src/main/resources/messages/MavenDetectiveBundle_de.properties");
    private static final Path PLUGIN_XML = Paths.get("src/main/resources/META-INF/plugin.xml");

    /** {@code message("key"} und Schlüssel in Bedingungen wie {@code message(x ? "a.b" : "c.d")}. */
    private static final Pattern KEY = Pattern.compile("message\\((?:[^\"()]|\\([^()]*\\))*?\"([a-zA-Z0-9._]+)\"(?:\\s*:\\s*\"([a-zA-Z0-9._]+)\")?");
    /** Schlüssel, die als Konstante im Code stehen (Spaltenköpfe, Fall-Tabellen): {@code "upgrade.kind.added"}. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"((?:usage|upgrade|matrix|exclusions|duplicates|confidence|doctor|tile)\\.[a-z0-9.]+)\"");
    private static final Pattern ACTION_ID = Pattern.compile("<action id=\"([^\"]+)\"");
    private static final Pattern XML_KEY = Pattern.compile("(?:key|nameKey|descriptionKey|categoryKey)=\"([^\"]+)\"|<categoryKey>([^<]+)</categoryKey>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)");

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    @Test
    void jederVerwendeteSchluesselStehtImBundle() throws IOException {
        Properties en = load(ENGLISH);
        Properties de = load(GERMAN);
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = KEY.matcher(code);
                while (m.find()) {
                    for (int g = 1; g <= 2; g++) {
                        check(m.group(g), f, en, de, missing);
                    }
                }
                Matcher l = KEY_LITERAL.matcher(code);
                while (l.find()) {
                    check(l.group(1), f, en, de, missing);
                }
            }
        }
        String xml = Files.readString(PLUGIN_XML, StandardCharsets.UTF_8);
        Matcher x = XML_KEY.matcher(xml);
        while (x.find()) {
            check(x.group(1) != null ? x.group(1) : x.group(2), PLUGIN_XML, en, de, missing);
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void jedeAktionHatEinenText() throws IOException {
        Properties en = load(ENGLISH);
        Properties de = load(GERMAN);
        String xml = Files.readString(PLUGIN_XML, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<resource-bundle>messages.MavenDetectiveBundle</resource-bundle>"));
        Matcher m = ACTION_ID.matcher(xml);
        Set<String> missing = new TreeSet<>();
        int actions = 0;
        while (m.find()) {
            actions++;
            check("action." + m.group(1) + ".text", PLUGIN_XML, en, de, missing);
        }
        assertTrue(actions >= 8, "Aktionen in plugin.xml nicht gefunden");
        assertEquals(Set.of(), missing);
    }

    @Test
    void beideSprachenHabenDieselbenSchluesselUndPlatzhalter() throws IOException {
        Properties en = load(ENGLISH);
        Properties de = load(GERMAN);
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(de.stringPropertyNames()));
        Set<String> different = new TreeSet<>();
        for (String k : en.stringPropertyNames()) {
            if (!placeholders(en.getProperty(k)).equals(placeholders(de.getProperty(k)))) {
                different.add(k);
            }
        }
        assertEquals(Set.of(), different, "Platzhalter weichen ab");
    }

    /**
     * Texte mit Platzhaltern laufen durch {@link MessageFormat}: Das Muster muss sich parsen lassen, und ein
     * einfaches Apostroph würde verschluckt (’ statt '). Texte ohne Platzhalter bleiben, wie sie sind.
     */
    @Test
    void platzhalterTexteSindGueltigeMessageFormate() throws IOException {
        Set<String> broken = new TreeSet<>();
        for (Path file : new Path[] {ENGLISH, GERMAN}) {
            Properties p = load(file);
            for (String k : p.stringPropertyNames()) {
                String v = p.getProperty(k);
                if (placeholders(v).isEmpty()) {
                    continue;
                }
                try {
                    new MessageFormat(v, Locale.ROOT);
                    if (v.indexOf('\'') >= 0) {
                        broken.add(file.getFileName() + ": " + k + " (Apostroph)");
                    }
                } catch (IllegalArgumentException e) {
                    broken.add(file.getFileName() + ": " + k + " (" + e.getMessage() + ")");
                }
            }
        }
        assertEquals(Set.of(), broken);
    }

    @Test
    void spracheNachEinstellung() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.ENGLISH);
        assertEquals("Overview", MavenDetectiveBundle.message("tab.overview"));
        assertEquals("3 modules · 2 versions", MavenDetectiveBundle.message("codevision.conflicts", 3, 2));
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
        assertEquals("Übersicht", MavenDetectiveBundle.message("tab.overview"));
        assertEquals("3 Module · 2 Versionen", MavenDetectiveBundle.message("codevision.conflicts", 3, 2));
        // Ohne laufende IDE: Automatisch = Sprache der IDE, Standard Englisch – nie die Sprache der JVM
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.AUTO);
        assertEquals("Overview", MavenDetectiveBundle.message("tab.overview"));
    }

    private static void check(String key, Path where, Properties en, Properties de, Set<String> missing) {
        if (key == null) {
            return;
        }
        if (!en.containsKey(key)) {
            missing.add(key + " (" + where.getFileName() + ", englisch)");
        }
        if (!de.containsKey(key)) {
            missing.add(key + " (" + where.getFileName() + ", deutsch)");
        }
    }

    private static Set<String> placeholders(String v) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(v);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }
}
