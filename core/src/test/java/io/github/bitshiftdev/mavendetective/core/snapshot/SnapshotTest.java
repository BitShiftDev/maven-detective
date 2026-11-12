package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.BuildDoctor;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTest {

    private static BuildSnapshot springBoot(String bootVersion, String java, String jackson) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_EXTENSION)
                .setMavenVersion("3.9.11").setJavaVersion(java).setOffline(false)
                .setUserSettings("/home/u/.m2/settings.xml");
        s.getActiveProfiles().add("dev");
        s.getMirrors().put("central-mirror", "https://nexus.example.com/central (central)");
        for (String module : new String[] {"com.example:api", "com.example:web"}) {
            BuildSnapshot.Module m = s.module(module).setVersion("1.0-SNAPSHOT")
                    .setParent("org.springframework.boot:spring-boot-starter-parent:" + bootVersion)
                    .setEffectivePomHash("hash-" + bootVersion)
                    .setDependenciesResolved(true);
            m.addPropertyIfVersion("spring-boot.version", bootVersion);
            m.addPropertyIfVersion("project.build.sourceEncoding", "UTF-8");
            m.getPlugins().put("org.apache.maven.plugins:maven-compiler-plugin", "3.13.0");
            m.getDependencies().put("com.fasterxml.jackson.core:jackson-databind", jackson);
            m.getDependencies().put("org.slf4j:slf4j-api", "2.0.16");
        }
        return s;
    }

    @Test
    void jsonRoundTrip() {
        BuildSnapshot s = springBoot("3.4.1", "21.0.4", "2.18.2").setCreatedAt("2026-11-12T08:14:03+01:00");
        BuildSnapshot back = BuildSnapshot.fromJson(s.toJson());
        assertEquals(s.toJson(), back.toJson());
        assertEquals("3.4.1", back.getModules().get("com.example:api").getVersionProperties().get("spring-boot.version"));
        assertFalse(back.getModules().get("com.example:api").getVersionProperties().containsKey("project.build.sourceEncoding"),
                "nur Versions-Properties werden gespeichert");
    }

    @Test
    void identicalSnapshotsHaveNoChanges() {
        assertTrue(SnapshotDiff.compare(springBoot("3.4.1", "21.0.4", "2.18.2"), springBoot("3.4.1", "21.0.4", "2.18.2")).isEmpty());
    }

    @Test
    void parentUpgradeAndJdkDowngradeAreNamedAndMerged() {
        BuildSnapshot green = springBoot("3.4.1", "21.0.4", "2.18.2");
        BuildSnapshot now = springBoot("3.5.0", "17.0.12", "2.19.0");
        SnapshotDiff diff = SnapshotDiff.compare(green, now);
        List<String> lines = diff.lines(10);

        assertEquals("JDK 21.0.4 → 17.0.12", lines.get(0), "Umgebung zuerst");
        assertTrue(lines.contains("Parent org.springframework.boot:spring-boot-starter-parent 3.4.1 → 3.5.0 (2 Module)"), lines.toString());
        assertTrue(lines.contains("Property spring-boot.version 3.4.1 → 3.5.0 (2 Module)"), lines.toString());
        assertTrue(lines.contains("Dependency com.fasterxml.jackson.core:jackson-databind 2.18.2 → 2.19.0 (2 Module)"), lines.toString());
        assertFalse(lines.toString().contains("Effective POM"), "Hash nur, wenn sonst nichts erklärt");
    }

    @Test
    void addedAndRemovedDependenciesOnlyWhenEquallyResolved() {
        BuildSnapshot green = springBoot("3.4.1", "21.0.4", "2.18.2");
        BuildSnapshot now = springBoot("3.4.1", "21.0.4", "2.18.2");
        now.getModules().get("com.example:web").getDependencies().put("commons-logging:commons-logging", "1.2");
        assertEquals("Dependency neu: commons-logging:commons-logging 1.2",
                SnapshotDiff.compare(green, now).lines(5).get(0));

        // Aktueller Lauf nur mit direkten Dependencies (z. B. validate): keine „entfernt“-Flut
        BuildSnapshot shallow = springBoot("3.4.1", "21.0.4", "2.18.2");
        for (BuildSnapshot.Module m : shallow.getModules().values()) {
            m.setDependenciesResolved(false);
            m.getDependencies().remove("org.slf4j:slf4j-api");
        }
        assertTrue(SnapshotDiff.compare(green, shallow).isEmpty());
    }

    @Test
    void acrossOriginsOnlyComparableFactsCount() {
        BuildSnapshot ide = springBoot("3.4.1", "21.0.12", "2.18.2").setOrigin(BuildSnapshot.ORIGIN_IDE);
        ide.getMirrors().clear();
        ide.setUserSettings(null);
        ide.getModules().get("com.example:api").getDependencies().put("org.junit.jupiter:junit-jupiter", "5.11.0");
        BuildSnapshot cli = springBoot("3.4.1", "21.0.12.1", "2.18.2");
        assertTrue(SnapshotDiff.compare(ide, cli).isEmpty(), SnapshotDiff.compare(ide, cli).lines(10).toString());

        cli.setJavaVersion("17.0.12");
        assertEquals("JDK 21.0.12 → 17.0.12", SnapshotDiff.compare(ide, cli).lines(5).get(0));
    }

    @Test
    void effectivePomHashExplainsOtherwiseInvisibleChange() {
        BuildSnapshot green = springBoot("3.4.1", "21.0.4", "2.18.2");
        BuildSnapshot now = springBoot("3.4.1", "21.0.4", "2.18.2");
        now.getModules().get("com.example:api").setEffectivePomHash("other");
        assertEquals("Effective POM com.example:api Stand grüner Build → geändert", SnapshotDiff.compare(green, now).lines(5).get(0));
    }

    @Test
    void storeKeepsGreenAndLatestPerProject(@TempDir Path tmp) throws Exception {
        SnapshotStore store = new SnapshotStore(tmp.resolve("snapshots"));
        Path project = Files.createDirectories(tmp.resolve("my project"));
        assertNull(store.changesSinceGreen(project));

        store.saveGreen(project, springBoot("3.4.1", "21.0.4", "2.18.2").setCreatedAt("2026-11-12T08:00:00.000+01:00").setSuccessful(true));
        store.saveLatest(project, springBoot("3.4.1", "21.0.4", "2.18.2").setCreatedAt("2026-11-12T08:00:00.000+01:00").setSuccessful(true));
        assertNull(store.changesSinceGreen(project), "letzter Lauf war der grüne");

        // Rot in derselben Sekunde: entscheidend ist das Ergebnis, nicht die Uhrzeit
        store.saveLatest(project, springBoot("3.5.0", "21.0.4", "2.18.2").setCreatedAt("2026-11-12T08:00:00.000+01:00").setSuccessful(false));
        SnapshotDiff diff = store.changesSinceGreen(project);
        assertNotNull(diff);
        assertEquals("Seit dem letzten grünen Build (2026-11-12 08:00)", diff.headline());
        assertTrue(store.projectDir(project).getFileName().toString().startsWith("my_project-"));

        // IDE-Schnappschuss als Rückfall, wenn die Extension noch keinen grünen hat
        Path other = Files.createDirectories(tmp.resolve("other"));
        store.saveGreen(other, springBoot("3.4.1", "21.0.4", "2.18.2").setOrigin(BuildSnapshot.ORIGIN_IDE));
        assertEquals(BuildSnapshot.ORIGIN_IDE, store.loadGreen(other, BuildSnapshot.ORIGIN_EXTENSION).getOrigin());
    }

    @Test
    void shortGreenRunDoesNotThinOutReference(@TempDir Path tmp) throws Exception {
        SnapshotStore store = new SnapshotStore(tmp);
        Path project = Files.createDirectories(tmp.resolve("p"));
        store.saveGreen(project, springBoot("3.4.1", "21.0.4", "2.18.2"));

        // mvn -pl web validate: nur ein Modul, nur direkte Dependencies
        BuildSnapshot quick = springBoot("3.4.1", "21.0.4", "2.18.2");
        quick.getModules().remove("com.example:api");
        quick.getModules().get("com.example:web").setDependenciesResolved(false).getDependencies().remove("org.slf4j:slf4j-api");
        store.saveGreen(project, quick);

        BuildSnapshot green = store.loadGreen(project, BuildSnapshot.ORIGIN_EXTENSION);
        assertEquals(2, green.getModules().size());
        assertTrue(green.getModules().get("com.example:web").isDependenciesResolved());
        assertEquals("2.0.16", green.getModules().get("com.example:web").getDependencies().get("org.slf4j:slf4j-api"));
    }

    @Test
    void consoleCardAndMarkdownShowChanges() {
        List<Diagnosis> ds = BuildDoctor.withDefaults().diagnose(
                MavenLogParser.parse("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile "
                        + "(default-compile) on project api: Fatal error compiling: error: release version 21 not supported -> [Help 1]\n"),
                Environment.builder().build());
        SnapshotDiff diff = SnapshotDiff.compare(springBoot("3.4.1", "21.0.4", "2.18.2"), springBoot("3.4.1", "17.0.12", "2.18.2"));
        String card = String.join("\n", Reports.consoleCard(ds, diff));
        assertTrue(card.contains("| Seit dem letzten grünen Build:"), card);
        assertTrue(card.contains("|   - JDK 21.0.4 → 17.0.12"), card);
        assertTrue(Reports.markdown(ds, MavenLogParser.parse(""), diff).contains("- JDK 21.0.4 → 17.0.12"));
    }
}
