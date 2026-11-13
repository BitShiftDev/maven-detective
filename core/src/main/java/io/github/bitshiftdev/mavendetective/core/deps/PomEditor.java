package io.github.bitshiftdev.mavendetective.core.deps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Textgenaue Änderungen an einer POM: Formatierung, Kommentare und Reihenfolge bleiben stehen, es
 * wird genau eine Stelle ersetzt oder eingefügt. Ergebnis ist ein {@link Edit}, das die IDE als
 * einen Undo-Schritt auf das Dokument anwendet und der {@code FixExecutor} auf die Datei.
 *
 * <p>Bewusst kein DOM-Roundtrip: der würde Einrückung, Kommentare und Zeilenenden verändern.
 */
public final class PomEditor {

    /** Eine Textänderung: an {@code offset} werden {@code length} Zeichen durch {@code text} ersetzt. */
    public static final class Edit {
        public final int offset;
        public final int length;
        public final String text;
        /** Zeile (ab 1) im geänderten Text, an der die Änderung steht – zum Hinspringen. */
        public final int line;
        /** Was getan wird, als Satz. */
        public final String description;

        Edit(int offset, int length, String text, int line, String description) {
            this.offset = offset;
            this.length = length;
            this.text = text;
            this.line = line;
            this.description = description;
        }

        public String apply(String content) {
            return content.substring(0, offset) + text + content.substring(offset + length);
        }
    }

    private PomEditor() {
    }

    /**
     * Legt die Version von {@code groupId:artifactId} im {@code dependencyManagement} dieser POM fest.
     * Gibt es dort schon einen Eintrag, wird dessen Version geändert – steht da {@code ${prop}} und
     * ist die Property in derselben POM definiert, ändert sich die Property. Sonst kommt ein neuer
     * Eintrag dazu, bei Bedarf samt {@code <dependencyManagement>}.
     *
     * @throws IllegalArgumentException wenn die Datei keine POM ist oder sich nicht zerlegen lässt
     */
    public static Edit pinManagedVersion(String xml, String groupId, String artifactId, String version) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException("Kein <project>-Element gefunden");
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        String unit = s.indentUnit(xml);

        for (DepEntry e : s.managed) {
            if (!groupId.equals(e.fields.get("groupId")) || !artifactId.equals(e.fields.get("artifactId"))) {
                continue;
            }
            String type = e.fields.get("type");
            String classifier = e.fields.get("classifier");
            if (type != null && !"jar".equals(type) || classifier != null && !classifier.isEmpty()) {
                continue;
            }
            Range v = e.ranges.get("version");
            if (v == null) {
                Range a = e.ranges.get("artifactId");
                String indent = lineIndent(xml, a.tagStart);
                String text = nl + indent + "<version>" + version + "</version>";
                return new Edit(a.tagEnd, 0, text, lineOf(xml, a.tagEnd) + 1,
                        "Version " + version + " im vorhandenen dependencyManagement-Eintrag ergänzt");
            }
            String current = xml.substring(v.contentStart, v.contentEnd).trim();
            if (current.startsWith("${") && current.endsWith("}") && current.indexOf('}') == current.length() - 1) {
                String prop = current.substring(2, current.length() - 1);
                Range p = s.properties.get(prop);
                if (p != null) {
                    return new Edit(p.contentStart, p.contentEnd - p.contentStart, version, lineOf(xml, p.contentStart),
                            "Property ${" + prop + "} von " + xml.substring(p.contentStart, p.contentEnd).trim() + " auf " + version
                                    + " gesetzt (wirkt auf alle Stellen, die sie nutzen)");
                }
            }
            return new Edit(v.contentStart, v.contentEnd - v.contentStart, version, lineOf(xml, v.contentStart),
                    "Version im vorhandenen dependencyManagement-Eintrag von " + current + " auf " + version + " geändert");
        }

