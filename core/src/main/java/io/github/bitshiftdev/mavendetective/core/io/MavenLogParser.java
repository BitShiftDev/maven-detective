package io.github.bitshiftdev.mavendetective.core.io;

import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import io.github.bitshiftdev.mavendetective.core.model.ErrorRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback, wenn keine Events vorliegen: liest die Textausgabe von Maven (Konsole, CI-Log, kopierter
 * Text) und macht daraus ein {@link BuildFailure}. Versteht Logs mit und ohne Zeitstempel,
 * ANSI-Farben und den {@code [ERROR]}-Präfix von Maven 3 und 4.
 */
public final class MavenLogParser {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    /**
     * Beginn einer Testausgabe von Surefire/Failsafe: Kopf eines gescheiterten Tests
     * ({@code … <<< FAILURE!}) oder die Liste in der Zusammenfassung ({@code Errors:}).
     */
    private static final Pattern TEST_OUTPUT_START = Pattern.compile(
            "<<< (?:FAILURE|ERROR)!|^(?:Failures|Errors|Flakes):\\s*$");
    private static final Pattern FAILED_GOAL = Pattern.compile(
            "Failed to execute goal (\\S+) \\(([^)]*)\\) on project ([^:\\s]+)");
    private static final Pattern MAVEN_VERSION = Pattern.compile("^Apache Maven (\\S+)");
    private static final Pattern JAVA_VERSION = Pattern.compile("^Java version: ([^,\\s]+)(?:, vendor: [^,]*, runtime: (.+))?");
    private static final Pattern MAVEN_HOME = Pattern.compile("^Maven home: (.+)");

    /** Maven-Textbausteine ohne Diagnosewert. */
    private static final String[] NOISE = {
        "To see the full stack trace of the errors",
        "Re-run Maven using the -X switch",
        "For more information about the errors and possible solutions",
        "After correcting the problems, you can resume the build",
        "-> [Help 1]",
    };
    private static final Pattern HELP_LINK = Pattern.compile("^\\[Help \\d+\\] https?://\\S+$");

    private static final int MAX_LOG_LINES = 20000;

    private MavenLogParser() {
    }

    public static BuildFailure parse(String log) {
        try {
            return parse(new StringReader(log));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BuildFailure parse(Reader reader) throws IOException {
        BuildFailure failure = new BuildFailure(BuildFailure.Source.LOG);
        BufferedReader br = new BufferedReader(reader);
        String raw;
        boolean previousWasError = false;
        boolean inTestOutput = false;
        while ((raw = br.readLine()) != null) {
            String line = raw.indexOf('\u001B') >= 0 ? ANSI.matcher(raw).replaceAll("") : raw;
            if (failure.getLogLines().size() < MAX_LOG_LINES) {
                failure.getLogLines().add(line);
            }
            LogPrefix parsed = LogPrefix.parse(line);
            String text = parsed.getText();
            readSessionHints(text, failure);

            String lvl = parsed.getLevel();
            if (lvl != null) {
                previousWasError = "ERROR".equals(lvl) || "FATAL".equals(lvl);
                if (previousWasError) {
                    String t = text.trim();
                    if (TEST_OUTPUT_START.matcher(t).find()) {
                        inTestOutput = true;
                    } else if (t.startsWith("Tests run:") || t.startsWith("Failed to execute goal")) {
                        inTestOutput = false;   // Summenzeile bzw. Maven-Meldung schließen den Testblock
                    }
                    addError(failure, text, inTestOutput);
                } else {
                    inTestOutput = false;
                    if (text.contains("offline mode")) {
                        failure.getSession().setOffline(Boolean.TRUE);
                    }
                }
                continue;
            }
            // Fortsetzungszeilen ohne Präfix (Stacktraces, mehrzeilige Meldungen) gehören zum Fehler davor
            if (previousWasError && !text.trim().isEmpty()) {
                addError(failure, text, inTestOutput);
            } else {
                previousWasError = false;
            }
            // Ausnahmen, die ohne [ERROR] im Log landen (Surefire-Reports, Konsolen-Dumps)
            if (!previousWasError && looksLikeRuntimeError(text)) {
                addError(failure, text.trim(), inTestOutput);
            }
        }
        return failure;
    }

    private static boolean looksLikeRuntimeError(String line) {
        return line.contains("java.lang.OutOfMemoryError")
                || line.contains("java.lang.NoSuchMethodError")
                || line.contains("java.lang.NoClassDefFoundError")
                || line.contains("java.lang.UnsupportedClassVersionError")
                || line.contains("There is insufficient memory for the Java Runtime");
    }

    private static void addError(BuildFailure failure, String text, boolean testOutput) {
        String t = text.trim();
        if (t.isEmpty() || isNoise(t)) {
            return;
        }
        // Maven 3.9 verschachtelt manchmal: "[ERROR] [ERROR] Some problems were encountered…"
        while (t.startsWith("[ERROR] ") || t.startsWith("[FATAL] ")) {
            t = t.substring(8).trim();
        }
        // " -> [Help 2]" am Zeilenende abschneiden
        t = t.replaceAll("\\s*->\\s*\\[Help \\d+\\]$", "");
        if (t.isEmpty()) {
            return;
        }
        ErrorRecord r = ErrorRecord.ofText(t);
        r.setTestOutput(testOutput);
        Matcher goal = FAILED_GOAL.matcher(t);
        if (goal.find()) {
            r.setMojo(goal.group(1));
            r.setExecution(goal.group(2));
            r.setProject(goal.group(3));
        }
        failure.getErrors().add(r);
    }

    private static boolean isNoise(String t) {
        if (HELP_LINK.matcher(t).matches()) {
            return true;
        }
        for (String n : NOISE) {
            if (t.startsWith(n) || t.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Liest Versionen aus {@code mvn -V}; {@code line} ist der Text ohne CI-Präfix. */
    private static void readSessionHints(String line, BuildFailure failure) {
        char c = line.isEmpty() ? ' ' : line.charAt(0);
        if (c != 'A' && c != 'J' && c != 'M') {
            return;   // schneller Ausstieg für die allermeisten Zeilen
        }
        Matcher m = MAVEN_VERSION.matcher(line);
        if (m.find()) {
            failure.getSession().setMavenVersion(m.group(1));
            return;
        }
        m = JAVA_VERSION.matcher(line);
        if (m.find()) {
            failure.getSession().setJavaVersion(m.group(1));
            if (m.group(2) != null) {
                failure.getSession().setJavaHome(m.group(2).trim());
            }
            return;
        }
        m = MAVEN_HOME.matcher(line);
        if (m.find() && failure.getSession().getGlobalSettings() == null) {
            failure.getSession().setGlobalSettings(m.group(1).trim() + "/conf/settings.xml");
        }
    }
}
