package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverviewPanelTest {

    @Test
    void zeitpunktDerGruenenReferenzKurz() {
        assertEquals("12.11. 07:45", OverviewPanel.formatCreatedAt("2026-11-12T07:45:12.345+01:00"));
    }

    @Test
    void unlesbarerZeitpunktBleibtStehen() {
        assertEquals("gestern", OverviewPanel.formatCreatedAt("gestern"));
        assertEquals("", OverviewPanel.formatCreatedAt(null));
    }
}
