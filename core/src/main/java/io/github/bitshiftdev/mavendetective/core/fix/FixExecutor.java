package io.github.bitshiftdev.mavendetective.core.fix;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.io.TextFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Führt die Quick-Fixes aus, die nur Dateien betreffen. IDE-Aktionen (Datei öffnen, JDK umstellen,
 * Neubau starten) erledigt das Plugin selbst. Löscht nur innerhalb erlaubter Wurzeln, damit ein
 * manipulierter Log keine beliebigen Dateien entfernen kann. Geänderte Dateien werden in ihrer eigenen
 * Kodierung zurückgeschrieben ({@link TextFile}).
 */
public final class FixExecutor {

    /** Ergebnis: was getan wurde und ob danach neu gebaut werden soll (mit welchen Argumenten). */
    public static final class Outcome {
        public final List<String> actions = new ArrayList<String>();
        public String rebuildArgs;
    }

    private final List<Path> deletableRoots;

    /**
     * @param deletableRoots Verzeichnisse, unter denen {@code delete-files} löschen darf –
     *                       typischerweise nur das lokale Maven-Repository.
     */
    public FixExecutor(List<Path> deletableRoots) {
        this.deletableRoots = new ArrayList<Path>();
        for (Path p : deletableRoots) {
            this.deletableRoots.add(p.toAbsolutePath().normalize());
        }
    }

    public static boolean canExecute(FixAction fix) {
        String t = fix.getType();
        return FixAction.DELETE_FILES.equals(t) || FixAction.REPLACE_IN_FILE.equals(t) || FixAction.SET_JVM_OPTION.equals(t)
                || PomEditor.isPomFix(t);
    }

    public Outcome execute(FixAction fix) throws IOException {
        Outcome o = new Outcome();
        String type = fix.getType();
        if (FixAction.DELETE_FILES.equals(type)) {
            for (String f : fix.param("files").split("\n")) {
                if (f.trim().isEmpty()) {
                    continue;
                }
                Path p = Paths.get(f.trim()).toAbsolutePath().normalize();
                if (!allowed(p)) {
                    throw new IOException(Texts.t("exec.delete.refused", p));
                }
                if (Files.deleteIfExists(p)) {
                    o.actions.add(Texts.t("exec.deleted", p));
                }
            }
            o.rebuildArgs = fix.param("args") == null ? "" : fix.param("args");
        } else if (FixAction.REPLACE_IN_FILE.equals(type)) {
            Path p = Paths.get(fix.param("file"));
            TextFile file = TextFile.read(p);
            String content = file.getText();
            String search = fix.param("search");
            String replace = fix.param("replace");
            if (isXml(p)) {
                // settings.xml/POM: nur ganze Repository-Adressen, keine längeren mit gleichem Anfang, keine Kommentare
                PomEditor.Edit edit;
                try {
                    edit = PomEditor.replaceUrl(content, search, replace);
                } catch (IllegalArgumentException e) {
                    throw new IOException(Texts.t("exec.url.not.found", p, search), e);
                }
                file.write(edit.apply(content));
                o.actions.add(p + ":" + edit.line + ": " + edit.description);
            } else {
                if (!content.contains(search)) {
                    throw new IOException(Texts.t("exec.text.not.found", p, search));
                }
                file.write(content.replace(search, replace));
                o.actions.add(Texts.t("exec.replaced", p, search, replace));
            }
            o.rebuildArgs = "";
        } else if (FixAction.SET_JVM_OPTION.equals(type)) {
            Path p = Paths.get(fix.param("file"));
            String option = fix.param("option");
            TextFile file = TextFile.readOrCreate(p);
            file.write(setJvmOption(file.getText(), option));
            o.actions.add(p + ": " + option);
            o.rebuildArgs = "";
        } else if (PomEditor.isPomFix(type)) {
            Path p = Paths.get(fix.param("file"));
            TextFile file = TextFile.read(p);
            String content = file.getText();
            PomEditor.Edit edit;
            try {
                edit = PomEditor.forFix(content, fix);
            } catch (IllegalArgumentException e) {
                throw new IOException(Texts.t("exec.not.editable", p, e.getMessage()), e);
            }
            file.write(edit.apply(content));
            o.actions.add(p + ":" + edit.line + ": " + edit.description);
            o.rebuildArgs = "";
        } else {
            throw new IllegalArgumentException(Texts.t("exec.unsupported", type));
        }
        return o;
    }

    private static boolean isXml(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".pom");
    }

    /**
     * Setzt {@code option} in einer {@code .mvn/jvm.config}, Zeile für Zeile: Eine vorhandene Angabe
     * derselben Option wird an ihrer Stelle ersetzt (weitere entfallen), sonst kommt die Option als
     * eigene Zeile ans Ende. Kommentare, Leerzeilen und die Aufteilung auf Zeilen bleiben stehen.
     */
    static String setJvmOption(String content, String option) {
        String nl = content.contains("\r\n") ? "\r\n" : "\n";
        String key = optionKey(option);
        String[] lines = content.split("\\r?\\n", -1);
        List<String> out = new ArrayList<String>();
        boolean placed = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                out.add(line);
                continue;
            }
            List<String> kept = new ArrayList<String>();
            boolean hit = false;
            for (String token : t.split("\\s+")) {
                if (optionKey(token).equals(key)) {
                    hit = true;
                    if (!placed) {
                        kept.add(option);
                        placed = true;
                    }
                } else {
                    kept.add(token);
                }
            }
            if (!hit) {
                out.add(line);
            } else if (!kept.isEmpty()) {
                out.add(line.substring(0, line.indexOf(t)) + String.join(" ", kept));
            }
        }
        // Am Ende steht nach einem abschließenden Zeilenumbruch ein leerer Rest – die neue Zeile davor
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        if (!placed) {
            out.add(option);
        }
        return String.join(nl, out) + nl;
    }

    /** "-Xmx2g" → "-Xmx", "-XX:MaxMetaspaceSize=512m" → "-XX:MaxMetaspaceSize". */
    static String optionKey(String option) {
        if (option.startsWith("-Xmx") || option.startsWith("-Xms") || option.startsWith("-Xss")) {
            return option.substring(0, 4);
        }
        int eq = option.indexOf('=');
        return eq > 0 ? option.substring(0, eq) : option;
    }

    private boolean allowed(Path p) {
        for (Path root : deletableRoots) {
            if (p.startsWith(root) && !p.equals(root)) {
                return true;
            }
        }
        return false;
    }
}
