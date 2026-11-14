package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.catalog.Template;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsersTest {

    @Test
    @SuppressWarnings("unchecked")
    void miniYamlReadsTheCatalogSubset() {
        String yaml = String.join("\n",
                "# Kommentar",
                "version: 1",
                "rules:",
                "  - id: a",
                "    patterns:",
                "      - 'single \\d+ ''quoted'''",
                "      - regex: \"double\"",
                "        cause: x # kein Kommentar im Wert? doch",
                "    checks: [one, 'two', three]",
                "    text: |",
                "      Zeile 1",
                "",
                "      Zeile 3",
                "    empty:",
                "  - id: b",
                "    list:",
                "    - x",
                "    - y");
        Map<String, Object> root = (Map<String, Object>) MiniYaml.parse(yaml);
        assertEquals("1", root.get("version"));
        List<Object> rules = (List<Object>) root.get("rules");
        assertEquals(2, rules.size());
        Map<String, Object> a = (Map<String, Object>) rules.get(0);
        List<Object> patterns = (List<Object>) a.get("patterns");
        assertEquals("single \\d+ 'quoted'", patterns.get(0));
        assertEquals("double", ((Map<String, Object>) patterns.get(1)).get("regex"));
        assertEquals("x", ((Map<String, Object>) patterns.get(1)).get("cause"));
        assertEquals(Arrays.asList("one", "two", "three"), a.get("checks"));
        assertEquals("Zeile 1\n\nZeile 3\n", a.get("text"));
        assertNull(a.get("empty"));
        assertEquals(Arrays.asList("x", "y"), ((Map<String, Object>) rules.get(1)).get("list"));
    }

    @Test
    void miniYamlReportsLineNumbers() {
        MiniYaml.YamlException e = assertThrows(MiniYaml.YamlException.class,
                () -> MiniYaml.parse("a: 1\nb: 'offen\n"));
        assertEquals(2, e.getLine());
        assertThrows(MiniYaml.YamlException.class, () -> MiniYaml.parse("a: 1\na: 2\n"));
        assertThrows(MiniYaml.YamlException.class, () -> MiniYaml.parse("a: \"\\d\"\n"));
    }

    @Test
    void catalogRejectsBrokenRules() {
        assertThrows(RuleCatalog.CatalogException.class, () -> RuleCatalog.parse(
                "rules:\n  - id: x\n    category: X\n    patterns: ['(unclosed']\n", "test"));
        assertThrows(RuleCatalog.CatalogException.class, () -> RuleCatalog.parse(
                "rules:\n  - id: x\n    category: X\n    patterns: ['ok']\n    fix:\n      type: explode\n      label: boom\n", "test"));
        assertThrows(RuleCatalog.CatalogException.class, () -> RuleCatalog.parse(
                "rules:\n  - id: x\n    category: X\n    patterns: ['ok']\n    typo: 1\n", "test"));
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "Zeile \"1\"\n\tTab ü");
        m.put("n", 42L);
        m.put("ok", true);
        m.put("list", Arrays.asList("a", 1L, null));
        String json = Json.write(m);
        assertEquals(m, Json.parse(json));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals("\u00e4", Json.parse("\"\\u00e4\""));
    }

    @Test
    void logParserStripsPrefixesAndNoise() {
        String log = "2026-11-12T06:48:01.123Z [INFO] Building x\n"
                + "\u001B[1;31m[ERROR]\u001B[m [ERROR] Some problems were encountered while processing the POMs:\n"
                + "[FATAL] Non-resolvable parent POM for a:b:1: boom -> [Help 2]\n"
                + "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.\n"
                + "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/ProjectBuildingException\n"
                + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project shop: x\n"
                + "  symbol:   class Foo\n"
                + "[INFO] done\n"
                + "unrelated line\n";
        BuildFailure f = MavenLogParser.parse(log);
        List<String> lines = f.searchableLines();
        assertEquals("Some problems were encountered while processing the POMs:", lines.get(0));
        assertEquals("Non-resolvable parent POM for a:b:1: boom", lines.get(1));
        assertTrue(lines.contains("symbol:   class Foo"));
        assertTrue(!lines.contains("unrelated line"));
        assertEquals(4, lines.size());
        assertEquals("shop", f.getErrors().get(2).getProject());
        assertEquals("org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile", f.getErrors().get(2).getMojo());
    }

    @Test
    void logParserReadsSessionHints() {
        BuildFailure f = MavenLogParser.parse("Apache Maven 3.9.9 (abc)\nJava version: 17.0.13, vendor: Eclipse Adoptium, runtime: /opt/jdk17\n[ERROR] x\n");
        assertEquals("3.9.9", f.getSession().getMavenVersion());
        assertEquals("17.0.13", f.getSession().getJavaVersion());
        assertEquals("/opt/jdk17", f.getSession().getJavaHome());
    }

    @Test
    void eventLogBuildsFailureAndIgnoresUnknownAndTruncatedLines() throws Exception {
        String jsonl = "{\"type\":\"session\",\"maven\":\"3.9.11\",\"java\":\"21.0.5\",\"offline\":true,\"profiles\":[\"ci\"]}\n"
                + "{\"type\":\"somethingNew\",\"x\":1}\n"
                + "{\"type\":\"repository\",\"event\":\"ARTIFACT_DOWNLOADED\",\"artifact\":\"g:a:jar:1\",\"repositoryId\":\"central\",\"repositoryUrl\":\"https://r\",\"failure\":\"boom\"}\n"
                + "{\"type\":\"mojoFailed\",\"project\":\"g:p:1\",\"mojo\":\"g:m:1:goal\",\"chain\":[{\"class\":\"E\",\"message\":\"top\"},{\"class\":\"C\",\"message\":\"cause\"}]}\n"
                + "{\"type\":\"projectFailed\",\"project\":\"g:p:1\",\"mojo\":\"g:m:1:goal\",\"chain\":[{\"class\":\"E\",\"message\":\"top\"},{\"class\":\"C\",\"message\":\"cause\"}]}\n"
                + "{\"type\":\"buildError\",\"problems\":[{\"source\":\"/p/pom.xml\",\"line\":6,\"column\":17,\"severity\":\"ERROR\",\"message\":\"bad\"}]}\n"
                + "{\"type\":\"buildErr";
        BuildFailure f = EventLog.read(new StringReader(jsonl));
        assertEquals(BuildFailure.Source.EVENTS, f.getSource());
        assertEquals(Boolean.TRUE, f.getSession().getOffline());
        assertEquals(Arrays.asList("ci"), f.getSession().getActiveProfiles());
        assertEquals(1, f.getRepositoryRequests().size());
        assertEquals(2, f.getErrors().size(), "mojoFailed und projectFailed mit gleicher Kette zählen einmal");
        assertTrue(f.searchableLines().contains("bad @ /p/pom.xml, line 6, column 17"));
    }

    @Test
    void templatesUseFallbacksAndAlternatives() {
        Map<String, String> vars = new HashMap<>();
        vars.put("b", "B");
        assertEquals("x B y", Template.render("x {a,b|none} y", vars));
        assertEquals("x none y", Template.render("x {a|none} y", vars));
        assertEquals("x y", Template.render("x {a} y", vars));
        assertEquals("$1 \\ B", Template.render("$1 \\ {b}", vars));
    }

    /** Review #8: verbreitete CI- und Logger-Präfixe vor dem Level. */
    @Test
    void ciPrefixesBeforeTheLevelAreRecognised() {
        // {Präfix vor Level-Zeilen, Präfix vor allen anderen Zeilen}: CI-Zeitstempel stehen vor jeder Zeile,
        // Logger-Angaben (Thread, Millisekunden) nur vor Zeilen des Loggers
        String[][] prefixes = {
            {"", ""},
            {"12:01:02 ", "12:01:02 "},
            {"2026-11-12T06:48:01.1234567Z ", "2026-11-12T06:48:01.1234567Z "},
            {"[2026-11-12T06:48:01.123Z] ", "[2026-11-12T06:48:01.123Z] "},
            {"2026-11-12 06:48:01 ", "2026-11-12 06:48:01 "},
            {"2026-11-12 06:48:01,123 ", "2026-11-12 06:48:01,123 "},
            {"2026-11-12T06:48:01.123456Z 01O ", "2026-11-12T06:48:01.123456Z 01O "},
            {"2026-11-12T06:48:01.123456Z 00E+ ", "2026-11-12T06:48:01.123456Z 00E+ "},
            {"[main] ", ""},
            {"06:48:01 [main] ", "06:48:01 "},
            {"1234 ", ""},
            {"1234 [main] ", ""},
            {"2026-11-12T06:48:01Z 1234 [main] ", "2026-11-12T06:48:01Z "},
        };
        for (String[] p : prefixes) {
            String prefix = p[0];
            String other = p[1];
            String log = other + "Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)\n"
                    + other + "Java version: 21.0.5, vendor: Eclipse Adoptium, runtime: /opt/java/openjdk\n"
                    + prefix + "[INFO] BUILD FAILURE\n"
                    + prefix + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project shop: Compilation failure\n"
                    + other + "\tat org.example.Foo.bar(Foo.java:1)\n";
            BuildFailure f = MavenLogParser.parse(log);
            assertEquals(2, f.getErrors().size(), "Präfix '" + prefix + "'");
            assertTrue(f.getErrors().get(0).getText().startsWith("Failed to execute goal"), prefix);
            assertEquals("at org.example.Foo.bar(Foo.java:1)", f.getErrors().get(1).getText(), prefix);
            assertEquals("shop", f.getErrors().get(0).getProject(), prefix);
            // Review #39: mvn -V auch mit Präfix
            assertEquals("3.9.11", f.getSession().getMavenVersion(), prefix);
            assertEquals("21.0.5", f.getSession().getJavaVersion(), prefix);

            LogOutline outline = LogOutline.of(Arrays.asList(log.split("\n")));
            assertEquals(3, outline.getFirstError(), prefix);
            assertEquals(LogOutline.Result.FAILURE, outline.getBuildResult(), prefix);
        }
    }

    @Test
    void prefixIsOnlyStrippedWhenItLooksLikeOne() {
        assertEquals("3 errors", LogPrefix.parse("3 errors").getText());
        assertNull(LogPrefix.parse("3 errors").getLevel());
        assertEquals("Downloading from central: x", LogPrefix.parse("2026-11-12T06:48:01Z Downloading from central: x").getText());
        // Ein Level im Text ist kein Präfix: "[INFO] [ERROR] x" bleibt INFO
        assertEquals("INFO", LogPrefix.parse("[INFO] [ERROR] x").getLevel());
        assertEquals("[ERROR] x", LogPrefix.parse("[INFO] [ERROR] x").getText());
        assertNull(LogPrefix.parse("some text [ERROR] x").getLevel());
        assertEquals("ERROR", LogPrefix.parse("[ERROR]").getLevel());
        assertEquals("", LogPrefix.parse("[ERROR]").getText());
    }

    /** Review #9: Zeilen aus gescheiterten Tests werden markiert, Maven-Meldungen danach nicht mehr. */
    @Test
    void testOutputIsMarked() {
        BuildFailure f = MavenLogParser.parse(String.join("\n",
                "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in a.FooTest",
                "[ERROR] a.FooTest.x -- Time elapsed: 0.1 s <<< ERROR!",
                "java.util.zip.ZipException: invalid LOC header (bad signature)",
                "\tat a.Foo.read(Foo.java:3)",
                "[INFO]",
                "[ERROR] Errors: ",
                "[ERROR]   FooTest.x:3 » Zip invalid LOC header (bad signature)",
                "[INFO]",
                "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project a: There are test failures."));
        boolean[] expected = {true, true, true, true, true, true, false, false};
        assertEquals(expected.length, f.getErrors().size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], f.getErrors().get(i).isTestOutput(), f.getErrors().get(i).getText());
        }
        assertTrue(f.searchableLines(false).stream().noneMatch(l -> l.contains("ZipException")));
        assertTrue(f.searchableLines().stream().anyMatch(l -> l.contains("ZipException")));
    }

    /** Review #7: durchsuchte Zeilen sind gekürzt. */
    @Test
    void searchableLinesAreCapped() {
        StringBuilder sb = new StringBuilder("[ERROR] x ");
        for (int i = 0; i < 10000; i++) {
            sb.append("abcdefghij");
        }
        BuildFailure f = MavenLogParser.parse(sb.toString());
        assertEquals(BuildFailure.MAX_LINE_LENGTH, f.searchableLines().get(0).length());
    }
}
