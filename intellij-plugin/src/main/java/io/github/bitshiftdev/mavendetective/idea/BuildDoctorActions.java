package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.DataFlavor;

/**
 * Aktionen des Build Doctors (registriert in {@code plugin.xml}). Sie funktionieren auch außerhalb
 * des Tool-Windows, etwa über Find Action: dann öffnen sie es und wählen die Seite „Build Doctor“.
 */
public final class BuildDoctorActions {

    private BuildDoctorActions() {
    }

    private abstract static class ProjectAction extends DumbAwareAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            e.getPresentation().setEnabled(e.getProject() != null);
        }

        /** Seite im Kontext, sonst das Tool-Window öffnen und die Seite wählen. */
        static void withPanel(@NotNull AnActionEvent e, @NotNull java.util.function.Consumer<BuildDoctorPanel> action) {
            if (e.getData(DetectivePanel.KEY) instanceof BuildDoctorPanel panel) {
                action.accept(panel);
            } else if (e.getProject() != null) {
                Navigator.buildDoctor(e.getProject(), action);
            }
        }
    }

    /** Log aus CI oder Terminal: kopieren, dann diese Aktion. */
    public static final class AnalyzeClipboard extends ProjectAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) {
                return;
            }
            String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            withPanel(e, panel -> {
                if (text == null || text.isBlank()) {
                    panel.showMessage(MavenDetectiveBundle.message("doctor.clipboard.empty"));
                } else {
                    BuildDoctorService.getInstance(project).analyzeLog(text, null, false);
                }
            });
        }
    }

    /** Womit baut die IDE, womit {@code ./mvnw}? */
    public static final class CompareSetup extends ProjectAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withPanel(e, BuildDoctorPanel::showSetupView);
        }
    }

    /**
     * Log als Testfall ablegen, wenn die Diagnose daneben lag – mit kurzem Dialog (richtige Kategorie,
     * Notiz). Im Tool-Window gilt der gezeigte Lauf, sonst der letzte.
     */
    public static final class ReportMisdiagnosis extends ProjectAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            BuildDoctorService.Result r = target(e);
            e.getPresentation().setEnabled(r != null && (!r.diagnoses().isEmpty() || r.environment() != null));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            BuildDoctorService.Result r = target(e);
            if (r != null && e.getProject() != null) {
                new MisdiagnosisDialog(e.getProject(), r).show();
            }
        }

        private static BuildDoctorService.Result target(AnActionEvent e) {
            if (e.getData(DetectivePanel.KEY) instanceof BuildDoctorPanel panel) {
                return panel.shownResult();
            }
            Project project = e.getProject();
            return project == null ? null : BuildDoctorService.getInstance(project).getLast();
        }
    }

    /**
     * Preflight vor dem Build: settings.xml, JDK, Maven, Parents/BOMs, Repositories. Steht auch in
     * der Toolbar des Maven-Tool-Windows.
     */
    public static final class Preflight extends ProjectAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            withPanel(e, BuildDoctorPanel::showPreflight);
        }
    }

    /** Log des gezeigten Laufs im Log-Navigator, beim ersten Fehler. */
    public static final class ShowLog extends ProjectAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            if (e.getData(DetectivePanel.KEY) instanceof BuildDoctorPanel panel) {
                e.getPresentation().setEnabled(panel.hasLog());
            } else {
                Project project = e.getProject();
                BuildDoctorService.Result last = project == null ? null : BuildDoctorService.getInstance(project).getLast();
                e.getPresentation().setEnabled(last != null && last.rawLog() != null && !last.rawLog().isBlank());
            }
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getData(DetectivePanel.KEY) instanceof BuildDoctorPanel panel) {
                panel.showLog(true);
            } else if (e.getProject() != null) {
                Navigator.buildDoctor(e.getProject(), panel -> {
                    panel.showLatest();
                    panel.showLog(true);
                });
            }
        }
    }
}
