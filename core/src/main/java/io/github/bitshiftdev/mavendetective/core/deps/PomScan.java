package io.github.bitshiftdev.mavendetective.core.deps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Durchlauf über die Tags einer POM für {@link PomEditor}: merkt sich, wo Dependencies,
 * Properties, Parent und Container stehen (Zeichenbereiche im Originaltext) und welche Werte sie
 * haben. Kommentare, CDATA, Processing Instructions und DOCTYPE werden übersprungen; Profile zählen
 * nicht, weil nur Pfade direkt unter {@code project} ausgewertet werden.
 *
 * <p>Werte werden wie von einem XML-Parser gelesen: Entities ({@code &amp;}, {@code &#46;}) und
 * CDATA aufgelöst, Kommentare im Wert ignoriert – sonst fände der Editor
 * {@code <groupId>org&#46;x</groupId>} nicht, obwohl {@link PomFile} es richtig liest.
 */
final class PomScan {

    /** Ein Element: Start des öffnenden Tags, Ende des öffnenden Tags, Inhalt, Ende des schließenden Tags. */
    static final class Range {
        int tagStart;
        int contentStart;
        int contentEnd;
        int tagEnd;
        /**
         * Leer geschrieben ({@code <x/>}): {@code contentStart == contentEnd == tagStart}. Einfügen und
         * Ersetzen klappen das Element dann auf (siehe {@link PomText#insertInto}, {@link PomText#replaceContent}).
         */
        boolean selfClosing;
    }

    /** Ein {@code <dependency>} in {@code project/dependencies} oder {@code project/dependencyManagement/dependencies}. */
    static final class DepEntry {
        final boolean managed;
        final Map<String, String> fields = new HashMap<String, String>();
        final Map<String, Range> ranges = new HashMap<String, Range>();
        final List<ExclusionEntry> exclusions = new ArrayList<ExclusionEntry>();
        Range exclusionsRange;
        /** Das ganze {@code <dependency>}-Element. */
        Range range;

        DepEntry(boolean managed) {
            this.managed = managed;
        }

        /** Typ des Eintrags; ohne {@code <type>} gilt {@code jar}. */
        String type() {
            String t = fields.get("type");
            return t == null || t.isEmpty() ? "jar" : t;
        }
    }

    /** Ein {@code <exclusion>} einer {@link DepEntry}. */
    static final class ExclusionEntry {
        final Map<String, String> fields = new HashMap<String, String>();
        Range range;
    }

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}$]+)}");
    private static final String DEPENDENCY = "project/dependencies/dependency";
    private static final String MANAGED_DEPENDENCY = "project/dependencyManagement/dependencies/dependency";

    final List<DepEntry> managed = new ArrayList<DepEntry>();
    final List<DepEntry> deps = new ArrayList<DepEntry>();
    final Map<String, Range> properties = new HashMap<String, Range>();
    /** {@code project/groupId}, {@code artifactId}, {@code version} als Text. */
    final Map<String, String> projectFields = new HashMap<String, String>();
    /** Werte unter {@code project/properties} (bei doppelter Angabe die letzte). */
    final Map<String, String> propertyValues = new HashMap<String, String>();
    /** Kinder von {@code project/parent}: Text bzw. Bereich. */
    final Map<String, String> parentFields = new HashMap<String, String>();
    final Map<String, Range> parentRanges = new HashMap<String, Range>();
    /** {@code project/properties} komplett; {@code null}, wenn es fehlt. */
    Range propertiesRange;
    int dmStart = -1;
    /** {@code project/dependencyManagement} bzw. dessen {@code dependencies}; {@code null}, wenn es fehlt. */
    Range dm;
    Range dmDependencies;
    int dependenciesStart = -1;
    /** {@code project/dependencies} komplett; {@code null}, wenn es fehlt. */
    Range dependencies;
    int buildStart = -1;
    int projectEnd = -1;
    int firstChildStart = -1;

    private PomScan() {
    }

    static PomScan of(String xml) {
        PomScan s = new PomScan();
        List<String> stack = new ArrayList<String>();
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> contentStarts = new ArrayList<Integer>();
        DepEntry entry = null;
        ExclusionEntry exclusion = null;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int skipped = skipSpecial(xml, lt);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            int gt = tagEnd(xml, lt);
            if (gt < 0) {
                throw new IllegalArgumentException("Unvollständiges Tag ab Zeichen " + lt);
            }
            boolean closing = xml.charAt(lt + 1) == '/';
            boolean empty = xml.charAt(gt - 1) == '/';
            String name = tagName(xml, closing ? lt + 2 : lt + 1, gt);
            if (closing) {
                if (stack.isEmpty() || !stack.get(stack.size() - 1).equals(name)) {
                    throw new IllegalArgumentException("Schließendes </" + name + "> passt nicht (Zeichen " + lt + ")");
                }
                String path = join(stack);
                Range r = new Range();
                r.tagStart = starts.get(starts.size() - 1);
                r.contentStart = contentStarts.get(contentStarts.size() - 1);
                r.contentEnd = lt;
                r.tagEnd = gt + 1;
                s.onClose(path, name, r, xml, entry, exclusion);
                if (entry != null && (DEPENDENCY.equals(path) || MANAGED_DEPENDENCY.equals(path))) {
                    entry.range = r;
                    s.deps.add(entry);
                    if (entry.managed) {
                        s.managed.add(entry);
                    }
                    entry = null;
                } else if (entry != null && exclusion != null && isExclusion(path)) {
                    exclusion.range = r;
                    entry.exclusions.add(exclusion);
                    exclusion = null;
                }
                stack.remove(stack.size() - 1);
                starts.remove(starts.size() - 1);
                contentStarts.remove(contentStarts.size() - 1);
            } else {
                stack.add(name);
                String path = join(stack);
                if (stack.size() == 2 && s.firstChildStart < 0) {
                    s.firstChildStart = lt;
                }
                if ("project/dependencies".equals(path) && s.dependenciesStart < 0) {
                    s.dependenciesStart = lt;
                } else if ("project/build".equals(path) && s.buildStart < 0) {
                    s.buildStart = lt;
                } else if ("project/dependencyManagement".equals(path) && s.dmStart < 0) {
                    s.dmStart = lt;
                } else if (DEPENDENCY.equals(path) || MANAGED_DEPENDENCY.equals(path)) {
                    entry = new DepEntry(MANAGED_DEPENDENCY.equals(path));
                } else if (entry != null && isExclusion(path)) {
                    exclusion = new ExclusionEntry();
                }
                if (empty) {
                    if (!"project".equals(path)) {
                        Range r = new Range();
                        r.tagStart = lt;
                        r.contentStart = lt;
                        r.contentEnd = lt;
                        r.tagEnd = gt + 1;
                        r.selfClosing = true;
                        s.onClose(path, name, r, xml, entry, exclusion);
                    }
                    // leer geschriebenes <dependency/> bzw. <exclusion/> hat keine Felder und wird nicht geführt
                    if (DEPENDENCY.equals(path) || MANAGED_DEPENDENCY.equals(path)) {
                        entry = null;
                    } else if (isExclusion(path)) {
                        exclusion = null;
                    }
                    stack.remove(stack.size() - 1);
                } else {
                    starts.add(lt);
                    contentStarts.add(gt + 1);
                }
            }
            i = gt + 1;
        }
        return s;
    }

    private static boolean isExclusion(String path) {
        return (DEPENDENCY + "/exclusions/exclusion").equals(path) || (MANAGED_DEPENDENCY + "/exclusions/exclusion").equals(path);
    }

    private void onClose(String path, String name, Range r, String xml, DepEntry entry, ExclusionEntry exclusion) {
        if ("project".equals(path)) {
            projectEnd = r.contentEnd;
        } else if (isChild(path, "project") && ("groupId".equals(name) || "artifactId".equals(name) || "version".equals(name))) {
            projectFields.put(name, text(xml, r));
        } else if (isChild(path, "project/parent")) {
            parentFields.put(name, text(xml, r));
            parentRanges.put(name, r);
        } else if ("project/properties".equals(path) && propertiesRange == null) {
            propertiesRange = r;
        } else if ("project/dependencyManagement".equals(path) && dm == null) {
            dm = r;
        } else if ("project/dependencyManagement/dependencies".equals(path) && dmDependencies == null) {
            dmDependencies = r;
        } else if ("project/dependencies".equals(path) && dependencies == null) {
            dependencies = r;
        } else if (entry != null && isChild(path, entry.managed ? MANAGED_DEPENDENCY : DEPENDENCY)) {
            entry.fields.put(name, "exclusions".equals(name) ? "" : text(xml, r));
            entry.ranges.put(name, r);
            if ("exclusions".equals(name)) {
                entry.exclusionsRange = r;
            }
        } else if (exclusion != null && isChild(path, (entry.managed ? MANAGED_DEPENDENCY : DEPENDENCY) + "/exclusions/exclusion")) {
            exclusion.fields.put(name, text(xml, r));
        } else if (path.startsWith("project/properties/") && path.indexOf('/', "project/properties/".length()) < 0) {
            // doppelt definiert: Maven nimmt die letzte, also wird auch die letzte geändert
            properties.put(name, r);
            propertyValues.put(name, text(xml, r));
        }
    }

    /**
     * Steht in {@code fields} dieses g:a? Ausdrücke wie {@code ${project.groupId}} werden mit dieser POM
     * aufgelöst (Projekt, Parent, eigene Properties). Bleibt in der groupId ein Ausdruck offen (Property aus
     * dem Parent), reicht die gleiche artifactId.
     */
    boolean is(Map<String, String> fields, String groupId, String artifactId) {
        String a = fields.get("artifactId");
        if (!artifactId.equals(a) && !artifactId.equals(resolve(a))) {
            return false;
        }
        String g = fields.get("groupId");
        if (groupId.equals(g)) {
            return true;
        }
        String resolved = resolve(g);
        return resolved != null && (groupId.equals(resolved) || resolved.contains("${"));
    }

    /** Setzt bekannte Ausdrücke ein; Unbekanntes bleibt stehen. */
    String resolve(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String v = value;
        for (int round = 0; round < 5 && v.contains("${"); round++) {
            Matcher m = EXPRESSION.matcher(v);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String known = lookup(m.group(1).trim());
                changed |= known != null;
                m.appendReplacement(sb, Matcher.quoteReplacement(known == null ? m.group() : known));
            }
            m.appendTail(sb);
            v = sb.toString();
            if (!changed) {
                break;
            }
        }
        return v;
    }

    private String lookup(String name) {
        String n = name.startsWith("pom.") ? "project." + name.substring(4) : name;
        if ("project.groupId".equals(n) || "groupId".equals(n)) {
            return first(projectFields.get("groupId"), parentFields.get("groupId"));
        }
        if ("project.artifactId".equals(n) || "artifactId".equals(n)) {
            return projectFields.get("artifactId");
        }
        if ("project.version".equals(n) || "version".equals(n)) {
            return first(projectFields.get("version"), parentFields.get("version"));
        }
        if ("project.parent.groupId".equals(n)) {
            return parentFields.get("groupId");
        }
        if ("project.parent.version".equals(n)) {
            return parentFields.get("version");
        }
        return propertyValues.get(name);
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static boolean isChild(String path, String parent) {
        return path.startsWith(parent + "/") && path.indexOf('/', parent.length() + 1) < 0;
    }

    /** Einrückungsschritt aus dem ersten Kind von {@code project}; sonst zwei Leerzeichen. */
    String indentUnit(String xml) {
        if (firstChildStart >= 0) {
            String indent = PomText.lineIndent(xml, firstChildStart);
            if (!indent.isEmpty() && PomText.onlyIndentBefore(xml, firstChildStart)) {
                return indent;
            }
        }
        return "  ";
    }

    // --- Werte -----------------------------------------------------------------------------------

    /**
     * Der Wert eines Elements wie ein XML-Parser ihn liest, ohne Rand-Leerraum: Kommentare fallen weg,
     * CDATA zählt wörtlich, Entities werden aufgelöst.
     */
    static String text(String xml, Range r) {
        String raw = xml.substring(r.contentStart, r.contentEnd);
        if (raw.indexOf('<') < 0 && raw.indexOf('&') < 0) {
            return raw.trim();
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            if (raw.startsWith("<!--", i)) {
                int e = raw.indexOf("-->", i + 4);
                i = e < 0 ? raw.length() : e + 3;
            } else if (raw.startsWith("<![CDATA[", i)) {
                int e = raw.indexOf("]]>", i + 9);
                int end = e < 0 ? raw.length() : e;
                sb.append(raw, i + 9, end);
                i = e < 0 ? raw.length() : e + 3;
            } else if (raw.charAt(i) == '&') {
                int semi = raw.indexOf(';', i);
                String decoded = semi < 0 ? null : entity(raw.substring(i + 1, semi));
                if (decoded == null) {
                    sb.append('&');
                    i++;
                } else {
                    sb.append(decoded);
                    i = semi + 1;
                }
            } else {
                sb.append(raw.charAt(i));
                i++;
            }
        }
        return sb.toString().trim();
    }

    private static String entity(String name) {
        if ("amp".equals(name)) {
            return "&";
        }
        if ("lt".equals(name)) {
            return "<";
        }
        if ("gt".equals(name)) {
            return ">";
        }
        if ("quot".equals(name)) {
            return "\"";
        }
        if ("apos".equals(name)) {
            return "'";
        }
        if (name.startsWith("#")) {
            try {
                int code = name.startsWith("#x") || name.startsWith("#X")
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                return new String(Character.toChars(code));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Bereich {@code {start, end}} des eigentlichen Werts im Inhalt von {@code r}: ohne Leerraum und
     * Kommentare am Rand. So ersetzt {@code setVersion} auf {@code <version>1<!-- LTS --></version>}
     * nur die {@code 1} und der Kommentar bleibt.
     */
    static int[] valueSpan(String xml, Range r) {
        int start = r.contentStart;
        int end = r.contentEnd;
        while (start < end) {
            if (Character.isWhitespace(xml.charAt(start))) {
                start++;
            } else if (xml.startsWith("<!--", start)) {
                int e = xml.indexOf("-->", start + 4);
                if (e < 0 || e + 3 > end) {
                    break;
                }
                start = e + 3;
            } else {
                break;
            }
        }
        while (end > start) {
            if (Character.isWhitespace(xml.charAt(end - 1))) {
                end--;
            } else if (end - start >= 7 && xml.startsWith("-->", end - 3)) {
                int b = xml.lastIndexOf("<!--", end - 3);
                if (b < start) {
                    break;
                }
                end = b;
            } else {
                break;
            }
        }
        return new int[] {start, end};
    }

    // --- Tokenizer -------------------------------------------------------------------------------

    /**
     * Steht bei {@code lt} ein Kommentar, CDATA, eine Processing Instruction oder DOCTYPE, die Position
     * dahinter; sonst {@code -1}.
     */
    static int skipSpecial(String xml, int lt) {
        if (xml.startsWith("<!--", lt)) {
            return skip(xml, lt, "-->");
        }
        if (xml.startsWith("<![CDATA[", lt)) {
            return skip(xml, lt, "]]>");
        }
        if (xml.startsWith("<?", lt)) {
            return skip(xml, lt, "?>");
        }
        if (xml.startsWith("<!", lt)) {
            return skip(xml, lt, ">");
        }
        return -1;
    }

    private static int skip(String xml, int from, String end) {
        int e = xml.indexOf(end, from);
        if (e < 0) {
            throw new IllegalArgumentException("Nicht geschlossen ab Zeichen " + from + ": " + end);
        }
        return e + end.length();
    }

    /** Ende des Tags; {@code >} in Attributwerten zählt nicht. */
    static int tagEnd(String xml, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** Name ohne Namensraum-Präfix. */
    static String tagName(String xml, int from, int gt) {
        int end = from;
        while (end < gt) {
            char c = xml.charAt(end);
            if (Character.isWhitespace(c) || c == '/' || c == '>') {
                break;
            }
            end++;
        }
        String name = xml.substring(from, end);
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String join(List<String> stack) {
        StringBuilder sb = new StringBuilder();
        for (String s : stack) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
