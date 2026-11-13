package io.github.bitshiftdev.mavendetective.idea.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlTest {

    @Test
    void maskiertSonderzeichen() {
        assertEquals("&lt;dependency&gt; &amp; &quot;x&quot;", Html.esc("<dependency> & \"x\""));
        assertEquals("", Html.esc(null));
    }

    @Test
    void dateiLinkZeigtNameUndZeile() {
        assertEquals("<a href=\"/p/api/pom.xml:12\">pom.xml:12</a>", Html.fileLink("/p/api/pom.xml", 12));
        assertEquals("<a href=\"/p/api/pom.xml\">pom.xml</a>", Html.fileLink("/p/api/pom.xml", 0));
    }

    @Test
    void pomLinkNenntDasModulVerzeichnis() {
        assertEquals("<a href=\"/p/api/pom.xml:7\">api/pom.xml:7</a>", Html.pomLink("/p/api/pom.xml", 7));
    }

    @Test
    void ortOhneZeile() {
        assertEquals("/p/pom.xml", Locations.of("/p/pom.xml", 0));
        assertEquals("/p/pom.xml:3", Locations.of("/p/pom.xml", 3));
    }
}
