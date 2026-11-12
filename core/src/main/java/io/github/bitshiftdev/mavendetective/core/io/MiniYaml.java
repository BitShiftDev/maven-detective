package io.github.bitshiftdev.mavendetective.core.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest die YAML-Teilmenge, die der Fehlerkatalog braucht – ohne Fremdbibliothek, damit der Kern
 * im Maven-Classloader nichts kollidieren lässt.
 *
 * <p>Unterstützt: Block-Mappings, Block-Sequenzen (auch {@code - key: value}), Flow-Sequenzen
 * {@code [a, 'b']}, einfache, 'einfach' und "doppelt" gequotete Skalare, Block-Skalare {@code |}
 * und {@code >}, Kommentare. Nicht unterstützt: Anker, Tags, Flow-Mappings, mehrere Dokumente.
 * Alle Skalare werden als {@link String} geliefert.
 *
 * <p>Tipp für Katalog-Autoren: Regex immer in 'einfache Anführungszeichen' setzen – dann bleiben
 * Backslashes unverändert.
 */
public final class MiniYaml {

    private static final class Line {
        final int number;
        int indent;
        String content;
        final String raw;

        Line(int number, int indent, String content, String raw) {
            this.number = number;
            this.indent = indent;
            this.content = content;
            this.raw = raw;
        }
    }

    private final List<Line> lines = new ArrayList<Line>();
    private final String[] rawLines;
    private int index;

