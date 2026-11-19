package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.check.ClasspathChecksTestAccess;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Hit;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Target;
import io.github.bitshiftdev.mavendetective.core.deps.ClassOrigin.Where;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import io.github.bitshiftdev.mavendetective.core.testkit.TestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.bitshiftdev.mavendetective.core.deps.EffectiveModelDifferentialTest.write;
import static io.github.bitshiftdev.mavendetective.core.testkit.TestPom.exclusions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassOrigin} direkt, ohne den Umweg über die Prüfungen (Testplan Welle 3): jede {@link Where}-Variante,
 * Paket- und Mitgliedssuche, Repository-Suche mit Grenze.
 */
class ClassOriginTest {

    @TempDir
    Path tmp;
    TestRepository repo;
    Path root;

    @BeforeEach
    void setUp() {
        repo = TestRepository.at(tmp.resolve("repo"));
        root = tmp.resolve("project");
    }

    ClassOrigin find(TestPom module, Target target) {
        write(root.resolve("pom.xml"), module.xml());
        PomLocator locator = new PomLocator(root, repo.root());
        PomFile pom = locator.load(root.resolve("pom.xml"));
        return ClassOrigin.find(locator, pom, Map.of(), null, Set.of(pom.getFile()), target, new JarIndex(), null);
    }

    /** Bibliothek mit POM (Dependencies aus {@code pom}) und JAR mit diesen Klassen. */
    void lib(TestPom pom, String variant, String... classes) {
        repo.pom(pom);
        repo.jar(pom.groupId(), pom.artifactId(), pom.version(), null, variant, classes);
    }

    static List<String> gavs(List<Hit> hits) {
        List<String> out = new ArrayList<>();
        for (Hit h : hits) {
            out.add(h.gav());
        }
        return out;
    }

