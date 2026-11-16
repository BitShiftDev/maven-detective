package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.check.Checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

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
            CliOptions o = CliOptions.parse(args);
            if ("diagnose".equals(command) && o.target != null) {
                return BuildCommands.diagnose(o, out, stdin);
            }
            if ("changes".equals(command)) {
                return BuildCommands.changes(o, out);
            }
            if ("why".equals(command) && o.target != null) {
                return DependencyCommands.why(o, out, err, stdin);
            }
            if ("matrix".equals(command)) {
                return DependencyCommands.matrix(o, out, err, stdin);
            }
            if ("exclusions".equals(command)) {
                return DependencyCommands.exclusions(o, out, err, stdin);
            }
            if ("duplicates".equals(command)) {
                return DependencyCommands.duplicates(o, out, err, stdin);
            }
            if ("analyze".equals(command)) {
                return DependencyCommands.analyze(o, out, err, stdin);
            }
            if ("upgrade".equals(command) && o.target != null) {
                return DependencyCommands.upgrade(o, out, err, stdin);
            }
            if ("preflight".equals(command)) {
                return BuildCommands.preflight(o, out, err);
            }
            if ("outline".equals(command) && o.target != null) {
                return BuildCommands.outline(o, out, stdin);
            }
            if ("evaluate".equals(command) && o.target != null) {
                return BuildCommands.evaluate(o, out);
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

    private static void usage(PrintStream err) {
        err.println(Texts.t("cli.usage"));
    }
}
