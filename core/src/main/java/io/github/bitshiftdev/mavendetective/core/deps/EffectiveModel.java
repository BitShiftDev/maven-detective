package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    /** Längste Kette von Properties, die auf Properties verweisen. */
    private static final int MAX_INTERPOLATION = 10;
    /** Längster Wert nach dem Einsetzen; schützt vor {@code <x>${y}${y}</x>}-Kaskaden. */
    static final int MAX_VALUE_LENGTH = 10000;

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
    /** Doppelte Management-Einträge derselben POM: Schlüssel → letzter Eintrag (gilt, sobald ein BOM importiert wird). */
    private Map<String, Managed> lastDuplicates;
    private boolean building;
    /** {@code groupId:artifactId} → verwaltete Einträge in Reihenfolge; nach dem Bau einmal angelegt. */
    private Map<String, List<Managed>> managedByGa;
    private Map<String, List<Declared>> declaredByGa;

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
                notes.add(Texts.t("model.parent.missing", current.parentGav(), current.getFile()));
            }
            current = parent;
        }
    }

    /**
     * Das Kind gewinnt. Deklariert eine POM eine Dependency zweimal, gilt wie bei Maven die letzte Deklaration (an der
     * Stelle der ersten). Doppelte Management-Einträge behandelt Maven je nach Lage verschieden – nachgebaut ist die
     * Reihenfolge des Model-Builders, siehe {@link #inheritManagement()}.
     */
    private void inherit() {
        inheritManagement();
        for (PomFile p : chain) {
            for (PomFile.Dep raw : p.dependencies) {
                PomFile.Dep d = coordinates(raw);
                Declared before = dependencies.get(d.managementKey());
                if (before == null || before.declaredIn == p) {
                    dependencies.put(d.managementKey(), new Declared(d, p));
                }
            }
        }
    }

    /**
     * {@code dependencyManagement} wie Mavens Vererbung, von der obersten POM nach unten: Hat der Parent (schon
     * zusammengeführt) Einträge, kommen erst die des Kindes in eine Map (bei doppeltem Schlüssel der letzte), dann die
     * des Parents, soweit der Schlüssel frei ist (bei Duplikaten dort der erste). Was nie zusammengeführt wurde, bleibt
     * doppelt stehen; Maven wendet dann je Attribut den ersten Wert an ({@link PomFile.Dep#mergedWith}). Wird später
     * ein BOM importiert, gilt bei solchen Duplikaten der letzte Eintrag ({@link #importBoms()}).
     */
    private void inheritManagement() {
        List<Managed> acc = new ArrayList<Managed>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            PomFile p = chain.get(i);
            List<Managed> own = new ArrayList<Managed>();
            for (PomFile.Dep raw : p.managed) {
                PomFile.Dep d = coordinates(raw);
                if (!d.isImport()) {
                    own.add(new Managed(d, p, this, Collections.<ImportStep>emptyList()));
                }
            }
            if (acc.isEmpty()) {
                acc = own;
                continue;
            }
            Map<String, Managed> merged = new LinkedHashMap<String, Managed>();
            for (Managed m : own) {
                merged.put(m.dep.managementKey(), m);
            }
            for (Managed m : acc) {
                if (!merged.containsKey(m.dep.managementKey())) {
                    merged.put(m.dep.managementKey(), m);
                }
            }
            acc = new ArrayList<Managed>(merged.values());
        }
        for (Managed m : acc) {
            String key = m.dep.managementKey();
            Managed before = managed.get(key);
            if (before == null) {
                managed.put(key, m);
            } else {
                managed.put(key, new Managed(before.dep.mergedWith(m.dep), before.declaredIn, this,
                        Collections.<ImportStep>emptyList()));
                if (lastDuplicates == null) {
                    lastDuplicates = new HashMap<String, Managed>();
                }
                lastDuplicates.put(key, m);
            }
        }
    }

    /**
     * Wie Maven: Ausdrücke in groupId, artifactId, type und classifier werden vor dem Zusammenführen im Kontext
     * dieses Modells eingesetzt ({@code <groupId>${project.groupId}</groupId>} bei Geschwistermodulen). Die Version
     * bleibt roh, damit {@link Managed#version()} die benutzten Properties nennen kann. Ohne Ausdruck: dieselbe Instanz.
     */
    private PomFile.Dep coordinates(PomFile.Dep d) {
        if (!d.hasCoordinateExpression()) {
            return d;
        }
        return d.withCoordinates(interpolate(d.getGroupId()).value, interpolate(d.getArtifactId()).value,
                interpolate(d.getType()).value, interpolate(d.getClassifier()).value);
    }

    private void importBoms() {
        boolean imported = false;
        Set<String> importKeys = new HashSet<String>();
        for (PomFile p : chain) {
            for (PomFile.Dep raw : p.managed) {
                PomFile.Dep d = coordinates(raw);
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
                imported = true;
                EffectiveModel bomModel = locator.model(bom);
                if (bomModel.building) {
                    notes.add(Texts.t("model.import.cycle", bom.gav()));
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
        if (imported && lastDuplicates != null) {
            // Mavens Import-Schritt legt das eigene Management vorher in eine Map: bei Duplikaten gilt der letzte Eintrag
            for (Map.Entry<String, Managed> e : lastDuplicates.entrySet()) {
                managed.put(e.getKey(), e.getValue());
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
        List<Managed> candidates = managedFor(groupId, artifactId);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Verwalteter Eintrag für genau diese Dependency – wie Maven über den vollen Schlüssel
     * {@code groupId:artifactId:type[:classifier]}. Nur wenn type oder classifier (hier oder im
     * Management) noch einen Ausdruck {@code ${…}} enthalten, zählt ein Eintrag mit gleichem g:a.
     */
    public Managed managed(String groupId, String artifactId, String type, String classifier) {
        String t = type == null || type.isEmpty() ? "jar" : type;
        String c = classifier == null ? "" : classifier;
        Managed exact = managed.get(groupId + ":" + artifactId + ":" + t + (c.isEmpty() ? "" : ":" + c));
        if (exact != null) {
            return exact;
        }
        boolean open = t.contains("${") || c.contains("${");
        for (Managed m : managedFor(groupId, artifactId)) {
            String mc = m.dep.getClassifier();
            if (open || m.dep.getType().contains("${") || mc != null && mc.contains("${")) {
                return m;
            }
        }
        return null;
    }

    /** Einträge mit diesem g:a über einen Index statt einer Suche über alle (BOMs haben Tausende). */
    private List<Managed> managedFor(String groupId, String artifactId) {
        if (building) {
            List<Managed> out = new ArrayList<Managed>();
            for (Managed m : managed.values()) {
                if (groupId.equals(m.dep.getGroupId()) && artifactId.equals(m.dep.getArtifactId())) {
                    out.add(m);
                }
            }
            return out;
        }
        if (managedByGa == null) {
            Map<String, List<Managed>> index = new HashMap<String, List<Managed>>();
            for (Managed m : managed.values()) {
                String key = m.dep.getGroupId() + ":" + m.dep.getArtifactId();
                List<Managed> list = index.get(key);
                if (list == null) {
                    list = new ArrayList<Managed>(1);
                    index.put(key, list);
                }
                list.add(m);
            }
            managedByGa = index;
        }
        List<Managed> found = managedByGa.get(groupId + ":" + artifactId);
        return found == null ? Collections.<Managed>emptyList() : found;
    }

    /** Verwalteter Eintrag für {@code dep} über den vollen Schlüssel, siehe {@link #managed(String, String, String, String)}. */
    public Managed managed(PomFile.Dep dep) {
        return managed(dep.getGroupId(), dep.getArtifactId(), dep.getType(), dep.getClassifier());
    }

    /** (Geerbte) {@code <dependency>} für {@code groupId:artifactId} oder {@code null}; bevorzugt {@code type=jar} ohne Classifier. */
    public Declared declared(String groupId, String artifactId) {
        Declared exact = dependencies.get(groupId + ":" + artifactId + ":jar");
        if (exact != null) {
            return exact;
        }
        if (declaredByGa == null || building) {
            Map<String, List<Declared>> index = new HashMap<String, List<Declared>>();
            for (Declared d : dependencies.values()) {
                String key = d.dep.getGroupId() + ":" + d.dep.getArtifactId();
                List<Declared> list = index.get(key);
                if (list == null) {
                    list = new ArrayList<Declared>(1);
                    index.put(key, list);
                }
                list.add(d);
            }
            if (building) {
                List<Declared> l = index.get(groupId + ":" + artifactId);
                return l == null ? null : l.get(0);
            }
            declaredByGa = index;
        }
        List<Declared> found = declaredByGa.get(groupId + ":" + artifactId);
        return found == null ? null : found.get(0);
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
        } else if ("project.parent.groupId".equals(n) || "parent.groupId".equals(n)) {
            value = pom.getParentGroupId();
        } else if ("project.parent.artifactId".equals(n) || "parent.artifactId".equals(n)) {
            value = pom.getParentArtifactId();
        }
        return value == null ? null : new PropertyOrigin(name, value, null, -1);
    }

    /**
     * Setzt {@code ${…}} ein und merkt sich, welche Properties beteiligt waren. Ein Property, das sich über
     * eine Kette selbst enthält ({@code a → b → a}), bleibt als Ausdruck stehen und zählt als unaufgelöst;
     * ebenso Werte, die beim Einsetzen länger als {@link #MAX_VALUE_LENGTH} würden.
     */
    public Interpolated interpolate(String raw) {
        List<PropertyOrigin> used = new ArrayList<PropertyOrigin>();
        List<String> unresolved = new ArrayList<String>();
        if (raw == null) {
            return new Interpolated(null, null, used, unresolved);
        }
        String value = expand(raw, used, unresolved, new ArrayList<String>(), new HashMap<String, String>());
        return new Interpolated(raw, value, used, unresolved);
    }

    private String expand(String text, List<PropertyOrigin> used, List<String> unresolved,
                          List<String> stack, Map<String, String> done) {
        if (!text.contains("${")) {
            return text;
        }
        Matcher m = EXPRESSION.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            last = m.end();
            String name = m.group(1);
            String v = done.get(name);
            if (v == null) {
                PropertyOrigin o = stack.contains(name) || stack.size() >= MAX_INTERPOLATION ? null : property(name);
                if (o == null || o.value == null) {
                    v = m.group();
                    if (!unresolved.contains(name)) {
                        unresolved.add(name);
                    }
                } else {
                    used.add(o);
                    stack.add(name);
                    v = expand(o.value, used, unresolved, stack, done);
                    stack.remove(stack.size() - 1);
                }
                done.put(name, v);
            }
            if (sb.length() + v.length() > MAX_VALUE_LENGTH) {
                v = m.group();
                if (!unresolved.contains(name)) {
                    unresolved.add(name);
                }
            }
            sb.append(v);
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }
}