        if (s.dmDependenciesEnd >= 0) {
            int at = s.dmDependenciesEnd;
            String closingIndent = lineIndent(xml, at);
            String block = dependency(groupId, artifactId, version, closingIndent + unit, unit, nl);
            return insertBefore(xml, at, block, closingIndent, nl,
                    groupId + ":" + artifactId + ":" + version + " in dependencyManagement eingetragen");
        }
        if (s.dmEnd >= 0) {
            int at = s.dmEnd;
            String closingIndent = lineIndent(xml, at);
            String inner = closingIndent + unit;
            String block = inner + "<dependencies>" + nl
                    + dependency(groupId, artifactId, version, inner + unit, unit, nl)
                    + inner + "</dependencies>" + nl;
            return insertBefore(xml, at, block, closingIndent, nl,
                    groupId + ":" + artifactId + ":" + version + " in dependencyManagement eingetragen");
        }
        int at = s.dependenciesStart >= 0 ? s.dependenciesStart : s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = at == s.projectEnd ? lineIndent(xml, at) + unit : lineIndent(xml, at);
        String block = indent + "<dependencyManagement>" + nl
                + indent + unit + "<dependencies>" + nl
                + dependency(groupId, artifactId, version, indent + unit + unit, unit, nl)
                + indent + unit + "</dependencies>" + nl
                + indent + "</dependencyManagement>" + nl;
        if (at != s.projectEnd) {
            block += nl;
        }
        String followIndent = at == s.projectEnd ? lineIndent(xml, at) : indent;
        return insertBefore(xml, at, block, followIndent, nl,
                "<dependencyManagement> mit " + groupId + ":" + artifactId + ":" + version + " angelegt");
    }

    /**
     * Entfernt {@code <exclusion>} {@code exclusion} (groupId:artifactId) bei der Dependency
     * {@code groupId:artifactId} unter {@code project/dependencies} bzw. – mit {@code managed} –
     * unter {@code project/dependencyManagement/dependencies}. Ist es die letzte Exclusion, geht
     * {@code <exclusions>} mit. Ganze Zeilen werden entfernt, wenn das Element allein darauf steht.
     *
     * @throws IllegalArgumentException wenn Dependency oder Exclusion nicht zu finden sind
     */
    public static Edit removeExclusion(String xml, String groupId, String artifactId, String exclusion, boolean managed) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException("Kein <project>-Element gefunden");
        }
        int colon = exclusion.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Exclusion erwartet groupId:artifactId: " + exclusion);
        }
        String eg = exclusion.substring(0, colon);
        String ea = exclusion.substring(colon + 1);
        String where = managed ? "dependencyManagement" : "dependencies";
        DepEntry found = null;
        ExclusionEntry target = null;
        for (DepEntry d : s.deps) {
            if (d.managed != managed || !groupId.equals(d.fields.get("groupId")) || !artifactId.equals(d.fields.get("artifactId"))) {
                continue;
            }
            for (ExclusionEntry e : d.exclusions) {
                if (target == null && eg.equals(e.fields.get("groupId")) && ea.equals(e.fields.get("artifactId"))) {
                    found = d; // bei doppelter Angabe die erste – die zweite wirkt danach weiter
                    target = e;
                }
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Exclusion " + exclusion + " bei " + groupId + ":" + artifactId + " unter " + where
                    + " nicht gefunden");
        }
        Range r = found.exclusions.size() == 1 && found.exclusionsRange != null ? found.exclusionsRange : target.range;
        int start = r.tagStart;
        int end = r.tagEnd;
        int lineStart = lineStart(xml, start);
        int lineEnd = lineEnd(xml, end);
        if (xml.substring(lineStart, start).trim().isEmpty() && xml.substring(end, lineEnd).trim().isEmpty()) {
            start = lineStart;
            end = lineEnd < xml.length() ? lineEnd + 1 : lineEnd;
        }
        String what = r == target.range ? "<exclusion> " + exclusion : "<exclusions> mit " + exclusion;
        return new Edit(start, end - start, "", lineOf(xml, start), what + " bei " + groupId + ":" + artifactId + " entfernt");
    }

    /**
     * Fügt bei der Dependency {@code groupId:artifactId} unter {@code project/dependencies} die
     * {@code <exclusion>} {@code exclusion} (groupId:artifactId) ein – in vorhandene
     * {@code <exclusions>} ans Ende, sonst mit neuem {@code <exclusions>} vor {@code </dependency>}.
     * Bei mehreren Einträgen (Classifier, Typ) zählt der erste ohne Classifier.
     *
     * @throws IllegalArgumentException wenn die Dependency fehlt oder die Exclusion schon da ist
     */
    public static Edit addExclusion(String xml, String groupId, String artifactId, String exclusion) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException("Kein <project>-Element gefunden");
        }
        int colon = exclusion.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Exclusion erwartet groupId:artifactId: " + exclusion);
        }
        String eg = exclusion.substring(0, colon);
        String ea = exclusion.substring(colon + 1);
        DepEntry found = null;
        for (DepEntry d : s.deps) {
            if (d.managed || !groupId.equals(d.fields.get("groupId")) || !artifactId.equals(d.fields.get("artifactId"))) {
                continue;
            }
            String classifier = d.fields.get("classifier");
            if (found == null || (classifier == null || classifier.isEmpty()) && found.fields.get("classifier") != null) {
                found = d;
            }
        }
        if (found == null || found.range == null) {
            throw new IllegalArgumentException("Dependency " + groupId + ":" + artifactId + " unter dependencies nicht gefunden");
        }
        for (ExclusionEntry e : found.exclusions) {
            if (eg.equals(e.fields.get("groupId")) && ea.equals(e.fields.get("artifactId"))) {
                throw new IllegalArgumentException(exclusion + " ist bei " + groupId + ":" + artifactId + " schon ausgeschlossen");
            }
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        String unit = s.indentUnit(xml);
        String description = "<exclusion> " + exclusion + " bei " + groupId + ":" + artifactId + " eingefügt";
        Range child = found.ranges.get("artifactId");
        String childIndent = child != null && xml.substring(lineStart(xml, child.tagStart), child.tagStart).trim().isEmpty()
                ? lineIndent(xml, child.tagStart) : lineIndent(xml, found.range.tagStart) + unit;
        if (found.exclusionsRange != null) {
            int at = found.exclusionsRange.contentEnd;
            String closingIndent = xml.substring(lineStart(xml, at), at).trim().isEmpty() ? lineIndent(xml, at) : childIndent;
            String block = exclusionBlock(eg, ea, closingIndent + unit, unit, nl);
            return insertBefore(xml, at, block, closingIndent, nl, description, "<exclusion>");
        }
        int at = found.range.contentEnd;
        String closingIndent = lineIndent(xml, found.range.tagStart);
        String block = childIndent + "<exclusions>" + nl
                + exclusionBlock(eg, ea, childIndent + unit, unit, nl)
                + childIndent + "</exclusions>" + nl;
        return insertBefore(xml, at, block, closingIndent, nl, description, "<exclusion>");
    }

    private static String exclusionBlock(String g, String a, String indent, String unit, String nl) {
        return indent + "<exclusion>" + nl
                + indent + unit + "<groupId>" + g + "</groupId>" + nl
                + indent + unit + "<artifactId>" + a + "</artifactId>" + nl
                + indent + "</exclusion>" + nl;
    }

    /** Position des Zeilenumbruchs nach {@code offset} (bzw. Textende). */
    private static int lineEnd(String xml, int offset) {
        int i = xml.indexOf('\n', offset);
        return i < 0 ? xml.length() : i;
    }

    private static String dependency(String g, String a, String v, String indent, String unit, String nl) {
        return indent + "<dependency>" + nl
                + indent + unit + "<groupId>" + g + "</groupId>" + nl
                + indent + unit + "<artifactId>" + a + "</artifactId>" + nl
                + indent + unit + "<version>" + v + "</version>" + nl
                + indent + "</dependency>" + nl;
    }

    /**
     * Fügt {@code block} (fertig eingerückt, endet mit Zeilenumbruch) vor dem Tag bei {@code at} ein.
     * Steht vor dem Tag nur Einrückung, beginnt der Block am Zeilenanfang; sonst bekommt er eine
     * eigene Zeile und das Tag danach die Einrückung {@code followIndent}.
     */
    private static Edit insertBefore(String xml, int at, String block, String followIndent, String nl, String description) {
        return insertBefore(xml, at, block, followIndent, nl, description, "<dependency>");
    }

    /** Wie oben; die Sprungzeile ist die mit {@code marker}. */
    private static Edit insertBefore(String xml, int at, String block, String followIndent, String nl, String description, String marker) {
        int lineStart = lineStart(xml, at);
        if (xml.substring(lineStart, at).trim().isEmpty()) {
            return new Edit(lineStart, 0, block, lineOf(xml, lineStart) + firstContentLine(block, marker), description);
        }
        String text = nl + block + followIndent;
        return new Edit(at, 0, text, lineOf(xml, at) + 1 + firstContentLine(block, marker), description);
    }

    /** Zeile (ab 0) im Block, auf der {@code marker} steht – dahin soll der Sprung gehen. */
    private static int firstContentLine(String block, String marker) {
        int idx = Math.max(0, block.indexOf(marker));
        int n = 0;
        for (int i = 0; i < idx; i++) {
            if (block.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    static int lineOf(String xml, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < xml.length(); i++) {
            if (xml.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int lineStart(String xml, int offset) {
        int i = offset;
        while (i > 0 && xml.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    private static String lineIndent(String xml, int offset) {
        int start = lineStart(xml, offset);
        int end = start;
        while (end < xml.length() && (xml.charAt(end) == ' ' || xml.charAt(end) == '\t')) {
            end++;
        }
        return xml.substring(start, end);
    }

    /** Ein Element: Start des öffnenden Tags, Ende des öffnenden Tags, Inhalt, Ende des schließenden Tags. */
    static final class Range {
        int tagStart;
        int contentStart;
        int contentEnd;
        int tagEnd;
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
    }

    /** Ein {@code <exclusion>} einer {@link DepEntry}. */
    static final class ExclusionEntry {
        final Map<String, String> fields = new HashMap<String, String>();
        Range range;
    }

    private static final String DEPENDENCY = "project/dependencies/dependency";
    private static final String MANAGED_DEPENDENCY = "project/dependencyManagement/dependencies/dependency";

    /**
     * Ein Durchlauf über die Tags. Kommentare, CDATA, Processing Instructions und DOCTYPE werden
     * übersprungen; Profile zählen nicht, weil nur Pfade direkt unter {@code project} ausgewertet werden.
     */
    static final class Scan {
        final List<DepEntry> managed = new ArrayList<DepEntry>();
        final List<DepEntry> deps = new ArrayList<DepEntry>();
        final Map<String, Range> properties = new HashMap<String, Range>();
        int dmEnd = -1;
        int dmDependenciesEnd = -1;
        int dependenciesStart = -1;
        int buildStart = -1;
        int projectEnd = -1;
        int firstChildStart = -1;

        static Scan of(String xml) {
            Scan s = new Scan();
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
                if (xml.startsWith("<!--", lt)) {
                    i = skip(xml, lt, "-->");
                    continue;
                }
                if (xml.startsWith("<![CDATA[", lt)) {
                    i = skip(xml, lt, "]]>");
                    continue;
                }
                if (xml.startsWith("<?", lt)) {
                    i = skip(xml, lt, "?>");
                    continue;
                }
                if (xml.startsWith("<!", lt)) {
                    i = skip(xml, lt, ">");
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
                    int contentStart = contentStarts.get(contentStarts.size() - 1);
                    Range r = new Range();
                    r.tagStart = starts.get(starts.size() - 1);
                    r.contentStart = contentStart;
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
                    } else if (entry != null && exclusion != null && (DEPENDENCY + "/exclusions/exclusion").equals(path)
                            || entry != null && exclusion != null && (MANAGED_DEPENDENCY + "/exclusions/exclusion").equals(path)) {
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
                    } else if (DEPENDENCY.equals(path) || MANAGED_DEPENDENCY.equals(path)) {
                        entry = new DepEntry(MANAGED_DEPENDENCY.equals(path));
                    } else if (entry != null && ((DEPENDENCY + "/exclusions/exclusion").equals(path)
                            || (MANAGED_DEPENDENCY + "/exclusions/exclusion").equals(path))) {
                        exclusion = new ExclusionEntry();
                    }
                    if (empty) {
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

        private void onClose(String path, String name, Range r, String xml, DepEntry entry, ExclusionEntry exclusion) {
            if ("project".equals(path)) {
                projectEnd = r.contentEnd;
            } else if ("project/dependencyManagement".equals(path)) {
                dmEnd = r.contentEnd;
            } else if ("project/dependencyManagement/dependencies".equals(path)) {
                dmDependenciesEnd = r.contentEnd;
            } else if (entry != null && isChild(path, entry.managed ? MANAGED_DEPENDENCY : DEPENDENCY)) {
                entry.fields.put(name, xml.substring(r.contentStart, r.contentEnd).trim());
                entry.ranges.put(name, r);
                if ("exclusions".equals(name)) {
                    entry.exclusionsRange = r;
                }
            } else if (exclusion != null && isChild(path, (entry.managed ? MANAGED_DEPENDENCY : DEPENDENCY) + "/exclusions/exclusion")) {
                exclusion.fields.put(name, xml.substring(r.contentStart, r.contentEnd).trim());
            } else if (path.startsWith("project/properties/") && path.indexOf('/', "project/properties/".length()) < 0
                    && !properties.containsKey(name)) {
                properties.put(name, r);
            }
        }

        private static boolean isChild(String path, String parent) {
            return path.startsWith(parent + "/") && path.indexOf('/', parent.length() + 1) < 0;
        }

        /** Einrückungsschritt aus dem ersten Kind von {@code project}; sonst zwei Leerzeichen. */
        String indentUnit(String xml) {
            if (firstChildStart >= 0) {
                String indent = lineIndent(xml, firstChildStart);
                if (!indent.isEmpty() && xml.substring(lineStart(xml, firstChildStart), firstChildStart).trim().isEmpty()) {
                    return indent;
                }
            }
            return "  ";
        }

        private static int skip(String xml, int from, String end) {
            int e = xml.indexOf(end, from);
            if (e < 0) {
                throw new IllegalArgumentException("Nicht geschlossen ab Zeichen " + from + ": " + end);
            }
            return e + end.length();
        }

        /** Ende des Tags; {@code >} in Attributwerten zählt nicht. */
        private static int tagEnd(String xml, int lt) {
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

        private static String tagName(String xml, int from, int gt) {
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
}
