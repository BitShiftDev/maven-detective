package io.github.bitshiftdev.mavendetective.core.preflight;

import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.BuildSetup;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Item;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Light;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Report;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Status;
import io.github.bitshiftdev.mavendetective.core.preflight.Preflight.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preflight gegen ein kleines Projekt mit eigenem Benutzerverzeichnis, JDK und lokalem Repository. */
class PreflightTest {

    @TempDir
    Path tmp;
    Path project;
    Path home;
    Path repo;
    BuildSetup setup;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    Path jdk(String version, boolean javac) throws IOException {
        Path dir = tmp.resolve("jdk-" + version + (javac ? "" : "-jre"));
        write(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        write(dir.resolve("bin").resolve("java"), "");
        if (javac) {
            write(dir.resolve("bin").resolve("javac"), "");
        }
        return dir;
    }

    void pom(String body) throws IOException {
        write(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                """ + body + "\n</project>\n");
    }

    void repoPom(String g, String a, String v, String body) throws IOException {
        Path dir = repo;
        for (String s : g.split("\\.")) {
            dir = dir.resolve(s);
        }
        write(dir.resolve(a).resolve(v).resolve(a + "-" + v + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                """.formatted(g, a, v) + body + "\n</project>\n");
    }

    @BeforeEach
    void setUp() throws IOException {
        project = tmp.resolve("project");
        home = tmp.resolve("home");
        repo = home.resolve(".m2").resolve("repository");
        Files.createDirectories(repo);
        Path jdk = jdk("21.0.4", true);
        setup = new BuildSetup("test").setMavenVersion("3.9.11").setMavenSource("/opt/maven")
                .setJavaHome(jdk.toString()).setJavaVersion("21.0.4");
    }

    Report run() {
        return run(new Preflight.Options());
    }

    Report run(Preflight.Options options) {
        return Preflight.run(project, setup, options.userHome(home).env(Map.of()));
    }

    static List<Item> items(Report r, Topic topic) {
        return r.getItems().stream().filter(i -> i.getTopic() == topic).collect(Collectors.toList());
    }

    static Item first(Report r, Topic topic, Status status) {
        for (Item i : items(r, topic)) {
            if (i.getStatus() == status) {
                return i;
            }
        }
        throw new AssertionError("keine Zeile " + status + " zu " + topic + " in " + r.getItems());
    }

    static FixAction fix(Item item, String type) {
        for (FixAction f : item.getFixes()) {
            if (type.equals(f.getType())) {
                return f;
            }
        }
        throw new AssertionError("kein Fix " + type + " in " + item.getFixes());
    }

    @Test
    void sauberesProjektIstGruen() throws IOException {
        pom("<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Report r = run();
        assertEquals(Light.GREEN, r.getLight(), r.getItems().toString());
        assertTrue(first(r, Topic.JDK, Status.OK).getText().contains("17"));
        assertEquals(Status.INFO, items(r, Topic.REPOSITORY).get(0).getStatus());
        assertFalse(r.isNetworkChecked());
        assertTrue(String.join("\n", r.lines()).contains("--network"));
        assertTrue(r.markdown().startsWith("### Preflight"));
    }

    @Test
    void zuAltesJdkIstRotMitWechselZumVerlangtenRelease() throws IOException {
        pom("<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Report r = run();
        assertEquals(Light.RED, r.getLight());
        Item jdk = first(r, Topic.JDK, Status.ERROR);
        assertEquals("25", fix(jdk, FixAction.SWITCH_JDK).param("required"));
        assertEquals(project.resolve("pom.xml").toString(), fix(jdk, FixAction.OPEN_FILE).param("file"));
    }

    @Test
    void releaseUeberJavaVersionAusDemParentZeigtAufDieWoertlicheAngabe() throws IOException {
        repoPom("org.example", "starter-parent", "3.0", """
                <packaging>pom</packaging>
                <properties>
                  <java.version>17</java.version>
                  <maven.compiler.release>${java.version}</maven.compiler.release>
                </properties>""");
        pom("""
                <parent><groupId>org.example</groupId><artifactId>starter-parent</artifactId><version>3.0</version><relativePath/></parent>
                <properties>
                  <java.version>24</java.version>
                </properties>""");
        Report r = run();
        Item jdk = first(r, Topic.JDK, Status.ERROR);
        assertEquals("24", fix(jdk, FixAction.SWITCH_JDK).param("required"));
        FixAction open = fix(jdk, FixAction.OPEN_FILE);
        assertEquals(project.resolve("pom.xml").toString(), open.param("file"));
        assertEquals("8", open.param("line"));
    }

    @Test
    void jreOhneJavacIstRot() throws IOException {
        pom("");
        Path jre = jdk("17.0.2", false);
        setup.setJavaHome(jre.toString()).setJavaVersion("17.0.2");
        Item item = first(run(), Topic.JDK, Status.ERROR);
        assertTrue(item.getText().contains("JRE"), item.getText());
    }

    @Test
    void toolchainsOhneToolchainsXml() throws IOException {
        pom("""
                <properties><maven.compiler.release>25</maven.compiler.release></properties>
                <build><plugins><plugin><artifactId>maven-toolchains-plugin</artifactId></plugin></plugins></build>""");
        Report r = run();
        // das Runner-JDK ist dann nur ein Hinweis, die fehlende toolchains.xml ist der Fehler
        assertTrue(items(r, Topic.JDK).stream().anyMatch(i -> i.getStatus() == Status.INFO));
        assertTrue(first(r, Topic.JDK, Status.ERROR).getText().contains("toolchains.xml"));
    }

    @Test
    void enforcerVerlangtNeueresMaven() throws IOException {
        pom("""
                <build><plugins><plugin>
                  <artifactId>maven-enforcer-plugin</artifactId>
                  <configuration><rules>
                    <requireMavenVersion>
                      <version>[3.9.12,)</version>
                    </requireMavenVersion>
                    <requireJavaVersion>
                      <version>[17,22)</version>
                    </requireJavaVersion>
                  </rules></configuration>
                </plugin></plugins></build>""");
        Report r = run();
        assertTrue(first(r, Topic.MAVEN, Status.ERROR).getText().contains("[3.9.12,)"));
        assertTrue(items(r, Topic.JDK).stream().allMatch(i -> i.getStatus() == Status.OK));
        setup.setJavaVersion("22.0.1");
        assertTrue(first(run(), Topic.JDK, Status.ERROR).getText().contains("[17,22)"));
    }

    @Test
    void maven4BrauchtJava17() throws IOException {
        pom("");
        setup.setMavenVersion("4.0.0").setJavaHome(jdk("11.0.20", true).toString()).setJavaVersion("11.0.20");
        assertTrue(first(run(), Topic.MAVEN, Status.ERROR).getText().contains("Java 17"));
    }

    @Test
    void kaputteSettingsXmlMitZeile() throws IOException {
        pom("");
        write(home.resolve(".m2").resolve("settings.xml"), "<settings>\n  <mirrors>\n    <mirror>\n</settings>\n");
        Item item = first(run(), Topic.SETTINGS, Status.ERROR);
        FixAction open = fix(item, FixAction.OPEN_FILE);
        assertTrue(Integer.parseInt(open.param("line")) > 1, open.toString());
    }

    @Test
    void httpMirrorWirdBlockiert() throws IOException {
        pom("");
        write(home.resolve(".m2").resolve("settings.xml"), """
                <settings>
                  <mirrors>
                    <mirror><id>corp</id><mirrorOf>*</mirrorOf><url>http://nexus.corp.example/repository/maven</url></mirror>
                  </mirrors>
                </settings>
                """);
        Report r = run();
        Item item = first(r, Topic.SETTINGS, Status.ERROR);
        FixAction replace = fix(item, FixAction.REPLACE_IN_FILE);
        assertEquals("https://nexus.corp.example/repository/maven", replace.param("replace"));
        // Central läuft über den Mirror und ist damit ebenfalls gesperrt
        assertTrue(first(r, Topic.REPOSITORY, Status.ERROR).getText().contains("corp"));
        setup.setMavenVersion("3.6.3");
        assertEquals(Status.WARNING, first(run(), Topic.SETTINGS, Status.WARNING).getStatus());
    }

    @Test
    void httpRepositoryInDerPom() throws IOException {
        pom("""
                <repositories>
                  <repository>
                    <id>old</id>
                    <url>http://repo.old.example/maven2</url>
                  </repository>
                </repositories>""");
        Item item = first(run(), Topic.REPOSITORY, Status.ERROR);
        FixAction replace = fix(item, FixAction.REPLACE_URL);
        assertEquals(project.resolve("pom.xml").toString(), replace.param("file"));
        assertEquals("https://repo.old.example/maven2", replace.param("replace"));
    }

    @Test
    void fehlendesModul() throws IOException {
        pom("<packaging>pom</packaging>\n<modules>\n  <module>core</module>\n  <module>web</module>\n</modules>");
        write(project.resolve("core").resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        Item item = first(run(), Topic.PROJECT, Status.ERROR);
        assertTrue(item.getText().contains("web"), item.getText());
        assertEquals("9", fix(item, FixAction.OPEN_FILE).param("line"));
    }

    @Test
    void dependencyOhneVersionUndOhneManagement() throws IOException {
        pom("""
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.example</groupId><artifactId>managed</artifactId><version>1</version></dependency>
                </dependencies></dependencyManagement>
                <dependencies>
                  <dependency><groupId>org.example</groupId><artifactId>managed</artifactId></dependency>
                  <dependency><groupId>org.example</groupId><artifactId>typo</artifactId><versoin>1</versoin></dependency>
                </dependencies>""");
        Report r = run();
        List<Item> errors = items(r, Topic.PROJECT).stream().filter(i -> i.getStatus() == Status.ERROR).toList();
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).getText().contains("org.example:typo"));
        assertEquals("11", fix(errors.get(0), FixAction.OPEN_FILE).param("line"));
        // fehlt der Parent, könnte die Version von dort kommen → kein Urteil
        pom("""
                <parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>
                <dependencies><dependency><groupId>org.example</groupId><artifactId>typo</artifactId></dependency></dependencies>""");
        assertTrue(items(run(), Topic.PROJECT).stream().noneMatch(i -> i.getStatus() == Status.ERROR));
    }

    @Test
    void fehlenderParentOnlineHinweisOfflineFehler() throws IOException {
        pom("<parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>");
        assertEquals(Status.INFO, items(run(), Topic.PROJECT).get(0).getStatus());
        setup.setMavenConfig("-o -B");
        Report r = run();
        Item item = first(r, Topic.PROJECT, Status.ERROR);
        assertEquals("org.example:gone:1.0", item.getEvidence().get(0).getText());
        assertEquals(Status.WARNING, first(r, Topic.SETTINGS, Status.WARNING).getStatus());
    }

    @Test
    void gespeicherterFehlversuchImLokalenRepository() throws IOException {
        pom("<dependencies><dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>2.0</version></dependency></dependencies>");
        Path lastUpdated = repo.resolve("org/example/lib/2.0/lib-2.0.jar.lastUpdated");
        write(lastUpdated, "");
        Item item = first(run(), Topic.LOCAL_REPOSITORY, Status.WARNING);
        FixAction delete = fix(item, FixAction.DELETE_FILES);
        assertEquals(lastUpdated.toString(), delete.param("files"));
        // liegt das JAR inzwischen da, ist die Marke harmlos
        write(repo.resolve("org/example/lib/2.0/lib-2.0.jar"), "");
        assertEquals(Status.OK, items(run(), Topic.LOCAL_REPOSITORY).get(0).getStatus());
    }

    @Test
    void netzpruefungMitFalschemNetz() throws IOException {
        pom("""
                <parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>
                <repositories>
                  <repository><id>private</id><url>https://repo.private.example/maven</url></repository>
                  <repository><id>dns</id><url>https://nowhere.example/maven</url></repository>
                </repositories>""");
        List<String> asked = new ArrayList<>();
        RepositoryProbe probe = (url, proxy, timeout) -> {
            asked.add(url);
            if (url.startsWith("https://repo.private.example")) {
                return RepositoryProbe.Response.status(401, 5);
            }
            if (url.startsWith("https://nowhere.example")) {
                return RepositoryProbe.Response.failed(RepositoryProbe.Failure.DNS, "nowhere.example");
            }
            return RepositoryProbe.Response.status(url.endsWith(".pom") ? 404 : 200, 7);
        };
        Report r = run(new Preflight.Options().network(true).probe(probe));
        assertTrue(r.isNetworkChecked());
        List<Item> remote = items(r, Topic.REPOSITORY);
        assertTrue(remote.stream().anyMatch(i -> i.getStatus() == Status.OK && i.getText().contains("central")));
        assertTrue(remote.stream().anyMatch(i -> i.getStatus() == Status.ERROR && i.getText().contains("'private'")));
        assertTrue(remote.stream().anyMatch(i -> i.getStatus() == Status.ERROR && i.getText().contains("nowhere.example")));
        // fehlender Parent: Central sagt 404, aber „private“ ließ sich nicht fragen → kein Urteil
        assertTrue(asked.contains("https://repo.maven.apache.org/maven2/org/example/gone/1.0/gone-1.0.pom"));
        assertFalse(remote.stream().anyMatch(i -> i.getText().contains("org.example:gone:1.0")), remote.toString());

        write(home.resolve(".m2").resolve("settings.xml"), "<settings><servers><server><id>private</id></server></servers></settings>");
        Item login = run(new Preflight.Options().network(true).probe(probe)).getItems().stream()
                .filter(i -> i.getText().contains("private")).findFirst().orElse(null);
        assertNotNull(login);
        assertEquals(Status.INFO, login.getStatus());
    }

    @Test
    void fehlenderParentLiegtInKeinemRepository() throws IOException {
        pom("<parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>");
        RepositoryProbe probe = (url, proxy, timeout) -> RepositoryProbe.Response.status(url.endsWith(".pom") ? 404 : 200, 3);
        Report r = run(new Preflight.Options().network(true).probe(probe));
        assertEquals(Light.RED, r.getLight());
        assertTrue(first(r, Topic.REPOSITORY, Status.ERROR).getText().contains("org.example:gone:1.0"));
        RepositoryProbe found = (url, proxy, timeout) -> RepositoryProbe.Response.status(200, 3);
        assertEquals(Light.GREEN, run(new Preflight.Options().network(true).probe(found)).getLight());
    }

    @Test
    void auskommentierteUndProfilAngabenZaehlenNicht() throws IOException {
        pom("""
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                <!-- <build><plugins><plugin><configuration><release>25</release></configuration></plugin></plugins></build> -->
                <!--
                <requireJavaVersion><version>[25,)</version></requireJavaVersion>
                -->
                <profiles>
                  <profile>
                    <id>jdk25</id>
                    <build><plugins><plugin><configuration><release>25</release></configuration></plugin></plugins></build>
                  </profile>
                </profiles>""");
        Report r = run();
        assertEquals(Light.GREEN, r.getLight(), r.getItems().toString());
        assertTrue(first(r, Topic.JDK, Status.OK).getText().contains("17"));
    }

    @Test
    void pomAusserhalbDerModuleZaehltNicht() throws IOException {
        pom("<packaging>pom</packaging>\n<modules><module>core</module></modules>");
        write(project.resolve("core").resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>""");
        // Beispielprojekt ohne Eintrag in <modules>: Maven baut es nicht
        write(project.resolve("examples").resolve("legacy").resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>legacy</artifactId><version>1</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                </project>""");
        Report r = run();
        assertEquals(Light.GREEN, r.getLight(), r.getItems().toString());
        assertTrue(first(r, Topic.JDK, Status.OK).getText().contains("17"));
    }

    @Test
    void platzhalterInSettingsWerdenAufgeloest() throws IOException {
        pom("");
        write(home.resolve(".m2").resolve("settings.xml"), """
                <settings>
                  <localRepository>${env.M2_CACHE}/repo</localRepository>
                  <mirrors><mirror><id>nexus</id><mirrorOf>*</mirrorOf><url>${env.NEXUS_URL}/repository/maven-public/</url></mirror></mirrors>
                </settings>
                """);
        List<String> asked = new ArrayList<>();
        RepositoryProbe probe = (url, proxy, timeout) -> {
            asked.add(url);
            return RepositoryProbe.Response.status(200, 1);
        };
        Path cache = tmp.resolve("cache");
        Preflight.Options options = new Preflight.Options().network(true).probe(probe).userHome(home)
                .env(Map.of("M2_CACHE", cache.toString(), "NEXUS_URL", "https://nexus.corp.example"));
        Report r = Preflight.run(project, setup, options);
        assertEquals(List.of("https://nexus.corp.example/repository/maven-public/"), asked);
        assertEquals(cache.resolve("repo"), r.getLocalRepository());
        assertEquals(Light.GREEN, r.getLight(), r.getItems().toString());

        // unbekannte Variable: nicht anfragen, kein ROT
        asked.clear();
        Report unknown = Preflight.run(project, setup, new Preflight.Options().network(true).probe(probe).userHome(home).env(Map.of()));
        assertTrue(asked.isEmpty(), asked.toString());
        assertTrue(items(unknown, Topic.REPOSITORY).stream().anyMatch(i -> i.getStatus() == Status.INFO
                && i.getText().contains("${env.NEXUS_URL}")), unknown.getItems().toString());
        assertTrue(unknown.getLight() != Light.RED, unknown.getItems().toString());
    }

    @Test
    void proxyAusSettingsMitAusnahmen() throws IOException {
        pom("");
        write(home.resolve(".m2").resolve("settings.xml"), """
                <settings><proxies><proxy>
                  <id>p</id><protocol>http</protocol><host>proxy.corp</host><port>3128</port>
                  <nonProxyHosts>*.corp.example|localhost</nonProxyHosts>
                </proxy></proxies></settings>
                """);
        List<String> proxies = new ArrayList<>();
        RepositoryProbe probe = (url, proxy, timeout) -> {
            proxies.add(proxy == null ? "direct" : proxy.host + ":" + proxy.port);
            return RepositoryProbe.Response.status(200, 1);
        };
        run(new Preflight.Options().network(true).probe(probe));
        assertEquals(List.of("proxy.corp:3128"), proxies);
        PreflightContext ctx = new PreflightContext(project, setup, new Preflight.Options().userHome(home));
        assertEquals(null, RemoteRepositories.proxyFor(ctx, "https://nexus.corp.example/x"));
    }

    @Test
    void eigenerNexusErsetztAlleRepositories() throws IOException {
        pom("""
                <parent><groupId>org.example</groupId><artifactId>gone</artifactId><version>1.0</version><relativePath/></parent>
                <repositories>
                  <repository><id>private</id><url>https://repo.private.example/maven</url></repository>
                </repositories>""");
        List<String> asked = new ArrayList<>();
        RepositoryProbe probe = (url, proxy, timeout) -> {
            asked.add(url);
            return RepositoryProbe.Response.status(url.endsWith(".pom") ? 404 : 200, 4);
        };
        Report r = run(new Preflight.Options().network(true).nexus(" https://nexus.corp.example/repository/public/ ").probe(probe));
        // nur der Nexus wird gefragt, Basis und fehlender Parent je einmal
        assertEquals(List.of("https://nexus.corp.example/repository/public/",
                "https://nexus.corp.example/repository/public/org/example/gone/1.0/gone-1.0.pom"), asked);
        List<Item> remote = items(r, Topic.REPOSITORY);
        Item notice = remote.get(0);
        assertEquals(Status.INFO, notice.getStatus());
        assertTrue(notice.getText().contains("keine settings.xml"), notice.getText());
        assertTrue(remote.stream().anyMatch(i -> i.getStatus() == Status.OK && i.getText().startsWith("Eigener Nexus")));
        assertTrue(first(r, Topic.REPOSITORY, Status.ERROR).getText().contains("org.example:gone:1.0"));

        // ohne Mirror-Eintrag gibt es keine Server-ID → Anmeldung nur als Hinweis
        RepositoryProbe login = (url, proxy, timeout) -> RepositoryProbe.Response.status(401, 2);
        Report locked = run(new Preflight.Options().network(true).nexus("https://nexus.corp.example/repository/public").probe(login));
        assertTrue(items(locked, Topic.REPOSITORY).stream().noneMatch(i -> i.getStatus() == Status.ERROR), locked.getItems().toString());

        // steht er als Mirror in settings.xml, gilt dessen ID für <server>
        write(home.resolve(".m2").resolve("settings.xml"), """
                <settings>
                  <mirrors><mirror><id>corp</id><mirrorOf>*</mirrorOf><url>https://nexus.corp.example/repository/public/</url></mirror></mirrors>
                </settings>
                """);
        Report mirrored = run(new Preflight.Options().network(true).nexus("https://nexus.corp.example/repository/public").probe(login));
        assertTrue(items(mirrored, Topic.REPOSITORY).get(0).getText().contains("corp"));
        assertTrue(first(mirrored, Topic.REPOSITORY, Status.ERROR).getText().contains("'corp'"));
    }

    @Test
    void eigenerNexusOhneNetzNurAufgelistet() throws IOException {
        pom("");
        Report r = run(new Preflight.Options().nexus("https://nexus.corp.example/maven"));
        Item list = items(r, Topic.REPOSITORY).get(1);
        assertTrue(list.getEvidence().get(0).getText().endsWith("→ https://nexus.corp.example/maven"), list.getEvidence().toString());
        assertEquals(Light.GREEN, r.getLight());
        assertTrue(Preflight.isRepositoryUrl("https://nexus.corp.example:8443/repository/maven-public"));
        assertFalse(Preflight.isRepositoryUrl("nexus.corp.example"));
        assertFalse(Preflight.isRepositoryUrl("ftp://nexus"));
    }

    @Test
    void versionsbereicheWieDerEnforcer() {
        assertTrue(VersionRange.contains("17", "21.0.1"));
        assertFalse(VersionRange.contains("17", "11.0.2"));
        assertTrue(VersionRange.contains("[1.8,11)", "1.8.0-392"));
        assertFalse(VersionRange.contains("[1.8,11)", "11.0.2"));
        assertTrue(VersionRange.contains("(,21]", "21"));
        assertFalse(VersionRange.contains("(,21)", "21"));
        assertTrue(VersionRange.contains("[1.8,9),[11,)", "17"));
        assertFalse(VersionRange.contains("[1.8,9),[11,)", "10"));
        assertTrue(VersionRange.contains("[3.9.11]", "3.9.11"));
    }
    /** Review #56: „8“ im Enforcer heißt 1.8. */
    @Test
    void enforcerReadsOldJavaBoundsLikeTheEnforcer() {
        String java8 = PreflightChecks.normalizeJava("1.8.0_392");
        assertTrue(VersionRange.contains(PreflightChecks.javaRange("8"), java8));
        assertTrue(VersionRange.contains(PreflightChecks.javaRange("[8,)"), java8));
        assertTrue(VersionRange.contains(PreflightChecks.javaRange("[1.8,9)"), java8));
        assertFalse(VersionRange.contains(PreflightChecks.javaRange("[11,)"), java8));
        assertTrue(VersionRange.contains(PreflightChecks.javaRange("[8,)"), "17.0.9"));
        assertEquals("[17,21)", PreflightChecks.javaRange("[17,21)"));
    }
}
