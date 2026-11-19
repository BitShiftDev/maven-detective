package io.github.bitshiftdev.mavendetective.core.testkit;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Maven selbst als Vergleichspartner für Differenztests (Testplan Welle 3): das effektive Modell aus
 * Mavens Model-Builder und der aufgelöste Baum aus dem Collector von Maven Resolver – beides offline,
 * nur aus dem Projekt und einem lokalen Repository im {@code @TempDir}. Eingestellt wie Maven 3.9 auf der
 * Kommandozeile ({@code MavenRepositorySystemUtils.newSession()}: {@code ClassicDependencyManager},
 * Scope-/Optional-/Exclusion-Selektoren, {@code ConflictResolver} mit nearest wins).
 */
public final class MavenReference {

    private MavenReference() {
    }

    /** Effektives Modell der POM ({@code mvn help:effective-pom} ohne Profile aus Settings und ohne Plugins). */
    public static Model effectiveModel(Path pom, Path repository) {
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pom.toFile());
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(false);
        request.setSystemProperties(new Properties());
        request.setUserProperties(new Properties());
        request.setModelResolver(new LocalModelResolver(repository));
        try {
            return new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new IllegalStateException("Maven kann die POM nicht bauen: " + e.getMessage(), e);
        }
    }

    /**
     * Aufgelöster Baum des Moduls (nur Gewinner, wie {@code dependency:tree} ohne {@code -Dverbose}): eine Zeile
     * je Knoten in Vorordnung, je Tiefe zwei Leerzeichen eingerückt, Form {@code g:a:ext[:classifier]:version:scope}.
     */
    public static List<String> tree(Path pom, Path repository) {
        return tree(pom, repository, false);
    }

    /**
     * Wie {@link #tree(Path, Path)}; {@code verbose} behält wie {@code dependency:tree -Dverbose} die Verlierer
     * (Zeile endet auf {@code  (omitted for g:a:version)}) und nennt verwaltete Versionen und Scopes – zum Untersuchen
     * einer Abweichung.
     */
    public static List<String> tree(Path pom, Path repository, boolean verbose) {
        Model model = effectiveModel(pom, repository);
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                throw new IllegalStateException(type + " / " + impl, exception);
            }
        });
        RepositorySystem system = locator.getService(RepositorySystem.class);
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setOffline(true);
        session.setConfigProperty(org.eclipse.aether.util.graph.transformer.ConflictResolver.CONFIG_PROP_VERBOSE, verbose);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,
                new LocalRepository(repository.toFile(), "simple")));

        CollectRequest request = new CollectRequest();
        // wie das Projekt-Artifact in Maven: Endung aus dem Packaging (ein Rückweg zum Modul ist dann ein Zyklus)
        ArtifactType packaging = session.getArtifactTypeRegistry().get(model.getPackaging());
        String extension = packaging == null ? model.getPackaging() : packaging.getExtension();
        request.setRootArtifact(new DefaultArtifact(model.getGroupId(), model.getArtifactId(), extension, model.getVersion()));
        for (Dependency d : model.getDependencies()) {
            request.addDependency(convert(session, d));
        }
        if (model.getDependencyManagement() != null) {
            for (Dependency d : model.getDependencyManagement().getDependencies()) {
                request.addManagedDependency(convert(session, d));
            }
        }
        try {
            DependencyNode root = system.collectDependencies(session, request).getRoot();
            List<String> out = new ArrayList<String>();
            for (DependencyNode child : root.getChildren()) {
                print(child, 0, verbose, out);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Maven Resolver kann den Baum nicht sammeln: " + e.getMessage(), e);
        }
    }

    private static void print(DependencyNode node, int depth, boolean verbose, List<String> out) {
        org.eclipse.aether.artifact.Artifact a = node.getArtifact();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(a.getGroupId()).append(':').append(a.getArtifactId()).append(':').append(a.getExtension());
        if (!a.getClassifier().isEmpty()) {
            sb.append(':').append(a.getClassifier());
        }
        sb.append(':').append(a.getVersion()).append(':').append(node.getDependency().getScope());
        Object winner = node.getData().get(org.eclipse.aether.util.graph.transformer.ConflictResolver.NODE_DATA_WINNER);
        if (winner instanceof DependencyNode) {
            sb.append(" (omitted for ").append(((DependencyNode) winner).getArtifact()).append(')');
        }
        if (verbose && node.getManagedBits() != 0) {
            sb.append(" [managed ").append(node.getManagedBits()).append(" from ")
                    .append(org.eclipse.aether.util.graph.manager.DependencyManagerUtils.getPremanagedVersion(node)).append('/')
                    .append(org.eclipse.aether.util.graph.manager.DependencyManagerUtils.getPremanagedScope(node)).append(']');
        }
        out.add(sb.toString());
        for (DependencyNode child : node.getChildren()) {
            print(child, depth + 1, verbose, out);
        }
    }

    /** Wie {@code RepositoryUtils.toDependency} in maven-core. */
    private static org.eclipse.aether.graph.Dependency convert(DefaultRepositorySystemSession session, Dependency d) {
        ArtifactType type = session.getArtifactTypeRegistry().get(d.getType());
        if (type == null) {
            type = new org.eclipse.aether.artifact.DefaultArtifactType(d.getType());
        }
        DefaultArtifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), null,
                d.getVersion(), type);
        List<Exclusion> exclusions = new ArrayList<Exclusion>();
        for (org.apache.maven.model.Exclusion e : d.getExclusions()) {
            exclusions.add(new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        Boolean optional = d.getOptional() == null ? null : Boolean.valueOf(d.isOptional());
        return new org.eclipse.aether.graph.Dependency(artifact, d.getScope(), optional, exclusions);
    }

    /** Parents und BOMs nur aus dem lokalen Repository; nie das Netz. */
    private static final class LocalModelResolver implements ModelResolver {
        private final Path repository;

        LocalModelResolver(Path repository) {
            this.repository = repository;
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
            Path dir = repository;
            for (String segment : groupId.split("\\.")) {
                dir = dir.resolve(segment);
            }
            File file = dir.resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".pom").toFile();
            if (!file.isFile()) {
                throw new UnresolvableModelException("nicht im lokalen Repository: " + file, groupId, artifactId, version);
            }
            return new FileModelSource(file);
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
        }

        @Override
        public ModelResolver newCopy() {
            return this;
        }
    }
}
