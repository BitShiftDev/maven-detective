package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * Schalter „Vorschau vor POM-Änderungen“ (an per Default). Über Find Action und im Filter-Menü der
 * Dependency-Seiten erreichbar; die Einstellungsseite folgt in U6.
 */
public final class PreviewPomFixesAction extends DumbAwareToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return PomChanges.isPreviewEnabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        PomChanges.setPreviewEnabled(state);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
