package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.testkit.CheckBench;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertConfirmed;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidence;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidenceText;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFirstFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertInfo;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertNotApplicable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüfungen aus {@link ResolutionChecks}, direkt über {@link Check#run(CheckContext)}. */
class ResolutionChecksTest {

    @TempDir
    Path tmp;

    private CheckBench bench() {
        return CheckBench.in(tmp);
    }

    // ------------------------------------------------------------------ offline-mode

    @Test
    void offlineFlagOfTheRunIsConfirmedWithRebuildWithoutOfflineArgs() {
        CheckResult r = bench().offline().line("[ERROR] x").run(new ResolutionChecks.OfflineMode());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.REBUILD, "removeArgs", "-o --offline");
        assertEvidenceText(r.getEvidence(), Evidence.Kind.ENVIRONMENT, "");
    }

    @Test
    void offlineInSettingsPointsToTheOfflineLine() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n  <offline>true</offline>\n</settings>\n");
        CheckResult r = b.run(new ResolutionChecks.OfflineMode());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), settings, 2);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "2");
    }

    @Test
    void offlineMessageInTheLogIsEnough() {
        CheckResult r = bench().line("[ERROR] Cannot access central (https://repo.maven.apache.org/maven2) in offline mode "
                + "and the artifact a:b:jar:1 has not been downloaded from it before.").run(new ResolutionChecks.OfflineMode());
        assertConfirmed(r);
    }

    @Test
    void onlineRunIsNotApplicable() {
        CheckResult r = bench().withHomeFile(".m2/settings.xml", "<settings><offline>false</offline></settings>")
                .line("[ERROR] Could not find artifact a:b:jar:1").run(new ResolutionChecks.OfflineMode());
        assertNotApplicable(r);
    }

    // ------------------------------------------------------------------ network-failure

    @Test
    void tlsProblemIsConfirmedAndNamesTheJdkOfTheRun() {
        CheckResult r = bench().runner("17.0.9").javaHome("/opt/jdk-17")
                .line("[ERROR] Could not transfer artifact a:b:pom:1 from/to nexus (https://nexus.acme.example/repo): "
                        + "PKIX path building failed: unable to find valid certification path to requested target")
                .run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
        Evidence jdk = assertEvidenceText(r.getEvidence(), Evidence.Kind.ENVIRONMENT, "17.0.9");
        assertTrue(jdk.getText().contains("/opt/jdk-17"), jdk.getText());
    }

    @Test
    void proxyAuthenticationPointsToTheProxiesInSettings() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n  <proxies>\n    <proxy><id>corp</id></proxy>\n  </proxies>\n</settings>\n");
        CheckResult r = b.line("[ERROR] Could not transfer artifact a:b:pom:1 from/to central (https://repo.maven.apache.org/maven2): "
                + "status code: 407, reason phrase: Proxy Authentication Required (407)").run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "2");
    }

    @Test
    void proxyAuthenticationWithoutSettingsIsConfirmedWithoutFix() {
        CheckResult r = bench().line("[ERROR] Proxy Authentication Required").run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertTrue(r.getFixes().isEmpty(), r.getFixes().toString());
    }

    /** Die Formen, in denen JDK und Transport einen unbekannten Host melden. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "from/to central (https://repo.acme.example/m2): repo.acme.example: Name or service not known | repo.acme.example",
        "from/to central (https://repo.acme.example/m2): repo.acme.example: nodename nor servname provided, or not known | repo.acme.example",
        "from/to central (https://repo.acme.example/m2): repo.acme.example: No such host is known (repo.acme.example) | repo.acme.example",
        "from/to central (https://repo.acme.example/m2): repo.acme.example: Temporary failure in name resolution | repo.acme.example",
        "from/to central (https://repo.acme.example/m2): Unknown host repo.acme.example: Name or service not known | repo.acme.example",
        "from/to central (https://repo.acme.example/m2): Unknown host repo.acme.example | repo.acme.example",
    })
    void unknownHostIsConfirmedWithTheHost(String message, String host) {
        CheckResult r = bench().line("[ERROR] Could not transfer artifact a:b:pom:1 " + message).run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertEquals(host, r.getVars().get("host"));
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void refusedConnectionNamesHostAndPort() {
        CheckResult r = bench().line("[ERROR] Could not transfer artifact a:b:pom:1 from/to nexus (http://localhost:8081/repo): "
                + "Connect to localhost:8081 [localhost/127.0.0.1] failed: Connection refused").run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertEquals("localhost:8081", r.getVars().get("host"));
    }

    @Test
    void timeoutIsConfirmedWithoutHost() {
        CheckResult r = bench().line("[ERROR] Could not transfer artifact a:b:pom:1 from/to central (https://repo.maven.apache.org/maven2): "
                + "Read timed out").run(new ResolutionChecks.NetworkFailure());
        assertConfirmed(r);
        assertNull(r.getVars().get("host"));
        assertTrue(r.getVars().containsKey("networkReason"));
    }

    @Test
    void artifactThatDoesNotExistIsNoNetworkFailure() {
        CheckResult r = bench().line("[ERROR] Could not find artifact a:b:jar:1 in central (https://repo.maven.apache.org/maven2)")
                .run(new ResolutionChecks.NetworkFailure());
        assertNotApplicable(r);
    }

    // ------------------------------------------------------------------ last-updated-files

    @Test
    void lastUpdatedFilesOfTheArtifactAreOfferedForDeletion() {
        CheckBench b = bench();
        Path jar = b.repositoryFile("org/lib/lib/1.0/lib-1.0.jar.lastUpdated", "#".getBytes());
        Path pom = b.repositoryFile("org/lib/lib/1.0/lib-1.0.pom.lastUpdated", "#".getBytes());
        b.repositoryFile("org/lib/lib/1.0/_remote.repositories", "#".getBytes());
        CheckResult r = b.var("artifact", "org.lib:lib:jar:1.0").run(new ResolutionChecks.LastUpdatedFiles());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), jar, -1);
        assertEvidence(r.getEvidence(), pom, -1);
        FixAction fix = assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "args", "-U");
        assertEquals(2, fix.param("files").split("\n").length, fix.param("files"));
    }

    @Test
    void lastUpdatedFilesOfAllFailedRequestsAreCollected() {
        CheckBench b = bench();
        Path first = b.repositoryFile("org/a/a/1.0/a-1.0.jar.lastUpdated", "#".getBytes());
        Path second = b.repositoryFile("org/b/b/2.0/b-2.0.pom.lastUpdated", "#".getBytes());
        CheckResult r = b.failedRequest("org.a:a:jar:1.0", "central", "https://repo.maven.apache.org/maven2", "not found")
                .failedRequest("org.b:b:pom:2.0", "central", "https://repo.maven.apache.org/maven2", "not found")
                .run(new ResolutionChecks.LastUpdatedFiles());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "files", first + "\n" + second);
    }

    @Test
    void cachedFailureWithoutMarkerFileOnlyForcesAnUpdate() {
        CheckResult r = bench().var("artifact", "org.lib:lib:jar:1.0")
                .line("[ERROR] org.lib:lib:jar:1.0 was not found in https://repo.maven.apache.org/maven2 during a previous attempt.")
                .run(new ResolutionChecks.LastUpdatedFiles());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.REBUILD, "args", "-U");
    }

    @Test
    void withoutMarkerAndCacheMessageLastUpdatedIsNotApplicable() {
        CheckResult r = bench().var("artifact", "org.lib:lib:jar:1.0")
                .line("[ERROR] Could not find artifact org.lib:lib:jar:1.0").run(new ResolutionChecks.LastUpdatedFiles());
        assertNotApplicable(r);
    }

    // ------------------------------------------------------------------ reactor-sibling

    private CheckBench reactor() {
        return bench()
                .withProjectFile("pom.xml", TestPom.of("com.acme", "root", "1.0").packaging("pom").module("core").module("web").xml())
                .withProjectFile("core/pom.xml", TestPom.of(null, "core", null).parent("com.acme", "root", "1.0").xml())
                .withProjectFile("web/pom.xml", TestPom.of(null, "web", null).parent("com.acme", "root", "1.0").packaging("war").xml());
    }

    @Test
    void reactorModuleIsConfirmedAndBuiltWithAlsoMake() {
        CheckBench b = reactor();
        CheckResult r = b.var("artifact", "com.acme:core:jar:1.0").run(new ResolutionChecks.ReactorSibling());
        assertConfirmed(r);
        assertEquals("core", r.getVars().get("module"));
        assertFirstFix(r.getFixes(), FixAction.REBUILD, "args", "-am");
        Path pom = b.project().resolve("core/pom.xml").toAbsolutePath().normalize();
        assertEquals(pom.toString(), r.getEvidence().get(0).getFile());
        assertTrue(r.getEvidence().get(0).getLine() > 0, r.getEvidence().toString());
    }

    @Test
    void rootProjectAsSiblingIsCalledDot() {
        CheckResult r = reactor().var("artifact", "com.acme:root:pom:1.0").run(new ResolutionChecks.ReactorSibling());
        assertConfirmed(r);
        assertEquals(".", r.getVars().get("module"));
    }

    @Test
    void warModuleRequestedAsJarIsATypeMismatchWithoutConsumer() {
        CheckResult r = reactor().var("artifact", "com.acme:web:jar:1.0").run(new ResolutionChecks.ReactorSibling());
        assertConfirmed(r);
        assertEquals("war", r.getVars().get("packaging"));
        assertEquals("jar", r.getVars().get("requestedType"));
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void requestWithClassifierIsNoTypeMismatch() {
        CheckResult r = reactor().var("artifact", "com.acme:web:jar:classes:1.0").run(new ResolutionChecks.ReactorSibling());
        assertConfirmed(r);
        assertNull(r.getVars().get("packaging"));
        assertFirstFix(r.getFixes(), FixAction.REBUILD, "args", "-am");
    }

    @Test
    void artifactFromOutsideTheProjectIsNotApplicable() {
        assertNotApplicable(reactor().var("artifact", "org.other:core:jar:1.0").run(new ResolutionChecks.ReactorSibling()));
        assertNotApplicable(bench().withoutProject().var("artifact", "com.acme:core:jar:1.0").run(new ResolutionChecks.ReactorSibling()));
    }

    // ------------------------------------------------------------------ requested-repository

    @Test
    void failedRequestsFromEventsAreListedOnce() {
        CheckBench b = bench();
        for (int i = 0; i < 3; i++) {
            b.failedRequest("org.a:a:jar:" + i, "nexus", "https://nexus.acme.example/repo", "status code: 401");
        }
        for (int i = 0; i < 8; i++) {
            b.failedRequest("org.b:b:jar:1", "repo" + i, "https://repo" + i + ".example", "not found");
        }
        CheckResult r = b.run(new ResolutionChecks.RequestedRepository());
        assertInfo(r);
        assertEquals(5, r.getEvidence().size(), r.getEvidence().toString());
        assertEquals("nexus", r.getVars().get("repoId"), "erste gescheiterte Anfrage");
    }

    @Test
    void repositoryFromTheLogIsShownWithoutEvents() {
        CheckResult r = bench().var("repoId", "nexus").var("repoUrl", "https://nexus.acme.example/repo")
                .run(new ResolutionChecks.RequestedRepository());
        assertInfo(r);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.ENVIRONMENT, "nexus (https://nexus.acme.example/repo)");
    }

    @Test
    void mirrorWithTheRepositoryIdPointsToItsSettingsLine() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<mirrors>\n<mirror>\n<id>corp</id>\n"
                + "<mirrorOf>*</mirrorOf>\n<url>https://nexus.acme.example/all</url>\n</mirror>\n</mirrors>\n</settings>\n");
        CheckResult r = b.var("repoId", "corp").var("repoUrl", "https://nexus.acme.example/all")
                .run(new ResolutionChecks.RequestedRepository());
        assertInfo(r);
        assertEvidence(r.getEvidence(), settings, 4);
    }

    @Test
    void mirrorThatCatchesTheRepositoryIsShown() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<mirrors>\n<mirror>\n<id>corp</id>\n"
                + "<mirrorOf>*</mirrorOf>\n<url>https://nexus.acme.example/all</url>\n</mirror>\n</mirrors>\n</settings>\n");
        CheckResult r = b.var("repoId", "central").var("repoUrl", "https://repo.maven.apache.org/maven2")
                .run(new ResolutionChecks.RequestedRepository());
        assertEvidence(r.getEvidence(), settings, 4);
    }

    /** Ohne Repository im Text kommt es nur aus den Events – der Mirror gilt trotzdem. */
    @Test
    void mirrorIsAlsoFoundForTheRepositoryFromEvents() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<mirrors>\n<mirror>\n<id>corp</id>\n"
                + "<mirrorOf>*</mirrorOf>\n<url>https://nexus.acme.example/all</url>\n</mirror>\n</mirrors>\n</settings>\n");
        CheckResult r = b.failedRequest("org.a:a:jar:1", "central", "https://repo.maven.apache.org/maven2", "not found")
                .run(new ResolutionChecks.RequestedRepository());
        assertInfo(r);
        assertEvidence(r.getEvidence(), settings, 4);
    }

    @Test
    void withoutRepositoryRequestedRepositoryIsNotApplicable() {
        CheckResult r = bench().line("[ERROR] Could not find artifact a:b:jar:1").run(new ResolutionChecks.RequestedRepository());
        assertNotApplicable(r);
    }
}
