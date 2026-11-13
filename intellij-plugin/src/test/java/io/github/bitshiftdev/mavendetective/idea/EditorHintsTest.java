package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** U4: Logik der Editor-Hinweise ohne IDE – Banner-Zuordnung, Bibliotheksnamen, Texte. */
class EditorHintsTest {

    private static final Path BASE = Paths.get("/home/u/projekt").toAbsolutePath();

    private static Diagnosis diagnosis(String rule, List<Evidence> evidence, List<FixAction> fixes) {
        return new Diagnosis(rule, 3, "Parent-POM nicht auflösbar", "Der Parent fehlt.", Confidence.CERTAIN, evidence, fixes);
    }

    @Test
    void bannerFindetDateiAusBelegMitZeile() {
        Path pom = BASE.resolve("app/pom.xml");
        Diagnosis other = diagnosis("jdk", List.of(Evidence.log("[ERROR] x")), List.of());
        Diagnosis parent = diagnosis("parent", List.of(
                Evidence.file("POM", pom.toString(), 0),
                Evidence.file("Parent-Angabe", pom.toString(), 7)), List.of());
        LastBuildNotificationProvider.Hit hit = LastBuildNotificationProvider.find(List.of(other, parent), pom, BASE);
        assertNotNull(hit);
        assertSame(parent, hit.diagnosis());
        assertEquals(7, hit.line());   // der Treffer mit Zeile gewinnt
        assertEquals("Der letzte Build scheiterte an dieser Datei (Zeile 7): #3 Parent-POM nicht auflösbar",
                LastBuildNotificationProvider.text(hit));
    }

    @Test
    void bannerFindetDateiAusFixUndRelativemPfad() {
        Path pom = BASE.resolve("pom.xml");
        Diagnosis d = diagnosis("plugin", List.of(), List.of(
                FixAction.of(FixAction.OPEN_FILE, "öffnen", "file", "pom.xml", "line", "1234")));
        LastBuildNotificationProvider.Hit hit = LastBuildNotificationProvider.find(List.of(d), pom, BASE);
        assertNotNull(hit);
        assertEquals(1234, hit.line());
        // Zeile ohne Tausenderpunkt
        assertEquals("Der letzte Build scheiterte an dieser Datei (Zeile 1234): #3 Parent-POM nicht auflösbar",
                LastBuildNotificationProvider.text(hit));
        assertNull(LastBuildNotificationProvider.find(List.of(d), BASE.resolve("app/pom.xml"), BASE));
    }

    @Test
    void mavenBibliothekAusExternalLibraries() {
        assertArrayEquals(new String[] {"com.fasterxml.jackson.core", "jackson-databind", "2.18.2"},
                IdeMaven.libraryCoordinates("Maven: com.fasterxml.jackson.core:jackson-databind:2.18.2"));
        assertArrayEquals(new String[] {"org.acme", "lib", "1.0"},
                IdeMaven.libraryCoordinates("Maven: org.acme:lib:test-jar:tests:1.0"));
        assertNull(IdeMaven.libraryCoordinates("Gradle: org.acme:lib:1.0"));
        assertNull(IdeMaven.libraryCoordinates("Maven: kaputt"));
        assertNull(IdeMaven.libraryCoordinates(null));
    }

    @Test
    void codeVisionText() {
        assertEquals("3 Module · 2 Versionen", ConflictCodeVisionProvider.label(3, 2));
        assertEquals("1 Modul · 1 Version", ConflictCodeVisionProvider.label(1, 1));
    }

    @Test
    void gutterTooltipNenntVerdraengteVersion() {
        DepNode root = new DepNode("com.acme", "app", "jar", null, "1.0", null);
        DepNode web = root.add(new DepNode("org.lib", "web", "jar", null, "1.0", "compile"));
        root.add(new DepNode("org.lib", "json", "jar", null, "2.0", "compile"));
        web.add(new DepNode("org.lib", "json", "jar", null, "2.5", "compile")).setState(DepNode.State.CONFLICT).setRelatedVersion("2.0");
        List<UpgradeSimulator.Risk> risks = UpgradeSimulator.risks(root);
        assertEquals("org.lib:json: verlangt 2.5, im Classpath landet 2.0", PomFindingsLineMarkerProvider.tooltip(risks, List.of()));
    }
}
