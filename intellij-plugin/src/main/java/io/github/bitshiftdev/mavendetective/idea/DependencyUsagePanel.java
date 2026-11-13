package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FindingTree;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
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
 * Seite „Nutzung“ im Tab „Dependencies“ (Modul A5): je Modul Dependencies, die der Code nennt, aber nur
 * transitiv bekommt, deklarierte, die er nicht nennt, und compile-Dependencies, die nur Tests
 * nennen. Die Namen kommen über den Code-Index ({@link IdeUsages}); unten Belege mit Sprung zur
 * Zeile. Baum Modul → Befund; „Beheben“ wendet die Vorschläge aller angekreuzten (sonst der gewählten) Befunde in einem
 * Undo-Schritt an, mit Vorschau.
 */
public final class DependencyUsagePanel extends DetectivePanel {

    private boolean withUncertain = true;
    private final SearchTextField search;
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("usage.apply")));
    private final FindingTree<DependencyUsage.Finding> tree;
    private final HtmlView details;
    private final Background checker = background(MavenDetectiveBundle.message("loading.usage"));
    private DependencyUsage result;

    /** „Auch über Starter“: gemerkt in den Einstellungen ({@link DetectiveSettings}), dort auch umstellbar. */
    private static boolean withBundles() {
        return DetectiveSettings.getInstance().isUsageViaStarters();
    }

    public DependencyUsagePanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.findings"), this::applyFilter);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.Stop"));
        actions.add(filterGroup(
                toggle(MavenDetectiveBundle.message("usage.filter.bundles"), MavenDetectiveBundle.message("usage.filter.bundles.description"),
                        DependencyUsagePanel::withBundles, v -> {
                            DetectiveSettings.getInstance().setUsageViaStarters(v);
                            applyFilter();
                        }),
                toggle(MavenDetectiveBundle.message("usage.filter.uncertain"), MavenDetectiveBundle.message("usage.filter.uncertain.description"),
                        () -> withUncertain, v -> {
                            withUncertain = v;
                            applyFilter();
                        }), registered("MavenDetective.PreviewPomFixes")));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        apply.setEnabled(false);
        apply.addActionListener(e -> applySelected());

        tree = new FindingTree<>(DependencyUsage.Finding.class, DependencyUsagePanel::render, DependencyUsagePanel::hasPomFix,
                f -> f.getModule().getName() + " " + f.getEntry().label());
        tree.addSelectionListener(() -> {
            List<DependencyUsage.Finding> sel = selected();
            show(tree.single(), sel.size());
            updateApply();
        });
        tree.addCheckListener(this::updateApply);
        // Nicht automatisch prüfen: das löst alle Referenzen aller Module auf – erst auf Klick
        intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                MavenDetectiveBundle.message("empty.check.now"), this::reload);

        details = new HtmlView(project);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(apply), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Usage.split", 0.55f);
        splitter.setFirstComponent(rows(tree.component(), this::selectedLocation, this::selectedText));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        show(null, 0);
        AnalysisResults.getInstance(project).addListener(new AnalysisResults.Listener() {
            @Override
            public void changed(@NotNull AnalysisResults.Analysis analysis, @NotNull AnalysisResults.Summary summary) {
            }

            /** Teuer: nicht von selbst neu prüfen, aber sichtbar als veraltet markieren. */
            @Override
            public void mavenImported() {
                if (result != null) {
                    setStale(true);
                }
            }
        }, this);
    }

    @Override
    public boolean supportsRefresh() {
        return true;
    }

    @Override
    public void refresh() {
        reload();
    }

    @Override
    public @NotNull String refreshText() {
        return MavenDetectiveBundle.message("action.check");
    }

    @Override
    public @NotNull Icon refreshIcon() {
        return AllIcons.Actions.Execute;
    }

    @Override
    public boolean canCopyMarkdown() {
        return result != null;
    }

    @Override
    public @Nullable String markdown() {
        return result == null ? null : result.markdown(withBundles());
    }

    private void reload() {
        DependenciesPanel.saveUnsavedPoms();
        setStatus(null);
        AnalysisResults results = AnalysisResults.getInstance(project);
        results.running(AnalysisResults.Analysis.USAGE);
        checker.run(c -> compute(project, c), r -> {
            result = r;
            setStale(false);
            applyFilter();
            results.set(AnalysisResults.Analysis.USAGE, summary(r));
        });
    }

    @Override
    protected void aborted() {
        AnalysisResults.getInstance(project).aborted(AnalysisResults.Analysis.USAGE);
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        if (result == null) {
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                    MavenDetectiveBundle.message("empty.check.now"), this::reload);
        }
    }

    /** Zahl = Befunde, die mit Standardfilter sichtbar sind (ohne „über Starter“). */
    private static AnalysisResults.Summary summary(@Nullable DependencyUsage r) {
        if (r == null) {
            return AnalysisResults.Summary.NOT_CHECKED;
        }
        int undeclared = 0;
        int unused = 0;
        int testOnly = 0;
        for (DependencyUsage.Finding f : r.findings()) {
            if (f.isViaBundle()) {
                continue;
            }
            switch (f.getKind()) {
                case UNDECLARED -> undeclared++;
                case UNUSED -> unused++;
                default -> testOnly++;
            }
        }
        return AnalysisResults.Summary.done(undeclared + unused + testOnly,
                MavenDetectiveBundle.message("summary.usage", undeclared, unused, testOnly));
    }

    /** F4: Deklaration in der POM, sonst die erste Fundstelle im Code. */
    private @Nullable String selectedLocation() {
        List<DependencyUsage.Finding> sel = selected();
        if (sel.isEmpty()) {
            return null;
        }
        DependencyUsage.Finding f = sel.get(0);
        if (f.declarationLocation() != null) {
            return f.declarationLocation();
        }
        List<ClassUsages.Use> uses = !f.getMainUses().isEmpty() ? f.getMainUses() : f.getTestUses();
        return uses.isEmpty() || uses.get(0).getFile() == null ? null : uses.get(0).location();
    }

    private @Nullable String selectedText() {
        List<DependencyUsage.Finding> sel = selected();
        if (sel.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (DependencyUsage.Finding f : sel) {
            sb.append(sb.length() == 0 ? "" : "\n").append(f.getModule().getName()).append(": ").append(f.message());
        }
        return sb.toString();
    }

    /** Hintergrund-Thread, außerhalb einer Read-Action (die Sammlung nimmt sich je Datei eine). */
    static @Nullable DependencyUsage compute(Project project, Cancellation cancellation) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = DependenciesPanel.locator(project, root);
        List<PomFile> poms = DuplicateClassesPanel.projectPoms(project, locator, root);
        if (poms.isEmpty()) {
            return null;
        }
        Map<String, DepNode> trees = IdeMaven.dependencyTrees(project);
        return DependencyUsage.build(locator, poms, trees, IdeUsages.JARS,
                pom -> IdeUsages.collect(project, pom.getFile(), cancellation), cancellation);
    }

    private void applyFilter() {
        List<DependencyUsage.Finding> rows = new ArrayList<>();
        if (result == null) {
            tree.set(Map.of(), l -> null, f -> false);
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            show(null, 0);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int hidden = 0;
        for (DependencyUsage.Finding f : result.findings()) {
            boolean visible = (withBundles() || !f.isViaBundle()) && (withUncertain || f.isCertain());
            if (visible && !q.isEmpty() && !matches(f, q)) {
                visible = false;
            }
            if (visible) {
                rows.add(f);
            } else {
                hidden++;
            }
        }
        tree.set(FindingTree.groupBy(rows, f -> f.getModule().getName()), DependencyUsagePanel::groupDetail, f -> false);
        int unchecked = 0;
        int files = 0;
        for (DependencyUsage.ModuleResult m : result.getModules()) {
            unchecked += m.getUnchecked().size();
            files += m.getUsages().getMainFiles() + m.getUsages().getTestFiles();
        }
        if (result.findings().isEmpty()) {
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("usage.empty.none"), null, null);
        } else {
            emptyText(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.all.filtered"), null, null);
        }
        setStatus(MavenDetectiveBundle.message("usage.status", result.getModules().size(), files,
                result.findings(DependencyUsage.Kind.UNDECLARED).size(), result.findings(DependencyUsage.Kind.UNUSED).size(),
                result.findings(DependencyUsage.Kind.TEST_ONLY).size())
                + (hidden > 0 ? " " + MavenDetectiveBundle.message("status.hidden", hidden) : "")
                + (unchecked > 0 ? ", " + MavenDetectiveBundle.message("status.unreadable", unchecked) : "")
                + (result.isEstimated() ? " – " + MavenDetectiveBundle.message("status.estimated") : ""));
        tree.selectFirstIfNone();
        show(tree.single(), selected().size());
        updateApply();
    }

    private static boolean matches(DependencyUsage.Finding f, String q) {
        if (f.getModule().getName().toLowerCase(Locale.ROOT).contains(q) || f.ga().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (ClassUsages.Use u : f.getMainUses()) {
            if (u.getClassName().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        for (ClassUsages.Use u : f.getTestUses()) {
            if (u.getClassName().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private List<DependencyUsage.Finding> selected() {
        return tree.selected();
    }

    /** Worauf „Beheben“ wirkt: die angekreuzten Befunde, sonst die ausgewählten. */
    private List<DependencyUsage.Finding> targets() {
        List<DependencyUsage.Finding> checked = tree.checked();
        return checked.isEmpty() ? selected() : checked;
    }

    private static boolean hasPomFix(DependencyUsage.Finding f) {
        for (FixAction fix : f.fixes()) {
            if (PomEditor.isPomFix(fix.getType())) {
                return true;
            }
        }
        return false;
    }

    private static String groupDetail(List<DependencyUsage.Finding> items) {
        int undeclared = 0;
        int unused = 0;
        int testOnly = 0;
        for (DependencyUsage.Finding f : items) {
            switch (f.getKind()) {
                case UNDECLARED -> undeclared++;
                case UNUSED -> unused++;
                default -> testOnly++;
            }
        }
        return MavenDetectiveBundle.message("summary.usage", undeclared, unused, testOnly);
    }

    /** Art als Icon + Wort (undeklariert / ungenutzt / nur in Tests, „?“ = unsicher), dann Dependency, Beleg, Vorschlag. */
    private static void render(ColoredTreeCellRenderer r, DependencyUsage.Finding f, boolean selected) {
        boolean strong = f.getKind() == DependencyUsage.Kind.UNDECLARED && f.getMainCount() > 0 && !f.isViaBundle();
        r.setIcon(switch (f.getKind()) {
            case UNDECLARED -> AllIcons.General.Warning;
            case UNUSED -> AllIcons.Actions.Cancel;
            default -> AllIcons.Nodes.Test;
        });
        SimpleTextAttributes plain = f.isCertain() && !f.isViaBundle() ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                : SimpleTextAttributes.GRAYED_ATTRIBUTES;
        r.append(kindLabel(f.getKind()) + (f.isCertain() ? "" : "?"), strong ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : plain);
        r.append("  " + f.getEntry().label(), plain);
        r.append("  " + f.getEntry().getScope(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        List<ClassUsages.Use> uses = !f.getMainUses().isEmpty() ? f.getMainUses() : f.getTestUses();
        if (uses.isEmpty()) {
            r.append("  · " + (f.getNote() != null ? f.getNote() : MavenDetectiveBundle.message("usage.no.use")), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        } else {
            int n = f.getMainCount() + f.getTestCount();
            r.append("  · " + uses.get(0).getClassName() + (n > 1 ? " +" + (n - 1) : ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        List<FixAction> fixes = f.fixes();
        if (!fixes.isEmpty()) {
            r.append("  → " + fixes.get(0).getLabel(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        r.setToolTipText(f.message());
    }

    private void show(@Nullable DependencyUsage.Finding f, int count) {
        if (f == null) {
            showText(count > 1 ? MavenDetectiveBundle.message("usage.detail.many", count)
                    : result == null ? MavenDetectiveBundle.message("usage.detail.start")
                    : result.findings().isEmpty() ? "<p>" + MavenDetectiveBundle.message("usage.empty.none") + "</p>"
                    : MavenDetectiveBundle.message("usage.detail.select"));
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(f.getModule().getName())).append(" – ").append(esc(kindLabel(f.getKind())))
                .append(f.isCertain() ? "" : " (" + MavenDetectiveBundle.message("usage.uncertain") + ")").append("</h3>");
        sb.append("<p>").append(esc(f.message())).append("</p>");
        if (f.getKind() == DependencyUsage.Kind.UNDECLARED && f.getEntry().getPath().size() > 1) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.path")).append("</b> ").append(esc(String.join(" → ", f.getEntry().getPath()))).append(", ").append(esc(f.getEntry().getScope()))
                    .append("</p>");
        }
        if (f.declarationLocation() != null) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("usage.declared")).append("</b> ").append(link(f.declarationLocation(), f.getDeclared().declaredIn.getArtifactId() + "/pom.xml:"
                    + f.getDeclared().dep.getLine())).append("</p>");
        }
        appendUses(sb, MavenDetectiveBundle.message("usage.named.main"), f.getMainUses(), f.getMainCount());
        appendUses(sb, MavenDetectiveBundle.message("usage.named.test"), f.getTestUses(), f.getTestCount());
        if (f.getNote() != null) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.note")).append("</b> ").append(esc(f.getNote())).append("</p>");
        }
        if (f.isViaBundle()) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.note")).append("</b> ")
                    .append(MavenDetectiveBundle.message("usage.via.bundle")).append("</p>");
        }
        List<FixAction> fixes = f.fixes();
        if (!fixes.isEmpty()) {
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.suggestion")).append("</b> ").append(esc(fixes.get(0).getLabel())).append("</p>");
        }
        showText(sb.toString());
    }

    static String kindLabel(DependencyUsage.Kind kind) {
        return MavenDetectiveBundle.message(switch (kind) {
            case UNDECLARED -> "usage.kind.undeclared";
            case UNUSED -> "usage.kind.unused";
            case TEST_ONLY -> "usage.kind.test.only";
        });
    }

    private static void appendUses(StringBuilder sb, String title, List<ClassUsages.Use> uses, int count) {
        if (uses.isEmpty()) {
            return;
        }
        sb.append("<p><b>").append(title).append("</b> (").append(MavenDetectiveBundle.message("duplicates.classes", count)).append("):</p><ul>");
        for (ClassUsages.Use u : uses) {
            sb.append("<li><code>").append(esc(u.getClassName())).append("</code> – ");
            sb.append(u.getFile() == null ? "" : link(u.location(), u.getFile().getFileName() + (u.getLine() > 0 ? ":" + u.getLine() : "")));
            sb.append("</li>");
        }
        if (count > uses.size()) {
            sb.append("<li>").append(MavenDetectiveBundle.message("detail.more", count - uses.size())).append("</li>");
        }
        sb.append("</ul>");
    }

    private void updateApply() {
        List<DependencyUsage.Finding> sel = targets();
        int pom = 0;
        for (DependencyUsage.Finding f : sel) {
            for (FixAction fix : f.fixes()) {
                if (PomEditor.isPomFix(fix.getType())) {
                    pom++;
                }
            }
        }
        boolean jump = pom == 0 && sel.size() == 1 && !sel.get(0).fixes().isEmpty();
        apply.setEnabled(pom > 0 || jump);
        apply.setText(jump ? MavenDetectiveBundle.message("fix.goto.declaration")
                : pom > 1 ? MavenDetectiveBundle.message("usage.apply.many", pom) : MavenDetectiveBundle.message("usage.apply"));
        apply.setToolTipText(sel.size() == 1 && !sel.get(0).fixes().isEmpty() ? sel.get(0).fixes().get(0).getLabel() : null);
    }

    private void applySelected() {
        List<DependencyUsage.Finding> sel = targets();
        List<FixAction> pomFixes = new ArrayList<>();
        FixAction open = null;
        for (DependencyUsage.Finding f : sel) {
            for (FixAction fix : f.fixes()) {
                if (PomEditor.isPomFix(fix.getType())) {
                    pomFixes.add(fix);
                } else if (open == null && FixAction.OPEN_FILE.equals(fix.getType())) {
                    open = fix;
                }
            }
        }
        if (pomFixes.isEmpty()) {
            if (open != null) {
                FixRunner.openLocation(project, open.param("file") + ":" + open.param("line"));
            }
            return;
        }
        int n = FixRunner.editPoms(project, pomFixes, MavenDetectiveBundle.message("command.clean.dependencies"));
        if (n > 0) {
            showText("<p>" + MavenDetectiveBundle.message("usage.applied", n) + "</p>");
            apply.setEnabled(false);
        }
    }

    private static String link(String location, String label) {
        return Html.link(location, label);
    }

    private void showText(String body) {
        details.show(body);
    }

    @Override
    protected @Nls @Nullable String about() {
        return MavenDetectiveBundle.message("usage.about");
    }
}
