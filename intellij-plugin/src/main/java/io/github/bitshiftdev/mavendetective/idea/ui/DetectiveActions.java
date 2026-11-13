package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import io.github.bitshiftdev.mavendetective.idea.MavenDetectiveBundle;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * Aktionen, die jede Seite des Tool-Windows anbietet; registriert in {@code plugin.xml}, damit sie
 * über Find Action und Tastenkürzel erreichbar sind. Sie wirken auf die Seite im Data-Context
 * ({@link DetectivePanel#KEY}); Text und Icon kommen von der Seite.
 */
public final class DetectiveActions {

    private DetectiveActions() {
    }

    /** Basis: nur sichtbar, wenn eine Seite im Kontext ist; Zustand der Seite wird auf dem EDT gelesen. */
    private abstract static class PanelAction extends DumbAwareAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        static DetectivePanel panel(@NotNull AnActionEvent e) {
            return e.getData(DetectivePanel.KEY);
        }
    }

    /** Aktualisieren, Prüfen oder Simulieren – je nach Seite. */
    public static final class Refresh extends PanelAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            DetectivePanel p = panel(e);
            boolean shown = p != null && p.supportsRefresh();
            // Ohne Seite (Find Action woanders) ausgegraut sichtbar, in Seiten ohne Neuberechnung unsichtbar
            e.getPresentation().setVisible(p == null || shown);
            e.getPresentation().setEnabled(shown && p.canRefresh());
            if (shown) {
                e.getPresentation().setText(p.refreshText());
                e.getPresentation().setIcon(p.refreshIcon());
            }
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            DetectivePanel p = panel(e);
            if (p != null && p.supportsRefresh() && p.canRefresh()) {
                p.refresh();
            }
        }
    }

    /** Laufende Prüfung abbrechen. */
    public static final class Stop extends PanelAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            DetectivePanel p = panel(e);
            e.getPresentation().setEnabled(p != null && p.isRunning());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            DetectivePanel p = panel(e);
            if (p != null) {
                p.cancelRunning();
            }
        }
    }

    /** Ergebnis der Seite als Markdown in die Zwischenablage – für Ticket oder Chat. */
    public static final class CopyMarkdown extends PanelAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            MavenDetectiveBundle.localize(this, e.getPresentation());
            DetectivePanel p = panel(e);
            e.getPresentation().setEnabled(p != null && p.canCopyMarkdown());
            if (p != null && p.copyMarkdownText() != null) {
                e.getPresentation().setText(p.copyMarkdownText());
            }
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            DetectivePanel p = panel(e);
            String md = p == null || !p.canCopyMarkdown() ? null : p.markdown();
            if (md != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(md));
            }
        }
    }
}
