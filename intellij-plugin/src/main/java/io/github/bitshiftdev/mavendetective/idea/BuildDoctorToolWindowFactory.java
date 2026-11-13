package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/**
 * Tool-Window „Maven Detective“ mit drei Tabs: „Übersicht“ (Einstieg), „Build Doctor“ (Modul B) und
 * „Dependencies“ (Modul A, Analysen links in einer Navigation). Sprünge von außen laufen über
 * {@link Navigator}.
 */
public final class BuildDoctorToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        add(toolWindow, new OverviewPanel(project), MavenDetectiveBundle.message("tab.overview"));
        add(toolWindow, new BuildDoctorPanel(project), MavenDetectiveBundle.message("tab.build.doctor"));
        add(toolWindow, new DependencyAnalysisView(project), MavenDetectiveBundle.message("tab.dependencies"));
    }

    private static <T extends JComponent & Disposable> void add(ToolWindow toolWindow, T component, String name) {
        Content content = ContentFactory.getInstance().createContent(component, name, false);
        content.setDisposer(component);
        toolWindow.getContentManager().addContent(content);
    }
}
