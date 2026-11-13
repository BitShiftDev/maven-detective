package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.ui.JBColor;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.snapshot.Change;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.idea.ui.Badge;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectiveColors;
import io.github.bitshiftdev.mavendetective.idea.ui.FixBar;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import io.github.bitshiftdev.mavendetective.idea.ui.Section;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Diagnose-Karte aus Swing-Komponenten: Kopf mit Nummer, Kategorie, Sicherheit als Badge und
 * Zeitpunkt; die Ursache in einem Satz; ein primärer Fix als Standardknopf, weitere im Menü, nicht
 * automatisch ausführbare als Anleitung; Belege, Änderungen seit grün, IDE-Vergleich und weitere
 * Treffer aufklappbar; unten „Bericht kopieren · Falsch erkannt?“.
 *
 * <p>Die Karte wird bei jedem Wechsel neu aufgebaut; welche Abschnitte aufgeklappt sind, merkt sie
 * sich über Läufe hinweg.
 */
final class DiagnosisCard {

    /** Was die Karte nach außen auslöst (Panel). */
    interface Host {
        void runFix(@NotNull FixAction fix, @NotNull BuildDoctorService.Result result);

        void showSetup();

        void copyReport();

        void reportMisdiagnosis(@NotNull BuildDoctorService.Result result);
    }

    static final String SECTION_EVIDENCE = "evidence";
    static final String SECTION_CHANGES = "changes";
    static final String SECTION_SETUP = "setup";
    static final String SECTION_MORE = "more";
    /** Client-Property der roten Karte: Komponente mit der Ursache (Anker für „Erste Schritte“). */
    static final String CAUSE = "MavenDetective.cause";
    private static final int MAX_CHANGES = 30;
    private static final int TAIL_LINES = 15;

    private final Project project;
    private final Host host;
    private final Map<String, Boolean> expanded = new HashMap<>(Map.of(
            SECTION_EVIDENCE, false, SECTION_CHANGES, true, SECTION_SETUP, false, SECTION_MORE, false));

    DiagnosisCard(@NotNull Project project, @NotNull Host host) {
        this.project = project;
        this.host = host;
    }

    // ---- Karten ---------------------------------------------------------------------------------

    /** Karte für einen roten Lauf bzw. Log aus der Zwischenablage; {@code index} = gezeigte Diagnose. */
    @NotNull JComponent red(@NotNull BuildDoctorService.Run run, int index, @NotNull java.util.function.IntConsumer select) {
        BuildDoctorService.Result result = run.result();
        if (result == null || result.diagnoses().isEmpty()) {
            return unknown(run);
        }
        Diagnosis d = result.diagnoses().get(Math.min(index, result.diagnoses().size() - 1));
        JPanel card = frame();
        card.add(header(AllIcons.General.Error, headline(d), badge(d.getConfidence()), when(run)));
        JComponent cause = text(d.getCause(), JBFont.label().biggerOn(1));
        card.add(cause);
        card.putClientProperty(CAUSE, cause);
        accessible(card, MavenDetectiveBundle.message("card.accessible.red", headline(d), d.getConfidence().label()), d.getCause());
        JComponent fixes = fixes(d, result);
        if (fixes != null) {
            card.add(fixes);
        }
        JComponent manual = manualFixes(d);
        if (manual != null) {
            card.add(manual);
        }
        if (!d.getEvidence().isEmpty()) {
            card.add(section(SECTION_EVIDENCE, MavenDetectiveBundle.message("card.evidence", d.getEvidence().size()), evidence(d)));
        }
        SnapshotDiff changes = result.changes();
        if (changes != null) {
            card.add(section(SECTION_CHANGES, MavenDetectiveBundle.message("card.changes",
                    OverviewPanel.formatCreatedAt(changes.getGreen().getCreatedAt())), changes(changes)));
        }
        SetupComparison setup = result.setup();
        if (setup != null && !setup.getDifferences().isEmpty()) {
            card.add(section(SECTION_SETUP, MavenDetectiveBundle.message("card.setup", setup.getDifferences().size()), setup(setup)));
        }
        if (result.diagnoses().size() > 1) {
            card.add(section(SECTION_MORE, MavenDetectiveBundle.message("card.more", result.diagnoses().size() - 1),
                    more(result.diagnoses(), d, select)));
        }
        card.add(footer(result, true));
        return card;
    }

