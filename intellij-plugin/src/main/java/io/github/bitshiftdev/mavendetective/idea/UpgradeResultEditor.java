package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.PomFetcher;
import io.github.bitshiftdev.mavendetective.core.deps.UpgradeSimulator;
import io.github.bitshiftdev.mavendetective.idea.ui.Badge;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Ergebnis des Upgrade-Simulators als Editor-Tab (UI-Plan U5): Ein Vorher/Nachher braucht Breite, die
 * das untere Tool-Window nicht hat. Oben Kopfzeile mit „Übernehmen“ und „Fehlende POMs laden“, ein
 * Warnbalken bei neuen Konflikten oder gesenkten Versionen; darunter die POM-Änderung im Diff-Viewer
 * der IDE und der Baum-Unterschied je Modul mit farbigen Zeichen ↑ ↓ + −. Der Zustand gehört der
 * Seite „Upgrade-Simulator“; der Tab zeigt ihn nur und meldet Klicks zurück.
 */
public final class UpgradeResultEditor extends UserDataHolderBase implements FileEditor {

    /** Datei des Tabs; trägt nur den Titel und die Seite, deren Ergebnis er zeigt. */
    static final class ResultFile extends LightVirtualFile {
        final UpgradeSimulatorPanel panel;

        ResultFile(@NotNull String name, @NotNull UpgradeSimulatorPanel panel) {
            super(name, PlainTextFileType.INSTANCE, "");
            this.panel = panel;
            setWritable(false);
        }
    }

    /** Öffnet {@link ResultFile} mit diesem Editor statt als Text. */
    public static final class Provider implements FileEditorProvider, DumbAware {
        @Override
        public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
            return file instanceof ResultFile;
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new UpgradeResultEditor(project, (ResultFile) file);
        }

        @Override
        public @NotNull String getEditorTypeId() {
            return "MavenDetective.UpgradeResult";
        }

