package io.github.bitshiftdev.mavendetective.core.io;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler JSON-Leser und -Schreiber für die Event-Datei. Abbildung:
 * Objekt → {@link LinkedHashMap}, Array → {@link ArrayList}, Zahl → {@link Long}/{@link Double}
 * (ganze Zahlen über {@code Long.MAX_VALUE} als {@link BigInteger}), sonst {@link String}, {@link Boolean}
 * oder {@code null}. Tiefer als {@link #MAX_DEPTH} verschachtelte Werte lehnt der Leser ab, statt mit einem
 * {@link StackOverflowError} abzubrechen.
 */
public final class Json {

    /** Größte Verschachtelungstiefe von Objekten und Arrays. */
    public static final int MAX_DEPTH = 256;

    private final String s;
    private int pos;
    private int depth;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw p.error("Unerwartete Zeichen nach Ende des Werts");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON-Objekt erwartet");
        }
        return (Map<String, Object>) value;
    }

    // ---------------------------------------------------------------- lesen

    private Object readValue() {
        if (pos >= s.length()) {
            throw error("Unerwartetes Ende");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
            case '[':
                if (++depth > MAX_DEPTH) {
                    throw error("Zu tief verschachtelt");
                }
                Object nested = c == '{' ? readObject() : readArray();
                depth--;
                return nested;
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unerwartetes Zeichen '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++;
        skipWs();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (!peek('"')) {
                throw error("Schlüssel erwartet");
            }
            String key = readString();
            skipWs();
            consume(':');
            skipWs();
            map.put(key, readValue());
            skipWs();
            if (peek(',')) {
                pos++;
                continue;
            }
            consume('}');
            return map;
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<Object>();
        pos++;
        skipWs();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            if (peek(',')) {
                pos++;
                continue;
            }
            consume(']');
            return list;
        }
    }

    private String readString() {
        consume('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("Unbeendeter String");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("Unbeendete Escape-Sequenz");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("Unvollständiges \\u-Escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("Unbekanntes Escape \\" + e);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        boolean decimal = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                decimal = true;
                pos++;
            } else {
                break;
            }
        }
        String n = s.substring(start, pos);
        if (decimal) {
            return Double.valueOf(n);
        }
        // mehr als 18 Ziffern können über Long.MAX_VALUE liegen
        return n.length() < 19 ? (Object) Long.valueOf(n) : parseInteger(n);
    }

    private Object parseInteger(String n) {
        BigInteger big = new BigInteger(n);
        return big.bitLength() < 64 ? (Object) Long.valueOf(big.longValue()) : big;
    }

    private void expect(String word) {
        if (!s.startsWith(word, pos)) {
            throw error("'" + word + "' erwartet");
        }
        pos += word.length();
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private void consume(char c) {
        if (!peek(c)) {
            throw error("'" + c + "' erwartet");
        }
        pos++;
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " (Position " + pos + ")");
    }

    // ---------------------------------------------------------------- schreiben

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
