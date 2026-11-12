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
}
