package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.testkit.Mutations;
import io.github.bitshiftdev.mavendetective.core.testkit.TestLogs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class LogPrefixTest {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final String TEXT = "Failed to execute goal x:y:1:z (default) on project app: Fehler";

    static List<Arguments> errorLineVariants() {
        List<Arguments> out = new ArrayList<Arguments>();
        for (Map.Entry<String, String> v : TestLogs.variants("ERROR", TEXT).entrySet()) {
            out.add(Arguments.of(v.getKey(), v.getValue()));
        }
        return out;
    }

    /** Der Parser bekommt Zeilen ohne ANSI-Farben (MavenLogParser entfernt sie vorher). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("errorLineVariants")
    void levelAndTextAreFoundBehindEveryKnownPrefix(String name, String line) {
        LogPrefix p = LogPrefix.parse(ANSI.matcher(line).replaceAll(""));

        assertEquals("ERROR", p.getLevel());
        assertEquals(TEXT, p.getText());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[FATAL] x   | FATAL   | x",
        "[WARNING] x | WARNING | x",
        "[WARN] x    | WARN    | x",
        "[INFO] x    | INFO    | x",
        "[DEBUG] x   | DEBUG   | x",
        "[INFO]      | INFO    | ``",
        "[ERROR]x    | ERROR   | x",
        "[ERROR]  x  | ERROR   | ` x`",
    })
    void everyMavenLevelIsRecognised(String line, String level, String text) {
        LogPrefix p = LogPrefix.parse(line);
        assertEquals(level, p.getLevel());
        assertEquals(text, p.getText());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[error] x",
        "[ERRORS] x",
        "Text [ERROR] x",
        "a b c d e [ERROR] x",
        "[sehr-langer-thread-name-der-ueber-vierundsechzig-zeichen-hinausgeht-und-weiter] [ERROR] x",
        "1 2 3 4 5 [ERROR] x",
    })
    void levelIsOnlyRecognisedAtTheStartOrBehindAShortPrefix(String line) {
        assertNull(LogPrefix.parse(line).getLevel(), line);
    }

    @Test
    void atMostFourPrefixTokensAreSkipped() {
        assertNotNull(LogPrefix.parse("06:48:01 [main] 1234 01O [ERROR] x").getLevel());
        assertNull(LogPrefix.parse("06:48:01 [main] 1234 01O 99 [ERROR] x").getLevel());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "2026-11-12T06:48:01Z Downloading x       | Downloading x",
        "2026/11/12 06:48:01 Downloading x        | Downloading x",
        "12.11.2026 x                             | 12.11.2026 x",
        "06:48:01 [main] Downloading x            | Downloading x",
        "[06:48:01] Downloading x                 | Downloading x",
        "[main] Downloading x                     | [main] Downloading x",
        "1234 Downloading x                       | 1234 Downloading x",
        "3 errors                                 | 3 errors",
        "`\tat org.x.Y.z(Y.java:1)`              | `\tat org.x.Y.z(Y.java:1)`",
    })
    void withoutLevelOnlyADateOrTimePrefixIsStripped(String line, String text) {
        LogPrefix p = LogPrefix.parse(line);
        assertNull(p.getLevel());
        assertEquals(text, p.getText());
    }

    @Test
    void emptyAndBlankLinesStayAsTheyAre() {
        assertEquals("", LogPrefix.parse("").getText());
        assertEquals("   ", LogPrefix.parse("   ").getText());
        assertNull(LogPrefix.parse("[").getLevel());
        assertEquals("[]", LogPrefix.parse("[]").getText());
    }

    @Test
    void veryLongLinesAreParsedInLinearTime() {
        StringBuilder sb = new StringBuilder("2026-11-12T06:48:01Z ");
        for (int i = 0; i < 200_000; i++) {
            sb.append(i % 10 == 0 ? ' ' : '[');
        }
        String line = sb.toString();
        String spaces = "[ERROR] " + repeat(' ', 200_000) + "x";

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            LogPrefix.parse(line);
            LogPrefix.parse(spaces);
        });
    }

    @Test
    void randomLinesNeverThrow() {
        List<String> lines = new ArrayList<String>();
        for (String v : TestLogs.variants("ERROR", TEXT).values()) {
            lines.addAll(Mutations.of(ANSI.matcher(v).replaceAll(""), 500, v.length()));
        }
        for (String line : lines) {
            LogPrefix p = LogPrefix.parse(line);
            assertNotNull(p.getText(), line);
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
