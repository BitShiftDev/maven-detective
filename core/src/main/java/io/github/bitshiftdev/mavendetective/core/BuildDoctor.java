package io.github.bitshiftdev.mavendetective.core;

import io.github.bitshiftdev.mavendetective.core.catalog.Rule;
import io.github.bitshiftdev.mavendetective.core.catalog.RuleCatalog;
import io.github.bitshiftdev.mavendetective.core.catalog.Template;
import io.github.bitshiftdev.mavendetective.core.check.Check;
import io.github.bitshiftdev.mavendetective.core.check.CheckContext;
import io.github.bitshiftdev.mavendetective.core.check.CheckResult;
import io.github.bitshiftdev.mavendetective.core.check.Checks;
import io.github.bitshiftdev.mavendetective.core.check.ProjectPoms;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Diagnosis;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Evidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.env.Environment;
import io.github.bitshiftdev.mavendetective.core.model.BuildFailure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordnet einen gescheiterten Build einer Kategorie zu und erklärt ihn.
 *
 * <ol>
 * <li><b>Einordnen:</b> Jede Regel prüft ihre Muster gegen die Fehlerzeilen; die Variablen aus
 *     benannten Gruppen ({@code (?<artifact>…)}) werden gesammelt.</li>
 * <li><b>Prüfen:</b> Die Prüfungen der Regel laufen in der angegebenen Reihenfolge.</li>
 * <li><b>Erklären:</b> Ursache = erste bestätigende Prüfung mit eigener Ursache, sonst die des Musters,
 *     sonst die der Regel. Sicherheit = „sicher“, sobald eine Prüfung bestätigt oder die Regel es erklärt.</li>
 * <li><b>Beheben:</b> Quick-Fix der ursachengebenden Prüfung zuerst, dann der der Regel, dann die übrigen.</li>
 * </ol>
 */
public final class BuildDoctor {

    private static final int MAX_LOG_EVIDENCE = 3;

    private final RuleCatalog catalog;
    private final Checks checks;

