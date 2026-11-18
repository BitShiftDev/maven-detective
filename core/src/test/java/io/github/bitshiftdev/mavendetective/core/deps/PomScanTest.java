package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.PomScan.DepEntry;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.Range;
import io.github.bitshiftdev.mavendetective.core.testkit.Mutations;
import io.github.bitshiftdev.mavendetective.core.testkit.TestPom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomScanTest {

    // --- Tokenizer -------------------------------------------------------------------------------

    @Test
    void tagEndIgnoresGreaterThanInsideQuotedAttributes() {
        String xml = "<a b=\"x>y\" c='>'>rest";
        assertEquals(xml.indexOf(">rest"), PomScan.tagEnd(xml, 0));
    }

    @Test
    void tagEndIsMinusOneForAnUnfinishedTag() {
        assertEquals(-1, PomScan.tagEnd("<a b=\"x>", 0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "<!-- a > b -->X     | 14",
        "<![CDATA[<x>]]>X    | 15",
        "<?xml version='1'?>X | 19",
        "<!DOCTYPE project>X  | 18",
    })
    void skipSpecialJumpsBehindCommentsCdataInstructionsAndDoctype(String xml, int expected) {
        assertEquals(expected, PomScan.skipSpecial(xml, 0));
        assertEquals('X', xml.charAt(expected));
    }

    @Test
    void skipSpecialLeavesOrdinaryTagsAlone() {
        assertEquals(-1, PomScan.skipSpecial("<project>", 0));
        assertEquals(-1, PomScan.skipSpecial("</project>", 0));
    }

    @Test
    void unclosedCommentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PomScan.skipSpecial("<!-- offen", 0));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "<groupId>       | groupId",
        "<pom:groupId>   | groupId",
        "<groupId a='1'> | groupId",
        "<groupId/>      | groupId",
        "<groupId\t>     | groupId",
    })
    void tagNameDropsNamespacePrefixAndAttributes(String tag, String expected) {
        assertEquals(expected, PomScan.tagName(tag, 1, tag.indexOf('>')));
    }

    // --- Werte -----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "<v> org.x </v>                     | org.x",
        "<v>org&#46;x</v>                   | org.x",
        "<v>org&#x2E;x</v>                  | org.x",
        "<v>a&amp;b&lt;c&gt;&quot;&apos;</v> | a&b<c>\"'",
        "<v><![CDATA[a<b&c]]></v>           | a<b&c",
        "<v>1<!-- LTS -->.0</v>             | 1.0",
        "<v>a & b</v>                       | a & b",
        "<v>a &unknown; b</v>               | a &unknown; b",
        "<v>&#xZZ;</v>                      | &#xZZ;",
        "<v>&#1114112;</v>                  | &#1114112;",
        "<v>&#;</v>                         | &#;",
        "<v>&#128512;</v>                   | 😀",
    })
    void textReadsValuesLikeAnXmlParser(String xml, String expected) {
        assertEquals(expected, PomScan.text(xml, contentOf(xml)));
    }

    @Test
    void valueSpanKeepsCommentsAtBothEdgesOutside() {
        String xml = "<v> <!-- vorne --> 1.2 <!-- hinten --> </v>";
        int[] span = PomScan.valueSpan(xml, contentOf(xml));
        assertEquals("1.2", xml.substring(span[0], span[1]));
    }

    @Test
    void valueSpanOfOnlyACommentIsEmpty() {
        String xml = "<v><!-- nichts --></v>";
        int[] span = PomScan.valueSpan(xml, contentOf(xml));
        assertEquals(span[0], span[1]);
    }

    // --- Struktur --------------------------------------------------------------------------------

    @Test
    void dependenciesAndManagedEntriesAreSeparatedAndProfilesAndPluginsIgnored() {
        String xml = TestPom.of("org.app", "app", "1.0")
                .managed("org.lib", "managed", "1")
                .dependency("org.lib", "direct", "2", "<scope>test</scope>")
                .profileDependency("ci", "org.lib", "in-profile", "3")
                .xml()
                .replace("</project>", "    <build><plugins><plugin><dependencies><dependency>"
                        + "<groupId>org.lib</groupId><artifactId>plugin-dep</artifactId>"
                        + "</dependency></dependencies></plugin></plugins></build>\n</project>");

        PomScan s = PomScan.of(xml);

        assertEquals(2, s.deps.size());
        assertEquals(1, s.managed.size());
        assertEquals("managed", s.managed.get(0).fields.get("artifactId"));
        DepEntry direct = s.deps.get(1);
        assertFalse(direct.managed);
        assertEquals("test", direct.fields.get("scope"));
        assertEquals("<dependency>", xml.substring(direct.range.tagStart, direct.range.tagStart + 12));
    }

    @Test
    void projectAndParentFieldsAreRead() {
        String xml = TestPom.of(null, "app", null).parent("org.parent", "parent", "7").xml();

        PomScan s = PomScan.of(xml);

        assertEquals("app", s.projectFields.get("artifactId"));
        assertNull(s.projectFields.get("groupId"));
        assertEquals("org.parent", s.parentFields.get("groupId"));
        assertEquals("7", PomScan.text(xml, s.parentRanges.get("version")));
    }

    @Test
    void duplicatePropertyTakesTheLastLikeMaven() {
        String xml = "<project><properties><v>1</v><v>2</v></properties></project>";

        PomScan s = PomScan.of(xml);

        assertEquals("2", s.propertyValues.get("v"));
        assertEquals(xml.lastIndexOf("<v>"), s.properties.get("v").tagStart);
    }

    @Test
    void nestedElementsInsidePropertiesAreNoProperties() {
        PomScan s = PomScan.of("<project><properties><a><b>x</b></a></properties></project>");

        assertTrue(s.propertyValues.containsKey("a"));
        assertFalse(s.propertyValues.containsKey("b"));
    }

    @Test
    void selfClosingContainersAreMarked() {
        String xml = "<project><dependencies/><properties /></project>";

        PomScan s = PomScan.of(xml);

        assertTrue(s.dependencies.selfClosing);
        assertTrue(s.propertiesRange.selfClosing);
        assertEquals("<dependencies/>", xml.substring(s.dependencies.tagStart, s.dependencies.tagEnd));
    }

    @Test
    void namespacePrefixedPomIsUnderstood() {
        String xml = "<pom:project xmlns:pom='http://maven.apache.org/POM/4.0.0'><pom:dependencies><pom:dependency>"
                + "<pom:groupId>g</pom:groupId><pom:artifactId>a</pom:artifactId></pom:dependency></pom:dependencies></pom:project>";

        PomScan s = PomScan.of(xml);

        assertEquals(1, s.deps.size());
        assertEquals("a", s.deps.get(0).fields.get("artifactId"));
        assertTrue(s.projectEnd > 0);
    }

    @Test
    void exclusionsBelongToTheirDependency() {
        String xml = "<project><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId>"
                + "<exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>"
                + "</dependency></dependencies></project>";

        DepEntry d = PomScan.of(xml).deps.get(0);

        assertEquals(1, d.exclusions.size());
        assertEquals("y", d.exclusions.get(0).fields.get("artifactId"));
        assertNotNull(d.exclusionsRange);
    }

    /** Ein leer geschriebenes {@code <exclusion/>} darf den Scan danach nicht aus dem Tritt bringen. */
    @Test
    void selfClosingExclusionDoesNotBreakTheRestOfThePom() {
        String xml = "<project><dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId>"
                + "<exclusions><exclusion/></exclusions></dependency></dependencies>"
                + "<build><finalName>app</finalName></build></project>";

        PomScan s = PomScan.of(xml);

        assertEquals(1, s.deps.size());
        assertTrue(s.deps.get(0).exclusions.isEmpty());
    }

    @Test
    void mismatchedClosingTagIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PomScan.of("<project><a></b></project>"));
        assertTrue(e.getMessage().contains("</b>"), e.getMessage());
    }

    @Test
    void unfinishedTagIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PomScan.of("<project><a"));
    }

    // --- Ausdrücke -------------------------------------------------------------------------------

    @Test
    void resolveUsesProjectParentAndProperties() {
        PomScan s = PomScan.of(TestPom.of(null, "app", null).parent("org.p", "p", "3")
                .property("lib", "${project.groupId}.lib").xml());

        assertEquals("org.p.lib", s.resolve("${lib}"));
        assertEquals("3", s.resolve("${pom.version}"));
        assertEquals("3", s.resolve("${project.parent.version}"));
        assertEquals("app-3", s.resolve("${artifactId}-${version}"));
        assertEquals("${unbekannt}", s.resolve("${unbekannt}"));
    }

    @Test
    void cyclicPropertiesStopResolving() {
        PomScan s = PomScan.of(TestPom.of("g", "a", "1").property("x", "${y}").property("y", "${x}").xml());

        String resolved = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> s.resolve("${x}"));

        assertTrue(resolved.contains("${"), resolved);
    }

    @Test
    void groupIdWithUnresolvableExpressionMatchesByArtifactId() {
        String xml = TestPom.of("org.app", "app", "1").dependency("${parent.group}", "lib", "1").xml();
        PomScan s = PomScan.of(xml);

        assertTrue(s.is(s.deps.get(0).fields, "irgendwas", "lib"));
        assertFalse(s.is(s.deps.get(0).fields, "irgendwas", "other"));
    }

    @Test
    void artifactIdIsResolvedThroughProperties() {
        String xml = TestPom.of("org.app", "app", "1").property("name", "lib").dependency("org.app", "${name}", "1").xml();
        PomScan s = PomScan.of(xml);

        assertTrue(s.is(s.deps.get(0).fields, "org.app", "lib"));
    }

    // --- Einrückung ------------------------------------------------------------------------------

    @Test
    void indentUnitFollowsTheFirstChildOfProject() {
        String[][] cases = {
            {"<project>\n\t<modelVersion/>\n</project>", "\t"},
            {"<project>\n  <modelVersion/>\n</project>", "  "},
            {"<project>\n    <modelVersion/>\n</project>", "    "},
            {"<project><modelVersion/></project>", "  "},
        };
        for (String[] c : cases) {
            assertEquals(c[1], PomScan.of(c[0]).indentUnit(c[0]), c[0]);
        }
    }

    // --- Robustheit ------------------------------------------------------------------------------

    @Test
    void brokenPomsFailWithIllegalArgumentOnly() {
        String valid = TestPom.of("org.app", "app", "1.0").parent("org.p", "p", "1").property("v", "1")
                .managed("g", "m", "${v}").dependency("g", "d", "1", "<exclusions><exclusion><groupId>x</groupId>"
                        + "<artifactId>y</artifactId></exclusion></exclusions>")
                .profileDependency("ci", "g", "p", "1").xml()
                .replace("<modelVersion>", "<!-- Kommentar --><modelVersion>");
        List<String> variants = Mutations.of(valid, 3000, 42);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < variants.size(); i++) {
                try {
                    PomScan.of(variants.get(i));
                } catch (IllegalArgumentException expected) {
                    // kaputte POM: erlaubt, solange es diese Ausnahme ist
                } catch (RuntimeException e) {
                    throw new AssertionError("Variante " + i + ": " + e + "\n" + variants.get(i), e);
                }
            }
        });
    }

    /** Bereich des Inhalts des äußersten Elements ({@code <v>…</v>}). */
    private static Range contentOf(String xml) {
        Range r = new Range();
        r.tagStart = 0;
        r.contentStart = xml.indexOf('>') + 1;
        r.contentEnd = xml.lastIndexOf("</");
        r.tagEnd = xml.length();
        return r;
    }
}
