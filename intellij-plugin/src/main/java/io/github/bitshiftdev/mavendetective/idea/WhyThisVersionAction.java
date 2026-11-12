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
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rechtsklick in einer {@code pom.xml} auf eine {@code <dependency>}: „Warum diese Version?“ öffnet
 * den Tab „Dependencies“ mit der Entscheidungskette.
 */
public final class WhyThisVersionAction extends AnAction implements DumbAware {

    private static final Pattern EXCLUSIONS = Pattern.compile("<exclusions>.*?</exclusions>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean pom = e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null
                && file != null && "pom.xml".equals(file.getName());
        e.getPresentation().setEnabledAndVisible(pom);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null || file == null) {
            return;
        }
        String[] ga = dependencyAt(editor.getDocument().getText(), editor.getCaretModel().getOffset());
        if (ga == null) {
            HintManager.getInstance().showInformationHint(editor, "Cursor auf eine <dependency> setzen, dann „Warum diese Version?“");
            return;
        }
        Path pom = file.toNioPath();
        if (ga[0].contains("${")) {
            // ${project.groupId} u. Ä. bei Geschwister-Modulen: groupId des Moduls nehmen
            for (IdeMaven.ModuleRef m : IdeMaven.modules(project)) {
                if (m.pom().equals(pom)) {
                    ga[0] = m.groupId();
                }
            }
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Maven Detective");
        if (window == null) {
            return;
        }
        String groupId = ga[0];
        String artifactId = ga[1];
        window.activate(() -> {
            Content content = window.getContentManager().findContent(DependenciesPanel.TAB_NAME);
            if (content != null && content.getComponent() instanceof DependenciesPanel panel) {
                window.getContentManager().setSelectedContent(content);
                panel.show(pom, groupId, artifactId);
            }
        });
    }

    /**
     * {@code groupId}/{@code artifactId} der {@code <dependency>}, in der der Cursor steht (auch in
     * {@code dependencyManagement}); Exclusions werden übersprungen. {@code null}, wenn keine.
     */
    static String @Nullable [] dependencyAt(@NotNull String text, int offset) {
        // Cursor mitten im Tag "<dependency>" zählt mit; dahinter liegende Dependencies nicht
        int searchFrom = Math.min(text.length(), offset + "<dependency>".length());
        int start = text.lastIndexOf("<dependency>", searchFrom);
        if (start > offset) {
            start = text.lastIndexOf("<dependency>", offset);
        }
        if (start < 0) {
            return null;
        }
        int end = text.indexOf("</dependency>", start);
        if (end < 0 || offset > end + "</dependency>".length()) {
            return null;
        }
        String block = EXCLUSIONS.matcher(text.substring(start, end)).replaceAll("");
        Matcher g = GROUP.matcher(block);
        Matcher a = ARTIFACT.matcher(block);
        if (!g.find() || !a.find()) {
            return null;
        }
        return new String[] {g.group(1), a.group(1)};
    }
}
