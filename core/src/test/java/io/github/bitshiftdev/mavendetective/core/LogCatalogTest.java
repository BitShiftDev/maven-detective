package io.github.bitshiftdev.mavendetective.core;

import io.github.bitshiftdev.mavendetective.core.catalog.Rule;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.io.MavenLogParser;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jede Datei unter {@code logs/} heißt {@code <erwartete-regel-id>__<beschreibung>.log}.
 * Der Test prüft, dass Build Doctor genau diese Regel als wahrscheinlichste Ursache meldet –
 * so wird jeder „Falsch erkannt“-Fall zu einem dauerhaften Testfall.
 *
 * <p>{@code logs-negative/<regel-id>__<beschreibung>.log} sind Beinahe-Treffer: die Regel darf in keiner
 * Diagnose auftauchen. {@code logs-multi/<regel-a>+<regel-b>__<beschreibung>.log} enthalten zwei Fehler:
 * die Regel mit der höheren {@code priority} steht vorn, die andere folgt.
 */
class LogCatalogTest {

    /** Maven 3.9, Maven 4 bzw. eine zweite Fehlerform, CI-Präfix oder Windows. */
    private static final int MIN_LOGS_PER_RULE = 3;

    private final BuildDoctor doctor = BuildDoctor.withDefaults();

    @Test
    void everyLogIsClassifiedAsItsFileNameSays() throws Exception {
        Environment env = isolatedEnvironment();
        List<String> failures = new ArrayList<>();
        List<Path> logs = logs();
        assertFalse(logs.isEmpty(), "keine Logs gefunden");
        for (Path log : logs) {
            String expected = log.getFileName().toString().split("__")[0];
            BuildFailure failure = MavenLogParser.parse(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            List<Diagnosis> result = doctor.diagnose(failure, env);
            String actual = result.isEmpty() ? "(keine)" : result.get(0).getRuleId();
            if (!expected.equals(actual)) {
                failures.add(log.getFileName() + ": erwartet " + expected + ", erkannt " + actual
                        + (result.isEmpty() ? "" : " – " + result.get(0).getCause()));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyRuleHasEnoughLogs() throws Exception {
        Map<String, Long> perRule = logs().stream()
                .collect(Collectors.groupingBy(p -> p.getFileName().toString().split("__")[0], Collectors.counting()));
        List<String> missing = new ArrayList<>();
        for (Rule r : doctor.getCatalog().getRules()) {
            long n = perRule.getOrDefault(r.getId(), 0L);
            if (n < MIN_LOGS_PER_RULE) {
                missing.add(r.getId() + " (" + n + ")");
            }
        }
        assertTrue(missing.isEmpty(), "Regeln mit weniger als " + MIN_LOGS_PER_RULE + " Testlogs: " + missing);
    }

    @Test
    void nearMissesDoNotTriggerTheirRule() throws Exception {
        Environment env = isolatedEnvironment();
        List<Path> logs = logs("/logs-negative");
        assertFalse(logs.isEmpty(), "keine Negativ-Logs gefunden");
        List<String> failures = new ArrayList<>();
        for (Path log : logs) {
            String forbidden = log.getFileName().toString().split("__")[0];
            assertTrue(doctor.getCatalog().rule(forbidden) != null, log + ": unbekannte Regel " + forbidden);
            List<String> ids = ruleIds(doctor.diagnose(parse(log), env));
            if (ids.contains(forbidden)) {
                failures.add(log.getFileName() + ": " + ids);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void twoErrorsAreOrderedByPriority() throws Exception {
        Environment env = isolatedEnvironment();
        List<Path> logs = logs("/logs-multi");
        assertFalse(logs.isEmpty(), "keine Logs mit zwei Fehlern gefunden");
        List<String> failures = new ArrayList<>();
        for (Path log : logs) {
            String[] expected = log.getFileName().toString().split("__")[0].split("\\+");
            Rule first = doctor.getCatalog().rule(expected[0]);
            Rule second = doctor.getCatalog().rule(expected[1]);
            assertTrue(first.getPriority() > second.getPriority(), log + ": Dateiname nicht nach Priorität geordnet");
            List<String> ids = ruleIds(doctor.diagnose(parse(log), env));
            if (ids.isEmpty() || !ids.get(0).equals(expected[0]) || !ids.contains(expected[1])) {
                failures.add(log.getFileName() + ": " + ids);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static List<String> ruleIds(List<Diagnosis> diagnoses) {
        List<String> ids = new ArrayList<>();
        for (Diagnosis d : diagnoses) {
            ids.add(d.getRuleId());
        }
        return ids;
    }

    private static BuildFailure parse(Path log) throws IOException {
        return MavenLogParser.parse(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    @Test
    void everyDiagnosisHasCauseEvidenceAndNoUnresolvedPlaceholders() throws Exception {
        Environment env = isolatedEnvironment();
        for (Path log : logs()) {
            BuildFailure failure = MavenLogParser.parse(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            Diagnosis d = doctor.diagnose(failure, env).get(0);
            assertFalse(d.getCause().isEmpty(), log + ": leere Ursache");
            assertFalse(d.getEvidence().isEmpty(), log + ": keine Belege");
            assertFalse(d.getCause().matches(".*\\{[A-Za-z][A-Za-z0-9,]*(\\|[^}]*)?\\}.*"), log + ": Platzhalter übrig: " + d.getCause());
            assertFalse(d.getFixes().isEmpty(), log + ": kein Quick-Fix");
        }
    }

    @Test
    void numbersMatchTheConceptCatalog() {
        Set<Integer> numbers = new TreeSet<>();
        for (Rule r : doctor.getCatalog().getRules()) {
            numbers.add(r.getNumber());
        }
        for (int i = 1; i <= 12; i++) {
            assertTrue(numbers.contains(i), "Kategorie " + i + " fehlt im Katalog");
        }
    }

    @Test
    void cleanLogGivesNoDiagnosis() {
        BuildFailure failure = MavenLogParser.parse("[INFO] BUILD SUCCESS\n[INFO] Total time: 1 s\n");
        assertEquals(0, doctor.diagnose(failure, isolatedEnvironment()).size());
    }

    @Test
    void unknownErrorFallsBackToUnknownRule() {
        BuildFailure failure = MavenLogParser.parse("[ERROR] Something nobody has ever seen before\n");
        Diagnosis d = doctor.diagnose(failure, isolatedEnvironment()).get(0);
        assertTrue(d.isUnknown());
        assertTrue(d.getCause().contains("Something nobody has ever seen before"));
    }

    /** Leeres Home und Projekt, damit die Ergebnisse nicht vom Rechner des Entwicklers abhängen. */
    static Environment isolatedEnvironment() {
        try {
            Path home = Files.createTempDirectory("md-home");
            Path project = Files.createTempDirectory("md-project");
            return Environment.builder().userHome(home).projectDirectory(project).env(new HashMap<>()).build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> logs() throws IOException, URISyntaxException {
        return logs("/logs");
    }

    static List<Path> logs(String resource) throws IOException, URISyntaxException {
        Path dir = Paths.get(LogCatalogTest.class.getResource(resource).toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".log")).sorted().collect(Collectors.toList());
        }
    }
}
