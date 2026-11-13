package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import org.jetbrains.annotations.NotNull;

/** Tool-Window „Maven Detective“: Tab „Build Doctor“ (Modul B), Tabs „Dependencies“, „Konflikt-Matrix“, „Doppelte Klassen“, „Ungenutzt/undeklariert“ und „Upgrade-Simulator“ (Modul A). */
public final class BuildDoctorToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        add(toolWindow, new BuildDoctorPanel(project), BuildDoctorPanel.TAB_NAME);
        add(toolWindow, new DependenciesPanel(project), DependenciesPanel.TAB_NAME);
        add(toolWindow, new ConflictMatrixPanel(project), ConflictMatrixPanel.TAB_NAME);
        add(toolWindow, new DuplicateClassesPanel(project), DuplicateClassesPanel.TAB_NAME);
        add(toolWindow, new DependencyUsagePanel(project), DependencyUsagePanel.TAB_NAME);
        add(toolWindow, new UpgradeSimulatorPanel(project), UpgradeSimulatorPanel.TAB_NAME);
    }

    private static void add(ToolWindow toolWindow, DetectivePanel panel, String name) {
        Content content = ContentFactory.getInstance().createContent(panel, name, false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
