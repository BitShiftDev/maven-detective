package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Tool-Window „Maven Detective“. Phase 1: Tab „Build Doctor“; Tab „Dependencies“ (Modul A) folgt. */
public final class BuildDoctorToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        BuildDoctorPanel panel = new BuildDoctorPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "Build Doctor", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
