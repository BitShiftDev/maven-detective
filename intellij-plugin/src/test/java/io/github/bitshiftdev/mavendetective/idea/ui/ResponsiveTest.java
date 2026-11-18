package io.github.bitshiftdev.mavendetective.idea.ui;

import io.github.bitshiftdev.mavendetective.idea.ui.Responsive.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResponsiveTest {

    private static Mode next(Mode current, int width) {
        return Responsive.next(current, width, 540, 600);
    }

    @Test
    void ersterModusNachDerMitteZwischenDenSchwellen() {
        assertEquals(Mode.NARROW, next(null, 350));
        assertEquals(Mode.NARROW, next(null, 569));
        assertEquals(Mode.WIDE, next(null, 570));
        assertEquals(Mode.WIDE, next(null, 1400));
    }

    @Test
    void nochNichtAusgelegtAendertNichts() {
        assertNull(next(null, 0));
        assertEquals(Mode.WIDE, next(Mode.WIDE, 0));
        assertEquals(Mode.NARROW, next(Mode.NARROW, -1));
    }

    @Test
    void hystereseVerhindertSpringenZwischenDenSchwellen() {
        assertEquals(Mode.WIDE, next(Mode.WIDE, 560));
        assertEquals(Mode.WIDE, next(Mode.WIDE, 540));
        assertEquals(Mode.NARROW, next(Mode.WIDE, 539));
        assertEquals(Mode.NARROW, next(Mode.NARROW, 560));
        assertEquals(Mode.NARROW, next(Mode.NARROW, 599));
        assertEquals(Mode.WIDE, next(Mode.NARROW, 600));
    }

    @Test
    void schwellenSkalierenMit() {
        // 200 %: Schwellen 1080/1200
        assertEquals(Mode.NARROW, Responsive.next(Mode.WIDE, 1000, 1080, 1200));
        assertEquals(Mode.NARROW, Responsive.next(Mode.NARROW, 1100, 1080, 1200));
        assertEquals(Mode.WIDE, Responsive.next(Mode.NARROW, 1200, 1080, 1200));
    }

    @Test
    void breitBehaeltDenBisherigenProportionsKey() {
        assertEquals("MavenDetective.BuildDoctor.split", Responsive.proportionKey("MavenDetective.BuildDoctor.split", Mode.WIDE));
        assertEquals("MavenDetective.BuildDoctor.split.narrow", Responsive.proportionKey("MavenDetective.BuildDoctor.split", Mode.NARROW));
    }
}
