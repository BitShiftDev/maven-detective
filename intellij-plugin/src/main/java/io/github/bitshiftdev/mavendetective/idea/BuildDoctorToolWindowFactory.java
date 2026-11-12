package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Tool-Window „Maven Detective“: Tab „Build Doctor“ (Modul B), Tabs „Dependencies“ und „Konflikt-Matrix“ (Modul A). */
public final class BuildDoctorToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        BuildDoctorPanel panel = new BuildDoctorPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Build Doctor", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        DependenciesPanel deps = new DependenciesPanel(project);
        Content depsContent = ContentFactory.getInstance().createContent(deps, DependenciesPanel.TAB_NAME, false);
        depsContent.setDisposer(deps);
        toolWindow.getContentManager().addContent(depsContent);

        ConflictMatrixPanel matrix = new ConflictMatrixPanel(project);
        Content matrixContent = ContentFactory.getInstance().createContent(matrix, ConflictMatrixPanel.TAB_NAME, false);
        matrixContent.setDisposer(matrix);
        toolWindow.getContentManager().addContent(matrixContent);
    }
}
