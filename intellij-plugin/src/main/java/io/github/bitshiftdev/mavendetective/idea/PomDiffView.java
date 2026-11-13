package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;

/**
 * Geplante POM-Änderungen im Diff-Viewer der IDE: links die betroffenen Dateien (ab zwei), rechts
 * vorher/nachher mit XML-Hervorhebung. Grundlage der Fix-Vorschau und des Upgrade-Ergebnisses.
 */
final class PomDiffView {

    private final Project project;
    private final DiffRequestPanel diff;
    private final JBList<PomChanges.FileChange> files = new JBList<>();
    private final JPanel root = new JPanel(new BorderLayout());

    PomDiffView(@NotNull Project project, @NotNull Disposable parent, @org.jetbrains.annotations.Nullable Window window) {
        this.project = project;
        diff = DiffManager.getInstance().createRequestPanel(project, parent, window);
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        files.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends PomChanges.FileChange> list, PomChanges.FileChange value,
                                                 int index, boolean selected, boolean focus) {
                append(label(value.file()));
                int n = value.edits().size();
                append("  " + MavenDetectiveBundle.message("preview.changes", n), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                setToolTipText(String.join("\n", value.descriptions()));
            }
        });
        files.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && files.getSelectedValue() != null) {
                show(files.getSelectedValue());
            }
        });
        files.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("preview.files"));
    }

    @NotNull JComponent component() {
        return root;
    }

    /** Dateien zeigen; die erste ist gewählt. Bei einer einzigen Datei entfällt die Liste. */
    void set(@NotNull List<PomChanges.FileChange> changes) {
        root.removeAll();
        if (changes.size() > 1) {
            files.setListData(changes.toArray(new PomChanges.FileChange[0]));
            JBScrollPane scroll = new JBScrollPane(files);
            scroll.setBorder(JBUI.Borders.empty());
            OnePixelSplitter split = new OnePixelSplitter(false, "MavenDetective.PomDiff.files", 0.22f);
            split.setFirstComponent(scroll);
            split.setSecondComponent(diff.getComponent());
            root.add(split, BorderLayout.CENTER);
            files.setSelectedIndex(0);
        } else {
            root.add(diff.getComponent(), BorderLayout.CENTER);
            if (!changes.isEmpty()) {
                show(changes.get(0));
            }
        }
        root.revalidate();
        root.repaint();
    }

    private void show(PomChanges.FileChange c) {
        DiffContentFactory f = DiffContentFactory.getInstance();
        DiffContent before = f.create(project, c.before(), XmlFileType.INSTANCE);
        DiffContent after = f.create(project, c.after(), XmlFileType.INSTANCE);
        diff.setRequest(new SimpleDiffRequest(label(c.file()), before, after,
                MavenDetectiveBundle.message("preview.before"), MavenDetectiveBundle.message("preview.after")));
    }

    /** {@code modul/pom.xml} – bei vielen gleichnamigen POMs braucht es das Verzeichnis. */
    static @NotNull String label(@NotNull Path file) {
        Path dir = file.getParent() == null ? null : file.getParent().getFileName();
        return (dir == null ? "" : dir + "/") + file.getFileName();
    }
}
