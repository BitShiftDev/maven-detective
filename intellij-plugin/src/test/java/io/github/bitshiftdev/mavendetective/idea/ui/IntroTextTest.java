package io.github.bitshiftdev.mavendetective.idea.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntroTextTest {

    /** Ein Zeichen = ein Pixel. */
    private static List<String> wrap(String text, int max) {
        return IntroText.wrap(text, max, String::length);
    }

    @Test
    void passtInEineZeile() {
        assertEquals(List.of("Noch nicht geprüft."), wrap("Noch nicht geprüft.", 40));
    }

    @Test
    void brichtAnLeerzeichenUm() {
        assertEquals(List.of("Vergleicht die Klassen,", "die die Quellen nennen,", "mit den Dependencies."),
                wrap("Vergleicht die Klassen, die die Quellen nennen, mit den Dependencies.", 24));
    }

    @Test
    void zuLangesWortBleibtGanz() {
        assertEquals(List.of("in", "org.springframework.boot:spring-boot", "fehlt"),
                wrap("in org.springframework.boot:spring-boot fehlt", 10));
    }

    @Test
    void ohneBreiteKeinUmbruch() {
        assertEquals(List.of("Vergleicht die Klassen"), wrap("Vergleicht die Klassen", 0));
    }
}
