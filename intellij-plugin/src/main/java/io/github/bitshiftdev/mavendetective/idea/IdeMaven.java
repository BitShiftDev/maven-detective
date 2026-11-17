package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.ide.projectView.impl.nodes.NamedLibraryElement;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.DeclaredVersions;
import io.github.bitshiftdev.mavendetective.core.snapshot.RunArguments;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.idea.maven.execution.MavenExternalParameters;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.model.MavenArtifactState;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenHomeType;
import org.jetbrains.idea.maven.project.MavenImportListener;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.project.MavenWrapper;
import org.jetbrains.idea.maven.project.StaticResolvedMavenHomeType;
import org.jetbrains.idea.maven.utils.MavenDataKeys;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.idea.maven.utils.actions.MavenActionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adapter-Schicht zu IntelliJs Maven-Plugin. Alles, was dessen (teils interne) API anfasst, steht
 * hier – ändert sich die API mit einer IDE-Version, ist nur diese Klasse betroffen. Jede Methode
 * fängt Fehler ab und liefert dann weniger statt gar nichts.
 */
final class IdeMaven {

    private static final Logger LOG = Logger.getInstance(IdeMaven.class);

    /**
     * Nur für Plattform-Tests: Maven-Modell ohne echten Import. Ein Test-Projekt kennt kein Maven-Projekt
     * (ein Import bräuchte Netz und einen Maven-Server); gesetzt, liefern {@link #pom}, {@link #reactorRoot}
     * und {@link #localRepository} diese Werte, und {@link #reimport} merkt sich die Dateien, statt zu importieren.
     *
     * @param pom        POM jedes Moduls
     * @param reimported hier landen die Dateien jedes angestoßenen Imports
     */
    record TestModel(@NotNull Path root, @NotNull Path localRepository, @NotNull Path pom, @NotNull List<VirtualFile> reimported) {
    }

    private static volatile @Nullable TestModel testModel;

    private IdeMaven() {
    }

    /** Ersetzt das Maven-Modell, bis {@code parent} entsorgt wird. */
    @TestOnly
    static void useForTests(@NotNull TestModel model, @NotNull Disposable parent) {
        testModel = model;
        Disposer.register(parent, () -> testModel = null);
    }

    /**
     * Lesezugriff auf das Modell. {@code ReadAction.compute} ist ab 2026.2 veraltet, der Ersatz
     * {@code ReadAction.computeBlocking} fehlt aber in 2025.3 (since-build 253);
     * {@code Application.runReadAction} gibt es in beiden und ist nicht veraltet.
     */
    static <T> T read(@NotNull Supplier<T> action) {
        return ApplicationManager.getApplication().runReadAction((Computable<T>) action::get);
    }

    /**
     * Schnappschuss aus dem Maven-Modell der IDE (Stand des letzten Imports) und den Runner-Einstellungen.
     * Profile und {@code -D} stammen aus der Run-Konfiguration; weil das Modell aus dem Import stammt, stehen dessen
     * Profile als {@code importProfiles} dabei, und je Modul die Versionen, die die POMs unter den Profilen des Laufs
     * direkt deklarieren.
     */
    // ------------------------------------------------------------------ Läufe

    private static @Nullable MavenRunConfiguration maven(@Nullable RunConfiguration run) {
        return run instanceof MavenRunConfiguration m ? m : null;
    }

    /** Ist das ein Maven-Lauf (Run-Konfiguration des Maven-Plugins)? */
    static boolean isMavenRun(@Nullable ExecutionEnvironment env) {
        return env != null && env.getRunProfile() instanceof MavenRunConfiguration;
    }

    /** Run-Konfiguration eines Maven-Laufs, sonst {@code null}. */
    static @Nullable RunConfiguration mavenConfiguration(@Nullable ExecutionEnvironment env) {
        return env != null && env.getRunProfile() instanceof MavenRunConfiguration m ? m : null;
    }

    /** Hat die Run-Konfiguration eigene Runner-Einstellungen (eigenes JDK)? */
    static boolean hasOwnRunnerSettings(@Nullable RunConfiguration run) {
        MavenRunConfiguration cfg = maven(run);
        return cfg != null && cfg.getRunnerSettings() != null;
    }

    /**
     * Startet eine temporäre Kopie der Maven-Run-Konfiguration mit zusätzlichen Kommandozeilen-Optionen (z. B.
     * {@code -U}); die gespeicherte Konfiguration bleibt unverändert. {@code false}, wenn es kein Maven-Lauf ist.
     */
    static boolean runWithExtraArgs(@NotNull Project project, @Nullable RunConfiguration run, @NotNull String args) {
        MavenRunConfiguration cfg = maven(run);
        if (cfg == null) {
            return false;
        }
        MavenRunnerParameters params = cfg.getRunnerParameters().clone();
        String old = params.getCmdOptions();
        if (!args.isBlank()) {
            params.setCmdOptions(old == null || old.isBlank() ? args.trim() : old.trim() + " " + args.trim());
        }
        org.jetbrains.idea.maven.execution.MavenRunConfigurationType.runConfiguration(project, params, cfg.getGeneralSettings(),
                cfg.getRunnerSettings(), null);
        return true;
    }

