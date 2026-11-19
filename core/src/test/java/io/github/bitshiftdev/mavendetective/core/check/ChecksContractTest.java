package io.github.bitshiftdev.mavendetective.core.check;

import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.Cancellation;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import io.github.bitshiftdev.mavendetective.core.testkit.CheckBench;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Was für <b>jede</b> registrierte Prüfung gilt: sie ändert keine Datei, geht nie ins Netz, übersteht
 * verstümmelte Variablen aus dem Log, und eine Diagnose bricht bei {@link Cancellation} ab.
 */
class ChecksContractTest {

    /** Variablen, die Prüfungen lesen (Namen aus den Regex-Gruppen des Katalogs). */
    private static final String[] VARS = {
        "artifact", "class", "classVersion", "dir", "failedModule", "file", "member", "module", "moduleType", "package",
        "plugin", "pluginArtifact", "prefix", "project", "repoId", "repoUrl", "required", "rule", "runner",
        "runtimeClassVersion", "sourceFile", "status",
    };

    @TempDir
    Path tmp;

    private ProxySelector previousProxySelector;

    /** Jeder Versuch, eine Verbindung aufzubauen, fragt zuerst den ProxySelector – hier scheitert er laut. */
    @BeforeEach
    void forbidNetwork() {
        previousProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                throw new AssertionError("Prüfung geht ins Netz: " + uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                throw new AssertionError("Prüfung geht ins Netz: " + uri);
            }
        });
    }

    @AfterEach
    void allowNetwork() {
        ProxySelector.setDefault(previousProxySelector);
    }

    /** Projekt, Home und Repository mit allem, worauf Prüfungen anspringen. */
    private CheckBench richBench() {
        CheckBench b = CheckBench.in(tmp);
        b.projectFile("pom.xml", TestPom.of("com.acme", "root", "1.0").packaging("pom").module("app").module("web")
                .property("maven.compiler.release", "21").xml());
        b.projectFile("app/pom.xml", "<project>\n<parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version>"
                + "<relativePath>../wrong</relativePath></parent>\n<artifactId>app</artifactId>\n<packaging>ear</packaging>\n"
                + "<repositories><repository><id>old</id><url>http://repo.example.org/m2</url></repository></repositories>\n"
                + "<dependencies><dependency><groupId>javax</groupId><artifactId>javaee-api</artifactId><version>8.0</version></dependency>"
                + "</dependencies>\n<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                + "<configuration><argLine>-Xmx512m</argLine></configuration></plugin>\n"
                + "<plugin><artifactId>maven-ear-plugin</artifactId></plugin></plugins></build>\n</project>\n");
        b.projectFile("web/pom.xml", TestPom.of(null, "web", null).parent("com.acme", "root", "1.0").packaging("war").xml());
        b.projectFile(".mvn/jvm.config", "-Xmx1g\n");
        b.homeFile(".m2/settings.xml", "<settings>\n<offline>false</offline>\n<servers><server><id>Nexus</id></server></servers>\n"
                + "<mirrors><mirror><id>corp</id><mirrorOf>*</mirrorOf><url>http://repo.example.org/m2</url></mirror></mirrors>\n"
                + "<proxies><proxy><id>p</id><active>true</active><host>proxy</host><port>8080</port></proxy></proxies>\n</settings>\n");
        b.homeFile(".m2/toolchains.xml", "<toolchains/>");
        b.repositoryFile("com/x/lib/1.0/lib-1.0.jar", "not a zip".getBytes(StandardCharsets.US_ASCII));
        b.repositoryFile("com/x/lib/1.0/lib-1.0.jar.lastUpdated", "#".getBytes(StandardCharsets.US_ASCII));
        return b;
    }

    @Test
    void noCheckChangesAFileOrGoesOnline() throws Exception {
        CheckBench b = richBench();
        b.var("artifact", "com.x:lib:jar:1.0").var("repoId", "old").var("repoUrl", "http://repo.example.org/m2")
                .var("project", "com.acme:app:1.0").var("prefix", "spring-boot").var("module", "missing")
                .var("file", b.project().resolve("pom.xml").toString()).var("plugin", "org.apache.maven.plugins:maven-surefire-plugin")
                .var("pluginArtifact", "maven-ear-plugin").var("moduleType", "war").var("module", "com.acme:web")
                .var("package", "jakarta.servlet").var("required", "25").var("rule", "a.b.DependencyConvergence")
                .var("class", "com.x.Missing")
                .failedRequest("com.x:lib:jar:1.0", "nexus", "https://nexus.acme.example", "status code: 401")
                .line(logLines());
        Map<String, String> before = fingerprint(tmp);
        for (Check check : Checks.defaults().all().values()) {
            b.run(check);
            assertEquals(before, fingerprint(tmp), "Prüfung " + check.id() + " hat Dateien verändert");
        }
    }

    /** Alle Zeilen aller Testlogs: jede Prüfung findet darin etwas, worauf sie anspringt. */
    private static String[] logLines() throws Exception {
        List<String> out = new ArrayList<String>();
        Path dir = Paths.get(ChecksContractTest.class.getResource("/logs").toURI());
        try (Stream<Path> logs = Files.list(dir)) {
            for (Path log : (Iterable<Path>) logs.filter(p -> p.toString().endsWith(".log")).sorted()::iterator) {
                out.addAll(MavenLogParser.parse(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)).searchableLines());
            }
        }
        return out.toArray(new String[0]);
    }

    /** Relativer Pfad → SHA-1 des Inhalts (Verzeichnisse mit „dir“), damit auch neue und gelöschte Dateien auffallen. */
    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> out = new TreeMap<String, String>();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                String key = root.relativize(p).toString();
                if (Files.isDirectory(p)) {
                    out.put(key, "dir");
                } else {
                    byte[] digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p));
                    out.put(key, new java.math.BigInteger(1, digest).toString(16) + "@" + Files.getLastModifiedTime(p).toMillis());
                }
            }
        }
        return out;
    }

    /**
     * Was eine Regex-Gruppe im verstümmelten Log trifft, darf keine Prüfung mit einer Ausnahme beenden –
     * BuildDoctor fängt sie zwar ab, aber die Karte zeigt dann „Prüfung fehlgeschlagen“ statt eines Befunds.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "..", ":", "::::", "a:", ":b", "a:b:c:d:e:f:g", "99999999999", "-1", "${x}",
        "\\\\host\\share\\x.jar", "//host/share/pom.xml", "C:\\nope\\pom.xml", "nul\u0000l", "ä€\uD83D\uDE00", "a/../../b"})
    void everyCheckSurvivesGarbageVariables(String value) {
        CheckBench b = richBench();
        for (String name : VARS) {
            b.var(name, value);
        }
        // Zeilen, hinter denen die Prüfungen erst tiefer einsteigen
        b.line("[ERROR] " + value, "[ERROR] 'build.plugins.plugin.version' for " + value + " is missing.",
                "[ERROR] Cannot access central in offline mode", "[ERROR] toolchain", "[ERROR] Process Exit Code: 137",
                "Rule 0: " + value + " failed with message:", value + ":[1,1] x", "[ERROR] @ " + value + ", line 1, column 1");
        List<String> failures = new ArrayList<String>();
        for (Check check : Checks.defaults().all().values()) {
            try {
                b.run(check);
            } catch (RuntimeException e) {
                failures.add(check.id() + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), "Wert '" + value + "': " + failures);
    }

    /** Die IDE bricht eine Diagnose ab (Projekt geschlossen, neuer Lauf) – auch wenn keine Prüfung lange sucht. */
    @Test
    void diagnosisStopsWhenCancelled() {
        CheckBench b = richBench();
        final int[] calls = {0};
        Cancellation cancelled = new Cancellation() {
            @Override
            public void check() {
                calls[0]++;
                throw new Cancelled();
            }
        };
        String log = "[ERROR] Failed to execute goal on project app: Could not resolve dependencies for project com.acme:app:ear:1.0: "
                + "The following artifacts could not be resolved: com.acme:web:jar:1.0 (absent): Could not find artifact com.acme:web:jar:1.0";
        assertThrows(Cancelled.class, () -> BuildDoctor.withDefaults().diagnose(MavenLogParser.parse(log), b.environment(), cancelled));
        assertEquals(1, calls[0]);
    }

    @Test
    void checksSeeTheCancellationOfTheDiagnosis() {
        final Cancellation[] seen = {null};
        Cancellation mine = new Cancellation() {
            @Override
            public void check() {
            }
        };
        Check spy = new Check() {
            @Override
            public String id() { return "spy"; }

            @Override
            public CheckResult run(CheckContext ctx) {
                seen[0] = ctx.cancellation();
                return CheckResult.notApplicable();
            }
        };
        CheckBench b = CheckBench.in(tmp).cancellation(mine);
        b.run(spy);
        if (seen[0] != mine) {
            fail("CheckContext gibt den Abbruch nicht weiter");
        }
    }

    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
