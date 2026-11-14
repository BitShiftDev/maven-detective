package io.github.bitshiftdev.mavendetective.core.fix;

import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        Path config = Files.createTempFile("config", ".txt");
        Files.write(config, "repo=http://repo.example.org/m2".getBytes(StandardCharsets.UTF_8));
        new FixExecutor(Collections.emptyList()).execute(FixAction.of(FixAction.REPLACE_IN_FILE, "x",
                "file", config.toString(), "search", "http://repo.example.org/m2", "replace", "https://repo.example.org/m2"));
        assertEquals("repo=https://repo.example.org/m2", new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
    }

    @Test
    void jvmOptionReplacesExistingValue() throws Exception {
        Path dir = Files.createTempDirectory("proj");
        Path config = dir.resolve(".mvn/jvm.config");
        FixExecutor ex = new FixExecutor(Collections.emptyList());
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-Xmx1g"));
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-XX:MaxMetaspaceSize=512m"));
        ex.execute(FixAction.of(FixAction.SET_JVM_OPTION, "x", "file", config.toString(), "option", "-Xmx2g"));
        assertEquals("-Xmx2g\n-XX:MaxMetaspaceSize=512m\n", new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
    }

    @Test
    void jvmOptionKeepsLinesAndComments() {
        assertEquals("# Speicher für große Builds\n-Xmx2g\n-Dfile.encoding=UTF-8\n",
                FixExecutor.setJvmOption("# Speicher für große Builds\n-Xmx1g\n-Dfile.encoding=UTF-8", "-Xmx2g"));
        assertEquals("# Kommentar\r\n-Xss4m -Xmx2g\r\n\r\n-XX:MaxMetaspaceSize=512m\r\n",
                FixExecutor.setJvmOption("# Kommentar\r\n-Xss4m -Xmx2g\r\n\r\n", "-XX:MaxMetaspaceSize=512m"));
        // doppelte Angabe: die erste wird ersetzt, die zweite (samt nun leerer Zeile) entfällt
        assertEquals("-Xmx3g -Xss4m\n# Ende\n", FixExecutor.setJvmOption("-Xmx1g -Xss4m\n  -Xmx2g\n# Ende\n", "-Xmx3g"));
    }

    @Test
    void keepsLatin1EncodingOfPom() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<project>\n  <name>Größenprüfung Müller</name>\n"
                + "  <dependencies>\n    <dependency>\n      <groupId>g</groupId>\n      <artifactId>a</artifactId>\n"
                + "      <version>1.0</version>\n    </dependency>\n  </dependencies>\n</project>\n";
        Files.write(pom, xml.getBytes(StandardCharsets.ISO_8859_1));
        new FixExecutor(Collections.emptyList()).execute(FixAction.of(FixAction.SET_VERSION, "x",
                "file", pom.toString(), "artifact", "g:a", "section", "dependencies", "version", "2.0"));
        assertEquals(xml.replace("1.0</version>", "2.0</version>"), new String(Files.readAllBytes(pom), StandardCharsets.ISO_8859_1));
    }

    @Test
    void keepsBomAndRefusesUndecodableFile() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        byte[] body = "<project><properties><a>1</a><n>ä</n></properties></project>".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(pom, withBom);
        FixExecutor ex = new FixExecutor(Collections.emptyList());
        ex.execute(FixAction.of(FixAction.SET_VERSION, "x", "file", pom.toString(), "property", "a", "version", "2"));
        byte[] after = Files.readAllBytes(pom);
        assertEquals((byte) 0xEF, after[0]);
        assertEquals("<project><properties><a>2</a><n>ä</n></properties></project>",
                new String(after, 3, after.length - 3, StandardCharsets.UTF_8));

        // als UTF-8 erklärt, aber Latin-1-Bytes: nicht anfassen statt Ersatzzeichen schreiben
        Path broken = Files.createTempFile("pom", ".xml");
        byte[] latin = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><project><properties><a>1</a><n>ü</n></properties></project>"
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(broken, latin);
        assertThrows(IOException.class, () -> ex.execute(FixAction.of(FixAction.SET_VERSION, "x",
                "file", broken.toString(), "property", "a", "version", "2")));
        assertArrayEquals(latin, Files.readAllBytes(broken));
    }

    @Test
    void settingsUrlReplacedExactlyAndInOwnEncoding() throws Exception {
        Path settings = Files.createTempFile("settings", ".xml");
        String xml = "<settings>\n  <!-- Zugang über Müllers http://nexus.corp/repo -->\n  <mirrors>\n    <mirror>\n"
                + "      <url>http://nexus.corp/repo</url>\n    </mirror>\n    <mirror>\n"
                + "      <url>http://nexus.corp/repository/other</url>\n    </mirror>\n  </mirrors>\n</settings>\n";
        Files.write(settings, xml.getBytes(StandardCharsets.ISO_8859_1));
        new FixExecutor(Collections.emptyList()).execute(FixAction.of(FixAction.REPLACE_IN_FILE, "x",
                "file", settings.toString(), "search", "http://nexus.corp/repo", "replace", "https://nexus.corp/repo"));
        assertEquals(xml.replace("<url>http://nexus.corp/repo</url>", "<url>https://nexus.corp/repo</url>"),
                new String(Files.readAllBytes(settings), StandardCharsets.ISO_8859_1));
    }

    @Test
    void replaceUrlInPom() throws Exception {
        Path pom = Files.createTempFile("pom", ".xml");
        String xml = "<project><repositories><repository><url>http://r.example/m2</url></repository></repositories></project>";
        Files.write(pom, xml.getBytes(StandardCharsets.UTF_8));
        assertTrue(FixExecutor.canExecute(FixAction.of(FixAction.REPLACE_URL, "x")));
        new FixExecutor(Collections.emptyList()).execute(FixAction.of(FixAction.REPLACE_URL, "x",
                "file", pom.toString(), "search", "http://r.example/m2", "replace", "https://r.example/m2"));
        assertEquals(xml.replace("http:", "https:"), new String(Files.readAllBytes(pom), StandardCharsets.UTF_8));
    }
}
