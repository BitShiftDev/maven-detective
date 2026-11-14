package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
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
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight;
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
 * Diagnose-Karte des gewählten Laufs ({@link DiagnosisCard}), der Vergleich IDE gegen
 * Kommandozeile ({@link SetupComparisonView}), der Preflight vor dem Build ({@link PreflightView}) oder
 * das Log des Laufs im Log-Navigator ({@link LogNavigatorView}). Über der Karte: „Maven-Lauf läuft …“ bzw. nach einem
 * Quick-Fix „Neu bauen läuft …“ und danach „Behoben“ oder was sich geändert hat.
 */
public final class BuildDoctorPanel extends DetectivePanel implements BuildDoctorService.Listener, DiagnosisCard.Host,
        PreflightView.Host {

    private static final String VIEW_CARD = "card";
    private static final String VIEW_SETUP = "setup";
    private static final String VIEW_PREFLIGHT = "preflight";
    private static final String VIEW_LOG = "log";

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
    private final PreflightView preflightView;
    private final Background preflightCheck = background(MavenDetectiveBundle.message("preflight.loading"));
    private final LogNavigatorView logView;
    private @Nullable Run shown;
    private int diagnosisIndex;
    /** Welche Ansicht rechts steht (VIEW_*). */
    private String view = VIEW_CARD;

    public BuildDoctorPanel(@NotNull Project project) {
        super(project);
        this.service = BuildDoctorService.getInstance(project);
        this.cards = new DiagnosisCard(project, this);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.AnalyzeClipboard"));
        actions.add(registered("MavenDetective.CompareSetup"));
        actions.add(registered("MavenDetective.Preflight"));
        actions.add(registered("MavenDetective.ShowLog"));
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
        preflightView = new PreflightView(project, this);
        logView = new LogNavigatorView(project, this::showCardView);
        Disposer.register(this, logView);
        right.add(cardView, VIEW_CARD);
        right.add(setupView, VIEW_SETUP);
        right.add(preflightView, VIEW_PREFLIGHT);
        right.add(logView, VIEW_LOG);

        JBSplitter split = new JBSplitter(false, "MavenDetective.BuildDoctor.split", 0.22f);
        split.setFirstComponent(rows(history, () -> null, () -> {
            Run run = history.getSelectedValue();
            return run == null ? null : historyText(run);
        }));
        split.setSecondComponent(right);
        setBody(split);

        service.addListener(this, this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(DetectiveSettings.Listener.TOPIC,
                (DetectiveSettings.Listener) () -> {
                    if (VIEW_PREFLIGHT.equals(view) && !DetectiveSettings.getInstance().isPreflight()) {
                        showCardView();
                    }
                });
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
        showView(VIEW_CARD);
    }

    private void showView(String id) {
        view = id;
        views.show(right, id);
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
        showView(VIEW_SETUP);
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
                setupView.unavailable();
                setStatus(MavenDetectiveBundle.message("doctor.setup.failed"));
            }
        });
    }

    /** Preflight anzeigen: letztes Ergebnis sofort, dann ohne Netz neu prüfen (liest nur Dateien). */
    void showPreflight() {
        if (!DetectiveSettings.getInstance().isPreflight()) {
            showCardView();   // abgeschaltet (z. B. Sprung von einer alten Kachel)
            return;
        }
        showView(VIEW_PREFLIGHT);
        Preflight.Report known = service.getLastPreflight();
        if (known != null) {
            preflightView.show(known);
        }
        check(false);
    }

    @Override
    public void back() {
        showCardView();
    }

    /** Abbruch oder Fehler: Ansichten, die noch „lädt …“ zeigen, auf „kein Ergebnis“ setzen. */
    @Override
    protected void aborted() {
        if (!setupCheck.isRunning()) {
            setupView.unavailable();
        }
        if (!preflightCheck.isRunning()) {
            preflightView.unavailable();
        }
    }

    @Override
    public void check(boolean network) {
        if (preflightView.report() == null) {
            preflightView.loading();
        }
        setStatus(network ? MavenDetectiveBundle.message("preflight.network.running") : null);
        preflightCheck.run(c -> service.preflight(network, c), report -> {
            setStatus(null);
            service.preflightDone(report);
        });
    }

    @Override
    public void preflightChanged(@NotNull Preflight.Report report) {
        preflightView.show(report);
    }

    /**
     * Quick-Fix aus dem Preflight: läuft wie auf der Karte, aber ohne Neubau danach (es gibt noch keinen
     * Lauf); das Löschen ist nur im lokalen Repository des Preflights erlaubt.
     */
    @Override
    public void runFix(@NotNull FixAction fix, @NotNull Preflight.Report report) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>(fix.getParams());
        params.remove("args");
        BuildFailure failure = new BuildFailure(BuildFailure.Source.LOG);
        if (report.getLocalRepository() != null) {
            failure.getSession().setLocalRepository(report.getLocalRepository().toString());
        }
        FixRunner.run(project, new FixAction(fix.getType(), fix.getLabel(), params),
                new BuildDoctorService.Result(failure, List.of(), "", null, null, null, report.getLocalRepository()));
        if (!FixAction.OPEN_FILE.equals(fix.getType())) {
            setStatus(MavenDetectiveBundle.message("preflight.fix.started", fix.getLabel()));
        }
    }

    /** Log des gezeigten Laufs im Log-Navigator; {@code jump}: gleich zum ersten Fehler. */
    void showLog(boolean jump) {
        BuildDoctorService.Result r = shownResult();
        if (r == null) {
            setStatus(MavenDetectiveBundle.message("log.none"));
            return;
        }
        showView(VIEW_LOG);
        logView.show(r.rawLog(), jump);
    }

    /** Ob der gezeigte Lauf ein Log hat. */
    boolean hasLog() {
        BuildDoctorService.Result r = shownResult();
        return r != null && r.rawLog() != null && !r.rawLog().isBlank();
    }

    @Override
    public void showLog(@NotNull BuildDoctorService.Result result) {
        showView(VIEW_LOG);
        logView.show(result.rawLog(), true);
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
        if (VIEW_PREFLIGHT.equals(view)) {
            return preflightView.report() != null;
        }
        return VIEW_CARD.equals(view) && r != null && !r.diagnoses().isEmpty();
    }

    @Override
    public @Nullable String markdown() {
        if (VIEW_PREFLIGHT.equals(view)) {
            Preflight.Report p = preflightView.report();
            return p == null ? null : p.markdown();
        }
        return canCopyMarkdown() ? report(Objects.requireNonNull(shownResult())) : null;
    }

    @Override
    public @Nullable String copyMarkdownText() {
        return MavenDetectiveBundle.message(VIEW_PREFLIGHT.equals(view) ? "preflight.copy" : "doctor.copy.report");
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
