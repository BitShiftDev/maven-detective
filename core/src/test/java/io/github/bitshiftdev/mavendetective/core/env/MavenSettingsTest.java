package io.github.bitshiftdev.mavendetective.core.env;

import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenSettings}: Platzhalter, Proxies, Mirrors. Mirror- und Proxy-Wahl werden gegen Maven selbst geprüft
 * ({@code DefaultMirrorSelector}, {@code DefaultProxySelector} aus Maven Resolver, Zusammenführen von Nutzer- und
 * globaler settings.xml über Mavens Settings-Builder) – so, wie Maven 3.9 die Session daraus baut.
 */
class MavenSettingsTest {

    /** Repositories, für die Mirror und Proxy gesucht werden: entfernt, lokal, Datei, dav, Groß/Klein, Zugangsdaten. */
    static final String[][] REPOSITORIES = {
        {"central", "https://repo.maven.apache.org/maven2"},
        {"snapshots", "http://repo.corp.example/snapshots"},
        {"internal", "http://localhost:8081/repo"},
        {"loopback", "http://127.0.0.1/x"},
        {"local", "file:///tmp/repo"},
        {"lookalike", "http://localhost.corp.example/x"},
        {"upper", "HTTP://REPO.corp.example/x"},
        {"dav", "dav:http://dav.corp.example/x"},
        {"secured", "https://user:secret@nexus.corp.example/x"},
        {"tls", "https://x.CORP.example:8443/y"},
    };

    @TempDir
    Path tmp;

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    MavenSettings read(String xml, Map<String, String> env) throws IOException {
        return MavenSettings.read(write(tmp.resolve("settings.xml"), xml), env, tmp.resolve("home"));
    }

    String mirrorUrl(String url, Map<String, String> env) throws IOException {
        return read("<settings><mirrors><mirror><id>m</id><mirrorOf>*</mirrorOf><url>" + url
                + "</url></mirror></mirrors></settings>", env).getMirrors().get(0).url;
    }

