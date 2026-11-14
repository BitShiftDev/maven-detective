package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Delta;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Kind;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.ModuleResult;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Request;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Result;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator.Target;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projekt: Root (Property {@code lib.version}, verwaltet org.lib:lib, importiert org.bom:bom) mit
 * den Modulen app (lib, x, e) und tool (direkt org.d:d:1.0). Im Repository liegen lib 0.9/1.0/2.0,
 * deren Bäume sich unterscheiden, und das BOM in 1.0 und 2.0.
 */
class UpgradeSimulatorTest {

    @TempDir
    Path tmp;
    Path repo;
    Path root;

    void artifact(String g, String a, String v, String body) throws IOException {
        Path dir = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(a + "-" + v + ".pom"), "<project><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>" + body + "</project>");
    }

    static String deps(String... deps) {
        return "<dependencies>" + String.join("", deps) + "</dependencies>";
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    void write(String path, String content) throws IOException {
        Path f = root.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    @BeforeEach
    void setUp() throws IOException {
        repo = tmp.resolve("repo");
        root = tmp.resolve("project");
        artifact("org.lib", "lib", "0.9", deps(dep("org.a", "a", "0.9", "")));
        artifact("org.lib", "lib", "1.0", deps(dep("org.a", "a", "1.0", ""), dep("org.c", "c", "1.0", "")));
        artifact("org.lib", "lib", "2.0", deps(dep("org.a", "a", "2.0", ""), dep("org.b", "b", "1.0", "")));
        artifact("org.a", "a", "0.9", "");
        artifact("org.a", "a", "1.0", "");
        artifact("org.a", "a", "1.5", "");
        artifact("org.a", "a", "2.0", "");
        artifact("org.b", "b", "1.0", "");
        artifact("org.c", "c", "1.0", "");
        artifact("org.x", "x", "1.0", deps(dep("org.a", "a", "1.5", "")));
        artifact("org.d", "d", "1.0", "");
        artifact("org.d", "d", "1.1", "");
        artifact("org.e", "e", "1.0", "");
        artifact("org.e", "e", "2.0", deps(dep("org.b", "b", "1.0", "")));
        artifact("org.bom", "bom", "1.0", "<packaging>pom</packaging><dependencyManagement>" + deps(dep("org.e", "e", "1.0", ""))
                + "</dependencyManagement>");
        artifact("org.bom", "bom", "2.0", "<packaging>pom</packaging><dependencyManagement>" + deps(dep("org.e", "e", "2.0", ""))
                + "</dependencyManagement>");

        write("pom.xml", """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>app</module>
                    <module>tool</module>
                  </modules>
                  <properties>
                    <lib.version>1.0</lib.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib</artifactId>
                        <version>${lib.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.bom</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write("app/pom.xml", """
                <project>
                  <parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency>
                    <dependency><groupId>org.x</groupId><artifactId>x</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>org.e</groupId><artifactId>e</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        write("tool/pom.xml", """
                <project>
                  <parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>tool</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.d</groupId>
                      <artifactId>d</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    Result simulate(Request request) {
        PomLocator locator = new PomLocator(root, repo);
        List<PomFile> poms = new ArrayList<>();
        for (String m : new String[] {"pom.xml", "app/pom.xml", "tool/pom.xml"}) {
            poms.add(locator.load(root.resolve(m)));
        }
        return UpgradeSimulator.simulate(locator, poms, request, Map.of(), null);
    }

    static ModuleResult module(Result r, String name) {
        for (ModuleResult m : r.getModules()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("Modul fehlt: " + name);
    }

    static Delta delta(ModuleResult m, String ga) {
        for (Delta d : m.getDeltas()) {
            if (d.ga().equals(ga)) {
                return d;
            }
        }
        return null;
    }

    @Test
    void propertyIsTheLeverAndTreeDiffShowsEverything() throws IOException {
        String before = Files.readString(root.resolve("pom.xml"));
        Result r = simulate(Request.parse("org.lib:lib:2.0"));

        assertEquals(Target.ARTIFACT, r.getTarget());
        assertEquals(1, r.getSteps().size());
        UpgradeSimulator.Step s = r.getSteps().get(0);
        assertEquals(FixAction.SET_VERSION, s.getFix().getType());
        assertEquals("lib.version", s.getFix().param("property"));
        assertEquals(root.resolve("pom.xml").toString(), s.getFile());
        assertEquals(11, s.getLine());
        assertTrue(s.getDescription().contains("von 1.0 auf 2.0"), s.getDescription());
        assertEquals(List.of("1.0"), r.getCurrentVersions());
        assertTrue(r.isComplete(), r.getMissing().toString());
        assertEquals(before, Files.readString(root.resolve("pom.xml")), "Simulation ändert nichts auf der Platte");

        ModuleResult app = module(r, "app");
        assertEquals(Kind.UPGRADED, delta(app, "org.lib:lib").getKind());
        assertEquals("1.0", app.getTargetBefore());
        assertEquals("2.0", app.getTargetAfter());
        Delta a = delta(app, "org.a:a");
        assertEquals(Kind.UPGRADED, a.getKind());
        assertEquals("1.0", a.getBeforeVersion());
        assertEquals("2.0", a.getAfterVersion());
        assertEquals(Kind.ADDED, delta(app, "org.b:b").getKind());
        assertEquals(Kind.REMOVED, delta(app, "org.c:c").getKind());
        assertTrue(app.getRisks().isEmpty(), "x verlangt nur 1.5 – das ist jetzt niedriger");
        assertNull(app.getNote());
        assertTrue(module(r, "tool").isUnchanged());
        assertFalse(r.hasRisks());

        String text = String.join("\n", r.lines(false));
        assertTrue(text.contains("Upgrade-Simulation: org.lib:lib 1.0 → 2.0"), text);
        assertTrue(text.contains("Property ${lib.version} von 1.0 auf 2.0"), text);
        assertTrue(text.contains("+ neu: org.b:b:1.0 über org.lib:lib:2.0 → org.b:b:1.0"), text);
        assertTrue(text.contains("− fällt weg: org.c:c:1.0"), text);
        assertTrue(text.contains("Ohne Änderung: root, tool"), text);
        assertTrue(r.markdown(false).contains("| ↑ | `org.a:a` | 1.0 | 2.0 | org.lib:lib:2.0 → org.a:a:2.0 |"), r.markdown(false));
    }

    @Test
    void downgradeCreatesRisks() {
        Result r = simulate(Request.artifact("org.lib", "lib", "0.9"));
        ModuleResult app = module(r, "app");
        assertEquals(Kind.DOWNGRADED, delta(app, "org.lib:lib").getKind());
        assertEquals(Kind.DOWNGRADED, delta(app, "org.a:a").getKind());
        assertEquals(1, app.getRisks().size());
        UpgradeSimulator.Risk risk = app.getRisks().get(0);
        assertEquals("org.a:a", risk.ga());
        assertEquals("1.5", risk.getWanted());
        assertEquals("0.9", risk.getChosen());
        assertEquals("org.x:x:1.0", risk.getRequester());
        assertTrue(r.hasRisks());
    }

    @Test
    void directVersionIsChangedWhereItStands() throws IOException {
        Result r = simulate(Request.parse("org.d:d:1.1"));
        UpgradeSimulator.Step s = r.getSteps().get(0);
        assertEquals("dependencies", s.getFix().param("section"));
        assertEquals(root.resolve("tool/pom.xml").toString(), s.getFile());
        assertEquals(8, s.getLine());
        assertEquals(Kind.UPGRADED, delta(module(r, "tool"), "org.d:d").getKind());

        // Übernehmen schreibt genau das
        new FixExecutor(Collections.emptyList()).execute(s.getFix());
        assertTrue(Files.readString(root.resolve("tool/pom.xml")).contains("<version>1.1</version>"));
    }

    @Test
    void transitiveOnlyIsPinnedInTheTopmostProjectPom() {
        Result r = simulate(Request.parse("org.a:a:1.5"));
        assertEquals(1, r.getSteps().size());
        FixAction fix = r.getSteps().get(0).getFix();
        assertEquals(FixAction.PIN_VERSION, fix.getType());
        assertEquals(root.resolve("pom.xml").toString(), fix.param("file"));
        Delta a = delta(module(r, "app"), "org.a:a");
        assertEquals("1.0", a.getBeforeVersion());
        assertEquals("1.5", a.getAfterVersion());
        assertEquals(1, a.getAfter().depth() - 1, "Pfad bleibt über lib");
    }

    @Test
    void missingVersionMakesSimulationIncomplete() {
        Result r = simulate(Request.parse("org.lib:lib:3.0"));
        assertFalse(r.isComplete());
        assertEquals(List.of("org.lib:lib:3.0"), r.getMissing());
        assertEquals(List.of("org.lib:lib:3.0"), PomFetcher.coordinates(r.getMissing()));
        assertTrue(String.join("\n", r.lines(false)).contains("Unvollständig – lokal fehlt 1 POM: org.lib:lib:3.0"));
    }

    @Test
    void bomCanBeUpgradedDirectly() {
        Result r = simulate(Request.parse("org.bom:bom:2.0"));
        assertEquals(Target.BOM, r.getTarget());
        UpgradeSimulator.Step s = r.getSteps().get(0);
        assertEquals("dependencyManagement", s.getFix().param("section"));
        assertEquals("org.bom:bom", s.getFix().param("artifact"));
        assertEquals(List.of("1.0"), r.getCurrentVersions());
        ModuleResult app = module(r, "app");
        assertEquals("2.0", delta(app, "org.e:e").getAfterVersion());
        assertEquals(Kind.ADDED, delta(app, "org.b:b").getKind());
        assertTrue(r.headline().startsWith("Upgrade-Simulation: BOM org.bom:bom 1.0 → 2.0"), r.headline());
    }

    @Test
    void artifactFromBomWithSameVersionLiftsTheBom() {
        Result r = simulate(Request.parse("org.e:e:2.0"));
        assertEquals("org.bom:bom", r.getSteps().get(0).getFix().param("artifact"));
        assertTrue(r.getSteps().get(0).getReason().contains("BOM bom"), r.getSteps().get(0).getReason());
    }

    @Test
    void managePinsEvenWhenAnotherLeverExists() {
        Result r = simulate(Request.parse("org.d:d:1.1").pinned());
        assertEquals(FixAction.PIN_VERSION, r.getSteps().get(0).getFix().getType());
        ModuleResult tool = module(r, "tool");
        assertTrue(tool.isUnchanged());
        assertNotNull(tool.getNote());
        assertTrue(tool.getNote().startsWith("org.d:d bleibt hier bei 1.0"), tool.getNote());
    }

    @Test
    void parentAndExternalPropertiesAreHandled() throws IOException {
        artifact("org.p", "parent", "1.0", "<packaging>pom</packaging><properties><f.version>1.0</f.version></properties>"
                + "<dependencyManagement>" + deps(dep("org.f", "f", "${f.version}", "")) + "</dependencyManagement>");
        artifact("org.p", "parent", "2.0", "<packaging>pom</packaging><properties><f.version>2.0</f.version></properties>"
                + "<dependencyManagement>" + deps(dep("org.f", "f", "${f.version}", "")) + "</dependencyManagement>");
        artifact("org.f", "f", "1.0", "");
        artifact("org.f", "f", "2.0", "");
        Path single = tmp.resolve("single");
        Files.createDirectories(single);
        Files.writeString(single.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.p</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>single</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.f</groupId><artifactId>f</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        PomLocator locator = new PomLocator(single, repo);
        List<PomFile> poms = List.of(locator.load(single.resolve("pom.xml")));

        Result parent = UpgradeSimulator.simulate(locator, poms, Request.parse("org.p:parent:2.0"), Map.of(), null);
        assertEquals(Target.PARENT, parent.getTarget());
        assertEquals("parent", parent.getSteps().get(0).getFix().param("section"));
        assertEquals(5, parent.getSteps().get(0).getLine());
        assertEquals("2.0", delta(parent.getModules().get(0), "org.f:f").getAfterVersion());

        Result f = UpgradeSimulator.simulate(locator, poms, Request.parse("org.f:f:2.0"), Map.of(), null);
        FixAction fix = f.getSteps().get(0).getFix();
        assertEquals("f.version", fix.param("property"));
        assertTrue(f.getSteps().get(0).getDescription().contains("<properties> mit ${f.version} = 2.0 angelegt"),
                f.getSteps().get(0).getDescription());
        assertEquals("2.0", delta(f.getModules().get(0), "org.f:f").getAfterVersion());

        Result prop = UpgradeSimulator.simulate(locator, poms, Request.parse("f.version=2.0"), Map.of(), null);
        assertEquals(Target.PROPERTY, prop.getTarget());
        assertEquals("2.0", delta(prop.getModules().get(0), "org.f:f").getAfterVersion());
    }

    @Test
    void unknownArtifactIsAProblem() {
        Result r = simulate(Request.parse("org.nope:nope:1.0"));
        assertTrue(r.getSteps().isEmpty());
        assertTrue(r.getProblems().get(0).contains("kommt in keinem Modul vor"), r.getProblems().toString());
    }

    @Test
    void deviationFromRealTreeIsReported() {
        PomLocator locator = new PomLocator(root, repo);
        List<PomFile> poms = List.of(locator.load(root.resolve("app/pom.xml")));
        DepNode real = new DepNode("com.acme", "app", "jar", null, "1.0", null);
        real.add(new DepNode("org.lib", "lib", "jar", null, "1.0", "compile"));
        real.add(new DepNode("org.x", "x", "jar", null, "1.0", "compile"));
        real.add(new DepNode("org.e", "e", "jar", null, "1.0", "compile"));
        real.add(new DepNode("org.a", "a", "jar", null, "1.5", "compile"));
        Result r = UpgradeSimulator.simulate(locator, poms, Request.parse("org.lib:lib:2.0"), Map.of("com.acme:app", real), null);
        String dev = r.getModules().get(0).getDeviation();
        assertNotNull(dev);
        assertTrue(dev.contains("org.a:a 1.5 statt 1.0"), dev);
    }

    /** Module, die schon die Zielversion haben, bekommen keinen Schritt „von X auf X“. */
    @Test
    void modulesAlreadyOnTheTargetGetNoStep() throws IOException {
        write("svc/pom.xml", """
                <project>
                  <parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>svc</artifactId>
                  <dependencies>
                    <dependency><groupId>org.d</groupId><artifactId>d</artifactId><version>1.1</version></dependency>
                    <dependency><groupId>org.x</groupId><artifactId>x</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        PomLocator locator = new PomLocator(root, repo);
        List<PomFile> poms = new ArrayList<>();
        for (String m : new String[] {"pom.xml", "app/pom.xml", "tool/pom.xml", "svc/pom.xml"}) {
            poms.add(locator.load(root.resolve(m)));
        }

        Result r = UpgradeSimulator.simulate(locator, poms, Request.parse("org.d:d:1.1"), Map.of(), null);
        assertEquals(1, r.getSteps().size(), r.lines(false).toString());
        assertEquals(root.resolve("tool/pom.xml").toString(), r.getSteps().get(0).getFile());
        assertTrue(r.getProblems().isEmpty(), r.getProblems().toString());

        // svc bekommt org.a:a 1.5 schon über x, app erst nach dem Pin: kein zweiter Schritt
        Result a = UpgradeSimulator.simulate(locator, poms, Request.parse("org.a:a:1.5"), Map.of(), null);
        assertEquals(1, a.getSteps().size(), a.lines(false).toString());

        Result same = UpgradeSimulator.simulate(locator, poms.subList(0, 3), Request.parse("org.d:d:1.0"), Map.of(), null);
        assertTrue(same.getSteps().isEmpty());
        assertEquals(List.of("org.d:d steht in jedem Modul schon auf 1.0 – nichts zu ändern."), same.getProblems());
        assertFalse(String.join("\n", same.lines(false)).contains("von 1.0 auf 1.0"));
    }

    /** Fehlt das gehobene BOM lokal, ist eine unbekannte Version kein Downgrade (Review: „drops 2.18.2 → null“). */
    @Test
    void missingBomIsNoDowngrade() {
        Result r = simulate(Request.parse("org.bom:bom:3.0"));
        assertFalse(r.isComplete());
        ModuleResult app = module(r, "app");
        assertTrue(app.isIncomplete());
        assertEquals(0, app.count(Kind.DOWNGRADED), app.getDeltas().toString());
        assertNull(delta(app, "org.e:e"), "Version unbekannt – steht unter „fehlt“");
        assertFalse(r.hasRisks());
        String text = String.join("\n", r.lines(false));
        assertFalse(text.contains("→ null"), text);
        assertTrue(text.contains("Modul app: keine Änderung – unvollständig, 1 POM fehlt"), text);
    }

    /** Fehlt die POM der neuen Version, fallen ihre Kinder nicht „weg“ – sie sind nur unbekannt. */
    @Test
    void missingPomHidesRemovalsButKeepsWhatIsKnown() {
        Result r = simulate(Request.parse("lib.version=3.0"));
        ModuleResult app = module(r, "app");
        assertTrue(app.isIncomplete());
        assertEquals(Kind.UPGRADED, delta(app, "org.lib:lib").getKind());
        assertEquals(0, app.count(Kind.REMOVED), app.getDeltas().toString());
        assertTrue(app.getRisks().isEmpty());
        assertFalse(r.hasRisks());
        assertFalse(module(r, "tool").isIncomplete());
    }

    @Test
    void requestParsing() {
        Request a = Request.parse("g:a:1.0");
        assertEquals("g:a", a.ga());
        assertEquals("1.0", a.getVersion());
        Request p = Request.parse("${jackson-bom.version}=2.18.0");
        assertTrue(p.isProperty());
        assertEquals("jackson-bom.version", p.getProperty());
        assertEquals("2.18.0", p.getVersion());
        assertThrows(IllegalArgumentException.class, () -> Request.parse("g:a"));
    }
}
