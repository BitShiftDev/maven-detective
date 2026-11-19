package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.bitshiftdev.mavendetective.idea.ui.DetectivePanel;

import java.util.List;
import java.util.function.Function;

/**
 * Welle 6: Jede Seite des Tool-Windows entsteht in einem Projekt ohne Maven, ohne Ausnahme; leere Zustände
 * erklären die Seite in zwei Zeilen, ein Import ohne Ergebnis macht nichts „veraltet“. Die Rechenlogik testen
 * {@link PanelModelsTest} und die Kern-Tests; Bedienung und Aussehen bleiben in der Abnahmeliste ({@code runIde}).
 */
public class PagesSmokeTest extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            MavenDetectiveBundle.useForTests(null);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    private <T extends DetectivePanel> T page(Function<com.intellij.openapi.project.Project, T> factory) {
        T page = factory.apply(getProject());
        Disposer.register(getTestRootDisposable(), page);
        // Seiten, die beim Öffnen selbst rechnen (Konflikte, Exclusions), liefern im Hintergrund – fertig werden lassen
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        return page;
    }

    public void testJedeSeiteEntstehtOhneMavenProjekt() {
        List<DetectivePanel> pages = List.of(page(OverviewPanel::new), page(BuildDoctorPanel::new), page(DependenciesPanel::new),
                page(ConflictMatrixPanel::new), page(ExclusionsPanel::new), page(DuplicateClassesPanel::new),
                page(DependencyUsagePanel::new), page(UpgradeSimulatorPanel::new));
        for (DetectivePanel p : pages) {
            assertFalse(p.getClass().getSimpleName(), p.isStale());
        }
        DependencyAnalysisView view = new DependencyAnalysisView(getProject());
        Disposer.register(getTestRootDisposable(), view);
        assertNotNull(view.page(ConflictMatrixPanel.class));
        assertNotNull(view.page(UpgradeSimulatorPanel.class));
    }

    /** {@code about()} steht in den leeren Zuständen: zwei kurze Sätze, je eine Zeile, in beiden Sprachen. */
    public void testLeereZustaendeErklaerenDieSeiteInZweiZeilen() {
        for (MavenDetectiveBundle.Language language : MavenDetectiveBundle.Language.values()) {
            if (language == MavenDetectiveBundle.Language.AUTO) {
                continue;
            }
            MavenDetectiveBundle.useForTests(language);
            List<String> abouts = List.of(page(ConflictMatrixPanel::new).about(), page(ExclusionsPanel::new).about(),
                    page(DuplicateClassesPanel::new).about(), page(DependencyUsagePanel::new).about(),
                    page(UpgradeSimulatorPanel::new).about());
            for (String about : abouts) {
                assertNotNull(language.name(), about);
                String[] lines = about.split("\n", -1);
                assertEquals(language + ": " + about, 2, lines.length);
                for (String line : lines) {
                    assertFalse(language + ": " + about, line.isBlank());
                }
            }
        }
    }

    public void testOhneErgebnisNichtsZuKopierenUndNichtsVeraltet() {
        ConflictMatrixPanel matrix = page(ConflictMatrixPanel::new);
        ExclusionsPanel exclusions = page(ExclusionsPanel::new);
        DuplicateClassesPanel duplicates = page(DuplicateClassesPanel::new);
        DependencyUsagePanel usage = page(DependencyUsagePanel::new);
        UpgradeSimulatorPanel upgrade = page(UpgradeSimulatorPanel::new);
        for (DetectivePanel p : List.of(matrix, exclusions, duplicates, usage)) {
            assertFalse(p.getClass().getSimpleName(), p.canCopyMarkdown());
        }

        AnalysisResults.getInstance(getProject()).imported();
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        for (DetectivePanel p : List.of(matrix, exclusions, duplicates, usage, upgrade)) {
            assertFalse("ohne Ergebnis nichts veraltet: " + p.getClass().getSimpleName(), p.isStale());
        }
    }

    public void testSprungOhneErgebnisTutNichts() {
        ExclusionsPanel exclusions = page(ExclusionsPanel::new);
        exclusions.select("/nirgends/pom.xml", 3);
        ConflictMatrixPanel matrix = page(ConflictMatrixPanel::new);
        matrix.select("org.none:none");
        assertFalse(matrix.canCopyMarkdown());
    }
}
