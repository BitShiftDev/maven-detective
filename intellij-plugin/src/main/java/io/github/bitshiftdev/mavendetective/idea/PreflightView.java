package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight;
import io.github.bitshiftdev.mavendetective.idea.ui.Badge;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionListener;

/**
 * Preflight im Build Doctor: Ampel mit Zusammenfassung, darunter je Thema (settings.xml, JDK, Maven,
 * Projekt, lokales Repository, Remote-Repositories) eine Zeile mit Status. Die gewählte Zeile zeigt
 * Belege (Datei-Links) und Quick-Fixes. „Repositories prüfen“ geht als einziges ins Netz.
 */
final class PreflightView extends JPanel {

    /** Was die Ansicht vom Build Doctor braucht. */
    interface Host {
        void back();

        /** Erneut prüfen; {@code network}: Repositories anfragen. */
        void check(boolean network);

        void runFix(@NotNull FixAction fix, @NotNull Preflight.Report report);
    }

    private final Project project;
    private final Host host;
    private final JBLabel summary = new JBLabel();
    private final JPanel badgeSlot = new JPanel(new BorderLayout());
    private final JBLabel network = new JBLabel();
    private final DefaultListModel<Preflight.Item> model = new DefaultListModel<>();
    private final JBList<Preflight.Item> list = new JBList<>(model);
    private final JPanel details = new JPanel(new VerticalLayout(JBUI.scale(6)));
    private @Nullable Preflight.Report shown;

