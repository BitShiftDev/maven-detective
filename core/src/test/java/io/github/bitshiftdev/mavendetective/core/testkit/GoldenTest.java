package io.github.bitshiftdev.mavendetective.core.testkit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldenTest {

    @Test
    void normalizationReplacesTheLongestPathFirstAndEndsWithOneNewline() {
        Map<String, String> paths = new LinkedHashMap<String, String>();
        paths.put("/t", "$TMP");
        paths.put("/t/repo", "$REPO");
        assertEquals("$REPO/x and $TMP/y\nz\n", Golden.normalize("/t/repo/x and /t/y\r\nz\n\n\n", paths));
    }
}
