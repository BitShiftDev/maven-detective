package io.github.bitshiftdev.mavendetective.core.cli;

import io.github.bitshiftdev.mavendetective.core.deps.PomFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCommandTest {

    @TempDir
    Path tmp;
    Path repo;
    Path project;

    private void artifact(Path base, String g, String a, String v, String deps) throws Exception {
        Path dir = base.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(a + "-" + v + ".pom"), "<project><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><dependencies>" + deps + "</dependencies></project>");
    }

    private static String dep(String g, String a, String v) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version></dependency>";
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = tmp.resolve("repo");
        project = tmp.resolve("project");
        artifact(repo, "org.lib", "lib", "1.0", dep("org.a", "a", "1.0"));
        artifact(repo, "org.lib", "lib", "0.9", dep("org.a", "a", "0.9"));
        artifact(repo, "org.a", "a", "0.9", "");
        artifact(repo, "org.a", "a", "1.0", "");
        artifact(repo, "org.a", "a", "1.5", "");
        artifact(repo, "org.x", "x", "1.0", dep("org.a", "a", "1.5"));
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.lib</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency><groupId>org.x</groupId><artifactId>x</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
    }

    private String run(int expectedCode, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(new byte[0]));
        assertEquals(expectedCode, code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void downgradeIsSimulatedWithExitCode3AndPomStaysUntouched() throws Exception {
        String before = Files.readString(project.resolve("pom.xml"));
        String text = run(3, "upgrade", "org.lib:lib:0.9", "--project", project.toString(), "--local-repo", repo.toString());
        assertTrue(text.startsWith("Upgrade-Simulation: org.lib:lib 1.0 → 0.9 (POMs unverändert)"), text);
        assertTrue(text.contains("Dependency lib: Version von 1.0 auf 0.9 geändert  → " + project.resolve("pom.xml") + ":7"), text);
        assertTrue(text.contains("↓ org.lib:lib sinkt 1.0 → 0.9 (direkt)"), text);
        assertTrue(text.contains("! Konflikt org.a:a: org.x:x:1.0 verlangt 1.5, im Classpath landet 0.9"), text);
        assertEquals(before, Files.readString(project.resolve("pom.xml")));
        assertTrue(run(3, "upgrade", "org.lib:lib:0.9", "--project", project.toString(), "--local-repo", repo.toString(), "--markdown")
                .startsWith("### Upgrade-Simulation: org.lib:lib 1.0 → 0.9"));
    }

    @Test
    void applyWritesTheChange() throws Exception {
        run(0, "upgrade", "org.lib:lib:0.9", "--project", project.toString(), "--local-repo", repo.toString(), "--apply");
        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<version>0.9</version>"));
    }

    @Test
    void manageAddsDependencyManagementButDirectVersionWins() {
        String text = run(0, "upgrade", "org.a:a:1.5", "--manage", "--project", project.toString(), "--local-repo", repo.toString());
        assertTrue(text.contains("<dependencyManagement> mit org.a:a:1.5 angelegt"), text);
        assertTrue(text.contains("↑ org.a:a 1.0 → 1.5 über org.lib:lib:1.0 → org.a:a:1.5"), text);
    }

    @Test
    void wrongSpecAndUnknownArtifact() {
        run(2, "upgrade", "org.lib:lib", "--project", project.toString());
        String text = run(1, "upgrade", "org.nope:nope:1.0", "--project", project.toString(), "--local-repo", repo.toString());
        assertTrue(text.contains("kommt in keinem Modul vor"), text);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void fetchRunsMavenForMissingPomsAndSimulatesAgain() throws Exception {
        Path remote = tmp.resolve("remote");
        artifact(remote, "org.lib", "lib", "2.0", dep("org.a", "a", "2.0"));
        artifact(remote, "org.a", "a", "2.0", "");
        Path log = tmp.resolve("args.txt");
        // Stellvertreter für ./mvnw: merkt sich die Argumente und „lädt“ aus remote/
        Path mvnw = project.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho \"$@\" > '" + log + "'\ncp -r '" + remote + "'/. '" + repo + "'/\necho '[INFO] BUILD SUCCESS'\n");
        assertTrue(mvnw.toFile().setExecutable(true));

        String text = run(0, "upgrade", "org.lib:lib:2.0", "--fetch", "--project", project.toString(), "--local-repo", repo.toString());
        assertTrue(text.contains("↑ org.a:a 1.0 → 2.0 über org.lib:lib:2.0 → org.a:a:2.0"), text);
        assertTrue(!text.contains("Unvollständig"), text);
        String args = Files.readString(log);
        assertTrue(args.startsWith("-B -f "), args);
        assertTrue(args.contains("-Dmaven.repo.local=" + repo), args);
        assertTrue(args.trim().endsWith(PomFetcher.GOAL), args);
    }
}
