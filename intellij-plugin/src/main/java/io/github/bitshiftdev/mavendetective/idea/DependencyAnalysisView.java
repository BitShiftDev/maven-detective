package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.List;

/**
 * Tab „Dependencies“: links die Analysen von Modul A mit Zähler und Zustand („3“, „nicht geprüft“,
 * „veraltet“), rechts die gewählte Seite. Ersetzt fünf gleichrangige Tabs.
 */
final class DependencyAnalysisView extends JPanel implements Disposable {

    private static final String SELECTED_KEY = "MavenDetective.Dependencies.page";

    /** Ein Eintrag der Navigation. */
    record Page(@NotNull String id, @Nls @NotNull String name, @NotNull Icon icon, @Nullable Analysis analysis,
                @NotNull DetectivePanel panel) {
    }

    private final List<Page> pages;
    private final JBList<Page> nav;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);

    DependencyAnalysisView(@NotNull Project project) {
        super(new BorderLayout());
        pages = List.of(
                new Page("why", MavenDetectiveBundle.message("nav.why"), AllIcons.Actions.Help, null, new DependenciesPanel(project)),
                new Page("conflicts", MavenDetectiveBundle.message("nav.conflicts"), AllIcons.Actions.Diff, Analysis.CONFLICTS,
                        new ConflictMatrixPanel(project)),
                new Page("duplicates", MavenDetectiveBundle.message("nav.duplicates"), AllIcons.Actions.Copy, Analysis.DUPLICATES,
                        new DuplicateClassesPanel(project)),
                new Page("usage", MavenDetectiveBundle.message("nav.usage"), AllIcons.Actions.Find, Analysis.USAGE,
                        new DependencyUsagePanel(project)),
                new Page("upgrade", MavenDetectiveBundle.message("nav.upgrade"), AllIcons.Actions.Upload, Analysis.UPGRADE,
                        new UpgradeSimulatorPanel(project)));
        for (Page p : pages) {
            Disposer.register(this, p.panel());
            content.add(p.panel(), p.id());
        }

        AnalysisResults results = AnalysisResults.getInstance(project);
        nav = new JBList<>(pages);
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends Page> list, Page page, int index, boolean selected, boolean focus) {
                setIcon(page.icon());
                append(page.name());
                String badge = page.analysis() == null ? null : badge(results.get(page.analysis()));
                if (badge != null) {
                    append("  " + badge, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                Summary s = page.analysis() == null ? null : results.get(page.analysis());
                setToolTipText(s == null ? null : s.text());
                setBorder(JBUI.Borders.empty(3, 6));
            }
        });
        nav.addListSelectionListener(e -> {
            Page p = nav.getSelectedValue();
            if (!e.getValueIsAdjusting() && p != null) {
                cards.show(content, p.id());
                PropertiesComponent.getInstance(project).setValue(SELECTED_KEY, p.id());
            }
        });
        results.addListener((analysis, summary) -> nav.repaint(), this);

        JBScrollPane navScroll = new JBScrollPane(nav);
        navScroll.setBorder(JBUI.Borders.empty());
        OnePixelSplitter splitter = new OnePixelSplitter(false, "MavenDetective.Dependencies.nav", 0.16f);
        splitter.setFirstComponent(navScroll);
        splitter.setSecondComponent(content);
        add(splitter, BorderLayout.CENTER);

        String last = PropertiesComponent.getInstance(project).getValue(SELECTED_KEY, "why");
        int idx = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(last)) {
                idx = i;
            }
        }
        nav.setSelectedIndex(idx);
    }

    /** Zähler oder Zustand hinter dem Namen; {@code null} = nichts anzeigen. */
    static @Nls @Nullable String badge(@NotNull Summary s) {
        return switch (s.state()) {
            case NOT_CHECKED -> MavenDetectiveBundle.message("state.not.checked");
            case RUNNING -> MavenDetectiveBundle.message("state.running");
            case STALE -> MavenDetectiveBundle.message("state.stale");
            case DONE -> s.count() < 0 ? null : String.valueOf(s.count());
        };
    }

    <T extends DetectivePanel> @Nullable T page(@NotNull Class<T> type) {
        for (Page p : pages) {
            if (type.isInstance(p.panel())) {
                return type.cast(p.panel());
            }
        }
        return null;
    }

    /** Seite in der Navigation wählen. */
    <T extends DetectivePanel> @Nullable T select(@NotNull Class<T> type) {
        for (int i = 0; i < pages.size(); i++) {
            if (type.isInstance(pages.get(i).panel())) {
                nav.setSelectedIndex(i);
                return type.cast(pages.get(i).panel());
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        // Seiten hängen per Disposer.register an dieser Ansicht
    }
}
