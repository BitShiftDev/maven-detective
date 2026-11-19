package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.testkit.CheckBench;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertConfirmed;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidence;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidenceText;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFirstFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertInfo;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertNotApplicable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüfungen aus {@link PomChecks}, direkt über {@link Check#run(CheckContext)}. {@code pom-location} und
 * {@code plugin-declaration} bestätigen nie etwas (nur Fundstelle), {@code ear-module} gibt nie nur einen Hinweis.
 */
class PomChecksTest {

    @TempDir
    Path tmp;

    private CheckBench bench() {
        return CheckBench.in(tmp);
    }

    /** Absoluter, normalisierter Pfad – so liefert {@link ProjectPoms} die Dateien. */
    private static Path abs(Path p) {
        return p.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ pom-location

    private static final String BROKEN_POM = "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <versoin>1</versoin>\n</project>\n";

    @Test
    void pomProblemFromEventsGivesFileLineAndSnippet() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", BROKEN_POM);
        CheckResult r = b.pomProblem(pom.toString(), 3, "Unrecognised tag: 'versoin'").run(new PomChecks.PomLocation());
        assertInfo(r);
        assertEquals("3", r.getVars().get("pomLine"));
        Evidence e = assertEvidence(r.getEvidence(), pom, 3);
        assertTrue(e.getText().contains("<versoin>1</versoin>"), e.getText());
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", pom.toString(), "line", "3");
    }

    @Test
    void locationWithFileIsTakenFromTheLog() {
        CheckBench b = bench();
        Path pom = b.projectFile("app/pom.xml", BROKEN_POM);
        CheckResult r = b.line("[ERROR] Unrecognised tag: 'versoin' (position: START_TAG seen ...) @ " + pom + ", line 3, column 12")
                .run(new PomChecks.PomLocation());
        assertInfo(r);
        assertEquals(pom.toString(), r.getVars().get("pomFile"));
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", pom.toString(), "line", "3");
    }

    @Test
    void lineOnlyTakesTheFileOfTheProjectLine() {
        CheckBench b = bench();
        Path pom = b.projectFile("app/pom.xml", BROKEN_POM);
        CheckResult r = b.line("[ERROR] The project com.acme:app:1.0 (" + pom + ") has 1 error",
                "[ERROR] 'dependencies.dependency.version' for a:b:jar is missing. @ line 3, column 9").run(new PomChecks.PomLocation());
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", pom.toString(), "line", "3");
    }

    @Test
    void lineOnlyFallsBackToTheFileVariableAndThenToTheRootPom() {
        CheckBench b = bench();
        Path root = b.projectFile("pom.xml", BROKEN_POM);
        Path other = b.projectFile("other.xml", BROKEN_POM);
        String line = "[ERROR] 'dependencies.dependency.version' for a:b:jar is missing. @ line 2, column 9";
        assertFirstFix(b.line(line).run(new PomChecks.PomLocation()).getFixes(), FixAction.OPEN_FILE,
                "file", abs(root).toString(), "line", "2");
        assertFirstFix(b.var("file", other.toString()).run(new PomChecks.PomLocation()).getFixes(), FixAction.OPEN_FILE,
                "file", other.toString());
    }

    @Test
    void lineBehindTheEndOfTheFileStillPointsThere() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", BROKEN_POM);
        CheckResult r = b.pomProblem(pom.toString(), 40, "Unexpected end").run(new PomChecks.PomLocation());
        Evidence e = assertEvidence(r.getEvidence(), pom, 40);
        assertEquals("Unexpected end", e.getText());
    }

    /** Ein verstümmeltes Log darf die Prüfung nicht mit NumberFormatException abbrechen. */
    @Test
    void lineNumberBeyondIntIsIgnored() {
        CheckBench b = bench();
        b.projectFile("pom.xml", BROKEN_POM);
        CheckResult r = b.line("[ERROR] x @ line 99999999999, column 1").run(new PomChecks.PomLocation());
        assertNotApplicable(r);
    }

    @Test
    void withoutLocationOrWithNetworkPathPomLocationIsNotApplicable() {
        assertNotApplicable(bench().line("[ERROR] Non-resolvable parent POM").run(new PomChecks.PomLocation()));
        assertNotApplicable(bench().line("[ERROR] x @ \\\\host\\share\\pom.xml, line 3, column 1").run(new PomChecks.PomLocation()));
    }

    // ------------------------------------------------------------------ parent-relative-path

    private static String child(String relativePath) {
        return "<project>\n<parent>\n<groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>\n"
                + (relativePath == null ? "" : "<relativePath>" + relativePath + "</relativePath>\n")
                + "</parent>\n<artifactId>app</artifactId>\n</project>\n";
    }

    @Test
    void relativePathToAnotherPomIsConfirmed() {
        CheckBench b = bench();
        Path parent = b.projectFile("pom.xml", TestPom.of("com.acme", "parent", "2.0").packaging("pom").xml());
        Path app = b.projectFile("app/pom.xml", child(null));
        CheckResult r = b.line("[ERROR] The project com.acme:app:1.0 (" + app + ") has 1 error").run(new PomChecks.ParentRelativePath());
        assertConfirmed(r);
        assertEquals("com.acme:parent:2.0", r.getVars().get("foundGav"));
        assertEvidence(r.getEvidence(), abs(app), 2);
        assertEquals(abs(parent).toString(), r.getEvidence().get(1).getFile());
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", abs(app).toString(), "line", "2");
    }

    @Test
    void relativePathToADirectoryReadsItsPom() {
        CheckBench b = bench();
        b.projectFile("parent/pom.xml", TestPom.of("com.acme", "parent", "1.0").packaging("pom").xml());
        b.projectFile("app/pom.xml", child("../parent"));
        CheckResult r = b.var("project", "com.acme:app:1.0").run(new PomChecks.ParentRelativePath());
        assertInfo(r);
        assertEquals("../parent", r.getVars().get("relativePath"));
        assertTrue(r.getEvidence().isEmpty(), "Parent passt: " + r.getEvidence());
    }

    @Test
    void relativePathToNothingPointsToTheParentElement() {
        CheckBench b = bench();
        Path app = b.projectFile("app/pom.xml", child("../missing/pom.xml"));
        CheckResult r = b.var("project", "com.acme:app:1.0").run(new PomChecks.ParentRelativePath());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", abs(app).toString(), "line", "2");
    }

    @Test
    void emptyRelativePathIsOnlyNamed() {
        CheckBench b = bench();
        Path root = b.projectFile("pom.xml", child(""));
        CheckResult r = b.run(new PomChecks.ParentRelativePath());
        assertInfo(r);
        assertEvidence(r.getEvidence(), abs(root), 2);
        assertTrue(r.getFixes().isEmpty(), r.getFixes().toString());
    }

    @Test
    void pomWithoutParentOrWithoutProjectIsNotApplicable() {
        CheckBench b = bench();
        b.projectFile("pom.xml", TestPom.of("com.acme", "root", "1.0").xml());
        assertNotApplicable(b.run(new PomChecks.ParentRelativePath()));
        assertNotApplicable(bench().withoutProject().run(new PomChecks.ParentRelativePath()));
    }

    // ------------------------------------------------------------------ child-module

    private CheckBench withModules() {
        CheckBench b = bench();
        b.projectFile("pom.xml", "<project>\n  <modules>\n    <module>core</module>\n    <module>servcie</module>\n  </modules>\n</project>\n");
        b.projectFile("core/pom.xml", "<project/>");
        return b;
    }

    @Test
    void missingModuleDirectoryIsConfirmedWithSimilarName() throws Exception {
        CheckBench b = withModules();
        Files.createDirectories(b.project().resolve("service"));
        Path pom = b.project().resolve("pom.xml");
        CheckResult r = b.var("module", b.project().resolve("servcie").toString()).var("file", pom.toString())
                .run(new PomChecks.ChildModule());
        assertConfirmed(r);
        assertEquals("servcie", r.getVars().get("moduleName"));
        assertEvidence(r.getEvidence(), pom, 4);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.HINT, "'service'");
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", pom.toString(), "line", "4");
    }

    @Test
    void moduleDirectoryWithoutPomIsConfirmed() throws Exception {
        CheckBench b = withModules();
        Files.createDirectories(b.project().resolve("servcie"));
        Path pom = b.project().resolve("pom.xml");
        CheckResult r = b.var("module", "servcie").var("file", pom.toString()).run(new PomChecks.ChildModule());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), pom, 4);
    }

    @Test
    void maven4NamesTheDirectoryInsteadOfThePom() {
        CheckBench b = withModules();
        CheckResult r = b.var("module", "servcie").var("dir", b.project().toString()).run(new PomChecks.ChildModule());
        assertConfirmed(r);
        assertEquals(b.project().resolve("pom.xml").toString(), r.getVars().get("file"));
    }

    @Test
    void moduleThatExistsByNowIsOnlyAHint() {
        CheckBench b = withModules();
        CheckResult r = b.var("module", "core").var("file", b.project().resolve("pom.xml").toString()).run(new PomChecks.ChildModule());
        assertInfo(r);
    }

    @Test
    void withoutModuleChildModuleIsNotApplicable() {
        assertNotApplicable(withModules().var("file", "pom.xml").run(new PomChecks.ChildModule()));
        assertNotApplicable(bench().var("module", "x").var("file", "\\\\host\\share\\pom.xml").run(new PomChecks.ChildModule()));
    }

    // ------------------------------------------------------------------ plugin-prefix

    @Test
    void knownPrefixWithoutPluginIsConfirmedAndPointsToThePlugins() {
        CheckBench b = bench();
        Path root = b.projectFile("pom.xml", "<project>\n<build>\n<plugins>\n</plugins>\n</build>\n</project>\n");
        CheckResult r = b.var("prefix", "spring-boot").run(new PomChecks.PluginPrefix());
        assertConfirmed(r);
        assertEquals("org.springframework.boot:spring-boot-maven-plugin", r.getVars().get("plugin"));
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
        assertFix(r.getFixes(), FixAction.OPEN_FILE, "file", abs(root).toString(), "line", "3");
    }

    @Test
    void knownPrefixDeclaredInAnotherModuleShowsWhere() {
        CheckBench b = bench();
        b.projectFile("pom.xml", "<project>\n</project>\n");
        Path app = b.projectFile("app/pom.xml", "<project>\n<build><plugins><plugin>\n"
                + "<artifactId>spring-boot-maven-plugin</artifactId>\n</plugin></plugins></build>\n</project>\n");
        CheckResult r = b.var("prefix", "spring-boot").run(new PomChecks.PluginPrefix());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), abs(app), 3);
        assertFix(r.getFixes(), FixAction.OPEN_FILE, "line", "2");
    }

    @Test
    void unknownPrefixGivesSuggestionGroupsAndOfflineHint() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<pluginGroups>\n<pluginGroup>org.acme</pluginGroup>\n</pluginGroups>\n</settings>\n");
        CheckResult r = b.offline().var("prefix", "dependncy").run(new PomChecks.PluginPrefix());
        assertInfo(r);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.HINT, "'dependency'");
        assertEvidence(r.getEvidence(), settings, 2);
        assertEquals(3, r.getEvidence().size(), r.getEvidence().toString());
    }

    @Test
    void withoutPrefixPluginPrefixIsNotApplicable() {
        assertNotApplicable(bench().run(new PomChecks.PluginPrefix()));
    }

    // ------------------------------------------------------------------ plugin-version

    private static final String VERSION_MISSING =
            "[ERROR] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-site-plugin is missing. @ line 4, column 9";

    @Test
    void pluginWithoutVersionGetsTheVersionInserted() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project>\n<build><plugins>\n<plugin>\n<artifactId>maven-site-plugin</artifactId>\n"
                + "</plugin>\n</plugins></build>\n</project>\n");
        CheckResult r = b.line(VERSION_MISSING).var("plugin", "org.apache.maven.plugins:maven-site-plugin").run(new PomChecks.PluginVersion());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), abs(pom), 4);
        assertFirstFix(r.getFixes(), FixAction.INSERT_PLUGIN_VERSION, "file", abs(pom).toString(), "line", "4",
                "plugin", "org.apache.maven.plugins:maven-site-plugin");
    }

    /** {@code <plugin>} in der Konfiguration (reportPlugins) gehört nicht zum äußeren Plugin. */
    @Test
    void pluginInsideConfigurationDoesNotHideTheOuterPlugin() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project>\n<build><plugins>\n<plugin>\n<artifactId>maven-site-plugin</artifactId>\n"
                + "<configuration><reportPlugins>\n<plugin><artifactId>maven-project-info-reports-plugin</artifactId><version>3.5</version></plugin>\n"
                + "</reportPlugins></configuration>\n</plugin>\n</plugins></build>\n</project>\n");
        CheckResult r = b.line(VERSION_MISSING).var("plugin", "org.apache.maven.plugins:maven-site-plugin").run(new PomChecks.PluginVersion());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.INSERT_PLUGIN_VERSION, "file", abs(pom).toString(), "line", "4");
    }

    @Test
    void pluginNotInTheProjectIsOnlyAHint() {
        CheckBench b = bench();
        b.projectFile("pom.xml", "<project/>");
        CheckResult r = b.line(VERSION_MISSING).var("plugin", "org.apache.maven.plugins:maven-site-plugin").run(new PomChecks.PluginVersion());
        assertInfo(r);
    }

    @Test
    void pluginWithVersionEverywhereIsNotApplicable() {
        CheckBench b = bench();
        b.projectFile("pom.xml", "<project><build><plugins><plugin><artifactId>maven-site-plugin</artifactId>"
                + "<version>3.12.1</version></plugin></plugins></build></project>");
        assertNotApplicable(b.line(VERSION_MISSING).var("plugin", "org.apache.maven.plugins:maven-site-plugin")
                .run(new PomChecks.PluginVersion()));
    }

    @Test
    void otherPluginErrorsAreNotApplicable() {
        assertNotApplicable(bench().line("[ERROR] Could not find goal 'x' in plugin a:b:1").var("plugin", "a:b:1")
                .run(new PomChecks.PluginVersion()));
        assertNotApplicable(bench().line(VERSION_MISSING).run(new PomChecks.PluginVersion()));
    }

    // ------------------------------------------------------------------ plugin-declaration

    @Test
    void pluginDeclarationPrefersTheFailedModule() {
        CheckBench b = bench();
        b.projectFile("pom.xml", "<project><artifactId>root</artifactId>\n<build><pluginManagement><plugins><plugin>\n"
                + "<artifactId>maven-war-plugin</artifactId></plugin></plugins></pluginManagement></build></project>\n");
        Path web = b.projectFile("web/pom.xml", "<project><artifactId>web</artifactId>\n<build><plugins><plugin>\n"
                + "<artifactId>maven-war-plugin</artifactId></plugin></plugins></build></project>\n");
        CheckResult r = b.var("pluginArtifact", "maven-war-plugin").var("failedModule", "web").run(new PomChecks.PluginDeclaration());
        assertInfo(r);
        assertEquals(2, r.getEvidence().size());
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", abs(web).toString(), "line", "3");
    }

    @Test
    void pluginOutsideTheProjectIsOnlyNamed() {
        CheckResult r = bench().var("pluginArtifact", "maven-war-plugin").run(new PomChecks.PluginDeclaration());
        assertInfo(r);
        assertTrue(r.getFixes().isEmpty(), r.getFixes().toString());
    }

    @Test
    void withoutPluginArtifactPluginDeclarationIsNotApplicable() {
        assertNotApplicable(bench().run(new PomChecks.PluginDeclaration()));
    }

    // ------------------------------------------------------------------ ear-module

    private CheckBench earProject(String webPackaging, String earDependencies) {
        CheckBench b = bench();
        b.projectFile("pom.xml", TestPom.of("com.acme", "root", "1.0").packaging("pom").xml());
        b.projectFile("web/pom.xml", TestPom.of(null, "web", null).parent("com.acme", "root", "1.0").packaging(webPackaging).xml());
        b.projectFile("app/pom.xml", "<project>\n<parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>\n"
                + "<artifactId>app</artifactId>\n<packaging>ear</packaging>\n" + earDependencies
                + "<build><plugins><plugin>\n<artifactId>maven-ear-plugin</artifactId>\n<configuration><modules><webModule>\n"
                + "<groupId>com.acme</groupId>\n<artifactId>web</artifactId>\n</webModule></modules></configuration>\n"
                + "</plugin></plugins></build>\n</project>\n");
        return b.var("moduleType", "war").var("module", "com.acme:web").var("failedModule", "app");
    }

    @Test
    void earModuleWithoutDependencyIsConfirmed() {
        CheckBench b = earProject("war", "");
        Path ear = abs(b.project().resolve("app/pom.xml"));
        CheckResult r = b.run(new PomChecks.EarModule());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), ear, 9);
        assertEquals("war", r.getVars().get("modulePackaging"));
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", ear.toString(), "line", "4");
    }

    @Test
    void earModuleWithOtherPackagingNamesTheDifference() {
        CheckResult r = earProject("jar", "<dependencies>\n</dependencies>\n").run(new PomChecks.EarModule());
        assertConfirmed(r);
        assertEquals("jar", r.getVars().get("modulePackaging"));
        assertEvidenceText(r.getEvidence(), Evidence.Kind.HINT, "jar");
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "line", "5");
    }

    @Test
    void earModuleThatIsADependencyIsNotApplicable() {
        String deps = "<dependencies><dependency><groupId>com.acme</groupId><artifactId>web</artifactId>"
                + "<version>1.0</version><type>war</type></dependency></dependencies>\n";
        assertNotApplicable(earProject("war", deps).run(new PomChecks.EarModule()));
        assertNotApplicable(bench().var("moduleType", "war").var("module", "com.acme:web").run(new PomChecks.EarModule()));
        assertNotApplicable(bench().var("moduleType", "war").var("module", "web").run(new PomChecks.EarModule()));
    }

    // ------------------------------------------------------------------ ee-namespace

    private static final String JAVAX_API = "<project>\n<dependencies><dependency>\n<groupId>javax</groupId>\n"
            + "<artifactId>javaee-api</artifactId>\n<version>8.0.1</version></dependency></dependencies>\n</project>\n";

    @Test
    void jakartaPackageWithOnlyJavaxApiIsConfirmed() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", JAVAX_API);
        CheckResult r = b.var("package", "jakarta.servlet.http").run(new PomChecks.EeNamespace());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), abs(pom), 4);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "line", "4");
    }

    @Test
    void jakartaPackageWithBothApisIsOnlyAHint() {
        CheckBench b = bench();
        b.projectFile("pom.xml", JAVAX_API);
        b.projectFile("web/pom.xml", "<project><dependencies><dependency><groupId>jakarta.platform</groupId>"
                + "<artifactId>jakarta.jakartaee-api</artifactId></dependency></dependencies></project>");
        assertInfo(b.var("package", "jakarta.servlet.http").run(new PomChecks.EeNamespace()));
    }

    @Test
    void javaxImportWithJakartaArtifactsAsksToSwitchImports() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project>\n<dependencies><dependency>\n<groupId>jakarta.servlet</groupId>\n"
                + "<artifactId>jakarta.servlet-api</artifactId></dependency></dependencies>\n</project>\n");
        CheckResult r = b.var("package", "javax.servlet.http").run(new PomChecks.EeNamespace());
        assertInfo(r);
        assertEvidence(r.getEvidence(), abs(pom), 3);
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javax.xml.bind", "javax.xml.bind.annotation", "javax.annotation", "javax.activation", "javax.jws"})
    void packageRemovedFromTheJdkIsExplained(String pkg) {
        CheckResult r = bench().runner("17.0.2").var("package", pkg).run(new PomChecks.EeNamespace());
        assertInfo(r);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.ENVIRONMENT, "17.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javax.annotation.processing", "javax.transaction.xa", "javax.servlet", "org.slf4j"})
    void packageThatIsNeitherIsNotApplicable(String pkg) {
        assertNotApplicable(bench().var("package", pkg).run(new PomChecks.EeNamespace()));
    }

    @Test
    void jakartaPackageWithoutJavaxApiIsNotApplicable() {
        CheckResult r = bench().var("package", "jakarta.servlet").run(new PomChecks.EeNamespace());
        assertNotApplicable(r);
        assertNull(r.getCause());
    }
}
