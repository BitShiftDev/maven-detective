package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.TextFieldWithAutoCompletion;
import javax.swing.JList;
import java.util.concurrent.CopyOnWriteArrayList;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
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
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
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
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.github.bitshiftdev.mavendetective.idea.ui.Html.esc;

/**
 * Seite „Upgrade-Simulator“ im Tab „Dependencies“ (Modul A6): Artifact, BOM, Parent oder Property (mit Autovervollständigung)
 * und Zielversion (Vorschläge aus dem lokalen Repository mit Markern neuer/älter/Major-Sprung) wählen,
 * „Simulieren“ zeigt je Modul, was sich im Baum ändert – bevor die POM angefasst wird. Oben die
 * Stellen, die geändert würden (mit Sprung), in der Tabelle die Unterschiede, darunter Pfade,
 * Hinweise und neue Konflikte. „Übernehmen“ schreibt genau diese Änderungen (ein Undo-Schritt),
 * „Fehlende POMs laden“ holt mit Maven, was für die neue Version lokal fehlt – der einzige
 * Netzzugriff in Modul A und nur auf Klick. Das Ergebnis öffnet zusätzlich als Editor-Tab ({@link UpgradeResultEditor}):
 * POM-Änderung im Diff-Viewer, Baum-Unterschied darunter.
 */
public final class UpgradeSimulatorPanel extends DetectivePanel {

