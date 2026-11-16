package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixRunnerTest {

    private static final String TAG = "<artifactId>maven-compiler-plugin</artifactId>";

    @Test
    void pluginOhneVersionWirdGefunden() {
        String pom = """
                <project>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                    </plugin>
                  </plugins></build>
                </project>
                """;
        assertEquals(pom.indexOf(TAG), FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void pluginMitVersionBleibtUnberuehrt() {
        String pom = """
                <plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <version>3.14.0</version>
                </plugin>
                """;
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void trefferAusserhalbEinesPluginsZaehltNicht() {
        // Erst ein abgeschlossenes Plugin, dann dieselbe artifactId in einer Exclusion
        String pom = """
                <plugin><artifactId>other</artifactId></plugin>
                <dependency><exclusions><exclusion>
                  <artifactId>maven-compiler-plugin</artifactId>
                </exclusion></exclusions></dependency>
                """;
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    @Test
    void sucheBeginntAbDerGemeldetenZeile() {
        String pom = """
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>1</version></plugin>
                <plugin><artifactId>maven-compiler-plugin</artifactId></plugin>
                """;
        int second = pom.indexOf(TAG, pom.indexOf(TAG) + 1);
        assertEquals(second, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, pom.indexOf('\n')));
        assertEquals(-1, FixRunner.pluginArtifactIdWithoutVersion(pom, TAG, 0));
    }

    /** Alle Quick-Fix-Typen des Kerns. */
    private static List<String> allFixTypes() throws IllegalAccessException {
        List<String> out = new java.util.ArrayList<>();
        for (Field f : FixAction.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                out.add((String) f.get(null));
            }
        }
        return out;
    }

    @Test
    void jederFixTypDesKernsLaeuftInDerIde() throws Exception {
        // show-dependency öffnet Modul A, manual ist nur Text – alles andere braucht einen Knopf auf der Karte
        for (String type : allFixTypes()) {
            boolean expected = !FixAction.SHOW_DEPENDENCY.equals(type) && !FixAction.MANUAL.equals(type);
            assertEquals(expected, FixRunner.isSupported(FixAction.of(type, "x")), type);
        }
    }

    @Test
    void jederPomFixHatEinenKnopf() throws Exception {
        for (String type : allFixTypes()) {
            if (PomEditor.isPomFix(type)) {
                assertTrue(FixRunner.isSupported(FixAction.of(type, "x")), type);
            }
        }
        assertTrue(PomEditor.isPomFix(FixAction.REMOVE_EXCLUSION));
    }

    @Test
    void meldungenEscapenKlartext() {
        assertEquals("&lt;server&gt; fehlt<br>Zeile 2", FixRunner.plainToHtml("<server> fehlt\nZeile 2"));
        assertEquals("a &amp; b", FixRunner.plainToHtml("a & b"));
    }

    @Test
    void testfallNameTraegtRegelUndZeit() {
        BuildDoctorService.Result result = new BuildDoctorService.Result(new BuildFailure(BuildFailure.Source.LOG),
                List.of(new Diagnosis("corrupt-jar", 4, "Kaputtes JAR", "x", Confidence.CERTAIN, List.of(), List.of())),
                "[ERROR] x", null, null, null, null);
        LocalDateTime at = LocalDateTime.of(2026, 11, 16, 9, 5, 7);
        assertEquals("missing-artifact__erkannt-als-corrupt-jar-20261116-090507.log",
                FixRunner.testCaseName(result, "missing-artifact", at));
        assertEquals("RICHTIGE-REGEL__erkannt-als-corrupt-jar-20261116-090507.log", FixRunner.testCaseName(result, " ", at));
        String odd = FixRunner.testCaseName(result, "../böse regel", at);
        assertTrue(odd.startsWith(".._b_se_regel__"), odd);
        assertFalse(odd.contains("/"), odd);
        BuildDoctorService.Result none = new BuildDoctorService.Result(new BuildFailure(BuildFailure.Source.LOG), List.of(),
                "", null, null, null, null);
        assertTrue(FixRunner.testCaseName(none, null, at).contains("erkannt-als-none-"));
    }
}
