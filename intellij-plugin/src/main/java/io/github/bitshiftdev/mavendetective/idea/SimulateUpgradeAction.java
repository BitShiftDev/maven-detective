package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rechtsklick in einer {@code pom.xml} auf eine {@code <dependency>} (auch BOM-Import) oder den
 * {@code <parent>} – oder im Maven-Tool-Window bzw. unter <i>External Libraries</i> auf eine Dependency:
 * „Upgrade simulieren…“ öffnet die Seite „Upgrade-Simulator“ (Tab „Dependencies“) mit diesem Artifact.
 */
public final class SimulateUpgradeAction extends AnAction implements DumbAware {

    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean inPom = e.getData(CommonDataKeys.EDITOR) != null && WhyThisVersionAction.isProjectPom(e.getData(CommonDataKeys.VIRTUAL_FILE));
        e.getPresentation().setEnabledAndVisible(project != null
                && (inPom || IdeMaven.selectedDependency(project, e.getDataContext()) != null));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null) {
            return;
        }
        if (editor == null || !WhyThisVersionAction.isProjectPom(file)) {
            IdeMaven.SelectedDependency d = IdeMaven.selectedDependency(project, e.getDataContext());
            if (d != null) {
                Navigator.open(project, UpgradeSimulatorPanel.class, panel -> panel.show(d.groupId(), d.artifactId()));
            }
            return;
        }
        openAtCaret(project, editor, file);
    }

    /** Seite „Upgrade-Simulator“ für {@code <dependency>} oder {@code <parent>} am Cursor. EDT. */
    static void openAtCaret(@NotNull Project project, @NotNull Editor editor, @NotNull VirtualFile file) {
        String text = editor.getDocument().getText();
        int offset = editor.getCaretModel().getOffset();
        String[] ga = WhyThisVersionAction.dependencyAt(text, offset);
        if (ga == null) {
            ga = parentAt(text, offset);
        }
        if (ga == null) {
            HintManager.getInstance().showInformationHint(editor, MavenDetectiveBundle.message("hint.caret.dependency.or.parent"));
            return;
        }
        WhyThisVersionAction.resolveGroupId(project, file.toNioPath(), ga);
        String groupId = ga[0];
        String artifactId = ga[1];
        Navigator.open(project, UpgradeSimulatorPanel.class, panel -> panel.show(groupId, artifactId));
    }

    /** {@code groupId}/{@code artifactId} des {@code <parent>}, wenn der Cursor darin steht; sonst {@code null}. */
    static String @Nullable [] parentAt(@NotNull String text, int offset) {
        int start = text.indexOf("<parent>");
        int end = start < 0 ? -1 : text.indexOf("</parent>", start);
        if (start < 0 || end < 0 || offset < start || offset > end + "</parent>".length()) {
            return null;
        }
        String block = text.substring(start, end);
        Matcher g = GROUP.matcher(block);
        Matcher a = ARTIFACT.matcher(block);
        return g.find() && a.find() ? new String[] {g.group(1), a.group(1)} : null;
    }
}
