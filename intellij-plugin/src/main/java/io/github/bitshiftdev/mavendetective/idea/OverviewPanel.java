package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.SetupComparison;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Analysis;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.State;
import io.github.bitshiftdev.mavendetective.idea.AnalysisResults.Summary;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;
import io.github.bitshiftdev.mavendetective.idea.ui.Tile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tab „Übersicht“: der Zustand des Projekts auf einen Blick, je Thema eine Kachel mit Zustand und
 * Sprung in die Vertiefung. Teure Analysen (doppelte Klassen, Nutzung) rechnen nicht von selbst –
 * die Kachel sagt dann „nicht geprüft“; „Alles prüfen“ startet sie gemeinsam.
 */
public final class OverviewPanel extends DetectivePanel implements BuildDoctorService.Listener, AnalysisResults.Listener {

    /** Abstand zwischen den Kacheln und zum Rand (unskaliert). */
    private static final int GAP = 12;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BuildDoctorService service;
    private final AnalysisResults results;
    private final Tile build;
    private final Tile changes;
    private final Tile setup;
    private final Tile preflight;
    private final Tile conflicts;
    private final Tile duplicates;
    private final Tile usage;
    private final Tile exclusions;
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
        preflight = new Tile(MavenDetectiveBundle.message("tile.preflight"), () -> Navigator.buildDoctor(project, BuildDoctorPanel::showPreflight));
        conflicts = new Tile(MavenDetectiveBundle.message("nav.conflicts"), () -> Navigator.open(project, ConflictMatrixPanel.class, null));
        duplicates = new Tile(MavenDetectiveBundle.message("nav.duplicates"), () -> Navigator.open(project, DuplicateClassesPanel.class, null));
        usage = new Tile(MavenDetectiveBundle.message("nav.usage"), () -> Navigator.open(project, DependencyUsagePanel.class, null));
        exclusions = new Tile(MavenDetectiveBundle.message("tile.exclusions"), this::openExclusions);

        JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP)));
        List<Tile> tiles = List.of(build, changes, setup, preflight, conflicts, duplicates, usage, exclusions);
        for (Tile t : tiles) {
            grid.add(t);
        }
        JBScrollPane scroll = new JBScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        // Passt nur eine Spalte (seitlich angeheftet), füllen die Kacheln die Breite statt Rand zu lassen
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = tileWidth(scroll.getViewport().getWidth(), JBUI.scale(Tile.WIDTH), JBUI.scale(GAP));
                for (Tile t : tiles) {
                    t.setTileWidth(w);
                }
                grid.revalidate();
            }
        });
        setBody(scroll);

        service.addListener(this, this);
        results.addListener(this, this);
        showBuild();
        showChanges();
        showSetup();
        showPreflight(service.getLastPreflight());
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(DetectiveSettings.Listener.TOPIC,
                (DetectiveSettings.Listener) () -> showPreflight(service.getLastPreflight()));
        for (Analysis a : List.of(Analysis.CONFLICTS, Analysis.DUPLICATES, Analysis.USAGE, Analysis.EXCLUSIONS)) {
            changed(a, results.get(a));
        }
        loadReference();
        loadSetup();
    }

    /**
     * Breite einer Kachel für einen Ausschnitt von {@code available} Pixeln: im Raster {@code tile}; passt
     * keine zweite Spalte daneben, die ganze Breite abzüglich der Ränder (FlowLayout setzt den Abstand
     * links und rechts). Alles skaliert; {@link Tile#setTileWidth} achtet auf die Untergrenze.
     */
    static int tileWidth(int available, int tile, int gap) {
        if (available <= 0 || available >= 2 * tile + 3 * gap) {
            return tile;
        }
        return available - 2 * gap;
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
        check(ExclusionsPanel.class);
        loadSetup();
    }

    @Override
    public boolean isRunning() {
        if (super.isRunning()) {
            return true;
        }
        for (Analysis a : List.of(Analysis.CONFLICTS, Analysis.DUPLICATES, Analysis.USAGE, Analysis.EXCLUSIONS)) {
            if (results.get(a).state() == State.RUNNING) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void aborted() {
        showSetup();   // Kachel „Setup“ nicht weiterdrehen lassen
    }

    @Override
    public void cancelRunning() {
        super.cancelRunning();
        for (Class<? extends DetectivePanel> type : List.of(ConflictMatrixPanel.class, DuplicateClassesPanel.class, DependencyUsagePanel.class,
                ExclusionsPanel.class)) {
            DetectivePanel page = Navigator.find(project, type);
            if (page != null) {
                page.cancelRunning();
            }
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
            case EXCLUSIONS -> showAnalysis(exclusions, s, () -> check(ExclusionsPanel.class), "tile.exclusions.none",
                    "tile.exclusions.detail");
            default -> {
            }
        }
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
        if (diff != null && diff.isEmpty() && diff.getLimitation() != SnapshotDiff.Limitation.NONE) {
            changes.update(AllIcons.General.Information, MavenDetectiveBundle.message("tile.changes.limited"),
                    MavenDetectiveBundle.message("tile.changes.limited.detail"), null, null);
        } else if (diff != null && diff.isEmpty()) {
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

    @Override
    public void preflightChanged(@NotNull Preflight.Report report) {
        showPreflight(report);
    }

    /** Ampel des letzten Preflights; ohne Preflight der Hinweis, was er prüft. Abgeschaltet: keine Kachel. */
    private void showPreflight(@Nullable Preflight.Report report) {
        boolean enabled = DetectiveSettings.getInstance().isPreflight();
        if (preflight.isVisible() != enabled) {
            preflight.setVisible(enabled);
            if (preflight.getParent() != null) {
                preflight.getParent().revalidate();
                preflight.getParent().repaint();
            }
        }
        if (!enabled) {
            return;
        }
        Runnable open = () -> Navigator.buildDoctor(project, BuildDoctorPanel::showPreflight);
        if (report == null) {
            preflight.update(AllIcons.RunConfigurations.TestNotRan, MavenDetectiveBundle.message("state.not.checked"),
                    MavenDetectiveBundle.message("tile.preflight.about"), MavenDetectiveBundle.message("tile.check"), open);
            return;
        }
        preflight.update(PreflightView.icon(report.getLight()), PreflightView.lightText(report.getLight()), report.headline(),
                MavenDetectiveBundle.message("preflight.again"), open);
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

    /** Seite „Exclusions“ öffnen, bei Befunden mit dem ersten gewählt. */
    private void openExclusions() {
        Summary s = results.get(Analysis.EXCLUSIONS);
        Navigator.open(project, ExclusionsPanel.class, page -> {
            String loc = s.location();
            int colon = loc == null ? -1 : loc.lastIndexOf(':');
            if (colon > 1) {
                page.select(loc.substring(0, colon), Integer.parseInt(loc.substring(colon + 1)));
            }
        });
    }

    /** Uhrzeit, bei älteren Tagen mit Datum davor. */
    static @Nls String time(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return (t.toLocalDate().equals(LocalDate.now()) ? TIME : dateTime()).format(t);
    }

    /** {@code 2026-11-12T07:45:12…} → „12.11. 07:45“ bzw. „Nov 12, 07:45“; Unlesbares bleibt, wie es ist. */
    static @NotNull String formatCreatedAt(@Nullable String iso) {
        if (iso == null || iso.length() < 16 || iso.charAt(4) != '-' || iso.charAt(10) != 'T') {
            return iso == null ? "" : iso;
        }
        try {
            return dateTime().format(LocalDateTime.parse(iso.substring(0, 16)));
        } catch (java.time.DateTimeException e) {
            return iso;
        }
    }

    /** Tag und Uhrzeit ohne Jahr, Muster aus dem Bundle ({@code format.date.time}). */
    private static DateTimeFormatter dateTime() {
        java.util.Locale l = MavenDetectiveBundle.bundleLocale();
        return DateTimeFormatter.ofPattern(MavenDetectiveBundle.message("format.date.time"), l == java.util.Locale.ROOT ? java.util.Locale.ENGLISH : l);
    }
}
