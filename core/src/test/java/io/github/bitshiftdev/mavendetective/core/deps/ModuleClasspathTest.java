package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.core.deps.EffectiveModelDifferentialTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Classpath eines Moduls (Testplan Welle 3): Repository-Layout, Reihenfolge, eigene Klassen, Reactor. */
class ModuleClasspathTest {

    @TempDir
    Path tmp;
    TestRepository repo;
    Path root;

    @BeforeEach
    void setUp() {
        repo = TestRepository.at(tmp.resolve("repo"));
        root = tmp.resolve("project");
    }

    @ParameterizedTest(name = "{0}:{1}:{2} type={3} classifier={4} → {5}")
    @CsvSource(nullValues = "-", value = {
            "org.a, a, 1.0, jar, -, org/a/a/1.0/a-1.0.jar",
            "org.a, a, 1.0, test-jar, -, org/a/a/1.0/a-1.0-tests.jar",
            "org.a, a, 1.0, ejb-client, -, org/a/a/1.0/a-1.0-client.jar",
            "org.a, a, 1.0, jar, linux, org/a/a/1.0/a-1.0-linux.jar",
            "org.a, a, 1.0, test-jar, it, org/a/a/1.0/a-1.0-it.jar",
            "org.a, a, 1.0, maven-plugin, -, org/a/a/1.0/a-1.0.jar",
            "org.a, a, 1.0, ejb, -, org/a/a/1.0/a-1.0.jar",
            "org.a, a, 1.0, bundle, -, org/a/a/1.0/a-1.0.jar",
            "org.a, a, 1.0, aar, -, org/a/a/1.0/a-1.0.aar",
            "org.a.b.c, a, 1.0-SNAPSHOT, jar, -, org/a/b/c/a/1.0-SNAPSHOT/a-1.0-SNAPSHOT.jar",
            "org.a, a, 1.0-20240101.123456-7, jar, -, org/a/a/1.0-SNAPSHOT/a-1.0-20240101.123456-7.jar",
    })
    void repositoryLayoutWieMaven(String g, String a, String v, String type, String classifier, String expected) {
        Path repoRoot = tmp.resolve("r");
        assertEquals(repoRoot.resolve(expected), ModuleClasspath.artifactFile(repoRoot, g, a, v, type, classifier));
    }

    ModuleClasspath classpath(TestPom module, Map<String, PomFile> reactor) {
        write(root.resolve("pom.xml"), module.xml());
        PomLocator locator = new PomLocator(root, repo.root());
        return ModuleClasspath.of(locator, locator.load(root.resolve("pom.xml")), null, reactor);
    }

    static List<String> gavs(ModuleClasspath cp) {
        List<String> out = new ArrayList<>();
        for (ModuleClasspath.Entry e : cp.getEntries()) {
            out.add(e.gav() + (e.isSelf() ? " (self)" : ""));
        }
        return out;
    }

    @Test
    void reihenfolgeIstVorordnungDesBaums() {
        repo.pom(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"));
        repo.pom(TestPom.of("org.b", "b", "1").dependency("org.d", "d", "1"));
        repo.pom("org.c", "c", "1");
        repo.pom("org.d", "d", "1");
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1")
                .dependency("org.b", "b", "1"), Map.of());
        assertEquals(List.of("org.a:a:1", "org.c:c:1", "org.b:b:1", "org.d:d:1"), gavs(cp));
        assertFalse(cp.isFromTree());
    }

    @Test
    void gleichstandGewinntDieErsteDeklaration() {
        repo.pom(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"));
        repo.pom(TestPom.of("org.b", "b", "1").dependency("org.c", "c", "2"));
        repo.pom("org.c", "c", "1");
        repo.pom("org.c", "c", "2");
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.b", "b", "1")
                .dependency("org.a", "a", "1"), Map.of());
        assertEquals("2", cp.find("org.c", "c").getVersion());
        assertEquals(List.of("org.b:b", "org.a:a"), cp.directDependenciesBringing("org.c:c"), "Verlierer zählen mit");
    }

