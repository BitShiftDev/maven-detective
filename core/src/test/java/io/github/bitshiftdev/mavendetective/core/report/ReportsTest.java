package io.github.bitshiftdev.mavendetective.core.report;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.SessionInfo;
import io.github.bitshiftdev.mavendetective.core.snapshot.BuildSnapshot;
import io.github.bitshiftdev.mavendetective.core.snapshot.SnapshotDiff;
import io.github.bitshiftdev.mavendetective.core.testkit.Golden;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Konsolen-Karte und Markdown-Bericht als Golden Files (de/en), dazu die Kanten von {@code fixLine}/{@code transliterate}. */
class ReportsTest {

    private static final String STDOUT_ENCODING = "stdout.encoding";
    private final String stdoutEncoding = System.getProperty(STDOUT_ENCODING);

    @AfterEach
    void restoreEncoding() {
        if (stdoutEncoding == null) {
            System.clearProperty(STDOUT_ENCODING);
        } else {
            System.setProperty(STDOUT_ENCODING, stdoutEncoding);
        }
    }

    /** Volle Diagnose: sieben Belege (einer zu viel), überlange Logzeile mit Backtick, vier Fixes (einer zu viel). */
    static Diagnosis full() {
        List<Evidence> evidence = new ArrayList<Evidence>();
        evidence.add(Evidence.log("[ERROR] Failed to execute goal on project app: Could not resolve dependencies: "
                + "org.acme:lib:jar:1.0 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. "
                + "This failure was cached in the local repository and resolution is not reattempted until the update "
                + "interval of central has elapsed or updates are forced: `-U` → retry"));
        evidence.add(Evidence.file("Fehlversuch im lokalen Repository gemerkt", "/repo/org/acme/lib/1.0/lib-1.0.jar.lastUpdated", 0));
        evidence.add(Evidence.setting("Mirror „corp“ für *", "/home/u/.m2/settings.xml", 12));
        evidence.add(Evidence.environment("Maven 3.9.11, JDK 21"));
        evidence.add(Evidence.hint("Größe der Datei: 0 Bytes"));
        evidence.add(Evidence.file("Deklaration", "/w/app/pom.xml", 27));
        evidence.add(Evidence.hint("wird auf der Karte nicht mehr gezeigt"));
        List<FixAction> fixes = Arrays.asList(
                FixAction.of(FixAction.DELETE_FILES, "Fehlversuche löschen und neu bauen",
                        "files", "/repo/org/acme/lib/1.0/lib-1.0.jar.lastUpdated\n/repo/org/acme/lib/1.0/lib-1.0.pom.lastUpdated"),
                FixAction.of(FixAction.REBUILD, "Mit -U neu bauen", "args", "-U verify"),
                FixAction.of(FixAction.OPEN_FILE, "Deklaration öffnen", "file", "/w/app/pom.xml", "line", "27"),
                FixAction.of(FixAction.MANUAL, "Erscheint nicht auf der Konsole"));
        return new Diagnosis("missing-artifact", 3, "Artifact fehlt", "org.acme:lib:1.0 liegt in keinem Repository – "
                + "der Fehlversuch ist gemerkt", Confidence.CERTAIN, evidence, fixes);
    }

    static Diagnosis second() {
        return new Diagnosis("offline-mode", 2, "Offline-Modus", "Maven läuft offline", Confidence.LIKELY,
                Collections.<Evidence>emptyList(), Collections.<FixAction>emptyList());
    }

    static Diagnosis unknown() {
        return new Diagnosis(Diagnosis.UNKNOWN_RULE, 0, "Unbekannter Fehler", "Keine Regel passt", Confidence.LIKELY,
                Collections.singletonList(Evidence.log("[ERROR] something odd")), Collections.<FixAction>emptyList());
    }

    static SnapshotDiff changes() {
        BuildSnapshot green = snapshot("1.0").setSuccessful(true);
        BuildSnapshot red = snapshot("2.0").setJavaVersion("17.0.2").setSuccessful(false);
        return SnapshotDiff.compare(green, red);
    }

    private static BuildSnapshot snapshot(String libVersion) {
        BuildSnapshot s = new BuildSnapshot().setOrigin(BuildSnapshot.ORIGIN_EXTENSION).setMavenVersion("3.9.11")
                .setJavaVersion("21.0.4").setOffline(false).setUserSettings("/home/u/.m2/settings.xml");
        s.module("g:app").setVersion("1.0").setDependenciesResolved(true).getDependencies().put("org.acme:lib", libVersion);
        return s;
    }

    static BuildFailure failure() {
        BuildFailure f = new BuildFailure(BuildFailure.Source.EVENTS);
        SessionInfo s = f.getSession();
        s.setMavenVersion("3.9.11");
        s.setJavaVersion("21.0.4");
        s.setJavaHome("/usr/lib/jvm/java-21");
        s.setOffline(false);
        s.setUserSettings("/home/u/.m2/settings.xml");
        s.getActiveProfiles().add("ci");
        s.getGoals().addAll(Arrays.asList("clean", "verify"));
        return f;
    }

    private static String card(List<Diagnosis> diagnoses, SnapshotDiff changes) {
        return String.join("\n", Reports.consoleCard(diagnoses, changes));
    }

    /** Die Zeile „- OS: …“ hängt vom Rechner ab. */
    private static String withoutOs(String markdown) {
        return markdown.replaceAll("(?m)^- OS: .*$", "- OS: <os>");
    }

    @Test
    void cardWithEverything() {
        Golden.both("report/card-full", "txt", () -> card(Arrays.asList(full(), second(), unknown()), changes()));
    }

