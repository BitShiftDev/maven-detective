package io.github.bitshiftdev.mavendetective.core.env;

import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.SessionInfo;

import io.github.bitshiftdev.mavendetective.core.check.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Wo die Prüfungen nachsehen: Projektverzeichnis, lokales Repository, settings.xml, Umgebungsvariablen,
 * JDK des Maven-Laufs. Werte aus dem Build (Events) haben Vorrang vor Annahmen.
 */
public final class Environment {

    private final Path projectDirectory;
    private final Path userHome;
    private final Path localRepository;
    private final Path userSettingsFile;
    private final Path globalSettingsFile;
    private final Map<String, String> env;
    private final String runnerJavaVersion;
    private final Boolean offline;

    private MavenSettings userSettings;
    private MavenSettings globalSettings;
    private boolean settingsLoaded;
    private String settingsError;

    private Environment(Builder b) {
        this.projectDirectory = b.projectDirectory;
        this.userHome = b.userHome;
        this.env = Collections.unmodifiableMap(new HashMap<String, String>(b.env));
        this.userSettingsFile = b.userSettingsFile != null ? b.userSettingsFile
                : userHome == null ? null : userHome.resolve(".m2").resolve("settings.xml");
        this.globalSettingsFile = b.globalSettingsFile;
        this.runnerJavaVersion = b.runnerJavaVersion;
        this.offline = b.offline;
        this.localRepository = b.localRepository;
    }

    /**
     * Umgebung für einen Build: nimmt, was die Events wissen, und ergänzt den Rest aus der aktuellen
     * JVM (Benutzerverzeichnis, Umgebungsvariablen).
     */
    public static Environment forBuild(BuildFailure failure, Path projectDirectory) {
        return forBuild(failure, projectDirectory, null);
    }

    /**
     * Wie {@link #forBuild(BuildFailure, Path)}, aber was der Build selbst nicht nennt (Text-Log), kommt aus
     * {@code setup} – den Einstellungen, mit denen er tatsächlich lief (z. B. Maven-Einstellungen der IDE:
     * eigene settings.xml, lokales Repository, Runner-JDK). {@code setup} darf {@code null} sein.
     */
    public static Environment forBuild(BuildFailure failure, Path projectDirectory, BuildSetup setup) {
        SessionInfo s = failure.getSession();
        Builder b = builder()
                .userHome(Paths.get(System.getProperty("user.home")))
                .env(System.getenv())
                .runnerJavaVersion(s.getJavaVersion())
                .offline(s.getOffline());
        if (projectDirectory != null) {
            b.projectDirectory(projectDirectory);
        } else if (s.getProjectDirectory() != null) {
            b.projectDirectory(Text.localPath(s.getProjectDirectory()));
        }
        // Angaben der Session können aus einem fremden Log stammen: Netzwerkpfade nie öffnen
        if (s.getLocalRepository() != null) {
            b.localRepository(Text.localPath(s.getLocalRepository()));
        }
        if (s.getUserSettings() != null) {
            b.userSettingsFile(Text.localPath(s.getUserSettings()));
        }
        if (s.getGlobalSettings() != null) {
            b.globalSettingsFile(Text.localPath(s.getGlobalSettings()));
        }
        if (setup != null) {
            if (s.getLocalRepository() == null && notEmpty(setup.getLocalRepository())) {
                b.localRepository(Paths.get(setup.getLocalRepository()));
            }
            if (s.getUserSettings() == null && notEmpty(setup.getUserSettings())) {
                b.userSettingsFile(Paths.get(setup.getUserSettings()));
            }
            if (s.getGlobalSettings() == null && notEmpty(setup.getMavenHome())) {
                b.globalSettingsFile(Paths.get(setup.getMavenHome(), "conf", "settings.xml"));
            }
            if (s.getJavaVersion() == null && notEmpty(setup.getJavaVersion())) {
                b.runnerJavaVersion(setup.getJavaVersion());
            }
        }
        return b.build();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getProjectDirectory() { return projectDirectory; }

    public Path getUserHome() { return userHome; }

    /** Lokales Repository: aus dem Build, sonst aus settings.xml, sonst ~/.m2/repository. */
    public Path getLocalRepository() {
        if (localRepository != null) {
            return localRepository;
        }
        String fromSettings = userSettings() == null ? null : userSettings().getLocalRepository();
        if (fromSettings != null && !fromSettings.isEmpty()) {
            return Paths.get(fromSettings.replace("${user.home}", String.valueOf(userHome)));
        }
        return userHome == null ? null : userHome.resolve(".m2").resolve("repository");
    }

    public Path getUserSettingsFile() { return userSettingsFile; }

    public Path getGlobalSettingsFile() { return globalSettingsFile; }

    public String getenv(String name) { return env.get(name); }

    /** Java-Version, mit der Maven lief (z. B. "17.0.9"), oder {@code null}. */
    public String getRunnerJavaVersion() { return runnerJavaVersion; }

    public Boolean getOffline() { return offline; }

    public MavenSettings userSettings() {
        loadSettings();
        return userSettings;
    }

    public MavenSettings globalSettings() {
        loadSettings();
        return globalSettings;
    }

    /** Fehlermeldung, falls eine settings.xml nicht gelesen werden konnte. */
    public String settingsError() {
        loadSettings();
        return settingsError;
    }

    private void loadSettings() {
        if (settingsLoaded) {
            return;
        }
        settingsLoaded = true;
        try {
            userSettings = MavenSettings.read(userSettingsFile);
        } catch (IOException e) {
            settingsError = e.getMessage();
        }
        try {
            globalSettings = MavenSettings.read(globalSettingsFile);
        } catch (IOException e) {
            settingsError = settingsError == null ? e.getMessage() : settingsError;
        }
    }

    public static final class Builder {
        private Path projectDirectory;
        private Path userHome;
        private Path localRepository;
        private Path userSettingsFile;
        private Path globalSettingsFile;
        private Map<String, String> env = new HashMap<String, String>();
        private String runnerJavaVersion;
        private Boolean offline;

        public Builder projectDirectory(Path p) { this.projectDirectory = p; return this; }
        public Builder userHome(Path p) { this.userHome = p; return this; }
        public Builder localRepository(Path p) { this.localRepository = p; return this; }
        public Builder userSettingsFile(Path p) { this.userSettingsFile = p; return this; }
        public Builder globalSettingsFile(Path p) { this.globalSettingsFile = p; return this; }
        public Builder env(Map<String, String> e) { this.env = new HashMap<String, String>(e); return this; }
        public Builder runnerJavaVersion(String v) { this.runnerJavaVersion = v; return this; }
        public Builder offline(Boolean o) { this.offline = o; return this; }

        public Environment build() {
            return new Environment(this);
        }
    }
}
