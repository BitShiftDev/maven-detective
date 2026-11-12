package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.PomCache;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplainer;
import io.github.bitshiftdev.mavendetective.core.deps.VersionExplanation;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab „Dependencies“ (Modul A): links die aufgelösten Dependencies eines Moduls, rechts die
 * Antwort auf „Warum diese Version?“ mit klickbaren Stationen bis in Parent-POMs und BOMs.
 */
public final class DependenciesPanel extends JPanel implements Disposable {

    static final String TAB_NAME = "Dependencies";

    /** Ein Eintrag der Liste: die Dependency, die im Classpath landet, und wie viele Versionen sie verdrängt hat. */
    private record Entry(DepNode node, int conflicts) {
    }

    private final Project project;
    private final ComboBox<IdeMaven.ModuleRef> modules = new ComboBox<>();
    private final SearchTextField search = new SearchTextField(false);
    private final DefaultListModel<Entry> listModel = new DefaultListModel<>();
    private final JBList<Entry> list = new JBList<>(listModel);
    private final JEditorPane details = new JEditorPane("text/html", "");
    private final JButton copy = new JButton("Als Markdown kopieren");
    private List<Entry> all = new ArrayList<>();
    private DepNode tree;
    private VersionExplanation shown;
    private boolean loading;
    /** Zähler je Hintergrund-Aufgabe (nur EDT): Ein älteres Ergebnis überschreibt kein neueres. */
    private int treeGeneration;
    private int explainGeneration;
    private volatile boolean disposed;

    public DependenciesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Aktualisieren");
        refresh.setToolTipText("Module und Bäume neu aus dem Maven-Modell der IDE lesen (Stand des letzten Imports)");
        refresh.addActionListener(e -> reloadModules(null));
        copy.setEnabled(false);
        copy.addActionListener(e -> {
            if (shown != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(shown.markdown()));
            }
        });
        toolbar.add(modules);
        toolbar.add(search);
        toolbar.add(refresh);
        toolbar.add(copy);

        modules.addActionListener(e -> {
            if (!loading) {
                loadTree(null, null);
            }
        });
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                applyFilter();
            }
        });
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

        details.setEditable(false);
        details.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        details.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                FixRunner.openLocation(project, e.getDescription());
            }
        });
        showText("<p>Dependency links auswählen oder in einer <code>pom.xml</code> per Rechtsklick "
                + "„Warum diese Version?“ aufrufen.</p>");

        JBSplitter splitter = new JBSplitter(false, 0.35f);
        splitter.setFirstComponent(new JBScrollPane(list));
        splitter.setSecondComponent(new JBScrollPane(details));
        add(toolbar, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        reloadModules(null);
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
            showText("<p>Kein Maven-Projekt importiert. Nach dem Maven-Import „Aktualisieren“ klicken.</p>");
            return;
        }
        int generation = ++treeGeneration;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DepNode t = IdeMaven.dependencyTree(project, module.pom());
            List<Entry> entries = entries(t);
            // Nicht ModalityState.any(): explain() speichert Dokumente, und das verlangt einen
            // write-safe Kontext (TransactionGuard), den any() nie bietet
            ApplicationManager.getApplication().invokeLater(() -> {
                if (generation != treeGeneration) {
                    return;   // inzwischen ein anderes Modul gewählt
                }
                tree = t;
                all = entries;
                applyFilter();
                if (groupId != null) {
                    selectInList(groupId, artifactId);
                    explain(groupId, artifactId);
                } else if (entries.isEmpty()) {
                    showText("<p>Für dieses Modul liegt kein aufgelöster Baum vor. Maven-Projekt neu laden und „Aktualisieren“ klicken.</p>");
                }
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
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
        int generation = ++explainGeneration;
        showText("<p>Ermittle, woher die Version von " + esc(groupId + ":" + artifactId) + " kommt …</p>");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VersionExplanation x = explainNow(project, module.pom(), groupId, artifactId, t);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (generation != explainGeneration) {
                    return;   // inzwischen eine andere Dependency gewählt
                }
                shown = x;
                copy.setEnabled(x != null);
                showText(x == null ? "<p>Die POM des Moduls ist nicht lesbar.</p>" : render(x));
            }, ModalityState.defaultModalityState(), o -> disposed || project.isDisposed());
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

    private static String render(VersionExplanation x) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3>Warum ").append(esc(x.getGroupId() + ":" + x.getArtifactId()));
        if (x.getVersion() != null) {
            sb.append(" ").append(esc(x.getVersion()));
        }
        sb.append("?</h3>");
        sb.append("<p><b>").append(esc(x.getSummary())).append("</b></p>");
        sb.append("<ol>");
        boolean openSub = false;
        boolean openItem = false;
        for (VersionExplanation.Station s : x.getStations()) {
            if (s.getKind() == VersionExplanation.Station.Kind.HINT) {
                continue;
            }
            if (s.getLevel() == 0) {
                if (openSub) {
                    sb.append("</ul>");
                    openSub = false;
                }
                if (openItem) {
                    sb.append("</li>");
                }
                sb.append("<li>").append(station(s));
                openItem = true;
            } else {
                if (!openSub) {
                    sb.append("<ul>");
                    openSub = true;
                }
                sb.append("<li>").append(station(s)).append("</li>");
            }
        }
        if (openSub) {
            sb.append("</ul>");
        }
        if (openItem) {
            sb.append("</li>");
        }
        sb.append("</ol>");
        List<VersionExplanation.Station> hints = x.hints();
        if (!hints.isEmpty()) {
            sb.append("<p><b>Hinweise</b></p><ul>");
            for (VersionExplanation.Station h : hints) {
                sb.append("<li>").append(station(h)).append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private static String station(VersionExplanation.Station s) {
        String text = esc(s.getText());
        if (s.getFile() == null) {
            return text;
        }
        String target = s.getFile() + (s.getLine() > 0 ? ":" + s.getLine() : "");
        String name = Paths.get(s.getFile()).getFileName() + (s.getLine() > 0 ? ":" + s.getLine() : "");
        return text + " – <a href=\"" + esc(target) + "\">" + esc(name) + "</a>";
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
        disposed = true;   // laufende Hintergrund-Aufgaben verwerfen ihr Ergebnis
    }
}
