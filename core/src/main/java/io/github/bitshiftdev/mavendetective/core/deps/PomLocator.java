package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Findet POMs wie Maven: Parent über {@code relativePath}, sonst im Reactor, sonst im lokalen
 * Repository. Geht nie ins Netz – fehlt eine POM lokal, steht das in {@link #getMissing()}.
 */
public final class PomLocator {

    private final ProjectPoms reactor;
    private final Path localRepository;
    private final PomCache shared;
    private final Map<Path, PomFile> cache = new HashMap<Path, PomFile>();
    private final Map<Path, EffectiveModel> models = new HashMap<Path, EffectiveModel>();
    private final Set<String> missing = new LinkedHashSet<String>();

    /**
     * @param projectRoot     Wurzel des Reactors (darf {@code null} sein)
     * @param localRepository lokales Repository, meist {@code ~/.m2/repository}
     */
    public PomLocator(Path projectRoot, Path localRepository) {
        this(projectRoot, localRepository, null);
    }

    /** Wie oben, liest POMs aber über einen langlebigen {@link PomCache} (IDE). */
    public PomLocator(Path projectRoot, Path localRepository, PomCache shared) {
        this.reactor = new ProjectPoms(projectRoot);
        this.localRepository = localRepository;
        this.shared = shared;
    }

    public Path getLocalRepository() { return localRepository; }

    /** Koordinaten, deren POM weder im Projekt noch im lokalen Repository lag. */
    public List<String> getMissing() {
        return Collections.unmodifiableList(new ArrayList<String>(missing));
    }

    /** Liest eine POM-Datei (gecacht); {@code null}, wenn sie fehlt oder kaputt ist. */
    public PomFile load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (Files.isDirectory(key)) {
            key = key.resolve("pom.xml");
        }
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        PomFile pom = null;
        if (shared != null) {
            pom = shared.load(key);
        } else if (Files.isRegularFile(key)) {
            try {
                pom = PomFile.read(key);
            } catch (Exception e) {
                pom = null;
            }
        }
        cache.put(key, pom);
        return pom;
    }

    /** Effektives Modell (Vererbung + BOM-Importe) einer POM, gecacht pro Datei. */
    public EffectiveModel model(PomFile pom) {
        EffectiveModel m = models.get(pom.getFile());
        if (m == null) {
            m = new EffectiveModel(pom, this);
            models.put(pom.getFile(), m);
            m.build();
        }
        return m;
    }

    /** Parent-POM; {@code null}, wenn es keinen gibt oder er lokal nicht zu finden ist. */
    public PomFile parentOf(PomFile child) {
        if (!child.hasParent()) {
            return null;
        }
        String rel = child.getParentRelativePath();
        if (rel == null || !rel.isEmpty()) {
            Path candidate = child.getFile().getParent().resolve(rel == null ? "../pom.xml" : rel);
            PomFile p = load(candidate);
            if (p != null && matches(p, child.getParentGroupId(), child.getParentArtifactId(), child.getParentVersion())) {
                return p;
            }
        }
        return byCoordinates(child.getParentGroupId(), child.getParentArtifactId(), child.getParentVersion());
    }

    /** POM zu Koordinaten: erst Reactor, dann lokales Repository. */
    public PomFile byCoordinates(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        for (ProjectPoms.Pom p : reactor.all()) {
            if (artifactId.equals(p.artifactId) && groupId.equals(p.effectiveGroupId()) && version.equals(p.effectiveVersion())) {
                PomFile f = load(p.file);
                if (f != null) {
                    return f;
                }
            }
        }
        if (localRepository != null && !version.contains("${")) {
            Path dir = localRepository;
            for (String segment : groupId.split("\\.")) {
                dir = dir.resolve(segment);
            }
            PomFile f = load(dir.resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".pom"));
            if (f != null) {
                return f;
            }
        }
        missing.add(groupId + ":" + artifactId + ":" + version);
        return null;
    }

    private static boolean matches(PomFile p, String groupId, String artifactId, String version) {
        if (!artifactId.equals(p.getArtifactId()) || groupId != null && !groupId.equals(p.getGroupId())) {
            return false;
        }
        return version == null || version.contains("${") || p.getVersion() == null || version.equals(p.getVersion());
    }
}
