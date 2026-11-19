package io.github.bitshiftdev.mavendetective.core.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {

    private static CliOptions parse(String... args) throws CliOptions.UsageException {
        String[] all = new String[args.length + 1];
        all[0] = "cmd";
        System.arraycopy(args, 0, all, 1, args.length);
        return CliOptions.parse(all);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "project    | /w/app",
            "catalog    | extra.yaml",
            "module     | sub/module",
            "tree       | -",
            "local-repo | /r",
            "pin        | g:a:1",
            "nexus      | https://nexus/",
            "lang       | en",
    })
    void valueWithEqualsSignEqualsValueAsNextArgument(String option, String value) throws Exception {
        CliOptions separate = parse("--" + option, value);
        CliOptions joined = parse("--" + option + "=" + value);
        assertEquals(describe(separate), describe(joined));
        assertTrue(describe(joined).contains(value), describe(joined));
    }

    private static String describe(CliOptions o) {
        return o.target + "|" + o.lang + "|" + o.project + "|" + o.catalog + "|" + o.module + "|" + o.tree + "|" + o.localRepo
                + "|" + o.pin + "|" + o.nexus + "|" + o.context;
    }

    @Test
    void valueAfterEqualsSignMayContainEqualsSignsAndBeEmpty() throws Exception {
        assertEquals("a=b", parse("--project=a=b").project);
        assertEquals("", parse("--project=").project);
    }

    @Test
    void repeatedOptionKeepsTheLastValue() throws Exception {
        CliOptions o = parse("--project", "a", "--project=b", "--markdown", "--markdown");
        assertEquals("b", o.project);
        assertTrue(o.markdown);
    }

    @Test
    void pathsWithSpacesAndUmlautsStayOneArgument() throws Exception {
        CliOptions o = parse("C:\\Users\\Jörg Müller\\build log.txt", "--project", "/home/jörg/mein projekt");
        assertEquals("C:\\Users\\Jörg Müller\\build log.txt", o.target);
        assertEquals("/home/jörg/mein projekt", o.project);
    }

    @Test
    void everyFlagIsRecognised() throws Exception {
        CliOptions o = parse("--markdown", "--all", "--remove", "--exclude", "--declare", "--manage", "--fetch", "--apply",
                "--id", "--network", "--strip");
        assertTrue(o.markdown && o.all && o.remove && o.exclude && o.declare && o.manage && o.fetch && o.apply
                && o.idOnly && o.network && o.strip);
        assertNull(o.target);
    }

    @Test
    void optionsMayComeBeforeAndAfterTheTarget() throws Exception {
        CliOptions o = parse("--markdown", "build.log", "--project", "p");
        assertEquals("build.log", o.target);
        assertEquals("p", o.project);
    }

    @Test
    void dashIsTheTargetStdin() throws Exception {
        assertEquals("-", parse("-").target);
        assertEquals("-", parse("--tree", "-").tree);
    }

    @Test
    void afterDoubleDashEverythingIsTarget() throws Exception {
        assertEquals("--weird.log", parse("--", "--weird.log").target);
        assertEquals("-x.log", parse("--markdown", "--", "-x.log").target);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "7, 7", "-3, 0", "' 5 ', 5", "999999999, 999999999"})
    void contextIsANonNegativeNumber(String value, int expected) throws Exception {
        assertEquals(expected, parse("--context", value).context);
        assertEquals(expected, parse("--context=" + value).context);
    }

    @Test
    void contextDefaultsToThree() throws Exception {
        assertEquals(3, parse().context);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "", "1.5", "99999999999", "0x10"})
    void contextRejectsWhatIsNotAnInt(String value) {
        CliOptions.UsageException e = assertThrows(CliOptions.UsageException.class, () -> parse("--context", value));
        assertTrue(e.getMessage().startsWith("--context erwartet eine Zahl"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "--bogus                | Unbekannte Option: --bogus",
            "--bogus=1              | Unbekannte Option: --bogus",
            "-v                     | Unbekannte Option: -v",
            "--project              | Option --project braucht einen Wert",
            "--all=true             | Option --all hat keinen Wert",
            "--lang=klingon         | --lang erwartet en oder de, nicht „klingon“",
            "a b                    | Unerwartetes Argument: b",
    })
    void mistakesAreRejectedWithAMessage(String args, String message) {
        CliOptions.UsageException e = assertThrows(CliOptions.UsageException.class, () -> parse(args.split(" ")));
        assertEquals(message, e.getMessage());
    }

    @Test
    void doubleDashAloneIsAllowed() throws Exception {
        assertNull(parse("--").target);
    }

    @Test
    void optionWithoutValueBeforeAnotherOptionIsMissingItsValue() {
        assertThrows(CliOptions.UsageException.class, () -> parse("--project", "--markdown"));
    }

    @Test
    void languageIsFoundBeforeParsingAndTheLastOneWins() {
        assertEquals("en", CliOptions.language(new String[] {"cmd", "--lang", "de", "--lang=en", "--bogus"}));
        assertNull(CliOptions.language(new String[] {"cmd", "--", "--lang", "en"}));
        assertNull(CliOptions.language(new String[] {"cmd", "--lang"}));
        assertNull(CliOptions.language(new String[] {"--lang", "en"}), "args[0] ist der Befehl");
    }
}
