package io.github.bitshiftdev.mavendetective.core.deps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gelesene POMs über mehrere {@link PomLocator} hinweg – für die IDE, die bei jeder Prüfung einen
 * neuen Locator baut. Ein Eintrag gilt, solange Änderungszeit und Größe der Datei gleich sind.
 * Thread-sicher; {@link PomFile} wird nach dem Lesen nicht mehr verändert.
 */
public final class PomCache {

    private static final int MAX_ENTRIES = 20000;

    private static final class Entry {
        final long modified;
        final long size;
        final PomFile pom;

        Entry(long modified, long size, PomFile pom) {
            this.modified = modified;
            this.size = size;
            this.pom = pom;
        }
    }

    private final Map<Path, Entry> entries = new ConcurrentHashMap<Path, Entry>();

    /** Gecachte oder frisch gelesene POM; {@code null}, wenn sie fehlt oder kaputt ist. */
    PomFile load(Path file) {
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException e) {
            entries.remove(file);
            return null;
        }
        Entry e = entries.get(file);
        if (e != null && e.modified == modified && e.size == size) {
            return e.pom;
        }
        PomFile pom;
        try {
            pom = PomFile.read(file);
        } catch (Exception ex) {
            pom = null;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(file, new Entry(modified, size, pom));
        return pom;
    }
}
