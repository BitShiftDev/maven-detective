package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Logik der Einstellungen und Erste Schritte ohne laufende IDE. */
class DetectiveSettingsTest {

    @Test
    void schnappschuesseAbgeschaltetHeisstKeineAblage() {
        assertNull(DetectiveSettings.snapshotStore(false, ""));
        assertNull(DetectiveSettings.snapshotStore(false, "/tmp/x"));
    }

    @Test
    void ohneEigenenOrdnerWieDieExtension() {
        SnapshotStore store = DetectiveSettings.snapshotStore(true, "");
        assertNotNull(store);
        assertEquals(SnapshotStore.defaultStore().getRoot(), store.getRoot());
    }

    @Test
    void eigenerOrdnerIstBasisWieMavenDetectiveHome() {
        Path base = Paths.get(System.getProperty("java.io.tmpdir"), "md-home").toAbsolutePath();
        SnapshotStore store = DetectiveSettings.snapshotStore(true, base.toString());
        assertNotNull(store);
        assertEquals(base.resolve("snapshots"), store.getRoot());
    }

    @Test
    void ordnerMussAbsolutSein() {
        assertNull(DetectiveSettings.parseDir(""));
        assertNull(DetectiveSettings.parseDir("   "));
        assertNull(DetectiveSettings.parseDir("relativ/ordner"));
        assertEquals(Paths.get(System.getProperty("user.home"), "md"), DetectiveSettings.parseDir("~/md"));
        assertEquals(Paths.get(System.getProperty("user.home")), DetectiveSettings.parseDir("~"));
    }

    @Test
    void hinweisInDerPomZeigtAufDieErsteDependency() {
        String pom = """
                <project>
                  <dependencyManagement><dependencies><dependency>x</dependency></dependencies></dependencyManagement>
                  <dependencies>
                    <dependency>y</dependency>
                  </dependencies>
                </project>""";
        // dependencyManagement zählt nicht: Alt+Enter hilft an den Projekt-Dependencies
        assertEquals(pom.indexOf("<dependency>y"), FirstSteps.firstDependency(pom));
        assertEquals(-1, FirstSteps.firstDependency("<project><modules/></project>"));
        assertEquals(-1, FirstSteps.firstDependency("<project><dependencies></dependencies><build><plugins><plugin>"
                + "<dependencies><dependency>z</dependency></dependencies></plugin></plugins></build></project>"));
    }
}
