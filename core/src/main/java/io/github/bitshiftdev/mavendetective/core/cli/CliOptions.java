package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Optionen der Kommandozeile; das erste Argument ohne {@code --} ist das Ziel (Datei, Koordinate, Spezifikation).
 * Werte als {@code --opt wert} oder {@code --opt=wert}; eine wiederholte Option gilt mit dem letzten Wert. Nach
 * {@code --} ist alles Ziel (für Dateien, die mit {@code -} beginnen). Unbekannte Optionen, fehlende Werte und ein
 * zweites Ziel sind Fehler ({@link UsageException}, Exit-Code 2) – ein Tippfehler wie {@code --markdwon} soll nicht
 * still übergangen werden.
 */
final class CliOptions {

    /** Fehler in den Argumenten; die Meldung ist schon übersetzt. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private static final Set<String> WITH_VALUE = new HashSet<String>(Arrays.asList(
            "lang", "project", "catalog", "module", "tree", "local-repo", "pin", "nexus", "context"));
    private static final Set<String> FLAGS = new HashSet<String>(Arrays.asList(
            "markdown", "all", "remove", "exclude", "declare", "manage", "fetch", "apply", "id", "network", "strip"));

    String target;
    String lang;
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

    /** Liest {@code args[1..]} ({@code args[0]} ist der Befehl). */
    static CliOptions parse(String[] args) throws UsageException {
        CliOptions o = new CliOptions();
        boolean onlyTargets = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!onlyTargets && "--".equals(a)) {
                onlyTargets = true;
            } else if (!onlyTargets && a.startsWith("--")) {
                int eq = a.indexOf('=');
                String name = eq < 0 ? a.substring(2) : a.substring(2, eq);
                String value = eq < 0 ? null : a.substring(eq + 1);
                if (WITH_VALUE.contains(name)) {
                    if (value == null) {
                        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                            throw new UsageException(Texts.t("cli.missing.value", "--" + name));
                        }
                        value = args[++i];
                    }
                    o.set(name, value);
                } else if (FLAGS.contains(name)) {
                    if (value != null) {
                        throw new UsageException(Texts.t("cli.flag.with.value", "--" + name));
                    }
                    o.flag(name);
                } else {
                    throw new UsageException(Texts.t("cli.unknown.option", eq < 0 ? a : a.substring(0, eq)));
                }
            } else if (!onlyTargets && a.length() > 1 && a.startsWith("-")) {
                throw new UsageException(Texts.t("cli.unknown.option", a));
            } else if (o.target == null) {
                o.target = a;
            } else {
                throw new UsageException(Texts.t("cli.unexpected.argument", a));
            }
        }
        return o;
    }

    /** Wert von {@code --lang} ohne vollständiges Parsen – die Sprache muss vor der ersten Meldung feststehen. */
    static String language(String[] args) {
        String lang = null;
        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                break;
            }
            if ("--lang".equals(args[i]) && i + 1 < args.length) {
                lang = args[++i];
            } else if (args[i].startsWith("--lang=")) {
                lang = args[i].substring("--lang=".length());
            }
        }
        return lang;
    }

    private void set(String name, String value) throws UsageException {
        if ("lang".equals(name)) {
            if (Texts.Language.parse(value) == null) {
                throw new UsageException(Texts.t("cli.invalid.language", value));
            }
            lang = value;
        } else if ("project".equals(name)) {
            project = value;
        } else if ("catalog".equals(name)) {
            catalog = value;
        } else if ("module".equals(name)) {
            module = value;
        } else if ("tree".equals(name)) {
            tree = value;
        } else if ("local-repo".equals(name)) {
            localRepo = value;
        } else if ("pin".equals(name)) {
            pin = value;
        } else if ("nexus".equals(name)) {
            nexus = value;
        } else {
            context = number("--" + name, value);
        }
    }

    private void flag(String name) {
        if ("markdown".equals(name)) {
            markdown = true;
        } else if ("all".equals(name)) {
            all = true;
        } else if ("remove".equals(name)) {
            remove = true;
        } else if ("exclude".equals(name)) {
            exclude = true;
        } else if ("declare".equals(name)) {
            declare = true;
        } else if ("manage".equals(name)) {
            manage = true;
        } else if ("fetch".equals(name)) {
            fetch = true;
        } else if ("apply".equals(name)) {
            apply = true;
        } else if ("id".equals(name)) {
            idOnly = true;
        } else if ("network".equals(name)) {
            network = true;
        } else {
            strip = true;
        }
    }

    /** Nicht-negative Zahl (negativ → 0); kein {@code int} → Fehler. */
    private static int number(String option, String value) throws UsageException {
        if (!value.trim().matches("-?\\d{1,9}")) {
            throw new UsageException(Texts.t("cli.invalid.number", option, value));
        }
        return Math.max(0, Integer.parseInt(value.trim()));
    }
}
