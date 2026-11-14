package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hängt sich an jeden Maven-Lauf aus der IDE, sammelt die Konsolenausgabe und startet bei
 * Exit-Code ≠ 0 die Diagnose.
 *
 * <p>Grüne Läufe werden als Schnappschuss abgelegt, damit ein späterer roter Lauf zeigen kann, was
 * sich seitdem geändert hat. Vom Nutzer abgebrochene Läufe werden ignoriert.
 *
 * <p>Das Ende eines Laufs kommt auf zwei Wegen an: über den {@link ProcessListener} am Handler und
 * über {@link #processTerminated(String, ExecutionEnvironment, ProcessHandler, int)} des Topics.
 * Ausgewertet wird nur der erste – so funktioniert es auch, wenn ein Handler eines der beiden
 * Ereignisse nicht liefert.
 *
 * <p>Warum die IDE die Extension nicht selbst anhängt: Seit 2025.x startet IntelliJ Maven über das
 * {@code mvn}-Skript und setzt dabei {@code -Dmaven.ext.class.path} für den eigenen Event-Listener.
 * Ein zweiter Wert würde den ersten überschreiben und den Build-View der IDE leeren. Die IDE nimmt
 * deshalb den Text-Log plus ihr eigenes Maven-Modell; die Extension bleibt für Terminal und CI.
 */
public final class MavenRunListener implements ExecutionListener {

    private static final Logger LOG = Logger.getInstance(MavenRunListener.class);

    /** Obergrenze, damit ein endloser Log den Speicher nicht füllt (~8 Mio. Zeichen). */
    static final int MAX_CHARS = 8_000_000;

    /**
     * Wird die Obergrenze erreicht, bleiben Kopf (Maven-Version, Reactor, Einstellungen) und Ende
     * (BUILD FAILURE, {@code [ERROR]}-Block) erhalten; die Mitte fällt weg.
     */
    static final int HEAD_CHARS = 200_000;

    /** Platzhalter für die weggefallene Mitte; eine eigene Zeile, damit der Log-Parser sie als INFO liest. */
    static String cutMarker() {
        return "\n[INFO] … " + MavenDetectiveBundle.message("log.cut") + " …\n";
    }

    private final Project project;
    private final Map<ProcessHandler, Run> runs = new ConcurrentHashMap<>();

    /** Zustand eines laufenden Maven-Prozesses. */
    private static final class Run {
        final ExecutionEnvironment env;
        final StringBuilder log = new StringBuilder();
        volatile boolean destroyed;
        volatile boolean done;

        Run(ExecutionEnvironment env) {
            this.env = env;
        }

        String text() {
            synchronized (log) {
                return log.toString();
            }
        }
    }

    public MavenRunListener(Project project) {
        this.project = project;
    }

    @Override
    public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        Object profile = env.getRunProfile();
        if (!(profile instanceof MavenRunConfiguration)) {
            LOG.debug("Maven Detective: kein Maven-Lauf, ignoriert: " + profile.getClass().getName());
            return;
        }
        LOG.info("Maven Detective: Maven-Lauf gestartet (" + env.getRunProfile().getName() + ", Handler "
                + handler.getClass().getSimpleName() + ")");
        Run run = new Run(env);
        runs.put(handler, run);
        BuildDoctorService.getInstance(project).runStarted();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                synchronized (run.log) {
                    append(run.log, event.getText());
                }
            }

            @Override
            public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {
                run.destroyed |= willBeDestroyed;
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                finish(handler, event.getExitCode(), "ProcessListener");
            }
        });
    }

    /**
     * Hängt Text an und kürzt bei Überlauf die Mitte, nicht das Ende – dort stehen Mavens Fehler.
     * Danach ist wieder rund die Hälfte frei, das Kürzen kostet also amortisiert O(1) je Zeichen.
     */
    static void append(StringBuilder log, String text) {
        log.append(text);
        if (log.length() > MAX_CHARS) {
            log.replace(HEAD_CHARS, log.length() - MAX_CHARS / 2, cutMarker());
        }
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                                  @NotNull ProcessHandler handler, int exitCode) {
        finish(handler, exitCode, "ExecutionListener");
    }

    /**
     * Vom Nutzer gestoppt? {@code willBeDestroyed} allein reicht nicht: Seit 2025.x meldet IntelliJ
     * bei Maven-Läufen über das mvn-Skript auch ein normales Ende als „wird zerstört“. Maven selbst
     * endet aber immer mit 0 (grün) oder 1 (Fehler); ein Stopp liefert ein Signal (130, 137, 143)
     * oder unter Windows -1.
     */
    static boolean userAborted(boolean willBeDestroyed, int exitCode) {
        return willBeDestroyed && exitCode != 0 && exitCode != 1;
    }

    private void finish(ProcessHandler handler, int exitCode, String via) {
        Run run = runs.get(handler);
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (run.done) {
                return;
            }
            run.done = true;
        }
        runs.remove(handler);
        String text = run.text();
        LOG.info("Maven Detective: Maven-Lauf beendet, Exit-Code " + exitCode + ", abgebrochen " + run.destroyed
                + ", " + text.length() + " Zeichen Log (gemeldet über " + via + ")");
        if (project.isDisposed()) {
            return;
        }
        BuildDoctorService service = BuildDoctorService.getInstance(project);
        if (userAborted(run.destroyed, exitCode)) {
            service.runAborted();
            return;
        }
        if (exitCode == 0) {
            service.recordGreen(run.env);
        } else {
            service.analyzeLog(text, run.env, true);
        }
    }
}
