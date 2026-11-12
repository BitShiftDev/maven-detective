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
import com.intellij.openapi.wm.ToolWindowManager;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Hält die letzte Diagnose eines Projekts und benachrichtigt das Tool-Window.
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
    }

    private final Project project;
    private static final Logger LOG = Logger.getInstance(BuildDoctorService.class);

    private final BuildDoctor doctor = BuildDoctor.withDefaults();
    private final SnapshotStore store = SnapshotStore.defaultStore();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Result last;

    public BuildDoctorService(Project project) {
        this.project = project;
    }

    public static BuildDoctorService getInstance(@NotNull Project project) {
        return project.getService(BuildDoctorService.class);
    }

    public @Nullable Result getLast() {
        return last;
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
                ApplicationManager.getApplication().invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(TOOL_WINDOW_ID)
                        .createNotification("Maven Detective: Analyse fehlgeschlagen", String.valueOf(e.getMessage()), NotificationType.ERROR)
                        .notify(project), project.getDisposed());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> publish(result, notify), project.getDisposed());
        });
    }

    /** Ein Lauf aus der IDE war grün: Schnappschuss wird die neue Referenz für „Was hat sich geändert?“. */
    public void recordGreen(@Nullable ExecutionEnvironment env) {
        Path dir = projectDir();
        if (dir == null) {
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

    /** IDE gegen Kommandozeile, im Hintergrund ermittelt; {@code callback} läuft auf dem EDT. */
    public void compareSetupsAsync(@Nullable MavenRunConfiguration cfg, Consumer<SetupComparison> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SetupComparison c = compareSetups(cfg);
            if (c != null) {
                ApplicationManager.getApplication().invokeLater(() -> callback.accept(c), project.getDisposed());
            }
        });
    }

    private @Nullable SnapshotDiff changesSinceGreen(@Nullable MavenRunConfiguration cfg) {
        Path dir = projectDir();
        if (dir == null) {
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

    private @Nullable SetupComparison compareSetups(@Nullable MavenRunConfiguration cfg) {
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
        if (notify && !result.diagnoses().isEmpty()) {
            Diagnosis d = result.diagnoses().get(0);
            NotificationGroupManager.getInstance().getNotificationGroup(TOOL_WINDOW_ID)
                    .createNotification("Maven-Build gescheitert: " + d.getCategory(), d.getCause(), NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Diagnose anzeigen", this::showToolWindow))
                    .notify(project);
        }
    }

    public void showToolWindow() {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (tw != null) {
            tw.show();
        }
    }

    public @Nullable Path projectDir() {
        String base = project.getBasePath();
        return base == null ? null : Paths.get(base);
    }
}
