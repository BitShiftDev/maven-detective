package io.github.bitshiftdev.mavendetective.core.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildSetupTest {

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void detectsWrapperJdkAndMavenConfig(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("project");
        Path home = tmp.resolve("home");
        Path jdk = tmp.resolve("jdk-17");
        write(project.resolve("mvnw"), "#!/bin/sh");
        write(project.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n");
        // absolut auf jedem System – "/cache/m2" hat unter Windows keinen Laufwerksbuchstaben
        String cache = tmp.resolve("cache").resolve("m2").toAbsolutePath().toString();
        write(project.resolve(".mvn/maven.config"), "-s .mvn/settings.xml\n-Pci,fast -Dmaven.repo.local=" + cache + "\n");
        write(project.resolve(".mvn/jvm.config"), "-Xmx2g");
        write(jdk.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"17.0.12\"\n");
        write(home.resolve(".m2/settings.xml"), "<settings/>");

        Map<String, String> env = new HashMap<String, String>();
        env.put("JAVA_HOME", jdk.toString());
        env.put("MAVEN_OPTS", "-Xmx1g");
        BuildSetup cli = BuildSetup.detectCommandLine(project, home, env);

        assertEquals("3.9.6", cli.getMavenVersion());
        assertEquals("Wrapper (./mvnw)", cli.getMavenSource());
        assertEquals("17.0.12", cli.getJavaVersion());
        assertEquals(project.resolve(".mvn/settings.xml").toString(), cli.getUserSettings(), "-s aus maven.config schlägt ~/.m2");
        assertEquals(cache, cli.getLocalRepository());
        assertEquals(Arrays.asList("ci", "fast"), cli.getProfiles());
        assertEquals("-Xmx2g", cli.getJvmConfig());
        assertEquals("-Xmx1g", cli.getMavenOpts());
    }

    @Test
    void findsUnpackedWrapperDistributionOfBothLayouts(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Map<String, String> env = new HashMap<String, String>();
        // Wrapper bis 3.2: dists/apache-maven-X-bin/<hash>/apache-maven-X
        Path old = home.resolve(".m2/wrapper/dists/apache-maven-3.9.6-bin/abc/apache-maven-3.9.6");
        Files.createDirectories(old.resolve("conf"));
        assertEquals(old, BuildSetup.wrapperDistribution("3.9.6", null, home, env));
        // Wrapper 3.3.x (only-script): dists/apache-maven-X/<hash>
        Path neu = home.resolve(".m2/wrapper/dists/apache-maven-3.9.11/5f2a1c");
        Files.createDirectories(neu.resolve("conf"));
        assertEquals(neu, BuildSetup.wrapperDistribution("3.9.11", null, home, env));
        assertNull(BuildSetup.wrapperDistribution("3.8.8", null, home, env));
    }

    @Test
    void detectsInstalledMavenFromMavenHome(@TempDir Path tmp) throws Exception {
        Path mvnHome = tmp.resolve("apache-maven");
        write(mvnHome.resolve("lib/maven-core-3.9.11.jar"), "");
        Map<String, String> env = new HashMap<String, String>();
        env.put("MAVEN_HOME", mvnHome.toString());
        BuildSetup cli = BuildSetup.detectCommandLine(Files.createDirectories(tmp.resolve("p")), tmp, env);
        assertEquals("3.9.11", cli.getMavenVersion());
        assertEquals(mvnHome.toString(), cli.getMavenSource());
        assertNull(cli.getMavenConfig());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "null", value = {
        "1.8.0_402, 8", "21.0.4, 21", "17, 17", "' 11.0.2 ', 11", "25-ea, 25", "1.7, 7", "1, 1", "9.0.1+11, 9",
        "null, -1", "'', -1", "abc, -1", "v17, -1",
        // Zahlen jenseits von int (kaputte release-Datei, IDE-Angabe) sind unbekannt statt NumberFormatException
        "99999999999, -1", "1.99999999999, 1", "123456789, 123456789",
    })
    void javaMajor(String version, int expected) {
        assertEquals(expected, BuildSetup.javaMajor(version));
    }

    static final String URL_3_9_11 = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip";

    /** Mehrere Hash-Ordner (alte URL, Firmen-Mirror): der zur aktuellen distributionUrl gewinnt, nicht der zufällig erste. */
    @Test
    void wrapperDistributionZurAktuellenUrl(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        Path dists = home.resolve(".m2/wrapper/dists/apache-maven-3.9.11");
        for (String other : new String[] {"0000", "1111", "ffffffff"}) {
            Files.createDirectories(dists.resolve(other).resolve("conf"));
        }
        // a2d47e15 = hash_string aus mvnw 3.3.x (sh) für URL_3_9_11
        Path script = dists.resolve("a2d47e15");
        Files.createDirectories(script.resolve("conf"));
        assertEquals(script, BuildSetup.wrapperDistribution("3.9.11", URL_3_9_11, home, Map.of()));
        assertEquals("a2d47e15", BuildSetup.wrapperHashes(URL_3_9_11).get(0));

        // ohne passenden Hash: fester erster Ordner statt Reihenfolge des Dateisystems
        assertEquals(dists.resolve("0000"), BuildSetup.wrapperDistribution("3.9.11", "https://mirror.corp/x.zip", home, Map.of()));
        assertEquals(dists.resolve("0000"), BuildSetup.wrapperDistribution("3.9.11", null, home, Map.of()));
    }

    @Test
    void wrapperDistributionMitHashDerAnderenWrapper(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        List<String> hashes = BuildSetup.wrapperHashes(URL_3_9_11);
        assertEquals(4, hashes.size(), hashes.toString());
        assertEquals(64, hashes.get(1).length(), "mvnw.cmd: SHA-256 als Hex");
        assertEquals(32, hashes.get(2).length(), "mvnw.cmd bis 3.3.1: MD5 als Hex");
        Path bin = home.resolve(".m2/wrapper/dists/apache-maven-3.9.11-bin");
        Files.createDirectories(bin.resolve("aaaa/apache-maven-3.9.11/conf"));
        // Wrapper bis 3.2: MD5 zur Basis 36, darunter apache-maven-X
        Path md5 = bin.resolve(hashes.get(3)).resolve("apache-maven-3.9.11");
        Files.createDirectories(md5.resolve("conf"));
        assertEquals(md5, BuildSetup.wrapperDistribution("3.9.11", URL_3_9_11, home, Map.of()));
        assertTrue(BuildSetup.wrapperHashes("").isEmpty());
    }

    @Test
    void mavenUserHomeVerlegtDieWrapperDistributionen(@TempDir Path tmp) throws Exception {
        Path userHome = tmp.resolve("m2-elsewhere");
        Path dist = userHome.resolve("wrapper/dists/apache-maven-3.9.11/a2d47e15");
        Files.createDirectories(dist.resolve("conf"));
        Path project = tmp.resolve("p");
        write(project.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=" + URL_3_9_11 + "\n");
        BuildSetup cli = BuildSetup.detectCommandLine(project, tmp.resolve("home"), Map.of("MAVEN_USER_HOME", userHome.toString()));
        assertEquals(dist.toString(), cli.getMavenHome());
        assertEquals("3.9.11", cli.getMavenVersion());
        assertEquals("Wrapper-Konfiguration ohne mvnw-Skript", cli.getMavenSource());
        assertNull(BuildSetup.wrapperDistribution("3.9.11", URL_3_9_11, null, Map.of()), "ohne Home kein Ort");
        assertNull(BuildSetup.wrapperDistribution(null, URL_3_9_11, tmp, Map.of()));
    }

    @Test
    void m2HomeWennMavenHomeFehltOderNichtExistiert(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("maven-3.8.8");
        write(m2.resolve("lib/maven-core-3.8.8.jar"), "");
        Map<String, String> env = Map.of("MAVEN_HOME", tmp.resolve("gibt-es-nicht").toString(), "M2_HOME", m2.toString());
        BuildSetup cli = BuildSetup.detectCommandLine(Files.createDirectories(tmp.resolve("p")), tmp, env);
        assertEquals("3.8.8", cli.getMavenVersion());
        assertEquals(m2.toString(), cli.getMavenHome());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)   // ausführbares Skript und Symlink
    void mavenUndJavaAusDemPath(@TempDir Path tmp) throws Exception {
        Path maven = tmp.resolve("opt/apache-maven-3.9.9");
        write(maven.resolve("lib/maven-core-3.9.9.jar"), "");
        write(maven.resolve("bin/mvn"), "#!/bin/sh");
        maven.resolve("bin/mvn").toFile().setExecutable(true);
        Path jdk = tmp.resolve("jvm/temurin-17");
        write(jdk.resolve("release"), "JAVA_VERSION=\"17.0.12\"\n");
        write(jdk.resolve("bin/java"), "#!/bin/sh");
        jdk.resolve("bin/java").toFile().setExecutable(true);
        // /usr/bin/java → …/jvm/temurin-17/bin/java wie bei update-alternatives
        Path usrBin = Files.createDirectories(tmp.resolve("usr/bin"));
        Files.createSymbolicLink(usrBin.resolve("java"), jdk.resolve("bin/java"));
        Path notExecutable = tmp.resolve("other/bin/mvn");
        write(notExecutable, "");

        String path = String.join(java.io.File.pathSeparator, "", tmp.resolve("other/bin").toString(),
                maven.resolve("bin").toString(), usrBin.toString());
        BuildSetup cli = BuildSetup.detectCommandLine(Files.createDirectories(tmp.resolve("p")), tmp, Map.of("PATH", path));
        assertEquals("3.9.9", cli.getMavenVersion());
        assertEquals(maven.toRealPath().toString(), cli.getMavenHome());
        assertEquals(jdk.toRealPath().toString(), cli.getJavaHome());
        assertEquals("17.0.12", cli.getJavaVersion());
        assertEquals(1, cli.getNotes().size(), "Hinweis: JAVA_HOME fehlt");
    }

    @Test
    void ohneMavenUndJavaBleibtBeidesUnbekanntMitHinweis(@TempDir Path tmp) throws Exception {
        BuildSetup cli = BuildSetup.detectCommandLine(Files.createDirectories(tmp.resolve("p")), tmp,
                Map.of("JAVA_HOME", " ", "MAVEN_OPTS", "  "));
        assertNull(cli.getMavenVersion());
        assertNull(cli.getMavenHome());
        assertNull(cli.getJavaHome());
        assertNull(cli.getMavenOpts());
        assertNull(cli.getUserSettings());
        assertEquals(1, cli.getNotes().size(), cli.getNotes().toString());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "-s a.xml|a.xml",
        "-sa.xml|a.xml",
        "--settings a.xml|a.xml",
        "--settings=a.xml|a.xml",
        "-s a.xml -s b.xml|b.xml",
        "'-s \"my settings.xml\"'|my settings.xml",
        "--settings-security x|",
        "-s|",
        "-U -B|",
    })
    void settingsAusMavenConfig(String config, String expected) {
        assertEquals(expected, BuildSetup.argValue(BuildSetup.splitArgs(config), "-s", "--settings"));
    }

    @Test
    void profileUndLokalesRepositoryAusMavenConfig(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("p");
        write(project.resolve(".mvn/maven.config"), "-P a,b -Pc --activate-profiles=d -P ,, -Dmaven.repo.local=/x\n-Dmaven.repo.local=/y");
        BuildSetup cli = BuildSetup.detectCommandLine(project, tmp, Map.of());
        assertEquals(List.of("a", "b", "c", "d"), cli.getProfiles());
        assertEquals("/y", cli.getLocalRepository().replace('\\', '/').replaceFirst("^[A-Za-z]:", ""), "spätere Angabe gewinnt");
        assertEquals(List.of("a b", "c", "d'e"), BuildSetup.splitArgs("'a b' c \"d'e\""));
        assertTrue(BuildSetup.splitArgs(null).isEmpty());
    }

    @Test
    void comparisonNamesWhatDiffersAndWhy() {
        BuildSetup ide = new BuildSetup("IDE").setMavenVersion("3.9.11").setMavenSource("gebündelt")
                .setJavaVersion("21.0.4").setUserSettings("/home/u/.m2/settings.xml");
        BuildSetup cli = new BuildSetup("Kommandozeile").setMavenVersion("3.8.8").setMavenSource("Wrapper (./mvnw)")
                .setJavaVersion("17.0.12").setUserSettings("/home/u/.m2/settings.xml").setMavenConfig("-Pci");
        cli.getProfiles().add("ci");

        SetupComparison c = SetupComparison.compare(ide, cli);
        assertEquals("IDE und Kommandozeile unterscheiden sich bei: JDK, Maven, Profile (explizit), .mvn/maven.config", c.summary());
        assertTrue(c.getDifferences().get(0).getHint().startsWith("IDE baut mit Java 21, Kommandozeile mit Java 17"));
        assertTrue(c.getDifferences().get(1).getHint().contains("Minor-Version"));
        String md = c.markdown();
        assertTrue(md.contains("| **JDK** | 21.0.4 | 17.0.12 |"), md);
        assertTrue(md.contains("| settings.xml | /home/u/.m2/settings.xml | /home/u/.m2/settings.xml |"), md);
    }

    @Test
    void sameSetupSaysSo() {
        BuildSetup a = new BuildSetup("IDE").setMavenVersion("3.9.11").setJavaVersion("21.0.4");
        BuildSetup b = new BuildSetup("Kommandozeile").setMavenVersion("3.9.11").setJavaVersion("21.0.2");
        SetupComparison c = SetupComparison.compare(a, b);
        assertFalse(c.getRows().get(0).isDifferent(), "Patch-Versionen des JDK zählen nicht");
        assertEquals("IDE und Kommandozeile bauen mit derselben Umgebung.", c.summary());
    }
    /** Review #55: Homebrew legt die Installation unter libexec ab. */
    @Test
    void homebrewInstallationLivesInLibexec(@TempDir Path tmp) throws Exception {
        Path cellar = tmp.resolve("Cellar/maven/3.9.11");
        write(cellar.resolve("bin/mvn"), "#!/bin/bash\nexec libexec/bin/mvn \"$@\"");
        write(cellar.resolve("libexec/lib/maven-core-3.9.11.jar"), "");
        write(cellar.resolve("libexec/conf/settings.xml"), "<settings/>");
        Map<String, String> env = new HashMap<String, String>();
        env.put("MAVEN_HOME", cellar.toString());
        BuildSetup cli = BuildSetup.detectCommandLine(Files.createDirectories(tmp.resolve("p")), tmp, env);
        assertEquals("3.9.11", cli.getMavenVersion());
        assertEquals(cellar.resolve("libexec").toString(), cli.getMavenHome());
    }

    /** Review #67: ein relatives -Dmaven.repo.local gilt relativ zum Projekt. */
    @Test
    void relativeLocalRepositoryIsResolvedAgainstTheProject(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("project");
        write(project.resolve(".mvn/maven.config"), "-Dmaven.repo.local=.m2/repository\n");
        BuildSetup cli = BuildSetup.detectCommandLine(project, tmp.resolve("home"), new HashMap<String, String>());
        assertEquals(project.resolve(".m2/repository").toString(), cli.getLocalRepository());
    }
    /** Review #66: Mirror und Proxy wie Maven über Nutzer- und globale settings.xml. */
    @Test
    void mirrorAndProxyAreChosenLikeMaven(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("user.xml"), "<settings><mirrors><mirror><id>all</id><mirrorOf>*</mirrorOf><url>https://all/</url></mirror></mirrors>"
                + "<proxies><proxy><id>p1</id><protocol>https</protocol><host>p1</host><nonProxyHosts>*.example.org</nonProxyHosts></proxy>"
                + "<proxy><id>p2</id><protocol>http</protocol><host>p2</host></proxy></proxies></settings>");
        write(tmp.resolve("global.xml"), "<settings><mirrors><mirror><id>c</id><mirrorOf>central</mirrorOf><url>https://c/</url></mirror>"
                + "<mirror><id>all</id><mirrorOf>other</mirrorOf><url>https://ignored/</url></mirror></mirrors></settings>");
        MavenSettings user = MavenSettings.read(tmp.resolve("user.xml"));
        MavenSettings global = MavenSettings.read(tmp.resolve("global.xml"));
        java.util.List<MavenSettings> both = Arrays.asList(user, global);

        MavenSettings.Chosen<MavenSettings.Mirror> central = MavenSettings.mirrorFor(both, "central", "https://repo.maven.apache.org/maven2");
        assertEquals("c", central.value.id, "exakte ID in global schlägt * beim Nutzer");
        assertTrue(central.from == global);
        assertEquals("all", MavenSettings.mirrorFor(both, "other", "https://x/").value.id);
        assertEquals("https://all/", MavenSettings.mirrorFor(both, "other", "https://x/").value.url, "gleiche ID in global fällt weg");

        assertEquals("p2", MavenSettings.proxyFor(both, "https://nexus.example.org/repo").host, "https-Proxy ausgenommen → http-Proxy");
        assertEquals("p1", MavenSettings.proxyFor(both, "https://repo.maven.apache.org/maven2").host);
        assertEquals("p2", MavenSettings.proxyFor(both, "http://x/").host);
    }
}
