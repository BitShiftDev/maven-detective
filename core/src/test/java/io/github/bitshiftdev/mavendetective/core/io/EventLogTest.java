package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import io.github.bitshiftdev.mavendetective.core.model.PomProblem;
import io.github.bitshiftdev.mavendetective.core.model.RepositoryRequest;
import io.github.bitshiftdev.mavendetective.core.testkit.Mutations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {

    private static final String SESSION = "{\"type\":\"session\",\"maven\":\"3.9.11\",\"java\":\"21.0.5\",\"javaHome\":\"/jdk\","
            + "\"offline\":true,\"userSettings\":\"/u/settings.xml\",\"localRepository\":\"/repo\",\"projectDirectory\":\"/p\","
            + "\"profiles\":[\"ci\",null,\"it\"],\"goals\":[\"verify\"]}";
    private static final String MOJO = "{\"type\":\"mojoFailed\",\"project\":\"g:a:1.0\",\"mojo\":\"g:p:1:compile\","
            + "\"execution\":\"default-compile\",\"chain\":[{\"class\":\"x.MojoFailureException\",\"message\":\"Compilation failure\"}]}";
    private static final String PROJECT = "{\"type\":\"projectFailed\",\"project\":\"g:a:1.0\","
            + "\"chain\":[{\"class\":\"x.MojoFailureException\",\"message\":\"Compilation failure\"}]}";

    @Test
    void sessionIsReadWithAllFields() throws IOException {
        BuildFailure f = read(SESSION);

        assertSame(BuildFailure.Source.EVENTS, f.getSource());
        assertEquals("3.9.11", f.getSession().getMavenVersion());
        assertEquals("21.0.5", f.getSession().getJavaVersion());
        assertEquals("/jdk", f.getSession().getJavaHome());
        assertEquals(Boolean.TRUE, f.getSession().getOffline());
        assertEquals("/repo", f.getSession().getLocalRepository());
        assertEquals(Arrays.asList("ci", "it"), f.getSession().getActiveProfiles());
        assertEquals(Collections.singletonList("verify"), f.getSession().getGoals());
        assertFalse(f.hasErrors());
    }

    @Test
    void offlineWithoutBooleanIsUnknown() throws IOException {
        assertNull(read("{\"type\":\"session\",\"offline\":\"yes\"}").getSession().getOffline());
    }

    @Test
    void mojoFailureAndProjectFailureWithTheSameExceptionCountOnce() throws IOException {
        BuildFailure f = read(MOJO, PROJECT);

        assertEquals(1, f.getErrors().size());
        ErrorRecord e = f.getErrors().get(0);
        assertEquals("g:p:1:compile", e.getMojo());
        assertEquals("default-compile", e.getExecution());
        assertEquals(Arrays.asList("Compilation failure", "x.MojoFailureException"), e.lines());
    }

    @Test
    void testFailuresAreMarkedAsTestOutput() throws IOException {
        BuildFailure f = read("{\"type\":\"testFailures\",\"project\":\"g:a:1\",\"text\":\"A.b <<< ERROR!\\njava.lang.NoSuchMethodError: x\"}");

        assertTrue(f.getErrors().get(0).isTestOutput());
        assertEquals(Arrays.asList("A.b <<< ERROR!", "java.lang.NoSuchMethodError: x"), f.getErrors().get(0).lines());
    }

    @Test
    void pomProblemsKeepLineAndColumnEvenAsDecimals() throws IOException {
        BuildFailure f = read("{\"type\":\"buildError\",\"problems\":[{\"source\":\"pom.xml\",\"line\":6.0,\"column\":17,"
                + "\"severity\":\"FATAL\",\"message\":\"Non-parseable POM\"},{\"message\":\"ohne Zeile\"},\"kein Objekt\"]}");

        List<PomProblem> problems = f.getErrors().get(0).getProblems();
        assertEquals(2, problems.size());
        assertEquals(6, problems.get(0).getLine());
        assertEquals(17, problems.get(0).getColumn());
        assertEquals(-1, problems.get(1).getLine());
        assertEquals("Non-parseable POM @ pom.xml, line 6, column 17", f.getErrors().get(0).lines().get(0));
    }

    @Test
    void repositoryEventsAreKept() throws IOException {
        BuildFailure f = read("{\"type\":\"repository\",\"event\":\"ARTIFACT_RESOLVED\",\"artifact\":\"g:a:jar:1\","
                + "\"repositoryId\":\"central\",\"repositoryUrl\":\"https://repo\",\"failure\":\"401\"}");

        RepositoryRequest r = f.getRepositoryRequests().get(0);
        assertEquals("central", r.getRepositoryId());
        assertTrue(r.isFailed());
    }

    @Test
    void unknownTypesFieldsAndBrokenLinesAreSkipped() throws IOException {
        BuildFailure f = read(
                "",
                "{\"type\":\"futureEvent\",\"x\":1}",
                "[1,2,3]",
                "kein json",
                "{\"type\":\"mojoFailed\",\"neu\":{\"tief\":[1]},\"chain\":\"kein Array\",\"text\":\"Fehler\"}",
                "{\"type\":\"mojoFailed\",\"text\":\"abgeschni");

        assertEquals(1, f.getErrors().size());
        assertEquals("Fehler", f.getErrors().get(0).getText());
        assertTrue(f.getErrors().get(0).getChain().isEmpty());
    }

    @Test
    void fileIsReadAsUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("events.jsonl");
        Files.write(file, "{\"type\":\"mojoFailed\",\"text\":\"Größe überschritten\"}\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("Größe überschritten", EventLog.read(file).getErrors().get(0).getText());
    }

    @Test
    void brokenEventFilesNeverThrow() {
        String valid = String.join("\n", SESSION, MOJO, PROJECT,
                "{\"type\":\"buildError\",\"problems\":[{\"source\":\"pom.xml\",\"line\":6,\"message\":\"m\"}]}");
        List<String> variants = Mutations.of(valid, 3000, 5);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < variants.size(); i++) {
                try {
                    EventLog.read(new StringReader(variants.get(i)));
                } catch (RuntimeException e) {
                    throw new AssertionError("Variante " + i + ": " + e + "\n" + variants.get(i), e);
                }
            }
        });
    }

    private static BuildFailure read(String... lines) throws IOException {
        return EventLog.read(new StringReader(String.join("\n", lines)));
    }
}
