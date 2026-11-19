package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.cli.CliProjects.Result;
import io.github.bitshiftdev.mavendetective.core.cli.CliProjects.State;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import io.github.bitshiftdev.mavendetective.core.testkit.Golden;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ganze Ausgaben der Befehle als Golden Files ({@code golden/cli/<name>.<de|en>.<txt|md>}), über {@code Main.run} – also
 * Text und Markdown von Modul A (A1–A6), Log-Navigator und Diagnose-Karte so, wie sie auf der Konsole landen. Pfade des
 * Projekts stehen als {@code $PROJECT}. Neu schreiben: {@code ./gradlew :core:test -DupdateGolden=true}.
 */
class CommandsGoldenTest {

    private static final String USER_HOME = "user.home";

    @TempDir
    Path tmp;

    private final String homeBefore = System.getProperty(USER_HOME);
    private final String detectiveHomeBefore = System.getProperty(SnapshotStore.PROP_HOME);

    @BeforeEach
    void ownHomes() throws IOException {
        // Prüfungen der Diagnose lesen ~/.m2 – hier ein leeres Home, damit die Karte auf jedem Rechner gleich ist
        System.setProperty(USER_HOME, Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty(SnapshotStore.PROP_HOME, tmp.resolve("detective-home").toString());
    }

    @AfterEach
    void restoreHomes() {
        restore(USER_HOME, homeBefore);
        restore(SnapshotStore.PROP_HOME, detectiveHomeBefore);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "why             | 0 | why com.google.guava:guava",
            "why-markdown    | 0 | why com.google.guava:guava --markdown",
            "why-module      | 0 | why org.slf4j:slf4j-api --module a",
            "matrix          | 0 | matrix",
            "matrix-all      | 0 | matrix --all",
            "matrix-markdown | 0 | matrix --markdown",
            "exclusions      | 3 | exclusions",
            "exclusions-markdown | 3 | exclusions --markdown",
            "duplicates      | 3 | duplicates",
            "duplicates-markdown | 3 | duplicates --markdown",
            "analyze         | 3 | analyze",
            "analyze-markdown | 3 | analyze --markdown",
            "upgrade         | 0 | upgrade com.google.guava:guava:33.3.1-jre",
            "upgrade-markdown | 0 | upgrade com.google.guava:guava:33.3.1-jre --markdown",
            "upgrade-manage  | 0 | upgrade com.google.guava:guava:33.3.1-jre --manage --all",
            "upgrade-downgrade | 3 | upgrade com.google.guava:guava:32.1.3-jre",
    })
    void dependencyCommand(String name, int code, String command) {
        Path project = CliProjects.create(tmp.resolve("project"), State.FINDING);
        List<String> args = new ArrayList<String>(Arrays.asList(command.trim().split(" +")));
        args.addAll(Arrays.asList("--project", project.toString(), "--local-repo", project.resolve("repo").toString()));
        Golden.both("cli/" + name, command.contains("--markdown") ? "md" : "txt",
                () -> output(CliProjects.run("", args.toArray(new String[0])), code),
                Golden.path(project, "$PROJECT"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "outline         | outline $LOG",
            "outline-strip   | outline $LOG --strip",
    })
    void outlineCommand(String name, String command) throws IOException {
        Path log = copy("/logs-multi/jdk-mismatch+compile-missing-dependency__two-modules.log");
        Golden.both("cli/" + name, "txt", () -> output(CliProjects.run("", command.trim().replace("$LOG", log.toString()).split(" +")), 0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "diagnose-test-failures      | 0 | /logs/test-failures__assertion.log           | ''",
            "diagnose-test-failures-markdown | 0 | /logs/test-failures__assertion.log       | --markdown",
            "diagnose-two-errors         | 0 | /logs-multi/compile-missing-dependency+test-failures__fail-at-end.log | ''",
            "diagnose-unknown            | 4 | unknown                                       | ''",
    })
    void diagnoseCommand(String name, int code, String resource, String option) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path log = copy(resource);
        List<String> args = new ArrayList<String>(Arrays.asList("diagnose", log.toString(), "--project", project.toString()));
        if (!option.isEmpty()) {
            args.add(option);
        }
        Golden.both("cli/" + name, option.isEmpty() ? "txt" : "md",
                () -> output(CliProjects.run("", args.toArray(new String[0])), code).replaceAll("(?m)^- OS: .*$", "- OS: <os>"),
                Golden.path(project, "$PROJECT"));
    }

    private Path copy(String resource) throws IOException {
        if ("unknown".equals(resource)) {
            return Files.writeString(tmp.resolve("unknown.log"), "[INFO] Building app 1.0\n"
                    + "[ERROR] the flux capacitor is out of plutonium\n[INFO] BUILD FAILURE\n");
        }
        Path file = tmp.resolve(Path.of(resource).getFileName().toString());
        try (InputStream in = CommandsGoldenTest.class.getResourceAsStream(resource)) {
            Files.write(file, in.readAllBytes());
        }
        return file;
    }

    /** stdout, und stderr nur, wenn etwas drinsteht – mit Exit-Code als erster Zeile. */
    private static String output(Result r, int code) {
        assertEquals(code, r.code, r.toString());
        return "exit " + r.code + "\n" + r.out + (r.err.isEmpty() ? "" : "--- stderr\n" + r.err);
    }
}
