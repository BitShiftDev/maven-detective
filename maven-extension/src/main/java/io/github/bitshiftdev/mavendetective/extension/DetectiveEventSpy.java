package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.Json;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maven-Core-Extension: schreibt strukturierte Events und gibt bei einem Fehler die Diagnose-Karte aus.
 *
 * <p>Einbinden
 * <ul>
 * <li>einmalig: {@code mvn -Dmaven.ext.class.path=/pfad/maven-detective-extension.jar verify}</li>
 * <li>im Projekt (CI): {@code .mvn/extensions.xml} mit groupId/artifactId/version der Extension</li>
 * </ul>
 *
 * <p>Einstellungen (als -D oder in .mvn/maven.config)
 * <ul>
 * <li>{@code mavenDetective.eventsFile=<pfad>} – Events als JSON-Zeilen schreiben (die IDE setzt das)</li>
 * <li>{@code mavenDetective.report=false} – keine Karte im Log</li>
 * <li>{@code mavenDetective.snapshots=false} – keine Schnappschüsse für „Was hat sich geändert?“</li>
 * <li>{@code mavenDetective.home=<ordner>} – Ablage statt {@code ~/.maven-detective}</li>
 * </ul>
 *
 * <p>Nach jedem Lauf schreibt die Extension einen Schnappschuss (Maven, JDK, Profile, Mirrors, je Modul
 * Parent, Plugins, Versions-Properties und Dependencies) nach {@code ~/.maven-detective/snapshots}.
 * Ist der Lauf grün, wird er die neue Referenz; scheitert er, zeigt die Karte, was sich seit dem
 * letzten grünen Lauf geändert hat.
 *
 * <p>Scheitert Surefire oder Failsafe, liest die Extension die XML-Berichte dieses Laufs ({@link TestFailures}),
 * denn Maven meldet selbst nur „There are test failures“.
 *
 * <p>Die Extension darf einen Build nie verändern oder abbrechen: jeder Fehler hier wird geschluckt.
 */
@Named("maven-detective")
@Singleton
public class DetectiveEventSpy extends AbstractEventSpy {

    static final String PROP_EVENTS_FILE = "mavenDetective.eventsFile";
    static final String PROP_REPORT = "mavenDetective.report";
    static final String PROP_SNAPSHOTS = "mavenDetective.snapshots";
    private static final int MAX_REPOSITORY_EVENTS = 500;
    private static final int MAX_CHAIN = 12;

    private final Logger log;
    private final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
    /** Events kommen unter {@code -T} und aus Resolver-Threads parallel; Zugriff nur unter {@link #writerLock}. */
    private final Object writerLock = new Object();
    private Writer writer;
    private boolean report = true;
    private boolean snapshots = true;
    private MavenExecutionRequest request;
    private SnapshotStore store;
    private String mavenVersion;
    private String projectDirectory;
    private final AtomicInteger repositoryEvents = new AtomicInteger();
    /** Testberichte, die älter sind, stammen aus einem früheren Lauf. */
    private final long startedAt = System.currentTimeMillis();
    /** groupId:artifactId → Scopes, die gestartete Mojos auflösen ließen (für die Schnappschuss-Referenz). */
    private final Map<String, java.util.Set<String>> resolvedScopes = new java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>>();
    /**
     * groupId:artifactId → größte aufgelöste Artifact-Menge eines Mojos. Maven ersetzt {@code getArtifacts()} je Mojo:
     * nach {@code jar} (runtime) fehlen am Ende eines grünen {@code verify} die test-Dependencies von Surefire.
     */
    private final Map<String, java.util.Set<org.apache.maven.artifact.Artifact>> resolvedArtifacts =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.Set<org.apache.maven.artifact.Artifact>>();
    /**
     * groupId:artifactId → Hash der Effective POM vor dem ersten Mojo. Am Ende des Builds enthält das Modell Properties,
     * die Plugins zur Laufzeit setzen (jacoco {@code argLine}, buildnumber-Zeitstempel) – jeder Lauf sähe „geändert“ aus.
     */
    private final Map<String, String> pomHashes = new java.util.concurrent.ConcurrentHashMap<String, String>();

