package io.github.bitshiftdev.mavendetective.core.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionsCommandTest {

    @TempDir
    Path tmp;
    Path repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = tmp.resolve("repo");
        Path lib = repo.resolve("org/lib/lib/2.0");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("lib-2.0.pom"), """
                <project><groupId>org.lib</groupId><artifactId>lib</artifactId><version>2.0</version>
                  <dependencies><dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.16</version></dependency></dependencies>
                </project>
                """);
        Path slf4j = repo.resolve("org/slf4j/slf4j-api/2.0.16");
        Files.createDirectories(slf4j);
        Files.writeString(slf4j.resolve("slf4j-api-2.0.16.pom"), "<project><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.16</version></project>");
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.lib</groupId>
                      <artifactId>lib</artifactId>
                      <version>2.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>commons-logging</groupId>
                          <artifactId>commons-logging</artifactId>
                        </exclusion>
                        <exclusion>
                          <groupId>org.slf4j</groupId>
                          <artifactId>slf4j-api</artifactId>
                        </exclusion>
                      </exclusions>
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
    void reportsStaleExclusionWithExitCode3() {
        String text = run(3, "exclusions", "--project", tmp.toString(), "--local-repo", repo.toString());
        assertTrue(text.startsWith("Exclusion-Hygiene: 2 Exclusions in 1 POM – 1 veraltet"), text);
        assertTrue(text.contains("veraltet  " + tmp.resolve("app/pom.xml") + ":9"), text);
        assertTrue(text.contains("commons-logging:commons-logging schließt nichts aus"), text);
        assertFalse(text.contains("wirkt"), text);
        assertTrue(run(3, "exclusions", "--project", tmp.toString(), "--local-repo", repo.toString(), "--all").contains("wirkt"));
    }

    @Test
    void removeDeletesOnlyStaleOnes() throws Exception {
        String text = run(0, "exclusions", "--project", tmp.toString(), "--local-repo", repo.toString(), "--remove");
        assertTrue(text.contains("1 Exclusion entfernt."), text);
        String pom = Files.readString(tmp.resolve("app/pom.xml"));
        assertFalse(pom.contains("commons-logging"), pom);
        assertTrue(pom.contains("<artifactId>slf4j-api</artifactId>"), pom);
        run(0, "exclusions", "--project", tmp.toString(), "--local-repo", repo.toString());
    }
}
