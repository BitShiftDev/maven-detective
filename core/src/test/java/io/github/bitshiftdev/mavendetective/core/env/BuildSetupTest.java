package io.github.bitshiftdev.mavendetective.core.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
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
        write(project.resolve(".mvn/maven.config"), "-s .mvn/settings.xml\n-Pci,fast -Dmaven.repo.local=/cache/m2\n");
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
        assertEquals("/cache/m2", cli.getLocalRepository());
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
        assertEquals(old, BuildSetup.wrapperDistribution("3.9.6", home, env));
        // Wrapper 3.3.x (only-script): dists/apache-maven-X/<hash>
        Path neu = home.resolve(".m2/wrapper/dists/apache-maven-3.9.11/5f2a1c");
        Files.createDirectories(neu.resolve("conf"));
        assertEquals(neu, BuildSetup.wrapperDistribution("3.9.11", home, env));
        assertNull(BuildSetup.wrapperDistribution("3.8.8", home, env));
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

    @Test
    void javaMajor() {
        assertEquals(8, BuildSetup.javaMajor("1.8.0_402"));
        assertEquals(21, BuildSetup.javaMajor("21.0.4"));
        assertEquals(17, BuildSetup.javaMajor("17"));
        assertEquals(-1, BuildSetup.javaMajor(null));
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
