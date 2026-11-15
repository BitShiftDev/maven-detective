package io.github.bitshiftdev.mavendetective.core.snapshot;

import io.github.bitshiftdev.mavendetective.core.deps.PomFile;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.report.Reports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grüner {@code verify -Pgreen} gegen roten {@code verify} aus der IDE: Beide Schnappschüsse lesen die Module aus
 * demselben Maven-Import – der Unterschied steckt nur in den Argumenten des Laufs (Demo-Projekt, gateway).
 */
class RunArgumentsTest {

    private static final String JACKSON = "com.fasterxml.jackson.core:jackson-databind";

    @Test
    void profileUndPropertiesAusGoalsUndOptionen() {
        RunArguments a = new RunArguments()
                .parse(Arrays.asList("clean", "verify", "-Pgreen", "-P", "a,!b,-c,+d", "-Dx=1", "-D", "y", "--activate-profiles=e",
                        "-pl", "gateway", "--define=z=2=3"))
                .parse("-q '-Dmsg=hallo welt' -P\"f\"");
        assertEquals(Arrays.asList("a", "d", "e", "f", "green", "!b", "!c"), a.profileList());
        assertEquals("1", a.getProperties().get("x"));
        assertEquals("true", a.getProperties().get("y"), "-Dflag ohne Wert = true wie bei Maven");
        assertEquals("2=3", a.getProperties().get("z"));
        assertEquals("hallo welt", a.getProperties().get("msg"));
        assertFalse(a.getProperties().containsKey("pl"));

        // spätere Angabe gewinnt, Profil-Liste der Run-Konfiguration ebenso
        a.profile("green", false);
        assertEquals(Boolean.FALSE, a.explicit("green"));
        assertNull(a.explicit("nirgends"));
        assertEquals(a.profileList(), RunArguments.ofProfileList(a.profileList()).profileList());
    }

    @Test
    void profilDeklariertDirekteVersion(@TempDir Path tmp) throws Exception {
        List<PomFile> poms = demo(tmp);
        Map<String, String> green = DeclaredVersions.of(poms, new RunArguments().parse(Arrays.asList("verify", "-Pgreen")))
                .get("com.acme.shop:gateway");
        Map<String, String> red = DeclaredVersions.of(poms, new RunArguments().parse(Arrays.asList("verify")))
                .get("com.acme.shop:gateway");
        assertEquals("2.18.2", green.get(JACKSON), "Profil gewinnt in seiner POM");
        assertEquals("2.15.4", red.get(JACKSON));
        assertEquals("1.33", red.get("org.yaml:snakeyaml"));
        assertFalse(red.containsKey("org.junit.jupiter:junit-jupiter"), "ohne Version (Management) nicht sicher ablesbar");
        assertEquals("2.4.0-SNAPSHOT", red.get("com.acme.shop:commons"), "${project.version} aufgelöst");
        assertEquals("2.0.16", red.get("org.slf4j:slf4j-api"), "vom Parent geerbt, Property aus dem Parent");
        assertEquals("2.0.17", DeclaredVersions.of(poms, new RunArguments().parse("-Dslf4j.version=2.0.17"))
                .get("com.acme.shop:gateway").get("org.slf4j:slf4j-api"), "-D schlägt die POM");
        assertNull(DeclaredVersions.of(poms, new RunArguments()).get("com.acme.shop:shop-platform"), "packaging pom");
    }