    @Test
    void eigeneKlassenStehenVorn() throws Exception {
        Files.createDirectories(root.resolve("target/classes"));
        repo.pom("org.a", "a", "1");
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"), Map.of());
        assertEquals(List.of("org.app:app:1 (self)", "org.a:a:1"), gavs(cp));
        assertTrue(cp.getEntries().get(0).isSelf());
    }

    @Test
    void typenOhneKlassenFehlen() {
        repo.pom(TestPom.of("org.g", "group", "1").packaging("pom").dependency("org.x", "x", "1"));
        repo.pom("org.x", "x", "1");
        repo.pom("org.w", "w", "1");
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1")
                .dependency("org.g", "group", "1", "<type>pom</type>")
                .dependency("org.w", "w", "1", "<type>war</type>"), Map.of());
        assertEquals(List.of("org.x:x:1"), gavs(cp), "pom- und war-Dependencies bringen keine Klassen, ihre Kinder schon");
    }

    @Test
    void jarDateiUndZeitgestempelterSnapshot() {
        repo.pom("org.a", "a", "1");
        Path jar = repo.jar("org.a", "a", "1", null, "", "org.a.A");
        repo.pom("org.s", "s", "1.0-20240101.123456-7");
        Path snapshot = repo.jar("org.s", "s", "1.0-SNAPSHOT", null, "", "org.s.S");
        repo.pom("org.m", "m", "1");
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1")
                .dependency("org.s", "s", "1.0-20240101.123456-7").dependency("org.m", "m", "1"), Map.of());
        assertEquals(jar, cp.find("org.a", "a").getFile());
        assertEquals(snapshot, cp.find("org.s", "s").getFile(), "nur a-1.0-SNAPSHOT.jar liegt da");
        assertNull(cp.find("org.m", "m").getFile(), "JAR fehlt");
        assertEquals(repo.dir("org.m", "m", "1").resolve("m-1.jar").toString(), cp.find("org.m", "m").getExpected());
    }

    @Test
    void reactorModulNutztTargetClasses() throws Exception {
        Path lib = tmp.resolve("lib");
        write(lib.resolve("pom.xml"), TestPom.of("org.app", "lib", "1").xml());
        Files.createDirectories(lib.resolve("target/classes"));
        Files.createDirectories(lib.resolve("target/test-classes"));
        PomFile libPom = PomFile.read(lib.resolve("pom.xml"));
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.app", "lib", "1"),
                Map.of("org.app:lib", libPom));
        ModuleClasspath.Entry e = cp.find("org.app", "lib");
        assertTrue(e.isReactor());
        assertEquals(lib.resolve("target/classes"), e.getFile());
    }

    @Test
    void testJarEinesReactorModulsNutztTargetTestClasses() throws Exception {
        Path lib = tmp.resolve("lib");
        write(lib.resolve("pom.xml"), TestPom.of("org.app", "lib", "1").xml());
        Files.createDirectories(lib.resolve("target/classes"));
        Files.createDirectories(lib.resolve("target/test-classes"));
        PomFile libPom = PomFile.read(lib.resolve("pom.xml"));
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1").dependency("org.app", "lib", "1")
                .dependency("org.app", "lib", "1", "<type>test-jar</type>", "<scope>test</scope>"), Map.of("org.app:lib", libPom));
        List<Path> files = new ArrayList<>();
        for (ModuleClasspath.Entry e : cp.getEntries()) {
            files.add(e.getFile());
        }
        // wie Mavens ReactorReader: test-jar → target/test-classes
        assertEquals(List.of(lib.resolve("target/classes"), lib.resolve("target/test-classes")), files);
    }

    @Test
    void gepacktesTestJarEinesReactorModuls() throws Exception {
        Path lib = tmp.resolve("lib");
        write(lib.resolve("pom.xml"), TestPom.of("org.app", "lib", "1").xml());
        Path jar = TestRepository.zip(lib.resolve("target/lib-1-tests.jar"), Map.of("org/app/T.class", new byte[] {1}));
        PomFile libPom = PomFile.read(lib.resolve("pom.xml"));
        ModuleClasspath cp = classpath(TestPom.of("org.app", "app", "1")
                .dependency("org.app", "lib", "1", "<type>test-jar</type>", "<scope>test</scope>"), Map.of("org.app:lib", libPom));
        assertEquals(jar, cp.getEntries().get(0).getFile());
    }
}
