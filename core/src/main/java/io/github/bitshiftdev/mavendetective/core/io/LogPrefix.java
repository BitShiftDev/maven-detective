package io.github.bitshiftdev.mavendetective.core.io;

/**
 * Zerlegt eine Logzeile (ohne ANSI-Farben) in Maven-Level und Text. CI-Systeme und Logger setzen vor
 * {@code [ERROR]} eigene Präfixe: Zeitstempel ({@code 2026-11-12T06:48:01.123Z}, {@code 2026-11-12 06:48:01},
 * {@code [06:48:01]}), Thread-Namen ({@code [main]}), Millisekunden seit Start ({@code 1234}) oder
 * Stream-Kennungen (GitLab {@code 01O}). Vor einem Level werden bis zu {@value #MAX_TOKENS} solcher Teile
 * übersprungen. Ohne Level fällt nur ein Präfix weg, der mit Datum oder Uhrzeit beginnt – sonst wäre
 * „3 errors“ nicht mehr von einem Präfix zu unterscheiden.
 *
 * <p>Jeder Teil ist höchstens {@value #MAX_TOKEN} Zeichen lang; die Zerlegung läuft ohne Regex und
 * bleibt auch bei sehr langen Zeilen linear.
 */
public final class LogPrefix {

    private static final int MAX_TOKENS = 4;
    private static final int MAX_TOKEN = 64;
    private static final String[] LEVELS = {"FATAL", "ERROR", "WARNING", "WARN", "INFO", "DEBUG"};

    private enum Kind { TIME, DATE, NUMBER, STREAM, THREAD }

    private final String level;
    private final String text;

    private LogPrefix(String level, String text) {
        this.level = level;
        this.text = text;
    }

    /** {@code FATAL}, {@code ERROR}, {@code WARNING}, {@code WARN}, {@code INFO}, {@code DEBUG} oder {@code null}. */
    public String getLevel() { return level; }

    /** Text nach Präfix und Level (ein Leerzeichen nach dem Level fällt weg, sonst unverändert). */
    public String getText() { return text; }

    public static LogPrefix parse(String line) {
        int pos = 0;
        for (int i = 0; i <= MAX_TOKENS; i++) {
            String lvl = levelAt(line, pos);
            if (lvl != null) {
                int start = pos + lvl.length() + 2;
                if (start < line.length() && Character.isWhitespace(line.charAt(start))) {
                    start++;
                }
                return new LogPrefix(lvl, line.substring(start));
            }
            if (i == MAX_TOKENS) {
                break;
            }
            int end = tokenEnd(line, pos);
            if (end < 0) {
                break;
            }
            int next = skipSpace(line, end);
            if (next == end) {
                break;
            }
            pos = next;
        }
        return new LogPrefix(null, line.substring(withoutLevelPrefix(line)));
    }

    /** Länge eines Präfixes, das mit Datum oder Uhrzeit beginnt; dahinter nur Uhrzeit, Thread, Stream. */
    private static int withoutLevelPrefix(String line) {
        int pos = 0;
        for (int i = 0; i < MAX_TOKENS; i++) {
            int end = tokenEnd(line, pos);
            if (end < 0) {
                break;
            }
            Kind kind = kind(line, pos, end);
            boolean allowed = i == 0 ? kind == Kind.TIME || kind == Kind.DATE
                    : kind == Kind.TIME || kind == Kind.STREAM || kind == Kind.THREAD;
            int next = skipSpace(line, end);
            if (!allowed || next == end) {
                break;
            }
            pos = next;
        }
        return pos;
    }

    private static String levelAt(String line, int pos) {
        if (pos >= line.length() || line.charAt(pos) != '[') {
            return null;
        }
        for (String l : LEVELS) {
            if (line.startsWith(l, pos + 1) && line.startsWith("]", pos + 1 + l.length())) {
                return l;
            }
        }
        return null;
    }

    /** Ende eines Präfix-Teils ab {@code pos}, oder -1, wenn dort keiner steht. */
    private static int tokenEnd(String line, int pos) {
        int n = line.length();
        if (pos >= n) {
            return -1;
        }
        int limit = Math.min(n, pos + MAX_TOKEN);
        if (line.charAt(pos) == '[') {
            for (int i = pos + 1; i < limit; i++) {
                if (line.charAt(i) == ']') {
                    return i > pos + 1 ? i + 1 : -1;
                }
            }
            return -1;
        }
        int end = pos;
        while (end < n && !Character.isWhitespace(line.charAt(end))) {
            end++;
            if (end - pos > MAX_TOKEN) {
                return -1;
            }
        }
        return kind(line, pos, end) == null ? -1 : end;
    }

    private static Kind kind(String line, int start, int end) {
        if (line.charAt(start) == '[') {
            return hasTime(line, start + 1, end - 1) ? Kind.TIME : Kind.THREAD;
        }
        if (hasTime(line, start, end)) {
            return Kind.TIME;
        }
        int len = end - start;
        boolean digits = true;
        for (int i = start; i < end; i++) {
            if (!isDigit(line.charAt(i))) {
                digits = false;
                break;
            }
        }
        if (digits) {
            return Kind.NUMBER;
        }
        if (len == 10 && isDigits(line, start, 4) && isDigits(line, start + 5, 2) && isDigits(line, start + 8, 2)) {
            char a = line.charAt(start + 4);
            char b = line.charAt(start + 7);
            if (a == b && (a == '-' || a == '/' || a == '.')) {
                return Kind.DATE;
            }
        }
        if ((len == 3 || len == 4) && isDigits(line, start, 2)) {
            char s = line.charAt(start + 2);
            if ((s == 'O' || s == 'E') && (len == 3 || line.charAt(start + 3) == '+')) {
                return Kind.STREAM;
            }
        }
        return null;
    }

    /** Enthält {@code hh:mm:ss}. */
    private static boolean hasTime(String line, int start, int end) {
        for (int i = start; i + 8 <= end; i++) {
            if (isDigits(line, i, 2) && line.charAt(i + 2) == ':' && isDigits(line, i + 3, 2)
                    && line.charAt(i + 5) == ':' && isDigits(line, i + 6, 2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDigits(String s, int start, int count) {
        for (int i = start; i < start + count; i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int skipSpace(String line, int pos) {
        while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) {
            pos++;
        }
        return pos;
    }
}
