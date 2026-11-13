package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regeln 9 und 10 mit Modul A: echte Klassen (mit {@code javax.tools} kompiliert) in JARs eines
 * temporären lokalen Repositorys.
 */
class ClasspathChecksTest {

    private static final String UTIL_V1 = "package org.x; public class Util { public static void old() {} }";
    private static final String UTIL_V2 = "package org.x; public class Util { public static void old() {} public static void newMethod() {} }";

    private final BuildDoctor doctor = BuildDoctor.withDefaults();

    @TempDir
    Path tmp;
    Path home;
    Path repo;
    Path project;
    Environment env;

    @BeforeEach
    void setUp() throws IOException {
        home = tmp.resolve("home");
        repo = home.resolve(".m2/repository");
        project = tmp.resolve("project");
        Files.createDirectories(repo);
        Files.createDirectories(project);
        env = Environment.builder().userHome(home).projectDirectory(project).env(new HashMap<>()).build();
    }

    // ------------------------------------------------------------------ Regel 10

    @Test
    void displacedVersionWithTheMethodIsRaised() throws Exception {
        // app → old-user → util 1.0 (Tiefe 2) gewinnt gegen app → new-user → mid → util 2.0 (Tiefe 3)
        artifact("org.x", "util", "1.0", "", UTIL_V1);
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        artifact("org.o", "old-user", "1.0", dep("org.x", "util", "1.0", ""), "package org.o; public class OldUser {}");
        artifact("org.n", "mid", "1.0", dep("org.x", "util", "2.0", ""), "package org.n; public class Mid {}");
        artifact("org.n", "new-user", "1.0", dep("org.n", "mid", "1.0", ""), "package org.n; public class NewUser { public void call() {} }");
        app(dep("org.o", "old-user", "1.0", "") + dep("org.n", "new-user", "1.0", ""));

        Diagnosis d = diagnose(testFailure("java.lang.NoSuchMethodError: 'void org.x.Util.newMethod()'"));
        assertEquals("runtime-class-conflict", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("util:1.0") && d.getCause().contains("Util.newMethod()")
                && d.getCause().contains("org.n:mid:1.0") && d.getCause().contains("nearest wins"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.PIN_VERSION, fix.getType(), d.getFixes().toString());
        assertEquals("org.x:util", fix.param("artifact"));
        assertEquals("2.0", fix.param("version"));
        assertEquals(project.resolve("pom.xml").toString(), fix.param("file"));
        assertTrue(hasEvidence(d, "Verdrängt: util:2.0") && hasEvidence(d, "hat Util.newMethod()"), d.getEvidence().toString());
        assertTrue(hasEvidence(d, "Aufrufer org.n.NewUser stammt aus new-user:1.0"), d.getEvidence().toString());
    }

    @Test
    void managedDowngradeIsRaisedInTheManagement() throws Exception {
        artifact("org.x", "util", "1.0", "", UTIL_V1);
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        artifact("org.n", "new-user", "1.0", dep("org.x", "util", "2.0", ""), "package org.n; public class NewUser {}");
        write(project.resolve("pom.xml"), pom("<dependencyManagement><dependencies>" + dep("org.x", "util", "1.0", "")
                + "</dependencies></dependencyManagement><dependencies>" + dep("org.n", "new-user", "1.0", "") + "</dependencies>"));

        Diagnosis d = diagnose(testFailure("java.lang.NoSuchMethodError: org.x.Util.newMethod()V"));
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("dependencyManagement setzt 1.0"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.SET_VERSION, fix.getType(), d.getFixes().toString());
        assertEquals("dependencyManagement", fix.param("section"));
        assertEquals("2.0", fix.param("version"));
    }

    @Test
    void duplicateClassWithoutTheMethodIsExcluded() throws Exception {
        // legacy (über wrapper) bringt eine alte Kopie von org.x.Util und steht zuerst im Classpath
        artifact("org.old", "legacy", "1.0", "", UTIL_V1, "package org.old; public class Legacy {}");
        artifact("org.w", "wrapper", "1.0", dep("org.old", "legacy", "1.0", ""), "package org.w; public class Wrapper {}");
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        app(dep("org.w", "wrapper", "1.0", "") + dep("org.x", "util", "2.0", ""));

        Diagnosis d = diagnose(testFailure("java.lang.NoSuchMethodError: 'void org.x.Util.newMethod()'"));
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("mehrfach") && d.getCause().contains("legacy:1.0") && d.getCause().contains("util:2.0"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.ADD_EXCLUSION, fix.getType(), d.getFixes().toString());
        assertEquals("org.w:wrapper", fix.param("artifact"));
        assertEquals("org.old:legacy", fix.param("exclusion"));
        assertTrue(hasEvidence(d, "Geladen: legacy:1.0") && hasEvidence(d, "ohne Util.newMethod()"), d.getEvidence().toString());
    }

    @Test
    void excludedArtifactExplainsClassNotFound() throws Exception {
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        artifact("org.w", "web", "1.0", dep("org.x", "util", "2.0", ""), "package org.w; public class Web {}");
        app(dep("org.w", "web", "1.0", "<exclusions><exclusion><groupId>org.x</groupId><artifactId>util</artifactId></exclusion></exclusions>"));

        Diagnosis d = diagnose(testFailure("java.lang.NoClassDefFoundError: org/x/Util"));
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("Exclusion org.x:util bei org.w:web"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.REMOVE_EXCLUSION, fix.getType());
        assertEquals("org.w:web", fix.param("artifact"));
        assertEquals("org.x:util", fix.param("exclusion"));
        assertEquals("dependencies", fix.param("section"));
    }

    @Test
    void failedStaticInitialiserIsNotBlamedOnTheClasspath() throws Exception {
        app("");
        Diagnosis d = diagnose(testFailure("java.lang.NoClassDefFoundError: Could not initialize class org.app.Config"));
        assertEquals("runtime-class-conflict", d.getRuleId());
        assertTrue(d.getCause().contains("statischer Initialisierer"), d.getCause());
        assertEquals(FixAction.MANUAL, d.getPrimaryFix().getType());
        assertTrue(d.getPrimaryFix().getLabel().contains("ExceptionInInitializerError"), d.getPrimaryFix().getLabel());
    }

    // ------------------------------------------------------------------ Regel 9

    @Test
    void testScopedDependencyIsMadeVisibleToMainSources() throws Exception {
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        app(dep("org.x", "util", "2.0", "<scope>test</scope>"));

        Diagnosis d = diagnose(compileFailure("package org.x does not exist"));
        assertEquals("compile-missing-dependency", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("Scope test") && d.getCause().contains("util:2.0"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.SET_SCOPE, fix.getType());
        assertEquals("compile", fix.param("scope"));
        assertEquals("org.x:util", fix.param("artifact"));
    }

    @Test
    void missingPackageIsFoundInTheLocalRepository() throws Exception {
        artifact("org.apache.commons", "commons-lang3", "3.12.0", "", "package org.apache.commons.lang3; public class StringUtils {}");
        artifact("org.apache.commons", "commons-lang3", "3.14.0", "", "package org.apache.commons.lang3; public class StringUtils {}");
        artifact("org.apache.commons", "commons-text", "1.10.0", "", "package org.apache.commons.text; public class StringSubstitutor {}");
        app("");

        Diagnosis d = diagnose(compileFailure("package org.apache.commons.lang3 does not exist"));
        assertTrue(hasEvidence(d, "liegt org.apache.commons.lang3 in commons-lang3:3.14.0"), d.getEvidence().toString());
        FixAction add = null;
        for (FixAction f : d.getFixes()) {
            if (FixAction.ADD_DEPENDENCY.equals(f.getType())) {
                add = f;
            }
        }
        assertNotNull(add, d.getFixes().toString());
        assertEquals(add, d.getPrimaryFix());
        assertTrue(d.getCause().contains("vermutlich fehlt die Dependency"), d.getCause());
        assertEquals("org.apache.commons:commons-lang3", add.param("artifact"));
        assertEquals("3.14.0", add.param("version"));
        assertEquals(null, add.param("scope"));
    }

    @Test
    void excludedArtifactExplainsMissingPackage() throws Exception {
        artifact("org.x", "util", "2.0", "", UTIL_V2);
        artifact("org.w", "web", "1.0", dep("org.x", "util", "2.0", ""), "package org.w; public class Web {}");
        app(dep("org.w", "web", "1.0", "<exclusions><exclusion><groupId>org.x</groupId><artifactId>util</artifactId></exclusion></exclusions>"));

        Diagnosis d = diagnose(compileFailure("package org.x does not exist"));
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertEquals(FixAction.REMOVE_EXCLUSION, d.getPrimaryFix().getType());
    }

    // ------------------------------------------------------------------ Hilfen

    private Diagnosis diagnose(String log) {
        return doctor.diagnose(MavenLogParser.parse(log), env).get(0);
    }

    private static boolean hasEvidence(Diagnosis d, String text) {
        for (Evidence e : d.getEvidence()) {
            if (e.getText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String testFailure(String error) {
        return "[INFO] Running org.app.AppTest\n"
                + "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in org.app.AppTest\n"
                + "[ERROR] org.app.AppTest.works -- Time elapsed: 0.1 s <<< ERROR!\n"
                + error + "\n"
                + "\tat org.n.NewUser.call(NewUser.java:5)\n"
                + "\tat org.app.AppTest.works(AppTest.java:9)\n"
                + "[INFO] BUILD FAILURE\n"
                + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project app: There are test failures.\n";
    }

    private String compileFailure(String error) {
        String file = project.resolve("src/main/java/org/app/App.java").toString();
        return "[ERROR] COMPILATION ERROR : \n"
                + "[ERROR] " + file + ":[3,14] " + error + "\n"
                + "[INFO] BUILD FAILURE\n"
                + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project app: Compilation failure\n"
                + "[ERROR] " + file + ":[3,14] " + error + "\n";
    }

    private void app(String dependencies) throws IOException {
        write(project.resolve("pom.xml"), pom(dependencies.isEmpty() ? "" : "<dependencies>" + dependencies + "</dependencies>"));
    }

    private static String pom(String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>org.app</groupId><artifactId>app</artifactId><version>1.0</version>"
                + body + "</project>";
    }

    static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** POM + JAR mit den kompilierten Quellen im lokalen Repository. */
    private void artifact(String g, String a, String v, String deps, String... sources) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        dir = dir.resolve(a).resolve(v);
        write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>" + (deps.isEmpty() ? "" : "<dependencies>" + deps + "</dependencies>")
                + "</project>");
        Map<String, byte[]> classes = compile(tmp.resolve("work").resolve(g + "-" + a + "-" + v), sources);
        try (OutputStream os = Files.newOutputStream(dir.resolve(a + "-" + v + ".jar")); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
            }
        }
    }

    /** Kompiliert Quelltexte (Klassenname aus {@code package} + {@code class}); Ergebnis: Pfad im JAR → Bytes. */
    static Map<String, byte[]> compile(Path work, String... sources) throws IOException {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "8", "-nowarn", "-Xlint:-options"));
        for (String s : sources) {
            String pkg = s.replaceAll("(?s)^\\s*package ([\\w.]+);.*", "$1");
            String cls = s.replaceAll("(?s).*?public class (\\w+).*", "$1");
            Path file = src.resolve(pkg.replace('.', '/')).resolve(cls + ".java");
            write(file, s);
            args.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, args.toArray(new String[0]));
        assertEquals(0, rc, "javac");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                classes.put(out.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return classes;
    }
}
