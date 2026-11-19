package io.github.bitshiftdev.mavendetective.core.testkit;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden Files: ganze Ausgaben (Karte, Markdown, Berichte) gegen {@code src/test/resources/golden/<name>.<de|en>.<ext>}.
 * Mit {@code -DupdateGolden=true} ({@code ./gradlew :core:test -DupdateGolden=true}) werden die Dateien neu
 * geschrieben – der Diff zeigt im Review jede Textänderung.
 *
 * <pre>
 * Golden.both("report/card-full", "txt", () -> String.join("\n", Reports.consoleCard(diagnoses)));
 * Golden.both("cli/matrix", "md", () -> cli("matrix", "--markdown"), Golden.path(tmp, "$PROJECT"));
 * </pre>
 *
 * Vor dem Vergleich: Zeilenenden → {@code \n}, ersetzte Pfade (Windows: {@code \} → {@code /}), genau ein
 * Zeilenumbruch am Ende.
 */
public final class Golden {

    public static final String UPDATE = "updateGolden";

    private Golden() {
    }

    /** Ersetzung eines Pfads durch einen Platzhalter (auch die echte Schreibweise hinter Symlinks). */
    public static Map<String, String> path(Path path, String placeholder) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        try {
            m.put(path.toRealPath().toString(), placeholder);
        } catch (IOException ignored) {
            // existiert (noch) nicht
        }
        m.put(path.toAbsolutePath().normalize().toString(), placeholder);
        return m;
    }

    /** Erzeugt die Ausgabe einmal auf Deutsch und einmal auf Englisch und vergleicht beide. */
    @SafeVarargs
    public static void both(String name, String extension, Supplier<String> output, Map<String, String>... replacements) {
        for (Texts.Language language : new Texts.Language[] {Texts.Language.DE, Texts.Language.EN}) {
            String text = in(language, output);
            check(name + "." + language.code() + "." + extension, text, replacements);
        }
    }

    /** Führt {@code output} in dieser Sprache aus (System-Property, danach wieder wie vorher). */
    public static <T> T in(Texts.Language language, Supplier<T> output) {
        String before = System.getProperty(Texts.PROPERTY);
        System.setProperty(Texts.PROPERTY, language.code());
        try {
            return output.get();
        } finally {
            if (before == null) {
                System.clearProperty(Texts.PROPERTY);
            } else {
                System.setProperty(Texts.PROPERTY, before);
            }
        }
    }

    /** Vergleicht {@code actual} mit {@code golden/<file>} (oder schreibt die Datei mit {@code -DupdateGolden=true}). */
    @SafeVarargs
    public static void check(String file, String actual, Map<String, String>... replacements) {
        String text = normalize(actual, replacements);
        Path golden = dir().resolve(file);
        if (Boolean.getBoolean(UPDATE)) {
            try {
                Files.createDirectories(golden.getParent());
                Files.write(golden, text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        if (!Files.isRegularFile(golden)) {
            fail("Golden File fehlt: " + golden + " – mit ./gradlew :core:test -D" + UPDATE + "=true anlegen.\n" + text);
        }
        String expected;
        try {
            expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(expected, text, "Ausgabe weicht von " + file + " ab (bewusst? -D" + UPDATE + "=true)");
    }

    @SafeVarargs
    static String normalize(String actual, Map<String, String>... replacements) {
        String text = actual.replace("\r\n", "\n");
        for (Map<String, String> r : replacements) {
            // längere Pfade zuerst, damit /tmp/x/repo nicht als /tmp/x + /repo ersetzt wird
            for (Map.Entry<String, String> e : sortedByLength(r).entrySet()) {
                text = text.replace(e.getKey(), e.getValue());
            }
        }
        if (File.separatorChar == '\\') {
            text = text.replace('\\', '/');
        }
        while (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "\n";
    }

    private static Map<String, String> sortedByLength(Map<String, String> r) {
        Map<String, String> sorted = new LinkedHashMap<String, String>();
        r.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** {@code core/src/test/resources/golden} – Gradle startet die Tests im Modulordner, die IDE evtl. im Wurzelordner. */
    static Path dir() {
        Path inModule = Paths.get("src", "test", "resources", "golden");
        if (Files.isDirectory(Paths.get("src", "test", "resources"))) {
            return inModule.toAbsolutePath();
        }
        return Paths.get("core").resolve(inModule).toAbsolutePath();
    }
}
