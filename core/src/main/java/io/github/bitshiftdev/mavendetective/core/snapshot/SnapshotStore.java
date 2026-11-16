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

    /**
     * Ordner für ein Projekt; lesbarer Name plus Hash des Pfads. Der Pfad ist kanonisch (Symlinks aufgelöst, unter
     * Windows ohne Unterschied in der Schreibweise): IDE und {@code mvn.cmd} in einem klein geschriebenen Pfad landen im
     * selben Ordner. Eine Ablage unter dem früheren Schlüssel (nur absoluter Pfad) wird übernommen.
     */
    public Path projectDir(Path projectDirectory) {
        Path abs = projectDirectory.toAbsolutePath().normalize();
        Path real;
        try {
            real = abs.toRealPath();
        } catch (IOException e) {
            real = abs;
        }
        String key = real.toString();
        if (WINDOWS) {
            key = key.toLowerCase(java.util.Locale.ROOT);
        }
        Path dir = root.resolve(folderName(real) + "-" + sha256(key).substring(0, 12));
        Path legacy = root.resolve(folderName(abs) + "-" + sha256(abs.toString()).substring(0, 12));
        if (!legacy.equals(dir) && !Files.isDirectory(dir) && Files.isDirectory(legacy)) {
            try {
                Files.move(legacy, dir);
            } catch (IOException e) {
                return legacy;
            }
        }
        return dir;
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private static String folderName(Path p) {
        return p.getFileName() == null ? "root" : p.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Projektordner, in die so lange nichts geschrieben wurde, werden beim Schreiben entfernt. */
    static final long PRUNE_AFTER_MILLIS = 180L * 24 * 60 * 60 * 1000;
    private static volatile boolean pruned;

    /** Entfernt einmal je JVM verwaiste Projektordner (gelöschte oder lange nicht gebaute Projekte). */
    void pruneOldProjects(Path keep) {
        if (pruned) {
            return;
        }
        pruned = true;
        prune(keep, System.currentTimeMillis() - PRUNE_AFTER_MILLIS);
    }

    void prune(Path keep, long olderThan) {
        java.nio.file.DirectoryStream<Path> dirs = null;
        try {
            dirs = Files.newDirectoryStream(root);
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir) || dir.equals(keep) || newest(dir) >= olderThan) {
                    continue;
                }
                java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(dir);
                try {
                    for (Path f : files) {
                        String n = f.getFileName().toString();
                        if (n.endsWith(".json") || n.endsWith(".tmp")) {
                            Files.deleteIfExists(f);
                        }
                    }
                } finally {
                    files.close();
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException notEmpty) {
                    // fremde Dateien darin: stehen lassen
                }
            }
        } catch (IOException e) {
            // Aufräumen ist nur nett, nie ein Fehler
        } finally {
            if (dirs != null) {
                try {
                    dirs.close();
                } catch (IOException ignored) {
                    // nichts zu tun
                }
            }
        }
    }

    private static long newest(Path dir) throws IOException {
        long newest = Files.getLastModifiedTime(dir).toMillis();
        java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(dir);
        try {
            for (Path f : files) {
                newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
            }
        } finally {
            files.close();
        }
        return newest;
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
        Path written = write(target, snapshot);
        pruneOldProjects(target.getParent());
        return written;
    }

    static void carryOver(BuildSnapshot previous, BuildSnapshot next) {
        for (java.util.Map.Entry<String, BuildSnapshot.Module> e : previous.getModules().entrySet()) {
            BuildSnapshot.Module now = next.getModules().get(e.getKey());
            BuildSnapshot.Module old = e.getValue();
            if (now == null) {
                next.getModules().put(e.getKey(), old);
                continue;
            }
            boolean samePom = now.getEffectivePomHash() != null && now.getEffectivePomHash().equals(old.getEffectivePomHash());
            if (!samePom || !old.isDependenciesResolved()) {
                continue;
            }
            if (!now.isDependenciesResolved()) {
                now.getDependencies().clear();
                now.getDependencies().putAll(old.getDependencies());
                now.getDependencyScopes().clear();
                now.getDependencyScopes().putAll(old.getDependencyScopes());
                now.getResolvedScopes().clear();
                now.getResolvedScopes().addAll(old.getResolvedScopes());
                now.setDependenciesResolved(true);
            } else if (!now.getResolvedScopes().isEmpty()) {
                // z. B. grünes compile nach grünem verify: test-Dependencies des alten Laufs behalten
                for (java.util.Map.Entry<String, String> d : old.getDependencies().entrySet()) {
                    String scope = old.getDependencyScopes().get(d.getKey());
                    if (scope != null && !now.covers(scope) && !now.getDependencies().containsKey(d.getKey())) {
                        now.getDependencies().put(d.getKey(), d.getValue());
                        now.getDependencyScopes().put(d.getKey(), scope);
                    }
                }
                // alte Fassung ohne Scope-Angaben: nichts übernehmbar, die Scopes dieses Laufs bleiben
                now.getResolvedScopes().addAll(old.getResolvedScopes());
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