    static BuildSnapshot snapshot(@NotNull Project project, @Nullable RunConfiguration configuration) {
        MavenRunConfiguration cfg = maven(configuration);
        BuildSetup setup = ideSetup(project, cfg);
        RunArguments run = runArguments(project, cfg);
        BuildSnapshot s = new BuildSnapshot()
                .setOrigin(BuildSnapshot.ORIGIN_IDE)
                .setCreatedAt(SnapshotStore.now())
                .setProjectDirectory(project.getBasePath())
                .setMavenVersion(setup.getMavenVersion())
                .setJavaVersion(setup.getJavaVersion())
                .setJavaHome(setup.getJavaHome())
                .setUserSettings(setup.getUserSettings());
        s.getActiveProfiles().addAll(run.profileList());
        s.setRunProperties(run.getProperties());
        try {
            MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
            s.setImportProfiles(importArguments(manager).profileList());
            s.setOffline(manager.getGeneralSettings().isWorkOffline());
            List<MavenProject> projects = read(manager::getProjects);
            List<PomFile> poms = new ArrayList<>();
            for (MavenProject p : projects) {
                try {
                    poms.add(PomFile.read(p.getFile().toNioPath()));
                } catch (Exception e) {
                    LOG.info("Maven Detective: POM für den Schnappschuss nicht lesbar: " + p.getFile().getPath() + " – " + e.getMessage());
                }
            }
            Map<String, Map<String, String>> declared = DeclaredVersions.of(poms, run);
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
                Map<String, String> own = declared.get(id.getGroupId() + ":" + id.getArtifactId());
                if (own != null) {
                    m.getDeclaredDependencies().putAll(own);
                }
                // IntelliJ hält die vollständig (transitiv) aufgelöste Liste – Stand des Imports, nicht des Laufs
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
    static BuildSetup ideSetup(@NotNull Project project, @Nullable RunConfiguration run) {
        MavenRunConfiguration cfg = maven(run);
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
                s.setMavenSource(home.getClass().getSimpleName().startsWith("Bundled") ? MavenDetectiveBundle.message("setup.maven.bundled") : String.valueOf(path));
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
            s.getNotes().add(MavenDetectiveBundle.message("setup.runner.jdk.unresolved", e.getMessage()));
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
        s.getProfiles().addAll(cfg == null ? importArguments(manager).profileList() : runArguments(project, cfg).profileList());
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
        TestModel t = testModel;
        if (t != null) {
            return t.pom();
        }
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
        TestModel t = testModel;
        if (t != null) {
            return t.root();
        }
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
        TestModel t = testModel;
        if (t != null) {
            return t.localRepository();
        }
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

    /**
     * {@code onFinished} läuft nach jedem fertigen Maven-Import (auch nach einem Quick-Fix), bis
     * {@code parent} entsorgt wird. Der Thread ist nicht festgelegt – der Aufrufer wechselt selbst auf den EDT.
     */
    static void onImport(@NotNull Project project, @NotNull Disposable parent, @NotNull Runnable onFinished) {
        try {
            project.getMessageBus().connect(parent).subscribe(MavenImportListener.TOPIC, new MavenImportListener() {
                @Override
                public void importFinished(@NotNull Collection<MavenProject> importedProjects, @NotNull List<Module> newModules) {
                    onFinished.run();
                }
            });
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: Maven-Import nicht beobachtbar – Ergebnisse veralten still", e);
        }
    }

    /** Eine Dependency, die im Maven-Tool-Window oder unter <i>External Libraries</i> gewählt ist. */
    record SelectedDependency(@NotNull Path pom, @NotNull String groupId, @NotNull String artifactId, @Nullable String version) {
    }

    /**
     * Gewählte Dependency im Kontext einer Aktion: im Maven-Tool-Window ({@code MAVEN_DEPENDENCIES} +
     * Modul des Knotens) oder unter <i>External Libraries</i> (Bibliothek „Maven: g:a:v“ eines Moduls;
     * ohne Modul das erste, in dessen Baum sie liegt). {@code null}, wenn keine gewählt ist.
     */
    static @Nullable SelectedDependency selectedDependency(@NotNull Project project, @NotNull DataContext context) {
        try {
            Collection<MavenArtifact> artifacts = context.getData(MavenDataKeys.MAVEN_DEPENDENCIES);
            if (artifacts != null && !artifacts.isEmpty()) {
                MavenArtifact a = artifacts.iterator().next();
                MavenProject p = MavenActionUtil.getMavenProject(context);
                if (p != null) {
                    return new SelectedDependency(Paths.get(p.getPath()), a.getGroupId(), a.getArtifactId(), a.getVersion());
                }
            }
            NamedLibraryElement[] libraries = context.getData(NamedLibraryElement.ARRAY_DATA_KEY);
            if (libraries != null && libraries.length == 1) {
                String[] gav = libraryCoordinates(libraries[0].getName());
                if (gav == null) {
                    return null;
                }
                Module module = libraries[0].getModule();
                Path pom = module == null ? null : read(() -> pom(module));
                if (pom == null) {
                    pom = moduleWith(project, gav[0], gav[1]);
                }
                return pom == null ? null : new SelectedDependency(pom, gav[0], gav[1], gav[2]);
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: gewählte Dependency nicht ermittelbar", e);
        }
        return null;
    }

    /**
     * {@code groupId}, {@code artifactId}, {@code version} aus dem Namen einer Maven-Bibliothek
     * („Maven: g:a:v“, „Maven: g:a:type:classifier:v“); {@code null} bei anderen Bibliotheken.
     */
    static String @Nullable [] libraryCoordinates(@Nullable String name) {
        String prefix = "Maven: ";
        if (name == null || !name.startsWith(prefix)) {
            return null;
        }
        String[] parts = name.substring(prefix.length()).trim().split(":");
        if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return new String[] {parts[0], parts[1], parts[parts.length - 1]};
    }

    /** POM des ersten Moduls, in dessen Baum {@code g:a} im Classpath liegt. */
    private static @Nullable Path moduleWith(@NotNull Project project, @NotNull String groupId, @NotNull String artifactId) {
        return read(() -> {
            for (MavenProject p : MavenProjectsManager.getInstance(project).getProjects()) {
                for (MavenArtifact a : p.getDependencies()) {
                    if (groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId())) {
                        return Paths.get(p.getPath());
                    }
                }
            }
            return null;
        });
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

    /**
     * Nach einer Änderung an einzelnen POMs nur die betroffenen Maven-Projekte neu lesen: die geänderten und alle,
     * die (auch über mehrere Ebenen) von ihnen erben. Ein voller Import über Hunderte Module dauerte Minuten und
     * ließe danach jede Analyse veralten. Kennt die IDE eine Datei nicht, doch alles.
     */
    static void reimport(@NotNull Project project, @NotNull Collection<VirtualFile> changed) {
        TestModel t = testModel;
        if (t != null) {
            t.reimported().addAll(changed);
            return;
        }
        try {
            MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
            Set<MavenProject> touched = new LinkedHashSet<>();
            for (VirtualFile f : changed) {
                MavenProject mp = manager.findProject(f);
                if (mp == null) {
                    reimport(project);
                    return;
                }
                touched.add(mp);
            }
            List<MavenProject> update = new ArrayList<>(touched);
            for (MavenProject mp : manager.getProjects()) {
                if (touched.contains(mp)) {
                    continue;
                }
                MavenProject p = mp;
                for (int depth = 0; depth < 20 && p != null && p.getParentId() != null; depth++) {
                    p = manager.findProject(p.getParentId());
                    if (p != null && touched.contains(p)) {
                        update.add(mp);
                        break;
                    }
                }
            }
            manager.scheduleForceUpdateMavenProjects(update);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Maven Detective: gezielter Maven-Import nicht möglich, importiere alles", e);
            reimport(project);
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

    /**
     * Profile und {@code -D} eines Laufs: Profil-Liste der Run-Konfiguration, dazu {@code -P}/{@code -D} in den Goals
     * und Kommandozeilen-Optionen (z. B. Goals {@code verify -Pgreen}) und die Properties/„Skip tests“ des Runners.
     * Ohne Run-Konfiguration: Profile des Imports.
     */
    static RunArguments runArguments(@NotNull Project project, @Nullable RunConfiguration run) {
        MavenRunConfiguration cfg = maven(run);
        if (cfg == null) {
            return importArguments(MavenProjectsManager.getInstance(project));
        }
        RunArguments a = new RunArguments();
        try {
            MavenRunnerParameters params = cfg.getRunnerParameters();
            for (Map.Entry<String, Boolean> e : params.getProfilesMap().entrySet()) {
                a.profile(e.getKey(), Boolean.TRUE.equals(e.getValue()));
            }
            a.parse(params.getGoals());
            a.parse(params.getCmdOptions());
            MavenRunnerSettings runner = cfg.getRunnerSettings() != null ? cfg.getRunnerSettings()
                    : MavenRunner.getInstance(project).getSettings();
            Map<String, String> props = runner.getMavenProperties();
            if (props != null) {
                props.forEach(a::property);
            }
            if (runner.isSkipTests()) {
                a.property("skipTests", "true");
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Argumente der Run-Konfiguration nicht lesbar", e);
        }
        return a;
    }

    /** Profile, mit denen die IDE das Projekt importiert hat (Häkchen im Maven-Tool-Window). */
    private static RunArguments importArguments(MavenProjectsManager manager) {
        RunArguments a = new RunArguments();
        try {
            Collection<String> enabled = manager.getExplicitProfiles().getEnabledProfiles();
            Collection<String> disabled = manager.getExplicitProfiles().getDisabledProfiles();
            enabled.forEach(p -> a.profile(p, true));
            disabled.forEach(p -> a.profile(p, false));
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Profile des Imports nicht lesbar", e);
        }
        return a;
    }
}
