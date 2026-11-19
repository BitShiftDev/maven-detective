package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.Texts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SnapshotDiff}: was über Herkünfte hinweg zählt, Module, Run-Argumente, Einschränkungen, Ausgabe. */
class SnapshotDiffTest {

    static BuildSnapshot snapshot(String origin) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(origin).setMavenVersion("3.9.11").setJavaVersion("21.0.4")
                .setOffline(false).setUserSettings("/home/u/.m2/settings.xml");
        s.getMirrors().put("corp", "https://nexus.corp/public (*)");
        BuildSnapshot.Module m = s.module("g:app").setVersion("1.0").setParent("g:parent:1").setDependenciesResolved(true);
        m.getDependencies().put("g:lib", "1.0");
        m.getPlugins().put("org.apache.maven.plugins:maven-surefire-plugin", "3.5.2");
        m.addPropertyIfVersion("lib.version", "1.0");
        return s;
    }

    static BuildSnapshot ext() {
        return snapshot(BuildSnapshot.ORIGIN_EXTENSION);
    }

    static List<String> changes(BuildSnapshot green, BuildSnapshot current) {
        return SnapshotDiff.compare(green, current).getChanges().stream()
                .map(c -> c.getArea() + " " + c.getSubject() + " " + c.getBefore() + " " + c.getAfter() + " " + c.getModules())
                .collect(Collectors.toList());
    }

    @Test
    void neuesModulZaehltFehlendesNicht() {
        BuildSnapshot green = ext();
        BuildSnapshot current = ext();
        current.module("g:new").setVersion("1.0");
        assertEquals(List.of("MODULES g:new null 1.0 []"), changes(green, current));
        assertTrue(changes(current, green).isEmpty(), "ein Lauf mit -pl baut nur einen Teil des Reactors");
    }

    @Test
    void umgebungNurInDerselbenHerkunft() {
        BuildSnapshot green = ext();
        BuildSnapshot current = ext().setOffline(true).setUserSettings("/ci/settings.xml");
        current.getMirrors().put("corp", "https://other.corp/public (*)");
        assertEquals(List.of("OFFLINE null " + Texts.t("changes.off") + " " + Texts.t("changes.on") + " []",
                "SETTINGS null /home/u/.m2/settings.xml /ci/settings.xml []",
                "MIRRORS corp https://nexus.corp/public (*) https://other.corp/public (*) []"), changes(green, current));

        BuildSnapshot ide = snapshot(BuildSnapshot.ORIGIN_IDE);
        assertTrue(changes(ide, current).isEmpty(), "IDE und Extension messen Offline, Settings und Mirrors verschieden");
        assertTrue(changes(ext().setOffline(null), current.setUserSettings(null)).stream().noneMatch(c -> c.startsWith("OFFLINE")),
                "unbekannt ist keine Änderung");
        assertTrue(changes(snapshot(null), snapshot(null).setJavaVersion("17")).get(0).startsWith("JDK"),
                "ohne Herkunft zählt nur der JDK-Wechsel über die Hauptversion");
    }

    @Test
    void profileUndMinusDAusDemLauf() {
        BuildSnapshot green = ext().setRunProperties(Map.of("skipTests", "true", "env", "dev"));
        green.getActiveProfiles().add("ci");
        BuildSnapshot current = ext().setRunProperties(Map.of("env", "prod", "debug", "true"));
        List<String> c = changes(green, current);
        assertEquals("PROFILES null ci " + Texts.t("changes.none") + " []", c.get(0));
        assertTrue(c.contains("RUN_PROPERTY -Ddebug null true []"), c.toString());
        assertTrue(c.contains("RUN_PROPERTY -Denv dev prod []"), c.toString());
        assertTrue(c.contains("RUN_PROPERTY -DskipTests true null []"), c.toString());
        assertTrue(changes(ext(), ext().setRunProperties(Map.of("x", "1"))).isEmpty(), "ein Lauf ohne bekannte -D: kein Vergleich");
    }

    @Test
    void parentPropertiesUndPluginsJeModul() {
        BuildSnapshot green = ext();
        BuildSnapshot current = ext();
        BuildSnapshot.Module m = current.getModules().get("g:app").setParent("other:parent:2");
        m.addPropertyIfVersion("new.version", "1.0");
        m.getVersionProperties().put("lib.version", "1.1");
        m.getPlugins().put("org.apache.maven.plugins:maven-surefire-plugin", "3.5.3");
        m.getPlugins().put("org.apache.maven.plugins:maven-jar-plugin", "3.4.2");
        assertEquals(List.of("PARENT null g:parent:1 other:parent:2 [g:app]",
                "PROPERTY lib.version 1.0 1.1 [g:app]",
                "PLUGIN org.apache.maven.plugins:maven-surefire-plugin 3.5.2 3.5.3 [g:app]"), changes(green, current),
                "nur Wechsel, nicht neue Properties/Plugins");

        BuildSnapshot noParent = ext();
        noParent.getModules().get("g:app").setParent(null);
        assertEquals(List.of("PARENT null g:parent:1 null [g:app]"), changes(green, noParent));
    }

    @Test
    void deklarierteVersionErgaenztNurWasDerVergleichNichtNennt() {
        BuildSnapshot green = ext();
        BuildSnapshot current = ext();
        green.getModules().get("g:app").getDeclaredDependencies().putAll(Map.of("g:lib", "1.0", "g:other", "2.0"));
        current.getModules().get("g:app").getDependencies().put("g:lib", "1.1");
        current.getModules().get("g:app").getDeclaredDependencies().putAll(Map.of("g:lib", "1.1", "g:other", "2.1"));
        assertEquals(List.of("DEPENDENCY g:lib 1.0 1.1 [g:app]", "DEPENDENCY g:other 2.0 2.1 [g:app]"), changes(green, current),
                "g:lib nur einmal");
    }

    @ParameterizedTest(name = "Import grün {0}, rot {1}, Profile {2}/{3}, -D anders {4} → {5}")
    @CsvSource(nullValues = "null", value = {
        "false, false, a, b, false, NONE",
        "true, true, a, a, false, NONE",
        "true, true, a, b, false, RUN_ARGUMENTS",
        "true, false, a, a, true, RUN_ARGUMENTS",
        "true, true, '', '', false, IMPORT_PROFILES",
        "false, true, a, a, false, NONE",
        "false, true, '', '', false, IMPORT_PROFILES",
    })
    void einschraenkungDesVergleichs(boolean greenImport, boolean currentImport, String greenProfile, String currentProfile,
                                     boolean propertiesDiffer, SnapshotDiff.Limitation expected) {
        BuildSnapshot green = ext().setRunProperties(Map.of("x", "1"));
        BuildSnapshot current = ext().setRunProperties(Map.of("x", propertiesDiffer ? "2" : "1"));
        if (!greenProfile.isEmpty()) {
            green.getActiveProfiles().add(greenProfile);
        }
        if (!currentProfile.isEmpty()) {
            current.getActiveProfiles().add(currentProfile);
        }
        // der Import der IDE kennt nur Profil "a"
        if (greenImport) {
            green.setImportProfiles(List.of("a"));
        }
        if (currentImport) {
            current.setImportProfiles(List.of("a"));
        }
        SnapshotDiff diff = SnapshotDiff.compare(green, current);
        assertEquals(expected, diff.getLimitation());
        assertEquals(expected == SnapshotDiff.Limitation.NONE, diff.limitationText() == null);
    }

    @Test
    void importProfileBevorzugtDieDesRotenLaufs() {
        BuildSnapshot green = ext().setImportProfiles(List.of("g"));
        BuildSnapshot current = ext();
        assertEquals(List.of("g"), SnapshotDiff.compare(green, current).importProfiles());
        current.setImportProfiles(List.of());
        SnapshotDiff diff = SnapshotDiff.compare(green, current);
        assertEquals(List.of(), diff.importProfiles());
        assertTrue(diff.limitationText().contains(Texts.t("changes.none")), diff.limitationText());
        assertEquals(List.of(), SnapshotDiff.compare(ext(), ext()).importProfiles());
    }

    @Test
    void zeilenMitObergrenzeUndUeberschriftMitZeitpunkt() {
        BuildSnapshot green = ext().setCreatedAt("2026-11-12T08:14:03.512+01:00");
        BuildSnapshot current = ext().setJavaVersion("17").setMavenVersion("3.9.9");
        current.getModules().get("g:app").getDependencies().put("g:lib", "2.0");
        SnapshotDiff diff = SnapshotDiff.compare(green, current);
        assertEquals(3, diff.getChanges().size());
        List<String> lines = diff.lines(2);
        assertEquals(3, lines.size());
        assertEquals(Texts.t("changes.more", 1), lines.get(2));
        assertEquals(3, diff.lines(3).size());
        assertTrue(diff.headline().endsWith(" (2026-11-12 08:14)"), diff.headline());
        assertEquals(Texts.t("changes.headline"), SnapshotDiff.compare(ext(), ext()).headline());
        assertEquals(Texts.t("changes.headline") + " (heute)", SnapshotDiff.compare(ext().setCreatedAt("heute"), ext()).headline());
        assertTrue(diff.getGreen() == green && diff.getCurrent() == current);
    }

    @Test
    void gleicheAenderungInVielenModulenIstEineZeile() {
        BuildSnapshot green = ext();
        BuildSnapshot current = ext();
        for (String module : new String[] {"g:a", "g:b"}) {
            green.module(module).setVersion("1").setParent("g:parent:1");
            current.module(module).setVersion("1").setParent("g:parent:2");
        }
        current.getModules().get("g:app").setParent("g:parent:2");
        assertEquals(List.of("PARENT g:parent 1 2 [g:a, g:app, g:b]"), changes(green, current));
        assertNull(SnapshotDiff.compare(green, current).limitationText());
    }
}
