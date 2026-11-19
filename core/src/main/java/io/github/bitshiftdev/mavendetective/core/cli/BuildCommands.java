package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.check.Checks;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Befehle rund um gescheiterte Builds: {@code diagnose}, {@code evaluate}, {@code changes}, {@code preflight}, {@code outline}. */
final class BuildCommands {

    private BuildCommands() {
    }

    static int diagnose(CliOptions o, PrintStream out, InputStream stdin) throws IOException {
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
            if (failure.reportsFailure()) {
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

    /**
     * Preflight vor dem Build: settings.xml, JDK, Maven, Projekt, lokales Repository, Repositories –
     * mit dem Setup, das {@code ./mvnw} bzw. {@code mvn} hier nähme. Netz nur mit {@code --network};
     * {@code --nexus URL} fragt alle Repositories über den eigenen Repository-Manager an.
     * Exit-Code 3, wenn der Build so nicht laufen kann (Ampel rot).
     */
    static int preflight(CliOptions o, PrintStream out, PrintStream err) {
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
    static int outline(CliOptions o, PrintStream out, InputStream stdin) throws IOException {
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
    static int changes(CliOptions o, PrintStream out) {
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
            out.println(Texts.t("cli.changes.green", SnapshotDiff.shortTime(green.getCreatedAt())));
            return 0;
        }
        SnapshotDiff diff = SnapshotDiff.compare(green, latest);
        out.println(Texts.t("cli.changes.headline", diff.headline(), SnapshotDiff.shortTime(latest.getCreatedAt())));
        if (diff.isEmpty()) {
            out.println("  " + Texts.t(diff.limitationText() == null ? "cli.changes.none" : "cli.changes.not.comparable"));
        }
        for (String line : diff.lines(Integer.MAX_VALUE)) {
            out.println("  - " + line);
        }
        if (diff.limitationText() != null) {
            out.println("  " + diff.limitationText());
        }
        return 0;
    }

    static int evaluate(CliOptions o, PrintStream out) throws IOException {
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

    static BuildDoctor doctor(CliOptions o) throws IOException {
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
}
