package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.event;
import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.mojo;
import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.project;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotsAndTestFailuresTest {

    @TempDir
    Path tmp;

    private static Artifact artifact(String g, String a, String v, String scope) {
        return new DefaultArtifact(g, a, v, scope, "jar", null, new DefaultArtifactHandler("jar"));
    }

    private MavenProject app(String libVersion) {
        MavenProject p = project(tmp.resolve("app"), "app");
        Parent parent = new Parent();
        parent.setGroupId("org.acme");
        parent.setArtifactId("parent");
        parent.setVersion("7");
        p.getModel().setParent(parent);
        p.getProperties().setProperty("lib.version", libVersion);
        p.getProperties().setProperty("project.build.sourceEncoding", "UTF-8");
        Set<Artifact> artifacts = new LinkedHashSet<>();
        artifacts.add(artifact("org.lib", "lib", libVersion, "compile"));
        p.setArtifacts(artifacts);
        return p;
    }

    private static DefaultMavenExecutionResult result(MavenProject p, boolean red) {
        DefaultMavenExecutionResult r = new DefaultMavenExecutionResult();
        r.setTopologicallySortedProjects(List.of(p));
        if (red) {
            r.addException(new MojoExecutionException("Compilation failure"));
        }
        return r;
    }

    // --- Snapshots -------------------------------------------------------------------------------

    @Test
    void buildsSnapshotFromTheProjectModel() {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setActiveProfiles(List.of("ci"));
        request.setInactiveProfiles(List.of("slow"));
        request.setOffline(true);
        MavenProject p = app("1.0");
        // Mojo „test“ bekam mehr Artifacts als am Ende in getArtifacts() stehen
        Set<Artifact> wider = new LinkedHashSet<>(p.getArtifacts());
        wider.add(artifact("org.junit", "junit", "5", "test"));

        BuildSnapshot s = Snapshots.build(request, result(p, false), "3.9.11", tmp.toString(),
                Map.of("org.acme:app", Set.of("compile", "test")), Map.of("org.acme:app", wider));

        assertEquals(BuildSnapshot.ORIGIN_EXTENSION, s.getOrigin());
        assertEquals("3.9.11", s.getMavenVersion());
        assertEquals(List.of("ci", "!slow"), s.getActiveProfiles());
        BuildSnapshot.Module m = s.module("org.acme:app");
        assertEquals("org.acme:parent:7", m.getParent());
        assertEquals("1.0", m.getVersionProperties().get("lib.version"));
        assertFalse(m.getVersionProperties().containsKey("project.build.sourceEncoding"));
        assertEquals("1.0", m.getDependencies().get("org.lib:lib"));
        assertEquals("5", m.getDependencies().get("org.junit:junit"));
        assertEquals("test", m.getDependencyScopes().get("org.junit:junit"));
        assertTrue(m.isDependenciesResolved());
        assertTrue(m.getResolvedScopes().contains("test"));
        assertNotNull(m.getEffectivePomHash());
    }

    @Test
    void withoutResolutionFallsBackToDeclaredDependencies() {
        MavenProject p = project(tmp.resolve("app"), "app");
        Dependency d = new Dependency();
        d.setGroupId("org.lib");
        d.setArtifactId("lib");
        d.setVersion("2.0");
        d.setClassifier("tests");
        p.getModel().addDependency(d);
        BuildSnapshot s = Snapshots.build(null, result(p, false), "3.9.11", tmp.toString(), null, null);
        BuildSnapshot.Module m = s.module("org.acme:app");
        assertFalse(m.isDependenciesResolved());
        assertEquals("2.0", m.getDependencies().get("org.lib:lib:tests"));
    }

    @Test
    void greenRunBecomesReferenceRedRunShowsWhatChanged() {
        SnapshotStore store = new SnapshotStore(tmp.resolve("home/snapshots"));
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();

        assertNull(Snapshots.record(store, request, result(app("1.0"), false), "3.9.11", tmp.toString(), null, null,
                NOPLogger.NOP_LOGGER), "grüner Lauf: kein Vergleich");
        assertTrue(Files.isRegularFile(store.greenFile(tmp, BuildSnapshot.ORIGIN_EXTENSION)));

        SnapshotDiff diff = Snapshots.record(store, request, result(app("2.0"), true), "3.9.11", tmp.toString(), null, null,
                NOPLogger.NOP_LOGGER);
        assertNotNull(diff);
        assertFalse(diff.isEmpty());
        String text = String.join("\n", diff.lines(20));
        assertTrue(text.contains("1.0") && text.contains("2.0"), text);
        // Referenz bleibt der grüne Lauf
        assertEquals(Boolean.TRUE, store.loadGreen(tmp, BuildSnapshot.ORIGIN_EXTENSION).getSuccessful());
    }

    @Test
    void recordNeverThrows() {
        SnapshotStore store = new SnapshotStore(tmp.resolve("snapshots"));
        assertNull(Snapshots.record(store, null, null, null, tmp.toString(), null, null, NOPLogger.NOP_LOGGER));
        assertNull(Snapshots.record(store, null, new DefaultMavenExecutionResult(), null, null, null, null, NOPLogger.NOP_LOGGER));
    }

    // --- TestFailures ----------------------------------------------------------------------------

    @Test
    void knowsSurefireAndFailsafeFolders() {
        assertEquals("surefire-reports", TestFailures.defaultFolder("maven-surefire-plugin"));
        assertEquals("failsafe-reports", TestFailures.defaultFolder("maven-failsafe-plugin"));
        assertNull(TestFailures.defaultFolder("maven-compiler-plugin"));
    }

    @Test
    void resolvesCommonExpressionsOnly() {
        File base = new File("/w/app");
        assertEquals("/w/app/target/it", TestFailures.resolve("${project.build.directory}/it", "/w/app/target", base));
        assertEquals("/w/app/reports", TestFailures.resolve("${project.basedir}/reports", null, base));
        assertEquals("/w/app/reports", TestFailures.resolve("${basedir}/reports", null, base));
        assertNull(TestFailures.resolve("${reportsDirectory}/x", "/w/app/target", base), "Unbekanntes → Standard");
        assertNull(TestFailures.resolve("", "/w/app/target", base));
    }

    private static String report(String testClass) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"" + testClass + "\" tests=\"1\" errors=\"0\" failures=\"1\">\n"
                + "  <testcase name=\"works\" classname=\"" + testClass + "\" time=\"0.1\">\n"
                + "    <failure type=\"org.opentest4j.AssertionFailedError\"><![CDATA[org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
                + "\tat org.acme.ItTest.works(ItTest.java:9)\n]]></failure>\n"
                + "  </testcase>\n"
                + "</testsuite>\n";
    }

    @Test
    void readsConfiguredFailsafeDirectory() throws Exception {
        Path base = tmp.resolve("it");
        Path reports = base.resolve("target/it-reports");
        Files.createDirectories(reports);
        Files.write(reports.resolve("TEST-org.acme.ItTest.xml"), report("org.acme.ItTest").getBytes(StandardCharsets.UTF_8));
        MojoExecution failsafe = mojo("maven-failsafe-plugin", "verify");
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom dir = new Xpp3Dom("reportsDirectory");
        dir.setValue("${project.build.directory}/it-reports");
        config.addChild(dir);
        failsafe.setConfiguration(config);

        Map<String, Object> e = TestFailures.event(event(ExecutionEvent.Type.MojoFailed, project(base, "it"), failsafe,
                new MojoExecutionException("There are test failures.")), 0, NOPLogger.NOP_LOGGER);

        assertNotNull(e);
        assertEquals(reports.toString(), e.get("reports"));
        assertEquals("org.apache.maven.plugins:maven-failsafe-plugin:3.5.2:verify", e.get("mojo"));
        assertTrue(String.valueOf(e.get("text")).contains("expected: <1> but was: <2>"), e.toString());
    }

    @Test
    void ignoresReportsFromEarlierRunsAndOtherMojos() throws Exception {
        Path base = tmp.resolve("old");
        Path reports = base.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Path file = reports.resolve("TEST-org.acme.OldTest.xml");
        Files.write(file, report("org.acme.OldTest").getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        MavenProject p = project(base, "old");
        MojoExecutionException failure = new MojoExecutionException("There are test failures.");

        assertNull(TestFailures.event(event(ExecutionEvent.Type.MojoFailed, p, mojo("maven-surefire-plugin", "test"), failure),
                System.currentTimeMillis(), NOPLogger.NOP_LOGGER), "Bericht stammt aus einem früheren Lauf");
        assertNull(TestFailures.event(event(ExecutionEvent.Type.MojoFailed, p, mojo("maven-compiler-plugin", "compile"), failure),
                0, NOPLogger.NOP_LOGGER), "kein Test-Plugin");
        assertNull(TestFailures.event(event(ExecutionEvent.Type.MojoSucceeded, p, mojo("maven-surefire-plugin", "test"), null),
                0, NOPLogger.NOP_LOGGER), "nur bei MojoFailed");
        assertNull(TestFailures.event(event(ExecutionEvent.Type.MojoFailed, null, null, null), 0, NOPLogger.NOP_LOGGER));
    }
}
