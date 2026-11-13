package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import io.github.bitshiftdev.mavendetective.core.io.LogOutline;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Log-Navigator im Build Doctor: links die Gliederung des Laufs (erster Fehler, gescheitertes Modul,
 * Module mit Plugin-Schritten), rechts das Log in einem schreibgeschützten Editor – Download-Rauschen
 * eingeklappt, Fehlerzeilen markiert (auch im Scrollbalken). Ein Klick in der Gliederung springt zur
 * Zeile. Grundlage ist {@link LogOutline} aus dem Kern (dieselbe Logik wie {@code outline} in der CLI).
 */
final class LogNavigatorView extends JPanel implements Disposable {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private final Project project;
    private final JBLabel summary = new JBLabel();
    private final JBCheckBox foldDownloads = new JBCheckBox(MavenDetectiveBundle.message("log.fold.downloads"), true);
    private final ActionLink firstError;
    private final ActionLink failedModule;
    private final ActionLink previousError;
    private final ActionLink nextError;
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JPanel editorSlot = new JPanel(new BorderLayout());
    private @Nullable Editor editor;
    private @Nullable LogOutline outline;
    private @Nullable String shownLog;
    private final List<FoldRegion> folds = new ArrayList<>();

    /** Eintrag der Gliederung. */
    private record Node(@Nls String text, @Nullable String detail, int line, @Nullable Icon icon, boolean strong) {
    }

