package io.github.bitshiftdev.mavendetective.core.preflight;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differenztest (Testplan Welle 3): {@link VersionRange} gegen {@code org.apache.maven.artifact.versioning.VersionRange}
 * mit der Auslegung des Maven Enforcers – eine Version ohne Klammern heißt „mindestens“ (wie
 * {@code AbstractVersionEnforcer.containsVersion}), Bereiche wie {@code VersionRange.containsVersion}.
 */
class VersionRangeTest {

    static boolean maven(String spec, String version) throws InvalidVersionSpecificationException {
        org.apache.maven.artifact.versioning.VersionRange range =
                org.apache.maven.artifact.versioning.VersionRange.createFromVersionSpec(spec);
        ArtifactVersion v = new DefaultArtifactVersion(version);
        ArtifactVersion recommended = range.getRecommendedVersion();
        return recommended == null ? range.containsVersion(v) : v.compareTo(recommended) >= 0;
    }

    @ParameterizedTest(name = "{0} enthält {1}: {2}")
    @CsvSource(delimiter = ';', value = {
            "17; 21.0.1; true",
            "17; 11.0.2; false",
            "1.8; 1.8.0-392; true",
            "[1.8,11); 1.8.0-392; true",
            "[1.8,11); 11; false",
            "[1.8,11); 11-ea; false", // unbekannter Qualifier liegt nach der Release-Version
            "(1.8,11]; 1.8; false",
            "(,21]; 21; true",
            "(,21); 21; false",
            "[21,); 21; true",
            "(21,); 21.0.1; true",
            "[3.9.11]; 3.9.11; true",
            "[3.9.11]; 3.9.11.0; true",
            "[3.9.11]; 3.9.12; false",
            "[1.8,9),[11,); 17; true",
            "[1.8,9),[11,); 10; false",
            "[1.0 , 2.0); 1.5; true",
            "[3.6.3,4); 4.0.0-rc-4; true",
            "[3.6.3,4.0.0-alpha); 4.0.0-rc-4; false",
    })
    void tabelleWieMaven(String spec, String version, boolean expected) throws Exception {
        assertEquals(expected, maven(spec, version), "Referenz");
        assertEquals(expected, VersionRange.contains(spec, version));
    }

    /** Zufällige Bereiche aus echten Versionsformen, fester Startwert. */
    @Test
    void zufaelligeBereicheWieMaven() throws Exception {
        String[] versions = {"1", "1.0", "1.8", "1.8.0-392", "9", "10", "11", "11.0.2", "11-ea", "17", "17.0.12", "21",
                "21.0.1", "3.6.3", "3.9.0", "3.9.11", "4.0.0-alpha-13", "4.0.0-rc-4", "4.0.0", "2.0-SNAPSHOT", "2.0",
                "2.0-sp1", "1.0-final", "1.0-ga"};
        Random random = new Random(42);
        int checked = 0;
        for (int i = 0; i < 20_000; i++) {
            String spec = randomSpec(random, versions);
            String version = versions[random.nextInt(versions.length)];
            boolean expected;
            try {
                expected = maven(spec, version);
            } catch (InvalidVersionSpecificationException e) {
                continue; // unlesbar: VersionRange meldet dann nichts (eigener Test)
            }
            assertEquals(expected, VersionRange.contains(spec, version), spec + " enthält " + version);
            checked++;
        }
        assertTrue(checked > 5_000, "nur " + checked);
    }

    private static String randomSpec(Random random, String[] versions) {
        if (random.nextInt(5) == 0) {
            return versions[random.nextInt(versions.length)];
        }
        List<String> sets = new ArrayList<>();
        int count = 1 + random.nextInt(2);
        for (int s = 0; s < count; s++) {
            if (random.nextInt(6) == 0) {
                sets.add("[" + versions[random.nextInt(versions.length)] + "]");
                continue;
            }
            String lower = random.nextInt(4) == 0 ? "" : versions[random.nextInt(versions.length)];
            String upper = random.nextInt(4) == 0 ? "" : versions[random.nextInt(versions.length)];
            String space = random.nextInt(5) == 0 ? " " : "";
            sets.add((random.nextBoolean() ? "[" : "(") + lower + "," + space + upper + (random.nextBoolean() ? "]" : ")"));
        }
        return String.join(",", sets);
    }

    @ParameterizedTest(name = "unlesbar: {0}")
    @CsvSource(delimiter = ';', value = {"[1.0,2.0", "(1.0)", "]", "[", "''"})
    void unlesbareBereicheGeltenAlsErfuellt(String spec) {
        assertTrue(VersionRange.contains(spec, "1.5"));
    }
}
