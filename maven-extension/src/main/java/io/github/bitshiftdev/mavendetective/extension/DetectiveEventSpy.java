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

    private final Logger log = LoggerFactory.getLogger("maven-detective");
    private final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
    private Writer writer;
    private boolean report = true;
    private boolean snapshots = true;
    private MavenExecutionRequest request;
    private SnapshotStore store;
    private String mavenVersion;
    private String projectDirectory;
    private int repositoryEvents;

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
                writer = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8);
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
                if (e != null && repositoryEvents++ < MAX_REPOSITORY_EVENTS) {
                    record(e);
                }
            } else if (event instanceof ExecutionEvent) {
                Map<String, Object> e = execution((ExecutionEvent) event);
                if (e != null) {
                    record(e);
                }
            } else if (event instanceof MavenExecutionResult) {
                MavenExecutionResult result = (MavenExecutionResult) event;
                for (Map<String, Object> e : result(result)) {
                    record(e);
                }
                SnapshotDiff changes = snapshots && store != null
                        ? Snapshots.record(store, request, result, mavenVersion, projectDirectory, log) : null;
                if (report && result.hasExceptions()) {
                    printDiagnosis(changes);
                }
            }
        } catch (Throwable t) {
            // Nie den Build stören – auch nicht bei unbekannten Maven-Versionen (LinkageError)
            log.debug("Maven Detective: Event übersprungen", t);
        }
    }

    @Override
    public void close() {
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
        e.put("failure", String.valueOf(exceptions.get(0).getMessage()));
        return e;
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

    private static List<Map<String, Object>> result(MavenExecutionResult result) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Throwable t : result.getExceptions()) {
            Map<String, Object> e = event(EventLog.TYPE_BUILD_ERROR);
            e.put("chain", chain(t));
            List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
            if (t instanceof ProjectBuildingException) {
                List<ProjectBuildingResult> results = ((ProjectBuildingException) t).getResults();
                if (results != null) {
                    for (ProjectBuildingResult pbr : results) {
                        String pom = path(pbr.getPomFile());
                        for (ModelProblem mp : pbr.getProblems()) {
                            if (mp.getSeverity() == ModelProblem.Severity.WARNING) {
                                continue;
                            }
                            Map<String, Object> pm = new LinkedHashMap<String, Object>();
                            String source = mp.getSource();
                            pm.put("source", source != null && source.endsWith(".xml") ? source : pom);
                            pm.put("line", (long) mp.getLineNumber());
                            pm.put("column", (long) mp.getColumnNumber());
                            pm.put("severity", mp.getSeverity().name());
                            pm.put("message", mp.getMessage());
                            problems.add(pm);
                        }
                    }
                }
            }
            if (!problems.isEmpty()) {
                e.put("problems", problems);
            }
            out.add(e);
        }
        return out;
    }

    private static List<Map<String, Object>> chain(Throwable t) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<Throwable, Boolean>();
        while (t != null && out.size() < MAX_CHAIN && !seen.containsKey(t)) {
            seen.put(t, Boolean.TRUE);
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("class", t.getClass().getName());
            c.put("message", t.getMessage());
            out.add(c);
            t = t.getCause();
        }
        return out;
    }

    // ---------------------------------------------------------------- Ausgabe

    private void record(Map<String, Object> event) {
        events.add(event);
        if (writer != null) {
            try {
                writer.write(Json.write(event));
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                log.debug("Maven Detective: Event-Datei nicht schreibbar", e);
                writer = null;
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
