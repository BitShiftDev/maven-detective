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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixCommandTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                </project>
                """);
        for (String[] m : new String[][] {{"a", "33.3.1-jre"}, {"b", "32.1.3-jre"}}) {
            Files.createDirectories(tmp.resolve(m[0]));
            Files.writeString(tmp.resolve(m[0]).resolve("pom.xml"), """
                    <project>
                      <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                      <artifactId>%s</artifactId>
                      <dependencies>
                        <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>%s</version></dependency>
                      </dependencies>
                    </project>
                    """.formatted(m[0], m[1]));
        }
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
    void listsDivergentVersionsWithoutTree() {
        String text = run(0, "matrix", "--project", tmp.toString(), "--local-repo", tmp.resolve("repo").toString());
        assertTrue(text.contains("com.google.guava:guava  – 2 Versionen"), text);
        assertTrue(text.contains("32.1.3-jre: b (direkt angegeben)"), text);
        assertTrue(text.contains("Vorschlag: 33.3.1-jre in parent"), text);
        assertTrue(text.contains("nur direkte Dependencies"), text);
    }

    @Test
    void pinWritesParent() throws Exception {
        String text = run(0, "matrix", "--project", tmp.toString(), "--local-repo", tmp.resolve("repo").toString(),
                "--pin", "com.google.guava:guava");
        assertTrue(text.contains("pom.xml:6: <dependencyManagement> mit com.google.guava:guava:33.3.1-jre angelegt"), text);
        assertTrue(text.contains("! Direkte Angabe <version>32.1.3-jre</version> in b"), text);
        assertTrue(Files.readString(tmp.resolve("pom.xml")).contains("<version>33.3.1-jre</version>"));
        run(1, "matrix", "--project", tmp.toString(), "--pin", "org.none:none");
    }
}
