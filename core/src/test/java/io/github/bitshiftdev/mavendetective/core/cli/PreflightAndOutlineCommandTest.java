package io.github.bitshiftdev.mavendetective.core.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreflightAndOutlineCommandTest {

    @TempDir
    Path tmp;

    private static String[] run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new String[] {String.valueOf(code), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void preflightOhnePomIstRot() {
        String[] r = run("", "preflight", "--project", tmp.toString());
        assertEquals("3", r[0], r[2]);
        assertTrue(r[1].startsWith("Preflight rot"), r[1]);
        assertTrue(r[1].contains("Keine pom.xml"), r[1]);
        String[] md = run("", "preflight", "--project", tmp.toString(), "--markdown");
        assertTrue(md[1].contains("| ✗ | Projekt |"), md[1]);
    }

    @Test
    void preflightMitEigenemNexus() {
        String[] bad = run("", "preflight", "--project", tmp.toString(), "--nexus", "nexus.corp");
        assertEquals("2", bad[0]);
        assertTrue(bad[2].contains("nexus.corp"), bad[2]);
        String[] ok = run("", "preflight", "--project", tmp.toString(), "--nexus", "https://nexus.corp.example/maven");
        assertTrue(ok[1].contains("über den eigenen Nexus https://nexus.corp.example/maven"), ok[1]);
    }

    @Test
    void outlineAusStdin() {
        String log = String.join("\n",
                "[INFO] Building app 1.0",
                "[INFO] Downloading from central: https://repo.maven.apache.org/maven2/a.pom",
                "[INFO] Downloaded from central: https://repo.maven.apache.org/maven2/a.pom (1 kB at 2 kB/s)",
                "[INFO] --- compiler:3.13.0:compile (default-compile) @ app ---",
                "[ERROR] /w/App.java:[1,1] class, interface, enum, or record expected",
                "[INFO] BUILD FAILURE", "");
        String[] r = run(log, "outline", "-", "--context", "0");
        assertEquals("0", r[0], r[2]);
        assertTrue(r[1].contains("Erster Fehler, Zeile 5:"), r[1]);
        assertTrue(r[1].contains("gescheitert in compiler:compile (default-compile)"), r[1]);
        String[] stripped = run(log, "outline", "-", "--strip");
        assertFalse(stripped[1].contains("Downloading"), stripped[1]);
        assertTrue(stripped[1].contains("2 Download-Zeilen ausgeblendet"), stripped[1]);
    }
}
