package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Alt+Enter auf einer {@code <dependency>} in der {@code pom.xml}: „Warum diese Version?“ als Popup am
 * Cursor (UI-Plan U4). Ändert nichts, daher ohne Write-Action und ohne Vorschau.
 */
public final class WhyThisVersionIntention implements IntentionAction, DumbAware {

    @Override
    public @NotNull String getText() {
        return MavenDetectiveBundle.message("action.MavenDetective.WhyThisVersion.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return MavenDetectiveBundle.message("intention.family");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return editor != null && file != null && WhyThisVersionAction.isProjectPom(file.getVirtualFile())
                && WhyThisVersionAction.dependencyAt(editor.getDocument().getText(), editor.getCaretModel().getOffset()) != null;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (editor != null && file != null && WhyThisVersionAction.isProjectPom(file.getVirtualFile())) {
            WhyThisVersionAction.showAtCaret(project, editor, file.getVirtualFile());
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
