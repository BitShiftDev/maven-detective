package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation.Station;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * „Warum diese Version?“ als Popup am Cursor (UI-Plan U4): Ein-Satz-Antwort, darunter die Kette der
 * Stationen mit Sprung zu Datei und Zeile, Hinweise klein darunter. Pin-Knopf und Link „Im
 * Tool-Window öffnen“ führen zur Seite „Warum diese Version?“ mit Liste und Baum.
 */
final class WhyThisVersionPopup {

    private static final int WIDTH = 460;

    private WhyThisVersionPopup() {
    }

    /** Erklärung im Hintergrund rechnen (liest POMs), dann am Cursor zeigen. EDT. */
    static void show(@NotNull Project project, @NotNull Editor editor, @NotNull Path pom, @NotNull String groupId, @NotNull String artifactId) {
        DependenciesPanel.saveUnsavedPoms();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, MavenDetectiveBundle.message("popup.why.loading", groupId + ":" + artifactId), true) {
            private @Nullable VersionExplanation result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                DepNode tree = IdeMaven.dependencyTree(project, pom);
                indicator.checkCanceled();
                result = DependenciesPanel.explainNow(project, pom, groupId, artifactId, tree);
            }

            @Override
            public void onSuccess() {
                if (editor.isDisposed() || project.isDisposed()) {
                    return;
                }
                if (result == null) {
                    HintManager.getInstance().showErrorHint(editor, MavenDetectiveBundle.message("popup.why.unreadable"));
                    return;
                }
                popup(project, pom, result).showInBestPositionFor(editor);
            }
        });
    }

    static @NotNull JBPopup popup(@NotNull Project project, @NotNull Path pom, @NotNull VersionExplanation x) {
        JBPopup[] self = {null};
        Runnable openToolWindow = () -> {
            if (self[0] != null) {
                self[0].cancel();
            }
            Navigator.open(project, DependenciesPanel.class, p -> p.show(pom, x.getGroupId(), x.getArtifactId()));
        };
        Consumer<String> jump = location -> {
            if (self[0] != null) {
                self[0].cancel();
            }
            Locations.open(project, location);
        };
        JComponent content = content(project, x, jump, openToolWindow, () -> {
            if (self[0] != null) {
                self[0].cancel();
            }
            Navigator.open(project, UpgradeSimulatorPanel.class, p -> p.show(x.getGroupId(), x.getArtifactId()));
        });
        JScrollPane scroll = ScrollPaneFactory.createScrollPane(content, true);
        Dimension pref = content.getPreferredSize();
        scroll.setPreferredSize(new Dimension(pref.width + JBUI.scale(16), Math.min(pref.height + JBUI.scale(8), JBUI.scale(440))));
        self[0] = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
                .setTitle(MavenDetectiveBundle.message("popup.why.title", x.getGroupId() + ":" + x.getArtifactId()
                        + (x.getVersion() == null ? "" : " " + x.getVersion())))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setCouldPin(p -> {
                    openToolWindow.run();
                    return false;
                })
                .createPopup();
        return self[0];
    }

    private static JComponent content(Project project, VersionExplanation x, Consumer<String> jump, Runnable openToolWindow, Runnable simulate) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8, 10));

        JBLabel summary = wrapped(x.getSummary() == null ? "" : x.getSummary());
        summary.setFont(JBFont.label().asBold());
        panel.add(left(summary));
        panel.add(Box.createVerticalStrut(JBUI.scale(8)));

        for (Station s : x.getStations()) {
            if (s.getKind() != Station.Kind.HINT) {
                panel.add(left(station(s, jump)));
            }
        }
        if (!x.hints().isEmpty()) {
            panel.add(Box.createVerticalStrut(JBUI.scale(6)));
            for (Station h : x.hints()) {
                JBLabel l = wrapped(h.getText());
                l.setIcon(AllIcons.General.Information);
                l.setForeground(UIUtil.getContextHelpForeground());
                l.setFont(JBFont.small());
                panel.add(left(l));
            }
        }
        panel.add(Box.createVerticalStrut(JBUI.scale(10)));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footer.add(new ActionLink(MavenDetectiveBundle.message("popup.why.open"), (ActionListener) e -> openToolWindow.run()));
        footer.add(Box.createHorizontalStrut(JBUI.scale(16)));
        footer.add(new ActionLink(MavenDetectiveBundle.message("action.MavenDetective.SimulateUpgrade.text"), (ActionListener) e -> simulate.run()));
        panel.add(left(footer));
        return panel;
    }

    private static JComponent station(Station s, Consumer<String> jump) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setBorder(JBUI.Borders.empty(2, 18 * s.getLevel(), 2, 0));
        JBLabel text = wrapped(s.getText());
        text.setIcon(icon(s.getKind()));
        row.add(text, BorderLayout.CENTER);
        if (s.getFile() != null) {
            String location = Locations.of(s.getFile(), s.getLine());
            ActionLink link = new ActionLink(DiagnosisCard.fileLabel(s.getFile(), s.getLine()), (ActionListener) e -> jump.accept(location));
            link.setToolTipText(location);
            JPanel east = new JPanel(new BorderLayout());
            east.add(link, BorderLayout.NORTH);
            row.add(east, BorderLayout.EAST);
        }
        return row;
    }

    /** Icon je Art der Station – Text sagt dasselbe, das Icon hilft beim Überfliegen. */
    static @NotNull Icon icon(@NotNull Station.Kind kind) {
        return switch (kind) {
            case TREE_PATH -> AllIcons.Nodes.Related;
            case DIRECT, DECLARED_TRANSITIVE -> AllIcons.Nodes.PpLib;
            case INHERITED -> AllIcons.Nodes.UpLevel;
            case IMPORT -> AllIcons.Nodes.Include;
            case MANAGED -> AllIcons.Nodes.Artifact;
            case PROPERTY -> AllIcons.Nodes.Property;
            case CONFLICT -> AllIcons.General.Warning;
            case HINT -> AllIcons.General.Information;
        };
    }

    /** Umbrechender Text fester Breite (HTML-Label; die Breite skaliert das HTML-Kit selbst). */
    static JBLabel wrapped(@Nls String text) {
        JBLabel l = new JBLabel("<html><div style='width:" + WIDTH + "px'>" + Html.esc(text) + "</div></html>");
        l.getAccessibleContext().setAccessibleName(text);
        return l;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
