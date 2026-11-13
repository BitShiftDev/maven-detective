package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;
import io.github.bitshiftdev.mavendetective.core.deps.DepNode;
import io.github.bitshiftdev.mavendetective.core.deps.ExclusionAudit;
import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.deps.PomLocator;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.State;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.Locations;
import io.github.bitshiftdev.mavendetective.idea.ui.Tile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Tab „Übersicht“: der Zustand des Projekts auf einen Blick, je Thema eine Kachel mit Zustand und
 * Sprung in die Vertiefung. Teure Analysen (doppelte Klassen, Nutzung) rechnen nicht von selbst –
 * die Kachel sagt dann „nicht geprüft“; „Alles prüfen“ startet sie gemeinsam.
 */
public final class OverviewPanel extends DetectivePanel implements BuildDoctorService.Listener, AnalysisResults.Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final BuildDoctorService service;
    private final AnalysisResults results;
    private final Tile build;
    private final Tile changes;
    private final Tile setup;
    private final Tile conflicts;
    private final Tile duplicates;
    private final Tile usage;
    private final Tile exclusions;
    private final Background exclusionCheck = background(null);
    private final Background reference = background(null);
    private final Background setupCheck = background(null);
    /** Zeitpunkt der grünen Referenz ({@code null}: keine oder noch nicht gelesen). */
    private @Nullable String greenAt;
    private @Nullable SetupComparison setupComparison;

    public OverviewPanel(@NotNull Project project) {
        super(project);
        service = BuildDoctorService.getInstance(project);
        results = AnalysisResults.getInstance(project);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(registered("MavenDetective.Refresh"));
        actions.add(registered("MavenDetective.Stop"));
        installToolbar(null, actions, null);

        build = new Tile(MavenDetectiveBundle.message("tile.build"), () -> Navigator.buildDoctor(project, null));
        changes = new Tile(MavenDetectiveBundle.message("tile.changes"), () -> Navigator.buildDoctor(project, null));
        setup = new Tile(MavenDetectiveBundle.message("tile.setup"), () -> Navigator.buildDoctor(project, BuildDoctorPanel::showSetupView));
        conflicts = new Tile(MavenDetectiveBundle.message("nav.conflicts"), () -> Navigator.open(project, ConflictMatrixPanel.class, null));
        duplicates = new Tile(MavenDetectiveBundle.message("nav.duplicates"), () -> Navigator.open(project, DuplicateClassesPanel.class, null));
        usage = new Tile(MavenDetectiveBundle.message("nav.usage"), () -> Navigator.open(project, DependencyUsagePanel.class, null));
        exclusions = new Tile(MavenDetectiveBundle.message("tile.exclusions"), this::openExclusions);

        JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(12)));
        for (Tile t : List.of(build, changes, setup, conflicts, duplicates, usage, exclusions)) {
            grid.add(t);
        }
        JBScrollPane scroll = new JBScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        setBody(scroll);

        service.addListener(this, this);
        results.addListener(this, this);
        showBuild();
        showChanges();
        showSetup();
        for (Analysis a : List.of(Analysis.CONFLICTS, Analysis.DUPLICATES, Analysis.USAGE, Analysis.EXCLUSIONS)) {
            changed(a, results.get(a));
        }
        loadReference();
        loadSetup();
        checkExclusions();
    }

    // ---- Alles prüfen ------------------------------------------------------------------------

    @Override
    public boolean supportsRefresh() {
        return true;
    }

    @Override
    public boolean canRefresh() {
        return !isRunning();
    }

    @Override
    public @NotNull String refreshText() {
        return MavenDetectiveBundle.message("overview.check.all");
    }

    @Override
    public @NotNull Icon refreshIcon() {
        return AllIcons.Actions.RunAll;
    }

    /** Alle Analysen auf einmal: Konflikte, Exclusions, doppelte Klassen, Nutzung. */
    @Override
    public void refresh() {
        check(ConflictMatrixPanel.class);
        check(DuplicateClassesPanel.class);
        check(DependencyUsagePanel.class);
        checkExclusions();
        loadSetup();
    }

    @Override
    public boolean isRunning() {
        if (super.isRunning()) {
            return true;
        }
        for (Analysis a : List.of(Analysis.CONFLICTS, Analysis.DUPLICATES, Analysis.USAGE)) {
            if (results.get(a).state() == State.RUNNING) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void cancelRunning() {
        super.cancelRunning();
        for (Class<? extends DetectivePanel> type : List.of(ConflictMatrixPanel.class, DuplicateClassesPanel.class, DependencyUsagePanel.class)) {
            DetectivePanel page = Navigator.find(project, type);
            if (page != null) {
                page.cancelRunning();
            }
        }
    }

    @Override
    protected void aborted() {
        if (!exclusionCheck.isRunning()) {
            results.aborted(Analysis.EXCLUSIONS);
        }
    }

    /** Seite im Hintergrund prüfen lassen, ohne hinzuspringen. */
    private void check(Class<? extends DetectivePanel> type) {
        DetectivePanel page = Navigator.find(project, type);
        if (page != null && !page.isRunning() && page.canRefresh()) {
            page.refresh();
        }
    }

    // ---- Ereignisse ---------------------------------------------------------------------------

    @Override
    public void resultChanged(BuildDoctorService.Result result) {
        showChanges();
        if (result.setup() != null) {
            setupComparison = result.setup();
            showSetup();
        }
    }

    @Override
    public void runFinished(@NotNull BuildDoctorService.Run run) {
        showBuild();
        showChanges();
        if (run.green()) {
            loadReference();
        }
    }

    @Override
    public void changed(@NotNull Analysis analysis, @NotNull Summary s) {
        switch (analysis) {
            case CONFLICTS -> showAnalysis(conflicts, s, () -> check(ConflictMatrixPanel.class), "tile.conflicts.none",
                    "tile.conflicts.detail");
            case DUPLICATES -> showAnalysis(duplicates, s, () -> check(DuplicateClassesPanel.class), "tile.duplicates.none",
                    "tile.duplicates.detail");
            case USAGE -> showAnalysis(usage, s, () -> check(DependencyUsagePanel.class), "tile.usage.none", "tile.usage.detail");
            case EXCLUSIONS -> showAnalysis(exclusions, s, this::checkExclusions, "tile.exclusions.none", "tile.exclusions.detail");
            default -> {
            }
        }
    }

    /** Exclusions prüfen nur POMs – günstig genug, um nach jedem Import neu zu rechnen. */
    @Override
    public void mavenImported() {
        checkExclusions();
    }

    // ---- Kacheln ------------------------------------------------------------------------------

    private void showBuild() {
        BuildDoctorService.Run run = service.getLastRun();
        if (run == null) {
            build.update(AllIcons.RunConfigurations.TestNotRan, MavenDetectiveBundle.message("tile.build.none"),
                    MavenDetectiveBundle.message("tile.build.none.detail"), null, null);
        } else if (run.green()) {
            build.update(AllIcons.General.InspectionsOK, MavenDetectiveBundle.message("tile.build.green", time(run.at())),
                    MavenDetectiveBundle.message("tile.build.green.detail"), null, null);
        } else {
            List<Diagnosis> ds = run.result() == null ? List.of() : run.result().diagnoses();
            String detail = ds.isEmpty() ? MavenDetectiveBundle.message("tile.build.red.unknown")
                    : MavenDetectiveBundle.message("tile.build.red.detail", ds.get(0).getNumber(), ds.get(0).getCategory(),
                    ds.get(0).getConfidence().label());
            build.update(AllIcons.General.Error, MavenDetectiveBundle.message("tile.build.red", time(run.at())), detail,
                    MavenDetectiveBundle.message("tile.build.show"), () -> Navigator.buildDoctor(project, BuildDoctorPanel::showLatest));
        }
    }

    private void showChanges() {
        BuildDoctorService.Run run = service.getLastRun();
        SnapshotDiff diff = run == null || run.green() || run.result() == null ? null : run.result().changes();
        if (diff != null && diff.isEmpty()) {
            changes.update(AllIcons.General.InspectionsOK, MavenDetectiveBundle.message("tile.changes.nothing"),
                    MavenDetectiveBundle.message("tile.changes.nothing.detail"), null, null);
        } else if (diff != null) {
            int n = diff.getChanges().size();
            changes.update(AllIcons.General.Warning, MavenDetectiveBundle.message("tile.changes.count", n),
                    String.join(" · ", diff.lines(2)), null, null);
        } else if (greenAt != null) {
            changes.update(AllIcons.General.Information, MavenDetectiveBundle.message("tile.changes.reference", greenAt),
                    MavenDetectiveBundle.message("tile.changes.reference.detail"), null, null);
        } else {
            changes.update(AllIcons.RunConfigurations.TestNotRan, MavenDetectiveBundle.message("tile.changes.none"),
                    MavenDetectiveBundle.message("tile.changes.none.detail"), null, null);
        }
    }

    private void showSetup() {
        SetupComparison c = setupComparison;
        if (c == null) {
            boolean loading = setupCheck.isRunning();
            setup.update(loading ? AnimatedIcon.Default.INSTANCE : AllIcons.RunConfigurations.TestNotRan,
                    MavenDetectiveBundle.message(loading ? "state.running" : "tile.setup.unknown"), null, null, null);
            return;
        }
        int n = c.getDifferences().size();
        setup.update(n == 0 ? AllIcons.General.InspectionsOK : AllIcons.General.Warning,
                n == 0 ? MavenDetectiveBundle.message("tile.setup.same") : MavenDetectiveBundle.message("tile.setup.count", n),
                c.summary(), null, null);
    }

    /**
     * Gemeinsame Darstellung einer Analyse. Veraltet zeigt keine Zahl mehr – nach einem Import soll
     * nichts Altes als aktuell durchgehen.
     */
    private void showAnalysis(Tile tile, Summary s, Runnable check, String noneKey, String detailKey) {
        switch (s.state()) {
            case NOT_CHECKED -> tile.update(AllIcons.RunConfigurations.TestNotRan, MavenDetectiveBundle.message("state.not.checked"),
                    MavenDetectiveBundle.message(detailKey), MavenDetectiveBundle.message("tile.check"), check);
            case RUNNING -> tile.update(AnimatedIcon.Default.INSTANCE, MavenDetectiveBundle.message("state.running"),
                    MavenDetectiveBundle.message(detailKey), null, null);
            case STALE -> tile.update(AllIcons.General.Warning, MavenDetectiveBundle.message("state.stale"),
                    MavenDetectiveBundle.message("tile.stale.detail"), MavenDetectiveBundle.message("stale.recheck"), check);
            case DONE -> {
                if (s.count() == 0) {
                    tile.update(AllIcons.General.InspectionsOK, MavenDetectiveBundle.message(noneKey), s.text(), null, null);
                } else {
                    tile.update(AllIcons.General.Warning, String.valueOf(s.count()), s.text(), null, null);
                }
            }
        }
    }

    // ---- Rechnen ------------------------------------------------------------------------------

    private void loadReference() {
        reference.run(c -> {
            BuildSnapshot green = service.loadGreen();
            return green == null ? "" : formatCreatedAt(green.getCreatedAt());
        }, at -> {
            greenAt = at.isEmpty() ? null : at;
            showChanges();
        });
    }

    private void loadSetup() {
        BuildDoctorService.Result last = service.getLast();
        setupCheck.run(c -> service.compareSetups(last == null ? null : last.configuration()), c -> {
            setupComparison = c;
            showSetup();
        });
        showSetup();
    }

    private void checkExclusions() {
        if (exclusionCheck.isRunning()) {
            return;
        }
        results.running(Analysis.EXCLUSIONS);
        exclusionCheck.run(c -> {
            Path root = IdeMaven.reactorRoot(project);
            PomLocator locator = DependenciesPanel.locator(project, root);
            List<PomFile> poms = DuplicateClassesPanel.projectPoms(project, locator, root);
            if (poms.isEmpty()) {
                return null;
            }
            Map<String, DepNode> trees = IdeMaven.dependencyTrees(project);
            return ExclusionAudit.build(locator, poms, trees);
        }, audit -> {
            if (audit == null) {
                results.set(Analysis.EXCLUSIONS, AnalysisResults.Summary.NOT_CHECKED);
                return;
            }
            List<ExclusionAudit.Finding> removable = audit.removable();
            int stale = audit.count(ExclusionAudit.Status.STALE);
            int redundant = audit.count(ExclusionAudit.Status.REDUNDANT);
            Summary s = Summary.done(removable.size(), MavenDetectiveBundle.message("tile.exclusions.summary", stale, redundant));
            if (!removable.isEmpty()) {
                s = s.at(Locations.of(removable.get(0).getFile(), removable.get(0).getLine()));
            }
            results.set(Analysis.EXCLUSIONS, s);
        });
    }

    /** Exclusions haben (bis U5) keine eigene Seite: Sprung zur ersten, die Inspection markiert sie dort. */
    private void openExclusions() {
        Summary s = results.get(Analysis.EXCLUSIONS);
        if (s.location() != null) {
            Locations.open(project, s.location());
        } else if (s.state() != State.RUNNING) {
            checkExclusions();
        }
    }

    /** Uhrzeit, bei älteren Tagen mit Datum davor. */
    static @Nls String time(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return (t.toLocalDate().equals(LocalDate.now()) ? TIME : DATE_TIME).format(t);
    }

    /** {@code 2026-11-12T07:45:12…} → „12.11. 07:45“; Unlesbares bleibt, wie es ist. */
    static @NotNull String formatCreatedAt(@Nullable String iso) {
        if (iso == null || iso.length() < 16 || iso.charAt(4) != '-' || iso.charAt(10) != 'T') {
            return iso == null ? "" : iso;
        }
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + ". " + iso.substring(11, 16);
    }
}
