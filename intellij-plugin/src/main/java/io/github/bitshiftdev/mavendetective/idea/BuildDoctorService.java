package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.EditorNotifications;
import com.intellij.ui.BadgeIconSupplier;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveIcons;
import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotStore;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hält die letzte Diagnose eines Projekts, den Verlauf der letzten Läufe und die Prüfung nach einem
 * Quick-Fix und benachrichtigt das Tool-Window.
 * Die Analyse läuft im Hintergrund, die Anzeige auf dem EDT.
 */
@Service(Service.Level.PROJECT)
public final class BuildDoctorService {

    public static final String TOOL_WINDOW_ID = "Maven Detective";

    /**
     * Ergebnis einer Analyse, wie es das Tool-Window anzeigt.
     *
     * @param changes Vergleich mit dem letzten grünen Build ({@code null}: keiner gespeichert oder Log ohne Lauf)
     * @param setup   IDE gegen Kommandozeile ({@code null}: nicht ermittelbar)
     */
    public record Result(BuildFailure failure, List<Diagnosis> diagnoses, String rawLog,
                         @Nullable ExecutionEnvironment environment,
                         @Nullable SnapshotDiff changes, @Nullable SetupComparison setup) {

        public @Nullable MavenRunConfiguration configuration() {
            return environment != null && environment.getRunProfile() instanceof MavenRunConfiguration c ? c : null;
        }
    }

    public interface Listener {
        void resultChanged(Result result);

        /** Ein Maven-Lauf aus der IDE ist zu Ende (grün oder rot); siehe {@link #getLastRun()}. */
        default void runFinished(@NotNull Run run) {
        }

        /** Neuer Eintrag im Verlauf (Lauf aus der IDE oder Log aus der Zwischenablage), steht vorn. */
        default void historyChanged(@NotNull Run added) {
        }

        /** Ein Maven-Lauf aus der IDE hat begonnen oder ist samt Analyse fertig; siehe {@link #isRunning()}. */
        default void runningChanged() {
        }
    }

    /**
     * Ein Eintrag im Verlauf: Maven-Lauf aus der IDE (grün oder rot) oder ein Log aus der Zwischenablage.
     *
     * @param at     Ende des Laufs bzw. Zeitpunkt der Analyse, Millisekunden seit 1970
     * @param result Diagnose bei rotem Lauf oder Log aus der Zwischenablage
     * @param name   Name der Run-Konfiguration ({@code null}: Zwischenablage)
     * @param check  Lauf nach einem Quick-Fix: welcher Fix, welche Ursache vorher
     */
    public record Run(boolean green, long at, @Nullable Result result, @Nullable String name, @Nullable FixCheck check) {

        public boolean fromClipboard() {
            return result != null && result.environment() == null;
        }

        /** Regel der ersten Diagnose ({@code null}: grün oder keine erkannt). */
        public @Nullable String ruleId() {
            return result == null || result.diagnoses().isEmpty() ? null : result.diagnoses().get(0).getRuleId();
        }
    }

    /**
     * Fix-und-Prüf-Schleife: Nach einem Quick-Fix startet derselbe Lauf neu; der nächste Lauf trägt,
     * welcher Fix davor lief und welche Regel ihn ausgelöst hatte.
     */
    public record FixCheck(@NotNull String fix, @Nullable String previousRule) {
    }

    /** So viele Einträge hält der Verlauf (nur im Speicher). */
    static final int HISTORY_SIZE = 20;

    private static final BadgeIconSupplier BADGE = new BadgeIconSupplier(DetectiveIcons.TOOL_WINDOW);

    private final Project project;
    private static final Logger LOG = Logger.getInstance(BuildDoctorService.class);

    private final BuildDoctor doctor = BuildDoctor.withDefaults();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Result last;
    private volatile Run lastRun;
    /** Neueste zuerst; nur EDT. */
    private final Deque<Run> history = new ArrayDeque<>();
    /** Laufende Maven-Läufe samt anschließender Analyse; nur EDT. */
    private int running;
    /** Fix, nach dem der nächste Lauf startet; nur EDT. */
    private @Nullable FixCheck pendingCheck;
    /** {@link Run#at()} des Laufs, dessen Editor-Banner der Nutzer weggeklickt hat (gelesen im Hintergrund). */
    private volatile long bannerDismissed = -1;

    public BuildDoctorService(Project project) {
        this.project = project;
    }

    public static BuildDoctorService getInstance(@NotNull Project project) {
        return project.getService(BuildDoctorService.class);
    }

    public @Nullable Result getLast() {
        return last;
    }

    public @Nullable Run getLastRun() {
        return lastRun;
    }

    /** Verlauf, neueste zuerst (EDT). */
    public @NotNull List<Run> getHistory() {
        return new ArrayList<>(history);
    }

