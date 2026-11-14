package io.github.bitshiftdev.mavendetective.core.catalog;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Eine Regel des Fehlerkatalogs, geladen aus YAML. */
public final class Rule {

    /** Regex mit optional eigener Ursache, eigenem Quick-Fix und eigener Sicherheit. */
    public static final class PatternSpec {
        private final Pattern regex;
        private final List<String> groupNames;
        private final String cause;
        private final FixSpec fix;
        private final Confidence confidence;
        private final Map<String, String> causeI18n;

        public PatternSpec(Pattern regex, String cause, FixSpec fix, Confidence confidence) {
            this(regex, cause, Collections.<String, String>emptyMap(), fix, confidence);
        }

        /** {@code causeI18n}: Sprachcode → Ursache aus {@code cause_en}/{@code cause_de}. */
        public PatternSpec(Pattern regex, String cause, Map<String, String> causeI18n, FixSpec fix, Confidence confidence) {
            this.causeI18n = causeI18n;
            this.regex = regex;
            this.groupNames = groupNames(regex.pattern());
            this.cause = cause;
            this.fix = fix;
            this.confidence = confidence;
        }

        public Pattern getRegex() { return regex; }

        public String getCause() { return Texts.pick(cause, causeI18n); }

        public FixSpec getFix() { return fix; }

        public Confidence getConfidence() { return confidence; }

        /** Benannte Gruppen der Regex, die getroffen haben. */
        public Map<String, String> extract(Matcher m) {
            Map<String, String> vars = new LinkedHashMap<String, String>();
            for (String name : groupNames) {
                String v = m.group(name);
                if (v != null && !v.isEmpty()) {
                    vars.put(name, v);
                }
            }
            return vars;
        }

        private static final Pattern GROUP = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

        private static List<String> groupNames(String regex) {
            List<String> names = new ArrayList<String>();
            Matcher m = GROUP.matcher(regex);
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        }
    }

    /** Quick-Fix-Vorlage; Label und Parameter dürfen Platzhalter enthalten. */
    public static final class FixSpec {
        private final String type;
        private final String label;
        private final Map<String, String> params;
        private final Map<String, String> labelI18n;

        public FixSpec(String type, String label, Map<String, String> params) {
            this(type, label, Collections.<String, String>emptyMap(), params);
        }

        public FixSpec(String type, String label, Map<String, String> labelI18n, Map<String, String> params) {
            this.labelI18n = labelI18n;
            this.type = type;
            this.label = label;
            this.params = Collections.unmodifiableMap(new LinkedHashMap<String, String>(params));
        }

        public String getType() { return type; }

        public String getLabel() { return Texts.pick(label, labelI18n); }

        public Map<String, String> getParams() { return params; }
    }

    private final String id;
    private final int number;
    private final String category;
    private final int priority;
    private final Confidence confidence;
    private final List<PatternSpec> patterns;
    private final List<PatternSpec> extract;
    private final List<Pattern> unless;
    private final String cause;
    private final List<String> checks;
    private final FixSpec fix;
    private final Map<String, String> categoryI18n;
    private final Map<String, String> causeI18n;
    private final boolean outsideTests;

    public Rule(String id, int number, String category, int priority, Confidence confidence,
                List<PatternSpec> patterns, List<PatternSpec> extract, List<Pattern> unless,
                String cause, List<String> checks, FixSpec fix) {
        this(id, number, category, Collections.<String, String>emptyMap(), priority, confidence, patterns, extract, unless,
                cause, Collections.<String, String>emptyMap(), checks, fix);
    }

    /** Mit Übersetzungen: Sprachcode → Text aus {@code category_en}/{@code cause_de} usw. */
    public Rule(String id, int number, String category, Map<String, String> categoryI18n, int priority, Confidence confidence,
                List<PatternSpec> patterns, List<PatternSpec> extract, List<Pattern> unless,
                String cause, Map<String, String> causeI18n, List<String> checks, FixSpec fix) {
        this(id, number, category, categoryI18n, priority, confidence, patterns, extract, unless, cause, causeI18n,
                checks, fix, false);
    }

    /** @param outsideTests Muster und Prüfungen sehen keine Zeilen aus Testausgaben ({@link #isOutsideTests()}) */
    public Rule(String id, int number, String category, Map<String, String> categoryI18n, int priority, Confidence confidence,
                List<PatternSpec> patterns, List<PatternSpec> extract, List<Pattern> unless,
                String cause, Map<String, String> causeI18n, List<String> checks, FixSpec fix, boolean outsideTests) {
        this.outsideTests = outsideTests;
        this.categoryI18n = categoryI18n;
        this.causeI18n = causeI18n;
        this.id = id;
        this.number = number;
        this.category = category;
        this.priority = priority;
        this.confidence = confidence;
        this.patterns = Collections.unmodifiableList(patterns);
        this.extract = Collections.unmodifiableList(extract);
        this.unless = Collections.unmodifiableList(unless);
        this.cause = cause;
        this.checks = Collections.unmodifiableList(checks);
        this.fix = fix;
    }

    public String getId() { return id; }

    public int getNumber() { return number; }

    public String getCategory() { return Texts.pick(category, categoryI18n); }

    /** Höher gewinnt, wenn mehrere Regeln passen (spezifische Ursache vor allgemeiner). */
    public int getPriority() { return priority; }

    public Confidence getConfidence() { return confidence; }

    public List<PatternSpec> getPatterns() { return patterns; }

    /** Muster nur zum Auslesen von Variablen; lösen die Regel nicht aus. */
    public List<PatternSpec> getExtract() { return extract; }

    public List<Pattern> getUnless() { return unless; }

    /**
     * Regel beschreibt ein Maven-Problem, dessen Muster auch in Meldungen der Anwendung vorkommen
     * ({@code 403 Forbidden}, {@code ZipException}): Zeilen aus gescheiterten Tests zählen dann nicht.
     */
    public boolean isOutsideTests() { return outsideTests; }

    public String getCause() { return Texts.pick(cause, causeI18n); }

    public List<String> getChecks() { return checks; }

    public FixSpec getFix() { return fix; }
}
