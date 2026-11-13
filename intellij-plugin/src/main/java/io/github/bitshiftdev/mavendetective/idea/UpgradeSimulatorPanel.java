package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.EffectiveModel;
import io.github.bitshiftdev.mavendetective.core.deps.MavenVersion;
import io.github.bitshiftdev.mavendetective.core.deps.PomFetcher;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tab „Upgrade-Simulator“ (Modul A6): Artifact, BOM, Parent oder Property und Zielversion wählen,
 * „Simulieren“ zeigt je Modul, was sich im Baum ändert – bevor die POM angefasst wird. Oben die
 * Stellen, die geändert würden (mit Sprung), in der Tabelle die Unterschiede, darunter Pfade,
 * Hinweise und neue Konflikte. „Übernehmen“ schreibt genau diese Änderungen (ein Undo-Schritt),
 * „Fehlende POMs laden“ holt mit Maven, was für die neue Version lokal fehlt – der einzige
 * Netzzugriff in Modul A und nur auf Klick.
 */
public final class UpgradeSimulatorPanel extends JPanel implements Disposable {

    static final String TAB_NAME = "Upgrade-Simulator";

    private static final JBColor RISK = new JBColor(0xFDE2E1, 0x5A2A2A);

    private final Project project;
    private final ComboBox<String> artifact = new ComboBox<>();
    private final ComboBox<String> version = new ComboBox<>();
    private final JBCheckBox manage = new JBCheckBox("Im dependencyManagement festlegen");
    private final JButton simulate = new JButton("Simulieren");
    private final JButton fetch = new JButton("Fehlende POMs laden");
    private final JButton apply = new JButton("Übernehmen");
    private final JButton copy = new JButton("Als Markdown kopieren");
    private final JLabel status = new JLabel();
    private final DeltaModel model = new DeltaModel();
    private final JBTable table = new JBTable(model);
    private final JEditorPane details = new JEditorPane("text/html", "");
    /** Je {@code g:a} die heutige(n) Version(en) im Projekt – für Vorschläge und den Status. */
    private volatile Map<String, String> current = Map.of();
    private UpgradeSimulator.Result result;
    /** Fehlermeldung des letzten Ladens fehlender POMs. */
    private String fetchError;
    private int generation;
    private volatile boolean disposed;

