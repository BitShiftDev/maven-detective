package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüfungen gegen echte Dateien in temporären Verzeichnissen. */
class ChecksTest {

    private final BuildDoctor doctor = BuildDoctor.withDefaults();

    private Path home;
    private Path repo;
    private Path project;

    private Environment env(Map<String, String> vars, Boolean offline) throws IOException {
        home = Files.createTempDirectory("md-home");
        repo = home.resolve(".m2/repository");
        Files.createDirectories(repo);
        project = Files.createTempDirectory("md-project");
        return Environment.builder().userHome(home).projectDirectory(project).env(vars).offline(offline).build();
    }

    private Diagnosis diagnose(Environment env, String log) {
        return doctor.diagnose(MavenLogParser.parse(log), env).get(0);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void lastUpdatedFilesAreFoundAndOfferedForDeletion() throws Exception {
        Environment env = env(new HashMap<>(), null);
        Path marker = repo.resolve("org/webjars/bootstrap-icons/1.11.9/bootstrap-icons-1.11.9.jar.lastUpdated");
        write(marker, "#cached");
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal on project web: Could not resolve dependencies for project com.acme:web:jar:1.0: "
                + "The following artifacts could not be resolved: org.webjars:bootstrap-icons:jar:1.11.9 (absent): org.webjars:bootstrap-icons:jar:1.11.9 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. "
                + "This failure was cached in the local repository and resolution is not reattempted until the update interval of central has elapsed or updates are forced");
        assertEquals("artifact-not-resolvable", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("gecacht"), d.getCause());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.DELETE_FILES, fix.getType());
        assertEquals(marker.toString(), fix.param("files"));
        assertEquals("-U", fix.param("args"));
    }

    @Test
    void offlineModeWinsOverOtherCauses() throws Exception {
        Environment env = env(new HashMap<>(), Boolean.TRUE);
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal on project web: Could not resolve dependencies for project com.acme:web:jar:1.0: "
                + "The following artifacts could not be resolved: a:b:jar:1 (absent): Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact a:b:jar:1 has not been downloaded from it before.");
        assertTrue(d.getCause().startsWith("Maven läuft im Offline-Modus"), d.getCause());
        assertEquals("-o --offline", d.getPrimaryFix().param("removeArgs"));
    }

    @Test
    void reactorSiblingSuggestsAlsoMake() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></project>");
        write(project.resolve("core/pom.xml"), "<project><parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0-SNAPSHOT</version></parent><artifactId>core</artifactId></project>");
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal on project web: Could not resolve dependencies for project com.acme:web:jar:1.0-SNAPSHOT: "
                + "The following artifacts could not be resolved: com.acme:core:jar:1.0-SNAPSHOT (absent): Could not find artifact com.acme:core:jar:1.0-SNAPSHOT");
        assertTrue(d.getCause().contains("Modul dieses Projekts (core)"), d.getCause());
        assertEquals("-am", d.getPrimaryFix().param("args"));
    }

    @Test
    void reactorSiblingWithOtherPackagingNamesMissingType() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging></project>");
        write(project.resolve("web/pom.xml"), "<project><parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>\n"
                + "<artifactId>web</artifactId>\n<packaging>war</packaging>\n</project>");
        write(project.resolve("app/pom.xml"), "<project><parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>\n"
                + "<artifactId>app</artifactId>\n<packaging>ear</packaging>\n<dependencies>\n<dependency>\n<groupId>com.acme</groupId>\n"
                + "<artifactId>web</artifactId>\n<version>1.0</version>\n</dependency>\n</dependencies>\n</project>");
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal on project app: Could not resolve dependencies for project com.acme:app:ear:1.0\n"
                + "[ERROR] dependency: com.acme:web:jar:1.0 (compile)\n"
                + "[ERROR] \tCould not find artifact com.acme:web:jar:1.0 in central (https://repo.maven.apache.org/maven2)");
        assertEquals("artifact-not-resolvable", d.getRuleId());
        assertTrue(d.getCause().contains("packaging war") && d.getCause().contains("<type>war</type>"), d.getCause());
        assertEquals(FixAction.OPEN_FILE, d.getPrimaryFix().getType());
        assertEquals(project.resolve("app/pom.xml").toString(), d.getPrimaryFix().param("file"));
        assertEquals("5", d.getPrimaryFix().param("line"));
    }

    @Test
    void earModuleMissingFromDependenciesPointsToEarPom() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging></project>");
        write(project.resolve("web/pom.xml"), "<project><parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>"
                + "<artifactId>web</artifactId><packaging>war</packaging></project>");
        write(project.resolve("app/pom.xml"), "<project><parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>\n"
                + "<artifactId>app</artifactId>\n<packaging>ear</packaging>\n<build><plugins><plugin>\n<artifactId>maven-ear-plugin</artifactId>\n"
                + "<configuration><modules><webModule>\n<groupId>com.acme</groupId>\n<artifactId>web</artifactId>\n</webModule></modules></configuration>\n"
                + "</plugin></plugins></build>\n</project>");
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-ear-plugin:3.3.0:generate-application-xml "
                + "(default-generate-application-xml) on project app: Artifact[war:com.acme:web] is not a dependency of the project. -> [Help 1]");
        assertEquals("javaee-packaging", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("als war-Modul eingetragen"), d.getCause());
        boolean entry = false;
        for (Evidence e : d.getEvidence()) {
            entry |= e.getText().startsWith("Im maven-ear-plugin als war-Modul") && e.getLine() == 8;
        }
        assertTrue(entry, String.valueOf(d.getEvidence()));
    }

    @Test
    void jakartaImportWithJavaxApiIsNamespaceMismatch() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>web</artifactId><version>1.0</version>\n<dependencies><dependency>\n"
                + "<groupId>javax</groupId>\n<artifactId>javaee-api</artifactId>\n<version>8.0.1</version><scope>provided</scope></dependency></dependencies></project>");
        Diagnosis d = diagnose(env, "[ERROR] /src/main/java/com/acme/S.java:[3,28] package jakarta.servlet.http does not exist");
        assertEquals("compile-missing-dependency", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("alte Java-EE-API"), d.getCause());
        assertEquals("4", d.getPrimaryFix().param("line"));
    }

    @Test
    void javaxPackageRemovedFromJdkIsExplained() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>svc</artifactId><version>1.0</version></project>");
        Diagnosis d = diagnose(env, "[ERROR] /src/main/java/com/acme/S.java:[3,28] package javax.xml.bind.annotation does not exist");
        assertTrue(d.getCause().contains("mit Java 11 entfernt"), d.getCause());
    }

    @Test
    void serverIdMissingPointsToSettingsAndSimilarId() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(home.resolve(".m2/settings.xml"), "<settings>\n  <servers>\n    <server><id>Nexus</id><username>u</username></server>\n  </servers>\n</settings>\n");
        Diagnosis d = diagnose(env, "[ERROR] Could not transfer artifact a:b:pom:1 from/to nexus (https://nexus.acme.example/repo): status code: 401, reason phrase: Unauthorized (401)");
        assertEquals("repository-access-denied", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertTrue(d.getCause().contains("keinen <server> mit der ID 'nexus'"), d.getCause());
        assertTrue(d.getEvidence().stream().anyMatch(e -> e.getText().contains("Ähnliche ID gefunden: 'Nexus'")));
        assertEquals("2", d.getPrimaryFix().param("line"));
    }

    @Test
    void serverIdPresentMeansWrongCredentials() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(home.resolve(".m2/settings.xml"), "<settings><servers><server><id>nexus</id></server></servers></settings>");
        Diagnosis d = diagnose(env, "[ERROR] Could not transfer artifact a:b:pom:1 from/to nexus (https://nexus.acme.example/repo): status code: 401, reason phrase: Unauthorized (401)");
        assertEquals(Confidence.LIKELY, d.getConfidence());
        assertTrue(d.getCause().contains("lehnt sie aber ab"), d.getCause());
    }

    @Test
    void forbiddenOnCentralBlamesProxy() throws Exception {
        Environment env = env(new HashMap<>(), null);
        Diagnosis d = diagnose(env, "[ERROR] Could not transfer artifact a:b:pom:1 from/to central (https://repo.maven.apache.org/maven2): status code: 403, reason phrase: Forbidden (403)");
        assertTrue(d.getCause().startsWith("Maven Central antwortet mit 403"), d.getCause());
    }

    @Test
    void httpRepositoryInProjectPomGetsReplaceFix() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project>\n<repositories>\n<repository><id>old</id><url>http://repo.example.org/m2</url></repository>\n</repositories>\n</project>\n");
        Diagnosis d = diagnose(env, "[ERROR] Could not transfer artifact a:b:pom:1 from/to maven-default-http-blocker (http://0.0.0.0/): "
                + "Blocked mirror for repositories: [old (http://repo.example.org/m2, default, releases+snapshots)]");
        assertEquals("http-repository-blocked", d.getRuleId());
        FixAction fix = d.getPrimaryFix();
        assertEquals(FixAction.REPLACE_IN_FILE, fix.getType());
        assertEquals("http://repo.example.org/m2", fix.param("search"));
        assertEquals("https://repo.example.org/m2", fix.param("replace"));
    }

    @Test
    void corruptJarIsConfirmedByOpeningIt() throws Exception {
        Environment env = env(new HashMap<>(), null);
        Path jar = repo.resolve("com/x/lib/1.0/lib-1.0.jar");
        write(jar, "not a zip");
        Diagnosis d = diagnose(env, "[ERROR] error reading " + jar + "; zip END header not found");
        assertEquals("corrupt-jar", d.getRuleId());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
        assertEquals(jar.toString(), d.getPrimaryFix().param("files"));
    }

    @Test
    void missingChildModuleSuggestsSimilarDirectory() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project>\n  <modules>\n    <module>servcie</module>\n  </modules>\n</project>\n");
        Files.createDirectories(project.resolve("service"));
        Diagnosis d = diagnose(env, "[ERROR] Child module " + project.resolve("servcie") + " of " + project.resolve("pom.xml") + " does not exist");
        assertEquals("reactor-problem", d.getRuleId());
        assertTrue(d.getEvidence().stream().anyMatch(e -> e.getText().contains("'service'")), d.getEvidence().toString());
        assertEquals("3", d.getPrimaryFix().param("line"));
    }

    @Test
    void parentRelativePathPointingToWrongPom() throws Exception {
        Environment env = env(new HashMap<>(), null);
        write(project.resolve("pom.xml"), "<project><groupId>com.acme</groupId><artifactId>parent</artifactId><version>2.0</version><packaging>pom</packaging></project>");
        write(project.resolve("app/pom.xml"), "<project>\n<parent>\n<groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>\n</parent>\n<artifactId>app</artifactId></project>");
        Diagnosis d = diagnose(env, "[ERROR] The project com.acme:app:1.0 (" + project.resolve("app/pom.xml") + ") has 1 error\n"
                + "[ERROR] Non-resolvable parent POM for com.acme:app:1.0: The following artifacts could not be resolved: com.acme:parent:pom:1.0 (absent): Could not find artifact com.acme:parent:pom:1.0 and 'parent.relativePath' points at wrong local POM @ line 2, column 9");
        assertEquals("invalid-pom", d.getRuleId());
        assertTrue(d.getCause().contains("com.acme:parent:2.0"), d.getCause());
        assertEquals(Confidence.CERTAIN, d.getConfidence());
    }

    @Test
    void knownPluginPrefixNamesThePlugin() throws Exception {
        Environment env = env(new HashMap<>(), null);
        Diagnosis d = diagnose(env, "[ERROR] No plugin found for prefix 'spring-boot' in the current project and in the plugin groups [org.apache.maven.plugins, org.codehaus.mojo]");
        assertTrue(d.getCause().contains("org.springframework.boot:spring-boot-maven-plugin"), d.getCause());
        Diagnosis typo = diagnose(env, "[ERROR] No plugin found for prefix 'dependncy' in the current project");
        assertTrue(typo.getEvidence().stream().map(Evidence::getText).anyMatch(t -> t.contains("'dependency'")));
    }

    @Test
    void memoryForMavenJvmGoesToJvmConfig() throws Exception {
        Map<String, String> vars = new HashMap<>();
        vars.put("MAVEN_OPTS", "-Xmx768m");
        Environment env = env(vars, null);
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project m: Fatal error compiling: java.lang.OutOfMemoryError: Java heap space");
        assertEquals(FixAction.SET_JVM_OPTION, d.getPrimaryFix().getType());
        assertEquals("-Xmx1536m", d.getPrimaryFix().param("option"));
        assertEquals("-Xmx2g", "-Xmx" + RuntimeChecks.MemorySettings.doubled("1g"));
        assertEquals("-Xmx1g", "-Xmx" + RuntimeChecks.MemorySettings.doubled("512m"));
    }

    @Test
    void jdkMismatchUsesRunnerFromLogHeader() throws Exception {
        Environment env = Environment.builder().userHome(Files.createTempDirectory("h")).runnerJavaVersion("17.0.2").build();
        Diagnosis d = diagnose(env, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project g: Fatal error compiling: error: invalid target release: 21");
        assertEquals("Das Projekt verlangt Java 21, Maven läuft aber mit JDK 17.", d.getCause());
        assertEquals(FixAction.SWITCH_JDK, d.getPrimaryFix().getType());
        assertEquals("21", d.getPrimaryFix().param("required"));
    }

    @Test
    void coordinatesAndJavaVersions() {
        Coordinates c = Coordinates.parse("com.acme:lib:jar:tests:1.0,");
        assertEquals("tests", c.classifier);
        assertEquals("lib-1.0-tests.jar", c.fileName());
        assertEquals(null, Coordinates.parse("not coordinates"));
        assertEquals(8, Text.javaFeatureVersion("1.8.0_392"));
        assertEquals(25, Text.javaFeatureVersion("25-ea"));
        assertEquals(21, Text.javaForClassVersion(65));
    }
}
