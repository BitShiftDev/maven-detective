package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenExternalParameters;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenHomeType;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.project.MavenWrapper;
import org.jetbrains.idea.maven.project.StaticResolvedMavenHomeType;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adapter-Schicht zu IntelliJs Maven-Plugin. Alles, was dessen (teils interne) API anfasst, steht
 * hier – ändert sich die API mit einer IDE-Version, ist nur diese Klasse betroffen. Jede Methode
 * fängt Fehler ab und liefert dann weniger statt gar nichts.
 */
final class IdeMaven {

    private static final Logger LOG = Logger.getInstance(IdeMaven.class);

    private IdeMaven() {
    }

    /** Schnappschuss aus dem Maven-Modell der IDE (Stand des letzten Imports) und den Runner-Einstellungen. */
    static BuildSnapshot snapshot(@NotNull Project project, @Nullable MavenRunConfiguration cfg) {
        BuildSetup setup = ideSetup(project, cfg);
        BuildSnapshot s = new BuildSnapshot()
                .setOrigin(BuildSnapshot.ORIGIN_IDE)
                .setCreatedAt(SnapshotStore.now())
                .setProjectDirectory(project.getBasePath())
                .setMavenVersion(setup.getMavenVersion())
                .setJavaVersion(setup.getJavaVersion())
                .setJavaHome(setup.getJavaHome())
                .setUserSettings(setup.getUserSettings());
        s.getActiveProfiles().addAll(setup.getProfiles());
        try {
            MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
            s.setOffline(manager.getGeneralSettings().isWorkOffline());
            List<MavenProject> projects = ReadAction.compute(manager::getProjects);
            for (MavenProject p : projects) {
                MavenId id = p.getMavenId();
                BuildSnapshot.Module m = s.module(id.getGroupId() + ":" + id.getArtifactId()).setVersion(id.getVersion());
                MavenId parent = p.getParentId();
                if (parent != null) {
                    m.setParent(parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion());
                }
                for (Map.Entry<Object, Object> e : p.getProperties().entrySet()) {
                    m.addPropertyIfVersion(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                for (MavenPlugin plugin : p.getPlugins()) {
                    if (plugin.getVersion() != null) {
                        m.getPlugins().put(plugin.getGroupId() + ":" + plugin.getArtifactId(), plugin.getVersion());
                    }
                }
                // IntelliJ hält die vollständig (transitiv) aufgelöste Liste
                List<MavenArtifact> deps = p.getDependencies();
                m.setDependenciesResolved(!deps.isEmpty());
                for (MavenArtifact a : deps) {
                    String classifier = a.getClassifier();
                    m.getDependencies().put(a.getGroupId() + ":" + a.getArtifactId()
                            + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier), a.getVersion());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Maven-Modell nicht lesbar", e);
        }
        return s;
    }

    /** Womit die IDE baut: Maven aus den Einstellungen, JDK und VM-Optionen des Runners, Profile. */
    static BuildSetup ideSetup(@NotNull Project project, @Nullable MavenRunConfiguration cfg) {
        BuildSetup s = new BuildSetup("IDE");
        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        MavenGeneralSettings general = cfg != null && cfg.getGeneralSettings() != null ? cfg.getGeneralSettings() : manager.getGeneralSettings();
        MavenRunnerSettings runner = cfg != null && cfg.getRunnerSettings() != null ? cfg.getRunnerSettings()
                : MavenRunner.getInstance(project).getSettings();
        try {
            MavenHomeType home = general.getMavenHomeType();
            s.setMavenVersion(MavenUtil.getMavenVersion(project));
            if (home instanceof MavenWrapper) {
                s.setMavenSource("Wrapper (./mvnw)");
            } else if (home instanceof StaticResolvedMavenHomeType resolved) {
                Path path = MavenUtil.getMavenHomePath(resolved);
                String version = path == null ? null : BuildSetup.mavenVersionOf(path);
                if (version != null) {
                    s.setMavenVersion(version);
                }
                s.setMavenSource(home.getClass().getSimpleName().startsWith("Bundled") ? "gebündelt (IntelliJ)" : String.valueOf(path));
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: Maven-Version der IDE nicht ermittelbar", e);
        }
        try {
            Sdk jdk = MavenExternalParameters.getJdk(project, runner.getJreName(), cfg == null);
            if (jdk != null && jdk.getHomePath() != null) {
                s.setJavaHome(jdk.getHomePath());
                String v = BuildSetup.javaVersionOf(Paths.get(jdk.getHomePath()));
                s.setJavaVersion(v != null ? v : jdk.getVersionString());
            }
        } catch (ExecutionException | RuntimeException e) {
            s.getNotes().add("Runner-JDK nicht auflösbar: " + e.getMessage());
        }
        String settings = general.getUserSettingsFile();
        if (settings != null && !settings.isBlank()) {
            s.setUserSettings(settings.trim());
        } else {
            Path def = Paths.get(System.getProperty("user.home"), ".m2", "settings.xml");
            if (Files.isRegularFile(def)) {
                s.setUserSettings(def.toString());
            }
        }
        String repo = general.getLocalRepository();
        if (repo != null && !repo.isBlank()) {
            s.setLocalRepository(repo.trim());
        }
        s.getProfiles().addAll(profiles(manager, cfg));
        // .mvn/maven.config und .mvn/jvm.config liest Maven selbst – in IDE und Terminal gleich
        BuildSetup files = cliSetup(project);
        s.setMavenConfig(files.getMavenConfig());
        s.setJvmConfig(files.getJvmConfig());
        String vm = runner.getVmOptions();
        s.setMavenOpts(vm == null || vm.isBlank() ? null : vm.trim());
        return s;
    }

    /** Was {@code ./mvnw} bzw. {@code mvn} im Terminal nähme (Umgebung der IDE als Näherung für die Shell). */
    static BuildSetup cliSetup(@NotNull Project project) {
        String base = project.getBasePath();
        return BuildSetup.detectCommandLine(base == null ? null : Paths.get(base),
                Paths.get(System.getProperty("user.home")), System.getenv());
    }

    /** Version, mit der Maven ein Plugin aufgelöst hat (Effective POM), oder {@code null}. */
    static @Nullable String resolvedPluginVersion(@NotNull Project project, @NotNull String groupId, @NotNull String artifactId) {
        try {
            for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
                for (MavenPlugin plugin : p.getPlugins()) {
                    if (artifactId.equals(plugin.getArtifactId()) && groupId.equals(plugin.getGroupId()) && plugin.getVersion() != null) {
                        return plugin.getVersion();
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Plugin-Version nicht ermittelbar", e);
        }
        return null;
    }

    private static List<String> profiles(MavenProjectsManager manager, @Nullable MavenRunConfiguration cfg) {
        List<String> out = new ArrayList<>();
        if (cfg != null) {
            for (Map.Entry<String, Boolean> e : cfg.getRunnerParameters().getProfilesMap().entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
        Collection<String> enabled = manager.getExplicitProfiles().getEnabledProfiles();
        out.addAll(enabled);
        return out;
    }
}
