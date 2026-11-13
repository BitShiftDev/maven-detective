package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Sprünge ins Tool-Window von überall (Editor-Aktionen, Benachrichtigung, Übersicht, Matrix): öffnet
 * es, wählt Tab und Seite und reicht die Seite weiter. Seiten werden über ihre Klasse gefunden, nicht
 * über Tab-Namen – die sind übersetzbar.
 */
public final class Navigator {

    private Navigator() {
    }

    /** Tool-Window öffnen und die Seite {@code type} zeigen (Tab oder Seite im Tab „Dependencies“). */
    public static <T extends DetectivePanel> void open(@NotNull Project project, @NotNull Class<T> type, @Nullable Consumer<T> then) {
        ToolWindow window = window(project);
        if (window == null) {
            return;
        }
        window.activate(() -> {
            T page = select(window.getContentManager(), type);
            if (page != null && then != null) {
                then.accept(page);
            }
        });
    }

    public static void buildDoctor(@NotNull Project project, @Nullable Consumer<BuildDoctorPanel> then) {
        open(project, BuildDoctorPanel.class, then);
    }

    /**
     * Die Seite {@code type}, ohne etwas zu öffnen oder umzuschalten; {@code null}, solange das
     * Tool-Window noch keinen Inhalt hat (es entsteht beim ersten Öffnen).
     */
    public static <T extends DetectivePanel> @Nullable T find(@NotNull Project project, @NotNull Class<T> type) {
        ToolWindow window = window(project);
        ContentManager cm = window == null ? null : window.getContentManagerIfCreated();
        if (cm == null) {
            return null;
        }
        for (Content c : cm.getContents()) {
            if (type.isInstance(c.getComponent())) {
                return type.cast(c.getComponent());
            }
            if (c.getComponent() instanceof DependencyAnalysisView view) {
                T page = view.page(type);
                if (page != null) {
                    return page;
                }
            }
        }
        return null;
    }

    private static <T extends DetectivePanel> @Nullable T select(ContentManager cm, Class<T> type) {
        for (Content c : cm.getContents()) {
            if (type.isInstance(c.getComponent())) {
                cm.setSelectedContent(c, true);
                return type.cast(c.getComponent());
            }
            if (c.getComponent() instanceof DependencyAnalysisView view) {
                T page = view.select(type);
                if (page != null) {
                    cm.setSelectedContent(c, true);
                    return page;
                }
            }
        }
        return null;
    }

    static @Nullable ToolWindow window(@NotNull Project project) {
        return project.isDisposed() ? null : ToolWindowManager.getInstance(project).getToolWindow(BuildDoctorService.TOOL_WINDOW_ID);
    }
}
