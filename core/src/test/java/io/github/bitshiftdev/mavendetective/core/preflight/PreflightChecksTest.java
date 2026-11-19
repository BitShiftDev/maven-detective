package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Item;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Light;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Report;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Status;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Topic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statusmatrix des Preflights: jede Prüfung mit jedem Status, den sie liefern kann, samt erstem Quick-Fix –
 * dazu die Ampel aus Kombinationen von Zeilen. Texte werden nicht verglichen.
 */
class PreflightChecksTest {

    /** Projekt, Benutzerverzeichnis, JDK und lokales Repository in einem {@code @TempDir}; Standard ist grün. */
    static final class Bench {
        final Path tmp;
        final Path project;
        final Path home;
        final Path repo;
        final BuildSetup setup;
        Preflight.Options options;

        Bench(Path tmp) {
            this.tmp = tmp;
            project = tmp.resolve("project");
            home = tmp.resolve("home");
            repo = home.resolve(".m2").resolve("repository");
            setup = new BuildSetup("test").setMavenVersion("3.9.11").setMavenSource("/opt/maven");
            options = new Preflight.Options().userHome(home).env(Map.of());
            jdk("21.0.4", true);
            pom("<properties><maven.compiler.release>17</maven.compiler.release></properties>");
            dir(repo);
        }

