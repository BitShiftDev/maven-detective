package io.github.bitshiftdev.mavendetective.core.deps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Das, was Maven aus einer POM macht – beschränkt auf das, was eine Version bestimmt:
 * Parent-Kette, Properties, geerbte Dependencies und {@code dependencyManagement} inklusive
 * importierter BOMs. Jede Angabe behält ihre Herkunft (Datei und Zeile).
 *
 * <p>Die Regeln folgen Mavens Model-Builder: Erst wird vererbt (das Kind gewinnt), dann
 * interpoliert, und zwar im Kontext des <em>Kindes</em> – auch Einträge aus dem Parent. Danach
 * werden BOMs importiert; ihre Einträge sind im Kontext des BOMs interpoliert und kommen nur
 * dazu, wenn der Schlüssel noch frei ist (der erste Import gewinnt).
 */
public final class EffectiveModel {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_CHAIN = 20;
    private static final int MAX_INTERPOLATION = 10;

    /** Woher ein Property-Wert stammt. {@code shadowed} sind gleichnamige Definitionen weiter oben in der Kette. */
    public static final class PropertyOrigin {
        public final String name;
        public final String value;
        /** {@code null} bei eingebauten Ausdrücken wie {@code project.version}. */
        public final PomFile pom;
        public final int line;
        public final List<PropertyOrigin> shadowed = new ArrayList<PropertyOrigin>();

        PropertyOrigin(String name, String value, PomFile pom, int line) {
            this.name = name;
            this.value = value;
            this.pom = pom;
            this.line = line;
        }
    }

    /** Ergebnis einer Interpolation: Wert (ggf. mit Resten von {@code ${…}}) und benutzte Properties. */
    public static final class Interpolated {
        public final String raw;
        public final String value;
        public final List<PropertyOrigin> used;
        public final List<String> unresolved;

        Interpolated(String raw, String value, List<PropertyOrigin> used, List<String> unresolved) {
            this.raw = raw;
            this.value = value;
            this.used = used;
            this.unresolved = unresolved;
        }

        public boolean isResolved() {
            return unresolved.isEmpty();
        }
    }

    /** Ein BOM-Import auf dem Weg zu einem verwalteten Eintrag. */
    public static final class ImportStep {
        /** Die {@code <dependency>} mit {@code scope=import}. */
        public final PomFile.Dep declaration;
        /** POM, in der die Import-Zeile steht (Modul, Parent oder ein anderes BOM). */
        public final PomFile declaredIn;
        /** Version des BOMs, interpoliert im Kontext des importierenden Modells. */
        public final Interpolated version;
        /** Das importierte BOM; {@code null}, wenn es lokal fehlt. */
        public final PomFile bom;

        ImportStep(PomFile.Dep declaration, PomFile declaredIn, Interpolated version, PomFile bom) {
            this.declaration = declaration;
            this.declaredIn = declaredIn;
            this.version = version;
            this.bom = bom;
        }
    }

    /** Ein Eintrag in {@code dependencyManagement} mit Herkunft. */
    public static final class Managed {
        public final PomFile.Dep dep;
        public final PomFile declaredIn;
        /** Modell, in dessen Kontext die Version interpoliert wird. */
        public final EffectiveModel context;
        /** Import-Kette vom betrachteten Modul bis zum BOM, das den Eintrag enthält (leer = geerbt/direkt). */
        public final List<ImportStep> via;

        Managed(PomFile.Dep dep, PomFile declaredIn, EffectiveModel context, List<ImportStep> via) {
            this.dep = dep;
            this.declaredIn = declaredIn;
            this.context = context;
            this.via = via;
        }

        public Interpolated version() {
            return context.interpolate(dep.getVersion());
        }
    }

    /** Eine (ggf. geerbte) {@code <dependency>} des Moduls. */
    public static final class Declared {
        public final PomFile.Dep dep;
        public final PomFile declaredIn;

        Declared(PomFile.Dep dep, PomFile declaredIn) {
            this.dep = dep;
            this.declaredIn = declaredIn;
        }
    }

    private final PomFile pom;
    private final PomLocator locator;
    private final List<PomFile> chain = new ArrayList<PomFile>();
    private final Map<String, Managed> managed = new LinkedHashMap<String, Managed>();
    private final Map<String, Declared> dependencies = new LinkedHashMap<String, Declared>();
    private final List<ImportStep> imports = new ArrayList<ImportStep>();
    private final List<ImportStep> missingImports = new ArrayList<ImportStep>();
    private final List<String> notes = new ArrayList<String>();
    private boolean building;

    EffectiveModel(PomFile pom, PomLocator locator) {
        this.pom = pom;
        this.locator = locator;
    }

    void build() {
        building = true;
        try {
            buildChain();
            inherit();
            importBoms();
        } finally {
            building = false;
        }
    }

    private void buildChain() {
        Set<String> seen = new HashSet<String>();
        PomFile current = pom;
        while (current != null && chain.size() < MAX_CHAIN && seen.add(current.getFile().toString())) {
            chain.add(current);
            if (!current.hasParent()) {
                break;
            }
            PomFile parent = locator.parentOf(current);
            if (parent == null) {
                notes.add("Parent " + current.parentGav() + " nicht lokal gefunden (weder relativePath noch lokales Repository)"
                        + " – die Kette endet bei " + current.getFile());
            }
            current = parent;
        }
    }

    private void inherit() {
        for (PomFile p : chain) {
            for (PomFile.Dep d : p.managed) {
                if (!d.isImport() && !managed.containsKey(d.managementKey())) {
                    managed.put(d.managementKey(), new Managed(d, p, this, Collections.<ImportStep>emptyList()));
                }
            }
            for (PomFile.Dep d : p.dependencies) {
                if (!dependencies.containsKey(d.managementKey())) {
                    dependencies.put(d.managementKey(), new Declared(d, p));
                }
            }
        }
    }

