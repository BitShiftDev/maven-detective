package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Source;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Modul A2 – Konflikt-Matrix über den Reactor. Zeile = Artifact, Spalte = Modul, Zelle = Version,
 * die in diesem Modul im Classpath landet. Zeilen mit mehr als einer Version sind Abweichungen;
 * für sie schlägt die Matrix vor, die Version im gemeinsamen Parent unter
 * {@code dependencyManagement} festzulegen, und nennt, was diesen Pin noch überstimmen würde.
 *
 * <p>Mit Bäumen (IDE-Modell oder {@code mvn dependency:tree -Dverbose}) sind auch transitive
 * Dependencies dabei; ohne Baum nur, was die POMs direkt deklarieren.
 */
public final class ConflictMatrix {

    /** Eine Spalte: ein Modul des Reactors. */
    public static final class Module {
        private final PomFile pom;
        private final boolean fromTree;

        Module(PomFile pom, boolean fromTree) {
            this.pom = pom;
            this.fromTree = fromTree;
        }

        public PomFile getPom() { return pom; }
        public String ga() { return pom.getGroupId() + ":" + pom.getArtifactId(); }
        public String getName() { return pom.getArtifactId(); }
        /** {@code false}: kein Baum vorhanden, nur direkte Dependencies aus der POM. */
        public boolean isFromTree() { return fromTree; }
    }

    /** Eine Zelle: Version in einem Modul samt Herkunft. */
    public static final class Cell {
        private final String version;
        private final String scope;
        private final int depth;
        private final Source source;
        private final String file;
        private final int line;

        Cell(String version, String scope, int depth, Source source, String file, int line) {
            this.version = version;
            this.scope = scope;
            this.depth = depth;
            this.source = source;
            this.file = file;
            this.line = line;
        }

        public String getVersion() { return version; }
        public String getScope() { return scope; }
        /** 1 = direkt deklariert, größer = transitiv. */
        public int getDepth() { return depth; }
        public Source getSource() { return source; }
        /** POM, die die Version setzt (direkte Angabe oder dependencyManagement); {@code null} bei transitiv. */
        public String getFile() { return file; }
        public int getLine() { return line; }

        public String sourceLabel() {
            switch (source) {
                case DIRECT:
                    return "direkt angegeben";
                case MANAGED:
                    return "dependencyManagement";
                case BOM:
                    return "BOM";
                case TRANSITIVE:
                    return "transitiv (Tiefe " + depth + ")";
                default:
                    return "unbekannt";
            }
        }
    }

    /** Eine Stelle, die einen Pin im Parent überstimmen würde. */
    public static final class Blocker {
        private final Module module;
        private final String text;
        private final String file;
        private final int line;

        Blocker(Module module, String text, String file, int line) {
            this.module = module;
            this.text = text;
            this.file = file;
            this.line = line;
        }

        public Module getModule() { return module; }
        public String getText() { return text; }
        public String getFile() { return file; }
        public int getLine() { return line; }
    }

    /** Vorschlag: Version im gemeinsamen Parent unter {@code dependencyManagement} festlegen. */
    public static final class Pin {
        private final PomFile target;
        private final String version;
        private final String existing;
        private final List<Blocker> overrides;

        Pin(PomFile target, String version, String existing, List<Blocker> overrides) {
            this.target = target;
            this.version = version;
            this.existing = existing;
            this.overrides = overrides;
        }

        /** Gemeinsamer Parent im Projekt. */
        public PomFile getTarget() { return target; }
        /** Vorgeschlagene Version (die höchste der Zeile). */
        public String getVersion() { return version; }
        /** Version, die der Parent schon verwaltet, oder {@code null}. */
        public String getExisting() { return existing; }
        /** Direkte Angaben oder tiefer liegendes Management, das den Pin überstimmt. */
        public List<Blocker> getBlockers() { return overrides; }

        /** Quick-Fix {@code pin-version} für eine beliebige Version der Zeile. */
        public FixAction fix(String groupId, String artifactId, String version) {
            return FixAction.of(FixAction.PIN_VERSION,
                    "Version " + version + " in " + target.getArtifactId() + " unter dependencyManagement festlegen",
                    "file", target.getFile().toString(), "artifact", groupId + ":" + artifactId, "version", version);
        }

        public FixAction fix(String groupId, String artifactId) {
            return fix(groupId, artifactId, version);
        }
    }

    /** Eine Zeile: ein Artifact über alle Module. */
    public static final class Row {
        private final String groupId;
        private final String artifactId;
        private final Map<Module, Cell> cells = new LinkedHashMap<Module, Cell>();
        private Pin pin;
        private String pinProblem;

