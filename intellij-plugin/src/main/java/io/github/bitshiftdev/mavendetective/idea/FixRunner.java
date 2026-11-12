package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.fix.FixExecutor;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;

import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Führt Quick-Fixes in der IDE aus. */
final class FixRunner {

    private FixRunner() {
    }

    static boolean isSupported(FixAction fix) {
        return switch (fix.getType()) {
            case FixAction.OPEN_FILE, FixAction.DELETE_FILES, FixAction.REPLACE_IN_FILE,
                 FixAction.SET_JVM_OPTION, FixAction.REBUILD, FixAction.SWITCH_JDK,
                 FixAction.INSERT_PLUGIN_VERSION, FixAction.PIN_VERSION -> true;
            // show-dependency: Modul A
            default -> false;
        };
    }

    static void run(Project project, FixAction fix, BuildDoctorService.Result result) {
        try {
            switch (fix.getType()) {
                case FixAction.OPEN_FILE -> openLocation(project, fix.param("file") + ":" + fix.param("line"));
                case FixAction.DELETE_FILES, FixAction.REPLACE_IN_FILE, FixAction.SET_JVM_OPTION -> {
                    FixExecutor.Outcome o = executor(result).execute(fix);
                    refresh(fix);
                    notify(project, String.join("\n", o.actions), NotificationType.INFORMATION);
                    rebuild(project, result, o.rebuildArgs);
                }
                case FixAction.REBUILD -> rebuild(project, result, fix.param("args"));
                case FixAction.SWITCH_JDK -> switchJdk(project, result, fix.param("required"));
                case FixAction.INSERT_PLUGIN_VERSION -> insertPluginVersion(project, result, fix);
                case FixAction.PIN_VERSION -> {
                    if (pinVersion(project, fix)) {
                        rebuild(project, result, null);
                    }
                }
                default -> notify(project, fix.getLabel(), NotificationType.INFORMATION);
            }
        } catch (IOException | RuntimeException e) {
            notify(project, "Quick-Fix fehlgeschlagen: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    /** Löschen nur im lokalen Repository erlauben. */
    private static FixExecutor executor(BuildDoctorService.Result result) {
        List<Path> roots = new ArrayList<>();
        String repo = result.failure().getSession().getLocalRepository();
        roots.add(repo != null ? Paths.get(repo) : Paths.get(System.getProperty("user.home"), ".m2", "repository"));
        return new FixExecutor(roots);
    }

    /**
     * Neubau: ohne Zusatzargumente startet derselbe Lauf neu. Mit Argumenten (-U, -am) startet eine
     * temporäre Kopie der Run-Konfiguration mit den Argumenten zusätzlich – die gespeicherte
     * Konfiguration des Nutzers bleibt unverändert. Ohne Lauf (Log aus der Zwischenablage) wird der
     * Befehl in die Zwischenablage gelegt.
     */
    private static void rebuild(Project project, BuildDoctorService.Result result, String args) {
        boolean hasArgs = args != null && !args.isBlank();
        if (!hasArgs && result.environment() != null) {
            ExecutionUtil.restart(result.environment());
            return;
        }
        MavenRunConfiguration cfg = result.configuration();
        if (hasArgs && cfg != null) {
            MavenRunnerParameters params = cfg.getRunnerParameters().clone();
            String old = params.getCmdOptions();
            params.setCmdOptions(old == null || old.isBlank() ? args.trim() : old.trim() + " " + args.trim());
            MavenRunConfigurationType.runConfiguration(project, params, cfg.getGeneralSettings(), cfg.getRunnerSettings(), null);
            return;
        }
        if (hasArgs) {
            String cmd = "mvn " + String.join(" ", result.failure().getSession().getGoals()) + " " + args;
            CopyPasteManager.getInstance().setContents(new StringSelection(cmd.replaceAll(" +", " ").trim()));
            notify(project, "Neu bauen mit " + args + " – Befehl liegt in der Zwischenablage.", NotificationType.INFORMATION);
        }
    }

    /**
     * Öffnet die Stelle, an der das JDK des Maven-Laufs eingestellt wird: die Run-Konfiguration, wenn
     * sie eigene Runner-Einstellungen hat, sonst Einstellungen › Maven › Runner.
     */
    private static void switchJdk(Project project, BuildDoctorService.Result result, String required) {
        String hint = required == null || required.isBlank() ? "Passendes JDK wählen." : "JDK " + required + " oder neuer wählen.";
        MavenRunConfiguration cfg = result.configuration();
        if (cfg != null && cfg.getRunnerSettings() != null && result.environment() != null
                && result.environment().getRunnerAndConfigurationSettings() != null) {
            notify(project, "Diese Run-Konfiguration hat eigene Runner-Einstellungen. " + hint + " (Reiter Runner › JRE)",
                    NotificationType.INFORMATION);
            if (RunDialog.editConfiguration(project, result.environment().getRunnerAndConfigurationSettings(), "JDK des Maven-Laufs")) {
                ExecutionUtil.restart(result.environment());
            }
            return;
        }
        notify(project, hint + " (Feld JRE)", NotificationType.INFORMATION);
        ShowSettingsUtil.getInstance().showSettingsDialog(project,
                c -> c instanceof SearchableConfigurable sc && RUNNER_SETTINGS_ID.equals(sc.getId()), null);
    }

    private static final String RUNNER_SETTINGS_ID = "reference.settings.project.maven.runner";

    /**
     * Trägt hinter {@code <artifactId>} des Plugins die Version ein, mit der Maven es gerade auflöst
     * (Effective POM der IDE). Als ein Undo-Schritt; danach Neubau.
     */
    private static void insertPluginVersion(Project project, BuildDoctorService.Result result, FixAction fix) {
        String file = fix.param("file");
        String plugin = fix.param("plugin");
        int line = parseInt(fix.param("line"));
        int colon = plugin == null ? -1 : plugin.indexOf(':');
        if (file == null || colon < 0) {
            notify(project, fix.getLabel(), NotificationType.INFORMATION);
            return;
        }
        String groupId = plugin.substring(0, colon);
        String artifactId = plugin.substring(colon + 1);
        String version = IdeMaven.resolvedPluginVersion(project, groupId, artifactId);
        if (version == null) {
            openLocation(project, file + ":" + line);
            notify(project, "Keine aufgelöste Version für " + plugin + " bekannt. Bitte die gewünschte Version eintragen.",
                    NotificationType.WARNING);
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(file));
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            notify(project, "Datei nicht gefunden: " + file, NotificationType.WARNING);
            return;
        }
        String tag = "<artifactId>" + artifactId + "</artifactId>";
        CharSequence text = doc.getCharsSequence();
        int from = line > 0 && line <= doc.getLineCount() ? doc.getLineStartOffset(line - 1) : 0;
        int at = text.toString().indexOf(tag, from);
        if (at < 0) {
            at = text.toString().indexOf(tag);
        }
        if (at < 0) {
            openLocation(project, file + ":" + line);
            return;
        }
        int lineNo = doc.getLineNumber(at);
        int lineStart = doc.getLineStartOffset(lineNo);
        String indent = text.subSequence(lineStart, at).toString().replaceAll("\\S.*", "");
        int insertAt = at + tag.length();
        String insertion = "\n" + indent + "<version>" + version + "</version>";
        WriteCommandAction.runWriteCommandAction(project, "Plugin-Version einfügen", null,
                () -> doc.insertString(insertAt, insertion));
        FileDocumentManager.getInstance().saveDocument(doc);
        openLocation(project, file + ":" + (lineNo + 2));
        notify(project, "Version " + version + " für " + plugin + " eingetragen.", NotificationType.INFORMATION);
        rebuild(project, result, null);
    }

    /**
     * Modul A2: Version unter {@code dependencyManagement} der POM aus {@code file} festlegen – als ein
     * Undo-Schritt im Dokument, danach Maven-Import anstoßen. {@code false}, wenn nichts geändert wurde.
     */
    static boolean pinVersion(Project project, FixAction fix) {
        String file = fix.param("file");
        String artifact = fix.param("artifact");
        String version = fix.param("version");
        int colon = artifact == null ? -1 : artifact.indexOf(':');
        if (file == null || colon < 0 || version == null) {
            notify(project, fix.getLabel(), NotificationType.INFORMATION);
            return false;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(file));
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            notify(project, "Datei nicht gefunden: " + file, NotificationType.WARNING);
            return false;
        }
        PomEditor.Edit edit;
        try {
            edit = PomEditor.pinManagedVersion(doc.getText(), artifact.substring(0, colon), artifact.substring(colon + 1), version);
        } catch (IllegalArgumentException e) {
            openLocation(project, file);
            notify(project, vf.getName() + " lässt sich nicht automatisch bearbeiten (" + e.getMessage() + "). Bitte "
                    + artifact + ":" + version + " selbst unter dependencyManagement eintragen.", NotificationType.WARNING);
            return false;
        }
        WriteCommandAction.runWriteCommandAction(project, "Version festlegen (Maven Detective)", null,
                () -> doc.replaceString(edit.offset, edit.offset + edit.length, edit.text));
        FileDocumentManager.getInstance().saveDocument(doc);
        openLocation(project, file + ":" + edit.line);
        notify(project, edit.description + " (" + vf.getName() + "). Maven-Projekt wird neu geladen.", NotificationType.INFORMATION);
        IdeMaven.reimport(project);
        return true;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "pfad:zeile" öffnen; Zeile ist 1-basiert. */
    static void openLocation(Project project, String location) {
        String path = location;
        int line = 0;
        int colon = location.lastIndexOf(':');
        if (colon > 1 && location.substring(colon + 1).matches("-?\\d+")) {
            path = location.substring(0, colon);
            line = Math.max(0, Integer.parseInt(location.substring(colon + 1)) - 1);
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(path));
        if (vf == null) {
            notify(project, "Datei nicht gefunden: " + path, NotificationType.WARNING);
            return;
        }
        new OpenFileDescriptor(project, vf, line, 0).navigate(true);
    }

    /**
     * „Falsch erkannt“: legt den Log als Testfall ab. Mit dem richtigen Regelnamen umbenannt und
     * nach core/src/test/resources/logs kopiert, wird er zum dauerhaften Regressionstest.
     */
    static void reportMisdiagnosis(Project project, BuildDoctorService.Result result) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".maven-detective", "testfaelle");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String detected = result.diagnoses().isEmpty() ? "none" : result.diagnoses().get(0).getRuleId();
            Path file = dir.resolve("RICHTIGE-REGEL__erkannt-als-" + detected + "-" + stamp + ".log");
            Files.write(file, result.rawLog().getBytes(StandardCharsets.UTF_8));
            notify(project, "Testfall gespeichert: " + file + "\nBitte prüfen (keine Geheimnisse im Log?) und per Pull Request einreichen.",
                    NotificationType.INFORMATION);
        } catch (IOException e) {
            notify(project, "Testfall konnte nicht gespeichert werden: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    private static void refresh(FixAction fix) {
        for (String key : new String[] {"file", "files"}) {
            String v = fix.param(key);
            if (v == null) {
                continue;
            }
            for (String p : v.split("\n")) {
                Path path = Paths.get(p.trim());
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.getParent() != null ? path.getParent() : path);
                if (vf != null) {
                    vf.refresh(false, true);
                }
            }
        }
    }

    private static void notify(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(BuildDoctorService.TOOL_WINDOW_ID)
                .createNotification(text, type)
                .notify(project);
    }
}