        @Override
        public @NotNull FileEditorPolicy getPolicy() {
            return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
        }
    }

    private final Project project;
    private final ResultFile file;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBLabel headline = new JBLabel();
    private final JPanel badges = new JPanel(new HorizontalLayout(JBUI.scale(6)));
    private final JButton apply = FixBar.primary(new JButton(MavenDetectiveBundle.message("upgrade.apply")));
    private final JButton fetch = new JButton(MavenDetectiveBundle.message("upgrade.fetch"), AllIcons.Actions.Download);
    private final JButton again = new JButton(MavenDetectiveBundle.message("upgrade.editor.again"), AllIcons.Actions.Refresh);
    private final EditorNotificationPanel warning = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
    private final EditorNotificationPanel info = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
    private final PomDiffView diff;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
    private final Tree tree = new Tree(new DefaultTreeModel(treeRoot));
    private final JBLabel noSteps = new JBLabel();
    private final JPanel top = new JPanel(new BorderLayout());
    private final OnePixelSplitter split = new OnePixelSplitter(true, "MavenDetective.UpgradeEditor.split", 0.5f);
    /** Plan, den der Diff gerade zeigt – „Übernehmen“ schreibt genau den. */
    private @Nullable PomChanges.Plan shown;

    UpgradeResultEditor(@NotNull Project project, @NotNull ResultFile file) {
        this.project = project;
        this.file = file;
        diff = new PomDiffView(project, this, null);

        headline.setFont(JBFont.h3().asBold());
        badges.setOpaque(false);
        apply.addActionListener(e -> {
            if (shown != null) {
                file.panel.applyFromEditor(shown);
            }
        });
        fetch.addActionListener(e -> file.panel.fetchMissing());
        fetch.setToolTipText(MavenDetectiveBundle.message("upgrade.fetch.tooltip"));
        again.addActionListener(e -> file.panel.refresh());
        again.setToolTipText(MavenDetectiveBundle.message("upgrade.editor.again.tooltip"));

        JPanel title = new JPanel(new HorizontalLayout(JBUI.scale(10)));
        title.add(headline);
        title.add(badges);
        JPanel buttons = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        buttons.add(apply);
        buttons.add(fetch);
        buttons.add(again);
        JPanel head = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        head.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12)));
        head.add(title, BorderLayout.CENTER);
        head.add(buttons, BorderLayout.EAST);

        JPanel north = new JPanel(new VerticalLayout(0));
        north.add(head);
        north.add(warning);
        north.add(info);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DeltaRenderer());
        tree.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("upgrade.editor.tree"));
        TreeSpeedSearch.installOn(tree, true, p -> p.getLastPathComponent().toString());
        JBScrollPane treeScroll = new JBScrollPane(tree);
        treeScroll.setBorder(JBUI.Borders.empty());
        JPanel bottom = new JPanel(new BorderLayout());
        JBLabel treeTitle = new JBLabel(MavenDetectiveBundle.message("upgrade.editor.tree"));
        treeTitle.setFont(JBFont.label().asBold());
        treeTitle.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 12)));
        bottom.add(treeTitle, BorderLayout.NORTH);
        bottom.add(treeScroll, BorderLayout.CENTER);

        JBLabel diffTitle = new JBLabel(MavenDetectiveBundle.message("upgrade.editor.diff"));
        diffTitle.setFont(JBFont.label().asBold());
        diffTitle.setBorder(JBUI.Borders.empty(6, 12));
        top.add(diffTitle, BorderLayout.NORTH);
        top.add(diff.component(), BorderLayout.CENTER);
        noSteps.setBorder(JBUI.Borders.empty(8, 12));
        noSteps.setForeground(UIUtil.getContextHelpForeground());

        split.setFirstComponent(top);
        split.setSecondComponent(bottom);
        root.add(north, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

        file.panel.addResultListener(this::update, this);
        update();
    }

    /** Stand der Seite übernehmen (EDT). */
    private void update() {
        UpgradeSimulatorPanel panel = file.panel;
        UpgradeSimulator.Result res = panel.result();
        boolean busy = panel.isBusy();
        badges.removeAll();
        if (res == null) {
            headline.setText(MavenDetectiveBundle.message("upgrade.editor.none"));
            apply.setEnabled(false);
            fetch.setEnabled(false);
            again.setEnabled(!busy);
            warning.setVisible(false);
            info.setVisible(false);
            shown = null;
            diff.set(List.of());
            fillTree(null);
            return;
        }
        headline.setText(res.headline());
        int changed = res.changed().size();
        badges.add(new Badge(MavenDetectiveBundle.message("upgrade.editor.badge.modules", changed),
                changed == 0 ? DetectiveColors.SUCCESS_BACKGROUND : DetectiveColors.WARNING_BACKGROUND));
        if (!res.isComplete()) {
            badges.add(new Badge(MavenDetectiveBundle.message("upgrade.editor.badge.incomplete"), DetectiveColors.WARNING_BACKGROUND));
        }
        if (panel.isStale()) {
            badges.add(new Badge(MavenDetectiveBundle.message("state.stale"), DetectiveColors.WARNING_BACKGROUND));
        }

        // Warnbalken: neue Konflikte und gesenkte Versionen gehören vor das Übernehmen
        int risks = 0;
        int down = 0;
        for (UpgradeSimulator.ModuleResult m : res.getModules()) {
            risks += m.getRisks().size();
            for (UpgradeSimulator.Delta d : m.getDeltas()) {
                if (d.getKind() == UpgradeSimulator.Kind.DOWNGRADED) {
                    down++;
                }
            }
        }
        List<String> problems = new ArrayList<>(res.getProblems());
        if (panel.fetchError() != null) {
            problems.add(0, panel.fetchError().replace('\n', ' '));
        }
        if (risks > 0 || down > 0 || !problems.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (risks > 0) {
                parts.add(MavenDetectiveBundle.message("upgrade.editor.warning.risks", risks));
            }
            if (down > 0) {
                parts.add(MavenDetectiveBundle.message("upgrade.editor.warning.down", down));
            }
            String text = parts.isEmpty() ? "" : MavenDetectiveBundle.message("upgrade.editor.warning", String.join(", ", parts));
            warning.setText(text + (problems.isEmpty() ? "" : (text.isEmpty() ? "" : " ") + String.join(" ", problems)));
            warning.setVisible(true);
        } else {
            warning.setVisible(false);
        }
        if (!res.isComplete()) {
            List<String> m = res.getMissing();
            info.setText(MavenDetectiveBundle.message("upgrade.editor.incomplete", m.size(),
                    String.join(", ", m.subList(0, Math.min(4, m.size()))) + (m.size() > 4 ? ", …" : "")));
            info.setVisible(true);
        } else {
            info.setVisible(false);
        }

        shown = FixRunner.plan(res.fixes());
        diff.set(shown.files());
        if (shown.isEmpty()) {
            noSteps.setText(MavenDetectiveBundle.message("upgrade.editor.no.steps"));
            split.setFirstComponent(noSteps);
        } else if (split.getFirstComponent() == noSteps) {
            split.setFirstComponent(top);
        }
        apply.setEnabled(!busy && !shown.isEmpty() && !panel.isStale());
        fetch.setEnabled(!busy && !PomFetcher.coordinates(res.getMissing()).isEmpty());
        again.setEnabled(!busy);
        fillTree(res);
        badges.revalidate();
        root.revalidate();
        root.repaint();
    }

    private void fillTree(@Nullable UpgradeSimulator.Result res) {
        treeRoot.removeAllChildren();
        if (res != null) {
            for (UpgradeSimulator.ModuleResult m : res.getModules()) {
                if (m.getDeltas().isEmpty() && m.getRisks().isEmpty()) {
                    continue;
                }
                DefaultMutableTreeNode module = new DefaultMutableTreeNode(m);
                for (UpgradeSimulator.Risk r : m.getRisks()) {
                    module.add(new DefaultMutableTreeNode(r));
                }
                for (UpgradeSimulator.Delta d : m.getDeltas()) {
                    module.add(new DefaultMutableTreeNode(d));
                }
                treeRoot.add(module);
            }
        }
        ((DefaultTreeModel) tree.getModel()).reload();
        TreeUtil.expandAll(tree);
        tree.getEmptyText().setText(res == null ? MavenDetectiveBundle.message("upgrade.editor.none")
                : MavenDetectiveBundle.message("upgrade.empty.unchanged"));
    }

    /** Modul fett mit Zahl; Änderung mit farbigem Zeichen + Wort; neuer Konflikt mit rotem „!“. */
    private static final class DeltaRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row,
                                          boolean focus) {
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
            if (o instanceof UpgradeSimulator.ModuleResult m) {
                setIcon(AllIcons.Nodes.Module);
                append(m.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + MavenDetectiveBundle.message("upgrade.editor.module.detail", m.getDeltas().size(), m.getRisks().size()),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (m.getNote() != null) {
                    append("  · " + m.getNote(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            } else if (o instanceof UpgradeSimulator.Delta d) {
                append(" " + UpgradeLabels.symbol(d.getKind()) + " ", UpgradeLabels.symbolAttributes(d.getKind()));
                append(UpgradeLabels.word(d.getKind()) + "  ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UpgradeLabels.color(d.getKind())));
                append(d.label(), d.getKind() == UpgradeSimulator.Kind.REMOVED ? SimpleTextAttributes.GRAYED_ATTRIBUTES
                        : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + versions(d), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                DepNode n = d.getAfter() != null ? d.getAfter() : d.getBefore();
                append("  " + (n.depth() <= 1 ? MavenDetectiveBundle.message("upgrade.editor.direct")
                        : MavenDetectiveBundle.message("upgrade.editor.via", n.getParent().gav())), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                setToolTipText(d.message());
            } else if (o instanceof UpgradeSimulator.Risk r) {
                append(" ! ", UpgradeLabels.riskAttributes());
                append(MavenDetectiveBundle.message("upgrade.kind.risk") + "  ", UpgradeLabels.riskAttributes());
                append(r.ga(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + MavenDetectiveBundle.message("upgrade.editor.risk", r.getWanted(), r.getChosen(), r.getRequester()),
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                setToolTipText(r.message());
            }
        }

        private static String versions(UpgradeSimulator.Delta d) {
            String before = d.getBeforeVersion();
            String after = d.getAfterVersion();
            if (d.getKind() == UpgradeSimulator.Kind.SCOPE) {
                return d.getBefore().getScope() + " → " + d.getAfter().getScope();
            }
            return before == null ? after : after == null ? before : before + " → " + after;
        }
    }

    // ---- FileEditor ---------------------------------------------------------------------------

    @Override
    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return apply.isEnabled() ? apply : tree;
    }

    @Override
    public @Nls @NotNull String getName() {
        return MavenDetectiveBundle.message("upgrade.editor.name");
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public @Nullable FileEditorLocation getCurrentLocation() {
        return null;
    }

    @Override
    public void dispose() {
        // Listener und Diff hängen per Disposer an diesem Editor
    }
}
