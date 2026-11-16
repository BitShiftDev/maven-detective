package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Der Baum aus POMs soll wie {@code mvn dependency:tree -Dverbose} entscheiden. */
class DependencyResolverTest {

    @TempDir
    Path tmp;
    Path repo;

    void artifact(String g, String a, String v, String deps) throws IOException {
        Path dir = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(a + "-" + v + ".pom"), "<project><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><dependencies>" + deps + "</dependencies></project>");
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    PomFile project(String body) throws Exception {
        Path pom = tmp.resolve("app/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>" + body + "</project>");
        return PomFile.read(pom);
    }

    DependencyResolver.Tree resolve(PomFile pom) {
        return DependencyResolver.resolve(new PomLocator(pom.getFile().getParent(), repo), pom);
    }

    @BeforeEach
    void setUp() throws IOException {
        repo = tmp.resolve("repo");
        artifact("org.a", "a", "1.0", dep("org.c", "c", "1.0", "") + dep("org.t", "t", "1.0", "<scope>test</scope>")
                + dep("org.o", "o", "1.0", "<optional>true</optional>") + dep("org.p", "p", "1.0", "<scope>provided</scope>"));
        artifact("org.b", "b", "1.0", dep("org.c", "c", "2.0", "") + dep("org.r", "r", "1.0", "<scope>runtime</scope>"));
        artifact("org.c", "c", "1.0", dep("org.d", "d", "1.0", ""));
        artifact("org.c", "c", "2.0", dep("org.d", "d", "2.0", ""));
        artifact("org.c", "c", "3.0", "");
        artifact("org.d", "d", "1.0", "");
        artifact("org.d", "d", "2.0", "");
        artifact("org.r", "r", "1.0", "");
        artifact("org.x", "x", "1.0", dep("org.y", "y", "1.0", ""));
        artifact("org.y", "y", "1.0", dep("org.z", "z", "1.0", ""));
        artifact("org.z", "z", "1.0", "");
    }

