package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Interpolated;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut den kompletten Dependency-Baum eines Moduls aus POMs nach – so, wie {@code mvn dependency:tree
 * -Dverbose} ihn zeigt, aber ohne Maven und ohne Netz. Grundlage für den Upgrade-Simulator (Modul A6):
 * Er rechnet denselben Baum einmal mit den POMs auf der Platte und einmal mit der geänderten Fassung,
 * damit Unterschiede nur von der Änderung kommen und nicht von Abweichungen zwischen zwei Verfahren.
 *
 * <p>Nachgebaut wird Mavens Collector: Breitensuche, nearest wins (bei gleicher Tiefe gewinnt, was
 * zuerst deklariert ist), {@code dependencyManagement} des Moduls gilt für Version, Scope und
 * Exclusions transitiver Dependencies, transitive {@code test}/{@code provided}/{@code system}- und
 * {@code optional}-Dependencies fallen weg, Exclusions gelten für den ganzen Teilbaum, Scopes werden
 * wie im {@code JavaScopeDeriver} abgeleitet und bei Konflikten wie im {@code JavaScopeSelector}
 * gewählt. Fehlt eine POM lokal, endet der Ast dort und die Koordinaten stehen in
 * {@link Tree#getMissing()}.
 */
public final class DependencyResolver {

    private static final int MAX_NODES = 20000;

    /** Ergebnis: Baum (Wurzel = Modul) und was lokal fehlte. */
    public static final class Tree {
        private final DepNode root;
        private final List<String> missing;
        private final boolean truncated;

        Tree(DepNode root, List<String> missing, boolean truncated) {
            this.root = root;
            this.missing = missing;
            this.truncated = truncated;
        }

        public DepNode getRoot() { return root; }
        /** Koordinaten ({@code g:a:v}) bzw. Beschreibungen dessen, was fehlte; leer = vollständig. */
        public List<String> getMissing() { return Collections.unmodifiableList(missing); }
        /** Mehr als {@value #MAX_NODES} Knoten – der Rest wurde nicht mehr verfolgt. */
        public boolean isTruncated() { return truncated; }
    }

    /** Knoten während des Aufbaus; Scope und Gewinner werden erst am Ende endgültig. */
    private static final class N {
        final String groupId;
        final String artifactId;
        final String type;
        final String classifier;
        final String version;
        /** Scope nach Management, vor der Ableitung über den Parent. */
        final String declaredScope;
        final String premanagedVersion;
        final String premanagedScope;
        final N parent;
        final int depth;
        final List<PomFile.Exclusion> exclusions;
        final List<N> children = new ArrayList<N>();
        /** Alle Vorkommen desselben Artifacts (nur beim Gewinner gefüllt, inklusive ihm selbst). */
        final List<N> occurrences = new ArrayList<N>();
        String scope;
        N winner;

        N(String groupId, String artifactId, String type, String classifier, String version, String declaredScope,
          String premanagedVersion, String premanagedScope, N parent, List<PomFile.Exclusion> exclusions) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.type = type == null || type.isEmpty() ? "jar" : type;
            this.classifier = classifier == null || classifier.isEmpty() ? null : classifier;
            this.version = version;
            this.declaredScope = declaredScope;
            this.premanagedVersion = premanagedVersion;
            this.premanagedScope = premanagedScope;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
            this.exclusions = exclusions;
        }

        String key() {
            return groupId + ":" + artifactId + ":" + type + ":" + (classifier == null ? "" : classifier);
        }

        boolean isWinner() {
            return winner == this;
        }
    }

    private DependencyResolver() {
    }

    /** Baum des Moduls {@code module}, nur aus POMs im Projekt und im lokalen Repository. */
    public static Tree resolve(PomLocator locator, PomFile module) {
        return resolve(locator, module, Cancellation.NONE);
    }

    public static Tree resolve(PomLocator locator, PomFile module, Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        EffectiveModel consumer = locator.model(module);
        Set<String> missing = new LinkedHashSet<String>();
        N root = new N(module.getGroupId(), module.getArtifactId(), module.getPackaging(), null, module.getVersion(), null, null, null,
                null, Collections.<PomFile.Exclusion>emptyList());
        Map<String, N> winners = new LinkedHashMap<String, N>(); // Reihenfolge = Breitensuche
        List<N> queue = new ArrayList<N>();
        int count = 0;

        for (Declared d : consumer.getDependencies().values()) {
            PomFile.Dep dep = d.dep;
            if (dep.isImport()) {
                continue;
            }
            Managed managed = consumer.managed(dep);
            String scope = dep.getScope() != null && !dep.getScope().isEmpty() ? dep.getScope()
                    : managed != null && managed.dep.getScope() != null ? managed.dep.getScope() : "compile";
            if ("import".equals(scope)) {
                continue;
            }
            String version = ExclusionAudit.dependencyVersion(consumer, dep);
            List<PomFile.Exclusion> exclusions = new ArrayList<PomFile.Exclusion>(dep.getExclusionEntries());
            if (managed != null) {
                exclusions.addAll(managed.dep.getExclusionEntries()); // Maven mischt Exclusions aus dem Management ein
            }
            N n = new N(dep.getGroupId(), dep.getArtifactId(), dep.getType(), dep.getClassifier(), version, scope, null, null, root,
                    exclusions);
            if (version == null) {
                missing.add(Texts.t("resolver.version.undetermined", dep.ga(), module.getArtifactId()));
            }
            offer(root, n, winners, queue);
            count++;
        }

        for (int i = 0; i < queue.size(); i++) {
            N n = queue.get(i);
            if (n.version == null) {
                continue;
            }
            if (count >= MAX_NODES) {
                return finish(root, winners, missing, true);
            }
            cancel.check();
            PomFile pom = locator.byCoordinates(n.groupId, n.artifactId, n.version);
            if (pom == null) {
                missing.add(n.groupId + ":" + n.artifactId + ":" + n.version);
                continue;
            }
            EffectiveModel owner = locator.model(pom);
            for (EffectiveModel.ImportStep s : owner.getMissingImports()) {
                missing.add(s.declaration.ga() + ":" + (s.version.value == null ? "?" : s.version.value)
                        + " (" + Texts.t("resolver.bom.for", n.groupId + ":" + n.artifactId) + ")");
            }
            List<PomFile> chain = owner.getChain();
            PomFile last = chain.get(chain.size() - 1);
            if (last.hasParent() && locator.parentOf(last) == null) {
                missing.add(last.parentGav());
            }
            for (Declared d : owner.getDependencies().values()) {
                PomFile.Dep dep = d.dep;
                if (dep.isOptional() || dep.isImport() || excluded(n.exclusions, dep)) {
                    continue;
                }
                Managed own = owner.managed(dep);
                String ownScope = dep.getScope() != null && !dep.getScope().isEmpty() ? dep.getScope()
                        : own != null && own.dep.getScope() != null ? own.dep.getScope() : "compile";
                if (!"compile".equals(ownScope) && !"runtime".equals(ownScope)) {
                    continue;   // test, provided, system: nicht transitiv
                }
                String ownVersion = ownVersion(owner, dep, own);
                Managed cm = consumer.managed(dep);
                String version = ownVersion;
                String scope = ownScope;
                String premanagedVersion = null;
                String premanagedScope = null;
                List<PomFile.Exclusion> exclusions = new ArrayList<PomFile.Exclusion>(n.exclusions);
                exclusions.addAll(dep.getExclusionEntries());
                if (cm != null) {
                    Interpolated v = cm.version();
                    if (v.isResolved() && isFixed(v.value) && !v.value.equals(ownVersion)) {
                        premanagedVersion = ownVersion;
                        version = v.value;
                    }
                    String ms = cm.dep.getScope();
                    if (ms != null && !ms.isEmpty() && !ms.equals(ownScope) && !"import".equals(ms)) {
                        premanagedScope = ownScope;
                        scope = ms;
                    }
                    exclusions.addAll(cm.dep.getExclusionEntries());
                }
                N child = new N(dep.getGroupId(), dep.getArtifactId(), dep.getType(), dep.getClassifier(), version, scope,
                        premanagedVersion, premanagedScope, n, exclusions);
                if (version == null) {
                    missing.add(dep.ga() + " (" + Texts.t("excl.missing.undetermined", n.groupId + ":" + n.artifactId + ":" + n.version) + ")");
                }
                offer(n, child, winners, queue);
                count++;
            }
        }
        return finish(root, winners, missing, false);
    }

    /** Hängt den Knoten an; der erste mit diesem Schlüssel gewinnt und wird weiter verfolgt. */
    private static void offer(N parent, N child, Map<String, N> winners, List<N> queue) {
        parent.children.add(child);
        N w = winners.get(child.key());
        if (w == null) {
            child.winner = child;
            child.occurrences.add(child);
            winners.put(child.key(), child);
            if (!isAncestor(child, parent)) {
                queue.add(child);
            }
        } else {
            child.winner = w;
            w.occurrences.add(child);
        }
    }

    private static boolean isAncestor(N n, N from) {
        for (N p = from; p != null; p = p.parent) {
            if (p.groupId.equals(n.groupId) && p.artifactId.equals(n.artifactId)) {
                return true;
            }
        }
        return false;
    }

    private static Tree finish(N root, Map<String, N> winners, Set<String> missing, boolean truncated) {
        // Scopes von oben nach unten ableiten (winners ist nach Tiefe sortiert); der Gewinner nimmt den
        // weitesten seiner Vorkommen. Ein paar Runden, weil ein Vorkommen tiefer hängen kann als sein Gewinner.
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            for (N w : winners.values()) {
                String s;
                if (w.depth == 1) {
                    s = w.declaredScope;
                } else {
                    Set<String> scopes = new LinkedHashSet<String>();
                    for (N o : w.occurrences) {
                        String ps = o.parent.depth == 0 ? null : o.parent.winner.scope;
                        if (o.parent.depth == 0 || ps != null) {
                            scopes.add(derive(ps, o.declaredScope));
                        }
                    }
                    s = choose(scopes);
                }
                if (s != null && !s.equals(w.scope)) {
                    w.scope = s;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        DepNode out = new DepNode(root.groupId, root.artifactId, root.type, null, root.version, null);
        for (N c : root.children) {
            convert(out, c, 0);
        }
        return new Tree(out, new ArrayList<String>(missing), truncated);
    }

    private static void convert(DepNode parent, N n, int depth) {
        String scope = n.isWinner() ? n.scope : derive(n.parent.depth == 0 ? null : n.parent.winner.scope, n.declaredScope);
        DepNode d = parent.add(new DepNode(n.groupId, n.artifactId, n.type, n.classifier, n.version, scope));
        d.setPremanagedVersion(n.premanagedVersion).setPremanagedScope(n.premanagedScope);
        if (!n.isWinner()) {
            boolean same = n.version == null ? n.winner.version == null : n.version.equals(n.winner.version);
            if (isAncestor(n, n.parent)) {
                d.setState(DepNode.State.CYCLE);
            } else if (same) {
                d.setState(DepNode.State.DUPLICATE);
            } else {
                d.setState(DepNode.State.CONFLICT).setRelatedVersion(n.winner.version);
            }
            return;
        }
        if (depth < 60) {
            for (N c : n.children) {
                convert(d, c, depth + 1);
            }
        }
    }

    /** Scope eines Kindes unter einem Parent mit {@code parentScope} ({@code null} = direkt). */
    static String derive(String parentScope, String scope) {
        String s = scope == null || scope.isEmpty() ? "compile" : scope;
        if ("system".equals(s) || "test".equals(s) || parentScope == null || "compile".equals(parentScope)) {
            return s;
        }
        if ("test".equals(parentScope) || "runtime".equals(parentScope)) {
            return parentScope;
        }
        if ("system".equals(parentScope) || "provided".equals(parentScope)) {
            return "provided";
        }
        return "runtime";
    }

    /** Der weiteste Scope bei einem Konflikt. */
    private static String choose(Set<String> scopes) {
        if (scopes.isEmpty()) {
            return null;
        }
        if (scopes.size() > 1) {
            scopes.remove("system");
        }
        if (scopes.size() == 1) {
            return scopes.iterator().next();
        }
        for (String s : new String[] {"compile", "runtime", "provided", "test"}) {
            if (scopes.contains(s)) {
                return s;
            }
        }
        return scopes.iterator().next();
    }

    private static boolean excluded(List<PomFile.Exclusion> exclusions, PomFile.Dep d) {
        for (PomFile.Exclusion e : exclusions) {
            if (e.matches(d.getGroupId(), d.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    /** Version, wie die POM der Dependency sie für ihre eigene Dependency festlegt. */
    private static String ownVersion(EffectiveModel owner, PomFile.Dep d, Managed own) {
        if (d.getVersion() != null) {
            Interpolated v = owner.interpolate(d.getVersion());
            return v.isResolved() && isFixed(v.value) ? v.value : null;
        }
        if (own == null) {
            return null;
        }
        Interpolated v = own.version();
        return v.isResolved() && isFixed(v.value) ? v.value : null;
    }

    private static boolean isFixed(String v) {
        return v != null && !v.isEmpty() && !v.startsWith("[") && !v.startsWith("(");
    }
}
