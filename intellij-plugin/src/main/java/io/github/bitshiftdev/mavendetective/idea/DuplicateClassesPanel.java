package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
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
 * Tab „Doppelte Klassen“ (Modul A3): je Modul die JARs, die dieselben Klassen mitbringen. Unten
 * Klassen, Pfade und die Empfehlung; der Knopf fügt die Exclusion bei jeder direkten Dependency
 * ein, über die das gewählte JAR kommt (ist es direkt deklariert, springt er zur Deklaration).
 */
public final class DuplicateClassesPanel extends DetectivePanel {

    static final String TAB_NAME = MavenDetectiveBundle.message("tab.duplicates");

    private static final int MAX_CLASSES_SHOWN = 200;

    private boolean withTests;
    private boolean withIdentical = true;
    private final SearchTextField search;
    private final ClashModel model = new ClashModel();
    private final JBTable table = new JBTable(model);
    private final HtmlView details;
    private final ComboBox<ModuleClasspath.Entry> target = new ComboBox<>();
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("duplicates.exclude")));
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
                })));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                show(selected());
            }
        });
        TableSpeedSearch.installOn(table, (value, cell) -> value instanceof DuplicateClasses.Clash c && cell.column == 1 ? jars(c) : null);
        // Nicht automatisch prüfen: das öffnet alle JARs aller Module – erst auf Klick
        emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.not.checked"),
                MavenDetectiveBundle.message("empty.check.now"), this::reload);

        details = new HtmlView(project);
        target.setRenderer(SimpleListCellRenderer.create("", ModuleClasspath.Entry::label));
        target.addActionListener(e -> updateApply());
        apply.addActionListener(e -> applySelected());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(new JBLabel(MavenDetectiveBundle.message("duplicates.target")), target, apply), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Duplicates.split", 0.5f);
        splitter.setFirstComponent(rows(table, this::selectedLocation, () -> selected() == null ? null : selected().message()));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        show(null);
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
        DuplicateClasses.Clash before = selected();
        List<DuplicateClasses.Clash> rows = new ArrayList<>();
        if (result == null) {
            model.set(rows);
            emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
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
        model.set(rows);
        int unreadable = 0;
        for (DuplicateClasses.ModuleResult m : result.getModules()) {
            unreadable += m.getUnreadable().size();
        }
        int n = result.clashes().size();
        emptyText(table.getEmptyText(), MavenDetectiveBundle.message(n == 0 ? "duplicates.empty.none" : "empty.all.filtered"), null, null);
        setStatus(result.getModules().size() + " Module, " + n + (n == 1 ? " Fund" : " Funde")
                + (hidden > 0 ? " (" + hidden + " ausgeblendet)" : "")
                + (unreadable > 0 ? ", " + unreadable + " JARs lokal nicht lesbar" : "")
                + (result.isEstimated() ? " – ohne Baum aus den POMs nachgebaut (Maven-Projekt neu laden)" : ""));
        int idx = before == null ? -1 : rows.indexOf(before);
        if (idx >= 0) {
            table.getSelectionModel().setSelectionInterval(idx, idx);
        } else if (!rows.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        } else {
            show(null);
        }
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
        int r = table.getSelectedRow();
        return r < 0 || r >= model.rows.size() ? null : model.rows.get(table.convertRowIndexToModel(r));
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
                    + "„Prüfen“ klicken, um neu zu prüfen.</p>");
            apply.setEnabled(false);
        }
    }

    private void showText(String body) {
        details.show(body);
    }

    private static final class ClashModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Modul", "JARs (1. gewinnt)", "Klassen", "Inhalt", "Empfehlung"};
        private List<DuplicateClasses.Clash> rows = List.of();

        void set(List<DuplicateClasses.Clash> rows) {
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

    /** Verschiedene Fassungen im Laufzeit-Classpath hinterlegt; nur-Test grau. */
    private final class Renderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus, int row, int column) {
            if (!(value instanceof DuplicateClasses.Clash c)) {
                return;
            }
            boolean risky = c.isMain() && !c.isIdentical();
            if (risky && !selected) {
                setBackground(DetectiveColors.ERROR_BACKGROUND);
            }
            SimpleTextAttributes plain = c.isMain() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES;
            switch (column) {
                case 0 -> append(c.getModule().getName(), plain);
                case 1 -> append(jars(c), risky ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : plain);
                case 2 -> {
                    append(String.valueOf(c.getClasses().size()), plain);
                    List<String> p = c.packages();
                    append("  " + p.get(0) + (p.size() > 1 ? " +" + (p.size() - 1) : ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                case 3 -> append(c.isIdentical() ? "gleich" : c.getDiffering() + " verschieden", plain);
                default -> append(c.getDrop() == null ? "Entscheidung nötig" : c.getDrop().label() + " raus",
                        c.getDrop() == null ? SimpleTextAttributes.GRAYED_ATTRIBUTES : plain);
            }
            if (!c.isMain()) {
                append(column == 0 ? "  nur Tests" : "", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setToolTipText(c.message());
        }
    }
}