    /** Gescheitert, aber keine Fehlerzeile von Maven: letzte Zeilen zeigen, als Testfall melden lassen. */
    private @NotNull JComponent unknown(@NotNull BuildDoctorService.Run run) {
        JPanel card = frame();
        card.add(header(AllIcons.General.Warning, MavenDetectiveBundle.message("card.unknown.title"), null, when(run)));
        accessible(card, MavenDetectiveBundle.message("card.unknown.title"), MavenDetectiveBundle.message("card.unknown.text"));
        card.add(text(MavenDetectiveBundle.message("card.unknown.text"), JBFont.label()));
        card.add(code(tail(run.result() == null ? null : run.result().rawLog(), TAIL_LINES)));
        if (run.result() != null) {
            card.add(footer(run.result(), false));
        }
        return card;
    }

    /** Grüner Lauf: kurze Bestätigung, er ist die neue Referenz. */
    @NotNull JComponent green(@NotNull BuildDoctorService.Run run) {
        JPanel card = frame();
        card.add(header(AllIcons.General.InspectionsOK, MavenDetectiveBundle.message("card.green.title"),
                new Badge(MavenDetectiveBundle.message("card.green.badge"), DetectiveColors.SUCCESS_BACKGROUND), when(run)));
        card.add(text(MavenDetectiveBundle.message("card.green.text"), JBFont.label()));
        accessible(card, MavenDetectiveBundle.message("card.green.title"), MavenDetectiveBundle.message("card.green.text"));
        return card;
    }

    // ---- Bausteine ------------------------------------------------------------------------------

    /** Screenreader: Name (Art, Sicherheit) und Beschreibung (Ursache) der ganzen Karte. */
    private static void accessible(JComponent card, @Nls String name, @Nls String description) {
        card.getAccessibleContext().setAccessibleName(name);
        card.getAccessibleContext().setAccessibleDescription(description);
    }

    private static JPanel frame() {
        JPanel card = new JPanel(new VerticalLayout(JBUI.scale(10)));
        card.setBorder(JBUI.Borders.compound(new RoundedLineBorder(JBColor.border(), JBUI.scale(10)), JBUI.Borders.empty(12, 14)));
        return card;
    }

    private static JComponent header(Icon icon, @Nls String title, @Nullable JComponent badge, @Nls String time) {
        JBLabel label = new JBLabel(title, icon, JBLabel.LEADING);
        label.setFont(JBFont.h3().asBold());
        label.setIconTextGap(JBUI.scale(8));
        JPanel right = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        right.setOpaque(false);
        if (badge != null) {
            right.add(badge);
        }
        JBLabel at = new JBLabel(time);
        at.setForeground(NamedColorUtil.getInactiveTextColor());
        right.add(at);
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    static @Nls @NotNull String headline(@NotNull Diagnosis d) {
        return (d.getNumber() > 0 ? "#" + d.getNumber() + " " : "") + d.getCategory();
    }

    private static Badge badge(Confidence c) {
        return new Badge(c.label(), c == Confidence.CERTAIN ? DetectiveColors.SUCCESS_BACKGROUND : DetectiveColors.WARNING_BACKGROUND);
    }

    private static @Nls String when(BuildDoctorService.Run run) {
        String time = OverviewPanel.time(run.at());
        return run.fromClipboard() ? MavenDetectiveBundle.message("card.from.clipboard", time)
                : run.name() == null ? time : run.name() + " · " + time;
    }

    /** Umbrechender, markierbarer Text ohne Rahmen. */
    static JBTextArea text(@Nls String text, Font font) {
        JBTextArea area = new JBTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty());
        area.setFont(font);
        return area;
    }

