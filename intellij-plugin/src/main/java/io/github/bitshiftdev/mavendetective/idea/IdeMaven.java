package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
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
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.model.MavenArtifactState;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adapter-Schicht zu IntelliJs Maven-Plugin. Alles, was dessen (teils interne) API anfasst, steht
 * hier – ändert sich die API mit einer IDE-Version, ist nur diese Klasse betroffen. Jede Methode
 * fängt Fehler ab und liefert dann weniger statt gar nichts.
 */
final class IdeMaven {

    private static final Logger LOG = Logger.getInstance(IdeMaven.class);

    private IdeMaven() {
    }

    /**
     * Einzige Stelle mit {@code ReadAction.compute}: ab 2026.2 veraltet, der Ersatz
     * {@code ReadAction.computeBlocking} fehlt aber in 2025.3 (since-build 253). Wird since-build
     * angehoben, nur hier umstellen.
     */
    @SuppressWarnings("deprecation")
    static <T> T read(@NotNull Supplier<T> action) {
        return ReadAction.compute(action::get);
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
            List<MavenProject> projects = read(manager::getProjects);
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
        } catch (ProcessCanceledException e) {
            throw e;
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
        } catch (ProcessCanceledException e) {
            throw e;
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
        } catch (ProcessCanceledException e) {
            throw e;
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
        // Effektives Repository aus dem letzten Import (öffentliche API); MavenGeneralSettings.getLocalRepository() ist intern
        Path repo = localRepository(project);
        if (repo != null) {
            s.setLocalRepository(repo.toString());
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

    /**
     * Maven-Installation, mit der die IDE baut; {@code null} beim Wrapper (dann nimmt der Aufrufer
     * {@code ./mvnw}) oder wenn sie nicht ermittelbar ist.
     */
    static @Nullable Path mavenHome(@NotNull Project project) {
        try {
            MavenHomeType home = MavenProjectsManager.getInstance(project).getGeneralSettings().getMavenHomeType();
            if (home instanceof StaticResolvedMavenHomeType resolved) {
                return MavenUtil.getMavenHomePath(resolved);
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: Maven-Installation der IDE nicht ermittelbar", e);
        }
        return null;
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
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Plugin-Version nicht ermittelbar", e);
        }
        return null;
    }

    /** Ein Maven-Modul aus dem IDE-Modell: POM-Datei und Anzeigename. */
    record ModuleRef(@NotNull Path pom, @NotNull String groupId, @NotNull String artifactId, @NotNull String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Alle Maven-Module, die IntelliJ importiert hat. */
    static List<ModuleRef> modules(@NotNull Project project) {
        List<ModuleRef> out = new ArrayList<>();
        try {
            for (MavenProject p : read(() -> MavenProjectsManager.getInstance(project).getProjects())) {
                MavenId id = p.getMavenId();
                if (id.getArtifactId() == null) {
                    continue;
                }
                out.add(new ModuleRef(Paths.get(p.getPath()), String.valueOf(id.getGroupId()), id.getArtifactId(),
                        p.getDisplayName() + " (" + id.getGroupId() + ":" + id.getArtifactId() + ")"));
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Module nicht lesbar", e);
        }
        return out;
    }

    /** IntelliJ-Modul zu einer Maven-POM; {@code null}, wenn nicht importiert. */
    static @Nullable Module module(@NotNull Project project, @NotNull Path pom) {
        try {
            return read(() -> {
                MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
                for (MavenProject p : manager.getProjects()) {
                    if (Paths.get(p.getPath()).equals(pom)) {
                        return manager.findModule(p);
                    }
                }
                return null;
            });
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Modul zu " + pom + " nicht ermittelbar", e);
            return null;
        }
    }

    /** POM eines IntelliJ-Moduls; {@code null}, wenn es kein Maven-Modul ist. Aufruf in einer Read-Action. */
    static @Nullable Path pom(@NotNull Module module) {
        MavenProject p = MavenProjectsManager.getInstance(module.getProject()).findProject(module);
        return p == null ? null : Paths.get(p.getPath());
    }

    /** {@code groupId:artifactId} eines IntelliJ-Moduls; {@code null}, wenn es kein Maven-Modul ist. Aufruf in einer Read-Action. */
    static @Nullable String ga(@NotNull Module module) {
        MavenProject p = MavenProjectsManager.getInstance(module.getProject()).findProject(module);
        return p == null ? null : p.getMavenId().getGroupId() + ":" + p.getMavenId().getArtifactId();
    }

    /** Wurzel des Reactors (Verzeichnis der obersten POM), sonst das Projektverzeichnis. */
    static @Nullable Path reactorRoot(@NotNull Project project) {
        try {
            List<MavenProject> roots = read(() -> MavenProjectsManager.getInstance(project).getRootProjects());
            if (!roots.isEmpty()) {
                return Paths.get(roots.get(0).getDirectoryFile().getPath());
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Reactor-Wurzel nicht ermittelbar", e);
        }
        return project.getBasePath() == null ? null : Paths.get(project.getBasePath());
    }

    /** Lokales Repository laut IDE-Modell; {@code null}, wenn unbekannt. */
    static @Nullable Path localRepository(@NotNull Project project) {
        try {
            for (MavenProject p : read(() -> MavenProjectsManager.getInstance(project).getProjects())) {
                Path repo = p.getLocalRepositoryPath();
                if (repo != null) {
                    return repo;
                }
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: lokales Repository nicht ermittelbar", e);
        }
        return null;
    }

    /**
     * Aufgelöster Dependency-Baum eines Moduls aus dem IDE-Modell (Stand des letzten Imports), als
     * Kern-Modell. {@code null}, wenn das Modul unbekannt ist.
     */
    static @Nullable DepNode dependencyTree(@NotNull Project project, @NotNull Path pom) {
        try {
            return read(() -> {
                for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
                    if (!Paths.get(p.getPath()).equals(pom)) {
                        continue;
                    }
                    MavenId id = p.getMavenId();
                    DepNode root = new DepNode(id.getGroupId(), id.getArtifactId(), p.getPackaging(), null, id.getVersion(), null);
                    for (MavenArtifactNode n : p.getDependencyTree()) {
                        convert(root, n, 0);
                    }
                    return root;
                }
                return null;
            });
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Dependency-Baum nicht lesbar", e);
            return null;
        }
    }

    /** Bäume aller Module (Stand des letzten Imports), je {@code groupId:artifactId} des Moduls. */
    static Map<String, DepNode> dependencyTrees(@NotNull Project project) {
        Map<String, DepNode> out = new LinkedHashMap<>();
        try {
            read(() -> {
                for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
                    MavenId id = p.getMavenId();
                    List<MavenArtifactNode> nodes = p.getDependencyTree();
                    if (id.getArtifactId() == null || nodes.isEmpty() && !p.getDependencies().isEmpty()) {
                        continue; // nicht aufgelöst: lieber ohne Baum (POM-Fallback) als mit leerem
                    }
                    DepNode root = new DepNode(id.getGroupId(), id.getArtifactId(), p.getPackaging(), null, id.getVersion(), null);
                    for (MavenArtifactNode n : nodes) {
                        convert(root, n, 0);
                    }
                    out.put(root.ga(), root);
                }
                return null;
            });
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Dependency-Bäume nicht lesbar", e);
        }
        return out;
    }

    /** Maven-Projekte neu importieren, z. B. nach einer Änderung an der POM durch einen Quick-Fix. */
    static void reimport(@NotNull Project project) {
        try {
            MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: Maven-Import nicht anstoßbar", e);
        }
    }

    private static void convert(DepNode parent, MavenArtifactNode n, int depth) {
        MavenArtifact a = n.getArtifact();
        DepNode node = parent.add(new DepNode(a.getGroupId(), a.getArtifactId(), a.getType(), a.getClassifier(),
                a.getVersion(), a.getScope()));
        node.setPremanagedVersion(n.getPremanagedVersion()).setPremanagedScope(n.getPremanagedScope());
        MavenArtifactState state = n.getState();
        if (state == MavenArtifactState.CONFLICT) {
            node.setState(DepNode.State.CONFLICT);
            if (n.getRelatedArtifact() != null) {
                node.setRelatedVersion(n.getRelatedArtifact().getVersion());
            }
        } else if (state == MavenArtifactState.DUPLICATE) {
            node.setState(DepNode.State.DUPLICATE);
        } else if (state == MavenArtifactState.EXCLUDED) {
            node.setState(DepNode.State.EXCLUDED);
        } else if (state == MavenArtifactState.CYCLE) {
            node.setState(DepNode.State.CYCLE);
        }
        if (depth < 40) {
            for (MavenArtifactNode child : n.getDependencies()) {
                convert(node, child, depth + 1);
            }
        }
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
