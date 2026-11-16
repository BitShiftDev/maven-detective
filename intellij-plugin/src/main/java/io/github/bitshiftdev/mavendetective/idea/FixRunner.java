package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.notification.NotificationAction;
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
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;

import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Führt Quick-Fixes in der IDE aus. */
final class FixRunner {

    private FixRunner() {
    }

    static boolean isSupported(FixAction fix) {
        return switch (fix.getType()) {
            case FixAction.OPEN_FILE, FixAction.DELETE_FILES, FixAction.REPLACE_IN_FILE, FixAction.REPLACE_URL,
                 FixAction.SET_JVM_OPTION, FixAction.REBUILD, FixAction.SWITCH_JDK,
                 FixAction.INSERT_PLUGIN_VERSION, FixAction.PIN_VERSION, FixAction.ADD_EXCLUSION, FixAction.REMOVE_EXCLUSION,
                 FixAction.ADD_DEPENDENCY, FixAction.REMOVE_DEPENDENCY, FixAction.SET_SCOPE, FixAction.SET_VERSION -> true;
            // show-dependency: Modul A
            default -> false;
        };
    }

    static void run(Project project, FixAction fix, BuildDoctorService.Result result) {
        try {
            switch (fix.getType()) {
                case FixAction.OPEN_FILE -> openLocation(project, fix.param("file") + ":" + fix.param("line"));
                case FixAction.DELETE_FILES, FixAction.REPLACE_IN_FILE, FixAction.SET_JVM_OPTION -> runFileFix(project, fix, result);
                // „Neu bauen“ ist selbst der Fix – startet immer, auch wenn Neubau nach Fixes abgeschaltet ist
                case FixAction.REBUILD -> restart(project, result, fix.param("args"), fix.getLabel());
                case FixAction.SWITCH_JDK -> switchJdk(project, result, fix);
                case FixAction.INSERT_PLUGIN_VERSION -> insertPluginVersion(project, result, fix);
                case FixAction.PIN_VERSION -> {
                    if (pinVersion(project, fix)) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                // Fix-und-Prüf-Schleife: aus der Diagnose-Karte gestartete POM-Fixes bauen danach neu
                case FixAction.ADD_EXCLUSION -> {
                    if (addExclusions(project, List.of(fix)) > 0) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                // Klassen-Herkunft (Regel 9/10): eine Exclusion entfernt die gesuchte Klasse
                case FixAction.REMOVE_EXCLUSION -> {
                    if (editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.remove.exclusions")) > 0) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                case FixAction.ADD_DEPENDENCY, FixAction.REMOVE_DEPENDENCY, FixAction.SET_SCOPE -> {
                    if (editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.change.dependency")) > 0) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                case FixAction.SET_VERSION -> {
                    if (editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.change.version")) > 0) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                // Repository-Adresse in der POM (http → https): wie jede POM-Änderung mit Vorschau und Undo
                case FixAction.REPLACE_URL -> {
                    if (editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.change.repository.url")) > 0) {
                        rebuild(project, result, null, fix.getLabel());
                    }
                }
                default -> notify(project, fix.getLabel(), NotificationType.INFORMATION);
            }
        } catch (RuntimeException e) {
            notify(project, MavenDetectiveBundle.message("fix.failed", e.getMessage()), NotificationType.ERROR);
        }
    }

    /**
     * Dateibasierte Fixes des Kerns. Vorher werden offene, ungespeicherte Dokumente der Zieldateien
     * gespeichert – sonst schriebe der Kern an ihnen vorbei und die IDE meldete einen
     * Cache-Konflikt. Die Dateiarbeit (Löschen ganzer Artefaktbäume) läuft im Hintergrund, der
     * VFS-Refresh asynchron; Meldung und Neubau folgen danach auf dem EDT.
     */
    private static void runFileFix(Project project, FixAction fix, BuildDoctorService.Result result) {
        List<Path> targets = paths(fix);
        saveDocuments(targets);
        new Task.Backgroundable(project, "Maven Detective: " + fix.getLabel(), false) {
            private FixExecutor.Outcome outcome;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    outcome = executor(project, result).execute(fix);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void onSuccess() {
                List<Path> dirs = new ArrayList<>();
                for (Path p : targets) {
                    dirs.add(p.getParent() != null ? p.getParent() : p);
                }
                FixExecutor.Outcome o = outcome;
                LocalFileSystem.getInstance().refreshNioFiles(dirs, true, true, () ->
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FixRunner.notify(project, String.join("\n", o.actions), NotificationType.INFORMATION);
                            rebuild(project, result, o.rebuildArgs, fix.getLabel());
                        }, project.getDisposed()));
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Throwable cause = error instanceof UncheckedIOException u ? u.getCause() : error;
                // qualifiziert: im anonymen Task verdeckt Object.notify() die statische Methode
                FixRunner.notify(project, MavenDetectiveBundle.message("fix.failed", cause.getMessage()), NotificationType.ERROR);
            }
        }.queue();
    }

    /** Ziel-Dateien eines Fixes aus {@code file} bzw. {@code files} (eine je Zeile). */
    private static List<Path> paths(FixAction fix) {
        List<Path> out = new ArrayList<>();
        for (String key : new String[] {"file", "files"}) {
            String v = fix.param(key);
            if (v == null) {
                continue;
            }
            for (String p : v.split("\n")) {
                if (!p.isBlank()) {
                    out.add(Paths.get(p.trim()));
                }
            }
        }
        return out;
    }

    /** Ungespeicherte Editor-Inhalte dieser Dateien auf die Platte bringen (EDT). */
    private static void saveDocuments(List<Path> files) {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        for (Path p : files) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(p);
            Document doc = vf == null ? null : fdm.getCachedDocument(vf);
            if (doc != null && fdm.isDocumentUnsaved(doc)) {
                fdm.saveDocument(doc);
            }
        }
    }

    /**
     * Löschen nur im lokalen Repository erlauben – dem, mit dem die Diagnose gerechnet hat (auch aus
     * {@code <localRepository>} der settings.xml oder den Maven-Einstellungen der IDE), sonst ~/.m2/repository.
     */
    private static FixExecutor executor(Project project, BuildDoctorService.Result result) {
        List<Path> roots = new ArrayList<>();
        if (result.localRepository() != null) {
            roots.add(result.localRepository());
        }
        String repo = result.failure().getSession().getLocalRepository();
        if (repo != null) {
            roots.add(Paths.get(repo));
        }
        if (roots.isEmpty()) {
            Path ide = IdeMaven.localRepository(project);
            roots.add(ide != null ? ide : Paths.get(System.getProperty("user.home"), ".m2", "repository"));
        }
        return new FixExecutor(roots);
    }

    /**
     * Neubau: ohne Zusatzargumente startet derselbe Lauf neu. Mit Argumenten (-U, -am) startet eine
     * temporäre Kopie der Run-Konfiguration mit den Argumenten zusätzlich – die gespeicherte
     * Konfiguration des Nutzers bleibt unverändert. Ohne Lauf (Log aus der Zwischenablage) wird der
     * Befehl in die Zwischenablage gelegt.
     *
     * <p>Startet ein Lauf, meldet {@code fixLabel} ihn als Prüfung des Fixes an: Die Karte zeigt
     * „Neu bauen läuft …“ und danach „Behoben“ oder die neue Diagnose.
     *
     * <p>Ist „Nach einem Quick-Fix neu bauen“ abgeschaltet ({@link DetectiveSettings}), startet nichts von
     * selbst; die Meldung bietet „Neu bauen“ an.
     */
    private static void rebuild(Project project, BuildDoctorService.Result result, String args, String fixLabel) {
        boolean hasArgs = args != null && !args.isBlank();
        boolean wouldStart = result.environment() != null && !hasArgs || hasArgs && result.configuration() != null;
        if (wouldStart && !DetectiveSettings.getInstance().isRebuildAfterFix()) {
            NotificationGroupManager.getInstance().getNotificationGroup(BuildDoctorService.TOOL_WINDOW_ID)
                    .createNotification(plainToHtml(MavenDetectiveBundle.message("fix.rebuild.off", fixLabel)), NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimpleExpiring(MavenDetectiveBundle.message("fix.rebuild.now"),
                            () -> restart(project, result, args, fixLabel)))
                    .notify(project);
            return;
        }
        restart(project, result, args, fixLabel);
    }

    /** Neubau wie bei {@link #rebuild}, ohne die Einstellung zu fragen. */
    private static void restart(Project project, BuildDoctorService.Result result, String args, String fixLabel) {
        boolean hasArgs = args != null && !args.isBlank();
        if (!hasArgs && result.environment() != null) {
            BuildDoctorService.getInstance(project).expectCheck(fixLabel, result);
            ExecutionUtil.restart(result.environment());
            return;
        }
        MavenRunConfiguration cfg = result.configuration();
        if (hasArgs && cfg != null) {
            BuildDoctorService.getInstance(project).expectCheck(fixLabel, result);
            MavenRunnerParameters params = cfg.getRunnerParameters().clone();
            String old = params.getCmdOptions();
            params.setCmdOptions(old == null || old.isBlank() ? args.trim() : old.trim() + " " + args.trim());
            MavenRunConfigurationType.runConfiguration(project, params, cfg.getGeneralSettings(), cfg.getRunnerSettings(), null);
            return;
        }
        if (hasArgs) {
            String cmd = "mvn " + String.join(" ", result.failure().getSession().getGoals()) + " " + args;
            CopyPasteManager.getInstance().setContents(new StringSelection(cmd.replaceAll(" +", " ").trim()));
            notify(project, MavenDetectiveBundle.message("fix.rebuild.copied", args), NotificationType.INFORMATION);
        }
    }

    /**
     * Öffnet die Stelle, an der das JDK des Maven-Laufs eingestellt wird: die Run-Konfiguration, wenn
     * sie eigene Runner-Einstellungen hat, sonst Einstellungen › Maven › Runner.
     */
    private static void switchJdk(Project project, BuildDoctorService.Result result, FixAction fix) {
        String required = fix.param("required");
        String hint = required == null || required.isBlank() ? MavenDetectiveBundle.message("fix.jdk.any") : MavenDetectiveBundle.message("fix.jdk.min", required);
        MavenRunConfiguration cfg = result.configuration();
        if (cfg != null && cfg.getRunnerSettings() != null && result.environment() != null
                && result.environment().getRunnerAndConfigurationSettings() != null) {
            notify(project, MavenDetectiveBundle.message("fix.jdk.run.configuration", hint),
                    NotificationType.INFORMATION);
            if (RunDialog.editConfiguration(project, result.environment().getRunnerAndConfigurationSettings(), MavenDetectiveBundle.message("fix.jdk.dialog"))) {
                rebuild(project, result, null, fix.getLabel());
            }
            return;
        }
        notify(project, MavenDetectiveBundle.message("fix.jdk.settings", hint), NotificationType.INFORMATION);
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
            notify(project, MavenDetectiveBundle.message("fix.plugin.version.unknown", plugin),
                    NotificationType.WARNING);
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(file));
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            notify(project, MavenDetectiveBundle.message("notification.file.not.found", file), NotificationType.WARNING);
            return;
        }
        String tag = "<artifactId>" + artifactId + "</artifactId>";
        CharSequence text = doc.getCharsSequence();
        int from = line > 0 && line <= doc.getLineCount() ? doc.getLineStartOffset(line - 1) : 0;
        // Bewusst keine Suche ab Dateianfang als Rückfall: Der erste Treffer könnte eine Dependency,
        // Exclusion oder ein anderer Plugin-Eintrag sein
        int at = pluginArtifactIdWithoutVersion(text.toString(), tag, from);
        if (at < 0) {
            openLocation(project, file + ":" + line);
            notify(project, MavenDetectiveBundle.message("fix.plugin.version.ambiguous", plugin, version), NotificationType.WARNING);
            return;
        }
        int insertAt = at + tag.length();
        Path pom = Paths.get(file);
        String description = MavenDetectiveBundle.message("fix.plugin.version.insert", version, plugin);
        Supplier<PomChanges.Plan> plan = () -> {
            int lineNo = doc.getLineNumber(at);
            String indent = doc.getCharsSequence().subSequence(doc.getLineStartOffset(lineNo), at).toString().replaceAll("\\S.*", "");
            PomChanges.Edit edit = new PomChanges.Edit(insertAt, 0, "\n" + indent + "<version>" + version + "</version>", lineNo + 2);
            return new PomChanges.Plan(List.of(PomChanges.FileChange.single(pom, doc.getText(), edit, description)), List.of(), null);
        };
        if (apply(project, plan.get(), plan, MavenDetectiveBundle.message("command.insert.plugin.version"), false) > 0) {
            rebuild(project, result, null, fix.getLabel());
        }
    }

    /**
     * Offset von {@code tag} ab {@code from}, wenn er direkt in einem {@code <plugin>} steht, das noch
     * keine {@code <version>} hat; sonst -1. Konservativ: Steht irgendwo im Plugin-Block eine
     * {@code <version>} (auch in dessen Dependencies), wird nichts eingefügt – lieber selbst
     * eintragen lassen als eine doppelte Version erzeugen.
     */
    static int pluginArtifactIdWithoutVersion(String text, String tag, int from) {
        int at = text.indexOf(tag, from);
        if (at < 0) {
            return -1;
        }
        int start = text.lastIndexOf("<plugin>", at);
        int end = text.indexOf("</plugin>", at);
        if (start < 0 || end < 0 || text.lastIndexOf("</plugin>", at) > start) {
            return -1;   // Treffer liegt nicht in einem <plugin>-Block
        }
        return text.substring(start, end).contains("<version>") ? -1 : at;
    }

    /**
     * Modul A2: Version unter {@code dependencyManagement} der POM aus {@code file} festlegen – mit
     * Vorschau, als ein Undo-Schritt, danach Maven-Import. {@code false}, wenn nichts geändert wurde.
     */
    static boolean pinVersion(Project project, FixAction fix) {
        if (fix.param("file") == null || fix.param("artifact") == null || fix.param("version") == null) {
            notify(project, fix.getLabel(), NotificationType.INFORMATION);
            return false;
        }
        return editPoms(project, List.of(fix), MavenDetectiveBundle.message("command.pin.version")) > 0;
    }

    /**
     * Modul A3: Exclusions einfügen – alle in einem Undo-Schritt, jede POM über ihr Dokument
     * (offene Editoren sehen die Änderung sofort). Danach Maven-Import.
     *
     * @return wie viele eingefügt wurden
     */
    static int addExclusions(Project project, List<FixAction> fixes) {
        return editPoms(project, fixes, MavenDetectiveBundle.message("command.add.exclusion"));
    }

    /**
     * POM-Quick-Fixes ({@link PomEditor#isPomFix}) über die Dokumente anwenden. Vorher zeigt ein
     * Diff-Dialog alle betroffenen POMs (abschaltbar, {@link PomChanges#isPreviewEnabled()}); dann alle
     * in einem Undo-Schritt, offene Editoren sehen die Änderung sofort. Danach Maven-Import.
     *
     * @return wie viele Änderungen geschrieben wurden (0: abgebrochen oder nichts anwendbar)
     */
    static int editPoms(Project project, List<FixAction> fixes, String command) {
        return editPoms(project, fixes, command, false, null);
    }

    /**
     * Wie oben, aber der Nutzer hat {@code shown} schon als Diff gesehen (Upgrade-Ergebnis im Editor-Tab):
     * kein zweiter Dialog; geschrieben wird nur, wenn die Dokumente noch genau so aussehen.
     */
    static int editPoms(Project project, List<FixAction> fixes, String command, PomChanges.Plan shown) {
        return editPoms(project, fixes, command, true, shown);
    }

    private static int editPoms(Project project, List<FixAction> fixes, String command, boolean previewed,
                                @Nullable PomChanges.Plan shown) {
        return apply(project, shown != null ? shown : plan(fixes), () -> plan(fixes), command, previewed);
    }

    /**
     * Geplante Änderungen schreiben: erst Vorschau (außer {@code previewed} oder abgeschaltet), dann prüfen,
     * ob {@code replan} noch genau dasselbe ergibt, dann alles in einem Undo-Schritt, speichern, Maven-Import.
     */
    private static int apply(Project project, PomChanges.Plan plan, Supplier<PomChanges.Plan> replan, String command,
                             boolean previewed) {
        if (plan.isEmpty()) {
            if (!plan.problems().isEmpty()) {
                if (plan.failed() != null) {
                    openLocation(project, plan.failed().toString());
                }
                notify(project, String.join("\n", plan.problems()) + "\n" + MavenDetectiveBundle.message("fix.by.hand"),
                        NotificationType.WARNING);
            }
            return 0;
        }
        if (!previewed && PomChanges.isPreviewEnabled() && !PomChangePreviewDialog.confirm(project, plan, command)) {
            return 0;
        }
        // Hat sich ein Dokument seit der Vorschau geändert (Editor-Tab ist nicht modal), nichts schreiben
        PomChanges.Plan now = replan.get();
        if (!sameTexts(plan, now)) {
            notify(project, MavenDetectiveBundle.message("fix.changed.since.preview"), NotificationType.WARNING);
            return 0;
        }
        List<Document> docs = new ArrayList<>();
        for (PomChanges.FileChange c : now.files()) {
            docs.add(document(c.file()));
        }
        WriteCommandAction.runWriteCommandAction(project, command, null, () -> {
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                for (PomChanges.Edit edit : now.files().get(i).edits()) {
                    doc.replaceString(edit.offset(), edit.offset() + edit.length(), edit.text());
                }
            }
        });
        for (Document d : docs) {
            FileDocumentManager.getInstance().saveDocument(d);
        }
        PomChanges.FileChange first = now.files().get(0);
        openLocation(project, first.file() + ":" + first.firstLine());
        List<String> done = new ArrayList<>();
        for (PomChanges.FileChange c : now.files()) {
            for (String d : c.descriptions()) {
                done.add(d + " (" + c.file().getFileName() + ")");
            }
        }
        for (String p : now.problems()) {
            done.add(p);
        }
        done.add(MavenDetectiveBundle.message("fix.reimport"));
        notify(project, String.join("\n", done), NotificationType.INFORMATION);
        IdeMaven.reimport(project);
        return now.editCount();
    }

