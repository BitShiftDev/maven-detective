package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.ConflictMatrix;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab „Konflikt-Matrix“ (Modul A2): Zeile = Artifact, Spalte = Modul, Zelle = aufgelöste Version.
 * Abweichende Zellen sind markiert. Unten der Vorschlag, die Version im gemeinsamen Parent unter
 * {@code dependencyManagement} festzulegen, samt den Stellen, die das überstimmen würden.
 * Doppelklick auf eine Zelle öffnet „Warum diese Version?“ für Modul und Artifact.
 */
public final class ConflictMatrixPanel extends JPanel implements Disposable {

    static final String TAB_NAME = "Konflikt-Matrix";

    private static final JBColor DEVIATION = new JBColor(0xFFF1D6, 0x4D3B1F);

    private final Project project;
    private final JBCheckBox onlyDivergent = new JBCheckBox("Nur Abweichungen", true);
    private final SearchTextField search = new SearchTextField(false);
    private final JButton copy = new JButton("Als Markdown kopieren");
    private final JLabel status = new JLabel();
    private final MatrixModel model = new MatrixModel();
    private final JBTable table = new JBTable(model);
    private final JEditorPane details = new JEditorPane("text/html", "");
    private final ComboBox<String> versions = new ComboBox<>();
    private final JButton pin = new JButton("Im Parent festlegen");
    private ConflictMatrix matrix;