    private final TextFieldWithAutoCompletion<String> artifact;
    private final ComboBox<String> version = new ComboBox<>();
    private final JBCheckBox manage = new JBCheckBox(MavenDetectiveBundle.message("upgrade.manage"));
    private final JButton fetch = new JButton(MavenDetectiveBundle.message("upgrade.fetch"), AllIcons.Actions.Download);
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("upgrade.apply")));
    private final JButton openEditor = new JButton(MavenDetectiveBundle.message("upgrade.open.editor"), AllIcons.Actions.Diff);
    private final List<Runnable> resultListeners = new CopyOnWriteArrayList<>();
    /** Editor-Tab mit dem Ergebnis (einer je Seite; neuer Titel = neue Datei). */
    private @Nullable UpgradeResultEditor.ResultFile editorFile;
    private final DeltaModel model = new DeltaModel();
    private final JBTable table = new JBTable(model);
    private final HtmlView details;
    private final Background simulation = background(MavenDetectiveBundle.message("loading.simulate"));
    private final Background suggestions = background(null);
    private final Background localVersions = background(null);
    /** Je {@code g:a} die heutige(n) Version(en) im Projekt – für Vorschläge und den Status. */
    private volatile Map<String, String> current = Map.of();
    private UpgradeSimulator.Result result;
    /** Fehlermeldung des letzten Ladens fehlender POMs. */
    private String fetchError;
    /** Fehlende POMs werden gerade geladen (nur EDT). */
    private boolean fetching;

    public UpgradeSimulatorPanel(@NotNull Project project) {
        super(project);

        // Eingabe mit Autovervollständigung aus den Artifacts, Parents und BOM-Importen des Projekts
        artifact = TextFieldWithAutoCompletion.create(project, List.of(), true, null);
        artifact.setPreferredWidth(JBUI.scale(320));
        artifact.setToolTipText(MavenDetectiveBundle.message("upgrade.artifact.tooltip"));
        artifact.setPlaceholder(MavenDetectiveBundle.message("upgrade.artifact.placeholder"));
        artifact.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                loadVersions(null);
            }
        });
        version.setEditable(true);
        version.setRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends String> list, @Nullable String value, int index, boolean selected,
                                                 boolean focus) {
                if (value == null) {
                    return;
                }
                append(value);
                // Vorschläge stammen aus dem lokalen Repository; dazu die Einordnung gegenüber heute
                append("  " + MavenDetectiveBundle.message("upgrade.marker.local"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                for (UpgradeLabels.Marker m : UpgradeLabels.markers(value, current.get(text(artifact)))) {
                    append("  " + UpgradeLabels.markerText(m), switch (m) {
                        case MAJOR -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DetectiveColors.DELTA_SCOPE);
                        case NEWER -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DetectiveColors.DELTA_UP);
                        case OLDER -> new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DetectiveColors.DELTA_DOWN);
                        case CURRENT -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES;
                    });
                }
            }
        });
        version.setPrototypeDisplayValue("2.17.10-SNAPSHOT");
        version.setToolTipText(MavenDetectiveBundle.message("upgrade.version.tooltip"));
        manage.setToolTipText(MavenDetectiveBundle.message("upgrade.manage.tooltip"));
        manage.setOpaque(false);
        fetch.addActionListener(e -> fetchMissing());
        apply.addActionListener(e -> applyResult());
        fetch.setEnabled(false);
        apply.setEnabled(false);
        openEditor.setEnabled(false);
        openEditor.setToolTipText(MavenDetectiveBundle.message("upgrade.open.editor.tooltip"));
        openEditor.addActionListener(e -> openEditor(true));
        fetch.setToolTipText(MavenDetectiveBundle.message("upgrade.fetch.tooltip"));

        JPanel form = new JPanel(new HorizontalLayout(JBUI.scale(6)));
        form.setBorder(JBUI.Borders.emptyLeft(4));
        form.add(new JBLabel(MavenDetectiveBundle.message("upgrade.artifact")));
        form.add(artifact);
        form.add(new JBLabel(MavenDetectiveBundle.message("upgrade.version")));
        form.add(version);
        form.add(manage);
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(form, actions, null);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                render();
            }
        });
        TableSpeedSearch.installOn(table, (value, cell) -> cell.column == 2 && value != null ? value.toString() : null);
        intro(table.getEmptyText(), MavenDetectiveBundle.message("upgrade.empty.start"),
                MavenDetectiveBundle.message("action.simulate"), this::simulate);

        details = new HtmlView(project);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(details.component(), BorderLayout.CENTER);
        bottom.add(new FixBar(apply, openEditor, fetch), BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(true, "MavenDetective.Upgrade.split", 0.5f);
        splitter.setFirstComponent(rows(table, this::selectedLocation, this::selectedText));
        splitter.setSecondComponent(bottom);
        setBody(splitter);
        showText("<p>Artifact (<code>groupId:artifactId</code>, auch BOM oder Parent) oder <code>${property}</code> und Zielversion "
                + "wählen, dann „Simulieren“. Die POMs bleiben unverändert, bis „Übernehmen“ geklickt wird.</p>"
                + "<p>Im Editor: Rechtsklick auf eine <code>&lt;dependency&gt;</code> oder den <code>&lt;parent&gt;</code> in der "
                + "<code>pom.xml</code> → „Upgrade simulieren…“.</p>");
        AnalysisResults.getInstance(project).addListener(new AnalysisResults.Listener() {
            @Override
            public void changed(@NotNull AnalysisResults.Analysis analysis, @NotNull AnalysisResults.Summary summary) {
            }

            /** Simulation beruht auf den alten Bäumen: als veraltet markieren; Vorschläge sind günstig, die neu. */
            @Override
            public void mavenImported() {
                if (result != null) {
                    setStale(true);
                    fireResult();
                }
                loadSuggestions();
            }
        }, this);
        loadSuggestions();
    }

    @Override
    public boolean supportsRefresh() {
        return true;
    }

    @Override
    public boolean canRefresh() {
        return !fetching && !simulation.isRunning();
    }

    @Override
    public void refresh() {
        simulate();
    }

    @Override
    public @NotNull String refreshText() {
        return MavenDetectiveBundle.message("action.simulate");
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
        return result == null ? null : result.markdown(true);
    }

    /** F4: erste Stelle, die die Simulation in einer POM ändern würde. */
    private @Nullable String selectedLocation() {
        UpgradeSimulator.Result res = result;
        if (res == null || res.getSteps().isEmpty()) {
            return null;
        }
        UpgradeSimulator.Step s = res.getSteps().get(0);
        return Locations.of(s.getFile(), s.getLine());
    }

    private @Nullable String selectedText() {
        Row row = selected();
        if (row == null) {
            return null;
        }
        return row.module.getName() + ": " + (row.delta != null ? row.delta.message() : row.risk.message());
    }

    /** Von der Editor-Aktion: Artifact vorbelegen, Versionen laden, bei lokal vorhandener neuerer Version gleich simulieren. */
    void show(@NotNull String groupId, @NotNull String artifactId) {
        artifact.setText(groupId + ":" + artifactId);
        loadVersions(groupId + ":" + artifactId);
    }

    // ---- Zustand für den Editor-Tab ------------------------------------------------------------

    @Nullable UpgradeSimulator.Result result() {
        return result;
    }

    @Nullable String fetchError() {
        return fetchError;
    }

    /** Simulation oder Laden fehlender POMs läuft. */
    boolean isBusy() {
        return fetching || simulation.isRunning();
    }

    /** Meldet jede Änderung an Ergebnis, Zustand oder „läuft“ (EDT); ab mit {@code parent}. */
    void addResultListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        resultListeners.add(listener);
        Disposer.register(parent, () -> resultListeners.remove(listener));
    }

    private void fireResult() {
        for (Runnable r : resultListeners) {
            r.run();
        }
    }

    /** Ergebnis im Editor-Tab zeigen (Vorher/Nachher braucht Breite); öffnet ihn bei Bedarf, ohne den Fokus zu nehmen. */
    private void openEditor(boolean focus) {
        UpgradeSimulator.Result res = result;
        if (res == null || isGone()) {
            return;
        }
        String name = MavenDetectiveBundle.message("upgrade.editor.title", res.getRequest().label(), res.getRequest().getVersion());
        FileEditorManager fem = FileEditorManager.getInstance(project);
        if (editorFile != null && !editorFile.getName().equals(name)) {
            fem.closeFile(editorFile);
            editorFile = null;
        }
        if (editorFile == null) {
            editorFile = new UpgradeResultEditor.ResultFile(name, this);
        }
        fem.openFile(editorFile, focus, true);
    }

    /** „Übernehmen“ im Editor-Tab: genau den gezeigten Diff schreiben (kein zweiter Dialog). */
    void applyFromEditor(@NotNull PomChanges.Plan shown) {
        if (result == null || result.getSteps().isEmpty()) {
            return;
        }
        applied(FixRunner.editPoms(project, result.fixes(), "Upgrade übernehmen (Maven Detective)", shown));
    }

    private void loadSuggestions() {
        suggestions.run(c -> {
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
            return found;
        }, found -> {
            current = found;
            artifact.setVariants(found.keySet());
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
        if (p.length != 2 || p[0].isEmpty() || p[1].isEmpty()) {
            return;
        }
        String now = current.get(ga);
        localVersions.run(c -> {
            Path repo = DependenciesPanel.locator(project, IdeMaven.reactorRoot(project)).getLocalRepository();
            return UpgradeSimulator.localVersions(repo, p[0], p[1]);
        }, versions -> {
            if (!ga.equals(text(artifact))) {
                return;
            }
            version.removeAllItems();
            for (String v : versions) {
                version.addItem(v);
            }
            String newer = versions.isEmpty() || now == null || MavenVersion.compare(versions.get(0), now) <= 0 ? null : versions.get(0);
            version.setSelectedItem(newer);
            setStatus(now == null ? null : "heute " + now + (newer == null ? " – keine neuere Version lokal, Zielversion eintippen" : ""));
            if (autoSimulateFor != null && newer != null) {
                simulate();
            } else if (autoSimulateFor != null) {
                version.requestFocusInWindow();
            }
        });
    }

    private @Nullable UpgradeSimulator.Request request() {
        String what = text(artifact);
        String v = text(version);
        if (what.isEmpty() || v.isEmpty()) {
            setStatus(MavenDetectiveBundle.message("upgrade.missing.input"));
            return null;
        }
        UpgradeSimulator.Request r;
        try {
            r = what.startsWith("${") || !what.contains(":") ? UpgradeSimulator.Request.parse(what + "=" + v)
                    : UpgradeSimulator.Request.parse(what + ":" + v);
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage());
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
        setStatus(null);
        AnalysisResults.getInstance(project).running(AnalysisResults.Analysis.UPGRADE);
        simulation.run(c -> compute(project, r), res -> display(res, null));
        fireResult();
    }

    @Override
    protected void aborted() {
        AnalysisResults.getInstance(project).aborted(AnalysisResults.Analysis.UPGRADE);
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
    void fetchMissing() {
        UpgradeSimulator.Result start = result;
        if (start == null || start.isComplete()) {
            return;
        }
        UpgradeSimulator.Request r = start.getRequest();
        fetch.setEnabled(false);
        fetching = true;
        fireResult();
        int gen = simulation.next();
        new Task.Backgroundable(project, MavenDetectiveBundle.message("upgrade.fetch.task"), true) {
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
                fetching = false;
                if (simulation.isCurrent(gen) && !isGone()) {
                    display(last, error);
                } else {
                    fireResult();
                }
            }
        }.queue();
    }

    private void applyResult() {
        if (result == null || result.getSteps().isEmpty()) {
            return;
        }
        applied(FixRunner.editPoms(project, result.fixes(), "Upgrade übernehmen (Maven Detective)"));
    }

    private void applied(int n) {
        if (n > 0) {
            apply.setEnabled(false);
            fetch.setEnabled(false);
        }
        setStatus(MavenDetectiveBundle.message(n == 0 ? "upgrade.applied.none" : "upgrade.applied"));
    }

    private void display(@Nullable UpgradeSimulator.Result res, @Nullable String error) {
        result = res;
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
        openEditor.setEnabled(res != null);
        fetch.setEnabled(res != null && !PomFetcher.coordinates(res.getMissing()).isEmpty());
        if (res == null) {
            setStatus(null);
            intro(table.getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
        } else {
            emptyText(table.getEmptyText(), MavenDetectiveBundle.message("upgrade.empty.unchanged"), null, null);
            int changed = res.changed().size();
            setStatus(changed + (changed == 1 ? " Modul ändert sich" : " Module ändern sich")
                    + (res.isComplete() ? "" : " – unvollständig, POMs fehlen lokal"));
        }
        this.fetchError = error;
        setStale(false);
        AnalysisResults.getInstance(project).set(AnalysisResults.Analysis.UPGRADE, res == null ? AnalysisResults.Summary.NOT_CHECKED
                : AnalysisResults.Summary.done(-1, MavenDetectiveBundle.message("summary.upgrade", res.getRequest().label() + " " + res.getRequest().getVersion(),
                res.changed().size())));
        render();
        fireResult();
        if (res != null && (!res.getSteps().isEmpty() || !res.changed().isEmpty())) {
            openEditor(false);
        }
    }

    private void render() {
        UpgradeSimulator.Result res = result;
        if (res == null) {
            showText(fetchError == null ? "<p>Kein Maven-Projekt gefunden.</p>" : "<p>" + esc(fetchError) + "</p>");
            return;
        }
        StringBuilder sb = new StringBuilder("<h3>").append(esc(res.headline())).append("</h3>");
        if (fetchError != null) {
            sb.append("<p><b>").append(Html.error(esc(fetchError).replace("\n", "<br>"))).append("</b></p>");
        }
        if (!res.getSteps().isEmpty()) {
            sb.append("<p><b>Änderung an den POMs</b> (noch nicht angewendet):</p><ol>");
            for (UpgradeSimulator.Step s : res.getSteps()) {
                sb.append("<li>").append(esc(s.getDescription())).append(" – ").append(Html.pomLink(s.getFile(), s.getLine()));
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
        details.show(body);
    }

    private static String text(ComboBox<String> box) {
        Object item = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static String text(TextFieldWithAutoCompletion<String> field) {
        return field.getText().trim();
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
                setBackground(DetectiveColors.ERROR_BACKGROUND);
            }
            SimpleTextAttributes attrs = column == 1
                    ? r.delta != null ? UpgradeLabels.symbolAttributes(r.delta.getKind()) : UpgradeLabels.riskAttributes()
                    : r.delta != null && r.delta.getKind() == UpgradeSimulator.Kind.REMOVED
                    ? risky && !selected ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DetectiveColors.TINTED_SECONDARY)
                    : SimpleTextAttributes.GRAYED_ATTRIBUTES
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES;
            append(value == null ? "" : value.toString(), attrs);
            if (r.delta != null) {
                setToolTipText(r.delta.message());
            } else {
                setToolTipText(r.risk.message());
            }
        }
    }

    @Override
    protected @Nls @Nullable String about() {
        return MavenDetectiveBundle.message("upgrade.about");
    }
}
