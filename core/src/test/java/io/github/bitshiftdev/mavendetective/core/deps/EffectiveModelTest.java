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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was {@link EffectiveModel} über Mavens Ergebnis hinaus weiß (Testplan Welle 3): Herkunft jeder Angabe, fehlende
 * BOMs und Parents, Zyklen. Die Werte selbst prüft {@link EffectiveModelDifferentialTest} gegen Maven.
 */
class EffectiveModelTest {

    @TempDir
    Path tmp;
    TestRepository repo;
    Path root;

    @BeforeEach
    void setUp() {
        repo = TestRepository.at(tmp.resolve("repo"));
        root = tmp.resolve("project");
    }

    EffectiveModel model(TestPom pom) {
        write(root.resolve("pom.xml"), pom.xml());
        PomLocator locator = new PomLocator(root, repo.root());
        return locator.model(locator.load(root.resolve("pom.xml")));
    }

    @Test
    void propertyDesKindesVerdecktDieDesParentsMitZeile() {
        repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").property("v", "1.0"));
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").parent("org.p", "parent", "1").property("v", "2.0"));
        EffectiveModel.PropertyOrigin o = m.property("v");
        assertEquals("2.0", o.value);
        assertSame(m.getPom(), o.pom);
        assertEquals(13, o.line);
        assertEquals(1, o.shadowed.size());
        assertEquals("1.0", o.shadowed.get(0).value);
        assertEquals("parent", o.shadowed.get(0).pom.getArtifactId());
    }

    @Test
    void interpolationNenntDieBenutztenProperties() {
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").property("major", "2").property("full", "${major}.1"));
        EffectiveModel.Interpolated i = m.interpolate("${full}-${project.version}");
        assertEquals("2.1-1", i.value);
        assertTrue(i.isResolved());
        assertEquals(List.of("full", "major", "project.version"), i.used.stream().map(o -> o.name).toList());
        assertNull(i.used.get(2).pom, "eingebaut");
    }

    @Test
    void propertyZyklusBleibtStehenUndGiltAlsUnaufgeloest() {
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").property("a", "${b}").property("b", "x${a}"));
        EffectiveModel.Interpolated i = m.interpolate("${a}");
        assertFalse(i.isResolved());
        assertEquals("x${a}", i.value);
        assertEquals(List.of("a"), i.unresolved);
    }

    @Test
    void kaskadeWirdBeiDerGrenzeAbgebrochen() {
        TestPom pom = TestPom.of("org.app", "app", "1").property("p0", "xxxxxxxxxx");
        for (int i = 1; i <= 12; i++) {
            pom.property("p" + i, "${p" + (i - 1) + "}${p" + (i - 1) + "}");
        }
        EffectiveModel.Interpolated i = model(pom).interpolate("${p12}");
        assertFalse(i.isResolved());
        assertTrue(i.value.length() <= EffectiveModel.MAX_VALUE_LENGTH, "Länge " + i.value.length());
    }

    @Test
    void importKetteMitHerkunft() {
        repo.pom(TestPom.of("org.bom", "inner", "2").packaging("pom").managed("org.y", "y", "1.0"));
        repo.pom(TestPom.of("org.bom", "outer", "1").packaging("pom").bom("org.bom", "inner", "2"));
        repo.pom(TestPom.of("org.p", "parent", "1").packaging("pom").property("bom.v", "1").bom("org.bom", "outer", "${bom.v}"));
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").parent("org.p", "parent", "1"));
        EffectiveModel.Managed y = m.managed("org.y", "y");
        assertEquals("inner", y.declaredIn.getArtifactId());
        assertEquals(2, y.via.size());
        assertEquals("parent", y.via.get(0).declaredIn.getArtifactId(), "Import steht im Parent");
        assertEquals("1", y.via.get(0).version.value, "Version im Kontext des Moduls");
        assertEquals("outer", y.via.get(1).declaredIn.getArtifactId());
        assertEquals(1, m.getImports().size(), "nur die eigenen Importe, nicht die des BOMs");
    }