    public ConflictMatrixPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Aktualisieren");
        refresh.setToolTipText("Module und Bäume neu aus dem Maven-Modell der IDE lesen (Stand des letzten Imports)");
        refresh.addActionListener(e -> reload());
        onlyDivergent.addActionListener(e -> applyFilter());
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                applyFilter();
            }
        });
        copy.setEnabled(false);
        copy.addActionListener(e -> {
            if (matrix != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(matrix.markdown(!onlyDivergent.isSelected())));
            }
        });
        toolbar.add(refresh);
        toolbar.add(onlyDivergent);
        toolbar.add(search);
        toolbar.add(copy);
        toolbar.add(status);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showRow(selectedRow());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = table.rowAtPoint(e.getPoint());
                    int c = table.columnAtPoint(e.getPoint());
                    if (r >= 0 && c >= 1) {
                        whyThisVersion(model.rows.get(table.convertRowIndexToModel(r)), model.modules.get(table.convertColumnIndexToModel(c) - 1));
                    }
                }
            }
        });

        details.setEditable(false);
        details.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                FixRunner.openLocation(project, e.getDescription());
            }
        });
        pin.addActionListener(e -> pinSelected());
        JPanel fixBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fixBar.add(new JLabel("Version:"));
        fixBar.add(versions);
        fixBar.add(pin);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JBScrollPane(details), BorderLayout.CENTER);
        bottom.add(fixBar, BorderLayout.SOUTH);
        showRow(null);

        JBSplitter splitter = new JBSplitter(true, 0.6f);
        splitter.setFirstComponent(new JBScrollPane(table));
        splitter.setSecondComponent(bottom);
        add(toolbar, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        reload();
    }

    private void reload() {
        status.setText("Lese Module …");
        FileDocumentManager.getInstance().saveAllDocuments();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ConflictMatrix m = compute(project);
            ApplicationManager.getApplication().invokeLater(() -> {
                matrix = m;
                copy.setEnabled(m != null);
                applyFilter();
            }, ModalityState.any(), project.getDisposed());
        });
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

    private void applyFilter() {
        ConflictMatrix.Row before = selectedRow();
        List<ConflictMatrix.Row> rows = new ArrayList<>();
        if (matrix == null) {
            model.set(List.of(), rows);
            status.setText("Kein Maven-Projekt gefunden.");
            showRow(null);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        for (ConflictMatrix.Row r : matrix.getRows()) {
            if ((!onlyDivergent.isSelected() || r.isDivergent()) && (q.isEmpty() || r.ga().toLowerCase(Locale.ROOT).contains(q))) {
                rows.add(r);
            }
        }
        model.set(matrix.getModules(), rows);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        for (int i = 1; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(130);
        }
        int divergent = matrix.divergent().size();
        status.setText(matrix.getModules().size() + " Module, " + divergent + (divergent == 1 ? " Abweichung" : " Abweichungen")
                + (matrix.isPartial() ? " – ohne Baum nur direkte Dependencies (Maven-Projekt neu laden)" : ""));
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
            pin.setText("Im Parent festlegen");
            showText(matrix != null && matrix.divergent().isEmpty() && onlyDivergent.isSelected()
                    ? "<p>Alle Module nutzen jeweils dieselbe Version. „Nur Abweichungen“ abwählen, um alle Artifacts zu sehen.</p>"
                    : "<p>Zeile wählen, um Versionen, Herkunft und den Vorschlag zu sehen. Doppelklick auf eine Zelle zeigt "
                    + "„Warum diese Version?“ für dieses Modul.</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(row.ga())).append("</h3>");
        if (!row.isDivergent()) {
            sb.append("<p>Alle Module nutzen ").append(esc(row.versions().get(0))).append(".</p>");
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
            sb.append("<p><b>Vorschlag:</b> ").append(esc(p.getVersion())).append(" im gemeinsamen Parent <a href=\"")
                    .append(esc(p.getTarget().getFile().toString())).append("\">").append(esc(p.getTarget().getArtifactId()))
                    .append("</a> unter <code>dependencyManagement</code> festlegen");
            if (p.getExisting() != null) {
                sb.append(" – dort steht bisher ").append(esc(p.getExisting()));
            }
            sb.append(". Das gilt dann auch für transitive Vorkommen. Vorgeschlagen ist die höchste Version; Maven selbst nähme "
                    + "die nächste im Baum.</p>");
            if (!p.getBlockers().isEmpty()) {
                sb.append("<p><b>Überstimmt den Pin danach noch:</b></p><ul>");
                for (ConflictMatrix.Blocker b : p.getBlockers()) {
                    sb.append("<li>").append(esc(b.getText())).append(" – <a href=\"").append(esc(b.getFile() + ":" + b.getLine())).append("\">")
                            .append(esc(Paths.get(b.getFile()).getParent().getFileName() + "/" + Paths.get(b.getFile()).getFileName()
                                    + ":" + b.getLine())).append("</a></li>");
                }
                sb.append("</ul>");
            }
            List<String> vs = row.versions();
            for (int i = vs.size() - 1; i >= 0; i--) {
                versions.addItem(vs.get(i));
            }
            versions.setSelectedItem(p.getVersion());
            pin.setText("In " + p.getTarget().getArtifactId() + " festlegen");
            pin.setEnabled(true);
        } else if (row.getPinProblem() != null) {
            sb.append("<p>").append(esc(row.getPinProblem())).append("</p>");
        }
        showText(sb.toString());
    }

    private void pinSelected() {
        ConflictMatrix.Row row = selectedRow();
        String version = (String) versions.getSelectedItem();
        if (row == null || row.getPin() == null || version == null) {
            return;
        }
        FixRunner.pinVersion(project, row.getPin().fix(row.getGroupId(), row.getArtifactId(), version));
        showText("<p>Version " + esc(version) + " für " + esc(row.ga()) + " eingetragen. Nach dem Maven-Import "
                + "„Aktualisieren“ klicken, um die Matrix neu zu berechnen.</p>");
        pin.setEnabled(false);
    }

    private void whyThisVersion(ConflictMatrix.Row row, ConflictMatrix.Module module) {
        if (row.cell(module) == null) {
            return;
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(BuildDoctorService.TOOL_WINDOW_ID);
        if (window == null) {
            return;
        }
        Content content = window.getContentManager().findContent(DependenciesPanel.TAB_NAME);
        if (content != null && content.getComponent() instanceof DependenciesPanel panel) {
            window.getContentManager().setSelectedContent(content);
            panel.show(module.getPom().getFile(), row.getGroupId(), row.getArtifactId());
        }
    }

    private void showText(String body) {
        details.setText("<html><body>" + body + "</body></html>");
        details.setCaretPosition(0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void dispose() {
        // nichts zu lösen; Hintergrund-Aufgaben prüfen project.getDisposed()
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
            return column == 0 ? "Artifact" : modules.get(column - 1).getName();
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
                    append("  " + r.versions().size() + " Versionen", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                return;
            }
            if (!(value instanceof ConflictMatrix.Cell c)) {
                append("–", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            String suggested = r.getPin() != null ? r.getPin().getVersion() : MavenVersion.max(r.versions());
            boolean deviates = r.isDivergent() && !c.getVersion().equals(suggested);
            if (deviates && !selected) {
                setBackground(DEVIATION);
            }
            append(c.getVersion(), deviates ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("  " + shortSource(c), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            setToolTipText(c.sourceLabel() + (c.getScope() != null && !"compile".equals(c.getScope()) ? ", " + c.getScope() : "")
                    + (c.getFile() == null ? "" : " – " + c.getFile() + ":" + c.getLine()) + " · Doppelklick: Warum diese Version?");
        }

        private static String shortSource(ConflictMatrix.Cell c) {
            return switch (c.getSource()) {
                case DIRECT -> "direkt";
                case MANAGED -> "verwaltet";
                case BOM -> "BOM";
                case TRANSITIVE -> "transitiv";
                default -> "";
            };
        }
    }
}
