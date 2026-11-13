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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicatesCommandTest {

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
                String[] nv = c.split("=", 2);
                zip.putNextEntry(new ZipEntry(nv[0].replace('.', '/') + ".class"));
                zip.write(nv[1].getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = tmp.resolve("repo");
        artifact("org.web", "web", "1.0",
                "<dependency><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId><version>1.2</version></dependency>",
                "org.web.Web=w");
        artifact("commons-logging", "commons-logging", "1.2", "", "org.apache.commons.logging.Log=orig");
        artifact("org.slf4j", "jcl-over-slf4j", "1.7.36", "", "org.apache.commons.logging.Log=bridge");
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
                      <groupId>org.slf4j</groupId>
                      <artifactId>jcl-over-slf4j</artifactId>
                      <version>1.7.36</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
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
    void reportsDifferingDuplicatesWithExitCode3() {
        String text = run(3, "duplicates", "--project", tmp.toString(), "--local-repo", repo.toString());
        assertTrue(text.startsWith("Doppelte Klassen: 1 Fund in 1 Modul"), text);
        assertTrue(text.contains("Empfehlung: commons-logging:1.2 entfernen"), text);
        assertTrue(run(3, "duplicates", "--project", tmp.toString(), "--local-repo", repo.toString(), "--markdown")
                .startsWith("### Doppelte Klassen"));
    }

    @Test
    void excludeInsertsExclusionAndClashIsGone() throws Exception {
        String text = run(0, "duplicates", "--project", tmp.toString(), "--local-repo", repo.toString(), "--exclude");
        assertTrue(text.contains("1 Exclusion eingefügt."), text);
        String pom = Files.readString(tmp.resolve("pom.xml"));
        assertTrue(pom.contains("""
                      <version>1.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>commons-logging</groupId>
                          <artifactId>commons-logging</artifactId>
                        </exclusion>
                      </exclusions>
                """), pom);
        assertTrue(run(0, "duplicates", "--project", tmp.toString(), "--local-repo", repo.toString())
                .contains("Keine Klasse liegt in mehr als einem JAR."));
    }
}
