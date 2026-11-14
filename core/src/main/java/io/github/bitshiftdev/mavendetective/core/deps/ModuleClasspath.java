package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Declared;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel.Managed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classpath eines Moduls: die JARs (bzw. {@code target/classes} von Reactor-Modulen) in der
 * Reihenfolge, in der Maven 3 sie übergibt – Tiefe zuerst, wie im Baum. Grundlage für Modul A3.
 *
 * <p>Mit Baum (IDE-Modell oder {@code dependency:tree -Dverbose}) sind die Einträge genau die
 * Knoten mit Status {@code INCLUDED}. Ohne Baum wird der Classpath aus den POMs im lokalen
 * Repository nachgebaut (nearest wins, bei gleicher Tiefe die zuerst deklarierte Dependency);
 * Reihenfolge und Konfliktauflösung sind dann geschätzt ({@link #isFromTree()} {@code false}).
 * Geht nie ins Netz.
 */
public final class ModuleClasspath {

    private static final Pattern TIMESTAMPED = Pattern.compile("^(.*)-(\\d{8}\\.\\d{6})-(\\d+)$");

    /** Ein Eintrag im Classpath. */
    public static final class Entry {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String type;
        private final String classifier;
        private final String scope;
        private final List<String> path;
        private final Path file;
        private final String expected;
        private final boolean self;
        private final boolean reactor;

        Entry(String groupId, String artifactId, String version, String type, String classifier, String scope, List<String> path,
              Path file, String expected, boolean self, boolean reactor) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type == null || type.isEmpty() ? "jar" : type;
            this.classifier = classifier == null || classifier.isEmpty() ? null : classifier;
            this.scope = scope == null || scope.isEmpty() ? "compile" : scope;
            this.path = Collections.unmodifiableList(new ArrayList<String>(path));
            this.file = file;
            this.expected = expected;
            this.self = self;
            this.reactor = reactor;
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public String getType() { return type; }
        public String getClassifier() { return classifier; }
        public String getScope() { return scope; }
        /** Pfad ab der direkten Dependency ({@code g:a:v}, inklusive dieses Eintrags); leer für das Modul selbst. */
        public List<String> getPath() { return path; }
        /** JAR oder Klassenverzeichnis; {@code null}, wenn es lokal nicht liegt. */
        public Path getFile() { return file; }
        /** Wo das JAR erwartet wurde (für die Meldung, wenn es fehlt). */
        public String getExpected() { return expected; }
        /** Die eigenen Klassen des Moduls ({@code target/classes}). */
        public boolean isSelf() { return self; }
        /** Ein anderes Modul desselben Reactors. */
        public boolean isReactor() { return reactor; }
        public boolean isDirect() { return path.size() == 1; }

        public String ga() { return groupId + ":" + artifactId; }

        public String gav() {
            return groupId + ":" + artifactId + (classifier == null ? "" : ":" + classifier) + ":" + version;
        }

        /** Landet zur Laufzeit im Classpath (nicht nur in Tests). */
        public boolean isMain() {
            return !"test".equals(scope);
        }

        /** Für die Anzeige: {@code a:v}, beim Modul selbst „(eigene Klassen)“. */
        public String label() {
            return self ? artifactId + " (" + Texts.t("classpath.own.classes") + ")" : artifactId + (classifier == null ? "" : ":" + classifier) + ":" + version;
        }

        @Override
        public String toString() {
            return self ? label() : gav() + " (" + scope + ")";
        }
    }

    private final PomFile pom;
    private final EffectiveModel model;
    private final boolean fromTree;
    private final List<Entry> entries = new ArrayList<Entry>();
    /** Je {@code groupId:artifactId} die direkten Dependencies, über die es (auch verdrängt) in den Baum kommt. */
    private final Map<String, Set<String>> viaDirect = new HashMap<String, Set<String>>();
    private final Set<String> missing = new LinkedHashSet<String>();

    private ModuleClasspath(PomFile pom, EffectiveModel model, boolean fromTree) {
        this.pom = pom;
        this.model = model;
        this.fromTree = fromTree;
    }

    public PomFile getPom() { return pom; }
    public EffectiveModel getModel() { return model; }
    public String getName() { return pom.getArtifactId(); }
    public String ga() { return pom.getGroupId() + ":" + pom.getArtifactId(); }
    public boolean isFromTree() { return fromTree; }
    public List<Entry> getEntries() { return Collections.unmodifiableList(entries); }
    /** POMs, die beim Nachbauen ohne Baum fehlten. */
    public List<String> getMissing() { return new ArrayList<String>(missing); }

    /** Eintrag mit diesem JAR bzw. Klassenverzeichnis; {@code null}, wenn keiner passt. */
    public Entry find(Path file) {
        if (file == null) {
            return null;
        }
        Path wanted = file.toAbsolutePath().normalize();
        Entry byName = null;
        for (Entry e : entries) {
            if (e.getFile() == null) {
                continue;
            }
            Path p = e.getFile().toAbsolutePath().normalize();
            if (p.equals(wanted)) {
                return e;
            }
            if (byName == null && !e.isSelf() && p.getFileName().equals(wanted.getFileName())) {
                byName = e; // anderes lokales Repository oder Symlink: Dateiname a-v.jar ist eindeutig genug
            }
        }
        return byName;
    }

    /** Erster Eintrag (nicht das Modul selbst) mit {@code groupId:artifactId}; {@code null}, wenn keiner. */
    public Entry find(String groupId, String artifactId) {
        for (Entry e : entries) {
            if (!e.isSelf() && e.getGroupId().equals(groupId) && e.getArtifactId().equals(artifactId)) {
                return e;
            }
        }
        return null;
    }

    /** Direkte Dependencies ({@code groupId:artifactId}), deren Teilbaum {@code ga} enthält – in Deklarationsreihenfolge. */
    public List<String> directDependenciesBringing(String ga) {
        Set<String> s = viaDirect.get(ga);
        return s == null ? Collections.<String>emptyList() : new ArrayList<String>(s);
    }

    /**
     * Classpath eines Moduls.
     *
     * @param tree aufgelöster Baum des Moduls oder {@code null} (dann aus den POMs nachgebaut)
     */
    public static ModuleClasspath of(PomLocator locator, PomFile pom, DepNode tree, Map<String, PomFile> reactor) {
        EffectiveModel model = locator.model(pom);
        ModuleClasspath cp = new ModuleClasspath(pom, model, tree != null);
        Path classes = pom.getFile().getParent().resolve("target").resolve("classes");
        if (Files.isDirectory(classes)) {
            cp.entries.add(new Entry(pom.getGroupId(), pom.getArtifactId(), pom.getVersion(), pom.getPackaging(), null, "compile",
                    Collections.<String>emptyList(), classes, classes.toString(), true, false));
        }
        if (tree != null) {
            cp.fromTree(tree, locator.getLocalRepository(), reactor);
        } else {
            cp.fromPoms(locator, reactor);
        }
        return cp;
    }

    private void fromTree(DepNode tree, Path repo, Map<String, PomFile> reactor) {
        Set<String> seen = new HashSet<String>();
        for (DepNode direct : tree.getChildren()) {
            walk(direct, direct.ga(), repo, reactor, seen, 0);
        }
    }

    /** Tiefe zuerst wie Mavens Classpath; verdrängte Knoten zählen nur für {@link #directDependenciesBringing}. */
    private void walk(DepNode n, String direct, Path repo, Map<String, PomFile> reactor, Set<String> seen, int depth) {
        if (n.getState() != DepNode.State.EXCLUDED) {
            via(n.ga(), direct);
        }
        if (n.getState() == DepNode.State.INCLUDED && n.getVersion() != null
                && seen.add(n.ga() + ":" + n.getType() + ":" + (n.getClassifier() == null ? "" : n.getClassifier()))) {
            List<String> path = new ArrayList<String>();
            for (DepNode p : n.path()) {
                path.add(p.gav());
            }
            add(n.getGroupId(), n.getArtifactId(), n.getVersion(), n.getType(), n.getClassifier(), n.getScope(), path, repo, reactor);
        }
        if (depth < 60) {
            for (DepNode c : n.getChildren()) {
                walk(c, direct, repo, reactor, seen, depth + 1);
            }
        }
    }

    /** Kandidat beim Nachbauen ohne Baum. */
    private static final class Candidate {
        final String groupId;
        final String artifactId;
        final String version;
        final String type;
        final String classifier;
        final String scope;
        final List<String> path;
        final int directIndex;
        final int order;

        Candidate(String groupId, String artifactId, String version, String type, String classifier, String scope, List<String> path,
                  int directIndex, int order) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.classifier = classifier;
            this.scope = scope;
            this.path = path;
            this.directIndex = directIndex;
            this.order = order;
        }
    }

    private void fromPoms(PomLocator locator, Map<String, PomFile> reactor) {
        Map<String, Candidate> winners = new LinkedHashMap<String, Candidate>();
        int index = 0;
        int order = 0;
        for (Declared d : model.getDependencies().values()) {
            PomFile.Dep dep = d.dep;
            if ("pom".equals(dep.getType()) || dep.isImport()) {
                continue;
            }
            String version = ExclusionAudit.dependencyVersion(model, dep);
            Managed managed = model.managed(dep);
            String scope = dep.getScope() != null ? dep.getScope()
                    : managed != null && managed.dep.getScope() != null ? managed.dep.getScope() : "compile";
            if ("import".equals(scope)) {
                continue;
            }
            int directIndex = index++;
            String directGav = dep.ga() + ":" + (version == null ? "?" : version);
            via(dep.ga(), dep.ga());
            offer(winners, new Candidate(dep.getGroupId(), dep.getArtifactId(), version, dep.getType(), dep.getClassifier(), scope,
                    Collections.singletonList(directGav), directIndex, order++));
            if (version == null) {
                missing.add(Texts.t("excl.missing.version", dep.ga()));
                continue;
            }
            List<PomFile.Exclusion> exclusions = new ArrayList<PomFile.Exclusion>(dep.getExclusionEntries());
            if (managed != null) {
                exclusions.addAll(managed.dep.getExclusionEntries()); // Maven mischt Exclusions aus dem Management ein
            }
            ExclusionAudit.Closure closure = ExclusionAudit.Closure.of(locator, model, dep.getGroupId(), dep.getArtifactId(), version,
                    exclusions);
            missing.addAll(closure.missing);
            for (Map.Entry<String, List<String>> e : closure.paths.entrySet()) {
                via(e.getKey(), dep.ga());
                PomFile.Dep t = closure.decls.get(e.getKey());
                String last = e.getValue().get(e.getValue().size() - 1);
                String v = last.substring(last.lastIndexOf(':') + 1);
                String ts = "test".equals(scope) || "provided".equals(scope) ? scope
                        : "runtime".equals(t.getScope()) || "runtime".equals(scope) ? "runtime" : "compile";
                offer(winners, new Candidate(t.getGroupId(), t.getArtifactId(), "?".equals(v) ? null : v, t.getType(), t.getClassifier(),
                        ts, e.getValue(), directIndex, order++));
            }
        }
        List<Candidate> sorted = new ArrayList<Candidate>(winners.values());
        Collections.sort(sorted, new java.util.Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return a.directIndex != b.directIndex ? a.directIndex - b.directIndex : a.order - b.order;
            }
        });
        for (Candidate c : sorted) {
            if (c.version != null) {
                add(c.groupId, c.artifactId, c.version, c.type, c.classifier, c.scope, c.path, locator.getLocalRepository(), reactor);
            }
        }
    }

    /** Nearest wins; bei gleicher Tiefe gewinnt die zuerst deklarierte Dependency. */
    private static void offer(Map<String, Candidate> winners, Candidate c) {
        String key = c.groupId + ":" + c.artifactId;
        Candidate old = winners.get(key);
        if (old == null || c.path.size() < old.path.size()) {
            winners.put(key, c);
        }
    }

    private void via(String ga, String direct) {
        Set<String> s = viaDirect.get(ga);
        if (s == null) {
            s = new LinkedHashSet<String>();
            viaDirect.put(ga, s);
        }
        s.add(direct);
    }

    private void add(String g, String a, String v, String type, String classifier, String scope, List<String> path, Path repo,
                     Map<String, PomFile> reactor) {
        String t = type == null || type.isEmpty() ? "jar" : type;
        if (!hasClasses(t)) {
            return;
        }
        PomFile module = reactor == null ? null : reactor.get(g + ":" + a);
        if (module != null && v.equals(module.getVersion())) {
            Path classes = module.getFile().getParent().resolve("target").resolve("classes");
            Path file = Files.isDirectory(classes) ? classes : null;
            if (file == null) {
                Path jar = module.getFile().getParent().resolve("target").resolve(a + "-" + v + ".jar");
                file = Files.isRegularFile(jar) ? jar : null;
            }
            entries.add(new Entry(g, a, v, t, classifier, scope, path, file, classes.toString(), false, true));
            return;
        }
        Path expected = artifactFile(repo, g, a, v, t, classifier);
        Path file = expected != null && Files.isRegularFile(expected) ? expected : alternative(expected, repo, g, a, v, t, classifier);
        entries.add(new Entry(g, a, v, t, classifier, scope, path, file, expected == null ? null : expected.toString(), false, false));
    }

    /** Typen ohne Klassen im Classpath (POM, WAR, EAR, Archive, Quellen). */
    private static boolean hasClasses(String type) {
        return !"pom".equals(type) && !"war".equals(type) && !"ear".equals(type) && !"rar".equals(type) && !"zip".equals(type)
                && !type.startsWith("tar") && !"java-source".equals(type) && !"javadoc".equals(type);
    }

    /** Datei im lokalen Repository nach Mavens Layout; Typ bestimmt Endung und ggf. Classifier. */
    static Path artifactFile(Path repo, String g, String a, String v, String type, String classifier) {
        if (repo == null) {
            return null;
        }
        String c = classifier;
        String ext = type;
        if ("test-jar".equals(type)) {
            ext = "jar";
            c = c == null ? "tests" : c;
        } else if ("ejb-client".equals(type)) {
            ext = "jar";
            c = c == null ? "client" : c;
        } else if ("ejb".equals(type) || "bundle".equals(type) || "maven-plugin".equals(type) || "jar".equals(type)) {
            ext = "jar";
        }
        String baseVersion = v;
        Matcher m = TIMESTAMPED.matcher(v);
        if (m.matches()) {
            baseVersion = m.group(1) + "-SNAPSHOT";
        }
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        return dir.resolve(a).resolve(baseVersion).resolve(a + "-" + v + (c == null ? "" : "-" + c) + "." + ext);
    }

    /** Zeitgestempelter Snapshot: oft liegt nur {@code a-1.0-SNAPSHOT.jar}. */
    private static Path alternative(Path expected, Path repo, String g, String a, String v, String type, String classifier) {
        Matcher m = TIMESTAMPED.matcher(v);
        if (expected == null || !m.matches()) {
            return null;
        }
        Path p = artifactFile(repo, g, a, m.group(1) + "-SNAPSHOT", type, classifier);
        return p != null && Files.isRegularFile(p) ? p : null;
    }
}
