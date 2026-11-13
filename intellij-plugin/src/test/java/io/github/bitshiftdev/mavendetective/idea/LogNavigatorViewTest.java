package io.github.bitshiftdev.mavendetective.idea;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Aufbereitung des Logs für das Dokument des Log-Navigators (ohne laufende IDE). */
class LogNavigatorViewTest {

    @Test
    void ansiUndFortschrittVerschwindenZeilenBleibenGleichGezaehlt() {
        String log = "\u001B[1;34m[INFO]\u001B[m Scanning\r\n"
                + "Progress (1): 1/4 kB\rProgress (1): 4 kB\r\n"
                + "[ERROR] x\n";
        assertEquals(List.of("[INFO] Scanning", "Progress (1): 4 kB", "[ERROR] x"), LogNavigatorView.clean(log));
        assertEquals(List.of(), LogNavigatorView.clean(""));
    }
}
