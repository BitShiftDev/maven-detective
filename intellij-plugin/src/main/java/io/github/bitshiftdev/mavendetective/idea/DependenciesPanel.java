package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplainer;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Seite „Warum diese Version?“ im Tab „Dependencies“ (Modul A1): links die aufgelösten Dependencies eines Moduls, rechts die
 * Antwort als Zeitleiste ({@link VersionTimeline}) mit klickbaren Stationen bis in Parent-POMs und BOMs und dem Pfad im
 * Baum als Breadcrumb.
 */
public final class DependenciesPanel extends DetectivePanel {

    /** Ein Eintrag der Liste: die Dependency, die im Classpath landet, und wie viele Versionen sie verdrängt hat. */
    private record Entry(DepNode node, int conflicts) {
    }

    /** Ergebnis des Ladens: Baum des Moduls und die Einträge der Liste. */
    private record Loaded(@Nullable DepNode tree, List<Entry> entries) {
    }

    private final ComboBox<IdeMaven.ModuleRef> modules = new ComboBox<>();
    private final SearchTextField search;
    private final DefaultListModel<Entry> listModel = new DefaultListModel<>();
    private final JBList<Entry> list = new JBList<>(listModel);
    private final VersionTimeline details;
    private final Background trees = background(MavenDetectiveBundle.message("loading.tree"));
    private final Background explanations = background(null);
    private List<Entry> all = new ArrayList<>();
    private DepNode tree;
    private VersionExplanation shown;
    private boolean loading;