    private void importBoms() {
        Set<String> importKeys = new HashSet<String>();
        for (PomFile p : chain) {
            for (PomFile.Dep d : p.managed) {
                if (!d.isImport() || !importKeys.add(d.managementKey())) {
                    continue;
                }
                Interpolated v = interpolate(d.getVersion());
                PomFile bom = v.isResolved() ? locator.byCoordinates(d.getGroupId(), d.getArtifactId(), v.value) : null;
                ImportStep step = new ImportStep(d, p, v, bom);
                imports.add(step);
                if (bom == null) {
                    missingImports.add(step);
                    continue;
                }
                EffectiveModel bomModel = locator.model(bom);
                if (bomModel.building) {
                    notes.add("Import-Zyklus bei BOM " + bom.gav());
                    continue;
                }
                for (ImportStep missing : bomModel.missingImports) {
                    if (!missingImports.contains(missing)) {
                        missingImports.add(missing);
                    }
                }
                for (Managed m : bomModel.managed.values()) {
                    String key = m.dep.managementKey();
                    if (!managed.containsKey(key)) {
                        List<ImportStep> via = new ArrayList<ImportStep>();
                        via.add(step);
                        via.addAll(m.via);
                        managed.put(key, new Managed(m.dep, m.declaredIn, m.context, Collections.unmodifiableList(via)));
                    }
                }
            }
        }
    }

    public PomFile getPom() { return pom; }

    /** Das Modul selbst, sein Parent, dessen Parent … */
    public List<PomFile> getChain() { return Collections.unmodifiableList(chain); }

    public List<ImportStep> getImports() { return Collections.unmodifiableList(imports); }

    public List<String> getNotes() { return Collections.unmodifiableList(notes); }

    /** Importierte BOMs (auch verschachtelt), deren POM lokal fehlt – ihre Einträge fehlen in {@link #getManaged()}. */
    public List<ImportStep> getMissingImports() { return Collections.unmodifiableList(missingImports); }

    public Map<String, Managed> getManaged() { return Collections.unmodifiableMap(managed); }

    public Map<String, Declared> getDependencies() { return Collections.unmodifiableMap(dependencies); }

    /** Verwalteter Eintrag für {@code groupId:artifactId}; bevorzugt {@code type=jar} ohne Classifier. */
    public Managed managed(String groupId, String artifactId) {
        Managed exact = managed.get(groupId + ":" + artifactId + ":jar");
        if (exact != null) {
            return exact;
        }
        for (Managed m : managed.values()) {
            if (groupId.equals(m.dep.getGroupId()) && artifactId.equals(m.dep.getArtifactId())) {
                return m;
            }
        }
        return null;
    }

    /** (Geerbte) {@code <dependency>} für {@code groupId:artifactId} oder {@code null}. */
    public Declared declared(String groupId, String artifactId) {
        for (Declared d : dependencies.values()) {
            if (groupId.equals(d.dep.getGroupId()) && artifactId.equals(d.dep.getArtifactId())) {
                return d;
            }
        }
        return null;
    }

    /** Wo eine Property definiert ist (das Kind gewinnt), oder {@code null}. */
    public PropertyOrigin property(String name) {
        PropertyOrigin found = null;
        for (PomFile p : chain) {
            PomFile.Property prop = p.properties.get(name);
            if (prop == null) {
                continue;
            }
            PropertyOrigin o = new PropertyOrigin(name, prop.getValue(), p, prop.getLine());
            if (found == null) {
                found = o;
            } else {
                found.shadowed.add(o);
            }
        }
        if (found != null) {
            return found;
        }
        return builtin(name);
    }

    private PropertyOrigin builtin(String name) {
        String n = name.startsWith("pom.") ? "project." + name.substring(4) : name;
        String value = null;
        if ("project.version".equals(n) || "version".equals(n)) {
            value = pom.getVersion();
        } else if ("project.groupId".equals(n) || "groupId".equals(n)) {
            value = pom.getGroupId();
        } else if ("project.artifactId".equals(n) || "artifactId".equals(n)) {
            value = pom.getArtifactId();
        } else if ("project.parent.version".equals(n) || "parent.version".equals(n)) {
            value = pom.getParentVersion();
        } else if ("project.parent.groupId".equals(n)) {
            value = pom.getParentGroupId();
        }
        return value == null ? null : new PropertyOrigin(name, value, null, -1);
    }

    /** Setzt {@code ${…}} ein und merkt sich, welche Properties beteiligt waren. */
    public Interpolated interpolate(String raw) {
        List<PropertyOrigin> used = new ArrayList<PropertyOrigin>();
        List<String> unresolved = new ArrayList<String>();
        if (raw == null) {
            return new Interpolated(null, null, used, unresolved);
        }
        String value = raw;
        for (int round = 0; round < MAX_INTERPOLATION && value.contains("${"); round++) {
            Matcher m = EXPRESSION.matcher(value);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String name = m.group(1);
                PropertyOrigin o = property(name);
                if (o == null || o.value == null) {
                    if (!unresolved.contains(name)) {
                        unresolved.add(name);
                    }
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                } else {
                    used.add(o);
                    changed = true;
                    m.appendReplacement(sb, Matcher.quoteReplacement(o.value));
                }
            }
            m.appendTail(sb);
            value = sb.toString();
            if (!changed) {
                break;
            }
        }
        return new Interpolated(raw, value, used, unresolved);
    }
}
