package io.github.bitshiftdev.mavendetective.core;

import io.github.bitshiftdev.mavendetective.core.catalog.Rule;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Texte des Kerns in zwei Sprachen: gleiche Schlüssel, gleiche Platzhalter, jeder Schlüssel aus dem
 * Code vorhanden; der Standardkatalog hat zu jedem Text eine englische Fassung.
 */
class TextsTest {

    private static final Path SOURCES = Paths.get("src/main/java");
    private static final Path CATALOG = Paths.get("src/main/resources/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml");
    private static final Pattern KEY = Pattern.compile("Texts\\.t\\((?:Texts\\.Language\\.[A-Z]+, )?\"([a-z0-9._]+)\"");
    /** Schlüssel in Bedingungen: {@code Texts.t(x ? "a.b" : "c.d")}. */
    private static final Pattern KEY_CHOICE = Pattern.compile("Texts\\.t\\([^\"()]*\\?\\s*\"([a-z0-9._]+)\"\\s*:\\s*\"([a-z0-9._]+)\"");
    /** Schlüssel als Konstante (Tabellen wie {@code new Known("usage.known.jdbc", …)}). */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"((?:dup\\.known|usage\\.known)\\.[a-z0-9.]+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)");

    @AfterEach
    void zuruecksetzen() {
        System.setProperty(Texts.PROPERTY, "de");
        Texts.setSource(null);
    }

    @Test
    void beideSprachenHabenDieselbenSchluesselUndPlatzhalter() {
        Properties en = Texts.load("/io/github/bitshiftdev/mavendetective/core/texts.properties");
        Properties de = Texts.load("/io/github/bitshiftdev/mavendetective/core/texts_de.properties");
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(de.stringPropertyNames()));
        Set<String> different = new TreeSet<>();
        for (String k : en.stringPropertyNames()) {
            if (!placeholders(en.getProperty(k)).equals(placeholders(de.getProperty(k)))) {
                different.add(k);
            }
        }
        assertEquals(Set.of(), different, "Platzhalter weichen ab");
    }

    @Test
    void jederSchluesselAusDemCodeExistiert() throws IOException {
        Properties en = Texts.load("/io/github/bitshiftdev/mavendetective/core/texts.properties");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                for (Pattern p : new Pattern[] {KEY, KEY_CHOICE, KEY_LITERAL}) {
                    Matcher m = p.matcher(code);
                    while (m.find()) {
                        for (int g = 1; g <= m.groupCount(); g++) {
                            // Präfixe wie "confidence." + name() prüft enumTexte
                            if (m.group(g) != null && !m.group(g).endsWith(".") && !en.containsKey(m.group(g))) {
                                missing.add(m.group(g) + " (" + f.getFileName() + ")");
                            }
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void enumTexte() {
        Properties en = Texts.load("/io/github/bitshiftdev/mavendetective/core/texts.properties");
        Set<String> missing = new TreeSet<>();
        for (Confidence c : Confidence.values()) {
            missing.add("confidence." + c.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (io.github.bitshiftdev.mavendetective.core.snapshot.Change.Area a : io.github.bitshiftdev.mavendetective.core.snapshot.Change.Area.values()) {
            missing.add("change.area." + a.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage.Kind k : io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage.Kind.values()) {
            missing.add("usage.kind." + k.name().toLowerCase(java.util.Locale.ROOT));
        }
        missing.removeAll(en.stringPropertyNames());
        assertEquals(Set.of(), missing);
    }

    /** Jedes {@code category}, {@code cause} und {@code label} im Standardkatalog hat ein {@code _en} darunter. */
    @Test
    void katalogHatJedenTextAufEnglisch() throws IOException {
        List<String> lines = Files.readAllLines(CATALOG, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        Pattern field = Pattern.compile("^(\\s*)(category|cause|label):");
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = field.matcher(lines.get(i));
            if (m.find()) {
                String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
                if (!next.startsWith(m.group(1) + m.group(2) + "_en:")) {
                    missing.add((i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void katalogFolgtDerSprache() {
        Rule rule = RuleCatalog.loadDefault().getRules().get(0);
        System.setProperty(Texts.PROPERTY, "en");
        String en = rule.getCategory() + " / " + rule.getCause() + " / " + rule.getFix().getLabel();
        System.setProperty(Texts.PROPERTY, "de");
        String de = rule.getCategory() + " / " + rule.getCause() + " / " + rule.getFix().getLabel();
        assertEquals("HTTP repository blocked", en.substring(0, en.indexOf(" / ")));
        assertEquals("HTTP-Repository blockiert", de.substring(0, de.indexOf(" / ")));
        assertNotEquals(en, de);
    }

    @Test
    void spracheAusQuellePropertyOderStandard() {
        System.setProperty(Texts.PROPERTY, "en");
        assertEquals("certain", Confidence.CERTAIN.label());
        System.setProperty(Texts.PROPERTY, "de");
        assertEquals("sicher", Confidence.CERTAIN.label());
        // Eine gesetzte Quelle (IDE-Einstellung) geht vor
        Texts.setSource(() -> "en");
        assertEquals("certain", Confidence.CERTAIN.label());
        Texts.setSource(() -> null);
        assertEquals("sicher", Confidence.CERTAIN.label());
        assertEquals(Texts.Language.DE, Texts.Language.parse("de_DE"));
        assertEquals(Texts.Language.EN, Texts.Language.parse("English"));
        assertNull(Texts.Language.parse("fr"));
    }

    @Test
    void platzhalterUndMehrzahl() {
        assertEquals("1 Klasse in a", Texts.format("{0} {0|Klasse|Klassen} in {1}", 1, "a"));
        assertEquals("3 Klassen in a", Texts.format("{0} {0|Klasse|Klassen} in {1}", 3, "a"));
        assertEquals("Ein BOM fehlt", Texts.format("{0|Ein BOM fehlt|{0} BOMs fehlen}", 1));
        assertEquals("2 BOMs fehlen", Texts.format("{0|Ein BOM fehlt|{0} BOMs fehlen}", 2));
        // Zeilennummern ohne Tausenderpunkt, benannte Platzhalter bleiben stehen
        assertEquals("Zeile 1234: {artifact|?}", Texts.format("Zeile {0}: {artifact|?}", 1234));
        // Werte werden nicht erneut gelesen
        assertEquals("a {1} b", Texts.format("a {0} b", "{1}"));
        assertEquals("ohne Argumente {0}", Texts.format("ohne Argumente {0}"));
        assertEquals("1 file: a.jar", Texts.t(Texts.Language.EN, "card.files.one", "a.jar"));
    }

    private static Set<String> placeholders(String v) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(v);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