    public DetectiveEventSpy() {
        this(LoggerFactory.getLogger("maven-detective"));
    }

    /** Für Tests: fängt die Karte ab (im Test gibt es keine slf4j-Bindung). */
    DetectiveEventSpy(Logger log) {
        this.log = log;
    }

    @Override
    public void init(Context context) {
        try {
            Map<String, Object> data = context.getData();
            Properties user = props(data.get("userProperties"));
            Properties system = props(data.get("systemProperties"));
            Properties version = props(data.get("versionProperties"));
            mavenVersion = version.getProperty("version");
            String eventsFile = user.getProperty(PROP_EVENTS_FILE, system.getProperty(PROP_EVENTS_FILE));
            report = !"false".equalsIgnoreCase(user.getProperty(PROP_REPORT, system.getProperty(PROP_REPORT, "true")));
            snapshots = !"false".equalsIgnoreCase(user.getProperty(PROP_SNAPSHOTS, system.getProperty(PROP_SNAPSHOTS, "true")));
            String home = user.getProperty(SnapshotStore.PROP_HOME, system.getProperty(SnapshotStore.PROP_HOME));
            store = home != null && !home.trim().isEmpty()
                    ? new SnapshotStore(Paths.get(home.trim()).resolve("snapshots")) : SnapshotStore.defaultStore();
            if (eventsFile != null && !eventsFile.trim().isEmpty()) {
                File f = new File(eventsFile.trim());
                File parent = f.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    log.debug("Maven Detective: kann {} nicht anlegen", parent);
                }
                synchronized (writerLock) {
                    writer = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable t) {
            log.debug("Maven Detective: Initialisierung fehlgeschlagen", t);
        }
    }

    @Override
    public void onEvent(Object event) {
        try {
            if (event instanceof MavenExecutionRequest) {
                request = (MavenExecutionRequest) event;
                record(session(request));
            } else if (event instanceof RepositoryEvent) {
                Map<String, Object> e = repository((RepositoryEvent) event);
                if (e != null && repositoryEvents.getAndIncrement() < MAX_REPOSITORY_EVENTS) {
                    record(e);
                }
            } else if (event instanceof ExecutionEvent) {
                ExecutionEvent ee = (ExecutionEvent) event;
                if (ee.getType() == ExecutionEvent.Type.SessionStarted && ee.getSession() != null && snapshots) {
                    pomHashes.putAll(Snapshots.hashes(ee.getSession().getProjects()));
                }
                rememberScopes(ee);
                Map<String, Object> e = execution((ExecutionEvent) event);
                if (e != null) {
                    record(e);
                    Map<String, Object> tests = TestFailures.event((ExecutionEvent) event, startedAt, log);
                    if (tests != null) {
                        record(tests);
                    }
                }
            } else if (event instanceof MavenExecutionResult) {
                finish((MavenExecutionResult) event);
            }
        } catch (Throwable t) {
            // Nie den Build stören – auch nicht bei unbekannten Maven-Versionen (LinkageError)
            log.debug("Maven Detective: Event übersprungen", t);
        }
    }

    /** Ende des Builds: Events, Schnappschuss und Karte je für sich – ein kaputtes Teil kostet nicht die anderen. */
    private void finish(MavenExecutionResult result) {
        try {
            for (Map<String, Object> e : result(result)) {
                record(e);
            }
        } catch (Throwable t) {
            log.debug("Maven Detective: Build-Fehler übersprungen", t);
        }
        SnapshotDiff changes = null;
        try {
            if (snapshots && store != null) {
                changes = Snapshots.record(store, request, result, mavenVersion, projectDirectory, resolvedScopes,
                        resolvedArtifacts, pomHashes, log);
            }
        } catch (Throwable t) {
            log.debug("Maven Detective: Schnappschuss übersprungen", t);
        }
        try {
            if (report && result.hasExceptions()) {
                printDiagnosis(changes);
            }
        } catch (Throwable t) {
            log.debug("Maven Detective: Karte übersprungen", t);
        }
    }

    @Override
    public void close() {
        synchronized (writerLock) {
            closeWriter();
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // egal
            }
            writer = null;
        }
    }

