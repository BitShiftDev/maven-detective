package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.cli.CliProjects.Result;
import io.github.bitshiftdev.mavendetective.core.cli.CliProjects.State;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jeder Befehl × Zustand des Projekts bzw. der Eingabe → Exit-Code und erste Zeile (stdout, sonst stderr). Dazu
 * Bedienfehler: kein/unbekannter Befehl, unbekannte Option, fehlender Wert, zweites Ziel, {@code -} als stdin,
 * {@code --lang en}.
 */
class CliMatrixTest {

    @TempDir
    Path tmp;

    private final String homeBefore = System.getProperty(SnapshotStore.PROP_HOME);

    @BeforeEach
    void ownSnapshotHome() {
        // diagnose/changes lesen Schnappschüsse – nie aus dem echten ~/.maven-detective
        System.setProperty(SnapshotStore.PROP_HOME, tmp.resolve("detective-home").toString());
    }

    @AfterEach
    void restoreSnapshotHome() {
        if (homeBefore == null) {
            System.clearProperty(SnapshotStore.PROP_HOME);
        } else {
            System.setProperty(SnapshotStore.PROP_HOME, homeBefore);
        }
    }

    private static String dirOf(Path dir) {
        return dir.toAbsolutePath().normalize().toString();
    }

    private void assertRun(Result r, int code, String firstLine) {
        assertEquals(code, r.code, r.toString());
        assertTrue(r.firstLine().startsWith(firstLine), "erwartet „" + firstLine + "…“\n" + r);
    }

    // ---- Befehle über einem Projekt ---------------------------------------------------------------------------

    static Stream<Arguments> projectCommands() {
        String noPom = "Keine pom.xml unter $DIR";
        String brokenPom = "$POM ist nicht lesbar: ";
        String upgrade = "upgrade " + CliProjects.GUAVA + ":33.3.1-jre";
        String why = "why " + CliProjects.GUAVA;
        return Stream.of(
                Arguments.of(why, State.NONE, 1, noPom),
                Arguments.of(why, State.BROKEN, 1, brokenPom),
                Arguments.of(why, State.CLEAN, 0, "Warum com.google.guava:guava 32.1.3-jre in com.acme:app:1.0?"),
                Arguments.of(why, State.FINDING, 0, "Warum com.google.guava:guava 33.3.1-jre in com.acme:a:1.0?"),
                Arguments.of("matrix", State.NONE, 1, noPom),
                Arguments.of("matrix", State.BROKEN, 1, brokenPom),
                Arguments.of("matrix", State.CLEAN, 0, "Konflikt-Matrix: 1 Modul, 2 Artifacts, 0 mit abweichenden Versionen"),
                Arguments.of("matrix", State.FINDING, 0, "Konflikt-Matrix: 2 Module, 4 Artifacts, 1 mit abweichenden Versionen"),
                Arguments.of("exclusions", State.NONE, 1, noPom),
                Arguments.of("exclusions", State.BROKEN, 1, brokenPom),
                Arguments.of("exclusions", State.CLEAN, 0, "Exclusion-Hygiene: 0 Exclusions in 1 POM – 0 veraltet"),
                Arguments.of("exclusions", State.FINDING, 3, "Exclusion-Hygiene: 1 Exclusion in 3 POMs – 1 veraltet"),
                Arguments.of("duplicates", State.NONE, 1, noPom),
                Arguments.of("duplicates", State.BROKEN, 1, brokenPom),
                Arguments.of("duplicates", State.CLEAN, 0, "Doppelte Klassen: 0 Funde in 1 Modul"),
                Arguments.of("duplicates", State.FINDING, 3, "Doppelte Klassen: 1 Fund in 2 Modulen – 1 mit verschiedenem Inhalt"),
                Arguments.of("analyze", State.NONE, 1, noPom),
                Arguments.of("analyze", State.BROKEN, 1, brokenPom),
                Arguments.of("analyze", State.CLEAN, 0, "Dependency-Nutzung: 0 undeklariert, 0 ungenutzt, 0 nur in Tests"),
                Arguments.of("analyze", State.FINDING, 3, "Dependency-Nutzung: 1 undeklariert, 1 ungenutzt, 0 nur in Tests"),
                Arguments.of(upgrade, State.NONE, 1, noPom),
                Arguments.of(upgrade, State.BROKEN, 1, brokenPom),
                Arguments.of(upgrade, State.CLEAN, 0, "Upgrade-Simulation: com.google.guava:guava 32.1.3-jre → 33.3.1-jre"),
                Arguments.of(upgrade, State.FINDING, 0, "Upgrade-Simulation: com.google.guava:guava 33.3.1-jre/32.1.3-jre → 33.3.1-jre"),
                Arguments.of("upgrade org.none:none:1.0", State.CLEAN, 1, "Upgrade-Simulation: org.none:none"),
                Arguments.of("upgrade org.none:none", State.CLEAN, 2, ""),
                // Preflight mit Projekt hängt vom Rechner ab (JDK, Maven, ~/.m2) – nur die Fälle, die immer rot sind
                Arguments.of("preflight", State.NONE, 3, "Preflight rot"),
                Arguments.of("preflight", State.BROKEN, 3, "Preflight rot"));
    }