        Row(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String ga() { return groupId + ":" + artifactId; }
        public Cell cell(Module m) { return cells.get(m); }
        public Map<Module, Cell> getCells() { return Collections.unmodifiableMap(cells); }

        /** Unterschiedliche Versionen, aufsteigend. */
        public List<String> versions() {
            Set<String> set = new LinkedHashSet<String>();
            for (Cell c : cells.values()) {
                set.add(c.version);
            }
            List<String> out = new ArrayList<String>(set);
            Collections.sort(out, MavenVersion.ORDER);
            return out;
        }

        public boolean isDivergent() {
            return versions().size() > 1;
        }

        /** Module je Version, aufsteigend nach Version. */
        public Map<String, List<Module>> modulesByVersion() {
            Map<String, List<Module>> out = new TreeMap<String, List<Module>>(MavenVersion.ORDER);
            for (Map.Entry<Module, Cell> e : cells.entrySet()) {
                List<Module> l = out.get(e.getValue().version);
                if (l == null) {
                    l = new ArrayList<Module>();
                    out.put(e.getValue().version, l);
                }
                l.add(e.getKey());
            }
            return out;
        }

        /** Vorschlag für den Pin; {@code null}, wenn die Zeile nicht abweicht oder kein Parent passt. */
        public Pin getPin() { return pin; }
        /** Warum es keinen Pin gibt (nur bei abweichenden Zeilen). */
        public String getPinProblem() { return pinProblem; }
    }

    private final List<Module> modules;
    private final List<Row> rows;

    private ConflictMatrix(List<Module> modules, List<Row> rows) {
        this.modules = modules;
        this.rows = rows;
    }

    public List<Module> getModules() { return Collections.unmodifiableList(modules); }

    public List<Row> getRows() { return Collections.unmodifiableList(rows); }

    public List<Row> divergent() {
        List<Row> out = new ArrayList<Row>();
        for (Row r : rows) {
            if (r.isDivergent()) {
                out.add(r);
            }
        }
        return out;
    }