    @Test
    void cardWithoutEvidenceFixesOrNumber() {
        Golden.both("report/card-minimal", "txt", () -> card(Collections.singletonList(unknown()), null));
    }

    @Test
    void cardForConsoleWithoutUnicode() {
        Golden.both("report/card-ascii", "txt", () -> {
            StringBuilder sb = new StringBuilder();
            for (String line : Reports.consoleCard(Arrays.asList(full(), second()), changes())) {
                sb.append(Reports.transliterate(line)).append('\n');
            }
            return sb.toString();
        });
    }

    @Test
    void markdownWithEverything() {
        Golden.both("report/markdown-full", "md",
                () -> withoutOs(Reports.markdown(Arrays.asList(full(), second()), failure(), changes())));
    }

    @Test
    void markdownWithoutSessionAndFixes() {
        Golden.both("report/markdown-minimal", "md", () -> withoutOs(Reports.markdown(
                Collections.singletonList(unknown()), new BuildFailure(BuildFailure.Source.LOG))));
    }

    @Test
    void emptyDiagnosesGiveNoCardAndANoErrorReport() {
        assertTrue(Reports.consoleCard(Collections.<Diagnosis>emptyList(), changes()).isEmpty());
        Golden.both("report/markdown-empty", "md",
                () -> Reports.markdown(Collections.<Diagnosis>emptyList(), failure()));
    }

    @Test
    void cardStaysAsciiAfterTransliteration() {
        for (String line : Reports.consoleCard(Arrays.asList(full(), second(), unknown()), changes())) {
            String ascii = Reports.transliterate(line);
            assertTrue(ascii.chars().allMatch(c -> c < 128), ascii);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Größe übermäßig   | Groesse uebermaessig",
            "ÄÖÜ äöü ß         | AeOeUe aeoeue ss",
            "a · b – c — d     | a - b - c - d",
            "warte…            | warte...",
            "a → b             | a -> b",
            "„zitiert“ ”x”     | \"zitiert\" \"x\"",
            "Café naïve Ångström | Cafe naive Angstroem",
            "ł 中               | ? ?",
            "plain ascii 123   | plain ascii 123",
    })
    void transliterateReplacesWhatAsciiCannotShow(String in, String expected) {
        assertEquals(expected.trim(), Reports.transliterate(in.trim()));
    }

    @Test
    void transliterateTurnsASurrogatePairIntoOneQuestionMark() {
        assertEquals("ok ?", Reports.transliterate("ok 😀"));
    }

    @ParameterizedTest
    @CsvSource({"UTF-8, true", "UTF-16, true", "US-ASCII, false", "ISO-8859-1, false", "no-such-charset, false"})
    void consoleSupportsUnicodeFollowsTheStdoutEncoding(String encoding, boolean expected) {
        System.setProperty(STDOUT_ENCODING, encoding);
        assertEquals(expected, Reports.consoleSupportsUnicode());
    }

    @Test
    void fixLineToleratesMissingParameters() {
        assertEquals("Löschen", Reports.fixLine(FixAction.of(FixAction.DELETE_FILES, "Löschen")));
        assertEquals("Öffnen", Reports.fixLine(FixAction.of(FixAction.OPEN_FILE, "Öffnen")));
        assertEquals("Öffnen  (pom.xml)", Reports.fixLine(FixAction.of(FixAction.OPEN_FILE, "Öffnen", "file", "pom.xml", "line", "-1")));
        assertEquals("Neu bauen", Reports.fixLine(FixAction.of(FixAction.REBUILD, "Neu bauen", "args", "")));
        assertFalse(Reports.consoleCard(Collections.singletonList(new Diagnosis("r", 1, "K", "U", Confidence.CERTAIN,
                Collections.<Evidence>emptyList(), Collections.singletonList(FixAction.of(FixAction.DELETE_FILES, "Löschen"))))).isEmpty());
    }

    @Test
    void evidenceIsShortenedWithoutSplittingASurrogatePair() {
        String text = "x".repeat(218) + "😀" + "tail";
        String shown = Reports.evidence(Evidence.log(text));
        assertTrue(shown.endsWith("…"), shown);
        assertFalse(Character.isHighSurrogate(shown.charAt(shown.length() - 2)), shown);
        assertEquals(text, Reports.evidence(Evidence.log(text.substring(0, 200))) + text.substring(200));
    }

    @Test
    void evidenceWithFileShowsTheLineOnlyWhenKnown() {
        assertEquals("a -> f.xml:3", Reports.evidence(Evidence.file("a", "f.xml", 3)));
        assertEquals("a -> f.xml", Reports.evidence(Evidence.file("a", "f.xml", 0)));
        assertEquals("", Reports.evidence(Evidence.hint(null)));
    }

    @Test
    void markdownKeepsAllEvidenceButOnlyTheFirstFix() {
        String md = Reports.markdown(Collections.singletonList(full()), failure(), null);
        assertTrue(md.contains("wird auf der Karte nicht mehr gezeigt"), md);
        assertFalse(md.contains("Mit -U neu bauen"), md);
        assertTrue(md.endsWith(Reports.footer()), md);
    }

    @Test
    void markdownReplacesBackticksInLogLines() {
        Diagnosis d = new Diagnosis("r", 1, "K", "U", Confidence.CERTAIN,
                Collections.singletonList(Evidence.log("use `-U` to retry")), Collections.<FixAction>emptyList());
        String md = Reports.markdown(Collections.singletonList(d), failure());
        assertTrue(md.contains("- `use '-U' to retry`"), md);
    }
}
