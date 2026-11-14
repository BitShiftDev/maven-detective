package io.github.bitshiftdev.mavendetective.core.deps;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenVersionTest {

    @Test
    void ordersLikeMaven() {
        List<String> sorted = new ArrayList<>(Arrays.asList(
                "2.0", "1.10", "1.9", "1.0-SNAPSHOT", "1.0-rc1", "1.0-beta-2", "1.0-alpha1", "1.0", "1.0-sp1", "1.0.1", "1.0-M3"));
        sorted.sort(MavenVersion.ORDER);
        assertEquals(List.of("1.0-alpha1", "1.0-beta-2", "1.0-M3", "1.0-rc1", "1.0-SNAPSHOT", "1.0", "1.0-sp1", "1.0.1",
                "1.9", "1.10", "2.0"), sorted);
    }

    @Test
    void equalForms() {
        assertEquals(0, MavenVersion.compare("1.0", "1"));
        assertEquals(0, MavenVersion.compare("1.0.0", "1.0-final"));
        assertEquals(0, MavenVersion.compare("2.0-GA", "2.0.RELEASE"));
        assertEquals(0, MavenVersion.compare("1.0-CR1", "1.0-rc1"));
    }

    @Test
    void realWorld() {
        assertTrue(MavenVersion.compare("33.3.1-jre", "32.1.3-jre") > 0);
        assertTrue(MavenVersion.compare("2.18.2", "2.9.10") > 0);
        assertTrue(MavenVersion.compare("6.2.1", "6.2.1-RC1") > 0);
        assertEquals("2.18.2", MavenVersion.max(List.of("2.15.0", "2.18.2", "2.9.10")));
    }

    @Test
    void faelleAusDemReview() {
        assertEquals(-1, MavenVersion.compare("1.0-1", "1.0.1"));
        assertEquals(0, MavenVersion.compare("1.0.0-rc1", "1.0-rc1"));
        assertEquals(0, MavenVersion.compare("2.0.0-M1", "2.0-M1"));
        assertEquals(1, MavenVersion.compare("1.0-a", "1.0"));
        assertEquals(1, MavenVersion.compare("2.13.4.2", "2.13.4-2"));
        assertEquals(1, MavenVersion.compare("1.2.3-b", "1.2.3-beta"));
        assertEquals(1, MavenVersion.compare("1.12345678901234567890", "1.12345678901234567889"));
    }

    @Test
    void stimmtMitComparableVersionUeberein() {
        List<String> versions = new ArrayList<>(Arrays.asList(
                "1", "1.0", "1.0.0", "1-0", "1.0-0", "1.0-1", "1.0.1", "1.0-a", "1.0-a1", "1.0-alpha-1", "1.0-b", "1.0-b2",
                "1.0-beta", "1.0-m1", "1.0-M1", "1.0-milestone-1", "1.0-rc1", "1.0-RC-1", "1.0-cr1", "1.0-SNAPSHOT",
                "1.0-final", "1.0-ga", "1.0.RELEASE", "1.0-sp", "1.0-sp1", "1.0-foo", "1.0-bar", "1.0.foo", "1.0-1-foo",
                "1.0jre", "33.3.1-jre", "32.1.3-android", "2.13.4.2", "2.13.4-2", "2.0.0-M1", "2.0-M1", "1.0.0-rc1",
                "1.0-rc1", "1.2.3-b", "1.2.3-beta", "1-1.foo-bar1baz-.1", "1.0.0.0.1", "0.9", "007", "7",
                "123456789012", "1234567890123456789012", "1.0_1", "1.0+build", "1.0-+", "", "-", ".", "1..2", "1.-2",
                "6.2.1-RC1", "6.2.1", "1.0-alpha-1-SNAPSHOT", "1.0-20240101.123456-1", "1.0--1", "1.0-a.1"));
        Random random = new Random(42);
        String alphabet = "0123456789.-abmrcfinalspgjre_";
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + random.nextInt(10);
            for (int k = 0; k < len; k++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            versions.add(sb.toString());
        }
        int checked = 0;
        for (String a : versions) {
            for (int j = 0; j < 60; j++) {
                String b = versions.get(random.nextInt(versions.size()));
                int expected = Integer.signum(new ComparableVersion(a).compareTo(new ComparableVersion(b)));
                assertEquals(expected, MavenVersion.compare(a, b), a + " vs " + b);
                checked++;
            }
        }
        assertTrue(checked > 100_000);
    }
}