    private MiniYaml(String text) {
        rawLines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < rawLines.length; i++) {
            String raw = rawLines[i];
            if (raw.indexOf('\t') >= 0 && raw.trim().length() > 0 && raw.substring(0, indentOf(raw)).indexOf('\t') >= 0) {
                throw new YamlException(i + 1, "Tabulator in der Einrückung – bitte Leerzeichen verwenden");
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---")) {
                continue;
            }
            lines.add(new Line(i + 1, indentOf(raw), trimmed, raw));
        }
    }

    public static Object parse(String text) {
        MiniYaml y = new MiniYaml(text);
        if (y.lines.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Object result = y.parseBlock(y.lines.get(0).indent);
        if (y.index < y.lines.size()) {
            Line l = y.lines.get(y.index);
            throw new YamlException(l.number, "Unerwartete Einrückung");
        }
        return result;
    }

    private static int indentOf(String raw) {
        int i = 0;
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private Object parseBlock(int indent) {
        Line l = lines.get(index);
        if (isSequenceItem(l.content)) {
            return parseSequence(indent);
        }
        return parseMapping(indent);
    }

    private static boolean isSequenceItem(String content) {
        return content.equals("-") || content.startsWith("- ");
    }

    private List<Object> parseSequence(int indent) {
        List<Object> list = new ArrayList<Object>();
        while (index < lines.size()) {
            Line l = lines.get(index);
            if (l.indent < indent) {
                break;
            }
            if (l.indent > indent) {
                throw new YamlException(l.number, "Unerwartete Einrückung in Liste");
            }
            if (!isSequenceItem(l.content)) {
                break;
            }
            String rest = l.content.equals("-") ? "" : l.content.substring(2);
            int restIndent = indent + 2 + (rest.length() - rest.replaceAll("^\\s+", "").length());
            rest = rest.trim();
            if (rest.isEmpty()) {
                index++;
                if (index < lines.size() && lines.get(index).indent > indent) {
                    list.add(parseBlock(lines.get(index).indent));
                } else {
                    list.add(null);
                }
            } else if (startsMappingEntry(rest)) {
                // "- key: value" → Mapping, dessen erste Zeile in dieser Zeile steht
                l.indent = restIndent;
                l.content = rest;
                list.add(parseMapping(restIndent));
            } else {
                index++;
                list.add(scalar(rest, l));
            }
        }
        return list;
    }

    private Map<String, Object> parseMapping(int indent) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        while (index < lines.size()) {
            Line l = lines.get(index);
            if (l.indent < indent) {
                break;
            }
            if (l.indent > indent) {
                throw new YamlException(l.number, "Unerwartete Einrückung");
            }
            if (isSequenceItem(l.content)) {
                break;
            }
            int colon = keyColon(l.content);
            if (colon < 0) {
                throw new YamlException(l.number, "'schlüssel: wert' erwartet, gefunden: " + l.content);
            }
            String key = unquoteKey(l.content.substring(0, colon).trim());
            String rest = l.content.substring(colon + 1).trim();
            if (map.containsKey(key)) {
                throw new YamlException(l.number, "Schlüssel '" + key + "' doppelt");
            }
            index++;
            if (rest.isEmpty() || rest.startsWith("#")) {
                if (index < lines.size()) {
                    Line next = lines.get(index);
                    if (next.indent > indent) {
                        map.put(key, parseBlock(next.indent));
                        continue;
                    }
                    if (next.indent == indent && isSequenceItem(next.content)) {
                        map.put(key, parseSequence(indent));
                        continue;
                    }
                }
                map.put(key, null);
            } else if (rest.equals("|") || rest.equals(">") || rest.equals("|-") || rest.equals(">-")) {
                map.put(key, blockScalar(l, indent, rest));
            } else {
                map.put(key, scalar(rest, l));
            }
        }
        return map;
    }

    /** Position des Doppelpunkts, der Schlüssel und Wert trennt, oder -1. */
    private static int keyColon(String content) {
        char first = content.charAt(0);
        if (first == '"' || first == '\'') {
            int end = content.indexOf(first, 1);
            if (end > 0 && end + 1 < content.length() && content.charAt(end + 1) == ':') {
                return end + 1;
            }
            return -1;
        }
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == ':' && (i + 1 == content.length() || content.charAt(i + 1) == ' ')) {
                return i;
            }
            if (content.charAt(i) == ' ' && i + 1 < content.length() && content.charAt(i + 1) == '#') {
                return -1;
            }
        }
        return -1;
    }

    private static boolean startsMappingEntry(String content) {
        char first = content.charAt(0);
        if (first == '[' || first == '{') {
            return false;
        }
        if (first == '"' || first == '\'') {
            return keyColon(content) > 0;
        }
        int colon = keyColon(content);
        return colon > 0 && content.substring(0, colon).matches("[A-Za-z0-9_.\\-]+");
    }

    private static String unquoteKey(String key) {
        if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private String blockScalar(Line header, int parentIndent, String style) {
        boolean folded = style.startsWith(">");
        boolean strip = style.endsWith("-");
        // Block-Skalare lesen die Rohzeilen, damit Leerzeilen und '#' erhalten bleiben
        int rawIndex = header.number; // header.number ist 1-basiert → nächste Zeile
        int blockIndent = -1;
        List<String> collected = new ArrayList<String>();
        while (rawIndex < rawLines.length) {
            String raw = rawLines[rawIndex];
            if (raw.trim().isEmpty()) {
                collected.add("");
                rawIndex++;
                continue;
            }
            int ind = indentOf(raw);
            if (ind <= parentIndent) {
                break;
            }
            if (blockIndent < 0) {
                blockIndent = ind;
            }
            if (ind < blockIndent) {
                break;
            }
            collected.add(raw.substring(blockIndent));
            rawIndex++;
        }
        while (!collected.isEmpty() && collected.get(collected.size() - 1).isEmpty()) {
            collected.remove(collected.size() - 1);
        }
        // geparste Zeilen überspringen, die zum Block gehören
        while (index < lines.size() && lines.get(index).number <= rawIndex) {
            index++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < collected.size(); i++) {
            String c = collected.get(i);
            if (i > 0) {
                sb.append(folded && !c.isEmpty() && !collected.get(i - 1).isEmpty() ? " " : "\n");
            }
            sb.append(c);
        }
        if (!strip && sb.length() > 0) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private Object scalar(String text, Line l) {
        if (text.startsWith("[")) {
            return flowSequence(text, l);
        }
        if (text.startsWith("{")) {
            throw new YamlException(l.number, "Flow-Mappings {…} werden nicht unterstützt");
        }
        if (text.startsWith("'")) {
            return singleQuoted(text, l);
        }
        if (text.startsWith("\"")) {
            return doubleQuoted(text, l);
        }
        int comment = text.indexOf(" #");
        if (comment >= 0) {
            text = text.substring(0, comment).trim();
        }
        if (text.equals("~") || text.equals("null")) {
            return null;
        }
        return text;
    }

    private static String singleQuoted(String text, Line l) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    sb.append('\'');
                    i += 2;
                    continue;
                }
                checkTrailing(text.substring(i + 1), l);
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        throw new YamlException(l.number, "Fehlendes schließendes '");
    }

    private static String doubleQuoted(String text, Line l) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                checkTrailing(text.substring(i + 1), l);
                return sb.toString();
            }
            if (c == '\\' && i + 1 < text.length()) {
                char e = text.charAt(i + 1);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default:
                        throw new YamlException(l.number,
                                "Escape \\" + e + " in \"…\" – für Regex lieber '…' verwenden");
                }
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        throw new YamlException(l.number, "Fehlendes schließendes \"");
    }

    private static void checkTrailing(String rest, Line l) {
        String t = rest.trim();
        if (!t.isEmpty() && !t.startsWith("#") && !t.equals(",") && !t.startsWith("]")) {
            throw new YamlException(l.number, "Text nach schließendem Anführungszeichen: " + t);
        }
    }

    private List<Object> flowSequence(String text, Line l) {
        boolean inSingle = false;
        boolean inDouble = false;
        int end = -1;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ']' && !inSingle && !inDouble) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new YamlException(l.number, "Fehlendes ]");
        }
        String after = text.substring(end + 1).trim();
        if (!after.isEmpty() && !after.startsWith("#")) {
            throw new YamlException(l.number, "Text nach ]: " + after);
        }
        List<Object> list = new ArrayList<Object>();
        String body = text.substring(1, end).trim();
        if (body.isEmpty()) {
            return list;
        }
        StringBuilder cur = new StringBuilder();
        inSingle = false;
        inDouble = false;
        for (int i = 0; i <= body.length(); i++) {
            char c = i < body.length() ? body.charAt(i) : ',';
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (c == ',' && !inSingle && !inDouble) {
                String item = cur.toString().trim();
                if (item.isEmpty()) {
                    throw new YamlException(l.number, "Leerer Eintrag in [...]");
                }
                list.add(scalar(item, l));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        return list;
    }

    /** Fehler mit Zeilennummer, damit Katalog-Autoren die Stelle direkt finden. */
    public static final class YamlException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int line;

        public YamlException(int line, String message) {
            super("Zeile " + line + ": " + message);
            this.line = line;
        }

        public int getLine() { return line; }
    }
}
