package io.github.bitshiftdev.mavendetective.idea;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.idea.ModelProject.dep;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Welle 6: Rechenlogik der Seiten aus Modul A ohne Swing – Filter, Suche, Auswahl → Fixes. Die Kern-Ergebnisse
 * entstehen an kleinen echten Projekten ({@link ModelProject}).
 */
class PanelModelsTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void deutsch() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
    }

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    private static List<String> gas(List<? extends Object> rows) {
        List<String> out = new ArrayList<>();
        for (Object o : rows) {
            out.add(o instanceof ConflictMatrix.Row r ? r.ga() : o instanceof DependencyUsage.Finding f ? f.ga()
                    : o instanceof ExclusionAudit.Finding e ? e.getExclusion().ga() : String.valueOf(o));
        }
        return out;
    }

    @Nested
    class Filter {

        @Test
        void suchtextWirdGetrimmtUndKleinGeschrieben() {
            PanelFilter.Filtered<String> f = PanelFilter.apply(List.of("Alpha", "beta", "GAMMA"), s -> true, "  AL ",
                    (s, q) -> PanelFilter.contains(s, q));
            assertEquals(List.of("Alpha"), f.rows());
            assertEquals(2, f.hidden());
        }

        @Test
        void schalterUndSucheZaehlenGemeinsamAlsAusgeblendet() {
            PanelFilter.Filtered<String> f = PanelFilter.apply(List.of("a1", "a2", "b1"), s -> !s.equals("a2"), "a",
                    (s, q) -> PanelFilter.contains(s, q));
            assertEquals(List.of("a1"), f.rows());
            assertEquals(2, f.hidden());
        }

        @Test
        void ohneSuchtextZaehltNurDerSchalter() {
            PanelFilter.Filtered<String> f = PanelFilter.apply(List.of("a", "b"), s -> true, null, (s, q) -> false);
            assertEquals(List.of("a", "b"), f.rows());
            assertEquals(0, f.hidden());
            assertFalse(PanelFilter.contains(null, "a"));
        }
    }

    @Nested
    class Matrix {

        private ConflictMatrix matrix() throws Exception {
            ModelProject p = new ModelProject(tmp).parent("", "app", "svc")
                    .module("app", dep("org.lib:lib:1.0") + dep("org.lib:common:1.0"))
                    .module("svc", dep("org.lib:lib:2.0") + dep("org.lib:common:1.0") + dep("org.lib:extra:1.0"));
            PomLocator locator = p.locator();
            return ConflictMatrix.build(locator, p.root, p.poms(locator), Map.of());
        }

        @Test
        void nurAbweichendeUndSuche() throws Exception {
            ConflictMatrix m = matrix();
            assertEquals(List.of("org.lib:lib"), gas(ConflictMatrixModel.visible(m, true, null).rows()));
            PanelFilter.Filtered<ConflictMatrix.Row> all = ConflictMatrixModel.visible(m, false, "COMMON");
            assertEquals(List.of("org.lib:common"), gas(all.rows()));
            assertEquals(2, all.hidden());
        }

        @Test
        void sprungAufGleichfoermigeZeileHebtDenFilterAuf() throws Exception {
            ConflictMatrix m = matrix();
            ConflictMatrixModel.Preselect same = ConflictMatrixModel.preselect(m, "org.lib:common", true);
            assertEquals("org.lib:common", same.row().ga());
            assertFalse(same.onlyDivergent());
            ConflictMatrixModel.Preselect divergent = ConflictMatrixModel.preselect(m, "org.lib:lib", true);
            assertTrue(divergent.onlyDivergent());
            ConflictMatrixModel.Preselect unknown = ConflictMatrixModel.preselect(m, "org.none:none", true);
            assertNull(unknown.row());
            assertTrue(unknown.onlyDivergent());
        }

        @Test
        void auswahlUeberlebtNeuberechnungUeberGa() throws Exception {
            ConflictMatrix before = matrix();
            ConflictMatrix after = matrix();
            ConflictMatrix.Row selected = before.getRows().get(1);
            List<ConflictMatrix.Row> rows = after.getRows();
            assertEquals(1, ConflictMatrixModel.indexOf(rows, selected));
            assertSame(rows.get(1), rows.get(ConflictMatrixModel.indexOf(rows, rows.get(1))));
            assertEquals(-1, ConflictMatrixModel.indexOf(ConflictMatrixModel.visible(after, true, null).rows(),
                    before.getRows().stream().filter(r -> !r.isDivergent()).findFirst().orElseThrow()));
            assertEquals(-1, ConflictMatrixModel.indexOf(rows, null));
        }

        @Test
        void festlegenHoechsteZuerstUndAngeboteJeZelle() throws Exception {
            ConflictMatrix.Row lib = ConflictMatrixModel.preselect(matrix(), "org.lib:lib", true).row();
            assertNotNull(lib.getPin(), "gemeinsamer Parent");
            assertEquals(List.of("2.0", "1.0"), ConflictMatrixModel.pinChoices(lib));
            assertEquals("2.0", lib.getPin().getVersion());
            assertEquals(List.of("1.0", "2.0"), ConflictMatrixModel.pinOffers(lib, "1.0"), "Zelle, dann Vorschlag");
            assertEquals(List.of("2.0"), ConflictMatrixModel.pinOffers(lib, "2.0"), "Zelle = Vorschlag: einmal");
            assertEquals(List.of("2.0"), ConflictMatrixModel.pinOffers(lib, null), "Zeilenkopf: nur der Vorschlag");
            ConflictMatrix.Row common = ConflictMatrixModel.preselect(matrix(), "org.lib:common", true).row();
            assertNull(common.getPin());
            assertTrue(ConflictMatrixModel.pinOffers(common, "1.0").isEmpty());
        }
    }

    @Nested
    class Exclusions {

        private ExclusionAudit audit(ModelProject p) {
            PomLocator locator = p.locator();
            return ExclusionAudit.build(locator, p.poms(locator), Map.of());
        }

        private ModelProject project() throws Exception {
            return new ModelProject(tmp).parent("", "app")
                    .module("app", dep("org.example:direct:1.0", "org.gone:gone", "org.used:used"))
                    .repoPom("org.example:direct:1.0", "org.used:used:1.0")
                    .repoPom("org.used:used:1.0");
        }

        @Test
        void ohneAlleNurEntfernbare() throws Exception {
            ExclusionAudit a = audit(project());
            assertEquals(List.of("org.gone:gone"), gas(ExclusionsModel.visible(a, false, null).rows()));
            assertEquals(List.of("org.gone:gone", "org.used:used"), gas(ExclusionsModel.visible(a, true, null).rows()));
            assertEquals(List.of("org.used:used"), gas(ExclusionsModel.visible(a, true, "USED:").rows()));
            assertEquals(2, ExclusionsModel.visible(a, true, "app").rows().size(), "Suche nach dem Modul");
            assertEquals(1, ExclusionsModel.removable(a.getFindings()));
        }

        @Test
        void nurEntfernbareTragenEinenFix() throws Exception {
            ExclusionAudit a = audit(project());
            List<FixAction> fixes = ExclusionsModel.fixes(a.getFindings());
            assertEquals(1, fixes.size());
            assertEquals(FixAction.REMOVE_EXCLUSION, fixes.get(0).getType());
            assertEquals("org.gone:gone", fixes.get(0).param("exclusion"));
            assertEquals("dependencies", fixes.get(0).param("section"));
        }

        @Test
        void sprungZielUeberDateiUndZeile() throws Exception {
            ExclusionAudit a = audit(project());
            ExclusionAudit.Finding used = a.getFindings().stream().filter(f -> !f.isRemovable()).findFirst().orElseThrow();
            assertSame(used, ExclusionsModel.at(a, used.getFile(), used.getLine()));
            assertNull(ExclusionsModel.at(a, used.getFile(), used.getLine() + 100));
        }
    }

    @Nested
    class Duplicates {

        /** {@code a} (über {@code direct}) teilt eine Klasse mit {@code b} und eine andere mit {@code c}: zwei Funde, beide raten zu „a raus“. */
        private DuplicateClasses duplicates() throws Exception {
            ModelProject p = new ModelProject(tmp).parent("", "app")
                    .module("app", dep("org.example:direct:1.0") + dep("org.b:b:1.0") + dep("org.c:c:1.0"))
                    .repoPom("org.example:direct:1.0", "org.a:a:1.0").repoJar("org.example:direct:1.0", "org.direct.D")
                    .repoPom("org.a:a:1.0").repoJar("org.a:a:1.0", "org.x.One=a", "org.x.Two=a")
                    .repoPom("org.b:b:1.0").repoJar("org.b:b:1.0", "org.x.One=b", "org.b.Own")
                    .repoPom("org.c:c:1.0").repoJar("org.c:c:1.0", "org.x.Two=c", "org.c.Own")
                    .repoPom("org.t:t:1.0");
            PomLocator locator = p.locator();
            return DuplicateClasses.build(locator, p.poms(locator), Map.of(), new JarIndex(), Cancellation.NONE);
        }

        @Test
        void gleicheExclusionAusZweiFundenZaehltEinmal() throws Exception {
            DuplicateClasses d = duplicates();
            List<DuplicateClasses.Clash> clashes = d.clashes();
            assertEquals(2, clashes.size(), clashes.toString());
            for (DuplicateClasses.Clash c : clashes) {
                assertEquals("org.a:a", c.getDrop().ga(), c.toString());
                assertEquals(1, DuplicateClassesModel.exclusionsFor(c).size());
            }
            List<FixAction> fixes = DuplicateClassesModel.exclusionsFor(clashes);
            assertEquals(1, fixes.size(), "der Knopf zählt, was eingefügt wird");
            assertEquals("org.example:direct", fixes.get(0).param("artifact"));
            assertEquals("org.a:a", fixes.get(0).param("exclusion"));
        }

        @Test
        void sucheUeberModulJarUndKlasse() throws Exception {
            DuplicateClasses d = duplicates();
            assertEquals(2, DuplicateClassesModel.visible(d, false, false, "APP").rows().size());
            assertEquals(1, DuplicateClassesModel.visible(d, false, false, "org.c:").rows().size());
            PanelFilter.Filtered<DuplicateClasses.Clash> one = DuplicateClassesModel.visible(d, false, false, "x.one");
            assertEquals(1, one.rows().size());
            assertEquals(1, one.hidden());
            assertEquals(2, DuplicateClassesModel.differing(d.clashes()));
            assertEquals(0, DuplicateClassesModel.unreadable(d));
        }

        @Test
        void inhaltsgleicheNurMitSchalter() throws Exception {
            ModelProject p = new ModelProject(tmp).parent("", "app")
                    .module("app", dep("org.b:b:1.0") + dep("org.c:c:1.0"))
                    .repoPom("org.b:b:1.0").repoJar("org.b:b:1.0", "org.x.Same=gleich")
                    .repoPom("org.c:c:1.0").repoJar("org.c:c:1.0", "org.x.Same=gleich");
            PomLocator locator = p.locator();
            DuplicateClasses d = DuplicateClasses.build(locator, p.poms(locator), Map.of(), new JarIndex(), Cancellation.NONE);
            assertTrue(d.clashes().get(0).isIdentical());
            assertTrue(DuplicateClassesModel.visible(d, true, false, null).rows().isEmpty());
            assertEquals(1, DuplicateClassesModel.visible(d, true, true, null).rows().size());
            assertTrue(DuplicateClassesModel.exclusionsFor(d.clashes().get(0)).isEmpty(), "beide direkt: keine Exclusion");
        }
    }

    @Nested
    class Usage {

        private DependencyUsage usage() throws Exception {
            ModelProject p = new ModelProject(tmp).parent(dep("org.inherited:inherited:1.0"), "app")
                    .module("app", dep("org.example:direct:1.0") + dep("org.unused:unused:1.0"))
                    .repoPom("org.example:direct:1.0", "org.a:a:1.0").repoJar("org.example:direct:1.0", "org.direct.D")
                    .repoPom("org.a:a:1.0").repoJar("org.a:a:1.0", "org.a.Api")
                    .repoPom("org.unused:unused:1.0").repoJar("org.unused:unused:1.0", "org.unused.U")
                    .repoPom("org.inherited:inherited:1.0").repoJar("org.inherited:inherited:1.0", "org.inherited.I");
            PomLocator locator = p.locator();
            Path source = p.root.resolve("app/src/main/java/App.java");
            return DependencyUsage.build(locator, p.poms(locator), Map.of(), new JarIndex(), pom -> {
                ClassUsages u = new ClassUsages();
                if ("app".equals(pom.getArtifactId())) {
                    u.countFile(false);
                    u.add("org.direct.D", false, source, 3);
                    u.add("org.a.Api", false, source, 4);
                }
                return u;
            }, Cancellation.NONE);
        }

        private DependencyUsage.Finding finding(DependencyUsage u, String ga) {
            return u.findings().stream().filter(f -> f.ga().equals(ga) && f.getModule().getName().equals("app")).findFirst()
                    .orElseThrow(() -> new AssertionError(ga + " in " + u.findings()));
        }

        @Test
        void sucheUeberArtifactUndGenannteKlasse() throws Exception {
            DependencyUsage u = usage();
            assertEquals(List.of("org.a:a"), gas(DependencyUsageModel.visible(u, false, true, "a.api").rows()));
            assertTrue(gas(DependencyUsageModel.visible(u, false, true, "UNUSED").rows()).contains("org.unused:unused"));
            assertEquals(0, DependencyUsageModel.visible(u, false, true, "kein-treffer").rows().size());
        }

        @Test
        void undeklariertUndUngenutztWerdenPomFixesGeerbtWirdSprung() throws Exception {
            DependencyUsage u = usage();
            DependencyUsage.Finding undeclared = finding(u, "org.a:a");
            DependencyUsage.Finding unused = finding(u, "org.unused:unused");
            DependencyUsage.Finding inherited = finding(u, "org.inherited:inherited");
            assertTrue(DependencyUsageModel.hasPomFix(undeclared));
            assertTrue(DependencyUsageModel.hasPomFix(unused));
            assertFalse(DependencyUsageModel.hasPomFix(inherited), "geerbt: nur die Stelle im Parent öffnen");

            DependencyUsageModel.Apply both = DependencyUsageModel.apply(List.of(undeclared, unused));
            assertEquals(List.of(FixAction.ADD_DEPENDENCY, FixAction.REMOVE_DEPENDENCY),
                    both.pomFixes().stream().map(FixAction::getType).toList());
            assertFalse(both.isJump());

            DependencyUsageModel.Apply jump = DependencyUsageModel.apply(List.of(inherited));
            assertTrue(jump.isJump());
            assertTrue(jump.isEnabled());
            assertEquals(FixAction.OPEN_FILE, jump.open().getType());

            DependencyUsageModel.Apply many = DependencyUsageModel.apply(List.of(inherited, inherited));
            assertFalse(many.isEnabled(), "mehrere ohne POM-Änderung: kein Sprung zu einem beliebigen");
            assertFalse(DependencyUsageModel.apply(List.of()).isEnabled());
        }

        @Test
        void angekreuzteVorAusgewaehlten() throws Exception {
            DependencyUsage u = usage();
            List<DependencyUsage.Finding> checked = List.of(finding(u, "org.a:a"));
            List<DependencyUsage.Finding> selected = List.of(finding(u, "org.unused:unused"));
            assertSame(checked, DependencyUsageModel.targets(checked, selected));
            assertSame(selected, DependencyUsageModel.targets(List.of(), selected));
        }

        @Test
        void zaehltJeArt() throws Exception {
            DependencyUsage u = usage();
            int[] n = DependencyUsageModel.counts(u.findings(), true);
            assertEquals(u.findings(DependencyUsage.Kind.UNDECLARED).size(), n[0]);
            assertEquals(u.findings(DependencyUsage.Kind.UNUSED).size(), n[1]);
            assertEquals(u.findings(DependencyUsage.Kind.TEST_ONLY).size(), n[2]);
        }
    }

    @Nested
    class Upgrade {

        @Test
        void vorschlaegeAusBaumParentUndBom() throws Exception {
            ModelProject p = new ModelProject(tmp).parent("", "app").module("app", dep("org.lib:lib:1.0"));
            PomLocator locator = p.locator();
            List<PomFile> poms = p.poms(locator);
            DepNode root = new DepNode("org.example", "app", "jar", null, "1", null);
            DepNode lib = root.add(new DepNode("org.lib", "lib", "jar", null, "1.0", "compile"));
            lib.add(new DepNode("org.lib", "json", "jar", null, "2.0", "compile"));
            root.add(new DepNode("org.lib", "json", "jar", null, "2.5", "compile")).setState(DepNode.State.CONFLICT);

            Map<String, String> s = UpgradeInput.suggestions(List.of(root), poms, locator);

            assertEquals("1.0", s.get("org.lib:lib"));
            assertEquals("2.0", s.get("org.lib:json"), "nur eingebundene Knoten");
            assertEquals("1", s.get("org.example:parent"), "Parent der Module");
            assertEquals(List.copyOf(s.keySet()).stream().sorted().toList(), List.copyOf(s.keySet()), "sortiert");
        }

        @ParameterizedTest(name = "lokal {0}, heute {1} → {2}")
        @CsvSource(nullValues = "-", value = {
                "'2.0,1.5,1.0', 1.0, 2.0",
                "'1.0,2.0', 1.0, 2.0",
                "'1.0', 1.0, -",
                "'0.9', 1.0, -",
                "'2.0', -, -",
                "'', 1.0, -",
                "'1.0-SNAPSHOT,1.0', 1.0-SNAPSHOT, 1.0",
        })
        void neuereLokaleVersion(String local, String current, String expected) {
            List<String> versions = local.isEmpty() ? List.of() : List.of(local.split(","));
            assertEquals(expected, UpgradeInput.newer(versions, current));
        }

        @Test
        void anfrageAusDenFeldern() {
            UpgradeSimulator.Request a = UpgradeInput.request(" org.lib:lib ", " 2.0 ", false);
            assertEquals("org.lib:lib", a.ga());
            assertEquals("2.0", a.getVersion());
            assertFalse(a.isPin());
            assertTrue(UpgradeInput.request("org.lib:lib", "2.0", true).isPin());
            UpgradeSimulator.Request prop = UpgradeInput.request("${lib.version}", "2.0", true);
            assertEquals("lib.version", prop.getProperty());
            assertFalse(prop.isPin(), "Properties werden nie festgelegt");
            assertEquals("spring.version", UpgradeInput.request("spring.version", "6.1", false).getProperty());
        }

        @ParameterizedTest(name = "\"{0}\" / \"{1}\"")
        @CsvSource(nullValues = "-", value = {
                "'', 2.0",
                "org.lib:lib, ''",
                "-, 2.0",
                "org.lib:lib, 1.0 2.0",
                "org.lib:lib, 1=2",
                "org.lib:lib, 1.0:x",
                "org.lib:lib:1.0, 2.0",
        })
        void unbrauchbareEingabeWirdMeldungStattSimulation(String what, String version) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> UpgradeInput.request(what, version, false));
            assertNotNull(e.getMessage());
        }

        @Test
        void gaNurMitZweiTeilen() {
            assertArrayEquals(new String[] {"org.lib", "lib"}, UpgradeInput.ga(" org.lib:lib "));
            for (String bad : List.of("org.lib:lib:", ":lib", "org.lib:", "${x}", "a:b:c", "")) {
                assertNull(UpgradeInput.ga(bad), bad);
            }
        }

        @Test
        void pfadDirektMitScopeSonstKette() {
            DepNode root = new DepNode("org.example", "app", "jar", null, "1", null);
            DepNode web = root.add(new DepNode("org.lib", "web", "jar", null, "1.0", "test"));
            DepNode json = web.add(new DepNode("org.lib", "json", "jar", null, "2.0", "runtime"));
            assertEquals("org.lib:web:1.0 (direkt, test)", UpgradeInput.path(web));
            assertTrue(UpgradeInput.path(json).endsWith(" (runtime)"), UpgradeInput.path(json));
            assertTrue(UpgradeInput.path(json).contains("org.lib:web"), UpgradeInput.path(json));
        }
    }
}
