package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.Texts;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.DepEntry;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.ExclusionEntry;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.Range;
import io.github.bitshiftdev.mavendetective.core.diagnosis.FixAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.bitshiftdev.mavendetective.core.deps.PomText.element;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.insertBefore;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.insertInto;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.lineIndent;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.lineOf;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.newline;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.onlyIndentBefore;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.onlyWhitespaceBesides;
import static io.github.bitshiftdev.mavendetective.core.deps.PomText.replaceContent;

/**
 * Textgenaue Änderungen an einer POM: Formatierung, Kommentare und Reihenfolge bleiben stehen, es
 * wird genau eine Stelle ersetzt oder eingefügt. Ergebnis ist ein {@link Edit}, das die IDE als
 * einen Undo-Schritt auf das Dokument anwendet und der {@code FixExecutor} auf die Datei.
 *
 * <p>Bewusst kein DOM-Roundtrip: der würde Einrückung, Kommentare und Zeilenenden verändern. Den
 * Tag-Scan macht {@link PomScan}, die Textbausteine {@link PomText}; Werte werden beim Lesen
 * dekodiert (Entities, CDATA) und beim Schreiben escaped.
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

    /** Gültiger Elementname für eine Property (ohne Namensraum). */
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]*");

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
        PomScan s = scan(xml);
        String nl = newline(xml);
        String unit = s.indentUnit(xml);

        for (DepEntry e : s.managed) {
            if (!s.is(e.fields, groupId, artifactId)) {
                continue;
            }
            String classifier = e.fields.get("classifier");
            if (!"jar".equals(e.type()) || classifier != null && !classifier.isEmpty()) {
                continue;
            }
            Range v = e.ranges.get("version");
            if (v == null) {
                Range a = e.ranges.get("artifactId");
                String text = nl + lineIndent(xml, a.tagStart) + element("version", version);
                return new Edit(a.tagEnd, 0, text, lineOf(xml, a.tagEnd) + 1, Texts.t("edit.managed.version.added", version));
            }
            String current = e.fields.get("version");
            if (current.startsWith("${") && current.endsWith("}") && current.indexOf('}') == current.length() - 1) {
                String prop = current.substring(2, current.length() - 1);
                Range p = s.properties.get(prop);
                if (p != null) {
                    return replaceContent(xml, p, version,
                            Texts.t("edit.property.changed", "${" + prop + "}", s.propertyValues.get(prop), version));
                }
            }
            return replaceContent(xml, v, version, Texts.t("edit.managed.version.changed", current, version));
        }

        String coords = groupId + ":" + artifactId + ":" + version;
        if (s.dmDependencies != null) {
            String closingIndent = lineIndent(xml, s.dmDependencies.contentEnd);
            String block = PomText.dependencyBlock(groupId, artifactId, version, null, null, null, closingIndent + unit, unit, nl);
            return insertInto(xml, s.dmDependencies, block, closingIndent, nl, Texts.t("edit.managed.added", coords), "<dependency>");
        }
        if (s.dm != null) {
            String closingIndent = lineIndent(xml, s.dm.contentEnd);
            String inner = closingIndent + unit;
            String block = inner + "<dependencies>" + nl
                    + PomText.dependencyBlock(groupId, artifactId, version, null, null, null, inner + unit, unit, nl)
                    + inner + "</dependencies>" + nl;
            return insertInto(xml, s.dm, block, closingIndent, nl, Texts.t("edit.managed.added", coords), "<dependency>");
        }
        int at = s.dependenciesStart >= 0 ? s.dependenciesStart : s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = blockIndent(xml, s, at, unit);
        String block = indent + "<dependencyManagement>" + nl
                + indent + unit + "<dependencies>" + nl
                + PomText.dependencyBlock(groupId, artifactId, version, null, null, null, indent + unit + unit, unit, nl)
                + indent + unit + "</dependencies>" + nl
                + indent + "</dependencyManagement>" + nl;
        return insertSection(xml, s, at, indent, block, nl, Texts.t("edit.managed.created", coords), "<dependency>");
    }

    /**
     * Entfernt {@code <exclusion>} {@code exclusion} (groupId:artifactId) bei der Dependency
     * {@code groupId:artifactId} unter {@code project/dependencies} bzw. – mit {@code managed} –
     * unter {@code project/dependencyManagement/dependencies}. Ist es die letzte Exclusion, geht
     * {@code <exclusions>} mit (nur wenn darin sonst nichts als Leerraum steht). Ganze Zeilen werden
     * entfernt, wenn das Element allein darauf steht.
     *
     * @throws IllegalArgumentException wenn Dependency oder Exclusion nicht zu finden sind
     */
    public static Edit removeExclusion(String xml, String groupId, String artifactId, String exclusion, boolean managed) {
        PomScan s = scan(xml);
        String[] ex = splitExclusion(exclusion);
        String where = managed ? "dependencyManagement" : "dependencies";
        DepEntry found = null;
        ExclusionEntry target = null;
        for (DepEntry d : s.deps) {
            if (d.managed != managed || !s.is(d.fields, groupId, artifactId)) {
                continue;
            }
            for (ExclusionEntry e : d.exclusions) {
                if (target == null && s.is(e.fields, ex[0], ex[1])) {
                    found = d; // bei doppelter Angabe die erste – die zweite wirkt danach weiter
                    target = e;
                }
            }
        }
        if (target == null) {
            throw new IllegalArgumentException(Texts.t("edit.exclusion.not.found", exclusion, groupId + ":" + artifactId, where));
        }
        boolean whole = found.exclusions.size() == 1 && found.exclusionsRange != null
                && onlyWhitespaceBesides(xml, found.exclusionsRange, target.range);
        return PomText.remove(xml, whole ? found.exclusionsRange : target.range,
                Texts.t(whole ? "edit.exclusions.removed" : "edit.exclusion.removed", exclusion, groupId + ":" + artifactId));
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
        return addExclusion(xml, groupId, artifactId, exclusion, null, null);
    }

    /**
     * Wie {@link #addExclusion(String, String, String, String)}, aber für den Eintrag mit genau
     * diesem {@code classifier}/{@code type} ({@code null} = bevorzugt ohne Classifier, Typ jar).
     */
    public static Edit addExclusion(String xml, String groupId, String artifactId, String exclusion, String classifier,
                                    String type) {
        PomScan s = scan(xml);
        String[] ex = splitExclusion(exclusion);
        DepEntry found = find(s, groupId, artifactId, classifier, type);
        for (ExclusionEntry e : found.exclusions) {
            if (s.is(e.fields, ex[0], ex[1])) {
                throw new IllegalArgumentException(Texts.t("edit.exclusion.exists", exclusion, groupId + ":" + artifactId));
            }
        }
        String nl = newline(xml);
        String unit = s.indentUnit(xml);
        String description = Texts.t("edit.exclusion.added", exclusion, groupId + ":" + artifactId);
        Range child = found.ranges.get("artifactId");
        String childIndent = child != null && onlyIndentBefore(xml, child.tagStart)
                ? lineIndent(xml, child.tagStart) : lineIndent(xml, found.range.tagStart) + unit;
        if (found.exclusionsRange != null) {
            int at = found.exclusionsRange.contentEnd;
            String closingIndent = onlyIndentBefore(xml, at) ? lineIndent(xml, at) : childIndent;
            String block = PomText.exclusionBlock(ex[0], ex[1], closingIndent + unit, unit, nl);
            return insertInto(xml, found.exclusionsRange, block, closingIndent, nl, description, "<exclusion>");
        }
        String block = childIndent + "<exclusions>" + nl
                + PomText.exclusionBlock(ex[0], ex[1], childIndent + unit, unit, nl)
                + childIndent + "</exclusions>" + nl;
        return insertBefore(xml, found.range.contentEnd, block, lineIndent(xml, found.range.tagStart), nl, description, "<exclusion>");
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
        PomScan s = scan(xml);
        String wantedType = type == null || type.isEmpty() ? "jar" : type;
        for (DepEntry d : s.deps) {
            if (!d.managed && s.is(d.fields, groupId, artifactId)
                    && same(classifier, d.fields.get("classifier")) && d.type().equals(wantedType)) {
                throw new IllegalArgumentException(Texts.t("edit.dependency.exists", groupId + ":" + artifactId));
            }
        }
        String nl = newline(xml);
        String unit = s.indentUnit(xml);
        String coords = groupId + ":" + artifactId + (version == null ? "" : ":" + version)
                + (scope == null || "compile".equals(scope) ? "" : " (" + scope + ")");
        if (s.dependencies != null) {
            String closingIndent = lineIndent(xml, s.dependencies.tagStart);
            String block = PomText.dependencyBlock(groupId, artifactId, version, scope, type, classifier, closingIndent + unit, unit, nl);
            return insertInto(xml, s.dependencies, block, closingIndent, nl, Texts.t("edit.dependency.added", coords), "<dependency>");
        }
        int at = s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = blockIndent(xml, s, at, unit);
        String block = indent + "<dependencies>" + nl
                + PomText.dependencyBlock(groupId, artifactId, version, scope, type, classifier, indent + unit, unit, nl)
                + indent + "</dependencies>" + nl;
        return insertSection(xml, s, at, indent, block, nl, Texts.t("edit.dependencies.created", coords), "<dependency>");
    }

    /**
     * Entfernt die Dependency {@code groupId:artifactId} (mit {@code classifier}, sonst die erste
     * ohne) aus {@code project/dependencies}. War es die einzige, geht {@code <dependencies>} mit.
     *
     * @throws IllegalArgumentException wenn sie dort nicht steht
     */
    public static Edit removeDependency(String xml, String groupId, String artifactId, String classifier) {
        return removeDependency(xml, groupId, artifactId, classifier, null);
    }

    /**
     * Wie {@link #removeDependency(String, String, String, String)}, nur für den Eintrag vom Typ
     * {@code type} ({@code null} = bevorzugt {@code jar}).
     */
    public static Edit removeDependency(String xml, String groupId, String artifactId, String classifier, String type) {
        PomScan s = scan(xml);
        DepEntry found = find(s, groupId, artifactId, classifier, type);
        int count = 0;
        for (DepEntry d : s.deps) {
            if (!d.managed) {
                count++;
            }
        }
        boolean whole = count == 1 && s.dependencies != null && onlyWhitespaceBesides(xml, s.dependencies, found.range);
        return PomText.remove(xml, whole ? s.dependencies : found.range,
                Texts.t(whole ? "edit.dependency.removed.block" : "edit.dependency.removed", groupId + ":" + artifactId));
    }

    /**
     * Setzt den Scope der Dependency {@code groupId:artifactId} unter {@code project/dependencies}:
     * vorhandenes {@code <scope>} ändern, sonst nach {@code <version>} (bzw. artifactId, type,
     * classifier) einfügen.
     *
     * @throws IllegalArgumentException wenn sie fehlt oder den Scope schon hat
     */
    public static Edit setScope(String xml, String groupId, String artifactId, String scope, String classifier) {
        return setScope(xml, groupId, artifactId, scope, classifier, null);
    }

    /** Wie {@link #setScope(String, String, String, String, String)} für den Eintrag vom Typ {@code type} ({@code null} = bevorzugt jar). */
    public static Edit setScope(String xml, String groupId, String artifactId, String scope, String classifier, String type) {
        PomScan s = scan(xml);
        DepEntry found = find(s, groupId, artifactId, classifier, type);
        String ga = groupId + ":" + artifactId;
        Range current = found.ranges.get("scope");
        if (current != null) {
            String old = found.fields.get("scope");
            if (scope.equals(old)) {
                throw new IllegalArgumentException(Texts.t("edit.scope.exists", ga, scope));
            }
            return replaceContent(xml, current, scope, Texts.t("edit.scope.changed", ga, old, scope));
        }
        Range after = null;
        for (String child : new String[] {"artifactId", "version", "type", "classifier"}) {
            Range r = found.ranges.get(child);
            if (r != null && (after == null || r.tagEnd > after.tagEnd)) {
                after = r;
            }
        }
        if (after == null) {
            throw new IllegalArgumentException(Texts.t("edit.no.artifact.id", ga));
        }
        String description = Texts.t("edit.scope.set", ga, scope);
        if (!onlyIndentBefore(xml, after.tagStart)) {
            return new Edit(after.tagEnd, 0, element("scope", scope), lineOf(xml, after.tagEnd), description);
        }
        String text = newline(xml) + lineIndent(xml, after.tagStart) + element("scope", scope);
        return new Edit(after.tagEnd, 0, text, lineOf(xml, after.tagEnd) + 1, description);
    }

    /**
     * Setzt eine Version an genau der Stelle, die sie heute festlegt (Modul A6):
     * {@code section} = {@code parent} ändert {@code <parent><version>} (groupId/artifactId müssen
     * passen), {@code dependencies} die Version der Dependency unter {@code project/dependencies},
     * {@code dependencyManagement} die des verwalteten Eintrags (auch BOM-Import mit
     * {@code type=pom}). Ersetzt wird der Text wörtlich – steht dort {@code ${prop}}, ist das
     * {@link #setProperty} zu nehmen. Kommentare neben dem Wert bleiben stehen.
     *
     * @throws IllegalArgumentException wenn die Stelle fehlt oder keine {@code <version>} hat
     */
    public static Edit setVersion(String xml, String section, String groupId, String artifactId, String version) {
        PomScan s = scan(xml);
        Range v;
        String current;
        String what;
        if ("parent".equals(section)) {
            if (!groupId.equals(s.parentFields.get("groupId")) || !artifactId.equals(s.parentFields.get("artifactId"))) {
                throw new IllegalArgumentException(Texts.t("edit.parent.mismatch", groupId + ":" + artifactId));
            }
            v = s.parentRanges.get("version");
            current = s.parentFields.get("version");
            what = "Parent " + artifactId;
        } else {
            boolean managed = "dependencyManagement".equals(section);
            DepEntry found = pick(s, managed ? s.managed : s.deps, managed, groupId, artifactId, null, null, true);
            if (found == null) {
                throw new IllegalArgumentException(Texts.t("edit.version.not.found", groupId + ":" + artifactId, section));
            }
            v = found.ranges.get("version");
            current = found.fields.get("version");
            what = Texts.t(managed ? "edit.what.managed" : "edit.what.dependency", artifactId);
        }
        if (v == null) {
            throw new IllegalArgumentException(Texts.t("edit.no.version", groupId + ":" + artifactId));
        }
        return replaceContent(xml, v, version, what + ": " + Texts.t("edit.version.changed", current, version));
    }

    /**
     * Setzt die Property {@code name} unter {@code project/properties} auf {@code value}; fehlt sie,
     * kommt sie dazu (bei Bedarf samt {@code <properties>}). So überschreibt ein Projekt z. B. eine
     * Version, die ein Parent wie {@code spring-boot-starter-parent} über eine Property verwaltet.
     *
     * @throws IllegalArgumentException wenn {@code name} kein gültiger Elementname ist
     */
    public static Edit setProperty(String xml, String name, String value) {
        PomScan s = scan(xml);
        Range p = s.properties.get(name);
        if (p != null) {
            return replaceContent(xml, p, value, Texts.t("edit.property.changed", "${" + name + "}", s.propertyValues.get(name), value));
        }
        if (!PROPERTY_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(Texts.t("edit.property.name", name));
        }
        String nl = newline(xml);
        String unit = s.indentUnit(xml);
        String entry = element(name, value);
        String marker = "<" + name + ">";
        if (s.propertiesRange != null) {
            String closingIndent = lineIndent(xml, s.propertiesRange.tagStart);
            return insertInto(xml, s.propertiesRange, closingIndent + unit + entry + nl, closingIndent, nl,
                    Texts.t("edit.property.added", "${" + name + "} = " + value), marker);
        }
        int at = s.dmStart >= 0 ? s.dmStart : s.dependenciesStart >= 0 ? s.dependenciesStart : s.buildStart >= 0 ? s.buildStart : s.projectEnd;
        String indent = blockIndent(xml, s, at, unit);
        String block = indent + "<properties>" + nl + indent + unit + entry + nl + indent + "</properties>" + nl;
        return insertSection(xml, s, at, indent, block, nl, Texts.t("edit.properties.created", "${" + name + "} = " + value), marker);
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
            return addExclusion(xml, g, a, required(fix, "exclusion"), fix.param("classifier"), fix.param("type"));
        }
        if (FixAction.ADD_DEPENDENCY.equals(type)) {
            return addDependency(xml, g, a, fix.param("version"), fix.param("scope"), fix.param("type"), fix.param("classifier"));
        }
        if (FixAction.REMOVE_DEPENDENCY.equals(type)) {
            return removeDependency(xml, g, a, fix.param("classifier"), fix.param("type"));
        }
        if (FixAction.SET_SCOPE.equals(type)) {
            return setScope(xml, g, a, required(fix, "scope"), fix.param("classifier"), fix.param("type"));
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
    private static final Set<String> URL_OWNERS = new HashSet<String>(Arrays.asList(
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
        String shown = stripSlash(replace.trim());
        String to = PomText.escape(shown);
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
            int skipped = PomScan.skipSpecial(xml, lt);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            int gt = PomScan.tagEnd(xml, lt);
            if (gt < 0) {
                throw new IllegalArgumentException("Unvollständiges Tag ab Zeichen " + lt);
            }
            boolean closing = xml.charAt(lt + 1) == '/';
            String name = PomScan.tagName(xml, closing ? lt + 2 : lt + 1, gt);
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
        return new Edit(start, end - start, text.toString(), lineOf(xml, start), Texts.t("edit.url.changed", from, shown, hits.size()));
    }

    // --- Hilfen ----------------------------------------------------------------------------------

    private static PomScan scan(String xml) {
        PomScan s = PomScan.of(xml);
        if (s.projectEnd < 0) {
            throw new IllegalArgumentException(Texts.t("edit.no.project"));
        }
        return s;
    }

    /** Einrückung eines neuen Abschnitts vor dem Tag bei {@code at} (am Projektende eine Stufe tiefer). */
    private static String blockIndent(String xml, PomScan s, int at, String unit) {
        return at == s.projectEnd ? lineIndent(xml, at) + unit : lineIndent(xml, at);
    }

    /**
     * Fügt einen neuen Abschnitt (etwa {@code <dependencyManagement>}) vor dem Tag bei {@code at} ein;
     * vor einem folgenden Abschnitt mit Leerzeile dazwischen.
     */
    private static Edit insertSection(String xml, PomScan s, int at, String indent, String block, String nl, String description,
                                      String marker) {
        boolean atEnd = at == s.projectEnd;
        return insertBefore(xml, at, atEnd ? block : block + nl, atEnd ? lineIndent(xml, at) : indent, nl, description, marker);
    }

    private static String[] splitExclusion(String exclusion) {
        int colon = exclusion.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(Texts.t("edit.exclusion.format", exclusion));
        }
        return new String[] {exclusion.substring(0, colon), exclusion.substring(colon + 1)};
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
    private static DepEntry find(PomScan s, String groupId, String artifactId, String classifier, String type) {
        DepEntry found = pick(s, s.deps, false, groupId, artifactId, classifier, type, false);
        if (found == null || found.range == null) {
            throw new IllegalArgumentException(Texts.t("edit.dependency.not.found", groupId + ":" + artifactId));
        }
        return found;
    }

    /**
     * Der passende Eintrag unter {@code dependencies} bzw. {@code dependencyManagement}: mit
     * {@code classifier}/{@code type} genau dieser (Typ ohne {@code <type>} = {@code jar}); fehlen
     * sie, bevorzugt ohne Classifier und vom Typ {@code jar} – sonst würde bei {@code jar} +
     * {@code test-jar} der falsche Eintrag getroffen. {@code needsVersion}: nur Einträge mit {@code <version>}.
     */
    private static DepEntry pick(PomScan s, List<DepEntry> entries, boolean managed, String groupId, String artifactId,
                                 String classifier, String type, boolean needsVersion) {
        boolean wantClassifier = classifier != null && !classifier.isEmpty();
        boolean wantType = type != null && !type.isEmpty();
        DepEntry found = null;
        int foundRank = Integer.MAX_VALUE;
        for (DepEntry d : entries) {
            if (d.managed != managed || !s.is(d.fields, groupId, artifactId)
                    || needsVersion && d.ranges.get("version") == null) {
                continue;
            }
            String c = d.fields.get("classifier");
            boolean noClassifier = c == null || c.isEmpty();
            if (wantClassifier && !classifier.equals(c)) {
                continue;
            }
            String t = d.type();
            if (wantType && !type.equals(t)) {
                continue;
            }
            int rank = (wantClassifier || noClassifier ? 0 : 2) + (wantType || "jar".equals(t) ? 0 : 1);
            if (rank < foundRank) {
                found = d;
                foundRank = rank;
            }
        }
        return found;
    }

    private static boolean same(String classifier, String other) {
        String a = classifier == null ? "" : classifier;
        String b = other == null ? "" : other;
        return a.equals(b);
    }
}
