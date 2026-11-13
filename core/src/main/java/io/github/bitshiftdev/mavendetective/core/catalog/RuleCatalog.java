package io.github.bitshiftdev.mavendetective.core.catalog;

import io.github.bitshiftdev.mavendetective.core.diagnosis.Confidence;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;
import io.github.bitshiftdev.mavendetective.core.io.MiniYaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Der Fehlerkatalog: eine YAML-Datei mit Regeln. Neue Regeln brauchen keinen Code, solange sie
 * vorhandene Prüfungen ({@code checks}) und Quick-Fix-Typen verwenden.
 */
public final class RuleCatalog {

    public static final String DEFAULT_RESOURCE = "/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml";

    private static final Set<String> FIX_TYPES = new HashSet<String>(Arrays.asList(
            FixAction.REBUILD, FixAction.DELETE_FILES, FixAction.OPEN_FILE, FixAction.REPLACE_IN_FILE,
            FixAction.SWITCH_JDK, FixAction.SET_JVM_OPTION, FixAction.INSERT_PLUGIN_VERSION,
            FixAction.SHOW_DEPENDENCY, FixAction.PIN_VERSION, FixAction.REMOVE_EXCLUSION, FixAction.ADD_EXCLUSION,
            FixAction.ADD_DEPENDENCY, FixAction.REMOVE_DEPENDENCY, FixAction.SET_SCOPE, FixAction.SET_VERSION, FixAction.MANUAL));

    private static final Set<String> RULE_KEYS = new HashSet<String>(Arrays.asList(
            "id", "number", "category", "priority", "confidence", "patterns", "extract", "unless",
            "cause", "checks", "fix", "description",
            "category_en", "category_de", "cause_en", "cause_de"));

    /** Sprachen, für die {@code feld_<code>} neben dem Grundtext stehen darf. */
    private static final String[] LANGUAGES = {"en", "de"};

    private final int version;
    private final List<Rule> rules;

    private RuleCatalog(int version, List<Rule> rules) {
        this.version = version;
        this.rules = Collections.unmodifiableList(rules);
    }

