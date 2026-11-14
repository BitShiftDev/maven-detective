package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Preflight-Check: vor dem Build prüfen, ob er überhaupt eine Chance hat – settings.xml gültig, JDK
 * passend, Maven passend, Parent/BOMs und Module auffindbar, Repositories nicht blockiert. Liest nur
 * Dateien; ins Netz geht er nur mit {@link Options#network(boolean)} (in der IDE „auf Klick“, in der
 * CLI {@code --network}), und auch dann ohne Zugangsdaten.
 *
 * <p>Ergebnis ist eine Ampel ({@link Report#getStatus()}) und je Thema eine oder mehrere Zeilen mit
 * Belegen und Quick-Fixes ({@link FixAction}, wie auf der Diagnose-Karte).
 */
public final class Preflight {

    /** Schwere einer Zeile, aufsteigend. */
    public enum Status {
        OK, INFO, WARNING, ERROR;

        public Status worst(Status other) {
            return other != null && other.ordinal() > ordinal() ? other : this;
        }
    }

    /** Ampel über alle Zeilen. */
    public enum Light { GREEN, YELLOW, RED }

    /** Thema einer Zeile; legt die Reihenfolge fest. */
    public enum Topic { SETTINGS, JDK, MAVEN, PROJECT, LOCAL_REPOSITORY, REPOSITORY;

        public String label() {
            return Texts.t("preflight.topic." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Eine Zeile des Ergebnisses. */
    public static final class Item {
        private final Topic topic;
        private final Status status;
        private final String text;
        private final List<Evidence> evidence = new ArrayList<Evidence>();
        private final List<FixAction> fixes = new ArrayList<FixAction>();

        Item(Topic topic, Status status, String text) {
            this.topic = topic;
            this.status = status;
            this.text = text;
        }

        public static Item ok(Topic topic, String text) { return new Item(topic, Status.OK, text); }
        public static Item info(Topic topic, String text) { return new Item(topic, Status.INFO, text); }
        public static Item warning(Topic topic, String text) { return new Item(topic, Status.WARNING, text); }
        public static Item error(Topic topic, String text) { return new Item(topic, Status.ERROR, text); }

        Item evidence(Evidence e) {
            if (e != null) {
                evidence.add(e);
            }
            return this;
        }

        Item fix(FixAction f) {
            if (f != null) {
                fixes.add(f);
            }
            return this;
        }

        public Topic getTopic() { return topic; }
        public Status getStatus() { return status; }
        public String getText() { return text; }
        public List<Evidence> getEvidence() { return Collections.unmodifiableList(evidence); }
        public List<FixAction> getFixes() { return Collections.unmodifiableList(fixes); }

        @Override
        public String toString() {
            return status + " " + topic + ": " + text;
        }
    }

    /** Ergebnis eines Preflights. */
    public static final class Report {
        private final List<Item> items;
        private final boolean networkChecked;
        private final Path project;
        private final Path localRepository;

        Report(Path project, Path localRepository, List<Item> items, boolean networkChecked) {
            this.project = project;
            this.localRepository = localRepository;
            this.items = Collections.unmodifiableList(items);
            this.networkChecked = networkChecked;
        }

        public Path getProject() { return project; }

        /** Lokales Repository des Builds ({@code null}: unbekannt) – dort dürfen Fixes Dateien löschen. */
        public Path getLocalRepository() { return localRepository; }

        public List<Item> getItems() { return items; }

        /** Ob Repositories über das Netz geprüft wurden. */
        public boolean isNetworkChecked() { return networkChecked; }

        public Status getStatus() {
            Status s = Status.OK;
            for (Item i : items) {
                s = s.worst(i.getStatus());
            }
            return s;
        }

        public Light getLight() {
            Status s = getStatus();
            return s == Status.ERROR ? Light.RED : s == Status.WARNING ? Light.YELLOW : Light.GREEN;
        }

        public int count(Status status) {
            int n = 0;
            for (Item i : items) {
                if (i.getStatus() == status) {
                    n++;
                }
            }
            return n;
        }

        /** Eine Zeile Zusammenfassung: „2 Probleme, 1 Warnung“ bzw. „Alles bereit“. */
        public String headline() {
            int errors = count(Status.ERROR);
            int warnings = count(Status.WARNING);
            if (errors == 0 && warnings == 0) {
                return Texts.t("preflight.headline.ok");
            }
            if (errors == 0) {
                return Texts.t("preflight.headline.warnings", warnings);
            }
            return warnings == 0 ? Texts.t("preflight.headline.errors", errors)
                    : Texts.t("preflight.headline.both", errors, warnings);
        }

        /** Ausgabe für die Konsole. */
        public List<String> lines() {
            List<String> out = new ArrayList<String>();
            out.add(Texts.t("preflight.title", lightSymbol(getLight()), headline()));
            Topic last = null;
            for (Item i : items) {
                if (i.getTopic() != last) {
                    out.add("");
                    out.add(i.getTopic().label());
                    last = i.getTopic();
                }
                out.add("  " + symbol(i.getStatus()) + " " + i.getText());
                for (Evidence e : i.getEvidence()) {
                    out.add("      · " + evidenceText(e));
                }
                for (FixAction f : i.getFixes()) {
                    out.add("      → " + f.getLabel());
                }
            }
            if (!networkChecked) {
                out.add("");
                out.add(Texts.t("preflight.network.hint"));
            }
            return out;
        }

        public String markdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("### ").append(Texts.t("preflight.title", lightSymbol(getLight()), headline())).append("\n\n");
            sb.append("| | ").append(Texts.t("preflight.column.topic")).append(" | ")
                    .append(Texts.t("preflight.column.result")).append(" |\n| --- | --- | --- |\n");
            for (Item i : items) {
                StringBuilder cell = new StringBuilder(escape(i.getText()));
                for (Evidence e : i.getEvidence()) {
                    cell.append("<br>· ").append(escape(evidenceText(e)));
                }
                for (FixAction f : i.getFixes()) {
                    cell.append("<br>→ ").append(escape(f.getLabel()));
                }
                sb.append("| ").append(symbol(i.getStatus())).append(" | ").append(escape(i.getTopic().label()))
                        .append(" | ").append(cell).append(" |\n");
            }
            if (!networkChecked) {
                sb.append("\n").append(Texts.t("preflight.network.hint")).append("\n");
            }
            return sb.toString();
        }

        private static String evidenceText(Evidence e) {
            if (e.getFile() == null) {
                return e.getText();
            }
            return e.getText() + " (" + e.getFile() + (e.getLine() > 0 ? ":" + e.getLine() : "") + ")";
        }

        private static String escape(String s) {
            return s.replace("|", "\\|");
        }
    }

    /** Konsolenzeichen je Status (auch für Screenreader als Text lesbar). */
    public static String symbol(Status s) {
        switch (s) {
            case ERROR: return "✗";
            case WARNING: return "!";
            case INFO: return "i";
            default: return "✓";
        }
    }

    static String lightSymbol(Light l) {
        return Texts.t("preflight.light." + l.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Einstellungen eines Laufs. */
    public static final class Options {
        boolean network;
        int timeoutMillis = 5000;
        Path userHome = Paths.get(System.getProperty("user.home"));
        Map<String, String> env = new HashMap<String, String>(System.getenv());
        Cancellation cancellation = Cancellation.NONE;
        RepositoryProbe probe;
        String nexus;

        public Options network(boolean on) { this.network = on; return this; }

        /**
         * Eigener Repository-Manager (Nexus, Artifactory …): der Preflight fragt alle Repositories über
         * diese URL an, als stünde sie in der settings.xml als Mirror mit {@code mirrorOf *}. Leer oder
         * {@code null} = wie Maven über die Mirrors der settings.xml. Zugangsdaten werden auch hier nie
         * gesendet.
         */
        public Options nexus(String url) {
            String u = url == null ? "" : url.trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            this.nexus = u.isEmpty() ? null : u;
            return this;
        }

        public Options timeoutMillis(int ms) { this.timeoutMillis = ms; return this; }
        public Options userHome(Path p) { this.userHome = p; return this; }
        public Options env(Map<String, String> e) { this.env = new HashMap<String, String>(e); return this; }
        public Options cancellation(Cancellation c) { this.cancellation = c == null ? Cancellation.NONE : c; return this; }
        /** Für Tests: eigener Netzzugriff. */
        public Options probe(RepositoryProbe p) { this.probe = p; return this; }
    }

    private Preflight() {
    }

    /** Taugt der Text als Repository-URL für {@link Options#nexus(String)}? ({@code http(s)://host…}) */
    public static boolean isRepositoryUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim();
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return false;
        }
        String host = io.github.bitshiftdev.mavendetective.core.check.Text.host(u);
        return host != null && !host.isEmpty() && u.indexOf(' ') < 0;
    }

    /**
     * Prüft das Projekt mit dem gegebenen Setup (Kommandozeile: {@link BuildSetup#detectCommandLine},
     * IDE: das Setup des Maven-Runners).
     */
    public static Report run(Path projectDir, BuildSetup setup, Options options) {
        PreflightContext ctx = new PreflightContext(projectDir.toAbsolutePath().normalize(), setup, options);
        List<Item> items = new ArrayList<Item>();
        add(items, Topic.SETTINGS, PreflightChecks.settings(ctx), Texts.t("preflight.settings.none"));
        add(items, Topic.JDK, PreflightChecks.jdk(ctx), null);
        add(items, Topic.MAVEN, PreflightChecks.maven(ctx), null);
        add(items, Topic.PROJECT, PreflightChecks.project(ctx), null);
        add(items, Topic.LOCAL_REPOSITORY, PreflightChecks.localRepository(ctx), null);
        add(items, Topic.REPOSITORY, RemoteRepositories.check(ctx), null);
        return new Report(ctx.project, ctx.localRepository(), items, options.network);
    }

    private static void add(List<Item> items, Topic topic, List<Item> found, String fallback) {
        if (found.isEmpty() && fallback != null) {
            items.add(Item.ok(topic, fallback));
        }
        items.addAll(found);
    }
}
