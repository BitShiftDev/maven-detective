package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log-Navigator: gliedert die Textausgabe eines Maven-Laufs in Module und Plugin-Schritte, findet
 * Download-Rauschen (zum Einklappen), den ersten echten Fehler und das gescheiterte Modul. Arbeitet
 * zeilenweise auf dem Text, wie er in Konsole oder CI-Log steht (Maven 3.x und 4, mit oder ohne
 * Zeitstempel und ANSI-Farben). Zeilennummern sind 0-basiert.
 */
public final class LogOutline {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern DOWNLOAD = Pattern.compile(
            "^(?:Downloading|Downloaded|Uploading|Uploaded)(?: from| to)? [^:]*:\\s*\\S+.*$|^(?:Downloading|Downloaded): \\S+.*$");
    private static final Pattern PROGRESS = Pattern.compile("^Progress \\(\\d+\\): .*$");
    private static final Pattern GA_HEADER = Pattern.compile("^-+< (\\S+):(\\S+) >-+$");
    private static final Pattern BUILDING = Pattern.compile("^Building (.+?)(?: (\\S+))?(?:\\s+\\[(\\d+)/(\\d+)\\])?$");
    private static final Pattern STEP = Pattern.compile("^--- (\\S+) \\(([^)]*)\\) @ (\\S+) ---$");
    private static final Pattern SUMMARY_ROW = Pattern.compile(
            "^(.+?) \\.+\\s*(SUCCESS|FAILURE|SKIPPED)(?:\\s*\\[\\s*([^\\]]+?)\\s*\\])?\\s*$");
    private static final Pattern FAILED_GOAL = Pattern.compile("Failed to execute goal \\S+ \\([^)]*\\) on project ([^:\\s]+)");
    private static final Pattern BUILD_RESULT = Pattern.compile("^BUILD (SUCCESS|FAILURE)\\s*$");
    /** Maven-Textbausteine, die nur zum eigentlichen Fehler hinführen. */
    private static final Pattern NOT_A_CAUSE = Pattern.compile(
            "^(?:COMPILATION ERROR\\s*:?|-+|Errors:|Failures:|Tests run:.*|To see the full stack trace.*|"
                    + "Re-run Maven using the -X switch.*|For more information about the errors.*|"
                    + "After correcting the problems.*|\\[Help \\d+\\].*|mvn <args> -rf .*|)$");
    /** Download-Läufe ab dieser Länge werden zum Einklappen angeboten. */
    public static final int MIN_FOLD = 2;

    /** Ergebnis eines Moduls laut Reactor Summary. */
    public enum Result { SUCCESS, FAILURE, SKIPPED }

    /** Ein Plugin-Schritt, z. B. {@code compiler:3.13.0:compile (default-compile)}. */
    public static final class Step {
        private final String mojo;
        private final String execution;
        private final int line;
        private int errors;

        Step(String mojo, String execution, int line) {
            this.mojo = mojo;
            this.execution = execution;
            this.line = line;
        }

        public String getMojo() { return mojo; }
        public String getExecution() { return execution; }
        public int getLine() { return line; }
        public int getErrors() { return errors; }

        /** „compiler:compile (default-compile)“ – Version weggelassen. */
        public String label() {
            String[] p = mojo.split(":");
            if (p.length < 3) {
                return mojo + " (" + execution + ")";
            }
            String plugin = p[p.length - 3];
            if (plugin.startsWith("maven-")) {
                plugin = plugin.substring("maven-".length());
            }
            if (plugin.endsWith("-maven-plugin")) {
                plugin = plugin.substring(0, plugin.length() - "-maven-plugin".length());
            } else if (plugin.endsWith("-plugin")) {
                plugin = plugin.substring(0, plugin.length() - "-plugin".length());
            }
            return plugin + ":" + p[p.length - 1] + " (" + execution + ")";
        }
    }

    /** Ein Modul des Reactors mit Zeilenbereich {@code [start, end)}. */
    public static final class Module {
        private final String name;
        private final String version;
        private String groupId;
        private String artifactId;
        private final int start;
        private int end;
        private Result result;
        private String time;
        private int errors;
        private int warnings;
        private int firstError = -1;
        private final List<Step> steps = new ArrayList<Step>();

