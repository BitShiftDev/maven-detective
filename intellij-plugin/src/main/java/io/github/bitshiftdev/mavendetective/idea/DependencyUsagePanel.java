package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.ClassUsages;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DependencyUsage;
import io.github.bitshiftdev.mavendetective.core.deps.PomEditor;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Tab „Ungenutzt/undeklariert“ (Modul A5): je Modul Dependencies, die der Code nennt, aber nur
 * transitiv bekommt, deklarierte, die er nicht nennt, und compile-Dependencies, die nur Tests
 * nennen. Die Namen kommen über den Code-Index ({@link IdeUsages}); unten Belege mit Sprung zur
 * Zeile. „Beheben“ wendet die Vorschläge aller markierten Zeilen in einem Undo-Schritt an.
 */
public final class DependencyUsagePanel extends DetectivePanel {

    static final String TAB_NAME = MavenDetectiveBundle.message("tab.usage");

    private boolean withBundles;
    private boolean withUncertain = true;
    private final SearchTextField search;
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("usage.apply")));
    private final FindingModel model = new FindingModel();
    private final JBTable table = new JBTable(model);
    private final HtmlView details;
    private final Background checker = background(MavenDetectiveBundle.message("loading.usage"));
    private DependencyUsage result;

    public DependencyUsagePanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.findings"), this::applyFilter);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.Stop"));
        actions.add(filterGroup(
                toggle(MavenDetectiveBundle.message("usage.filter.bundles"), MavenDetectiveBundle.message("usage.filter.bundles.description"),
                        () -> withBundles, v -> {
                            withBundles = v;
                            applyFilter();
                        }),
                toggle(MavenDetectiveBundle.message("usage.filter.uncertain"), MavenDetectiveBundle.message("usage.filter.uncertain.description"),
                        () -> withUncertain, v -> {
                            withUncertain = v;
                            applyFilter();
                        })));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        apply.setEnabled(false);
        apply.addActionListener(e -> applySelected());

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                List<DependencyUsage.Finding> sel = selected();
                show(sel.size() == 1 ? sel.get(0) : null, sel.size());
                updateApply(sel);
            }
        });
        TableSpeedSearch.installOn(table, (value, cell) -> value instanceof DependencyUsage.Finding f && cell.column == 2 ? f.getEntry().label() : null);
        // Nicht automatisch prüfen: das löst alle Referenzen aller Module auf – erst auf Klick
        emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                MavenDetectiveBundle.message("empty.check.now"), this::reload);

        details = new HtmlView(project);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(apply), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Usage.split", 0.55f);
        splitter.setFirstComponent(rows(table, this::selectedLocation, this::selectedText));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        show(null, 0);
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
        return result == null ? null : result.markdown(withBundles);
    }

    private void reload() {
        DependenciesPanel.saveUnsavedPoms();
        setStatus(null);
        checker.run(c -> compute(project, c), r -> {
            result = r;
            applyFilter();
        });
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        if (result == null) {
            emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                    MavenDetectiveBundle.message("empty.check.now"), this::reload);
        }
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
        List<DependencyUsage.Finding> before = selected();
        List<DependencyUsage.Finding> rows = new ArrayList<>();
        if (result == null) {
            model.set(rows);
            emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            show(null, 0);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int hidden = 0;
        for (DependencyUsage.Finding f : result.findings()) {
            boolean visible = (withBundles || !f.isViaBundle()) && (withUncertain || f.isCertain());
            if (visible && !q.isEmpty() && !matches(f, q)) {
                visible = false;
            }
            if (visible) {
                rows.add(f);
            } else {
                hidden++;
            }
        }
        model.set(rows);
        int unchecked = 0;
        int files = 0;
        for (DependencyUsage.ModuleResult m : result.getModules()) {
            unchecked += m.getUnchecked().size();
            files += m.getUsages().getMainFiles() + m.getUsages().getTestFiles();
        }
        emptyText(table.getEmptyText(), MavenDetectiveBundle.message(result.findings().isEmpty() ? "usage.empty.none" : "empty.all.filtered"),
                null, null);
        setStatus(result.getModules().size() + " Module, " + files + " Quelldateien – "
                + result.findings(DependencyUsage.Kind.UNDECLARED).size() + " undeklariert, "
                + result.findings(DependencyUsage.Kind.UNUSED).size() + " ungenutzt, "
                + result.findings(DependencyUsage.Kind.TEST_ONLY).size() + " nur in Tests"
                + (hidden > 0 ? " (" + hidden + " ausgeblendet)" : "")
                + (unchecked > 0 ? ", " + unchecked + " JARs lokal nicht lesbar" : "")
                + (result.isEstimated() ? " – ohne Baum aus den POMs nachgebaut (Maven-Projekt neu laden)" : ""));
        table.clearSelection();
        for (DependencyUsage.Finding f : before) {
            int idx = rows.indexOf(f);
            if (idx >= 0) {
                table.getSelectionModel().addSelectionInterval(idx, idx);
            }
        }
        if (table.getSelectedRowCount() == 0) {
            if (!rows.isEmpty()) {
                table.getSelectionModel().setSelectionInterval(0, 0);
            } else {
                show(null, 0);
                updateApply(List.of());
            }
        }
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
        List<DependencyUsage.Finding> out = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            int m = table.convertRowIndexToModel(r);
            if (m >= 0 && m < model.rows.size()) {
                out.add(model.rows.get(m));
            }
        }
        return out;
    }

    private void show(@Nullable DependencyUsage.Finding f, int count) {
        if (f == null) {
            showText(count > 1 ? "<p>" + count + " Befunde markiert – „Beheben“ wendet alle Vorschläge in einem Undo-Schritt an.</p>"
                    : result == null
                    ? "<p>Vergleicht, welche Klassen die Quellen jedes Moduls nennen (über den Code-Index aufgelöst), mit den "
                    + "Dependencies in der <code>pom.xml</code>:</p><ul>"
                    + "<li><b>undeklariert</b> – genannt, aber nur transitiv da; fällt die vermittelnde Dependency weg, bricht der Build</li>"
                    + "<li><b>ungenutzt</b> – deklariert, aber keine Klasse daraus wird genannt</li>"
                    + "<li><b>nur in Tests</b> – Scope compile, aber nur Tests nennen sie</li></ul>"
                    + "<p>JDBC-Treiber, Logging-Backends, Test-Engines und Annotation-Prozessoren gelten nie als ungenutzt. "
                    + "Live im Editor markiert die Inspection <i>Import aus nicht deklarierter Dependency</i> die Imports.</p>"
                    : result.findings().isEmpty() ? "<p>Alles deklariert, was genannt wird, und alles genannt, was deklariert ist.</p>"
                    : "<p>Befund wählen, um Belege und Vorschlag zu sehen.</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(f.getModule().getName())).append(" – ").append(esc(f.getKind().label()))
                .append(f.isCertain() ? "" : " (unsicher)").append("</h3>");
        sb.append("<p>").append(esc(f.message())).append("</p>");
        if (f.getKind() == DependencyUsage.Kind.UNDECLARED && f.getEntry().getPath().size() > 1) {
            sb.append("<p><b>Pfad:</b> ").append(esc(String.join(" → ", f.getEntry().getPath()))).append(", ").append(esc(f.getEntry().getScope()))
                    .append("</p>");
        }
        if (f.declarationLocation() != null) {
            sb.append("<p><b>Deklariert:</b> ").append(link(f.declarationLocation(), f.getDeclared().declaredIn.getArtifactId() + "/pom.xml:"
                    + f.getDeclared().dep.getLine())).append("</p>");
        }
        appendUses(sb, "Genannt in Hauptquellen", f.getMainUses(), f.getMainCount());
        appendUses(sb, "Genannt in Tests", f.getTestUses(), f.getTestCount());
        if (f.getNote() != null) {
            sb.append("<p><b>Hinweis:</b> ").append(esc(f.getNote())).append("</p>");
        }
        if (f.isViaBundle()) {
            sb.append("<p><b>Hinweis:</b> kommt über einen Starter ohne eigene Klassen – dort ist Deklarieren üblich nicht nötig, "
                    + "macht den Classpath aber unabhängig vom Starter.</p>");
        }
        List<FixAction> fixes = f.fixes();
        if (!fixes.isEmpty()) {
            sb.append("<p><b>Vorschlag:</b> ").append(esc(fixes.get(0).getLabel())).append("</p>");
        }
        showText(sb.toString());
    }

    private static void appendUses(StringBuilder sb, String title, List<ClassUsages.Use> uses, int count) {
        if (uses.isEmpty()) {
            return;
        }
        sb.append("<p><b>").append(title).append("</b> (").append(count).append(count == 1 ? " Klasse" : " Klassen").append("):</p><ul>");
        for (ClassUsages.Use u : uses) {
            sb.append("<li><code>").append(esc(u.getClassName())).append("</code> – ");
            sb.append(u.getFile() == null ? "" : link(u.location(), u.getFile().getFileName() + (u.getLine() > 0 ? ":" + u.getLine() : "")));
            sb.append("</li>");
        }
        if (count > uses.size()) {
            sb.append("<li>… und ").append(count - uses.size()).append(" weitere</li>");
        }
        sb.append("</ul>");
    }

    private void updateApply(List<DependencyUsage.Finding> sel) {
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
        List<DependencyUsage.Finding> sel = selected();
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
        int n = FixRunner.editPoms(project, pomFixes, "Dependencies bereinigen (Maven Detective)");
        if (n > 0) {
            showText("<p>" + n + (n == 1 ? " Änderung" : " Änderungen") + " an der POM. Nach dem Maven-Import „Prüfen“ klicken, "
                    + "um neu zu prüfen.</p>");
            apply.setEnabled(false);
        }
    }

    private static String link(String location, String label) {
        return Html.link(location, label);
    }

    private void showText(String body) {
        details.show(body);
    }

    private static final class FindingModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Modul", "Art", "Dependency", "Beleg", "Vorschlag"};
        private List<DependencyUsage.Finding> rows = List.of();

        void set(List<DependencyUsage.Finding> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex);
        }
    }

    /** Undeklarierte in Hauptquellen hinterlegt; unsichere und Starter-Fälle grau. */
    private final class Renderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus, int row, int column) {
            if (!(value instanceof DependencyUsage.Finding f)) {
                return;
            }
            boolean strong = f.getKind() == DependencyUsage.Kind.UNDECLARED && f.getMainCount() > 0 && !f.isViaBundle();
            if (strong && !selected) {
                setBackground(DetectiveColors.WARNING_BACKGROUND);
            }
            SimpleTextAttributes plain = f.isCertain() && !f.isViaBundle() ? SimpleTextAttributes.REGULAR_ATTRIBUTES
                    : SimpleTextAttributes.GRAYED_ATTRIBUTES;
            switch (column) {
                case 0 -> append(f.getModule().getName(), plain);
                case 1 -> append(f.getKind().label() + (f.isCertain() ? "" : "?"), strong ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : plain);
                case 2 -> {
                    append(f.getEntry().label(), plain);
                    append("  " + f.getEntry().getScope(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                case 3 -> {
                    List<ClassUsages.Use> uses = !f.getMainUses().isEmpty() ? f.getMainUses() : f.getTestUses();
                    if (uses.isEmpty()) {
                        append(f.getNote() != null ? f.getNote() : "keine Nennung", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    } else {
                        int n = f.getMainCount() + f.getTestCount();
                        append(uses.get(0).getClassName(), plain);
                        if (n > 1) {
                            append(" +" + (n - 1), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        }
                    }
                }
                default -> {
                    List<FixAction> fixes = f.fixes();
                    append(fixes.isEmpty() ? "–" : fixes.get(0).getLabel(), plain);
                }
            }
            setToolTipText(f.message());
        }
    }
}
