package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.github.bitshiftdev.mavendetective.idea.UpgradeLabels.Marker.CURRENT;
import static io.github.bitshiftdev.mavendetective.idea.UpgradeLabels.Marker.MAJOR;
import static io.github.bitshiftdev.mavendetective.idea.UpgradeLabels.Marker.NEWER;
import static io.github.bitshiftdev.mavendetective.idea.UpgradeLabels.Marker.OLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpgradeLabelsTest {

    @Test
    void neuerAelterHeute() {
        assertEquals(EnumSet.of(NEWER), UpgradeLabels.markers("2.18.2", "2.17.0"));
        assertEquals(EnumSet.of(OLDER), UpgradeLabels.markers("2.16.1", "2.17.0"));
        assertEquals(EnumSet.of(CURRENT), UpgradeLabels.markers("2.17.0", "2.17.0"));
    }

    @Test
    void majorSprungInBeideRichtungen() {
        assertEquals(EnumSet.of(NEWER, MAJOR), UpgradeLabels.markers("3.0.0", "2.17.0"));
        assertEquals(EnumSet.of(OLDER, MAJOR), UpgradeLabels.markers("1.9", "2.0"));
        assertEquals(EnumSet.of(NEWER, MAJOR), UpgradeLabels.markers("33.0-jre", "31.1-jre"));
    }

    @Test
    void ohneHeutigeVersionKeineMarker() {
        assertEquals(EnumSet.noneOf(UpgradeLabels.Marker.class), UpgradeLabels.markers("1.0", null));
    }

    @Test
    void major() {
        assertEquals("2", UpgradeLabels.major("2.17.1"));
        assertEquals("31", UpgradeLabels.major("31.1-jre"));
        assertEquals("1", UpgradeLabels.major("01.2"));
        assertNull(UpgradeLabels.major("RELEASE"));
    }
}
