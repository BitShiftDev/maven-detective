package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Tool-Window „Maven Detective“: Tab „Build Doctor“ (Modul B), Tabs „Dependencies“, „Konflikt-Matrix“, „Doppelte Klassen“, „Ungenutzt/undeklariert“ und „Upgrade-Simulator“ (Modul A). */
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

        DuplicateClassesPanel duplicates = new DuplicateClassesPanel(project);
        Content duplicatesContent = ContentFactory.getInstance().createContent(duplicates, DuplicateClassesPanel.TAB_NAME, false);
        duplicatesContent.setDisposer(duplicates);
        toolWindow.getContentManager().addContent(duplicatesContent);

        DependencyUsagePanel usage = new DependencyUsagePanel(project);
        Content usageContent = ContentFactory.getInstance().createContent(usage, DependencyUsagePanel.TAB_NAME, false);
        usageContent.setDisposer(usage);
        toolWindow.getContentManager().addContent(usageContent);

        UpgradeSimulatorPanel upgrade = new UpgradeSimulatorPanel(project);
        Content upgradeContent = ContentFactory.getInstance().createContent(upgrade, UpgradeSimulatorPanel.TAB_NAME, false);
        upgradeContent.setDisposer(upgrade);
        toolWindow.getContentManager().addContent(upgradeContent);
    }
}
