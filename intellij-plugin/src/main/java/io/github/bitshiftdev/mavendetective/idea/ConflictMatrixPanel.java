package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Html;
import io.github.bitshiftdev.mavendetective.idea.ui.HtmlView;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Seite „Konflikte“ im Tab „Dependencies“ (Modul A2): Zeile = Artifact, Spalte = Modul, Zelle = aufgelöste Version.
 * Abweichende Zellen sind markiert. Unten der Vorschlag, die Version im gemeinsamen Parent unter
 * {@code dependencyManagement} festzulegen, samt den Stellen, die das überstimmen würden.
 * Doppelklick (oder Enter) auf eine Zelle öffnet „Warum diese Version?“ für Modul und Artifact, das Kontextmenü legt
 * die Version der Zelle fest. Die Artifact-Spalte bleibt beim waagrechten Scrollen stehen; je Version hat eine Zeile
 * eine eigene Farbe, damit Gruppen auffallen (die Version steht immer als Text da).
 */
public final class ConflictMatrixPanel extends DetectivePanel {

    private static final int HEADER_WIDTH = 320;

    private boolean onlyDivergent = true;
    private final SearchTextField search;
    private final MatrixModel model = new MatrixModel();
    /** Module als Spalten; die Artifact-Spalte steht fixiert im {@link #header}. */
    private final JBTable table = new JBTable(model) {
        @Override
        public void createDefaultColumnsFromModel() {
            super.createDefaultColumnsFromModel();
            if (getColumnModel().getColumnCount() > 0) {
                removeColumn(getColumnModel().getColumn(0));
            }
        }
    };
    /** Erste Spalte (Artifact) als Zeilenkopf: bleibt beim waagrechten Scrollen stehen. */
    private final JBTable header = new JBTable(model) {
        @Override
        public void createDefaultColumnsFromModel() {
            super.createDefaultColumnsFromModel();
            while (getColumnModel().getColumnCount() > 1) {
                removeColumn(getColumnModel().getColumn(1));
            }
        }
    };
    private final HtmlView details;
    private final ComboBox<String> versions = new ComboBox<>();
    private final JButton pin = FixBar.primary(new JButton(MavenDetectiveBundle.message("matrix.pin")));
    private final Background loader = background(MavenDetectiveBundle.message("loading.modules"));
    private ConflictMatrix matrix;
    /** Zeile, die nach dem (nächsten) Laden gewählt werden soll – Sprung aus dem Editor. */
    private @Nullable String pendingSelect;

