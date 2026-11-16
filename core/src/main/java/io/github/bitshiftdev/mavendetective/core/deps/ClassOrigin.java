package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath.Entry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Woher kommt eine Klasse (oder ein Paket) im Classpath eines Moduls – und wo läge sie sonst?
 * Grundlage der Build-Doctor-Prüfungen für Regel 9 (Compiler-Fehler) und Regel 10 (Laufzeitkonflikt).
 *
 * <p>Gesucht wird in vier Kreisen, jeder nur, wenn er lokal lesbar ist:
 * <ol>
 *   <li>{@link Where#CLASSPATH}: Einträge des Classpaths in Mavens Reihenfolge (alle Scopes)</li>
 *   <li>{@link Where#DISPLACED}: Versionen, die im Baum verdrängt wurden – durch nearest wins
 *       ({@code CONFLICT}) oder durch {@code dependencyManagement} (verwaltete Version statt der verlangten)</li>
 *   <li>{@link Where#EXCLUDED}: Artifacts, die eine {@code <exclusion>} in einer Projekt-POM entfernt</li>
 *   <li>{@link Where#REPOSITORY}: nur auf Anfrage ({@link #searchRepository}) – JARs im lokalen Repository
 *       unter dem Verzeichnis, das zum Paketnamen passt</li>
 * </ol>
 * Geht nie ins Netz.
 */
public final class ClassOrigin {

    /** Wo eine Fundstelle liegt. */
    public enum Where { CLASSPATH, DISPLACED, EXCLUDED, REPOSITORY }

    /** Was gesucht wird: eine Klasse (binärer Name) oder ein Paket, optional mit Mitglied. */
    public static final class Target {
        private final String name;
        private final boolean pkg;
        private final ClassFile.Member member;

        private Target(String name, boolean pkg, ClassFile.Member member) {
            this.name = name;
            this.pkg = pkg;
            this.member = member;
        }

        public static Target type(String className) {
            return new Target(className, false, null);
        }

        public static Target pkg(String packageName) {
            return new Target(packageName, true, null);
        }

        public static Target member(ClassFile.Member m) {
            return new Target(m.getOwner(), false, m);
        }

        public String getName() { return name; }
        public boolean isPackage() { return pkg; }
        public ClassFile.Member getMember() { return member; }

        /** Name, wie ihn {@link JarIndex} kennt: ohne innere Klassen. */
        String indexName() {
            int dollar = name.indexOf('$');
            return dollar > 0 ? name.substring(0, dollar) : name;
        }

        /** Erste passende Klasse in {@code c} oder {@code null}. */
        String matchIn(JarIndex.Contents c) {
            if (!pkg) {
                return c.contains(indexName()) ? name : null;
            }
            String prefix = name + ".";
            for (int i = 0; i < c.size(); i++) {
                String n = c.name(i);
                if (n.startsWith(prefix) && n.indexOf('.', prefix.length()) < 0) {
                    return n;
                }
            }
            return null;
        }
    }

    /** Eine Fundstelle. */
    public static final class Hit {
        private final Where where;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final Path file;
        private final String className;
        private final long crc;
        private Entry entry;
        private DepNode node;
        private String chosenVersion;
        private boolean managed;
        private ExclusionAudit.Finding exclusion;
        private Boolean hasMember;

        Hit(Where where, String groupId, String artifactId, String version, Path file, String className, long crc) {
            this.where = where;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.file = file;
            this.className = className;
            this.crc = crc;
        }

        public Where getWhere() { return where; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public Path getFile() { return file; }
        /** Gefundene Klasse (bei Paketsuche ein Beispiel aus dem Paket). */
        public String getClassName() { return className; }
        /** CRC32 der Klassendatei – gleiche CRC = gleicher Inhalt. */
        public long getCrc() { return crc; }
        /** Bei {@link Where#CLASSPATH}: der Eintrag. */
        public Entry getEntry() { return entry; }
        /** Bei {@link Where#DISPLACED}: der verdrängte Knoten (bzw. der verwaltete bei {@link #isManaged()}). */
        public DepNode getNode() { return node; }
        /** Bei {@link Where#DISPLACED}: die Version, die stattdessen im Classpath ist. */
        public String getChosenVersion() { return chosenVersion; }
        /** Bei {@link Where#DISPLACED}: {@code true}, wenn {@code dependencyManagement} die Version ersetzt hat. */
        public boolean isManaged() { return managed; }
        /** Bei {@link Where#EXCLUDED}: die Exclusion, die das Artifact entfernt. */
        public ExclusionAudit.Finding getExclusion() { return exclusion; }
        /** Kennt diese Fassung das gesuchte Mitglied? {@code null} = nicht gefragt oder nicht lesbar. */
        public Boolean getHasMember() { return hasMember; }

        public String ga() { return groupId + ":" + artifactId; }

        public String gav() { return ga() + ":" + version; }

        public boolean isSelf() { return entry != null && entry.isSelf(); }

        /** Für Texte: {@code a:v}, beim Modul selbst „(eigene Klassen)“. */
        public String label() {
            return entry != null ? entry.label() : artifactId + ":" + version;
        }

        /** Wer die verdrängte Version verlangt ({@code g:a:v}) – oder {@code null}. */
        public String getRequester() {
            return node == null || node.getParent() == null || node.getParent().getParent() == null ? null : node.getParent().gav();
        }
    }

    private final ModuleClasspath classpath;
    private final DepNode tree;
    private final Target target;
    private final List<Hit> hits = new ArrayList<Hit>();
    private final List<Entry> withoutFile = new ArrayList<Entry>();
    private int checked;

    private ClassOrigin(ModuleClasspath classpath, DepNode tree, Target target) {
        this.classpath = classpath;
        this.tree = tree;
        this.target = target;
    }

    public ModuleClasspath getClasspath() { return classpath; }
    public DepNode getTree() { return tree; }
    public Target getTarget() { return target; }
    /** Alle Fundstellen: erst Classpath (in dessen Reihenfolge), dann verdrängte, dann ausgeschlossene. */
    public List<Hit> getHits() { return Collections.unmodifiableList(hits); }
    /** Classpath-Einträge, deren JAR lokal fehlt (konnten nicht geprüft werden). */
    public List<Entry> getWithoutFile() { return Collections.unmodifiableList(withoutFile); }
    /** Zahl der geprüften JARs und Klassenverzeichnisse im Classpath. */
    public int getChecked() { return checked; }

    public List<Hit> hits(Where where) {
        List<Hit> out = new ArrayList<Hit>();
        for (Hit h : hits) {
            if (h.where == where) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * Sucht {@code target} für ein Modul.
     *
     * @param tree     aufgelöster Baum des Moduls oder {@code null} (dann mit {@link DependencyResolver} nachgebaut)
     * @param reactor  Module des Projekts je {@code groupId:artifactId}
     * @param projectFiles POM-Dateien des Projekts – nur deren Exclusions werden betrachtet
     */
    public static ClassOrigin find(PomLocator locator, PomFile module, Map<String, PomFile> reactor, DepNode tree, Set<Path> projectFiles,
                                   Target target, JarIndex index, Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        DepNode t = tree != null ? tree : DependencyResolver.resolve(locator, module, cancel).getRoot();
        ModuleClasspath cp = ModuleClasspath.of(locator, module, t, reactor);
        ClassOrigin o = new ClassOrigin(cp, t, target);
        o.scanClasspath(index, cancel);
        o.scanDisplaced(locator.getLocalRepository(), index, cancel);
        o.scanExcluded(locator, module, projectFiles, index, cancel);
        if (target.getMember() != null) {
            for (Hit h : o.hits) {
                h.hasMember = hasMember(h.file, target.getMember());
            }
        }
        return o;
    }

    private void scanClasspath(JarIndex index, Cancellation cancel) {
        for (Entry e : classpath.getEntries()) {
            cancel.check();
            if (target.isPackage() && e.isSelf()) {
                continue; // eigene Quellen kennt der Compiler ohnehin
            }
            if (e.getFile() == null) {
                withoutFile.add(e);
                continue;
            }
            checked++;
            Hit h = look(index, target, Where.CLASSPATH, e.getGroupId(), e.getArtifactId(), e.getVersion(), e.getFile());
            if (h != null) {
                h.entry = e;
                hits.add(h);
            }
        }
    }

    private void scanDisplaced(Path repo, JarIndex index, Cancellation cancel) {
        Set<String> seen = new HashSet<String>();
        for (Hit h : hits) {
            seen.add(h.gav());
        }
        List<DepNode> level = new ArrayList<DepNode>(tree.getChildren());
        for (int guard = 0; !level.isEmpty() && guard < 100; guard++) {
            List<DepNode> next = new ArrayList<DepNode>();
            for (DepNode n : level) {
                cancel.check();
                if (n.getState() == DepNode.State.CONFLICT && n.getVersion() != null && n.getRelatedVersion() != null
                        && !n.getVersion().equals(n.getRelatedVersion())) {
                    Hit h = displaced(repo, index, n, n.getVersion(), seen);
                    if (h != null) {
                        h.chosenVersion = n.getRelatedVersion();
                    }
                } else if (n.getState() == DepNode.State.INCLUDED && n.getPremanagedVersion() != null && n.getVersion() != null
                        && !n.getPremanagedVersion().equals(n.getVersion())) {
                    Hit h = displaced(repo, index, n, n.getPremanagedVersion(), seen);
                    if (h != null) {
                        h.chosenVersion = n.getVersion();
                        h.managed = true;
                    }
                }
                next.addAll(n.getChildren());
            }
            level = next;
        }
    }

    private Hit displaced(Path repo, JarIndex index, DepNode n, String version, Set<String> seen) {
        if (!seen.add(n.ga() + ":" + version)) {
            return null;
        }
        Path jar = ModuleClasspath.artifactFile(repo, n.getGroupId(), n.getArtifactId(), version, n.getType(), n.getClassifier());
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        Hit h = look(index, target, Where.DISPLACED, n.getGroupId(), n.getArtifactId(), version, jar);
        if (h != null) {
            h.node = n;
            hits.add(h);
        }
        return h;
    }

    private void scanExcluded(PomLocator locator, PomFile module, Set<Path> projectFiles, JarIndex index, Cancellation cancel) {
        if (!hits(Where.CLASSPATH).isEmpty() && !target.isPackage()) {
            return; // die Klasse ist da – eine Exclusion erklärt nichts
        }
        EffectiveModel model = classpath.getModel();
        Set<String> seen = new HashSet<String>();
        for (PomFile pom : model.getChain()) {
            if (projectFiles != null && !projectFiles.contains(pom.getFile())) {
                continue;
            }
            for (ExclusionAudit.Finding f : ExclusionAudit.audit(locator, pom, null)) {
                cancel.check();
                if (f.getStatus() != ExclusionAudit.Status.USED || f.getPath().isEmpty()
                        || model.declared(f.getDependency().getGroupId(), f.getDependency().getArtifactId()) == null) {
                    continue;
                }
                String last = f.getPath().get(f.getPath().size() - 1);
                String[] gav = last.split(":");
                if (gav.length < 3 || "?".equals(gav[2]) || !seen.add(last)) {
                    continue;
                }
                Path jar = ModuleClasspath.artifactFile(locator.getLocalRepository(), gav[0], gav[1], gav[2], "jar", null);
                if (jar == null || !Files.isRegularFile(jar) || classpath.find(gav[0], gav[1]) != null) {
                    continue;
                }
                Hit h = look(index, target, Where.EXCLUDED, gav[0], gav[1], gav[2], jar);
                if (h != null) {
                    h.exclusion = f;
                    hits.add(h);
                }
            }
        }
    }

    private static Hit look(JarIndex index, Target target, Where where, String g, String a, String v, Path file) {
        try {
            JarIndex.Contents c = index.read(file);
            String found = target.matchIn(c);
            if (found == null) {
                return null;
            }
            return new Hit(where, g, a, v, file, found, c.crc(target.isPackage() ? found : target.indexName()));
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean hasMember(Path file, ClassFile.Member m) {
        try {
            ClassFile c = ClassFile.read(file, m.getOwner());
            return c == null ? null : c.has(m);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lokales Repository

    /**
     * JARs im lokalen Repository, die das Ziel enthalten – gesucht unter dem längsten Verzeichnis, das
     * zum Paketnamen passt (mindestens zwei Ebenen, z. B. {@code org/apache/commons} für
     * {@code org.apache.commons.lang3}). Liegen dort mehr als {@code maxJars} JARs, wird nicht gesucht.
     *
     * @return Fundstellen je {@code groupId:artifactId}, die höchste Version zuerst; leer, wenn nichts passt
     */
    public static List<Hit> searchRepository(Path repo, Target target, JarIndex index, int maxJars, Cancellation cancellation) {
        Cancellation cancel = cancellation == null ? Cancellation.NONE : cancellation;
        if (repo == null || !Files.isDirectory(repo)) {
            return Collections.emptyList();
        }
        String[] segments = (target.isPackage() ? target.getName() : packageOf(target.indexName())).split("\\.");
        for (int k = segments.length; k >= 2; k--) {
            Path dir = repo;
            for (int i = 0; i < k; i++) {
                dir = dir.resolve(segments[i]);
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> jars = jars(dir, maxJars);
            if (jars == null) {
                return Collections.emptyList();
            }
            Map<String, Hit> best = new LinkedHashMap<String, Hit>();
            for (Path jar : jars) {
                cancel.check();
                Path versionDir = jar.getParent();
                Path artifactDir = versionDir.getParent();
                if (artifactDir == null || artifactDir.getParent() == null) {
                    continue;
                }
                String a = artifactDir.getFileName().toString();
                String v = versionDir.getFileName().toString();
                if (!jar.getFileName().toString().equals(a + "-" + v + ".jar")) {
                    continue; // Classifier-JARs (sources, tests …) überspringen
                }
                String g = repo.relativize(artifactDir.getParent()).toString().replace('\\', '/').replace('/', '.');
                Hit h = look(index, target, Where.REPOSITORY, g, a, v, jar);
                if (h == null) {
                    continue;
                }
                Hit old = best.get(h.ga());
                if (old == null || MavenVersion.compare(v, old.version) > 0) {
                    best.put(h.ga(), h);
                }
            }
            if (!best.isEmpty()) {
                return new ArrayList<Hit>(best.values());
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(0, dot);
    }

    /** JARs unter {@code dir}; {@code null}, wenn es mehr als {@code max} sind. */
    private static List<Path> jars(Path dir, final int max) {
        final List<Path> out = new ArrayList<Path>();
        try {
            Files.walkFileTree(dir, Collections.<java.nio.file.FileVisitOption>emptySet(), 12, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
                            && !name.endsWith("-tests.jar")) {
                        out.add(file);
                        if (out.size() > max) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return out;
        }
        return out.size() > max ? null : out;
    }
}