    /** Logzeilen: Schrift des Editors, Hintergrund wie ein Codeblock. */
    private static JComponent code(String text) {
        JBTextArea area = text(text, EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        area.setOpaque(true);
        area.setBackground(DetectiveColors.CODE_BACKGROUND);
        area.setBorder(JBUI.Borders.empty(4, 8));
        return area;
    }

    /** Erster ausführbarer Fix als Standardknopf, weitere ausführbare im Menü „Weitere Fixes“. */
    private @Nullable JComponent fixes(Diagnosis d, BuildDoctorService.Result result) {
        List<FixAction> supported = supportedFixes(d.getFixes());
        if (supported.isEmpty()) {
            return null;
        }
        JPanel row = new JPanel(new HorizontalLayout(JBUI.scale(12)));
        row.setOpaque(false);
        FixAction first = supported.get(0);
        JButton primary = FixBar.primary(new JButton(first.getLabel()));
        primary.addActionListener(e -> host.runFix(first, result));
        row.add(primary);
        if (supported.size() > 1) {
            ActionLink more = new ActionLink(MavenDetectiveBundle.message("card.more.fixes"));
            more.setDropDownLinkIcon();
            more.addActionListener(e -> {
                DefaultActionGroup group = new DefaultActionGroup();
                for (FixAction fix : supported.subList(1, supported.size())) {
                    group.add(DumbAwareAction.create(fix.getLabel(), ev -> host.runFix(fix, result)));
                }
                ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(null, group,
                        DataManager.getInstance().getDataContext(more), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
                popup.showUnderneathOf(more);
            });
            row.add(more);
        }
        return row;
    }

    /** Fixes, die das Plugin nicht selbst ausführt: als Anleitung, nicht als ausgegrauter Knopf. */
    private static @Nullable JComponent manualFixes(Diagnosis d) {
        List<FixAction> manual = new ArrayList<>();
        for (FixAction f : d.getFixes()) {
            if (!FixRunner.isSupported(f)) {
                manual.add(f);
            }
        }
        if (manual.isEmpty()) {
            return null;
        }
        JPanel box = new JPanel(new VerticalLayout(JBUI.scale(4)));
        box.setOpaque(false);
        JBLabel title = new JBLabel(MavenDetectiveBundle.message("card.manual"), AllIcons.General.Information, JBLabel.LEADING);
        title.setFont(JBFont.label().asBold());
        box.add(title);
        for (FixAction f : manual) {
            JBTextArea step = text("• " + f.getLabel(), JBFont.label());
            step.setBorder(JBUI.Borders.emptyLeft(20));
            box.add(step);
        }
        return box;
    }

    /** Reihenfolge des Katalogs, nur was {@link FixRunner} ausführen kann. */
    static @NotNull List<FixAction> supportedFixes(@NotNull List<FixAction> fixes) {
        List<FixAction> out = new ArrayList<>();
        for (FixAction f : fixes) {
            if (FixRunner.isSupported(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private JComponent evidence(Diagnosis d) {
        JPanel box = new JPanel(new VerticalLayout(JBUI.scale(6)));
        box.setOpaque(false);
        for (Evidence e : d.getEvidence()) {
            if (e.getKind() == Evidence.Kind.LOG_LINE && e.getFile() == null) {
                box.add(code(e.getText()));
            } else if (e.getFile() != null) {
                JPanel row = new JPanel(new VerticalLayout(JBUI.scale(2)));
                row.setOpaque(false);
                row.add(text(e.getText(), JBFont.label()));
                String target = Locations.of(e.getFile(), e.getLine());
                ActionLink link = new ActionLink(fileLabel(e.getFile(), e.getLine()), (ActionListener) ev -> Locations.open(project, target));
                link.setToolTipText(target);
                row.add(link);
                box.add(row);
            } else {
                box.add(text(e.getText(), JBFont.label()));
            }
        }
        return box;
    }

    /** {@code datei.xml:12} – voller Pfad steht im Tooltip. */
    static @NotNull String fileLabel(@NotNull String file, int line) {
        Path name;
        try {
            name = Paths.get(file).getFileName();
        } catch (RuntimeException e) {
            name = null;
        }
        return (name == null ? file : name.toString()) + (line > 0 ? ":" + line : "");
    }

    /** Zweispaltig: Bereich (JDK, Parent …) und vorher → nachher. */
    private static JComponent changes(SnapshotDiff diff) {
        if (diff.isEmpty()) {
            return text(MavenDetectiveBundle.message("card.changes.none"), JBFont.label());
        }
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        c.insets = JBUI.insets(1, 0, 1, 12);
        List<Change> all = diff.getChanges();
        for (int i = 0; i < all.size() && i < MAX_CHANGES; i++) {
            Change ch = all.get(i);
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            JBLabel area = new JBLabel(ch.getArea().label());
            area.setForeground(NamedColorUtil.getInactiveTextColor());
            grid.add(area, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            JBTextArea value = text(changeText(ch), JBFont.label());
            if (ch.getSubject() != null) {
                value.setToolTipText(ch.getSubject());
            }
            grid.add(value, c);
        }
        if (all.size() > MAX_CHANGES) {
            c.gridy = MAX_CHANGES;
            c.gridx = 1;
            grid.add(new JBLabel(MavenDetectiveBundle.message("card.changes.more", all.size() - MAX_CHANGES)), c);
        }
        return grid;
    }

    /**
     * Wert einer Änderung ohne den Bereich: „spring-boot-starter-parent 3.4.1 → 3.5.0 (2 Module)“.
     * Von {@code groupId:artifactId} bleibt die artifactId; der volle Name steht im Tooltip.
     */
    static @NotNull String changeText(@NotNull Change ch) {
        StringBuilder sb = new StringBuilder();
        String subject = ch.getSubject();
        if (subject != null && !subject.isEmpty()) {
            int colon = subject.lastIndexOf(':');
            sb.append(colon >= 0 && colon < subject.length() - 1 ? subject.substring(colon + 1) : subject).append(' ');
        }
        if (ch.getBefore() == null) {
            sb.append(MavenDetectiveBundle.message("card.change.added", String.valueOf(ch.getAfter())));
        } else if (ch.getAfter() == null) {
            sb.append(MavenDetectiveBundle.message("card.change.removed", ch.getBefore()));
        } else {
            sb.append(ch.getBefore()).append("  →  ").append(ch.getAfter());
        }
        int modules = ch.getModules().size();
        if (modules > 1) {
            sb.append("  ").append(MavenDetectiveBundle.message("card.change.modules", modules));
        }
        return sb.toString().trim();
    }

    private JComponent setup(SetupComparison setup) {
        JPanel box = new JPanel(new VerticalLayout(JBUI.scale(6)));
        box.setOpaque(false);
        for (SetupComparison.Row r : setup.getDifferences()) {
            JBLabel aspect = new JBLabel("≠ " + r.getAspect(), AllIcons.General.Warning, JBLabel.LEADING);
            aspect.setFont(JBFont.label().asBold());
            box.add(aspect);
            box.add(text(r.getLeft() + "  ≠  " + r.getRight(), JBFont.label()));
            if (r.getHint() != null && !r.getHint().isEmpty()) {
                JBTextArea hint = text(r.getHint(), JBFont.small());
                hint.setForeground(NamedColorUtil.getInactiveTextColor());
                box.add(hint);
            }
        }
        box.add(new ActionLink(MavenDetectiveBundle.message("card.setup.show"), (ActionListener) e -> host.showSetup()));
        return box;
    }

    private static JComponent more(List<Diagnosis> all, Diagnosis shown, java.util.function.IntConsumer select) {
        JPanel box = new JPanel(new VerticalLayout(JBUI.scale(4)));
        box.setOpaque(false);
        for (int i = 0; i < all.size(); i++) {
            Diagnosis d = all.get(i);
            if (d == shown) {
                continue;
            }
            int index = i;
            ActionLink link = new ActionLink(headline(d) + " · " + d.getConfidence().label(), (ActionListener) e -> select.accept(index));
            link.setToolTipText(d.getCause());
            box.add(link);
        }
        return box;
    }

    private JComponent footer(BuildDoctorService.Result result, boolean report) {
        JPanel row = new JPanel(new HorizontalLayout(JBUI.scale(6)));
        row.setOpaque(false);
        if (report) {
            row.add(new ActionLink(MavenDetectiveBundle.message("doctor.copy.report"), (ActionListener) e -> host.copyReport()));
            JBLabel dot = new JBLabel("·");
            dot.setForeground(NamedColorUtil.getInactiveTextColor());
            row.add(dot);
        }
        row.add(new ActionLink(MavenDetectiveBundle.message("card.misdiagnosis"), (ActionListener) e -> host.reportMisdiagnosis(result)));
        return row;
    }

    /** Aufklappbarer Abschnitt; der Zustand gilt für alle folgenden Karten. */
    private JComponent section(String id, @Nls String title, JComponent content) {
        return new Section(title, content, expanded.getOrDefault(id, false), on -> expanded.put(id, on));
    }

    /** Letzte nicht leere Zeilen, ohne ANSI-Farbcodes. */
    static @NotNull String tail(@Nullable String log, int lines) {
        if (log == null || log.isBlank()) {
            return MavenDetectiveBundle.message("card.unknown.no.output");
        }
        String[] all = log.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").split("\\R");
        List<String> out = new ArrayList<>();
        for (int i = all.length - 1; i >= 0 && out.size() < lines; i--) {
            if (!all[i].isBlank()) {
                out.add(0, all[i]);
            }
        }
        return String.join("\n", out);
    }
}
