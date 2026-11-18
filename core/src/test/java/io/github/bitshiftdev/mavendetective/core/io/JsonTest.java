package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.testkit.Mutations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    // --- Lesen -----------------------------------------------------------------------------------

    @Test
    void numbersBecomeLongDoubleOrBigInteger() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(Long.MAX_VALUE, Json.parse(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, Json.parse(String.valueOf(Long.MIN_VALUE)));
        assertEquals(new BigInteger("9223372036854775808"), Json.parse("9223372036854775808"));
        assertEquals(1.5e-3, Json.parse("1.5E-3"));
    }

    @Test
    void escapesAreDecoded() {
        assertEquals("\"\\/\b\f\n\r\t\u00e4\uD83D\uDE00", Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00E4\\ud83d\\ude00\""));
    }

    @Test
    void whitespaceAroundTokensIsAllowed() {
        Map<String, Object> expected = new LinkedHashMap<String, Object>();
        expected.put("a", Arrays.asList(1L, true, null));
        assertEquals(expected, Json.parse(" \n{ \"a\" :\t[ 1 , true , null ] }\r\n"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "``", "{", "[1,]", "{\"a\" 1}", "{\"a\":1,}", "{a:1}", "tru", "nul", "\"abc", "\"\\x\"", "\"\\u12\"",
        "\"\\u12zz\"", "-", "1 2", "1.2.3", "[1 2]", "{\"a\":1}}", "'a'",
    })
    void malformedInputIsRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(json));
    }

    @Test
    void parseObjectRejectsOtherValues() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("\"x\""));
    }

    @Test
    void errorMessageNamesThePosition() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": x}"));
        assertTrue(e.getMessage().contains("Position 6"), e.getMessage());
    }

    // --- Schreiben -------------------------------------------------------------------------------

    @Test
    void controlCharactersAreEscapedAndReadBack() {
        String text = "a\u0000b\u001Fc\"d\\e\nf\rg\th\u00e4\uD83D\uDE00";

        String json = Json.write(text);

        assertTrue(json.contains("\\u0000") && json.contains("\\u001f"), json);
        assertEquals(text, Json.parse(json));
    }

    @Test
    void nullValuesInObjectsAreLeftOutButKeptInLists() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("a", null);
        m.put("b", Arrays.asList("x", null));

        assertEquals("{\"b\":[\"x\",null]}", Json.write(m));
    }

    @Test
    void unknownObjectsAreWrittenAsTheirText() {
        assertEquals("\"PT1S\"", Json.write(Duration.ofSeconds(1)));
    }

    @Test
    void nonFiniteNumbersAreWrittenAsNullSoTheFileStaysReadable() {
        List<Object> values = new ArrayList<Object>(Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5));

        String json = Json.write(values);

        assertEquals("[null,null,null,1.5]", json);
        assertEquals(Arrays.asList(null, null, null, 1.5), Json.parse(json));
    }

    @Test
    void roundTripKeepsNestedStructures() {
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("big", new BigInteger("123456789012345678901234567890"));
        inner.put("list", Arrays.asList(1L, -2.5, "s", false, Arrays.asList()));
        Map<String, Object> outer = new LinkedHashMap<String, Object>();
        outer.put("inner", inner);
        outer.put("empty", new LinkedHashMap<String, Object>());

        assertEquals(outer, Json.parse(Json.write(outer)));
    }

    @Test
    void maximumDepthIsReadableButOneMoreIsNot() {
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH; i++) {
            ok.append('[');
        }
        for (int i = 0; i < Json.MAX_DEPTH; i++) {
            ok.append(']');
        }
        Json.parse(ok.toString());
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[" + ok + "]"));
    }

    // --- Robustheit ------------------------------------------------------------------------------

    @Test
    void brokenEventLinesFailWithIllegalArgumentOnly() {
        String valid = "{\"type\":\"mojoFailed\",\"project\":\"g:a:1.0\",\"mojo\":\"g:a:v:goal\",\"execution\":\"default\","
                + "\"chain\":[{\"class\":\"java.lang.X\",\"message\":\"a \\\"b\\\" \\u00e4\\n\"}],\"line\":-12,\"x\":1.5e3,"
                + "\"ok\":true,\"none\":null}";
        Json.parse(valid);
        List<String> variants = Mutations.of(valid, 5000, 11);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < variants.size(); i++) {
                try {
                    Json.parse(variants.get(i));
                } catch (IllegalArgumentException expected) {
                    // kaputte Zeile: EventLog überspringt sie
                } catch (RuntimeException e) {
                    throw new AssertionError("Variante " + i + ": " + e + "\n" + variants.get(i), e);
                }
            }
        });
    }

    @Test
    void readingNullLiteralGivesNull() {
        assertNull(Json.parse("null"));
    }
}
