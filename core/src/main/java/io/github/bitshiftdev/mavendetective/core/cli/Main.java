package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.check.Checks;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyTreeParser;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.PomFetcher;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplainer;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Kommandozeile.
 *
 * <pre>
 * java -jar maven-detective-core.jar diagnose build.log [--project DIR] [--markdown] [--catalog extra.yaml]
 * java -jar maven-detective-core.jar diagnose events.jsonl
 * mvn verify 2>&amp;1 | java -jar maven-detective-core.jar diagnose -
 * java -jar maven-detective-core.jar evaluate logs/ [--catalog extra.yaml]
 * java -jar maven-detective-core.jar validate-catalog extra.yaml
 * java -jar maven-detective-core.jar changes [--project DIR]
 * java -jar maven-detective-core.jar why groupId:artifactId [--project DIR] [--module DIR] [--tree tree.txt | -] [--markdown]
 * java -jar maven-detective-core.jar matrix [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--pin groupId:artifactId[:version]]
 * java -jar maven-detective-core.jar exclusions [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--remove]
 * java -jar maven-detective-core.jar duplicates [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--exclude]
 * java -jar maven-detective-core.jar analyze [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--declare] [--remove]
 * java -jar maven-detective-core.jar upgrade groupId:artifactId:version | property=wert [--project DIR] [--tree tree.txt | -]
 *     [--all] [--markdown] [--manage] [--fetch] [--apply] [--local-repo DIR]
 * </pre>
 *
 * {@code evaluate} ist das Werkzeug für Gate 1: alle Logs eines Ordners einordnen und, falls
 * {@code labels.properties} (Dateiname=erwartete-regel-id) vorliegt, die Trefferquote messen.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws java.io.UnsupportedEncodingException {
        // Immer UTF-8: Umlaute und Rahmenzeichen sollen auch bei LANG=C (CI-Container) lesbar bleiben
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8");
        System.exit(run(args, out, err, System.in));
    }

    static int run(String[] args, PrintStream out, PrintStream err, InputStream stdin) {
        if (args.length < 1) {
            usage(err);
            return 2;
        }
        try {
            String command = args[0];
            Options o = Options.parse(args);
            if ("diagnose".equals(command) && o.target != null) {
                return diagnose(o, out, stdin);
            }
            if ("changes".equals(command)) {
                return changes(o, out);
            }
            if ("why".equals(command) && o.target != null) {
                return why(o, out, err, stdin);
            }
            if ("matrix".equals(command)) {
                return matrix(o, out, err, stdin);
            }
            if ("exclusions".equals(command)) {
                return exclusions(o, out, err, stdin);
            }
            if ("duplicates".equals(command)) {
                return duplicates(o, out, err, stdin);
            }
            if ("analyze".equals(command)) {
                return analyze(o, out, err, stdin);
            }
            if ("upgrade".equals(command) && o.target != null) {
                return upgrade(o, out, err, stdin);
            }
            if ("evaluate".equals(command) && o.target != null) {
                return evaluate(o, out);
            }
            if ("validate-catalog".equals(command) && o.target != null) {
                RuleCatalog c = RuleCatalog.load(Paths.get(o.target));
                new BuildDoctor(RuleCatalog.loadDefault().merge(c), Checks.defaults());
                out.println("OK: " + c.getRules().size() + " Regeln");
                return 0;
            }
            usage(err);
            return 2;
        } catch (RuleCatalog.CatalogException e) {
            err.println("Fehler im Katalog: " + e.getMessage());
            return 3;
        } catch (IOException e) {
            err.println("Fehler: " + e.getMessage());
            return 1;
        }
    }

    private static int diagnose(Options o, PrintStream out, InputStream stdin) throws IOException {
        BuildFailure failure;
        if ("-".equals(o.target)) {
            failure = MavenLogParser.parse(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        } else {
            failure = load(Paths.get(o.target));
        }
        Path project = o.project != null ? Paths.get(o.project) : null;
        if (project == null && failure.getSession().getProjectDirectory() == null) {
            project = Paths.get("").toAbsolutePath();
        }
        List<Diagnosis> result = doctor(o).diagnose(failure, Environment.forBuild(failure, project));
        Path snapshotProject = project != null ? project
                : failure.getSession().getProjectDirectory() == null ? null : Paths.get(failure.getSession().getProjectDirectory());
        SnapshotDiff changes = snapshotProject == null || o.idOnly ? null : SnapshotStore.defaultStore().changesSinceGreen(snapshotProject);
        if (result.isEmpty()) {
            out.println("Keine Fehler im Log gefunden.");
            return 0;
        }
        if (o.idOnly) {
            out.println(result.get(0).getRuleId());
        } else if (o.markdown) {
            out.print(Reports.markdown(result, failure, changes));
        } else {
            for (String line : Reports.consoleCard(result, changes)) {
                out.println(line);
            }
        }
        return result.get(0).isUnknown() ? 4 : 0;
    }

    /** „Was hat sich geändert?“ – letzter grüner Build gegen letzten Lauf, ohne Log. */
    private static int changes(Options o, PrintStream out) {
        Path project = Paths.get(o.project != null ? o.project : o.target != null ? o.target : "").toAbsolutePath();
        SnapshotStore store = SnapshotStore.defaultStore();
        BuildSnapshot latest = store.loadLatest(project);
        BuildSnapshot green = store.loadGreen(project, latest == null ? BuildSnapshot.ORIGIN_EXTENSION : latest.getOrigin());
        if (green == null) {
            out.println("Noch kein grüner Build gespeichert für " + project + " (Ablage: " + store.projectDir(project) + ").");
            out.println("Die Extension speichert ihn automatisch: mvn -Dmaven.ext.class.path=<extension.jar> verify");
            return 1;
        }
        if (latest == null || !Boolean.FALSE.equals(latest.getSuccessful())) {
            out.println("Der letzte Lauf war grün (" + green.getCreatedAt() + "). Nichts zu vergleichen.");
            return 0;
        }
        SnapshotDiff diff = SnapshotDiff.compare(green, latest);
        out.println(diff.headline() + " bis zum letzten Lauf (" + latest.getCreatedAt() + "):");
        if (diff.isEmpty()) {
            out.println("  keine Änderung an Maven, JDK, Profilen, Parent, Plugins oder Dependencies erkannt.");
        }
        for (String line : diff.lines(Integer.MAX_VALUE)) {
            out.println("  - " + line);
        }
        return 0;
    }

    /**
     * Modul A1: Warum steht diese Version im Classpath? Ohne {@code --module} werden alle Module
     * erklärt, die die Dependency deklarieren oder (laut Baum) enthalten.
     */
    private static int why(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        String[] ga = o.target.split(":");
        if (ga.length < 2) {
            err.println("Erwartet groupId:artifactId, z. B. com.fasterxml.jackson.core:jackson-databind");
            return 2;
        }
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        PomLocator locator = new PomLocator(root, localRepository(o, root));
        VersionExplainer explainer = new VersionExplainer(locator, root);
        List<PomFile> modules = new ArrayList<PomFile>();
        if (o.module != null) {
            PomFile m = locator.load(root.resolve(o.module));
            if (m == null) {
                err.println("Keine lesbare POM unter " + root.resolve(o.module));
                return 1;
            }
            modules.add(m);
        } else {
            for (ProjectPoms.Pom p : new ProjectPoms(root).all()) {
                PomFile m = locator.load(p.file);
                if (m == null) {
                    continue;
                }
                DepNode tree = trees.get(m.getGroupId() + ":" + m.getArtifactId());
                if (tree != null && !tree.findAll(ga[0], ga[1]).isEmpty()
                        || locator.model(m).declared(ga[0], ga[1]) != null) {
                    modules.add(m);
                }
            }
            if (modules.isEmpty()) {
                PomFile rootPom = locator.load(root.resolve("pom.xml"));
                if (rootPom == null) {
                    err.println("Keine pom.xml in " + root + " (--project DIR angeben)");
                    return 1;
                }
                modules.add(rootPom);
            }
        }
        boolean first = true;
        for (PomFile m : modules) {
            VersionExplanation x = explainer.explain(m, ga[0], ga[1], trees.get(m.getGroupId() + ":" + m.getArtifactId()));
            if (!first) {
                out.println();
            }
            first = false;
            if (o.markdown) {
                out.print(x.markdown());
            } else {
                for (String line : x.lines()) {
                    out.println(line);
                }
            }
        }
        return 0;
    }

    private static Path projectRoot(Options o) {
        return Paths.get(o.project != null ? o.project : "").toAbsolutePath().normalize();
    }

    private static Path localRepository(Options o, Path root) {
        return o.localRepo != null ? Paths.get(o.localRepo)
                : Environment.builder().projectDirectory(root).userHome(Paths.get(System.getProperty("user.home")))
                .env(System.getenv()).build().getLocalRepository();
    }

    /** Bäume aus {@code --tree} (Datei oder stdin) je {@code groupId:artifactId} des Moduls. */
    private static Map<String, DepNode> trees(Options o, PrintStream err, InputStream stdin) throws IOException {
        Map<String, DepNode> trees = new HashMap<String, DepNode>();
        if (o.tree == null) {
            return trees;
        }
        Reader r = "-".equals(o.tree) ? new InputStreamReader(stdin, StandardCharsets.UTF_8)
                : Files.newBufferedReader(Paths.get(o.tree), StandardCharsets.UTF_8);
        try {
            for (DepNode n : DependencyTreeParser.parse(r)) {
                trees.put(n.ga(), n);
            }
        } finally {
            r.close();
        }
        if (trees.isEmpty()) {
            err.println("Kein Baum in " + o.tree + " gefunden (erwartet: Ausgabe von mvn dependency:tree -Dverbose).");
        }
        return trees;
    }

    /**
     * Modul A2: Konflikt-Matrix über den Reactor. Mit {@code --pin g:a[:version]} wird die Version
     * im gemeinsamen Parent unter dependencyManagement festgelegt (ohne Version: die höchste).
     */
    private static int matrix(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        PomLocator locator = new PomLocator(root, localRepository(o, root));
        List<PomFile> poms = new ArrayList<PomFile>();
        for (ProjectPoms.Pom p : new ProjectPoms(root).all()) {
            PomFile m = locator.load(p.file);
            if (m != null) {
                poms.add(m);
            }
        }
        if (poms.isEmpty()) {
            err.println("Keine pom.xml unter " + root + " (--project DIR angeben)");
            return 1;
        }
        ConflictMatrix matrix = ConflictMatrix.build(locator, root, poms, trees);
        if (o.pin != null) {
            return pin(matrix, o.pin, out, err);
        }
        if (o.markdown) {
            out.print(matrix.markdown(o.all));
        } else {
            for (String line : matrix.lines(o.all)) {
                out.println(line);
            }
        }
        return 0;
    }

    /** POMs des Projekts unter {@code root}, frisch gelesen über {@code locator}. */
    private static List<PomFile> projectPoms(PomLocator locator, Path root) {
        List<PomFile> poms = new ArrayList<PomFile>();
        for (ProjectPoms.Pom p : new ProjectPoms(root).all()) {
            PomFile m = locator.load(p.file);
            if (m != null) {
                poms.add(m);
            }
        }
        return poms;
    }

    /**
     * Modul A4: Exclusions, die nichts mehr ausschließen. Mit {@code --remove} werden veraltete und
     * doppelte entfernt – eine nach der anderen, nach jeder Änderung neu geprüft (bei doppelt
     * notierten Exclusions bleibt so eine stehen). Exit-Code 3, wenn ohne {@code --remove} etwas zu
     * entfernen wäre (für CI).
     */
    private static int exclusions(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        Path repo = localRepository(o, root);
        PomLocator locator = new PomLocator(root, repo);
        List<PomFile> poms = projectPoms(locator, root);
        if (poms.isEmpty()) {
            err.println("Keine pom.xml unter " + root + " (--project DIR angeben)");
            return 1;
        }
        ExclusionAudit audit = ExclusionAudit.build(locator, poms, trees);
        if (o.remove) {
            FixExecutor executor = new FixExecutor(Collections.<Path>emptyList());
            int removed = 0;
            for (int round = 0; round < 500 && !audit.removable().isEmpty(); round++) {
                ExclusionAudit.Finding f = audit.removable().get(0);
                for (String a : executor.execute(f.fix()).actions) {
                    out.println(a + " (" + (f.getStatus() == ExclusionAudit.Status.STALE ? "veraltet" : "doppelt") + ")");
                }
                removed++;
                locator = new PomLocator(root, repo);
                audit = ExclusionAudit.build(locator, projectPoms(locator, root), trees);
            }
            out.println(removed == 0 ? "Nichts zu entfernen." : removed + (removed == 1 ? " Exclusion entfernt." : " Exclusions entfernt."));
            if (audit.count(ExclusionAudit.Status.UNKNOWN) > 0) {
                out.println(audit.count(ExclusionAudit.Status.UNKNOWN) + " nicht prüfbar (POMs fehlen lokal) – Details ohne --remove.");
            }
            return 0;
        }
        if (o.markdown) {
            out.print(audit.markdown(o.all));
        } else {
            for (String line : audit.lines(o.all)) {
                out.println(line);
            }
        }
        return audit.removable().isEmpty() ? 0 : 3;
    }

    /**
     * Modul A3: Klassen, die in mehreren JARs eines Moduls liegen. Mit {@code --exclude} werden die
     * empfohlenen Exclusions eingefügt (nur Funde mit eindeutiger Empfehlung, ein Durchgang – danach
     * neu prüfen, mit {@code --tree} auch den Baum neu erzeugen). Exit-Code 3, wenn im
     * Laufzeit-Classpath Klassen mit verschiedenem Inhalt doppelt liegen (für CI).
     */
    private static int duplicates(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        PomLocator locator = new PomLocator(root, localRepository(o, root));
        List<PomFile> poms = projectPoms(locator, root);
        if (poms.isEmpty()) {
            err.println("Keine pom.xml unter " + root + " (--project DIR angeben)");
            return 1;
        }
        DuplicateClasses result = DuplicateClasses.build(locator, poms, trees, new JarIndex(), null);
        if (o.exclude) {
            FixExecutor executor = new FixExecutor(Collections.<Path>emptyList());
            java.util.Set<String> done = new java.util.HashSet<String>();
            int added = 0;
            for (DuplicateClasses.Clash c : result.fixable()) {
                for (FixAction f : c.fixes()) {
                    if (!done.add(f.param("file") + "|" + f.param("artifact") + "|" + f.param("exclusion"))) {
                        continue;
                    }
                    try {
                        for (String a : executor.execute(f).actions) {
                            out.println(a);
                        }
                        added++;
                    } catch (IOException e) {
                        err.println("Übersprungen: " + e.getMessage());
                    }
                }
            }
            out.println(added == 0 ? "Nichts einzufügen." : added + (added == 1 ? " Exclusion eingefügt." : " Exclusions eingefügt.")
                    + (added > 0 && !trees.isEmpty() ? " Baum neu erzeugen und erneut prüfen." : added > 0 ? " Erneut prüfen." : ""));
            int open = result.clashes().size() - result.fixable().size();
            if (open > 0) {
                out.println(open + (open == 1 ? " Fund braucht" : " Funde brauchen") + " eine Entscheidung – Details ohne --exclude.");
            }
            return 0;
        }
        if (o.markdown) {
            out.print(result.markdown(o.all));
        } else {
            for (String line : result.lines(o.all)) {
                out.println(line);
            }
        }
        for (DuplicateClasses.Clash c : result.clashes()) {
            if (c.isMain() && !c.isIdentical()) {
                return 3;
            }
        }
        return 0;
    }

    /**
     * Modul A5: Dependencies, die der Quelltext nennt, aber nur transitiv da sind, und deklarierte,
     * die er nicht nennt. {@code --declare} deklariert die undeklarierten (ohne die über Starter),
     * {@code --remove} entfernt die sicher ungenutzten (nur im eigenen POM, nicht im Parent). Ein
     * Durchgang – danach neu prüfen. Exit-Code 3, wenn ohne Schalter etwas zu tun wäre (für CI).
     */
    private static int analyze(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        PomLocator locator = new PomLocator(root, localRepository(o, root));
        List<PomFile> poms = projectPoms(locator, root);
        if (poms.isEmpty()) {
            err.println("Keine pom.xml unter " + root + " (--project DIR angeben)");
            return 1;
        }
        DependencyUsage result = DependencyUsage.build(locator, poms, trees, new JarIndex(), DependencyUsage.sourceScanner(null), null);
        if (o.declare || o.remove) {
            FixExecutor executor = new FixExecutor(Collections.<Path>emptyList());
            int changed = 0;
            for (DependencyUsage.Finding f : result.findings()) {
                boolean wanted = o.declare && f.getKind() == DependencyUsage.Kind.UNDECLARED && !f.isViaBundle()
                        || o.remove && f.getKind() == DependencyUsage.Kind.UNUSED && f.isCertain() && !f.isInherited();
                if (!wanted) {
                    continue;
                }
                for (FixAction fix : f.fixes()) {
                    if (!FixExecutor.canExecute(fix)) {
                        continue;
                    }
                    try {
                        for (String a : executor.execute(fix).actions) {
                            out.println(a);
                        }
                        changed++;
                    } catch (IOException e) {
                        err.println("Übersprungen: " + e.getMessage());
                    }
                }
            }
            out.println(changed == 0 ? "Nichts zu ändern." : changed + (changed == 1 ? " Änderung." : " Änderungen.")
                    + " Erneut prüfen" + (trees.isEmpty() ? "." : " (Baum neu erzeugen)."));
            return 0;
        }
        if (o.markdown) {
            out.print(result.markdown(o.all));
        } else {
            for (String line : result.lines(o.all)) {
                out.println(line);
            }
        }
        for (DependencyUsage.Finding f : result.findings()) {
            if (f.isCertain() && !f.isViaBundle()) {
                return 3;
            }
        }
        return 0;
    }

    /**
     * Modul A6: Upgrade-Simulator. Zeigt je Modul, was sich im Baum ändert, wenn {@code g:a} auf
     * {@code version} (bzw. eine Property auf einen Wert) gehoben wird – ohne die POMs anzufassen.
     * {@code --manage} legt die Version im dependencyManagement der obersten Projekt-POM fest statt
     * an der heutigen Stelle; {@code --fetch} lädt fehlende POMs mit Maven nach (einziger Netzzugriff
     * in Modul A, nur auf Wunsch); {@code --apply} schreibt die Änderung danach in die POMs.
     * Exit-Code 3, wenn Versionen sinken oder neue Konflikte entstehen (für CI).
     */
    private static int upgrade(Options o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
        UpgradeSimulator.Request request;
        try {
            request = UpgradeSimulator.Request.parse(o.target);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        if (o.manage) {
            request = request.pinned();
        }
        Path root = projectRoot(o);
        Map<String, DepNode> trees = trees(o, err, stdin);
        Path repo = localRepository(o, root);
        PomLocator locator = new PomLocator(root, repo);
        List<PomFile> poms = projectPoms(locator, root);
        if (poms.isEmpty()) {
            err.println("Keine pom.xml unter " + root + " (--project DIR angeben)");
            return 1;
        }
        UpgradeSimulator.Result result = UpgradeSimulator.simulate(locator, poms, request, trees, null);
        if (o.fetch) {
            List<Path> files = new ArrayList<Path>();
            for (PomFile p : poms) {
                files.add(p.getFile());
            }
            String mvn = PomFetcher.executable(root, null);
            for (int round = 1; round <= 4 && !result.isComplete(); round++) {
                List<String> gavs = PomFetcher.coordinates(result.getMissing());
                if (gavs.isEmpty()) {
                    break;
                }
                err.println("Lade " + gavs.size() + (gavs.size() == 1 ? " fehlende POM" : " fehlende POMs") + " mit " + mvn + " …");
                PomFetcher.Outcome outcome;
                try {
                    outcome = PomFetcher.fetch(mvn, root, repo, gavs, files, null, null, 900);
                } catch (IOException e) {
                    err.println("Maven ließ sich nicht starten (" + mvn + "): " + e.getMessage());
                    break;
                }
                if (!outcome.isOk()) {
                    err.println("Maven konnte nicht alles laden (Exit " + outcome.getExitCode() + "):");
                    for (String l : outcome.errors()) {
                        err.println("  " + l);
                    }
                    break;
                }
                locator = new PomLocator(root, repo);
                poms = projectPoms(locator, root);
                UpgradeSimulator.Result next = UpgradeSimulator.simulate(locator, poms, request, trees, null);
                boolean progress = !next.getMissing().equals(result.getMissing());
                result = next;
                if (!progress) {
                    break;
                }
            }
        }
        if (o.markdown) {
            out.print(result.markdown(o.all));
        } else {
            for (String line : result.lines(o.all)) {
                out.println(line);
            }
        }
        if (result.getSteps().isEmpty()) {
            return 1;
        }
        if (o.apply) {
            out.println();
            FixExecutor executor = new FixExecutor(Collections.<Path>emptyList());
            for (FixAction fix : result.fixes()) {
                try {
                    for (String a : executor.execute(fix).actions) {
                        out.println(a);
                    }
                } catch (IOException e) {
                    err.println("Übersprungen: " + e.getMessage());
                }
            }
            return 0;
        }
        return result.hasRisks() ? 3 : 0;
    }

    private static int pin(ConflictMatrix matrix, String spec, PrintStream out, PrintStream err) throws IOException {
        String[] parts = spec.split(":");
        if (parts.length < 2) {
            err.println("--pin erwartet groupId:artifactId[:version]");
            return 2;
        }
        for (ConflictMatrix.Row row : matrix.getRows()) {
            if (!row.getGroupId().equals(parts[0]) || !row.getArtifactId().equals(parts[1])) {
                continue;
            }
            ConflictMatrix.Pin pin = row.getPin();
            if (pin == null) {
                err.println(row.isDivergent() ? row.getPinProblem() : row.ga() + " hat in allen Modulen dieselbe Version " + row.versions());
                return 1;
            }
            FixAction fix = pin.fix(row.getGroupId(), row.getArtifactId(), parts.length > 2 ? parts[2] : pin.getVersion());
            FixExecutor.Outcome outcome = new FixExecutor(Collections.<Path>emptyList()).execute(fix);
            for (String a : outcome.actions) {
                out.println(a);
            }
            for (ConflictMatrix.Blocker b : pin.getBlockers()) {
                out.println("! " + b.getText() + "  → " + b.getFile() + (b.getLine() > 0 ? ":" + b.getLine() : ""));
            }
            return 0;
        }
        err.println(spec + " kommt in keinem Modul vor.");
        return 1;
    }

    private static int evaluate(Options o, PrintStream out) throws IOException {
        Path dir = Paths.get(o.target);
        BuildDoctor doctor = doctor(o);
        Properties labels = new Properties();
        Path labelFile = dir.resolve("labels.properties");
        if (Files.isRegularFile(labelFile)) {
            Reader r = Files.newBufferedReader(labelFile, StandardCharsets.UTF_8);
            try {
                labels.load(r);
            } finally {
                r.close();
            }
        }
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".jsonl"))) {
                    files.add(p);
                }
            }
        } finally {
            ds.close();
        }
        Collections.sort(files);
        int classified = 0;
        int labelled = 0;
        int correct = 0;
        Map<String, Integer> perRule = new TreeMap<String, Integer>();
        out.println(String.format("%-40s %-28s %-15s %s", "Datei", "Regel", "Sicherheit", "Erwartet"));
        for (Path f : files) {
            BuildFailure failure = load(f);
            List<Diagnosis> ds2 = doctor.diagnose(failure, Environment.forBuild(failure, o.project == null ? null : Paths.get(o.project)));
            String rule = ds2.isEmpty() ? "(kein Fehler)" : ds2.get(0).getRuleId();
            String conf = ds2.isEmpty() ? "" : ds2.get(0).getConfidence().label();
            if (!ds2.isEmpty() && !ds2.get(0).isUnknown()) {
                classified++;
            }
            Integer n = perRule.get(rule);
            perRule.put(rule, n == null ? 1 : n + 1);
            String expected = labels.getProperty(f.getFileName().toString());
            String mark = "";
            if (expected != null) {
                labelled++;
                if (expected.trim().equals(rule)) {
                    correct++;
                    mark = "✓";
                } else {
                    mark = "✗ " + expected.trim();
                }
            }
            out.println(String.format("%-40s %-28s %-15s %s", f.getFileName(), rule, conf, mark));
        }
        out.println();
        out.println("Logs: " + files.size() + ", eingeordnet: " + classified + " (" + percent(classified, files.size()) + ")");
        if (labelled > 0) {
            out.println("Mit Label: " + labelled + ", richtig: " + correct + " (" + percent(correct, labelled) + ")");
        } else {
            out.println("Tipp: labels.properties (datei.log=regel-id) anlegen, um die Trefferquote zu messen.");
        }
        out.println("Verteilung: " + perRule);
        return 0;
    }

    private static String percent(int a, int b) {
        return b == 0 ? "–" : Math.round(100.0 * a / b) + " %";
    }

    private static BuildDoctor doctor(Options o) throws IOException {
        RuleCatalog catalog = RuleCatalog.loadDefault();
        if (o.catalog != null) {
            catalog = catalog.merge(RuleCatalog.load(Paths.get(o.catalog)));
        }
        return new BuildDoctor(catalog, Checks.defaults());
    }

    static BuildFailure load(Path file) throws IOException {
        if (file.getFileName().toString().endsWith(".jsonl")) {
            return EventLog.read(file);
        }
        Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        try {
            return MavenLogParser.parse(r);
        } finally {
            r.close();
        }
    }

    private static void usage(PrintStream err) {
        err.println("Maven Detective – Build Doctor");
        err.println();
        err.println("  diagnose <build.log | events.jsonl | -> [--project DIR] [--markdown | --id] [--catalog extra.yaml]");
        err.println("  evaluate <ordner-mit-logs> [--catalog extra.yaml]   (Gate 1: Trefferquote messen)");
        err.println("  changes [--project DIR]   (was hat sich seit dem letzten grünen Build geändert?)");
        err.println("  validate-catalog <extra.yaml>");
        err.println("  why <groupId:artifactId> [--project DIR] [--module DIR] [--tree tree.txt | -] [--markdown] [--local-repo DIR]");
        err.println("      (Modul A: woher kommt die Version? Baum: mvn dependency:tree -Dverbose > tree.txt)");
        err.println("  matrix [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--pin groupId:artifactId[:version]]");
        err.println("      (Modul A2: welche Module nutzen welche Version? --pin legt sie im gemeinsamen Parent fest)");
        err.println("  exclusions [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--remove]");
        err.println("      (Modul A4: Exclusions, die nichts mehr ausschließen; --remove entfernt sie, Exit 3 = es gibt welche)");
        err.println("  duplicates [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--exclude]");
        err.println("      (Modul A3: Klassen in mehreren JARs; --exclude fügt die empfohlenen Exclusions ein,");
        err.println("       Exit 3 = verschiedene Fassungen im Laufzeit-Classpath)");
        err.println("  analyze [--project DIR] [--tree tree.txt | -] [--all] [--markdown] [--declare] [--remove]");
        err.println("      (Modul A5: genannt, aber nur transitiv da / deklariert, aber nicht genannt; --declare deklariert,");
        err.println("       --remove entfernt sicher ungenutzte, Exit 3 = es gibt Befunde)");
        err.println("  upgrade <groupId:artifactId:version | property=wert> [--project DIR] [--tree tree.txt | -] [--all] [--markdown]");
        err.println("          [--manage] [--fetch] [--apply] [--local-repo DIR]");
        err.println("      (Modul A6: was ändert sich im Baum, bevor die POM geändert wird? --manage legt die Version im");
        err.println("       dependencyManagement fest, --fetch lädt fehlende POMs mit Maven, --apply schreibt die Änderung,");
        err.println("       Exit 3 = Versionen sinken oder neue Konflikte)");
    }

    private static final class Options {
        String target;
        String project;
        String catalog;
        String module;
        String tree;
        String localRepo;
        String pin;
        boolean markdown;
        boolean all;
        boolean remove;
        boolean exclude;
        boolean declare;
        boolean manage;
        boolean fetch;
        boolean apply;
        boolean idOnly;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if ("--project".equals(a) && i + 1 < args.length) {
                    o.project = args[++i];
                } else if ("--catalog".equals(a) && i + 1 < args.length) {
                    o.catalog = args[++i];
                } else if ("--module".equals(a) && i + 1 < args.length) {
                    o.module = args[++i];
                } else if ("--tree".equals(a) && i + 1 < args.length) {
                    o.tree = args[++i];
                } else if ("--local-repo".equals(a) && i + 1 < args.length) {
                    o.localRepo = args[++i];
                } else if ("--pin".equals(a) && i + 1 < args.length) {
                    o.pin = args[++i];
                } else if ("--all".equals(a)) {
                    o.all = true;
                } else if ("--remove".equals(a)) {
                    o.remove = true;
                } else if ("--exclude".equals(a)) {
                    o.exclude = true;
                } else if ("--declare".equals(a)) {
                    o.declare = true;
                } else if ("--manage".equals(a)) {
                    o.manage = true;
                } else if ("--fetch".equals(a)) {
                    o.fetch = true;
                } else if ("--apply".equals(a)) {
                    o.apply = true;
                } else if ("--markdown".equals(a)) {
                    o.markdown = true;
                } else if ("--id".equals(a)) {
                    o.idOnly = true;
                } else if (o.target == null) {
                    o.target = a;
                }
            }
            return o;
        }
    }
}
