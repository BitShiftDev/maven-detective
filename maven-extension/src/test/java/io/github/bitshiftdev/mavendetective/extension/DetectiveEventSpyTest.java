package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.Json;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Die Extension ohne Maven-Lauf: Events, Grenzen und „wirft nie“. Der echte Lauf steckt in {@code fixtureTest}. */
class DetectiveEventSpyTest {

    @TempDir
    Path tmp;

    // --- Hilfen ----------------------------------------------------------------------------------

    private EventSpy.Context context(Properties user) {
        Map<String, Object> data = new HashMap<>();
        Properties version = new Properties();
        version.setProperty("version", "3.9.11");
        data.put("userProperties", user);
        data.put("systemProperties", new Properties());
        data.put("versionProperties", version);
        return () -> data;
    }

    private Properties props(Path events) {
        Properties p = new Properties();
        p.setProperty(DetectiveEventSpy.PROP_EVENTS_FILE, events.toString());
        p.setProperty(DetectiveEventSpy.PROP_REPORT, "false");
        p.setProperty(DetectiveEventSpy.PROP_SNAPSHOTS, "false");
        return p;
    }

    private DefaultMavenExecutionRequest request() {
        DefaultMavenExecutionRequest r = new DefaultMavenExecutionRequest();
        r.setMultiModuleProjectDirectory(tmp.toFile());
        r.setLocalRepositoryPath(tmp.resolve("repo").toFile());
        r.setGoals(List.of("verify"));
        return r;
    }

    static MavenProject project(Path basedir, String artifactId) {
        Model model = new Model();
        model.setGroupId("org.acme");
        model.setArtifactId(artifactId);
        model.setVersion("1.0");
        Build build = new Build();
        build.setDirectory(basedir.resolve("target").toString());
        model.setBuild(build);
        MavenProject p = new MavenProject(model);
        p.setFile(basedir.resolve("pom.xml").toFile());
        return p;
    }

