package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.snapshot.Change;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisCardTest {

    /** Erwartete Texte auf Deutsch (wie die Diagnosen aus dem Kern); beide Sprachen prüft MavenDetectiveBundleTest. */
    @BeforeEach
    void deutsch() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
    }

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    private static Diagnosis diagnosis(String rule, List<FixAction> fixes) {
        return new Diagnosis(rule, 4, "Kaputtes JAR", "Das JAR ist beschädigt.", Confidence.CERTAIN, List.of(), fixes);
    }

    private static BuildDoctorService.Result result(List<Diagnosis> ds) {
        return new BuildDoctorService.Result(new BuildFailure(BuildFailure.Source.LOG), ds, "[ERROR] x", null, null, null);
    }

    @Test
    void kopfZeigtNummerUndKategorie() {
        assertEquals("#4 Kaputtes JAR", DiagnosisCard.headline(diagnosis("corrupt-jar", List.of())));
        assertEquals("Unbekannt", DiagnosisCard.headline(new Diagnosis("unknown", 0, "Unbekannt", "?", Confidence.LIKELY, List.of(), List.of())));
    }

    @Test
    void erstAusfuehrbareFixesInKatalogReihenfolge() {
        FixAction manual = FixAction.of(FixAction.MANUAL, "Proxy prüfen");
        FixAction delete = FixAction.of(FixAction.DELETE_FILES, "JAR löschen", "files", "/x.jar");
        FixAction rebuild = FixAction.of(FixAction.REBUILD, "Neu bauen");
        assertEquals(List.of(delete, rebuild), DiagnosisCard.supportedFixes(List.of(manual, delete, rebuild)));
        assertEquals(List.of(), DiagnosisCard.supportedFixes(List.of(manual)));
    }

    @Test
    void dateiLinkKurz() {
        assertEquals("pom.xml:12", DiagnosisCard.fileLabel("/home/u/projekt/modul/pom.xml", 12));
        assertEquals("settings.xml", DiagnosisCard.fileLabel("/home/u/.m2/settings.xml", 0));
    }

    @Test
    void letzteZeilenOhneFarbcodes() {
        String log = "a\n\n\u001B[1;31m[ERROR]\u001B[m b\n  \nc\n";
        assertEquals("[ERROR] b\nc", DiagnosisCard.tail(log, 2));
        assertEquals("a\n[ERROR] b\nc", DiagnosisCard.tail(log, 10));
    }

    @Test
    void aenderungMitKurzemNamen() {
        Change parent = new Change(Change.Area.PARENT, "org.springframework.boot:spring-boot-starter-parent", "3.4.1", "3.5.0");
        assertEquals("spring-boot-starter-parent 3.4.1  →  3.5.0", DiagnosisCard.changeText(parent));
        assertEquals("21.0.4  →  17.0.12", DiagnosisCard.changeText(new Change(Change.Area.JDK, null, "21.0.4", "17.0.12")));
        assertTrue(DiagnosisCard.changeText(new Change(Change.Area.DEPENDENCY, "g:neu", null, "1.0")).startsWith("neu neu: 1.0"));
    }

    @Test
    void verlaufZeigtZeitKategorieUndSicherheit() {
        BuildDoctorService.Result r = result(List.of(diagnosis("corrupt-jar", List.of())));
        BuildDoctorService.Run run = new BuildDoctorService.Run(false, 0, r, "build", null);
        String text = BuildDoctorPanel.historyText(run);
        assertTrue(text.endsWith(" · #4 Kaputtes JAR · sicher"), text);
        assertEquals("corrupt-jar", run.ruleId());
        assertTrue(run.fromClipboard());   // ohne ExecutionEnvironment: aus der Zwischenablage
        assertNull(new BuildDoctorService.Run(true, 0, null, "build", null).ruleId());
    }

    @Test
    void testfallTraegtRichtigeRegelImNamen() {
        BuildDoctorService.Result r = result(List.of(diagnosis("corrupt-jar", List.of())));
        LocalDateTime t = LocalDateTime.of(2026, 11, 13, 14, 2, 5);
        assertEquals("artifact-offline__erkannt-als-corrupt-jar-20261113-140205.log", FixRunner.testCaseName(r, "artifact-offline", t));
        assertEquals("RICHTIGE-REGEL__erkannt-als-none-20261113-140205.log", FixRunner.testCaseName(result(List.of()), null, t));
        assertEquals("a_b__erkannt-als-corrupt-jar-20261113-140205.log", FixRunner.testCaseName(r, "a/b", t));
    }
}
