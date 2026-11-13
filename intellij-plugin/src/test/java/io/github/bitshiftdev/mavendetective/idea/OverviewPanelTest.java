package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverviewPanelTest {

    @AfterEach
    void zuruecksetzen() {
        MavenDetectiveBundle.useForTests(null);
    }

    @Test
    void zeitpunktDerGruenenReferenzKurz() {
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.GERMAN);
        assertEquals("12.11. 07:45", OverviewPanel.formatCreatedAt("2026-11-12T07:45:12.345+01:00"));
        MavenDetectiveBundle.useForTests(MavenDetectiveBundle.Language.ENGLISH);
        assertEquals("Nov 12, 07:45", OverviewPanel.formatCreatedAt("2026-11-12T07:45:12.345+01:00"));
    }

    @Test
    void unlesbarerZeitpunktBleibtStehen() {
        assertEquals("gestern", OverviewPanel.formatCreatedAt("gestern"));
        assertEquals("", OverviewPanel.formatCreatedAt(null));
    }
}
