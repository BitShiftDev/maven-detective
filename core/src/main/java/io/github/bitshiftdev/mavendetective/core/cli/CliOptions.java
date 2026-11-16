package io.github.bitshiftdev.mavendetective.core.cli;

/** Optionen der Kommandozeile; das erste Argument ohne {@code --} ist das Ziel (Datei, Koordinate, Spezifikation). */
final class CliOptions {
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

    static CliOptions parse(String[] args) {
        CliOptions o = new CliOptions();
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
