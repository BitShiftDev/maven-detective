package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.Responsive;
import io.github.bitshiftdev.mavendetective.idea.ui.Responsive.Mode;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ItemEvent;
import java.util.List;

/**
 * Tab „Dependencies“: links die Analysen von Modul A mit Zähler und Zustand („3“, „nicht geprüft“,
 * „veraltet“), rechts die gewählte Seite. Ersetzt fünf gleichrangige Tabs. Im schmalen Tool-Window
 * (seitlich angeheftet) steht statt der Navigation eine Auswahlliste über der Seite.
 */
final class DependencyAnalysisView extends JPanel implements Disposable {

    private static final String SELECTED_KEY = "MavenDetective.Dependencies.page";

    /** Ein Eintrag der Navigation. */
    record Page(@NotNull String id, @Nls @NotNull String name, @NotNull Icon icon, @Nullable Analysis analysis,
                @NotNull DetectivePanel panel) {
    }

    private final List<Page> pages;
    private final JBList<Page> nav;
    private final ComboBox<Page> combo;
    private final JBScrollPane navScroll;
    private final OnePixelSplitter splitter;
    private final JPanel comboRow;
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
                new Page("exclusions", MavenDetectiveBundle.message("nav.exclusions"), AllIcons.General.Filter, Analysis.EXCLUSIONS,
                        new ExclusionsPanel(project)),
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
        nav.setCellRenderer(renderer(results));
        // Schmal ersetzt die Auswahlliste die Navigation; beide zeigen dieselbe Auswahl
        combo = new ComboBox<>(pages.toArray(new Page[0]));
        combo.setRenderer(renderer(results));
        nav.addListSelectionListener(e -> {
            Page p = nav.getSelectedValue();
            if (!e.getValueIsAdjusting() && p != null) {
                cards.show(content, p.id());
                PropertiesComponent.getInstance(project).setValue(SELECTED_KEY, p.id());
                combo.setSelectedItem(p);
            }
        });
        combo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && e.getItem() instanceof Page p) {
                nav.setSelectedValue(p, true);
            }
        });
        results.addListener((analysis, summary) -> {
            nav.repaint();
            combo.repaint();
        }, this);

        navScroll = new JBScrollPane(nav) {
            @Override
            public Dimension getMinimumSize() {
                // Namen und Zähler lesbar halten, auch wenn der Anteil am Splitter klein wird
                return new Dimension(nav.getPreferredSize().width + JBUI.scale(4), 0);
            }
        };
        navScroll.setBorder(JBUI.Borders.empty());
        content.setMinimumSize(JBUI.size(200, 0));
        splitter = new OnePixelSplitter(false, "MavenDetective.Dependencies.nav", 0.16f);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(navScroll);
        comboRow = new JPanel(new BorderLayout());
        comboRow.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4)));
        comboRow.add(combo, BorderLayout.CENTER);
        layoutFor(Mode.WIDE);
        Responsive.install(this, this, this::layoutFor);

        String last = PropertiesComponent.getInstance(project).getValue(SELECTED_KEY, "why");
        int idx = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(last)) {
                idx = i;
            }
        }
        nav.setSelectedIndex(idx);
    }

    /** Breit: Navigation links im Splitter; schmal: Auswahlliste über der Seite. Die Seiten bleiben dieselben. */
    private void layoutFor(@NotNull Mode mode) {
        removeAll();
        if (mode == Mode.NARROW) {
            // Splitter gibt die Seite frei, sonst hängt er sie beim Zurückschalten nicht wieder ein
            splitter.setSecondComponent(null);
            add(comboRow, BorderLayout.NORTH);
            add(content, BorderLayout.CENTER);
        } else {
            splitter.setSecondComponent(content);
            add(splitter, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Icon, Name und Zähler/Zustand einer Seite; für Liste und Auswahlliste je eine eigene Instanz. */
    private static @NotNull ColoredListCellRenderer<Page> renderer(@NotNull AnalysisResults results) {
        return new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends Page> list, Page page, int index, boolean selected, boolean focus) {
                if (page == null) {
                    return;
                }
                setIcon(page.icon());
                append(page.name());
                Summary s = page.analysis() == null ? null : results.get(page.analysis());
                String badge = s == null ? null : badge(s);
                if (badge != null) {
                    append("  " + badge, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                setToolTipText(s == null ? null : s.text());
                if (index >= 0) {
                    setBorder(JBUI.Borders.empty(3, 6));   // nicht im geschlossenen Feld der Auswahlliste
                }
            }
        };
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
