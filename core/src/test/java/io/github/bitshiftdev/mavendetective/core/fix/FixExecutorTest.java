package io.github.bitshiftdev.mavendetective.core.fix;

import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixExecutorTest {

    @Test
    void deletesOnlyInsideAllowedRoot() throws Exception {
        Path repo = Files.createTempDirectory("repo");
        Path inside = Files.createFile(repo.resolve("a.jar.lastUpdated"));
        Path outside = Files.createTempFile("outside", ".txt");
        FixExecutor ex = new FixExecutor(Collections.singletonList(repo));

        FixExecutor.Outcome o = ex.execute(FixAction.of(FixAction.DELETE_FILES, "x", "files", inside.toString(), "args", "-U"));
        assertFalse(Files.exists(inside));
        assertEquals("-U", o.rebuildArgs);

        assertThrows(IOException.class, () -> ex.execute(FixAction.of(FixAction.DELETE_FILES, "x", "files", outside.toString())));
        assertThrows(IOException.class, () -> ex.execute(FixAction.of(FixAction.DELETE_FILES, "x", "files", repo + "/../" + outside.getFileName())));
        assertTrue(Files.exists(outside));
    }

    @Test
    void replacesTextInFile() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        Files.write(pom, "<url>http://repo.example.org/m2</url>".getBytes(StandardCharsets.UTF_8));
        new FixExecutor(Collections.emptyList()).execute(FixAction.of(FixAction.REPLACE_IN_FILE, "x",
                "file", pom.toString(), "search", "http://repo.example.org/m2", "replace", "https://repo.example.org/m2"));
        assertEquals("<url>https://repo.example.org/m2</url>", new String(Files.readAllBytes(pom), StandardCharsets.UTF_8));
    }

    @Test
    void jvmOptionReplacesExistingValue() throws Exception {
        Path dir = Files.createTempDirectory("proj");
        Path config = dir.resolve(".mvn/jvm.config");
        FixExecutor ex = new FixExecutor(Collections.emptyList());
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-Xmx1g"));
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-XX:MaxMetaspaceSize=512m"));
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-Xmx2g"));
        assertEquals("-XX:MaxMetaspaceSize=512m -Xmx2g\n", new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
    }
}
