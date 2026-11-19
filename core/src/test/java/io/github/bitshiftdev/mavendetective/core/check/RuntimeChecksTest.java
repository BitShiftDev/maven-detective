package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.testkit.CheckBench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertConfirmed;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidence;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidenceText;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFirstFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertInfo;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertNoFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertNotApplicable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüfungen aus {@link RuntimeChecks}, direkt über {@link Check#run(CheckContext)}. {@code jdk-version} und
 * {@code memory-settings} liefern immer etwas (mindestens die Umgebung als Beleg), nie „nicht anwendbar“.
 */
class RuntimeChecksTest {

    @TempDir
    Path tmp;

    private CheckBench bench() {
        return CheckBench.in(tmp);
    }

    // ------------------------------------------------------------------ jdk-version

    @Test
    void olderRunnerThanRequiredIsConfirmedWithJdkSwitch() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project>\n<properties>\n<maven.compiler.release>21</maven.compiler.release>\n</properties>\n</project>\n");
        CheckResult r = b.runner("17.0.9").var("required", "21").run(new RuntimeChecks.JdkVersion());
        assertConfirmed(r);
        assertEquals("21", r.getVars().get("required"));
        assertEquals("17", r.getVars().get("runner"));
        assertEvidence(r.getEvidence(), pom.toAbsolutePath().normalize(), 3);
        assertFirstFix(r.getFixes(), FixAction.SWITCH_JDK, "required", "21");
    }

    @Test
    void classFileVersionsAreTranslatedToJavaVersions() {
        CheckResult r = bench().var("classVersion", "65").var("runtimeClassVersion", "61").run(new RuntimeChecks.JdkVersion());
        assertConfirmed(r);
        assertEquals("21", r.getVars().get("required"));
        assertEquals("17", r.getVars().get("runner"));
    }

    @Test
    void runnerFromTheEnforcerMessageCounts() {
        CheckResult r = bench().var("required", "17").var("runner", "1.8.0_392").run(new RuntimeChecks.JdkVersion());
        assertConfirmed(r);
        assertEquals("8", r.getVars().get("runner"));
    }

    @Test
    void unknownRunnerIsOnlyAHintButStillOffersTheSwitch() {
        CheckResult r = bench().var("required", "21").run(new RuntimeChecks.JdkVersion());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.SWITCH_JDK, "required", "21");
    }

    @Test
    void sourceOptionNoLongerSupportedRaisesTheReleaseInsteadOfSwitchingJdk() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project>\n<properties>\n<maven.compiler.source>7</maven.compiler.source>\n"
                + "<maven.compiler.target>7</maven.compiler.target>\n</properties>\n</project>\n");
        CheckResult r = b.runner("21.0.2").var("required", "7")
                .line("[ERROR] Source option 7 is no longer supported. Use 8 or later.").run(new RuntimeChecks.JdkVersion());
        assertConfirmed(r);
        assertNoFix(r.getFixes(), FixAction.SWITCH_JDK);
        String file = pom.toAbsolutePath().normalize().toString();
        assertFix(r.getFixes(), FixAction.OPEN_FILE, "file", file, "line", "4");
        assertEquals(2, r.getFixes().size(), r.getFixes().toString());
    }

    @Test
    void toolchainProblemPointsToToolchainsXml() {
        CheckBench b = bench();
        Path toolchains = b.homeFile(".m2/toolchains.xml", "<toolchains/>");
        CheckResult r = b.line("[ERROR] Cannot find matching toolchain definitions for the following toolchain types:")
                .run(new RuntimeChecks.JdkVersion());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", toolchains.toString(), "line", "1");
    }

    @Test
    void missingToolchainsXmlIsNamed() {
        CheckResult r = bench().line("[ERROR] Cannot find matching toolchain definitions").run(new RuntimeChecks.JdkVersion());
        assertInfo(r);
        assertTrue(r.getFixes().isEmpty(), r.getFixes().toString());
        assertEquals(Evidence.Kind.HINT, r.getEvidence().get(r.getEvidence().size() - 1).getKind());
    }

    /** {@code (?<classVersion>\d+)} trifft auch Unsinn; die Prüfung bricht daran nicht ab. */
    @Test
    void classVersionBeyondIntIsIgnored() {
        CheckResult r = bench().var("classVersion", "99999999999").var("runtimeClassVersion", "99999999999")
                .run(new RuntimeChecks.JdkVersion());
        assertInfo(r);
        assertNull(r.getVars().get("required"));
    }

    // ------------------------------------------------------------------ source-location

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/w/app/src/main/java/com/acme/A.java:[3,8] cannot find symbol | /w/app/src/main/java/com/acme/A.java | false",
        "/w/app/src/test/java/com/acme/ATest.java:[12,5] package org.junit does not exist | /w/app/src/test/java/com/acme/ATest.java | true",
        "C:\\w\\app\\src\\test\\java\\ATest.java:[12,5] cannot find symbol | C:\\w\\app\\src\\test\\java\\ATest.java | true",
        "/w/app/src/main/kotlin/A.kt:[1,1] unresolved | /w/app/src/main/kotlin/A.kt | false",
        // javac unter Windows über den maven-compiler-plugin: Pfad als URI-Pfad mit führendem Schrägstrich
        "/C:/build/portal/src/main/java/com/acme/Csv.java:[12,9] cannot find symbol | C:/build/portal/src/main/java/com/acme/Csv.java | false",
    })
    void sourceLocationOpensTheFileAndTellsMainFromTest(String message, String file, boolean test) {
        CheckResult r = bench().line("[ERROR] " + message).run(new RuntimeChecks.SourceLocation());
        assertInfo(r);
        assertEquals(file, r.getVars().get("sourceFile"));
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", file);
        String last = r.getEvidence().get(r.getEvidence().size() - 1).getText();
        assertEquals(Texts.t(test ? "check.compile.test" : "check.compile.main"), last);
    }

    @Test
    void lineNumberBeyondIntIsNoSourceLocation() {
        assertNotApplicable(bench().line("[ERROR] /w/A.java:[99999999999,1] x").run(new RuntimeChecks.SourceLocation()));
    }

    @Test
    void withoutLocationOrWithNetworkPathSourceLocationIsNotApplicable() {
        assertNotApplicable(bench().line("[ERROR] COMPILATION ERROR :").run(new RuntimeChecks.SourceLocation()));
        assertNotApplicable(bench().line("[ERROR] \\\\host\\share\\A.java:[1,1] x").run(new RuntimeChecks.SourceLocation()));
    }

    // ------------------------------------------------------------------ enforcer-rule

    private static final String CONVERGENCE = "org.apache.maven.enforcer.rules.dependency.DependencyConvergence";

    @Test
    void enforcerRuleShowsItsMessageAndTheDependency() {
        CheckResult r = bench().var("rule", CONVERGENCE).var("artifact", "org.slf4j:slf4j-api")
                .line("Rule 0: " + CONVERGENCE + " failed with message:",
                        "Failed while enforcing releasability.",
                        "Dependency convergence error for org.slf4j:slf4j-api:jar:2.0.9 paths to dependency are:",
                        "Rule 1: org.apache.maven.enforcer.rules.BanDuplicatePomDependencyVersions failed with message:",
                        "other rule")
                .run(new RuntimeChecks.EnforcerRule());
        assertInfo(r);
        assertEquals("DependencyConvergence", r.getVars().get("ruleName"));
        assertEquals(2, r.getEvidence().size(), "nur die Zeilen bis zur nächsten Regel: " + r.getEvidence());
        assertFirstFix(r.getFixes(), FixAction.SHOW_DEPENDENCY, "artifact", "org.slf4j:slf4j-api");
    }

    @Test
    void enforcerRuleWithoutDependencyHasNoDependencyView() {
        CheckResult r = bench().var("rule", "org.apache.maven.enforcer.rules.version.RequireJavaVersion")
                .var("artifact", "x:y").run(new RuntimeChecks.EnforcerRule());
        assertInfo(r);
        assertEquals("RequireJavaVersion", r.getVars().get("ruleName"));
        assertTrue(r.getFixes().isEmpty(), r.getFixes().toString());
    }

    @Test
    void withoutRuleEnforcerRuleIsNotApplicable() {
        assertNotApplicable(bench().line("Some Enforcer rules have failed.").run(new RuntimeChecks.EnforcerRule()));
    }

    // ------------------------------------------------------------------ memory-settings

    @Test
    void containerKillIsConfirmed() {
        CheckResult r = bench().line("[ERROR] Process Exit Code: 137").run(new RuntimeChecks.MemorySettings());
        assertConfirmed(r);
    }

    @Test
    void heapOfMavenGoesToJvmConfigAndDoublesMavenOpts() {
        CheckBench b = bench();
        CheckResult r = b.env("MAVEN_OPTS", "-Xms256m -Xmx768m").line("[ERROR] java.lang.OutOfMemoryError: Java heap space")
                .run(new RuntimeChecks.MemorySettings());
        assertInfo(r);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.ENVIRONMENT, "MAVEN_OPTS=-Xms256m -Xmx768m");
        assertFirstFix(r.getFixes(), FixAction.SET_JVM_OPTION, "option", "-Xmx1536m",
                "file", b.project().resolve(".mvn/jvm.config").toAbsolutePath().normalize().toString());
    }

    @Test
    void existingJvmConfigIsShownAndDoubled() {
        CheckBench b = bench();
        Path config = b.projectFile(".mvn/jvm.config", "-Xmx1g\n");
        CheckResult r = b.line("[ERROR] java.lang.OutOfMemoryError: Java heap space").run(new RuntimeChecks.MemorySettings());
        assertEvidence(r.getEvidence(), config.toAbsolutePath().normalize(), 1);
        assertFirstFix(r.getFixes(), FixAction.SET_JVM_OPTION, "option", "-Xmx2g");
    }

    @Test
    void withoutProjectMavenOptsIsTheWay() {
        CheckResult r = bench().withoutProject().line("[ERROR] java.lang.OutOfMemoryError: Java heap space")
                .run(new RuntimeChecks.MemorySettings());
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
        assertEquals("-Xmx2g", r.getVars().get("memoryOption"));
    }

    @Test
    void testJvmWithArgLinePointsToTheArgLine() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", "<project><build><plugins><plugin>\n<artifactId>maven-surefire-plugin</artifactId>\n"
                + "<configuration>\n<argLine>-Xmx512m -Dfile.encoding=UTF-8</argLine>\n</configuration></plugin></plugins></build></project>\n");
        CheckResult r = b.mojoError("org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test",
                "[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?")
                .run(new RuntimeChecks.MemorySettings());
        assertEquals("-Xmx1g", r.getVars().get("memoryOption"));
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", pom.toAbsolutePath().normalize().toString(), "line", "4");
    }

    @Test
    void testJvmWithoutArgLineTakesTheForkCommandLine() {
        CheckResult r = bench().line("[ERROR] ExecutionException The forked VM terminated without properly saying goodbye.",
                "[ERROR] Command was /bin/sh -c cd /w && /opt/jdk/bin/java -Xmx256m -jar surefirebooter.jar")
                .run(new RuntimeChecks.MemorySettings());
        assertEquals("-Xmx512m", r.getVars().get("memoryOption"));
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void metaspaceGetsItsOwnOption() {
        CheckResult r = bench().line("[ERROR] java.lang.OutOfMemoryError: Metaspace").run(new RuntimeChecks.MemorySettings());
        assertEquals("-XX:MaxMetaspaceSize=512m", r.getVars().get("memoryOption"));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "1g, 2g", "768m, 1536m", "512m, 1g", "1536m, 3g", "2G, 4g", "1024k, 2048k", "4194304, 8388608",
        "null, 2g", "abc, 2g", "99999999999999999999g, 2g",
    })
    void doubledHeap(String xmx, String expected) {
        assertEquals(expected, RuntimeChecks.MemorySettings.doubled(xmx));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Xmx99999999999999999999m", "-Xmx9223372036854775807k"})
    void absurdHeapInMavenOptsFallsBackToTwoGigabytes(String opts) {
        CheckResult r = bench().env("MAVEN_OPTS", opts).line("[ERROR] java.lang.OutOfMemoryError: Java heap space")
                .run(new RuntimeChecks.MemorySettings());
        assertEquals("-Xmx2g", r.getVars().get("memoryOption"));
    }
}
