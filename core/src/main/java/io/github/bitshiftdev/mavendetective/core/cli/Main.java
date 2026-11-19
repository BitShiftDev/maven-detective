package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.check.Checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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

    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList("diagnose", "evaluate", "validate-catalog",
            "changes", "why", "matrix", "exclusions", "duplicates", "analyze", "upgrade", "preflight", "outline"));
    /** Befehle, die ein Ziel brauchen (Datei, Ordner, Koordinate, Spezifikation). */
    private static final Set<String> NEEDS_TARGET = new HashSet<String>(Arrays.asList("diagnose", "evaluate",
            "validate-catalog", "why", "upgrade", "outline"));

    private Main() {
    }

    public static void main(String[] args) throws java.io.UnsupportedEncodingException {
        // Immer UTF-8: Umlaute und Rahmenzeichen sollen auch bei LANG=C (CI-Container) lesbar bleiben
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8");
        System.exit(run(args, out, err, System.in));
    }

    static int run(String[] args, PrintStream out, PrintStream err, InputStream stdin) {
        // --lang gilt nur für diesen Aufruf; danach wieder die Sprache von vorher (Tests, eingebettete Aufrufe)
        String languageBefore = System.getProperty(Texts.PROPERTY);
        String lang = CliOptions.language(args);
        if (lang != null && Texts.Language.parse(lang) != null) {
            System.setProperty(Texts.PROPERTY, lang);
        }
        try {
            return command(args, out, err, stdin);
        } finally {
            if (languageBefore == null) {
                System.clearProperty(Texts.PROPERTY);
            } else {
                System.setProperty(Texts.PROPERTY, languageBefore);
            }
        }
    }

    private static int command(String[] args, PrintStream out, PrintStream err, InputStream stdin) {
        if (args.length < 1) {
            usage(err);
            return 2;
        }
        String command = args[0];
        if ("help".equals(command) || "--help".equals(command) || "-h".equals(command)) {
            usage(out);
            return 0;
        }
        if (!COMMANDS.contains(command)) {
            err.println(Texts.t("cli.unknown.command", command));
            usage(err);
            return 2;
        }
        CliOptions o;
        try {
            o = CliOptions.parse(args);
        } catch (CliOptions.UsageException e) {
            err.println(e.getMessage());
            usage(err);
            return 2;
        }
        if (o.target == null && NEEDS_TARGET.contains(command)) {
            err.println(Texts.t("cli.missing.argument", command));
            usage(err);
            return 2;
        }
        try {
            if ("diagnose".equals(command)) {
                return BuildCommands.diagnose(o, out, stdin);
            }
            if ("changes".equals(command)) {
                return BuildCommands.changes(o, out);
            }
            if ("why".equals(command)) {
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
            if ("upgrade".equals(command)) {
                return DependencyCommands.upgrade(o, out, err, stdin);
            }
            if ("preflight".equals(command)) {
                return BuildCommands.preflight(o, out, err);
            }
            if ("outline".equals(command)) {
                return BuildCommands.outline(o, out, stdin);
            }
            if ("evaluate".equals(command)) {
                return BuildCommands.evaluate(o, out);
            }
            RuleCatalog c = RuleCatalog.load(Paths.get(o.target));
            new BuildDoctor(RuleCatalog.loadDefault().merge(c), Checks.defaults());
            out.println(Texts.t("cli.catalog.ok", c.getRules().size()));
            return 0;
        } catch (RuleCatalog.CatalogException e) {
            err.println(Texts.t("cli.catalog.error", e.getMessage()));
            return 3;
        } catch (InvalidPathException e) {
            // z. B. ein Nullzeichen oder unter Windows ein „:“ mitten im Pfad
            err.println(Texts.t("cli.error", e.getMessage()));
            return 2;
        } catch (IOException e) {
            err.println(Texts.t("cli.error", e.getMessage()));
            return 1;
        } catch (UncheckedIOException e) {
            err.println(Texts.t("cli.error", e.getCause().getMessage()));
            return 1;
        }
    }

    private static void usage(PrintStream stream) {
        stream.println(Texts.t("cli.usage"));
    }
}
