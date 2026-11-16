package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verlauf im Build Doctor: Einträge, Beschriftung, Regel des Laufs; Bericht und „Got it“-Ziel in der POM. */
class BuildHistoryTest {

    @BeforeEach
    void deutsch() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
    }

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    private static BuildDoctorService.Result result(Diagnosis... ds) {
        return new BuildDoctorService.Result(new BuildFailure(BuildFailure.Source.LOG), List.of(ds), "[ERROR] x", null, null, null, null, null);
    }

    private static Diagnosis corruptJar() {
        return new Diagnosis("corrupt-jar", 4, "Kaputtes JAR", "Das JAR ist beschädigt.", Confidence.CERTAIN, List.of(), List.of());
    }

    @Test
    void laufKenntSeineRegel() {
        BuildDoctorService.Run red = new BuildDoctorService.Run(false, 0, result(corruptJar()), "verify", null);
        assertEquals("corrupt-jar", red.ruleId());
        assertTrue(red.fromClipboard(), "ohne Ausführungsumgebung kommt der Log aus der Zwischenablage");
        assertNull(new BuildDoctorService.Run(true, 0, null, "verify", null).ruleId());
        assertNull(new BuildDoctorService.Run(false, 0, result(), null, null).ruleId());
        assertFalse(new BuildDoctorService.Run(true, 0, null, null, null).fromClipboard());
    }

    @Test
    void verlaufBeschriftetGruenRotUndUnbekannt() {
        assertEquals("grün", BuildDoctorPanel.historyLabel(new BuildDoctorService.Run(true, 0, null, null, null)));
        assertEquals("#4 Kaputtes JAR", BuildDoctorPanel.historyLabel(new BuildDoctorService.Run(false, 0, result(corruptJar()), null, null)));
        assertEquals("keine Fehlerzeile erkannt", BuildDoctorPanel.historyLabel(new BuildDoctorService.Run(false, 0, result(), null, null)));
        String text = BuildDoctorPanel.historyText(new BuildDoctorService.Run(false, 0, result(corruptJar()), null, null));
        assertTrue(text.contains(" · #4 Kaputtes JAR · "), text);
    }

    @Test
    void berichtOhneSetupVergleichIstDerKernbericht() {
        String md = BuildDoctorPanel.report(result(corruptJar()));
        assertTrue(md.contains("Kaputtes JAR"), md);
        assertTrue(md.contains("Das JAR ist beschädigt."), md);
    }

    @Test
    void ersteProjektDependencyFuerDenHinweis() {
        String pom = """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><artifactId>managed</artifactId></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency><artifactId>real</artifactId></dependency>
                  </dependencies>
                </project>
                """;
        assertEquals(pom.indexOf("<dependency><artifactId>real"), FirstSteps.firstDependency(pom));
        assertEquals(-1, FirstSteps.firstDependency("<project><dependencies></dependencies></project>"));
        assertEquals(-1, FirstSteps.firstDependency("""
                <project><build><plugins><plugin>
                  <dependencies><dependency/></dependencies>
                </plugin></plugins></build></project>
                """));
    }
    /** Review #51: ältere Einträge halten keinen Lauf und nur das Ende des Logs. */
    @Test
    void aeltereEintraegeWerdenSchlank() {
        String log = "x".repeat(BuildDoctorService.Result.SLIM_LOG_CHARS) + "\n[INFO] BUILD FAILURE\n";
        BuildDoctorService.Result big = new BuildDoctorService.Result(new BuildFailure(BuildFailure.Source.LOG), List.of(corruptJar()),
                "[INFO] Scanning for projects\n".repeat(100) + log, null, null, null, null, null);
        BuildDoctorService.Run run = new BuildDoctorService.Run(false, 1, big, null, null);
        BuildDoctorService.Run slim = run.slim();
        assertNull(slim.result().environment());
        assertTrue(slim.result().rawLog().endsWith("[INFO] BUILD FAILURE\n"), "Ende bleibt");
        assertFalse(slim.result().rawLog().contains("Scanning for projects"));
        assertTrue(slim.result().rawLog().length() < big.rawLog().length(), "kürzer");
        assertTrue(slim.result().rawLog().chars().allMatch(c -> c < 256), () -> "nur Latin-1: " + slim.result().rawLog().substring(0, 80));
        BuildDoctorService.Run small = new BuildDoctorService.Run(false, 1, result(corruptJar()), null, null);
        assertTrue(small.slim() == small, "nichts zu kürzen: derselbe Eintrag");
    }
}
