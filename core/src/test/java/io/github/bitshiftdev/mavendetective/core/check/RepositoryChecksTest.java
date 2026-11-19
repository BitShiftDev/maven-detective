package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.testkit.CheckBench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertConfirmed;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidence;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertEvidenceText;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertFirstFix;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertInfo;
import static io.github.bitshiftdev.mavendetective.core.testkit.Diagnoses.assertNotApplicable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Prüfungen aus {@link RepositoryChecks}, direkt über {@link Check#run(CheckContext)}. */
class RepositoryChecksTest {

    private static final String NEXUS = "https://nexus.acme.example/repo";

    @TempDir
    Path tmp;

    private CheckBench bench() {
        return CheckBench.in(tmp);
    }

    // ------------------------------------------------------------------ server-id

    @Test
    void missingServerIsConfirmedAndPointsToTheServersBlock() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n  <servers>\n    <server><id>Nexus</id></server>\n  </servers>\n</settings>\n");
        CheckResult r = b.var("repoId", "nexus").var("repoUrl", NEXUS).var("status", "401").run(new RepositoryChecks.ServerId());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), settings, 2);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.HINT, "'Nexus'");
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "2");
    }

    @Test
    void settingsWithoutServersPointToTheEndOfTheFile() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n  <offline>false</offline>\n</settings>\n");
        CheckResult r = b.var("repoId", "nexus").var("repoUrl", NEXUS).run(new RepositoryChecks.ServerId());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "3");
    }

    @Test
    void mirrorThatTakesOverTheRepositoryIsNamed() {
        CheckBench b = bench();
        b.homeFile(".m2/settings.xml", "<settings>\n<mirrors><mirror><id>corp</id><mirrorOf>*</mirrorOf>"
                + "<url>https://nexus.acme.example/all</url></mirror></mirrors>\n</settings>\n");
        CheckResult r = b.var("repoId", "internal").var("repoUrl", NEXUS).run(new RepositoryChecks.ServerId());
        assertConfirmed(r);
        assertEvidenceText(r.getEvidence(), Evidence.Kind.HINT, "corp");
    }

    @Test
    void presentServerMeansWrongCredentialsAndIsOnlyAHint() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<servers>\n<server>\n<id>nexus</id>\n</server>\n</servers>\n</settings>\n");
        CheckResult unauthorized = b.var("repoId", "nexus").var("repoUrl", NEXUS).var("status", "401").run(new RepositoryChecks.ServerId());
        assertInfo(unauthorized);
        assertEvidence(unauthorized.getEvidence(), settings, 4);
        assertFirstFix(unauthorized.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "4");

        CheckResult forbidden = b.var("status", "403").run(new RepositoryChecks.ServerId());
        assertInfo(forbidden);
        assertNotEquals(unauthorized.getCause(), forbidden.getCause(), "403 = Rechte fehlen, nicht Zugangsdaten");
    }

    @Test
    void serverInTheGlobalSettingsCounts() {
        CheckBench b = bench();
        b.homeFile(".m2/settings.xml", "<settings><servers><server><id>other</id></server></servers></settings>");
        Path global = b.globalSettings("<settings>\n<servers>\n<server><id>nexus</id></server>\n</servers>\n</settings>\n");
        CheckResult r = b.var("repoId", "nexus").var("repoUrl", NEXUS).run(new RepositoryChecks.ServerId());
        assertInfo(r);
        assertEvidence(r.getEvidence(), global, 3);
    }

    @Test
    void withoutAnySettingsTheFileIsMissing() {
        CheckBench b = bench();
        CheckResult r = b.var("repoId", "nexus").var("repoUrl", NEXUS).run(new RepositoryChecks.ServerId());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), b.home().resolve(".m2/settings.xml"), -1);
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void mavenCentralNeedsNoServerEntry() {
        CheckResult r = bench().var("repoId", "central").var("repoUrl", "https://repo.maven.apache.org/maven2")
                .var("status", "403").run(new RepositoryChecks.ServerId());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void repositoryFromTheFirstDeniedRequestIsTaken() {
        CheckBench b = bench();
        b.homeFile(".m2/settings.xml", "<settings><servers/></settings>");
        CheckResult r = b.failedRequest("a:b:jar:1", "other", "https://other.example", "not found")
                .failedRequest("a:b:jar:1", "nexus", NEXUS, "status code: 401, reason phrase: Unauthorized (401)")
                .run(new RepositoryChecks.ServerId());
        assertConfirmed(r);
        assertEquals("nexus", r.getVars().get("repoId"));
    }

    @Test
    void withoutDeniedRepositoryServerIdIsNotApplicable() {
        CheckResult r = bench().failedRequest("a:b:jar:1", "other", "https://other.example", "status code: 404")
                .run(new RepositoryChecks.ServerId());
        assertNotApplicable(r);
    }

    // ------------------------------------------------------------------ http-repository

    private static final String BLOCKED = "http://repo.example.org/m2";

    private static String pomWithRepository(String url) {
        return "<project>\n<repositories>\n<repository>\n<id>old</id>\n<url>" + url + "</url>\n</repository>\n</repositories>\n</project>\n";
    }

    @Test
    void httpRepositoryInTheProjectGetsTheUrlReplaced() {
        CheckBench b = bench();
        Path pom = b.projectFile("pom.xml", pomWithRepository(BLOCKED));
        CheckResult r = b.var("repoId", "old").var("repoUrl", BLOCKED + "/").run(new RepositoryChecks.HttpRepository());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), pom.toAbsolutePath().normalize(), 5);
        assertFirstFix(r.getFixes(), FixAction.REPLACE_URL, "search", BLOCKED, "replace", "https://repo.example.org/m2");
    }

    /** Eine längere Adresse mit demselben Anfang ist ein anderes Repository; replace-url fände dort nichts. */
    @Test
    void longerUrlWithTheSamePrefixIsNotTheBlockedRepository() {
        CheckBench b = bench();
        b.projectFile("pom.xml", pomWithRepository(BLOCKED + "-old"));
        CheckResult r = b.var("repoId", "old").var("repoUrl", BLOCKED).run(new RepositoryChecks.HttpRepository());
        assertInfo(r);
    }

    @Test
    void httpRepositoryInSettingsIsReplacedThere() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n<mirrors>\n<mirror><id>m</id><mirrorOf>*</mirrorOf>\n<url>"
                + BLOCKED + "</url></mirror>\n</mirrors>\n</settings>\n");
        CheckResult r = b.var("repoId", "m").var("repoUrl", BLOCKED).run(new RepositoryChecks.HttpRepository());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), settings, 4);
        assertFirstFix(r.getFixes(), FixAction.REPLACE_IN_FILE, "file", settings.toString(), "search", BLOCKED);
    }

    @Test
    void transitiveHttpRepositoryOnlyPointsToTheMirrors() {
        CheckBench b = bench();
        Path settings = b.homeFile(".m2/settings.xml", "<settings>\n  <mirrors/>\n</settings>\n");
        CheckResult r = b.var("repoId", "old").var("repoUrl", BLOCKED).run(new RepositoryChecks.HttpRepository());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.OPEN_FILE, "file", settings.toString(), "line", "2");
    }

    @Test
    void transitiveHttpRepositoryWithoutSettingsIsManual() {
        CheckResult r = bench().var("repoId", "old").var("repoUrl", BLOCKED).run(new RepositoryChecks.HttpRepository());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.MANUAL);
    }

    @Test
    void httpsRepositoryIsNotApplicable() {
        assertNotApplicable(bench().var("repoUrl", "https://repo.example.org/m2").run(new RepositoryChecks.HttpRepository()));
        assertNotApplicable(bench().run(new RepositoryChecks.HttpRepository()));
    }

    // ------------------------------------------------------------------ corrupt-jar

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("a/A.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha1(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-1").digest(data)) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    @Test
    void unreadableJarIsConfirmedAndOfferedForDeletion() {
        CheckBench b = bench();
        Path jar = b.repositoryFile("com/x/lib/1.0/lib-1.0.jar", "not a zip".getBytes(StandardCharsets.US_ASCII));
        CheckResult r = b.var("file", jar.toString()).run(new RepositoryChecks.CorruptJar());
        assertConfirmed(r);
        assertEvidence(r.getEvidence(), jar, -1);
        assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "files", jar.toString());
    }

    @Test
    void readableJarWithWrongChecksumIsConfirmed() throws Exception {
        CheckBench b = bench();
        Path jar = b.repositoryFile("com/x/lib/1.0/lib-1.0.jar", zip());
        b.repositoryFile("com/x/lib/1.0/lib-1.0.jar.sha1", "0000000000000000000000000000000000000000  lib-1.0.jar".getBytes(StandardCharsets.US_ASCII));
        CheckResult r = b.var("file", jar.toString()).run(new RepositoryChecks.CorruptJar());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "files", jar.toString());
    }

    @Test
    void readableJarWithMatchingChecksumIsOnlyAHint() throws Exception {
        CheckBench b = bench();
        byte[] zip = zip();
        Path jar = b.repositoryFile("com/x/lib/1.0/lib-1.0.jar", zip);
        b.repositoryFile("com/x/lib/1.0/lib-1.0.jar.sha1", sha1(zip).toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        CheckResult r = b.var("file", jar.toString()).run(new RepositoryChecks.CorruptJar());
        assertInfo(r);
        assertEvidence(r.getEvidence(), jar, -1);
    }

    @Test
    void jarPathIsTakenFromTheLogLine() {
        CheckBench b = bench();
        Path jar = b.repositoryFile("com/x/lib/1.0/lib-1.0.jar", new byte[0]);
        CheckResult r = b.line("[ERROR] error reading " + jar + "; zip file is empty").run(new RepositoryChecks.CorruptJar());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "files", jar.toString());
    }

    @Test
    void jarPathIsDerivedFromTheArtifact() {
        CheckBench b = bench();
        Path jar = b.repositoryFile("com/x/lib/1.0/lib-1.0-tests.jar", "x".getBytes(StandardCharsets.US_ASCII));
        CheckResult r = b.var("artifact", "com.x:lib:jar:tests:1.0").run(new RepositoryChecks.CorruptJar());
        assertConfirmed(r);
        assertFirstFix(r.getFixes(), FixAction.DELETE_FILES, "files", jar.toString());
    }

    @Test
    void jarThatIsAlreadyGoneOnlySuggestsARebuild() {
        CheckBench b = bench();
        CheckResult r = b.var("file", b.repository().resolve("com/x/lib/1.0/lib-1.0.jar").toString()).run(new RepositoryChecks.CorruptJar());
        assertInfo(r);
        assertFirstFix(r.getFixes(), FixAction.REBUILD);
    }

    @Test
    void withoutJarOrNetworkPathCorruptJarIsNotApplicable() {
        assertNotApplicable(bench().line("[ERROR] zip END header not found").run(new RepositoryChecks.CorruptJar()));
        assertNotApplicable(bench().var("file", "\\\\host\\share\\lib.jar").run(new RepositoryChecks.CorruptJar()));
    }
}