    // ---------------------------------------------------------------- Lesen

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "${env.NEXUS}/repo|https://nexus.example/repo",
        "${NEXUS}/repo|https://nexus.example/repo",
        "${env.NEXUS}${env.SUFFIX}|https://nexus.example/a$1\\b",
        "${env.MISSING}/repo|${env.MISSING}/repo",
        "${MISSING}|${MISSING}",
        "${env.NEXUS|${env.NEXUS",
        "plain|plain",
    })
    void platzhalterWieMaven(String raw, String expected) throws IOException {
        Map<String, String> env = Map.of("NEXUS", "https://nexus.example", "SUFFIX", "/a$1\\b");
        assertEquals(expected, mirrorUrl(raw, env));
    }

    @Test
    void userHomeAusDemAufrufSonstSystemProperty() throws IOException {
        assertEquals(tmp.resolve("home") + "/repo", mirrorUrl("${user.home}/repo", Map.of()));
        assertEquals(System.getProperty("java.version"), mirrorUrl("${java.version}", Map.of()));
        String fromJvm = MavenSettings.read(write(tmp.resolve("s.xml"), "<settings><localRepository>${user.home}/r</localRepository></settings>"),
                Map.of(), null).getLocalRepository();
        assertEquals(System.getProperty("user.home") + "/r", fromJvm);
    }

    @Test
    void unaufgeloesteWerteGeltenAlsUnbekannt() throws IOException {
        MavenSettings s = read("""
                <settings>
                  <localRepository>${env.CACHE}/repo</localRepository>
                  <offline>${env.OFFLINE}</offline>
                  <proxies><proxy><host>${env.PROXY}</host><port>${env.PORT}</port></proxy></proxies>
                </settings>""", Map.of());
        assertNull(s.getLocalRepository());
        assertNull(s.getOffline());
        assertNull(s.getProxies().get(0).host);
        assertEquals(8080, s.getProxies().get(0).port);
        assertNull(s.activeProxy("http"), "Proxy ohne bekannten Host wird nie gewählt");
        assertTrue(MavenSettings.unresolved("${x}"));
        assertFalse(MavenSettings.unresolved("$x"));
        assertFalse(MavenSettings.unresolved(null));
    }

    @Test
    void proxyMitMavensStandardwerten() throws IOException {
        MavenSettings s = read("""
                <settings><proxies>
                  <proxy><id>off</id><active>false</active><host>off.corp</host></proxy>
                  <proxy><id>plain</id><host>plain.corp</host><port>nonsense</port></proxy>
                  <proxy><id>tls</id><protocol>HTTPS</protocol><host>tls.corp</host><port>3129</port></proxy>
                </proxies></settings>""", Map.of());
        MavenSettings.Proxy off = s.getProxies().get(0);
        MavenSettings.Proxy plain = s.getProxies().get(1);
        assertFalse(off.active);
        assertTrue(plain.active);
        assertEquals("http", plain.protocol);
        assertEquals(8080, plain.port);
        assertEquals("plain", s.activeProxy("http").id, "inaktiver Proxy wird übersprungen");
        assertEquals("tls", s.activeProxy("https").id);
        assertNull(s.activeProxy("ftp"));
    }

    @Test
    void offlineServerUndPluginGruppen() throws IOException {
        MavenSettings s = read("""
                <settings>
                  <offline> true </offline>
                  <servers><server><id>nexus</id></server><server><id>${env.SERVER}</id></server></servers>
                  <pluginGroups><pluginGroup>org.mortbay.jetty</pluginGroup></pluginGroups>
                </settings>""", Map.of("SERVER", "corp"));
        assertEquals(Boolean.TRUE, s.getOffline());
        assertEquals(List.of("nexus", "corp"), s.getServerIds());
        assertEquals(List.of("org.mortbay.jetty"), s.getPluginGroups());
        assertEquals(3, s.lineOf("<servers>"));
        assertEquals(-1, s.lineOf("<mirrors>"));
        assertEquals("  <offline> true </offline>", s.getLines().get(1));
    }

    @Test
    void fehlendeDateiIstNullKaputteWirft() throws IOException {
        assertNull(MavenSettings.read(tmp.resolve("gibt-es-nicht.xml"), Map.of(), null));
        assertNull(MavenSettings.read(null, Map.of(), null));
        assertNull(MavenSettings.read(Files.createDirectories(tmp.resolve("dir")), Map.of(), null));
        IOException broken = assertThrows(IOException.class, () -> read("<settings><mirrors></settings>", Map.of()));
        assertTrue(broken.getMessage().contains("settings.xml"), broken.getMessage());
        // DOCTYPE (externe Entities) wird nie gelesen
        assertThrows(IOException.class, () -> read("<!DOCTYPE settings [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><settings>&x;</settings>", Map.of()));
    }

    // ---------------------------------------------------------------- mirrorOf gegen Maven

    static Stream<Arguments> mirrorPatterns() {
        String[] patterns = {
            "*", "central", "external:*", "external:http:*", "*,!snapshots", "central,snapshots", "central, snapshots",
            "*, !snapshots", "!central,*", "central,!central", "external:*,!internal", "external:http:*,!snapshots",
            "other", "!", "!,*", ",central", "Central", "external:*,local",
        };
        List<Arguments> out = new ArrayList<>();
        for (String p : patterns) {
            for (String[] r : REPOSITORIES) {
                out.add(Arguments.of(p, r[0], r[1]));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "mirrorOf \"{0}\" für {1}")
    @MethodSource("mirrorPatterns")
    void mirrorOfWieMaven(String mirrorOf, String id, String url) {
        DefaultMirrorSelector maven = new DefaultMirrorSelector()
                .add("m", "https://mirror.example/", "default", false, false, mirrorOf, "default,legacy");
        boolean expected = maven.getMirror(new RemoteRepository.Builder(id, "default", url).build()) != null;
        assertEquals(expected, MavenSettings.Mirror.of("m", "https://mirror.example/", mirrorOf).matches(id, url));
    }

    // ---------------------------------------------------------------- nonProxyHosts gegen Maven

    @ParameterizedTest(name = "nonProxyHosts \"{0}\" für {1}")
    @CsvSource(delimiter = ';', value = {
        "*.corp.example;nexus.corp.example",
        "*.corp.example;corp.example",
        "*.CORP.example;nexus.corp.EXAMPLE",
        "localhost|*.corp.example;localhost",
        "localhost|*.corp.example;nexus.corp.example",
        "localhost,*.corp.example;nexus.corp.example",
        "localhost, nexus.corp.example;nexus.corp.example",
        "localhost| nexus.corp.example;nexus.corp.example",
        "repo.maven.apache.org;repo.maven.apache.org",
        "repo.maven.apache.org;repoxmavenxapache.org",
        "10.*;10.1.2.3",
        "*;anything.example",
        "||;nexus.corp.example",
        "nexus*;nexus.corp.example",
    })
    void nonProxyHostsWieMaven(String nonProxyHosts, String host) {
        String url = "https://" + host + "/repo";
        DefaultProxySelector maven = new DefaultProxySelector()
                .add(new org.eclipse.aether.repository.Proxy("https", "proxy", 3128), nonProxyHosts);
        boolean expected = maven.getProxy(new RemoteRepository.Builder("r", "default", url).build()) == null;
        MavenSettings.Proxy ours = new MavenSettings.Proxy("p", true, "https", "proxy", 3128, nonProxyHosts);
        assertEquals(expected, ours.bypasses(host));
    }

    // ---------------------------------------------------------------- Nutzer + global gegen Maven

    /** Zufällige Nutzer- und globale settings.xml: Maven (Settings-Builder + Resolver) und wir wählen dasselbe. */
    @Test
    void zusammengefuehrteSettingsWaehlenWieMaven() throws Exception {
        SettingsBuilder builder = new DefaultSettingsBuilderFactory().newInstance();
        Path home = tmp.resolve("home");
        Map<String, String> env = System.getenv();
        String var = env.keySet().stream().sorted()
                .filter(k -> k.matches("[A-Z][A-Z0-9_]*") && env.get(k).matches("[A-Za-z0-9/._:-]+"))
                .findFirst().orElse(null);
        Properties system = new Properties();
        system.putAll(System.getProperties());
        system.setProperty("user.home", home.toString());
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            Path user = write(tmp.resolve("user.xml"), randomSettings(rnd, "u", var));
            Path global = write(tmp.resolve("global.xml"), randomSettings(rnd, "g", var));
            String context = "Runde " + round + "\nNutzer:\n" + Files.readString(user) + "\nglobal:\n" + Files.readString(global);

            Settings effective = builder.build(new DefaultSettingsBuildingRequest().setUserSettingsFile(user.toFile())
                    .setGlobalSettingsFile(global.toFile()).setSystemProperties(system)
                    .setUserProperties(new Properties())).getEffectiveSettings();
            DefaultMirrorSelector mirrors = new DefaultMirrorSelector();
            for (org.apache.maven.settings.Mirror m : effective.getMirrors()) {
                mirrors.add(m.getId(), m.getUrl(), m.getLayout(), false, m.isBlocked(), m.getMirrorOf(), m.getMirrorOfLayouts());
            }
            DefaultProxySelector proxies = new DefaultProxySelector();
            for (org.apache.maven.settings.Proxy p : effective.getProxies()) {
                if (p.isActive()) {
                    proxies.add(new org.eclipse.aether.repository.Proxy(p.getProtocol(), p.getHost(), p.getPort()), p.getNonProxyHosts());
                }
            }
            List<MavenSettings> ours = Arrays.asList(MavenSettings.read(user, env, home), MavenSettings.read(global, env, home));

            for (String[] r : REPOSITORIES) {
                RemoteRepository repository = new RemoteRepository.Builder(r[0], "default", r[1]).build();
                RemoteRepository mirror = mirrors.getMirror(repository);
                MavenSettings.Chosen<MavenSettings.Mirror> chosen = MavenSettings.mirrorFor(ours, r[0], r[1]);
                assertEquals(mirror == null ? null : mirror.getId() + " " + mirror.getUrl(),
                        chosen == null ? null : chosen.value.id + " " + chosen.value.url, "Mirror für " + r[0] + "\n" + context);

                org.eclipse.aether.repository.Proxy proxy = proxies.getProxy(repository);
                MavenSettings.Proxy own = MavenSettings.proxyFor(ours, r[1]);
                assertEquals(proxy == null ? null : proxy.getHost() + ":" + proxy.getPort(),
                        own == null ? null : own.host + ":" + own.port, "Proxy für " + r[0] + "\n" + context);
            }
        }
    }

    private static String randomSettings(Random rnd, String file, String envVar) {
        String[] ids = file.equals("u") ? new String[] {"m1", "m2", "m3"} : new String[] {"m2", "m3", "m4"};
        String[] mirrorOf = {"*", "central", "external:*", "external:http:*", "*,!snapshots", "central, snapshots",
            "snapshots", "internal,local", "!central,*", "external:*,!secured"};
        String[] urls = {"https://%s.example/repo", "http://%s.example/repo", "${env.NOPE_%s}/repo", "${user.home}/%s"};
        StringBuilder sb = new StringBuilder("<settings>\n  <mirrors>\n");
        List<String> pool = new ArrayList<>(Arrays.asList(ids));
        int mirrorCount = rnd.nextInt(4);
        for (int i = 0; i < mirrorCount && !pool.isEmpty(); i++) {
            String id = pool.remove(rnd.nextInt(pool.size()));
            String url = urls[rnd.nextInt(urls.length)].formatted(id + "-" + file);
            if (envVar != null && rnd.nextInt(5) == 0) {
                url = (rnd.nextBoolean() ? "${env." + envVar + "}" : "${" + envVar + "}") + "/" + id;
            }
            sb.append("    <mirror><id>").append(id).append("</id><mirrorOf>").append(mirrorOf[rnd.nextInt(mirrorOf.length)])
                    .append("</mirrorOf><url>").append(url).append("</url></mirror>\n");
        }
        sb.append("  </mirrors>\n  <proxies>\n");
        String[] protocols = {"http", "https", "HTTP"};
        String[] nonProxy = {null, "*.corp.example", "localhost|*.corp.example", "*.corp.example,localhost",
            "repo.maven.apache.org", "*.CORP.example", "nexus.corp.example | localhost"};
        int proxyCount = rnd.nextInt(4);
        for (int i = 0; i < proxyCount; i++) {
            String id = rnd.nextInt(4) == 0 ? null : "p" + rnd.nextInt(3);
            if (id != null && sb.indexOf("<id>" + id + "</id>") >= 0 || id == null && sb.indexOf("<proxy><host>") >= 0) {
                continue;   // IDs je Datei eindeutig (sonst lehnt Mavens Validierung die Datei ab)
            }
            sb.append("    <proxy>");
            if (id != null) {
                sb.append("<id>").append(id).append("</id>");
            }
            sb.append("<host>").append(file).append(i).append(".proxy</host>");
            if (rnd.nextBoolean()) {
                sb.append("<protocol>").append(protocols[rnd.nextInt(protocols.length)]).append("</protocol>");
            }
            if (rnd.nextInt(3) == 0) {
                sb.append("<active>false</active>");
            }
            if (rnd.nextBoolean()) {
                sb.append("<port>").append(3000 + i).append("</port>");
            }
            String np = nonProxy[rnd.nextInt(nonProxy.length)];
            if (np != null) {
                sb.append("<nonProxyHosts>").append(np).append("</nonProxyHosts>");
            }
            sb.append("</proxy>\n");
        }
        return sb.append("  </proxies>\n</settings>\n").toString();
    }
}