    /** Hat irgendein Modul keinen Baum (dann fehlen transitive Dependencies in der Matrix)? */
    public boolean isPartial() {
        for (Module m : modules) {
            if (!m.fromTree) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param locator     POM-Zugriff (Reactor + lokales Repository)
     * @param projectRoot Wurzel des Reactors; nur POMs darunter kommen als Ziel für den Pin infrage
     * @param poms        die Module in Reactor-Reihenfolge
     * @param trees       aufgelöste Bäume je {@code groupId:artifactId} des Moduls (darf leer sein)
     */
    public static ConflictMatrix build(PomLocator locator, Path projectRoot, List<PomFile> poms, Map<String, DepNode> trees) {
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        List<Module> modules = new ArrayList<Module>();
        Map<String, Row> rows = new TreeMap<String, Row>();
        for (PomFile pom : poms) {
            DepNode tree = trees == null ? null : trees.get(pom.getGroupId() + ":" + pom.getArtifactId());
            EffectiveModel model = locator.model(pom);
            Module module = new Module(pom, tree != null);
            Map<String, Cell> cells = tree != null ? fromTree(model, tree) : fromPom(model);
            if (cells.isEmpty() && "pom".equals(pom.getPackaging())) {
                continue; // Aggregator/Parent ohne eigene Dependencies: leere Spalte bringt nichts
            }
            modules.add(module);
            for (Map.Entry<String, Cell> e : cells.entrySet()) {
                Row row = rows.get(e.getKey());
                if (row == null) {
                    int colon = e.getKey().indexOf(':');
                    row = new Row(e.getKey().substring(0, colon), e.getKey().substring(colon + 1));
                    rows.put(e.getKey(), row);
                }
                row.cells.put(module, e.getValue());
            }
        }
        List<Row> out = new ArrayList<Row>(rows.values());
        for (Row row : out) {
            if (row.isDivergent()) {
                pin(locator, root, row);
            }
        }
        return new ConflictMatrix(modules, out);
    }

    /** Je Artifact der Knoten, der im Classpath landet (der nächste zuerst). */
    private static Map<String, Cell> fromTree(EffectiveModel model, DepNode tree) {
        Map<String, Cell> out = new LinkedHashMap<String, Cell>();
        List<DepNode> queue = new ArrayList<DepNode>(tree.getChildren());
        for (int i = 0; i < queue.size() && i < 50000; i++) {
            DepNode n = queue.get(i);
            if (n.getState() == DepNode.State.INCLUDED && !out.containsKey(n.ga()) && n.getVersion() != null) {
                out.put(n.ga(), cell(model, n.getGroupId(), n.getArtifactId(), n.getVersion(), n.getScope(), n.depth()));
            }
            queue.addAll(n.getChildren());
        }
        return out;
    }

    /** Ohne Baum: nur die (geerbten) direkten Dependencies mit Version aus POM oder Management. */
    private static Map<String, Cell> fromPom(EffectiveModel model) {
        Map<String, Cell> out = new LinkedHashMap<String, Cell>();
        for (Declared d : model.getDependencies().values()) {
            String ga = d.dep.ga();
            if (out.containsKey(ga)) {
                continue;
            }
            String version;
            if (d.dep.getVersion() != null) {
                version = model.interpolate(d.dep.getVersion()).value;
            } else {
                Managed m = model.managed(d.dep.getGroupId(), d.dep.getArtifactId());
                version = m == null ? null : m.version().value;
            }
            if (version == null) {
                continue;
            }
            out.put(ga, cell(model, d.dep.getGroupId(), d.dep.getArtifactId(), version,
                    d.dep.getScope() == null ? "compile" : d.dep.getScope(), 1));
        }
        return out;
    }

    private static Cell cell(EffectiveModel model, String groupId, String artifactId, String version, String scope, int depth) {
        Declared d = model.declared(groupId, artifactId);
        if (d != null && d.dep.getVersion() != null) {
            return new Cell(version, scope, depth, Source.DIRECT, d.declaredIn.getFile().toString(), d.dep.getVersionLine());
        }
        Managed m = model.managed(groupId, artifactId);
        if (m != null) {
            return new Cell(version, scope, depth, m.via.isEmpty() ? Source.MANAGED : Source.BOM,
                    m.declaredIn.getFile().toString(), m.dep.getVersionLine());
        }
        return new Cell(version, scope, depth, depth > 1 ? Source.TRANSITIVE : Source.UNKNOWN, null, -1);
    }

    /** Gemeinsamen Parent im Projekt suchen und prüfen, was den Pin dort überstimmen würde. */
    private static void pin(PomLocator locator, Path root, Row row) {
        List<PomFile> common = null;
        for (Module m : row.cells.keySet()) {
            List<PomFile> chain = new ArrayList<PomFile>();
            for (PomFile p : locator.model(m.pom).getChain()) {
                if (root == null || p.getFile().startsWith(root)) {
                    chain.add(p);
                }
            }
            if (common == null) {
                common = chain;
            } else {
                common.retainAll(chain);
            }
        }
        if (common == null || common.isEmpty()) {
            row.pinProblem = "Die betroffenen Module haben keinen gemeinsamen Parent im Projekt – die Version muss in jedem Modul "
                    + "einzeln festgelegt werden (oder ein gemeinsamer Parent eingeführt).";
            return;
        }
        PomFile target = common.get(0);
        String version = MavenVersion.max(row.versions());
        String existing = null;
        List<Blocker> overrides = new ArrayList<Blocker>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Module m : row.cells.keySet()) {
            EffectiveModel model = locator.model(m.pom);
            List<PomFile> chain = model.getChain();
            int targetIdx = chain.indexOf(target);
            Declared d = model.declared(row.groupId, row.artifactId);
            if (d != null && d.dep.getVersion() != null) {
                String key = d.declaredIn.getFile() + ":" + d.dep.getVersionLine();
                if (seen.add(key)) {
                    overrides.add(new Blocker(m, "Direkte Angabe <version>" + d.dep.getVersion() + "</version> in "
                            + d.declaredIn.getArtifactId() + " überstimmt dependencyManagement – nach dem Pin dort die Version entfernen",
                            d.declaredIn.getFile().toString(), d.dep.getVersionLine()));
                }
            }
            Managed mg = model.managed(row.groupId, row.artifactId);
            if (mg != null && mg.via.isEmpty()) {
                if (mg.declaredIn == target) {
                    existing = mg.version().value;
                } else if (chain.indexOf(mg.declaredIn) >= 0 && chain.indexOf(mg.declaredIn) < targetIdx) {
                    String key = mg.declaredIn.getFile() + ":" + mg.dep.getVersionLine();
                    if (seen.add(key)) {
                        overrides.add(new Blocker(m, "dependencyManagement in " + mg.declaredIn.getArtifactId() + " ("
                                + mg.version().value + ") liegt näher am Modul und überstimmt den Parent – dort entfernen oder anpassen",
                                mg.declaredIn.getFile().toString(), mg.dep.getVersionLine()));
                    }
                }
            }
            // Aus einem BOM: Ein eigener Eintrag im Parent gewinnt gegen jeden Import, nichts zu tun
        }
        row.pin = new Pin(target, version, existing, Collections.unmodifiableList(overrides));
    }

    /** Konsolenausgabe: je abweichendem Artifact die Versionen, Module und der Vorschlag. */
    public List<String> lines(boolean all) {
        List<String> out = new ArrayList<String>();
        List<Row> shown = all ? rows : divergent();
        out.add("Konflikt-Matrix: " + modules.size() + " Module, " + rows.size() + " Artifacts, " + divergent().size() + " mit abweichenden Versionen");
        if (isPartial()) {
            out.add("(ohne Baum für " + partialNames() + ": nur direkte Dependencies – vollständig mit mvn dependency:tree -Dverbose > tree.txt, dann --tree tree.txt)");
        }
        for (Row r : shown) {
            out.add("");
            Map<String, List<Module>> byVersion = r.modulesByVersion();
            out.add(r.ga() + (r.isDivergent() ? "  – " + byVersion.size() + " Versionen" : ""));
            for (Map.Entry<String, List<Module>> e : byVersion.entrySet()) {
                StringBuilder sb = new StringBuilder("    ").append(e.getKey()).append(": ");
                boolean first = true;
                for (Module m : e.getValue()) {
                    Cell c = r.cell(m);
                    sb.append(first ? "" : ", ").append(m.getName()).append(" (").append(c.sourceLabel())
                            .append("compile".equals(c.scope) || c.scope == null ? "" : ", " + c.scope).append(')');
                    first = false;
                }
                out.add(sb.toString());
            }
            if (r.pin != null) {
                out.add("    Vorschlag: " + r.pin.version + " in " + r.pin.target.getArtifactId() + " (" + r.pin.target.getFile()
                        + ") unter dependencyManagement festlegen"
                        + (r.pin.existing == null ? "" : " – dort steht bisher " + r.pin.existing));
                for (Blocker o : r.pin.overrides) {
                    out.add("      ! " + o.text + "  → " + o.file + (o.line > 0 ? ":" + o.line : ""));
                }
            } else if (r.pinProblem != null) {
                out.add("    " + r.pinProblem);
            }
        }
        if (shown.isEmpty()) {
            out.add("");
            out.add("Alle Module nutzen jeweils dieselbe Version.");
        }
        return out;
    }

    /** Markdown-Tabelle (Zeile = Artifact, Spalte = Modul); abweichende Zellen fett. */
    public String markdown(boolean all) {
        List<Row> shown = all ? rows : divergent();
        StringBuilder sb = new StringBuilder("### Konflikt-Matrix\n\n");
        sb.append(modules.size()).append(" Module, ").append(divergent().size()).append(" Artifacts mit abweichenden Versionen.");
        if (isPartial()) {
            sb.append(" Ohne Baum für ").append(partialNames()).append(" – nur direkte Dependencies.");
        }
        sb.append("\n\n");
        if (shown.isEmpty()) {
            return sb.append("Alle Module nutzen jeweils dieselbe Version.\n").toString();
        }
        sb.append("| Artifact |");
        for (Module m : modules) {
            sb.append(' ').append(m.getName()).append(" |");
        }
        sb.append(" Vorschlag |\n|---|");
        for (int i = 0; i < modules.size(); i++) {
            sb.append("---|");
        }
        sb.append("---|\n");
        for (Row r : shown) {
            String suggested = r.pin != null ? r.pin.version : MavenVersion.max(r.versions());
            sb.append("| `").append(r.ga()).append("` |");
            for (Module m : modules) {
                Cell c = r.cell(m);
                if (c == null) {
                    sb.append(" – |");
                } else if (r.isDivergent() && !c.version.equals(suggested)) {
                    sb.append(" **").append(c.version).append("** |");
                } else {
                    sb.append(' ').append(c.version).append(" |");
                }
            }
            if (r.pin != null) {
                sb.append(' ').append(r.pin.version).append(" in `").append(r.pin.target.getArtifactId()).append("`");
                if (!r.pin.overrides.isEmpty()) {
                    sb.append(" (").append(r.pin.overrides.size()).append(r.pin.overrides.size() == 1 ? " Stelle überstimmt ihn)" : " Stellen überstimmen ihn)");
                }
                sb.append(" |\n");
            } else {
                sb.append(r.isDivergent() ? " kein gemeinsamer Parent |\n" : " |\n");
            }
        }
        return sb.toString();
    }

    private String partialNames() {
        StringBuilder sb = new StringBuilder();
        for (Module m : modules) {
            if (!m.fromTree) {
                sb.append(sb.length() == 0 ? "" : ", ").append(m.getName());
            }
        }
        return sb.toString();
    }
}
