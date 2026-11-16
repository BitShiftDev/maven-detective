package io.github.bitshiftdev.mavendetective.core.deps;

import io.github.bitshiftdev.mavendetective.core.deps.PomEditor.Edit;
import io.github.bitshiftdev.mavendetective.core.deps.PomScan.Range;

/**
 * Textbausteine für {@link PomEditor}: Zeilen und Einrückung lesen, Blöcke im Stil der Datei bauen
 * und als {@link Edit} einfügen, ersetzen oder entfernen. Alles, was als Wert in die POM geht, wird
 * hier XML-escaped.
 */
final class PomText {

    private PomText() {
    }

    /** Zeilenumbruch der Datei. */
    static String newline(String xml) {
        return xml.contains("\r\n") ? "\r\n" : "\n";
    }

    /** {@code &}, {@code <} und {@code >} als Entities – sonst wäre {@code a&b<c} als Wert ungültiges XML. */
    static String escape(String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('&') < 0 && value.indexOf('<') < 0 && value.indexOf('>') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code <name>wert</name>} mit escaptem Wert. */
    static String element(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    // --- Blöcke ----------------------------------------------------------------------------------

    static String dependencyBlock(String g, String a, String v, String scope, String type, String classifier, String indent,
                                  String unit, String nl) {
        StringBuilder sb = new StringBuilder();
        String inner = indent + unit;
        sb.append(indent).append("<dependency>").append(nl);
        sb.append(inner).append(element("groupId", g)).append(nl);
        sb.append(inner).append(element("artifactId", a)).append(nl);
        if (v != null && !v.isEmpty()) {
            sb.append(inner).append(element("version", v)).append(nl);
        }
        if (type != null && !type.isEmpty() && !"jar".equals(type)) {
            sb.append(inner).append(element("type", type)).append(nl);
        }
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(inner).append(element("classifier", classifier)).append(nl);
        }
        if (scope != null && !scope.isEmpty() && !"compile".equals(scope)) {
            sb.append(inner).append(element("scope", scope)).append(nl);
        }
        return sb.append(indent).append("</dependency>").append(nl).toString();
    }

    static String exclusionBlock(String g, String a, String indent, String unit, String nl) {
        return indent + "<exclusion>" + nl
                + indent + unit + element("groupId", g) + nl
                + indent + unit + element("artifactId", a) + nl
                + indent + "</exclusion>" + nl;
    }

    // --- Edits -----------------------------------------------------------------------------------

    /**
     * Fügt {@code block} (fertig eingerückt, endet mit Zeilenumbruch) vor dem Tag bei {@code at} ein.
     * Steht vor dem Tag nur Einrückung, beginnt der Block am Zeilenanfang; sonst bekommt er eine
     * eigene Zeile und das Tag danach die Einrückung {@code followIndent}. Die Sprungzeile ist die
     * mit {@code marker}.
     */
    static Edit insertBefore(String xml, int at, String block, String followIndent, String nl, String description, String marker) {
        int lineStart = lineStart(xml, at);
        if (onlyIndentBefore(xml, at)) {
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
    static Edit insertInto(String xml, Range r, String block, String closingIndent, String nl, String description, String marker) {
        if (!r.selfClosing) {
            return insertBefore(xml, r.contentEnd, block, closingIndent, nl, description, marker);
        }
        String text = openTag(xml, r) + nl + block + closingIndent + closeTag(xml, r);
        return new Edit(r.tagStart, r.tagEnd - r.tagStart, text, lineOf(xml, r.tagStart) + 1 + firstContentLine(block, marker),
                description);
    }

    /**
     * Ersetzt den Wert von {@code r} durch {@code value} (wird escaped). Kommentare am Rand des Werts
     * bleiben stehen; aus {@code <x/>} wird {@code <x>wert</x>}.
     */
    static Edit replaceContent(String xml, Range r, String value, String description) {
        String text = escape(value);
        if (r.selfClosing) {
            return new Edit(r.tagStart, r.tagEnd - r.tagStart, openTag(xml, r) + text + closeTag(xml, r), lineOf(xml, r.tagStart),
                    description);
        }
        if (xml.substring(r.contentStart, r.contentEnd).contains("<!--")) {
            int[] span = PomScan.valueSpan(xml, r);
            return new Edit(span[0], span[1] - span[0], text, lineOf(xml, span[0]), description);
        }
        return new Edit(r.contentStart, r.contentEnd - r.contentStart, text, lineOf(xml, r.contentStart), description);
    }

    /** Entfernt das Element {@code r}; steht es allein auf seinen Zeilen, gehen die ganzen Zeilen mit. */
    static Edit remove(String xml, Range r, String description) {
        int start = r.tagStart;
        int end = r.tagEnd;
        int lineEnd = lineEnd(xml, end);
        if (onlyIndentBefore(xml, start) && xml.substring(end, lineEnd).trim().isEmpty()) {
            start = lineStart(xml, start);
            end = lineEnd < xml.length() ? lineEnd + 1 : lineEnd;
        }
        return new Edit(start, end - start, "", lineOf(xml, start), description);
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
    static boolean onlyWhitespaceBesides(String xml, Range container, Range element) {
        return xml.substring(container.contentStart, element.tagStart).trim().isEmpty()
                && xml.substring(element.tagEnd, container.contentEnd).trim().isEmpty();
    }

    // --- Zeilen ----------------------------------------------------------------------------------

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

    /** Zeile (ab 1) von {@code offset}. */
    static int lineOf(String xml, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < xml.length(); i++) {
            if (xml.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static int lineStart(String xml, int offset) {
        int i = offset;
        while (i > 0 && xml.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** Position des Zeilenumbruchs nach {@code offset} (bzw. Textende). */
    static int lineEnd(String xml, int offset) {
        int i = xml.indexOf('\n', offset);
        return i < 0 ? xml.length() : i;
    }

    /** Steht vor {@code offset} in seiner Zeile nur Einrückung? */
    static boolean onlyIndentBefore(String xml, int offset) {
        return xml.substring(lineStart(xml, offset), offset).trim().isEmpty();
    }

    static String lineIndent(String xml, int offset) {
        int start = lineStart(xml, offset);
        int end = start;
        while (end < xml.length() && (xml.charAt(end) == ' ' || xml.charAt(end) == '\t')) {
            end++;
        }
        return xml.substring(start, end);
    }
}