    LogNavigatorView(@NotNull Project project, @NotNull Runnable back) {
        super(new BorderLayout());
        this.project = project;

        JBLabel title = new JBLabel(MavenDetectiveBundle.message("log.title"));
        title.setFont(JBFont.h3().asBold());
        ActionLink backLink = new ActionLink(MavenDetectiveBundle.message("setup.back"), (ActionListener) e -> back.run());
        backLink.setIcon(AllIcons.Actions.Back);
        firstError = new ActionLink(MavenDetectiveBundle.message("log.first.error"), (ActionListener) e -> jumpFirstError());
        firstError.setIcon(AllIcons.General.Error);
        failedModule = new ActionLink(MavenDetectiveBundle.message("log.failed.module"), (ActionListener) e -> jumpFailedModule());
        previousError = new ActionLink(MavenDetectiveBundle.message("log.previous.error"), (ActionListener) e -> jumpError(-1));
        previousError.setIcon(AllIcons.Actions.PreviousOccurence);
        nextError = new ActionLink(MavenDetectiveBundle.message("log.next.error"), (ActionListener) e -> jumpError(1));
        nextError.setIcon(AllIcons.Actions.NextOccurence);
        foldDownloads.addItemListener(e -> applyFolding());

        JPanel links = new JPanel(new HorizontalLayout(JBUI.scale(16)));
        links.setOpaque(false);
        links.add(firstError);
        links.add(failedModule);
        links.add(previousError);
        links.add(nextError);
        links.add(foldDownloads);

        JPanel head = new JPanel(new VerticalLayout(JBUI.scale(6)));
        head.add(backLink);
        head.add(title);
        head.add(summary);
        head.add(links);
        head.setBorder(JBUI.Borders.empty(8, 12));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText(MavenDetectiveBundle.message("log.empty"));
        tree.addTreeSelectionListener(e -> {
            Node n = node(e.getNewLeadSelectionPath());
            if (n != null && n.line() >= 0) {
                scrollTo(n.line(), false);
            }
        });
        tree.getAccessibleContext().setAccessibleName(MavenDetectiveBundle.message("log.outline"));

        JBSplitter split = new JBSplitter(false, "MavenDetective.LogNavigator.split", 0.28f);
        JBScrollPane treeScroll = new JBScrollPane(tree);
        treeScroll.setBorder(JBUI.Borders.empty());
        split.setFirstComponent(treeScroll);
        split.setSecondComponent(editorSlot);
        split.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
        add(head, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    /** Log zeigen; derselbe Text wird nicht neu aufgebaut. {@code jumpToError}: gleich zum ersten Fehler. */
    void show(@Nullable String rawLog, boolean jumpToError) {
        String log = rawLog == null ? "" : rawLog;
        if (!log.equals(shownLog)) {
            shownLog = log;
            build(clean(log));
        }
        if (jumpToError) {
            jumpFirstError();
        }
    }

    /** ANSI-Farben weg, Fortschrittszeilen ({@code \r}) auf ihren letzten Stand – das Dokument kennt nur {@code \n}. */
    static List<String> clean(String log) {
        List<String> out = new ArrayList<>();
        for (String line : log.split("\\n", -1)) {
            String l = line.indexOf('\u001B') >= 0 ? ANSI.matcher(line).replaceAll("") : line;
            if (l.endsWith("\r")) {
                l = l.substring(0, l.length() - 1);
            }
            int cr = l.lastIndexOf('\r');
            out.add(cr >= 0 ? l.substring(cr + 1) : l);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private void build(List<String> lines) {
        LogOutline o = LogOutline.of(lines);
        outline = o;
        releaseEditor();
        Document doc = EditorFactory.getInstance().createDocument(String.join("\n", lines));
        doc.setReadOnly(true);
        Editor e = EditorFactory.getInstance().createViewer(doc, project);
        EditorSettings s = e.getSettings();
        s.setLineNumbersShown(true);
        s.setFoldingOutlineShown(true);
        s.setLineMarkerAreaShown(false);
        s.setUseSoftWraps(false);
        s.setCaretRowShown(true);
        editor = e;
        editorSlot.removeAll();
        editorSlot.add(e.getComponent(), BorderLayout.CENTER);

        TextAttributes error = new TextAttributes();
        error.setBackgroundColor(DetectiveColors.ERROR_BACKGROUND);
        error.setErrorStripeColor(JBColor.RED);
        for (int line : o.getErrorLines()) {
            RangeHighlighter h = e.getMarkupModel().addLineHighlighter(line, HighlighterLayer.ERROR, error);
            h.setErrorStripeTooltip(MavenDetectiveBundle.message("log.error.line", line + 1));
        }
        folds.clear();
        FoldingModel fm = e.getFoldingModel();
        fm.runBatchFoldingOperation(() -> {
            for (LogOutline.Fold f : o.getDownloads()) {
                int start = doc.getLineStartOffset(f.getStart());
                int end = doc.getLineEndOffset(f.getEnd() - 1);
                FoldRegion r = fm.addFoldRegion(start, end, LogOutline.placeholder(f.size()));
                if (r != null) {
                    folds.add(r);
                }
            }
        });
        applyFolding();

        String result = o.getBuildResult() == null ? MavenDetectiveBundle.message("log.result.unknown") : "BUILD " + o.getBuildResult().name();
        summary.setText(MavenDetectiveBundle.message("log.summary", lines.size(), result, o.getModules().size(),
                o.getErrorLines().size(), o.getDownloadLines(), String.valueOf(o.getDownloadLines())));
        firstError.setEnabled(o.getFirstError() >= 0);
        previousError.setEnabled(!o.getErrorLines().isEmpty());
        nextError.setEnabled(!o.getErrorLines().isEmpty());
        failedModule.setEnabled(o.getFailedModule() != null);
        foldDownloads.setEnabled(!o.getDownloads().isEmpty());
        buildTree(o);
        revalidate();
        repaint();
    }

    private void buildTree(LogOutline o) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (o.getFirstError() >= 0) {
            LogOutline.Module m = o.moduleAt(o.getFirstError());
            root.add(leaf(new Node(MavenDetectiveBundle.message("log.node.first.error", o.getFirstError() + 1),
                    excerpt(o.getLines().get(o.getFirstError())), o.getFirstError(), AllIcons.General.Error, true)));
            if (m == null && o.getFailedModule() == null) {
                // keine Gliederung in Module (z. B. Fehler beim Einlesen der POMs)
                root.add(leaf(new Node(MavenDetectiveBundle.message("log.node.errors", o.getErrorLines().size()), null,
                        o.getFirstError(), AllIcons.General.Error, false)));
            }
        }
        LogOutline.Module failed = o.getFailedModule();
        if (failed != null) {
            LogOutline.Step step = failed.getFailedStep();
            root.add(leaf(new Node(MavenDetectiveBundle.message("log.node.failed.module", failed.getName()),
                    step == null ? null : step.label(), failed.getFirstError() >= 0 ? failed.getFirstError() : failed.getStart(),
                    AllIcons.RunConfigurations.TestError, true)));
        }
        if (!o.getModules().isEmpty()) {
            DefaultMutableTreeNode modules = new DefaultMutableTreeNode(new Node(
                    MavenDetectiveBundle.message("log.node.modules", o.getModules().size()), null, -1, AllIcons.Nodes.ModuleGroup, false));
            for (LogOutline.Module m : o.getModules()) {
                String detail = m.getTime() != null ? m.getTime() : null;
                if (m.getErrors() > 0) {
                    String errors = MavenDetectiveBundle.message("log.node.module.errors", m.getErrors());
                    detail = detail == null ? errors : detail + " · " + errors;
                }
                DefaultMutableTreeNode mn = new DefaultMutableTreeNode(new Node(m.getName(), detail, m.getStart(), moduleIcon(m), m == failed));
                for (LogOutline.Step s : m.getSteps()) {
                    mn.add(leaf(new Node(s.label(), s.getErrors() > 0 ? MavenDetectiveBundle.message("log.node.module.errors", s.getErrors()) : null,
                            s.getLine(), s.getErrors() > 0 ? AllIcons.General.Error : AllIcons.Nodes.Plugin, s.getErrors() > 0)));
                }
                modules.add(mn);
            }
            root.add(modules);
        }
        if (o.getDownloadLines() > 0) {
            int first = o.getDownloads().isEmpty() ? -1 : o.getDownloads().get(0).getStart();
            root.add(leaf(new Node(MavenDetectiveBundle.message("log.node.downloads", o.getDownloadLines(), String.valueOf(o.getDownloadLines())), null, first,
                    AllIcons.Actions.Download, false)));
        }
        if (o.getResultLine() >= 0) {
            root.add(leaf(new Node(o.getLines().get(o.getResultLine()).replaceFirst("^.*?(BUILD )", "$1").trim(), null,
                    o.getResultLine(), o.getBuildResult() == LogOutline.Result.FAILURE
                    ? AllIcons.RunConfigurations.TestError : AllIcons.RunConfigurations.TestPassed, false)));
        }
        tree.setModel(new DefaultTreeModel(root));
        TreeUtil.expand(tree, 2);
    }

    private static Icon moduleIcon(LogOutline.Module m) {
        if (m.getResult() == null) {
            return m.getErrors() > 0 ? AllIcons.RunConfigurations.TestError : AllIcons.Nodes.Module;
        }
        return switch (m.getResult()) {
            case FAILURE -> AllIcons.RunConfigurations.TestError;
            case SKIPPED -> AllIcons.RunConfigurations.TestIgnored;
            case SUCCESS -> AllIcons.RunConfigurations.TestPassed;
        };
    }

    private static DefaultMutableTreeNode leaf(Node n) {
        return new DefaultMutableTreeNode(n, false);
    }

    private static String excerpt(String line) {
        String t = line.replaceFirst("^\\S*\\d{2}:\\d{2}:\\d{2}\\S*\\s+", "").replaceFirst("^\\[(?:ERROR|FATAL)\\]\\s*", "").trim();
        return t.length() > 120 ? t.substring(0, 119) + "…" : t;
    }

    private static @Nullable Node node(@Nullable TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode n)) {
            return null;
        }
        return n.getUserObject() instanceof Node node ? node : null;
    }

    // ---- Springen -------------------------------------------------------------------------------

    void jumpFirstError() {
        LogOutline o = outline;
        if (o != null && o.getFirstError() >= 0) {
            scrollTo(o.getFirstError(), true);
        }
    }

    private void jumpFailedModule() {
        LogOutline o = outline;
        if (o != null && o.getFailedModule() != null) {
            LogOutline.Module m = o.getFailedModule();
            scrollTo(m.getFirstError() >= 0 ? m.getFirstError() : m.getStart(), true);
        }
    }

    /** Nächster ({@code +1}) bzw. vorheriger ({@code -1}) Fehler ab der Cursor-Zeile, im Kreis. */
    private void jumpError(int direction) {
        LogOutline o = outline;
        Editor e = editor;
        if (o == null || e == null || o.getErrorLines().isEmpty()) {
            return;
        }
        int caret = e.getCaretModel().getLogicalPosition().line;
        List<Integer> errors = o.getErrorLines();
        int target = direction > 0 ? errors.get(0) : errors.get(errors.size() - 1);
        if (direction > 0) {
            for (int line : errors) {
                if (line > caret) {
                    target = line;
                    break;
                }
            }
        } else {
            for (int i = errors.size() - 1; i >= 0; i--) {
                if (errors.get(i) < caret) {
                    target = errors.get(i);
                    break;
                }
            }
        }
        scrollTo(target, true);
    }

    private void scrollTo(int line, boolean focus) {
        Editor e = editor;
        if (e == null || line < 0 || line >= e.getDocument().getLineCount()) {
            return;
        }
        e.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, 0));
        e.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        if (focus) {
            e.getContentComponent().requestFocusInWindow();
        }
    }

    private void applyFolding() {
        Editor e = editor;
        if (e == null) {
            return;
        }
        boolean fold = foldDownloads.isSelected();
        e.getFoldingModel().runBatchFoldingOperation(() -> {
            for (FoldRegion r : folds) {
                if (r.isValid()) {
                    r.setExpanded(!fold);
                }
            }
        });
    }

    /** Text der Cursor-Zeile bzw. der Auswahl im Baum (Strg+C über das Kontextmenü der Seite). */
    @Nullable String selectedText() {
        Node n = node(tree.getSelectionPath());
        return n == null ? null : n.text() + (n.detail() == null ? "" : " – " + n.detail());
    }

    private void releaseEditor() {
        Editor e = editor;
        editor = null;
        if (e != null && !e.isDisposed()) {
            EditorFactory.getInstance().releaseEditor(e);
        }
    }

    @Override
    public void dispose() {
        releaseEditor();
    }

    private static final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded, boolean leaf,
                                          int row, boolean hasFocus) {
            Node n = value instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof Node node ? node : null;
            if (n == null) {
                return;
            }
            setIcon(n.icon());
            append(n.text(), n.strong() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (n.detail() != null) {
                append("  " + n.detail(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            if (n.line() >= 0) {
                append("  :" + (n.line() + 1), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
        }
    }
}
