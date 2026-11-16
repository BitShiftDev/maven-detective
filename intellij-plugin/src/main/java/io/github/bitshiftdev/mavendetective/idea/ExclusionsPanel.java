package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FindingTree;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Seite „Exclusions“ im Tab „Dependencies“ (Modul A4, UI-Plan U5): alle veralteten und doppelten
 * Exclusions des Projekts als Baum <i>POM → Exclusion</i>, angekreuzte lassen sich in einem
 * Undo-Schritt entfernen (mit Vorschau). Auf Wunsch auch die wirksamen und nicht prüfbaren. Liest
 * nur POMs – günstig genug, um nach jedem Maven-Import von selbst neu zu rechnen. Live im Editor
 * markiert die Inspection <i>Veraltete Exclusion</i> dieselben Stellen.
 */
public final class ExclusionsPanel extends DetectivePanel {

    private boolean withAll;
    private final SearchTextField search;
    private final FindingTree<ExclusionAudit.Finding> tree;
    private final HtmlView details;
    private final JButton remove = FixBar.primary(new JButton(MavenDetectiveBundle.message("exclusions.remove")));
    private final Background checker = background(MavenDetectiveBundle.message("loading.exclusions"));
    private @Nullable ExclusionAudit result;

    public ExclusionsPanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.findings"), this::applyFilter);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(filterGroup(toggle(MavenDetectiveBundle.message("exclusions.filter.all"),
                MavenDetectiveBundle.message("exclusions.filter.all.description"), () -> withAll, v -> {
                    withAll = v;
                    applyFilter();
                }), registered("MavenDetective.PreviewPomFixes")));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        tree = new FindingTree<>(ExclusionAudit.Finding.class, ExclusionsPanel::render, ExclusionAudit.Finding::isRemovable,
                f -> f.getDependency().ga() + " " + f.getExclusion().ga())
                // Häkchen hängen am Befund, nicht am Objekt: nach dem Neu-Prüfen bleibt eine abgewählte Exclusion abgewählt
                .keyedBy(f -> List.of(f.getFile(), f.isManaged(), f.getDependency().managementKey(), f.getExclusion().ga()));
        tree.addSelectionListener(this::showSelection);
        tree.addCheckListener(this::updateRemove);
        emptyText(tree.component().getEmptyText(), MavenDetectiveBundle.message("loading.exclusions"), null, null);

        details = new HtmlView(project);
        remove.addActionListener(e -> removeChecked());
        remove.setEnabled(false);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(remove), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Exclusions.split", 0.55f);
        splitter.setFirstComponent(rows(tree.component(), this::selectedLocation, this::selectedText));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        showSelection();

        AnalysisResults.getInstance(project).addListener(new AnalysisResults.Listener() {
            @Override
            public void changed(@NotNull AnalysisResults.Analysis analysis, @NotNull AnalysisResults.Summary summary) {
            }

            /** Günstig (nur POMs): nach jedem Import von selbst neu rechnen. */
            @Override
            public void mavenImported() {
                reload(false);
            }
        }, this);
        reload(false);
    }

    @Override
    public boolean supportsRefresh() {
        return true;
    }

    @Override
    public boolean canRefresh() {
        return !checker.isRunning();
    }

    @Override
    public void refresh() {
        reload(true);
    }

    @Override
    public boolean canCopyMarkdown() {
        return result != null;
    }

    @Override
    public @Nullable String markdown() {
        return result == null ? null : result.markdown(withAll);
    }

    /** @param save ungespeicherte POMs vorher speichern – nur auf Klick, nicht beim automatischen Lauf */
    void reload(boolean save) {
        if (save) {
            DependenciesPanel.saveUnsavedPoms();
        }
        AnalysisResults results = AnalysisResults.getInstance(project);
        results.running(AnalysisResults.Analysis.EXCLUSIONS);
        checker.run(c -> compute(project, c), audit -> {
            result = audit;
            applyFilter();
            results.set(AnalysisResults.Analysis.EXCLUSIONS, summary(audit));
        });
    }

    @Override
    protected void aborted() {
        AnalysisResults.getInstance(project).aborted(AnalysisResults.Analysis.EXCLUSIONS);
    }

    /** Hintergrund-Thread; liest nur POMs. */
    static @Nullable ExclusionAudit compute(Project project, Cancellation cancel) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = DependenciesPanel.locator(project, root, null, cancel);
        List<PomFile> poms = DuplicateClassesPanel.projectPoms(project, locator, root);
        if (poms.isEmpty()) {
            return null;
        }
        Map<String, DepNode> trees = IdeMaven.dependencyTrees(project);
        return ExclusionAudit.build(locator, poms, trees, cancel);
    }

    static @NotNull AnalysisResults.Summary summary(@Nullable ExclusionAudit audit) {
        if (audit == null) {
            return AnalysisResults.Summary.NOT_CHECKED;
        }
        List<ExclusionAudit.Finding> removable = audit.removable();
        AnalysisResults.Summary s = AnalysisResults.Summary.done(removable.size(), MavenDetectiveBundle.message("tile.exclusions.summary",
                audit.count(ExclusionAudit.Status.STALE), audit.count(ExclusionAudit.Status.REDUNDANT)));
        return removable.isEmpty() ? s : s.at(Locations.of(removable.get(0).getFile(), removable.get(0).getLine()));
    }

    /** Sprung aus der Übersicht oder dem Editor: diese Stelle wählen (ggf. Filter lockern). */
    void select(@NotNull String file, int line) {
        if (result == null) {
            return;
        }
        for (ExclusionAudit.Finding f : result.getFindings()) {
            if (f.getFile().equals(file) && f.getLine() == line) {
                withAll |= !f.isRemovable();
                if (!search.getText().isEmpty()) {
                    search.setText("");
                }
                applyFilter();
                tree.select(f);
                return;
            }
        }
    }

    private void applyFilter() {
        if (result == null) {
            tree.set(Map.of(), l -> null, f -> false);
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            setStatus(null);
            showSelection();
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<ExclusionAudit.Finding> visible = new ArrayList<>();
        for (ExclusionAudit.Finding f : result.getFindings()) {
            if ((withAll || f.isRemovable()) && (q.isEmpty() || matches(f, q))) {
                visible.add(f);
            }
        }
        tree.set(FindingTree.groupBy(visible, f -> PomDiffView.label(f.getPom().getFile())), ExclusionsPanel::groupDetail, f -> true);
        boolean none = result.removable().isEmpty();
        if (none && !withAll) {
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("exclusions.empty.none"),
                    result.getFindings().isEmpty() ? null : MavenDetectiveBundle.message("exclusions.empty.show.all"), () -> {
                        withAll = true;
                        applyFilter();
                    });
        } else {
            emptyText(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.no.match"), null, null);
        }
        setStatus(MavenDetectiveBundle.message("exclusions.status", result.getFindings().size(),
                result.count(ExclusionAudit.Status.STALE), result.count(ExclusionAudit.Status.REDUNDANT),
                result.count(ExclusionAudit.Status.UNKNOWN)));
        tree.selectFirstIfNone();
        showSelection();
        updateRemove();
    }

    private static boolean matches(ExclusionAudit.Finding f, String q) {
        return f.getDependency().ga().toLowerCase(Locale.ROOT).contains(q) || f.getExclusion().ga().toLowerCase(Locale.ROOT).contains(q)
                || f.getPom().getArtifactId().toLowerCase(Locale.ROOT).contains(q);
    }

    private static @Nls String groupDetail(List<ExclusionAudit.Finding> items) {
        int removable = 0;
        for (ExclusionAudit.Finding f : items) {
            if (f.isRemovable()) {
                removable++;
            }
        }
        return MavenDetectiveBundle.message("exclusions.group", removable, items.size());
    }

    /** Art als Icon + Wort, dann Exclusion und Dependency. */
    private static void render(ColoredTreeCellRenderer r, ExclusionAudit.Finding f, boolean selected) {
        r.setIcon(icon(f.getStatus()));
        boolean removable = f.isRemovable();
        r.append(label(f.getStatus()), removable ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
        r.append("  " + f.getExclusion().ga(), removable ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
        r.append("  " + MavenDetectiveBundle.message("exclusions.at", f.getDependency().ga())
                + (f.isManaged() ? " · dependencyManagement" : ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        r.setToolTipText(f.message());
    }

    static @NotNull Icon icon(@NotNull ExclusionAudit.Status s) {
        return switch (s) {
            case STALE -> AllIcons.General.Warning;
            case REDUNDANT -> AllIcons.Actions.Copy;
            case USED -> AllIcons.General.InspectionsOK;
            case UNKNOWN -> AllIcons.General.ContextHelp;
        };
    }

    static @Nls @NotNull String label(@NotNull ExclusionAudit.Status s) {
        return MavenDetectiveBundle.message(switch (s) {
            case STALE -> "exclusions.kind.stale";
            case REDUNDANT -> "exclusions.kind.redundant";
            case USED -> "exclusions.kind.used";
            case UNKNOWN -> "exclusions.kind.unknown";
        });
    }

    private void showSelection() {
        ExclusionAudit.Finding f = tree.single();
        if (f == null) {
            List<ExclusionAudit.Finding> sel = tree.selected();
            details.show(result == null ? MavenDetectiveBundle.message("exclusions.detail.start")
                    : "<p>" + (sel.size() > 1 ? MavenDetectiveBundle.message("exclusions.detail.many", sel.size())
                    : result.removable().isEmpty() ? MavenDetectiveBundle.message("exclusions.empty.none")
                    : MavenDetectiveBundle.message("exclusions.detail.select")) + "</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(label(f.getStatus()))).append(": ").append(esc(f.getExclusion().ga()))
                .append("</h3>");
        sb.append("<p>").append(esc(f.message())).append("</p>");
        sb.append("<p><b>").append(MavenDetectiveBundle.message("exclusions.detail.file")).append("</b> ").append(Html.pomLink(f.getFile(), f.getLine()))
                .append(" ").append(esc(MavenDetectiveBundle.message("exclusions.at",
                        f.getDependency().ga() + (f.getDependencyVersion() == null ? "" : ":" + f.getDependencyVersion()))))
                .append(f.isManaged() ? " (dependencyManagement)" : "").append("</p>");
        if (!f.getPath().isEmpty()) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.path")).append("</b> ").append(esc(String.join(" → ", f.getPath()))).append("</p>");
        }
        if (f.getCoveredBy() != null) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("exclusions.detail.covered")).append("</b> ").append(esc(f.getCoveredBy())).append("</p>");
        }
        if (!f.getMissing().isEmpty()) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("exclusions.detail.missing")).append("</b> ").append(esc(String.join(", ", f.getMissing()))).append("</p>");
        }
        FixAction fix = f.fix();
        if (fix != null) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.suggestion")).append("</b> ").append(esc(fix.getLabel())).append("</p>");
        }
        details.show(sb.toString());
    }

    private void updateRemove() {
        int n = tree.checked().size();
        remove.setEnabled(n > 0);
        remove.setText(n > 1 ? MavenDetectiveBundle.message("exclusions.remove.many", n) : MavenDetectiveBundle.message("exclusions.remove"));
    }

    private void removeChecked() {
        List<FixAction> fixes = new ArrayList<>();
        for (ExclusionAudit.Finding f : tree.checked()) {
            FixAction fix = f.fix();
            if (fix != null) {
                fixes.add(fix);
            }
        }
        if (fixes.isEmpty()) {
            return;
        }
        int n = FixRunner.editPoms(project, fixes, MavenDetectiveBundle.message("command.remove.exclusions"));
        if (n > 0) {
            remove.setEnabled(false);
            setStatus(MavenDetectiveBundle.message("exclusions.removed", n));
        }
    }

    /** F4: die gewählte Exclusion in ihrer POM. */
    private @Nullable String selectedLocation() {
        List<ExclusionAudit.Finding> sel = tree.selected();
        return sel.isEmpty() ? null : Locations.of(sel.get(0).getFile(), sel.get(0).getLine());
    }

    private @Nullable String selectedText() {
        List<ExclusionAudit.Finding> sel = tree.selected();
        if (sel.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ExclusionAudit.Finding f : sel) {
            sb.append(sb.length() == 0 ? "" : "\n").append(f.message());
        }
        return sb.toString();
    }

    @Override
    protected @Nls @Nullable String about() {
        return MavenDetectiveBundle.message("exclusions.about");
    }
}
