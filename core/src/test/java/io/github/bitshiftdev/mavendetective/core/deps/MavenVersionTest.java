package io.github.bitshiftdev.mavendetective.core.deps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
}
