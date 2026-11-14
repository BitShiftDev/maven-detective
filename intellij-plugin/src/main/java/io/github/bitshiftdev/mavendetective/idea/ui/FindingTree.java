package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckboxTreeBase;
import com.intellij.ui.CheckboxTreeListener;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Baum <i>Gruppe → Befund</i> mit Checkboxen für Sammel-Fixes (UI-Plan U5, Seiten Doppelte Klassen,
 * Exclusions, Nutzung). Gruppe ist meist das Modul bzw. die POM. Ankreuzen lässt sich nur, was einen
 * Fix hat ({@code checkable}, sonst ist die Checkbox ausgegraut); die Leertaste schaltet, Mehrfachauswahl zeigt Details zur Auswahl.
 * Beim Neubefüllen bleiben Häkchen, Auswahl und Aufklappzustand erhalten, soweit die Befunde gleich sind.
 * Was der Nutzer an- oder abgehakt hat, merkt sich der Baum je Schlüssel ({@link #keyedBy}) – auch für
 * Befunde, die gerade ausgefiltert sind, und über eine Neuberechnung hinweg (neue Objekte, gleicher Befund).
 *
 * @param <T> Befund
 */
public final class FindingTree<T> {

    /** Wie ein Befund im Baum aussieht: Icon + Wort für die Art, dann Text. */
    public interface ItemRenderer<T> {
        void render(@NotNull ColoredTreeCellRenderer target, @NotNull T item, boolean selected);
    }

    /** Kopfzeile einer Gruppe. */
    private record Group(@Nls String name, @Nls @Nullable String detail) {
    }

    private final Class<T> type;
    private final CheckedTreeNode root = new CheckedTreeNode(null);
    private final CheckboxTree tree;
    private final Predicate<T> checkable;
    private final Function<T, String> searchText;
    private final List<Runnable> checkListeners = new ArrayList<>();
    /** Stabiler Schlüssel eines Befunds (Standard: das Objekt selbst). */
    private Function<T, Object> key = item -> item;
    /** Häkchen je Schlüssel, wie der Nutzer sie zuletzt gesehen bzw. gesetzt hat. */
    private final Map<Object, Boolean> choices = new HashMap<>();
    /** Häkchen-Ereignisse beim Befüllen nicht melden. */
    private boolean filling;

    public FindingTree(@NotNull Class<T> type, @NotNull ItemRenderer<T> renderer, @NotNull Predicate<T> checkable,
                       @NotNull Function<T, String> searchText) {
        this.type = type;
        this.checkable = checkable;
        this.searchText = searchText;
        tree = new CheckboxTree(new CheckboxTree.CheckboxTreeCellRenderer(true, true) {
            @Override
            public void customizeRenderer(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                if (!(value instanceof CheckedTreeNode node)) {
                    return;
                }
                ColoredTreeCellRenderer text = getTextRenderer();
                Object o = node.getUserObject();
                if (o instanceof Group g) {
                    text.setIcon(AllIcons.Nodes.Module);
                    text.append(g.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    if (g.detail() != null) {
                        text.append("  " + g.detail(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                } else if (type.isInstance(o)) {
                    renderer.render(text, type.cast(o), selected);
                }
            }
        }, root, new CheckboxTreeBase.CheckPolicy(true, true, true, true));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addCheckboxTreeListener(new CheckboxTreeListener() {
            @Override
            public void nodeStateChanged(@NotNull CheckedTreeNode node) {
                if (!filling) {
                    rememberChoices();
                    checkListeners.forEach(Runnable::run);
                }
            }
        });
        TreeSpeedSearch.installOn(tree, true, path -> {
            Object o = ((CheckedTreeNode) path.getLastPathComponent()).getUserObject();
            return o instanceof Group g ? g.name() : type.isInstance(o) ? searchText.apply(type.cast(o)) : null;
        });
    }

    /**
     * Schlüssel, unter dem Häkchen über Suche, Filter und Neuberechnung hinweg erhalten bleiben – z. B.
     * Datei + Dependency + Exclusion. Muss vor dem ersten {@link #set} gesetzt werden.
     */
    public @NotNull FindingTree<T> keyedBy(@NotNull Function<T, ?> key) {
        this.key = key::apply;
        return this;
    }

    /** Häkchen aller sichtbaren ankreuzbaren Befunde merken (ausgeblendete behalten ihren Stand). */
    private void rememberChoices() {
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
            for (int j = 0; j < g.getChildCount(); j++) {
                CheckedTreeNode n = (CheckedTreeNode) g.getChildAt(j);
                if (n.isEnabled()) {
                    choices.put(key.apply(type.cast(n.getUserObject())), n.isChecked());
                }
            }
        }
    }

    public @NotNull CheckboxTree component() {
        return tree;
    }

    /**
     * Neu befüllen. {@code groups}: Gruppenname → Befunde in Anzeigereihenfolge, {@code detail}: Zusatz hinter
     * dem Gruppennamen. Befunde, deren Schlüssel der Baum noch nicht kennt, bekommen ein Häkchen, wenn
     * {@code checkedByDefault} es sagt; bekannte behalten die Wahl des Nutzers.
     */
    public void set(@NotNull Map<String, List<T>> groups, @NotNull Function<List<T>, @Nls String> detail,
                    @NotNull Predicate<T> checkedByDefault) {
        rememberChoices();
        List<T> wasSelected = selected();
        Set<String> collapsed = new HashSet<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
            if (!tree.isExpanded(new TreePath(g.getPath()))) {
                collapsed.add(((Group) g.getUserObject()).name());
            }
        }
        filling = true;
        try {
            root.removeAllChildren();
            for (Map.Entry<String, List<T>> e : groups.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                CheckedTreeNode g = new CheckedTreeNode(new Group(e.getKey(), detail.apply(e.getValue())));
                boolean any = false;
                for (T item : e.getValue()) {
                    CheckedTreeNode n = new CheckedTreeNode(item);
                    boolean can = checkable.test(item);
                    n.setEnabled(can);
                    Boolean choice = choices.get(key.apply(item));
                    n.setChecked(can && (choice != null ? choice : checkedByDefault.test(item)));
                    any |= can;
                    g.add(n);
                }
                g.setEnabled(any);
                root.add(g);
            }
            for (int i = 0; i < root.getChildCount(); i++) {
                syncGroup((CheckedTreeNode) root.getChildAt(i));
            }
            ((DefaultTreeModel) tree.getModel()).reload();
            for (int i = 0; i < root.getChildCount(); i++) {
                CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
                if (!collapsed.contains(((Group) g.getUserObject()).name())) {
                    tree.expandPath(new TreePath(g.getPath()));
                }
            }
        } finally {
            filling = false;
        }
        List<TreePath> paths = new ArrayList<>();
        for (T item : wasSelected) {
            TreePath p = path(item);
            if (p != null) {
                paths.add(p);
            }
        }
        if (!paths.isEmpty()) {
            tree.setSelectionPaths(paths.toArray(new TreePath[0]));
        }
        checkListeners.forEach(Runnable::run);
    }

    /** Gruppe hat ein Häkchen genau dann, wenn alle ankreuzbaren Befunde eins haben. */
    private void syncGroup(CheckedTreeNode g) {
        boolean all = g.getChildCount() > 0;
        boolean any = false;
        for (int i = 0; i < g.getChildCount(); i++) {
            CheckedTreeNode n = (CheckedTreeNode) g.getChildAt(i);
            if (n.isEnabled()) {
                any = true;
                all &= n.isChecked();
            }
        }
        g.setChecked(any && all);
    }

    /** Ausgewählte Befunde; eine gewählte Gruppe zählt mit all ihren Befunden. */
    public @NotNull List<T> selected() {
        LinkedHashMap<T, Boolean> out = new LinkedHashMap<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) {
            return List.of();
        }
        for (TreePath p : paths) {
            CheckedTreeNode n = (CheckedTreeNode) p.getLastPathComponent();
            if (type.isInstance(n.getUserObject())) {
                out.put(type.cast(n.getUserObject()), true);
            } else {
                for (int i = 0; i < n.getChildCount(); i++) {
                    out.put(type.cast(((CheckedTreeNode) n.getChildAt(i)).getUserObject()), true);
                }
            }
        }
        return new ArrayList<>(out.keySet());
    }

    /** Genau ein Befund gewählt (nicht die Gruppe)? Dann der, sonst {@code null}. */
    public @Nullable T single() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length != 1) {
            return null;
        }
        Object o = ((CheckedTreeNode) paths[0].getLastPathComponent()).getUserObject();
        return type.isInstance(o) ? type.cast(o) : null;
    }

    /** Angekreuzte Befunde, die einen Fix haben. */
    public @NotNull List<T> checked() {
        List<T> out = new ArrayList<>();
        for (T item : all()) {
            CheckedTreeNode n = node(item);
            if (n != null && n.isChecked() && checkable.test(item)) {
                out.add(item);
            }
        }
        return out;
    }

    public @NotNull List<T> all() {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
            for (int j = 0; j < g.getChildCount(); j++) {
                out.add(type.cast(((CheckedTreeNode) g.getChildAt(j)).getUserObject()));
            }
        }
        return out;
    }

    /** Alle ankreuzbaren an- bzw. abhaken. */
    public void setAllChecked(boolean checked) {
        filling = true;
        try {
            for (int i = 0; i < root.getChildCount(); i++) {
                CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
                for (int j = 0; j < g.getChildCount(); j++) {
                    CheckedTreeNode n = (CheckedTreeNode) g.getChildAt(j);
                    if (n.isEnabled()) {
                        n.setChecked(checked);
                    }
                }
                syncGroup(g);
            }
        } finally {
            filling = false;
        }
        rememberChoices();
        tree.repaint();
        checkListeners.forEach(Runnable::run);
    }

    /** Diesen Befund wählen und sichtbar machen; {@code false}, wenn er nicht im Baum steht. */
    public boolean select(@NotNull T item) {
        TreePath p = path(item);
        if (p == null) {
            return false;
        }
        tree.setSelectionPath(p);
        tree.scrollPathToVisible(p);
        return true;
    }

    /** Ersten Befund wählen, wenn nichts gewählt ist. */
    public void selectFirstIfNone() {
        if (tree.getSelectionCount() == 0 && root.getChildCount() > 0) {
            CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(0);
            if (g.getChildCount() > 0) {
                TreePath p = new TreePath(((CheckedTreeNode) g.getChildAt(0)).getPath());
                tree.setSelectionPath(p);
                tree.scrollPathToVisible(p);
            }
        }
    }

    public boolean isEmpty() {
        return root.getChildCount() == 0;
    }

    public void addSelectionListener(@NotNull Runnable r) {
        tree.addTreeSelectionListener(e -> r.run());
    }

    public void addCheckListener(@NotNull Runnable r) {
        checkListeners.add(r);
    }

    public void expandAll() {
        TreeUtil.expandAll(tree);
    }

    private @Nullable CheckedTreeNode node(T item) {
        Object k = key.apply(item);
        for (int i = 0; i < root.getChildCount(); i++) {
            CheckedTreeNode g = (CheckedTreeNode) root.getChildAt(i);
            for (int j = 0; j < g.getChildCount(); j++) {
                CheckedTreeNode n = (CheckedTreeNode) g.getChildAt(j);
                if (item.equals(n.getUserObject()) || k.equals(key.apply(type.cast(n.getUserObject())))) {
                    return n;
                }
            }
        }
        return null;
    }

    private @Nullable TreePath path(T item) {
        CheckedTreeNode n = node(item);
        return n == null ? null : new TreePath(n.getPath());
    }

    /** Befunde nach Gruppe, Reihenfolge des ersten Auftretens. */
    public static <T> @NotNull Map<String, List<T>> groupBy(@NotNull Collection<T> items, @NotNull Function<T, String> group) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        for (T item : items) {
            out.computeIfAbsent(group.apply(item), g -> new ArrayList<>()).add(item);
        }
        return out;
    }
}
