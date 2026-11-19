package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link PomCache}: gültig, solange Änderungszeit und Größe gleich sind – aber nie für eine eben geänderte Datei. */
class PomCacheTest {

    @TempDir
    Path tmp;

    Path pom(String version, FileTime modified) throws Exception {
        Path file = tmp.resolve("pom.xml");
        Files.writeString(file, TestPom.of("org.app", "app", version).xml());
        Files.setLastModifiedTime(file, modified);
        return file;
    }

    @Test
    void alteDateiKommtAusDemCache() throws Exception {
        FileTime hourAgo = FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        Path file = pom("1.0", hourAgo);
        PomCache cache = new PomCache();
        PomFile first = cache.load(file);
        assertSame(first, cache.load(file));
    }

    @Test
    void aenderungMitGleicherGroesseUndZeitWirdErkannt() throws Exception {
        // Dateisystem mit Sekunden-Auflösung: zweimal gespeichert in derselben Sekunde, gleiche Länge
        FileTime now = FileTime.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Path file = pom("1.0", now);
        PomCache cache = new PomCache();
        assertEquals("1.0", cache.load(file).getVersion());
        pom("2.0", now);
        assertEquals("2.0", cache.load(file).getVersion());
    }

    @Test
    void groessenaenderungWirdErkannt() throws Exception {
        FileTime hourAgo = FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        Path file = pom("1.0", hourAgo);
        PomCache cache = new PomCache();
        cache.load(file);
        pom("1.0.1", hourAgo);
        assertEquals("1.0.1", cache.load(file).getVersion());
    }

    @Test
    void geloeschteOderKaputteDatei() throws Exception {
        FileTime hourAgo = FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS));
        Path file = pom("1.0", hourAgo);
        PomCache cache = new PomCache();
        cache.load(file);
        Files.delete(file);
        assertNull(cache.load(file));
        Files.writeString(file, "<project><broken");
        assertNull(cache.load(file));
    }

    @Test
    void sichtenFolgenDerselbenRegel() throws Exception {
        FileTime now = FileTime.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Path file = pom("1.0", now);
        PomCache cache = new PomCache();
        AtomicInteger reads = new AtomicInteger();
        PomCache.Reader<String> reader = f -> {
            reads.incrementAndGet();
            return PomFile.read(f).getVersion();
        };
        assertEquals("1.0", cache.view(file, reader));
        pom("2.0", now);
        assertEquals("2.0", cache.view(file, reader));

        FileTime hourAgo = FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS));
        Files.setLastModifiedTime(file, hourAgo);
        cache.view(file, reader);
        int before = reads.get();
        assertEquals("2.0", cache.view(file, reader));
        assertEquals(before, reads.get(), "alte Datei: aus dem Cache");
    }
}