    public BuildDoctor(RuleCatalog catalog, Checks checks) {
        this.catalog = catalog;
        this.checks = checks;
        List<String> problems = new ArrayList<String>();
        for (Rule r : catalog.getRules()) {
            for (String id : r.getChecks()) {
                if (checks.get(id) == null) {
                    problems.add("Regel '" + r.getId() + "' verweist auf unbekannte Prüfung '" + id + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleCatalog.CatalogException(problems.toString() + " – bekannt: " + checks.all().keySet());
        }
    }

    public static BuildDoctor withDefaults() {
        return new BuildDoctor(RuleCatalog.loadDefault(), Checks.defaults());
    }

    public RuleCatalog getCatalog() { return catalog; }

    /**
     * Alle passenden Diagnosen, die wahrscheinlichste zuerst. Passt keine Regel, kommt genau eine
     * Diagnose mit {@link Diagnosis#UNKNOWN_RULE}. Ohne Fehler im Build: leere Liste.
     */
    public List<Diagnosis> diagnose(BuildFailure failure, Environment env) {
        if (!failure.hasErrors()) {
            return Collections.emptyList();
        }
        List<String> lines = failure.searchableLines();
        ProjectPoms poms = new ProjectPoms(env.getProjectDirectory());
        List<Rule> rules = new ArrayList<Rule>(catalog.getRules());
        Collections.sort(rules, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(b.getPriority(), a.getPriority());
            }
        });
        List<Diagnosis> out = new ArrayList<Diagnosis>();
        for (Rule rule : rules) {
            Diagnosis d = apply(rule, failure, env, lines, poms);
            if (d != null) {
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            out.add(unknown(lines));
        }
        return out;
    }

    /** Nur die wahrscheinlichste Diagnose. */
    public Diagnosis primary(BuildFailure failure, Environment env) {
        List<Diagnosis> all = diagnose(failure, env);
        return all.isEmpty() ? null : all.get(0);
    }

    private Diagnosis apply(Rule rule, BuildFailure failure, Environment env, List<String> lines, ProjectPoms poms) {
        for (Pattern unless : rule.getUnless()) {
            for (String line : lines) {
                if (unless.matcher(line).find()) {
                    return null;
                }
            }
        }
        // 1. Einordnen
        Rule.PatternSpec matchedSpec = null;
        String matchedLine = null;
        Map<String, String> vars = new LinkedHashMap<String, String>();
        Set<String> logEvidence = new LinkedHashSet<String>();
        for (Rule.PatternSpec spec : rule.getPatterns()) {
            for (String line : lines) {
                Matcher m = spec.getRegex().matcher(line);
                if (m.find()) {
                    if (matchedSpec == null) {
                        matchedSpec = spec;
                        matchedLine = line;
                        vars.putAll(spec.extract(m));
                    }
                    logEvidence.add(line);
                    break;
                }
            }
        }
        if (matchedSpec == null) {
            return null;
        }
        List<Rule.PatternSpec> extractors = new ArrayList<Rule.PatternSpec>(rule.getPatterns());
        extractors.addAll(rule.getExtract());
        for (Rule.PatternSpec spec : extractors) {
            for (String line : lines) {
                Matcher m = spec.getRegex().matcher(line);
                if (m.find()) {
                    for (Map.Entry<String, String> e : spec.extract(m).entrySet()) {
                        if (!vars.containsKey(e.getKey())) {
                            vars.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }

        // 2. Prüfen
        List<CheckResult> results = new ArrayList<CheckResult>();
        for (String id : rule.getChecks()) {
            Check check = checks.get(id);
            CheckResult result;
            try {
                result = check.run(new CheckContext(failure, env, vars, lines, poms));
            } catch (RuntimeException e) {
                // Eine fehlerhafte Prüfung darf die Diagnose nicht verhindern
                result = CheckResult.info().evidence(Evidence.hint("Prüfung '" + id + "' fehlgeschlagen: " + e));
            }
            if (result.getStatus() == CheckResult.Status.NOT_APPLICABLE) {
                continue;
            }
            for (Map.Entry<String, String> e : result.getVars().entrySet()) {
                vars.put(e.getKey(), e.getValue());
            }
            results.add(result);
        }

        // 3. Erklären
        CheckResult causeGiver = null;
        for (CheckResult.Status wanted : new CheckResult.Status[] {CheckResult.Status.CONFIRMED, CheckResult.Status.INFO}) {
            for (CheckResult r : results) {
                if (causeGiver == null && r.getStatus() == wanted && r.getCause() != null) {
                    causeGiver = r;
                }
            }
        }
        String causeTemplate = causeGiver != null ? causeGiver.getCause()
                : matchedSpec.getCause() != null ? matchedSpec.getCause() : rule.getCause();
        if (causeTemplate == null) {
            causeTemplate = rule.getCategory() + ".";
        }
        String cause = Template.render(causeTemplate, vars);

        Confidence confidence = matchedSpec.getConfidence() != null ? matchedSpec.getConfidence() : rule.getConfidence();
        for (CheckResult r : results) {
            if (r.getStatus() == CheckResult.Status.CONFIRMED) {
                confidence = Confidence.CERTAIN;
            }
        }

        List<Evidence> evidence = new ArrayList<Evidence>();
        evidence.add(Evidence.log(matchedLine));
        for (String line : logEvidence) {
            if (evidence.size() >= MAX_LOG_EVIDENCE) {
                break;
            }
            // reine Klassennamen aus der Exception-Kette sind als Beleg wertlos
            if (!line.equals(matchedLine) && !line.matches("[\\w.$]+")) {
                evidence.add(Evidence.log(line));
            }
        }
        if (causeGiver != null) {
            evidence.addAll(causeGiver.getEvidence());
        }
        for (CheckResult r : results) {
            if (r != causeGiver) {
                evidence.addAll(r.getEvidence());
            }
        }

        // 4. Beheben
        List<FixAction> fixes = new ArrayList<FixAction>();
        if (causeGiver != null) {
            fixes.addAll(causeGiver.getFixes());
        }
        Rule.FixSpec spec = matchedSpec.getFix() != null ? matchedSpec.getFix() : rule.getFix();
        if (spec != null) {
            fixes.add(render(spec, vars));
        }
        for (CheckResult r : results) {
            if (r != causeGiver) {
                fixes.addAll(r.getFixes());
            }
        }
        return new Diagnosis(rule.getId(), rule.getNumber(), rule.getCategory(), cause, confidence,
                dedupeEvidence(evidence), dedupe(fixes));
    }

    private static List<Evidence> dedupeEvidence(List<Evidence> evidence) {
        List<Evidence> out = new ArrayList<Evidence>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Evidence e : evidence) {
            if (seen.add(e.getKind() + "|" + e)) {
                out.add(e);
            }
        }
        return out;
    }

    private static FixAction render(Rule.FixSpec spec, Map<String, String> vars) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : spec.getParams().entrySet()) {
            params.put(e.getKey(), Template.render(e.getValue(), vars));
        }
        return new FixAction(spec.getType(), Template.render(spec.getLabel(), vars), params);
    }

    private static List<FixAction> dedupe(List<FixAction> fixes) {
        List<FixAction> out = new ArrayList<FixAction>();
        Set<String> seen = new LinkedHashSet<String>();
        for (FixAction f : fixes) {
            if (seen.add(f.getType() + f.getParams())) {
                out.add(f);
            }
        }
        return out;
    }

    private static Diagnosis unknown(List<String> lines) {
        List<Evidence> evidence = new ArrayList<Evidence>();
        for (int i = 0; i < lines.size() && i < MAX_LOG_EVIDENCE; i++) {
            evidence.add(Evidence.log(lines.get(i)));
        }
        String first = lines.isEmpty() ? "" : lines.get(0);
        List<FixAction> fixes = new ArrayList<FixAction>();
        fixes.add(FixAction.of(FixAction.MANUAL, "Als Testfall melden („Falsch erkannt“), damit der Katalog wächst"));
        return new Diagnosis(Diagnosis.UNKNOWN_RULE, 0, "Unbekannter Fehler",
                "Kein Eintrag im Fehlerkatalog passt. Erste Fehlermeldung: " + first,
                Confidence.LIKELY, evidence, fixes);
    }
}
