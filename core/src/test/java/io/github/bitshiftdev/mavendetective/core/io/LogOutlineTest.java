package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.io.LogOutline.Module;
import io.github.bitshiftdev.mavendetective.core.io.LogOutline.Result;
import io.github.bitshiftdev.mavendetective.core.testkit.Golden;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogOutlineTest {

    /** Maven 3.9, drei Module, Downloads, Kompilierfehler im zweiten Modul. */
    static final String REACTOR = """
            [INFO] Scanning for projects...
            [INFO] ------------------------------------------------------------------------
            [INFO] Reactor Build Order:
            [INFO]
            [INFO] parent                                                             [pom]
            [INFO] core                                                               [jar]
            [INFO] web                                                                [jar]
            [INFO]
            [INFO] -------------------------< com.acme:parent >--------------------------
            [INFO] Building parent 1.0-SNAPSHOT                                       [1/3]
            [INFO]   from pom.xml
            [INFO] --------------------------------[ pom ]---------------------------------
            [INFO] Downloading from central: https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/3.1.2/maven-install-plugin-3.1.2.pom
            [INFO] Downloaded from central: https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/3.1.2/maven-install-plugin-3.1.2.pom (8.4 kB at 120 kB/s)
            [INFO] Downloading from central: https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/3.1.2/maven-install-plugin-3.1.2.jar
            [INFO] Downloaded from central: https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/3.1.2/maven-install-plugin-3.1.2.jar (31 kB at 400 kB/s)
            [INFO]
            [INFO] --- install:3.1.2:install (default-install) @ parent ---
            [INFO]
            [INFO] --------------------------< com.acme:core >---------------------------
            [INFO] Building core 1.0-SNAPSHOT                                         [2/3]
            [INFO]   from core/pom.xml
            [INFO] --------------------------------[ jar ]---------------------------------
            [INFO]
            [INFO] --- resources:3.3.1:resources (default-resources) @ core ---
            [INFO] Copying 1 resource from src/main/resources to target/classes
            [INFO]
            [INFO] --- compiler:3.13.0:compile (default-compile) @ core ---
            [WARNING] system modules path not set in conjunction with -source 17
            [INFO] -------------------------------------------------------------
            [ERROR] COMPILATION ERROR :
            [INFO] -------------------------------------------------------------
            [ERROR] /work/core/src/main/java/com/acme/Foo.java:[3,17] cannot find symbol
              symbol:   class Bar
              location: package com.acme
            [INFO] 1 error
            [INFO] -------------------------------------------------------------
            [INFO] ------------------------------------------------------------------------
            [INFO] Reactor Summary for parent 1.0-SNAPSHOT:
            [INFO]
            [INFO] parent ............................................. SUCCESS [  0.210 s]
            [INFO] core ............................................... FAILURE [  1.134 s]
            [INFO] web ................................................ SKIPPED
            [INFO] ------------------------------------------------------------------------
            [INFO] BUILD FAILURE
            [INFO] ------------------------------------------------------------------------
            [INFO] Total time:  1.912 s
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project core: Compilation failure
            [ERROR] /work/core/src/main/java/com/acme/Foo.java:[3,17] cannot find symbol
            [ERROR] -> [Help 1]
            [ERROR]
            [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
            [ERROR] Re-run Maven using the -X switch to enable full debug logging.
            """;

    @Test
    void modulePluginSchritteUndErsterFehler() {
        LogOutline o = LogOutline.of(REACTOR);
        assertEquals(Result.FAILURE, o.getBuildResult());
        List<Module> modules = o.getModules();
        assertEquals(List.of("parent", "core"), modules.stream().map(Module::getName).toList());
        assertEquals("com.acme", modules.get(1).getGroupId());
        assertEquals(Result.SUCCESS, modules.get(0).getResult());
        assertEquals("0.210 s", modules.get(0).getTime());

        Module failed = o.getFailedModule();
        assertNotNull(failed);
        assertEquals("core", failed.getName());
        assertEquals(Result.FAILURE, failed.getResult());
        assertEquals("compiler:compile (default-compile)", failed.getFailedStep().label());
        assertEquals(1, failed.getWarnings());

        // erster echter Fehler ist die Fundstelle, nicht „COMPILATION ERROR :“
        assertTrue(o.getLines().get(o.getFirstError()).contains("Foo.java:[3,17]"), o.getLines().get(o.getFirstError()));
        assertEquals(failed, o.moduleAt(o.getFirstError()));
        assertTrue(o.getLines().get(o.getResultLine()).contains("BUILD FAILURE"));
    }

    @Test
    void zusammenfassungAlsGoldenFile() {
        LogOutline o = LogOutline.of(List.of(REACTOR.split("\n", -1)));
        Golden.both("io/outline-reactor", "txt", () -> String.join("\n", o.summary(3)));
        Golden.both("io/outline-reactor-context0", "txt", () -> String.join("\n", o.summary(0)));
        Golden.check("io/outline-reactor-strip.txt", String.join("\n", Golden.in(io.github.bitshiftdev.mavendetective.core.Texts.Language.DE,
                o::withoutDownloads)));
    }

    @Test
    void downloadsWerdenZuEinemBlock() {
        LogOutline o = LogOutline.of(REACTOR);
        assertEquals(4, o.getDownloadLines());
        assertEquals(1, o.getDownloads().size());
        assertEquals(4, o.getDownloads().get(0).size());
        List<String> stripped = o.withoutDownloads();
        assertEquals(o.getLines().size() - 3, stripped.size());
        assertTrue(stripped.contains(LogOutline.placeholder(4)));
        assertTrue(LogOutline.placeholder(4).contains("4 Download-Zeilen"));
    }

    @Test
    void downloadRauschenErkennen() {
        assertTrue(LogOutline.isDownloadNoise("[INFO] Downloading from central: https://repo.maven.apache.org/x.pom"));
        assertTrue(LogOutline.isDownloadNoise("Downloaded: https://repo1.maven.org/maven2/x.jar (12 KB at 3.0 KB/sec)"));
        assertTrue(LogOutline.isDownloadNoise("2026-11-12T06:48:01.123Z [INFO] Downloaded from nexus: https://n/x.jar (1 kB at 2 kB/s)"));
        assertTrue(LogOutline.isDownloadNoise("\u001B[1;34mProgress (1): 2.3/4.5 kB\u001B[m"));
        assertTrue(LogOutline.isDownloadNoise("Progress (2): 12 kB | 4.1/9.0 kB\rProgress (2): 12 kB | 9.0 kB"));
        assertTrue(LogOutline.isDownloadNoise("[INFO] Downloaded from central: https://r/x.pom (1 kB at 2 kB/s)\r\n"));
        assertFalse(LogOutline.isDownloadNoise("[INFO] Building core 1.0"));
        assertFalse(LogOutline.isDownloadNoise("[ERROR] Failed to execute goal on project x: Could not resolve dependencies"));
        assertFalse(LogOutline.isDownloadNoise(""));
    }

    @Test
    void einzelnesModulOhneReactorSummary() {
        String log = """
                [INFO] -----------------------< com.acme:single >------------------------
                [INFO] Building single 1.0
                [INFO] --------------------------------[ jar ]---------------------------------
                [INFO] --- surefire:3.2.5:test (default-test) @ single ---
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] com.acme.FooTest.works -- Time elapsed: 0.01 s <<< FAILURE!
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project single: There are test failures.
                """;
        LogOutline o = LogOutline.of(log);
        assertEquals(1, o.getModules().size());
        Module m = o.getFailedModule();
        assertEquals("single", m.getArtifactId());
        assertEquals(Result.FAILURE, m.getResult());
        assertEquals("surefire:test (default-test)", m.getFailedStep().label());
        assertTrue(o.getLines().get(o.getFirstError()).contains("FooTest.works"));
        String summary = String.join("\n", o.summary(1));
        assertTrue(summary.contains("FooTest.works"), summary);
    }

    @Test
    void gruenerLaufOhneFehler() {
        LogOutline o = LogOutline.of("[INFO] Building x 1\n[INFO] BUILD SUCCESS\n");
        assertEquals(-1, o.getFirstError());
        assertNull(o.getFailedModule());
        assertEquals(Result.SUCCESS, o.getModules().get(0).getResult());
    }

    @Test
    void jedesTestlogHatEinenErstenFehler() throws IOException {
        Path dir = Paths.get("src/test/resources/logs");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.log")) {
            for (Path p : ds) {
                LogOutline o = LogOutline.of(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                assertTrue(o.getFirstError() >= 0, p.toString());
                o.summary(2);
                o.withoutDownloads();
            }
        }
    }
}
