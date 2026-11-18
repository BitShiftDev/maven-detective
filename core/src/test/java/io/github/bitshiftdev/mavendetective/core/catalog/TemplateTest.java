package io.github.bitshiftdev.mavendetective.core.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateTest {

    private static final Map<String, String> VARS = new HashMap<String, String>();

    static {
        VARS.put("artifact", "org.x:lib");
        VARS.put("empty", "");
        VARS.put("member", "run()");
        VARS.put("path", "C:\\Program Files\\$HOME");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiterString = "=>", quoteCharacter = '`', value = {
        "Fehlt {artifact}. => Fehlt org.x:lib.",
        "Fehlt {missing}. => Fehlt .",
        "Fehlt {missing|ein Artifact}. => Fehlt ein Artifact.",
        "Fehlt {empty|ein Artifact}. => Fehlt ein Artifact.",
        "{empty,member|nichts} => run()",
        "{missing,empty|nichts} => nichts",
        "{missing|}x => x",
        "Pfad {path} => Pfad C:\\Program Files\\$HOME",
        "kein {Platzhalter => kein {Platzhalter",
        "{1abc} und {a-b} => {1abc} und {a-b}",
        "{missing|mit {klammer}} => {missing|mit }",
    })
    void placeholdersAreFilledFromVariablesOrFallback(String template, String expected) {
        assertEquals(expected, Template.render(template, VARS));
    }

    @Test
    void renderCollapsesSpacesLeftByEmptyPlaceholders() {
        assertEquals("Version von lib", Template.render("Version {missing} von  lib ", VARS));
    }

    @Test
    void renderParameterKeepsSpacesVerbatim() {
        assertEquals("a  b", Template.renderParameter("a {missing} b", VARS));
        assertEquals(" C:\\Program Files\\$HOME ", Template.renderParameter(" {path} ", VARS));
    }

    @Test
    void nullTemplateIsNull() {
        assertNull(Template.render(null, VARS));
        assertNull(Template.renderParameter(null, VARS));
    }
}
