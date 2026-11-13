package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.DuplicateClasses;
import io.github.bitshiftdev.mavendetective.core.deps.JarIndex;
import io.github.bitshiftdev.mavendetective.core.deps.ModuleClasspath;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab „Doppelte Klassen“ (Modul A3): je Modul die JARs, die dieselben Klassen mitbringen. Unten
 * Klassen, Pfade und die Empfehlung; der Knopf fügt die Exclusion bei jeder direkten Dependency
 * ein, über die das gewählte JAR kommt (ist es direkt deklariert, springt er zur Deklaration).
 */
public final class DuplicateClassesPanel extends JPanel implements Disposable {

    static final String TAB_NAME = "Doppelte Klassen";

    private static final Logger LOG = Logger.getInstance(DuplicateClassesPanel.class);
    private static final JBColor DIFFERS = new JBColor(0xFFE4E1, 0x4D2B2B);
    private static final int MAX_CLASSES_SHOWN = 200;
    /** JAR-Inhalte über alle Aktualisierungen (mtime + Größe), damit nicht jedes Mal alle Archive geöffnet werden. */
    private static final JarIndex JARS = new JarIndex();

    private final Project project;
    private final JBCheckBox withTests = new JBCheckBox("Auch nur Tests", false);
    private final JBCheckBox withIdentical = new JBCheckBox("Auch inhaltsgleiche", true);
    private final SearchTextField search = new SearchTextField(false);
    private final JButton copy = new JButton("Als Markdown kopieren");
    private final JLabel status = new JLabel();
    private final ClashModel model = new ClashModel();
    private final JBTable table = new JBTable(model);
    private final JEditorPane details = new JEditorPane("text/html", "");
    private final ComboBox<ModuleClasspath.Entry> target = new ComboBox<>();
    private final JButton apply = new JButton("Ausschließen");
    private DuplicateClasses result;
    private int generation;
    private volatile boolean disposed;

    public DuplicateClassesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Prüfen");
        refresh.setToolTipText("Classpath jedes Moduls aus dem Maven-Modell der IDE lesen und die JARs nach gleichen Klassen durchsuchen");
        refresh.addActionListener(e -> reload());
        withTests.addActionListener(e -> applyFilter());
        withIdentical.addActionListener(e -> applyFilter());
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                applyFilter();
            }
        });
        search.setToolTipText("Filtert nach Modul, Artifact oder Klassenname");
        copy.setEnabled(false);
        copy.addActionListener(e -> {
            if (result != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(result.markdown(false)));
            }
        });
        toolbar.add(refresh);
        toolbar.add(withTests);
        toolbar.add(withIdentical);
        toolbar.add(search);
        toolbar.add(copy);
        toolbar.add(status);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                show(selected());
            }
        });

        details.setEditable(false);
        details.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                FixRunner.openLocation(project, e.getDescription());
            }
        });
        target.setRenderer(SimpleListCellRenderer.create("", ModuleClasspath.Entry::label));
        target.addActionListener(e -> updateApply());
        apply.addActionListener(e -> applySelected());
        JPanel fixBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fixBar.add(new JLabel("Aus dem Classpath nehmen:"));
        fixBar.add(target);
        fixBar.add(apply);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JBScrollPane(details), BorderLayout.CENTER);
        bottom.add(fixBar, BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, 0.5f);
        splitter.setFirstComponent(new JBScrollPane(table));
        splitter.setSecondComponent(bottom);
        add(toolbar, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        // Nicht automatisch prüfen: das öffnet alle JARs aller Module – erst auf Klick
        status.setText("„Prüfen“ klicken, um die Classpaths nach doppelten Klassen zu durchsuchen.");
        show(null);
    }

    private void reload() {
        status.setText("Lese Classpaths und JARs …");
        DependenciesPanel.saveUnsavedPoms();
        int gen = ++generation;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DuplicateClasses r;
            try {
                r = compute(project, () -> {
                    if (disposed || project.isDisposed() || gen != generation) {
                        throw new ProcessCanceledException();
                    }
                });
            } catch (ProcessCanceledException e) {
                return;
            } catch (RuntimeException e) {
                LOG.warn("Maven Detective: Prüfung auf doppelte Klassen fehlgeschlagen", e);
                r = null;
            }
            DuplicateClasses fin = r;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                result = fin;
                copy.setEnabled(fin != null);
                applyFilter();
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
        });
    }

    /** Hintergrund-Thread; liest nur Dateien. */
    static @Nullable DuplicateClasses compute(Project project, Cancellation cancellation) {
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
        return DuplicateClasses.build(locator, poms, trees, JARS, cancellation);
    }

    private void applyFilter() {
        DuplicateClasses.Clash before = selected();
        List<DuplicateClasses.Clash> rows = new ArrayList<>();
        if (result == null) {
            model.set(rows);
            status.setText("Kein Maven-Projekt gefunden.");
            show(null);
            return;
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int hidden = 0;
        for (DuplicateClasses.Clash c : result.clashes()) {
            boolean visible = (withTests.isSelected() || c.isMain()) && (withIdentical.isSelected() || !c.isIdentical());
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
        status.setText(result.getModules().size() + " Module, " + n + (n == 1 ? " Fund" : " Funde")
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
            apply.setText("Ausschließen");
            return;
        }
        List<FixAction> fixes = c.fixesFor(e);
        apply.setEnabled(!fixes.isEmpty());
        if (fixes.isEmpty()) {
            apply.setText("Ausschließen");
            apply.setToolTipText("Keine direkte Dependency im Projekt gefunden, über die " + e.ga() + " kommt");
        } else if (FixAction.OPEN_FILE.equals(fixes.get(0).getType())) {
            apply.setText("Zur Deklaration");
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
        details.setText("<html><body>" + body + "</body></html>");
        details.setCaretPosition(0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void dispose() {
        disposed = true;
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
                setBackground(DIFFERS);
            }
            SimpleTextAttributes plain = c.isMain() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES;
            switch (column) {
                case 0 -> append(c.getModule().getName(), plain);
                case 1 -> {
                    List<String> labels = new ArrayList<>();
                    for (ModuleClasspath.Entry e : c.getJars()) {
                        labels.add(e.label());
                    }
                    append(String.join(", ", labels), risky ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : plain);
                }
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
