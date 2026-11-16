package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
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
    /** Reactor-POMs nach {@code groupId:artifactId}; lazy, siehe {@link #reactor(String, String)}. */
    private Map<String, List<ReactorPom>> reactorIndex;
    /** POMs, deren Inhalt nur im Speicher geändert ist (Upgrade-Simulation); gehen vor die Platte. */
    private final Map<Path, PomFile> overrides;
    /** Wenn gesetzt: jede gelesene POM und jede Datei eines benutzten Modells (Kette, BOMs) landet hier. */
    private Set<Path> touched;

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
        this.reactor = new ProjectPoms(projectRoot, this.cancellation, shared);
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
        // gelesene, unveränderte POMs weiterverwenden (BOMs mit Tausenden Einträgen nicht neu parsen);
        // effektive Modelle nicht – sie können von einer geänderten Datei abhängen
        for (Map.Entry<Path, PomFile> e : base.cache.entrySet()) {
            if (!overrides.containsKey(e.getKey())) {
                cache.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Ein Locator, der dieselben POMs findet, für die angegebenen Dateien aber den übergebenen Text
     * liest statt der Platte – „was wäre, wenn die POM so aussähe?“ (Modul A6). Gelesene POMs werden
     * übernommen, effektive Modelle nicht, damit keines der alten Fassung übrig bleibt.
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
                throw new IllegalArgumentException(Texts.t("pom.invalid.after.change", key, ex.getMessage()), ex);
            }
        }
        return new PomLocator(this, Collections.unmodifiableMap(parsed));
    }

    public Path getLocalRepository() { return localRepository; }

    /**
     * Ab jetzt jede POM merken, die gelesen oder über ein Modell (Parent-Kette, BOM-Importe) benutzt wird;
     * {@code null} beendet das. Damit weiß die Upgrade-Simulation, welche Module eine Änderung überhaupt sehen.
     * Nicht thread-sicher (wie der ganze Locator).
     */
    public void recordTouched(Set<Path> sink) {
        this.touched = sink;
    }

    private void touch(EffectiveModel m, Set<EffectiveModel> seen) {
        if (!seen.add(m)) {
            return;
        }
        for (PomFile p : m.getChain()) {
            touched.add(p.getFile());
        }
        for (EffectiveModel.ImportStep step : m.getImports()) {
            if (step.bom != null) {
                touched.add(step.bom.getFile());
                EffectiveModel bom = models.get(step.bom.getFile());
                if (bom != null) {
                    touch(bom, seen);
                }
            }
        }
    }

    /** Alle lesbaren POMs des Reactors (über den Cache). */
    public List<PomFile> reactorPoms() {
        List<PomFile> out = new ArrayList<PomFile>();
        for (ProjectPoms.Pom p : reactor.all()) {
            PomFile f = load(p.file);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

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
        if (touched != null) {
            touched.add(key);
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
        if (touched != null) {
            touch(m, new java.util.HashSet<EffectiveModel>());
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

    /**
     * POM zu Koordinaten: erst Reactor, dann lokales Repository. Reactor-POMs mit Ausdruck in der Version
     * (CI-friendly {@code ${revision}}) werden im eigenen Modell interpoliert; bleibt dort oder in der verlangten
     * Version ein {@code ${…}} offen, reicht {@code groupId:artifactId} (wie Mavens Reactor).
     */
    public PomFile byCoordinates(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        boolean open = version.contains("${");
        for (ReactorPom r : reactor(groupId, artifactId)) {
            String v = reactorVersion(r);
            if (open || version.equals(v) || v == null || v.contains("${")) {
                PomFile f = load(r.pom.file);
                if (f != null) {
                    return f;
                }
            }
        }
        if (localRepository != null && !open) {
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

    /** Eine POM des Reactors mit (einmal) interpolierter Version. */
    private static final class ReactorPom {
        final ProjectPoms.Pom pom;
        String version;
        boolean resolved;
        boolean resolving;

        ReactorPom(ProjectPoms.Pom pom) {
            this.pom = pom;
        }
    }

    private List<ReactorPom> reactor(String groupId, String artifactId) {
        if (reactorIndex == null) {
            Map<String, List<ReactorPom>> index = new HashMap<String, List<ReactorPom>>();
            for (ProjectPoms.Pom p : reactor.all()) {
                if (p.artifactId == null || p.effectiveGroupId() == null) {
                    continue;
                }
                String key = p.effectiveGroupId() + ":" + p.artifactId;
                List<ReactorPom> list = index.get(key);
                if (list == null) {
                    list = new ArrayList<ReactorPom>(1);
                    index.put(key, list);
                }
                list.add(new ReactorPom(p));
            }
            reactorIndex = index;
        }
        List<ReactorPom> found = reactorIndex.get(groupId + ":" + artifactId);
        return found == null ? Collections.<ReactorPom>emptyList() : found;
    }

    /**
     * Version einer Reactor-POM; {@code ${revision}} usw. im Modell der POM selbst aufgelöst. Während das Modell
     * gebaut wird (Parent im Reactor über Koordinaten), gilt die rohe Version – dann vergleicht der Aufrufer nur g:a.
     */
    private String reactorVersion(ReactorPom r) {
        if (r.resolved || r.resolving) {
            return r.resolved ? r.version : r.pom.effectiveVersion();
        }
        String v = r.pom.effectiveVersion();
        if (v != null && v.contains("${")) {
            r.resolving = true;
            try {
                PomFile f = load(r.pom.file);
                if (f != null) {
                    String interpolated = model(f).interpolate(v).value;
                    if (interpolated != null) {
                        v = interpolated;
                    }
                }
            } finally {
                r.resolving = false;
            }
        }
        r.version = v;
        r.resolved = true;
        return v;
    }

    private static boolean matches(PomFile p, String groupId, String artifactId, String version) {
        if (!artifactId.equals(p.getArtifactId()) || groupId != null && !groupId.equals(p.getGroupId())) {
            return false;
        }
        return version == null || version.contains("${") || p.getVersion() == null || version.equals(p.getVersion());
    }
}
