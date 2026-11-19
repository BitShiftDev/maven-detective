package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.Json;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.PomProblem;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.event;
import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.mojo;
import static io.github.bitshiftdev.mavendetective.extension.DetectiveEventSpyTest.project;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Welle 6: Die Extension schluckt jeden internen Fehler, ein kaputtes Teil kostet nicht die übrigen
 * (Events, Schnappschuss, Karte), und sie ändert weder Anfrage, Ergebnis noch Projekte.
 */
class DetectiveEventSpyRobustnessTest {

    @TempDir
    Path tmp;

    // --- Hilfen ----------------------------------------------------------------------------------

    /** Fängt, was die Extension ins Maven-Log schreibt (ohne slf4j-Bindung im Test). */
    static final class CapturedLog {
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final List<Object> debug = Collections.synchronizedList(new ArrayList<>());

        Logger logger() {
            return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("error".equals(name) && args != null && args[0] instanceof String) {
                            errors.add((String) args[0]);
                        } else if ("debug".equals(name) && args != null) {
                            debug.add(args[args.length - 1]);
                        }
                        if (method.getReturnType() == boolean.class) {
                            return "equals".equals(name) ? proxy == args[0] : Boolean.TRUE;
                        }
                        if (method.getReturnType() == int.class) {
                            return System.identityHashCode(proxy);
                        }
                        return method.getReturnType() == String.class ? "captured" : null;
                    });
        }

        String card() {
            synchronized (errors) {
                return String.join("\n", errors);
            }
        }
    }

    private static EventSpy.Context context(Properties user, Properties system) {
        Map<String, Object> data = new HashMap<>();
        Properties version = new Properties();
        version.setProperty("version", "3.9.11");
        data.put("userProperties", user);
        data.put("systemProperties", system);
        data.put("versionProperties", version);
        return () -> data;
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    private Path events() {
        return tmp.resolve("events.jsonl");
    }

    private Path home() {
        return tmp.resolve("home");
    }

    /** Events in eine Datei, Karte an, Schnappschüsse in {@link #home()}. */
    private Properties full() {
        return props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString(), SnapshotStore.PROP_HOME, home().toString());
    }

    private DefaultMavenExecutionRequest request() {
        DefaultMavenExecutionRequest r = new DefaultMavenExecutionRequest();
        r.setMultiModuleProjectDirectory(tmp.toFile());
        r.setLocalRepositoryPath(tmp.resolve("repo").toFile());
        r.setGoals(new ArrayList<>(List.of("verify")));
        return r;
    }

    private static Artifact artifact(String g, String a, String v, String scope) {
        return new DefaultArtifact(g, a, v, scope, "jar", null, new DefaultArtifactHandler("jar"));
    }

    private MavenProject app(String libVersion) {
        MavenProject p = project(tmp.resolve("app"), "app");
        p.getProperties().setProperty("lib.version", libVersion);
        Set<Artifact> artifacts = new LinkedHashSet<>();
        artifacts.add(artifact("org.lib", "lib", libVersion, "compile"));
        p.setArtifacts(artifacts);
        return p;
    }

    private static DefaultMavenExecutionResult result(MavenProject p, Throwable... failures) {
        DefaultMavenExecutionResult r = new DefaultMavenExecutionResult();
        if (p != null) {
            r.setTopologicallySortedProjects(new ArrayList<>(List.of(p)));
        }
        for (Throwable t : failures) {
            r.addException(t);
        }
        return r;
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

    private static List<Object> types(List<Map<String, Object>> events) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            out.add(e.get("type"));
        }
        return out;
    }

    /** Eine Ausnahme, deren {@code getMessage()} selbst scheitert (kommt bei fremden Plugin-Ausnahmen vor). */
    static final class BrokenMessage extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage kaputt");
        }
    }

    private static final String RESOLUTION_FAILURE = "Could not resolve dependencies for project org.acme:app:jar:1.0: "
            + "The following artifacts could not be resolved: org.lib:lib:jar:9.9 (absent): "
            + "Could not find artifact org.lib:lib:jar:9.9 in central (https://repo.maven.apache.org/maven2)";

    // --- Je Ereignistyp ein kaputter Zustand ---------------------------------------------------------

    static Stream<Arguments> brokenEvents() {
        MavenProject brokenProject = new MavenProject() {
            @Override
            public String getGroupId() {
                throw new IllegalStateException("kaputtes Projekt");
            }
        };
        MojoDescriptor brokenDescriptor = new MojoDescriptor() {
            @Override
            public String getDependencyResolutionRequired() {
                throw new IllegalStateException("kaputter Deskriptor");
            }
        };
        MavenProject artifactsThrow = new MavenProject() {
            @Override
            public Set<Artifact> getArtifacts() {
                throw new IllegalStateException("kaputte Artifacts");
            }
        };
        MojoDescriptor compile = new MojoDescriptor();
        compile.setDependencyResolutionRequired("compile");
        MavenSession brokenSession = new MavenSession(null, null, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult()) {
            @Override
            public List<MavenProject> getProjects() {
                throw new IllegalStateException("kaputte Sitzung");
            }
        };
        DefaultRepositorySystemSession rs = new DefaultRepositorySystemSession();
        return Stream.of(
                Arguments.of(Named.of("Anfrage, deren Getter werfen", new DefaultMavenExecutionRequest() {
                    @Override
                    public List<String> getActiveProfiles() {
                        throw new IllegalStateException("kaputte Anfrage");
                    }
                })),
                Arguments.of(Named.of("Repository-Event mit null als Ausnahme", new RepositoryEvent.Builder(rs,
                        RepositoryEvent.EventType.ARTIFACT_RESOLVED).setExceptions(Collections.singletonList(null)).build())),
                Arguments.of(Named.of("Repository-Event, dessen Meldung wirft", new RepositoryEvent.Builder(rs,
                        RepositoryEvent.EventType.METADATA_RESOLVED).setExceptions(List.of(new BrokenMessage())).build())),
                Arguments.of(Named.of("ExecutionEvent ohne Typ", event(null, null, null, null))),
                Arguments.of(Named.of("SessionStarted mit kaputter Sitzung", new ExecutionEvent() {
                    @Override public Type getType() { return Type.SessionStarted; }
                    @Override public MavenSession getSession() { return brokenSession; }
                    @Override public MavenProject getProject() { return null; }
                    @Override public MojoExecution getMojoExecution() { return null; }
                    @Override public Exception getException() { return null; }
                })),
                Arguments.of(Named.of("MojoStarted mit kaputtem Deskriptor",
                        event(ExecutionEvent.Type.MojoStarted, project(Paths.get("x"), "x"), new MojoExecution(brokenDescriptor), null))),
                Arguments.of(Named.of("MojoStarted, Artifacts werfen",
                        event(ExecutionEvent.Type.MojoStarted, artifactsThrow, new MojoExecution(compile), null))),
                Arguments.of(Named.of("MojoFailed mit kaputtem Projekt",
                        event(ExecutionEvent.Type.MojoFailed, brokenProject, mojo("maven-compiler-plugin", "compile"),
                                new MojoExecutionException("x")))),
                Arguments.of(Named.of("Ergebnis, dessen Ausnahmen werfen", new DefaultMavenExecutionResult() {
                    @Override
                    public List<Throwable> getExceptions() {
                        throw new IllegalStateException("kaputtes Ergebnis");
                    }
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenEvents")
    void brokenEventIsSwallowedAndLaterEventsStillArrive(Object broken) throws Exception {
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString(),
                DetectiveEventSpy.PROP_REPORT, "false", DetectiveEventSpy.PROP_SNAPSHOTS, "false"), new Properties()));

        assertDoesNotThrow(() -> spy.onEvent(broken));
        spy.onEvent(event(ExecutionEvent.Type.MojoFailed, app("1.0"), mojo("maven-compiler-plugin", "compile"),
                new MojoExecutionException("Compilation failure")));
        spy.close();

        List<Map<String, Object>> written = lines(events());
        Map<String, Object> last = written.get(written.size() - 1);
        assertEquals(EventLog.TYPE_MOJO_FAILED, last.get("type"));
        assertEquals("org.acme:app:1.0", last.get("project"));
    }

    // --- Ein kaputtes Teil kostet nicht die übrigen ------------------------------------------------

    @Test
    void brokenExceptionDoesNotCostTheOtherErrorsTheSnapshotOrTheCard() throws Exception {
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy spy = new DetectiveEventSpy(log.logger());
        spy.init(context(full(), new Properties()));
        spy.onEvent(request());
        spy.onEvent(result(app("1.0"), new BrokenMessage(), new MojoExecutionException(RESOLUTION_FAILURE)));
        spy.close();

        List<Map<String, Object>> written = lines(events());
        assertEquals(List.of(EventLog.TYPE_SESSION, EventLog.TYPE_BUILD_ERROR, EventLog.TYPE_BUILD_ERROR), types(written));
        BuildFailure read = EventLog.read(events());
        assertEquals(BrokenMessage.class.getName(), read.getErrors().get(0).getChain().get(0).getClassName());
        assertTrue(read.getErrors().get(1).lines().toString().contains("org.lib:lib:jar:9.9"));
        assertTrue(Files.isRegularFile(new SnapshotStore(home().resolve("snapshots")).latestFile(tmp, BuildSnapshot.ORIGIN_EXTENSION)),
                "Schnappschuss geschrieben");
        assertTrue(log.card().contains("org.lib:lib"), log.card());
    }

    @Test
    void exceptionWithBrokenMessageKeepsItsClassAndCause() throws Exception {
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString(),
                DetectiveEventSpy.PROP_REPORT, "false", DetectiveEventSpy.PROP_SNAPSHOTS, "false"), new Properties()));
        Exception failure = new MojoExecutionException("Compilation failure", new BrokenMessage());
        spy.onEvent(event(ExecutionEvent.Type.MojoFailed, app("1.0"), mojo("maven-compiler-plugin", "compile"), failure));
        spy.close();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) lines(events()).get(0).get("chain");
        assertEquals(2, chain.size());
        assertEquals("Compilation failure", chain.get(0).get("message"));
        assertEquals(BrokenMessage.class.getName(), chain.get(1).get("class"));
        assertNull(chain.get(1).get("message"));
    }

    /** Ein ProjectBuildingResult ohne Maven-eigene Implementierung (die ist package-private). */
    private static ProjectBuildingResult buildingResult(File pom, List<ModelProblem> problems) {
        return new ProjectBuildingResult() {
            @Override public String getProjectId() { return "org.acme:app:1.0"; }
            @Override public File getPomFile() { return pom; }
            @Override public MavenProject getProject() { return null; }
            @Override public List<ModelProblem> getProblems() { return problems; }
            @Override public DependencyResolutionResult getDependencyResolutionResult() { return null; }
        };
    }

    /** Mavens {@code DefaultModelProblem} setzt immer eine Schwere; fremde Implementierungen tun das nicht. */
    private static ModelProblem withoutSeverity() {
        return new ModelProblem() {
            @Override public String getSource() { return ""; }
            @Override public int getLineNumber() { return 0; }
            @Override public int getColumnNumber() { return 0; }
            @Override public String getModelId() { return ""; }
            @Override public Exception getException() { return null; }
            @Override public String getMessage() { return "ohne Schwere"; }
            @Override public Severity getSeverity() { return null; }
            @Override public Version getVersion() { return Version.BASE; }
        };
    }

    @Test
    void pomProblemsKeepFileAndLineSkipWarningsAndTolerateMissingSeverity() throws Exception {
        File pom = tmp.resolve("app/pom.xml").toFile();
        String parentPom = tmp.resolve("parent/pom.xml").toString();
        List<ModelProblem> problems = List.of(
                new DefaultModelProblem("Non-parseable POM: unexpected end tag", ModelProblem.Severity.FATAL,
                        ModelProblem.Version.BASE, parentPom, 12, 7, "org.acme:parent:1", null),
                new DefaultModelProblem("'version' is missing", ModelProblem.Severity.WARNING,
                        ModelProblem.Version.BASE, pom.getPath(), 3, 1, null, null),
                withoutSeverity());
        ProjectBuildingException failure = new ProjectBuildingException(List.of(buildingResult(pom, problems)));

        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString(),
                DetectiveEventSpy.PROP_REPORT, "false", DetectiveEventSpy.PROP_SNAPSHOTS, "false"), new Properties()));
        spy.onEvent(result(null, failure));
        spy.close();

        List<PomProblem> read = EventLog.read(events()).getErrors().get(0).getProblems();
        assertEquals(2, read.size(), read.toString());
        assertAll(
                () -> assertEquals(parentPom, read.get(0).getSource()),
                () -> assertEquals(12, read.get(0).getLine()),
                () -> assertEquals("FATAL", read.get(0).getSeverity()),
                () -> assertEquals(pom.getAbsolutePath(), read.get(1).getSource(), "Quelle ohne .xml → POM des Projekts"),
                () -> assertNull(read.get(1).getSeverity()));
    }

    @Test
    void repositoryEventsNameMetadataAndLocalRepositories() throws Exception {
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString()), new Properties()));
        DefaultRepositorySystemSession rs = new DefaultRepositorySystemSession();
        spy.onEvent(new RepositoryEvent.Builder(rs, RepositoryEvent.EventType.METADATA_RESOLVED)
                .setMetadata(new DefaultMetadata("org.acme", "lib", "maven-metadata.xml", Metadata.Nature.RELEASE))
                .setRepository(new LocalRepository(tmp.resolve("repo").toFile()))
                .setExceptions(List.of(new Exception("Checksum validation failed")))
                .build());
        spy.onEvent(new RepositoryEvent.Builder(rs, RepositoryEvent.EventType.ARTIFACT_DOWNLOADED)
                .setRepository(new RemoteRepository.Builder("nexus", "default", "https://nexus.example.com/repo").build())
                .setExceptions(List.of(new BrokenMessage()))
                .build());
        spy.close();

        List<Map<String, Object>> written = lines(events());
        assertEquals(2, written.size());
        assertEquals("METADATA_RESOLVED", written.get(0).get("event"));
        assertEquals("org.acme:lib/maven-metadata.xml", written.get(0).get("artifact"));
        assertEquals("local", written.get(0).get("repositoryId"));
        assertFalse(written.get(0).containsKey("repositoryUrl"));
        assertEquals("nexus", written.get(1).get("repositoryId"));
        assertEquals(BrokenMessage.class.getName(), written.get(1).get("failure"), "Klasse statt Meldung");
    }

    // --- Einstellungen -------------------------------------------------------------------------------

    @ParameterizedTest(name = "Nutzer report={0}, System report={1} → Karte {2}")
    @CsvSource(nullValues = "-", value = {
            "-, -, true",
            "false, -, false",
            "FALSE, -, false",
            "-, false, false",
            "true, false, true",
            "false, true, false",
            "nein, -, true",
    })
    void cardFollowsReportSettingUserBeforeSystem(String user, String system, boolean printed) {
        Properties u = props(DetectiveEventSpy.PROP_SNAPSHOTS, "false");
        Properties s = new Properties();
        if (user != null) {
            u.setProperty(DetectiveEventSpy.PROP_REPORT, user);
        }
        if (system != null) {
            s.setProperty(DetectiveEventSpy.PROP_REPORT, system);
        }
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy spy = new DetectiveEventSpy(log.logger());
        spy.init(context(u, s));
        spy.onEvent(request());
        spy.onEvent(result(null, new MojoExecutionException(RESOLUTION_FAILURE)));
        spy.close();
        assertEquals(printed, log.card().contains("org.lib:lib"), log.card());
    }

    @Test
    void greenRunPrintsNoCard() {
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy spy = new DetectiveEventSpy(log.logger());
        spy.init(context(props(DetectiveEventSpy.PROP_SNAPSHOTS, "false"), new Properties()));
        spy.onEvent(request());
        spy.onEvent(result(app("1.0")));
        spy.close();
        assertTrue(log.errors.isEmpty(), log.card());
    }

    @ParameterizedTest(name = "Datei aus {0}")
    @CsvSource({"user", "system"})
    void eventsFileFromUserOrSystemPropertiesCreatesMissingFolders(String where) throws Exception {
        Path file = tmp.resolve("a/b/c/events.jsonl");
        Properties p = props(DetectiveEventSpy.PROP_EVENTS_FILE, "  " + file + "  ", DetectiveEventSpy.PROP_SNAPSHOTS, "false",
                DetectiveEventSpy.PROP_REPORT, "false");
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init("user".equals(where) ? context(p, new Properties()) : context(new Properties(), p));
        spy.onEvent(request());
        spy.close();
        assertEquals(List.of(EventLog.TYPE_SESSION), types(lines(file)));
    }

    @Test
    void blankEventsFileWritesNothing() throws Exception {
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, "   ", DetectiveEventSpy.PROP_SNAPSHOTS, "false",
                DetectiveEventSpy.PROP_REPORT, "false"), new Properties()));
        spy.onEvent(request());
        spy.close();
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(0, files.count());
        }
    }

    // --- Schnappschüsse ------------------------------------------------------------------------------

    private void run(DetectiveEventSpy spy, MavenProject project, Throwable... failures) {
        spy.onEvent(request());
        MavenSession session = new MavenSession(null, null, request(), new DefaultMavenExecutionResult());
        session.setProjects(List.of(project));
        spy.onEvent(new ExecutionEvent() {
            @Override public Type getType() { return Type.SessionStarted; }
            @Override public MavenSession getSession() { return session; }
            @Override public MavenProject getProject() { return null; }
            @Override public MojoExecution getMojoExecution() { return null; }
            @Override public Exception getException() { return null; }
        });
        spy.onEvent(result(project, failures));
    }

    @Test
    void snapshotsOffWritesNothingIntoHome() throws Exception {
        Properties p = props(SnapshotStore.PROP_HOME, home().toString(), DetectiveEventSpy.PROP_SNAPSHOTS, "false");
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy green = new DetectiveEventSpy(log.logger());
        green.init(context(p, new Properties()));
        run(green, app("1.7.1"));
        green.close();
        DetectiveEventSpy red = new DetectiveEventSpy(log.logger());
        red.init(context(new Properties(), p));   // auch als System-Property
        run(red, app("1.7.2"), new MojoExecutionException(RESOLUTION_FAILURE));
        red.close();

        assertFalse(Files.exists(home()), "kein Ordner, keine Datei");
        assertTrue(log.card().contains("org.lib:lib"), "Karte trotzdem");
        assertFalse(log.card().contains("1.7.1"), "ohne Vergleich mit dem grünen Lauf");
    }

    @Test
    void greenThenRedShowsWhatChangedOnTheCard() {
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy green = new DetectiveEventSpy(log.logger());
        green.init(context(full(), new Properties()));
        run(green, app("1.7.1"));
        green.close();
        DetectiveEventSpy red = new DetectiveEventSpy(log.logger());
        red.init(context(full(), new Properties()));
        run(red, app("1.7.2"), new MojoExecutionException(RESOLUTION_FAILURE));
        red.close();
        assertTrue(log.card().contains("1.7.1") && log.card().contains("1.7.2"), log.card());
    }

    @Test
    void homeThatIsAFileDoesNotCostTheCard() throws Exception {
        Files.write(home(), "kein Ordner".getBytes(StandardCharsets.UTF_8));
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy spy = new DetectiveEventSpy(log.logger());
        spy.init(context(full(), new Properties()));
        assertDoesNotThrow(() -> run(spy, app("1.0"), new MojoExecutionException(RESOLUTION_FAILURE)));
        spy.close();
        assertTrue(log.card().contains("org.lib:lib"), log.card());
        assertEquals("kein Ordner", new String(Files.readAllBytes(home()), StandardCharsets.UTF_8));
    }

    /** Schreibgeschützte Ablage (z. B. ein CI-Cache): der rote Lauf zeigt trotzdem den Vergleich mit dem grünen. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readOnlyHomeStillComparesWithTheLastGreenRun() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root darf überall schreiben");
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy green = new DetectiveEventSpy(log.logger());
        green.init(context(full(), new Properties()));
        run(green, app("1.7.1"));
        green.close();
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(home())) {
            walk.filter(Files::isDirectory).forEach(dirs::add);
        }
        for (Path d : dirs) {
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("r-xr-xr-x"));
        }
        try {
            DetectiveEventSpy red = new DetectiveEventSpy(log.logger());
            red.init(context(full(), new Properties()));
            run(red, app("1.7.2"), new MojoExecutionException(RESOLUTION_FAILURE));
            red.close();
        } finally {
            for (Path d : dirs) {
                Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
        assertTrue(log.card().contains("1.7.1") && log.card().contains("1.7.2"), log.card());
    }

    // --- Schreiben -----------------------------------------------------------------------------------

    /** {@code /dev/full} nimmt das Öffnen an und scheitert bei jedem Schreiben (ENOSPC) – wie eine volle Platte. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void fullDiskWhileWritingEventsDoesNotCostTheCard() {
        assumeTrue(new File("/dev/full").exists());
        CapturedLog log = new CapturedLog();
        DetectiveEventSpy spy = new DetectiveEventSpy(log.logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, "/dev/full", DetectiveEventSpy.PROP_SNAPSHOTS, "false"),
                new Properties()));
        assertDoesNotThrow(() -> {
            spy.onEvent(request());
            spy.onEvent(event(ExecutionEvent.Type.MojoFailed, app("1.0"), mojo("maven-compiler-plugin", "compile"),
                    new MojoExecutionException(RESOLUTION_FAILURE)));
            spy.onEvent(result(null, new MojoExecutionException(RESOLUTION_FAILURE)));
            spy.close();
        });
        assertTrue(log.card().contains("org.lib:lib"), log.card());
    }

    @Test
    void parallelEventsNeverMixLines() throws Exception {
        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(props(DetectiveEventSpy.PROP_EVENTS_FILE, events().toString(),
                DetectiveEventSpy.PROP_REPORT, "false", DetectiveEventSpy.PROP_SNAPSHOTS, "false"), new Properties()));
        int threads = 8;
        int perThread = 150;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        String longMessage = "x".repeat(4000);
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    spy.onEvent(event(ExecutionEvent.Type.MojoFailed, project(tmp.resolve("m" + id), "m" + id),
                            mojo("maven-compiler-plugin", "compile"), new MojoExecutionException(id + ":" + i + ":" + longMessage)));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        spy.close();

        List<Map<String, Object>> written = lines(events());   // jede Zeile ist gültiges JSON
        assertEquals(threads * perThread, written.size());
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : written) {
            @SuppressWarnings("unchecked")
            String message = (String) ((List<Map<String, Object>>) e.get("chain")).get(0).get("message");
            seen.add(message.substring(0, message.indexOf(":x")));
        }
        assertEquals(threads * perThread, seen.size());
    }

    // --- Ändert nichts am Build ----------------------------------------------------------------------

    @Test
    void neverChangesRequestResultProjectsOrSystemProperties() throws Exception {
        MavenProject p = app("1.0");
        MojoDescriptor descriptor = new MojoDescriptor();
        descriptor.setDependencyResolutionRequired("test");
        MojoExecution compile = new MojoExecution(descriptor);
        DefaultMavenExecutionRequest request = request();
        request.setActiveProfiles(new ArrayList<>(List.of("ci")));
        request.setUserProperties(props("x", "1"));
        Throwable failure = new MojoExecutionException(RESOLUTION_FAILURE);
        DefaultMavenExecutionResult result = result(p, failure);

        String before = state(request, result, p);
        Properties system = (Properties) System.getProperties().clone();

        DetectiveEventSpy spy = new DetectiveEventSpy(new CapturedLog().logger());
        spy.init(context(full(), new Properties()));
        spy.onEvent(request);
        spy.onEvent(event(ExecutionEvent.Type.MojoStarted, p, compile, null));
        spy.onEvent(event(ExecutionEvent.Type.MojoFailed, p, compile, (Exception) failure));
        spy.onEvent(event(ExecutionEvent.Type.ProjectFailed, p, null, (Exception) failure));
        spy.onEvent(result);
        spy.close();

        assertEquals(before, state(request, result, p));
        assertEquals(system, System.getProperties());
        assertEquals(List.of(failure), result.getExceptions());
    }

    private static String state(DefaultMavenExecutionRequest request, DefaultMavenExecutionResult result, MavenProject p) {
        return String.join("|",
                String.valueOf(request.getGoals()), String.valueOf(request.getActiveProfiles()),
                String.valueOf(request.getInactiveProfiles()), String.valueOf(request.getUserProperties()),
                String.valueOf(request.getSystemProperties()), String.valueOf(request.isOffline()),
                String.valueOf(result.getExceptions()), String.valueOf(result.getTopologicallySortedProjects()),
                String.valueOf(p.getArtifacts()), String.valueOf(p.getProperties()), String.valueOf(p.getDependencies()),
                String.valueOf(p.getModel().getProperties()));
    }
}
