package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.env.MavenSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Was alle Prüfungen eines Preflights brauchen, einmal gelesen. */
final class PreflightContext {

    private static final int MAX_MODULES = 500;
    private static final Pattern LOCAL_REPO_ARG = Pattern.compile("-Dmaven\\.repo\\.local=(\\S+)");

    final Path project;
    final BuildSetup setup;
    final Preflight.Options options;

    private ProjectPoms projectPoms;
    private PomLocator locator;
    private List<PomFile> poms;
    private List<ProjectPoms.Pom> modulePoms;
    private final Map<String, List<String>> code = new HashMap<String, List<String>>();
    private boolean settingsLoaded;
    private MavenSettings userSettings;
    private MavenSettings globalSettings;
    private String userSettingsError;
    private String globalSettingsError;
    private int userSettingsErrorLine = 1;
    private int globalSettingsErrorLine = 1;
    private boolean modelsBuilt;

    PreflightContext(Path project, BuildSetup setup, Preflight.Options options) {
        this.project = project;
        this.setup = setup;
        this.options = options;
    }

    // ---------------------------------------------------------------- settings.xml

    /** settings.xml des Benutzers: aus dem Setup ({@code -s}, IDE), sonst {@code ~/.m2/settings.xml}. */
    Path userSettingsFile() {
        if (setup.getUserSettings() != null) {
            return Paths.get(setup.getUserSettings());
        }
        return options.userHome == null ? null : options.userHome.resolve(".m2").resolve("settings.xml");
    }

    /** {@code conf/settings.xml} der Maven-Installation, sofern bekannt. */
    Path globalSettingsFile() {
        return setup.getMavenHome() == null ? null : Paths.get(setup.getMavenHome()).resolve("conf").resolve("settings.xml");
    }

    MavenSettings userSettings() {
        loadSettings();
        return userSettings;
    }

    MavenSettings globalSettings() {
        loadSettings();
        return globalSettings;
    }

    String userSettingsError() {
        loadSettings();
        return userSettingsError;
    }

    String globalSettingsError() {
        loadSettings();
        return globalSettingsError;
    }

    int userSettingsErrorLine() {
        loadSettings();
        return userSettingsErrorLine;
    }

    int globalSettingsErrorLine() {
        loadSettings();
        return globalSettingsErrorLine;
    }

    private static int line(IOException e) {
        Throwable c = e.getCause();
        return c instanceof org.xml.sax.SAXParseException ? Math.max(1, ((org.xml.sax.SAXParseException) c).getLineNumber()) : 1;
    }

    private void loadSettings() {
        if (settingsLoaded) {
            return;
        }
        settingsLoaded = true;
        try {
            userSettings = MavenSettings.read(userSettingsFile(), options.env, options.userHome);
        } catch (IOException e) {
            userSettingsError = e.getMessage();
            userSettingsErrorLine = line(e);
        }
        try {
            globalSettings = MavenSettings.read(globalSettingsFile(), options.env, options.userHome);
        } catch (IOException e) {
            globalSettingsError = e.getMessage();
            globalSettingsErrorLine = line(e);
        }
    }

    /** Settings in Mavens Reihenfolge (Benutzer vor global); ohne kaputte/fehlende. */
    List<MavenSettings> settings() {
        List<MavenSettings> out = new ArrayList<MavenSettings>();
        if (userSettings() != null) {
            out.add(userSettings());
        }
        if (globalSettings() != null) {
            out.add(globalSettings());
        }
        return out;
    }

    /** Offline über {@code -o} in {@code .mvn/maven.config} oder {@code <offline>} in settings.xml. */
    boolean offline() {
        return offlineByConfig() || offlineBySettings() != null;
    }