    @Test
    void unsichereProfileLassenEintraegeWeg(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.write(pom, ("<project><groupId>g</groupId><artifactId>m</artifactId><version>1</version>\n"
                + "<properties><lib.version>1.0</lib.version></properties>\n"
                + "<dependencies>\n"
                + "  <dependency><groupId>g</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>\n"
                + "  <dependency><groupId>g</groupId><artifactId>jdk</artifactId><version>1.0</version></dependency>\n"
                + "  <dependency><groupId>g</groupId><artifactId>fix</artifactId><version>3.0</version></dependency>\n"
                + "</dependencies>\n"
                + "<profiles>\n"
                + "  <profile><id>by-jdk</id><activation><jdk>[21,)</jdk></activation>\n"
                + "    <dependencies><dependency><groupId>g</groupId><artifactId>jdk</artifactId><version>2.0</version></dependency></dependencies>\n"
                + "  </profile>\n"
                + "  <profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>\n"
                + "    <properties><lib.version>1.5</lib.version></properties>\n"
                + "  </profile>\n"
                + "  <profile><id>new-lib</id><properties><lib.version>2.0</lib.version></properties></profile>\n"
                + "</profiles></project>").getBytes("UTF-8"));
        List<PomFile> poms = Collections.singletonList(PomFile.read(pom));

        Map<String, String> plain = DeclaredVersions.of(poms, new RunArguments()).get("g:m");
        assertFalse(plain.containsKey("g:jdk"), "Profil mit JDK-Aktivierung: hängt von der Umgebung ab");
        assertFalse(plain.containsKey("g:lib"), "activeByDefault neben unsicherem Profil: offen");
        assertEquals("3.0", plain.get("g:fix"));

        Map<String, String> jdkOff = DeclaredVersions.of(poms, new RunArguments().parse("-P!by-jdk")).get("g:m");
        assertEquals("1.0", jdkOff.get("g:jdk"), "abgeschaltet ist sicher");
        assertEquals("1.5", jdkOff.get("g:lib"), "activeByDefault greift");

        Map<String, String> newLib = DeclaredVersions.of(poms, new RunArguments().parse("-P!by-jdk,new-lib")).get("g:m");
        assertEquals("2.0", newLib.get("g:lib"), "anderes Profil aktiv → activeByDefault fällt weg");
    }

    @Test
    void karteNenntProfilUndVersionStattKeineAenderung(@TempDir Path tmp) throws Exception {
        List<PomFile> poms = demo(tmp);
        BuildSnapshot green = ide(poms, new RunArguments().parse(Arrays.asList("verify", "-Pgreen")));
        BuildSnapshot red = ide(poms, new RunArguments().parse(Arrays.asList("verify")));
        // Rundreise: so liegt der grüne Schnappschuss auf der Platte
        green = BuildSnapshot.fromJson(green.toJson());

        SnapshotDiff diff = SnapshotDiff.compare(green, red);
        List<String> lines = diff.lines(10);
        assertEquals("Profile green → (keine)", lines.get(0), lines.toString());
        assertTrue(lines.contains("Dependency " + JACKSON + " 2.18.2 → 2.15.4"), lines.toString());
        assertEquals(Collections.singletonList("com.acme.shop:gateway"), diff.getChanges().get(1).getModules());
        assertEquals(SnapshotDiff.Limitation.RUN_ARGUMENTS, diff.getLimitation());
        assertTrue(diff.limitationText().contains("Maven-Import"), diff.limitationText());

        List<String> card = Reports.consoleCard(Collections.singletonList(new Diagnosis("r", 10, "Kategorie", "Ursache",
                Confidence.LIKELY, Collections.<Evidence>emptyList(), Collections.<FixAction>emptyList())), diff);
        assertTrue(card.toString().contains("Maven-Import"), card.toString());
    }

    @Test
    void ohneDeklarierteVersionenKeinFalschesKeineAenderung() {
        BuildSnapshot green = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_IDE).setImportProfiles(Collections.<String>emptyList())
                .setRunProperties(Collections.<String, String>emptyMap());
        green.getActiveProfiles().add("green");
        green.module("com.acme.shop:gateway").setDependenciesResolved(true).getDependencies().put(JACKSON, "2.15.4");
        BuildSnapshot red = BuildSnapshot.fromJson(green.toJson());
        red.getActiveProfiles().clear();
        SnapshotDiff diff = SnapshotDiff.compare(green, red);
        assertEquals(1, diff.getChanges().size(), diff.lines(5).toString());
        assertEquals(SnapshotDiff.Limitation.RUN_ARGUMENTS, diff.getLimitation());

        // gleiche Argumente, beide ohne Profil, Import auch ohne: nichts eingeschränkt
        green.getActiveProfiles().clear();
        assertTrue(SnapshotDiff.compare(green, red).isEmpty());
        assertEquals(SnapshotDiff.Limitation.NONE, SnapshotDiff.compare(green, red).getLimitation());
        assertNull(SnapshotDiff.compare(green, red).limitationText());

