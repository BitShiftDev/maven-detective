package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.preflight.RemoteRepositories.Repo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link RemoteRepositories} direkt: URL-Vergleich, Repositories aus der POM, Mirror je Repository, http wie Maven. */
class RemoteRepositoriesTest {

    @ParameterizedTest(name = "{0} = {1}: {2}")
    @CsvSource({
        "https://nexus.corp/repo, https://nexus.corp/repo, true",
        "https://nexus.corp/repo/, https://nexus.corp/repo, true",
        "' https://nexus.corp/repo// ', https://nexus.corp/repo, true",
        "https://NEXUS.Corp/repo, https://nexus.corp/repo, true",
        "HTTPS://nexus.corp:8443/repo, https://nexus.corp:8443/repo, true",
        "https://nexus.corp, https://NEXUS.corp/, true",
        // der Pfad unterscheidet Groß/Klein (Nexus, Artifactory)
        "https://nexus.corp/Repo, https://nexus.corp/repo, false",
        "https://nexus.corp/repository/Public, https://nexus.corp/repository/public, false",
        "https://nexus.corp/repo, http://nexus.corp/repo, false",
        "https://nexus.corp:8443/repo, https://nexus.corp/repo, false",
        "https://nexus.corp/repo, https://nexus.corp/repo2, false",
        "nexus.corp/repo, NEXUS.corp/repo, false",
    })
    void gleicheUrl(String a, String b, boolean same) {
        assertEquals(same, RemoteRepositories.sameUrl(a, b));
        assertEquals(same, RemoteRepositories.sameUrl(b, a));
    }

    /** Wie {@code external:http:*} in Mavens {@code DefaultMirrorSelector}: nur localhost und 127.0.0.1 sind lokal. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
        "http://repo.corp/m2, true",
        "HTTP://repo.corp/m2, true",
        "http://user:pw@repo.corp/m2, true",
        "dav:http://repo.corp/m2, true",
        "http://localhost:8081/repo, false",
        "http://127.0.0.1/repo, false",
        "http://localhost.corp.example/repo, true",
        "http://0.0.0.0/, true",
        "https://repo.corp/m2, false",
        "file:///tmp/repo, false",
        "${env.NEXUS}/repo, false",
    })
    void httpWieMaven(String url, boolean blocked) {
        assertEquals(blocked, PreflightChecks.isRemoteHttp(url));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "null", value = {
        "http://repo.corp/m2, https://repo.corp/m2",
        "HTTP://repo.corp/m2, https://repo.corp/m2",
        "dav:http://repo.corp/m2, null",
        "https://repo.corp/m2, null",
    })
    void httpsErsatz(String url, String expected) {
        assertEquals(expected, PreflightChecks.https(url));
    }

    static List<String> declared(String... lines) {
        return RemoteRepositories.declared(null, Arrays.asList(lines)).stream()
                .map(r -> r.id + " " + r.url + " @" + r.line).collect(Collectors.toList());
    }

    @Test
    void repositoriesUndPluginRepositoriesMitZeileDerUrl() {
        assertEquals(List.of("corp https://nexus.corp/m2 @5", "plugins https://plugins.corp/m2 @10"), declared(
                "<project>",
                "  <repositories>",
                "    <repository>",
                "      <id>corp</id>",
                "      <url>https://nexus.corp/m2</url>",
                "    </repository>",
                "  </repositories>",
                "  <pluginRepositories>",
                "    <pluginRepository><id>plugins</id>",
                "      <url> https://plugins.corp/m2 </url>",
                "    </pluginRepository>",
                "  </pluginRepositories>",
                "</project>"));
    }

    @Test
    void nurVollstaendigeRepositoriesAusserhalbVonDistributionManagement() {
        assertEquals(List.of("one https://one/m2 @2"), declared(
                "<distributionManagement><repository><id>deploy</id><url>https://deploy/m2</url></repository></distributionManagement>",
                "<repositories><repository><id>one</id><url>https://one/m2</url></repository>",
                "<repository><id>no-url</id></repository>",
                "<repository><url>https://no-id/m2</url></repository>",
                "<repository><id>placeholder</id><url>${repo.url}/m2</url></repository>",
                "</repositories>",
                "<repository><id>outside</id><url>https://outside/m2</url></repository>"));
    }

    @Test
    void repositoriesAusProfilenZaehlenMit() {
        // Der Preflight wertet Profile nicht aus; ein Repository aus einem Profil wird geprüft wie jedes andere
        assertEquals(List.of("in-profile https://profile/m2 @4"), declared(
                "<profiles><profile><id>p</id>",
                "  <repositories>",
                "    <repository><id>in-profile</id>",
                "      <url>https://profile/m2</url></repository>",
                "  </repositories>",
                "</profile></profiles>"));
    }

    @Test
    void projektRepositoryUeberschreibtCentralUndMirrorsGeltenJeRepository(@TempDir Path tmp) throws Exception {
        PreflightChecksTest.Bench bench = new PreflightChecksTest.Bench(tmp);
        bench.pom("""
                <packaging>pom</packaging>
                <modules><module>module</module></modules>
                <repositories>
                  <repository><id>central</id><url>https://central.corp/m2</url></repository>
                  <repository><id>snapshots</id><url>https://snapshots.corp/m2</url></repository>
                </repositories>""");
        bench.file("module/pom.xml", "<project><artifactId>m</artifactId>"
                + "<repositories><repository><id>snapshots</id><url>https://other.corp/m2</url></repository></repositories></project>");
        bench.settings("""
                <settings><mirrors>
                  <mirror><id>all</id><mirrorOf>*,!snapshots</mirrorOf><url>https://nexus.corp/public</url></mirror>
                </mirrors></settings>""");
        PreflightContext ctx = new PreflightContext(bench.project, bench.setup, bench.options);
        List<Repo> repos = RemoteRepositories.collect(ctx);
        assertEquals(List.of("central", "snapshots"), repos.stream().map(r -> r.id).toList());
        Repo central = repos.get(0);
        assertEquals("https://central.corp/m2", central.url, "Projekt überschreibt Mavens Central");
        assertEquals("all", central.effectiveId());
        assertEquals("https://nexus.corp/public", central.effectiveUrl());
        Repo snapshots = repos.get(1);
        assertNull(snapshots.mirror, "!snapshots nimmt es aus");
        assertEquals("https://snapshots.corp/m2", snapshots.effectiveUrl(), "erste Deklaration gewinnt");
    }

    @Test
    void relativesLokalesRepositoryAusSettingsGiltImProjekt(@TempDir Path tmp) {
        PreflightChecksTest.Bench bench = new PreflightChecksTest.Bench(tmp);
        bench.settings("<settings><localRepository>.m2/repo</localRepository></settings>");
        PreflightContext ctx = new PreflightContext(bench.project.toAbsolutePath(), bench.setup, bench.options);
        assertEquals(bench.project.toAbsolutePath().resolve(".m2/repo"), ctx.localRepository());

        bench.settings("<settings><localRepository>" + tmp.resolve("abs").toAbsolutePath() + "</localRepository></settings>");
        assertEquals(tmp.resolve("abs").toAbsolutePath(),
                new PreflightContext(bench.project.toAbsolutePath(), bench.setup, bench.options).localRepository());
    }
}