    public DependenciesPanel(@NotNull Project project) {
        super(project);

        search = searchField(MavenDetectiveBundle.message("search.dependencies"), this::applyFilter);
        modules.setToolTipText(MavenDetectiveBundle.message("dependencies.module.tooltip"));
        modules.addActionListener(e -> {
            if (!loading) {
                loadTree(null, null);
            }
        });
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.CopyMarkdown"));
        installToolbar(modules, actions, search);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends Entry> l, Entry value, int index, boolean selected, boolean focus) {
                DepNode n = value.node();
                append(n.ga());
                append("  " + n.getVersion(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (n.getScope() != null && !"compile".equals(n.getScope())) {
                    append("  " + n.getScope(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
                if (n.depth() > 1) {
                    append("  transitiv", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                if (value.conflicts() > 0) {
                    append("  " + value.conflicts() + "× verdrängt", SimpleTextAttributes.ERROR_ATTRIBUTES);
                }
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                DepNode n = list.getSelectedValue().node();
                explain(n.getGroupId(), n.getArtifactId());
            }
        });
        ListSpeedSearch.installOn(list, e -> e.node().ga());

        details = new VersionTimeline(project);
        details.showMessage(MavenDetectiveBundle.message("why.start"));
        JBScrollPane detailScroll = new JBScrollPane(details);
        detailScroll.setBorder(JBUI.Borders.empty());

        JBSplitter splitter = new JBSplitter(false, "MavenDetective.Dependencies.split", 0.35f);
        splitter.setFirstComponent(rows(list, () -> null, () -> list.getSelectedValue() == null ? null : list.getSelectedValue().node().gav()));
        splitter.setSecondComponent(detailScroll);
        setBody(splitter);

        AnalysisResults.getInstance(project).addListener(new AnalysisResults.Listener() {
            @Override
            public void changed(@NotNull AnalysisResults.Analysis analysis, @NotNull AnalysisResults.Summary summary) {
            }

            /** Günstig: Baum und gezeigte Erklärung nach jedem Import neu, damit keine alte Version stehen bleibt. */
            @Override
            public void mavenImported() {
                IdeMaven.ModuleRef module = (IdeMaven.ModuleRef) modules.getSelectedItem();
                VersionExplanation x = shown;
                if (module != null && x != null) {
                    show(module.pom(), x.getGroupId(), x.getArtifactId());
                } else {
                    reloadModules(null);
                }
            }
        }, this);
        reloadModules(null);
    }

    @Override
    public boolean supportsRefresh() {
        return true;
    }

    @Override
    public void refresh() {
        reloadModules(null);
    }

    @Override
    public boolean canCopyMarkdown() {
        return shown != null;
    }

    @Override
    public @Nullable String markdown() {
        return shown == null ? null : shown.markdown();
    }

    /** Einstieg aus der Editor-Aktion: Modul wählen und die Erklärung zeigen. */
    void show(@NotNull Path pom, @NotNull String groupId, @NotNull String artifactId) {
        reloadModules(pom);
        loadTree(groupId, artifactId);
    }

    private void reloadModules(@Nullable Path select) {
        IdeMaven.ModuleRef before = (IdeMaven.ModuleRef) modules.getSelectedItem();
        Path wanted = select != null ? select : before == null ? null : before.pom();
        loading = true;
        try {
            modules.removeAllItems();
            for (IdeMaven.ModuleRef m : IdeMaven.modules(project)) {
                modules.addItem(m);
                if (m.pom().equals(wanted)) {
                    modules.setSelectedItem(m);
                }
            }
        } finally {
            loading = false;
        }
        if (select == null) {
            loadTree(null, null);
        }
    }

    /** Baum des gewählten Moduls laden; danach optional eine Dependency erklären. */
    private void loadTree(@Nullable String groupId, @Nullable String artifactId) {
        IdeMaven.ModuleRef module = (IdeMaven.ModuleRef) modules.getSelectedItem();
        if (module == null) {
            listModel.clear();
            all = new ArrayList<>();
            emptyText(list.getEmptyText(), MavenDetectiveBundle.message("empty.no.maven.project"),
                    MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            return;
        }
        // Nicht ModalityState.any(): explain() speichert Dokumente, und das verlangt einen
        // write-safe Kontext (TransactionGuard), den any() nie bietet – Background nutzt defaultModalityState()
        trees.run(c -> {
            DepNode t = IdeMaven.dependencyTree(project, module.pom());
            return new Loaded(t, entries(t));
        }, r -> {
            List<Entry> entries = r.entries();
            tree = r.tree();
            all = entries;
            if (entries.isEmpty()) {
                emptyText(list.getEmptyText(), MavenDetectiveBundle.message("empty.no.tree"),
                        MavenDetectiveBundle.message("empty.reload.maven"), () -> IdeMaven.reimport(project));
            } else {
                emptyText(list.getEmptyText(), MavenDetectiveBundle.message("empty.no.match"), null, null);
            }
            applyFilter();
            if (groupId != null) {
                selectInList(groupId, artifactId);
                explain(groupId, artifactId);
            }
        });
    }

    /** Jede Dependency einmal (der Knoten im Classpath), sortiert; dazu, wie oft andere Versionen verdrängt wurden. */
    private static List<Entry> entries(@Nullable DepNode tree) {
        List<Entry> out = new ArrayList<>();
        if (tree == null) {
            return out;
        }
        Map<String, DepNode> included = new LinkedHashMap<>();
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        List<DepNode> queue = new ArrayList<>(tree.getChildren());
        for (int i = 0; i < queue.size() && i < 20_000; i++) {
            DepNode n = queue.get(i);
            if (n.getState() == DepNode.State.INCLUDED) {
                included.putIfAbsent(n.ga(), n);
            } else if (n.getState() == DepNode.State.CONFLICT) {
                conflicts.merge(n.ga(), 1, Integer::sum);
            }
            queue.addAll(n.getChildren());
        }
        for (DepNode n : included.values()) {
            out.add(new Entry(n, conflicts.getOrDefault(n.ga(), 0)));
        }
        out.sort(Comparator.comparing((Entry e) -> e.node().ga()));
        return out;
    }

    private void applyFilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        Entry selected = list.getSelectedValue();
        listModel.clear();
        for (Entry e : all) {
            if (q.isEmpty() || e.node().ga().toLowerCase(Locale.ROOT).contains(q)) {
                listModel.addElement(e);
            }
        }
        if (selected != null) {
            list.setSelectedValue(selected, true);
        }
    }

    private void selectInList(String groupId, String artifactId) {
        for (int i = 0; i < listModel.size(); i++) {
            DepNode n = listModel.get(i).node();
            if (n.getGroupId().equals(groupId) && n.getArtifactId().equals(artifactId)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
        list.clearSelection();
    }

    private void explain(String groupId, String artifactId) {
        IdeMaven.ModuleRef module = (IdeMaven.ModuleRef) modules.getSelectedItem();
        if (module == null) {
            return;
        }
        // Ungespeicherte Änderungen an POMs sollen in die Erklärung eingehen
        saveUnsavedPoms();
        DepNode t = tree;
        details.showMessage(MavenDetectiveBundle.message("why.loading", groupId + ":" + artifactId));
        explanations.run(c -> explainNow(project, module.pom(), groupId, artifactId, t), x -> {
            shown = x;
            if (x == null) {
                details.showMessage(MavenDetectiveBundle.message("popup.why.unreadable"));
            } else {
                details.show(x, t, n -> {
                    selectInList(n.getGroupId(), n.getArtifactId());
                    explain(n.getGroupId(), n.getArtifactId());
                });
            }
        });
    }

    /**
     * Nur ungespeicherte {@code pom.xml} speichern, nicht alle Dokumente: {@code saveAllDocuments()}
     * löste bei jedem Klick „Actions on Save“ (Reformat, Optimize Imports, Build) und File-Watcher aus.
     * Braucht einen write-safe Kontext (EDT, nicht {@code ModalityState.any()}).
     */
    static void saveUnsavedPoms() {
        FileDocumentManager fdm = FileDocumentManager.getInstance();
        for (Document d : fdm.getUnsavedDocuments()) {
            VirtualFile f = fdm.getFile(d);
            if (f != null && "pom.xml".equals(f.getName())) {
                fdm.saveDocument(d);
            }
        }
    }

    /** Erklärung berechnen (Hintergrund-Thread; liest nur Dateien). */
    static @Nullable VersionExplanation explainNow(Project project, Path pom, String groupId, String artifactId, @Nullable DepNode tree) {
        Path root = IdeMaven.reactorRoot(project);
        PomLocator locator = locator(project, root);
        PomFile module = locator.load(pom);
        if (module == null) {
            return null;
        }
        return new VersionExplainer(locator, root).explain(module, groupId, artifactId, tree);
    }

    /** POM-Zugriff für den Reactor; lokales Repository aus dem IDE-Modell, sonst wie Maven es fände. */
    static PomLocator locator(Project project, @Nullable Path root) {
        return locator(project, root, null, Cancellation.NONE);
    }

    /**
     * Wie oben, mit langlebigem POM-Cache und Abbruchpunkt (Inspection: {@code ProgressManager::checkCanceled},
     * damit sie beim nächsten Tastendruck abbricht statt die Read-Action festzuhalten).
     */
    static PomLocator locator(Project project, @Nullable Path root, @Nullable PomCache cache, Cancellation cancellation) {
        Path repo = IdeMaven.localRepository(project);
        if (repo == null) {
            repo = Environment.builder().projectDirectory(root).userHome(Paths.get(System.getProperty("user.home")))
                    .env(System.getenv()).build().getLocalRepository();
        }
        return new PomLocator(root, repo, cache, cancellation);
    }
}
