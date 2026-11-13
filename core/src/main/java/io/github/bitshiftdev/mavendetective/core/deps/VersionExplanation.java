package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Antwort auf „Warum steht genau diese Version im Classpath?“: eine Kette von Stationen, jede mit
 * Datei und Zeile, damit die IDE dorthin springen kann.
 */
public final class VersionExplanation {

    /** Woher die Version kommt (kurz, für Filter und Tests). */
    public enum Source {
        /** Direkt im Modul (oder geerbt aus einem Parent) mit Version deklariert. */
        DIRECT,
        /** Aus {@code dependencyManagement} des Moduls oder eines Parents. */
        MANAGED,
        /** Aus {@code dependencyManagement} eines importierten BOMs. */
        BOM,
        /** Transitiv, nearest wins. */
        TRANSITIVE,
        /** Nicht ermittelbar (fehlt im Modul, POM fehlt lokal …). */
        UNKNOWN
    }

    /** Eine Station der Kette. */
    public static final class Station {
        public enum Kind { TREE_PATH, DIRECT, INHERITED, IMPORT, MANAGED, PROPERTY, DECLARED_TRANSITIVE, CONFLICT, HINT }

        private final Kind kind;
        private final String text;
        private final String file;
        private final int line;
        private final int level;

        Station(Kind kind, String text, String file, int line, int level) {
            this.kind = kind;
            this.text = text;
            this.file = file;
            this.line = line;
            this.level = level;
        }

        public Kind getKind() { return kind; }
        public String getText() { return text; }
        /** Datei zum Hinspringen oder {@code null}. */
        public String getFile() { return file; }
        /** Zeile ab 1 oder -1. */
        public int getLine() { return line; }
        /** Einrückung: 0 = Hauptkette, 1 = Detail (Property, Parent-Schritt). */
        public int getLevel() { return level; }

        @Override
        public String toString() {
            return text + (file == null ? "" : " (" + file + (line > 0 ? ":" + line : "") + ")");
        }
    }

    private final String groupId;
    private final String artifactId;
    private final String module;
    private String version;
    private String treeVersion;
    private Source source = Source.UNKNOWN;
    private String summary;
    private final List<Station> stations = new ArrayList<Station>();

    VersionExplanation(String groupId, String artifactId, String module) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.module = module;
    }

    void add(Station.Kind kind, String text, String file, int line, int level) {
        stations.add(new Station(kind, text, file, line, level));
    }

    void setVersion(String v) { this.version = v; }
    void setTreeVersion(String v) { this.treeVersion = v; }
    void setSource(Source s) { this.source = s; }
    void setSummary(String s) { this.summary = s; }

    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    /** Koordinaten des betrachteten Moduls. */
    public String getModule() { return module; }
    /** Ermittelte Version (laut Baum, sonst laut POM-Analyse) oder {@code null}. */
    public String getVersion() { return version; }
    /** Version laut aufgelöstem Baum, falls einer vorlag. */
    public String getTreeVersion() { return treeVersion; }
    public Source getSource() { return source; }
    /** Ein Satz: woher die Version kommt. */
    public String getSummary() { return summary; }
    public List<Station> getStations() { return Collections.unmodifiableList(stations); }

    public List<Station> hints() {
        List<Station> out = new ArrayList<Station>();
        for (Station s : stations) {
            if (s.kind == Station.Kind.HINT) {
                out.add(s);
            }
        }
        return out;
    }

    /** Textform für Konsole und Tests. */
    public List<String> lines() {
        List<String> out = new ArrayList<String>();
        out.add(Texts.t("why.title", groupId + ":" + artifactId + (version == null ? "" : " " + version), module));
        out.add(summary);
        int n = 0;
        for (Station s : stations) {
            if (s.kind == Station.Kind.HINT) {
                continue;
            }
            String where = s.file == null ? "" : "  → " + s.file + (s.line > 0 ? ":" + s.line : "");
            if (s.level == 0) {
                n++;
                out.add("  " + n + ". " + s.text + where);
            } else {
                out.add("       " + s.text + where);
            }
        }
        List<Station> hints = hints();
        if (!hints.isEmpty()) {
            out.add(Texts.t("why.hints") + ":");
            for (Station h : hints) {
                out.add("  - " + h.text + (h.file == null ? "" : "  → " + h.file + (h.line > 0 ? ":" + h.line : "")));
            }
        }
        return out;
    }

    /** Markdown für Ticket oder Chat. */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(Texts.t("why.title", "`" + groupId + ":" + artifactId + "`" + (version == null ? "" : " " + version),
                "`" + module + "`")).append("\n\n");
        sb.append(summary).append("\n\n");
        for (Station s : stations) {
            if (s.kind == Station.Kind.HINT) {
                continue;
            }
            sb.append(s.level == 0 ? "1. " : "   - ").append(s.text);
            if (s.file != null) {
                sb.append(" — `").append(s.file).append(s.line > 0 ? ":" + s.line : "").append('`');
            }
            sb.append('\n');
        }
        List<Station> hints = hints();
        if (!hints.isEmpty()) {
            sb.append("\n**").append(Texts.t("why.hints")).append("**\n\n");
            for (Station h : hints) {
                sb.append("- ").append(h.text).append('\n');
            }
        }
        return sb.toString();
    }
}