        Module(String name, String version, int start) {
            this.name = name;
            this.version = version;
            this.start = start;
        }

        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public int getStart() { return start; }
        public int getEnd() { return end; }
        /** Laut Reactor Summary bzw. BUILD-Zeile; {@code null}: Lauf abgebrochen oder unbekannt. */
        public Result getResult() { return result; }
        public String getTime() { return time; }
        public int getErrors() { return errors; }
        public int getWarnings() { return warnings; }
        public int getFirstError() { return firstError; }
        public List<Step> getSteps() { return Collections.unmodifiableList(steps); }

        /** Plugin-Schritt mit dem ersten Fehler des Moduls, oder {@code null}. */
        public Step getFailedStep() {
            if (firstError < 0) {
                return null;
            }
            Step found = null;
            for (Step s : steps) {
                if (s.line <= firstError) {
                    found = s;
                }
            }
            return found;
        }
    }

    /** Zusammenhängende Download-Zeilen {@code [start, end)}. */
    public static final class Fold {
        private final int start;
        private final int end;

        Fold(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() { return start; }
        public int getEnd() { return end; }
        public int size() { return end - start; }
    }

    private final List<String> lines;
    private final List<Module> modules = new ArrayList<Module>();
    private final List<Fold> downloads = new ArrayList<Fold>();
    private final List<Integer> errorLines = new ArrayList<Integer>();
    private int downloadLines;
    private int firstError = -1;
    private int summaryLine = -1;
    private int resultLine = -1;
    private Result buildResult;
    private Module failedModule;

    private LogOutline(List<String> lines) {
        this.lines = lines;
    }

    public static LogOutline of(String log) {
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, log.split("\\r?\\n", -1));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return of(lines);
    }

    public static LogOutline of(List<String> rawLines) {
        LogOutline o = new LogOutline(Collections.unmodifiableList(new ArrayList<String>(rawLines)));
        o.analyze();
        return o;
    }

    // ------------------------------------------------------------------ öffentliche Helfer