        static void write(Path file, String content) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static Path dir(Path dir) {
            try {
                return Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Bench jdk(String version, boolean javac) {
            Path dir = tmp.resolve("jdk-" + version + (javac ? "" : "-jre"));
            write(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
            write(dir.resolve("bin").resolve("java"), "");
            if (javac) {
                write(dir.resolve("bin").resolve("javac"), "");
            }
            setup.setJavaHome(dir.toString()).setJavaVersion(version);
            return this;
        }

        Bench pom(String body) {
            return file("pom.xml", "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.acme</groupId>\n"
                    + "  <artifactId>app</artifactId>\n  <version>1.0</version>\n" + body + "\n</project>\n");
        }

        Bench file(String relative, String content) {
            write(project.resolve(relative), content);
            return this;
        }

        Bench settings(String xml) {
            write(home.resolve(".m2").resolve("settings.xml"), xml);
            return this;
        }

        Bench global(String xml) {
            Path maven = tmp.resolve("maven");
            write(maven.resolve("conf").resolve("settings.xml"), xml);
            setup.setMavenHome(maven.toString());
            return this;
        }

        Bench repoFile(String relative, String content) {
            write(repo.resolve(relative), content);
            return this;
        }

        Bench network(RepositoryProbe probe) {
            options.network(true).probe(probe);
            return this;
        }

        Report run() {
            return Preflight.run(project, setup, options);
        }
    }

    static final String MISSING_PARENT =
            "<parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>";

    static RepositoryProbe answer(int code) {
        return (url, proxy, timeout) -> RepositoryProbe.Response.status(url.endsWith(".pom") ? 404 : code, 3);
    }

    static Arguments row(String name, Topic topic, Status status, String fix, Consumer<Bench> arrange) {
        return Arguments.of(name, topic, status, fix, arrange);
    }

    static Stream<Arguments> matrix() {
        String httpMirror = "<settings><mirrors><mirror><id>corp</id><mirrorOf>*</mirrorOf><url>http://nexus.corp/m2</url></mirror></mirrors></settings>";
        String enforcer = """
                <build><plugins><plugin><artifactId>maven-enforcer-plugin</artifactId><configuration><rules>
                  <%1$s>
                    <version>%2$s</version>
                  </%1$s>
                </rules></configuration></plugin></plugins></build>""";
        String toolchains = "<properties><maven.compiler.release>25</maven.compiler.release></properties>"
                + "<build><plugins><plugin><artifactId>maven-toolchains-plugin</artifactId></plugin></plugins></build>";
        return Stream.of(
                // settings.xml
                row("ohne settings.xml", Topic.SETTINGS, Status.OK, null, b -> { }),
                row("gültige settings.xml", Topic.SETTINGS, Status.OK, null, b -> b.settings("<settings/>")),
                row("-s zeigt ins Leere", Topic.SETTINGS, Status.ERROR, null, b -> b.setup.setUserSettings(b.tmp.resolve("nope.xml").toString())),
                row("kaputte Nutzer-settings.xml", Topic.SETTINGS, Status.ERROR, FixAction.OPEN_FILE, b -> b.settings("<settings>\n<mirrors>\n</settings>")),
                row("kaputte globale settings.xml", Topic.SETTINGS, Status.ERROR, FixAction.OPEN_FILE, b -> b.global("<settings")),
                row("-o in maven.config", Topic.SETTINGS, Status.WARNING, FixAction.OPEN_FILE, b -> b.setup.setMavenConfig("-B -o")),
                row("offline in settings.xml", Topic.SETTINGS, Status.WARNING, FixAction.OPEN_FILE, b -> b.settings("<settings><offline>true</offline></settings>")),
                row("http-Mirror ab 3.8.1", Topic.SETTINGS, Status.ERROR, FixAction.REPLACE_IN_FILE, b -> b.settings(httpMirror)),
                row("http-Mirror vor 3.8.1", Topic.SETTINGS, Status.WARNING, FixAction.REPLACE_IN_FILE, b -> {
                    b.settings(httpMirror);
                    b.setup.setMavenVersion("3.6.3");
                }),
                row("dav-Mirror ohne https-Ersatz", Topic.SETTINGS, Status.ERROR, FixAction.OPEN_FILE,
                        b -> b.settings(httpMirror.replace("http://", "dav:http://"))),
                row("http-Blocker selbst ist kein Befund", Topic.SETTINGS, Status.OK, null, b -> b.global(
                        "<settings><mirrors><mirror><id>maven-default-http-blocker</id><mirrorOf>external:http:*</mirrorOf>"
                                + "<url>http://0.0.0.0/</url><blocked>true</blocked></mirror></mirrors></settings>")),
                // JDK
                row("JDK passt", Topic.JDK, Status.OK, null, b -> { }),
                row("kein JDK", Topic.JDK, Status.ERROR, FixAction.MANUAL, b -> b.setup.setJavaHome(null)),
                row("JRE ohne javac", Topic.JDK, Status.ERROR, FixAction.SWITCH_JDK, b -> b.jdk("21.0.4", false)),
                row("JDK zu alt", Topic.JDK, Status.ERROR, FixAction.SWITCH_JDK, b -> b.jdk("11.0.2", true)),
                row("JDK zu alt, aber Toolchains", Topic.JDK, Status.INFO, FixAction.OPEN_FILE, b -> b.pom(toolchains)),
                row("Toolchains ohne toolchains.xml", Topic.JDK, Status.ERROR, FixAction.MANUAL, b -> b.pom(toolchains)),
                row("Toolchains mit toolchains.xml", Topic.JDK, Status.INFO, FixAction.OPEN_FILE, b -> {
                    b.pom(toolchains);
                    Bench.write(b.home.resolve(".m2").resolve("toolchains.xml"), "<toolchains/>");
                }),
                row("Enforcer verlangt anderes JDK", Topic.JDK, Status.ERROR, FixAction.OPEN_FILE,
                        b -> b.pom(enforcer.formatted("requireJavaVersion", "[17,21)"))),
                row("Enforcer mit Java 8 als 1.8", Topic.JDK, Status.OK, null, b -> {
                    b.jdk("1.8.0_402", true);
                    b.pom(enforcer.formatted("requireJavaVersion", "8"));
                }),
                // Maven
                row("Maven passt", Topic.MAVEN, Status.OK, null, b -> { }),
                row("kein Maven", Topic.MAVEN, Status.ERROR, FixAction.MANUAL, b -> b.setup.setMavenVersion(null).setMavenSource(null)),
                row("Version unbekannt", Topic.MAVEN, Status.WARNING, null, b -> b.setup.setMavenVersion(null)),
                row("Wrapper ohne Skript", Topic.MAVEN, Status.WARNING, FixAction.MANUAL,
                        b -> b.file(".mvn/wrapper/maven-wrapper.properties", "distributionUrl=x")),
                row("Maven 4 mit Java 11", Topic.MAVEN, Status.ERROR, FixAction.SWITCH_JDK, b -> {
                    b.setup.setMavenVersion("4.0.0");
                    b.jdk("11.0.2", true);
                    b.pom("");
                }),
                row("Maven vor 3.6.3", Topic.MAVEN, Status.WARNING, null, b -> b.setup.setMavenVersion("3.5.4")),
                row("Enforcer verlangt neueres Maven", Topic.MAVEN, Status.ERROR, FixAction.OPEN_FILE,
                        b -> b.pom(enforcer.formatted("requireMavenVersion", "[3.9.12,)"))),
                // Projekt
                row("Projekt in Ordnung", Topic.PROJECT, Status.OK, null, b -> { }),
                row("keine pom.xml", Topic.PROJECT, Status.ERROR, null, b -> {
                    try {
                        Files.delete(b.project.resolve("pom.xml"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }),
                row("kaputte pom.xml", Topic.PROJECT, Status.ERROR, FixAction.OPEN_FILE, b -> b.file("pom.xml", "<project>\n<version>\n</project>")),
                row("fehlendes Modul", Topic.PROJECT, Status.ERROR, FixAction.OPEN_FILE, b -> b.pom("<packaging>pom</packaging><modules><module>core</module></modules>")),
                row("Dependency ohne Version", Topic.PROJECT, Status.ERROR, FixAction.OPEN_FILE,
                        b -> b.pom("<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId></dependency></dependencies>")),
                row("relativePath zeigt auf fremde POM", Topic.PROJECT, Status.WARNING, FixAction.OPEN_FILE, b -> {
                    b.pom("<parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>"
                            + "<relativePath>../other/pom.xml</relativePath></parent>");
                    Bench.write(b.tmp.resolve("other/pom.xml"), "<project><groupId>x</groupId><artifactId>y</artifactId><version>1</version></project>");
                }),
                row("fehlender Parent, online", Topic.PROJECT, Status.INFO, null, b -> b.pom(MISSING_PARENT)),
                row("fehlender Parent, offline", Topic.PROJECT, Status.ERROR, FixAction.MANUAL, b -> {
                    b.pom(MISSING_PARENT);
                    b.setup.setMavenConfig("--offline");
                }),
                // lokales Repository
                row("Repository vorhanden", Topic.LOCAL_REPOSITORY, Status.OK, null, b -> { }),
                row("Repository noch nicht angelegt", Topic.LOCAL_REPOSITORY, Status.INFO, null,
                        b -> b.setup.setLocalRepository(b.tmp.resolve("new-repo").toString())),
                row("Repository unbekannt", Topic.LOCAL_REPOSITORY, Status.WARNING, null, b -> b.options.userHome(null)),
                row("gespeicherter Fehlversuch", Topic.LOCAL_REPOSITORY, Status.WARNING, FixAction.DELETE_FILES, b -> {
                    b.pom("<dependencies><dependency><groupId>org.x</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies>");
                    b.repoFile("org/x/lib/1.0/lib-1.0.jar.lastUpdated", "");
                }),
                // Remote-Repositories
                row("ohne Netz nur aufgelistet", Topic.REPOSITORY, Status.INFO, null, b -> { }),
                row("offline", Topic.REPOSITORY, Status.INFO, null, b -> b.setup.setMavenConfig("-o")),
                row("http-Repository in der POM", Topic.REPOSITORY, Status.ERROR, FixAction.REPLACE_URL,
                        b -> b.pom("<repositories><repository><id>old</id><url>http://repo.corp/m2</url></repository></repositories>")),
                row("http-Repository vor 3.8.1", Topic.REPOSITORY, Status.WARNING, null, b -> {
                    b.setup.setMavenVersion("3.6.3");
                    b.pom("<repositories><repository><id>old</id><url>http://repo.corp/m2</url></repository></repositories>");
                }),
                row("http-Mirror sperrt Central", Topic.REPOSITORY, Status.ERROR, FixAction.OPEN_FILE, b -> b.settings(httpMirror)),
                row("Repository antwortet", Topic.REPOSITORY, Status.OK, null, b -> b.network(answer(200))),
                row("DNS-Fehler", Topic.REPOSITORY, Status.ERROR, null, b -> b.network(
                        (url, proxy, timeout) -> RepositoryProbe.Response.failed(RepositoryProbe.Failure.DNS, "x"))),
                row("401 ohne server, mit settings.xml", Topic.REPOSITORY, Status.ERROR, FixAction.OPEN_FILE, b -> {
                    b.settings("<settings/>");
                    b.network(answer(401));
                }),
                row("401 ohne settings.xml", Topic.REPOSITORY, Status.ERROR, FixAction.MANUAL, b -> b.network(answer(401))),
                row("401 mit server", Topic.REPOSITORY, Status.INFO, null, b -> {
                    b.settings("<settings><servers><server><id>central</id></server></servers></settings>");
                    b.network(answer(401));
                }),
                row("407 Proxy-Anmeldung", Topic.REPOSITORY, Status.WARNING, null, b -> b.network(answer(407))),
                row("Serverfehler", Topic.REPOSITORY, Status.WARNING, null, b -> b.network(answer(503))),
                row("Platzhalter in der Mirror-URL", Topic.REPOSITORY, Status.INFO, null, b -> {
                    b.settings(httpMirror.replace("http://nexus.corp/m2", "${env.NEXUS}"));
                    b.network(answer(200));
                }),
                row("Parent in keinem Repository", Topic.REPOSITORY, Status.ERROR, FixAction.MANUAL, b -> {
                    b.pom(MISSING_PARENT);
                    b.network(answer(200));
                }),
                row("eigener Nexus ohne Mirror-Eintrag", Topic.REPOSITORY, Status.INFO, FixAction.OPEN_FILE, b -> {
                    b.settings("<settings>\n<mirrors/>\n</settings>");
                    b.options.nexus("https://nexus.corp/public");
                }));
    }

    @ParameterizedTest(name = "{0}: {1} {2}")
    @MethodSource("matrix")
    void statusMatrix(String name, Topic topic, Status status, String fix, Consumer<Bench> arrange, @TempDir Path tmp) {
        Bench bench = new Bench(tmp);
        arrange.accept(bench);
        Report report = bench.run();

        List<Item> items = report.getItems().stream().filter(i -> i.getTopic() == topic).toList();
        Item hit = items.stream().filter(i -> i.getStatus() == status).findFirst()
                .orElseThrow(() -> new AssertionError("keine Zeile " + status + " in " + items));
        assertEquals(fix, hit.getFixes().isEmpty() ? null : hit.getFixes().get(0).getType(), hit.toString());
        for (Topic t : Topic.values()) {
            assertTrue(report.getItems().stream().anyMatch(i -> i.getTopic() == t), "jede Prüfung liefert eine Zeile: " + t);
        }
        Status worst = report.getItems().stream().map(Item::getStatus).reduce(Status.OK, Status::worst);
        assertEquals(worst, report.getStatus());
    }

    @Test
    void schreibgeschuetztesRepositoryIstRot(@TempDir Path tmp) throws IOException {
        Bench bench = new Bench(tmp);
        Assumptions.assumeTrue(Files.getFileStore(bench.repo).supportsFileAttributeView("posix"), "nur mit POSIX-Rechten");
        Files.setPosixFilePermissions(bench.repo, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(bench.repo), "läuft als root");
            Item item = bench.run().getItems().stream().filter(i -> i.getTopic() == Topic.LOCAL_REPOSITORY).findFirst().orElseThrow();
            assertEquals(Status.ERROR, item.getStatus());
            assertEquals(FixAction.MANUAL, item.getFixes().get(0).getType());
        } finally {
            Files.setPosixFilePermissions(bench.repo, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ---------------------------------------------------------------- Ampel

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "'', GREEN",
        "OK, GREEN",
        "INFO, GREEN",
        "OK INFO INFO, GREEN",
        "WARNING, YELLOW",
        "OK WARNING INFO, YELLOW",
        "ERROR, RED",
        "INFO ERROR WARNING OK, RED",
        "WARNING WARNING ERROR, RED",
    })
    void ampelAusDemSchlechtestenStatus(String statuses, Light light) {
        List<Item> items = new ArrayList<>();
        for (String s : statuses.split(" ")) {
            if (!s.isEmpty()) {
                items.add(new Item(Topic.PROJECT, Status.valueOf(s), s));
            }
        }
        Report report = new Report(Path.of("p"), null, items, false);
        assertEquals(light, report.getLight());
        int errors = report.count(Status.ERROR);
        int warnings = report.count(Status.WARNING);
        assertEquals(items.stream().filter(i -> i.getStatus() == Status.ERROR).count(), errors);
        assertFalse(report.headline().isEmpty());
        assertEquals(errors > 0 || warnings > 0, report.headline().matches(".*\\d.*"), "Zahl nur bei Problemen: " + report.headline());
    }

    @Test
    void schlechtesterStatusIstUnabhaengigVonDerReihenfolge() {
        for (Status a : Status.values()) {
            for (Status b : Status.values()) {
                assertEquals(a.worst(b), b.worst(a));
                assertEquals(a.ordinal() >= b.ordinal() ? a : b, a.worst(b));
            }
            assertEquals(a, a.worst(null));
        }
    }
}
