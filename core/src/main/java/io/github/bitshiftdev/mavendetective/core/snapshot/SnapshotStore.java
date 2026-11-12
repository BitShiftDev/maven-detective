package io.github.bitshiftdev.mavendetective.core.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ablage der Schnappschüsse außerhalb des Projekts, damit nichts im Git-Status auftaucht:
 * {@code ~/.maven-detective/snapshots/<projektname>-<hash>/green-<origin>.json} (letzter grüner Build)
 * und {@code latest-<origin>.json} (letzter Lauf, auch rot).
 *
 * <p>Pro Projekt und Herkunft (IDE, Extension) gibt es genau einen grünen Schnappschuss. Beim
 * Vergleich wird der der eigenen Herkunft bevorzugt, sonst der jeweils andere genommen.
 * Der Basisordner lässt sich mit {@code -DmavenDetective.home=…} oder der Umgebungsvariable
 * {@code MAVEN_DETECTIVE_HOME} verlegen.
 */
public final class SnapshotStore {

    public static final String PROP_HOME = "mavenDetective.home";
    public static final String ENV_HOME = "MAVEN_DETECTIVE_HOME";

    private final Path root;

    public SnapshotStore(Path root) {
        this.root = root;
    }

    /** Standardablage: Property, Umgebungsvariable oder {@code ~/.maven-detective}. */
    public static SnapshotStore defaultStore() {
        String home = System.getProperty(PROP_HOME);
        if (home == null || home.trim().isEmpty()) {
            home = System.getenv(ENV_HOME);
        }
        Path base = home != null && !home.trim().isEmpty() ? Paths.get(home.trim())
                : Paths.get(System.getProperty("user.home"), ".maven-detective");
        return new SnapshotStore(base.resolve("snapshots"));
    }

    public Path getRoot() {
        return root;
    }

    /** Ordner für ein Projekt; lesbarer Name plus Hash des absoluten Pfads. */
    public Path projectDir(Path projectDirectory) {
        Path abs = projectDirectory.toAbsolutePath().normalize();
        String name = abs.getFileName() == null ? "root" : abs.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(name + "-" + sha256(abs.toString()).substring(0, 12));
    }

    public Path greenFile(Path projectDirectory, String origin) {
        return projectDir(projectDirectory).resolve("green-" + origin + ".json");
    }

    public Path latestFile(Path projectDirectory, String origin) {
        return projectDir(projectDirectory).resolve("latest-" + origin + ".json");
    }

    /**
     * Speichert den Schnappschuss eines grünen Builds (atomar ersetzt). Ein kurzer grüner Lauf soll
     * die Referenz nicht ausdünnen: Module, die er nicht gebaut hat ({@code -pl}), und transitiv
     * aufgelöste Dependencies bei unveränderter Effective POM ({@code validate}) werden aus dem
     * vorherigen grünen Schnappschuss übernommen.
     */
    public Path saveGreen(Path projectDirectory, BuildSnapshot snapshot) throws IOException {
        Path target = greenFile(projectDirectory, origin(snapshot));
        BuildSnapshot previous = read(target);
        if (previous != null) {
            carryOver(previous, snapshot);
        }
        return write(target, snapshot);
    }

    static void carryOver(BuildSnapshot previous, BuildSnapshot next) {
        for (java.util.Map.Entry<String, BuildSnapshot.Module> e : previous.getModules().entrySet()) {
            BuildSnapshot.Module now = next.getModules().get(e.getKey());
            BuildSnapshot.Module old = e.getValue();
            if (now == null) {
                next.getModules().put(e.getKey(), old);
            } else if (!now.isDependenciesResolved() && old.isDependenciesResolved()
                    && now.getEffectivePomHash() != null && now.getEffectivePomHash().equals(old.getEffectivePomHash())) {
                now.getDependencies().clear();
                now.getDependencies().putAll(old.getDependencies());
                now.setDependenciesResolved(true);
            }
        }
    }

    /** Speichert den Schnappschuss des letzten Laufs, egal ob grün oder rot. */
    public Path saveLatest(Path projectDirectory, BuildSnapshot snapshot) throws IOException {
        return write(latestFile(projectDirectory, origin(snapshot)), snapshot);
    }

    /** Letzter Lauf derselben Herkunft oder {@code null}. */
    public BuildSnapshot loadLatest(Path projectDirectory, String origin) {
        return read(latestFile(projectDirectory, origin));
    }

    /** Jüngster letzter Lauf über alle Herkünfte oder {@code null}. */
    public BuildSnapshot loadLatest(Path projectDirectory) {
        BuildSnapshot a = loadLatest(projectDirectory, BuildSnapshot.ORIGIN_EXTENSION);
        BuildSnapshot b = loadLatest(projectDirectory, BuildSnapshot.ORIGIN_IDE);
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        return String.valueOf(a.getCreatedAt()).compareTo(String.valueOf(b.getCreatedAt())) >= 0 ? a : b;
    }

    /**
     * Vergleich „letzter grüner Build gegen letzten Lauf“, wenn der letzte Lauf rot war;
     * sonst {@code null} (dann gibt es nichts zu erklären).
     */
    public SnapshotDiff changesSinceGreen(Path projectDirectory) {
        BuildSnapshot latest = loadLatest(projectDirectory);
        if (latest == null || !Boolean.FALSE.equals(latest.getSuccessful())) {
            return null;
        }
        BuildSnapshot green = loadGreen(projectDirectory, latest.getOrigin());
        return green == null ? null : SnapshotDiff.compare(green, latest);
    }

    private static String origin(BuildSnapshot s) {
        return s.getOrigin() == null ? "unknown" : s.getOrigin();
    }

    private static Path write(Path target, BuildSnapshot snapshot) throws IOException {
        if (snapshot.getCreatedAt() == null) {
            snapshot.setCreatedAt(now());
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
        try {
            Files.write(tmp, snapshot.toJson().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * Letzter grüner Schnappschuss: bevorzugt aus derselben Herkunft, sonst der jüngste andere.
     * {@code null}, wenn es noch keinen gibt oder die Datei unlesbar ist.
     */
    public BuildSnapshot loadGreen(Path projectDirectory, String preferredOrigin) {
        BuildSnapshot preferred = read(greenFile(projectDirectory, preferredOrigin));
        if (preferred != null) {
            return preferred;
        }
        BuildSnapshot best = null;
        for (String origin : new String[] {BuildSnapshot.ORIGIN_EXTENSION, BuildSnapshot.ORIGIN_IDE}) {
            if (origin.equals(preferredOrigin)) {
                continue;
            }
            BuildSnapshot s = read(greenFile(projectDirectory, origin));
            if (s != null && (best == null || String.valueOf(s.getCreatedAt()).compareTo(String.valueOf(best.getCreatedAt())) > 0)) {
                best = s;
            }
        }
        return best;
    }

    private static BuildSnapshot read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return BuildSnapshot.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Lokaler Zeitstempel mit Versatz, z. B. 2026-11-12T08:14:03.512+01:00. */
    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(new Date());
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
