package io.github.bitshiftdev.mavendetective.idea;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * {@link BuildDoctorActions}: wann die Aktionen außerhalb des Tool-Windows (Find Action, Toolbar des
 * Maven-Tool-Windows) bedienbar sind.
 */
public class BuildDoctorActionsTest extends BasePlatformTestCase {

    private static final String LOG = """
            [INFO] BUILD FAILURE
            [ERROR] Failed to execute goal on project app: Could not resolve dependencies for project org.acme:app:jar:1.0: \
            The following artifacts could not be resolved: org.lib:lib:jar:9.9 (absent): Could not find artifact \
            org.lib:lib:jar:9.9 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
            """;

    private boolean preflightBefore;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        preflightBefore = DetectiveSettings.getInstance().isPreflight();
        // Das leichte Testprojekt lebt über alle Tests: jeder Test beginnt ohne Lauf
        ServiceContainerUtil.replaceService(getProject(), BuildDoctorService.class, new BuildDoctorService(getProject()),
                getTestRootDisposable());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            DetectiveSettings.getInstance().setPreflight(preflightBefore);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    private DataContext withProject() {
        return SimpleDataContext.getProjectContext(getProject());
    }

    private static DataContext withoutProject() {
        return SimpleDataContext.builder().build();
    }

    private static Presentation update(AnAction action, DataContext context) {
        AnActionEvent e = TestActionEvent.createTestEvent(action, context);
        action.update(e);
        return e.getPresentation();
    }

    private void analyze(String log) {
        BuildDoctorService service = BuildDoctorService.getInstance(getProject());
        BuildDoctorService.Result before = service.getLast();
        service.analyzeLog(log, null, false);
        PlatformTestUtil.waitWithEventsDispatching("Analyse fertig", () -> service.getLast() != before, 30);
    }

    public void testOhneProjektIstNichtsBedienbar() {
        for (AnAction a : List.of(new BuildDoctorActions.AnalyzeClipboard(), new BuildDoctorActions.CompareSetup(),
                new BuildDoctorActions.ReportMisdiagnosis(), new BuildDoctorActions.ShowLog(), new BuildDoctorActions.Preflight())) {
            assertFalse(a.getClass().getSimpleName(), update(a, withoutProject()).isEnabled());
        }
        assertNull(withoutProject().getData(CommonDataKeys.PROJECT));
    }

    public void testZwischenablageUndVergleichBrauchenNurDasProjekt() {
        assertTrue(update(new BuildDoctorActions.AnalyzeClipboard(), withProject()).isEnabled());
        assertTrue(update(new BuildDoctorActions.CompareSetup(), withProject()).isEnabled());
    }

    public void testFalschErkanntUndLogBrauchenEinenLauf() {
        assertNull("frisches Projekt ohne Lauf", BuildDoctorService.getInstance(getProject()).getLast());
        assertFalse(update(new BuildDoctorActions.ReportMisdiagnosis(), withProject()).isEnabled());
        assertFalse(update(new BuildDoctorActions.ShowLog(), withProject()).isEnabled());

        analyze(LOG);

        assertFalse(BuildDoctorService.getInstance(getProject()).getLast().diagnoses().isEmpty());
        assertTrue(update(new BuildDoctorActions.ReportMisdiagnosis(), withProject()).isEnabled());
        assertTrue(update(new BuildDoctorActions.ShowLog(), withProject()).isEnabled());
    }

    public void testLeererLogOhneDiagnoseIstNichtMeldbar() {
        analyze("   \n");
        BuildDoctorService.Result last = BuildDoctorService.getInstance(getProject()).getLast();
        assertTrue(last.diagnoses().isEmpty());
        assertFalse("nichts zu melden", update(new BuildDoctorActions.ReportMisdiagnosis(), withProject()).isEnabled());
        assertFalse("kein Log zu zeigen", update(new BuildDoctorActions.ShowLog(), withProject()).isEnabled());
    }

    public void testPreflightVerschwindetWennAbgeschaltet() {
        DetectiveSettings.getInstance().setPreflight(true);
        Presentation on = update(new BuildDoctorActions.Preflight(), withProject());
        assertTrue(on.isEnabledAndVisible());
        DetectiveSettings.getInstance().setPreflight(false);
        Presentation off = update(new BuildDoctorActions.Preflight(), withProject());
        assertFalse(off.isVisible());
        assertFalse(off.isEnabled());
    }

    /** Die IDE ist englisch, die Einstellung deutsch: registrierte Aktionen holen ihren Text aus dem eigenen Bundle. */
    public void testRegistrierteAktionTraegtDenTextDerEingestelltenSprache() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
        try {
            AnAction registered = ActionManager.getInstance().getAction("MavenDetective.CompareSetup");
            assertNotNull(registered);
            Presentation p = update(registered, withProject());
            assertEquals(MavenDetectiveBundle.message("action.MavenDetective.CompareSetup.text"), p.getText());
            assertFalse(p.getText(), p.getText().equals("IDE vs. Command Line"));
        } finally {
            MavenDetectiveBundle.useForTests(null);
        }
    }
}
