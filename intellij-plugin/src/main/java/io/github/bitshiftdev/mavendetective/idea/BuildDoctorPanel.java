package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import io.github.bitshiftdev.mavendetective.idea.BuildDoctorService.FixCheck;
import io.github.bitshiftdev.mavendetective.idea.BuildDoctorService.Run;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.TrackingPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Objects;

/**
 * Tab „Build Doctor“: links der Verlauf der letzten Läufe (rot/grün, Zeit, Kategorie), rechts die
 * Diagnose-Karte des gewählten Laufs ({@link DiagnosisCard}) oder der Vergleich IDE gegen
 * Kommandozeile ({@link SetupComparisonView}). Über der Karte: „Maven-Lauf läuft …“ bzw. nach einem
 * Quick-Fix „Neu bauen läuft …“ und danach „Behoben“ oder was sich geändert hat.
 */
public final class BuildDoctorPanel extends DetectivePanel implements BuildDoctorService.Listener, DiagnosisCard.Host {

    private static final String VIEW_CARD = "card";
    private static final String VIEW_SETUP = "setup";

    private final BuildDoctorService service;
    private final DefaultListModel<Run> historyModel = new DefaultListModel<>();
    private final JBList<Run> history = new JBList<>(historyModel);
    private final DiagnosisCard cards;
    /** „Got it“ zur Karte in dieser Sitzung schon angefordert. */
    private boolean firstStepsShown;
    private final CardLayout views = new CardLayout();
    private final JPanel right = new JPanel(views);
    private final JPanel cardHolder = new TrackingPanel(new BorderLayout());
    private final JBPanelWithEmptyText empty = new JBPanelWithEmptyText();
    private final JBScrollPane cardScroll;
    private final EditorNotificationPanel runningBanner = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
    /** Ausgang der Prüfung nach einem Fix; enthält je Lauf ein neues Banner (Status ist fest). */
    private final JPanel checkSlot = new JPanel(new BorderLayout());
    private final SetupComparisonView setupView;
    private final Background setupCheck = background(MavenDetectiveBundle.message("doctor.setup.loading"));
    private @Nullable Run shown;
    private int diagnosisIndex;
    private boolean setupVisible;

