package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.bitshiftdev.mavendetective.core.deps.EffectiveModelDifferentialTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inlay „Version · Quelle“ hinter Dependencies ohne {@code <version>} (Testplan Welle 3). */
class PomHintsTest {

    @TempDir
    Path tmp;
    TestRepository repo;
    Path root;

    @BeforeEach
    void setUp() {
        repo = TestRepository.at(tmp.resolve("repo"));
        root = tmp.resolve("project");
    }

    List<PomHints.ManagedVersion> hints(TestPom pom) {
        write(root.resolve("pom.xml"), pom.xml());
        PomLocator locator = new PomLocator(root, repo.root());
        return PomHints.managedVersions(locator, locator.load(root.resolve("pom.xml")));
    }

    @Test
    void eigenesManagementMitDateiUndZeileDerVersion() {
        List<PomHints.ManagedVersion> h = hints(TestPom.of("org.app", "app", "1").property("v", "2.0")
                .managed("org.a", "a", "${v}").dependency("org.a", "a", null).dependency("org.b", "b", "1.0"));
        assertEquals(1, h.size(), "nur Dependencies ohne Version");
        assertEquals("2.0 · dependencyManagement", h.get(0).label());
        assertEquals(root.resolve("pom.xml").toString(), h.get(0).getFile());
        assertEquals(15, h.get(0).getLine(), "Zeile von <version> im Management");
    }

    @Test
    void ausParentUndAusBom() {
        repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").managed("org.a", "a", "1.5"));
        repo.pom(TestPom.of("org.bom", "inner", "3").packaging("pom").managed("org.b", "b", "3.0"));
        repo.pom(TestPom.of("org.bom", "outer", "3").packaging("pom").bom("org.bom", "inner", "3"));
        List<PomHints.ManagedVersion> h = hints(TestPom.of("org.app", "app", "1").parent("org.p", "parent", "1")
                .bom("org.bom", "outer", "3").dependency("org.a", "a", null).dependency("org.b", "b", null));
        assertEquals("1.5 · parent", h.get(0).label());
        assertEquals("3.0 · inner", h.get(1).label(), "Quelle = das BOM, in dem der Eintrag steht");
        assertEquals(repo.dir("org.bom", "inner", "3").resolve("inner-3.pom").toString(), h.get(1).getFile());
    }

    @Test
    void classifierUndAusdruckInDerGroupId() {
        List<PomHints.ManagedVersion> h = hints(TestPom.of("org.app", "app", "1")
                .managed("org.app", "core", "1.0").managed("org.app", "core", "2.0", "<classifier>tests</classifier>")
                .dependency("${project.groupId}", "core", null)
                .dependency("org.app", "core", null, "<classifier>tests</classifier>"));
        assertEquals(2, h.size());
        assertEquals("1.0", h.get(0).getVersion());
        assertEquals("2.0", h.get(1).getVersion());
    }

    @Test
    void lieberKeinHinweisAlsEinFalscher() {
        List<PomHints.ManagedVersion> h = hints(TestPom.of("org.app", "app", "1")
                .bom("org.bom", "missing", "1")
                .managed("org.a", "a", "${undefined}")
                .dependency("org.a", "a", null)
                .dependency("org.b", "b", null)
                .dependency("${undefined.group}", "c", null));
        assertTrue(h.isEmpty(), h.toString());
    }
}
