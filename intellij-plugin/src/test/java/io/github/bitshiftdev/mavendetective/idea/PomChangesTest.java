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
}
