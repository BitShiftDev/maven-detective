package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE gegen Kommandozeile als Tabelle: je Aspekt (Maven, JDK, settings.xml, Profile, .mvn/ …) die
 * Werte beider Seiten. Abweichungen tragen Warn-Icon, „≠“ und Fettdruck, der Hintergrund unterstreicht
 * sie nur. Darunter steht, was bei der gewählten Abweichung zu tun ist.
 */
final class SetupComparisonView extends JPanel {

    private final JBLabel title = new JBLabel(MavenDetectiveBundle.message("setup.title"));
    private final JBLabel summary = new JBLabel();
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final JPanel hints = new JPanel(new VerticalLayout(JBUI.scale(4)));
    private @Nullable SetupComparison shown;

    SetupComparisonView(@NotNull Runnable back) {
        super(new BorderLayout());
        title.setFont(JBFont.h3().asBold());
        ActionLink backLink = new ActionLink(MavenDetectiveBundle.message("setup.back"), (ActionListener) e -> back.run());
        backLink.setIcon(AllIcons.Actions.Back);
        JPanel head = new JPanel(new VerticalLayout(JBUI.scale(4)));
        head.add(backLink);
        head.add(title);
        head.add(summary);
        head.setBorder(JBUI.Borders.empty(8, 12));

        table.setShowGrid(false);
        table.setStriped(false);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getEmptyText().setText(MavenDetectiveBundle.message("doctor.setup.loading"));
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showHints();
            }
        });
        hints.setBorder(JBUI.Borders.empty(8, 12));

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setBorder(JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1, 0, 1, 0));
        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(hints, BorderLayout.SOUTH);
    }

    /** Tabelle der gewählten Zeile bzw. aller Zeilen als Text (Strg+C). */
    @Nullable String selectedText() {
        int row = table.getSelectedRow();
        if (shown == null || row < 0) {
            return null;
        }
        SetupComparison.Row r = model.rows.get(row);
        return r.getAspect() + ": " + r.getLeft() + " | " + r.getRight();
    }

    void loading() {
        shown = null;
        table.getEmptyText().setText(MavenDetectiveBundle.message("doctor.setup.loading"));
        summary.setText("");
        model.set(null);
        showHints();
    }

    /** Kein Vergleich (Fehler oder Abbruch); ein schon gezeigter bleibt stehen. */
    void unavailable() {
        if (shown == null) {
            table.getEmptyText().setText(MavenDetectiveBundle.message("doctor.setup.none"));
        }
    }

    void show(@NotNull SetupComparison c) {
        shown = c;
        summary.setText(c.summary());
        model.set(c);
        int firstDiff = -1;
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).isDifferent()) {
                firstDiff = i;
                break;
            }
        }
        if (firstDiff >= 0) {
            table.getSelectionModel().setSelectionInterval(firstDiff, firstDiff);
        }
        showHints();
    }

    /** Hinweis zur gewählten Abweichung, sonst alle; dazu die Notizen beider Seiten. */
    private void showHints() {
        hints.removeAll();
        SetupComparison c = shown;
        if (c != null) {
            List<SetupComparison.Row> rows = new ArrayList<>();
            int sel = table.getSelectedRow();
            if (sel >= 0 && model.rows.get(sel).isDifferent()) {
                rows.add(model.rows.get(sel));
            } else {
                rows.addAll(c.getDifferences());
            }
            for (SetupComparison.Row r : rows) {
                if (r.getHint() != null && !r.getHint().isEmpty()) {
                    JBLabel aspect = new JBLabel(r.getAspect(), AllIcons.General.Warning, JBLabel.LEADING);
                    aspect.setFont(JBFont.label().asBold());
                    hints.add(aspect);
                    hints.add(DiagnosisCard.text(r.getHint(), JBFont.label()));
                }
            }
            List<String> notes = new ArrayList<>(c.getLeft().getNotes());
            notes.addAll(c.getRight().getNotes());
            for (String n : notes) {
                hints.add(DiagnosisCard.text(n, JBFont.small()));
            }
            JBTextArea foot = DiagnosisCard.text(MavenDetectiveBundle.message("setup.footnote"), JBFont.small());
            foot.setForeground(NamedColorUtil.getInactiveTextColor());
            hints.add(foot);
        }
        hints.revalidate();
        hints.repaint();
    }

    private static final class Model extends AbstractTableModel {
        private final List<SetupComparison.Row> rows = new ArrayList<>();
        private String left = MavenDetectiveBundle.message("setup.ide");
        private String right = MavenDetectiveBundle.message("setup.command.line");

        void set(@Nullable SetupComparison c) {
            rows.clear();
            if (c != null) {
                rows.addAll(c.getRows());
                left = c.getLeft().getLabel();
                right = c.getRight().getLabel();
            }
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> MavenDetectiveBundle.message("setup.column.aspect");
                case 1 -> left;
                default -> right;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            SetupComparison.Row r = rows.get(row);
            return switch (column) {
                case 0 -> r.getAspect();
                case 1 -> r.getLeft();
                default -> r.getRight();
            };
        }
    }

    private final class Renderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus,
                                             int row, int column) {
            SetupComparison.Row r = model.rows.get(row);
            boolean diff = r.isDifferent();
            SimpleTextAttributes attrs = diff ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
            if (column == 0) {
                setIcon(diff ? AllIcons.General.Warning : AllIcons.General.InspectionsOK);
                append(diff ? "≠ " : "", attrs);
            }
            append(String.valueOf(value), attrs);
            setToolTipText(diff ? r.getHint() : null);
            if (diff && !selected) {
                setBackground(DetectiveColors.WARNING_BACKGROUND);
            }
            getAccessibleContext().setAccessibleDescription(diff ? MavenDetectiveBundle.message("setup.different") : null);
        }
    }
}