    /** Läuft gerade ein Maven-Lauf aus der IDE oder seine Analyse? (EDT) */
    public boolean isRunning() {
        return running > 0;
    }

    /** Fix, nach dem gerade neu gebaut wird ({@code null}: keiner). (EDT) */
    public @Nullable FixCheck getPendingCheck() {
        return pendingCheck;
    }

    /** {@link MavenRunListener}: ein Maven-Lauf beginnt. */
    void runStarted() {
        ApplicationManager.getApplication().invokeLater(() -> {
            running++;
            listeners.forEach(Listener::runningChanged);
        }, project.getDisposed());
    }

    /** {@link MavenRunListener}: vom Nutzer gestoppt – zählt nicht als Prüfung eines Fixes. */
    void runAborted() {
        ApplicationManager.getApplication().invokeLater(() -> {
            pendingCheck = null;
            runEnded();
        }, project.getDisposed());
    }

    private void runEnded() {
        running = Math.max(0, running - 1);
        listeners.forEach(Listener::runningChanged);
    }

    /**
     * {@link FixRunner}: Nach dem Fix startet der Lauf neu; dessen Ergebnis wird als Prüfung des Fixes
     * gezeigt („Behoben“ oder die neue Diagnose). EDT.
     */
    void expectCheck(@NotNull String fix, @Nullable Result before) {
        String rule = before == null || before.diagnoses().isEmpty() ? null : before.diagnoses().get(0).getRuleId();
        pendingCheck = new FixCheck(fix, rule);
        listeners.forEach(Listener::runningChanged);
    }

    /** Meldet sich ab, sobald {@code parent} entsorgt wird – kein Panel bleibt am Service hängen. */
    public void addListener(@NotNull Listener l, @NotNull Disposable parent) {
        listeners.add(l);
        Disposer.register(parent, () -> listeners.remove(l));
    }

