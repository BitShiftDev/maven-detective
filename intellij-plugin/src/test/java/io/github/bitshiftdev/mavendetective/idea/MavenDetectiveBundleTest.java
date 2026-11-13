package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jeder Schlüssel, den der Code oder {@code plugin.xml} nennt, steht im Bundle. Ein fehlender
 * Schlüssel fiele sonst erst zur Laufzeit auf (MissingResourceException beim Öffnen des Tool-Windows).
 */
class MavenDetectiveBundleTest {

    private static final Path SOURCES = Paths.get("src/main/java");
    private static final Path BUNDLE = Paths.get("src/main/resources/messages/MavenDetectiveBundle.properties");
    private static final Path PLUGIN_XML = Paths.get("src/main/resources/META-INF/plugin.xml");

    /** {@code message("key"} und Schlüssel in Bedingungen wie {@code message(x ? "a.b" : "c.d")}. */
    private static final Pattern KEY = Pattern.compile("message\\((?:[^\"()]|\\([^()]*\\))*?\"([a-zA-Z0-9._]+)\"(?:\\s*:\\s*\"([a-zA-Z0-9._]+)\")?");
    private static final Pattern ACTION_ID = Pattern.compile("<action id=\"([^\"]+)\"");

    @Test
    void jederVerwendeteSchluesselStehtImBundle() throws IOException {
        Properties bundle = load();
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = KEY.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    for (int g = 1; g <= 2; g++) {
                        if (m.group(g) != null && !bundle.containsKey(m.group(g))) {
                            missing.add(m.group(g) + " (" + f.getFileName() + ")");
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test
    void jedeAktionHatEinenText() throws IOException {
        Properties bundle = load();
        String xml = Files.readString(PLUGIN_XML, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<resource-bundle>messages.MavenDetectiveBundle</resource-bundle>"));
        Matcher m = ACTION_ID.matcher(xml);
        Set<String> missing = new TreeSet<>();
        int actions = 0;
        while (m.find()) {
            actions++;
            if (!bundle.containsKey("action." + m.group(1) + ".text")) {
                missing.add(m.group(1));
            }
        }
        assertTrue(actions >= 8, "Aktionen in plugin.xml nicht gefunden");
        assertEquals(Set.of(), missing);
    }

    private static Properties load() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }
}
