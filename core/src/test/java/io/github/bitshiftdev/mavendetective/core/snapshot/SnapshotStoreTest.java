package io.github.bitshiftdev.mavendetective.core.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SnapshotStore}: Ablage, Auswahl über Herkünfte, Übernahme aus dem letzten grünen Lauf, Aufräumen. */
class SnapshotStoreTest {

    @TempDir
    Path tmp;

    static BuildSnapshot snapshot(String origin, String createdAt, Boolean successful) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(origin).setCreatedAt(createdAt).setSuccessful(successful)
                .setMavenVersion("3.9.11").setJavaVersion("21.0.4");
        s.module("g:app").setVersion("1.0").setEffectivePomHash("h1").setDependenciesResolved(true)
                .getDependencies().put("g:lib", "1.0");
        return s;
    }

    /**
     * Zeitpunkte mit Versatz zählen als Zeitpunkt, nicht als Text: Bei der Rückstellung auf Winterzeit ist 02:10+01:00
     * jünger als 02:30+02:00; ein Lauf in UTC (CI-Container, {@code -Duser.timezone}) ebenso.
     */
    @ParameterizedTest(name = "{0} jünger als {1}")
    @CsvSource({
        "2026-10-25T02:10:00.000+01:00, 2026-10-25T02:30:00.000+02:00",
        "2026-11-19T20:00:00.000Z, 2026-11-19T20:30:00.000+01:00",
        "2026-11-19T08:14:04+01:00, 2026-11-19T08:14:03.999+01:00",
        "2026-11-19T08:15:00.000+01:00, 2026-11-19T08:14:00.000+01:00",
    })
    void juengsterLetzterLaufNachZeitpunkt(String newer, String older) {
        Path project = tmp.resolve("p");
        SnapshotStore store = new SnapshotStore(tmp.resolve("store"));
        for (boolean ideNewer : new boolean[] {true, false}) {
            BuildSnapshot ide = snapshot(BuildSnapshot.ORIGIN_IDE, ideNewer ? newer : older, false);
            BuildSnapshot ext = snapshot(BuildSnapshot.ORIGIN_EXTENSION, ideNewer ? older : newer, false);
            write(store, project, ide, ext, false);
            assertEquals(ideNewer ? BuildSnapshot.ORIGIN_IDE : BuildSnapshot.ORIGIN_EXTENSION, store.loadLatest(project).getOrigin());

            write(store, project, ide, ext, true);
            String newest = ideNewer ? BuildSnapshot.ORIGIN_IDE : BuildSnapshot.ORIGIN_EXTENSION;
            assertEquals(newest, store.loadGreen(project, "unknown").getOrigin(), "ohne eigene Herkunft: der jüngste grüne");
        }
    }

    private static void write(SnapshotStore store, Path project, BuildSnapshot ide, BuildSnapshot ext, boolean green) {
        try {
            for (BuildSnapshot s : new BuildSnapshot[] {ide, ext}) {
                Path file = green ? store.greenFile(project, s.getOrigin()) : store.latestFile(project, s.getOrigin());
                Files.createDirectories(file.getParent());
                Files.write(file, s.toJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void gleichAlteLaeufeUndUnlesbareZeitenFallenAufDenTextZurueck() {
        assertTrue(SnapshotStore.newerOrSame("2026-11-19T08:00:00.000+01:00", "2026-11-19T07:00:00.000Z"), "gleicher Zeitpunkt");
        assertTrue(SnapshotStore.newerOrSame("b", "a"));
        assertFalse(SnapshotStore.newerOrSame(null, "2026-11-19T08:00:00.000+01:00"));
        assertTrue(SnapshotStore.newerOrSame("2026-11-19T08:00:00.000+01:00", null));
    }

    @Test
    void kaputteOderFehlendeDateienSindKeinSchnappschuss() throws Exception {
        Path project = tmp.resolve("p");
        SnapshotStore store = new SnapshotStore(tmp.resolve("store"));
        assertNull(store.loadLatest(project));
        assertNull(store.loadGreen(project, BuildSnapshot.ORIGIN_IDE));
        Path latest = store.latestFile(project, BuildSnapshot.ORIGIN_IDE);
        Files.createDirectories(latest.getParent());
        Files.write(latest, "{ kaputt".getBytes(StandardCharsets.UTF_8));
        assertNull(store.loadLatest(project, BuildSnapshot.ORIGIN_IDE));
        assertNull(store.changesSinceGreen(project));

        store.saveLatest(project, snapshot(BuildSnapshot.ORIGIN_EXTENSION, null, false));
        assertNull(store.changesSinceGreen(project), "roter Lauf ohne grünen: nichts zu vergleichen");
        assertTrue(store.loadLatest(project).getCreatedAt() != null, "Zeitpunkt wird beim Schreiben gesetzt");
        BuildSnapshot noOrigin = snapshot(null, null, true);
        assertTrue(store.saveLatest(project, noOrigin).getFileName().toString().endsWith("-unknown.json"));
    }

    @Test
    void geaenderteEffectivePomUebernimmtKeineAltenDependencies() {
        BuildSnapshot old = snapshot(BuildSnapshot.ORIGIN_EXTENSION, null, true);
        BuildSnapshot next = snapshot(BuildSnapshot.ORIGIN_EXTENSION, null, true);
        BuildSnapshot.Module now = next.getModules().get("g:app").setEffectivePomHash("h2").setDependenciesResolved(false);
        now.getDependencies().clear();
        SnapshotStore.carryOver(old, next);
        assertTrue(now.getDependencies().isEmpty(), "andere POM: alte Auflösung gilt nicht mehr");
        assertFalse(now.isDependenciesResolved());

        BuildSnapshot unresolvedOld = snapshot(BuildSnapshot.ORIGIN_EXTENSION, null, true);
        unresolvedOld.getModules().get("g:app").setDependenciesResolved(false);
        BuildSnapshot next2 = snapshot(BuildSnapshot.ORIGIN_EXTENSION, null, true);
        next2.getModules().get("g:app").setDependenciesResolved(false).getDependencies().clear();
        SnapshotStore.carryOver(unresolvedOld, next2);
        assertTrue(next2.getModules().get("g:app").getDependencies().isEmpty(), "alter Lauf war selbst nicht aufgelöst");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)   // Symlink
    void ablageUnterDemFrueherenSchluesselWirdUebernommen() throws Exception {
        Path real = Files.createDirectories(tmp.resolve("real-project"));
        Path link = Files.createSymbolicLink(tmp.resolve("link-project"), real);
        SnapshotStore store = new SnapshotStore(tmp.resolve("store"));
        // früher: Schlüssel aus dem absoluten Pfad, ohne Symlinks aufzulösen
        Path legacy = store.getRoot().resolve("link-project-" + SnapshotStore.sha256(link.toAbsolutePath().toString()).substring(0, 12));
        Files.createDirectories(legacy);
        Files.write(legacy.resolve("green-ide.json"), snapshot(BuildSnapshot.ORIGIN_IDE, null, true).toJson().getBytes(StandardCharsets.UTF_8));

        Path dir = store.projectDir(link);
        assertFalse(Files.exists(legacy), "verschoben");
        assertEquals(dir, store.projectDir(real), "Symlink und echter Pfad teilen sich die Ablage");
        assertEquals("1.0", store.loadGreen(real, BuildSnapshot.ORIGIN_IDE).getModules().get("g:app").getVersion());
    }

    @Test
    void aufraeumenLaesstFremdeDateienUndJungeProjekteStehen() throws Exception {
        SnapshotStore store = new SnapshotStore(tmp.resolve("store"));
        long old = System.currentTimeMillis() - SnapshotStore.PRUNE_AFTER_MILLIS - 60_000;
        Path stale = Files.createDirectories(store.getRoot().resolve("stale-000000000000"));
        Files.write(stale.resolve("green-ide.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Path foreign = Files.createDirectories(store.getRoot().resolve("foreign-000000000000"));
        Files.write(foreign.resolve("notes.txt"), "meins".getBytes(StandardCharsets.UTF_8));
        Path young = Files.createDirectories(store.getRoot().resolve("young-000000000000"));
        Files.write(young.resolve("green-ide.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(store.getRoot().resolve("loose.json"), "{}".getBytes(StandardCharsets.UTF_8));
        for (Path p : new Path[] {stale.resolve("green-ide.json"), stale, foreign.resolve("notes.txt"), foreign}) {
            Files.setLastModifiedTime(p, FileTime.fromMillis(old));
        }

        store.prune(null, System.currentTimeMillis() - SnapshotStore.PRUNE_AFTER_MILLIS);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(foreign.resolve("notes.txt")), "fremde Datei bleibt, Ordner damit auch");
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(store.getRoot().resolve("loose.json")), "nur Ordner werden aufgeräumt");

        new SnapshotStore(tmp.resolve("gibt-es-nicht")).prune(null, Long.MAX_VALUE);   // kein Fehler
    }

    @Test
    void standardAblageAusSystemProperty() {
        String before = System.getProperty(SnapshotStore.PROP_HOME);
        try {
            System.setProperty(SnapshotStore.PROP_HOME, " " + tmp + " ");
            assertEquals(tmp.resolve("snapshots"), SnapshotStore.defaultStore().getRoot());
        } finally {
            if (before == null) {
                System.clearProperty(SnapshotStore.PROP_HOME);
            } else {
                System.setProperty(SnapshotStore.PROP_HOME, before);
            }
        }
    }
}
