package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * Fix-Vorschau (UI-Plan U5): Bevor ein Quick-Fix eine POM ändert, zeigt dieser Dialog alle
 * betroffenen Dateien als Diff; „Anwenden“ schreibt sie in einem Undo-Schritt. Über das Häkchen
 * „Vorschau nicht mehr zeigen“ abschaltbar, wieder einschaltbar über die Aktion „Vorschau vor POM-Änderungen“.
 */
final class PomChangePreviewDialog extends DialogWrapper {

    private final PomChanges.Plan plan;
    private final PomDiffView diff;
    private final JBCheckBox dontShow = new JBCheckBox(MavenDetectiveBundle.message("preview.do.not.show"));

    private PomChangePreviewDialog(@NotNull Project project, @NotNull PomChanges.Plan plan, @NotNull String command) {
        super(project, true);
        this.plan = plan;
        this.diff = new PomDiffView(project, getDisposable(), null);
        setTitle(command);
        setOKButtonText(MavenDetectiveBundle.message("preview.apply"));
        init();
    }

    /** Vorschau zeigen; {@code true} = anwenden. EDT, modal. */
    static boolean confirm(@NotNull Project project, @NotNull PomChanges.Plan plan, @NotNull String command) {
        PomChangePreviewDialog d = new PomChangePreviewDialog(project, plan, command);
        boolean ok = d.showAndGet();
        if (ok && d.dontShow.isSelected()) {
            PomChanges.setPreviewEnabled(false);
        }
        return ok;
    }

    @Override
    protected @Nullable JPanel createSouthAdditionalPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(dontShow, BorderLayout.WEST);
        return p;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        int n = plan.editCount();
        head.add(new JBLabel(MavenDetectiveBundle.message("preview.headline", n, plan.files().size())));
        for (PomChanges.FileChange f : plan.files()) {
            for (String d : f.descriptions()) {
                JBLabel l = new JBLabel("<html>" + Html.esc(d) + " <span style='color:#"
                        + com.intellij.ui.ColorUtil.toHex(UIUtil.getContextHelpForeground()) + "'>(" + Html.esc(PomDiffView.label(f.file()))
                        + ")</span></html>");
                l.setIcon(AllIcons.Actions.Edit);
                l.setBorder(JBUI.Borders.emptyTop(2));
                head.add(l);
            }
        }
        for (String p : plan.problems()) {
            JBLabel l = new JBLabel(p);
            l.setIcon(AllIcons.General.Warning);
            l.setBorder(JBUI.Borders.emptyTop(2));
            head.add(l);
        }
        panel.add(head, BorderLayout.NORTH);
        diff.set(plan.files());
        JComponent d = diff.component();
        d.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(480)));
        panel.add(d, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "MavenDetective.PomChangePreview";
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[] {getOKAction(), getCancelAction()};
    }
}
