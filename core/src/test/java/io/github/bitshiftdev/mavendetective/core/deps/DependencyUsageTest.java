package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage.Finding;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage.Kind;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lokales Repository mit winzigen JARs:
 * <ul>
 *   <li>web (direkt) bringt slf4j-api mit; App nennt org.slf4j.* → undeklariert (verwaltet: ohne Version)</li>
 *   <li>starter (direkt, ohne Klassen) bringt boot-core; App nennt org.boot.Boot → Starter genutzt, boot-core über Starter</li>
 *   <li>lib (direkt) wird nicht genannt → ungenutzt, sicher</li>
 *   <li>plugin (direkt) wird nicht genannt, hat META-INF/services → ungenutzt, unsicher</li>
 *   <li>testlib (compile) nur im Test genannt → nur in Tests</li>
 *   <li>h2 nicht genannt → bekannte Laufzeit-Bibliothek, kein Befund</li>
 *   <li>rt (runtime) nicht genannt → kein Befund</li>
 *   <li>Modul core (Reactor, nicht gebaut) wird von app genannt → genutzt über die Quelldateien</li>
 * </ul>
 */
class DependencyUsageTest {

    @TempDir
    Path tmp;
    Path project;
    Path repo;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** POM + JAR; Einträge mit „META-INF/“ werden als Datei abgelegt, sonst als Klasse. */
    void artifact(String g, String a, String v, String deps, String... entries) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        dir = dir.resolve(a).resolve(v);
        write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>" + (deps.isEmpty() ? "" : "<dependencies>" + deps + "</dependencies>")
                + "</project>");
        Files.createDirectories(dir);
        try (OutputStream os = Files.newOutputStream(dir.resolve(a + "-" + v + ".jar")); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e.startsWith("META-INF/") ? e : e.replace('.', '/') + ".class"));
                zip.write(e.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        repo = tmp.resolve("repo");
        artifact("org.web", "web", "1.0", dep("org.slf4j", "slf4j-api", "2.0", ""), "org.web.Web");
        artifact("org.slf4j", "slf4j-api", "2.0", "", "org.slf4j.Logger", "org.slf4j.LoggerFactory");
        artifact("org.boot", "starter", "1.0", dep("org.boot", "boot-core", "1.0", ""));
        artifact("org.boot", "boot-core", "1.0", "", "org.boot.Boot");
        artifact("org.unused", "lib", "1.0", "", "org.unused.Lib");
        artifact("org.svc", "plugin", "1.0", "", "org.svc.Impl", "META-INF/services/org.api.Spi");
        artifact("org.tl", "testlib", "1.0", "", "org.tl.Assert");
        artifact("com.h2database", "h2", "2.0", "", "org.h2.Driver");
        artifact("org.rt", "rt", "1.0", "", "org.rt.Rt");

        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>app</module></modules>
                  <dependencyManagement>
                    <dependencies>
                      %s
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(dep("org.slf4j", "slf4j-api", "2.0", "")));
        write(project.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        write(project.resolve("core/src/main/java/com/acme/core/Service.java"), "package com.acme.core;\npublic class Service {}\n");
        write(project.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                    %s
                  </dependencies>
                </project>
                """.formatted(
                dep("com.acme", "core", "1.0", ""),
                dep("org.web", "web", "1.0", ""),
                dep("org.boot", "starter", "1.0", ""),
                dep("org.unused", "lib", "1.0", ""),
                dep("org.svc", "plugin", "1.0", ""),
                dep("org.tl", "testlib", "1.0", ""),
                dep("com.h2database", "h2", "2.0", ""),
                dep("org.rt", "rt", "1.0", "<scope>runtime</scope>")));
        write(project.resolve("app/src/main/java/com/acme/App.java"), """
                package com.acme;

                import org.web.Web;
                import org.slf4j.*;
                import org.boot.Boot;
                import com.acme.core.Service;
                // import org.unused.Lib;

                public class App {
                    Logger log = LoggerFactory.getLogger(App.class);
                    String s = "org.unused.Lib";
                }
                """);
        write(project.resolve("app/src/test/java/com/acme/AppTest.java"), """
                package com.acme;

                import org.tl.Assert;

                class AppTest {
                    org.web.Web web;
                }
                """);
    }

    private DependencyUsage run() {
        PomLocator locator = new PomLocator(project, repo);
        List<PomFile> poms = new ArrayList<>();
        for (String p : List.of("pom.xml", "core/pom.xml", "app/pom.xml")) {
            poms.add(locator.load(project.resolve(p)));
        }
        return DependencyUsage.build(locator, poms, Map.of(), new JarIndex(), DependencyUsage.sourceScanner(null), null);
    }

    private static DependencyUsage.ModuleResult module(DependencyUsage r, String name) {
        for (DependencyUsage.ModuleResult m : r.getModules()) {
            if (m.getClasspath().getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("Modul fehlt: " + name);
    }

    private static Finding find(DependencyUsage.ModuleResult m, String ga) {
        for (Finding f : m.getFindings()) {
            if (f.ga().equals(ga)) {
                return f;
            }
        }
        return null;
    }

    @Test
    void classifiesDeclaredAndUndeclared() {
        DependencyUsage r = run();
        assertEquals(2, r.getModules().size(), "Parent mit packaging pom zählt nicht");
        DependencyUsage.ModuleResult app = module(r, "app");
        assertEquals(1, app.getUsages().getMainFiles());
        assertEquals(1, app.getUsages().getTestFiles());

        Finding slf4j = find(app, "org.slf4j:slf4j-api");
        assertNotNull(slf4j, app.getFindings().toString());
        assertEquals(Kind.UNDECLARED, slf4j.getKind());
        assertEquals(2, slf4j.getMainCount(), "Logger und LoggerFactory über den Wildcard-Import");
        assertEquals("org.web:web", slf4j.getVia().ga());
        assertFalse(slf4j.isViaBundle());
        assertEquals("compile", slf4j.suggestedScope());
        FixAction add = slf4j.fixes().get(0);
        assertEquals(FixAction.ADD_DEPENDENCY, add.getType());
        assertNull(add.param("version"), "verwaltet im Parent → ohne Version");
        assertNull(add.param("scope"));
        assertEquals(project.resolve("app/pom.xml").toString(), add.param("file"));

        Finding boot = find(app, "org.boot:boot-core");
        assertNotNull(boot);
        assertEquals(Kind.UNDECLARED, boot.getKind());
        assertTrue(boot.isViaBundle());
        assertEquals("1.0", boot.fixes().get(0).param("version"));
        assertNull(find(app, "org.boot:starter"), "Starter gilt als genutzt, weil boot-core genannt wird");

        Finding lib = find(app, "org.unused:lib");
        assertEquals(Kind.UNUSED, lib.getKind());
        assertTrue(lib.isCertain(), "Kommentar und Zeichenkette zählen nicht");
        assertEquals(FixAction.REMOVE_DEPENDENCY, lib.fixes().get(0).getType());

        Finding plugin = find(app, "org.svc:plugin");
        assertEquals(Kind.UNUSED, plugin.getKind());
        assertFalse(plugin.isCertain());
        assertTrue(plugin.getNote().contains("META-INF/services/org.api.Spi"), plugin.getNote());

        Finding testlib = find(app, "org.tl:testlib");
        assertEquals(Kind.TEST_ONLY, testlib.getKind());
        assertEquals("org.tl.Assert", testlib.getTestUses().get(0).getClassName());
        assertEquals(3, testlib.getTestUses().get(0).getLine());
        FixAction scope = testlib.fixes().get(0);
        assertEquals(FixAction.SET_SCOPE, scope.getType());
        assertEquals("test", scope.param("scope"));

        assertNull(find(app, "org.web:web"), "web wird genannt");
        assertNull(find(app, "com.acme:core"), "Reactor-Modul aus den Quellen erschlossen");
        assertNull(find(app, "com.h2database:h2"));
        assertNull(find(app, "org.rt:rt"));
        assertTrue(app.getNotes().stream().anyMatch(n -> n.contains("h2") && n.contains("JDBC")), app.getNotes().toString());

        assertEquals(List.of(Kind.UNDECLARED, Kind.UNDECLARED, Kind.TEST_ONLY, Kind.UNUSED, Kind.UNUSED),
                app.getFindings().stream().map(Finding::getKind).toList());
        assertEquals("org.slf4j:slf4j-api", app.getFindings().get(0).ga(), "über Starter zuletzt");
        assertTrue(app.getFindings().get(3).isCertain(), "sichere Ungenutzte vor unsicheren");

        DependencyUsage.ModuleResult core = module(r, "core");
        assertTrue(core.getFindings().isEmpty());
    }

    @Test
    void fixesResolveTheFindings() throws IOException {
        DependencyUsage r = run();
        FixExecutor executor = new FixExecutor(List.of());
        for (Finding f : module(r, "app").getFindings()) {
            if (f.isCertain() && !f.isViaBundle()) {
                executor.execute(f.fixes().get(0));
            }
        }
        String pom = Files.readString(project.resolve("app/pom.xml"));
        assertTrue(pom.contains("<artifactId>slf4j-api</artifactId>"), pom);
        assertFalse(pom.contains("org.unused"), pom);
        assertTrue(pom.contains("<artifactId>testlib</artifactId><version>1.0</version><scope>test</scope>"), pom);

        DependencyUsage again = run();
        DependencyUsage.ModuleResult app = module(again, "app");
        assertNull(find(app, "org.slf4j:slf4j-api"));
        assertNull(find(app, "org.unused:lib"));
        assertNull(find(app, "org.tl:testlib"));
        assertNotNull(find(app, "org.svc:plugin"));
    }

    @Test
    void outputMentionsFindingsAndHidesBundlesByDefault() {
        DependencyUsage r = run();
        String text = String.join("\n", r.lines(false));
        assertTrue(text.contains("Dependency-Nutzung: 2 undeklariert, 2 ungenutzt, 1 nur in Tests"), text);
        assertTrue(text.contains("slf4j-api:2.0 ist nicht deklariert"), text);
        assertFalse(text.contains("boot-core:1.0 ist nicht deklariert"), text);
        assertTrue(text.contains("undeklarierte über Starter"), text);
        assertTrue(String.join("\n", r.lines(true)).contains("boot-core:1.0 ist nicht deklariert"));
        String md = r.markdown(false);
        assertTrue(md.contains("| `app` | undeklariert | `slf4j-api:2.0` | `org.slf4j.Logger` (+1) |"), md);
        assertTrue(md.contains("ungenutzt (unsicher)"), md);
    }

    @Test
    void singleUseForTheEditorInspection() {
        PomLocator locator = new PomLocator(project, repo);
        PomFile app = locator.load(project.resolve("app/pom.xml"));
        ModuleClasspath cp = ModuleClasspath.of(locator, app, null, Map.of());
        ModuleClasspath.Entry slf4j = cp.find(repo.resolve("org/slf4j/slf4j-api/2.0/slf4j-api-2.0.jar"));
        assertNotNull(slf4j);
        Path file = project.resolve("app/src/test/java/X.java");
        Finding f = DependencyUsage.undeclared(cp, slf4j, "org.slf4j.Logger", true, file, 4, new JarIndex());
        assertNotNull(f);
        assertEquals("test", f.fixes().get(0).param("scope"));
        assertTrue(f.message().startsWith("slf4j-api:2.0 ist nicht deklariert, wird aber genannt: org.slf4j.Logger (nur in Tests)"),
                f.message());
        assertTrue(DependencyUsage.undeclared(cp, cp.find("org.boot", "boot-core"), "org.boot.Boot", false, file, 1, new JarIndex())
                .isViaBundle());
        assertNull(DependencyUsage.undeclared(cp, cp.find("org.web", "web"), "org.web.Web", false, file, 1, new JarIndex()),
                "direkt deklariert");
    }

    @Test
    void moduleWithoutSourcesReportsNoUnused() throws IOException {
        Files.delete(project.resolve("app/src/main/java/com/acme/App.java"));
        Files.delete(project.resolve("app/src/test/java/com/acme/AppTest.java"));
        DependencyUsage.ModuleResult app = module(run(), "app");
        assertTrue(app.getFindings().isEmpty(), app.getFindings().toString());
        assertTrue(app.getNotes().get(0).contains("Keine Quelldateien"));
    }
}