    /** Plan aus dem heutigen Inhalt der Dokumente (auch ungespeicherte Änderungen). EDT. */
    static PomChanges.Plan plan(List<FixAction> fixes) {
        return PomChanges.plan(fixes, path -> {
            Document doc = document(path);
            return doc == null ? null : doc.getText();
        });
    }

    private static boolean sameTexts(PomChanges.Plan a, PomChanges.Plan b) {
        if (a.files().size() != b.files().size()) {
            return false;
        }
        for (int i = 0; i < a.files().size(); i++) {
            if (!a.files().get(i).before().equals(b.files().get(i).before()) || !a.files().get(i).after().equals(b.files().get(i).after())) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable Document document(Path file) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        return vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
    }

    /** Benachrichtigungen zeigen HTML: Text escapen ({@code <server>} bliebe sonst unsichtbar), Zeilen umbrechen. */
    static String plainToHtml(String text) {
        return Html.esc(text).replace("\n", "<br>");
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
        Locations.open(project, location);
    }

    /**
     * „Falsch erkannt“: legt den Log als Testfall ab. Ist die richtige Regel bekannt, steht sie vorn im
     * Dateinamen ({@code <regel-id>__…}) – nach core/src/test/resources/logs kopiert, wird die Datei so
     * ohne Umbenennen zum Regressionstest. Eine Notiz landet daneben als {@code .txt}.
     *
     * @param correctRule richtige Regel ({@code null}: unbekannt)
     * @param note        Beschreibung der echten Ursache ({@code null}/leer: keine)
     */
    static void reportMisdiagnosis(Project project, BuildDoctorService.Result result, @Nullable String correctRule,
                                   @Nullable String note) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".maven-detective", "testfaelle");
            Files.createDirectories(dir);
            Path file = dir.resolve(testCaseName(result, correctRule, LocalDateTime.now()));
            Files.write(file, result.rawLog().getBytes(StandardCharsets.UTF_8));
            if (note != null && !note.isBlank()) {
                String base = file.getFileName().toString();
                Files.write(dir.resolve(base.substring(0, base.length() - 4) + ".txt"), note.trim().concat("\n").getBytes(StandardCharsets.UTF_8));
            }
            notify(project, MavenDetectiveBundle.message("misdiagnosis.saved", file),
                    NotificationType.INFORMATION);
        } catch (IOException e) {
            notify(project, MavenDetectiveBundle.message("misdiagnosis.save.failed", e.getMessage()), NotificationType.ERROR);
        }
    }

    /** {@code <regel-id oder RICHTIGE-REGEL>__erkannt-als-<regel>-<zeit>.log} */
    static String testCaseName(BuildDoctorService.Result result, @Nullable String correctRule, LocalDateTime now) {
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String detected = result.diagnoses().isEmpty() ? "none" : result.diagnoses().get(0).getRuleId();
        String rule = correctRule == null || correctRule.isBlank() ? "RICHTIGE-REGEL" : correctRule.replaceAll("[^A-Za-z0-9._-]", "_");
        return rule + "__erkannt-als-" + detected + "-" + stamp + ".log";
    }

    /** {@code text} ist Klartext (Zeilen mit {@code \n}), nie HTML. */
    private static void notify(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(BuildDoctorService.TOOL_WINDOW_ID)
                .createNotification(plainToHtml(String.valueOf(text)), type)
                .notify(project);
    }
}