    static MojoExecution mojo(String artifactId, String goal) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifactId);
        plugin.setVersion("3.5.2");
        return new MojoExecution(plugin, goal, "default-" + goal);
    }

    static ExecutionEvent event(ExecutionEvent.Type type, MavenProject project, MojoExecution mojo, Exception exception) {
        return new ExecutionEvent() {
            @Override public Type getType() { return type; }
            @Override public MavenSession getSession() { return null; }
            @Override public MavenProject getProject() { return project; }
            @Override public MojoExecution getMojoExecution() { return mojo; }
            @Override public Exception getException() { return exception; }
        };
    }

    private static List<Map<String, Object>> lines(Path file) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(line);
            out.add(m);
        }
        return out;
    }

    // --- Tests -----------------------------------------------------------------------------------

    @Test
    void writesSessionFailuresAndBuildErrorsAsJsonLines() throws Exception {
        Path events = tmp.resolve("out/events.jsonl");   // Ordner fehlt noch – die Extension legt ihn an
        DetectiveEventSpy spy = new DetectiveEventSpy();
        spy.init(context(props(events)));

        MavenProject app = project(tmp.resolve("app"), "app");
        MojoExecutionException failure = new MojoExecutionException("Compilation failure",
                new IllegalStateException("cannot find symbol"));
        spy.onEvent(request());
        spy.onEvent(event(ExecutionEvent.Type.MojoStarted, app, mojo("maven-compiler-plugin", "compile"), null));
        spy.onEvent(event(ExecutionEvent.Type.MojoFailed, app, mojo("maven-compiler-plugin", "compile"), failure));
        DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
        result.addException(failure);
        spy.onEvent(result);
        spy.close();

        List<Map<String, Object>> written = lines(events);
        List<Object> types = new ArrayList<>();
        for (Map<String, Object> e : written) {
            types.add(e.get("type"));
        }
        assertEquals(List.of(EventLog.TYPE_SESSION, EventLog.TYPE_MOJO_FAILED, EventLog.TYPE_BUILD_ERROR), types);
        assertEquals("3.9.11", written.get(0).get("maven"));
        assertEquals(tmp.toFile().getAbsolutePath(), written.get(0).get("projectDirectory"));
        assertEquals("org.acme:app:1.0", written.get(1).get("project"));
        assertEquals("org.apache.maven.plugins:maven-compiler-plugin:3.5.2:compile", written.get(1).get("mojo"));

        BuildFailure read = EventLog.read(events);
        assertEquals("verify", read.getSession().getGoals().get(0));
        ErrorRecord first = read.getErrors().get(0);
        assertEquals(2, first.getChain().size(), "Ausnahme samt Ursache");
        assertTrue(first.lines().toString().contains("cannot find symbol"), first.lines().toString());
    }

    @Test
    void carriesTestExceptionsFromSurefireReports() throws Exception {
        Path base = tmp.resolve("svc");
        Path reports = base.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.write(reports.resolve("TEST-org.acme.GatewayTest.xml"), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"org.acme.GatewayTest\" tests=\"1\" errors=\"1\" failures=\"0\">\n"
                        + "  <testcase name=\"routes\" classname=\"org.acme.GatewayTest\" time=\"0.1\">\n"
                        + "    <error type=\"java.lang.NoSuchMethodError\"><![CDATA[java.lang.NoSuchMethodError: 'void org.lib.Api.call()'\n"
                        + "\tat org.acme.Gateway.accept(Gateway.java:19)\n]]></error>\n"
                        + "  </testcase>\n"
                        + "</testsuite>\n").getBytes(StandardCharsets.UTF_8));
        Path events = tmp.resolve("events.jsonl");
        DetectiveEventSpy spy = new DetectiveEventSpy();
        spy.init(context(props(events)));
        spy.onEvent(request());
        spy.onEvent(event(ExecutionEvent.Type.MojoFailed, project(base, "svc"), mojo("maven-surefire-plugin", "test"),
                new MojoExecutionException("There are test failures.")));
        spy.close();

        List<Map<String, Object>> written = lines(events);
        Map<String, Object> tests = written.get(written.size() - 1);
        assertEquals(EventLog.TYPE_TEST_FAILURES, tests.get("type"));
        assertTrue(String.valueOf(tests.get("text")).contains("NoSuchMethodError: 'void org.lib.Api.call()'"), tests.toString());
        assertEquals(reports.toString(), tests.get("reports"));
        BuildFailure read = EventLog.read(events);
        assertTrue(read.getErrors().get(read.getErrors().size() - 1).isTestOutput());
    }

    @Test
    void limitsRepositoryEvents() throws Exception {
        Path events = tmp.resolve("events.jsonl");
        DetectiveEventSpy spy = new DetectiveEventSpy();
        spy.init(context(props(events)));
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo.example.com/maven2").build();
        for (int i = 0; i < 600; i++) {
            spy.onEvent(new RepositoryEvent.Builder(session, RepositoryEvent.EventType.ARTIFACT_RESOLVED)
                    .setArtifact(new DefaultArtifact("org.acme:lib" + i + ":1.0"))
                    .setRepository(central)
                    .setExceptions(List.of(new Exception("Could not transfer artifact")))
                    .build());
        }
        // ohne Ausnahme: kein Event
        spy.onEvent(new RepositoryEvent.Builder(session, RepositoryEvent.EventType.ARTIFACT_RESOLVED)
                .setArtifact(new DefaultArtifact("org.acme:ok:1.0")).build());
        spy.close();

        List<Map<String, Object>> written = lines(events);
        assertEquals(500, written.size());
        assertEquals("central", written.get(0).get("repositoryId"));
        assertEquals("https://repo.example.com/maven2", written.get(0).get("repositoryUrl"));
        assertEquals("Could not transfer artifact", written.get(0).get("failure"));
    }

    @Test
    void neverThrows() {
        DetectiveEventSpy spy = new DetectiveEventSpy();
        assertDoesNotThrow(() -> {
            spy.init(() -> null);                     // kaputter Kontext
            spy.onEvent(null);
            spy.onEvent(new Object());
            spy.onEvent(event(null, null, null, null));   // unbekannter Typ
            spy.onEvent(new DefaultMavenExecutionRequest());   // ohne Verzeichnisse
            DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
            result.addException(new RuntimeException("boom"));
            spy.onEvent(result);                      // Karte ohne Events-Datei
            spy.close();
            spy.close();
        });
    }

    @Test
    void unwritableEventsFileDoesNotBreakTheBuild() throws Exception {
        Path dir = tmp.resolve("ein-ordner");
        Files.createDirectories(dir);
        DetectiveEventSpy spy = new DetectiveEventSpy();
        assertDoesNotThrow(() -> {
            spy.init(context(props(dir)));   // Ordner statt Datei: FileOutputStream scheitert
            spy.onEvent(request());
            spy.close();
        });
        assertTrue(new File(dir.toString()).isDirectory());
    }

    @Test
    void cardIsPrintedWithoutTouchingTheResult() {
        Properties p = new Properties();
        p.setProperty(DetectiveEventSpy.PROP_SNAPSHOTS, "false");
        DetectiveEventSpy spy = new DetectiveEventSpy();
        spy.init(context(p));
        spy.onEvent(request());
        DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
        result.addException(new MojoExecutionException("Compilation failure"));
        spy.onEvent(result);
        spy.close();
        assertEquals(1, result.getExceptions().size(), "Extension ändert das Ergebnis nicht");
    }
}