    @Test
    void fehlendesBomAuchVerschachtelt() {
        repo.pom(TestPom.of("org.bom", "outer", "1").packaging("pom").bom("org.bom", "gone", "9"));
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").bom("org.bom", "outer", "1").bom("org.bom", "missing", "1")
                .bom("org.bom", "open", "${undefined}"));
        List<String> missing = m.getMissingImports().stream().map(s -> s.declaration.ga() + ":" + s.version.value).toList();
        assertEquals(List.of("org.bom:gone:9", "org.bom:missing:1", "org.bom:open:${undefined}"), missing);
        assertNull(m.getMissingImports().get(1).bom);
    }

    @Test
    void importZyklusWirdNotiert() {
        repo.pom(TestPom.of("org.bom", "a", "1").packaging("pom").bom("org.bom", "b", "1").managed("org.x", "x", "1"));
        repo.pom(TestPom.of("org.bom", "b", "1").packaging("pom").bom("org.bom", "a", "1").managed("org.y", "y", "1"));
        write(root.resolve("pom.xml"), TestPom.of("org.app", "app", "1").bom("org.bom", "a", "1").xml());
        PomLocator locator = new PomLocator(root, repo.root());
        EffectiveModel m = locator.model(locator.load(root.resolve("pom.xml")));
        assertEquals("1", m.managed("org.x", "x").version().value);
        assertEquals("1", m.managed("org.y", "y").version().value);
        // notiert im Modell, dessen Import den Kreis schließt
        EffectiveModel b = locator.model(locator.byCoordinates("org.bom", "b", "1"));
        assertEquals(1, b.getNotes().size(), b.getNotes().toString());
    }

    @Test
    void fehlenderParentBeendetDieKetteMitNotiz() {
        EffectiveModel m = model(TestPom.of("org.app", "app", "1").parent("org.p", "missing", "1").property("v", "1"));
        assertEquals(1, m.getChain().size());
        assertEquals(1, m.getNotes().size());
    }

    @Test
    void parentZyklusEndet() {
        write(root.resolve("a/pom.xml"), TestPom.of("org.c", "a", "1").parent("org.c", "b", "1").xml()
                .replace("<parent>", "<parent><relativePath>../b/pom.xml</relativePath>"));
        write(root.resolve("b/pom.xml"), TestPom.of("org.c", "b", "1").parent("org.c", "a", "1").xml()
                .replace("<parent>", "<parent><relativePath>../a/pom.xml</relativePath>"));
        PomLocator locator = new PomLocator(root, repo.root());
        EffectiveModel m = locator.model(locator.load(root.resolve("a/pom.xml")));
        assertEquals(2, m.getChain().size());
    }

    @Test
    void ausdruckInDerGroupIdBehaeltZeileUndExclusions() {
        EffectiveModel m = model(TestPom.of("org.app", "app", "1")
                .dependency("${project.groupId}", "core", "1", TestPom.exclusions("org.x:x")));
        EffectiveModel.Declared d = m.declared("org.app", "core");
        assertEquals("org.app:core", d.dep.ga());
        assertEquals(8, d.dep.getLine());
        assertEquals(List.of("org.x:x"), d.dep.getExclusions());
        assertEquals("${project.groupId}", d.declaredIn.getDependencies().get(0).getGroupId(), "Rohfassung bleibt");
    }

    @Test
    void doppelterManagementEintragZeigtAufDieZeileDerGeltendenVersion() {
        EffectiveModel m = model(TestPom.of("org.app", "app", "1")
                .managed("org.m", "m", null, "<scope>runtime</scope>")
                .managed("org.m", "m", "2.0"));
        EffectiveModel.Managed mm = m.managed("org.m", "m");
        assertEquals("2.0", mm.version().value);
        assertEquals("runtime", mm.dep.getScope());
        assertEquals(17, mm.dep.getVersionLine());
    }
}