    PreflightView(@NotNull Project project, @NotNull Host host) {
        super(new BorderLayout());
        this.project = project;
        this.host = host;

        JBLabel title = new JBLabel(MavenDetectiveBundle.message("preflight.title"));
        title.setFont(JBFont.h3().asBold());
        ActionLink back = new ActionLink(MavenDetectiveBundle.message("setup.back"), (ActionListener) e -> host.back());
        back.setIcon(AllIcons.Actions.Back);
        badgeSlot.setOpaque(false);
        JPanel line = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        line.setOpaque(false);
        line.add(badgeSlot);
        line.add(summary);
        ActionLink again = new ActionLink(MavenDetectiveBundle.message("preflight.again"), (ActionListener) e -> host.check(false));
        again.setIcon(AllIcons.Actions.Refresh);
        ActionLink repos = new ActionLink(MavenDetectiveBundle.message("preflight.network"), (ActionListener) e -> host.check(true));
        repos.setIcon(AllIcons.General.Web);
        repos.setToolTipText(MavenDetectiveBundle.message("preflight.network.tooltip"));
        JPanel links = new JPanel(new HorizontalLayout(JBUI.scale(16)));
        links.setOpaque(false);
        links.add(again);
        links.add(repos);
        network.setForeground(JBColor.GRAY);

        JPanel head = new JPanel(new VerticalLayout(JBUI.scale(6)));
        head.add(back);
        head.add(title);
        head.add(line);
        head.add(links);
        head.add(network);
        head.setBorder(JBUI.Borders.empty(8, 12));

        list.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.getEmptyText().setText(MavenDetectiveBundle.message("preflight.loading"));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
            }
        });
        details.setBorder(JBUI.Borders.empty(8, 12));

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0));
        JBScrollPane detailScroll = new JBScrollPane(details);
        detailScroll.setBorder(JBUI.Borders.empty());
        detailScroll.setPreferredSize(JBUI.size(200, 150));
        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(detailScroll, BorderLayout.SOUTH);
    }

    void loading() {
        model.clear();
        details.removeAll();
        list.getEmptyText().setText(MavenDetectiveBundle.message("preflight.loading"));
        revalidate();
        repaint();
    }

    void show(@NotNull Preflight.Report report) {
        shown = report;
        Preflight.Item keep = list.getSelectedValue();
        model.clear();
        report.getItems().forEach(model::addElement);
        badgeSlot.removeAll();
        badgeSlot.add(badge(report.getLight()), BorderLayout.CENTER);
        summary.setText(report.headline());
        network.setText(MavenDetectiveBundle.message(report.isNetworkChecked() ? "preflight.network.done" : "preflight.network.not"));
        getAccessibleContext().setAccessibleDescription(lightText(report.getLight()) + " – " + report.headline());
        // erste Zeile mit Problem wählen, sonst die erste
        int select = 0;
        for (int i = 0; i < model.size(); i++) {
            if (keep != null && model.get(i).getText().equals(keep.getText())) {
                select = i;
                break;
            }
            if (select == 0 && model.get(i).getStatus().ordinal() >= Preflight.Status.WARNING.ordinal()) {
                select = i;
            }
        }
        if (!model.isEmpty()) {
            list.setSelectedIndex(select);
            list.ensureIndexIsVisible(select);
        }
        showDetails();
        revalidate();
        repaint();
    }

    @Nullable Preflight.Report report() {
        return shown;
    }

    /** Gewählte Zeile als Text (Strg+C). */
    @Nullable String selectedText() {
        Preflight.Item item = list.getSelectedValue();
        return item == null ? null : item.getTopic().label() + ": " + item.getText();
    }

    private void showDetails() {
        details.removeAll();
        Preflight.Item item = list.getSelectedValue();
        Preflight.Report report = shown;
        if (item != null && report != null) {
            for (Evidence e : item.getEvidence()) {
                details.add(evidence(e));
            }
            JPanel fixes = fixes(item, report);
            if (fixes != null) {
                details.add(fixes);
            }
            for (FixAction f : item.getFixes()) {
                if (!FixRunner.isSupported(f)) {
                    JBLabel manual = new JBLabel("• " + f.getLabel(), AllIcons.General.Information, JBLabel.LEADING);
                    details.add(manual);
                }
            }
        }
        details.revalidate();
        details.repaint();
    }

    private JPanel evidence(Evidence e) {
        JPanel row = new JPanel(new VerticalLayout(JBUI.scale(2)));
        row.setOpaque(false);
        row.add(DiagnosisCard.text(e.getText(), JBFont.label()));
        if (e.getFile() != null) {
            String target = Locations.of(e.getFile(), e.getLine());
            ActionLink link = new ActionLink(DiagnosisCard.fileLabel(e.getFile(), e.getLine()),
                    (ActionListener) ev -> Locations.open(project, target));
            link.setToolTipText(target);
            row.add(link);
        }
        return row;
    }

    private @Nullable JPanel fixes(Preflight.Item item, Preflight.Report report) {
        java.util.List<FixAction> supported = DiagnosisCard.supportedFixes(item.getFixes());
        if (supported.isEmpty()) {
            return null;
        }
        JButton[] buttons = new JButton[supported.size()];
        for (int i = 0; i < supported.size(); i++) {
            FixAction fix = supported.get(i);
            JButton b = new JButton(fix.getLabel());
            b.addActionListener(e -> host.runFix(fix, report));
            buttons[i] = i == 0 ? FixBar.primary(b) : b;
        }
        return new FixBar(buttons);
    }

    static @Nls @NotNull String lightText(Preflight.Light light) {
        return switch (light) {
            case RED -> MavenDetectiveBundle.message("preflight.light.red");
            case YELLOW -> MavenDetectiveBundle.message("preflight.light.yellow");
            case GREEN -> MavenDetectiveBundle.message("preflight.light.green");
        };
    }

    static @NotNull Badge badge(Preflight.Light light) {
        Color bg = switch (light) {
            case RED -> DetectiveColors.ERROR_BACKGROUND;
            case YELLOW -> DetectiveColors.WARNING_BACKGROUND;
            case GREEN -> DetectiveColors.SUCCESS_BACKGROUND;
        };
        return new Badge(lightText(light), bg);
    }

    static @NotNull Icon icon(Preflight.Status status) {
        return switch (status) {
            case ERROR -> AllIcons.General.Error;
            case WARNING -> AllIcons.General.Warning;
            case INFO -> AllIcons.General.Information;
            case OK -> AllIcons.General.InspectionsOK;
        };
    }

    static @NotNull Icon icon(Preflight.Light light) {
        return switch (light) {
            case RED -> AllIcons.General.Error;
            case YELLOW -> AllIcons.General.Warning;
            case GREEN -> AllIcons.General.InspectionsOK;
        };
    }

    /** Status-Icon, Thema grau, Text; Status zusätzlich als Wort für Screenreader. */
    private static final class Renderer extends ColoredListCellRenderer<Preflight.Item> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Preflight.Item> l, Preflight.Item item, int index,
                                             boolean selected, boolean focus) {
            setIcon(icon(item.getStatus()));
            append(item.getTopic().label() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append(item.getText(), item.getStatus() == Preflight.Status.ERROR
                    ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            String status = switch (item.getStatus()) {
                case ERROR -> MavenDetectiveBundle.message("preflight.status.error");
                case WARNING -> MavenDetectiveBundle.message("preflight.status.warning");
                case INFO -> MavenDetectiveBundle.message("preflight.status.info");
                case OK -> MavenDetectiveBundle.message("preflight.status.ok");
            };
            setToolTipText(item.getText());
            getAccessibleContext().setAccessibleName(status + ": " + item.getTopic().label() + ", " + item.getText());
        }
    }
}
