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

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * „Warum diese Version?“: in einer {@code pom.xml} auf einer {@code <dependency>} (Rechtsklick, Alt+Enter
 * über {@link WhyThisVersionIntention}) als Popup am Cursor; im Maven-Tool-Window und unter <i>External
 * Libraries</i> auf einer Dependency als Seite „Warum diese Version?“ im Tool-Window (dort gibt es keinen Cursor).
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
        MavenDetectiveBundle.localize(this, e.getPresentation());
        Project project = e.getProject();
        boolean inPom = e.getData(CommonDataKeys.EDITOR) != null && isProjectPom(e.getData(CommonDataKeys.VIRTUAL_FILE));
        e.getPresentation().setEnabledAndVisible(project != null
                && (inPom || IdeMaven.selectedDependency(project, e.getDataContext()) != null));
    }

    /**
     * Nur POMs auf der Platte: Die {@code pom.xml} unter {@code META-INF/maven/…} in einem Library-JAR
     * heißt genauso, liegt aber im JarFileSystem, und dort wirft {@code toNioPath()}.
     */
    static boolean isProjectPom(@Nullable VirtualFile file) {
        return file != null && file.isInLocalFileSystem() && "pom.xml".equals(file.getName());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null) {
            return;
        }
        if (editor == null || !isProjectPom(file)) {
            IdeMaven.SelectedDependency d = IdeMaven.selectedDependency(project, e.getDataContext());
            if (d != null) {
                Navigator.open(project, DependenciesPanel.class, panel -> panel.show(d.pom(), d.groupId(), d.artifactId()));
            }
            return;
        }
        showAtCaret(project, editor, file);
    }

    /** Popup für die {@code <dependency>} am Cursor; steht er auf keiner, ein kurzer Hinweis. EDT. */
    static void showAtCaret(@NotNull Project project, @NotNull Editor editor, @NotNull VirtualFile file) {
        String[] ga = dependencyAt(editor.getDocument().getText(), editor.getCaretModel().getOffset());
        if (ga == null) {
            HintManager.getInstance().showInformationHint(editor, MavenDetectiveBundle.message("hint.caret.dependency"));
            return;
        }
        Path pom = file.toNioPath();
        resolveGroupId(project, pom, ga);
        WhyThisVersionPopup.show(project, editor, pom, ga[0], ga[1]);
    }

    /** {@code ${project.groupId}} u. Ä. bei Geschwister-Modulen: groupId des Moduls nehmen. */
    static void resolveGroupId(@NotNull Project project, @NotNull Path pom, String @NotNull [] ga) {
        if (ga[0].contains("${")) {
            for (IdeMaven.ModuleRef m : IdeMaven.modules(project)) {
                if (m.pom().equals(pom)) {
                    ga[0] = m.groupId();
                }
            }
        }
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
