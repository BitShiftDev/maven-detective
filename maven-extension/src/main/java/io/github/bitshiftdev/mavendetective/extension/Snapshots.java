package io.github.bitshiftdev.mavendetective.extension;

import io.github.bitshiftdev.mavendetective.core.check.Text;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Mirror;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Baut aus einem Maven-Lauf einen {@link BuildSnapshot} und legt ihn ab. */
final class Snapshots {

    private Snapshots() {
    }

    /**
     * Speichert den Lauf als „letzter Lauf“ und, wenn grün, als neue Referenz. Bei einem roten Lauf
     * kommt der Vergleich mit dem letzten grünen zurück, sonst {@code null}. Wirft nie.
     */
    static SnapshotDiff record(SnapshotStore store, MavenExecutionRequest request, MavenExecutionResult result,
                               String mavenVersion, String projectDirectory, Map<String, ? extends Set<String>> resolvedScopes,
                               Map<String, ? extends Set<Artifact>> resolvedArtifacts, Map<String, String> pomHashes,
                               Logger log) {
        try {
            if (projectDirectory == null) {
                return null;
            }
            Path project = Paths.get(projectDirectory);
            BuildSnapshot current = build(request, result, mavenVersion, projectDirectory, resolvedScopes, resolvedArtifacts, pomHashes)
                    .setSuccessful(!result.hasExceptions());
            try {
                store.saveLatest(project, current);
                if (!result.hasExceptions()) {
                    store.saveGreen(project, current);
                }
            } catch (IOException | RuntimeException e) {
                // Ablage schreibgeschützt (CI-Cache) oder voll: der Vergleich mit dem grünen Lauf geht trotzdem
                log.debug("Maven Detective: Schnappschuss nicht gespeichert", e);
            }
            if (!result.hasExceptions()) {
                return null;
            }
            BuildSnapshot green = store.loadGreen(project, BuildSnapshot.ORIGIN_EXTENSION);
            return green == null ? null : SnapshotDiff.compare(green, current);
        } catch (Throwable t) {
            log.debug("Maven Detective: Schnappschuss übersprungen", t);
            return null;
        }
    }

    /**
     * @param resolvedScopes    groupId:artifactId → Scopes, die die gelaufenen Mojos auflösen ließen; fehlt ein Modul,
     *                          gilt „unbekannt“ (wie bisher: alles)
     * @param resolvedArtifacts groupId:artifactId → größte Artifact-Menge, die ein Mojo bekam; fehlt ein Modul, gilt
     *                          {@code getArtifacts()} am Ende (Maven ersetzt sie je Mojo, z. B. test → runtime)
     */
    static BuildSnapshot build(MavenExecutionRequest request, MavenExecutionResult result,
                               String mavenVersion, String projectDirectory, Map<String, ? extends Set<String>> resolvedScopes,
                               Map<String, ? extends Set<Artifact>> resolvedArtifacts) {
        return build(request, result, mavenVersion, projectDirectory, resolvedScopes, resolvedArtifacts, null);
    }

