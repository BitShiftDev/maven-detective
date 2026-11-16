package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.Texts;
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
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Befehle von Modul A: {@code why}, {@code matrix}, {@code exclusions}, {@code duplicates}, {@code analyze},
 * {@code upgrade}. Lesen POMs nur lokal; {@code --remove}, {@code --exclude}, {@code --declare}, {@code --pin} und
 * {@code --apply} ändern POMs über den {@code FixExecutor}.
 */
final class DependencyCommands {

    private DependencyCommands() {
    }

    /**
     * Modul A1: Warum steht diese Version im Classpath? Ohne {@code --module} werden alle Module
     * erklärt, die die Dependency deklarieren oder (laut Baum) enthalten.
     */
    static int why(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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

    private static Path projectRoot(CliOptions o) {
        return Paths.get(o.project != null ? o.project : "").toAbsolutePath().normalize();
    }

    private static Path localRepository(CliOptions o, Path root) {
        return o.localRepo != null ? Paths.get(o.localRepo)
                : Environment.builder().projectDirectory(root).userHome(Paths.get(System.getProperty("user.home")))
                .env(System.getenv()).build().getLocalRepository();
    }

    /** Bäume aus {@code --tree} (Datei oder stdin) je {@code groupId:artifactId} des Moduls. */
    private static Map<String, DepNode> trees(CliOptions o, PrintStream err, InputStream stdin) throws IOException {
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
    static int matrix(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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
    static int exclusions(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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
    static int duplicates(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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
    static int analyze(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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
    static int upgrade(CliOptions o, PrintStream out, PrintStream err, InputStream stdin) throws IOException {
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
}
