package io.github.bitshiftdev.mavendetective.core.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lokales Maven-Repository im {@code @TempDir}: legt POMs (und leere JARs) im Layout
 * {@code g/r/o/u/p/artifact/version/artifact-version.pom} ab.
 *
 * <pre>
 * TestRepository repo = TestRepository.at(tmp.resolve("repo"));
 * repo.pom(TestPom.of("org.lib", "lib", "1.0").dependency("org.dep", "dep", "2.0"));
 * </pre>
 */
public final class TestRepository {

    private final Path root;

    private TestRepository(Path root) {
        this.root = root;
    }

    public static TestRepository at(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TestRepository(root);
    }

    public Path root() {
        return root;
    }

    /** Ordner {@code g/r/o/u/p/artifact/version}. */
    public Path dir(String g, String a, String v) {
        Path dir = root;
        for (String segment : g.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve(a).resolve(v);
    }

    /** Schreibt die POM unter ihren eigenen Koordinaten (groupId/version notfalls vom Parent). */
    public Path pom(TestPom pom) {
        return pom(pom.groupId(), pom.artifactId(), pom.version(), pom.xml());
    }

    public Path pom(String g, String a, String v, String xml) {
        return write(dir(g, a, v).resolve(a + "-" + v + ".pom"), xml);
    }

    /** Minimale POM ohne Dependencies. */
    public Path pom(String g, String a, String v) {
        return pom(TestPom.of(g, a, v));
    }

    /** Leeres Archiv {@code a-v[-classifier].ext} neben der POM (nur die Datei, kein Inhalt). */
    public Path file(String g, String a, String v, String classifier, String extension) {
        String name = a + "-" + v + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        return write(dir(g, a, v).resolve(name), "");
    }

    /**
     * JAR {@code a-v[-classifier].jar} mit diesen Klassen (binäre Namen, auch {@code a.B$Inner}). Inhalt jeder Klasse =
     * ihr Name plus {@code variant}: gleiche Variante = gleicher CRC, andere Variante = andere Fassung.
     */
    public Path jar(String g, String a, String v, String classifier, String variant, String... classNames) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (String c : classNames) {
            entries.put(c.replace('.', '/') + ".class", (c + variant).getBytes(StandardCharsets.UTF_8));
        }
        return jar(g, a, v, classifier, entries);
    }

    /** JAR mit genau diesen Einträgen (Pfad im Archiv → Inhalt), z. B. echte Klassen aus einem Compiler. */
    public Path jar(String g, String a, String v, String classifier, Map<String, byte[]> entries) {
        String name = a + "-" + v + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
        return zip(dir(g, a, v).resolve(name), entries);
    }

    /** Archiv an beliebiger Stelle (z. B. {@code target/app-1.0.jar} eines Moduls). */
    public static Path zip(Path file, Map<String, byte[]> entries) {
        try {
            Files.createDirectories(file.getParent());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    out.putNextEntry(new ZipEntry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
