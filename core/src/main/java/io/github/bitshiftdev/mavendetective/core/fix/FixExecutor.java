package io.github.bitshiftdev.mavendetective.core.fix;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Führt die Quick-Fixes aus, die nur Dateien betreffen. IDE-Aktionen (Datei öffnen, JDK umstellen,
 * Neubau starten) erledigt das Plugin selbst. Löscht nur innerhalb erlaubter Wurzeln, damit ein
 * manipulierter Log keine beliebigen Dateien entfernen kann.
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
                || FixAction.PIN_VERSION.equals(t);
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
                    throw new IOException("Löschen außerhalb des lokalen Repositorys verweigert: " + p);
                }
                if (Files.deleteIfExists(p)) {
                    o.actions.add("gelöscht: " + p);
                }
            }
            o.rebuildArgs = fix.param("args") == null ? "" : fix.param("args");
        } else if (FixAction.REPLACE_IN_FILE.equals(type)) {
            Path p = Paths.get(fix.param("file"));
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String search = fix.param("search");
            if (!content.contains(search)) {
                throw new IOException("Text nicht gefunden in " + p + ": " + search);
            }
            Files.write(p, content.replace(search, fix.param("replace")).getBytes(StandardCharsets.UTF_8));
            o.actions.add("ersetzt in " + p + ": " + search + " → " + fix.param("replace"));
            o.rebuildArgs = "";
        } else if (FixAction.SET_JVM_OPTION.equals(type)) {
            Path p = Paths.get(fix.param("file"));
            String option = fix.param("option");
            List<String> tokens = new ArrayList<String>();
            if (Files.isRegularFile(p)) {
                tokens.addAll(Arrays.asList(new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim().split("\\s+")));
                tokens.remove("");
            }
            String key = optionKey(option);
            List<String> kept = new ArrayList<String>();
            for (String t : tokens) {
                if (!optionKey(t).equals(key)) {
                    kept.add(t);
                }
            }
            kept.add(option);
            Files.createDirectories(p.getParent());
            Files.write(p, (String.join(" ", kept) + "\n").getBytes(StandardCharsets.UTF_8));
            o.actions.add(p + ": " + option);
            o.rebuildArgs = "";
        } else if (FixAction.PIN_VERSION.equals(type)) {
            Path p = Paths.get(fix.param("file"));
            String artifact = fix.param("artifact");
            int colon = artifact == null ? -1 : artifact.indexOf(':');
            if (colon < 0 || fix.param("version") == null) {
                throw new IOException("pin-version braucht artifact (groupId:artifactId) und version");
            }
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            PomEditor.Edit edit;
            try {
                edit = PomEditor.pinManagedVersion(content, artifact.substring(0, colon), artifact.substring(colon + 1), fix.param("version"));
            } catch (IllegalArgumentException e) {
                throw new IOException(p + " lässt sich nicht bearbeiten: " + e.getMessage(), e);
            }
            Files.write(p, edit.apply(content).getBytes(StandardCharsets.UTF_8));
            o.actions.add(p + ":" + edit.line + ": " + edit.description);
            o.rebuildArgs = "";
        } else {
            throw new IllegalArgumentException("Quick-Fix-Typ wird hier nicht ausgeführt: " + type);
        }
        return o;
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