    /** @param pomHashes groupId:artifactId → Hash vor dem ersten Mojo ({@link #hashes}); fehlt einer, zählt das Modell am Ende */
    static BuildSnapshot build(MavenExecutionRequest request, MavenExecutionResult result,
                               String mavenVersion, String projectDirectory, Map<String, ? extends Set<String>> resolvedScopes,
                               Map<String, ? extends Set<Artifact>> resolvedArtifacts, Map<String, String> pomHashes) {
        BuildSnapshot s = new BuildSnapshot()
                .setOrigin(BuildSnapshot.ORIGIN_EXTENSION)
                .setCreatedAt(SnapshotStore.now())
                .setProjectDirectory(projectDirectory)
                .setMavenVersion(mavenVersion)
                .setJavaVersion(System.getProperty("java.version"))
                .setJavaHome(System.getProperty("java.home"));
        if (request != null) {
            s.setOffline(request.isOffline());
            s.setUserSettings(request.getUserSettingsFile() == null ? null : request.getUserSettingsFile().getAbsolutePath());
            s.getActiveProfiles().addAll(request.getActiveProfiles());
            // wie die IDE: abgeschaltete Profile (-P !x) mit "!" – sonst sähen "-P !x" und nichts gleich aus
            if (request.getInactiveProfiles() != null) {
                for (String p : request.getInactiveProfiles()) {
                    s.getActiveProfiles().add("!" + p);
                }
            }
            List<Mirror> mirrors = request.getMirrors();
            if (mirrors != null) {
                for (Mirror m : mirrors) {
                    // Zugangsdaten in der URL (https://user:token@…) gehören nicht in die Ablage, Karte oder Markdown
                    s.getMirrors().put(String.valueOf(m.getId()), Text.maskUserInfo(m.getUrl()) + " (" + m.getMirrorOf() + ")");
                }
            }
        }
        List<MavenProject> projects = result.getTopologicallySortedProjects();
        if (projects == null) {
            return s;
        }
        for (MavenProject p : projects) {
            BuildSnapshot.Module m = s.module(p.getGroupId() + ":" + p.getArtifactId());
            m.setVersion(p.getVersion());
            if (p.getModel() != null && p.getModel().getParent() != null) {
                org.apache.maven.model.Parent parent = p.getModel().getParent();
                m.setParent(parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion());
            }
            String early = pomHashes == null ? null : pomHashes.get(p.getGroupId() + ":" + p.getArtifactId());
            m.setEffectivePomHash(early != null ? early : hash(p.getModel()));
            if (p.getProperties() != null) {
                for (Map.Entry<Object, Object> e : p.getProperties().entrySet()) {
                    m.addPropertyIfVersion(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            if (p.getBuildPlugins() != null) {
                for (Plugin plugin : p.getBuildPlugins()) {
                    if (plugin.getVersion() != null) {
                        m.getPlugins().put(plugin.getGroupId() + ":" + plugin.getArtifactId(), plugin.getVersion());
                    }
                }
            }
            // getArtifacts() ist nur gefüllt, wenn ein Mojo die Auflösung angefordert hat (compile, test, …)
            Set<Artifact> artifacts = p.getArtifacts();
            Set<Artifact> widest = resolvedArtifacts == null ? null : resolvedArtifacts.get(p.getGroupId() + ":" + p.getArtifactId());
            if (widest != null && (artifacts == null || widest.size() > artifacts.size())) {
                artifacts = widest;
            }
            if (artifacts != null && !artifacts.isEmpty()) {
                m.setDependenciesResolved(true);
                Set<String> scopes = resolvedScopes == null ? null : resolvedScopes.get(p.getGroupId() + ":" + p.getArtifactId());
                if (scopes != null) {
                    m.getResolvedScopes().addAll(scopes);
                }
                for (Artifact a : artifacts) {
                    String k = key(a.getGroupId(), a.getArtifactId(), a.getClassifier());
                    m.getDependencies().put(k, a.getBaseVersion());
                    if (a.getScope() != null) {
                        m.getDependencyScopes().put(k, a.getScope());
                        if (scopes != null) {
                            m.getResolvedScopes().add(a.getScope());
                        }
                    }
                }
            } else if (p.getDependencies() != null) {
                for (Dependency d : p.getDependencies()) {
                    if (d.getVersion() != null) {
                        m.getDependencies().put(key(d.getGroupId(), d.getArtifactId(), d.getClassifier()), d.getVersion());
                    }
                }
            }
        }
        return s;
    }

    private static String key(String groupId, String artifactId, String classifier) {
        return groupId + ":" + artifactId + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    /** Hashes der Effective POMs aller Projekte, solange noch kein Mojo Properties gesetzt hat. */
    static Map<String, String> hashes(List<MavenProject> projects) {
        Map<String, String> out = new java.util.HashMap<String, String>();
        if (projects != null) {
            for (MavenProject p : projects) {
                String h = hash(p.getModel());
                if (h != null) {
                    out.put(p.getGroupId() + ":" + p.getArtifactId(), h);
                }
            }
        }
        return out;
    }

    /** SHA-256 der Effective POM, wie Maven sie schreiben würde. */
    private static String hash(Model model) {
        if (model == null) {
            return null;
        }
        try {
            StringWriter w = new StringWriter();
            new MavenXpp3Writer().write(w, model);
            return SnapshotStore.sha256(w.toString()).substring(0, 16);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