    @ParameterizedTest(name = "{0} [{1}] → {2}")
    @MethodSource("projectCommands")
    void projectCommand(String command, State state, int code, String firstLine) {
        Path dir = CliProjects.create(tmp.resolve(state.name()), state);
        List<String> args = new ArrayList<String>(Arrays.asList(command.split(" ")));
        args.addAll(Arrays.asList("--project", dir.toString(), "--local-repo", dir.resolve("repo").toString()));
        Result r = CliProjects.run("", args.toArray(new String[0]));
        assertRun(r, code, firstLine.replace("$POM", dirOf(dir.resolve("pom.xml"))).replace("$DIR", dirOf(dir)));
    }

    @Test
    void markdownVariantsKeepTheExitCode() {
        Path dir = CliProjects.create(tmp.resolve("p"), State.FINDING);
        for (String command : new String[] {"exclusions", "duplicates", "analyze"}) {
            Result r = CliProjects.run("", command, "--markdown", "--project", dir.toString(), "--local-repo", dir.resolve("repo").toString());
            assertEquals(3, r.code, command + "\n" + r);
            assertTrue(r.out.startsWith("#"), command + "\n" + r);
        }
    }

    @Test
    void projectDirectoryCanAlsoBeTheTargetOfPreflightAndChanges() {
        Result preflight = CliProjects.run("", "preflight", dirOf(tmp));
        assertRun(preflight, 3, "Preflight rot");
        Result changes = CliProjects.run("", "changes", dirOf(tmp));
        assertRun(changes, 1, "Noch kein grüner Build gespeichert für " + dirOf(tmp));
    }

    // ---- changes ------------------------------------------------------------------------------------------------

    @Test
    void changesWithoutAnySnapshot() {
        assertRun(CliProjects.run("", "changes", "--project", dirOf(tmp)), 1, "Noch kein grüner Build gespeichert");
    }

    @Test
    void changesAfterAGreenRun() throws IOException {
        SnapshotStore store = SnapshotStore.defaultStore();
        store.saveGreen(tmp, snapshot("1.0", true));
        store.saveLatest(tmp, snapshot("1.0", true));
        assertRun(CliProjects.run("", "changes", "--project", dirOf(tmp)), 0, "Der letzte Lauf war grün (2026-11-19 10:00).");
    }

    @Test
    void changesAfterARedRunListTheDifferences() throws IOException {
        SnapshotStore store = SnapshotStore.defaultStore();
        store.saveGreen(tmp, snapshot("1.0", true));
        store.saveLatest(tmp, snapshot("2.0", false));
        Result r = CliProjects.run("", "changes", "--project", dirOf(tmp));
        assertRun(r, 0, "Seit dem letzten grünen Build (2026-11-19 10:00) bis zum letzten Lauf (2026-11-19 10:00):");
        assertTrue(r.out.contains("  - Dependency org.acme:lib 1.0 → 2.0"), r.toString());
    }

    @Test
    void changesAfterARedRunWithoutDifferences() throws IOException {
        SnapshotStore store = SnapshotStore.defaultStore();
        store.saveGreen(tmp, snapshot("1.0", true));
        store.saveLatest(tmp, snapshot("1.0", false));
        Result r = CliProjects.run("", "changes", "--project", dirOf(tmp));
        assertEquals(0, r.code, r.toString());
        assertTrue(r.out.contains("keine Änderung an Maven, JDK"), r.toString());
    }

