package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verzeichnisse ({@code target/classes}) werden gecacht, Änderungen aber bemerkt. */
class JarIndexTest {

    @TempDir
    Path tmp;

    private Path classFile(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return f;
    }

    @Test
    void verzeichnisWirdNurBeiAenderungNeuGelesen() throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path a = classFile(classes, "org.x.A", "a");
        classFile(classes, "org.x.B", "b");
        JarIndex index = new JarIndex();

        JarIndex.Contents first = index.read(classes);
        assertEquals(List.of("org.x.A", "org.x.B"), JarIndex.names(first));
        assertSame(first, index.read(classes), "unverändert: derselbe Inhalt aus dem Cache");

        long crcBefore = first.crc("org.x.A");
        Files.writeString(a, "geändert");
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5000));
        JarIndex.Contents changed = index.read(classes);
        assertNotEquals(crcBefore, changed.crc("org.x.A"));

        classFile(classes, "org.x.C", "c");
        assertTrue(index.read(classes).contains("org.x.C"));
    }

    @Test
    void sichtJeLaufLiestJedesVerzeichnisEinmal() throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        classFile(classes, "org.x.A", "a");
        JarIndex run = new JarIndex().forRun();
        JarIndex.Contents once = run.read(classes);
        classFile(classes, "org.x.B", "b");
        assertSame(once, run.read(classes), "innerhalb eines Laufs zählt der erste Stand");
    }

    @Test
    void hatKlassenEndetBeimErstenFund() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty/META-INF"));
        Files.writeString(empty.resolve("MANIFEST.MF"), "x");
        assertFalse(new JarIndex().hasClasses(tmp.resolve("empty")));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        classFile(classes, "org.x.A", "a");
        assertTrue(new JarIndex().hasClasses(classes));
    }
}
