package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VersionTimelineTest {

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    /** Format wie in {@code VersionExplainer}: „Verdrängt: <version> über <pfad> – <grund>“. */
    @Test
    void verdraengteVersionAusDemStationstext() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
        String de = "Verdrängt: 2.15.0 über com.acme:a:1 → com.fasterxml.jackson.core:jackson-core:2.15.0 – Pfad ist länger (Tiefe 2 gegen 1)";
        assertEquals("2.15.0", VersionTimeline.displacedVersion(de));
        assertEquals("über com.acme:a:1 → com.fasterxml.jackson.core:jackson-core:2.15.0 – Pfad ist länger (Tiefe 2 gegen 1)",
                VersionTimeline.rest(de, "2.15.0"));
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.ENGLISH);
        assertEquals("2.15.0", VersionTimeline.displacedVersion("Displaced: 2.15.0 via com.acme:a:1 – another version won"));
        assertEquals("via com.acme:a:1 – another version won", VersionTimeline.rest("Displaced: 2.15.0 via com.acme:a:1 – another version won", "2.15.0"));
    }

    @Test
    void andererTextHatKeineVersion() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
        assertNull(VersionTimeline.displacedVersion("Transitiv über a:b:1 → c:d:2"));
        assertNull(VersionTimeline.displacedVersion("Verdrängt: über nichts"));
    }
}
