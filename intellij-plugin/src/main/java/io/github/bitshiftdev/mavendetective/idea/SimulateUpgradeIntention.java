package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Alt+Enter auf einer {@code <dependency>} oder dem {@code <parent>}: „Upgrade simulieren…“ öffnet die
 * Seite „Upgrade-Simulator“ mit diesem Artifact. Ändert nichts, daher ohne Write-Action und ohne Vorschau.
 */
public final class SimulateUpgradeIntention implements IntentionAction, LowPriorityAction, DumbAware {

    @Override
    public @NotNull String getText() {
        return MavenDetectiveBundle.message("action.MavenDetective.SimulateUpgrade.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return MavenDetectiveBundle.message("intention.family");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (editor == null || file == null || !WhyThisVersionAction.isProjectPom(file.getVirtualFile())) {
            return false;
        }
        String text = editor.getDocument().getText();
        int offset = editor.getCaretModel().getOffset();
        return WhyThisVersionAction.dependencyAt(text, offset) != null || SimulateUpgradeAction.parentAt(text, offset) != null;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (editor != null && file != null && WhyThisVersionAction.isProjectPom(file.getVirtualFile())) {
            SimulateUpgradeAction.openAtCaret(project, editor, file.getVirtualFile());
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return IntentionPreviewInfo.EMPTY;
    }
}