    public BuildDoctorPanel(@NotNull Project project) {
        super(project);
        this.service = BuildDoctorService.getInstance(project);
        this.cards = new DiagnosisCard(project, this);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.AnalyzeClipboard"));
        actions.add(registered("MavenDetective.CompareSetup"));
        actions.addSeparator();
        actions.add(registered("MavenDetective.CopyMarkdown"));
        actions.add(registered("MavenDetective.ReportMisdiagnosis"));
        installToolbar(null, actions, null);

        history.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.setCellRenderer(new HistoryRenderer());
        history.getEmptyText().setText(MavenDetectiveBundle.message("doctor.history.empty"));
        history.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Run run = history.getSelectedValue();
                if (run != null && run != shown) {
                    show(run, 0);
                }
            }
        });

        empty.getEmptyText().setText(MavenDetectiveBundle.message("doctor.empty"));
        empty.getEmptyText().appendLine(MavenDetectiveBundle.message("doctor.empty.clipboard"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                e -> ActionManager.getInstance().tryToExecute(registered("MavenDetective.AnalyzeClipboard"), null, this,
                        ActionPlaces.UNKNOWN, true));
        empty.getEmptyText().appendLine(MavenDetectiveBundle.message("doctor.empty.reference"));

        runningBanner.icon(AnimatedIcon.Default.INSTANCE);
        runningBanner.setVisible(false);
        checkSlot.setVisible(false);
        JPanel banners = new JPanel(new VerticalLayout(0));
        banners.add(runningBanner);
        banners.add(checkSlot);

        cardHolder.setBorder(JBUI.Borders.empty(10, 12));
        cardScroll = new JBScrollPane(cardHolder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        cardScroll.setBorder(JBUI.Borders.empty());
        JPanel cardView = new JPanel(new BorderLayout());
        cardView.add(banners, BorderLayout.NORTH);
        cardView.add(cardScroll, BorderLayout.CENTER);

        setupView = new SetupComparisonView(this::showCardView);
        right.add(cardView, VIEW_CARD);
        right.add(setupView, VIEW_SETUP);

        JBSplitter split = new JBSplitter(false, "MavenDetective.BuildDoctor.split", 0.22f);
        split.setFirstComponent(rows(history, () -> null, () -> {
            Run run = history.getSelectedValue();
            return run == null ? null : historyText(run);
        }));
        split.setSecondComponent(right);
        setBody(split);

        service.addListener(this, this);
        reloadHistory(true);
        updateRunning();
    }

    // ---- Ereignisse ---------------------------------------------------------------------------

    @Override
    public void resultChanged(BuildDoctorService.Result result) {
        // Zwischenablage ohne Fehlerzeilen landet nicht im Verlauf – nur sagen, warum nichts passiert
        setStatus(result.environment() == null && result.diagnoses().isEmpty()
                ? MavenDetectiveBundle.message("doctor.clipboard.no.errors") : null);
    }

    @Override
    public void historyChanged(@NotNull Run added) {
        reloadHistory(true);
    }

    @Override
    public void runningChanged() {
        updateRunning();
    }

    // ---- Anzeige ------------------------------------------------------------------------------

    /** Liste neu füllen; {@code newest}: den neuesten Lauf zeigen (sonst die Auswahl behalten). */
    private void reloadHistory(boolean newest) {
        Run keep = newest ? null : shown;
        List<Run> runs = service.getHistory();
        historyModel.clear();
        runs.forEach(historyModel::addElement);
        Run target = keep != null && runs.contains(keep) ? keep : runs.isEmpty() ? null : runs.get(0);
        if (target == null) {
            shown = null;
            showCard(null);
        } else {
            history.setSelectedValue(target, true);
            if (target != shown) {
                show(target, 0);
            }
        }
    }

    private void show(@NotNull Run run, int index) {
        shown = run;
        diagnosisIndex = index;
        showCardView();
        if (run.green()) {
            showCard(cards.green(run));
        } else {
            JComponent card = cards.red(run, index, i -> show(run, i));
            showCard(card);
            // Erste Schritte: beim ersten roten Build zeigen, wo die Ursache steht (einmal je Installation)
            if (!firstStepsShown && card.getClientProperty(DiagnosisCard.CAUSE) instanceof JComponent cause) {
                firstStepsShown = true;
                FirstSteps.redBuild(cause, this);
            }
        }
        updateCheckBanner(run);
    }

    private void showCard(@Nullable JComponent card) {
        cardHolder.removeAll();
        cardHolder.add(card == null ? empty : card, card == null ? BorderLayout.CENTER : BorderLayout.NORTH);
        if (card == null) {
            checkSlot.removeAll();
            checkSlot.setVisible(false);
        }
        cardHolder.revalidate();
        cardHolder.repaint();
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    /** Ausgang der Fix-und-Prüf-Schleife über der Karte. */
    private void updateCheckBanner(Run run) {
        checkSlot.removeAll();
        FixCheck check = run.check();
        if (check != null) {
            String key;
            EditorNotificationPanel.Status status;
            if (run.green()) {
                key = "doctor.check.fixed";
                status = EditorNotificationPanel.Status.Success;
            } else if (check.previousRule() != null && Objects.equals(check.previousRule(), run.ruleId())) {
                key = "doctor.check.same";
                status = EditorNotificationPanel.Status.Warning;
            } else {
                key = "doctor.check.other";
                status = EditorNotificationPanel.Status.Info;
            }
            EditorNotificationPanel banner = new EditorNotificationPanel(status);
            banner.setText(MavenDetectiveBundle.message(key, check.fix()));
            checkSlot.add(banner, BorderLayout.CENTER);
        }
        updateRunning();
    }

    private void updateRunning() {
        boolean running = service.isRunning();
        FixCheck pending = service.getPendingCheck();
        runningBanner.setText(pending != null
                ? MavenDetectiveBundle.message("doctor.running.check", pending.fix())
                : MavenDetectiveBundle.message("doctor.running"));
        runningBanner.setVisible(running);
        // Während neu gebaut wird, gehört „Behoben/gleiche Ursache“ des alten Laufs nicht mehr dazu
        checkSlot.setVisible(!running && checkSlot.getComponentCount() > 0);
        checkSlot.revalidate();
    }

    private void showCardView() {
        setupVisible = false;
        views.show(right, VIEW_CARD);
    }

    /** Neuesten Lauf zeigen (Link „Ursache anzeigen“ in der Konsole, Benachrichtigung). */
    void showLatest() {
        if (!historyModel.isEmpty()) {
            Run newest = historyModel.get(0);
            history.setSelectedValue(newest, true);
            show(newest, 0);
        } else {
            showCardView();
        }
    }

    /** Kurze Meldung in der Statuszeile (z. B. leere Zwischenablage); die Karte bleibt. */
    void showMessage(@NotNull String text) {
        showCardView();
        setStatus(text);
    }

    /** Vergleich IDE gegen Kommandozeile anzeigen; ohne fertigen Vergleich wird er jetzt ermittelt. */
    void showSetupView() {
        setupVisible = true;
        views.show(right, VIEW_SETUP);
        BuildDoctorService.Result result = shownResult();
        SetupComparison known = result == null ? null : result.setup();
        if (known != null) {
            setupView.show(known);
            return;
        }
        setupView.loading();
        BuildDoctorService.Result last = service.getLast();
        setupCheck.run(c -> service.compareSetups(last == null ? null : last.configuration()), comparison -> {
            if (comparison != null) {
                setupView.show(comparison);
            } else {
                setStatus(MavenDetectiveBundle.message("doctor.setup.failed"));
            }
        });
    }

    /** Ergebnis des gezeigten Laufs ({@code null}: keiner oder grün). */
    @Nullable BuildDoctorService.Result shownResult() {
        return shown == null ? null : shown.result();
    }

    // ---- Karte → Panel ------------------------------------------------------------------------

    @Override
    public void runFix(@NotNull FixAction fix, @NotNull BuildDoctorService.Result result) {
        FixRunner.run(project, fix, result);
    }

    @Override
    public void showSetup() {
        showSetupView();
    }

    @Override
    public void copyReport() {
        String md = markdown();
        if (md != null) {
            CopyPasteManager.getInstance().setContents(new StringSelection(md));
            setStatus(MavenDetectiveBundle.message("doctor.report.copied"));
        }
    }

    @Override
    public void reportMisdiagnosis(@NotNull BuildDoctorService.Result result) {
        new MisdiagnosisDialog(project, result).show();
    }

    // ---- gemeinsame Aktionen ------------------------------------------------------------------

    @Override
    public boolean canCopyMarkdown() {
        BuildDoctorService.Result r = shownResult();
        return !setupVisible && r != null && !r.diagnoses().isEmpty();
    }

    @Override
    public @Nullable String markdown() {
        return canCopyMarkdown() ? report(Objects.requireNonNull(shownResult())) : null;
    }

    @Override
    public @Nullable String copyMarkdownText() {
        return MavenDetectiveBundle.message("doctor.copy.report");
    }

    static String report(BuildDoctorService.Result r) {
        String md = Reports.markdown(r.diagnoses(), r.failure(), r.changes());
        SetupComparison setup = r.setup();
        if (setup != null && !setup.getDifferences().isEmpty()) {
            md = md.replace(Reports.footer(),
                    "\n**" + MavenDetectiveBundle.message("setup.title") + "**\n\n" + setup.markdown() + Reports.footer());
        }
        return md;
    }

    // ---- Verlauf ------------------------------------------------------------------------------

    /** Eine Zeile Verlauf als Text: „14:02 · #4 Kaputtes JAR · sicher“. */
    static @NotNull String historyText(@NotNull Run run) {
        StringBuilder sb = new StringBuilder(OverviewPanel.time(run.at())).append(" · ").append(historyLabel(run));
        if (run.result() != null && !run.result().diagnoses().isEmpty()) {
            sb.append(" · ").append(DiagnosisCard.confidence(run.result().diagnoses().get(0).getConfidence()));
        }
        return sb.toString();
    }

    static @Nls @NotNull String historyLabel(@NotNull Run run) {
        if (run.green()) {
            return MavenDetectiveBundle.message("doctor.history.green");
        }
        List<Diagnosis> ds = run.result() == null ? List.of() : run.result().diagnoses();
        return ds.isEmpty() ? MavenDetectiveBundle.message("doctor.history.unknown") : DiagnosisCard.headline(ds.get(0));
    }

    private static final class HistoryRenderer extends ColoredListCellRenderer<Run> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Run> list, Run run, int index, boolean selected, boolean focus) {
            setIcon(run.green() ? AllIcons.RunConfigurations.TestPassed
                    : run.fromClipboard() ? AllIcons.Actions.MenuPaste : AllIcons.RunConfigurations.TestError);
            append(OverviewPanel.time(run.at()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append("  " + historyLabel(run));
            if (run.check() != null && run.green()) {
                append("  " + MavenDetectiveBundle.message("doctor.history.fixed"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
            String origin = run.fromClipboard() ? MavenDetectiveBundle.message("doctor.history.clipboard") : run.name();
            setToolTipText(origin);
            getAccessibleContext().setAccessibleName(historyText(run) + (origin == null ? "" : " · " + origin));
        }
    }
}
