package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomFetcherTest {

    @TempDir
    Path tmp;

    @Test
    void onlyCoordinatesAreFetched() {
        assertEquals(List.of("org.a:a:1.0", "org.bom:bom:2.0", "org.p:parent:3"), PomFetcher.coordinates(List.of(
                "org.a:a:1.0",
                "org.bom:bom:2.0 (BOM für org.x:x)",
                "org.a:a:1.0",
                "Version von org.b:b in app nicht bestimmbar",
                "org.c:c (Version in org.d:d:1.0 nicht bestimmbar)",
                "org.e:e:${e.version}",
                "org.p:parent:3")));
    }

    @Test
    void fetchPomCarriesProjectRepositories() throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <repositories>
                    <repository><id>corp</id><url>https://repo.example.com/maven</url></repository>
                    <repository><id>tpl</id><url>${repo.url}</url></repository>
                  </repositories>
                  <profiles><profile><repositories>
                    <repository><id>corp</id><url>https://other.example.com</url></repository>
                  </repositories></profile></profiles>
                </project>
                """);
        List<String> repos = PomFetcher.repositories(List.of(pom));
        assertEquals(List.of("<repository><id>corp</id><url>https://repo.example.com/maven</url></repository>"), repos);
        String xml = PomFetcher.fetchPom(List.of("org.a:a:1.0"), repos);
        assertTrue(xml.contains("<artifactId>a</artifactId><version>1.0</version><type>pom</type>"), xml);
        assertTrue(xml.contains("https://repo.example.com/maven"), xml);
        PomFile.parse(tmp.resolve("x.xml"), xml);   // gültiges XML
    }

    @Test
    void prefersTheWrapper() throws Exception {
        assertEquals(System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn", PomFetcher.executable(tmp, null));
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Path mvnw = tmp.resolve("mvnw");
            Files.writeString(mvnw, "#!/bin/sh\n");
            assertTrue(mvnw.toFile().setExecutable(true));
            assertEquals(mvnw.toString(), PomFetcher.executable(tmp, null));
        }
    }
}
