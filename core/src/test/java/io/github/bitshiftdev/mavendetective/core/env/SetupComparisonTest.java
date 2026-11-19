package io.github.bitshiftdev.mavendetective.core.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SetupComparison}: jede Zeile einmal gleich, einmal abweichend; die Zusammenfassung nennt nur Abweichungen. */
class SetupComparisonTest {

    static final int JDK = 0;
    static final int MAVEN = 1;
    static final int SETTINGS = 2;
    static final int REPOSITORY = 3;
    static final int PROFILES = 4;
    static final int MAVEN_CONFIG = 5;
    static final int JVM_CONFIG = 6;
    static final int MAVEN_OPTS = 7;

    /** Beide Seiten gleich und vollständig bekannt. */
    static BuildSetup base(String label) {
        BuildSetup s = new BuildSetup(label).setJavaVersion("21.0.4").setJavaHome("/jdk/21").setMavenVersion("3.9.11")
                .setMavenSource("/opt/maven").setUserSettings("/home/u/.m2/settings.xml").setLocalRepository("/home/u/.m2/repository")
                .setMavenConfig("-Pci").setJvmConfig("-Xmx2g").setMavenOpts("-Xss4m");
        s.getProfiles().add("ci");
        return s;
    }

    static Arguments row(int index, boolean different, Consumer<BuildSetup> left, Consumer<BuildSetup> right) {
        return Arguments.of(index, different, left, right);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                row(JDK, false, l -> l.setJavaVersion("17.0.1"), r -> r.setJavaVersion("17.0.12")),
                row(JDK, false, l -> l.setJavaVersion("1.8.0_402"), r -> r.setJavaVersion("8")),
                row(JDK, false, l -> l.setJavaVersion(null), r -> r.setJavaVersion("17")),
                row(JDK, true, l -> l.setJavaVersion("17.0.12"), r -> r.setJavaVersion("21.0.4")),
                row(MAVEN, false, l -> l.setMavenSource("gebündelt"), r -> r.setMavenSource("Wrapper (./mvnw)")),
                row(MAVEN, false, l -> l.setMavenVersion(null), r -> r.setMavenVersion("3.8.8")),
                row(MAVEN, true, l -> l.setMavenVersion("3.9.9"), r -> r.setMavenVersion("3.9.11")),
                row(MAVEN, true, l -> l.setMavenVersion("3.8.8"), r -> r.setMavenVersion("4.0.0")),
                row(SETTINGS, true, l -> l.setUserSettings(null), r -> { }),
                row(SETTINGS, true, l -> l.setUserSettings("/p/.mvn/settings.xml"), r -> { }),
                row(REPOSITORY, false, l -> l.setLocalRepository(null), r -> { }),
                row(REPOSITORY, false, l -> l.setLocalRepository(""), r -> { }),
                row(REPOSITORY, true, l -> l.setLocalRepository("/cache/m2"), r -> { }),
                row(PROFILES, false, l -> l.getProfiles().add(0, "fast"), r -> r.getProfiles().add("fast")),
                row(PROFILES, true, l -> l.getProfiles().clear(), r -> { }),
                row(PROFILES, true, l -> l.getProfiles().add("!slow"), r -> { }),
                row(MAVEN_CONFIG, false, l -> l.setMavenConfig("  -Pci\n"), r -> { }),
                row(MAVEN_CONFIG, false, l -> l.setMavenConfig(""), r -> r.setMavenConfig(null)),
                row(MAVEN_CONFIG, true, l -> l.setMavenConfig(null), r -> { }),
                row(MAVEN_CONFIG, true, l -> l.setMavenConfig("-Pci -U"), r -> { }),
                row(JVM_CONFIG, false, l -> l.setJvmConfig("-Xmx2g\n\n"), r -> { }),
                row(JVM_CONFIG, true, l -> l.setJvmConfig("-Xmx4g"), r -> { }),
                row(MAVEN_OPTS, false, l -> l.setMavenOpts(" "), r -> r.setMavenOpts(null)),
                row(MAVEN_OPTS, true, l -> l.setMavenOpts(null), r -> { }));
    }

    @ParameterizedTest(name = "Zeile {0}, abweichend: {1}")
    @MethodSource("rows")
    void jedeZeileGleichOderAbweichend(int index, boolean different, Consumer<BuildSetup> left, Consumer<BuildSetup> right) {
        BuildSetup l = base("IDE");
        BuildSetup r = base("Kommandozeile");
        left.accept(l);
        right.accept(r);
        SetupComparison c = SetupComparison.compare(l, r);

        assertEquals(8, c.getRows().size());
        SetupComparison.Row row = c.getRows().get(index);
        assertEquals(different, row.isDifferent(), row.getAspect() + ": " + row.getLeft() + " / " + row.getRight());
        assertEquals(different, row.getHint() != null, "Hinweis nur bei Abweichung");
        assertEquals(different ? List.of(row) : List.of(), c.getDifferences(), "keine andere Zeile weicht ab");
        assertEquals(different, c.summary().endsWith(row.getAspect()), c.summary());
        assertNotNull(row.getLeft());
        assertNotNull(row.getRight());
    }

    @Test
    void zusammenfassungNenntNurAbweichungenInZeilenreihenfolge() {
        BuildSetup l = base("IDE").setJavaVersion("17").setMavenOpts(null);
        BuildSetup r = base("Kommandozeile").setLocalRepository("/cache");
        SetupComparison c = SetupComparison.compare(l, r);
        List<String> aspects = c.getDifferences().stream().map(SetupComparison.Row::getAspect).toList();
        assertEquals(List.of(c.getRows().get(JDK).getAspect(), c.getRows().get(REPOSITORY).getAspect(),
                c.getRows().get(MAVEN_OPTS).getAspect()), aspects);
        assertTrue(c.summary().endsWith(String.join(", ", aspects)), c.summary());
        assertTrue(c.summary().startsWith("IDE und Kommandozeile"), c.summary());
        assertEquals("IDE und Kommandozeile bauen mit derselben Umgebung.",
                SetupComparison.compare(base("IDE"), base("Kommandozeile")).summary());
    }

    @Test
    void unbekannteWerteHabenEinenPlatzhalter() {
        SetupComparison c = SetupComparison.compare(new BuildSetup("IDE"), new BuildSetup("Kommandozeile"));
        assertTrue(c.getDifferences().isEmpty(), c.getDifferences().toString());
        for (SetupComparison.Row row : c.getRows()) {
            assertFalse(row.getLeft().isEmpty(), row.getAspect());
            assertNull(row.getHint());
        }
        assertEquals("? (/jdk)", SetupComparison.compare(new BuildSetup("a").setJavaHome("/jdk"), new BuildSetup("b"))
                .getRows().get(JDK).getLeft());
    }

    @Test
    void markdownMarkiertAbweichungenUndMaskiertTabellenzeichen() {
        BuildSetup l = base("IDE").setMavenOpts("-Dx=a|b\n-Dy=c");
        SetupComparison c = SetupComparison.compare(l, base("Kommandozeile"));
        String md = c.markdown();
        assertTrue(md.startsWith("| | IDE | Kommandozeile |\n| --- | --- | --- |\n"), md);
        String opts = c.getRows().get(MAVEN_OPTS).getAspect();
        assertTrue(md.contains("| **" + opts + "** | -Dx=a\\|b -Dy=c | -Xss4m |"), md);
        assertTrue(md.contains("| JDK | 21.0.4 (/jdk/21) | 21.0.4 (/jdk/21) |"), md);
        assertTrue(md.endsWith("\n- " + c.getRows().get(MAVEN_OPTS).getHint() + "\n"), md);
        assertEquals(13, md.split("\n", -1).length, "Kopf, Trenner, 8 Zeilen, Leerzeile, ein Hinweis, Ende");
    }
}