        // gleiche Argumente, aber anders als der Import: leer, aber nicht „keine Änderung“
        green.getActiveProfiles().add("green");
        red.getActiveProfiles().add("green");
        SnapshotDiff same = SnapshotDiff.compare(green, red);
        assertTrue(same.isEmpty());
        assertEquals(SnapshotDiff.Limitation.IMPORT_PROFILES, same.getLimitation());
        assertTrue(same.limitationText().contains("(keine)"), same.limitationText());
    }

    @Test
    void minusDWirdVerglichen() {
        BuildSnapshot green = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_IDE).setImportProfiles(Collections.<String>emptyList())
                .setRunProperties(Collections.<String, String>emptyMap());
        BuildSnapshot red = BuildSnapshot.fromJson(green.toJson());
        red.setRunProperties(Collections.singletonMap("skipTests", "true"));
        SnapshotDiff diff = SnapshotDiff.compare(green, red);
        assertEquals("Argument neu: -DskipTests true", diff.lines(5).get(0));
        assertEquals(SnapshotDiff.Limitation.RUN_ARGUMENTS, diff.getLimitation());
    }

    @Test
    void extensionModellStammtAusDemLauf() {
        BuildSnapshot green = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_EXTENSION);
        green.getActiveProfiles().add("green");
        green.module("com.acme.shop:gateway").setDependenciesResolved(true).getDependencies().put(JACKSON, "2.18.2");
        BuildSnapshot red = BuildSnapshot.fromJson(green.toJson());
        red.getActiveProfiles().clear();
        red.getModules().get("com.acme.shop:gateway").getDependencies().put(JACKSON, "2.15.4");
        SnapshotDiff diff = SnapshotDiff.compare(green, red);
        assertEquals(2, diff.getChanges().size(), diff.lines(5).toString());
        assertEquals(SnapshotDiff.Limitation.NONE, diff.getLimitation());
        assertNull(red.getRunProperties(), "Extension erfasst keine -D");
    }

    /** Wie {@code IdeMaven.snapshot}: Module aus dem Import (ohne Profil), Argumente und deklarierte Versionen aus dem Lauf. */
    private static BuildSnapshot ide(List<PomFile> poms, RunArguments run) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_IDE).setCreatedAt("2026-11-15T10:00:00+01:00")
                .setImportProfiles(new ArrayList<String>()).setRunProperties(run.getProperties());
        s.getActiveProfiles().addAll(run.profileList());
        Map<String, Map<String, String>> declared = DeclaredVersions.of(poms, run);
        for (String module : Arrays.asList("com.acme.shop:shop-platform", "com.acme.shop:commons", "com.acme.shop:gateway")) {
            BuildSnapshot.Module m = s.module(module).setVersion("2.4.0-SNAPSHOT").setDependenciesResolved(true);
            if (module.endsWith("gateway")) {
                m.getDependencies().put(JACKSON, "2.15.4");   // Import kennt -Pgreen nicht
            }
            if (declared.get(module) != null) {
                m.getDeclaredDependencies().putAll(declared.get(module));
            }
        }
        return s;
    }

    private static List<PomFile> demo(Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("gateway"));
        Files.createDirectories(tmp.resolve("commons"));
        Files.write(tmp.resolve("pom.xml"), ("<project><groupId>com.acme.shop</groupId><artifactId>shop-platform</artifactId>"
                + "<version>2.4.0-SNAPSHOT</version><packaging>pom</packaging>\n"
                + "<modules><module>commons</module><module>gateway</module></modules>\n"
                + "<properties><slf4j.version>2.0.16</slf4j.version></properties>\n"
                + "<dependencies><dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>"
                + "<version>${slf4j.version}</version></dependency></dependencies>\n"
                + "</project>").getBytes("UTF-8"));
        Files.write(tmp.resolve("commons/pom.xml"), ("<project><parent><groupId>com.acme.shop</groupId><artifactId>shop-platform</artifactId>"
                + "<version>2.4.0-SNAPSHOT</version></parent><artifactId>commons</artifactId></project>").getBytes("UTF-8"));
        Files.write(tmp.resolve("gateway/pom.xml"), ("<project><parent><groupId>com.acme.shop</groupId><artifactId>shop-platform</artifactId>"
                + "<version>2.4.0-SNAPSHOT</version></parent><artifactId>gateway</artifactId>\n"
                + "<dependencies>\n"
                + "  <dependency><groupId>com.acme.shop</groupId><artifactId>commons</artifactId><version>${project.version}</version></dependency>\n"
                + "  <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.15.4</version></dependency>\n"
                + "  <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>1.33</version></dependency>\n"
                + "  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>\n"
                + "</dependencies>\n"
                + "<profiles><profile><id>green</id><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId>"
                + "<artifactId>jackson-databind</artifactId><version>2.18.2</version></dependency></dependencies></profile></profiles>\n"
                + "</project>").getBytes("UTF-8"));
        List<PomFile> out = new ArrayList<PomFile>();
        for (String p : new String[] {"pom.xml", "commons/pom.xml", "gateway/pom.xml"}) {
            out.add(PomFile.read(tmp.resolve(p)));
        }
        return out;
    }
}
