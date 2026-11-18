package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor.Edit;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomTextTest {

    // --- Escaping und Blöcke ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "1.0         | 1.0",
        "a&b         | a&amp;b",
        "<x>         | &lt;x&gt;",
        "&amp;       | &amp;amp;",
        "\"quoted'\" | \"quoted'\"",
    })
    void escapeReplacesOnlyCharactersThatBreakElementContent(String value, String expected) {
        assertEquals(expected, PomText.escape(value));
    }

    @Test
    void escapeOfNullIsNullAndPlainValuesAreNotCopied() {
        assertNull(PomText.escape(null));
        String plain = "org.example";
        assertSame(plain, PomText.escape(plain));
    }

    @Test
    void elementEscapesItsValue() {
        assertEquals("<version>1&lt;2</version>", PomText.element("version", "1<2"));
    }

    @Test
    void dependencyBlockLeavesOutDefaults() {
        String block = PomText.dependencyBlock("g", "a", "", "compile", "jar", "", "  ", "  ", "\n");

        assertEquals("  <dependency>\n"
                + "    <groupId>g</groupId>\n"
                + "    <artifactId>a</artifactId>\n"
                + "  </dependency>\n", block);
    }

    @Test
    void dependencyBlockWritesNonDefaultsInMavenOrder() {
        String block = PomText.dependencyBlock("g", "a", "1", "test", "test-jar", "tests", "", "\t", "\r\n");

        assertEquals("<dependency>\r\n"
                + "\t<groupId>g</groupId>\r\n"
                + "\t<artifactId>a</artifactId>\r\n"
                + "\t<version>1</version>\r\n"
                + "\t<type>test-jar</type>\r\n"
                + "\t<classifier>tests</classifier>\r\n"
                + "\t<scope>test</scope>\r\n"
                + "</dependency>\r\n", block);
    }

    @Test
    void exclusionBlockUsesIndentAndNewline() {
        assertEquals("    <exclusion>\n      <groupId>x</groupId>\n      <artifactId>y</artifactId>\n    </exclusion>\n",
                PomText.exclusionBlock("x", "y", "    ", "  ", "\n"));
    }

    // --- Zeilen ----------------------------------------------------------------------------------

    @Test
    void newlineFollowsTheFile() {
        assertEquals("\r\n", PomText.newline("<a>\r\n</a>"));
        assertEquals("\n", PomText.newline("<a>\n</a>"));
        assertEquals("\n", PomText.newline("<a/>"));
    }

    @Test
    void linePositionsAreCountedFromOne() {
        String xml = "a\nbb\r\nccc";

        assertEquals(1, PomText.lineOf(xml, 0));
        assertEquals(2, PomText.lineOf(xml, 2));
        assertEquals(3, PomText.lineOf(xml, xml.length()));
        assertEquals(2, PomText.lineStart(xml, 4));
        assertEquals(5, PomText.lineEnd(xml, 2));
        assertEquals(xml.length(), PomText.lineEnd(xml, 7));
    }

    @Test
    void lineIndentReadsSpacesAndTabsOnly() {
        String xml = "<a>\n \t <b>x</b>\n</a>";

        assertEquals(" \t ", PomText.lineIndent(xml, xml.indexOf("<b>")));
        assertTrue(PomText.onlyIndentBefore(xml, xml.indexOf("<b>")));
        assertFalse(PomText.onlyIndentBefore(xml, xml.indexOf("x</b>")));
    }

    // --- Edits -----------------------------------------------------------------------------------

    @Test
    void insertBeforeATagOnItsOwnLineStartsAtTheLineStart() {
        String xml = "<a>\n  <b/>\n</a>";
        int at = xml.indexOf("<b/>");

        Edit e = PomText.insertBefore(xml, at, "  <new/>\n", "  ", "\n", "test", "<new/>");

        assertEquals("<a>\n  <new/>\n  <b/>\n</a>", e.apply(xml));
        assertEquals(2, e.line);
    }

    @Test
    void insertBeforeATagInsideALineGivesTheBlockItsOwnLine() {
        String xml = "<a><b/></a>";
        int at = xml.indexOf("<b/>");

        Edit e = PomText.insertBefore(xml, at, "  <new/>\n", "", "\n", "test", "<new/>");

        assertEquals("<a>\n  <new/>\n<b/></a>", e.apply(xml));
        assertEquals(2, e.line);
    }

    @Test
    void insertIntoSelfClosingContainerOpensItKeepingPrefixAndAttributes() {
        String xml = "<project>\n  <pom:dependencies a=\"1\" />\n</project>";
        Range r = range(xml, "<pom:dependencies a=\"1\" />");
        r.selfClosing = true;
        r.contentStart = r.tagStart;
        r.contentEnd = r.tagStart;

        Edit e = PomText.insertInto(xml, r, "    <dependency/>\n", "  ", "\n", "test", "<dependency/>");

        assertEquals("<project>\n  <pom:dependencies a=\"1\">\n    <dependency/>\n  </pom:dependencies>\n</project>", e.apply(xml));
        assertEquals(3, e.line);
    }

    @Test
    void replaceContentKeepsCommentsAroundTheValue() {
        String xml = "<version> <!-- LTS --> 1.0 <!-- bis 2027 --> </version>";
        Range r = range(xml, xml);
        r.contentStart = xml.indexOf('>') + 1;
        r.contentEnd = xml.lastIndexOf("</");

        assertEquals("<version> <!-- LTS --> 2.0&amp;x <!-- bis 2027 --> </version>",
                PomText.replaceContent(xml, r, "2.0&x", "test").apply(xml));
    }

    @Test
    void replaceContentOfSelfClosingElementOpensIt() {
        String xml = "<p><v/></p>";
        Range r = range(xml, "<v/>");
        r.selfClosing = true;

        assertEquals("<p><v>1</v></p>", PomText.replaceContent(xml, r, "1", "test").apply(xml));
    }

    @Test
    void removeTakesWholeLinesWhenTheElementStandsAlone() {
        String xml = "<a>\r\n  <b>x</b>  \r\n  <c/>\r\n</a>";

        Edit e = PomText.remove(xml, range(xml, "<b>x</b>"), "test");

        assertEquals("<a>\r\n  <c/>\r\n</a>", e.apply(xml));
        assertEquals(2, e.line);
    }

    @Test
    void removeKeepsTheLineWhenOtherContentSharesIt() {
        String xml = "<a><b>x</b><c/></a>";

        assertEquals("<a><c/></a>", PomText.remove(xml, range(xml, "<b>x</b>"), "test").apply(xml));
    }

    @Test
    void removeOnTheLastLineWithoutNewline() {
        String xml = "<a/>\n<b/>";

        assertEquals("<a/>\n", PomText.remove(xml, range(xml, "<b/>"), "test").apply(xml));
    }

    @Test
    void onlyWhitespaceBesidesSeesCommentsInTheContainer() {
        String plain = "<c>\n  <e/>\n</c>";
        String commented = "<c>\n  <!-- alt --><e/>\n</c>";

        assertTrue(PomText.onlyWhitespaceBesides(plain, container(plain), range(plain, "<e/>")));
        assertFalse(PomText.onlyWhitespaceBesides(commented, container(commented), range(commented, "<e/>")));
    }

    /** Bereich des Elements {@code element} (erstes Vorkommen), Inhalt = alles zwischen den Tags. */
    private static Range range(String xml, String element) {
        Range r = new Range();
        r.tagStart = xml.indexOf(element);
        r.tagEnd = r.tagStart + element.length();
        r.contentStart = xml.indexOf('>', r.tagStart) + 1;
        int close = element.lastIndexOf("</");
        r.contentEnd = close < 0 ? r.contentStart : r.tagStart + close;
        return r;
    }

    /** Das äußerste Element {@code <c>…</c>}. */
    private static Range container(String xml) {
        Range r = new Range();
        r.tagStart = 0;
        r.tagEnd = xml.length();
        r.contentStart = xml.indexOf('>') + 1;
        r.contentEnd = xml.lastIndexOf("</c>");
        return r;
    }
}