    /**
     * Analysiert einen Text-Log. Stammt er aus einem Lauf der IDE ({@code env} gesetzt), wird der Lauf
     * als „rot“ abgelegt und mit dem letzten grünen verglichen. {@code notify}: Sprechblase zeigen.
     */
    public void analyzeLog(String log, @Nullable ExecutionEnvironment env, boolean notify) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Result result;
            try {
                BuildFailure failure = MavenLogParser.parse(log);
                List<Diagnosis> diagnoses = doctor.diagnose(failure, Environment.forBuild(failure, projectDir()));
                LOG.info("Maven Detective: Analyse " + (env == null ? "Zwischenablage" : "Lauf") + ", " + failure.getErrors().size()
                        + " Fehlerblöcke, Diagnose: " + (diagnoses.isEmpty() ? "keine" : diagnoses.get(0).getRuleId()));
                MavenRunConfiguration cfg = env != null && env.getRunProfile() instanceof MavenRunConfiguration c ? c : null;
                SnapshotDiff changes = env == null ? null : changesSinceGreen(cfg);
                result = new Result(failure, diagnoses, log, env, changes, compareSetups(cfg));
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                // Ohne diese Meldung täte der Knopf „Log aus Zwischenablage analysieren“ scheinbar nichts
                LOG.warn("Maven Detective: Analyse fehlgeschlagen", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (env != null) {
                        pendingCheck = null;
                        runEnded();
                    }
                    NotificationGroupManager.getInstance().getNotificationGroup(TOOL_WINDOW_ID)
                            .createNotification(MavenDetectiveBundle.message("notification.analysis.failed"), String.valueOf(e.getMessage()), NotificationType.ERROR)
                            .notify(project);
                }, project.getDisposed());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> publish(result, notify), project.getDisposed());
        });
    }

    /** Ein Lauf aus der IDE war grün: Schnappschuss wird die neue Referenz für „Was hat sich geändert?“. */
    public void recordGreen(@Nullable ExecutionEnvironment env) {
        long at = System.currentTimeMillis();
        String name = env == null ? null : env.getRunProfile().getName();
        ApplicationManager.getApplication().invokeLater(() -> finished(new Run(true, at, null, name, takeCheck())), project.getDisposed());
        Path dir = projectDir();
        SnapshotStore store = DetectiveSettings.getInstance().snapshotStore();
        if (dir == null || store == null) {
            return;
        }
        MavenRunConfiguration cfg = env != null && env.getRunProfile() instanceof MavenRunConfiguration c ? c : null;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                BuildSnapshot s = IdeMaven.snapshot(project, cfg).setSuccessful(true);
                Path file = store.saveGreen(dir, s);
                store.saveLatest(dir, s);
                LOG.info("Maven Detective: grüner Schnappschuss gespeichert: " + file + " (" + s.getModules().size() + " Module)");
            } catch (IOException | RuntimeException e) {
                LOG.warn("Maven Detective: grüner Schnappschuss nicht gespeichert", e);
            }
        });
    }

    /**
     * Grüne Referenz für „Was hat sich geändert?“ ({@code null}: keine oder Schnappschüsse abgeschaltet);
     * liest Dateien, nicht auf dem EDT aufrufen.
     */
    public @Nullable BuildSnapshot loadGreen() {
        Path dir = projectDir();
        SnapshotStore store = DetectiveSettings.getInstance().snapshotStore();
        try {
            return dir == null || store == null ? null : store.loadGreen(dir, BuildSnapshot.ORIGIN_IDE);
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: grüner Schnappschuss nicht lesbar", e);
            return null;
        }
    }

    private @Nullable SnapshotDiff changesSinceGreen(@Nullable MavenRunConfiguration cfg) {
        Path dir = projectDir();
        SnapshotStore store = DetectiveSettings.getInstance().snapshotStore();
        if (dir == null || store == null) {
            return null;
        }
        try {
            BuildSnapshot current = IdeMaven.snapshot(project, cfg).setSuccessful(false);
            store.saveLatest(dir, current);
            BuildSnapshot green = store.loadGreen(dir, BuildSnapshot.ORIGIN_IDE);
            LOG.info("Maven Detective: roter Lauf abgelegt, grüne Referenz " + (green == null ? "fehlt" : "vom " + green.getCreatedAt()));
            return green == null ? null : SnapshotDiff.compare(green, current);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Maven Detective: Vergleich mit dem letzten grünen Build fehlgeschlagen", e);
            return null;
        }
    }

    /** IDE gegen Kommandozeile ({@code null}: nicht ermittelbar); liest Dateien, nicht auf dem EDT aufrufen. */
    public @Nullable SetupComparison compareSetups(@Nullable MavenRunConfiguration cfg) {
        try {
            return SetupComparison.compare(IdeMaven.ideSetup(project, cfg), IdeMaven.cliSetup(project));
        } catch (RuntimeException e) {
            LOG.warn("Maven Detective: Umgebungsvergleich fehlgeschlagen", e);
            return null;
        }
    }

    private void publish(Result result, boolean notify) {
        last = result;
        for (Listener l : listeners) {
            l.resultChanged(result);
        }
        if (result.environment() != null) {
            finished(new Run(false, System.currentTimeMillis(), result, result.environment().getRunProfile().getName(), takeCheck()));
        } else if (!result.diagnoses().isEmpty()) {
            remember(new Run(false, System.currentTimeMillis(), result, null, null));
        }
        // Sprechblase nur, wenn das Tool-Window nicht ohnehin zu sehen ist – sonst genügt das Badge;
        // abschaltbar (Einstellungen), das Badge bleibt
        ToolWindow window = Navigator.window(project);
        if (notify && DetectiveSettings.getInstance().isNotifyRedBuild() && !result.diagnoses().isEmpty()
                && (window == null || !window.isVisible())) {
            Diagnosis d = result.diagnoses().get(0);
            NotificationGroupManager.getInstance().getNotificationGroup(TOOL_WINDOW_ID)
                    .createNotification(MavenDetectiveBundle.message("notification.build.failed", d.getCategory()), d.getCause(),
                            NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple(MavenDetectiveBundle.message("notification.show.diagnosis"),
                            () -> Navigator.buildDoctor(project, BuildDoctorPanel::showLatest)))
                    .notify(project);
        }
    }

    /**
     * Lauf aus der IDE ist samt Analyse fertig: merken, Badge an der Tool-Window-Leiste setzen (rot)
     * oder entfernen (grün), melden (EDT).
     */
    private void finished(Run run) {
        lastRun = run;
        ToolWindow window = Navigator.window(project);
        if (window != null) {
            window.setIcon(run.green() ? DetectiveIcons.TOOL_WINDOW : BADGE.getErrorIcon(true));
        }
        remember(run);
        for (Listener l : listeners) {
            l.runFinished(run);
        }
        runEnded();
    }

    private void remember(Run run) {
        history.addFirst(run);
        while (history.size() > HISTORY_SIZE) {
            history.removeLast();
        }
        for (Listener l : listeners) {
            l.historyChanged(run);
        }
        // Banner „Der letzte Build scheiterte an dieser Datei“ in offenen Editoren nachziehen
        EditorNotifications.getInstance(project).updateAllNotifications();
    }

    /** Banner zum Lauf mit diesem Zeitpunkt ausgeblendet. */
    long getBannerDismissed() {
        return bannerDismissed;
    }

    void dismissBanner(long runAt) {
        bannerDismissed = runAt;
        EditorNotifications.getInstance(project).updateAllNotifications();
    }

    /** Wartende Prüfung eines Fixes dem gerade beendeten Lauf zuordnen (EDT). */
    private @Nullable FixCheck takeCheck() {
        FixCheck c = pendingCheck;
        pendingCheck = null;
        return c;
    }

    public @Nullable Path projectDir() {
        String base = project.getBasePath();
        return base == null ? null : Paths.get(base);
    }
}
