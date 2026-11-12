package io.github.bitshiftdev.mavendetective.core.cli;

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

class WhyCommandTest {

    @TempDir
    Path tmp;

    @Test
    void explainsFromTreeOnStdin() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <properties><guava.version>33.3.1-jre</guava.version></properties>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                  </dependencies>
                </project>
                """);
        String tree = """
                [INFO] com.acme:app:jar:1.0
                [INFO] \\- com.google.guava:guava:jar:33.3.1-jre:compile
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"why", "com.google.guava:guava", "--project", tmp.toString(),
                        "--tree", "-", "--local-repo", tmp.resolve("repo").toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(tree.getBytes(StandardCharsets.UTF_8)));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(text.startsWith("Warum com.google.guava:guava 33.3.1-jre in com.acme:app:1.0?"), text);
        assertTrue(text.contains("${guava.version} = 33.3.1-jre gesetzt in app (pom.xml)"), text);
        assertTrue(text.contains("pom.xml:3"), text);
    }
}
