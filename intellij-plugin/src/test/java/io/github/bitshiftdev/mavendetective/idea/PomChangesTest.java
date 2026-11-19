package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomChangesTest {

    private static final Path POM = Paths.get("/projekt/app/pom.xml");

    private static final String XML = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.acme</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static FixAction exclusion(String exclusion) {
        return FixAction.of(FixAction.ADD_EXCLUSION, "Exclusion " + exclusion, "file", POM.toString(), "artifact", "com.acme:lib",
                "exclusion", exclusion);
    }

    @Test
    void mehrereFixesDerselbenDateiNacheinander() {
        PomChanges.Plan plan = PomChanges.plan(List.of(exclusion("a:b"), exclusion("c:d")), Map.of(POM, XML)::get);
        assertEquals(1, plan.files().size());
        PomChanges.FileChange c = plan.files().get(0);
        assertEquals(XML, c.before());
        assertEquals(2, c.edits().size());
        assertTrue(c.after().contains("<artifactId>b</artifactId>") && c.after().contains("<artifactId>d</artifactId>"), c.after());
        assertEquals(2, c.descriptions().size());
        assertTrue(plan.problems().isEmpty());
        assertNull(plan.failed());
        // Erneut auf das Original angewendet ergibt sich derselbe Text – so schreibt FixRunner ins Dokument
        String text = c.before();
        for (var e : c.edits()) {
            text = e.apply(text);
        }
        assertEquals(c.after(), text);
        assertTrue(c.firstLine() > 0);
    }

    @Test
    void scheiternderFixWirdProblemDieAnderenBleiben() {
        // Dieselbe Exclusion zweimal: der zweite Fix scheitert (Duplikat)
        PomChanges.Plan plan = PomChanges.plan(List.of(exclusion("a:b"), exclusion("a:b")), Map.of(POM, XML)::get);
        assertEquals(1, plan.editCount());
        assertEquals(1, plan.problems().size());
        assertTrue(plan.problems().get(0).startsWith("pom.xml: "), plan.problems().get(0));
        assertEquals(POM, plan.failed());
    }

    @Test
    void andereFixArtenZaehlenNicht() {
        FixAction open = FixAction.of(FixAction.OPEN_FILE, "Öffnen", "file", POM.toString(), "line", "3");
        PomChanges.Plan plan = PomChanges.plan(List.of(open), Map.of(POM, XML)::get);
        assertTrue(plan.isEmpty());
        assertTrue(plan.problems().isEmpty());
    }

    @Test
    void fehlendeDateiIstEinProblemNichtJederFix() {
        Path missing = Paths.get("/projekt/fehlt/pom.xml");
        FixAction a = FixAction.of(FixAction.ADD_EXCLUSION, "a", "file", missing.toString(), "artifact", "com.acme:lib", "exclusion", "a:b");
        FixAction b = FixAction.of(FixAction.ADD_EXCLUSION, "b", "file", missing.toString(), "artifact", "com.acme:lib", "exclusion", "c:d");
        PomChanges.Plan plan = PomChanges.plan(List.of(a, b, exclusion("e:f")), Map.of(POM, XML)::get);
        assertEquals(1, plan.problems().size(), plan.problems().toString());
        assertEquals(missing, plan.failed());
        assertEquals(List.of(POM), plan.files().stream().map(PomChanges.FileChange::file).toList());
    }

    @Test
    void dateienInDerReihenfolgeIhresErstenFixes() {
        Path parent = Paths.get("/projekt/pom.xml");
        String parentXml = XML.replace("<artifactId>app</artifactId>", "<artifactId>parent</artifactId>");
        FixAction pin = FixAction.of(FixAction.PIN_VERSION, "pin", "file", parent.toString(), "artifact", "com.acme:lib", "version", "2.0");
        PomChanges.Plan plan = PomChanges.plan(List.of(pin, exclusion("a:b"), exclusion("c:d")),
                Map.of(POM, XML, parent, parentXml)::get);
        assertEquals(List.of(parent, POM), plan.files().stream().map(PomChanges.FileChange::file).toList());
        assertEquals(3, plan.editCount());
    }

    @Test
    void repositoryAdresseInDerSettingsXml() {
        Path settings = Paths.get("/home/u/.m2/settings.xml");
        String xml = "<settings><mirrors><mirror><id>m</id><url>http://repo.example.com/m2/</url>"
                + "<mirrorOf>*</mirrorOf></mirror></mirrors></settings>";
        FixAction https = FixAction.of(FixAction.REPLACE_URL, "https", "file", settings.toString(),
                "search", "http://repo.example.com/m2", "replace", "https://repo.example.com/m2");
        PomChanges.Plan plan = PomChanges.plan(List.of(https), Map.of(settings, xml)::get);
        assertTrue(plan.files().get(0).after().contains("<url>https://repo.example.com/m2/</url>"), "Schrägstrich am Ende bleibt");
    }

    @Test
    void fixOhneKoordinatenWirdProblem() {
        FixAction broken = FixAction.of(FixAction.SET_SCOPE, "scope", "file", POM.toString(), "scope", "test");
        PomChanges.Plan plan = PomChanges.plan(List.of(broken, exclusion("a:b")), Map.of(POM, XML)::get);
        assertEquals(1, plan.problems().size());
        assertEquals(1, plan.editCount(), "der andere Fix bleibt");
    }
}
