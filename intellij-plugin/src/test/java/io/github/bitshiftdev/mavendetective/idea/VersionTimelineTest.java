package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VersionTimelineTest {

    /** Format wie in {@code VersionExplainer}: „Verdrängt: <version> über <pfad> – <grund>“. */
    @Test
    void verdraengteVersionAusDemStationstext() {
        assertEquals("2.15.0", VersionTimeline.displacedVersion(
                "Verdrängt: 2.15.0 über com.acme:a:1 → com.fasterxml.jackson.core:jackson-core:2.15.0 – Pfad ist länger (Tiefe 2 gegen 1)"));
    }

    @Test
    void andererTextHatKeineVersion() {
        assertNull(VersionTimeline.displacedVersion("Transitiv über a:b:1 → c:d:2"));
        assertNull(VersionTimeline.displacedVersion("Verdrängt: über nichts"));
    }
}