    @Test
    void ciFriendlyRevisionFindetReactorGeschwister() throws Exception {
        Path root = tmp.resolve("ci");
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("pom.xml"), "<project><groupId>g</groupId><artifactId>root</artifactId><version>${revision}</version>"
                + "<packaging>pom</packaging><properties><revision>1.0-SNAPSHOT</revision></properties>"
                + "<modules><module>a</module><module>b</module></modules></project>");
        Files.writeString(root.resolve("a/pom.xml"), "<project><parent><groupId>g</groupId><artifactId>root</artifactId>"
                + "<version>${revision}</version></parent><artifactId>a</artifactId><dependencies>"
                + dep("org.x", "x", "1.0", "") + "</dependencies></project>");
        Files.writeString(root.resolve("b/pom.xml"), "<project><parent><groupId>g</groupId><artifactId>root</artifactId>"
                + "<version>${revision}</version></parent><artifactId>b</artifactId><dependencies>"
                + dep("g", "a", "${project.version}", "") + "</dependencies></project>");
        PomLocator locator = new PomLocator(root, repo);
        DependencyResolver.Tree t = DependencyResolver.resolve(locator, locator.load(root.resolve("b/pom.xml")));
        assertTrue(t.getMissing().isEmpty(), t.getMissing().toString());
        assertEquals("1.0-SNAPSHOT", t.getRoot().findIncluded("g", "a").getVersion());
        assertNotNull(t.getRoot().findIncluded("org.y", "y"), "transitive Dependencies des Geschwisters fehlen");
    }

    @Test
    void nearestWinsAndFirstDeclarationAtSameDepth() throws Exception {
        PomFile pom = project("<dependencies>" + dep("org.a", "a", "1.0", "") + dep("org.b", "b", "1.0", "") + "</dependencies>");
        DependencyResolver.Tree t = resolve(pom);
        assertTrue(t.getMissing().isEmpty(), t.getMissing().toString());
        DepNode c = t.getRoot().findIncluded("org.c", "c");
        assertEquals("1.0", c.getVersion());
        assertEquals("org.a:a:1.0 → org.c:c:1.0", c.pathText());
        List<DepNode> all = t.getRoot().findAll("org.c", "c");
        assertEquals(2, all.size());
        assertEquals(DepNode.State.CONFLICT, all.get(1).getState());
        assertEquals("1.0", all.get(1).getRelatedVersion());
        // d kommt nur über den Gewinner c:1.0 – der Teilbaum des Verlierers fehlt
        assertEquals("1.0", t.getRoot().findIncluded("org.d", "d").getVersion());
        assertEquals(1, t.getRoot().findAll("org.d", "d").size());
    }

    @Test
    void testProvidedAndOptionalAreNotTransitive() throws Exception {
        PomFile pom = project("<dependencies>" + dep("org.a", "a", "1.0", "") + "</dependencies>");
        DepNode root = resolve(pom).getRoot();
        assertTrue(root.findAll("org.t", "t").isEmpty());
        assertTrue(root.findAll("org.o", "o").isEmpty());
        assertTrue(root.findAll("org.p", "p").isEmpty());
    }

    @Test
    void managementOverridesTransitiveVersionAndScope() throws Exception {
        PomFile pom = project("<dependencyManagement><dependencies>" + dep("org.c", "c", "3.0", "")
                + dep("org.r", "r", null, "<scope>test</scope>") + "</dependencies></dependencyManagement>"
                + "<dependencies>" + dep("org.a", "a", "1.0", "") + dep("org.b", "b", "1.0", "") + "</dependencies>");
        DepNode root = resolve(pom).getRoot();
        DepNode c = root.findIncluded("org.c", "c");
        assertEquals("3.0", c.getVersion());
        assertEquals("1.0", c.getPremanagedVersion());
        assertTrue(root.findAll("org.d", "d").isEmpty(), "c:3.0 hat keine Dependencies");
        DepNode r = root.findIncluded("org.r", "r");
        assertEquals("test", r.getScope());
        assertEquals("runtime", r.getPremanagedScope());
    }

    /** Review #16: Management gilt nur für denselben Schlüssel g:a:type[:classifier] – wie bei Maven. */
    @Test
    void managementMatchesTypeAndClassifier() throws Exception {
        // nur test-jar verwaltet (Scope test): die jar-Deklaration bleibt compile
        PomFile pom = project("<dependencyManagement><dependencies>"
                + dep("org.c", "c", "1.0", "<type>test-jar</type><scope>test</scope>") + "</dependencies></dependencyManagement>"
                + "<dependencies>" + dep("org.c", "c", "1.0", "") + "</dependencies>");
        DepNode c = resolve(pom).getRoot().findIncluded("org.c", "c");
        assertEquals("compile", c.getScope());
        assertEquals("compile", resolve(pom).getRoot().findIncluded("org.d", "d").getScope());

        // jar (ohne Scope) und test-jar (test) verwaltet: das test-jar ohne eigenen Scope wird test
        pom = project("<dependencyManagement><dependencies>" + dep("org.c", "c", "1.0", "")
                + dep("org.c", "c", "1.0", "<type>test-jar</type><scope>test</scope>") + "</dependencies></dependencyManagement>"
                + "<dependencies>" + dep("org.c", "c", null, "<type>test-jar</type>") + "</dependencies>");
        DepNode testJar = resolve(pom).getRoot().findIncluded("org.c", "c");
        assertEquals("test-jar", testJar.getType());
        assertEquals("test", testJar.getScope());
    }

    @Test
    void scopesAreDerivedAndWidened() throws Exception {
        // x (test) bringt y → z; b (compile) bringt r (runtime)
        PomFile pom = project("<dependencies>" + dep("org.x", "x", "1.0", "<scope>test</scope>")
                + dep("org.b", "b", "1.0", "<scope>runtime</scope>") + "</dependencies>");
        DepNode root = resolve(pom).getRoot();
        assertEquals("test", root.findIncluded("org.y", "y").getScope());
        assertEquals("test", root.findIncluded("org.z", "z").getScope());
        assertEquals("runtime", root.findIncluded("org.c", "c").getScope());
        assertEquals("runtime", root.findIncluded("org.r", "r").getScope());

        // y kommt nah über einen Test-Pfad und tiefer über compile: der weitere Scope gewinnt
        artifact("org.w", "w", "1.0", dep("org.v", "v", "1.0", ""));
        artifact("org.v", "v", "1.0", dep("org.y", "y", "1.0", ""));
        PomFile widened = project("<dependencies>" + dep("org.x", "x", "1.0", "<scope>test</scope>")
                + dep("org.w", "w", "1.0", "") + "</dependencies>");
        DepNode y = resolve(widened).getRoot().findIncluded("org.y", "y");
        assertEquals("org.x:x:1.0 → org.y:y:1.0", y.pathText());
        assertEquals("compile", y.getScope());
        assertEquals("compile", resolve(widened).getRoot().findIncluded("org.z", "z").getScope());
    }

    @Test
    void exclusionsApplyToTheWholeSubtree() throws Exception {
        PomFile pom = project("<dependencies>" + dep("org.x", "x", "1.0",
                "<exclusions><exclusion><groupId>org.z</groupId><artifactId>z</artifactId></exclusion></exclusions>") + "</dependencies>");
        DepNode root = resolve(pom).getRoot();
        assertNotNull(root.findIncluded("org.y", "y"));
        assertNull(root.findIncluded("org.z", "z"));
    }

    @Test
    void missingPomsAreReported() throws Exception {
        PomFile pom = project("<dependencies>" + dep("org.gone", "gone", "9.9", "") + "</dependencies>");
        DependencyResolver.Tree t = resolve(pom);
        assertEquals(List.of("org.gone:gone:9.9"), t.getMissing());
        assertEquals("9.9", t.getRoot().findIncluded("org.gone", "gone").getVersion());
    }

    @Test
    void matchesTheTreeMavenPrints() throws Exception {
        PomFile pom = project("<dependencies>" + dep("org.a", "a", "1.0", "") + dep("org.b", "b", "1.0", "") + "</dependencies>");
        DepNode simulated = resolve(pom).getRoot();
        DepNode maven = DependencyTreeParser.parse(new java.io.StringReader("""
                [INFO] com.acme:app:jar:1.0
                [INFO] +- org.a:a:jar:1.0:compile
                [INFO] |  \\- org.c:c:jar:1.0:compile
                [INFO] |     \\- org.d:d:jar:1.0:compile
                [INFO] \\- org.b:b:jar:1.0:compile
                [INFO]    +- (org.c:c:jar:2.0:compile - omitted for conflict with 1.0)
                [INFO]    \\- org.r:r:jar:1.0:runtime
                """)).get(0);
        assertEquals(text(maven), text(simulated));
    }

    private static String text(DepNode n) {
        StringBuilder sb = new StringBuilder();
        append(sb, n, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, DepNode n, int depth) {
        sb.append("  ".repeat(depth)).append(n.gav()).append(' ').append(n.getScope()).append(' ').append(n.getState()).append('\n');
        for (DepNode c : n.getChildren()) {
            append(sb, c, depth + 1);
        }
    }
    /** Review #42: Exclusions aus dem Management der Dependency-POM gelten für ihre Dependencies ohne eigene. */
    @Test
    void exclusionsFromTheManagementOfTheDependencyPomApply() throws Exception {
        Path dir = repo.resolve("org/l/l/1.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("l-1.0.pom"), "<project><groupId>org.l</groupId><artifactId>l</artifactId><version>1.0</version>"
                + "<dependencyManagement><dependencies>" + dep("org.x", "x", "1.0", "<exclusions><exclusion><groupId>org.y</groupId>"
                + "<artifactId>y</artifactId></exclusion></exclusions>") + "</dependencies></dependencyManagement>"
                + "<dependencies>" + dep("org.x", "x", null, "") + "</dependencies></project>");
        DepNode root = resolve(project("<dependencies>" + dep("org.l", "l", "1.0", "") + "</dependencies>")).getRoot();
        assertNotNull(root.findIncluded("org.x", "x"));
        assertNull(root.findIncluded("org.y", "y"), root.toString());
        assertNull(root.findIncluded("org.z", "z"));
    }

    /** Review #64: ein Weg zurück zum Modul selbst ist ein Zyklus, kein Eintrag im eigenen Baum. */
    @Test
    void pathBackToTheModuleIsACycle() throws Exception {
        artifact("org.l", "l", "1.0", dep("com.acme", "app", "0.9", ""));
        DepNode root = resolve(project("<dependencies>" + dep("org.l", "l", "1.0", "") + "</dependencies>")).getRoot();
        assertNull(root.findIncluded("com.acme", "app"), root.toString());
        List<DepNode> all = root.findAll("com.acme", "app");
        assertEquals(1, all.size());
        assertEquals(DepNode.State.CYCLE, all.get(0).getState());
    }
    /** Review #68: -Dtokens=extended und transitive system-Dependencies. */
    @Test
    void extendedTreeTokensAndTransitiveSystemScope() throws Exception {
        List<DepNode> roots = DependencyTreeParser.parse(new java.io.StringReader(String.join("\n",
                "[INFO] com.acme:app:jar:1.0",
                "[INFO] \u251C\u2500 org.a:a:jar:1.0:compile",
                "[INFO] \u2502  \u2514\u2500 org.c:c:jar:1.0:compile",
                "[INFO] \u2514\u2500 org.b:b:jar:1.0:compile")));
        assertEquals(1, roots.size());
        assertEquals(2, roots.get(0).getChildren().size());
        assertNotNull(roots.get(0).findIncluded("org.c", "c"));

        artifact("org.s", "s", "1.0", dep("org.tools", "tools", "1.8", "<scope>system</scope><systemPath>/x/tools.jar</systemPath>"));
        artifact("org.tools", "tools", "1.8", "");
        DepNode root = resolve(project("<dependencies>" + dep("org.s", "s", "1.0", "") + "</dependencies>")).getRoot();
        assertNotNull(root.findIncluded("org.tools", "tools"), root.toString());
    }
}
