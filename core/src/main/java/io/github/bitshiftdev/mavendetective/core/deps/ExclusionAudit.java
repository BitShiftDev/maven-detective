package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Interpolated;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modul A4 – Exclusion-Hygiene: Prüft jede {@code <exclusion>} in den POMs des Projekts darauf, ob
 * sie noch etwas ausschließt. Dazu wird der transitive Baum der Dependency aus den POMs im lokalen
 * Repository nachgebaut – <em>ohne</em> die geprüfte Exclusion. Taucht das ausgeschlossene Artifact
 * darin nicht auf, ist die Exclusion veraltet (typisch nach einem Upgrade) und kann weg.
 *
 * <p>Nachgebaut wird wie bei Maven: nur {@code compile}/{@code runtime}, keine {@code optional}en
 * Dependencies, Exclusions tiefer im Baum gelten weiter, der erste Fund eines Artifacts gewinnt
 * (nearest wins), Versionen aus dem {@code dependencyManagement} des prüfenden Moduls gehen vor.
 * Fehlt unterwegs eine POM lokal, heißt das Ergebnis {@link Status#UNKNOWN} statt „veraltet“ –
 * lieber keine Meldung als eine falsche. Geht nie ins Netz.
 */
public final class ExclusionAudit {

    private static final int MAX_NODES = 5000;

    /** Befund für eine Exclusion. */
    public enum Status {
        /** Schließt tatsächlich etwas aus. */
        USED,
        /** Das Artifact käme nur über einen Pfad, den eine andere Exclusion derselben Dependency schon kappt. */
        REDUNDANT,
        /** Die Dependency bringt das Artifact gar nicht (mehr) mit. */
        STALE,
        /** Nicht prüfbar, weil POMs lokal fehlen oder Versionen offen sind. */
        UNKNOWN
    }

    /** Eine geprüfte Exclusion. */
    public static final class Finding {
        private final PomFile pom;
        private final PomFile.Dep dependency;
        private final boolean managed;
        private final PomFile.Exclusion exclusion;
        private final String dependencyVersion;
        private Status status;
        private List<String> path = Collections.emptyList();
        private String coveredBy;
        private List<String> missing = Collections.emptyList();
        private List<String> limits = Collections.emptyList();
        private String alsoIncludedVia;

        Finding(PomFile pom, PomFile.Dep dependency, boolean managed, PomFile.Exclusion exclusion, String dependencyVersion) {
            this.pom = pom;
            this.dependency = dependency;
            this.managed = managed;
            this.exclusion = exclusion;
            this.dependencyVersion = dependencyVersion;
        }

        /** POM, in der die Exclusion steht. */
        public PomFile getPom() { return pom; }
        public PomFile.Dep getDependency() { return dependency; }
        /** {@code true}: Eintrag unter {@code dependencyManagement}. */
        public boolean isManaged() { return managed; }
        public PomFile.Exclusion getExclusion() { return exclusion; }
        /** Aufgelöste Version der Dependency oder {@code null}. */
        public String getDependencyVersion() { return dependencyVersion; }
        public Status getStatus() { return status; }
        /** Bei {@link Status#USED}/{@link Status#REDUNDANT}: Pfad von der Dependency bis zum ausgeschlossenen Artifact. */
        public List<String> getPath() { return path; }
        /** Bei {@link Status#REDUNDANT}: die Exclusion, die den Pfad schon kappt. */
        public String getCoveredBy() { return coveredBy; }
        /** Bei {@link Status#UNKNOWN}: was lokal fehlt. */
        public List<String> getMissing() { return missing; }
        /** Bei {@link Status#UNKNOWN}: was der Nachbau nicht abbildet (Relocation, Profile mit Dependencies). */
        public List<String> getLimits() { return limits; }
        /** Bei {@link Status#USED} mit Baum: Pfad, über den das Artifact trotzdem im Classpath landet. */
        public String getAlsoIncludedVia() { return alsoIncludedVia; }

        public String getFile() { return pom.getFile().toString(); }
        public int getLine() { return exclusion.getLine() > 0 ? exclusion.getLine() : dependency.getLine(); }

        /** Wie gewichtig der Status ist: ein Modul, das die Exclusion braucht, schlägt alle anderen. */
        private int weight() {
            switch (status) {
                case USED: return 3;
                case UNKNOWN: return 2;
                case REDUNDANT: return 1;
                default: return 0;
            }
        }

        /** Übernimmt das Ergebnis aus dem Kontext eines erbenden Moduls, wenn es gewichtiger ist. */
        private void raiseTo(Finding heir) {
            if (heir.weight() <= weight()) {
                return;
            }
            status = heir.status;
            path = heir.path;
            coveredBy = heir.coveredBy;
            missing = heir.missing;
            limits = heir.limits;
            alsoIncludedVia = heir.alsoIncludedVia;
        }

        /** Kann (und sollte) entfernt werden. */
        public boolean isRemovable() {
            return status == Status.STALE || status == Status.REDUNDANT;
        }

        private String dependencyLabel() {
            return dependency.ga() + (dependencyVersion == null ? "" : ":" + dependencyVersion);
        }

        /** Befund in einem Satz. */
        public String message() {
            String e = exclusion.ga();
            switch (status) {
                case STALE:
                    return Texts.t("excl.msg.stale", e, dependencyLabel());
                case REDUNDANT:
                    return Texts.t("excl.msg.redundant", e, join(path), coveredBy);
                case USED:
                    return Texts.t("excl.msg.used", e, join(path))
                            + (alsoIncludedVia == null ? "" : " – " + Texts.t("excl.msg.also.included", e, alsoIncludedVia));
                default:
                    if (missing.isEmpty()) {
                        return Texts.t("excl.msg.unsupported", e, dependencyLabel(), join(limits, ", "));
                    }
                    return Texts.t("excl.msg.unknown", e, dependencyLabel(), join(missing, ", "))
                            + (limits.isEmpty() ? "" : "; " + join(limits, ", "));
            }
        }

        /** Quick-Fix {@code remove-exclusion}; {@code null}, wenn die Exclusion bleiben soll. */
        public FixAction fix() {
            if (!isRemovable()) {
                return null;
            }
            return FixAction.of(FixAction.REMOVE_EXCLUSION,
                    Texts.t("excl.fix.remove", exclusion.ga(), dependency.ga()),
                    "file", getFile(), "artifact", dependency.ga(), "exclusion", exclusion.ga(),
                    "section", managed ? "dependencyManagement" : "dependencies");
        }
    }

    private final List<Finding> findings;
    private final int pomCount;

    private ExclusionAudit(List<Finding> findings, int pomCount) {
        this.findings = findings;
        this.pomCount = pomCount;
    }

    public List<Finding> getFindings() { return Collections.unmodifiableList(findings); }

    /** Veraltete und doppelte Exclusions. */
    public List<Finding> removable() {
        List<Finding> out = new ArrayList<Finding>();
        for (Finding f : findings) {
            if (f.isRemovable()) {
                out.add(f);
            }
        }
        return out;
    }

    public int count(Status status) {
        int n = 0;
        for (Finding f : findings) {
            if (f.status == status) {
                n++;
            }
        }
        return n;
    }

    /**
     * Alle Exclusions der Projekt-POMs prüfen.
     *
     * @param trees aufgelöste Bäume je {@code groupId:artifactId} des Moduls (darf leer sein); nur
     *              für den Hinweis, dass ein ausgeschlossenes Artifact über einen anderen Pfad doch kommt
     */
    public static ExclusionAudit build(PomLocator locator, List<PomFile> poms, Map<String, DepNode> trees) {
        return build(locator, poms, trees, Cancellation.NONE);
    }

    /** Wie oben; {@code cancel} wird je Modul geprüft (IDE: „Stop“). */
    public static ExclusionAudit build(PomLocator locator, List<PomFile> poms, Map<String, DepNode> trees, Cancellation cancel) {
        List<Finding> out = new ArrayList<Finding>();
        for (PomFile pom : poms) {
            cancel.check();
            DepNode tree = trees == null ? null : trees.get(pom.getGroupId() + ":" + pom.getArtifactId());
            out.addAll(audit(locator, pom, tree));
        }
        return new ExclusionAudit(out, poms.size());
    }

    /**
     * Exclusions einer einzelnen POM prüfen (nur die dort notierten, nicht geerbte). Steht die Exclusion in
     * einem Parent, zählt sie erst als veraltet, wenn sie auch im Kontext jedes erbenden Reactor-Moduls nichts
     * mehr ausschließt – dort kann eigenes Management eine andere Version mit anderen Dependencies wählen.
     */
    public static List<Finding> audit(PomLocator locator, PomFile pom, DepNode tree) {
        List<Finding> out = new ArrayList<Finding>();
        EffectiveModel consumer = locator.model(pom);
        for (PomFile.Dep d : pom.getDependencies()) {
            auditDependency(locator, consumer, pom, d, false, tree, out);
        }
        for (PomFile.Dep d : pom.getManagedDependencies()) {
            if (!d.isImport()) {
                auditDependency(locator, consumer, pom, d, true, null, out);
            }
        }
        if ("pom".equals(pom.getPackaging())) {
            recheckInHeirs(locator, pom, out);
        }
        return out;
    }

    private static void recheckInHeirs(PomLocator locator, PomFile pom, List<Finding> findings) {
        List<EffectiveModel> heirs = null;
        for (Finding f : findings) {
            if (!f.isRemovable()) {
                continue;
            }
            if (heirs == null) {
                heirs = heirs(locator, pom);
            }
            for (EffectiveModel heir : heirs) {
                List<Finding> there = new ArrayList<Finding>();
                auditDependency(locator, heir, pom, f.dependency, f.managed, null, there);
                for (Finding h : there) {
                    if (h.exclusion == f.exclusion) {
                        f.raiseTo(h);
                    }
                }
                if (f.status == Status.USED) {
                    break;
                }
            }
        }
    }

    /** Modelle der Reactor-Module, deren Parent-Kette über {@code pom} läuft. */
    private static List<EffectiveModel> heirs(PomLocator locator, PomFile pom) {
        List<EffectiveModel> out = new ArrayList<EffectiveModel>();
        for (PomFile p : locator.reactorPoms()) {
            if (p.getFile().equals(pom.getFile())) {
                continue;
            }
            EffectiveModel m = locator.model(p);
            for (PomFile c : m.getChain()) {
                if (c.getFile().equals(pom.getFile())) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    private static void auditDependency(PomLocator locator, EffectiveModel consumer, PomFile pom, PomFile.Dep d, boolean managed,
                                        DepNode tree, List<Finding> out) {
        List<PomFile.Exclusion> exclusions = d.getExclusionEntries();
        if (exclusions.isEmpty()) {
            return;
        }
        String version = dependencyVersion(consumer, d);
        if (version == null) {
            for (PomFile.Exclusion e : exclusions) {
                Finding f = new Finding(pom, d, managed, e, null);
                f.status = Status.UNKNOWN;
                f.missing = Collections.singletonList(Texts.t("excl.missing.version", d.ga()));
                out.add(f);
            }
            return;
        }
        Closure full = Closure.of(locator, consumer, d.getGroupId(), d.getArtifactId(), version,
                Collections.<PomFile.Exclusion>emptyList());
        for (PomFile.Exclusion e : exclusions) {
            Finding f = new Finding(pom, d, managed, e, version);
            List<PomFile.Exclusion> others = new ArrayList<PomFile.Exclusion>(exclusions);
            others.remove(e);
            Closure withOthers = others.isEmpty() ? full
                    : Closure.of(locator, consumer, d.getGroupId(), d.getArtifactId(), version, others);
            List<String> hit = withOthers.find(e);
            List<String> anyHit = full.find(e);
            if (hit != null) {
                f.status = Status.USED;
                f.path = hit;
                if (tree != null && !"*".equals(e.getGroupId()) && !"*".equals(e.getArtifactId())) {
                    DepNode inc = tree.findIncluded(e.getGroupId(), e.getArtifactId());
                    if (inc != null) {
                        f.alsoIncludedVia = inc.pathText();
                    }
                }
            } else if (anyHit != null) {
                f.status = Status.REDUNDANT;
                f.path = anyHit;
                f.coveredBy = coveredBy(anyHit, others);
            } else if (withOthers.missing.isEmpty() && full.missing.isEmpty() && full.limits.isEmpty() && withOthers.limits.isEmpty()) {
                f.status = Status.STALE;
            } else {
                f.status = Status.UNKNOWN;
                Set<String> m = new LinkedHashSet<String>(full.missing);
                m.addAll(withOthers.missing);
                f.missing = new ArrayList<String>(m);
                Set<String> l = new LinkedHashSet<String>(full.limits);
                l.addAll(withOthers.limits);
                f.limits = new ArrayList<String>(l);
            }
            out.add(f);
        }
    }

    /** Version der Dependency: direkt angegeben, sonst aus dem dependencyManagement des Moduls. */
    static String dependencyVersion(EffectiveModel consumer, PomFile.Dep d) {
        if (d.getVersion() != null) {
            Interpolated v = consumer.interpolate(d.getVersion());
            return v.isResolved() && isFixed(v.value) ? v.value : null;
        }
        Managed m = consumer.managed(d);
        if (m == null) {
            return null;
        }
        Interpolated v = m.version();
        return v.isResolved() && isFixed(v.value) ? v.value : null;
    }

    private static boolean isFixed(String v) {
        return v != null && !v.isEmpty() && !v.startsWith("[") && !v.startsWith("(");
    }

    /** Welche der anderen Exclusions trifft einen Knoten auf dem Pfad? */
    private static String coveredBy(List<String> path, List<PomFile.Exclusion> others) {
        for (int i = 1; i < path.size(); i++) {
            String[] p = path.get(i).split(":");
            for (PomFile.Exclusion o : others) {
                if (p.length >= 2 && o.matches(p[0], p[1])) {
                    return o.ga();
                }
            }
        }
        return others.isEmpty() ? "?" : others.get(0).ga();
    }

    /** Transitiver Baum einer Dependency, nachgebaut aus den POMs im lokalen Repository. */
    static final class Closure {
        /** Je {@code groupId:artifactId} der erste (kürzeste) Pfad, beginnend bei der Dependency selbst. */
        final Map<String, List<String>> paths = new LinkedHashMap<String, List<String>>();
        /** Je {@code groupId:artifactId} die Angabe, über die es kam (Typ, Classifier, Scope). */
        final Map<String, PomFile.Dep> decls = new HashMap<String, PomFile.Dep>();
        final Set<String> missing = new LinkedHashSet<String>();
        /** Was der Nachbau nicht abbildet (Relocation, Profile mit Dependencies) – das Ergebnis ist dann unsicher. */
        final Set<String> limits = new LinkedHashSet<String>();

        private static final class Node {
            final String groupId;
            final String artifactId;
            final String version;
            final List<String> path;
            final List<PomFile.Exclusion> exclusions;

            Node(String groupId, String artifactId, String version, List<String> path, List<PomFile.Exclusion> exclusions) {
                this.groupId = groupId;
                this.artifactId = artifactId;
                this.version = version;
                this.path = path;
                this.exclusions = exclusions;
            }
        }

        static Closure of(PomLocator locator, EffectiveModel consumer, String groupId, String artifactId, String version,
                          List<PomFile.Exclusion> exclusions) {
            Closure c = new Closure();
            Set<String> seen = new HashSet<String>();
            seen.add(groupId + ":" + artifactId);
            List<Node> queue = new ArrayList<Node>();
            queue.add(new Node(groupId, artifactId, version,
                    Collections.singletonList(groupId + ":" + artifactId + ":" + version), exclusions));
            for (int i = 0; i < queue.size() && i < MAX_NODES; i++) {
                Node n = queue.get(i);
                PomFile pom = locator.byCoordinates(n.groupId, n.artifactId, n.version);
                if (pom == null) {
                    c.missing.add(n.groupId + ":" + n.artifactId + ":" + n.version);
                    continue;
                }
                EffectiveModel model = locator.model(pom);
                List<PomFile> chain = model.getChain();
                PomFile last = chain.get(chain.size() - 1);
                if (last.hasParent() && locator.parentOf(last) == null) {
                    c.missing.add(last.parentGav() + " (" + Texts.t("excl.missing.parent", n.groupId + ":" + n.artifactId) + ")");
                }
                // Relocation und Profile bildet der Nachbau nicht ab: was dort käme, fehlt sonst
                // stillschweigend – dann lieber „nicht prüfbar“ als „veraltet“.
                if (pom.isRelocated()) {
                    c.limits.add(Texts.t("excl.missing.relocation", n.groupId + ":" + n.artifactId + ":" + n.version,
                            pom.relocationTarget()));
                }
                for (PomFile p : chain) {
                    for (String profile : p.getProfilesWithDependencies()) {
                        c.limits.add(Texts.t("excl.missing.profile", profile, p.gav()));
                    }
                }
                for (Declared d : model.getDependencies().values()) {
                    String scope = d.dep.getScope();
                    if (scope != null && !scope.isEmpty() && !"compile".equals(scope) && !"runtime".equals(scope)
                            || d.dep.isOptional() || excluded(n.exclusions, d.dep)) {
                        continue;
                    }
                    String ga = d.dep.ga();
                    if (!seen.add(ga)) {
                        continue;
                    }
                    String v = version(consumer, model, d.dep);
                    List<String> path = new ArrayList<String>(n.path);
                    path.add(ga + ":" + (v == null ? "?" : v));
                    c.paths.put(ga, Collections.unmodifiableList(path));
                    c.decls.put(ga, d.dep);
                    if (v == null) {
                        c.missing.add(ga + " (" + Texts.t("excl.missing.undetermined", n.groupId + ":" + n.artifactId) + ")");
                        continue;
                    }
                    // Exclusions der Deklaration (ohne eigene: aus dem Management ihrer POM) und aus dem
                    // Management des prüfenden Moduls – wie DependencyResolver
                    List<PomFile.Exclusion> childExclusions = n.exclusions;
                    List<PomFile.Exclusion> own = d.dep.getExclusionEntries();
                    if (own.isEmpty()) {
                        Managed om = model.managed(d.dep);
                        own = om == null ? own : om.dep.getExclusionEntries();
                    }
                    Managed cm = consumer.managed(d.dep);
                    List<PomFile.Exclusion> managedByConsumer = cm == null ? Collections.<PomFile.Exclusion>emptyList()
                            : cm.dep.getExclusionEntries();
                    if (!own.isEmpty() || !managedByConsumer.isEmpty()) {
                        childExclusions = new ArrayList<PomFile.Exclusion>(n.exclusions);
                        childExclusions.addAll(own);
                        childExclusions.addAll(managedByConsumer);
                    }
                    queue.add(new Node(d.dep.getGroupId(), d.dep.getArtifactId(), v, path, childExclusions));
                }
            }
            if (queue.size() >= MAX_NODES) {
                c.missing.add(Texts.t("excl.missing.truncated", MAX_NODES));
            }
            return c;
        }

        /** Erster Pfad zu einem Artifact, das die Exclusion trifft, oder {@code null}. */
        List<String> find(PomFile.Exclusion e) {
            for (Map.Entry<String, List<String>> p : paths.entrySet()) {
                int colon = p.getKey().indexOf(':');
                if (e.matches(p.getKey().substring(0, colon), p.getKey().substring(colon + 1))) {
                    return p.getValue();
                }
            }
            return null;
        }

        private static boolean excluded(List<PomFile.Exclusion> exclusions, PomFile.Dep d) {
            for (PomFile.Exclusion e : exclusions) {
                if (e.matches(d.getGroupId(), d.getArtifactId())) {
                    return true;
                }
            }
            return false;
        }

        /** Management des prüfenden Moduls gewinnt, sonst die Angabe in der POM der Dependency. */
        private static String version(EffectiveModel consumer, EffectiveModel owner, PomFile.Dep d) {
            Managed cm = consumer.managed(d);
            if (cm != null) {
                Interpolated v = cm.version();
                if (v.isResolved() && isFixed(v.value)) {
                    return v.value;
                }
            }
            if (d.getVersion() != null) {
                Interpolated v = owner.interpolate(d.getVersion());
                return v.isResolved() && isFixed(v.value) ? v.value : null;
            }
            Managed m = owner.managed(d);
            if (m == null) {
                return null;
            }
            Interpolated v = m.version();
            return v.isResolved() && isFixed(v.value) ? v.value : null;
        }
    }

    /** Konsolenausgabe; ohne {@code all} nur, was entfernt werden kann oder nicht prüfbar ist. */
    public List<String> lines(boolean all) {
        List<String> out = new ArrayList<String>();
        out.add(summary());
        boolean any = false;
        for (Finding f : findings) {
            if (!all && f.status == Status.USED) {
                continue;
            }
            any = true;
            out.add("");
            out.add(label(f.status) + "  " + f.getFile() + ":" + f.getLine() + (f.managed ? "  (dependencyManagement)" : ""));
            out.add("    " + f.message());
        }
        if (!any) {
            out.add("");
            out.add(Texts.t(findings.isEmpty() ? "excl.none" : "excl.all.used"));
        }
        return out;
    }

    /** Markdown-Tabelle für Ticket oder Review. */
    public String markdown(boolean all) {
        StringBuilder sb = new StringBuilder("### ").append(Texts.t("excl.title")).append("\n\n").append(summary()).append("\n\n");
        List<Finding> shown = new ArrayList<Finding>();
        for (Finding f : findings) {
            if (all || f.status != Status.USED) {
                shown.add(f);
            }
        }
        if (shown.isEmpty()) {
            return sb.append(Texts.t(findings.isEmpty() ? "excl.none" : "excl.all.used")).append("\n").toString();
        }
        sb.append(Texts.t("excl.md.table")).append("\n|---|---|---|---|---|\n");
        for (Finding f : shown) {
            sb.append("| ").append(label(f.status)).append(" | `").append(f.pom.getArtifactId()).append(':').append(f.getLine())
                    .append("` | `").append(f.dependency.ga()).append("` | `").append(f.exclusion.ga()).append("` | ")
                    .append(f.message().replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private String summary() {
        return Texts.t("excl.summary", findings.size(), pomCount, count(Status.STALE), count(Status.REDUNDANT), count(Status.UNKNOWN));
    }

    static String label(Status s) {
        switch (s) {
            case STALE:
                return Texts.t("excl.status.stale");
            case REDUNDANT:
                return Texts.t("excl.status.redundant");
            case USED:
                return Texts.t("excl.status.used");
            default:
                return Texts.t("excl.status.unknown");
        }
    }

    private static String join(List<String> parts) {
        return join(parts, " → ");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
