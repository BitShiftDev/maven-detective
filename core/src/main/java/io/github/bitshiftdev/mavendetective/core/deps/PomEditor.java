package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

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
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
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
                        Texts.t("edit.managed.version.added", version));
            }
            String current = xml.substring(v.contentStart, v.contentEnd).trim();
            if (current.startsWith("${") && current.endsWith("}") && current.indexOf('}') == current.length() - 1) {
                String prop = current.substring(2, current.length() - 1);
                Range p = s.properties.get(prop);
                if (p != null) {
                    return replaceContent(xml, p, version,
                            Texts.t("edit.property.changed", "${" + prop + "}", xml.substring(p.contentStart, p.contentEnd).trim(), version));
                }
            }
            return replaceContent(xml, v, version, Texts.t("edit.managed.version.changed", current, version));
        }

        if (s.dmDependencies != null) {
            String closingIndent = lineIndent(xml, s.dmDependencies.contentEnd);
            String block = dependency(groupId, artifactId, version, closingIndent + unit, unit, nl);
            return insertInto(xml, s.dmDependencies, block, closingIndent, nl,
                    Texts.t("edit.managed.added", groupId + ":" + artifactId + ":" + version), "<dependency>");
        }
        if (s.dm != null) {
            String closingIndent = lineIndent(xml, s.dm.contentEnd);
            String inner = closingIndent + unit;
            String block = inner + "<dependencies>" + nl
                    + dependency(groupId, artifactId, version, inner + unit, unit, nl)
                    + inner + "</dependencies>" + nl;
            return insertInto(xml, s.dm, block, closingIndent, nl,
                    Texts.t("edit.managed.added", groupId + ":" + artifactId + ":" + version), "<dependency>");
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
                Texts.t("edit.managed.created", groupId + ":" + artifactId + ":" + version));
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
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        int colon = exclusion.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(Texts.t("edit.exclusion.format", exclusion));
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
            throw new IllegalArgumentException(Texts.t("edit.exclusion.not.found", exclusion, groupId + ":" + artifactId, where));
        }
        Range r = found.exclusions.size() == 1 && found.exclusionsRange != null
                && onlyWhitespaceBesides(xml, found.exclusionsRange, target.range) ? found.exclusionsRange : target.range;
        int start = r.tagStart;
        int end = r.tagEnd;
        int lineStart = lineStart(xml, start);
        int lineEnd = lineEnd(xml, end);
        if (xml.substring(lineStart, start).trim().isEmpty() && xml.substring(end, lineEnd).trim().isEmpty()) {
            start = lineStart;
            end = lineEnd < xml.length() ? lineEnd + 1 : lineEnd;
        }
        return new Edit(start, end - start, "", lineOf(xml, start),
                Texts.t(r == target.range ? "edit.exclusion.removed" : "edit.exclusions.removed", exclusion, groupId + ":" + artifactId));
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
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        int colon = exclusion.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(Texts.t("edit.exclusion.format", exclusion));
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
            throw new IllegalArgumentException(Texts.t("edit.dependency.not.found", groupId + ":" + artifactId));
        }
        for (ExclusionEntry e : found.exclusions) {
            if (eg.equals(e.fields.get("groupId")) && ea.equals(e.fields.get("artifactId"))) {
                throw new IllegalArgumentException(Texts.t("edit.exclusion.exists", exclusion, groupId + ":" + artifactId));
            }
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        String unit = s.indentUnit(xml);
        String description = Texts.t("edit.exclusion.added", exclusion, groupId + ":" + artifactId);
        Range child = found.ranges.get("artifactId");
        String childIndent = child != null && xml.substring(lineStart(xml, child.tagStart), child.tagStart).trim().isEmpty()
                ? lineIndent(xml, child.tagStart) : lineIndent(xml, found.range.tagStart) + unit;
        if (found.exclusionsRange != null) {
            int at = found.exclusionsRange.contentEnd;
            String closingIndent = xml.substring(lineStart(xml, at), at).trim().isEmpty() ? lineIndent(xml, at) : childIndent;
            String block = exclusionBlock(eg, ea, closingIndent + unit, unit, nl);
            return insertInto(xml, found.exclusionsRange, block, closingIndent, nl, description, "<exclusion>");
        }
        int at = found.range.contentEnd;
        String closingIndent = lineIndent(xml, found.range.tagStart);
        String block = childIndent + "<exclusions>" + nl
                + exclusionBlock(eg, ea, childIndent + unit, unit, nl)
                + childIndent + "</exclusions>" + nl;
        return insertBefore(xml, at, block, closingIndent, nl, description, "<exclusion>");
    }

    /**
     * Deklariert {@code groupId:artifactId} unter {@code project/dependencies} – am Ende der
     * vorhandenen Liste, sonst mit neuem {@code <dependencies>} vor {@code <build>} bzw. am Ende von
     * {@code <project>}. {@code version} {@code null}, wenn sie aus dem dependencyManagement kommt;
     * {@code scope}, {@code type}, {@code classifier} nur, wenn nicht Standard.
     *
     * @throws IllegalArgumentException wenn die Dependency schon dasteht
     */
    public static Edit addDependency(String xml, String groupId, String artifactId, String version, String scope, String type,
                                     String classifier) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        for (DepEntry d : s.deps) {
            if (!d.managed && groupId.equals(d.fields.get("groupId")) && artifactId.equals(d.fields.get("artifactId"))
                    && same(classifier, d.fields.get("classifier"))) {
                throw new IllegalArgumentException(Texts.t("edit.dependency.exists", groupId + ":" + artifactId));
            }
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        String unit = s.indentUnit(xml);
        String coords = groupId + ":" + artifactId + (version == null ? "" : ":" + version)
                + (scope == null || "compile".equals(scope) ? "" : " (" + scope + ")");
        if (s.dependencies != null) {
            String closingIndent = lineIndent(xml, s.dependencies.tagStart);
            String block = dependencyBlock(groupId, artifactId, version, scope, type, classifier, closingIndent + unit, unit, nl);
            return insertInto(xml, s.dependencies, block, closingIndent, nl, Texts.t("edit.dependency.added", coords), "<dependency>");
        }
        int at = s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = at == s.projectEnd ? lineIndent(xml, at) + unit : lineIndent(xml, at);
        String block = indent + "<dependencies>" + nl
                + dependencyBlock(groupId, artifactId, version, scope, type, classifier, indent + unit, unit, nl)
                + indent + "</dependencies>" + nl;
        if (at != s.projectEnd) {
            block += nl;
        }
        String followIndent = at == s.projectEnd ? lineIndent(xml, at) : indent;
        return insertBefore(xml, at, block, followIndent, nl, Texts.t("edit.dependencies.created", coords));
    }

    /**
     * Entfernt die Dependency {@code groupId:artifactId} (mit {@code classifier}, sonst die erste
     * ohne) aus {@code project/dependencies}. War es die einzige, geht {@code <dependencies>} mit.
     *
     * @throws IllegalArgumentException wenn sie dort nicht steht
     */
    public static Edit removeDependency(String xml, String groupId, String artifactId, String classifier) {
        Scan s = Scan.of(xml);
        DepEntry found = find(s, groupId, artifactId, classifier);
        int count = 0;
        for (DepEntry d : s.deps) {
            if (!d.managed) {
                count++;
            }
        }
        Range r = count == 1 && s.dependencies != null && onlyWhitespaceBesides(xml, s.dependencies, found.range)
                ? s.dependencies : found.range;
        int start = r.tagStart;
        int end = r.tagEnd;
        int lineStart = lineStart(xml, start);
        int lineEnd = lineEnd(xml, end);
        if (xml.substring(lineStart, start).trim().isEmpty() && xml.substring(end, lineEnd).trim().isEmpty()) {
            start = lineStart;
            end = lineEnd < xml.length() ? lineEnd + 1 : lineEnd;
        }
        return new Edit(start, end - start, "", lineOf(xml, start),
                Texts.t(r == found.range ? "edit.dependency.removed" : "edit.dependency.removed.block", groupId + ":" + artifactId));
    }

    /**
     * Setzt den Scope der Dependency {@code groupId:artifactId} unter {@code project/dependencies}:
     * vorhandenes {@code <scope>} ändern, sonst nach {@code <version>} (bzw. artifactId, type,
     * classifier) einfügen.
     *
     * @throws IllegalArgumentException wenn sie fehlt oder den Scope schon hat
     */
    public static Edit setScope(String xml, String groupId, String artifactId, String scope, String classifier) {
        Scan s = Scan.of(xml);
        DepEntry found = find(s, groupId, artifactId, classifier);
        String description = Texts.t("edit.scope.set", groupId + ":" + artifactId, scope);
        Range current = found.ranges.get("scope");
        if (current != null) {
            String old = xml.substring(current.contentStart, current.contentEnd).trim();
            if (scope.equals(old)) {
                throw new IllegalArgumentException(Texts.t("edit.scope.exists", groupId + ":" + artifactId, scope));
            }
            return replaceContent(xml, current, scope, Texts.t("edit.scope.changed", groupId + ":" + artifactId, old, scope));
        }
        Range after = null;
        for (String child : new String[] {"artifactId", "version", "type", "classifier"}) {
            Range r = found.ranges.get(child);
            if (r != null && (after == null || r.tagEnd > after.tagEnd)) {
                after = r;
            }
        }
        if (after == null) {
            throw new IllegalArgumentException(Texts.t("edit.no.artifact.id", groupId + ":" + artifactId));
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        boolean ownLine = xml.substring(lineStart(xml, after.tagStart), after.tagStart).trim().isEmpty();
        if (!ownLine) {
            return new Edit(after.tagEnd, 0, "<scope>" + scope + "</scope>", lineOf(xml, after.tagEnd), description);
        }
        String text = nl + lineIndent(xml, after.tagStart) + "<scope>" + scope + "</scope>";
        return new Edit(after.tagEnd, 0, text, lineOf(xml, after.tagEnd) + 1, description);
    }

    /**
     * Setzt eine Version an genau der Stelle, die sie heute festlegt (Modul A6):
     * {@code section} = {@code parent} ändert {@code <parent><version>} (groupId/artifactId müssen
     * passen), {@code dependencies} die Version der Dependency unter {@code project/dependencies},
     * {@code dependencyManagement} die des verwalteten Eintrags (auch BOM-Import mit
     * {@code type=pom}). Ersetzt wird der Text wörtlich – steht dort {@code ${prop}}, ist das
     * {@link #setProperty} zu nehmen.
     *
     * @throws IllegalArgumentException wenn die Stelle fehlt oder keine {@code <version>} hat
     */
    public static Edit setVersion(String xml, String section, String groupId, String artifactId, String version) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        Range v;
        String what;
        if ("parent".equals(section)) {
            if (!groupId.equals(s.parentFields.get("groupId")) || !artifactId.equals(s.parentFields.get("artifactId"))) {
                throw new IllegalArgumentException(Texts.t("edit.parent.mismatch", groupId + ":" + artifactId));
            }
            v = s.parentRanges.get("version");
            what = "Parent " + artifactId;
        } else {
            boolean managed = "dependencyManagement".equals(section);
            DepEntry found = null;
            for (DepEntry d : managed ? s.managed : s.deps) {
                if (d.managed == managed && groupId.equals(d.fields.get("groupId")) && artifactId.equals(d.fields.get("artifactId"))
                        && d.ranges.get("version") != null
                        && (found == null || found.fields.get("classifier") != null && d.fields.get("classifier") == null)) {
                    found = d;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException(Texts.t("edit.version.not.found", groupId + ":" + artifactId, section));
            }
            v = found.ranges.get("version");
            what = Texts.t(managed ? "edit.what.managed" : "edit.what.dependency", artifactId);
        }
        if (v == null) {
            throw new IllegalArgumentException(Texts.t("edit.no.version", groupId + ":" + artifactId));
        }
        String current = xml.substring(v.contentStart, v.contentEnd).trim();
        return replaceContent(xml, v, version, what + ": " + Texts.t("edit.version.changed", current, version));
    }

    /**
     * Setzt die Property {@code name} unter {@code project/properties} auf {@code value}; fehlt sie,
     * kommt sie dazu (bei Bedarf samt {@code <properties>}). So überschreibt ein Projekt z. B. eine
     * Version, die ein Parent wie {@code spring-boot-starter-parent} über eine Property verwaltet.
     */
    public static Edit setProperty(String xml, String name, String value) {
        Scan s = Scan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        Range p = s.properties.get(name);
        if (p != null) {
            String current = xml.substring(p.contentStart, p.contentEnd).trim();
            return replaceContent(xml, p, value, Texts.t("edit.property.changed", "${" + name + "}", current, value));
        }
        String nl = xml.contains("\r\n") ? "\r\n" : "\n";
        String unit = s.indentUnit(xml);
        String element = "<" + name + ">" + value + "</" + name + ">";
        if (s.propertiesRange != null) {
            String closingIndent = lineIndent(xml, s.propertiesRange.tagStart);
            return insertInto(xml, s.propertiesRange, closingIndent + unit + element + nl, closingIndent, nl,
                    Texts.t("edit.property.added", "${" + name + "} = " + value), "<" + name + ">");
        }
        int at = s.dmStart >= 0 ? s.dmStart : s.dependenciesStart >= 0 ? s.dependenciesStart : s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = at == s.projectEnd ? lineIndent(xml, at) + unit : lineIndent(xml, at);
        String block = indent + "<properties>" + nl + indent + unit + element + nl + indent + "</properties>" + nl;
        if (at != s.projectEnd) {
            block += nl;
        }
        String followIndent = at == s.projectEnd ? lineIndent(xml, at) : indent;
        return insertBefore(xml, at, block, followIndent, nl,
                Texts.t("edit.properties.created", "${" + name + "} = " + value), "<" + name + ">");
    }

    /**
     * Die Textänderung zu einem POM-Quick-Fix ({@code pin-version}, {@code remove-exclusion},
     * {@code add-exclusion}, {@code add-dependency}, {@code remove-dependency}, {@code set-scope},
     * {@code set-version}, {@code replace-url}).
     *
     * @throws IllegalArgumentException bei anderem Typ, fehlenden Parametern oder wenn die Stelle fehlt
     */
    public static Edit forFix(String xml, FixAction fix) {
        String type = fix.getType();
        if (FixAction.REPLACE_URL.equals(type)) {
            return replaceUrl(xml, required(fix, "search"), required(fix, "replace"));
        }
        if (FixAction.SET_VERSION.equals(type) && fix.param("property") != null) {
            return setProperty(xml, fix.param("property"), required(fix, "version"));
        }
        String artifact = fix.param("artifact");
        int colon = artifact == null ? -1 : artifact.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(Texts.t("edit.needs.artifact", type));
        }
        String g = artifact.substring(0, colon);
        String a = artifact.substring(colon + 1);
        if (FixAction.PIN_VERSION.equals(type)) {
            return pinManagedVersion(xml, g, a, required(fix, "version"));
        }
        if (FixAction.REMOVE_EXCLUSION.equals(type)) {
            return removeExclusion(xml, g, a, required(fix, "exclusion"), "dependencyManagement".equals(fix.param("section")));
        }
        if (FixAction.ADD_EXCLUSION.equals(type)) {
            return addExclusion(xml, g, a, required(fix, "exclusion"));
        }
        if (FixAction.ADD_DEPENDENCY.equals(type)) {
            return addDependency(xml, g, a, fix.param("version"), fix.param("scope"), fix.param("type"), fix.param("classifier"));
        }
        if (FixAction.REMOVE_DEPENDENCY.equals(type)) {
            return removeDependency(xml, g, a, fix.param("classifier"));
        }
        if (FixAction.SET_SCOPE.equals(type)) {
            return setScope(xml, g, a, required(fix, "scope"), fix.param("classifier"));
        }
        if (FixAction.SET_VERSION.equals(type)) {
            return setVersion(xml, required(fix, "section"), g, a, required(fix, "version"));
        }
        throw new IllegalArgumentException(Texts.t("edit.not.pom.fix", type));
    }

    /** Ändert dieser Quick-Fix-Typ eine POM über {@link #forFix}? */
    public static boolean isPomFix(String type) {
        return FixAction.PIN_VERSION.equals(type) || FixAction.REMOVE_EXCLUSION.equals(type) || FixAction.ADD_EXCLUSION.equals(type)
                || FixAction.ADD_DEPENDENCY.equals(type) || FixAction.REMOVE_DEPENDENCY.equals(type) || FixAction.SET_SCOPE.equals(type)
                || FixAction.SET_VERSION.equals(type) || FixAction.REPLACE_URL.equals(type);
    }

    /** Elemente, deren {@code <url>} {@link #replaceUrl} ändert. */
    private static final java.util.Set<String> URL_OWNERS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "repository", "pluginRepository", "snapshotRepository", "mirror"));

    /**
     * Ersetzt die Adresse {@code search} durch {@code replace} – nur dort, wo sie der ganze Wert eines
     * {@code <url>} in {@code <repository>}, {@code <pluginRepository>}, {@code <snapshotRepository>}
     * oder {@code <mirror>} ist (überall, auch in Profilen; ein abschließender {@code /} darf abweichen).
     * Längere Adressen mit demselben Anfang und Kommentare bleiben stehen. Geht auch für settings.xml.
     * Mehrere Stellen ergeben ein {@link Edit} von der ersten bis zur letzten.
     *
     * @throws IllegalArgumentException wenn keine solche {@code <url>} vorkommt
     */
    public static Edit replaceUrl(String xml, String search, String replace) {
        String from = stripSlash(search.trim());
        String to = stripSlash(replace.trim());
        List<int[]> hits = new ArrayList<int[]>();
        List<String> stack = new ArrayList<String>();
        int open = -1;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                i = Scan.skip(xml, lt, "-->");
                continue;
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                i = Scan.skip(xml, lt, "]]>");
                continue;
            }
            if (xml.startsWith("<?", lt)) {
                i = Scan.skip(xml, lt, "?>");
                continue;
            }
            if (xml.startsWith("<!", lt)) {
                i = Scan.skip(xml, lt, ">");
                continue;
            }
            int gt = Scan.tagEnd(xml, lt);
            if (gt < 0) {
                throw new IllegalArgumentException("Unvollständiges Tag ab Zeichen " + lt);
            }
            boolean closing = xml.charAt(lt + 1) == '/';
            String name = Scan.tagName(xml, closing ? lt + 2 : lt + 1, gt);
            if (closing) {
                if (open >= 0 && "url".equals(name) && stack.size() >= 2 && URL_OWNERS.contains(stack.get(stack.size() - 2))) {
                    String raw = xml.substring(open, lt);
                    String value = raw.trim();
                    if (stripSlash(value).equals(from)) {
                        int start = open + raw.indexOf(value);
                        hits.add(new int[] {start, start + from.length()});
                    }
                }
                open = -1;
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else if (xml.charAt(gt - 1) != '/') {
                stack.add(name);
                open = gt + 1;
            }
            i = gt + 1;
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException(Texts.t("edit.url.not.found", from));
        }
        int start = hits.get(0)[0];
        int end = hits.get(hits.size() - 1)[1];
        StringBuilder text = new StringBuilder();
        int at = start;
        for (int[] h : hits) {
            text.append(xml, at, h[0]).append(to);
            at = h[1];
        }
        return new Edit(start, end - start, text.toString(), lineOf(xml, start),
                Texts.t("edit.url.changed", from, to, hits.size()));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String required(FixAction fix, String key) {
        String v = fix.param(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(Texts.t("edit.needs", fix.getType(), key));
        }
        return v;
    }

    /** Dependency unter {@code project/dependencies}; ohne Classifier bevorzugt die ohne. */
    private static DepEntry find(Scan s, String groupId, String artifactId, String classifier) {
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        DepEntry found = null;
        for (DepEntry d : s.deps) {
            if (d.managed || !groupId.equals(d.fields.get("groupId")) || !artifactId.equals(d.fields.get("artifactId"))) {
                continue;
            }
            String c = d.fields.get("classifier");
            if (classifier != null && !classifier.isEmpty()) {
                if (classifier.equals(c)) {
                    return d;
                }
            } else if (found == null || (c == null || c.isEmpty()) && found.fields.get("classifier") != null) {
                found = d;
            }
        }
        if (found == null || found.range == null) {
            throw new IllegalArgumentException(Texts.t("edit.dependency.not.found", groupId + ":" + artifactId));
        }
        return found;
    }

    private static boolean same(String classifier, String other) {
        String a = classifier == null ? "" : classifier;
        String b = other == null ? "" : other;
        return a.equals(b);
    }

    private static String dependencyBlock(String g, String a, String v, String scope, String type, String classifier, String indent,
                                          String unit, String nl) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("<dependency>").append(nl);
        sb.append(indent).append(unit).append("<groupId>").append(g).append("</groupId>").append(nl);
        sb.append(indent).append(unit).append("<artifactId>").append(a).append("</artifactId>").append(nl);
        if (v != null && !v.isEmpty()) {
            sb.append(indent).append(unit).append("<version>").append(v).append("</version>").append(nl);
        }
        if (type != null && !type.isEmpty() && !"jar".equals(type)) {
            sb.append(indent).append(unit).append("<type>").append(type).append("</type>").append(nl);
        }
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(indent).append(unit).append("<classifier>").append(classifier).append("</classifier>").append(nl);
        }
        if (scope != null && !scope.isEmpty() && !"compile".equals(scope)) {
            sb.append(indent).append(unit).append("<scope>").append(scope).append("</scope>").append(nl);
        }
        return sb.append(indent).append("</dependency>").append(nl).toString();
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

    /**
     * Fügt {@code block} am Ende des Containers {@code r} ein (wie {@link #insertBefore} vor dessen
     * schließendem Tag). Ist der Container leer geschrieben ({@code <dependencies/>}), wird er zu
     * {@code <dependencies>…</dependencies>} aufgeklappt – sonst entstünde ein zweites Element gleichen
     * Namens, und Maven lehnte die POM ab.
     */
    private static Edit insertInto(String xml, Range r, String block, String closingIndent, String nl, String description,
                                   String marker) {
        if (!r.selfClosing) {
            return insertBefore(xml, r.contentEnd, block, closingIndent, nl, description, marker);
        }
        String text = openTag(xml, r) + nl + block + closingIndent + closeTag(xml, r);
        return new Edit(r.tagStart, r.tagEnd - r.tagStart, text, lineOf(xml, r.tagStart) + 1 + firstContentLine(block, marker),
                description);
    }

    /** Ersetzt den Inhalt von {@code r} durch {@code text}; aus {@code <x/>} wird {@code <x>text</x>}. */
    private static Edit replaceContent(String xml, Range r, String text, String description) {
        if (r.selfClosing) {
            return new Edit(r.tagStart, r.tagEnd - r.tagStart, openTag(xml, r) + text + closeTag(xml, r), lineOf(xml, r.tagStart),
                    description);
        }
        return new Edit(r.contentStart, r.contentEnd - r.contentStart, text, lineOf(xml, r.contentStart), description);
    }

    /** {@code <x a="1"/>} → {@code <x a="1">}. */
    private static String openTag(String xml, Range r) {
        int end = r.tagEnd - 2;
        while (end > r.tagStart && Character.isWhitespace(xml.charAt(end - 1))) {
            end--;
        }
        return xml.substring(r.tagStart, end) + ">";
    }

    /** {@code <p:x a="1"/>} → {@code </p:x>} (mit Präfix, wie geschrieben). */
    private static String closeTag(String xml, Range r) {
        int end = r.tagStart + 1;
        while (end < r.tagEnd && !Character.isWhitespace(xml.charAt(end)) && xml.charAt(end) != '/' && xml.charAt(end) != '>') {
            end++;
        }
        return "</" + xml.substring(r.tagStart + 1, end) + ">";
    }

    /**
     * Steht in {@code container} außer {@code element} nur Leerraum? Nur dann darf der Container beim
     * Entfernen des letzten Eintrags mitgehen – Kommentare (etwa eine auskommentierte alte Dependency)
     * blieben sonst nicht erhalten.
     */
    private static boolean onlyWhitespaceBesides(String xml, Range container, Range element) {
        return xml.substring(container.contentStart, element.tagStart).trim().isEmpty()
                && xml.substring(element.tagEnd, container.contentEnd).trim().isEmpty();
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
        /**
         * Leer geschrieben ({@code <x/>}): {@code contentStart == contentEnd == tagStart}. Einfügen und
         * Ersetzen gehen dann über {@link #insertInto} bzw. {@link #replaceContent}.
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
                    } else if ("project/dependencyManagement".equals(path) && s.dmStart < 0) {
                        s.dmStart = lt;
                    } else if (DEPENDENCY.equals(path) || MANAGED_DEPENDENCY.equals(path)) {
                        entry = new DepEntry(MANAGED_DEPENDENCY.equals(path));
                    } else if (entry != null && ((DEPENDENCY + "/exclusions/exclusion").equals(path)
                            || (MANAGED_DEPENDENCY + "/exclusions/exclusion").equals(path))) {
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
            } else if (isChild(path, "project/parent")) {
                parentFields.put(name, xml.substring(r.contentStart, r.contentEnd).trim());
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
