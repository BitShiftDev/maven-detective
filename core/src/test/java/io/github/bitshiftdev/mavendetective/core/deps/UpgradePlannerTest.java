package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Request;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Result;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Target;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFirstFix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hebelwahl des Upgrade-Simulators ({@link UpgradePlanner}, Testplan Welle 3): je Hebel ein Fall. Projekt = Root
 * ({@code com.acme:root}) mit dem Modul {@code app}; Parent und BOMs außerhalb liegen im lokalen Repository.
 */
class UpgradePlannerTest {

    @TempDir
    Path tmp;
    TestRepository repo;
    Path root;

    @BeforeEach
    void setUp() {
        repo = TestRepository.at(tmp.resolve("repo"));
        root = tmp.resolve("project");
        repo.pom("org.lib", "lib", "1.0");
        repo.pom("org.lib", "lib", "2.0");
        repo.pom(TestPom.of("org.via", "via", "1.0").dependency("org.lib", "lib", "1.0"));
    }

    static TestPom rootPom() {
        return TestPom.of("com.acme", "root", "1.0").packaging("pom").module("app");
    }

    static TestPom app() {
        return TestPom.of(null, "app", null).parent("com.acme", "root", "1.0");
    }

    Result simulate(Request request, TestPom rootPom, TestPom app) {
        EffectiveModelDifferentialTest.write(root.resolve("pom.xml"), rootPom.xml());
        EffectiveModelDifferentialTest.write(root.resolve("app/pom.xml"), app.xml());
        PomLocator locator = new PomLocator(root, repo.root());
        List<PomFile> poms = new ArrayList<>();
        poms.add(locator.load(root.resolve("pom.xml")));
        poms.add(locator.load(root.resolve("app/pom.xml")));
        return UpgradeSimulator.simulate(locator, poms, request, Map.of(), null);
    }

    String file(String module) {
        return root.resolve(module.isEmpty() ? "pom.xml" : module + "/pom.xml").toAbsolutePath().normalize().toString();
    }

    /** Genau ein Schritt mit diesem Typ und diesen Parametern. */
    static void assertOnlyStep(Result r, String type, String... params) {
        assertEquals(1, r.getSteps().size(), r.getSteps() + " " + r.getProblems());
        assertFirstFix(r.fixes(), type, params);
    }

    static void assertNoStep(Result r) {
        assertTrue(r.getSteps().isEmpty(), r.getSteps().toString());
        assertEquals(1, r.getProblems().size(), r.getProblems().toString());
    }