    private static BuildSnapshot snapshot(String libVersion, boolean green) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_EXTENSION).setMavenVersion("3.9.11")
                .setJavaVersion("21.0.4").setSuccessful(green).setCreatedAt("2026-11-19T10:00:00Z");
        s.module("g:app").setVersion("1.0").setDependenciesResolved(true).getDependencies().put("org.acme:lib", libVersion);
        return s;
    }

    // ---- Befehle über einer Datei -------------------------------------------------------------------------------

    private Path logs() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("logs"));
        Files.writeString(dir.resolve("empty.log"), "");
        Files.writeString(dir.resolve("garbled.log"), "something went wrong somewhere\n[INFO] BUILD FAILURE\n");
        Files.writeString(dir.resolve("unknown.log"), "[ERROR] the flux capacitor is out of plutonium\n[INFO] BUILD FAILURE\n");
        Files.write(dir.resolve("finding.log"), resource("/logs/test-failures__assertion.log"));
        Files.writeString(dir.resolve("one-rule.yaml"), "version: 1\nrules:\n  - id: flux\n    number: 20\n"
                + "    category: Fluxkompensator\n    priority: 50\n    patterns:\n      - 'flux capacitor'\n    cause: 'Kein Plutonium.'\n");
        Files.writeString(dir.resolve("broken.yaml"), "version: 1\nrules:\n  - id: x\n    patterns:\n      - '('\n");
        Files.createDirectories(tmp.resolve("no-logs"));
        return dir;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = CliMatrixTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "diagnose missing.log          | 1 | Fehler: ",
            "diagnose empty.log            | 0 | Keine Fehler im Log gefunden.",
            "diagnose garbled.log          | 4 | Keine Fehlerzeile erkannt, obwohl das Log BUILD FAILURE meldet",
            "diagnose unknown.log --id     | 4 | unknown",
            "diagnose finding.log --id     | 0 | test-failures",
            "diagnose unknown.log --id --catalog one-rule.yaml | 0 | flux",
            "diagnose unknown.log --catalog broken.yaml | 3 | Fehler im Katalog: ",
            "outline missing.log           | 1 | Fehler: ",
            "outline empty.log             | 0 | Log: 0 Zeilen",
            "outline garbled.log           | 0 | Log: 2 Zeilen",
            "outline finding.log --strip   | 0 | [INFO]",
            "evaluate missing              | 1 | Fehler: ",
            "evaluate ../no-logs           | 0 | Datei",
            "evaluate .                    | 0 | Datei",
            "validate-catalog missing.yaml | 1 | Fehler: ",
            "validate-catalog empty.log    | 3 | Fehler im Katalog: ",
            "validate-catalog one-rule.yaml | 0 | OK: 1 Regel",
            "validate-catalog broken.yaml  | 3 | Fehler im Katalog: ",
    })
    void fileCommand(String command, int code, String firstLine) throws IOException {
        Path dir = logs();
        List<String> args = new ArrayList<String>();
        for (String a : command.trim().split(" +")) {
            boolean file = a.contains(".") && !a.startsWith("--");
            args.add(file ? dir.resolve(a).normalize().toString() : a.equals("missing") ? dir.resolve(a).toString() : a);
        }
        assertRun(CliProjects.run("", args.toArray(new String[0])), code, firstLine);
    }

    @Test
    void evaluateCountsTheLogsOfAFolder() throws IOException {
        Result r = CliProjects.run("", "evaluate", logs().toString());
        assertEquals(0, r.code, r.toString());
        assertTrue(r.out.contains("Logs: 4, eingeordnet: 1 (25 %)"), r.toString());
    }

    // ---- stdin ------------------------------------------------------------------------------------------------

    @Test
    void diagnoseReadsTheLogFromStdin() throws IOException {
        String log = new String(resource("/logs/test-failures__assertion.log"), StandardCharsets.UTF_8);
        assertRun(CliProjects.run(log, "diagnose", "-", "--id", "--project", dirOf(tmp)), 0, "test-failures");
    }

    @Test
    void outlineReadsTheLogFromStdin() {
        assertRun(CliProjects.run("[INFO] BUILD SUCCESS\n", "outline", "-"), 0, "Log: 1 Zeile");
    }

    @Test
    void whyReadsTheTreeFromStdin() {
        Path dir = CliProjects.create(tmp.resolve("p"), State.CLEAN);
        String tree = "[INFO] com.acme:app:jar:1.0\n[INFO] +- org.slf4j:slf4j-api:jar:2.0:compile\n"
                + "[INFO] \\- com.google.guava:guava:jar:32.1.3-jre:compile\n";
        Result r = CliProjects.run(tree, "why", CliProjects.GUAVA, "--tree", "-", "--project", dir.toString(),
                "--local-repo", dir.resolve("repo").toString());
        assertRun(r, 0, "Warum com.google.guava:guava 32.1.3-jre in com.acme:app:1.0?");
        assertEquals("", r.err);
    }

    @Test
    void anEmptyTreeOnStdinIsReported() {
        Path dir = CliProjects.create(tmp.resolve("p"), State.CLEAN);
        Result r = CliProjects.run("no tree here\n", "matrix", "--tree", "-", "--project", dir.toString(),
                "--local-repo", dir.resolve("repo").toString());
        assertEquals(0, r.code, r.toString());
        assertTrue(r.err.startsWith("Kein Baum in - gefunden"), r.toString());
    }

    // ---- Bedienfehler -----------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "''                              | 2 | Maven Detective – Build Doctor",
            "help                            | 0 | Maven Detective – Build Doctor",
            "--help                          | 0 | Maven Detective – Build Doctor",
            "bogus                           | 2 | Unbekannter Befehl: bogus",
            "--project                       | 2 | Unbekannter Befehl: --project",
            "diagnose                        | 2 | diagnose: Argument fehlt",
            "why                             | 2 | why: Argument fehlt",
            "upgrade                         | 2 | upgrade: Argument fehlt",
            "outline                         | 2 | outline: Argument fehlt",
            "evaluate                        | 2 | evaluate: Argument fehlt",
            "validate-catalog                | 2 | validate-catalog: Argument fehlt",
            "why guava                       | 2 | Erwartet groupId:artifactId",
            "why g:                          | 2 | Erwartet groupId:artifactId",
            "why :a                          | 2 | Erwartet groupId:artifactId",
            "matrix --bogus                  | 2 | Unbekannte Option: --bogus",
            "matrix --bogus=1                | 2 | Unbekannte Option: --bogus",
            "matrix -x                       | 2 | Unbekannte Option: -x",
            "matrix --project                | 2 | Option --project braucht einen Wert",
            "matrix --project --all          | 2 | Option --project braucht einen Wert",
            "matrix --markdown=yes           | 2 | Option --markdown hat keinen Wert",
            "outline a.log b.log             | 2 | Unerwartetes Argument: b.log",
            "outline a.log --context x       | 2 | --context erwartet eine Zahl, nicht „x“",
            "outline a.log --context 99999999999 | 2 | --context erwartet eine Zahl",
            "matrix --lang fr                | 2 | --lang erwartet en oder de, nicht „fr“",
            "matrix --pin g                  | 2 | --pin erwartet groupId:artifactId[:version]",
            "matrix --pin g:                 | 2 | --pin erwartet groupId:artifactId[:version]",
            "matrix --pin :a                 | 2 | --pin erwartet groupId:artifactId[:version]",
            "matrix --pin g:a:               | 2 | --pin erwartet groupId:artifactId[:version]",
            "matrix --pin g:a:1:x            | 2 | --pin erwartet groupId:artifactId[:version]",
            "preflight --nexus nexus.corp    | 2 | Keine Repository-URL",
    })
    void usageErrors(String command, int code, String firstLine) {
        Path dir = CliProjects.create(tmp.resolve("p"), State.FINDING);
        List<String> args = new ArrayList<String>();
        if (!command.isEmpty()) {
            args.addAll(Arrays.asList(command.trim().split(" +")));
        }
        if (command.startsWith("matrix --pin")) {
            args.addAll(Arrays.asList("--project", dir.toString(), "--local-repo", dir.resolve("repo").toString()));
        }
        assertRun(CliProjects.run("", args.toArray(new String[0])), code, firstLine);
    }

    @Test
    void usageErrorsPrintTheUsageToStderr() {
        Result r = CliProjects.run("", "matrix", "--bogus");
        assertEquals("", r.out);
        assertTrue(r.err.contains("Maven Detective – Build Doctor"), r.err);
    }

    @Test
    void aPathWithANullCharacterIsAUsageError() {
        Result r = CliProjects.run("", "diagnose", "build\0.log");
        assertEquals(2, r.code, r.toString());
        assertTrue(r.err.startsWith("Fehler: "), r.toString());
    }

    @Test
    void whyAcceptsAVersionAfterTheCoordinates() {
        Path dir = CliProjects.create(tmp.resolve("p"), State.CLEAN);
        Result r = CliProjects.run("", "why", CliProjects.GUAVA + ":1.0", "--project", dir.toString(),
                "--local-repo", dir.resolve("repo").toString());
        assertRun(r, 0, "Warum com.google.guava:guava 32.1.3-jre");
    }

    // ---- Sprache ----------------------------------------------------------------------------------------------

    @Test
    void langEnSwitchesTheOutputForThisCallOnly() {
        Path dir = CliProjects.create(tmp.resolve("p"), State.FINDING);
        String before = System.getProperty(Texts.PROPERTY);
        Result en = CliProjects.run("", "matrix", "--lang", "en", "--project", dir.toString(), "--local-repo", dir.resolve("repo").toString());
        assertRun(en, 0, "Conflict matrix: 2 modules, 4 artifacts, 1 with divergent versions");
        assertEquals(before, System.getProperty(Texts.PROPERTY), "--lang darf die Sprache nach dem Aufruf nicht verstellen");
        Result de = CliProjects.run("", "matrix", "--project", dir.toString(), "--local-repo", dir.resolve("repo").toString());
        assertRun(de, 0, "Konflikt-Matrix: 2 Module");
    }

    @Test
    void langAppliesToUsageErrorsToo() {
        assertRun(CliProjects.run("", "matrix", "--lang=en", "--bogus"), 2, "Unknown option: --bogus");
        assertRun(CliProjects.run("", "why", "--lang", "en"), 2, "why: argument missing");
    }
}