    public UpgradeSimulatorPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        artifact.setEditable(true);
        artifact.setPrototypeDisplayValue("com.fasterxml.jackson.core:jackson-databind");
        artifact.setToolTipText("groupId:artifactId eines Artifacts, BOMs oder Parents – oder ${property}");
        artifact.addActionListener(e -> {
            if ("comboBoxChanged".equals(e.getActionCommand())) {
                loadVersions(null);
            }
        });
        version.setEditable(true);
        version.setPrototypeDisplayValue("2.17.10-SNAPSHOT");
        version.setToolTipText("Zielversion; vorgeschlagen werden Versionen, deren POM lokal liegt");
        manage.setToolTipText("Statt an der heutigen Stelle (Property, BOM, direkte Angabe) im dependencyManagement der obersten Projekt-POM festlegen");
        simulate.addActionListener(e -> simulate());
        fetch.addActionListener(e -> fetchMissing());
        apply.addActionListener(e -> applyResult());
        copy.addActionListener(e -> {
            if (result != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(result.markdown(true)));
            }
        });
        fetch.setEnabled(false);
        apply.setEnabled(false);
        copy.setEnabled(false);
        fetch.setToolTipText("Lädt die POMs, die für die neue Version lokal fehlen, mit Maven (dependency:collect) und simuliert erneut");

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(new JLabel("Artifact:"));
        toolbar.add(artifact);
        toolbar.add(new JLabel("Version:"));
        toolbar.add(version);
        toolbar.add(manage);
        toolbar.add(simulate);
        toolbar.add(status);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                render();
            }
        });
        details.setEditable(false);
        details.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                FixRunner.openLocation(project, e.getDescription());
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(apply);
        actions.add(fetch);
        actions.add(copy);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JBScrollPane(details), BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, 0.5f);
        splitter.setFirstComponent(new JBScrollPane(table));
        splitter.setSecondComponent(bottom);
        add(toolbar, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);
        showText("<p>Artifact (<code>groupId:artifactId</code>, auch BOM oder Parent) oder <code>${property}</code> und Zielversion "
                + "wählen, dann „Simulieren“. Die POMs bleiben unverändert, bis „Übernehmen“ geklickt wird.</p>"
                + "<p>Im Editor: Rechtsklick auf eine <code>&lt;dependency&gt;</code> oder den <code>&lt;parent&gt;</code> in der "
                + "<code>pom.xml</code> → „Upgrade simulieren…“.</p>");
        loadSuggestions();
    }

    /** Von der Editor-Aktion: Artifact vorbelegen, Versionen laden, bei lokal vorhandener neuerer Version gleich simulieren. */
    void show(@NotNull String groupId, @NotNull String artifactId) {
        artifact.setSelectedItem(groupId + ":" + artifactId);
        loadVersions(groupId + ":" + artifactId);
    }

    private void loadSuggestions() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Map<String, String> found = new TreeMap<>();
            Path root = IdeMaven.reactorRoot(project);
            PomLocator locator = DependenciesPanel.locator(project, root);
            for (DepNode tree : IdeMaven.dependencyTrees(project).values()) {
                collect(tree, found);
            }
            for (PomFile p : poms(project, locator, root)) {
                if (p.hasParent()) {
                    found.putIfAbsent(p.getParentGroupId() + ":" + p.getParentArtifactId(), p.getParentVersion());
                }
                for (EffectiveModel.ImportStep s : locator.model(p).getImports()) {
                    found.putIfAbsent(s.declaration.ga(), s.version.value);
                }
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                current = found;
                Object typed = artifact.getEditor().getItem();
                artifact.removeAllItems();
                for (String ga : found.keySet()) {
                    artifact.addItem(ga);
                }
                artifact.setSelectedItem(typed == null || typed.toString().isBlank() ? null : typed.toString());
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
        });
    }

    private static void collect(DepNode n, Map<String, String> out) {
        for (DepNode c : n.getChildren()) {
            if (c.getState() == DepNode.State.INCLUDED) {
                out.putIfAbsent(c.ga(), c.getVersion());
                collect(c, out);
            }
        }
    }

    /** Lokal vorhandene Versionen ins Feld; mit {@code autoSimulateFor} wird bei einer neueren gleich simuliert. */
    private void loadVersions(@Nullable String autoSimulateFor) {
        String ga = text(artifact);
        String[] p = ga.split(":");
        if (p.length != 2) {
            return;
        }
        String now = current.get(ga);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Path repo = DependenciesPanel.locator(project, IdeMaven.reactorRoot(project)).getLocalRepository();
            List<String> versions = UpgradeSimulator.localVersions(repo, p[0], p[1]);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!ga.equals(text(artifact))) {
                    return;
                }
                version.removeAllItems();
                for (String v : versions) {
                    version.addItem(v);
                }
                String newer = versions.isEmpty() || now == null || MavenVersion.compare(versions.get(0), now) <= 0 ? null : versions.get(0);
                version.setSelectedItem(newer);
                status.setText(now == null ? "" : "heute " + now + (newer == null ? " – keine neuere Version lokal, Zielversion eintippen" : ""));
                if (autoSimulateFor != null && newer != null) {
                    simulate();
                } else if (autoSimulateFor != null) {
                    version.requestFocusInWindow();
                }
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
        });
    }

    private @Nullable UpgradeSimulator.Request request() {
        String what = text(artifact);
        String v = text(version);
        if (what.isEmpty() || v.isEmpty()) {
            status.setText("Artifact und Version angeben.");
            return null;
        }
        UpgradeSimulator.Request r;
        try {
            r = what.startsWith("${") || !what.contains(":") ? UpgradeSimulator.Request.parse(what + "=" + v)
                    : UpgradeSimulator.Request.parse(what + ":" + v);
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return null;
        }
        return manage.isSelected() && !r.isProperty() ? r.pinned() : r;
    }

    private void simulate() {
        UpgradeSimulator.Request r = request();
        if (r == null) {
            return;
        }
        DependenciesPanel.saveUnsavedPoms();
        status.setText("Simuliere …");
        simulate.setEnabled(false);
        int gen = ++generation;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            UpgradeSimulator.Result res = compute(project, r);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (gen == generation) {
                    display(res, null);
                }
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
        });
    }

    /** Simulation über alle Module (Hintergrund-Thread; liest nur Dateien). */
    static @Nullable UpgradeSimulator.Result compute(Project project, UpgradeSimulator.Request request) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = DependenciesPanel.locator(project, root);
        List<PomFile> poms = poms(project, locator, root);
        if (poms.isEmpty()) {
            return null;
        }
        return UpgradeSimulator.simulate(locator, poms, request, IdeMaven.dependencyTrees(project), Cancellation.NONE);
    }

    private static List<PomFile> poms(Project project, PomLocator locator, @Nullable Path root) {
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

    /** Lädt fehlende POMs in bis zu vier Runden (die neue Version bringt oft weitere neue Versionen mit). */
    private void fetchMissing() {
        UpgradeSimulator.Result start = result;
        if (start == null || start.isComplete()) {
            return;
        }
        UpgradeSimulator.Request r = start.getRequest();
        fetch.setEnabled(false);
        simulate.setEnabled(false);
        int gen = ++generation;
        new Task.Backgroundable(project, "Maven Detective: fehlende POMs laden", true) {
            private UpgradeSimulator.Result last = start;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path root = IdeMaven.reactorRoot(project);
                String mvn = PomFetcher.executable(root, IdeMaven.mavenHome(project));
                BuildSetup setup = IdeMaven.ideSetup(project, null);
                Map<String, String> env = setup.getJavaHome() == null ? null : Map.of("JAVA_HOME", setup.getJavaHome());
                Path repo = DependenciesPanel.locator(project, root).getLocalRepository();
                for (int round = 1; round <= 4 && last != null && !last.isComplete(); round++) {
                    List<String> gavs = PomFetcher.coordinates(last.getMissing());
                    if (gavs.isEmpty()) {
                        break;
                    }
                    indicator.setText("Lade " + gavs.size() + (gavs.size() == 1 ? " POM" : " POMs") + " (Runde " + round + ") …");
                    List<Path> files = new ArrayList<>();
                    for (PomFile p : poms(project, DependenciesPanel.locator(project, root), root)) {
                        files.add(p.getFile());
                    }
                    PomFetcher.Outcome outcome;
                    try {
                        outcome = PomFetcher.fetch(mvn, root, repo, gavs, files, env, indicator::checkCanceled, 900);
                    } catch (java.io.IOException e) {
                        error = "Maven ließ sich nicht starten (" + mvn + "): " + e.getMessage();
                        break;
                    }
                    if (!outcome.isOk()) {
                        error = "Maven konnte nicht alles laden (Exit " + outcome.getExitCode() + "):\n" + String.join("\n", outcome.errors());
                        break;
                    }
                    indicator.setText("Simuliere erneut …");
                    UpgradeSimulator.Result next = compute(project, r);
                    boolean progress = next != null && !next.getMissing().equals(last.getMissing());
                    last = next;
                    if (!progress) {
                        break;
                    }
                }
            }

            @Override
            public void onFinished() {
                if (gen == generation && !disposed) {
                    display(last, error);
                } else {
                    simulate.setEnabled(true);
                }
            }
        }.queue();
    }

    private void applyResult() {
        if (result == null || result.getSteps().isEmpty()) {
            return;
        }
        int n = FixRunner.editPoms(project, result.fixes(), "Upgrade übernehmen (Maven Detective)");
        apply.setEnabled(false);
        fetch.setEnabled(false);
        status.setText(n == 0 ? "Nichts geändert." : "Übernommen – Maven-Projekt wird neu geladen.");
    }

    private void display(@Nullable UpgradeSimulator.Result res, @Nullable String error) {
        result = res;
        simulate.setEnabled(true);
        List<Row> rows = new ArrayList<>();
        if (res != null) {
            for (UpgradeSimulator.ModuleResult m : res.getModules()) {
                for (UpgradeSimulator.Delta d : m.getDeltas()) {
                    rows.add(new Row(m, d, null));
                }
                for (UpgradeSimulator.Risk k : m.getRisks()) {
                    rows.add(new Row(m, null, k));
                }
            }
        }
        model.set(rows);
        if (!rows.isEmpty()) {
            table.getColumnModel().getColumn(0).setPreferredWidth(120);
            table.getColumnModel().getColumn(1).setPreferredWidth(40);
            table.getColumnModel().getColumn(2).setPreferredWidth(320);
            table.getColumnModel().getColumn(5).setPreferredWidth(420);
        }
        apply.setEnabled(res != null && !res.getSteps().isEmpty());
        fetch.setEnabled(res != null && !PomFetcher.coordinates(res.getMissing()).isEmpty());
        copy.setEnabled(res != null);
        if (res == null) {
            status.setText("Kein Maven-Projekt gefunden.");
        } else {
            int changed = res.changed().size();
            status.setText(changed + (changed == 1 ? " Modul ändert sich" : " Module ändern sich")
                    + (res.isComplete() ? "" : " – unvollständig, POMs fehlen lokal"));
        }
        this.fetchError = error;
        render();
    }

    private void render() {
        UpgradeSimulator.Result res = result;
        if (res == null) {
            showText(fetchError == null ? "<p>Kein Maven-Projekt gefunden.</p>" : "<p>" + esc(fetchError) + "</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(res.headline())).append("</h3>");
        if (fetchError != null) {
            sb.append("<p style='color:#c62828'><b>").append(esc(fetchError).replace("\n", "<br>")).append("</b></p>");
        }
        if (!res.getSteps().isEmpty()) {
            sb.append("<p><b>Änderung an den POMs</b> (noch nicht angewendet):</p><ol>");
            for (UpgradeSimulator.Step s : res.getSteps()) {
                Path f = Paths.get(s.getFile());
                sb.append("<li>").append(esc(s.getDescription())).append(" – <a href=\"").append(esc(s.getFile() + ":" + s.getLine()))
                        .append("\">").append(esc(f.getParent().getFileName() + "/" + f.getFileName() + ":" + s.getLine())).append("</a>");
                if (s.getReason() != null) {
                    sb.append("<br><small>").append(esc(s.getReason())).append("</small>");
                }
                sb.append("</li>");
            }
            sb.append("</ol>");
        }
        for (String p : res.getProblems()) {
            sb.append("<p>⚠ ").append(esc(p)).append("</p>");
        }
        if (!res.isComplete()) {
            List<String> m = res.getMissing();
            sb.append("<p><b>Unvollständig:</b> lokal fehlen ").append(m.size()).append(m.size() == 1 ? " POM" : " POMs").append(" (")
                    .append(esc(String.join(", ", m.subList(0, Math.min(6, m.size()))))).append(m.size() > 6 ? ", …" : "")
                    .append("). Was darunter hängt, fehlt in der Simulation. „Fehlende POMs laden“ holt sie mit Maven.</p>");
        }
        Row row = selected();
        if (row != null) {
            sb.append("<hr>");
            if (row.delta != null) {
                UpgradeSimulator.Delta d = row.delta;
                sb.append("<p><b>").append(esc(d.label())).append("</b> in ").append(esc(row.module.getName())).append("</p><ul>");
                if (d.getBefore() != null) {
                    sb.append("<li>vorher: ").append(esc(path(d.getBefore()))).append("</li>");
                }
                if (d.getAfter() != null) {
                    sb.append("<li>nachher: ").append(esc(path(d.getAfter()))).append("</li>");
                }
                sb.append("</ul>");
            } else {
                sb.append("<p><b>Neuer Konflikt</b> in ").append(esc(row.module.getName())).append(": ").append(esc(row.risk.message()))
                        .append("</p><p>Pfad: ").append(esc(row.risk.getPath())).append("</p>");
            }
        }
        TreeSet<String> notes = new TreeSet<>();
        for (UpgradeSimulator.ModuleResult m : res.getModules()) {
            if (m.getNote() != null) {
                notes.add(m.getName() + ": " + m.getNote());
            }
            if (m.getDeviation() != null) {
                notes.add(m.getName() + ": " + m.getDeviation());
            }
        }
        if (!notes.isEmpty()) {
            sb.append("<p><b>Hinweise</b></p><ul>");
            for (String n : notes) {
                sb.append("<li>").append(esc(n)).append("</li>");
            }
            sb.append("</ul>");
        }
        if (res.changed().isEmpty() && res.getProblems().isEmpty()) {
            sb.append("<p>Kein Modul ändert sich.</p>");
        }
        showText(sb.toString());
    }

    private static String path(DepNode n) {
        return n.depth() <= 1 ? n.gav() + " (direkt" + (n.getScope() == null || "compile".equals(n.getScope()) ? "" : ", " + n.getScope()) + ")"
                : n.pathText() + (n.getScope() == null || "compile".equals(n.getScope()) ? "" : " (" + n.getScope() + ")");
    }

    private @Nullable Row selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= model.rows.size() ? null : model.rows.get(table.convertRowIndexToModel(r));
    }

    private void showText(String body) {
        details.setText("<html><body>" + body + "</body></html>");
        details.setCaretPosition(0);
    }

    private static String text(ComboBox<String> box) {
        Object item = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    /** Eine Tabellenzeile: Änderung im Baum oder neuer Konflikt. */
    private record Row(UpgradeSimulator.ModuleResult module, UpgradeSimulator.@Nullable Delta delta, UpgradeSimulator.@Nullable Risk risk) {
    }

    private static final class DeltaModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Modul", "", "Artifact", "vorher", "nachher", "über"};
        private List<Row> rows = List.of();

        void set(List<Row> rows) {
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
            Row r = rows.get(rowIndex);
            if (columnIndex == 0) {
                return r.module.getName();
            }
            if (r.risk != null) {
                return switch (columnIndex) {
                    case 1 -> "!";
                    case 2 -> r.risk.ga();
                    case 3 -> r.risk.getWanted() + " verlangt";
                    case 4 -> r.risk.getChosen() + " gewinnt";
                    default -> r.risk.getRequester();
                };
            }
            UpgradeSimulator.Delta d = r.delta;
            return switch (columnIndex) {
                case 1 -> switch (d.getKind()) {
                    case ADDED -> "+";
                    case REMOVED -> "−";
                    case UPGRADED -> "↑";
                    case DOWNGRADED -> "↓";
                    default -> "~";
                };
                case 2 -> d.label();
                case 3 -> d.getBefore() == null ? "–" : d.getBefore().getVersion() + scope(d.getBefore(), d.getKind());
                case 4 -> d.getAfter() == null ? "–" : d.getAfter().getVersion() + scope(d.getAfter(), d.getKind());
                default -> {
                    DepNode n = d.getAfter() != null ? d.getAfter() : d.getBefore();
                    yield n.depth() <= 1 ? "direkt" : n.getParent().gav();
                }
            };
        }

        private static String scope(DepNode n, UpgradeSimulator.Kind kind) {
            return kind == UpgradeSimulator.Kind.SCOPE || n.getScope() != null && !"compile".equals(n.getScope()) ? " (" + n.getScope() + ")" : "";
        }
    }

    /** Gesenkte Versionen und Konflikte hinterlegt, wegfallende grau. */
    private final class Renderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus, int row, int column) {
            Row r = model.rows.get(t.convertRowIndexToModel(row));
            boolean risky = r.risk != null || r.delta.getKind() == UpgradeSimulator.Kind.DOWNGRADED;
            if (risky && !selected) {
                setBackground(RISK);
            }
            SimpleTextAttributes attrs = r.delta != null && r.delta.getKind() == UpgradeSimulator.Kind.REMOVED
                    ? SimpleTextAttributes.GRAYED_ATTRIBUTES
                    : risky && column == 1 ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
            append(value == null ? "" : value.toString(), attrs);
            if (r.delta != null) {
                setToolTipText(r.delta.message());
            } else {
                setToolTipText(r.risk.message());
            }
        }
    }
}