    public static RuleCatalog loadDefault() {
        InputStream in = RuleCatalog.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Fehlerkatalog fehlt im Klassenpfad: " + DEFAULT_RESOURCE);
        }
        try {
            try {
                return parse(readAll(in), DEFAULT_RESOURCE);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuleCatalog load(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.toString());
    }

    /** Hängt die Regeln eines weiteren Katalogs an (z. B. projekteigene Regeln). Gleiche IDs ersetzen. */
    public RuleCatalog merge(RuleCatalog other) {
        Map<String, Rule> byId = new LinkedHashMap<String, Rule>();
        for (Rule r : rules) {
            byId.put(r.getId(), r);
        }
        for (Rule r : other.rules) {
            byId.put(r.getId(), r);
        }
        return new RuleCatalog(Math.max(version, other.version), new ArrayList<Rule>(byId.values()));
    }

    public int getVersion() { return version; }

    public List<Rule> getRules() { return rules; }

    public Rule rule(String id) {
        for (Rule r : rules) {
            if (r.getId().equals(id)) {
                return r;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- parsen

    @SuppressWarnings("unchecked")
    public static RuleCatalog parse(String yaml, String origin) {
        Object root;
        try {
            root = MiniYaml.parse(yaml);
        } catch (MiniYaml.YamlException e) {
            throw new CatalogException(origin + ": " + e.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new CatalogException(origin + ": Mapping mit 'version' und 'rules' erwartet");
        }
        Map<String, Object> top = (Map<String, Object>) root;
        int version = toInt(top.get("version"), 1, origin + ": version");
        Object rulesObj = top.get("rules");
        if (!(rulesObj instanceof List)) {
            throw new CatalogException(origin + ": 'rules' muss eine Liste sein");
        }
        List<Rule> rules = new ArrayList<Rule>();
        Set<String> ids = new HashSet<String>();
        for (Object o : (List<Object>) rulesObj) {
            if (!(o instanceof Map)) {
                throw new CatalogException(origin + ": jede Regel muss ein Mapping sein");
            }
            Rule r = parseRule((Map<String, Object>) o, origin);
            if (!ids.add(r.getId())) {
                throw new CatalogException(origin + ": Regel-ID doppelt: " + r.getId());
            }
            rules.add(r);
        }
        return new RuleCatalog(version, rules);
    }

    private static Rule parseRule(Map<String, Object> m, String origin) {
        String id = string(m, "id");
        if (id == null || !id.matches("[a-z0-9][a-z0-9\\-]*")) {
            throw new CatalogException(origin + ": Regel ohne gültige 'id' (kleinbuchstaben-mit-bindestrich): " + id);
        }
        String where = origin + " [" + id + "]";
        for (String key : m.keySet()) {
            if (!RULE_KEYS.contains(key)) {
                throw new CatalogException(where + ": unbekannter Schlüssel '" + key + "'");
            }
        }
        String category = string(m, "category");
        if (category == null) {
            throw new CatalogException(where + ": 'category' fehlt");
        }
        List<Rule.PatternSpec> patterns = patterns(m.get("patterns"), where + ".patterns");
        if (patterns.isEmpty()) {
            throw new CatalogException(where + ": mindestens ein Eintrag unter 'patterns' nötig");
        }
        List<Rule.PatternSpec> extract = patterns(m.get("extract"), where + ".extract");
        List<Pattern> unless = new ArrayList<Pattern>();
        for (Rule.PatternSpec p : patterns(m.get("unless"), where + ".unless")) {
            unless.add(p.getRegex());
        }
        List<String> checks = new ArrayList<String>();
        Object c = m.get("checks");
        if (c instanceof List) {
            for (Object o : (List<?>) c) {
                checks.add(String.valueOf(o));
            }
        } else if (c != null) {
            throw new CatalogException(where + ": 'checks' muss eine Liste sein");
        }
        return new Rule(id,
                toInt(m.get("number"), 0, where + ".number"),
                category, variants(m, "category"),
                toInt(m.get("priority"), 50, where + ".priority"),
                confidence(string(m, "confidence"), Confidence.LIKELY, where),
                patterns, extract, unless,
                string(m, "cause"), variants(m, "cause"),
                checks,
                fix(m.get("fix"), where + ".fix"));
    }

    @SuppressWarnings("unchecked")
    private static List<Rule.PatternSpec> patterns(Object o, String where) {
        List<Rule.PatternSpec> out = new ArrayList<Rule.PatternSpec>();
        if (o == null) {
            return out;
        }
        if (!(o instanceof List)) {
            throw new CatalogException(where + ": Liste erwartet");
        }
        int i = 0;
        for (Object item : (List<Object>) o) {
            String w = where + "[" + i++ + "]";
            if (item instanceof String) {
                out.add(new Rule.PatternSpec(compile((String) item, w), null, null, null));
            } else if (item instanceof Map) {
                Map<String, Object> pm = (Map<String, Object>) item;
                String regex = string(pm, "regex");
                if (regex == null) {
                    throw new CatalogException(w + ": 'regex' fehlt");
                }
                out.add(new Rule.PatternSpec(compile(regex, w), string(pm, "cause"), variants(pm, "cause"),
                        fix(pm.get("fix"), w + ".fix"), confidence(string(pm, "confidence"), null, w)));
            } else {
                throw new CatalogException(w + ": Regex-Text oder Mapping mit 'regex' erwartet");
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Rule.FixSpec fix(Object o, String where) {
        if (o == null) {
            return null;
        }
        if (!(o instanceof Map)) {
            throw new CatalogException(where + ": Mapping mit 'type' und 'label' erwartet");
        }
        Map<String, Object> m = (Map<String, Object>) o;
        String type = string(m, "type");
        if (!FIX_TYPES.contains(type)) {
            throw new CatalogException(where + ": unbekannter Quick-Fix-Typ '" + type + "', erlaubt: " + FIX_TYPES);
        }
        String label = string(m, "label");
        if (label == null) {
            throw new CatalogException(where + ": 'label' fehlt");
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        Object p = m.get("params");
        if (p instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) p).entrySet()) {
                params.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
            }
        } else if (p != null) {
            throw new CatalogException(where + ".params: Mapping erwartet");
        }
        return new Rule.FixSpec(type, label, variants(m, "label"), params);
    }

    private static Pattern compile(String regex, String where) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new CatalogException(where + ": ungültige Regex: " + e.getDescription() + " in '" + regex + "'");
        }
    }

    private static Confidence confidence(String s, Confidence fallback, String where) {
        if (s == null) {
            return fallback;
        }
        if (s.equals("certain") || s.equals("sicher")) {
            return Confidence.CERTAIN;
        }
        if (s.equals("likely") || s.equals("wahrscheinlich")) {
            return Confidence.LIKELY;
        }
        throw new CatalogException(where + ": confidence muss 'certain' oder 'likely' sein");
    }

    /** Übersetzungen eines Felds: {@code cause_en}, {@code cause_de} → {"en": …, "de": …}. */
    private static Map<String, String> variants(Map<String, Object> m, String key) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String lang : LANGUAGES) {
            String v = string(m, key + "_" + lang);
            if (v != null) {
                out.put(lang, v);
            }
        }
        return out;
    }

    private static String string(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new CatalogException("'" + key + "' muss Text sein");
        }
        return ((String) v).trim();
    }

    private static int toInt(Object o, int fallback, String where) {
        if (o == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new CatalogException(where + ": Zahl erwartet, gefunden '" + o + "'");
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static final class CatalogException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public CatalogException(String message) {
            super(message);
        }
    }
}
