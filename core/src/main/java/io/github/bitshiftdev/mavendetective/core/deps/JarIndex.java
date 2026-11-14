package io.github.bitshiftdev.mavendetective.core.deps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Klassen eines JARs oder eines {@code target/classes}-Verzeichnisses samt CRC32 je Klasse – für
 * Modul A3 (doppelte Klassen). Bei JARs kommt die Prüfsumme aus dem Zentralverzeichnis, die
 * Klassen werden also nicht entpackt. Ausgelassen werden innere Klassen (sie kommen immer mit der
 * äußeren), {@code module-info}, {@code package-info} und alles unter {@code META-INF/} (auch die
 * Multi-Release-Varianten).
 *
 * <p>Thread-sicher. JARs werden nach Änderungszeit und Größe gecacht, damit die IDE nicht bei jedem
 * Aktualisieren hunderte Archive neu öffnet. Verzeichnisse ({@code target/classes} von Reactor-Modulen)
 * werden bei jedem Aufruf nur durchlaufen; geöffnet werden allein Klassendateien, deren Zeit oder Größe
 * sich geändert hat. Eine Analyse über viele Module nimmt {@link #forRun()}: dort wird jedes Verzeichnis
 * nur einmal durchlaufen.
 */
public final class JarIndex {

    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_DIR_CLASSES = 100000;

    /** Klassen eines Classpath-Eintrags, sortiert nach Name. */
    public static final class Contents {
        private final String[] names;
        private final long[] crcs;
        private final List<String> services;

        private Contents(String[] names, long[] crcs, List<String> services) {
            this.names = names;
            this.crcs = crcs;
            this.services = services;
        }

        /**
         * Registrierungen, über die der Eintrag ohne Nennung im Code wirkt (Modul A5):
         * {@code META-INF/services/*}, {@code META-INF/spring.factories},
         * {@code META-INF/spring/*.imports}, Web-Fragmente u. Ä. – als Pfad im Archiv.
         */
        public List<String> getServices() { return services; }

        /** Anzahl der (äußeren) Klassen. */
        public int size() { return names.length; }

        /** Klassenname mit Punkten, z. B. {@code org.apache.commons.logging.Log}. */
        public String name(int i) { return names[i]; }

        public long crc(int i) { return crcs[i]; }

        /** CRC32 der Klasse oder {@code -1}, wenn sie fehlt. */
        public long crc(String className) {
            int i = Arrays.binarySearch(names, className);
            return i < 0 ? -1 : crcs[i];
        }

        public boolean contains(String className) {
            return Arrays.binarySearch(names, className) >= 0;
        }

        static Contents of(List<String> names, List<Long> crcs) {
            return of(names, crcs, Collections.<String>emptyList());
        }

        static Contents of(List<String> names, List<Long> crcs, List<String> services) {
            Integer[] order = new Integer[names.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            final List<String> n = names;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return n.get(a).compareTo(n.get(b));
                }
            });
            List<String> outNames = new ArrayList<String>();
            List<Long> outCrcs = new ArrayList<Long>();
            for (Integer i : order) {
                String name = names.get(i);
                if (!outNames.isEmpty() && outNames.get(outNames.size() - 1).equals(name)) {
                    continue; // doppelter Zip-Eintrag: zählt einmal
                }
                outNames.add(name);
                outCrcs.add(crcs.get(i));
            }
            String[] a = outNames.toArray(new String[0]);
            long[] c = new long[outCrcs.size()];
            for (int i = 0; i < c.length; i++) {
                c[i] = outCrcs.get(i);
            }
            return new Contents(a, c, Collections.unmodifiableList(new ArrayList<String>(services)));
        }
    }

    private static final class Entry {
        final long modified;
        final long size;
        final Contents contents;

        Entry(long modified, long size, Contents contents) {
            this.modified = modified;
            this.size = size;
            this.contents = contents;
        }
    }

    /** Ein gelesenes Verzeichnis mit Zeit, Größe und CRC je Klassendatei (relativer Pfad). */
    private static final class DirEntry {
        final Map<String, long[]> files;
        final Contents contents;

        DirEntry(Map<String, long[]> files, Contents contents) {
            this.files = files;
            this.contents = contents;
        }
    }

    private final Map<Path, Entry> entries;
    private final Map<Path, DirEntry> dirs;
    /** Nur in {@link #forRun()}: Verzeichnisse, die dieser Lauf schon gelesen hat. */
    private final Map<Path, Contents> run;

    public JarIndex() {
        this(new ConcurrentHashMap<Path, Entry>(), new ConcurrentHashMap<Path, DirEntry>(), null);
    }

    private JarIndex(Map<Path, Entry> entries, Map<Path, DirEntry> dirs, Map<Path, Contents> run) {
        this.entries = entries;
        this.dirs = dirs;
        this.run = run;
    }

    /**
     * Sicht für eine Analyse über viele Module (A3, A5): teilt die Caches, liest aber jedes Verzeichnis nur
     * einmal – sonst würde jedes Modul das {@code target/classes} jedes Reactor-Moduls, von dem es abhängt,
     * erneut durchlaufen. Änderungen während des Laufs sieht die Sicht nicht.
     */
    public JarIndex forRun() {
        return new JarIndex(entries, dirs, new ConcurrentHashMap<Path, Contents>());
    }

    /**
     * Enthält der Eintrag überhaupt eine Klasse? Bei Verzeichnissen endet die Suche bei der ersten Klassendatei
     * (für die Live-Inspection, die nur wissen will, ob ein Starter Klassen mitbringt).
     */
    public boolean hasClasses(Path fileOrDirectory) throws IOException {
        Path p = fileOrDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            return read(p).size() > 0;
        }
        Contents known = run != null ? run.get(p) : null;
        if (known != null) {
            return known.size() > 0;
        }
        final Path dir = p;
        final boolean[] found = {false};
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (className(dir.relativize(file).toString().replace('\\', '/')) != null) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /**
     * Klassen eines JARs oder Verzeichnisses.
     *
     * @throws IOException wenn die Datei fehlt oder kein lesbares ZIP ist
     */
    public Contents read(Path fileOrDirectory) throws IOException {
        Path p = fileOrDirectory.toAbsolutePath().normalize();
        if (Files.isDirectory(p)) {
            Contents known = run != null ? run.get(p) : null;
            if (known == null) {
                known = readDirectory(p);
                if (run != null) {
                    run.put(p, known);
                }
            }
            return known;
        }
        long modified = Files.getLastModifiedTime(p).toMillis();
        long size = Files.size(p);
        Entry e = entries.get(p);
        if (e != null && e.modified == modified && e.size == size) {
            return e.contents;
        }
        Contents c = readJar(p);
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(p, new Entry(modified, size, c));
        return c;
    }

    private static Contents readJar(Path jar) throws IOException {
        List<String> names = new ArrayList<String>();
        List<Long> crcs = new ArrayList<Long>();
        List<String> services = new ArrayList<String>();
        ZipFile zip = new ZipFile(jar.toFile());
        try {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory()) {
                    continue;
                }
                String className = className(z.getName());
                if (className != null) {
                    names.add(className);
                    crcs.add(z.getCrc());
                } else if (isService(z.getName())) {
                    services.add(z.getName());
                }
            }
        } finally {
            zip.close();
        }
        return Contents.of(names, crcs, services);
    }

    /** Durchläuft das Verzeichnis; nur neue oder geänderte Klassendateien werden gelesen. */
    private Contents readDirectory(final Path dir) throws IOException {
        DirEntry before = dirs.get(dir);
        final Map<String, long[]> previous = before == null ? Collections.<String, long[]>emptyMap() : before.files;
        final Map<String, long[]> files = new java.util.HashMap<String, long[]>();
        final List<String> names = new ArrayList<String>();
        final List<Long> crcs = new ArrayList<Long>();
        final List<String> services = new ArrayList<String>();
        final boolean[] changed = {false};
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                String className = className(rel);
                if (className != null) {
                    long modified = attrs.lastModifiedTime().toMillis();
                    long size = attrs.size();
                    long[] known = previous.get(rel);
                    long crc;
                    if (known != null && known[0] == modified && known[1] == size) {
                        crc = known[2];
                    } else {
                        crc = crc(file);
                        changed[0] = true;
                    }
                    files.put(rel, new long[] {modified, size, crc});
                    names.add(className);
                    crcs.add(crc);
                } else if (isService(rel)) {
                    services.add(rel);
                }
                return names.size() >= MAX_DIR_CLASSES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (before != null && !changed[0] && files.size() == previous.size()
                && services.equals(before.contents.getServices())) {
            return before.contents;
        }
        Contents c = Contents.of(names, crcs, services);
        if (dirs.size() >= MAX_ENTRIES) {
            dirs.clear();
        }
        dirs.put(dir, new DirEntry(files, c));
        return c;
    }

    /** Dateien, über die Frameworks Klassen ohne Nennung im Code laden. */
    static boolean isService(String entry) {
        return entry.startsWith("META-INF/services/") && entry.length() > "META-INF/services/".length()
                || "META-INF/spring.factories".equals(entry)
                || entry.startsWith("META-INF/spring/") && entry.endsWith(".imports")
                || "META-INF/web-fragment.xml".equals(entry)
                || "META-INF/beans.xml".equals(entry)
                || "META-INF/spring.handlers".equals(entry);
    }

    private static long crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[8192];
        InputStream in = Files.newInputStream(file);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return crc.getValue();
    }

    /** {@code org/x/Y.class} → {@code org.x.Y}; {@code null} für alles, was nicht zählt. */
    static String className(String entry) {
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
            return null;
        }
        String base = entry.substring(entry.lastIndexOf('/') + 1);
        if (base.indexOf('$') >= 0 || "module-info.class".equals(base) || "package-info.class".equals(base)) {
            return null;
        }
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }

    /** Für Tests und die Ausgabe: alle Klassennamen. */
    static List<String> names(Contents c) {
        List<String> out = new ArrayList<String>(c.size());
        for (int i = 0; i < c.size(); i++) {
            out.add(c.name(i));
        }
        return Collections.unmodifiableList(out);
    }
}
