package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Texts;
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
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.io.EventLog;
import io.github.bitshiftdev.mavendetective.core.io.LogOutline;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight;
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
 * java -jar maven-detective-core.jar preflight [--project DIR] [--network] [--nexus URL] [--markdown]
 * java -jar maven-detective-core.jar outline build.log | - [--strip] [--context N]
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
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--lang".equals(args[i])) {
                System.setProperty(Texts.PROPERTY, args[i + 1]);
            }
        }
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
            if ("preflight".equals(command)) {
                return preflight(o, out, err);
            }
            if ("outline".equals(command) && o.target != null) {
                return outline(o, out, stdin);
            }
            if ("evaluate".equals(command) && o.target != null) {
                return evaluate(o, out);
            }
            if ("validate-catalog".equals(command) && o.target != null) {
                RuleCatalog c = RuleCatalog.load(Paths.get(o.target));
                new BuildDoctor(RuleCatalog.loadDefault().merge(c), Checks.defaults());
                out.println(Texts.t("cli.catalog.ok", c.getRules().size()));
                return 0;
            }
            usage(err);
            return 2;
        } catch (RuleCatalog.CatalogException e) {
            err.println(Texts.t("cli.catalog.error", e.getMessage()));
            return 3;
        } catch (IOException e) {
            err.println(Texts.t("cli.error", e.getMessage()));
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
            if (reportsFailure(failure)) {
                // Log endet mit BUILD FAILURE, aber keine Fehlerzeile war lesbar (unbekanntes Zeilenformat)
                out.println(Texts.t("cli.no.errors.but.failure"));
                return 4;
            }
            out.println(Texts.t("cli.no.errors"));
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

    private static boolean reportsFailure(BuildFailure failure) {
        for (String line : failure.getLogLines()) {
            if (line.contains("BUILD FAILURE")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Preflight vor dem Build: settings.xml, JDK, Maven, Projekt, lokales Repository, Repositories –
     * mit dem Setup, das {@code ./mvnw} bzw. {@code mvn} hier nähme. Netz nur mit {@code --network};
     * {@code --nexus URL} fragt alle Repositories über den eigenen Repository-Manager an.
     * Exit-Code 3, wenn der Build so nicht laufen kann (Ampel rot).
     */
    private static int preflight(Options o, PrintStream out, PrintStream err) {
        if (o.nexus != null && !Preflight.isRepositoryUrl(o.nexus)) {
            err.println(Texts.t("cli.nexus.invalid", o.nexus));
            return 2;
        }
        Path root = Paths.get(o.project != null ? o.project : o.target != null ? o.target : "").toAbsolutePath().normalize();
        Path home = Paths.get(System.getProperty("user.home"));
        BuildSetup setup = BuildSetup.detectCommandLine(root, home, System.getenv());
        Preflight.Report report = Preflight.run(root, setup, new Preflight.Options().network(o.network).nexus(o.nexus));
        if (o.markdown) {
            out.print(report.markdown());
        } else {
            for (String line : report.lines()) {
                out.println(line);
            }
        }
        return report.getLight() == Preflight.Light.RED ? 3 : 0;
    }

    /** Log-Navigator: Module, gescheitertes Modul, erster echter Fehler; {@code --strip} gibt das Log ohne Downloads aus. */
    private static int outline(Options o, PrintStream out, InputStream stdin) throws IOException {
        List<String> lines = new ArrayList<String>();
        Reader r = "-".equals(o.target) ? new InputStreamReader(stdin, StandardCharsets.UTF_8)
                : Files.newBufferedReader(Paths.get(o.target), StandardCharsets.UTF_8);
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(r);
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            r.close();
        }
        LogOutline outline = LogOutline.of(lines);
        for (String line : o.strip ? outline.withoutDownloads() : outline.summary(o.context)) {
            out.println(line);
        }
        return 0;
    }

    /** „Was hat sich geändert?“ – letzter grüner Build gegen letzten Lauf, ohne Log. */
    private static int changes(Options o, PrintStream out) {
        Path project = Paths.get(o.project != null ? o.project : o.target != null ? o.target : "").toAbsolutePath();
        SnapshotStore store = SnapshotStore.defaultStore();
        BuildSnapshot latest = store.loadLatest(project);
        BuildSnapshot green = store.loadGreen(project, latest == null ? BuildSnapshot.ORIGIN_EXTENSION : latest.getOrigin());
        if (green == null) {
            out.println(Texts.t("cli.changes.no.green", project, store.projectDir(project)));
            out.println(Texts.t("cli.changes.how"));
            return 1;
        }
        if (latest == null || !Boolean.FALSE.equals(latest.getSuccessful())) {
            out.println(Texts.t("cli.changes.green", green.getCreatedAt()));
            return 0;
        }
        SnapshotDiff diff = SnapshotDiff.compare(green, latest);
        out.println(Texts.t("cli.changes.headline", diff.headline(), latest.getCreatedAt()));
        if (diff.isEmpty()) {
            out.println("  " + Texts.t("cli.changes.none"));
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
            err.println(Texts.t("cli.expected.ga"));
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
                err.println(Texts.t("cli.no.readable.pom", root.resolve(o.module)));
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
                    err.println(Texts.t("cli.no.pom", root));
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
            err.println(Texts.t("cli.no.tree", o.tree));
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
            err.println(Texts.t("cli.no.pom", root));
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
            err.println(Texts.t("cli.no.pom", root));
            return 1;
        }
        ExclusionAudit audit = ExclusionAudit.build(locator, poms, trees);
        if (o.remove) {
            FixExecutor executor = new FixExecutor(Collections.<Path>emptyList());
            int removed = 0;
            for (int round = 0; round < 500 && !audit.removable().isEmpty(); round++) {
                ExclusionAudit.Finding f = audit.removable().get(0);
                for (String a : executor.execute(f.fix()).actions) {
                    out.println(a + " (" + Texts.t(f.getStatus() == ExclusionAudit.Status.STALE ? "excl.status.stale" : "excl.status.redundant") + ")");
                }
                removed++;
                locator = new PomLocator(root, repo);
                audit = ExclusionAudit.build(locator, projectPoms(locator, root), trees);
            }
            out.println(removed == 0 ? Texts.t("cli.nothing.to.remove") : Texts.t("cli.exclusions.removed", removed));
            if (audit.count(ExclusionAudit.Status.UNKNOWN) > 0) {
                out.println(Texts.t("cli.exclusions.unknown", audit.count(ExclusionAudit.Status.UNKNOWN)));
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
            err.println(Texts.t("cli.no.pom", root));
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
                        err.println(Texts.t("cli.skipped", e.getMessage()));
                    }
                }
            }
            out.println(added == 0 ? Texts.t("cli.nothing.to.add") : Texts.t("cli.exclusions.added", added) + " "
                    + Texts.t(!trees.isEmpty() ? "cli.recheck.tree" : "cli.recheck"));
            int open = result.clashes().size() - result.fixable().size();
            if (open > 0) {
                out.println(Texts.t("cli.duplicates.open", open));
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
            err.println(Texts.t("cli.no.pom", root));
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
                        err.println(Texts.t("cli.skipped", e.getMessage()));
                    }
                }
            }
            out.println(changed == 0 ? Texts.t("cli.nothing.to.change") : Texts.t("cli.changes.made", changed) + " "
                    + Texts.t(trees.isEmpty() ? "cli.recheck" : "cli.recheck.tree"));
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
            err.println(Texts.t("cli.no.pom", root));
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
                err.println(Texts.t("cli.fetch.progress", gavs.size(), mvn));
                PomFetcher.Outcome outcome;
                try {
                    outcome = PomFetcher.fetch(mvn, root, repo, gavs, files, null, null, 900);
                } catch (IOException e) {
                    err.println(Texts.t("cli.fetch.start.failed", mvn, e.getMessage()));
                    break;
                }
                if (!outcome.isOk()) {
                    err.println(Texts.t("cli.fetch.failed", outcome.getExitCode()));
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
                    err.println(Texts.t("cli.skipped", e.getMessage()));
                }
            }
            return 0;
        }
        return result.hasRisks() ? 3 : 0;
    }

    private static int pin(ConflictMatrix matrix, String spec, PrintStream out, PrintStream err) throws IOException {
        String[] parts = spec.split(":");
        if (parts.length < 2) {
            err.println(Texts.t("cli.pin.format"));
            return 2;
        }
        for (ConflictMatrix.Row row : matrix.getRows()) {
            if (!row.getGroupId().equals(parts[0]) || !row.getArtifactId().equals(parts[1])) {
                continue;
            }
            ConflictMatrix.Pin pin = row.getPin();
            if (pin == null) {
                err.println(row.isDivergent() ? row.getPinProblem() : Texts.t("cli.pin.same", row.ga(), row.versions()));
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
        err.println(Texts.t("cli.not.in.any.module", spec));
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
        out.println(String.format("%-40s %-28s %-15s %s", Texts.t("cli.eval.file"), Texts.t("cli.eval.rule"), Texts.t("cli.eval.confidence"), Texts.t("cli.eval.expected")));
        for (Path f : files) {
            BuildFailure failure = load(f);
            List<Diagnosis> ds2 = doctor.diagnose(failure, Environment.forBuild(failure, o.project == null ? null : Paths.get(o.project)));
            String rule = ds2.isEmpty() ? "(" + Texts.t("cli.eval.no.error") + ")" : ds2.get(0).getRuleId();
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
        out.println(Texts.t("cli.eval.summary", files.size(), classified, percent(classified, files.size())));
        if (labelled > 0) {
            out.println(Texts.t("cli.eval.labelled", labelled, correct, percent(correct, labelled)));
        } else {
            out.println(Texts.t("cli.eval.tip"));
        }
        out.println(Texts.t("cli.eval.distribution", perRule));
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
        err.println(Texts.t("cli.usage"));
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
        boolean network;
        String nexus;
        boolean strip;
        int context = 3;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if ("--lang".equals(a) && i + 1 < args.length) {
                    i++; // in run() ausgewertet
                } else if ("--project".equals(a) && i + 1 < args.length) {
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
                } else if ("--network".equals(a)) {
                    o.network = true;
                } else if ("--nexus".equals(a) && i + 1 < args.length) {
                    o.nexus = args[++i];
                } else if ("--strip".equals(a)) {
                    o.strip = true;
                } else if ("--context".equals(a) && i + 1 < args.length) {
                    try {
                        o.context = Math.max(0, Integer.parseInt(args[++i]));
                    } catch (NumberFormatException ignored) {
                        // Standard behalten
                    }
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
