package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FindingTree;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Seite „Doppelte Klassen“ im Tab „Dependencies“ (Modul A3): Baum Modul → Fund (JARs, die dieselben Klassen mitbringen),
 * Art als Icon und Wort; angekreuzte Funde setzt „Markierte bereinigen“ gemäß Empfehlung in einem Schritt um. Unten
 * Klassen, Pfade und die Empfehlung; der Knopf fügt die Exclusion bei jeder direkten Dependency
 * ein, über die das gewählte JAR kommt (ist es direkt deklariert, springt er zur Deklaration).
 */
public final class DuplicateClassesPanel extends DetectivePanel {

    private static final int MAX_CLASSES_SHOWN = 200;

    private boolean withTests;
    private boolean withIdentical = true;
    private final SearchTextField search;
    private final FindingTree<DuplicateClasses.Clash> tree;
    private final HtmlView details;
    private final ComboBox<ModuleClasspath.Entry> target = new ComboBox<>();
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("duplicates.exclude")));
    private final JButton applyChecked = new JButton(MavenDetectiveBundle.message("duplicates.exclude.checked", 0));
    private final Background checker = background(MavenDetectiveBundle.message("loading.duplicates"));
    private DuplicateClasses result;

    public DuplicateClassesPanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.findings"), this::applyFilter);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.Stop"));
        actions.add(filterGroup(
                toggle(MavenDetectiveBundle.message("duplicates.filter.tests"), null, () -> withTests, v -> {
                    withTests = v;
                    applyFilter();
                }),
                toggle(MavenDetectiveBundle.message("duplicates.filter.identical"), null, () -> withIdentical, v -> {
                    withIdentical = v;
                    applyFilter();
                }), registered("MavenDetective.PreviewPomFixes")));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        tree = new FindingTree<>(DuplicateClasses.Clash.class, DuplicateClassesPanel::render, DuplicateClassesPanel::canExclude,
                c -> c.getModule().getName() + " " + jars(c));
        tree.addSelectionListener(() -> show(selected()));
        tree.addCheckListener(this::updateApplyChecked);
        // Nicht automatisch prüfen: das öffnet alle JARs aller Module – erst auf Klick
        intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                MavenDetectiveBundle.message("empty.check.now"), this::reload);

        details = new HtmlView(project);
        target.setRenderer(new SimpleListCellRenderer<ModuleClasspath.Entry>() {
            @Override
            public void customize(@NotNull JList<? extends ModuleClasspath.Entry> list, ModuleClasspath.Entry value,
                                  int index, boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value.label());
            }
        });
        target.addActionListener(e -> updateApply());
        apply.addActionListener(e -> applySelected());
        applyChecked.addActionListener(e -> applyChecked());
        applyChecked.setEnabled(false);
        applyChecked.setToolTipText(MavenDetectiveBundle.message("duplicates.exclude.checked.tooltip"));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(new JBLabel(MavenDetectiveBundle.message("duplicates.target")), target, apply, applyChecked), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Duplicates.split", 0.5f);
        splitter.setFirstComponent(rows(tree.component(), this::selectedLocation, () -> selected() == null ? null : selected().message()));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        show(null);
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
        return result == null ? null : result.markdown(false);
    }

    private void reload() {
        DependenciesPanel.saveUnsavedPoms();
        setStatus(null);
        AnalysisResults results = AnalysisResults.getInstance(project);
        results.running(AnalysisResults.Analysis.DUPLICATES);
        checker.run(c -> compute(project, c), r -> {
            result = r;
            setStale(false);
            applyFilter();
            results.set(AnalysisResults.Analysis.DUPLICATES, summary(r));
            PomInsights.getInstance(project).setDuplicates(r);
        });
    }

    /** Sprung aus dem Editor (Gutter-Icon): diesen Fund wählen; ist er ausgefiltert, die Filter lockern. */
    void select(@NotNull DuplicateClasses.Clash clash) {
        if (result == null || !result.clashes().contains(clash)) {
            return;
        }
        withTests |= !clash.isMain();
        withIdentical |= clash.isIdentical();
        if (!search.getText().isEmpty()) {
            search.setText("");
        }
        applyFilter();
        tree.select(clash);
    }

    @Override
    protected void aborted() {
        AnalysisResults.getInstance(project).aborted(AnalysisResults.Analysis.DUPLICATES);
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        if (result == null) {
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                    MavenDetectiveBundle.message("empty.check.now"), this::reload);
        }
    }

    /** Zahl = Funde im Laufzeit-Classpath (wie mit Standardfilter sichtbar). */
    private static AnalysisResults.Summary summary(@Nullable DuplicateClasses r) {
        if (r == null) {
            return AnalysisResults.Summary.NOT_CHECKED;
        }
        int main = 0;
        int differing = 0;
        for (DuplicateClasses.Clash c : r.clashes()) {
            if (c.isMain()) {
                main++;
                if (!c.isIdentical()) {
                    differing++;
                }
            }
        }
        return AnalysisResults.Summary.done(main, MavenDetectiveBundle.message("summary.duplicates", main, differing));
    }

    private static String jars(DuplicateClasses.Clash c) {
        List<String> labels = new ArrayList<>();
        for (ModuleClasspath.Entry e : c.getJars()) {
            labels.add(e.label());
        }
        return String.join(", ", labels);
    }

    /** F4: wo die Exclusion hinkäme bzw. wo das JAR deklariert ist. */
    private @Nullable String selectedLocation() {
        DuplicateClasses.Clash c = selected();
        if (c == null) {
            return null;
        }
        ModuleClasspath.Entry e = (ModuleClasspath.Entry) target.getSelectedItem();
        List<FixAction> fixes = e == null ? List.of() : c.fixesFor(e);
        if (fixes.isEmpty() || fixes.get(0).param("file") == null) {
            return c.getModule().getPom().getFile().toString();
        }
        FixAction f = fixes.get(0);
        return f.param("line") == null ? f.param("file") : f.param("file") + ":" + f.param("line");
    }

    /** Hintergrund-Thread; liest nur Dateien. */
    static @Nullable DuplicateClasses compute(Project project, Cancellation cancellation) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = DependenciesPanel.locator(project, root);
        List<PomFile> poms = projectPoms(project, locator, root);
        if (poms.isEmpty()) {
            return null;
        }
        Map<String, DepNode> trees = IdeMaven.dependencyTrees(project);
        return DuplicateClasses.build(locator, poms, trees, IdeUsages.JARS, cancellation);
    }

    /** POMs aller importierten Maven-Module; ohne Import die unter der Reactor-Wurzel. */
    static List<PomFile> projectPoms(Project project, PomLocator locator, @Nullable Path root) {
        List<PomFile> poms = new ArrayList<>();
        for (IdeMaven.ModuleRef ref : IdeMaven.modules(project)) {
            PomFile p = locator.load(ref.pom());
            if (p != null) {
                poms.add(p);
            }
        }
        if (poms.isEmpty() && root != null) {
            for (ProjectPoms.Pom p : new ProjectPoms(root).all()) {
                PomFile f = locator.load(p.file);
                if (f != null) {
                    poms.add(f);
                }
            }
        }
        return poms;
    }

    private void applyFilter() {
        List<DuplicateClasses.Clash> rows = new ArrayList<>();
        if (result == null) {
            tree.set(Map.of(), l -> null, c -> false);
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            show(null);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int hidden = 0;
        for (DuplicateClasses.Clash c : result.clashes()) {
            boolean visible = (withTests || c.isMain()) && (withIdentical || !c.isIdentical());
            if (visible && !q.isEmpty() && !matches(c, q)) {
                visible = false;
            }
            if (visible) {
                rows.add(c);
            } else {
                hidden++;
            }
        }
        tree.set(FindingTree.groupBy(rows, c -> c.getModule().getName()), DuplicateClassesPanel::groupDetail, c -> false);
        int unreadable = 0;
        for (DuplicateClasses.ModuleResult m : result.getModules()) {
            unreadable += m.getUnreadable().size();
        }
        int n = result.clashes().size();
        if (n == 0) {
            intro(tree.component().getEmptyText(), MavenDetectiveBundle.message("duplicates.empty.none"), null, null);
        } else {
            emptyText(tree.component().getEmptyText(), MavenDetectiveBundle.message("empty.all.filtered"), null, null);
        }
        setStatus(result.getModules().size() + " Module, " + n + (n == 1 ? " Fund" : " Funde")
                + (hidden > 0 ? " (" + hidden + " ausgeblendet)" : "")
                + (unreadable > 0 ? ", " + unreadable + " JARs lokal nicht lesbar" : "")
                + (result.isEstimated() ? " – ohne Baum aus den POMs nachgebaut (Maven-Projekt neu laden)" : ""));
        tree.selectFirstIfNone();
        show(selected());
        updateApplyChecked();
    }

    private static boolean matches(DuplicateClasses.Clash c, String q) {
        if (c.getModule().getName().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (ModuleClasspath.Entry e : c.getJars()) {
            if (e.ga().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        for (String cls : c.getClasses()) {
            if (cls.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable DuplicateClasses.Clash selected() {
        return tree.single();
    }

    /** Ankreuzbar = es gibt eine Empfehlung, die sich per Exclusion umsetzen lässt. */
    private static boolean canExclude(DuplicateClasses.Clash c) {
        return !exclusionsFor(c).isEmpty();
    }

    /** Exclusions, die die Empfehlung umsetzen ({@code drop} raus); leer, wenn es keine gibt. */
    private static List<FixAction> exclusionsFor(DuplicateClasses.Clash c) {
        List<FixAction> out = new ArrayList<>();
        if (c.getDrop() != null) {
            for (FixAction f : c.fixesFor(c.getDrop())) {
                if (FixAction.ADD_EXCLUSION.equals(f.getType())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String groupDetail(List<DuplicateClasses.Clash> items) {
        int differing = 0;
        for (DuplicateClasses.Clash c : items) {
            if (c.isMain() && !c.isIdentical()) {
                differing++;
            }
        }
        return MavenDetectiveBundle.message("duplicates.group", items.size(), differing);
    }

    /** Art als Icon + Wort (verschieden / inhaltsgleich / nur Tests), dann JARs, Klassen und Empfehlung. */
    private static void render(ColoredTreeCellRenderer r, DuplicateClasses.Clash c, boolean selected) {
        boolean risky = c.isMain() && !c.isIdentical();
        r.setIcon(!c.isMain() ? AllIcons.Nodes.Test : risky ? AllIcons.General.Error : AllIcons.General.Warning);
        String kind = MavenDetectiveBundle.message(!c.isMain() ? "duplicates.kind.test" : risky ? "duplicates.kind.differing"
                : "duplicates.kind.identical");
        SimpleTextAttributes plain = c.isMain() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES;
        r.append(kind, risky ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : plain);
        r.append("  " + jars(c), plain);
        List<String> p = c.packages();
        r.append("  " + MavenDetectiveBundle.message("duplicates.classes", c.getClasses().size()) + " · " + p.get(0)
                + (p.size() > 1 ? " +" + (p.size() - 1) : ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        r.append("  → " + (c.getDrop() == null ? MavenDetectiveBundle.message("duplicates.decide") : c.getDrop().label() + " raus"),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        r.setToolTipText(c.message());
    }

    private void updateApplyChecked() {
        int n = 0;
        for (DuplicateClasses.Clash c : tree.checked()) {
            n += exclusionsFor(c).size();
        }
        applyChecked.setEnabled(n > 0);
        applyChecked.setText(MavenDetectiveBundle.message("duplicates.exclude.checked", n));
    }

    /** Für alle angekreuzten Funde die Empfehlung umsetzen – ein Undo-Schritt, mit Vorschau. */
    private void applyChecked() {
        List<FixAction> fixes = new ArrayList<>();
        for (DuplicateClasses.Clash c : tree.checked()) {
            for (FixAction f : exclusionsFor(c)) {
                if (!containsSame(fixes, f)) {
                    fixes.add(f);
                }
            }
        }
        if (fixes.isEmpty()) {
            return;
        }
        int n = FixRunner.addExclusions(project, fixes);
        if (n > 0) {
            showText("<p>" + n + (n == 1 ? " Exclusion" : " Exclusions") + " eingefügt. Nach dem Maven-Import ist die Liste veraltet – "
                    + "dann oben „Neu prüfen“.</p>");
            applyChecked.setEnabled(false);
        }
    }

    /** Dieselbe Exclusion bei derselben Dependency derselben POM nur einmal einfügen (PomEditor lehnt Duplikate ab). */
    private static boolean containsSame(List<FixAction> fixes, FixAction f) {
        for (FixAction g : fixes) {
            if (Objects.equals(g.param("file"), f.param("file")) && Objects.equals(g.param("artifact"), f.param("artifact"))
                    && Objects.equals(g.param("exclusion"), f.param("exclusion"))) {
                return true;
            }
        }
        return false;
    }

    private void show(@Nullable DuplicateClasses.Clash c) {
        target.removeAllItems();
        apply.setEnabled(false);
        if (c == null) {
            showText(result == null
                    ? "<p>Prüft je Modul, ob dieselbe Klasse in mehreren JARs liegt – auch unter verschiedenen Koordinaten, "
                    + "etwa <code>commons-logging</code> neben <code>jcl-over-slf4j</code> oder <code>javax.xml.bind:jaxb-api</code> "
                    + "neben <code>jakarta.xml.bind-api</code> 2.x. Welche Fassung geladen wird, entscheidet dann die Reihenfolge im "
                    + "Classpath.</p>"
                    : result.clashes().isEmpty() ? "<p>Keine Klasse liegt in mehr als einem JAR.</p>"
                    : "<p>Fund wählen, um Klassen, Herkunft und Empfehlung zu sehen.</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(c.getModule().getName())).append("</h3>");
        sb.append("<p>").append(esc(c.message())).append("</p>");
        sb.append("<p><b>Classpath-Reihenfolge</b>").append(c.getModule().isFromTree() ? "" : " (ohne Baum geschätzt)")
                .append(":</p><ol>");
        for (ModuleClasspath.Entry e : c.getJars()) {
            sb.append("<li><b>").append(esc(e.label())).append("</b> ");
            if (e.isSelf()) {
                sb.append("– ").append(esc(String.valueOf(e.getFile())));
            } else if (e.isDirect()) {
                sb.append("direkt, ").append(esc(e.getScope()));
            } else {
                sb.append("über ").append(esc(String.join(" → ", e.getPath().subList(0, e.getPath().size() - 1))))
                        .append(", ").append(esc(e.getScope()));
            }
            sb.append("</li>");
        }
        sb.append("</ol>");
        if (c.getHint() != null) {
            sb.append("<p><b>Bekannt:</b> ").append(esc(c.getHint())).append("</p>");
        }
        sb.append("<p><b>Empfehlung:</b> ").append(esc(c.recommendation())).append("</p>");
        sb.append("<p><b>Pakete:</b> ").append(esc(String.join(", ", c.packages()))).append("</p>");
        sb.append("<p><b>Klassen</b> (").append(c.getClasses().size()).append("):</p><p><code>");
        List<String> classes = c.getClasses();
        for (int i = 0; i < classes.size() && i < MAX_CLASSES_SHOWN; i++) {
            sb.append(i == 0 ? "" : "<br>").append(esc(classes.get(i)));
        }
        if (classes.size() > MAX_CLASSES_SHOWN) {
            sb.append("<br>… und ").append(classes.size() - MAX_CLASSES_SHOWN).append(" weitere");
        }
        sb.append("</code></p>");
        showText(sb.toString());

        for (ModuleClasspath.Entry e : c.getJars()) {
            if (!e.isSelf()) {
                target.addItem(e);
            }
        }
        target.setSelectedItem(c.getDrop() != null ? c.getDrop() : null);
        updateApply();
    }

    private void updateApply() {
        DuplicateClasses.Clash c = selected();
        ModuleClasspath.Entry e = (ModuleClasspath.Entry) target.getSelectedItem();
        if (c == null || e == null) {
            apply.setEnabled(false);
            apply.setText(MavenDetectiveBundle.message("duplicates.exclude"));
            return;
        }
        List<FixAction> fixes = c.fixesFor(e);
        apply.setEnabled(!fixes.isEmpty());
        if (fixes.isEmpty()) {
            apply.setText(MavenDetectiveBundle.message("duplicates.exclude"));
            apply.setToolTipText("Keine direkte Dependency im Projekt gefunden, über die " + e.ga() + " kommt");
        } else if (FixAction.OPEN_FILE.equals(fixes.get(0).getType())) {
            apply.setText(MavenDetectiveBundle.message("fix.goto.declaration"));
            apply.setToolTipText(fixes.get(0).getLabel());
        } else {
            apply.setText(fixes.size() == 1 ? "Exclusion einfügen" : fixes.size() + " Exclusions einfügen");
            StringBuilder tip = new StringBuilder("<html>");
            for (FixAction f : fixes) {
                tip.append(esc(f.getLabel())).append("<br>");
            }
            apply.setToolTipText(tip.append("</html>").toString());
        }
    }

    private void applySelected() {
        DuplicateClasses.Clash c = selected();
        ModuleClasspath.Entry e = (ModuleClasspath.Entry) target.getSelectedItem();
        if (c == null || e == null) {
            return;
        }
        List<FixAction> fixes = c.fixesFor(e);
        if (fixes.isEmpty()) {
            return;
        }
        if (FixAction.OPEN_FILE.equals(fixes.get(0).getType())) {
            FixAction f = fixes.get(0);
            FixRunner.openLocation(project, f.param("file") + ":" + f.param("line"));
            return;
        }
        int n = FixRunner.addExclusions(project, fixes);
        if (n > 0) {
            showText("<p>" + esc(e.ga()) + " ausgeschlossen (" + n + (n == 1 ? " Exclusion" : " Exclusions") + "). Nach dem Maven-Import "
                    + "ist die Liste veraltet – dann oben „Neu prüfen“.</p>");
            apply.setEnabled(false);
        }
    }

    private void showText(String body) {
        details.show(body);
    }

    @Override
    protected @Nls @Nullable String about() {
        return MavenDetectiveBundle.message("duplicates.about");
    }
}
