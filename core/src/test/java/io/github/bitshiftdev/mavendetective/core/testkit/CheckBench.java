package io.github.bitshiftdev.mavendetective.core.testkit;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.check.Check;
import io.github.bitshiftdev.mavendetective.core.check.CheckContext;
import io.github.bitshiftdev.mavendetective.core.check.CheckResult;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;
import io.github.bitshiftdev.mavendetective.core.model.PomProblem;
import io.github.bitshiftdev.mavendetective.core.model.RepositoryRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prüfstand für eine einzelne {@link Check}: baut Home, lokales Repository und Projekt in einem
 * Testverzeichnis, sammelt Logzeilen, Variablen und Ereignisse und ruft die Prüfung direkt auf –
 * ohne Regelkatalog und ohne {@code BuildDoctor}.
 *
 * <pre>
 * CheckResult r = CheckBench.in(tmp)
 *         .projectFile("pom.xml", xml)
 *         .line("[ERROR] …")
 *         .var("artifact", "g:a:jar:1.0")
 *         .run(new ResolutionChecks.ReactorSibling());
 * </pre>
 */
public final class CheckBench {

    private final Path home;
    private final Path repository;
    private final Path project;
    private final BuildFailure failure = new BuildFailure(BuildFailure.Source.LOG);
    private final List<String> lines = new ArrayList<String>();
    private final Map<String, String> vars = new LinkedHashMap<String, String>();
    private final Map<String, String> envVars = new HashMap<String, String>();
    private Boolean offline;
    private String runner;
    private boolean withProject = true;
    private Cancellation cancellation = Cancellation.NONE;
    private Path globalSettings;

    private CheckBench(Path tmp) {
        this.home = tmp.resolve("home");
        this.repository = home.resolve(".m2").resolve("repository");
        this.project = tmp.resolve("project");
        try {
            Files.createDirectories(repository);
            Files.createDirectories(project);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Neuer Prüfstand unter {@code tmp} (meist ein {@code @TempDir}). */
    public static CheckBench in(Path tmp) {
        return new CheckBench(tmp);
    }

    public Path home() { return home; }

    public Path repository() { return repository; }

    public Path project() { return project; }

    /** Fehlerzeilen, wie sie der Parser liefert (Präfix {@code [ERROR]} bleibt stehen, wenn angegeben). */
    public CheckBench line(String... text) {
        for (String t : text) {
            lines.add(t);
            ErrorRecord e = ErrorRecord.ofText(t);
            failure.getErrors().add(e);
        }
        return this;
    }

    /** Variable, die sonst die Regex der Regel liefern würde. */
    public CheckBench var(String name, String value) {
        vars.put(name, value);
        return this;
    }

    /** Gescheiterte Repository-Anfrage aus den Events. */
    public CheckBench failedRequest(String artifact, String repoId, String repoUrl, String message) {
        failure.getRepositoryRequests().add(new RepositoryRequest("ARTIFACT_RESOLVED", artifact, repoId, repoUrl, message));
        return this;
    }

    /** Strukturiertes POM-Problem aus den Events (Maven meldet es mit Datei, Zeile und Spalte). */
    public CheckBench pomProblem(String source, int line, String message) {
        ErrorRecord e = ErrorRecord.ofText(message);
        e.getProblems().add(new PomProblem(source, line, 1, "ERROR", message));
        failure.getErrors().add(e);
        return this;
    }

    /** Fehler aus einem Mojo, z. B. {@code org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test}. */
    public CheckBench mojoError(String mojo, String text) {
        ErrorRecord e = ErrorRecord.ofText(text);
        e.setMojo(mojo);
        failure.getErrors().add(e);
        lines.add(text);
        return this;
    }

    public CheckBench javaHome(String path) {
        failure.getSession().setJavaHome(path);
        return this;
    }

    public CheckBench env(String name, String value) {
        envVars.put(name, value);
        return this;
    }

    public CheckBench offline() {
        this.offline = Boolean.TRUE;
        return this;
    }

    /** Java-Version, mit der Maven lief, z. B. {@code "17.0.9"}. */
    public CheckBench runner(String javaVersion) {
        this.runner = javaVersion;
        return this;
    }

    /** Ohne Projektverzeichnis, wie bei einem Log, das nicht zum offenen Projekt gehört. */
    public CheckBench withoutProject() {
        this.withProject = false;
        return this;
    }

    public CheckBench cancellation(Cancellation c) {
        this.cancellation = c;
        return this;
    }

    /** Schreibt eine Datei relativ zum Projekt und gibt ihren Pfad zurück. */
    public Path projectFile(String relative, String content) {
        return write(project.resolve(relative), content);
    }

    /** Schreibt eine Datei relativ zum Home (z. B. {@code .m2/settings.xml}). */
    public Path homeFile(String relative, String content) {
        return write(home.resolve(relative), content);
    }

    /** Schreibt eine Datei relativ zum lokalen Repository. */
    public Path repositoryFile(String relative, byte[] content) {
        Path file = repository.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** Wie {@link #projectFile}, aber die Rückgabe ist der Prüfstand – für Ketten. */
    public CheckBench withProjectFile(String relative, String content) {
        projectFile(relative, content);
        return this;
    }

    /** Wie {@link #homeFile}, aber die Rückgabe ist der Prüfstand – für Ketten. */
    public CheckBench withHomeFile(String relative, String content) {
        homeFile(relative, content);
        return this;
    }

    /** Schreibt die globale settings.xml (sonst {@code <maven.home>/conf/settings.xml}) und gibt ihren Pfad zurück. */
    public Path globalSettings(String content) {
        globalSettings = write(home.getParent().resolve("maven/conf/settings.xml"), content);
        return globalSettings;
    }

    public Environment environment() {
        return Environment.builder().userHome(home).projectDirectory(withProject ? project : null)
                .globalSettingsFile(globalSettings).env(envVars).offline(offline).runnerJavaVersion(runner).build();
    }

    public CheckContext context() {
        return new CheckContext(failure, environment(), vars, lines,
                new ProjectPoms(withProject ? project : null, cancellation), cancellation);
    }

    public CheckResult run(Check check) {
        return check.run(context());
    }

    private static Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