    @Test
    void parentAusserhalbWirdImModulAngehoben() {
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom"));
        repo.pom(TestPom.of("org.ext", "parent", "2").packaging("pom"));
        Result r = simulate(Request.artifact("org.ext", "parent", "2"),
                rootPom().parent("org.ext", "parent", "1"), app().dependency("org.lib", "lib", "1.0"));
        assertEquals(Target.PARENT, r.getTarget());
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "section", "parent", "artifact", "org.ext:parent",
                "version", "2", "current", "1");
    }

    @Test
    void parentMitAusdruckIstEinProblem() {
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom"));
        Result r = simulate(Request.artifact("org.ext", "parent", "2"),
                rootPom().parent("org.ext", "parent", "${parent.version}"), app());
        assertEquals(Target.PARENT, r.getTarget());
        assertNoStep(r);
    }

    @Test
    void bomImProjektWirdDortAngehoben() {
        repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.lib", "lib", "1.0"));
        repo.pom(TestPom.of("org.bom", "bom", "2").packaging("pom").managed("org.lib", "lib", "2.0"));
        Result r = simulate(Request.artifact("org.bom", "bom", "2"), rootPom().bom("org.bom", "bom", "1"),
                app().dependency("org.lib", "lib", null));
        assertEquals(Target.BOM, r.getTarget());
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "section", "dependencyManagement",
                "artifact", "org.bom:bom", "version", "2", "current", "1");
    }

    @Test
    void bomVersionAusPropertyImProjekt() {
        repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.lib", "lib", "1.0"));
        repo.pom(TestPom.of("org.bom", "bom", "2").packaging("pom").managed("org.lib", "lib", "2.0"));
        Result r = simulate(Request.artifact("org.bom", "bom", "2"),
                rootPom().property("bom.version", "1").bom("org.bom", "bom", "${bom.version}"),
                app().dependency("org.lib", "lib", null));
        assertEquals(Target.BOM, r.getTarget());
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "property", "bom.version", "version", "2", "current", "1");
    }

    @Test
    void bomImParentAusserhalbIstEinProblem() {
        repo.pom(TestPom.of("org.bom", "bom", "1").packaging("pom").managed("org.lib", "lib", "1.0"));
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom").bom("org.bom", "bom", "1"));
        Result r = simulate(Request.artifact("org.bom", "bom", "2"), rootPom().parent("org.ext", "parent", "1"),
                app().dependency("org.lib", "lib", null));
        assertEquals(Target.BOM, r.getTarget());
        assertNoStep(r);
    }

    @Test
    void direkteVersionImModul() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom(), app().dependency("org.lib", "lib", "1.0"));
        assertEquals(Target.ARTIFACT, r.getTarget());
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file("app"), "section", "dependencies", "artifact", "org.lib:lib",
                "version", "2.0", "current", "1.0");
    }

    @Test
    void direkteVersionAusPropertyImRoot() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().property("lib.version", "1.0"),
                app().dependency("org.lib", "lib", "${lib.version}"));
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "property", "lib.version", "version", "2.0", "current", "1.0");
    }

    @Test
    void propertyAusParentAusserhalbWirdImOberstenProjektPomUeberschrieben() {
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom").property("lib.version", "1.0"));
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().parent("org.ext", "parent", "1"),
                app().dependency("org.lib", "lib", "${lib.version}"));
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "property", "lib.version", "version", "2.0", "current", "1.0");
    }

    @Test
    void direkteVersionMitZusammengesetztemAusdruckIstEinProblem() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().property("major", "1"),
                app().dependency("org.lib", "lib", "${major}.0"));
        assertNoStep(r);
    }

    @Test
    void geerbteDependencyAusParentAusserhalbIstEinProblem() {
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom").dependency("org.lib", "lib", "1.0"));
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().parent("org.ext", "parent", "1"), app());
        assertTrue(r.getSteps().isEmpty(), r.getSteps().toString());
        assertTrue(r.getProblems().size() >= 1, r.getProblems().toString());
    }

    @Test
    void managementImProjekt() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().managed("org.lib", "lib", "1.0"),
                app().dependency("org.lib", "lib", null));
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "section", "dependencyManagement",
                "artifact", "org.lib:lib", "version", "2.0", "current", "1.0");
    }

    @Test
    void managementImParentAusserhalbWirdImOberstenProjektPomFestgelegt() {
        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom").managed("org.lib", "lib", "1.0"));
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().parent("org.ext", "parent", "1"),
                app().dependency("org.lib", "lib", null));
        assertOnlyStep(r, FixAction.PIN_VERSION, "file", file(""), "artifact", "org.lib:lib", "version", "2.0", "current", "1.0");
    }

    @Test
    void bomMitGleicherVersionsnummerWirdAngehoben() {
        repo.pom(TestPom.of("org.lib", "lib-bom", "1.0").packaging("pom").managed("org.lib", "lib", "1.0"));
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().bom("org.lib", "lib-bom", "1.0"),
                app().dependency("org.lib", "lib", null));
        assertOnlyStep(r, FixAction.SET_VERSION, "file", file(""), "section", "dependencyManagement",
                "artifact", "org.lib:lib-bom", "version", "2.0", "current", "1.0");
    }

    @Test
    void bomMitAndererVersionsnummerWirdUeberPinUebersteuert() {
        repo.pom(TestPom.of("org.bom", "bom", "7").packaging("pom").managed("org.lib", "lib", "1.0"));
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom().bom("org.bom", "bom", "7"),
                app().dependency("org.lib", "lib", null));
        assertOnlyStep(r, FixAction.PIN_VERSION, "file", file(""), "artifact", "org.lib:lib", "version", "2.0", "current", "1.0");
    }

    @Test
    void nurTransitivWirdImOberstenProjektPomFestgelegt() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0"), rootPom(), app().dependency("org.via", "via", "1.0"));
        assertOnlyStep(r, FixAction.PIN_VERSION, "file", file(""), "artifact", "org.lib:lib", "version", "2.0", "current", "1.0");
    }

    @Test
    void manageLegtFestObwohlEsEineDirekteVersionGibt() {
        Result r = simulate(Request.artifact("org.lib", "lib", "2.0").pinned(), rootPom(),
                app().dependency("org.lib", "lib", "1.0"));
        assertOnlyStep(r, FixAction.PIN_VERSION, "file", file(""), "artifact", "org.lib:lib", "version", "2.0", "current", "1.0");
    }

    @Test
    void propertyWirdDortGeaendertWoSieImProjektStehtSonstUeberschrieben() {
        Result inProject = simulate(Request.property("lib.version", "2.0"), rootPom().property("lib.version", "1.0"),
                app().dependency("org.lib", "lib", "${lib.version}"));
        assertEquals(Target.PROPERTY, inProject.getTarget());
        assertOnlyStep(inProject, FixAction.SET_VERSION, "file", file(""), "property", "lib.version", "version", "2.0",
                "current", "1.0");

        repo.pom(TestPom.of("org.ext", "parent", "1").packaging("pom").property("lib.version", "1.0"));
        Result outside = simulate(Request.property("lib.version", "2.0"), rootPom().parent("org.ext", "parent", "1"),
                app().dependency("org.lib", "lib", "${lib.version}"));
        assertOnlyStep(outside, FixAction.SET_VERSION, "file", file(""), "property", "lib.version", "version", "2.0",
                "current", "1.0");
    }

    @Test
    void unbekanntePropertyIstEinProblem() {
        Result r = simulate(Request.property("nirgends", "2.0"), rootPom(), app().dependency("org.lib", "lib", "1.0"));
        assertNoStep(r);
    }

    @Test
    void ohneHebelGibtEsKeinenSchritt() {
        Result r = simulate(Request.artifact("org.none", "none", "2.0"), rootPom(), app().dependency("org.lib", "lib", "1.0"));
        assertNoStep(r);
    }

    @Test
    void zielversionSchonDaGibtKeinenSchritt() {
        Result r = simulate(Request.artifact("org.lib", "lib", "1.0"), rootPom(), app().dependency("org.lib", "lib", "1.0"));
        assertNoStep(r);
    }
}