    /** Download-/Upload-Zeile oder Fortschrittsanzeige? (auch für das Einklappen in der Konsole) */
    public static boolean isDownloadNoise(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return false;
        }
        String text = text(rawLine);
        if (text.isEmpty()) {
            return false;
        }
        char c = text.charAt(0);
        if (c != 'D' && c != 'U' && c != 'P') {
            return false;   // schneller Ausstieg: die Konsole fragt jede Zeile
        }
        return DOWNLOAD.matcher(text).matches() || PROGRESS.matcher(text).matches();
    }

    /** Text ohne ANSI-Farben, Zeitstempel und Level-Präfix. */
    static String text(String rawLine) {
        String line = rawLine.indexOf('\u001B') >= 0 ? ANSI.matcher(rawLine).replaceAll("") : rawLine;
        int n = line.length();
        while (n > 0 && (line.charAt(n - 1) == '\n' || line.charAt(n - 1) == '\r')) {
            n--;
        }
        line = line.substring(0, n);
        int cr = line.lastIndexOf('\r');
        if (cr >= 0) {
            line = line.substring(cr + 1);   // Fortschrittsanzeigen überschreiben die Zeile
        }
        return LogPrefix.parse(line).getText().trim();
    }

    private static String level(String rawLine) {
        String line = rawLine.indexOf('\u001B') >= 0 ? ANSI.matcher(rawLine).replaceAll("") : rawLine;
        return LogPrefix.parse(line).getLevel();
    }

    // ------------------------------------------------------------------ Analyse

    private void analyze() {
        Module current = null;
        String pendingGroup = null;
        String pendingArtifact = null;
        int pendingHeader = -1;
        int runStart = -1;
        boolean inSummary = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String text = text(raw);
            String level = level(raw);

            if (isDownloadNoise(raw)) {
                downloadLines++;
                if (runStart < 0) {
                    runStart = i;
                }
                continue;
            }
            closeRun(runStart, i);
            runStart = -1;

            Matcher m = GA_HEADER.matcher(text);
            if (m.matches()) {
                pendingGroup = m.group(1);
                pendingArtifact = m.group(2);
                pendingHeader = i;
                continue;
            }
            m = BUILDING.matcher(text);
            if (m.matches() && !inSummary && !text.startsWith("Building jar:") && !text.startsWith("Building war:")
                    && !text.startsWith("Building ear:") && !text.contains(": ")) {
                int start = pendingHeader == i - 1 ? pendingHeader : i;
                if (current != null) {
                    current.end = start;
                }
                current = new Module(m.group(1), m.group(2), start);
                if (pendingHeader == i - 1) {
                    current.groupId = pendingGroup;
                    current.artifactId = pendingArtifact;
                }
                modules.add(current);
                pendingHeader = -1;
                continue;
            }
            if (text.startsWith("Reactor Summary")) {
                inSummary = true;
                summaryLine = i;
                if (current != null) {
                    current.end = i;
                    current = null;
                }
                continue;
            }
            m = BUILD_RESULT.matcher(text);
            if (m.matches()) {
                inSummary = false;
                resultLine = i;
                buildResult = Result.valueOf(m.group(1));
                if (current != null) {
                    current.end = i;
                    current = null;
                }
                continue;
            }
            if (inSummary) {
                m = SUMMARY_ROW.matcher(text);
                if (m.matches()) {
                    Module mod = byName(m.group(1));
                    if (mod != null) {
                        mod.result = Result.valueOf(m.group(2));
                        mod.time = m.group(3);
                    }
                }
                continue;
            }
            m = STEP.matcher(text);
            if (m.matches() && current != null) {
                current.steps.add(new Step(m.group(1), m.group(2), i));
                continue;
            }
            boolean error = "ERROR".equals(level) || "FATAL".equals(level);
            if (error && !NOT_A_CAUSE.matcher(text).matches()) {
                errorLines.add(i);
                if (firstError < 0) {
                    firstError = i;
                }
                if (current != null) {
                    current.errors++;
                    if (current.firstError < 0) {
                        current.firstError = i;
                    }
                    Step step = current.steps.isEmpty() ? null : current.steps.get(current.steps.size() - 1);
                    if (step != null) {
                        step.errors++;
                    }
                }
                Matcher goal = FAILED_GOAL.matcher(text);
                if (goal.find() && failedModule == null) {
                    failedModule = byArtifactOrName(goal.group(1));
                }
            } else if (("WARNING".equals(level) || "WARN".equals(level)) && current != null) {
                current.warnings++;
            }
        }
        closeRun(runStart, lines.size());
        if (current != null) {
            current.end = lines.size();
        }
        finish();
    }

    private void closeRun(int start, int end) {
        if (start >= 0 && end - start >= MIN_FOLD) {
            downloads.add(new Fold(start, end));
        }
    }

    private void finish() {
        if (modules.size() == 1 && buildResult != null && modules.get(0).result == null) {
            modules.get(0).result = buildResult;   // einzelnes Modul: kein Reactor Summary
        }
        for (Module m : modules) {
            if (m.result == Result.FAILURE && failedModule == null) {
                failedModule = m;
            }
        }
        if (failedModule == null && buildResult == Result.FAILURE) {
            for (Module m : modules) {
                if (m.errors > 0) {
                    failedModule = m;
                    break;
                }
            }
        }
    }

    private Module byName(String summaryName) {
        String name = summaryName.trim();
        Module best = null;
        for (Module m : modules) {
            if (name.equals(m.name) || name.equals(m.name + " " + m.version)) {
                return m;
            }
            if (best == null && name.startsWith(m.name + " ")) {
                best = m;
            }
        }
        return best;
    }

    private Module byArtifactOrName(String project) {
        for (Module m : modules) {
            if (project.equals(m.artifactId)) {
                return m;
            }
        }
        for (Module m : modules) {
            if (project.equals(m.name)) {
                return m;
            }
        }
        return modules.size() == 1 ? modules.get(0) : null;
    }

    // ------------------------------------------------------------------ Ergebnis

    public List<String> getLines() { return lines; }
    public List<Module> getModules() { return Collections.unmodifiableList(modules); }
    /** Download-Läufe ab {@link #MIN_FOLD} Zeilen. */
    public List<Fold> getDownloads() { return Collections.unmodifiableList(downloads); }
    /** Alle Download-Zeilen, auch einzelne. */
    public int getDownloadLines() { return downloadLines; }
    /** Zeilen mit echten Fehlern (ohne Maven-Textbausteine). */
    public List<Integer> getErrorLines() { return Collections.unmodifiableList(errorLines); }
    /** Erster echter Fehler, oder -1. */
    public int getFirstError() { return firstError; }
    public int getSummaryLine() { return summaryLine; }
    /** Zeile {@code BUILD SUCCESS/FAILURE}, oder -1. */
    public int getResultLine() { return resultLine; }
    public Result getBuildResult() { return buildResult; }
    /** Gescheitertes Modul, oder {@code null}. */
    public Module getFailedModule() { return failedModule; }

    /** Modul, zu dem eine Zeile gehört, oder {@code null}. */
    public Module moduleAt(int line) {
        for (Module m : modules) {
            if (line >= m.start && line < m.end) {
                return m;
            }
        }
        return null;
    }

    /** Ob eine Zeile in einem eingeklappten Download-Lauf liegt. */
    public boolean isFolded(int line) {
        for (Fold f : downloads) {
            if (line >= f.start && line < f.end) {
                return true;
            }
        }
        return false;
    }

    /** Text ohne Download-Läufe; jeder Lauf wird zu einer Zeile „… N Download-Zeilen ausgeblendet …“. */
    public List<String> withoutDownloads() {
        List<String> out = new ArrayList<String>();
        int f = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (f < downloads.size() && downloads.get(f).start == i) {
                Fold fold = downloads.get(f++);
                out.add(placeholder(fold.size()));
                i = fold.end - 1;
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    public static String placeholder(int count) {
        return Texts.t("outline.fold", count);
    }

    /** Ausgabe für die Konsole: Module, erster Fehler mit Umfeld, Downloads. */
    public List<String> summary(int context) {
        List<String> out = new ArrayList<String>();
        String result = buildResult == null ? Texts.t("outline.result.unknown")
                : Texts.t("outline.result." + buildResult.name().toLowerCase(Locale.ROOT));
        out.add(Texts.t("outline.title", lines.size(), result));
        if (!modules.isEmpty()) {
            out.add("");
            out.add(Texts.t("outline.modules", modules.size()));
            for (Module m : modules) {
                StringBuilder sb = new StringBuilder("  ");
                sb.append(symbol(m)).append(' ').append(m.name);
                sb.append("  ").append(Texts.t("outline.lines", m.start + 1, m.end));
                if (m.time != null) {
                    sb.append("  ").append(m.time);
                }
                if (m.errors > 0) {
                    sb.append("  ").append(Texts.t("outline.errors", m.errors));
                }
                if (m == failedModule) {
                    Step s = m.getFailedStep();
                    sb.append("  ← ").append(s == null ? Texts.t("outline.failed") : Texts.t("outline.failed.in", s.label()));
                }
                out.add(sb.toString());
            }
        }
        out.add("");
        if (firstError < 0) {
            out.add(Texts.t("outline.no.error"));
        } else {
            out.add(Texts.t("outline.first.error", firstError + 1));
            int from = Math.max(0, firstError - context);
            int to = Math.min(lines.size(), firstError + context + 1);
            for (int i = from; i < to; i++) {
                if (isFolded(i)) {
                    continue;
                }
                out.add((i == firstError ? "  > " : "    ") + String.format(Locale.ROOT, "%6d  ", i + 1) + lines.get(i));
            }
        }
        if (downloadLines > 0) {
            out.add("");
            out.add(Texts.t("outline.downloads", downloadLines, downloads.size()));
        }
        return out;
    }

    private static String symbol(Module m) {
        if (m.result == null) {
            return m.errors > 0 ? "✗" : "·";
        }
        switch (m.result) {
            case FAILURE: return "✗";
            case SKIPPED: return "–";
            default: return "✓";
        }
    }
}
