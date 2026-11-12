package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.check.Checks;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
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
    }

    private static final class Options {
        String target;
        String project;
        String catalog;
        boolean markdown;
        boolean idOnly;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if ("--project".equals(a) && i + 1 < args.length) {
                    o.project = args[++i];
                } else if ("--catalog".equals(a) && i + 1 < args.length) {
                    o.catalog = args[++i];
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