    public ConflictMatrixPanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.artifacts"), this::applyFilter);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(filterGroup(toggle(MavenDetectiveBundle.message("matrix.filter.divergent"), null,
                () -> onlyDivergent, v -> {
                    onlyDivergent = v;
                    applyFilter();
                })));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(null, actions, search);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCellSelectionEnabled(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        header.setSelectionModel(table.getSelectionModel());
        header.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        header.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        header.setDefaultRenderer(Object.class, new CellRenderer());
        header.setPreferredScrollableViewportSize(new Dimension(JBUI.scale(HEADER_WIDTH), 1));
        header.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("matrix.column.artifact"));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showRow(selectedRow());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectUnderMouse(table, e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int r = table.rowAtPoint(e.getPoint());
                    int c = table.columnAtPoint(e.getPoint());
                    if (r >= 0 && c >= 0) {
                        whyThisVersion(model.rows.get(table.convertRowIndexToModel(r)), model.modules.get(table.convertColumnIndexToModel(c) - 1));
                    }
                }
            }
        });
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectUnderMouse(header, e);
                // Klick auf den Artifact-Namen: keine Modul-Spalte gemeint
                table.getColumnModel().getSelectionModel().clearSelection();
            }
        });
        // Enter auf einer Zelle: „Warum diese Version?“ für dieses Modul (Doppelklick per Tastatur)
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "md.why");
        table.getActionMap().put("md.why", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ConflictMatrix.Row row = selectedRow();
                ConflictMatrix.Module m = selectedModule();
                if (row != null && m != null) {
                    whyThisVersion(row, m);
                }
            }
        });
        TableSpeedSearch.installOn(header, (value, cell) -> value instanceof ConflictMatrix.Row r ? r.ga() : null);
        TableSpeedSearch.installOn(table, (value, cell) -> value instanceof ConflictMatrix.Cell c ? c.getVersion() : null);
        emptyText(table.getEmptyText(), MavenDetectiveBundle.message("loading.modules"), null, null);

        details = new HtmlView(project);
        pin.addActionListener(e -> pinSelected());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(new JBLabel(MavenDetectiveBundle.message("matrix.version")), versions, pin), BorderLayout.SOUTH);
        showRow(null);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Matrix.split", 0.6f);
        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setRowHeaderView(header);
        scroll.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, header.getTableHeader());
        // Artifact-Spalte breiter ziehen: der fixierte Bereich wächst mit
        header.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnMarginChanged(ChangeEvent e) {
                header.setPreferredScrollableViewportSize(new Dimension(header.getColumnModel().getTotalColumnWidth(), 1));
                scroll.revalidate();
            }

            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }
        });
        splitter.setFirstComponent(rows(scroll, List.of(table, header), this::cellActions, this::selectedLocation, this::selectedText));
        splitter.setSecondComponent(bottom);
        setBody(splitter);

        AnalysisResults.getInstance(project).addListener(new AnalysisResults.Listener() {
            @Override
            public void changed(@NotNull AnalysisResults.Analysis analysis, @NotNull AnalysisResults.Summary summary) {
            }

            /** Günstig: nach jedem Import von selbst neu rechnen, statt alte Zahlen stehen zu lassen. */
            @Override
            public void mavenImported() {
                reload();
            }
        }, this);
        reload();
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
    public boolean canCopyMarkdown() {
        return matrix != null;
    }

    @Override
    public @Nullable String markdown() {
        return matrix == null ? null : matrix.markdown(!onlyDivergent);
    }

    private void reload() {
        DependenciesPanel.saveUnsavedPoms();
        AnalysisResults results = AnalysisResults.getInstance(project);
        results.running(AnalysisResults.Analysis.CONFLICTS);
        loader.run(c -> compute(project), m -> {
            matrix = m;
            applyFilter();
            results.set(AnalysisResults.Analysis.CONFLICTS, summary(m));
            PomInsights.getInstance(project).setConflicts(m);
        });
    }

    @Override
    protected void aborted() {
        AnalysisResults.getInstance(project).aborted(AnalysisResults.Analysis.CONFLICTS);
    }

    private static AnalysisResults.Summary summary(@Nullable ConflictMatrix m) {
        if (m == null) {
            return AnalysisResults.Summary.NOT_CHECKED;
        }
        int n = m.divergent().size();
        return AnalysisResults.Summary.done(n, MavenDetectiveBundle.message(m.isPartial() ? "summary.conflicts.partial" : "summary.conflicts",
                n, m.getModules().size()));
    }

    /** Matrix aus dem IDE-Modell berechnen (Hintergrund-Thread; liest nur Dateien). */
    static @Nullable ConflictMatrix compute(Project project) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = DependenciesPanel.locator(project, root);
        List<PomFile> poms = new ArrayList<>();
        for (IdeMaven.ModuleRef ref : IdeMaven.modules(project)) {
            PomFile p = locator.load(ref.pom());
            if (p != null) {
                poms.add(p);
            }
        }
        if (poms.isEmpty() && root != null) {
            // Kein Maven-Import: wenigstens die POMs auf der Platte auswerten
            for (ProjectPoms.Pom p : new ProjectPoms(root).all()) {
                PomFile f = locator.load(p.file);
                if (f != null) {
                    poms.add(f);
                }
            }
        }
        if (poms.isEmpty()) {
            return null;
        }
        Map<String, DepNode> trees = IdeMaven.dependencyTrees(project);
        return ConflictMatrix.build(locator, root, poms, trees);
    }

    /** Sprung aus dem Editor (Code Vision): Zeile {@code g:a} wählen, notfalls nach dem Laden. */
    void select(@NotNull String ga) {
        pendingSelect = ga;
        if (!search.getText().isEmpty()) {
            search.setText("");
        }
        applyFilter();
    }

    private void applyFilter() {
        ConflictMatrix.Row before = selectedRow();
        if (pendingSelect != null && matrix != null) {
            for (ConflictMatrix.Row r : matrix.getRows()) {
                if (r.ga().equals(pendingSelect)) {
                    before = r;
                    onlyDivergent &= r.isDivergent();
                }
            }
            pendingSelect = null;
        }
        List<ConflictMatrix.Row> rows = new ArrayList<>();
        if (matrix == null) {
            model.set(List.of(), rows);
            setStatus(null);
            intro(table.getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            showRow(null);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        for (ConflictMatrix.Row r : matrix.getRows()) {
            if ((!onlyDivergent || r.isDivergent()) && (q.isEmpty() || r.ga().toLowerCase(Locale.ROOT).contains(q))) {
                rows.add(r);
            }
        }
        model.set(matrix.getModules(), rows);
        if (header.getColumnCount() > 0) {
            header.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(HEADER_WIDTH));
        }
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(130));
        }
        int divergent = matrix.divergent().size();
        if (onlyDivergent && matrix.divergent().isEmpty()) {
            intro(table.getEmptyText(), MavenDetectiveBundle.message("matrix.empty.no.divergence"),
                    MavenDetectiveBundle.message("matrix.empty.show.all"), () -> {
                        onlyDivergent = false;
                        applyFilter();
                    });
        } else {
            emptyText(table.getEmptyText(), MavenDetectiveBundle.message("empty.no.match"), null, null);
        }
        setStatus(MavenDetectiveBundle.message("matrix.status", matrix.getModules().size(), divergent)
                + (matrix.isPartial() ? " – " + MavenDetectiveBundle.message("matrix.status.partial") : ""));
        int idx = before == null ? -1 : rows.indexOf(before);
        if (idx < 0) {
            for (int i = 0; i < rows.size(); i++) {
                if (before != null && rows.get(i).ga().equals(before.ga())) {
                    idx = i;
                }
            }
        }
        if (idx >= 0) {
            table.getSelectionModel().setSelectionInterval(idx, idx);
            table.scrollRectToVisible(table.getCellRect(idx, 0, true));
        } else {
            showRow(null);
        }
    }

    private @Nullable ConflictMatrix.Row selectedRow() {
        int r = table.getSelectedRow();
        return r < 0 || r >= model.rows.size() ? null : model.rows.get(table.convertRowIndexToModel(r));
    }

    private void showRow(@Nullable ConflictMatrix.Row row) {
        versions.removeAllItems();
        pin.setEnabled(false);
        if (row == null) {
            pin.setText(MavenDetectiveBundle.message("matrix.pin"));
            showText("<p>" + MavenDetectiveBundle.message(matrix != null && matrix.divergent().isEmpty() && onlyDivergent
                    ? "matrix.detail.none" : "matrix.detail.select") + "</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(row.ga())).append("</h3>");
        if (!row.isDivergent()) {
            sb.append("<p>").append(esc(MavenDetectiveBundle.message("matrix.detail.same", row.versions().get(0)))).append("</p>");
        }
        sb.append("<ul>");
        for (Map.Entry<String, List<ConflictMatrix.Module>> e : row.modulesByVersion().entrySet()) {
            sb.append("<li><b>").append(esc(e.getKey())).append("</b>: ");
            boolean first = true;
            for (ConflictMatrix.Module m : e.getValue()) {
                ConflictMatrix.Cell c = row.cell(m);
                sb.append(first ? "" : ", ").append(esc(m.getName())).append(" (");
                if (c.getFile() != null) {
                    sb.append("<a href=\"").append(esc(c.getFile() + ":" + c.getLine())).append("\">").append(esc(c.sourceLabel())).append("</a>");
                } else {
                    sb.append(esc(c.sourceLabel()));
                }
                if (c.getScope() != null && !"compile".equals(c.getScope())) {
                    sb.append(", ").append(esc(c.getScope()));
                }
                sb.append(')');
                first = false;
            }
            sb.append("</li>");
        }
        sb.append("</ul>");
        ConflictMatrix.Pin p = row.getPin();
        if (p != null) {
            String parent = "<a href=\"" + esc(p.getTarget().getFile().toString()) + "\">" + esc(p.getTarget().getArtifactId()) + "</a>";
            sb.append("<p><b>").append(MavenDetectiveBundle.message("detail.suggestion")).append("</b> ")
                    .append(MavenDetectiveBundle.message(p.getExisting() != null ? "matrix.detail.pin.existing" : "matrix.detail.pin",
                            esc(p.getVersion()), parent, p.getExisting() == null ? "" : esc(p.getExisting())))
                    .append("</p>");
            if (!p.getBlockers().isEmpty()) {
                sb.append("<p><b>").append(MavenDetectiveBundle.message("matrix.detail.blockers")).append("</b></p><ul>");
                for (ConflictMatrix.Blocker b : p.getBlockers()) {
                    sb.append("<li>").append(esc(b.getText())).append(" – ").append(Html.pomLink(b.getFile(), b.getLine())).append("</li>");
                }
                sb.append("</ul>");
            }
            List<String> vs = row.versions();
            for (int i = vs.size() - 1; i >= 0; i--) {
                versions.addItem(vs.get(i));
            }
            versions.setSelectedItem(p.getVersion());
            pin.setText(MavenDetectiveBundle.message("matrix.pin.in", p.getTarget().getArtifactId()));
            pin.setEnabled(true);
        } else if (row.getPinProblem() != null) {
            sb.append("<p>").append(esc(row.getPinProblem())).append("</p>");
        }
        showText(sb.toString());
    }

    private void pinSelected() {
        ConflictMatrix.Row row = selectedRow();
        String version = (String) versions.getSelectedItem();
        if (row != null && version != null) {
            pin(row, version);
        }
    }

    /** Version im gemeinsamen Parent festlegen (mit Vorschau). */
    private void pin(ConflictMatrix.Row row, String version) {
        if (row.getPin() == null) {
            return;
        }
        if (FixRunner.pinVersion(project, row.getPin().fix(row.getGroupId(), row.getArtifactId(), version))) {
            showText("<p>" + esc(MavenDetectiveBundle.message("matrix.pinned", version, row.ga())) + "</p>");
            pin.setEnabled(false);
        }
    }

    /** Kontextmenü auf einer Zelle: diese Version (und den Vorschlag) festlegen, „Warum diese Version?“ für das Modul. */
    private List<AnAction> cellActions() {
        ConflictMatrix.Row row = selectedRow();
        if (row == null) {
            return List.of();
        }
        List<AnAction> out = new ArrayList<>();
        ConflictMatrix.Module m = selectedModule();
        ConflictMatrix.Cell c = m == null ? null : row.cell(m);
        ConflictMatrix.Pin p = row.getPin();
        if (p != null) {
            String target = p.getTarget().getArtifactId();
            if (c != null) {
                String v = c.getVersion();
                out.add(DumbAwareAction.create(MavenDetectiveBundle.message("matrix.pin.version", v, target), AllIcons.General.Pin_tab,
                        e -> pin(row, v)));
            }
            if (c == null || !c.getVersion().equals(p.getVersion())) {
                out.add(DumbAwareAction.create(MavenDetectiveBundle.message("matrix.pin.suggested", p.getVersion(), target),
                        AllIcons.General.Pin_tab, e -> pin(row, p.getVersion())));
            }
        }
        if (c != null) {
            out.add(DumbAwareAction.create(MavenDetectiveBundle.message("matrix.why.module", m.getName()), AllIcons.Actions.Help,
                    e -> whyThisVersion(row, m)));
        }
        return out;
    }

    /** Rechtsklick wählt die Zelle unter der Maus, damit das Kontextmenü zu ihr passt. */
    private static void selectUnderMouse(JTable t, MouseEvent e) {
        if (!SwingUtilities.isRightMouseButton(e)) {
            return;
        }
        int r = t.rowAtPoint(e.getPoint());
        int c = t.columnAtPoint(e.getPoint());
        if (r >= 0 && c >= 0) {
            t.changeSelection(r, c, false, false);
        }
    }

    /** Modul der gewählten Zelle; {@code null}, wenn nur die Zeile (Artifact) gewählt ist. */
    private @Nullable ConflictMatrix.Module selectedModule() {
        int col = table.getSelectedColumn();
        if (col < 0 || table.getColumnModel().getSelectionModel().isSelectionEmpty()) {
            return null;
        }
        int m = table.convertColumnIndexToModel(col) - 1;
        return m >= 0 && m < model.modules.size() ? model.modules.get(m) : null;
    }

    private void whyThisVersion(ConflictMatrix.Row row, ConflictMatrix.Module module) {
        if (row.cell(module) == null) {
            return;
        }
        Navigator.open(project, DependenciesPanel.class, panel -> panel.show(module.getPom().getFile(), row.getGroupId(), row.getArtifactId()));
    }

    private void showText(String body) {
        details.show(body);
    }

    /** F4: Zelle (Spalte gewählt) bzw. Ziel-POM des Vorschlags. */
    private @Nullable String selectedLocation() {
        ConflictMatrix.Row row = selectedRow();
        if (row == null) {
            return null;
        }
        ConflictMatrix.Module m = selectedModule();
        ConflictMatrix.Cell c = m == null ? null : row.cell(m);
        if (c != null && c.getFile() != null) {
            return Locations.of(c.getFile(), c.getLine());
        }
        return row.getPin() == null ? null : row.getPin().getTarget().getFile().toString();
    }

    private @Nullable String selectedText() {
        ConflictMatrix.Row row = selectedRow();
        return row == null ? null : row.ga() + " " + String.join(", ", row.versions());
    }

    /** Spalte 0 = Artifact, danach je Modul eine Spalte. */
    private static final class MatrixModel extends AbstractTableModel {
        private List<ConflictMatrix.Module> modules = List.of();
        private List<ConflictMatrix.Row> rows = List.of();

        void set(List<ConflictMatrix.Module> modules, List<ConflictMatrix.Row> rows) {
            boolean structure = !modules.equals(this.modules);
            this.modules = modules;
            this.rows = rows;
            if (structure) {
                fireTableStructureChanged();
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return modules.size() + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? MavenDetectiveBundle.message("matrix.column.artifact") : modules.get(column - 1).getName();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ConflictMatrix.Row r = rows.get(rowIndex);
            return columnIndex == 0 ? r : r.cell(modules.get(columnIndex - 1));
        }
    }

    /** Abweichende Versionen hinterlegt, die vorgeschlagene normal; Herkunft klein dahinter. */
    private final class CellRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus, int row, int column) {
            ConflictMatrix.Row r = model.rows.get(t.convertRowIndexToModel(row));
            if (value instanceof ConflictMatrix.Row) {
                append(r.ga(), r.isDivergent() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (r.isDivergent()) {
                    append("  " + MavenDetectiveBundle.message("matrix.versions", r.versions().size()), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                return;
            }
            if (!(value instanceof ConflictMatrix.Cell c)) {
                append("–", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            String suggested = r.getPin() != null ? r.getPin().getVersion() : MavenVersion.max(r.versions());
            boolean deviates = r.isDivergent() && !c.getVersion().equals(suggested);
            List<String> vs = r.versions();
            int group = vs.indexOf(c.getVersion());
            if (r.isDivergent() && !selected) {
                // Gleiche Version, gleiche Farbe: Gruppen auf einen Blick; die Zahl steht trotzdem da
                setBackground(DetectiveColors.versionBackground(group));
            }
            append(c.getVersion(), deviates ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            // Auf eingefärbter Zelle reicht das übliche Grau nicht für 4.5:1 – eigene, dunklere Farbe
            append("  " + shortSource(c), r.isDivergent() && !selected
                    ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, DetectiveColors.TINTED_SECONDARY)
                    : SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setToolTipText((r.isDivergent() ? MavenDetectiveBundle.message("matrix.cell.group", group + 1, vs.size()) + " · " : "")
                    + c.sourceLabel() + (c.getScope() != null && !"compile".equals(c.getScope()) ? ", " + c.getScope() : "")
                    + (c.getFile() == null ? "" : " – " + c.getFile() + ":" + c.getLine()) + " · "
                    + MavenDetectiveBundle.message("matrix.cell.hint"));
        }

        private static String shortSource(ConflictMatrix.Cell c) {
            return switch (c.getSource()) {
                case DIRECT -> MavenDetectiveBundle.message("upgrade.editor.direct");
                case MANAGED -> MavenDetectiveBundle.message("matrix.source.managed");
                case BOM -> "BOM";
                case TRANSITIVE -> MavenDetectiveBundle.message("matrix.source.transitive");
                default -> "";
            };
        }
    }

    @Override
    protected @Nls @Nullable String about() {
        return MavenDetectiveBundle.message("matrix.about");
    }
}
