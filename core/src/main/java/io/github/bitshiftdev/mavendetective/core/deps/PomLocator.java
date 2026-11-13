package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
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
    private final Cancellation cancellation;
    private final Path localRepository;
    private final PomCache shared;
    private final Map<Path, PomFile> cache = new HashMap<Path, PomFile>();
    private final Map<Path, EffectiveModel> models = new HashMap<Path, EffectiveModel>();
    private final Set<String> missing = new LinkedHashSet<String>();
    /** POMs, deren Inhalt nur im Speicher geändert ist (Upgrade-Simulation); gehen vor die Platte. */
    private final Map<Path, PomFile> overrides;

    /**
     * @param projectRoot     Wurzel des Reactors (darf {@code null} sein)
     * @param localRepository lokales Repository, meist {@code ~/.m2/repository}
     */
    public PomLocator(Path projectRoot, Path localRepository) {
        this(projectRoot, localRepository, null);
    }

    /** Wie oben, liest POMs aber über einen langlebigen {@link PomCache} (IDE). */
    public PomLocator(Path projectRoot, Path localRepository, PomCache shared) {
        this(projectRoot, localRepository, shared, Cancellation.NONE);
    }

    /**
     * Wie oben; {@code cancellation} wird vor jedem Dateizugriff geprüft, damit eine IDE-Inspection
     * abbrechen kann (die Exception des Aufrufers geht unverändert durch).
     */
    public PomLocator(Path projectRoot, Path localRepository, PomCache shared, Cancellation cancellation) {
        this.cancellation = cancellation == null ? Cancellation.NONE : cancellation;
        this.reactor = new ProjectPoms(projectRoot, this.cancellation);
        this.localRepository = localRepository;
        this.shared = shared;
        this.overrides = Collections.emptyMap();
    }

    private PomLocator(PomLocator base, Map<Path, PomFile> overrides) {
        this.cancellation = base.cancellation;
        this.reactor = base.reactor;
        this.localRepository = base.localRepository;
        this.shared = base.shared;
        this.overrides = overrides;
    }

    /**
     * Ein Locator, der dieselben POMs findet, für die angegebenen Dateien aber den übergebenen Text
     * liest statt der Platte – „was wäre, wenn die POM so aussähe?“ (Modul A6). Der eigene Cache
     * startet leer, damit kein effektives Modell der alten Fassung übrig bleibt.
     *
     * @throws IllegalArgumentException wenn ein Text kein gültiges XML ist
     */
    public PomLocator withOverrides(Map<Path, String> contents) {
        Map<Path, PomFile> parsed = new HashMap<Path, PomFile>(overrides);
        for (Map.Entry<Path, String> e : contents.entrySet()) {
            Path key = e.getKey().toAbsolutePath().normalize();
            try {
                parsed.put(key, PomFile.parse(key, e.getValue()));
            } catch (Exception ex) {
                throw new IllegalArgumentException(key + " wäre nach der Änderung kein gültiges XML: " + ex.getMessage(), ex);
            }
        }
        return new PomLocator(this, Collections.unmodifiableMap(parsed));
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
        if (overrides.containsKey(key)) {
            cache.put(key, overrides.get(key));
            return overrides.get(key);
        }
        // vor dem try/catch(Exception) unten, sonst würde ein Abbruch geschluckt
        cancellation.check();
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