    // ---------------------------------------------------------------- Events

    private Map<String, Object> session(MavenExecutionRequest r) {
        Map<String, Object> e = event(EventLog.TYPE_SESSION);
        e.put("formatVersion", (long) EventLog.FORMAT_VERSION);
        e.put("maven", mavenVersion);
        e.put("java", System.getProperty("java.version"));
        e.put("javaHome", System.getProperty("java.home"));
        e.put("offline", r.isOffline());
        e.put("userSettings", path(r.getUserSettingsFile()));
        e.put("globalSettings", path(r.getGlobalSettingsFile()));
        e.put("localRepository", path(r.getLocalRepositoryPath()));
        File dir = r.getMultiModuleProjectDirectory() != null ? r.getMultiModuleProjectDirectory()
                : r.getBaseDirectory() == null ? null : new File(r.getBaseDirectory());
        projectDirectory = path(dir);
        e.put("projectDirectory", projectDirectory);
        e.put("profiles", new ArrayList<String>(r.getActiveProfiles()));
        e.put("goals", new ArrayList<String>(r.getGoals()));
        return e;
    }

    private static Map<String, Object> repository(RepositoryEvent re) {
        List<Exception> exceptions = re.getExceptions();
        if (exceptions == null || exceptions.isEmpty()) {
            return null;
        }
        Map<String, Object> e = event(EventLog.TYPE_REPOSITORY);
        e.put("event", re.getType().name());
        if (re.getArtifact() != null) {
            e.put("artifact", re.getArtifact().toString());
        } else if (re.getMetadata() != null) {
            e.put("artifact", re.getMetadata().toString());
        }
        ArtifactRepository repo = re.getRepository();
        if (repo instanceof RemoteRepository) {
            e.put("repositoryId", repo.getId());
            e.put("repositoryUrl", ((RemoteRepository) repo).getUrl());
        } else if (repo != null) {
            e.put("repositoryId", repo.getId());
        }
        Exception first = exceptions.get(0);
        String message = message(first);
        e.put("failure", message != null ? message : first == null ? "null" : first.getClass().getName());
        return e;
    }

