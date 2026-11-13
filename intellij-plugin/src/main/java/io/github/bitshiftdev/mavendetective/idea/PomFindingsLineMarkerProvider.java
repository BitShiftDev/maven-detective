package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gutter-Icon an einer {@code <dependency>} der {@code pom.xml} (UI-Plan U4), wenn über sie
 * <ul>
 *   <li>eine <b>höhere Version verdrängt</b> wird (sie verlangt X 2.5, im Classpath landet 2.0 –
 *       Gefahr von {@code NoSuchMethodError}), aus dem Baum des letzten Maven-Imports, oder</li>
 *   <li><b>doppelte Klassen</b> kommen (aus der letzten Prüfung der Seite „Doppelte Klassen“; die
 *       Prüfung öffnet alle JARs und läuft deshalb nie vom Editor aus).</li>
 * </ul>
 * Klick zeigt Kurzbefund und Fix. Die Befunde sind je Datei gecacht ({@link PomInsights}).
 */
public final class PomFindingsLineMarkerProvider implements LineMarkerProvider, DumbAware {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        XmlTag dep = PomTags.dependencyOfNameToken(element);
        if (dep == null || !PomTags.isProjectDependency(dep)) {
            return null;
        }
        PsiFile file = element.getContainingFile();
        if (PomTags.projectTag(file) == null) {
            return null;
        }
        PomInsights.Facts facts = PomInsights.getInstance(file.getProject()).facts((XmlFile) file);
        String ga = PomTags.ga(dep, facts.groupId());
        if (ga == null) {
            return null;
        }
        List<UpgradeSimulator.Risk> risks = facts.risks().getOrDefault(ga, List.of());
        List<DuplicateClasses.Clash> clashes = facts.clashes().getOrDefault(ga, List.of());
        if (risks.isEmpty() && clashes.isEmpty()) {
            return null;
        }
        String tooltip = tooltip(risks, clashes);
        Path pom = facts.pom();
        return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.General.Warning, e -> tooltip,
                (MouseEvent event, PsiElement el) -> popup(el.getProject(), pom, ga, risks, clashes).show(new RelativePoint(event)),
                GutterIconRenderer.Alignment.LEFT, () -> tooltip);
    }

    /** Kurz, für Tooltip und Screenreader. */
    static @Nls @NotNull String tooltip(List<UpgradeSimulator.Risk> risks, List<DuplicateClasses.Clash> clashes) {
        List<String> parts = new ArrayList<>();
        if (!risks.isEmpty()) {
            UpgradeSimulator.Risk r = risks.get(0);
            parts.add(MavenDetectiveBundle.message("gutter.risk", r.ga(), r.getWanted(), r.getChosen())
                    + (risks.size() > 1 ? " " + MavenDetectiveBundle.message("gutter.more", risks.size() - 1) : ""));
        }
        if (!clashes.isEmpty()) {
            int classes = 0;
            for (DuplicateClasses.Clash c : clashes) {
                classes += c.getClasses().size();
            }
            parts.add(MavenDetectiveBundle.message("gutter.clash", classes, clashes.size()));
        }
        return String.join(" · ", parts);
    }

    private static JBPopup popup(Project project, Path pom, String ga, List<UpgradeSimulator.Risk> risks, List<DuplicateClasses.Clash> clashes) {
        JBPopup[] self = {null};
        Runnable close = () -> {
            if (self[0] != null) {
                self[0].cancel();
            }
        };
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8, 10));
        for (UpgradeSimulator.Risk r : risks) {
            panel.add(left(heading(MavenDetectiveBundle.message("gutter.risk.title", r.ga()))));
            panel.add(left(WhyThisVersionPopup.wrapped(MavenDetectiveBundle.message("gutter.risk.text", r.getRequester(), r.getWanted(), r.getChosen()))));
            JButton pin = FixBar.primary(new JButton(MavenDetectiveBundle.message("gutter.risk.pin", r.getWanted())));
            pin.addActionListener(e -> {
                close.run();
                FixRunner.pinVersion(project, FixAction.of(FixAction.PIN_VERSION, pin.getText(),
                        "file", pom.toString(), "artifact", r.ga(), "version", r.getWanted()));
            });
            ActionLink why = new ActionLink(MavenDetectiveBundle.message("action.MavenDetective.WhyThisVersion.text"), (ActionListener) e -> {
                close.run();
                int colon = r.ga().indexOf(':');
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor != null) {
                    WhyThisVersionPopup.show(project, editor, pom, r.ga().substring(0, colon), r.ga().substring(colon + 1));
                }
            });
            panel.add(left(row(pin, why)));
            panel.add(Box.createVerticalStrut(JBUI.scale(10)));
        }
        for (DuplicateClasses.Clash c : clashes) {
            panel.add(left(heading(MavenDetectiveBundle.message("gutter.clash.title", c.getModule().getName()))));
            panel.add(left(WhyThisVersionPopup.wrapped(c.message())));
            if (c.recommendation() != null) {
                panel.add(left(WhyThisVersionPopup.wrapped(c.recommendation())));
            }
            List<FixAction> exclusions = new ArrayList<>();
            for (FixAction f : c.fixes()) {
                if (FixAction.ADD_EXCLUSION.equals(f.getType())) {
                    exclusions.add(f);
                }
            }
            ActionLink show = new ActionLink(MavenDetectiveBundle.message("gutter.clash.show"), (ActionListener) e -> {
                close.run();
                Navigator.open(project, DuplicateClassesPanel.class, p -> p.select(c));
            });
            if (exclusions.isEmpty()) {
                panel.add(left(row(show)));
            } else {
                JButton fix = FixBar.primary(new JButton(exclusions.size() == 1 ? exclusions.get(0).getLabel()
                        : MavenDetectiveBundle.message("gutter.clash.fix.many", exclusions.size())));
                fix.addActionListener(e -> {
                    close.run();
                    FixRunner.addExclusions(project, exclusions);
                });
                panel.add(left(row(fix, show)));
            }
            panel.add(Box.createVerticalStrut(JBUI.scale(10)));
        }
        self[0] = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
                .setTitle(MavenDetectiveBundle.message("gutter.popup.title", ga))
                .setMovable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .createPopup();
        return self[0];
    }

    private static JBLabel heading(@Nls String text) {
        JBLabel l = new JBLabel(text);
        l.setFont(JBFont.label().asBold());
        l.setBorder(JBUI.Borders.emptyBottom(4));
        return l;
    }

    private static JComponent row(JComponent... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setBorder(JBUI.Borders.emptyTop(6));
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(JBUI.scale(12)));
            }
            p.add(items[i]);
        }
        return p;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
