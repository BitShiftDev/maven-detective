package io.github.bitshiftdev.mavendetective.core.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code evaluate} (Gate 1): Trefferquote über einem Verzeichnis mit Logs und {@code labels.properties}. */
class EvaluateCommandTest {

    @TempDir
    Path tmp;

    private static String[] run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        return new String[] {String.valueOf(code), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)};
    }

    /** Verzeichnis mit den Logs; eigene Kataloge liegen daneben in {@link #tmp}. */
    private Path logs;

    private void copyLog(String name, String as) throws Exception {
        Files.copy(log(name), logs.resolve(as));
    }

    private static Path log(String name) throws URISyntaxException {
        return Paths.get(EvaluateCommandTest.class.getResource("/logs/" + name).toURI());
    }

    private void corpus() throws Exception {
        logs = Files.createDirectory(tmp.resolve("logs"));
        copyLog("corrupt-jar__zip-end-header.log", "a.log");
        copyLog("test-failures__assertion.log", "b.log");
        Files.write(logs.resolve("c.log"), "[ERROR] Something nobody has ever seen before\n".getBytes(StandardCharsets.UTF_8));
        copyLog("plugin-missing__unknown-lifecycle-phase.log", "d.txt");
        Files.write(logs.resolve("README.md"), "keine Logdatei".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void labelsGiveTheHitRate() throws Exception {
        corpus();
        // a richtig, b falsch (ist test-failures), c richtig als unbekannt; d ohne Label
        Files.write(logs.resolve("labels.properties"), "a.log=corrupt-jar\nb.log = corrupt-jar\nc.log=unknown\n"
                .getBytes(StandardCharsets.UTF_8));
        String[] r = run("evaluate", logs.toString());
        assertEquals("0", r[0], r[2]);
        assertTrue(r[1].contains("Logs: 4, eingeordnet: 3 (75 %)"), r[1]);
        assertTrue(r[1].contains("Mit Label: 3, richtig: 2 (67 %)"), r[1]);
        assertTrue(r[1].contains("✗ corrupt-jar"), "falsches Label wird markiert: " + r[1]);
        assertFalse(r[1].contains("README.md"), r[1]);
    }

    @Test
    void withoutLabelsOnlyTheDistributionIsShown() throws Exception {
        corpus();
        String[] r = run("evaluate", logs.toString());
        assertEquals("0", r[0], r[2]);
        assertFalse(r[1].contains("Mit Label"), r[1]);
        assertTrue(r[1].contains("labels.properties"), "Tipp: " + r[1]);
        assertTrue(r[1].contains("Verteilung: {corrupt-jar=1, plugin-missing=1, test-failures=1, unknown=1}"), r[1]);
    }

    @Test
    void ownCatalogChangesTheClassification() throws Exception {
        corpus();
        Path extra = tmp.resolve("extra.yaml");
        Files.write(extra, ("version: 1\nrules:\n  - id: acme-mystery\n    number: 20\n    category: Rätsel\n"
                + "    priority: 50\n    patterns:\n      - 'nobody has ever seen'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(logs.resolve("labels.properties"), "c.log=acme-mystery\n".getBytes(StandardCharsets.UTF_8));
        String[] r = run("evaluate", logs.toString(), "--catalog", extra.toString());
        assertEquals("0", r[0], r[2]);
        assertTrue(r[1].contains("Mit Label: 1, richtig: 1 (100 %)"), r[1]);
    }

    @Test
    void brokenOwnCatalogEndsWithExitCodeThree() throws Exception {
        corpus();
        Path extra = Files.write(tmp.resolve("broken.yaml"),
                "version: 1\nrules:\n  - id: Bad Id\n".getBytes(StandardCharsets.UTF_8));
        String[] r = run("evaluate", logs.toString(), "--catalog", extra.toString());
        assertEquals("3", r[0], r[1]);
        assertTrue(r[2].contains("Bad Id"), r[2]);
    }
}
