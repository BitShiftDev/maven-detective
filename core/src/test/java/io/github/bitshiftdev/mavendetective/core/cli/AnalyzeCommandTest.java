package io.github.bitshiftdev.mavendetective.core.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzeCommandTest {

    @TempDir
    Path tmp;
    Path repo;

    private void artifact(String g, String a, String v, String deps, String... classes) throws Exception {
        Path dir = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(a + "-" + v + ".pom"), "<project><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><dependencies>" + deps + "</dependencies></project>");
        try (OutputStream os = Files.newOutputStream(dir.resolve(a + "-" + v + ".jar")); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (String c : classes) {
                zip.putNextEntry(new ZipEntry(c.replace('.', '/') + ".class"));
                zip.write(c.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = tmp.resolve("repo");
        artifact("org.web", "web", "1.0",
                "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0</version></dependency>",
                "org.web.Web");
        artifact("org.slf4j", "slf4j-api", "2.0", "", "org.slf4j.Logger");
        artifact("org.unused", "lib", "1.0", "", "org.unused.Lib");
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.web</groupId>
                      <artifactId>web</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.unused</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path src = tmp.resolve("src/main/java/com/acme/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme;\nimport org.web.Web;\nimport org.slf4j.Logger;\nclass App {}\n");
    }

    private String run(int expectedCode, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        assertEquals(expectedCode, code, err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void reportsWithExitCode3() {
        String text = run(3, "analyze", "--project", tmp.toString(), "--local-repo", repo.toString());
        assertTrue(text.startsWith("Dependency-Nutzung: 1 undeklariert, 1 ungenutzt, 0 nur in Tests"), text);
        assertTrue(text.contains("genannt: org.slf4j.Logger (App.java:3)"), text);
        assertTrue(text.contains("→ org.slf4j:slf4j-api:2.0 in app deklarieren"), text);
        assertTrue(text.contains("→ org.unused:lib aus app entfernen"), text);
        assertTrue(run(3, "analyze", "--project", tmp.toString(), "--local-repo", repo.toString(), "--markdown")
                .startsWith("### Ungenutzte und undeklarierte Dependencies"));
    }

    @Test
    void declareAndRemoveFixThePom() throws Exception {
        String text = run(0, "analyze", "--project", tmp.toString(), "--local-repo", repo.toString(), "--declare", "--remove");
        assertTrue(text.contains("2 Änderungen."), text);
        String pom = Files.readString(tmp.resolve("pom.xml"));
        assertTrue(pom.contains("""
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                """), pom);
        assertFalse(pom.contains("org.unused"), pom);
        assertTrue(run(0, "analyze", "--project", tmp.toString(), "--local-repo", repo.toString())
                .contains("Alles deklariert, was genannt wird"));
    }
}