    boolean offlineByConfig() {
        String config = setup.getMavenConfig();
        if (config == null) {
            return false;
        }
        for (String arg : config.split("\\s+")) {
            if ("-o".equals(arg) || "--offline".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** settings.xml mit {@code <offline>true</offline>}, oder {@code null}. */
    MavenSettings offlineBySettings() {
        for (MavenSettings s : settings()) {
            if (Boolean.TRUE.equals(s.getOffline())) {
                return s;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Projekt

    Path rootPom() {
        return project.resolve("pom.xml");
    }

    boolean hasRootPom() {
        return Files.isRegularFile(rootPom());
    }

    Path localRepository() {
        if (setup.getLocalRepository() != null) {
            return Paths.get(setup.getLocalRepository());
        }
        if (setup.getMavenConfig() != null) {
            Matcher m = LOCAL_REPO_ARG.matcher(setup.getMavenConfig());
            if (m.find()) {
                return project.resolve(m.group(1)).normalize();
            }
        }
        for (MavenSettings s : settings()) {
            String repo = s.getLocalRepository();
            if (repo != null && !repo.isEmpty()) {
                // relativ: wie Maven gegen das Verzeichnis, in dem der Build läuft (Projekt), nicht gegen diese JVM
                return project.resolve(repo).normalize();
            }
        }
        return options.userHome == null ? null : options.userHome.resolve(".m2").resolve("repository");
    }

    ProjectPoms projectPoms() {
        if (projectPoms == null) {
            projectPoms = new ProjectPoms(project, options.cancellation);
        }
        return projectPoms;
    }

    PomLocator locator() {
        if (locator == null) {
            locator = new PomLocator(project, localRepository(), null, options.cancellation);
        }
        return locator;
    }

    /**
     * POMs des Reactors: die Wurzel und rekursiv ihre {@code <modules>} (ohne Profile), Wurzel zuerst. Andere
     * {@code pom.xml} unterhalb des Projekts (Beispiele, Testdaten) zählen nicht. Unlesbare POMs stehen drin, damit der
     * Preflight sie meldet, ihre Module aber nicht.
     */
    List<ProjectPoms.Pom> modulePoms() {
        if (modulePoms == null) {
            modulePoms = new ArrayList<ProjectPoms.Pom>();
            if (hasRootPom()) {
                Set<Path> seen = new HashSet<Path>();
                Deque<Path> queue = new ArrayDeque<Path>();
                queue.add(rootPom().toAbsolutePath().normalize());
                while (!queue.isEmpty() && modulePoms.size() < MAX_MODULES) {
                    options.cancellation.check();
                    Path file = queue.poll();
                    if (!seen.add(file)) {
                        continue;
                    }
                    ProjectPoms.Pom pom = projectPoms().read(file);
                    if (pom == null) {
                        continue;
                    }
                    modulePoms.add(pom);
                    PomFile f = pom.parseable ? locator().load(file) : null;
                    if (f == null) {
                        continue;
                    }
                    for (String module : f.getModules()) {
                        Path target = file.getParent().resolve(module.trim()).normalize();
                        queue.add(Files.isDirectory(target) ? target.resolve("pom.xml") : target);
                    }
                }
            }
        }
        return modulePoms;
    }

    /** Zeilen der POM ohne Kommentare, auf Wunsch auch ohne {@code <profiles>} (siehe {@link ProjectPoms#codeLines}). */
    List<String> code(ProjectPoms.Pom pom, boolean withoutProfiles) {
        String key = pom.file + (withoutProfiles ? "|p" : "|");
        List<String> lines = code.get(key);
        if (lines == null) {
            lines = ProjectPoms.codeLines(pom.lines, withoutProfiles);
            code.put(key, lines);
        }
        return lines;
    }

    /** Stellen in Reactor-POMs, die den Text außerhalb von Kommentaren und Profilen enthalten. */
    List<ProjectPoms.Location> findText(String needle) {
        List<ProjectPoms.Location> out = new ArrayList<ProjectPoms.Location>();
        for (ProjectPoms.Pom pom : modulePoms()) {
            List<String> lines = code(pom, true);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    out.add(new ProjectPoms.Location(pom, i + 1));
                }
            }
        }
        return out;
    }

    /** Lesbare POMs des Reactors, Wurzel zuerst. */
    List<PomFile> poms() {
        if (poms == null) {
            poms = new ArrayList<PomFile>();
            for (ProjectPoms.Pom p : modulePoms()) {
                PomFile f = p.parseable ? locator().load(p.file) : null;
                if (f != null) {
                    poms.add(f);
                }
            }
        }
        return poms;
    }

    /** Effektives Modell; baut beim ersten Aufruf alle Modelle, damit {@link PomLocator#getMissing()} vollständig ist. */
    EffectiveModel model(PomFile pom) {
        return locator().model(pom);
    }

    /** Parents und BOM-Importe, die weder im Projekt noch im lokalen Repository liegen. */
    List<String> missingPoms() {
        if (!modelsBuilt) {
            modelsBuilt = true;
            for (PomFile p : poms()) {
                options.cancellation.check();
                model(p);
            }
        }
        return locator().getMissing();
    }
}