    @Test
    void classpathInMavensReihenfolgeMitPruefsumme() {
        lib(TestPom.of("org.a", "a", "1"), "v1", "com.x.A");
        lib(TestPom.of("org.b", "b", "1"), "v2", "com.x.A", "com.x.B");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1").dependency("org.b", "b", "1"),
                Target.type("com.x.A"));
        List<Hit> hits = o.hits(Where.CLASSPATH);
        assertEquals(List.of("org.a:a:1", "org.b:b:1"), gavs(hits));
        assertNotEquals(hits.get(0).getCrc(), hits.get(1).getCrc(), "andere Fassung");
        assertEquals("org.a:a:1", hits.get(0).getEntry().gav());
        assertEquals(2, o.getChecked());
        assertTrue(o.hits(Where.DISPLACED).isEmpty());
    }

    @Test
    void verdraengtDurchNearestWins() {
        lib(TestPom.of("org.x", "x", "1").dependency("org.c", "c", "2"), "");
        lib(TestPom.of("org.y", "y", "1").dependency("org.c", "c", "1"), "");
        lib(TestPom.of("org.c", "c", "1"), "", "com.c.Old");
        lib(TestPom.of("org.c", "c", "2"), "", "com.c.New");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.x", "x", "1").dependency("org.y", "y", "1"),
                Target.type("com.c.Old"));
        assertTrue(o.hits(Where.CLASSPATH).isEmpty());
        Hit h = o.hits(Where.DISPLACED).get(0);
        assertEquals("org.c:c:1", h.gav());
        assertEquals("2", h.getChosenVersion());
        assertEquals("org.y:y:1", h.getRequester());
        assertFalse(h.isManaged());
    }

    @Test
    void verdraengtDurchManagement() {
        lib(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"), "");
        lib(TestPom.of("org.c", "c", "1"), "", "com.c.Old");
        lib(TestPom.of("org.c", "c", "2"), "", "com.c.New");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").managed("org.c", "c", "2").dependency("org.a", "a", "1"),
                Target.type("com.c.Old"));
        Hit h = o.hits(Where.DISPLACED).get(0);
        assertEquals("org.c:c:1", h.gav());
        assertEquals("2", h.getChosenVersion());
        assertTrue(h.isManaged());
    }

    @Test
    void verdraengteFassungOhneJarIstKeinFund() {
        lib(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"), "");
        repo.pom("org.c", "c", "1");
        lib(TestPom.of("org.c", "c", "2"), "", "com.c.New");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").managed("org.c", "c", "2").dependency("org.a", "a", "1"),
                Target.type("com.c.Old"));
        assertTrue(o.getHits().isEmpty());
    }

    @Test
    void ausgeschlossenDurchExclusionImProjekt() {
        lib(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"), "", "com.a.A");
        lib(TestPom.of("org.c", "c", "1"), "", "com.c.C");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1", exclusions("org.c:c")),
                Target.type("com.c.C"));
        Hit h = o.hits(Where.EXCLUDED).get(0);
        assertEquals("org.c:c:1", h.gav());
        assertEquals("org.a:a", h.getExclusion().getDependency().ga());
    }

    @Test
    void klasseImClasspathSuchtKeineExclusion() {
        lib(TestPom.of("org.a", "a", "1").dependency("org.c", "c", "1"), "", "com.a.A");
        lib(TestPom.of("org.c", "c", "1"), "", "com.c.C");
        lib(TestPom.of("org.d", "d", "1"), "", "com.c.C");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1", exclusions("org.c:c"))
                .dependency("org.d", "d", "1"), Target.type("com.c.C"));
        assertEquals(List.of("org.d:d:1"), gavs(o.hits(Where.CLASSPATH)));
        assertTrue(o.hits(Where.EXCLUDED).isEmpty());
    }

    @Test
    void paketsucheUeberspringtEigeneKlassen() throws Exception {
        Files.createDirectories(root.resolve("target/classes/com/x"));
        Files.write(root.resolve("target/classes/com/x/Self.class"), new byte[] {1});
        lib(TestPom.of("org.a", "a", "1"), "", "com.x.A", "com.x.sub.Deep");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"), Target.pkg("com.x"));
        List<Hit> hits = o.hits(Where.CLASSPATH);
        assertEquals(List.of("org.a:a:1"), gavs(hits));
        assertEquals("com.x.A", hits.get(0).getClassName(), "Beispiel aus genau diesem Paket, nicht aus Unterpaketen");
    }

    @Test
    void typsucheFindetAuchEigeneKlassen() throws Exception {
        Files.createDirectories(root.resolve("target/classes/com/x"));
        Files.write(root.resolve("target/classes/com/x/A.class"), new byte[] {1});
        lib(TestPom.of("org.a", "a", "1"), "", "com.x.A");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"), Target.type("com.x.A"));
        List<Hit> hits = o.hits(Where.CLASSPATH);
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).isSelf());
    }

    @Test
    void innereKlasseWirdUeberIhreAeussereGefunden() {
        lib(TestPom.of("org.a", "a", "1"), "", "com.x.A");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"), Target.type("com.x.A$Inner"));
        assertEquals("com.x.A$Inner", o.hits(Where.CLASSPATH).get(0).getClassName());
    }

    @Test
    void fehlendesJarWirdGemeldetStattGeprueft() {
        repo.pom("org.a", "a", "1");
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.a", "a", "1"), Target.type("com.x.A"));
        assertEquals(1, o.getWithoutFile().size());
        assertEquals(0, o.getChecked());
    }

    @Test
    void mitgliedWirdJeFassungGeprueft() throws Exception {
        Map<String, byte[]> v1 = ClasspathChecksTestAccess.compile(tmp.resolve("c1"),
                "package com.m; public class M { public void alt() {} }");
        Map<String, byte[]> v2 = ClasspathChecksTestAccess.compile(tmp.resolve("c2"),
                "package com.m; public class M { public void neu(String s) {} }");
        repo.pom("org.m", "m", "1");
        repo.jar("org.m", "m", "1", null, v1);
        repo.pom("org.n", "n", "1");
        repo.jar("org.n", "n", "1", null, v2);
        ClassOrigin o = find(TestPom.of("org.app", "app", "1").dependency("org.m", "m", "1").dependency("org.n", "n", "1"),
                Target.member(ClassFile.method("'void com.m.M.neu(java.lang.String)'")));
        List<Hit> hits = o.hits(Where.CLASSPATH);
        assertEquals(Boolean.FALSE, hits.get(0).getHasMember());
        assertEquals(Boolean.TRUE, hits.get(1).getHasMember());
    }

    // ---- lokales Repository

    @Test
    void repositorySucheNimmtJeArtifactDieHoechsteVersion() {
        repo.jar("org.acme", "one", "1.0", null, "", "org.acme.util.U");
        repo.jar("org.acme", "one", "2.0", null, "", "org.acme.util.U");
        repo.jar("org.acme", "one", "2.0", "sources", "", "org.acme.util.U");
        repo.jar("org.acme", "one", "3.0", "linux", "", "org.acme.util.U");
        repo.jar("org.acme", "two", "1.0", null, "", "org.acme.util.U");
        repo.jar("org.acme", "three", "1.0", null, "", "org.acme.other.O");
        List<Hit> hits = ClassOrigin.searchRepository(repo.root(), Target.type("org.acme.util.U"), new JarIndex(), 600, null);
        List<String> found = gavs(hits);
        found.sort(String::compareTo);
        assertEquals(List.of("org.acme:one:2.0", "org.acme:two:1.0"), found, "Classifier-JARs zählen nicht");
        assertEquals(Where.REPOSITORY, hits.get(0).getWhere());
    }

    @Test
    void repositorySucheGibtUeberDerGrenzeAuf() {
        for (int i = 0; i < 5; i++) {
            repo.jar("org.acme", "lib" + i, "1.0", null, "", "org.acme.util.U");
        }
        assertEquals(5, ClassOrigin.searchRepository(repo.root(), Target.type("org.acme.util.U"), new JarIndex(), 5, null).size());
        assertTrue(ClassOrigin.searchRepository(repo.root(), Target.type("org.acme.util.U"), new JarIndex(), 4, null).isEmpty());
    }

    @Test
    void repositorySucheBrauchtZweiEbenen() {
        repo.jar("org", "flat", "1.0", null, "", "org.Flat");
        assertTrue(ClassOrigin.searchRepository(repo.root(), Target.type("org.Flat"), new JarIndex(), 600, null).isEmpty());
        assertTrue(ClassOrigin.searchRepository(null, Target.type("org.Flat"), new JarIndex(), 600, null).isEmpty());
        assertTrue(ClassOrigin.searchRepository(tmp.resolve("fehlt"), Target.type("org.Flat"), new JarIndex(), 600, null).isEmpty());
    }

    @Test
    void repositorySucheNimmtDasLaengsteVorhandeneVerzeichnis() {
        repo.jar("org.acme.util", "u", "1.0", null, "", "org.acme.util.deep.U");
        repo.jar("org.acme", "elsewhere", "1.0", null, "", "org.acme.util.deep.U");
        List<Hit> hits = ClassOrigin.searchRepository(repo.root(), Target.type("org.acme.util.deep.U"), new JarIndex(), 600, null);
        assertEquals(List.of("org.acme.util:u:1.0"), gavs(hits));
        assertNull(hits.get(0).getEntry());
    }
}