    /** Merkt sich je Modul, welche Scopes Maven für die gestarteten Mojos aufgelöst hat. */
    private void rememberScopes(ExecutionEvent ee) {
        if (ee.getType() != ExecutionEvent.Type.MojoStarted || ee.getProject() == null || ee.getMojoExecution() == null
                || ee.getMojoExecution().getMojoDescriptor() == null) {
            return;
        }
        List<String> scopes = io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot.scopesResolvedBy(
                ee.getMojoExecution().getMojoDescriptor().getDependencyResolutionRequired());
        if (scopes.isEmpty()) {
            return;
        }
        String key = ee.getProject().getGroupId() + ":" + ee.getProject().getArtifactId();
        java.util.Set<String> set = resolvedScopes.get(key);
        if (set == null) {
            resolvedScopes.putIfAbsent(key, java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>()));
            set = resolvedScopes.get(key);
        }
        set.addAll(scopes);
        // Maven löst vor MojoStarted auf: jetzt enthält getArtifacts() genau die Menge für dieses Mojo
        java.util.Set<org.apache.maven.artifact.Artifact> artifacts = ee.getProject().getArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            synchronized (resolvedArtifacts) {
                java.util.Set<org.apache.maven.artifact.Artifact> known = resolvedArtifacts.get(key);
                if (known == null || artifacts.size() > known.size()) {
                    resolvedArtifacts.put(key, new java.util.LinkedHashSet<org.apache.maven.artifact.Artifact>(artifacts));
                }
            }
        }
    }

    private static Map<String, Object> execution(ExecutionEvent ee) {
        String type;
        switch (ee.getType()) {
            case MojoFailed: type = EventLog.TYPE_MOJO_FAILED; break;
            case ProjectFailed: type = EventLog.TYPE_PROJECT_FAILED; break;
            default: return null;
        }
        Map<String, Object> e = event(type);
        MavenProject p = ee.getProject();
        if (p != null) {
            e.put("project", p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion());
            e.put("pom", path(p.getFile()));
        }
        MojoExecution m = ee.getMojoExecution();
        if (m != null) {
            e.put("mojo", m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getVersion() + ":" + m.getGoal());
            e.put("execution", m.getExecutionId());
        }
        e.put("chain", chain(ee.getException()));
        return e;
    }

    private List<Map<String, Object>> result(MavenExecutionResult result) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Throwable t : result.getExceptions()) {
            try {
                out.add(buildError(t));
            } catch (Throwable broken) {
                log.debug("Maven Detective: Build-Fehler übersprungen", broken);
            }
        }
        return out;
    }

    private static Map<String, Object> buildError(Throwable t) {
        Map<String, Object> e = event(EventLog.TYPE_BUILD_ERROR);
        e.put("chain", chain(t));
        List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
        if (t instanceof ProjectBuildingException) {
            List<ProjectBuildingResult> results = ((ProjectBuildingException) t).getResults();
            if (results != null) {
                for (ProjectBuildingResult pbr : results) {
                    String pom = path(pbr.getPomFile());
                    for (ModelProblem mp : pbr.getProblems()) {
                        ModelProblem.Severity severity = mp.getSeverity();
                        if (severity == ModelProblem.Severity.WARNING) {
                            continue;
                        }
                        Map<String, Object> pm = new LinkedHashMap<String, Object>();
                        String source = mp.getSource();
                        pm.put("source", source != null && source.endsWith(".xml") ? source : pom);
                        pm.put("line", (long) mp.getLineNumber());
                        pm.put("column", (long) mp.getColumnNumber());
                        pm.put("severity", severity == null ? null : severity.name());
                        pm.put("message", mp.getMessage());
                        problems.add(pm);
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            e.put("problems", problems);
        }
        return e;
    }

    private static List<Map<String, Object>> chain(Throwable t) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<Throwable, Boolean>();
        while (t != null && out.size() < MAX_CHAIN && !seen.containsKey(t)) {
            seen.put(t, Boolean.TRUE);
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("class", t.getClass().getName());
            c.put("message", message(t));
            out.add(c);
            try {
                t = t.getCause();
            } catch (Throwable broken) {
                t = null;
            }
        }
        return out;
    }

    /** Meldung einer fremden Ausnahme; {@code null}, wenn {@code getMessage()} selbst scheitert. */
    private static String message(Throwable t) {
        try {
            return t == null ? null : t.getMessage();
        } catch (Throwable broken) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Ausgabe

    private void record(Map<String, Object> event) {
        events.add(event);
        synchronized (writerLock) {
            if (writer == null) {
                return;
            }
        }
        String line = Json.write(event) + "\n";   // außerhalb der Sperre: andere Threads warten nur aufs Schreiben
        synchronized (writerLock) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);   // eine Zeile in einem Aufruf, damit sich Zeilen nie vermischen
                writer.flush();
            } catch (IOException e) {
                log.debug("Maven Detective: Event-Datei nicht schreibbar", e);
                closeWriter();
            }
        }
    }

    private void printDiagnosis(SnapshotDiff changes) {
        List<Map<String, Object>> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<Map<String, Object>>(events);
        }
        BuildFailure failure = EventLog.fromEvents(snapshot);
        Environment env = Environment.forBuild(failure, projectDirectory == null ? null : Paths.get(projectDirectory));
        List<Diagnosis> diagnoses = BuildDoctor.withDefaults().diagnose(failure, env);
        if (diagnoses.isEmpty()) {
            return;
        }
        boolean unicode = Reports.consoleSupportsUnicode();
        log.error("");
        for (String line : Reports.consoleCard(diagnoses, changes)) {
            log.error(unicode ? line : Reports.transliterate(line));
        }
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("type", type);
        return e;
    }

    private static Properties props(Object o) {
        return o instanceof Properties ? (Properties) o : new Properties();
    }

    private static String path(File f) {
        return f == null ? null : f.getAbsolutePath();
    }
}
