package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.io.MiniYaml.YamlException;
import io.github.bitshiftdev.mavendetective.core.testkit.Mutations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniYamlTest {

    private static final String CATALOG = "/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml";

    // --- Skalare ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "k: wert                | wert",
        "k: wert # Kommentar    | wert",
        "k: a#b                 | a#b",
        "k: 'it''s'             | it's",
        "k: 'a # b' # Kommentar | a # b",
        "k: \"tab\\there\"      | tab\there",
        "k: \"a\\\\b\"          | a\\b",
        "k: 'C:\\pfad\\d+'      | C:\\pfad\\d+",
        "k: 'null'              | null",
        "k: 1.10                | 1.10",
        "k: a: b                | a: b",
    })
    void scalarsAreReadAsStrings(String yaml, String expected) {
        assertEquals(expected, map(yaml).get("k"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {"k: ~", "k: null", "k:", "k: # nur Kommentar"})
    void missingValuesAreNull(String yaml) {
        Map<String, Object> m = map(yaml);
        assertTrue(m.containsKey("k"));
        assertNull(m.get("k"));
    }

    @Test
    void quotedKeysMayContainSpacesAndColons() {
        assertEquals("1", map("'a b': 1").get("a b"));
        assertEquals("2", map("\"x: y\": 2").get("x: y"));
    }

    // --- Block-Skalare ---------------------------------------------------------------------------

    @Test
    void literalBlockKeepsLinesAndEndsWithNewline() {
        assertEquals("eins\n  zwei\n\n# kein Kommentar\n", map("k: |\n  eins\n    zwei\n\n  # kein Kommentar\nnext: x").get("k"));
    }

    @Test
    void foldedBlockJoinsLinesButKeepsParagraphs() {
        assertEquals("a b\nc\n", map("k: >\n  a\n  b\n\n  c\n").get("k"));
        assertEquals("a\n\nc\n", map("k: >\n  a\n\n\n  c\n").get("k"));
        assertEquals("\na\n", map("k: >\n\n  a\n").get("k"));
    }

    @Test
    void strippedBlocksHaveNoTrailingNewline() {
        assertEquals("a\nb", map("k: |-\n  a\n  b\n").get("k"));
        assertEquals("a b", map("k: >-\n  a\n  b\n").get("k"));
    }

    @Test
    void blockScalarDoesNotSwallowTheNextKey() {
        Map<String, Object> m = map("k: |\n  text\nnext: x\n");
        assertEquals("text\n", m.get("k"));
        assertEquals("x", m.get("next"));
    }

    // --- Listen ----------------------------------------------------------------------------------

    @Test
    void flowSequenceRespectsQuotes() {
        assertEquals(Arrays.asList("a,b", "c]d", "e"), map("k: ['a,b', \"c]d\", e] # Kommentar").get("k"));
    }

    @Test
    void emptyFlowSequenceIsEmptyList() {
        assertEquals(Collections.emptyList(), map("k: []").get("k"));
    }

    @Test
    void blockSequenceMayStartAtTheIndentOfItsKey() {
        assertEquals(Arrays.asList("x", "y"), map("k:\n- x\n- y\nnext: z").get("k"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sequenceOfMappingsWithNestedLists() {
        List<Object> rules = (List<Object>) map("rules:\n  - id: a\n    list:\n    - x\n  - id: b\n").get("rules");

        assertEquals(2, rules.size());
        assertEquals(Collections.singletonList("x"), ((Map<String, Object>) rules.get(0)).get("list"));
        assertEquals("b", ((Map<String, Object>) rules.get(1)).get("id"));
    }

    @Test
    void emptySequenceItemIsNull() {
        assertEquals(Arrays.asList("a", null, "b"), map("k:\n  - a\n  -\n  - b").get("k"));
    }

    // --- Dokument --------------------------------------------------------------------------------

    @Test
    void emptyAndCommentOnlyDocumentsAreEmptyMaps() {
        assertEquals(Collections.emptyMap(), MiniYaml.parse(""));
        assertEquals(Collections.emptyMap(), MiniYaml.parse("# nur\n\n# Kommentare\n---\n"));
    }

    @Test
    void windowsLineEndingsReadLikeUnix() {
        assertEquals(MiniYaml.parse("a: 1\nb:\n  - x\nc: |\n  t\n"), MiniYaml.parse("a: 1\r\nb:\r\n  - x\r\nc: |\r\n  t\r\n"));
    }

    // --- Fehler mit Zeile ------------------------------------------------------------------------

    @ParameterizedTest(name = "Zeile {1}: {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "a: 1\\na: 2                | 2",
        "a: 1\\n\\tb: 2             | 2",
        "a: {x: 1}                  | 1",
        "a: \"\\q\"                 | 1",
        "a: 'x' y                   | 1",
        "a: 'offen                  | 1",
        "a: [x, y                   | 1",
        "a: [x, , y]                | 1",
        "a: [x] y                   | 1",
        "a:\\n  b: 1\\n    c: 2     | 3",
        "a: 1\\nkein schluessel     | 2",
        "- x\\n  - y                | 2",
    })
    void errorsNameTheLine(String yaml, int line) {
        YamlException e = assertThrows(YamlException.class, () -> MiniYaml.parse(yaml.replace("\\n", "\n").replace("\\t", "\t")));
        assertEquals(line, e.getLine(), e.getMessage());
        assertTrue(e.getMessage().startsWith("Zeile " + line + ":"), e.getMessage());
    }

    // --- Robustheit ------------------------------------------------------------------------------

    @Test
    void brokenCatalogsFailWithYamlExceptionOnly() throws IOException {
        String valid = resource(CATALOG);
        List<String> variants = Mutations.of(valid, 2000, 7);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (int i = 0; i < variants.size(); i++) {
                try {
                    MiniYaml.parse(variants.get(i));
                } catch (YamlException expected) {
                    // kaputter Katalog: erlaubt, solange die Zeile genannt wird
                } catch (RuntimeException e) {
                    throw new AssertionError("Variante " + i + ": " + e, e);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String yaml) {
        return (Map<String, Object>) MiniYaml.parse(yaml);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MiniYamlTest.class.getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
