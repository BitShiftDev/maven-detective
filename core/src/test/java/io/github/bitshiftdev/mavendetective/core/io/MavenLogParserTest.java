package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import io.github.bitshiftdev.mavendetective.core.testkit.TestLogs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenLogParserTest {

    private static final String GOAL = "Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile "
            + "(default-compile) on project shop-web: Compilation failure";

    static List<Arguments> goalLineVariants() {
        List<Arguments> out = new ArrayList<Arguments>();
        for (Map.Entry<String, String> v : TestLogs.variants("ERROR", GOAL + " -> [Help 1]").entrySet()) {
            out.add(Arguments.of(v.getKey(), v.getValue()));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goalLineVariants")
    void failedGoalIsReadBehindEveryPrefix(String name, String line) {
        BuildFailure f = MavenLogParser.parse(TestLogs.log("[INFO] BUILD FAILURE", line));

        assertEquals(1, f.getErrors().size());
        ErrorRecord e = f.getErrors().get(0);
        assertEquals(GOAL, e.getText());
        assertEquals("org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile", e.getMojo());
        assertEquals("default-compile", e.getExecution());
        assertEquals("shop-web", e.getProject());
        assertTrue(f.reportsFailure());
    }

    @Test
    void windowsLineEndingsGiveTheSameResult() {
        String[] lines = {
            "Apache Maven 3.9.11 (x)",
            "[INFO] BUILD FAILURE",
            "[ERROR] " + GOAL,
            "[ERROR] /src/App.java:[3,8] cannot find symbol",
            "  symbol:   class Foo",
        };

        BuildFailure unix = MavenLogParser.parse(TestLogs.log(lines));
        BuildFailure windows = MavenLogParser.parse(TestLogs.windowsLog(lines));

        assertEquals(texts(unix), texts(windows));
        assertEquals("3.9.11", windows.getSession().getMavenVersion());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[ERROR] x -> [Help 1]                 | x",
        "[ERROR] x->[Help 12]                  | x",
        "[ERROR] x   ->   [Help 2]             | x",
        "[ERROR] [ERROR] Some problems         | Some problems",
        "[ERROR] [FATAL] [ERROR] tief          | tief",
        "[ERROR] x -> [Help y]                 | x -> [Help y]",
        "[ERROR] x [Help 1]                    | x [Help 1]",
    })
    void helpLinksAndNestedLevelsAreRemoved(String line, String text) {
        assertEquals(text, MavenLogParser.parse(line).getErrors().get(0).getText());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[ERROR] -> [Help 1]",
        "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException",
        "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
        "[ERROR] Re-run Maven using the -X switch to enable full debug logging.",
        "[ERROR]",
    })
    void mavenBoilerplateIsNoError(String line) {
        assertFalse(MavenLogParser.parse(line).hasErrors(), line);
    }

    @Test
    void continuationLinesBelongToTheErrorBeforeOnly() {
        BuildFailure f = MavenLogParser.parse(TestLogs.log(
                "[ERROR] erste Meldung",
                "\tat a.B.c(B.java:1)",
                "[INFO] Weiter",
                "\tat x.Y.z(Y.java:1)"));

        assertEquals(2, f.getErrors().size());
        assertEquals("at a.B.c(B.java:1)", f.getErrors().get(1).getText());
    }

    @Test
    void sessionHintsAreReadFromTheVersionBanner() {
        BuildFailure f = MavenLogParser.parse(TestLogs.log(
                "Apache Maven 4.0.0-rc-4 (bed0f8174bf728978f86fac533aa38a9511f3872)",
                "Maven home: /opt/maven",
                "Java version: 17.0.12, vendor: Eclipse Adoptium, runtime: /opt/java/17",
                "[WARNING] The repository system is in offline mode"));

        assertEquals("4.0.0-rc-4", f.getSession().getMavenVersion());
        assertEquals("17.0.12", f.getSession().getJavaVersion());
        assertEquals("/opt/java/17", f.getSession().getJavaHome());
        assertEquals("/opt/maven/conf/settings.xml", f.getSession().getGlobalSettings());
        assertEquals(Boolean.TRUE, f.getSession().getOffline());
    }

    @Test
    void runtimeErrorsWithoutPrefixAreWeakAndCapped() {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 80; i++) {
            lines.add("Exception in thread \"t" + i + "\" java.lang.NoSuchMethodError: a.B.c()V");
        }
        lines.add("[INFO] BUILD FAILURE");

        BuildFailure f = MavenLogParser.parse(TestLogs.log(lines.toArray(new String[0])));

        assertEquals(50, f.getErrors().size());
        assertTrue(f.hasWeakErrors());
        assertTrue(f.getErrors().get(0).isWeak());
    }

    @Test
    void truncatedLogWithoutResultStillYieldsErrors() {
        BuildFailure f = MavenLogParser.parse("[ERROR] " + GOAL);

        assertTrue(f.hasErrors());
        assertFalse(f.reportsFailure());
        assertFalse(f.reportsSuccess());
    }

    @Test
    void buildResultIsFoundBehindUnknownPrefixButNotInLongLines() {
        assertTrue(MavenLogParser.parse("irgendwas BUILD SUCCESS").reportsSuccess());
        assertFalse(MavenLogParser.parse(repeat('x', 300) + " BUILD FAILURE").reportsFailure());
    }

    @Test
    void emptyLogHasNoErrorsAndNoSession() {
        BuildFailure f = MavenLogParser.parse("");

        assertFalse(f.hasErrors());
        assertNull(f.getSession().getMavenVersion());
    }

    /** Regression: {@code \s*->} am Zeilenende lief bei vielen Leerzeichen quadratisch (40 000 → 2,4 s). */
    @Test
    void errorLineWithManySpacesIsParsedInLinearTime() {
        String line = "[ERROR] a" + repeat(' ', 200_000) + "b [Help 1]";

        BuildFailure f = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> MavenLogParser.parse(line));

        assertTrue(f.getErrors().get(0).getText().endsWith("b [Help 1]"));
    }

    @Test
    void manyLongLinesStayFast() {
        StringBuilder log = new StringBuilder();
        String noise = repeat('=', 20_000);
        for (int i = 0; i < 200; i++) {
            log.append("[ERROR] ").append(noise).append(" -> [Help ").append(i).append("]\n");
            log.append("[INFO] ").append(noise).append('\n');
        }

        BuildFailure f = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> MavenLogParser.parse(log.toString()));

        assertEquals(200, f.getErrors().size());
        assertEquals(noise, f.getErrors().get(0).getText());
    }

    private static List<String> texts(BuildFailure f) {
        List<String> out = new ArrayList<String>();
        for (ErrorRecord e : f.getErrors()) {
            out.add(e.getText());
        }
        return out;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
