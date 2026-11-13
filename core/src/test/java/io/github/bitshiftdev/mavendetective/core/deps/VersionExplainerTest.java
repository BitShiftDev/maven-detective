package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Source;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Station;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projekt und lokales Repository im Kleinen: Parent mit BOM-Import, BOM mit eigenem Parent und
 * verschachteltem Import, direkte Angaben mit Property und eine transitive Dependency.
 */
class VersionExplainerTest {

    @TempDir
    Path tmp;
    Path project;
    Path repo;
    PomLocator locator;
    VersionExplainer explainer;
    PomFile app;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    void repoPom(String g, String a, String v, String body) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        write(dir.resolve(a).resolve(v).resolve(a + "-" + v + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                """ + body + "\n</project>\n");
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        repo = tmp.resolve("repo");
        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>app</module></modules>
                  <properties>
                    <bom.version>1.0</bom.version>
                    <lib.version>9.9</lib.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.lib</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.5</version>
                      </dependency>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>platform-bom</artifactId>
                        <version>${bom.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <profiles>
                    <profile>
                      <id>legacy</id>
                      <properties><lib.version>1.0</lib.version></properties>
                    </profile>
                  </profiles>
                </project>
                """);
        write(project.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <properties>
                    <direct.version>7.0</direct.version>
                  </properties>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib-a</artifactId></dependency>
                    <dependency><groupId>org.lib</groupId><artifactId>lib-b</artifactId></dependency>
                    <dependency>
                      <groupId>org.lib</groupId>
                      <artifactId>lib-direct</artifactId>
                      <version>${direct.version}</version>
                    </dependency>
                    <dependency><groupId>org.lib</groupId><artifactId>lib-x</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>org.lib</groupId><artifactId>lib-y</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        repoPom("org.example", "platform-bom", "1.0", """
                  <parent><groupId>org.example</groupId><artifactId>bom-parent</artifactId><version>1.0</version></parent>
                  <artifactId>platform-bom</artifactId>
                  <packaging>pom</packaging>
                  <properties><lib.version>2.0</lib.version></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.lib</groupId><artifactId>lib-a</artifactId><version>${lib.version}</version></dependency>
                      <dependency><groupId>org.lib</groupId><artifactId>lib-direct</artifactId><version>6.0</version></dependency>
                      <dependency>
                        <groupId>org.example</groupId><artifactId>other-bom</artifactId><version>1.0</version>
                        <type>pom</type><scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                """);
        repoPom("org.example", "bom-parent", "1.0", """
                  <groupId>org.example</groupId>
                  <artifactId>bom-parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.lib</groupId><artifactId>lib-d</artifactId><version>4.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                """);
        repoPom("org.example", "other-bom", "1.0", """
                  <groupId>org.example</groupId>
                  <artifactId>other-bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>org.lib</groupId><artifactId>lib-c</artifactId><version>3.1</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                """);
        repoPom("org.lib", "lib-x", "1.0", """
                  <groupId>org.lib</groupId>
                  <artifactId>lib-x</artifactId>
                  <version>1.0</version>
                  <properties><t.version>5.0</t.version></properties>
                  <dependencies>
                    <dependency><groupId>org.lib</groupId><artifactId>lib-t</artifactId><version>${t.version}</version></dependency>
                  </dependencies>
                """);
        locator = new PomLocator(project, repo);
        explainer = new VersionExplainer(locator, project);
        app = locator.load(project.resolve("app"));
        assertNotNull(app);
    }

    static Station first(VersionExplanation x, Station.Kind kind) {
        for (Station s : x.getStations()) {
            if (s.getKind() == kind) {
                return s;
            }
        }
        return null;
    }

    static boolean hintContains(VersionExplanation x, String text) {
        for (Station s : x.hints()) {
            if (s.getText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void pomFileKeepsLineNumbers() {
        PomFile.Dep direct = null;
        for (PomFile.Dep d : app.getDependencies()) {
            if (d.getArtifactId().equals("lib-direct")) {
                direct = d;
            }
        }
        assertNotNull(direct);
        assertEquals("${direct.version}", direct.getVersion());
        assertEquals(18, direct.getVersionLine());
        assertEquals(10, app.getProperties().get("direct.version").getLine());
        assertEquals(3, app.getParentLine());
        PomFile root = locator.load(project);
        assertEquals(List.of("legacy"), root.getProfilesTouchingVersions());
        assertTrue(root.getManagedDependencies().get(1).isImport());
    }

    @Test
    void directVersionWithPropertyBeatsManagement() {
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-direct", null);
        assertEquals(Source.DIRECT, x.getSource());
        assertEquals("7.0", x.getVersion());
        Station prop = first(x, Station.Kind.PROPERTY);
        assertNotNull(prop);
        assertTrue(prop.getText().contains("${direct.version} = 7.0"), prop.getText());
        assertEquals(10, prop.getLine());
        assertTrue(hintContains(x, "nennt 6.0"), x.lines().toString());
    }

    @Test
    void managedInParent() {
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-b", null);
        assertEquals(Source.MANAGED, x.getSource());
        assertEquals("1.5", x.getVersion());
        Station managed = first(x, Station.Kind.MANAGED);
        assertTrue(managed.getFile().endsWith("project/pom.xml"), managed.getFile());
        assertEquals(17, managed.getLine());
        Station inherited = first(x, Station.Kind.INHERITED);
        assertNotNull(inherited);
        assertTrue(inherited.getFile().endsWith("app/pom.xml"));
    }

    @Test
    void importedBomWithPropertyPitfall() {
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-a", null);
        assertEquals(Source.BOM, x.getSource());
        assertEquals("2.0", x.getVersion());
        Station imp = first(x, Station.Kind.IMPORT);
        assertTrue(imp.getText().contains("org.example:platform-bom:1.0"), imp.getText());
        assertTrue(imp.getFile().endsWith("project/pom.xml"));
        Station managed = first(x, Station.Kind.MANAGED);
        assertTrue(managed.getFile().endsWith("platform-bom-1.0.pom"), managed.getFile());
        // lib.version=9.9 im eigenen Parent wirkt nicht auf das importierte BOM
        assertTrue(hintContains(x, "${lib.version} = 9.9"), x.lines().toString());
        assertTrue(hintContains(x, "legacy"), "Profil-Hinweis fehlt");
    }

    @Test
    void nestedImportAndBomParent() {
        VersionExplanation c = explainer.explain(app, "org.lib", "lib-c", null);
        assertEquals(Source.BOM, c.getSource());
        assertEquals("3.1", c.getVersion());
        long imports = c.getStations().stream().filter(s -> s.getKind() == Station.Kind.IMPORT).count();
        assertEquals(2, imports);

        VersionExplanation d = explainer.explain(app, "org.lib", "lib-d", null);
        assertEquals("4.0", d.getVersion());
        assertTrue(first(d, Station.Kind.MANAGED).getFile().endsWith("bom-parent-1.0.pom"));
    }

    @Test
    void transitiveNearestWinsWithTree() throws IOException {
        String tree = """
                [INFO] --- dependency:3.8.1:tree (default-cli) @ app ---
                [INFO] com.acme:app:jar:1.0
                [INFO] +- org.lib:lib-x:jar:1.0:compile
                [INFO] |  \\- org.lib:lib-t:jar:5.0:compile
                [INFO] \\- org.lib:lib-y:jar:1.0:compile
                [INFO]    \\- (org.lib:lib-t:jar:4.0:compile - omitted for conflict with 5.0)
                [INFO] ------------------------------------------------------------------------
                """;
        List<DepNode> roots = DependencyTreeParser.parse(new StringReader(tree));
        assertEquals(1, roots.size());
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-t", roots.get(0));
        assertEquals(Source.TRANSITIVE, x.getSource());
        assertEquals("5.0", x.getVersion());
        Station decl = first(x, Station.Kind.DECLARED_TRANSITIVE);
        assertNotNull(decl, x.lines().toString());
        assertTrue(decl.getFile().endsWith("lib-x-1.0.pom"));
        assertTrue(first(x, Station.Kind.PROPERTY).getText().contains("t.version"));
        Station conflict = first(x, Station.Kind.CONFLICT);
        assertTrue(conflict.getText().contains("4.0 über org.lib:lib-y:1.0"), conflict.getText());
        assertTrue(conflict.getText().contains("gleich tief"));
    }

    @Test
    void transitiveWithoutTreeAsksForIt() {
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-t", null);
        assertEquals(Source.UNKNOWN, x.getSource());
        assertNull(x.getVersion());
        assertTrue(hintContains(x, "dependency:tree -Dverbose"));
    }

    @Test
    void treeMismatchIsReported() throws IOException {
        String tree = """
                com.acme:app:jar:1.0
                +- org.lib:lib-b:jar:1.6:compile
                \\- org.lib:lib-x:jar:1.0:compile
                """;
        DepNode root = DependencyTreeParser.parse(new StringReader(tree)).get(0);
        VersionExplanation x = explainer.explain(app, "org.lib", "lib-b", root);
        assertEquals("1.6", x.getVersion());
        assertTrue(hintContains(x, "Der aufgelöste Baum sagt 1.6"));
    }

    @Test
    void missingBomIsANoteNotACrash() throws IOException {
        write(project.resolve("lonely/pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>lonely</artifactId><version>1</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.gone</groupId><artifactId>gone-bom</artifactId><version>1</version>
                      <type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        PomFile lonely = locator.load(project.resolve("lonely"));
        VersionExplanation x = explainer.explain(lonely, "org.lib", "lib-a", null);
        assertTrue(hintContains(x, "Ein importiertes BOM fehlt im lokalen Repository (gone-bom:1)"), x.lines().toString());
        assertTrue(locator.getMissing().contains("org.gone:gone-bom:1"));
    }

    @Test
    void parsesVerboseTreeAnnotations() throws IOException {
        String tree = """
                [INFO] com.acme:a:jar:1.0
                [INFO] +- org.x:core:jar:2.0:compile (version managed from 1.0)
                [INFO] |  \\- (org.x:util:jar:tests:2.0:test - version managed from 1.1; omitted for duplicate)
                [INFO] \\- org.x:other:jar:1.0:runtime
                [INFO] com.acme:b:jar:1.0
                [INFO] \\- org.x:core:jar:2.0:compile
                """;
        List<DepNode> roots = DependencyTreeParser.parse(new StringReader(tree));
        assertEquals(2, roots.size());
        DepNode core = roots.get(0).getChildren().get(0);
        assertEquals("1.0", core.getPremanagedVersion());
        DepNode util = core.getChildren().get(0);
        assertEquals("tests", util.getClassifier());
        assertEquals(DepNode.State.DUPLICATE, util.getState());
        assertEquals("1.1", util.getPremanagedVersion());
        assertEquals(2, util.depth());
        assertEquals("runtime", roots.get(0).getChildren().get(1).getScope());
        assertEquals(1, roots.get(1).getChildren().size());
    }

    @Test
    void inlayZeigtVerwalteteVersionUndQuelle() {
        List<PomHints.ManagedVersion> hints = PomHints.managedVersions(locator, app);
        assertEquals(2, hints.size());   // lib-direct, lib-x, lib-y haben eine eigene Version
        PomHints.ManagedVersion a = hints.get(0);
        assertEquals("lib-a", a.getDependency().getArtifactId());
        assertEquals("2.0 · platform-bom", a.label());   // ${lib.version} im Kontext des BOMs
        assertTrue(a.getDescription().contains("org.example:platform-bom:1.0"), a.getDescription());
        assertTrue(a.getDescription().contains("Import geerbt von parent"), a.getDescription());
        assertTrue(a.getFile().endsWith("platform-bom-1.0.pom"), a.getFile());
        PomHints.ManagedVersion b = hints.get(1);
        assertEquals("1.5 · parent", b.label());
        assertEquals(project.resolve("pom.xml").toString(), b.getFile());
        assertTrue(b.getLine() > 0);
    }

    @Test
    void verdraengteHoehereVersionGehoertZurDirektenDependency() {
        DepNode root = new DepNode("com.acme", "app", "jar", null, "1.0", null);
        DepNode web = root.add(new DepNode("org.lib", "web", "jar", null, "1.0", "compile"));
        root.add(new DepNode("org.lib", "json", "jar", null, "2.0", "compile"));
        web.add(new DepNode("org.lib", "json", "jar", null, "2.5", "compile"))
                .setState(DepNode.State.CONFLICT).setRelatedVersion("2.0");
        web.add(new DepNode("org.lib", "old", "jar", null, "0.9", "compile"))
                .setState(DepNode.State.CONFLICT).setRelatedVersion("1.0");
        List<UpgradeSimulator.Risk> risks = UpgradeSimulator.risks(root);
        assertEquals(1, risks.size());   // 0.9 gegen 1.0 ist kein Risiko
        assertEquals("org.lib:web", risks.get(0).getDirect());
        assertEquals("2.5", risks.get(0).getWanted());
        assertEquals("2.0", risks.get(0).getChosen());
    }
}
